package com.buildx.ai.call

/**
 * Follows a call the assistant placed and says what happened when it ends.
 * Android only tells us IDLE / RINGING / OFFHOOK. OFFHOOK covers both "dialing" and "talking", so the
 * spoken summary is based on how long the call lasted, not on whether the other person picked up.
 */
class CallTracker {
    companion object {
        const val IDLE = 0
        const val RINGING = 1
        const val OFFHOOK = 2
        const val NOT_STARTED_AFTER_MS = 12_000L
        const val TOO_SHORT_SEC = 4L
        private const val NONE = -1L

        /** the one shared by the service and the call action */
        val shared = CallTracker()
    }

    /** set by the service: speaks a sentence (used for "the call never started") */
    @Volatile
    var announce: ((String) -> Unit)? = null

    private var label: String? = null
    private var bn = true
    private var placedAt = 0L
    private var offhookAt = NONE

    @Synchronized
    fun expectOutgoing(label: String, bn: Boolean, now: Long) {
        this.label = label
        this.bn = bn
        placedAt = now
        offhookAt = NONE
    }

    /** returns a sentence to speak when a call we placed has just ended, else null */
    @Synchronized
    fun onState(state: Int, now: Long): String? {
        val l = label ?: return null
        if (state == OFFHOOK && offhookAt == NONE) offhookAt = now
        if (state == IDLE && offhookAt != NONE) {
            val sec = (now - offhookAt) / 1000
            val b = bn
            reset()
            return summary(l, sec, b)
        }
        return null
    }

    /** the call never started (permission, SIM dialog, blocked)? returns a sentence once, else null */
    @Synchronized
    fun checkNotStarted(now: Long): String? {
        val l = label ?: return null
        if (offhookAt == NONE && now - placedAt >= NOT_STARTED_AFTER_MS) {
            val b = bn
            reset()
            return if (b) "$l কে কল শুরু হয়নি" else "The call to $l did not start"
        }
        return null
    }

    @Synchronized
    fun waiting(): Boolean = label != null

    private fun reset() {
        label = null
        placedAt = 0L
        offhookAt = NONE
    }

    private fun summary(label: String, sec: Long, bn: Boolean): String {
        if (sec < TOO_SHORT_SEC) {
            return if (bn) "$label কে কল লাগেনি, বা সাথে সাথে কেটে গেছে" else "The call to $label failed or ended right away"
        }
        val m = sec / 60
        val s = sec % 60
        return if (bn) {
            val parts = ArrayList<String>()
            if (m > 0) parts.add("${digits(m)} মিনিট")
            parts.add("${digits(s)} সেকেন্ড")
            "কল শেষ। মোট ${parts.joinToString(" ")}"
        } else {
            "Call ended. ${if (m > 0) "$m min " else ""}$s sec"
        }
    }

    private fun digits(n: Long): String = n.toString().map { '০' + (it - '0') }.joinToString("")
}
