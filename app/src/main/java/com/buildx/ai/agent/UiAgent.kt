package com.buildx.ai.agent

import com.buildx.ai.a11y.ElKind
import com.buildx.ai.a11y.ScreenElement
import com.buildx.ai.a11y.ScreenSnapshot
import com.buildx.ai.actions.ActionResult
import com.buildx.ai.actions.Deferred
import com.buildx.ai.actions.FollowUp
import com.buildx.ai.llm.Failure
import com.buildx.ai.nlu.Reply

/** Looks at the screen (main thread). Null = it cannot be read. */
interface ScreenPort {
    fun capture(): ScreenSnapshot?
}

/** Carries out one step on the screen that [ScreenPort.capture] last returned. [done] is called on the main thread. */
interface UiPort {
    fun perform(step: AgentStep, done: (ok: Boolean, note: String) -> Unit)
}

/**
 * "Look at the screen, ask the model for the next step, do it, repeat." It is a leash as much as a loop:
 *  - never more than [MAX_STEPS] steps or [MAX_MILLIS] in total, and it stops when [cancel] is called;
 *  - a screen the snapshot refused to read (passwords, money apps) ends the task;
 *  - the same step failing three times in a row ends the task;
 *  - before a tap or a typed word it looks at the screen again, and does nothing if the element is no longer there;
 *  - a button that sends, pays, deletes, calls ... is only pressed after the person says yes.
 * Everything runs on the main thread except [AgentBackend.next], which goes through [io].
 * No Android classes.
 */
class UiAgent(
    private val goal: String,
    private val lang: String,
    private val bn: Boolean,
    private val screen: ScreenPort,
    private val ui: UiPort,
    private val backend: AgentBackend,
    private val io: (() -> Unit) -> Unit,
    private val main: (() -> Unit) -> Unit,
    private val later: (Long, () -> Unit) -> Unit,
    private val clock: () -> Long,
    private val nowIso: () -> String,
    private val log: (String) -> Unit = {},
    private val onFinish: () -> Unit = {}
) {
    companion object {
        const val MAX_STEPS = 12
        const val MAX_MILLIS = 120_000L
        const val FIRST_LOOK_MS = 1500L
        const val SETTLE_MS = 900L
        const val HISTORY_SENT = 8
    }

    @Volatile private var cancelled = false
    private var finished = false
    private var steps = 0
    private var startedAt = 0L
    private val history = ArrayList<String>()

    private fun t(b: String, e: String) = if (bn) b else e

    fun cancel() {
        cancelled = true
    }

    fun start(): ActionResult = ActionResult(
        true, t("ঠিক আছে, করছি", "On it"),
        deferred = Deferred { report ->
            startedAt = clock()
            later(FIRST_LOOK_MS) { look(report) }
        }
    )

    // ---- the loop ----

    private fun look(report: (ActionResult) -> Unit) {
        if (cancelled) return end(report, false, t("থামিয়েছি", "Stopped"))
        if (steps >= MAX_STEPS) return end(report, false, t("ধাপের সীমায় পৌঁছে গেছি, শেষ করতে পারিনি", "I hit my step limit without finishing"))
        if (clock() - startedAt > MAX_MILLIS) return end(report, false, t("অনেক সময় লাগছে, থামলাম", "This is taking too long, so I stopped"))
        if (stuck()) return end(report, false, t("একই জায়গায় আটকে গেছি, থামলাম", "I'm stuck in the same place, so I stopped"))

        val snap = screen.capture() ?: return end(report, false, t("স্ক্রিন পড়তে পারছি না। Accessibility চালু আছে তো?", "I can't read the screen. Is Accessibility on?"))
        if (snap.blocked != null) {
            log("AGENT blocked: ${snap.blocked}")
            return end(report, false, t("এই স্ক্রিনে আমি কাজ করি না (পাসওয়ার্ড বা টাকার অ্যাপ), নিজে করে নিন", "I don't work on this screen (passwords or money apps), please do it yourself"))
        }
        val req = AgentRequest(goal, lang, nowIso(), snap.pkg, snap.toPrompt(), history.takeLast(HISTORY_SENT))
        io {
            val out = try {
                backend.next(req)
            } catch (e: Exception) {
                AgentOutcome.Failed(Failure.NETWORK, e.javaClass.simpleName)
            }
            main { answered(report, snap, out) }
        }
    }

    private fun answered(report: (ActionResult) -> Unit, snap: ScreenSnapshot, out: AgentOutcome) {
        if (cancelled) return end(report, false, t("থামিয়েছি", "Stopped"))
        when (out) {
            is AgentOutcome.Failed -> {
                log("AGENT server failed ${out.why} ${out.detail}")
                end(report, false, serverProblem(out.why))
            }
            is AgentOutcome.Next -> act(report, snap, out.step)
        }
    }

    private fun act(report: (ActionResult) -> Unit, snap: ScreenSnapshot, step: AgentStep) {
        when (step) {
            is AgentStep.Done -> end(report, true, step.summary.ifBlank { t("হয়ে গেছে", "Done") })
            is AgentStep.Fail -> end(report, false, step.reason.ifBlank { t("পারিনি", "I couldn't do it") })
            is AgentStep.Tap -> {
                val el = snap.elements.firstOrNull { it.id == step.id }
                    ?: return record(report, "tap ${step.id}", false, "no such element")
                if (el.risky) return askFirst(report, snap, step, el)
                perform(report, step, describe(el)) { fresh(snap, el) }
            }
            is AgentStep.Type -> {
                val el = step.id?.let { id -> snap.elements.firstOrNull { it.id == id && it.kind == ElKind.FIELD } }
                if (step.id != null && el == null) return record(report, "type", false, "no such field")
                perform(report, step, "type \"${step.text.take(30)}\"") { if (el == null) true else fresh(snap, el) }
            }
            else -> perform(report, step, step.toString().lowercase()) { true }
        }
    }

    /** looks again; true only if the element the model chose is still where it was and still says the same */
    private fun fresh(old: ScreenSnapshot, el: ScreenElement): Boolean {
        val now = screen.capture() ?: return false
        if (now.blocked != null || now.pkg != old.pkg) return false
        val same = now.elements.firstOrNull { it.id == el.id } ?: return false
        return same.kind == el.kind && same.label == el.label
    }

    private fun perform(report: (ActionResult) -> Unit, step: AgentStep, what: String, stillThere: () -> Boolean) {
        if (!stillThere()) return record(report, what, false, "the screen changed")
        ui.perform(step) { ok, note -> record(report, what, ok, note) }
    }

    private fun record(report: (ActionResult) -> Unit, what: String, ok: Boolean, note: String) {
        steps++ // every try counts, even one that was never carried out, so the loop always ends
        history.add("$what -> ${if (ok) "ok" else "failed: $note"}")
        log("AGENT #${history.size} ${history.last()}")
        later(SETTLE_MS) { look(report) }
    }

    /** three identical failures in a row */
    private fun stuck(): Boolean {
        if (history.size < 3) return false
        val last = history.takeLast(3)
        return last.all { it.contains("failed") } && last.toSet().size == 1
    }

    // ---- asking before something that cannot be undone ----

    private fun askFirst(report: (ActionResult) -> Unit, snap: ScreenSnapshot, step: AgentStep.Tap, el: ScreenElement) {
        report(
            ActionResult(
                true, t("‘${el.label}’ চাপব? হ্যাঁ বা না বলুন", "Press “${el.label}”? Say yes or no"),
                FollowUp { reply ->
                    when {
                        cancelled -> stopped(t("থামিয়েছি", "Stopped"))
                        Reply.yesNo(reply) == Reply.YesNo.YES ->
                            ActionResult(true, "", deferred = Deferred { r2 ->
                                // the person took a moment: only press it if it is still the same button
                                perform(r2, step, describe(el)) { fresh(snap, el) }
                            })
                        else -> stopped(t("ঠিক আছে, এখানেই থামলাম", "Okay, I'll stop here"))
                    }
                }
            )
        )
    }

    private fun stopped(msg: String): ActionResult {
        finish()
        return ActionResult(true, msg)
    }

    // ---- ending ----

    private fun end(report: (ActionResult) -> Unit, ok: Boolean, msg: String) {
        if (finished) return
        finish()
        log("AGENT end ok=$ok after $steps steps")
        report(ActionResult(ok, msg))
    }

    private fun finish() {
        if (finished) return
        finished = true
        onFinish()
    }

    private fun describe(el: ScreenElement) = "tap [${el.id}] \"${el.label.take(30)}\""

    private fun serverProblem(f: Failure) = when (f) {
        Failure.NETWORK, Failure.TIMEOUT -> t("ইন্টারনেটে সমস্যা, কাজ থামালাম", "No connection, so I stopped")
        Failure.AUTH -> t("সার্ভারের টোকেন ঠিক নেই", "The server rejected the token")
        Failure.BUSY -> t("সার্ভার ব্যস্ত, একটু পরে চেষ্টা করুন", "The server is busy, try again in a moment")
        Failure.SERVER, Failure.BAD_RESPONSE -> t("সার্ভার থেকে ঠিক উত্তর আসেনি", "The server gave no usable answer")
    }
}
