package com.buildx.ai.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/** Reports phone state changes (CallTracker.IDLE / RINGING / OFFHOOK) on the main thread. Needs READ_PHONE_STATE. */
class CallWatcher(private val ctx: Context, private val onState: (Int) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var modern: Any? = null
    private var legacy: Any? = null

    fun start(): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return false
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = Modern { s -> main.post { onState(s) } }
                tm.registerTelephonyCallback(ctx.mainExecutor, cb)
                modern = cb
            } else {
                legacy = registerLegacy(tm)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return
        try {
            (modern as? TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }
            legacy?.let { stopLegacy(tm, it) }
        } catch (_: Exception) {
        }
        modern = null
        legacy = null
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy(tm: TelephonyManager): Any {
        val l = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                main.post { onState(state) }
            }
        }
        tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
        return l
    }

    @Suppress("DEPRECATION")
    private fun stopLegacy(tm: TelephonyManager, l: Any) {
        tm.listen(l as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
    }

    /** Separate class so Android 10 never tries to load TelephonyCallback. */
    @android.annotation.TargetApi(31)
    private class Modern(private val cb: (Int) -> Unit) : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = cb(state)
    }
}
