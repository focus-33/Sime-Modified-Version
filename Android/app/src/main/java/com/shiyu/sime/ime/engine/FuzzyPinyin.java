package com.shiyu.sime.ime.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fuzzy pinyin: generates alternative spellings of the typed pinyin
 * (e.g. "shang" also tries "sang" / "shan") so candidates for the
 * confusable spelling are decoded as if typed correctly.
 */
public final class FuzzyPinyin {
    public static final int Z_ZH = 1;
    public static final int C_CH = 2;
    public static final int S_SH = 4;
    /** xue <-> xie. */
    public static final int XUE_XIE = 8;
    public static final int EN_ENG = 16;
    public static final int IN_ING = 32;
    /** Finals un <-> ong, e.g. lun <-> long, dun <-> dong, yun <-> yong. */
    public static final int UN_ONG = 64;
    /** Finals iong <-> ong: a typed xong / qong / jong also tries xiong / qiong / jiong. */
    public static final int IONG_ONG = 128;
    public static final int DEFAULT_MASK = Z_ZH | C_CH | S_SH | XUE_XIE | EN_ENG | IN_ING;
    private static final int MAX_VARIANTS = 6;

    private static final Set<String> SYLLABLES = new HashSet<>(Arrays.asList((
            "a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu ca cai can " +
            "cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chuai " +
            "chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo da dai dan dang dao de dei den " +
            "deng di dian diao die ding diu dong dou du duan dui dun duo e ei en eng er fa fan fang fei " +
            "fen feng fo fou fu ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui " +
            "gun guo ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo ji " +
            "jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun ka kai kan kang kao ke kei ken " +
            "keng kong kou ku kua kuai kuan kuang kui kun kuo la lai lan lang lao le lei leng li lia " +
            "lian liang liao lie lin ling liu long lou lu luan lue lun luo lv ma mai man mang mao me " +
            "mei men meng mi mian miao mie min ming miu mo mou mu na nai nan nang nao ne nei nen neng " +
            "ni nian niang niao nie nin ning niu nong nou nu nuan nue nuo nv o ou pa pai pan pang pao " +
            "pei pen peng pi pian piao pie pin ping po pou pu qi qia qian qiang qiao qie qin qing qiong " +
            "qiu qu quan que qun ran rang rao re ren reng ri rong rou ru ruan rui run ruo sa sai san " +
            "sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai " +
            "shuan shuang shui shun shuo si song sou su suan sui sun suo ta tai tan tang tao te teng ti " +
            "tian tiao tie ting tong tou tu tuan tui tun tuo wa wai wan wang wei wen weng wo wu xi xia " +
            "xian xiang xiao xie xin xing xiong xiu xu xuan xue xun ya yan yang yao ye yi yin ying yo " +
            "yong you yu yuan yue yun za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe " +
            "zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu " +
            "zuan zui zun zuo "
            ).trim().split(" ")));
    private static final Set<String> INITIALS = new HashSet<>(Arrays.asList(
            "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x",
            "zh", "ch", "sh", "r", "z", "c", "s", "y", "w"));

    /**
     * Spellings people type for j/q/x + iong when they drop the i. They are
     * not real syllables, so they are only recognised when the iong/ong
     * rule is on, and they only ever map to the real syllable.
     */
    private static final Set<String> PSEUDO = new HashSet<>(Arrays.asList(
            "jong", "qong", "xong"));

    private FuzzyPinyin() {}

    public static boolean isSyllable(String s) { return SYLLABLES.contains(s); }

    /** One spelling alternative plus the data to map results back. */
    public static final class Variant {
        public final String input;
        private final String[] origTokens;
        private final int[] varTokenEnd, varSepEnd, origTokenEnd, origSepEnd;

        Variant(String input, String[] origTokens, int[] varTokenEnd,
                int[] varSepEnd, int[] origTokenEnd, int[] origSepEnd) {
            this.input = input;
            this.origTokens = origTokens;
            this.varTokenEnd = varTokenEnd;
            this.varSepEnd = varSepEnd;
            this.origTokenEnd = origTokenEnd;
            this.origSepEnd = origSepEnd;
        }

        /** Original-input offset for a consumed length of the variant, or -1. */
        public int mapConsumed(int c) {
            for (int j = 0; j < varTokenEnd.length; j++) {
                if (c == varTokenEnd[j]) return origTokenEnd[j];
                if (c == varSepEnd[j]) return origSepEnd[j];
            }
            return -1;
        }

        /** Original spelling (apostrophe separated) of the first k tokens. */
        public String originalUnits(int origOffset) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < origTokens.length; j++) {
                if (origTokenEnd[j] > origOffset) break;
                if (sb.length() > 0) sb.append('\'');
                sb.append(origTokens[j]);
            }
            return sb.toString();
        }
    }

    /** Split pinyin into syllable-like tokens; seps[i] follows tokens[i]. */
    static void tokenize(String in, List<String> tokens, List<String> seps, int mask) {
        boolean pseudo = (mask & IONG_ONG) != 0;
        int i = 0, n = in.length();
        while (i < n) {
            if (in.charAt(i) == '\'') { i++; continue; }
            String tok = null;
            for (int len = Math.min(6, n - i); len >= 1 && tok == null; len--) {
                String t = in.substring(i, i + len);
                if (t.indexOf('\'') >= 0) continue;
                if (SYLLABLES.contains(t) || INITIALS.contains(t)
                        || (pseudo && PSEUDO.contains(t))) tok = t;
            }
            if (tok == null) tok = in.substring(i, i + 1);
            i += tok.length();
            int s = i;
            while (i < n && in.charAt(i) == '\'') i++;
            tokens.add(tok);
            seps.add(in.substring(s, i));
        }
    }

    private static boolean valid(String t) {
        return SYLLABLES.contains(t) || INITIALS.contains(t);
    }

    /** Alternative spellings of a single token under the enabled rules. */
    static List<String> alternatives(String tok, int mask) {
        Set<String> heads = new LinkedHashSet<>();
        heads.add(tok);
        String h = initialSwap(tok, mask);
        if (h != null) heads.add(h);
        Set<String> out = new LinkedHashSet<>();
        for (String t : heads) {
            out.add(t);
            out.addAll(finalSwaps(t, mask));
        }
        out.remove(tok);
        List<String> res = new ArrayList<>();
        for (String t : out) if (valid(t)) res.add(t);
        return res;
    }

    private static String initialSwap(String t, int mask) {
        if ((mask & Z_ZH) != 0) {
            if (t.startsWith("zh")) return "z" + t.substring(2);
            if (t.startsWith("z")) return "zh" + t.substring(1);
        }
        if ((mask & C_CH) != 0) {
            if (t.startsWith("ch")) return "c" + t.substring(2);
            if (t.startsWith("c")) return "ch" + t.substring(1);
        }
        if ((mask & S_SH) != 0) {
            if (t.startsWith("sh")) return "s" + t.substring(2);
            if (t.startsWith("s")) return "sh" + t.substring(1);
        }
        return null;
    }

    private static List<String> finalSwaps(String t, int mask) {
        List<String> r = new ArrayList<>(2);
        if ((mask & XUE_XIE) != 0) {
            if (t.equals("xue")) r.add("xie");
            else if (t.equals("xie")) r.add("xue");
        }
        if ((mask & EN_ENG) != 0) {
            if (t.endsWith("eng")) r.add(t.substring(0, t.length() - 1));
            else if (t.endsWith("en")) r.add(t + "g");
        }
        if ((mask & IN_ING) != 0) {
            if (t.endsWith("ing")) r.add(t.substring(0, t.length() - 1));
            else if (t.endsWith("in")) r.add(t + "g");
        }
        if ((mask & UN_ONG) != 0) {
            if (t.endsWith("ong")) r.add(t.substring(0, t.length() - 3) + "un");
            else if (t.endsWith("un")) r.add(t.substring(0, t.length() - 2) + "ong");
        }
        if ((mask & IONG_ONG) != 0 && PSEUDO.contains(t)) {
            r.add(t.charAt(0) + "iong"); // xong -> xiong
        }
        return r;
    }

    /**
     * Alternative spellings of {@code input}; the unchanged spelling is
     * never included. Empty when fuzzy matching is off or nothing applies.
     */
    public static List<Variant> variants(String input, int mask) {
        List<Variant> out = new ArrayList<>();
        if (mask == 0 || input == null || input.length() < 2) return out;
        List<String> toks = new ArrayList<>();
        List<String> seps = new ArrayList<>();
        tokenize(input, toks, seps, mask);
        int n = toks.size();
        if (n == 0 || n > 12) return out;
        List<List<String>> alts = new ArrayList<>();
        boolean any = false;
        for (String t : toks) {
            List<String> a = alternatives(t, mask);
            alts.add(a);
            if (!a.isEmpty()) any = true;
        }
        if (!any) return out;
        Set<String> seen = new HashSet<>();
        seen.add(input);
        // One changed token at a time.
        for (int i = 0; i < n && out.size() < MAX_VARIANTS; i++) {
            for (String a : alts.get(i)) {
                String[] pick = toks.toArray(new String[0]);
                pick[i] = a;
                add(out, seen, pick, toks, seps);
                if (out.size() >= MAX_VARIANTS) break;
            }
        }
        // Two changed tokens (e.g. "shang'hai" typed as "sang'hai" + more).
        for (int i = 0; i < n && out.size() < MAX_VARIANTS; i++) {
            for (int j = i + 1; j < n && out.size() < MAX_VARIANTS; j++) {
                for (String a : alts.get(i)) {
                    for (String b : alts.get(j)) {
                        String[] pick = toks.toArray(new String[0]);
                        pick[i] = a;
                        pick[j] = b;
                        add(out, seen, pick, toks, seps);
                        if (out.size() >= MAX_VARIANTS) break;
                    }
                }
            }
        }
        return out;
    }

    private static void add(List<Variant> out, Set<String> seen, String[] pick,
                            List<String> toks, List<String> seps) {
        int n = pick.length;
        StringBuilder sb = new StringBuilder();
        int[] vt = new int[n], vs = new int[n], ot = new int[n], os = new int[n];
        int op = 0;
        for (int j = 0; j < n; j++) {
            sb.append(pick[j]);
            vt[j] = sb.length();
            op += toks.get(j).length();
            ot[j] = op;
            String sep = seps.get(j);
            if (!sep.isEmpty()) sb.append(sep);
            else if (j < n - 1) sb.append('\'');
            vs[j] = sb.length();
            op += sep.length();
            os[j] = op;
        }
        String v = sb.toString();
        if (!seen.add(v)) return;
        out.add(new Variant(v, toks.toArray(new String[0]), vt, vs, ot, os));
    }

    // ===== Nine-key (digit) fuzzy =====

    private static final String KEY_OF = "22233344455566677778889999";

    static char digitOf(char c) {
        return (c >= 'a' && c <= 'z') ? KEY_OF.charAt(c - 'a') : c;
    }

    static String imageOf(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) sb.append(digitOf(s.charAt(i)));
        return sb.toString();
    }

    /** One fuzzy pair on the nine-key: digits image D (from) -> D' (to). */
    private static final class Pair {
        final String from, to, toImg;
        Pair(String from, String to) {
            this.from = from; this.to = to; this.toImg = imageOf(to);
        }
    }

    private static java.util.Map<Integer, java.util.Map<String, List<Pair>>> PAIR_CACHE =
            new java.util.HashMap<>();

    /** Syllable digit image -> partner spellings, for the given rule mask. */
    private static synchronized java.util.Map<String, List<Pair>> pairs(int mask) {
        java.util.Map<String, List<Pair>> m = PAIR_CACHE.get(mask);
        if (m != null) return m;
        m = new java.util.HashMap<>();
        List<String> sources = new ArrayList<>(SYLLABLES);
        if ((mask & IONG_ONG) != 0) sources.addAll(PSEUDO);
        for (String syl : sources) {
            String img = imageOf(syl);
            for (String alt : alternatives(syl, mask)) {
                if (!SYLLABLES.contains(alt)) continue;
                if (imageOf(alt).equals(img)) continue;
                List<Pair> l = m.get(img);
                if (l == null) { l = new ArrayList<>(); m.put(img, l); }
                l.add(new Pair(syl, alt));
            }
        }
        PAIR_CACHE.put(mask, m);
        return m;
    }

    /** A nine-key digit string with one span replaced by its fuzzy partner. */
    public static final class DigitVariant {
        public final String digits;
        public final int start, oldLen, newLen;
        private final List<Pair> pairs;

        DigitVariant(String digits, int start, int oldLen, int newLen, List<Pair> pairs) {
            this.digits = digits; this.start = start;
            this.oldLen = oldLen; this.newLen = newLen; this.pairs = pairs;
        }

        /** Consumed digit count of the variant -> original digit count, or -1. */
        public int mapConsumed(int c) {
            if (c <= start) return c;
            if (c >= start + newLen) return c - newLen + oldLen;
            return -1;
        }

        /**
         * Rewrite the variant's pinyin units so the replaced syllable shows
         * the spelling that matches what was typed (digit count preserved).
         */
        public String fixUnits(String units, int unitsLetterOffset) {
            if (units == null) return units;
            String[] toks = units.split("'", -1);
            StringBuilder sb = new StringBuilder();
            int letters = 0;
            for (int t = 0; t < toks.length; t++) {
                String tok = toks[t];
                if (t > 0) sb.append('\'');
                if (letters == unitsLetterOffset + start && tok.length() == newLen) {
                    String repl = null;
                    for (Pair p : pairs) {
                        if (p.to.equals(tok)) { repl = p.from; break; }
                    }
                    if (repl != null) tok = repl;
                }
                sb.append(tok);
                letters += toks[t].length();
            }
            return sb.toString();
        }
    }

    /**
     * Fuzzy alternatives of a nine-key digit string: each span of digits
     * that spells a syllable with a fuzzy partner is replaced by the
     * partner's digits. {@code boundaries} (letter offsets of the engine's
     * best segmentation, may be null) puts aligned spans first.
     */
    public static List<DigitVariant> digitVariants(String digits, int mask,
                                                   java.util.Set<Integer> boundaries,
                                                   int cap) {
        List<DigitVariant> aligned = new ArrayList<>();
        List<DigitVariant> other = new ArrayList<>();
        if (mask == 0 || digits == null || digits.length() < 2) return aligned;
        java.util.Map<String, List<Pair>> table = pairs(mask);
        Set<String> seen = new HashSet<>();
        seen.add(digits);
        int n = digits.length();
        for (int i = 0; i < n; i++) {
            if (digits.charAt(i) == '\'') continue;
            for (int j = i + 2; j <= Math.min(n, i + 6); j++) {
                if (digits.charAt(j - 1) == '\'') break;
                String span = digits.substring(i, j);
                List<Pair> ps = table.get(span);
                if (ps == null) continue;
                java.util.Map<String, List<Pair>> byImg = new java.util.LinkedHashMap<>();
                for (Pair p : ps) {
                    List<Pair> l = byImg.get(p.toImg);
                    if (l == null) { l = new ArrayList<>(); byImg.put(p.toImg, l); }
                    l.add(p);
                }
                for (java.util.Map.Entry<String, List<Pair>> e : byImg.entrySet()) {
                    String v = digits.substring(0, i) + e.getKey() + digits.substring(j);
                    if (!seen.add(v)) continue;
                    DigitVariant dv = new DigitVariant(v, i, j - i, e.getKey().length(),
                            e.getValue());
                    boolean al = boundaries != null && boundaries.contains(i)
                            && boundaries.contains(j);
                    (al ? aligned : other).add(dv);
                }
            }
        }
        List<DigitVariant> out = new ArrayList<>(aligned);
        out.addAll(other);
        return out.size() > cap ? new ArrayList<>(out.subList(0, cap)) : out;
    }
}
