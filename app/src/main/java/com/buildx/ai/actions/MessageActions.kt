package com.buildx.ai.actions

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import com.buildx.ai.LogStore
import com.buildx.ai.a11y.BuildXAccessibilityService
import com.buildx.ai.call.MessageFlow
import com.buildx.ai.call.MessageSender
import com.buildx.ai.call.WaNumber
import com.buildx.ai.contacts.AndroidContacts
import com.buildx.ai.contacts.NicknameBook
import com.buildx.ai.contacts.PhoneNumbers
import com.buildx.ai.system.BackgroundLauncher
import com.buildx.ai.system.MainScheduler
import com.buildx.ai.whatsapp.WaContacts
import java.util.UUID

private fun ActionContext.t(bnText: String, enText: String) = if (bn) bnText else enText

private fun flow(ac: ActionContext, sender: MessageSender, channelBn: String, channelEn: String): MessageFlow {
    val ctx = ac.context.applicationContext
    val prefs = ctx.getSharedPreferences("buildx", Context.MODE_PRIVATE)
    return MessageFlow(
        bn = ac.bn,
        contacts = AndroidContacts(ctx),
        sender = sender,
        nicknames = NicknameBook.parse(prefs.getString("nicknames", "") ?: ""),
        channelBn = channelBn,
        channelEn = channelEn,
        log = { LogStore.add(ctx, it) }
    )
}

/** "রহিমকে মেসেজ পাঠাও আমি আসছি": always reads the text back and waits for a yes before sending. */
object SendSmsAction : Action {
    override val name = "send_sms"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult =
        flow(ac, AndroidSmsSender(ac.context.applicationContext, ac.bn), "এসএমএস", "by SMS")
            .start(params["target"], params["number"], params["text"])
}

/** the same, through WhatsApp: opens the chat with the text filled in, then presses Send (needs Accessibility). */
object SendWhatsAppAction : Action {
    override val name = "send_whatsapp"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult =
        flow(ac, WhatsAppSender(ac.context.applicationContext, ac.bn), "WhatsApp-এ", "on WhatsApp")
            .start(params["target"], params["number"], params["text"])
}

class AndroidSmsSender(private val ctx: Context, private val bn: Boolean) : MessageSender {

    private fun t(bnText: String, enText: String) = if (bn) bnText else enText

    @Suppress("DEPRECATION")
    override fun send(number: String, label: String, text: String): ActionResult {
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return ActionResult(false, t("এসএমএস পাঠানোর পারমিশন নেই। অ্যাপ খুলে পারমিশন দিন (না এলে অ্যাপ ইনফো থেকে \"Allow restricted settings\")", "No permission to send SMS. Open the app and allow it (if it won't appear, use \"Allow restricted settings\" in App info)"))
        }
        val dial = PhoneNumbers.dialable(number) ?: return ActionResult(false, t("নম্বরটা ঠিক মনে হচ্ছে না", "That number doesn't look right"))
        val sm: SmsManager = try {
            if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        } catch (e: Exception) {
            return ActionResult(false, t("এসএমএস সার্ভিস পাইনি", "No SMS service"))
        }
        val parts = sm.divideMessage(text)
        val action = "com.buildx.ai.SMS_SENT." + UUID.randomUUID()
        var left = parts.size
        var failure: Int? = null
        var finished = false
        var receiver: BroadcastReceiver? = null

        return ActionResult(true, "", deferred = Deferred { report ->
            fun finish(r: ActionResult) {
                if (finished) return
                finished = true
                try { receiver?.let { ctx.unregisterReceiver(it) } } catch (_: Exception) {}
                report(r)
            }
            receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (resultCode != Activity.RESULT_OK && failure == null) failure = resultCode
                    if (--left <= 0) {
                        val f = failure
                        LogStore.add(ctx, "SMS result to=$label parts=${parts.size} failure=$f")
                        finish(
                            if (f == null) ActionResult(true, t("${label}কে এসএমএস পাঠিয়েছি", "SMS sent to $label"))
                            else ActionResult(false, t("এসএমএস যায়নি (কোড $f)। সিম বা নেটওয়ার্ক দেখুন", "The SMS didn't go (code $f). Check the SIM and signal"))
                        )
                    }
                }
            }
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(receiver, filter)
            val sent = ArrayList<PendingIntent>()
            for (i in parts.indices) {
                sent.add(PendingIntent.getBroadcast(ctx, i, Intent(action).setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            try {
                sm.sendMultipartTextMessage(dial, null, parts, sent, null)
            } catch (e: Exception) {
                LogStore.add(ctx, "SMS send failed: ${e.javaClass.simpleName}: ${e.message}")
                finish(ActionResult(false, t("এসএমএস পাঠানো যায়নি", "Couldn't send the SMS")))
                return@Deferred
            }
            MainScheduler.after(25_000) {
                finish(ActionResult(true, t("এসএমএস পাঠানোর অনুরোধ দিয়েছি, কিন্তু পৌঁছেছে কি না নিশ্চিত নই", "I asked the phone to send it, but I'm not sure it went")))
            }
        })
    }
}

class WhatsAppSender(private val ctx: Context, private val bn: Boolean) : MessageSender {

    private fun t(bnText: String, enText: String) = if (bn) bnText else enText

    override fun send(number: String, label: String, text: String): ActionResult {
        val digits = WaNumber.digits(number) ?: return ActionResult(false, t("নম্বরটা ঠিক মনে হচ্ছে না", "That number doesn't look right"))
        val pkg = listOf(WaContacts.PERSONAL, WaContacts.BUSINESS).firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }
            ?: return ActionResult(false, t("WhatsApp ইনস্টল করা নেই", "WhatsApp is not installed"))
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + Uri.encode(text))).setPackage(pkg)
        val ok = BackgroundLauncher.start(ctx, intent)
        LogStore.add(ctx, "WHATSAPP message to=$label pkg=$pkg ok=$ok len=${text.length}")
        if (!ok) return ActionResult(false, t("WhatsApp খোলা যায়নি", "Couldn't open WhatsApp"))
        val svc = BuildXAccessibilityService.instance
            ?: return ActionResult(true, t("${label}-এর চ্যাট লেখাসহ খুলেছি। সেন্ড চাপুন, Accessibility চালু থাকলে আমিই চাপতাম", "I opened the chat with the text filled in. Tap Send. With Accessibility on, I'd do that for you"))

        return ActionResult(true, "", deferred = Deferred { report ->
            val queries = listOf("send", "পাঠান", "সেন্ড")
            ScreenWait.tapButton(ctx, svc, queries, tries = 10, gapMs = 800, expectPkg = pkg) { tapped ->
                if (!tapped) {
                    report(ActionResult(false, t("${label}-এর চ্যাট খুলেছি, কিন্তু সেন্ড বোতাম পাইনি। নিজে সেন্ড চাপুন", "I opened the chat but couldn't find Send. Please tap it yourself")))
                } else {
                    // after a real send the Send button turns back into the voice-message button
                    MainScheduler.after(1500) {
                        val still = ScreenWait.hasButton(svc, ctx, queries, pkg)
                        LogStore.add(ctx, "WHATSAPP send tapped, send button still there=$still")
                        report(
                            if (!still) ActionResult(true, t("${label}কে WhatsApp-এ পাঠিয়েছি", "Sent to $label on WhatsApp"))
                            else ActionResult(true, t("সেন্ড চেপেছি, কিন্তু গেছে কি না নিশ্চিত নই। চ্যাটটা দেখে নিন", "I tapped Send, but I'm not sure it went. Please check the chat"))
                        )
                    }
                }
            }
        })
    }
}
