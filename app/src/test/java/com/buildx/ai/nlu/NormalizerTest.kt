package com.buildx.ai.nlu

import org.junit.Assert
import org.junit.Test

class NormalizerTest {
    private fun n(s: String) = TextNormalizer.normalize(s)

    @Test fun banglaDigitsBecomeAscii() = Assert.assertEquals("01711223344", n("০১৭১১২২৩৩৪৪"))
    @Test fun punctuationBecomesSpace() = Assert.assertEquals("এখন কয়টা বাজে", n("  এখন, কয়টা বাজে? ।"))
    @Test fun lowerCase() = Assert.assertEquals("call rahim", n("Call RAHIM"))
    @Test fun clockColon() = Assert.assertEquals("6টা 30 pm", n("6:30pm"))
    @Test fun banglaClockColon() = Assert.assertEquals("6টা 30", n("৬:৩০"))
    @Test fun digitLetterSplit() = Assert.assertEquals("7 am", n("7am"))
    @Test fun banglaSuffixStaysAttached() = Assert.assertEquals("6টায় অ্যালার্ম", n("৬টায় অ্যালার্ম"))
    @Test fun percent() = Assert.assertEquals("volume 50 percent", n("volume 50%"))
    @Test fun apostropheRemoved() = Assert.assertEquals("6 oclock", n("6 o'clock"))
    @Test fun hyphenSplits() = Assert.assertEquals("wi fi on", n("Wi-Fi on"))
    @Test fun zeroWidthJoinerRemoved() = Assert.assertEquals(n("র্যাপ"), n("র\u200d্যাপ"))
    @Test fun nukta() {
        // য় as one code point (U+09DF) and as য + nukta must come out identical
        Assert.assertEquals(n("\u09DF"), n("\u09AF\u09BC"))
        Assert.assertEquals(n("ছ\u09DF"), n("ছ\u09AF\u09BC"))
    }
    @Test fun plusKeptForInternationalNumbers() = Assert.assertEquals("+8801711223344", n("+8801711223344"))
    @Test fun emptyStaysEmpty() = Assert.assertEquals("", n("   "))
}
