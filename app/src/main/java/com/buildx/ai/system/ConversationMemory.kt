package com.buildx.ai.system

/**
 * What was just said and done, so "ওকে আবার কল করো", "ওটা বন্ধ করো", "আবার করো" can mean something.
 * Forgets after [ttlMs] of silence, and everything is lost when the app process dies; nothing is saved to disk.
 * No Android classes.
 */
class ConversationMemory(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = 15 * 60_000L
) {
    data class Step(val intent: String, val slots: Map<String, String>)

    private var last: Step? = null
    private var lastAt = 0L
    private var contact: Step? = null
    private var contactAt = 0L
    private var toggle: Step? = null
    private var toggleAt = 0L
    private var opened: Step? = null
    private var openedAt = 0L
    private var lastText: String? = null
    private var lastTextAt = 0L

    companion object {
        val shared = ConversationMemory()

        /** commands that start something and name who it is for */
        val contactIntents = setOf("call", "whatsapp_call", "send_sms", "send_whatsapp")

        /** things that are switched on and off */
        val toggleIntents = setOf("wifi", "bluetooth", "flashlight", "dnd", "airplane", "mobile_data", "hotspot")

        /** slots that say how, never who or what was written */
        private val safeSlots = setOf("state", "action", "level", "delta", "app", "mode", "feed", "dir", "camera", "when", "hour", "minute", "in_minutes")

        /** not worth repeating or remembering */
        private val skip = setOf("unknown", "cancel", "echo", "routine")
    }

    private fun fresh(at: Long) = clock() - at <= ttlMs

    fun record(intent: String, slots: Map<String, String>) {
        if (intent in skip) return
        val s = Step(intent, slots.filterKeys { it != "via" })
        val now = clock()
        last = s; lastAt = now
        if (intent in contactIntents && (slots["target"] != null || slots["number"] != null)) { contact = s; contactAt = now }
        if (intent in toggleIntents) { toggle = s; toggleAt = now }
        if (intent == "open_app") { opened = s; openedAt = now }
    }

    /** the sentence the person last said that did something (for "save that as a routine") */
    fun recordText(text: String) {
        if (text.isBlank()) return
        lastText = text.trim(); lastTextAt = clock()
    }

    fun lastText(): String? = if (fresh(lastTextAt)) lastText else null
    fun lastStep(): Step? = if (fresh(lastAt)) last else null
    fun lastContact(): Step? = if (fresh(contactAt)) contact else null
    fun lastToggle(): Step? = if (fresh(toggleAt)) toggle else null
    fun lastOpened(): Step? = if (fresh(openedAt)) opened else null

    /**
     * What was just done, newest first, as short lines for the server ("wifi state=off"): so "ওটা আবার চালু করো" can be
     * understood there. Only slots that name no person and carry no text (no target, number, message, search or note);
     * each line is short enough for the proxy's limit.
     */
    fun safeSummary(max: Int = 3): List<String> {
        val items = ArrayList<Pair<Long, Step>>()
        val l = last
        val t = toggle
        val o = opened
        if (l != null && fresh(lastAt)) items.add(lastAt to l)
        if (t != null && fresh(toggleAt)) items.add(toggleAt to t)
        if (o != null && fresh(openedAt)) items.add(openedAt to o)
        return items.sortedByDescending { it.first }.map { describe(it.second) }.distinct().take(max)
    }

    private fun describe(s: Step): String {
        val parts = s.slots.filterKeys { it in safeSlots }.entries.joinToString(" ") { "${it.key}=${it.value.take(30)}" }
        return (if (parts.isEmpty()) s.intent else "${s.intent} $parts").take(100)
    }

    fun clear() {
        last = null; contact = null; toggle = null; opened = null; lastText = null
    }
}
