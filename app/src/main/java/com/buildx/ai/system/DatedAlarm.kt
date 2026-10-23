package com.buildx.ai.system

import java.util.Calendar

/**
 * [at]: the exact moment. [clockAppFires]: the Clock app's alarm intent would ring at exactly this moment too
 * (it can only do "the next hh:mm"); if false, "পরশু ৬টায়" or "কাল" said just before midnight needs our own alarm.
 */
data class DatedAlarmPlan(val at: Long, val hour: Int, val minute: Int, val clockAppFires: Boolean)

/**
 * Phase 6: the date part of an alarm. The Clock app's ACTION_SET_ALARM cannot take a date, so for anything other than
 * "the next time it is hh:mm" BuildX sets its own alarm with AlarmManager.setAlarmClock. No Android classes.
 */
object DatedAlarm {

    /** [slots] as the parser gives them: hour, minute, ambiguous, when. null = no usable time, or it has already passed. */
    fun plan(now: Calendar, slots: Map<String, String>): DatedAlarmPlan? {
        if (slots["hour"] == null) return null // a "in N minutes" timer is a different thing
        val at = Reminders.triggerAt(now, slots) ?: return null
        val t = Calendar.getInstance().apply { timeInMillis = at }
        val h = t.get(Calendar.HOUR_OF_DAY)
        val m = t.get(Calendar.MINUTE)
        return DatedAlarmPlan(at, h, m, nextOccurrence(now, h, m) == at)
    }

    /** the first moment at or after [now]'s next minute boundary when the wall clock reads hour:minute */
    fun nextOccurrence(now: Calendar, hour: Int, minute: Int): Long {
        val c = now.clone() as Calendar
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        if (c.timeInMillis <= now.timeInMillis) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** snooze: five minutes from [nowMs], never in the past */
    fun snoozeAt(nowMs: Long, minutes: Int = 5): Long = nowMs + minutes.coerceIn(1, 60) * 60_000L
}
