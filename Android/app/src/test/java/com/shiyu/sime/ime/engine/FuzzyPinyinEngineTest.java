package com.shiyu.sime.ime.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FuzzyPinyinEngineTest {

    private static Set<String> values(List<FuzzyVariant> variants) {
        Set<String> out = new HashSet<>();
        for (FuzzyVariant variant : variants) out.add(variant.value);
        return out;
    }

    @Test
    public void expandsEnEngInsideCompleteSyllables() {
        assertTrue(values(FuzzyPinyinEngine.expandPinyin("henghao")).contains("henhao"));
        assertTrue(values(FuzzyPinyinEngine.expandPinyin("henhao")).contains("henghao"));
        assertTrue(values(FuzzyPinyinEngine.expandPinyin("engjiangchoubao"))
                .contains("enjiangchoubao"));
    }

    @Test
    public void expandsInIngInsideCompleteSyllables() {
        Set<String> variants = values(FuzzyPinyinEngine.expandPinyin("yinyulaoshi"));
        assertTrue(variants.contains("yingyulaoshi"));
        assertFalse(variants.contains("yinyuelaoshi"));
        assertTrue(values(FuzzyPinyinEngine.expandPinyin("yingyu")).contains("yinyu"));
    }

    @Test
    public void doesNotApplyRulesToIllegalOrIncompleteSegmentation() {
        assertTrue(FuzzyPinyinEngine.expandPinyin("notapinyin").isEmpty());
        assertTrue(values(FuzzyPinyinEngine.expandPinyin("hengh")).contains("henh"));
        assertTrue(values(FuzzyPinyinEngine.expandPinyin("hengx")).contains("henx"));
    }

    @Test
    public void preservesExplicitSyllableSeparators() {
        assertTrue(values(FuzzyPinyinEngine.expandPinyin("heng'hao")).contains("hen'hao"));
    }

    @Test
    public void mapsT9VariantBackToOriginalDigitLength() {
        List<FuzzyVariant> variants = FuzzyPinyinEngine.expandT9("4364426");
        FuzzyVariant match = null;
        for (FuzzyVariant v : variants) if (v.value.equals("436426")) match = v;
        assertTrue(match != null);
        assertEquals(7, match.mapConsumed(6));
        assertEquals(4, match.mapConsumed(3));
        assertEquals(-1, match.mapConsumed(2));
    }

    @Test
    public void doesNotExpandAcrossExplicitT9SyllableBoundary() {
        assertFalse(values(FuzzyPinyinEngine.expandT9("4364'426")).isEmpty());
    }
}
