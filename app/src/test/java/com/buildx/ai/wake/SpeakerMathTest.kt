package com.buildx.ai.wake

import org.junit.Assert
import org.junit.Test

class SpeakerMathTest {
    private fun v(vararg d: Double) = doubleArrayOf(*d)

    @Test fun identicalDirectionIsZero() = Assert.assertTrue(SpeakerMath.cosineDistance(v(1.0, 2.0, 3.0), v(2.0, 4.0, 6.0)) < 1e-9)
    @Test fun orthogonalIsOne() = Assert.assertTrue(Math.abs(SpeakerMath.cosineDistance(v(1.0, 0.0), v(0.0, 1.0)) - 1.0) < 1e-9)
    @Test fun oppositeIsTwo() = Assert.assertTrue(Math.abs(SpeakerMath.cosineDistance(v(1.0, 0.0), v(-1.0, 0.0)) - 2.0) < 1e-9)
    @Test fun mismatchedOrEmptyIsFarAway() {
        Assert.assertEquals(2.0, SpeakerMath.cosineDistance(v(1.0), v(1.0, 2.0)), 1e-9)
        Assert.assertEquals(2.0, SpeakerMath.cosineDistance(v(0.0, 0.0), v(1.0, 1.0)), 1e-9)
    }
    @Test fun meanIsBetweenItsSamples() {
        val m = SpeakerMath.mean(listOf(v(1.0, 0.0), v(0.0, 1.0)))!!
        Assert.assertTrue(Math.abs(m[0] - m[1]) < 1e-9)
        Assert.assertTrue(SpeakerMath.cosineDistance(m, v(1.0, 0.0)) < 0.3)
    }
    @Test fun loudSamplesDoNotDominate() {
        val m = SpeakerMath.mean(listOf(v(100.0, 0.0), v(0.0, 1.0)))!!
        Assert.assertTrue(Math.abs(m[0] - m[1]) < 1e-9)
    }
    @Test fun meanOfNothingOrMixedSizes() {
        Assert.assertNull(SpeakerMath.mean(emptyList()))
        Assert.assertNull(SpeakerMath.mean(listOf(v(1.0), v(1.0, 2.0))))
    }
    @Test fun encodeDecodeRoundTrip() {
        val a = v(0.12345, -0.5, 3.0)
        val b = SpeakerMath.decode(SpeakerMath.encode(a))!!
        Assert.assertEquals(3, b.size)
        for (i in a.indices) Assert.assertTrue(Math.abs(a[i] - b[i]) < 1e-5)
    }
    @Test fun decodeRejectsGarbage() {
        Assert.assertNull(SpeakerMath.decode(null))
        Assert.assertNull(SpeakerMath.decode(""))
        Assert.assertNull(SpeakerMath.decode("1,2,abc"))
    }
    @Test fun calibrationFollowsTheSpreadOfTheSamples() {
        val tight = listOf(v(1.0, 0.0), v(1.0, 0.01), v(1.0, -0.01))
        val loose = listOf(v(1.0, 0.0), v(1.0, 3.0), v(1.0, -3.0))
        val tightBase = SpeakerMath.calibratedBase(tight, SpeakerMath.mean(tight)!!)
        val looseBase = SpeakerMath.calibratedBase(loose, SpeakerMath.mean(loose)!!)
        Assert.assertTrue(tightBase < looseBase)
        Assert.assertTrue(tightBase >= 0.40 && looseBase <= 0.60)
        Assert.assertEquals(SpeakerMath.DEFAULT_BASE, SpeakerMath.calibratedBase(emptyList(), v(1.0)), 1e-9)
    }
    @Test fun strictnessMovesTheLimit() {
        Assert.assertTrue(SpeakerMath.limit(0.5, VoiceStrictness.STRICT) < SpeakerMath.limit(0.5, VoiceStrictness.NORMAL))
        Assert.assertTrue(SpeakerMath.limit(0.5, VoiceStrictness.NORMAL) < SpeakerMath.limit(0.5, VoiceStrictness.LOOSE))
    }
    @Test fun isMeUsesTheLimit() {
        val lock = VoiceLock(v(1.0, 0.0), 0.3)
        Assert.assertTrue(SpeakerMath.isMe(lock, v(1.0, 0.1)))
        Assert.assertTrue(!SpeakerMath.isMe(lock, v(0.2, 1.0)))
    }
}
