package com.buildx.ai.voice

import android.content.Context
import com.buildx.ai.SttRunner
import com.buildx.ai.actions.ActionContext
import com.buildx.ai.nlu.CommandPipeline

/**
 * One exchange: say "জি, বলুন" -> listen (STT) -> run the command -> speak the result.
 * Used by the service (after the wake word) and by the app's "কথা বলো" button (no wake word).
 * The caller must make sure nothing else is using the microphone while this runs.
 * Main thread only.
 */
class Conversation(
    private val ctx: Context,
    private val lang: String,
    private val speaker: Speaker
) {
    private var stt: SttRunner? = null
    private var cancelled = false

    private fun say(bnText: String, enText: String): String =
        if (lang.startsWith("bn")) bnText else enText

    /** [onFinished] runs after the last spoken sentence (not when cancelled). */
    fun run(onFinished: () -> Unit) {
        speaker.speak(say("জি, বলুন", "Yes, go ahead")) {
            if (cancelled) return@speak
            val runner = SttRunner(ctx)
            stt = runner
            runner.start(lang) { text ->
                if (cancelled) return@start
                if (text.isNullOrBlank()) {
                    speaker.speak(say("বুঝতে পারিনি", "Sorry, I didn't catch that")) {
                        if (!cancelled) onFinished()
                    }
                } else {
                    val result = CommandPipeline.run(ActionContext(ctx, lang), text)
                    speaker.speak(result.message) {
                        if (!cancelled) onFinished()
                    }
                }
            }
        }
    }

    fun cancel() {
        cancelled = true
        stt?.cancel()
        stt = null
    }
}
