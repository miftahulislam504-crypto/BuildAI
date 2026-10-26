package com.buildx.ai.assistant

import android.os.Handler
import android.os.Looper

/**
 * The few lines shown in the assistant popup. The conversation (service or session) posts lines here;
 * the popup, if it is on screen, draws them. Main thread only.
 */
object AssistantBridge {
    private const val MAX_LINES = 16
    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayList<String>()

    /** the popup, while it is showing */
    var session: BuildXSession? = null
    var render: ((List<String>) -> Unit)? = null

    /** the lines so far, for a popup that has just appeared */
    fun current(): List<String> = lines.toList()

    fun clear() {
        lines.clear()
        render?.invoke(lines.toList())
    }

    fun post(line: String) {
        main.post {
            lines.add(line)
            while (lines.size > MAX_LINES) lines.removeAt(0)
            render?.invoke(lines.toList())
        }
    }

    /** close the popup after [ms] so the last line can be read */
    fun hideLater(ms: Long) {
        main.postDelayed({ session?.hide() }, ms)
    }
}
