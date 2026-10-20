package com.buildx.ai.actions

import android.content.Context
import com.buildx.ai.LogStore
import com.buildx.ai.a11y.BuildXAccessibilityService
import com.buildx.ai.call.CallFlow
import com.buildx.ai.contacts.AndroidContacts
import com.buildx.ai.contacts.NicknameBook
import com.buildx.ai.system.MainScheduler
import com.buildx.ai.system.Verdict
import com.buildx.ai.system.Verify
import com.buildx.ai.whatsapp.WhatsAppCallPlacer

private fun ActionContext.t(bnText: String, enText: String) = if (bn) bnText else enText

/** "রহিমকে হোয়াটসঅ্যাপে কল করো" / "ভিডিও কল করো": same conversation as a phone call, but the call goes through WhatsApp. */
object WhatsAppCallAction : Action {
    override val name = "whatsapp_call"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val ctx = ac.context.applicationContext
        val prefs = ctx.getSharedPreferences("buildx", Context.MODE_PRIVATE)
        val video = params["video"] == "1"
        val placer = WhatsAppCallPlacer(ctx, ac.bn, video)
        val flow = CallFlow(
            bn = ac.bn,
            contacts = AndroidContacts(ctx),
            placer = placer,
            nicknames = NicknameBook.parse(prefs.getString("nicknames", "") ?: ""),
            confirm = prefs.getBoolean("confirmCall", true) || params["via"] == "llm", // a call the model chose is always confirmed
            log = { LogStore.add(ctx, it) },
            viaBn = if (video) "WhatsApp-এ ভিডিও " else "WhatsApp-এ ",
            viaEn = if (video) " on WhatsApp video" else " on WhatsApp"
        )
        return verify(ac, ctx, placer, flow.start(params["target"], params["number"]))
    }

    /** once the call has been requested, check (with Accessibility, if on) that WhatsApp really came to the front */
    private fun verify(ac: ActionContext, ctx: Context, placer: WhatsAppCallPlacer, r: ActionResult): ActionResult {
        val ask = r.followUp
        if (ask != null) return r.copy(followUp = FollowUp { reply -> verify(ac, ctx, placer, ask.onReply(reply)) })
        val pkg = placer.launchedPackage
        if (!r.success || pkg == null) return r
        val svc = BuildXAccessibilityService.instance
        return ActionResult(true, "", deferred = Deferred { report ->
            var front: String? = null
            Verify.poll(MainScheduler, 600, 6, {
                if (svc == null) Verdict.UNKNOWN
                else {
                    front = svc.rootInActiveWindow?.packageName?.toString()
                    if (front == pkg) Verdict.YES else Verdict.NO
                }
            }) { v ->
                LogStore.add(ctx, "WHATSAPP verify expected=$pkg front=$front -> $v")
                report(
                    when (v) {
                        Verdict.YES -> r
                        Verdict.UNKNOWN -> ActionResult(true, r.message + ac.t(" (WhatsApp-এ শুরু হয়েছে কি না যাচাই করতে পারিনি)", " (couldn't verify it started)"))
                        Verdict.NO -> ActionResult(
                            false,
                            ac.t("WhatsApp কল স্ক্রিন সামনে আসেনি। ফোন লক থাকলে আনলক করুন, নইলে \"অন্য অ্যাপের উপর দেখানো\" পারমিশন দিন", "The WhatsApp call screen did not appear. Unlock the phone or allow \"display over other apps\"")
                        )
                    }
                )
            }
        })
    }
}
