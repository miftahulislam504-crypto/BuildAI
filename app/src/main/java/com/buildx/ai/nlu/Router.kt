package com.buildx.ai.nlu

import com.buildx.ai.LogStore
import com.buildx.ai.actions.ActionContext
import com.buildx.ai.actions.ActionRegistry
import com.buildx.ai.actions.ActionResult
import com.buildx.ai.actions.ChainRunner
import com.buildx.ai.actions.FollowUp
import com.buildx.ai.actions.RiskPolicy
import com.buildx.ai.llm.AndroidLlm
import com.buildx.ai.routine.RoutineStore
import com.buildx.ai.system.ConversationMemory
import com.buildx.ai.system.MainScheduler
import com.buildx.ai.system.Routine
import com.buildx.ai.system.RoutineBook

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
        // just saying a routine's name runs it
        routineByName(ac, text)?.let { return runRoutine(ac, it) }
        val parsed = IntentParser.parseChain(text)
        // "ওকে আবার কল করো", "ওটা বন্ধ করো", "আবার করো": fill in from what was just said and done
        val steps = Reference.resolve(parsed, text, ConversationMemory.shared)
        val plain = steps.none { it.intent == "unknown" || it.intent == "routine" } &&
            steps.map { it.intent to it.slots } == parsed.map { it.intent to it.slots }
        if (plain) ConversationMemory.shared.recordText(text)
        if (steps.any { it.intent == "unknown" }) {
            val llm = AndroidLlm.fallback(ac.context)
            if (llm != null) {
                LogStore.add(ac.context, "LLM \"$text\" -> not understood offline, asking the server")
                return llm.resolve(text, ac.lang, ac.bn, AndroidLlm.agentOn(ac.context)) { planned -> runSteps(ac, planned, text) }
            }
        }
        return runSteps(ac, steps, text)
    }

    /** the saved routine whose name is the whole sentence (spaces, small slips and the words "চালাও" / "routine" ignored) */
    private fun routineByName(ac: ActionContext, text: String): Routine? {
        val all = RoutineStore.load(ac.context)
        if (all.isEmpty()) return null
        val toks = Canonicalizer.tokenize(TextNormalizer.normalize(text))
        if (toks.any { it.canon == "routine" }) return null // "…রুটিন চালাও" is parsed as a routine command instead
        val rest = toks.filter { it.canon != "_f" && it.canon != "_v" && it.canon != "on" }.joinToString(" ") { it.raw }
        return RoutineBook.find(all, rest)
    }

    /** runs a saved routine: its steps one after another, like a chain */
    fun runRoutine(ac: ActionContext, r: Routine): ActionResult {
        val steps = r.steps.flatMap { IntentParser.parseChain(it) }
        LogStore.add(ac.context, "ROUTINE \"${r.name}\" -> ${steps.size} step(s): ${steps.joinToString(" | ") { Describe.say(it, ac.bn) }}")
        if (steps.isEmpty()) return ActionResult(false, if (ac.bn) "রুটিনে কোনো ধাপ নেই" else "The routine has no steps")
        return runSteps(ac, steps, r.name)
    }

    /** one step runs directly; several run as a chain */
    private fun runSteps(ac: ActionContext, steps: List<Parsed>, text: String): ActionResult {
        if (steps.size == 1) return runOne(ac, steps[0], text)
        LogStore.add(ac.context, "CHAIN \"$text\" -> ${steps.size} step(s): ${steps.joinToString(" | ") { Describe.say(it, ac.bn) }}")
        return ChainRunner(ac.bn, { p -> runOne(ac, p, p.normalized) }, MainScheduler).start(steps)
    }

    internal fun runOne(ac: ActionContext, parsed: Parsed, text: String): ActionResult { // internal: the instrumented tests check the risk gate
        val said = Describe.say(parsed, ac.bn)
        val action = ActionRegistry.get(parsed.intent)

        // Phase 6: a step the server chose that changes how the phone connects or rings is only run after a spoken yes
        if (action != null && RiskPolicy.mustAsk(parsed.intent, parsed.slots)) {
            LogStore.add(ac.context, "RISK ask first: ${parsed.intent} ${parsed.slots}")
            return ActionResult(true, if (ac.bn) "$said। করব?" else "$said. Should I go ahead?", FollowUp { reply ->
                when (Reply.yesNo(reply)) {
                    Reply.YesNo.YES -> runOne(ac, parsed.copy(slots = parsed.slots + (RiskPolicy.CONFIRMED to "1")), text)
                    Reply.YesNo.NO -> ActionResult(true, if (ac.bn) "ঠিক আছে, করিনি" else "Okay, not doing it")
                    Reply.YesNo.UNKNOWN -> ActionResult(false, if (ac.bn) "বুঝতে পারিনি, করিনি" else "Didn't get that, not doing it")
                }
            })
        }

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
        if (result.success) ConversationMemory.shared.record(parsed.intent, parsed.slots)
        return result
    }
}
