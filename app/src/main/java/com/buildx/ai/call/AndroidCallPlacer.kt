package com.buildx.ai.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import com.buildx.ai.contacts.PhoneNumbers

/**
 * Places the call through TelecomManager (works from the background service and lets us pick the SIM),
 * falling back to a normal CALL intent. Emergency numbers are never dialed automatically.
 */
class AndroidCallPlacer(private val ctx: Context, private val bn: Boolean) : CallPlacer {

    private fun t(bnText: String, enText: String) = if (bn) bnText else enText

    override fun place(number: String, label: String): PlaceOutcome {
        if (PhoneNumbers.isEmergency(number)) return openDialer(number)

        if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return PlaceOutcome(false, t("কল করার পারমিশন নেই। অ্যাপ খুলে পারমিশন দিন", "No call permission. Open the app and allow it"))
        }
        val uri = Uri.fromParts("tel", number, null)
        val sim = SimHelper.chosen(ctx)
        var error: String? = null

        try {
            val telecom = ctx.getSystemService(TelecomManager::class.java)
            val extras = Bundle()
            if (sim != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sim)
            telecom.placeCall(uri, extras)
            return started(label)
        } catch (e: Exception) {
            error = "${e.javaClass.simpleName}: ${e.message}"
        }

        try {
            val i = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (sim != null) i.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sim)
            ctx.startActivity(i)
            return started(label)
        } catch (e: Exception) {
            error = "$error / ${e.javaClass.simpleName}: ${e.message}"
        }
        return PlaceOutcome(false, t("কল করতে পারিনি ($error)", "I couldn't place the call ($error)"))
    }

    private fun started(label: String): PlaceOutcome {
        val tracker = CallTracker.shared
        tracker.expectOutgoing(label, bn, System.currentTimeMillis())
        Handler(Looper.getMainLooper()).postDelayed({
            tracker.checkNotStarted(System.currentTimeMillis())?.let { tracker.announce?.invoke(it) }
        }, CallTracker.NOT_STARTED_AFTER_MS + 1000)
        return PlaceOutcome(true)
    }

    /** 999 / 112 / 911: open the dialer with the number filled in; the person presses call themselves */
    private fun openDialer(number: String): PlaceOutcome = try {
        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        PlaceOutcome(true, t("জরুরি নম্বর, তাই ডায়ালার খুলেছি। নিজে কল বোতাম চাপুন", "Emergency number: I opened the dialer, press call yourself"))
    } catch (e: Exception) {
        PlaceOutcome(false, t("ডায়ালার খোলা যায়নি। নিজে ডায়াল করুন", "Couldn't open the dialer. Please dial yourself"))
    }
}
