package com.buildx.ai.system

import kotlin.math.roundToInt

/** Volume arithmetic on Android's integer steps (usually 0..15 for media). Pure Kotlin. */
object VolumeMath {
    /** "ভলিউম বাড়াও" without a number moves by this much of the full range */
    const val STEP_PERCENT = 20

    fun percentToIndex(percent: Int, max: Int): Int =
        (percent.coerceIn(0, 100) / 100.0 * max).roundToInt().coerceIn(0, max)

    fun indexToPercent(index: Int, max: Int): Int =
        if (max <= 0) 0 else (index * 100.0 / max).roundToInt().coerceIn(0, 100)

    /**
     * New volume step. [action]: up, down, set. For up/down [delta] is a percentage of the full range
     * (null = [STEP_PERCENT]); the volume always moves at least one step unless it is already at the end.
     * For set, [level] is the percentage.
     */
    fun target(current: Int, max: Int, action: String, level: Int?, delta: Int?): Int = when (action) {
        "set" -> percentToIndex(level ?: 50, max)
        "up" -> (current + maxOf(1, percentToIndex(delta ?: STEP_PERCENT, max))).coerceAtMost(max)
        "down" -> (current - maxOf(1, percentToIndex(delta ?: STEP_PERCENT, max))).coerceAtLeast(0)
        else -> current
    }

    /** what "unmute" goes back to: the volume before muting, or half if that is unknown or silent */
    fun restore(previous: Int, max: Int): Int = if (previous > 0) previous.coerceAtMost(max) else percentToIndex(50, max)
}
