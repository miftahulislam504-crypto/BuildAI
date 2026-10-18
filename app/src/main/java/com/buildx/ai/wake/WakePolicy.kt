package com.buildx.ai.wake

/** How easily "Hey BuildX" fires. A false trigger costs a "জি, বলুন"; a missed one costs the person a repeat. */
enum class WakeSensitivity(val label: String) {
    LOW("কম (ভুল ট্রিগার কম, মাঝে মাঝে ধরবে না)"),
    NORMAL("মাঝারি"),
    HIGH("বেশি (সহজে ধরে, ভুল ট্রিগার বাড়তে পারে)");

    companion object {
        fun from(i: Int): WakeSensitivity = values().getOrElse(i) { NORMAL }
    }
}

/**
 * Decides from what the recognizer heard whether it was the wake phrase. Pure, so the three levels can be tested.
 * [avgConf] is the average word confidence of a final result (null if the recognizer gave none).
 */
object WakePolicy {

    private val full = Regex("hey build (x|ex)")
    private val short = Regex("hey build")
    const val LOW_MIN_CONF = 0.8

    fun shouldFire(level: WakeSensitivity, isFinal: Boolean, text: String, avgConf: Double?): Boolean = when (level) {
        // only a finished utterance that clearly says all three words, and says them confidently
        WakeSensitivity.LOW -> isFinal && full.containsMatchIn(text) && (avgConf == null || avgConf >= LOW_MIN_CONF)
        // early on the partial result if all three words are there; on the final result "hey build" is enough
        WakeSensitivity.NORMAL -> if (isFinal) short.containsMatchIn(text) else full.containsMatchIn(text)
        // even "hey build" while still talking
        WakeSensitivity.HIGH -> short.containsMatchIn(text)
    }
}
