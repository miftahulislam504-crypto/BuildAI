package com.buildx.ai.whatsapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import com.buildx.ai.LogStore
import com.buildx.ai.call.CallPlacer
import com.buildx.ai.call.PlaceOutcome
import com.buildx.ai.contacts.PhoneNumbers
import com.buildx.ai.system.BackgroundLauncher

/**
 * Starts a WhatsApp voice or video call to a phone number by opening the contact's "call" row that WhatsApp
 * keeps in the phone's contacts. WhatsApp has no official calling API, so this depends on WhatsApp having synced
 * that contact; if it has not, we say so instead of guessing.
 */
class WhatsAppCallPlacer(private val ctx: Context, private val bn: Boolean, private val video: Boolean) : CallPlacer {

    /** which WhatsApp (personal or business) the call went to, once placed */
    @Volatile
    var launchedPackage: String? = null
        private set

    private fun t(bnText: String, enText: String) = if (bn) bnText else enText

    override fun place(number: String, label: String): PlaceOutcome {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return PlaceOutcome(false, t("কন্টাক্ট দেখার পারমিশন নেই। অ্যাপ খুলে পারমিশন দিন", "No permission to read contacts. Open the app and allow it"))
        }
        var installedAny = false
        for (pkg in listOf(WaContacts.PERSONAL, WaContacts.BUSINESS)) {
            if (ctx.packageManager.getLaunchIntentForPackage(pkg) == null) continue
            installedAny = true
            val mime = WaContacts.mime(pkg, video)
            val rowId = findRow(mime, number) ?: continue
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse("content://com.android.contacts/data/$rowId"), mime)
                .setPackage(pkg)
            val ok = BackgroundLauncher.start(ctx, intent)
            LogStore.add(ctx, "WHATSAPP call pkg=$pkg video=$video row=$rowId ok=$ok")
            if (ok) {
                launchedPackage = pkg
                return PlaceOutcome(true)
            }
            return PlaceOutcome(false, t("WhatsApp খোলা যায়নি", "Couldn't open WhatsApp"))
        }
        LogStore.add(ctx, "WHATSAPP call: $number-এর জন্য কল-সারি পাইনি (installed=$installedAny)")
        return PlaceOutcome(
            false,
            if (!installedAny) t("WhatsApp ইনস্টল করা নেই", "WhatsApp is not installed")
            else t(
                "$label-কে WhatsApp-এ পাইনি। এই নম্বরে WhatsApp নেই, অথবা WhatsApp-এ কন্টাক্ট সিঙ্ক বন্ধ",
                "I couldn't find $label on WhatsApp. They may not be on WhatsApp, or WhatsApp contact sync is off"
            )
        )
    }

    private fun findRow(mime: String, number: String): Long? {
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(mime),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                if (PhoneNumbers.same(WaContacts.digits(c.getString(1)), number)) return c.getLong(0)
            }
        }
        return null
    }
}
