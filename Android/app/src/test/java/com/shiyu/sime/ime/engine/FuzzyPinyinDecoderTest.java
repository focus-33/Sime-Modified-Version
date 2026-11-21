package com.shiyu.sime.ime.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

public class FuzzyPinyinDecoderTest {

    private static class StubDecoder implements Decoder {
        @Override public DecodeResult[] decodeSentence(String pinyin, int limit) {
            if ("henghao".equals(pinyin)) {
                return new DecodeResult[] {new DecodeResult("哼好", "heng'hao", 7,
                        new int[] {1}, -2f)};
            }
            if ("henhao".equals(pinyin)) {
                return new DecodeResult[] {new DecodeResult("很好", "hen'hao", 6,
                        new int[] {2}, -1f)};
            }
            return new DecodeResult[0];
        }

        @Override public DecodeResult[] decodeNumSentence(String start, String digits, int limit) {
            if ("".equals(start) && "4364426".equals(digits)) {
                return new DecodeResult[0];
            }
            if ("".equals(start) && "436426".equals(digits)) {
                return new DecodeResult[] {new DecodeResult("很好", "hen'hao", 6,
                        new int[] {2}, -1f)};
            }
            if ("heng".equals(start) && (digits.isEmpty() || "426".equals(digits))) {
                return new DecodeResult[0];
            }
            if ("hen".equals(start) && digits.isEmpty()) {
                return new DecodeResult[] {new DecodeResult("很", "hen", 3,
                        new int[] {3}, -1f)};
            }
            if ("hen".equals(start) && "426".equals(digits)) {
                return new DecodeResult[] {new DecodeResult("很好", "hen'hao", 6,
                        new int[] {4}, -1f)};
            }
            if ("wo".equals(start) && "4364426".equals(digits)) {
                return new DecodeResult[0];
            }
            if ("wo".equals(start) && "436426".equals(digits)) {
                return new DecodeResult[] {new DecodeResult("很好", "hen'hao", 8,
                        new int[] {5}, -1f)};
            }
            return new DecodeResult[0];
        }

        @Override public DecodeResult[] nextTokens(int[] contextIds, int limit, boolean enOnly) {
            return new DecodeResult[0];
        }
        @Override public DecodeResult[] getTokens(String prefix, int limit, boolean enOnly) {
            return new DecodeResult[0];
        }
        @Override public String[] t9PinyinSyllables(String digits, int limit) {
            return new String[0];
        }
        @Override public int contextSize() { return 0; }
    }

    @Test
    public void exactResultsRemainAheadOfFuzzyResults() {
        DecodeResult[] results = new FuzzyPinyinDecoder(new StubDecoder())
                .decodeSentence("henghao", 4);
        assertEquals("哼好", results[0].text);
        assertEquals("很好", results[1].text);
        assertEquals(7, results[1].consumed);
        assertEquals(1, results[1].fuzzyMatchCount);
        assertTrue(results[1].score < -1f);
    }

    @Test
    public void t9FuzzyResultUsesOriginalDigitConsumedLength() {
        DecodeResult[] results = new FuzzyPinyinDecoder(new StubDecoder())
                .decodeNumSentence("", "4364426", 4);
        assertEquals(1, results.length);
        assertEquals("很好", results[0].text);
        assertEquals(7, results[0].consumed);
        assertEquals(1, results[0].fuzzyMatchCount);
    }

    @Test
    public void explicitT9SyllablePrefixCanUseFuzzyRule() {
        DecodeResult[] results = new FuzzyPinyinDecoder(new StubDecoder())
                .decodeNumSentence("heng", "", 4);
        assertEquals(1, results.length);
        assertEquals("很", results[0].text);
        assertEquals(4, results[0].consumed);
    }

    @Test
    public void t9DigitVariantPreservesAlreadySelectedLetterPrefix() {
        DecodeResult[] results = new FuzzyPinyinDecoder(new StubDecoder())
                .decodeNumSentence("wo", "4364426", 4);
        assertEquals(1, results.length);
        assertEquals("很好", results[0].text);
        assertEquals(9, results[0].consumed);
    }

    @Test
    public void selectedT9SyllableVariantMapsCombinedConsumedLength() {
        DecodeResult[] results = new FuzzyPinyinDecoder(new StubDecoder())
                .decodeNumSentence("heng", "426", 4);
        assertEquals(1, results.length);
        assertEquals("很好", results[0].text);
        assertEquals(7, results[0].consumed);
    }
    private static final class PartialYingDecoder extends StubDecoder {
        @Override public DecodeResult[] decodeSentence(String pinyin, int limit) {
            if ("yuanyingshiyingwei".equals(pinyin)
                    || "yuanyinshiyingwei".equals(pinyin)) {
                return new DecodeResult[] {new DecodeResult("原因", "yuan'yin", 7,
                        new int[] {9}, -1f)};
            }
            return super.decodeSentence(pinyin, limit);
        }
    }

    @Test
    public void fuzzyCandidateReplacesExactResultEndingInsideYing() {
        DecodeResult[] results = new FuzzyPinyinDecoder(new PartialYingDecoder())
                .decodeSentence("yuanyingshiyingwei", 16);
        assertEquals("原因", results[0].text);
        assertEquals(8, results[0].consumed);
        assertEquals("yuan'ying", results[0].units);
        int count = 0;
        for (DecodeResult result : results) if ("原因".equals(result.text)) count++;
        assertEquals(1, count);
    }

    private static final class PartialT9YingDecoder extends StubDecoder {
        @Override public DecodeResult[] decodeNumSentence(String start, String digits, int limit) {
            if (start.isEmpty() && ("98269464".equals(digits) || "9826946".equals(digits))) {
                return new DecodeResult[] {new DecodeResult("原因", "yuan'yin", 7,
                        new int[] {10}, -1f)};
            }
            return super.decodeNumSentence(start, digits, limit);
        }
    }

    @Test
    public void t9FuzzyCandidateReplacesExactResultEndingInsideYing() {
        DecodeResult[] results = new FuzzyPinyinDecoder(new PartialT9YingDecoder())
                .decodeNumSentence("", "98269464", 16);
        assertEquals("原因", results[0].text);
        assertEquals(8, results[0].consumed);
        int count = 0;
        for (DecodeResult result : results) if ("原因".equals(result.text)) count++;
        assertEquals(1, count);
    }

    private static final class BecauseDecoder extends StubDecoder {
        @Override public DecodeResult[] decodeSentence(String pinyin, int limit) {
            if ("yingwei".equals(pinyin)) {
                return new DecodeResult[] {new DecodeResult("应为", "ying'wei", 7,
                        new int[] {11}, -1f)};
            }
            if ("yinwei".equals(pinyin)) {
                return new DecodeResult[] {new DecodeResult("因为", "yin'wei", 6,
                        new int[] {12}, -1f)};
            }
            return super.decodeSentence(pinyin, limit);
        }
    }

    @Test
    public void fuzzyYingWeiRetrievesBecauseAndRestoresTypedUnits() {
        DecodeResult[] results = new FuzzyPinyinDecoder(new BecauseDecoder())
                .decodeSentence("yingwei", 16);
        DecodeResult because = null;
        for (DecodeResult result : results) {
            if ("因为".equals(result.text)) because = result;
        }
        assertTrue(because != null);
        assertEquals(7, because.consumed);
        assertEquals("ying'wei", because.units);
    }

    @Test
    public void restoredFuzzyUnitsSurviveTunedDecoderFiltering() {
        Decoder pipeline = new TunedDecoder(new FuzzyPinyinDecoder(new BecauseDecoder()),
                new CandidateTuning(new File("/tmp/sime-fuzzy-test-rules")));
        DecodeResult because = null;
        for (DecodeResult result : pipeline.decodeSentence("yingwei", 16)) {
            if ("因为".equals(result.text)) because = result;
        }
        assertTrue(because != null);
        assertEquals(7, because.consumed);
        assertEquals("ying'wei", because.units);
    }

}
