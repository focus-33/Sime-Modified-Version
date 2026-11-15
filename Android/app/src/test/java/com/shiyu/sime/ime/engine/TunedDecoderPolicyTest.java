package com.shiyu.sime.ime.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TunedDecoderPolicyTest {

    private static DecodeResult r(String text, String units, int consumed, float score) {
        return new DecodeResult(text, units, consumed, new int[] {1}, score);
    }

    private static List<String> texts(List<DecodeResult> list) {
        List<String> out = new ArrayList<>();
        for (DecodeResult c : list) out.add(c.text);
        return out;
    }

    @Test
    public void longSentenceKeepsOneWholeCandidateThenFirstWord() {
        List<DecodeResult> in = Arrays.asList(
                r("我要去火车站", "wo'yao'qu'huo'che'zhan", 17, -20f),
                r("我先去火车站", "wo'xian'qu'huo'che'zhan", 17, -21f),
                r("我要求火车站", "wo'yao'qiu'huo'che'zhan", 17, -22f),
                r("我", "wo", 2, -8f),
                r("我要", "wo'yao", 5, -10f),
                r("我要去", "wo'yao'qu", 7, -12f));

        assertEquals(Arrays.asList("我要去火车站", "我要去", "我", "我要"),
                texts(TunedDecoder.limitLongSentences(in)));
    }

    @Test
    public void fourSyllableInputKeepsTwoWholeCandidates() {
        List<DecodeResult> in = Arrays.asList(
                r("一心一意", "yi'xin'yi'yi", 9, -12f),
                r("一心只想", "yi'xin'zhi'xiang", 9, -13f),
                r("一阴一阳", "yi'yin'yi'yang", 9, -14f),
                r("一心", "yi'xin", 5, -9f));

        assertEquals(Arrays.asList("一心一意", "一心只想", "一心"),
                texts(TunedDecoder.limitLongSentences(in)));
    }

    @Test
    public void shortInputIsUnchanged() {
        List<DecodeResult> in = Arrays.asList(
                r("你好", "ni'hao", 5, -8f),
                r("你号", "ni'hao", 5, -9f),
                r("尼", "ni", 2, -10f));

        assertEquals(texts(in), texts(TunedDecoder.limitLongSentences(in)));
    }
}
