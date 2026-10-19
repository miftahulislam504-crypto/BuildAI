package com.buildx.ai.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.buildx.ai.WakeService
import com.buildx.ai.voice.Conversation
import com.buildx.ai.voice.Speaker

/**
 * The popup: a small dark card with the last few lines of what the assistant heard, understood and did.
 * Shown when the assistant is invoked (side key / home gesture) or when "Hey BuildX" is heard.
 */
class BuildXSession(ctx: Context) : VoiceInteractionSession(ctx) {

    private var logView: TextView? = null
    private var speaker: Speaker? = null
    private var conversation: Conversation? = null

    override fun onCreateContentView(): View {
        val c = context
        val dp = c.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val title = TextView(c).apply {
            text = "BuildX AI"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        val log = TextView(c).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, px(8), 0, px(8))
            text = "…"
        }
        logView = log
        val close = Button(c).apply {
            text = "বন্ধ"
            setOnClickListener { hide() }
        }
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(18), px(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0202124"))
                cornerRadius = px(22).toFloat()
            }
            addView(title)
            addView(log)
            addView(close)
        }
        return FrameLayout(c).apply {
            addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                setMargins(px(12), px(12), px(12), px(24))
            })
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        AssistantBridge.session = this
        AssistantBridge.render = { lines -> logView?.text = if (lines.isEmpty()) "…" else lines.joinToString("\n") }
        val fromWake = args?.getBoolean("fromWake") == true
        if (!fromWake) {
            // the person pressed the assistant key / used the gesture: start listening now, no wake word needed
            AssistantBridge.clear()
            val svc = WakeService.instance
            if (svc != null) svc.triggerConversation(showPopup = false) else startLocal()
        }
    }

    /** The listening service is not running: listen once from here. */
    private fun startLocal() {
        val lang = context.getSharedPreferences("buildx", Context.MODE_PRIVATE).getString("lang", "bn-BD") ?: "bn-BD"
        val sp = Speaker(context)
        speaker = sp
        sp.init(lang) {
            val conv = Conversation(context, lang, sp) { AssistantBridge.post(it) }
            conversation = conv
            conv.run { AssistantBridge.hideLater(2500) }
        }
    }

    override fun onHide() {
        conversation?.cancel()
        conversation = null
        speaker?.shutdown()
        speaker = null
        AssistantBridge.session = null
        AssistantBridge.render = null
        super.onHide()
    }
}
