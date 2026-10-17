package com.buildx.ai.nlu

import org.junit.Assert
import org.junit.Test

class NumbersTest {
    private fun num(s: String) = Numbers.parse(TextNormalizer.normalize(s))

    @Test fun six() = Assert.assertEquals("6", num("ছয়"))
    @Test fun twentyFive() = Assert.assertEquals("25", num("পঁচিশ"))
    @Test fun fifty() = Assert.assertEquals("50", num("পঞ্চাশ"))
    @Test fun ninetyNine() = Assert.assertEquals("99", num("নিরানব্বই"))
    @Test fun hundred() = Assert.assertEquals("100", num("একশ"))
    @Test fun zero() = Assert.assertEquals("0", num("শূন্য"))
    @Test fun sixWithTa() = Assert.assertEquals("6" + TextNormalizer.nfc("টা"), num("ছয়টা"))
    @Test fun sixWithTay() = Assert.assertEquals("6" + TextNormalizer.nfc("টায়"), num("ছয়টায়"))
    @Test fun contractedTwo() = Assert.assertEquals("2" + TextNormalizer.nfc("টো"), num("দুটো"))
    @Test fun contractedNine() = Assert.assertEquals("9" + TextNormalizer.nfc("টায়"), num("নটায়"))
    @Test fun contractedSix() = Assert.assertEquals("6" + TextNormalizer.nfc("টা"), num("ছটা"))
    @Test fun contractedOnlyWithSuffix() = Assert.assertNull(num("ন"))
    @Test fun notANumber() = Assert.assertNull(num("রহিম"))
    @Test fun englishSix() = Assert.assertEquals("6", num("six"))
    @Test fun englishTwentyFiveInSentence() {
        val t = Canonicalizer.tokenize("volume twenty five")
        Assert.assertEquals(listOf("volume", "25"), t.map { it.canon })
    }
    @Test fun spokenDigitsOneByOne() {
        val t = Canonicalizer.tokenize(TextNormalizer.normalize("শূন্য এক সাত এক"))
        Assert.assertEquals(listOf("0", "1", "7", "1"), t.map { it.canon })
    }
    @Test fun hourSuffixMerged() {
        val t = Canonicalizer.tokenize(TextNormalizer.normalize("৬ টায়"))
        Assert.assertEquals(listOf("6" + TextNormalizer.nfc("টায়")), t.map { it.canon })
    }
    @Test fun oclockMerged() {
        val t = Canonicalizer.tokenize(TextNormalizer.normalize("6 o'clock"))
        Assert.assertEquals(listOf("6" + TextNormalizer.nfc("টা")), t.map { it.canon })
    }
}
