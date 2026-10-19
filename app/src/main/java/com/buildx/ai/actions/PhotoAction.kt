package com.buildx.ai.actions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.buildx.ai.photo.PhotoActivity
import com.buildx.ai.photo.PhotoBridge
import com.buildx.ai.system.BackgroundLauncher

/** "ছবি তোলো" / "সেলফি তোলো": opens the camera screen, 3 second countdown, saves to Pictures/BuildX. */
object PhotoAction : Action {
    override val name = "take_photo"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val ctx = ac.context.applicationContext
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return ActionResult(false, if (ac.bn) "ক্যামেরার পারমিশন নেই। অ্যাপ খুলে পারমিশন দিন" else "No camera permission. Open the app and allow it")
        }
        val front = params["camera"] == "front"
        return ActionResult(
            true,
            if (ac.bn) (if (front) "সেলফি তুলছি, ক্যামেরার দিকে তাকান" else "ছবি তুলছি, ক্যামেরা তাক করুন") else "Taking the photo, aim the camera",
            deferred = Deferred { report ->
                PhotoBridge.expect { ok, msg -> report(ActionResult(ok, msg)) }
                val started = BackgroundLauncher.start(
                    ctx,
                    Intent(ctx, PhotoActivity::class.java).putExtra("camera", if (front) "front" else "back").putExtra("bn", ac.bn)
                )
                if (!started) {
                    PhotoBridge.finish(false, if (ac.bn) "ক্যামেরা স্ক্রিন খোলা যায়নি" else "Couldn't open the camera screen")
                } else {
                    // safety net: never leave the conversation waiting forever
                    Handler(Looper.getMainLooper()).postDelayed({
                        PhotoBridge.finish(false, if (ac.bn) "ছবি তোলা শেষ হয়নি" else "The photo did not finish")
                    }, 30_000)
                }
            }
        )
    }
}
