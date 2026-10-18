package com.buildx.ai.system

/**
 * [hour] is 0-23. [firesToday]: the Clock app fires at the NEXT occurrence of hour:minute, so this says whether that is
 * today or tomorrow. [mismatch]: that is not the day the person asked for (the Clock intent cannot take a date).
 */
data class AlarmPlan(val hour: Int, val minute: Int, val minutesUntil: Int, val firesToday: Boolean, val mismatch: Boolean)

object AlarmMath {

    fun plan(nowHour: Int, nowMinute: Int, hour: Int, minute: Int, ambiguous: Boolean, whenSlot: String): AlarmPlan {
        val now = nowHour * 60 + nowMinute
        val candidates = if (ambiguous && hour in 1..12) listOf(hour % 12, hour % 12 + 12) else listOf(hour)

        fun until(h: Int): Int {
            var u = h * 60 + minute - now
            if (u <= 0) u += 1440
            return u
        }

        val chosen = when (whenSlot) {
            // "কাল ৬টায়" means the morning one; "কাল ১২টায়" means noon
            "tomorrow", "day_after" -> if (candidates.size == 2 && hour == 12) candidates[1] else candidates[0]
            else -> candidates.minByOrNull { until(it) } ?: hour
        }
        val firesToday = chosen * 60 + minute > now
        val mismatch = when (whenSlot) {
            "today" -> !firesToday
            "tomorrow" -> firesToday
            "day_after" -> true
            else -> false
        }
        return AlarmPlan(chosen, minute, until(chosen), firesToday, mismatch)
    }
}

/** Spoken forms of times and durations. */
object AlarmWords {

    private fun d(n: Int, bn: Boolean): String = if (bn) n.toString().map { '০' + (it - '0') }.joinToString("") else n.toString()

    fun period(h24: Int): String = when (h24) {
        in 4..5 -> "ভোর"
        in 6..11 -> "সকাল"
        in 12..14 -> "দুপুর"
        in 15..17 -> "বিকাল"
        in 18..19 -> "সন্ধ্যা"
        else -> "রাত"
    }

    /** "সকাল ৬টা ৩০ মিনিট" / "6:30 AM" */
    fun clock(h24: Int, minute: Int, bn: Boolean): String {
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        if (!bn) return "$h12:${minute.toString().padStart(2, '0')} ${if (h24 < 12) "AM" else "PM"}"
        val m = if (minute > 0) " ${d(minute, true)} মিনিট" else ""
        return "${period(h24)} ${d(h12, true)}টা$m"
    }

    /** "৭ ঘণ্টা ২০ মিনিট পরে" */
    fun duration(minutes: Int, bn: Boolean): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (bn) {
            val parts = ArrayList<String>()
            if (h > 0) parts.add("${d(h, true)} ঘণ্টা")
            if (m > 0) parts.add("${d(m, true)} মিনিট")
            parts.joinToString(" ") + " পরে"
        } else {
            val parts = ArrayList<String>()
            if (h > 0) parts.add("$h h")
            if (m > 0) parts.add("$m min")
            "in " + parts.joinToString(" ")
        }
    }
}
