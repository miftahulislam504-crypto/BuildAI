package com.buildx.ai.voice

import android.content.Context
import com.buildx.ai.LogStore
import com.buildx.ai.SttRunner
import com.buildx.ai.actions.ActionContext
import com.buildx.ai.actions.ActionResult
import com.buildx.ai.nlu.CommandPipeline

/**
 * One exchange: say "জি, বলুন" -> listen (STT) -> run the command -> speak the result.
 * If the result is a question (an action needs more: "কাকে?", "কল করব?"), it listens again and
 * hands the answer back to that action, until the action has nothing more to ask.
 * Used by the service (after the wake word) and by the app's "কথা বলো" button (no wake word).
 * The caller must make sure nothing else is using the microphone while this runs.
 * Main thread only.
 */
class Conversation(
    private val ctx: Context,
    private val lang: String,
    private val speaker: Speaker,
    private val onEvent: (String) -> Unit = {}
) {
    private var stt: SttRunner? = null
    private var cancelled = false
    private var onFinished: () -> Unit = {}

    private fun say(bnText: String, enText: String): String =
        if (lang.startsWith("bn")) bnText else enText

    /** [onFinished] runs after the last spoken sentence (not when cancelled). */
    fun run(onFinished: () -> Unit) {
        this.onFinished = {
            onEvent("✓ " + say("শেষ", "done"))
            onFinished()
        }
        onEvent("🎙 " + say("শুনছি…", "listening…"))
        speaker.speak(say("জি, বলুন", "Yes, go ahead")) {
            if (cancelled) return@speak
            listen { text ->
                if (text.isNullOrBlank()) {
                    onEvent(say("কিছু শুনতে পাইনি", "heard nothing"))
                    finishWith(say("বুঝতে পারিনি", "Sorry, I didn't catch that"))
                } else {
                    onEvent(say("শুনলাম: ", "heard: ") + "\"$text\"")
                    deliver(CommandPipeline.run(ActionContext(ctx, lang), text))
                }
            }
        }
    }

    private fun listen(onText: (String?) -> Unit) {
        val runner = SttRunner(ctx)
        stt = runner
        runner.start(lang) { text ->
            if (cancelled) return@start
            onText(text)
        }
    }

    private fun finishWith(sentence: String) {
        speaker.speak(sentence) {
            if (!cancelled) onFinished()
        }
    }

    /** speak the result; if it holds a question, listen for the answer and continue */
    private fun deliver(result: ActionResult) {
        if (result.message.isNotBlank()) onEvent((if (result.followUp != null) "❓ " else if (result.success) "→ " else "✗ ") + result.message)
        val later = result.deferred
        if (later != null) {
            // the real answer comes from the phone a moment later (screen locked? switch flipped?)
            val go = { later.run { final -> deliver(final) } }
            if (result.message.isBlank()) go() else speaker.speak(result.message) { if (!cancelled) go() }
            return
        }
        speaker.speak(result.message) {
            if (cancelled) return@speak
            val follow = result.followUp
            if (follow == null) {
                onFinished()
            } else {
                onEvent("🎙 " + say("উত্তর শুনছি…", "listening for your answer…"))
                listen { reply ->
                    onEvent(say("শুনলাম: ", "heard: ") + "\"${reply ?: ""}\"")
                    val next = try {
                        follow.onReply(reply)
                    } catch (e: Exception) {
                        LogStore.add(ctx, "FOLLOW-UP ERROR: ${e.message}")
                        ActionResult(false, say("কাজটি করতে পারিনি", "That failed"))
                    }
                    LogStore.add(ctx, "REPLY \"${reply ?: ""}\" -> ${next.message}")
                    deliver(next)
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
