package com.buildx.ai.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.buildx.ai.LogStore
import com.buildx.ai.system.Reminder
import com.buildx.ai.system.Reminders

/** The saved reminders (SharedPreferences) and the system alarms that fire them. */
object ReminderScheduler {
    private const val KEY = "reminders"
    const val EXTRA_ID = "id"
    const val EXTRA_TEXT = "text"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("buildx", Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context): List<Reminder> = Reminders.decode(prefs(ctx).getString(KEY, ""))

    @Synchronized
    private fun save(ctx: Context, list: List<Reminder>) {
        prefs(ctx).edit().putString(KEY, Reminders.encode(list)).apply()
    }

    private fun pending(ctx: Context, r: Reminder): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra(EXTRA_ID, r.id).putExtra(EXTRA_TEXT, r.text)
        return PendingIntent.getBroadcast(ctx, r.id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** saves the reminder and sets its alarm; returns it, or null if the system refused */
    @Synchronized
    fun add(ctx: Context, at: Long, text: String): Reminder? {
        val app = ctx.applicationContext
        val list = load(app)
        val r = Reminder(Reminders.nextId(list), at, text)
        if (!arm(app, r)) return null
        save(app, Reminders.upcoming(list, System.currentTimeMillis()) + r)
        return r
    }

    /** true if an alarm is set (exact when the phone allows it, else the nearest the system will give) */
    private fun arm(ctx: Context, r: Reminder): Boolean {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return try {
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pending(ctx, r))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pending(ctx, r))
            LogStore.add(ctx, "REMINDER #${r.id} set exact=$exact at=${r.at}")
            true
        } catch (e: Exception) {
            LogStore.add(ctx, "REMINDER set failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    @Synchronized
    fun done(ctx: Context, id: Int) {
        val app = ctx.applicationContext
        save(app, load(app).filter { it.id != id })
    }

    @Synchronized
    fun cancelAll(ctx: Context): Int {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val list = load(app)
        for (r in list) am.cancel(pending(app, r))
        save(app, emptyList())
        return list.size
    }

    /** after a reboot or an app update the system forgets our alarms */
    @Synchronized
    fun rescheduleAll(ctx: Context) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val list = load(app)
        val future = Reminders.upcoming(list, now)
        for (r in future) arm(app, r)
        save(app, future)
        // anything that came due while the phone was off is said once, late, rather than dropped
        for (r in list.filter { it.at <= now && now - it.at < 12 * 3_600_000L }) ReminderReceiver.notifyNow(app, r.id, r.text, late = true)
    }
}
