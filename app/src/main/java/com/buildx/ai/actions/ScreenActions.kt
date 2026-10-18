package com.buildx.ai.actions

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.buildx.ai.LogStore
import com.buildx.ai.a11y.BuildXAccessibilityService
import com.buildx.ai.system.BackgroundLauncher

private val mainHandler = Handler(Looper.getMainLooper())

/** "স্ক্রিন লক করো": needs the Accessibility service (the only way for a normal app to lock the screen). */
object ScreenLockAction : Action {
    override val name = "screen_lock"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val ctx = ac.context.applicationContext
        val svc = BuildXAccessibilityService.instance
            ?: return ActionResult(
                false,
                if (ac.bn) "স্ক্রিন লক করতে Accessibility সার্ভিস চালু করতে হবে। অ্যাপ খুলে গাইড দেখুন"
                else "To lock the screen the Accessibility service must be on. Open the app for the guide"
            )
        if (!svc.lockScreen()) {
            return ActionResult(false, if (ac.bn) "লক করতে পারিনি" else "I couldn't lock the screen")
        }
        return ActionResult(true, "", deferred = Deferred { report ->
            mainHandler.postDelayed({
                val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                val locked = km.isKeyguardLocked || !pm.isInteractive
                LogStore.add(ctx, "LOCK keyguard=${km.isKeyguardLocked} interactive=${pm.isInteractive}")
                report(
                    if (locked) ActionResult(true, if (ac.bn) "স্ক্রিন লক করেছি" else "Screen locked")
                    else ActionResult(false, if (ac.bn) "লক কমান্ড পাঠিয়েছি, কিন্তু লক হয়েছে মনে হচ্ছে না" else "I sent the lock command but it doesn't look locked")
                )
            }, 800)
        })
    }
}

/**
 * "স্ক্রিন জাগাও" / "আনলক করো": wakes the screen so the lock screen shows. An app cannot unlock the phone
 * (that is the point of the PIN/fingerprint), so for "unlock" it says what to do.
 */
object ScreenWakeAction : Action {
    override val name = "screen_wake"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val ctx = ac.context.applicationContext
        BackgroundLauncher.wakeScreen(ctx)
        val wantUnlock = params["unlock"] == "1"
        return ActionResult(true, "", deferred = Deferred { report ->
            mainHandler.postDelayed({
                val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                val on = pm.isInteractive
                val locked = km.isKeyguardLocked
                LogStore.add(ctx, "WAKE interactive=$on keyguard=$locked")
                report(
                    when {
                        !on -> ActionResult(false, if (ac.bn) "স্ক্রিন জাগাতে পারিনি" else "I couldn't wake the screen")
                        locked && km.isDeviceSecure -> ActionResult(
                            true,
                            if (ac.bn) "স্ক্রিন জাগিয়েছি। ফিঙ্গারপ্রিন্ট বা PIN দিয়ে আনলক করুন" else "Screen is on. Unlock it with your fingerprint or PIN"
                        )
                        locked -> ActionResult(true, if (ac.bn) "স্ক্রিন জাগিয়েছি, উপরে সোয়াইপ করুন" else "Screen is on, swipe up")
                        wantUnlock -> ActionResult(true, if (ac.bn) "ফোন আগেই আনলক করা আছে" else "The phone is already unlocked")
                        else -> ActionResult(true, if (ac.bn) "স্ক্রিন জাগিয়েছি" else "Screen is on")
                    }
                )
            }, 600)
        })
    }
}
