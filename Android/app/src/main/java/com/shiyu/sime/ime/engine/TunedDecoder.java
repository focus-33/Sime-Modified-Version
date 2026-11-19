package com.shiyu.sime.ime.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Decorates the native decoder with: fuzzy pinyin (variants decoded as if
 * typed correctly and merged by raw engine score, so they rank equally),
 * frequency-based candidates for a lone first T9 key, and the user's own
 * ordering / blocking rules.
 */
public final class TunedDecoder implements Decoder {
    private static final String[] KEY_LETTERS = {
            "", "", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"};
    /** a / e / o are whole syllables; keep them below real initials. */
    private static final float SYLLABLE_KEY_PENALTY = 4.0f;
    /** Every variant is a full extra decode; do fewer as the input grows. */
    private static int digitVariantCap(int digitCount) {
        if (digitCount <= 12) return 8;
        if (digitCount <= 20) return 3;
        return 0;
    }

    /** Longest input for which first-syllable probing is done. */
    /**
     * A whole-input sentence that only exists through a fuzzy spelling must beat
     * the sentence for exactly what was typed by this much (log-score units)
     * before it may replace it. Measured on 300 nine-key test sentences: with 0
     * (fully equal footing) fuzzy spellings pushed some correctly typed
     * sentences off the first place (25.3% first-place accuracy); with 2 or more
     * the loss is gone (26.0%, same as with fuzzy off). Raise it to trust the
     * typed spelling even more, lower it for a more eager fuzzy match.
     */
    static final float FUZZY_FULL_MARGIN = 2.0f;

    /** Longest stretch (letters / digits) that is decoded again for fuzzy matching on long input. */
    private static final int FUZZY_WINDOW = 14;
    private static final int FUZZY_WINDOW_DIGITS = 12;

    private static final int PROBE_MIN_DIGITS = 7;
    private static final int PROBE_MAX_DIGITS = 14;
    private static final int PROBE_MAX = 2;
    /**
     * Composed (multi-word) sentences below this average score per syllable
     * are almost always nonsense stitched from unrelated words
     * (e.g. 嗯将筹办); real phrases measured at -5.1 or better.
     */
    static final float WEAK_SENTENCE_PER_SYLLABLE = -5.3f;
    private static final int WEAK_MIN_SYLLABLES = 3;
    private static final int WEAK_MAX_SYLLABLES = 6;
    /** How many other candidates are shown before a demoted sentence. */
    private static final int WEAK_KEEP_AHEAD = 6;

    private final Decoder base;
    private final CandidateTuning tuning;
    private final IntSupplier fuzzyMask;

    public TunedDecoder(Decoder base, CandidateTuning tuning, IntSupplier fuzzyMask) {
        this.base = base;
        this.tuning = tuning;
        this.fuzzyMask = fuzzyMask != null ? fuzzyMask : () -> 0;
    }

    public CandidateTuning tuning() { return tuning; }

    // ===== Full-keyboard pinyin =====

    @Override
    public DecodeResult[] decodeSentence(String pinyin, int limit) {
        try {
            return decodeSentenceTuned(pinyin, limit);
        } catch (RuntimeException e) {
            return engSentence(pinyin, limit); // never lose candidates
        }
    }

    private DecodeResult[] decodeSentenceTuned(String pinyin, int limit) {
        DecodeResult[] orig = engSentence(pinyin, limit);
        List<DecodeResult> extras = new ArrayList<>();
        if (pinyin != null && pinyin.matches("[a-z']+")) {
            // Long input: a variant of the whole sentence would be a long extra
            // decode. Only the first words matter for the next pick anyway, so
            // vary just the opening stretch and keep its whole words.
            String text = pinyin;
            boolean windowed = false;
            if (pinyin.length() > FUZZY_WINDOW) {
                int end = FuzzyPinyin.windowEnd(pinyin, FUZZY_WINDOW);
                if (end > 0 && end < pinyin.length()) {
                    text = pinyin.substring(0, end);
                    windowed = true;
                }
            }
            List<FuzzyPinyin.Variant> vs = FuzzyPinyin.variants(text, fuzzyMask.getAsInt());
            int cap = windowed ? 4 : 6;
            if (vs.size() > cap) vs = vs.subList(0, cap);
            for (FuzzyPinyin.Variant v : vs) {
                for (DecodeResult r : engSentence(v.input, limit)) {
                    int off = v.mapConsumed(r.consumed);
                    if (off <= 0) continue;
                    if (windowed && !isCompleteWord(r)) continue;
                    float sc = (!windowed && off >= pinyin.length())
                            ? r.score - FUZZY_FULL_MARGIN : r.score;
                    extras.add(new DecodeResult(r.text, v.originalUnits(off),
                            off, r.tokenIds, sc));
                }
            }
        }
        return finish(limitLongSentences(restrictToTopSegmentation(
                dropNonsenseSentences(dropAbbreviations(dropBannedReadings(
                        merge(orig, extras)))))));
    }

    // ===== Nine-key =====

    @Override
    public DecodeResult[] decodeNumSentence(String startLetters, String digits, int limit) {
        try {
            return decodeNumTuned(startLetters, digits, limit);
        } catch (RuntimeException e) {
            return engNum(startLetters, digits, limit);
        }
    }

    private DecodeResult[] decodeNumTuned(String startLetters, String digits, int limit) {
        String start = startLetters == null ? "" : startLetters;
        if (start.isEmpty() && digits != null && digits.length() == 1
                && digits.charAt(0) >= '2' && digits.charAt(0) <= '9') {
            DecodeResult[] lone = loneKey(digits.charAt(0), limit);
            if (lone.length > 0) return finish(Arrays.asList(lone));
        }
        DecodeResult[] orig = engNum(start, digits, limit);
        List<DecodeResult> extras = new ArrayList<>();
        int mask = fuzzyMask.getAsInt();
        if (mask != 0 && digits != null) {
            addDigitVariants(extras, orig, start, digits, mask, limit);
        }
        if (start.isEmpty() && digits != null) {
            addFirstSyllableProbes(extras, orig, digits, limit);
        }
        return finish(limitLongSentences(restrictToTopSegmentation(
                demoteWeakSentences(dropNonsenseSentences(dropAbbreviations(
                        dropBannedReadings(merge(orig, extras))))))));
    }

    private void addDigitVariants(List<DecodeResult> extras, DecodeResult[] orig,
                                  String start, String digits, int mask, int limit) {
        if (digits.length() < 2) return;
        // Long input: vary only the opening stretch (see decodeSentenceTuned).
        String text = digits;
        boolean windowed = false;
        if (digits.length() > FUZZY_WINDOW_DIGITS) {
            text = digits.substring(0, FUZZY_WINDOW_DIGITS);
            windowed = true;
        }
        Set<Integer> bounds = null;
        if (orig.length > 0 && orig[0].units != null
                && digits.indexOf('\'') < 0 && start.isEmpty()) {
            bounds = new HashSet<>();
            int off = 0;
            bounds.add(0);
            for (String tok : orig[0].units.split("'")) {
                off += tok.length();
                bounds.add(off);
            }
        }
        int cap = windowed ? 4 : digitVariantCap(digits.length());
        for (FuzzyPinyin.DigitVariant v : FuzzyPinyin.digitVariants(text, mask, bounds, cap)) {
            for (DecodeResult r : engNum(start, v.digits, limit)) {
                int cd = r.consumed - start.length();
                int od = v.mapConsumed(cd);
                if (cd <= 0 || od <= 0) continue;
                if (!v.acceptsUnits(r.units, start.length(), cd)) continue;
                if (windowed && !isCompleteWord(r)) continue;
                String units = r.units == null ? "" : v.fixUnits(r.units, start.length());
                float sc = (!windowed && od >= digits.length())
                        ? r.score - FUZZY_FULL_MARGIN : r.score;
                extras.add(new DecodeResult(r.text, units,
                        od + start.length(), r.tokenIds, sc));
            }
        }
    }

    /**
     * A single dictionary word spelled with complete syllables (no
     * sentence, no abbreviation, no unfinished last syllable). The only kind
     * of result taken from the windowed fuzzy decode of a long input.
     */
    private static boolean isCompleteWord(DecodeResult r) {
        if (r.tokenIds == null || r.tokenIds.length != 1 || !isChinese(r)) return false;
        for (String piece : r.units.split("'")) {
            if (!FuzzyPinyin.isSyllable(piece) && !FuzzyPinyin.isPseudo(piece)) return false;
        }
        return true;
    }

    /**
     * The nine-key engine searches ambiguous digits with a limited beam, so
     * a rarer four-character word can be missed (e.g. 7487449838 finds
     * 舒适祖父 but not 熟视无睹), yet it is found at once when the first
     * syllable is fixed, as if the user had tapped it in the syllable list.
     * Do that automatically for the most likely first syllable(s).
     */
    private void addFirstSyllableProbes(List<DecodeResult> extras, DecodeResult[] orig,
                                        String digits, int limit) {
        if (digits.length() < PROBE_MIN_DIGITS || digits.length() > PROBE_MAX_DIGITS
                || digits.indexOf('\'') >= 0) return;
        List<String> firsts = new ArrayList<>();
        for (DecodeResult r : orig) {
            if (firsts.size() >= PROBE_MAX) break;
            if (!isChinese(r)) continue;
            String first = r.units.split("'")[0];
            if (first.length() >= digits.length() || !FuzzyPinyin.isSyllable(first)) continue;
            if (!firsts.contains(first)) firsts.add(first);
        }
        for (String first : firsts) {
            for (DecodeResult r : engNum(first,
                    digits.substring(first.length()), limit)) {
                if (r.consumed <= 0) continue;
                extras.add(r);
            }
        }
    }

    /** First keystroke on a T9 key: most frequent characters of all its letters. */
    private DecodeResult[] loneKey(char digit, int limit) {
        Map<String, DecodeResult> best = new HashMap<>();
        String letters = KEY_LETTERS[digit - '0'];
        for (int i = 0; i < letters.length(); i++) {
            char ch = letters.charAt(i);
            if (ch == 'i' || ch == 'u' || ch == 'v') continue;
            float penalty = (ch == 'a' || ch == 'e' || ch == 'o')
                    ? SYLLABLE_KEY_PENALTY : 0f;
            for (DecodeResult r : engSentence(String.valueOf(ch), limit)) {
                if (r.text == null || r.text.isEmpty() || isAscii(r.text)) continue;
                float score = r.score - penalty;
                DecodeResult old = best.get(r.text);
                if (old == null || old.score < score) {
                    best.put(r.text, new DecodeResult(r.text, String.valueOf(ch),
                            1, r.tokenIds, score));
                }
            }
        }
        List<DecodeResult> list = new ArrayList<>(best.values());
        Collections.sort(list, (a, b) -> Float.compare(b.score, a.score));
        return list.toArray(new DecodeResult[0]);
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7f) return false;
        return true;
    }

    // ===== Prediction / English completion: only hide blocked words =====

    @Override
    public DecodeResult[] nextTokens(int[] contextIds, int limit, boolean enOnly) {
        return dropBlocked(base.nextTokens(contextIds, limit, enOnly));
    }

    @Override
    public DecodeResult[] getTokens(String prefix, int limit, boolean enOnly) {
        return dropBlocked(base.getTokens(prefix, limit, enOnly));
    }

    private DecodeResult[] dropBlocked(DecodeResult[] in) {
        if (!tuning.hasRules()) return in;
        List<DecodeResult> out = new ArrayList<>(in.length);
        for (DecodeResult r : in) if (!tuning.isBlocked(r.text)) out.add(r);
        return out.toArray(new DecodeResult[0]);
    }

    private final java.util.LinkedHashMap<String, String> unitsCache =
            new java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> e) {
                    return size() > 128;
                }
            };

    @Override
    public String bestNumUnits(String digits) {
        if (digits == null || digits.isEmpty()) return "";
        String cached = unitsCache.get(digits);
        if (cached != null) return cached;
        String units;
        if (digits.length() == 1 && digits.charAt(0) >= '2' && digits.charAt(0) <= '9') {
            DecodeResult[] lone = loneKey(digits.charAt(0), 0);
            units = lone.length > 0 ? lone[0].units : "";
        } else {
            DecodeResult[] r = engNum("", digits, 0);
            units = (r.length > 0 && r[0].units != null) ? r[0].units : "";
        }
        unitsCache.put(digits, units);
        return units;
    }

    // ===== Pass-through =====

    @Override
    public String[] t9PinyinSyllables(String digits, int limit) {
        return aliasSyllablesOut(base.t9PinyinSyllables(digits, limit));
    }

    @Override public int contextSize() { return base.contextSize(); }

    @Override
    public void learnUserSentence(int[] context, int[] tokens) {
        base.learnUserSentence(context, tokens);
    }

    @Override public void flushUserSentence() { base.flushUserSentence(); }

    @Override public void onUserCommit(String text) { tuning.onCommit(text); }

    /**
     * Nine-key input is ambiguous, so when no real word fits, the engine
     * stitches several unrelated words into a "sentence" and puts it first.
     * Such weak full-length sentences are moved behind the first few
     * other candidates (still available, no longer the default).
     */
    static List<DecodeResult> demoteWeakSentences(List<DecodeResult> in) {
        int full = 0;
        for (DecodeResult r : in) full = Math.max(full, r.consumed);
        List<DecodeResult> keep = new ArrayList<>(in.size());
        List<DecodeResult> weak = new ArrayList<>();
        for (DecodeResult r : in) {
            if (isWeakSentence(r, full)) weak.add(r); else keep.add(r);
        }
        if (weak.isEmpty() || keep.isEmpty()) return in;
        keep.addAll(Math.min(WEAK_KEEP_AHEAD, keep.size()), weak);
        return keep;
    }

    private static boolean isWeakSentence(DecodeResult r, int full) {
        if (r.consumed != full || r.tokenIds == null || r.tokenIds.length < 2) return false;
        if (r.text == null || isAscii(r.text) || r.units == null) return false;
        int syl = r.units.split("'").length;
        if (syl < WEAK_MIN_SYLLABLES || syl > WEAK_MAX_SYLLABLES) return false;
        return r.score / syl < WEAK_SENTENCE_PER_SYLLABLE;
    }

    // ===== Nonsense whole-input sentences: do not show them at all =====

    /**
     * Composed sentences below this average score per syllable are never
     * real phrases (measured: real sentences score -3.4 or better, stitched
     * nonsense such as 嗯两次模板 scores -6.5; the limit was raised from
     * -6.0 to -5.5 to also catch borderline cases such as 佛教还目标 at -5.75). They are removed, not just
     * moved back.
     */
    static final float NONSENSE_PER_SYLLABLE = -5.5f;
    private static final int NONSENSE_MIN_SYLLABLES = 3;
    private static final int NONSENSE_MAX_SYLLABLES = 8;

    /**
     * Removes, among the candidates that cover the whole input:
     * <ol>
     *   <li>composed (2+ word) sentences that score far too low;</li>
     *   <li>"stretched" sentences that contain single-letter abbreviation
     *       pieces (e.g. ming'm'g'g'mi'shi -> 名美国公民是) when the best
     *       sentence is made of complete syllables only. When the user is
     *       really typing initials, the best sentence has such pieces itself
     *       and nothing is removed.</li>
     * </ol>
     * Words and single characters for part of the input are untouched, so
     * something sensible is always left to pick.
     */
    static List<DecodeResult> dropNonsenseSentences(List<DecodeResult> in) {
        int full = 0;
        for (DecodeResult r : in) if (isChinese(r)) full = Math.max(full, r.consumed);
        if (full <= 0) return in;
        List<DecodeResult> kept = new ArrayList<>(in.size());
        for (DecodeResult r : in) {
            if (isChinese(r) && r.consumed == full && isNonsenseSentence(r)) continue;
            kept.add(r);
        }
        if (kept.isEmpty()) return in;
        DecodeResult top = null;
        for (DecodeResult r : kept) {
            if (isChinese(r) && r.consumed == full) { top = r; break; }
        }
        if (top != null && !hasAbbreviationPiece(top)) {
            List<DecodeResult> kept2 = new ArrayList<>(kept.size());
            for (DecodeResult r : kept) {
                if (r != top && isChinese(r) && r.consumed == full
                        && hasAbbreviationPiece(r)) continue;
                kept2.add(r);
            }
            kept = kept2;
        }
        return kept;
    }

    private static boolean isNonsenseSentence(DecodeResult r) {
        if (r.tokenIds == null || r.tokenIds.length < 2) return false;
        int syl = r.units.split("'").length;
        if (syl < NONSENSE_MIN_SYLLABLES || syl > NONSENSE_MAX_SYLLABLES) return false;
        return r.score / syl < NONSENSE_PER_SYLLABLE;
    }

    /** True if any pinyin piece is not a complete syllable (an initial only). */
    private static boolean hasAbbreviationPiece(DecodeResult r) {
        for (String piece : r.units.split("'")) {
            if (!piece.isEmpty() && !FuzzyPinyin.isSyllable(piece)
                    && !FuzzyPinyin.isPseudo(piece)) return true;
        }
        return false;
    }

    // ===== Long phrases / sentences: show fewer, more useful candidates =====

    /**
     * Beyond the very first candidate, extra whole-input sentences are
     * almost never picked, so they only get in the way. Rules, applied to
     * the candidates that cover the whole input:
     * <ul>
     *   <li>up to 3 syllables: unchanged;</li>
     *   <li>4 syllables (a four-character word): keep 2;</li>
     *   <li>5 or more syllables: keep 1.</li>
     * </ul>
     * The candidates that follow are those for the first word of the
     * sentence (the longest word that starts the best sentence goes
     * first), then shorter words and single characters; after the user
     * picks, the next word's candidates appear the same way.
     */
    static List<DecodeResult> limitLongSentences(List<DecodeResult> in) {
        int full = 0;
        for (DecodeResult r : in) if (isChinese(r)) full = Math.max(full, r.consumed);
        if (full <= 0) return in;
        // Whole-input candidates, real ones before stitched-together ones.
        List<DecodeResult> solid = new ArrayList<>();
        List<DecodeResult> weak = new ArrayList<>();
        for (DecodeResult r : in) {
            if (!isChinese(r) || r.consumed != full) continue;
            if (isWeakSentence(r, full)) weak.add(r); else solid.add(r);
        }
        DecodeResult top = !solid.isEmpty() ? solid.get(0)
                : (!weak.isEmpty() ? weak.get(0) : null);
        if (top == null) return in;
        int syllables = top.units.split("'").length;
        if (syllables <= 3) return in;
        final int keepFull = (syllables == 4) ? 2 : 1;

        Set<DecodeResult> keep = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<DecodeResult, Boolean>());
        for (DecodeResult r : solid) { if (keep.size() < keepFull) keep.add(r); }
        for (DecodeResult r : weak) { if (keep.size() < keepFull) keep.add(r); }

        List<DecodeResult> lead = new ArrayList<>();   // kept real sentences, first
        List<DecodeResult> rest = new ArrayList<>(in.size());
        for (DecodeResult r : in) {
            boolean wholeInput = isChinese(r) && r.consumed == full;
            if (wholeInput && !keep.contains(r)) continue;       // dropped
            if (wholeInput && !isWeakSentence(r, full)) lead.add(r);
            else rest.add(r);
        }
        List<DecodeResult> out = new ArrayList<>(lead);
        out.addAll(rest);

        // Put the first word of the best sentence right after the kept
        // sentence(s): the longest shorter candidate the sentence starts with.
        int insertAt = lead.size();
        int best = -1;
        for (int i = 0; i < out.size(); i++) {
            DecodeResult r = out.get(i);
            if (!isChinese(r) || r.consumed >= full) continue;
            if (!top.text.startsWith(r.text)) continue;
            if (best < 0 || r.text.length() > out.get(best).text.length()) best = i;
        }
        if (best >= 0 && best != insertAt) {
            DecodeResult first = out.remove(best);
            out.add(Math.min(insertAt, out.size()), first);
        }
        return out;
    }

    private static boolean isChinese(DecodeResult r) {
        return r.text != null && !r.text.isEmpty() && !isAscii(r.text)
                && r.units != null && !r.units.isEmpty();
    }

    // ===== ü spellings =====
    // The dictionary spells the ü syllables with v (lve, nve), the standard
    // spelling is lue / nue. Typed lue / nue is therefore handed to the engine
    // as lve / nve (and shown back as lue / nue); without this the engine
    // can only split "lue" into lu'e (so 掠过 / 略过 never appear).

    private static boolean isPinyinLike(List<String> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            boolean last = (i == tokens.size() - 1);
            if (FuzzyPinyin.isSyllable(t)) continue;
            if (last && FuzzyPinyin.isSyllablePrefix(t)) continue;
            return false;
        }
        return !tokens.isEmpty();
    }

    /** Typed lue / nue -> dictionary lve / nve, only when the text is real pinyin. */
    static String aliasIn(String typed) {
        if (typed == null || (typed.indexOf("lue") < 0 && typed.indexOf("nue") < 0)) return typed;
        List<String> tokens = new ArrayList<>();
        List<String> seps = new ArrayList<>();
        FuzzyPinyin.tokenize(typed, tokens, seps, 0);
        if (!isPinyinLike(tokens)) return typed;      // e.g. the English word "blue"
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            sb.append(t.equals("lue") ? "lve" : t.equals("nue") ? "nve" : t).append(seps.get(i));
        }
        return sb.toString();
    }

    /** Dictionary lve / nve -> standard lue / nue for display and further input. */
    static String unitsOut(String units) {
        if (units == null || (units.indexOf("lve") < 0 && units.indexOf("nve") < 0)) return units;
        String[] pieces = units.split("'", -1);
        for (int i = 0; i < pieces.length; i++) {
            if (pieces[i].equals("lve")) pieces[i] = "lue";
            else if (pieces[i].equals("nve")) pieces[i] = "nue";
        }
        return String.join("'", pieces);
    }

    private static DecodeResult[] aliasResultsOut(DecodeResult[] in) {
        DecodeResult[] out = null;
        for (int i = 0; i < in.length; i++) {
            String u = unitsOut(in[i].units);
            if (u != in[i].units) {
                if (out == null) out = in.clone();
                DecodeResult r = in[i];
                out[i] = new DecodeResult(r.text, u, r.consumed, r.tokenIds, r.score);
            }
        }
        return out == null ? in : out;
    }

    private static String[] aliasSyllablesOut(String[] in) {
        if (in == null) return null;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String s : in) seen.add(s.equals("lve") ? "lue" : s.equals("nve") ? "nue" : s);
        return seen.toArray(new String[0]);
    }

    private DecodeResult[] engSentence(String pinyin, int limit) {
        return aliasResultsOut(base.decodeSentence(aliasIn(pinyin), limit));
    }

    private DecodeResult[] engNum(String start, String digits, int limit) {
        return aliasResultsOut(base.decodeNumSentence(aliasIn(start), digits, limit));
    }

    // ===== Cleaner candidate lists =====

    /** Readings that must never be offered (text|pinyin). */
    private static final java.util.Set<String> BANNED_READINGS = new java.util.HashSet<>(
            java.util.Arrays.asList("便宜|bian'yi"));

    /** Drops banned (word, reading) pairs, e.g. 便宜 read as bian'yi. */
    static List<DecodeResult> dropBannedReadings(List<DecodeResult> in) {
        List<DecodeResult> out = null;
        for (int i = 0; i < in.size(); i++) {
            DecodeResult r = in.get(i);
            boolean banned = r.units != null
                    && BANNED_READINGS.contains(r.text + "|" + r.units.toLowerCase());
            if (banned && out == null) out = new ArrayList<>(in.subList(0, i));
            if (!banned && out != null) out.add(r);
        }
        return out == null ? in : out;
    }

    /**
     * No abbreviations (简拼): a Chinese candidate is kept only when every
     * pinyin piece except the last is a complete syllable, and the last is a
     * complete syllable or the beginning of one (the syllable still being
     * typed). So h'g'z'y -> 后顾之忧 and d'm'jiang -> 打麻将 never appear.
     */
    static List<DecodeResult> dropAbbreviations(List<DecodeResult> in) {
        List<DecodeResult> out = new ArrayList<>(in.size());
        for (DecodeResult r : in) {
            if (isChinese(r) && usesAbbreviation(r.units)) continue;
            out.add(r);
        }
        return out;
    }

    private static boolean usesAbbreviation(String units) {
        String[] pieces = units.split("'");
        for (int i = 0; i < pieces.length; i++) {
            boolean last = (i == pieces.length - 1);
            if (FuzzyPinyin.isSyllable(pieces[i]) || FuzzyPinyin.isPseudo(pieces[i])) continue;
            if (last && FuzzyPinyin.isSyllablePrefix(pieces[i])) continue;
            return true;
        }
        return false;
    }

    /**
     * With a clean best sentence of four or more syllables, the words offered
     * for its first part must follow its syllable boundaries; words that cut
     * the same keys differently (e.g. 余泱漪 for wu'wang'zhi'zai) are noise.
     */
    static List<DecodeResult> restrictToTopSegmentation(List<DecodeResult> in) {
        int full = 0;
        for (DecodeResult r : in) if (isChinese(r)) full = Math.max(full, r.consumed);
        if (full <= 0) return in;
        DecodeResult top = null;
        for (DecodeResult r : in) {
            if (isChinese(r) && r.consumed == full && !isWeakSentence(r, full)) { top = r; break; }
        }
        if (top == null) return in;
        String[] pieces = top.units.split("'");
        if (pieces.length < 4) return in;
        for (String p : pieces) {
            if (!FuzzyPinyin.isSyllable(p) && !FuzzyPinyin.isPseudo(p)) return in;
        }
        java.util.Set<Integer> boundaries = new java.util.HashSet<>();
        int sum = 0;
        for (String p : pieces) { sum += p.length(); boundaries.add(sum); }
        if (sum != full) return in; // typed separators etc.: leave it alone
        List<DecodeResult> out = new ArrayList<>(in.size());
        for (DecodeResult r : in) {
            if (isChinese(r) && r.consumed < full && !boundaries.contains(r.consumed)) continue;
            out.add(r);
        }
        return out;
    }

    // ===== Merge =====

    private DecodeResult[] finish(List<DecodeResult> merged) {
        return tuning.apply(merged).toArray(new DecodeResult[0]);
    }

    /**
     * The best-scoring head (full-sentence result) of any source leads;
     * everything else follows ordered by engine score, de-duplicated.
     */
    static List<DecodeResult> merge(DecodeResult[] orig, List<DecodeResult> extras) {
        List<DecodeResult> origList = new ArrayList<>(Arrays.asList(orig));
        if (extras.isEmpty()) return origList;
        Map<String, DecodeResult> best = new HashMap<>();
        List<String> order = new ArrayList<>();
        List<DecodeResult> all = new ArrayList<>(origList);
        all.addAll(extras);
        DecodeResult head = null;
        for (DecodeResult r : all) {
            if (r.text == null || r.text.isEmpty()) continue;
            String key = r.text + "\u0001" + r.consumed;
            DecodeResult old = best.get(key);
            if (old == null) { best.put(key, r); order.add(key); }
            else if (r.score > old.score) best.put(key, r);
        }
        // Heads: first result of each source (orig first, then extras).
        List<DecodeResult> heads = new ArrayList<>();
        if (!origList.isEmpty()) heads.add(origList.get(0));
        for (DecodeResult e : extras) {
            if (e.consumed == fullLength(extras, e)) heads.add(e);
        }
        for (DecodeResult h : heads) {
            if (head == null || h.score > head.score) head = h;
        }
        List<DecodeResult> rest = new ArrayList<>();
        for (String k : order) rest.add(best.get(k));
        final DecodeResult lead = head;
        List<DecodeResult> out = new ArrayList<>(rest.size());
        if (lead != null) {
            DecodeResult chosen = best.get(lead.text + "\u0001" + lead.consumed);
            out.add(chosen != null ? chosen : lead);
            rest.remove(chosen != null ? chosen : lead);
        }
        // Stable sort keeps the engine's own order for equal scores.
        Collections.sort(rest, (a, b) -> Float.compare(b.score, a.score));
        out.addAll(rest);
        return out;
    }

    /** Longest consumed length among results (the full-input sentence). */
    private static int fullLength(List<DecodeResult> list, DecodeResult any) {
        int max = 0;
        for (DecodeResult r : list) max = Math.max(max, r.consumed);
        return max;
    }
}
