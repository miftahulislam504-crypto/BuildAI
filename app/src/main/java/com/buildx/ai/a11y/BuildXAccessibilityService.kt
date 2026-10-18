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
}
