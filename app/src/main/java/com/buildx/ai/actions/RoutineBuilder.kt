package com.buildx.ai.actions

import com.buildx.ai.nlu.Describe
import com.buildx.ai.nlu.IntentParser
import com.buildx.ai.nlu.Reply
import com.buildx.ai.nlu.TextNormalizer
import com.buildx.ai.system.ConversationMemory
import com.buildx.ai.system.Routine
import com.buildx.ai.system.RoutineBook

/**
 * "রুটিন বানাও গুড মর্নিং": asks for the steps one sentence at a time (each is understood but NOT run), until "শেষ".
 * "আগেরটা" adds the last command. Silence saves what was collected so far. No Android classes.
 */
class RoutineBuilder(
    private val bn: Boolean,
    private val load: () -> List<Routine>,
    private val store: (List<Routine>) -> Unit,
    private val memory: ConversationMemory,
    private val log: (String) -> Unit = {}
) {
    private val finishWords = setOf("শেষ", "done", "finish", "finished", "সেভ", "save", "ব্যস", "হয়েছে", "হয়ে")
    private val lastWords = setOf("আগের", "আগেরটা", "last", "previous")

    private fun t(bnText: String, enText: String) = if (bn) bnText else enText

    private fun cleanName(s: String): String = s.trim().take(RoutineBook.MAX_NAME)

    fun start(said: String, fromLast: Boolean): ActionResult {
        val steps = ArrayList<String>()
        if (fromLast) {
            val last = memory.lastText()
                ?: return ActionResult(false, t("আগের কোনো কমান্ড মনে নেই", "I don't remember a previous command"))
            steps.add(last)
        }
        val n = cleanName(said)
        if (n.isNotEmpty()) return collect(n, steps, misses = 0)
        return ActionResult(true, t("রুটিনের নাম কী?", "What should the routine be called?"), FollowUp { reply ->
            val name = reply?.let { cleanName(it) }.orEmpty()
            if (name.isEmpty()) ActionResult(false, t("নাম পাইনি, রুটিন বানাইনি", "No name, no routine made")) else collect(name, steps, misses = 0)
        })
    }

    private fun finish(name: String, steps: List<String>): ActionResult {
        if (steps.isEmpty()) return ActionResult(false, t("কোনো কাজ যোগ হয়নি, রুটিন বানাইনি", "No steps added, no routine made"))
        val before = load()
        val replaced = before.any { RoutineBook.key(it.name) == RoutineBook.key(name) }
        store(RoutineBook.put(before, Routine(name, steps.toList())))
        val n = if (bn) steps.size.toString().map { '০' + (it - '0') }.joinToString("") else steps.size.toString()
        log("ROUTINE saved \"$name\" steps=${steps.size} replaced=$replaced")
        val tail = t("চালাতে শুধু \"$name\" বলুন", "To run it, just say \"$name\"")
        return ActionResult(true, if (replaced) t("‘$name’ রুটিন বদলে দিলাম, ${n}টা ধাপ। $tail", "Replaced \"$name\", $n steps. $tail") else t("‘$name’ রুটিন সেভ হয়েছে, ${n}টা ধাপ। $tail", "Saved \"$name\", $n steps. $tail"))
    }

    /** asks for steps one at a time until "শেষ" */
    private fun collect(name: String, steps: MutableList<String>, misses: Int): ActionResult {
        if (steps.size >= RoutineBook.MAX_STEPS) return finish(name, steps)
        val prompt = if (steps.isEmpty()) t("‘$name’ রুটিনে প্রথম কাজ কী? কাজ বলুন, শেষ হলে \"শেষ\" বলুন", "What is the first step of \"$name\"? Say it, and say \"done\" when finished")
        else t("আর কী? শেষ হলে \"শেষ\" বলুন", "What next? Say \"done\" when finished")
        return ActionResult(true, prompt, FollowUp { reply ->
            val words = reply?.let { TextNormalizer.normalize(it).split(' ') }.orEmpty()
            when {
                reply.isNullOrBlank() -> finish(name, steps) // silence: keep what we have
                Reply.yesNo(reply) == Reply.YesNo.NO && words.size <= 2 -> ActionResult(true, t("ঠিক আছে, রুটিন বানাইনি", "Okay, no routine made"))
                words.any { it in finishWords } && words.size <= 3 -> finish(name, steps)
                words.any { it in lastWords } -> {
                    val last = memory.lastText()
                    if (last == null) collect(name, steps, misses)
                    else { steps.add(last); added(name, steps, last) }
                }
                else -> {
                    val parsed = IntentParser.parseChain(reply)
                    val bad = parsed.firstOrNull { it.intent == "unknown" || it.intent == "routine" }
                    if (bad != null) {
                        if (misses >= 1) ActionResult(false, t("বুঝতে পারছি না। রুটিন বানাইনি", "I'm not getting it. No routine made"))
                        else ActionResult(true, t("এটা বুঝতে পারিনি। অন্যভাবে বলুন, অথবা \"শেষ\"", "I didn't understand that. Say it another way, or \"done\""), FollowUp { again ->
                            // one more try goes through the same logic
                            collect(name, steps, misses + 1).followUp!!.onReply(again)
                        })
                    } else {
                        steps.add(reply.trim())
                        added(name, steps, reply)
                    }
                }
            }
        })
    }

    private fun added(name: String, steps: MutableList<String>, text: String): ActionResult {
        val said = IntentParser.parseChain(text).joinToString(t(", তারপর ", ", then ")) { Describe.say(it, bn) }
        val next = collect(name, steps, misses = 0)
        return next.copy(message = t("যোগ করেছি: $said। ${next.message}", "Added: $said. ${next.message}"))
    }
}
