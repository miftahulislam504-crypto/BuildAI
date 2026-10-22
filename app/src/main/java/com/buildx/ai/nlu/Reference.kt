package com.buildx.ai.nlu

import com.buildx.ai.system.ConversationMemory

/**
 * "ওকে আবার কল করো" (call HIM again), "ওটা বন্ধ করো" (turn IT off), "আবার করো" (do it again), "উল্টোটা করো" (the opposite).
 * Works on what the parser produced: a step that is missing its target gets the last contact, a step the parser
 * could not understand may turn out to be one of these short references. Nothing is guessed when nothing was remembered.
 * No Android classes.
 */
object Reference {

    /** spoken as the target instead of a name ("তাকে" arrives as "তা": the parser cuts off the trailing "কে") */
    private val pronouns = setOf("ওকে", "ও", "তাকে", "তা", "ওনাকে", "ওনা", "উনাকে", "উনা", "ওনার", "him", "her", "them")

    private fun norm(s: String) = TextNormalizer.nfc(s).lowercase().trim()

    fun isPronoun(target: String?): Boolean = target != null && norm(target) in pronouns.map { norm(it) }.toSet()

    private val flip = mapOf("on" to "off", "off" to "on")

    fun resolve(steps: List<Parsed>, text: String, mem: ConversationMemory): List<Parsed> {
        val toks = Canonicalizer.tokenize(TextNormalizer.normalize(text))
        val again = toks.any { it.canon == "again" }
        return steps.map { step -> resolveOne(step, toks, again, mem) }
    }

    private fun resolveOne(step: Parsed, toks: List<Tok>, again: Boolean, mem: ConversationMemory): Parsed {
        if (step.intent in ConversationMemory.contactIntents) {
            val noOne = step.slots["target"] == null && step.slots["number"] == null
            if (isPronoun(step.slots["target"]) || (again && noOne)) {
                val who = mem.lastContact() ?: return step
                val slots = LinkedHashMap(step.slots)
                slots.remove("target"); slots.remove("number")
                who.slots["number"]?.let { slots["number"] = it }
                who.slots["target"]?.let { slots["target"] = it }
                return Parsed(step.intent, slots, step.normalized)
            }
            return step
        }
        if (step.intent == "open_app" && toks.any { it.canon == "it_ref" }) {
            // "ওটা খোলো": the parser took "ওটা" for an app name
            val o = mem.lastOpened() ?: return step
            return Parsed(step.intent, o.slots, step.normalized)
        }
        if (step.intent != "unknown") return step
        return fromReference(toks, mem)?.let { Parsed(it.intent, it.slots, step.normalized) } ?: step
    }

    private val fillerCanon = setOf("_f", "_v", "sep")

    /** the whole sentence is only a reference: "আবার করো", "do it again", "ওটা বন্ধ করো", "turn that off", "উল্টোটা করো" */
    private fun fromReference(toks: List<Tok>, mem: ConversationMemory): ConversationMemory.Step? {
        if (toks.isEmpty()) return null
        val c = toks.map { it.canon }
        val itRef = c.any { it == "it_ref" } || toks.any { it.raw == "it" }
        val onWord = c.contains("on")
        val offWord = c.contains("off")
        val open = c.contains("open")
        val undo = c.contains("undo")
        val again = c.contains("again")
        val allowed = fillerCanon + setOf("again", "it_ref", "on", "off", "open", "undo")
        val rawOk = setOf("do", "it", "turn", "switch", "put", "again")
        if (toks.any { it.canon !in allowed && it.raw !in rawOk }) return null

        if (undo) {
            val t = mem.lastToggle() ?: return null
            val now = t.slots["state"]
            val back = flip[now] ?: return null
            return ConversationMemory.Step(t.intent, t.slots + ("state" to back))
        }
        if (itRef && (onWord || offWord) && !(onWord && offWord)) {
            val t = mem.lastToggle() ?: return null
            return ConversationMemory.Step(t.intent, t.slots + ("state" to if (onWord) "on" else "off"))
        }
        if (itRef && open) return mem.lastOpened()
        if (again && !itRef && !onWord && !offWord && !open) return mem.lastStep()
        if (again && itRef) return mem.lastStep()
        return null
    }
}
