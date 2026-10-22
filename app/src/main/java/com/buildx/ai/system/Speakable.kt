package com.buildx.ai.system

/** What gets said for incoming SMS and notifications. No Android classes. */
data class SmsItem(val from: String, val body: String, val at: Long)
data class NotifItem(val app: String, val title: String, val text: String)

object Speakable {

    private fun d(i: Int, bn: Boolean) = if (bn) i.toString().map { '০' + (it - '0') }.joinToString("") else i.toString()

    private fun squash(s: String, max: Int): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max).trimEnd() + "…"
    }

    /** the newest [limit] messages, each cut to [maxLen] characters, read oldest of those first so it sounds natural */
    fun sms(items: List<SmsItem>, limit: Int, maxLen: Int, bn: Boolean): String {
        if (items.isEmpty()) return if (bn) "নতুন কোনো মেসেজ নেই" else "No new messages"
        val take = items.sortedByDescending { it.at }.take(limit)
        val head = if (items.size == 1) (if (bn) "একটা মেসেজ। " else "One message. ")
        else if (bn) "${d(items.size, true)}টা মেসেজ। ".let { if (items.size > limit) it + "সবচেয়ে নতুন ${d(limit, true)}টা: " else it }
        else "${items.size} messages. " + (if (items.size > limit) "The newest $limit: " else "")
        val body = take.joinToString(" ") { m ->
            if (bn) "${m.from} লিখেছেন: ${squash(m.body, maxLen)}।" else "${m.from} says: ${squash(m.body, maxLen)}."
        }
        return head + body
    }

    /** the latest [limit] notifications that have something to read */
    fun notifications(items: List<NotifItem>, limit: Int, maxLen: Int, bn: Boolean): String {
        val usable = items.filter { it.title.isNotBlank() || it.text.isNotBlank() }
        if (usable.isEmpty()) return if (bn) "কোনো নতুন নোটিফিকেশন নেই" else "No notifications"
        val head = if (bn) "${d(usable.size, true)}টা নোটিফিকেশন। " + (if (usable.size > limit) "প্রথম ${d(limit, true)}টা: " else "")
        else "${usable.size} notification${if (usable.size == 1) "" else "s"}. " + (if (usable.size > limit) "The first $limit: " else "")
        val body = usable.take(limit).joinToString(" ") { n ->
            val what = listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(": ") { squash(it, maxLen) }
            "${n.app}, $what."
        }
        return head + body
    }

    /** who is calling, from the ringing call's notification title */
    fun caller(title: String?, ringing: Boolean, bn: Boolean): String = when {
        !title.isNullOrBlank() -> if (bn) "$title কল করছেন" else "$title is calling"
        ringing -> if (bn) "কেউ কল করছে, কিন্তু নামটা পাইনি" else "Someone is calling, but I couldn't get the name"
        else -> if (bn) "এখন কোনো কল আসছে না" else "No call is coming in right now"
    }
}
