package com.buildx.ai.nlu

import com.buildx.ai.LogStore
import com.buildx.ai.actions.ActionContext
import com.buildx.ai.actions.ActionRegistry
import com.buildx.ai.actions.ActionResult
import com.buildx.ai.actions.ChainRunner
import com.buildx.ai.llm.AndroidLlm
import com.buildx.ai.system.MainScheduler

/**
 * text -> IntentParser -> action(s) -> result. Always returns a result; never throws.
 * One command runs directly; several in one sentence ("ইউটিউব খুলে ভলিউম কমাও") run as a chain, step by step,
 * stopping at the first failure. Intents the parser understands but that have no action ("end_call") say so.
 * A sentence the offline parser cannot understand goes to the optional LLM fallback (off unless the user set it up),
 * which answers with steps that run through this same pipeline.
 * Main thread only.
 */
object CommandPipeline {

    fun run(ac: ActionContext, text: String): ActionResult {
        val steps = IntentParser.parseChain(text)
        if (steps.any { it.intent == "unknown" }) {
            val llm = AndroidLlm.fallback(ac.context)
            if (llm != null) {
                LogStore.add(ac.context, "LLM \"$text\" -> অফলাইনে বুঝিনি, সার্ভারকে জিজ্ঞেস করছি")
                return llm.resolve(text, ac.lang, ac.bn, AndroidLlm.agentOn(ac.context)) { planned -> runSteps(ac, planned, text) }
            }
        }
        return runSteps(ac, steps, text)
    }

    /** one step runs directly; several run as a chain */
    private fun runSteps(ac: ActionContext, steps: List<Parsed>, text: String): ActionResult {
        if (steps.size == 1) return runOne(ac, steps[0], text)
        LogStore.add(ac.context, "CHAIN \"$text\" -> ${steps.size} ধাপ: ${steps.joinToString(" | ") { Describe.say(it, ac.bn) }}")
        return ChainRunner(ac.bn, { p -> runOne(ac, p, p.normalized) }, MainScheduler).start(steps)
    }

    private fun runOne(ac: ActionContext, parsed: Parsed, text: String): ActionResult {
        val said = Describe.say(parsed, ac.bn)
        val action = ActionRegistry.get(parsed.intent)

        val result = when {
            parsed.intent == "end_call" ->
                ActionResult(false, if (ac.bn) "কলের মধ্যে কল কাটা এই অ্যাপে নেই" else "I can't hang up calls")
            action == null ->
                ActionResult(
                    false,
                    if (ac.bn) "বুঝেছি: $said। তবে এই কাজ এখনো তৈরি হয়নি" else "Understood: $said. That action isn't built yet"
                )
            else -> try {
                action.execute(ac, parsed.slots)
            } catch (e: Exception) {
                LogStore.add(ac.context, "ACTION ERROR ${parsed.intent}: ${e.message}")
                ActionResult(false, if (ac.bn) "কাজটি করতে পারিনি" else "That failed")
            }
        }

        LogStore.add(ac.context, "CMD \"$text\" -> ${parsed.intent} ${parsed.slots} ok=${result.success}")
        return result
    }
}
