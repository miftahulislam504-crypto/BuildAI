package com.buildx.ai.wake

/** How easily "Hey Build" fires. A false trigger costs a "জি, বলুন"; a missed one costs the person a repeat. */
enum class WakeSensitivity(val label: String) {
    LOW("Low (confirms at the end of speech, fewest false triggers)"),
    NORMAL("Medium (when you finish speaking)"),
    HIGH("High (catches it mid-sentence, may trigger falsely more often)");

    companion object {
        fun from(i: Int): WakeSensitivity = values().getOrElse(i) { NORMAL }
    }
}

/**
 * Decides from what the recognizer heard whether it was the wake phrase. The phrase is just "hey build"
 * ("Hey BuildX" also counts, the X is ignored). Pure, so the three levels can be tested.
 * [avgConf] is the average word confidence of a final result (null if the recognizer gave none).
 */
object WakePolicy {

    const val LOW_MIN_CONF = 0.8

    fun shouldFire(level: WakeSensitivity, isFinal: Boolean, text: String, avgConf: Double?, phrase: WakePhrase = WakePhrase.DEFAULT): Boolean {
        if (!phrase.matches(text)) return false
        return when (level) {
            WakeSensitivity.HIGH -> true // even while still talking
            WakeSensitivity.NORMAL -> isFinal
            WakeSensitivity.LOW -> isFinal && (avgConf == null || avgConf >= LOW_MIN_CONF)
        }
    }
}
