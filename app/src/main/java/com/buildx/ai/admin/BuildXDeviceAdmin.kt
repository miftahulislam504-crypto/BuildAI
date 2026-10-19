package com.buildx.ai.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/** Device admin with ONE policy: force-lock (lock the screen). Nothing else is requested. */
class BuildXDeviceAdmin : DeviceAdminReceiver() {
    companion object {
        fun component(ctx: Context) = ComponentName(ctx, BuildXDeviceAdmin::class.java)

        fun isActive(ctx: Context): Boolean {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(component(ctx))
        }

        /** true if the lock command was sent */
        fun lockNow(ctx: Context): Boolean = try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isAdminActive(component(ctx))) { dpm.lockNow(); true } else false
        } catch (e: SecurityException) {
            false
        }
    }
}
