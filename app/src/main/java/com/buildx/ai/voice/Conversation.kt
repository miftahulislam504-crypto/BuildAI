package com.buildx.ai.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.buildx.ai.LogStore
import com.buildx.ai.SttRunner
import com.buildx.ai.actions.ActionContext
import com.buildx.ai.actions.ActionResult
import com.buildx.ai.nlu.CommandPipeline

/**
 * One session: say "জি, বলুন", then keep taking commands, one after another:
 *   listen (STT) -> run the command -> speak the result -> listen again.
 * The session stays open until
 *  - nothing is said for 5 seconds, or
 *  - the person says "থাক" / "stop" (an action that ends the session), or
 *  - three attempts in a row could not be understood (so it cannot loop on its own voice).
 * A sentence that is not understood does NOT close the session: it is answered and the phone listens again.
 * If a command is a question ("কাকে?", "কল করব?"), it listens for the answer and hands it back to that action.
 * Used by the service (after the wake word) and by the app's "Speak" button (no wake word).
 * The caller must make sure nothing else is using the microphone while this runs.
 * Main thread only.
 */
class Conversation(
    private val ctx: Context,
    private val lang: String,
    private val speaker: Speaker,
    private val onEvent: (String) -> Unit = {}
) {
    private val main = Handler(Looper.getMainLooper())
    private var stt: SttRunner? = null
    private var cancelled = false
    private var onFinished: () -> Unit = {}

    /** attempts in a row that were not understood or heard as nothing */
    private var misses = 0

    private fun say(bnText: String, enText: String): String =
        if (lang.startsWith("bn")) bnText else enText

    /** [onFinished] runs when the session closes (not when cancelled). */
    fun run(onFinished: () -> Unit) {
        this.onFinished = {
            onEvent("Done")
            onFinished()
        }
        onEvent("Listening...")
        speaker.speak(say("জি, বলুন", "Yes, go ahead")) {
            if (cancelled) return@speak
            nextCommand(announce = false)
        }
    }

    /** the session loop: wait for a command, run it, then come back here */
    private fun nextCommand(announce: Boolean) {
        if (cancelled) return
        if (announce) onEvent("Listening...")
        listen(IDLE_MS) { text, silent ->
            when {
                !text.isNullOrBlank() -> {
                    onEvent("Heard: \"$text\"")
                    val result = CommandPipeline.run(ActionContext(ctx, lang), text)
                    misses = if (result.unrecognized) misses + 1 else 0
                    deliver(result) { last ->
                        when {
                            last.endsSession -> close("Closed: the person said stop")
                            misses >= MAX_MISSES -> close("Closed: not understood $MAX_MISSES times in a row")
                            else -> nextCommand(announce = true)
                        }
                    }
                }
                silent -> close("Closed: no speech for ${IDLE_MS / 1000} seconds")
                else -> {
                    // an error, or something that came out as nothing: stay open and listen again, quietly
                    misses++
                    onEvent("Heard nothing")
                    if (misses >= MAX_MISSES) close("Closed: nothing usable $MAX_MISSES times in a row") else nextCommand(announce = false)
                }
            }
        }
    }

    private fun close(why: String) {
        if (cancelled) return
        onEvent(why)
        onFinished()
    }

    /** [onText] gets the text (or null) and whether the person stayed completely silent */
    private fun listen(silenceMs: Long, onText: (String?, Boolean) -> Unit) {
        // a short pause first: the end of our own spoken prompt must not be heard as the command,
        // and the speech recognizer needs a moment after the speaker has finished
        main.postDelayed({
            if (cancelled) return@postDelayed
            val runner = SttRunner(ctx)
            stt = runner
            runner.start(lang, silenceMs) { text ->
                if (cancelled) return@start
                onText(text, runner.silent)
            }
        }, LISTEN_DELAY_MS)
    }

    /**
     * Speak the result. If it holds a question, listen for the answer and continue with the action's next result.
     * [then] gets the LAST result of that exchange (after any answers and any delayed outcome).
     */
    private fun deliver(result: ActionResult, then: (ActionResult) -> Unit) {
        if (result.message.isNotBlank()) onEvent((if (result.followUp != null) "Question: " else if (result.success) "Result: " else "Failed: ") + result.message)
        val later = result.deferred
        if (later != null) {
            // the real answer comes from the phone a moment later (screen locked? switch flipped?)
            val go = { later.run { final -> deliver(final, then) } }
            if (result.message.isBlank()) go() else speaker.speak(result.message) { if (!cancelled) go() }
            return
        }
        speaker.speak(result.message) {
            if (cancelled) return@speak
            val follow = result.followUp
            if (follow == null) {
                then(result)
            } else {
                onEvent("Listening for your answer...")
                listen(FOLLOW_UP_MS) { reply, _ ->
                    onEvent("Heard: \"${reply ?: ""}\"")
                    val next = try {
                        follow.onReply(reply)
                    } catch (e: Exception) {
                        LogStore.add(ctx, "FOLLOW-UP ERROR: ${e.message}")
                        ActionResult(false, say("কাজটি করতে পারিনি", "That failed"))
                    }
                    LogStore.add(ctx, "REPLY \"${reply ?: ""}\" -> ${next.message}")
                    deliver(next, then)
                }
            }
        }
    }

    fun cancel() {
        cancelled = true
        main.removeCallbacksAndMessages(null)
        stt?.cancel()
        stt = null
    }

    private companion object {
        const val LISTEN_DELAY_MS = 450L
        /** how long the microphone waits for the person to start speaking before the session closes */
        const val IDLE_MS = 5000L
        /** a little longer when it has just asked a question, so there is time to think of the answer */
        const val FOLLOW_UP_MS = 7000L
        const val MAX_MISSES = 3
    }
}
