package com.buildx.ai.llm

import com.buildx.ai.actions.ActionResult
import com.buildx.ai.actions.Deferred
import com.buildx.ai.actions.FollowUp
import com.buildx.ai.nlu.Parsed
import com.buildx.ai.nlu.Reply

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
 * When one missing detail would make a wrong action likely, the model may ask ONE question instead of guessing:
 * the question is spoken, the spoken answer starts a second round with the question and answer attached, and
 * that round can only act or speak (the proxy does not even offer the ask tool). "থাক" / "না" drops it.
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

    /** [recent]: name-free lines about what the phone just did (see ConversationMemory.safeSummary) */
    fun resolve(
        text: String,
        lang: String,
        bn: Boolean,
        agent: Boolean = false,
        recent: List<String> = emptyList(),
        run: (List<Parsed>) -> ActionResult
    ): ActionResult = round(text, lang, bn, agent, recent, null, run)

    private fun round(
        text: String,
        lang: String,
        bn: Boolean,
        agent: Boolean,
        recent: List<String>,
        clarify: Pair<String, String>?,
        run: (List<Parsed>) -> ActionResult
    ): ActionResult {
        val pending = Pending()
        val req = LlmRequest(text, lang, now(), agent, recent, clarify)
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
            deferred = Deferred { report ->
                pending.await { report(finish(it, text, lang, bn, agent, recent, clarify, run)) }
            }
        )
    }

    private fun finish(
        outcome: LlmOutcome,
        text: String,
        lang: String,
        bn: Boolean,
        agent: Boolean,
        recent: List<String>,
        clarify: Pair<String, String>?,
        run: (List<Parsed>) -> ActionResult
    ): ActionResult =
        when (outcome) {
            is LlmOutcome.Planned -> {
                val plan = outcome.plan
                val question = plan.ask
                if (question != null) {
                    log("LLM \"$text\" -> asks: $question")
                    if (clarify != null) {
                        // one question per request: a second one is only spoken, never answered
                        ActionResult(true, question)
                    } else {
                        ActionResult(true, question, FollowUp { reply -> answered(text, lang, bn, agent, recent, question, reply, run) })
                    }
                } else {
                    log("LLM \"$text\" -> ${plan.steps.joinToString(" | ") { "${it.intent} ${it.slots}" }}${if (plan.say != null) " say" else ""}")
                    // steps speak for themselves; a spoken answer is only used when there is nothing to do
                    if (plan.steps.isNotEmpty()) run(plan.steps) else ActionResult(true, plan.say.orEmpty())
                }
            }
            is LlmOutcome.Failed -> {
                log("LLM failed ${outcome.why} ${outcome.detail}")
                val why = when (outcome.why) {
                    Failure.NETWORK, Failure.TIMEOUT -> if (bn) "ইন্টারনেটে সমস্যা। " else "No connection. "
                    Failure.AUTH -> if (bn) "সার্ভারের টোকেন ঠিক নেই। " else "The server rejected the token. "
                    Failure.BUSY -> if (bn) "সার্ভার ব্যস্ত। " else "The server is busy. "
                    Failure.SERVER, Failure.BAD_RESPONSE -> if (bn) "সার্ভার থেকে ঠিক উত্তর আসেনি। " else "The server gave no usable answer. "
                }
                ActionResult(false, why + if (bn) "বুঝতে পারিনি: $text" else "I didn't understand: $text", unrecognized = true)
            }
        }

    /** the user's spoken answer to the model's question: silence or a short "না" / "থাক" drops it, anything else is round two */
    private fun answered(
        text: String,
        lang: String,
        bn: Boolean,
        agent: Boolean,
        recent: List<String>,
        question: String,
        reply: String?,
        run: (List<Parsed>) -> ActionResult
    ): ActionResult {
        val answer = reply?.trim().orEmpty()
        if (answer.isEmpty()) return ActionResult(false, if (bn) "উত্তর পাইনি, করিনি" else "No answer, so I did nothing")
        val short = answer.split(' ').count { it.isNotEmpty() } <= 2
        if (short && Reply.yesNo(answer) == Reply.YesNo.NO) return ActionResult(true, if (bn) "ঠিক আছে, করিনি" else "Okay, not doing it")
        return round(text, lang, bn, agent, recent, question to answer.take(MAX_ANSWER), run)
    }

    private companion object {
        const val MAX_ANSWER = 300
    }
}
