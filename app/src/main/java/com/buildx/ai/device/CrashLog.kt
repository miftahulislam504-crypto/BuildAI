package com.buildx.ai.device

import android.content.Context
import android.os.Build
import java.io.File

/** Writes uncaught crashes to a local file (nothing leaves the phone), so a crash on a friend's phone can be read later. */
object CrashLog {
    private const val FILE = "crash.log"

    fun install(app: Context) {
        val ctx = app.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val version = try {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                } catch (_: Exception) {
                    "?"
                }
                val header = "app=$version ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}"
                val f = File(ctx.filesDir, FILE)
                val old = if (f.exists()) f.readText() else ""
                f.writeText(CrashFormat.append(old, CrashFormat.entry(System.currentTimeMillis(), thread.name, error, header)))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(ctx: Context): String? = try {
        val f = File(ctx.filesDir, FILE)
        if (f.exists()) f.readText().takeIf { it.isNotBlank() } else null
    } catch (_: Exception) {
        null
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }
}
