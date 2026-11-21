package com.shiyu.sime.ime.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adds one-step fuzzy-pinyin retrieval around an existing Decoder. Exact
 * results remain first; fuzzy results carry a score penalty and mapped input
 * offsets. The wrapped native decoder and its dictionary are not modified.
 */
public final class FuzzyPinyinDecoder implements Decoder {
    private static final int MAX_FUZZY_RESULTS = 6;
    private static final int MAX_T9_QUERIES = 8;
    private final Decoder base;

    public FuzzyPinyinDecoder(Decoder base) {
        if (base == null) throw new IllegalArgumentException("base decoder is required");
        this.base = base;
    }

    @Override
    public DecodeResult[] decodeSentence(String pinyin, int limit) {
        DecodeResult[] exact = safe(base.decodeSentence(pinyin, limit));
        if (pinyin == null || pinyin.isEmpty()) return exact;
        List<FuzzyVariant> variants = FuzzyPinyinEngine.expandPinyin(pinyin);
        return appendFuzzy(exact, variants,
                variant -> base.decodeSentence(variant.value, limit),
                r -> r.consumed);
    }

    @Override
    public DecodeResult[] decodeNumSentence(String startLetters, String digits, int limit) {
        String start = startLetters == null ? "" : startLetters;
        String nums = digits == null ? "" : digits;
        DecodeResult[] exact = safe(base.decodeNumSentence(start, nums, limit));
        if (start.isEmpty() && nums.isEmpty()) return exact;

        List<DecodeResult> fuzzy = new ArrayList<>();
        Set<String> exactKeys = keys(exact);
        Map<String, DecodeResult> best = new HashMap<>();
        Map<String, FuzzyOrigin> origins = new HashMap<>();

        // T9 syllables already selected in the letter strip: transform only
        // that explicit prefix, leaving the still-ambiguous digit suffix intact.
        int queries = 0;
        for (FuzzyVariant v : FuzzyPinyinEngine.expandPinyin(start)) {
            if (queries++ >= MAX_T9_QUERIES) break;
            DecodeResult[] found = safe(base.decodeNumSentence(v.value, nums, limit));
            collectMappedStart(found, v, exactKeys, best, origins);
        }

        // Unselected T9 digits: syllable boundaries are inferred only when the
        // complete digit chunk can be segmented into legal pinyin syllables.
        for (FuzzyVariant v : FuzzyPinyinEngine.expandT9(nums)) {
            if (queries++ >= MAX_T9_QUERIES) break;
            DecodeResult[] found = safe(base.decodeNumSentence(start, v.value, limit));
            collectMappedDigits(found, v, start.length(), exactKeys, best, origins);
        }

        fuzzy.addAll(best.values());
        fuzzy.sort((a, b) -> Float.compare(b.score, a.score));
        return mergeExactAndFuzzy(exact, fuzzy, origins);
    }

    private interface VariantQuery {
        DecodeResult[] decode(FuzzyVariant variant);
    }

    private interface ConsumedReader {
        int get(DecodeResult result);
    }

    private DecodeResult[] appendFuzzy(DecodeResult[] exact, List<FuzzyVariant> variants,
                                       VariantQuery query, ConsumedReader consumedReader) {
        if (variants.isEmpty()) return exact;
        Set<String> exactKeys = keys(exact);
        Map<String, DecodeResult> best = new HashMap<>();
        Map<String, FuzzyOrigin> origins = new HashMap<>();
        for (FuzzyVariant variant : variants) {
            DecodeResult[] found = safe(query.decode(variant));
            for (DecodeResult r : found) {
                int mapped = variant.mapConsumed(consumedReader.get(r));
                addFuzzy(r, mapped, variant, 0, true, exactKeys, best, origins);
            }
        }
        List<DecodeResult> fuzzy = new ArrayList<>(best.values());
        fuzzy.sort((a, b) -> Float.compare(b.score, a.score));
        return mergeExactAndFuzzy(exact, fuzzy, origins);
    }

    private static void collectMappedStart(DecodeResult[] found, FuzzyVariant variant,
                                           Set<String> exactKeys,
                                           Map<String, DecodeResult> best,
                                           Map<String, FuzzyOrigin> origins) {
        for (DecodeResult r : found) {
            addFuzzy(r, variant.mapConsumed(r.consumed), variant, 0, true, exactKeys, best, origins);
        }
    }

    private static void collectMappedDigits(DecodeResult[] found, FuzzyVariant variant,
                                            int prefixLength, Set<String> exactKeys,
                                            Map<String, DecodeResult> best,
                                            Map<String, FuzzyOrigin> origins) {
        for (DecodeResult r : found) {
            int mapped;
            if (r.consumed <= prefixLength) {
                mapped = r.consumed;
            } else {
                int mappedDigits = variant.mapConsumed(r.consumed - prefixLength);
                mapped = mappedDigits < 0 ? -1 : prefixLength + mappedDigits;
            }
            addFuzzy(r, mapped, variant, prefixLength, false, exactKeys, best, origins);
        }
    }

    private static void addFuzzy(DecodeResult r, int mappedConsumed,
                                 FuzzyVariant variant, int unitsOffset, boolean restoreUnits,
                                 Set<String> exactKeys, Map<String, DecodeResult> best,
                                 Map<String, FuzzyOrigin> origins) {
        if (r == null || r.text == null || r.text.isEmpty() || mappedConsumed <= 0) return;
        String key = r.text + "\u0001" + mappedConsumed;
        if (exactKeys.contains(key)) return;
        DecodeResult candidate = new DecodeResult(r.text, restoreUnits ? variant.restoreUnits(r.units, unitsOffset) : r.units, mappedConsumed,
                r.tokenIds, r.score - variant.penalty, 1, variant.penalty);
        DecodeResult old = best.get(key);
        if (old == null || candidate.score > old.score) {
            best.put(key, candidate);
            origins.put(key, new FuzzyOrigin(variant, unitsOffset));
        }
    }

    private static final class FuzzyOrigin {
        final FuzzyVariant variant;
        final int offset;
        FuzzyOrigin(FuzzyVariant variant, int offset) {
            this.variant = variant;
            this.offset = offset;
        }
    }

    /** Replace an exact candidate that ended inside the fuzzy syllable with
     * its full-input fuzzy counterpart, preserving its original list position. */
    private static DecodeResult[] mergeExactAndFuzzy(DecodeResult[] exact,
                                                       List<DecodeResult> fuzzy,
                                                       Map<String, FuzzyOrigin> origins) {
        if (fuzzy.isEmpty()) return exact;
        Map<String, DecodeResult> replacements = new HashMap<>();
        Set<String> used = new HashSet<>();
        for (DecodeResult f : fuzzy) {
            FuzzyOrigin origin = origins.get(f.text + "\u0001" + f.consumed);
            if (origin == null) continue;
            for (DecodeResult e : exact) {
                if (e.text.equals(f.text) && e.consumed < f.consumed
                        && origin.variant.consumesPartialSource(e.consumed, origin.offset)) {
                    replacements.put(e.text + "\u0001" + e.consumed, f);
                    used.add(f.text + "\u0001" + f.consumed);
                    break;
                }
            }
        }
        List<DecodeResult> out = new ArrayList<>(exact.length + fuzzy.size());
        for (DecodeResult e : exact) {
            DecodeResult replacement = replacements.get(e.text + "\u0001" + e.consumed);
            out.add(replacement == null ? e : replacement);
        }
        int appended = 0;
        for (DecodeResult f : fuzzy) {
            if (!used.contains(f.text + "\u0001" + f.consumed)) {
                if (appended >= MAX_FUZZY_RESULTS) break;
                out.add(f);
                appended++;
            }
        }
        return out.toArray(new DecodeResult[0]);
    }

    private static Set<String> keys(DecodeResult[] results) {
        Set<String> out = new HashSet<>();
        for (DecodeResult r : results) {
            if (r != null && r.text != null) out.add(r.text + "\u0001" + r.consumed);
        }
        return out;
    }

    private static DecodeResult[] safe(DecodeResult[] results) {
        return results == null ? new DecodeResult[0] : results;
    }

    @Override public DecodeResult[] nextTokens(int[] contextIds, int limit, boolean enOnly) {
        return safe(base.nextTokens(contextIds, limit, enOnly));
    }
    @Override public DecodeResult[] getTokens(String prefix, int limit, boolean enOnly) {
        return safe(base.getTokens(prefix, limit, enOnly));
    }
    @Override public String[] t9PinyinSyllables(String digits, int limit) {
        String[] r = base.t9PinyinSyllables(digits, limit);
        return r == null ? new String[0] : r;
    }
    @Override public int contextSize() { return base.contextSize(); }
    @Override public void learnUserSentence(int[] context, int[] tokens) {
        base.learnUserSentence(context, tokens);
    }
    @Override public void flushUserSentence() { base.flushUserSentence(); }
    @Override public void onUserCommit(String text) { base.onUserCommit(text); }
    @Override public String bestNumUnits(String digits) { return base.bestNumUnits(digits); }
}
