package com.buildx.ai.llm

import com.buildx.ai.actions.ActionResult
import com.buildx.ai.actions.Deferred
import com.buildx.ai.nlu.Parsed

/** Asks the server. Blocking, so it is only ever called off the main thread. Never throws for a bad network. */
interface LlmBackend {
    fun plan(req: LlmRequest): LlmOutcome
}

/**
 * For a sentence the offline parser could not understand: ask the model, then do what it decided.
 *
 * The request starts immediately, while the holding line ("দেখছি") is being spoken, so the wait is not
 * "speak, then ask". When the answer comes back it is either steps (run by [run], which is the normal
 * pipeline, so confirmations, follow-up questions and chains behave exactly as for a spoken command) or a
 * short sentence to say. If the model, the network or the answer fails, the user hears what they heard
 * before this existed: "বুঝতে পারিনি".
 *
 * No Android classes: [io] runs the blocking call, [main] hands the result back to the main thread.
 * Everything here except the blocking call happens on the main thread.
 */
class LlmFallback(
    private val backend: LlmBackend,
    private val io: (() -> Unit) -> Unit,
    private val main: (() -> Unit) -> Unit,
    private val log: (String) -> Unit = {},
    private val now: () -> String
) {
    /** The answer may arrive before or after the conversation asks for it; both orders work. Main thread only. */
    private class Pending {
        private var done: LlmOutcome? = null
        private var waiter: ((LlmOutcome) -> Unit)? = null

        fun complete(o: LlmOutcome) {
            done = o
            val w = waiter
            waiter = null
            if (w != null) w(o)
        }

        fun await(cb: (LlmOutcome) -> Unit) {
            val d = done
            if (d != null) cb(d) else waiter = cb
        }
    }

    fun resolve(text: String, lang: String, bn: Boolean, agent: Boolean = false, run: (List<Parsed>) -> ActionResult): ActionResult {
        val pending = Pending()
        val req = LlmRequest(text, lang, now(), agent)
        io {
            val outcome = try {
                backend.plan(req)
            } catch (e: Exception) {
                LlmOutcome.Failed(Failure.NETWORK, e.javaClass.simpleName)
            }
            main { pending.complete(outcome) }
        }
        return ActionResult(
            true, if (bn) "দেখছি" else "One moment",
            deferred = Deferred { report -> pending.await { report(finish(it, text, bn, run)) } }
        )
    }

    private fun finish(outcome: LlmOutcome, text: String, bn: Boolean, run: (List<Parsed>) -> ActionResult): ActionResult =
        when (outcome) {
            is LlmOutcome.Planned -> {
                val plan = outcome.plan
                log("LLM \"$text\" -> ${plan.steps.joinToString(" | ") { "${it.intent} ${it.slots}" }}${if (plan.say != null) " say" else ""}")
                // steps speak for themselves; a spoken answer is only used when there is nothing to do
                if (plan.steps.isNotEmpty()) run(plan.steps) else ActionResult(true, plan.say.orEmpty())
            }
            is LlmOutcome.Failed -> {
                log("LLM failed ${outcome.why} ${outcome.detail}")
                val why = when (outcome.why) {
                    Failure.NETWORK, Failure.TIMEOUT -> if (bn) "ইন্টারনেটে সমস্যা। " else "No connection. "
                    Failure.AUTH -> if (bn) "সার্ভারের টোকেন ঠিক নেই। " else "The server rejected the token. "
                    Failure.BUSY -> if (bn) "সার্ভার ব্যস্ত। " else "The server is busy. "
                    Failure.SERVER, Failure.BAD_RESPONSE -> if (bn) "সার্ভার থেকে ঠিক উত্তর আসেনি। " else "The server gave no usable answer. "
                }
                ActionResult(false, why + if (bn) "বুঝতে পারিনি: $text" else "I didn't understand: $text")
            }
        }
}
