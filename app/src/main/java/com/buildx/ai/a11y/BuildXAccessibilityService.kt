package com.buildx.ai.a11y

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.buildx.ai.LogStore

/**
 * Does nothing by itself. It exists so the assistant may (1) lock the screen and (2) tap the Wi-Fi/Bluetooth
 * switch on the settings panel. It never reads, stores or sends what is on the screen.
 */
class BuildXAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BuildXAccessibilityService? = null

        /** true if the person has turned the service on in Settings (it may take a moment to connect) */
        fun isEnabledInSettings(ctx: Context): Boolean {
            val me = ComponentName(ctx, BuildXAccessibilityService::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }

    override fun onServiceConnected() {
        instance = this
        LogStore.add(this, "ACCESSIBILITY সংযুক্ত")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        LogStore.add(this, "ACCESSIBILITY বিচ্ছিন্ন")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /**
     * One swipe up the middle of the screen ([next] = content moves up: the next reel / short / page), or down ([next] = false).
     * [done] says whether the phone carried the gesture out. Needs "perform gestures" (declared; if the service was
     * enabled before that was added, switch it off and on once).
     */
    fun swipe(next: Boolean, done: (Boolean) -> Unit) {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val from = dm.heightPixels * (if (next) 0.75f else 0.25f)
        val to = dm.heightPixels * (if (next) 0.25f else 0.75f)
        val path = android.graphics.Path().apply {
            moveTo(x, from)
            lineTo(x, to)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 280))
            .build()
        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: android.accessibilityservice.GestureDescription?) = done(true)
            override fun onCancelled(g: android.accessibilityservice.GestureDescription?) = done(false)
        }, null)
        if (!sent) done(false)
    }
}
