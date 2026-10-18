package com.buildx.ai.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Tries the maker's own battery/autostart screen; if it is not there, opens this app's info page instead. */
object OemSettings {

    /** true if a maker-specific screen opened, false if it fell back to the app info page */
    fun open(ctx: Context, guide: Guide): Boolean {
        for ((pkg, cls) in guide.components) {
            try {
                val i = Intent().setClassName(pkg, cls)
                    .putExtra("package_name", ctx.packageName)
                    .putExtra("package_label", "BuildX AI")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
        openAppInfo(ctx)
        return false
    }

    fun openAppInfo(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }
}
