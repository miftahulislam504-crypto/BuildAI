package com.buildx.ai.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/** Which SIM to call with. "" in the preference = let the phone decide (it may ask on every call). */
object SimHelper {

    fun accounts(ctx: Context): List<PhoneAccountHandle> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return try {
            ctx.getSystemService(TelecomManager::class.java)?.callCapablePhoneAccounts ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    fun idOf(h: PhoneAccountHandle): String = h.componentName.flattenToString() + "|" + h.id

    fun label(ctx: Context, h: PhoneAccountHandle): String {
        val acc = try {
            ctx.getSystemService(TelecomManager::class.java)?.getPhoneAccount(h)
        } catch (e: SecurityException) {
            null
        }
        return acc?.label?.toString()?.takeIf { it.isNotBlank() } ?: h.id
    }

    /** the SIM chosen in settings, or null for "phone decides" (or if that SIM is gone) */
    fun chosen(ctx: Context): PhoneAccountHandle? {
        val id = ctx.getSharedPreferences("buildx", Context.MODE_PRIVATE).getString("simId", "") ?: ""
        if (id.isEmpty()) return null
        return accounts(ctx).firstOrNull { idOf(it) == id }
    }
}
