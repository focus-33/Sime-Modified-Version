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
    private static final int NUM_VARIANTS = 10;
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
            return base.decodeSentence(pinyin, limit); // never lose candidates
        }
    }

    private DecodeResult[] decodeSentenceTuned(String pinyin, int limit) {
        DecodeResult[] orig = base.decodeSentence(pinyin, limit);
        List<DecodeResult> extras = new ArrayList<>();
        if (pinyin != null && pinyin.matches("[a-z']+")) {
            for (FuzzyPinyin.Variant v : FuzzyPinyin.variants(pinyin, fuzzyMask.getAsInt())) {
                for (DecodeResult r : base.decodeSentence(v.input, limit)) {
                    int off = v.mapConsumed(r.consumed);
                    if (off <= 0) continue;
                    extras.add(new DecodeResult(r.text, v.originalUnits(off),
                            off, r.tokenIds, r.score));
                }
            }
        }
        return finish(limitLongSentences(merge(orig, extras)));
    }

    // ===== Nine-key =====

    @Override
    public DecodeResult[] decodeNumSentence(String startLetters, String digits, int limit) {
        try {
            return decodeNumTuned(startLetters, digits, limit);
        } catch (RuntimeException e) {
            return base.decodeNumSentence(startLetters, digits, limit);
        }
    }

    private DecodeResult[] decodeNumTuned(String startLetters, String digits, int limit) {
        String start = startLetters == null ? "" : startLetters;
        if (start.isEmpty() && digits != null && digits.length() == 1
                && digits.charAt(0) >= '2' && digits.charAt(0) <= '9') {
            DecodeResult[] lone = loneKey(digits.charAt(0), limit);
            if (lone.length > 0) return finish(Arrays.asList(lone));
        }
        DecodeResult[] orig = base.decodeNumSentence(start, digits, limit);
        List<DecodeResult> extras = new ArrayList<>();
        int mask = fuzzyMask.getAsInt();
        if (mask != 0 && digits != null) {
            addDigitVariants(extras, orig, start, digits, mask, limit);
        }
        return finish(limitLongSentences(demoteWeakSentences(merge(orig, extras))));
    }

    private void addDigitVariants(List<DecodeResult> extras, DecodeResult[] orig,
                                  String start, String digits, int mask, int limit) {
        if (digits.length() < 2) return;
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
        for (FuzzyPinyin.DigitVariant v :
                FuzzyPinyin.digitVariants(digits, mask, bounds, NUM_VARIANTS)) {
            for (DecodeResult r : base.decodeNumSentence(start, v.digits, limit)) {
                int cd = r.consumed - start.length();
                int od = v.mapConsumed(cd);
                if (cd <= 0 || od <= 0) continue;
                String units = r.units == null ? "" : v.fixUnits(r.units, start.length());
                extras.add(new DecodeResult(r.text, units,
                        od + start.length(), r.tokenIds, r.score));
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
            for (DecodeResult r : base.decodeSentence(String.valueOf(ch), limit)) {
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

    @Override
    public String bestNumUnits(String digits) {
        if (digits == null || digits.isEmpty()) return "";
        if (digits.length() == 1 && digits.charAt(0) >= '2' && digits.charAt(0) <= '9') {
            DecodeResult[] lone = loneKey(digits.charAt(0), 0);
            return lone.length > 0 ? lone[0].units : "";
        }
        DecodeResult[] r = base.decodeNumSentence("", digits, 0);
        return (r.length > 0 && r[0].units != null) ? r[0].units : "";
    }

    // ===== Pass-through =====

    @Override
    public String[] t9PinyinSyllables(String digits, int limit) {
        return base.t9PinyinSyllables(digits, limit);
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
