package com.buildx.ai.notif

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.buildx.ai.system.NotifItem

/**
 * Lets the assistant READ the notifications that are on the phone right now ("নোটিফিকেশন পড়ো", "কে কল করছে").
 * Off until the person turns it on in "Notification access". It keeps nothing: it only looks at what is showing
 * when asked, and nothing leaves the phone.
 */
class BuildXNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: BuildXNotificationListener? = null

        fun isEnabledInSettings(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, BuildXNotificationListener::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun label(sbn: StatusBarNotification): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
    } catch (e: Exception) {
        sbn.packageName
    }

    /** the notifications worth reading, newest first: not ours, not ongoing (music, downloads), not group headers */
    fun readable(): List<NotifItem> {
        val all = try { activeNotifications } catch (e: Exception) { null } ?: return emptyList()
        return all
            .filter { it.packageName != packageName && !it.isOngoing && (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
            .sortedByDescending { it.postTime }
            .map {
                val ex = it.notification.extras
                NotifItem(label(it), ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(), ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
            }
    }

    /** the title of the incoming-call notification (the caller's name or number), or null */
    fun ringingCaller(): String? {
        val all = try { activeNotifications } catch (e: Exception) { null } ?: return null
        val call = all.filter { it.notification.category == Notification.CATEGORY_CALL }
            .sortedByDescending { it.postTime }
            .firstOrNull() ?: return null
        return call.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
    }
}
