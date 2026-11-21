package com.shiyu.sime.ime.engine;

/** One single-substitution variant and its offset mapping back to typed input. */
public final class FuzzyVariant {
    public final String value;
    public final String ruleId;
    public final float penalty;
    private final int sourceStart;
    private final int sourceEnd;
    private final int targetEnd;

    FuzzyVariant(String original, int sourceStart, int sourceEnd,
                 String replacement, String ruleId, float penalty) {
        this.value = original.substring(0, sourceStart) + replacement
                + original.substring(sourceEnd);
        this.sourceStart = sourceStart;
        this.sourceEnd = sourceEnd;
        this.targetEnd = sourceStart + replacement.length();
        this.ruleId = ruleId;
        this.penalty = penalty;
    }

    /**
     * Maps a consumed offset in the variant back to the user's original input.
     * Offsets inside the substituted syllable are rejected: a partial match of
     * a transformed syllable must not be reported as a valid original prefix.
     */
    public int mapConsumed(int variantConsumed) {
        if (variantConsumed < 0) return -1;
        if (variantConsumed <= sourceStart) return variantConsumed;
        if (variantConsumed < targetEnd) return -1;
        if (variantConsumed == targetEnd) return sourceEnd;
        return variantConsumed + (sourceEnd - targetEnd);
    }
}
