package com.buildx.ai.wake

import com.buildx.ai.wake.WakeSensitivity.HIGH
import com.buildx.ai.wake.WakeSensitivity.LOW
import com.buildx.ai.wake.WakeSensitivity.NORMAL
import org.junit.Assert
import org.junit.Test

class WakePolicyTest {
    private fun fire(l: WakeSensitivity, final: Boolean, text: String, conf: Double? = null) = WakePolicy.shouldFire(l, final, text, conf)

    @Test fun heyBuildIsEnough() {
        Assert.assertTrue(fire(NORMAL, true, "hey build"))
        Assert.assertTrue(fire(HIGH, false, "hey build"))
        Assert.assertTrue(fire(LOW, true, "hey build", 0.95))
    }
    @Test fun theXIsIgnored() {
        Assert.assertTrue(fire(NORMAL, true, "hey build x"))
        Assert.assertTrue(fire(NORMAL, true, "hey build ex"))
        Assert.assertTrue(fire(HIGH, false, "hey build x"))
    }
    @Test fun normalWaitsForTheEndOfTheUtterance() = Assert.assertTrue(!fire(NORMAL, false, "hey build"))
    @Test fun highFiresWhileStillTalking() = Assert.assertTrue(fire(HIGH, false, "hey build"))
    @Test fun otherSpeechNeverFires() {
        for (l in WakeSensitivity.values()) {
            Assert.assertTrue(!fire(l, true, "hey bill"))
            Assert.assertTrue(!fire(l, true, "[unk]"))
            Assert.assertTrue(!fire(l, false, "hey there"))
        }
    }
    @Test fun lowIgnoresPartials() = Assert.assertTrue(!fire(LOW, false, "hey build"))
    @Test fun lowNeedsConfidence() {
        Assert.assertTrue(!fire(LOW, true, "hey build", 0.5))
        Assert.assertTrue(fire(LOW, true, "hey build", 0.9))
    }
    @Test fun lowWithoutConfidenceNumbersStillWorks() = Assert.assertTrue(fire(LOW, true, "hey build", null))
    @Test fun fromIndexIsSafe() {
        Assert.assertEquals(LOW, WakeSensitivity.from(0))
        Assert.assertEquals(HIGH, WakeSensitivity.from(2))
        Assert.assertEquals(NORMAL, WakeSensitivity.from(9))
    }
}
