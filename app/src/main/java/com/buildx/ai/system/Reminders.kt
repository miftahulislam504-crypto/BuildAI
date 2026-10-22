package com.buildx.ai.system

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Calendar

data class Reminder(val id: Int, val at: Long, val text: String)

/**
 * When a reminder (or calendar event) should fire, and how the saved list is stored. No Android classes.
 * Unlike the Clock app's alarm intent, an exact date works here: "কাল", "পরশু" mean what they say.
 */
object Reminders {

    /** [slots] as the parser gives them (hour/minute/ambiguous or in_minutes, and when). null = no usable time. */
    fun triggerAt(now: Calendar, slots: Map<String, String>): Long? {
        val rel = slots["in_minutes"]?.toIntOrNull()
        if (rel != null && slots["hour"] == null) {
            if (rel < 1 || rel > 60 * 24 * 30) return null
            return now.timeInMillis + rel * 60_000L
        }
        val hour = slots["hour"]?.toIntOrNull() ?: return null
        val minute = slots["minute"]?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        val ambiguous = slots["ambiguous"] == "true"
        val whenSlot = slots["when"] ?: "auto"

        val plan = AlarmMath.plan(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), hour, minute, ambiguous, whenSlot)
        val c = now.clone() as Calendar
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.set(Calendar.HOUR_OF_DAY, plan.hour)
        c.set(Calendar.MINUTE, plan.minute)
        when (whenSlot) {
            "tomorrow" -> c.add(Calendar.DAY_OF_YEAR, 1)
            "day_after" -> c.add(Calendar.DAY_OF_YEAR, 2)
            "today" -> {}
            else -> if (c.timeInMillis <= now.timeInMillis) c.add(Calendar.DAY_OF_YEAR, 1)
        }
        // "আজ ৯টায়" said at 10 pm: the time has passed, so do not pretend
        if (c.timeInMillis <= now.timeInMillis) return null
        return c.timeInMillis
    }

    /** "আজ বিকাল ৪টা" / "আগামীকাল সকাল ৯টা ৩০ মিনিট" / "today 4:00 PM" */
    fun sayWhen(at: Long, now: Calendar, bn: Boolean): String {
        val t = Calendar.getInstance().apply { timeInMillis = at }
        val days = dayDiff(now, t)
        val clock = AlarmWords.clock(t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE), bn)
        val day = when (days) {
            0 -> if (bn) "আজ" else "today"
            1 -> if (bn) "আগামীকাল" else "tomorrow"
            2 -> if (bn) "পরশু" else "the day after tomorrow"
            else -> if (bn) "${days.toString().map { '০' + (it - '0') }.joinToString("")} দিন পরে" else "in $days days"
        }
        return "$day $clock"
    }

    private fun dayDiff(a: Calendar, b: Calendar): Int {
        val x = Calendar.getInstance().apply { timeInMillis = a.timeInMillis; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val y = Calendar.getInstance().apply { timeInMillis = b.timeInMillis; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return Math.round((y.timeInMillis - x.timeInMillis) / 86_400_000.0).toInt()
    }

    // ---------- the saved list ----------

    fun encode(list: List<Reminder>): String {
        val a = JSONArray()
        for (r in list) a.put(JSONObject().put("id", r.id).put("at", r.at).put("text", r.text))
        return a.toString()
    }

    fun decode(s: String?): List<Reminder> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                if (!o.has("id") || !o.has("at")) null else Reminder(o.getInt("id"), o.getLong("at"), o.optString("text", ""))
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    /** a new id that no saved reminder uses */
    fun nextId(list: List<Reminder>): Int = (list.maxOfOrNull { it.id } ?: 0) + 1

    /** reminders that are still in the future, soonest first */
    fun upcoming(list: List<Reminder>, nowMs: Long): List<Reminder> = list.filter { it.at > nowMs }.sortedBy { it.at }
}

/** The note book: newest last. No Android classes. */
object NoteBook {
    const val MAX_NOTES = 100
    const val MAX_LEN = 500

    fun encode(notes: List<String>): String = JSONArray(notes).toString()

    fun decode(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotBlank() }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun add(notes: List<String>, text: String): List<String> {
        val t = text.trim().take(MAX_LEN)
        if (t.isEmpty()) return notes
        return (notes + t).takeLast(MAX_NOTES)
    }

    /** the latest [count] notes, newest first, as one speakable text */
    fun say(notes: List<String>, count: Int, bn: Boolean): String {
        if (notes.isEmpty()) return if (bn) "কোনো নোট নেই" else "You have no notes"
        val last = notes.takeLast(count).reversed()
        fun d(i: Int) = if (bn) i.toString().map { '০' + (it - '0') }.joinToString("") else i.toString()
        val body = last.mapIndexed { i, s -> "${d(i + 1)}. $s" }.joinToString(if (bn) "। " else ". ")
        val total = notes.size
        val head = if (bn) "মোট ${d(total)}টা নোট। " + (if (total > count) "শেষ ${d(count)}টা: " else "") else "$total note${if (total == 1) "" else "s"}. " + (if (total > count) "The latest $count: " else "")
        return head + body
    }
}
