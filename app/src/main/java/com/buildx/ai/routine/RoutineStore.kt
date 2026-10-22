package com.buildx.ai.routine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.buildx.ai.LogStore
import com.buildx.ai.system.Reminders
import com.buildx.ai.system.Routine
import com.buildx.ai.system.RoutineBook
import com.buildx.ai.system.Trigger
import java.util.Calendar

/** The saved routines (SharedPreferences) and the daily alarms that start them. */
object RoutineStore {
    private const val KEY = "routines"
    const val EXTRA_NAME = "name"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("buildx", Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context): List<Routine> = RoutineBook.decode(prefs(ctx).getString(KEY, ""))

    @Synchronized
    fun save(ctx: Context, list: List<Routine>) {
        prefs(ctx).edit().putString(KEY, RoutineBook.encode(list)).apply()
    }

    private fun pending(ctx: Context, name: String): PendingIntent {
        val i = Intent(ctx, RoutineAlarmReceiver::class.java).putExtra(EXTRA_NAME, name)
        // one alarm per routine: the request code comes from its name
        return PendingIntent.getBroadcast(ctx, 20_000 + (RoutineBook.key(name).hashCode() and 0xFFFF), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** sets (or sets again) the alarm for a routine with a daily trigger; any other routine has its alarm cancelled */
    fun arm(ctx: Context, r: Routine) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val t = r.trigger as? Trigger.Daily
        if (t == null) {
            am.cancel(pending(app, r.name))
            return
        }
        // from a minute from now, so an alarm that fires a moment early does not pick the same minute again
        val base = Calendar.getInstance().apply { add(Calendar.MINUTE, 1) }
        val at = Reminders.triggerAt(base, mapOf("hour" to t.hour.toString(), "minute" to t.minute.toString(), "ambiguous" to "false", "when" to "auto"))
        if (at == null) {
            LogStore.add(app, "ROUTINE alarm not set for \"${r.name}\": no valid time")
            return
        }
        try {
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(app, r.name))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(app, r.name))
            LogStore.add(app, "ROUTINE \"${r.name}\" alarm at=$at exact=$exact")
        } catch (e: Exception) {
            LogStore.add(app, "ROUTINE alarm failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun cancel(ctx: Context, name: String) {
        val app = ctx.applicationContext
        (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(app, name))
    }

    /** after a reboot or an app update the system forgets our alarms */
    fun rescheduleAll(ctx: Context) {
        for (r in load(ctx)) if (r.trigger is Trigger.Daily) arm(ctx, r)
    }
}
