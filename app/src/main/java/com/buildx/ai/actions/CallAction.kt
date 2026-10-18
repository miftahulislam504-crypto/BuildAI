package com.buildx.ai.actions

import android.content.Context
import com.buildx.ai.LogStore
import com.buildx.ai.call.AndroidCallPlacer
import com.buildx.ai.call.CallFlow
import com.buildx.ai.contacts.AndroidContacts
import com.buildx.ai.contacts.NicknameBook

/** "রহিমকে ফোন করো": the conversation itself lives in [CallFlow]; this only plugs in the Android parts. */
object CallAction : Action {
    override val name = "call"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val ctx = ac.context.applicationContext
        val prefs = ctx.getSharedPreferences("buildx", Context.MODE_PRIVATE)
        val flow = CallFlow(
            bn = ac.bn,
            contacts = AndroidContacts(ctx),
            placer = AndroidCallPlacer(ctx, ac.bn),
            nicknames = NicknameBook.parse(prefs.getString("nicknames", "") ?: ""),
            confirm = prefs.getBoolean("confirmCall", true),
            log = { LogStore.add(ctx, it) }
        )
        return flow.start(params["target"], params["number"])
    }
}
