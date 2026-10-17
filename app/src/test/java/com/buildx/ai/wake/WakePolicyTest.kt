package com.buildx.ai.wake

import com.buildx.ai.wake.WakeSensitivity.HIGH
import com.buildx.ai.wake.WakeSensitivity.LOW
import com.buildx.ai.wake.WakeSensitivity.NORMAL
import org.junit.Assert
import org.junit.Test

class WakePolicyTest {
    private fun fire(l: WakeSensitivity, final: Boolean, text: String, conf: Double? = null) = WakePolicy.shouldFire(l, final, text, conf)

    @Test fun normalFiresOnPartialWithAllThreeWords() = Assert.assertTrue(fire(NORMAL, false, "hey build x"))
    @Test fun normalIgnoresPartialWithTwoWords() = Assert.assertTrue(!fire(NORMAL, false, "hey build"))
    @Test fun normalFiresOnFinalWithTwoWords() = Assert.assertTrue(fire(NORMAL, true, "hey build"))
    @Test fun normalIgnoresOtherSpeech() {
        Assert.assertTrue(!fire(NORMAL, false, "hey bill"))
        Assert.assertTrue(!fire(NORMAL, true, "[unk]"))
    }
    @Test fun highFiresEvenOnHeyBuildAlone() = Assert.assertTrue(fire(HIGH, false, "hey build"))
    @Test fun highStillIgnoresOtherSpeech() = Assert.assertTrue(!fire(HIGH, false, "hey there"))
    @Test fun lowIgnoresPartials() = Assert.assertTrue(!fire(LOW, false, "hey build x"))
    @Test fun lowNeedsAllThreeWords() = Assert.assertTrue(!fire(LOW, true, "hey build", 0.95))
    @Test fun lowNeedsConfidence() {
        Assert.assertTrue(!fire(LOW, true, "hey build x", 0.5))
        Assert.assertTrue(fire(LOW, true, "hey build x", 0.9))
    }
    @Test fun lowWithoutConfidenceNumbersStillWorks() = Assert.assertTrue(fire(LOW, true, "hey build ex", null))
    @Test fun fromIndexIsSafe() {
        Assert.assertEquals(LOW, WakeSensitivity.from(0))
        Assert.assertEquals(HIGH, WakeSensitivity.from(2))
        Assert.assertEquals(NORMAL, WakeSensitivity.from(9))
    }
}
