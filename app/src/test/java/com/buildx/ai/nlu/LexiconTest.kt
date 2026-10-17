package com.buildx.ai.nlu

import org.junit.Assert
import org.junit.Test

class LexiconTest {
    @Test fun noAliasBelongsToTwoMeanings() = Assert.assertEquals(emptyList<String>(), Lexicon.duplicates)

    @Test fun suffixTolerant() {
        Assert.assertEquals("flashlight", Lexicon.lookupWord(TextNormalizer.normalize("ফ্ল্যাশলাইটটা")))
        Assert.assertEquals("phone", Lexicon.lookupWord(TextNormalizer.normalize("ফোনে")))
    }
    @Test fun namesAreNotSwallowed() {
        Assert.assertNull(Lexicon.lookupWord(TextNormalizer.normalize("রহিমকে")))
        Assert.assertNull(Lexicon.lookupWord(TextNormalizer.normalize("বাবাকে")))
    }
    @Test fun typoInLongLatinWord() {
        Assert.assertEquals("flashlight", Lexicon.fuzzyWord("flashligt"))
        Assert.assertEquals("bluetooth", Lexicon.fuzzyWord("bluetoth"))
    }
    @Test fun shortWordsAreNeverFuzzy() = Assert.assertNull(Lexicon.fuzzyWord("rahim"))
    @Test fun fuzzyDistance() {
        Assert.assertEquals(0, Fuzzy.distance("abc", "abc"))
        Assert.assertEquals(1, Fuzzy.distance("abc", "abd"))
        Assert.assertEquals(3, Fuzzy.distance("", "abc"))
        Assert.assertEquals("karim", Fuzzy.closest("karem", listOf("rahim", "karim"), 1))
        Assert.assertNull(Fuzzy.closest("zzz", listOf("rahim"), 1))
    }
}
