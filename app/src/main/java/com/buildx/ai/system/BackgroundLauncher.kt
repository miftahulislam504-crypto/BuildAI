package com.buildx.ai.system

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import com.buildx.ai.LogStore

/**
 * Starting an activity from the background service is restricted on Android 10+. Two helps:
 * the screen is woken first, and if the "display over other apps" permission is granted a 1-pixel invisible
 * overlay is shown for a moment (Android 15 only honours that permission while an overlay is visible).
 */
object BackgroundLauncher {

    private val main = Handler(Looper.getMainLooper())

    fun canOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    @Suppress("DEPRECATION")
    fun wakeScreen(ctx: Context) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "buildx:screen"
            )
            wl.acquire(5000)
        } catch (e: Exception) {
            LogStore.add(ctx, "স্ক্রিন জাগানো যায়নি: ${e.message}")
        }
    }

    /** true if the start request was accepted; the system may still silently block it, which cannot be detected here */
    fun start(ctx: Context, intent: Intent, wake: Boolean = true): Boolean {
        val app = ctx.applicationContext
        if (wake) wakeScreen(app)
        val anchor = showAnchor(app)
        return try {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (anchor == null) LogStore.add(app, "লঞ্চ: ওভারলে পারমিশন নেই, ব্যাকগ্রাউন্ড থেকে খোলা বাধা পেতে পারে")
            true
        } catch (e: Exception) {
            LogStore.add(app, "লঞ্চ ব্যর্থ: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            if (anchor != null) main.postDelayed({ removeAnchor(app, anchor) }, 3000)
        }
    }

    private fun showAnchor(ctx: Context): View? {
        if (!canOverlay(ctx)) return null
        return try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = View(ctx)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(v, lp)
            v
        } catch (e: Exception) {
            null
        }
    }

    private fun removeAnchor(ctx: Context, v: View) {
        try {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
    }
}
