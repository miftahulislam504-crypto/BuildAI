package com.buildx.ai.wake

import kotlin.math.sqrt

/** How strictly a voice must match the enrolled one. Smaller distance = more alike. */
enum class VoiceStrictness(val label: String, val offset: Double) {
    STRICT("কড়া (অন্য কেউ সহজে পারবে না, নিজের কণ্ঠও মাঝে মাঝে ফিরে যেতে পারে)", -0.07),
    NORMAL("মাঝারি", 0.0),
    LOOSE("ঢিলা (নিজের কণ্ঠ প্রায় সবসময় ধরে, অন্যের ঢোকার সুযোগ বেশি)", 0.07);

    companion object {
        fun from(i: Int): VoiceStrictness = values().getOrElse(i) { NORMAL }
    }
}

/** What to compare a new voice against, and how far it may be. */
class VoiceLock(val print: DoubleArray, val maxDistance: Double)

/** Voice-print arithmetic (Vosk gives a 128-number vector per utterance). Pure Kotlin. */
object SpeakerMath {
    const val DEFAULT_BASE = 0.50

    /** 1 - cosine similarity: 0 = same direction, 1 = unrelated, 2 = opposite */
    fun cosineDistance(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size || a.isEmpty()) return 2.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 2.0
        return 1.0 - dot / (sqrt(na) * sqrt(nb))
    }

    private fun unit(v: DoubleArray): DoubleArray {
        val n = sqrt(v.sumOf { it * it })
        return if (n == 0.0) v else DoubleArray(v.size) { v[it] / n }
    }

    /** the average direction of several samples (each is normalized first so loud ones do not dominate) */
    fun mean(vs: List<DoubleArray>): DoubleArray? {
        if (vs.isEmpty() || vs.any { it.size != vs[0].size }) return null
        val sum = DoubleArray(vs[0].size)
        for (v in vs) {
            val u = unit(v)
            for (i in u.indices) sum[i] += u[i]
        }
        return unit(sum)
    }

    fun encode(v: DoubleArray): String = v.joinToString(",") { "%.5f".format(java.util.Locale.US, it) }

    fun decode(s: String?): DoubleArray? {
        if (s.isNullOrBlank()) return null
        return try {
            s.split(',').map { it.trim().toDouble() }.toDoubleArray().takeIf { it.isNotEmpty() }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * The distance limit for this person: how far their own samples spread from their average, plus a margin,
     * kept between 0.40 and 0.60. (A person who says "hey build" very evenly gets a tighter limit.)
     */
    fun calibratedBase(samples: List<DoubleArray>, mean: DoubleArray): Double {
        val worst = samples.maxOfOrNull { cosineDistance(it, mean) } ?: return DEFAULT_BASE
        return (worst + 0.15).coerceIn(0.40, 0.60)
    }

    fun limit(base: Double, strictness: VoiceStrictness): Double = (base + strictness.offset).coerceIn(0.2, 0.8)

    fun isMe(lock: VoiceLock, sample: DoubleArray): Boolean = cosineDistance(lock.print, sample) <= lock.maxDistance
}
