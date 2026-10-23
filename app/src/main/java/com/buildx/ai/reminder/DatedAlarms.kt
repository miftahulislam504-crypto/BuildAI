package com.buildx.ai.reminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.buildx.ai.LogStore
import com.buildx.ai.MainActivity
import com.buildx.ai.system.DatedAlarm
import com.buildx.ai.system.Reminder
import com.buildx.ai.system.Reminders

/**
 * Phase 6: alarms with an exact DATE ("পরশু সকাল ৬টায়"). The Clock app's alarm intent takes only hour and minute, so
 * for these BuildX sets its own alarm with AlarmManager.setAlarmClock (the phone treats it like a real alarm clock:
 * it shows in the status bar and survives Doze) and rings it itself. Alarms the Clock app can do (the next hh:mm) still
 * go to the Clock app, so they show up there.
 */
object DatedAlarmScheduler {
    private const val KEY = "dated_alarms"
    const val EXTRA_ID = "id"
    const val EXTRA_TEXT = "text"
    const val ACTION_STOP = "com.buildx.ai.ALARM_STOP"
    const val ACTION_SNOOZE = "com.buildx.ai.ALARM_SNOOZE"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("buildx", Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context): List<Reminder> = Reminders.decode(prefs(ctx).getString(KEY, ""))

    @Synchronized
    private fun save(ctx: Context, list: List<Reminder>) {
        prefs(ctx).edit().putString(KEY, Reminders.encode(list)).apply()
    }

    private fun firePending(ctx: Context, id: Int, text: String): PendingIntent {
        val i = Intent(ctx, DatedAlarmReceiver::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_TEXT, text)
        // a different request-code range from reminders (those use the plain id)
        return PendingIntent.getBroadcast(ctx, 500_000 + id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun showPending(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** saves the alarm and sets it; null if the system refused (no exact-alarm permission, for instance) */
    @Synchronized
    fun add(ctx: Context, at: Long, text: String): Reminder? {
        val app = ctx.applicationContext
        val list = load(app)
        val r = Reminder(Reminders.nextId(list), at, text)
        if (!arm(app, r)) return null
        save(app, Reminders.upcoming(list, System.currentTimeMillis()) + r)
        return r
    }

    private fun arm(ctx: Context, r: Reminder): Boolean {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(r.at, showPending(ctx)), firePending(ctx, r.id, r.text))
            LogStore.add(ctx, "DATED-ALARM #${r.id} set at=${r.at}")
            true
        } catch (e: Exception) {
            LogStore.add(ctx, "DATED-ALARM set failed: ${e.javaClass.simpleName}: ${e.message}")
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
        for (r in list) am.cancel(firePending(app, r.id, r.text))
        save(app, emptyList())
        return list.size
    }

    /** after a reboot or an app update the system forgets our alarms; one that came due while the phone was off rings once, late */
    @Synchronized
    fun rescheduleAll(ctx: Context) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val list = load(app)
        val future = Reminders.upcoming(list, now)
        for (r in future) arm(app, r)
        save(app, future)
        for (r in list.filter { it.at <= now && now - it.at < 3 * 3_600_000L }) DatedAlarmReceiver.ring(app, r.id, r.text, late = true)
    }

    fun snooze(ctx: Context, text: String, minutes: Int = 5): Reminder? = add(ctx, DatedAlarm.snoozeAt(System.currentTimeMillis(), minutes), text)
}

/** The alarm time came: ring until the person stops it (the sound repeats until the notification is dismissed or after 10 minutes). */
class DatedAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(DatedAlarmScheduler.EXTRA_ID, 0)
        val text = intent.getStringExtra(DatedAlarmScheduler.EXTRA_TEXT).orEmpty()
        ring(context.applicationContext, id, text, late = false)
        DatedAlarmScheduler.done(context, id)
    }

    companion object {
        private const val CHANNEL = "buildx_alarm"
        const val NOTIF_BASE = 30_000

        fun ring(ctx: Context, id: Int, text: String, late: Boolean) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "BuildX অ্যালার্ম", NotificationManager.IMPORTANCE_HIGH)
                ch.enableVibration(true)
                ch.vibrationPattern = longArrayOf(0, 600, 400, 600)
                ch.setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                nm.createNotificationChannel(ch)
            }
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            fun action(act: String, req: Int): PendingIntent = PendingIntent.getBroadcast(
                ctx, req,
                Intent(ctx, DatedAlarmActionReceiver::class.java).setAction(act).putExtra(DatedAlarmScheduler.EXTRA_ID, id).putExtra(DatedAlarmScheduler.EXTRA_TEXT, text),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val body = text.ifBlank { "অ্যালার্ম বাজছে" }
            val title = if (late) "অ্যালার্ম (দেরিতে)" else "অ্যালার্ম"
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(open)
                .setOngoing(true)
                .setTimeoutAfter(10 * 60_000L)
                .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel), "বন্ধ করো", action(DatedAlarmScheduler.ACTION_STOP, 1)).build())
                .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, android.R.drawable.ic_lock_idle_alarm), "৫ মিনিট পরে", action(DatedAlarmScheduler.ACTION_SNOOZE, 2)).build())
                .build()
            n.flags = n.flags or Notification.FLAG_INSISTENT // keep ringing, like an alarm clock
            try {
                nm.notify(NOTIF_BASE + id, n)
                LogStore.add(ctx, "DATED-ALARM #$id rang late=$late")
            } catch (e: Exception) {
                LogStore.add(ctx, "DATED-ALARM notify failed: ${e.message}")
            }
        }
    }
}

/** The "বন্ধ করো" and "৫ মিনিট পরে" buttons on the ringing alarm. */
class DatedAlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(DatedAlarmScheduler.EXTRA_ID, 0)
        val text = intent.getStringExtra(DatedAlarmScheduler.EXTRA_TEXT).orEmpty()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(DatedAlarmReceiver.NOTIF_BASE + id) // stops the sound
        if (intent.action == DatedAlarmScheduler.ACTION_SNOOZE) {
            val r = DatedAlarmScheduler.snooze(context, text)
            LogStore.add(context, "DATED-ALARM #$id snoozed -> ${if (r != null) "#${r.id}" else "failed"}")
        } else {
            LogStore.add(context, "DATED-ALARM #$id stopped")
        }
    }
}
