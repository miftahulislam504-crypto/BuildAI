package com.buildx.ai.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.buildx.ai.R
import com.buildx.ai.WakeService
import com.buildx.ai.voice.Conversation
import com.buildx.ai.voice.Speaker

/**
 * The popup: a quiet card that slides up from the bottom, in the same colours as the app (light or dark).
 * It shows the last exchange (what was heard, what the assistant answered), a small ring that is green and
 * pulsing while it listens, and a status word. What it shows comes from [PopupState].
 * Shown when the assistant is invoked (side key / home gesture) or when "Hey BuildX" is heard.
 */
class BuildXSession(ctx: Context) : VoiceInteractionSession(ctx) {

    private var speaker: Speaker? = null
    private var conversation: Conversation? = null

    // the pieces render() updates
    private var card: View? = null
    private var body: View? = null
    private var orb: View? = null
    private var ring: GradientDrawable? = null
    private var core: GradientDrawable? = null
    private var statusView: TextView? = null
    private var placeholder: TextView? = null
    private var youBlock: View? = null
    private var heardView: TextView? = null
    private var replyBlock: View? = null
    private var replyView: TextView? = null
    private var hintView: TextView? = null
    private var pulse: ValueAnimator? = null
    private var shown: Pair<String?, String?> = Pair(null, null)

    private fun px(v: Int) = (v * context.resources.displayMetrics.density + 0.5f).toInt()
    private fun col(id: Int) = context.getColor(id)

    override fun onCreateContentView(): View {
        val c = context
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT

        fun caption(t: String) = TextView(c).apply {
            text = t
            textSize = 11f
            letterSpacing = 0.1f
            isAllCaps = true
            typeface = medium
            setTextColor(col(R.color.bx_text2))
        }

        // header: status ring, name, status word
        val ringDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(px(2), col(R.color.bx_outline))
        }
        val coreDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(col(R.color.bx_text2))
        }
        val orbView = FrameLayout(c).apply {
            background = ringDrawable
            addView(View(c).apply { background = coreDrawable }, FrameLayout.LayoutParams(px(8), px(8), Gravity.CENTER))
        }
        val name = TextView(c).apply {
            text = "BuildX AI"
            textSize = 15f
            typeface = medium
            setTextColor(col(R.color.bx_text))
        }
        val status = TextView(c).apply {
            textSize = 12f
            setTextColor(col(R.color.bx_text2))
        }
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(orbView, LinearLayout.LayoutParams(px(22), px(22)))
            addView(name, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = px(10) })
            addView(status)
        }

        // body: a prompt when nothing has been said yet, then "you" and the reply
        val empty = TextView(c).apply {
            text = "Say a command"
            textSize = 18f
            setTextColor(col(R.color.bx_text2))
            setPadding(0, px(18), 0, px(6))
        }
        val heard = TextView(c).apply {
            textSize = 15f
            setTextColor(col(R.color.bx_text2))
            setLineSpacing(0f, 1.15f)
            setPadding(0, px(4), 0, 0)
        }
        val you = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(18), 0, 0)
            addView(caption("You"))
            addView(heard)
        }
        val reply = TextView(c).apply {
            textSize = 19f
            typeface = medium
            setTextColor(col(R.color.bx_text))
            setLineSpacing(0f, 1.15f)
            setPadding(0, px(4), 0, 0)
        }
        val answer = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(16), 0, 0)
            addView(caption("BuildX"))
            addView(reply)
        }
        val bodyBox = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(empty)
            addView(you)
            addView(answer)
        }

        // footer: a hint on the left, a quiet Close on the right
        val hint = TextView(c).apply {
            textSize = 12f
            setTextColor(col(R.color.bx_text2))
        }
        val close = TextView(c).apply {
            text = "Close"
            textSize = 13f
            typeface = medium
            setTextColor(col(R.color.bx_text))
            setPadding(px(16), px(8), px(16), px(8))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = px(20).toFloat()
                setStroke(px(1), col(R.color.bx_outline))
            }
            setOnClickListener { hide() }
        }
        val footer = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(18), 0, 0)
            addView(hint, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(close)
        }

        val cardView = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(18), px(20), px(16))
            background = GradientDrawable().apply {
                setColor(col(R.color.bx_surface))
                cornerRadius = px(24).toFloat()
                setStroke(px(1), col(R.color.bx_outline))
            }
            elevation = px(10).toFloat()
            addView(header)
            addView(bodyBox)
            addView(footer)
        }

        card = cardView
        body = bodyBox
        orb = orbView
        ring = ringDrawable
        core = coreDrawable
        statusView = status
        placeholder = empty
        youBlock = you
        heardView = heard
        replyBlock = answer
        replyView = reply
        hintView = hint

        return FrameLayout(c).apply {
            addView(cardView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                setMargins(px(12), px(12), px(12), px(24))
            })
        }
    }

    /** redraw the popup for the lines posted so far */
    private fun render(lines: List<String>) {
        val s = PopupState.from(lines)
        val hasHeard = !s.heard.isNullOrBlank()
        val hasReply = !s.reply.isNullOrBlank()

        statusView?.text = when (s.status) {
            PopupState.Status.LISTENING -> "Listening"
            PopupState.Status.THINKING -> "Working on it"
            PopupState.Status.REPLIED -> "Replying"
            PopupState.Status.CLOSED -> "Closed"
        }
        placeholder?.visibility = if (!hasHeard && !hasReply) View.VISIBLE else View.GONE
        youBlock?.visibility = if (hasHeard) View.VISIBLE else View.GONE
        heardView?.text = s.heard ?: ""
        replyBlock?.visibility = if (hasReply) View.VISIBLE else View.GONE
        replyView?.text = s.reply ?: ""
        replyView?.setTextColor(col(if (s.replyKind == PopupState.ReplyKind.FAILED) R.color.bx_bad else R.color.bx_text))
        hintView?.text = when (s.status) {
            PopupState.Status.LISTENING -> "Closes after 5 seconds of silence"
            PopupState.Status.CLOSED -> s.closedReason?.replaceFirstChar { it.uppercase() } ?: ""
            else -> ""
        }
        paintOrb(s)

        // a soft fade whenever the words change
        val now = Pair(s.heard, s.reply)
        if (now != shown) {
            shown = now
            body?.alpha = 0.3f
            body?.animate()?.alpha(1f)?.setDuration(180)?.start()
        }
    }

    /** green and pulsing while listening, dark while working, red after a failure, grey once closed */
    private fun paintOrb(s: PopupState) {
        val dot = when {
            s.status == PopupState.Status.LISTENING -> R.color.bx_ok
            s.status == PopupState.Status.THINKING -> R.color.bx_text
            s.status == PopupState.Status.REPLIED && s.replyKind == PopupState.ReplyKind.FAILED -> R.color.bx_bad
            s.status == PopupState.Status.REPLIED -> R.color.bx_ok
            else -> R.color.bx_text2
        }
        val edge = if (s.status == PopupState.Status.CLOSED) R.color.bx_outline else dot
        ring?.setStroke(px(2), col(edge))
        core?.setColor(col(dot))
        orb?.invalidate()
        pulse(s.status == PopupState.Status.LISTENING)
    }

    private fun pulse(on: Boolean) {
        if (on) {
            if (pulse?.isRunning == true) return
            pulse = ValueAnimator.ofFloat(1f, 1.18f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener {
                    val v = it.animatedValue as Float
                    orb?.scaleX = v
                    orb?.scaleY = v
                }
                start()
            }
        } else {
            pulse?.cancel()
            pulse = null
            orb?.scaleX = 1f
            orb?.scaleY = 1f
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        AssistantBridge.session = this
        AssistantBridge.render = { lines -> render(lines) }
        shown = Pair(null, null)
        render(AssistantBridge.current())
        card?.let {
            it.translationY = px(36).toFloat()
            it.alpha = 0f
            it.animate().translationY(0f).alpha(1f).setDuration(220).start()
        }
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
        pulse(false)
        card?.animate()?.cancel()
        conversation?.cancel()
        conversation = null
        speaker?.shutdown()
        speaker = null
        AssistantBridge.session = null
        AssistantBridge.render = null
        super.onHide()
    }
}
