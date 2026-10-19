package com.buildx.ai.a11y

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Turns the Accessibility service on without the Settings screen (so "Restricted setting" cannot block it).
 * Works only after this one-time command from a computer, because the permission cannot be requested in the app:
 *   adb shell pm grant com.buildx.ai android.permission.WRITE_SECURE_SETTINGS
 */
object A11yEnabler {

    /** true if the service is now listed as enabled */
    fun enable(ctx: Context): Boolean {
        val me = ComponentName(ctx, BuildXAccessibilityService::class.java).flattenToString()
        return try {
            val cr = ctx.contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            if (!cur.split(':').any { it.equals(me, ignoreCase = true) }) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (cur.isEmpty()) me else "$cur:$me")
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
