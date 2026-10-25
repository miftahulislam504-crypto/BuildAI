package com.buildx.ai.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.buildx.ai.LogStore
import com.buildx.ai.MainActivity

/** The alarm went off: show the reminder as a notification (sound + vibration). */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(ReminderScheduler.EXTRA_ID, 0)
        val text = intent.getStringExtra(ReminderScheduler.EXTRA_TEXT).orEmpty()
        notifyNow(context.applicationContext, id, text, late = false)
        ReminderScheduler.done(context, id)
    }

    companion object {
        private const val CHANNEL = "buildx_reminders"

        fun notifyNow(ctx: Context, id: Int, text: String, late: Boolean) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "BuildX reminders", NotificationManager.IMPORTANCE_HIGH)
                ch.enableVibration(true)
                ch.setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
                )
                nm.createNotificationChannel(ch)
            }
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val title = if (late) "Reminder (late)" else "Reminder"
            val body = text.ifBlank { "It is time for your reminder" }
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                nm.notify(10_000 + id, n)
                LogStore.add(ctx, "REMINDER #$id fired late=$late")
            } catch (e: Exception) {
                LogStore.add(ctx, "REMINDER notify failed: ${e.message}")
            }
        }
    }
}

/** After a reboot or an app update: set the saved reminders again. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ReminderScheduler.rescheduleAll(context)
            DatedAlarmScheduler.rescheduleAll(context)
            com.buildx.ai.routine.RoutineStore.rescheduleAll(context)
        }
    }
}
