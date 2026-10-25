package com.buildx.ai.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.buildx.ai.R

/**
 * The small set of building blocks every screen is made of (cards, buttons, switches, status pills, a text tab bar),
 * so all tabs look the same and a change of colour or spacing happens in one place.
 *
 * Design rules: no emoji or symbol glyphs anywhere, one ink-coloured accent, hairline borders instead of shadows,
 * status shown with a small dot plus a word. Colours come from res/values/colors.xml and values-night/colors.xml,
 * so light and dark mode both work.
 */
class Ui(val ctx: Context) {

    enum class Kind { OK, WARN, BAD, INFO }

    /** the one slightly heavier weight used for titles, buttons and the active tab */
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun c(id: Int): Int = ctx.getColor(id)

    fun rounded(fill: Int, radiusDp: Int = 14, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun ripple(base: GradientDrawable, rippleColor: Int): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf((rippleColor and 0x00FFFFFF) or 0x22000000), base, null)

    // ---------- layout ----------

    /** a scrolling column of cards */
    fun page(): Pair<ScrollView, LinearLayout> {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(32))
        }
        val sv = ScrollView(ctx).apply {
            isFillViewport = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        return Pair(sv, col)
    }

    /** a flat surface with a hairline border, an optional title and one line of explanation */
    fun card(title: String? = null, subtitle: String? = null): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c(R.color.bx_surface), 16, c(R.color.bx_outline))
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(6), 0, dp(6))
            }
        }
        if (title != null) card.addView(label(title, 15f, bold = true))
        if (subtitle != null) card.addView(hint(subtitle).apply { setPadding(0, dp(3), 0, dp(8)) })
        else if (title != null) card.addView(gap(8))
        return card
    }

    fun gap(h: Int = 8): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    fun divider(): View = View(ctx).apply {
        setBackgroundColor(c(R.color.bx_outline))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(10), 0, dp(10)) }
    }

    // ---------- text ----------

    fun label(text: String, size: Float = 14f, bold: Boolean = false, color: Int = c(R.color.bx_text)): TextView = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = medium
    }

    fun hint(text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(c(R.color.bx_text2))
        setLineSpacing(0f, 1.15f)
    }

    /** a line whose text the screen updates later (status lines) */
    fun statusText(): TextView = TextView(ctx).apply {
        textSize = 12.5f
        setTextColor(c(R.color.bx_text2))
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(2), 0, dp(8))
    }

    fun pill(text: String, kind: Kind): TextView = TextView(ctx).apply { setPill(this, text, kind) }

    /** a small status chip: a dot in the status colour plus a word */
    fun setPill(v: TextView, text: String, kind: Kind) {
        val (bg, fg) = when (kind) {
            Kind.OK -> c(R.color.bx_ok_container) to c(R.color.bx_ok)
            Kind.WARN -> c(R.color.bx_warn_container) to c(R.color.bx_warn)
            Kind.BAD -> c(R.color.bx_bad_container) to c(R.color.bx_bad)
            Kind.INFO -> c(R.color.bx_primary_container) to c(R.color.bx_on_primary_container)
        }
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fg)
            setSize(dp(6), dp(6))
        }
        v.text = text
        v.textSize = 12f
        v.setTextColor(fg)
        v.typeface = medium
        v.background = rounded(bg, 20)
        v.setPadding(dp(10), dp(4), dp(12), dp(4))
        v.gravity = Gravity.CENTER
        v.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
        v.compoundDrawablePadding = dp(6)
    }

    // ---------- buttons ----------

    private fun styled(label: String, fill: Int, fg: Int, stroke: Int?, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        typeface = medium
        setTextColor(fg)
        stateListAnimator = null
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = ripple(rounded(fill, 12, stroke), fg)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(4))
        }
    }

    /** the one main action on a card: solid ink */
    fun primary(label: String, onClick: () -> Unit): Button = styled(label, c(R.color.bx_primary), c(R.color.bx_on_primary), null, onClick)

    /** a secondary action: soft neutral fill */
    fun tonal(label: String, onClick: () -> Unit): Button = styled(label, c(R.color.bx_primary_container), c(R.color.bx_on_primary_container), null, onClick)

    /** a quiet action: outline only */
    fun quiet(label: String, onClick: () -> Unit): Button = styled(label, Color.TRANSPARENT, c(R.color.bx_text), c(R.color.bx_outline), onClick)

    /** two buttons side by side */
    fun pair(a: Button, b: Button): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        for (x in listOf(a, b)) {
            x.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
            addView(x)
        }
    }

    // ---------- inputs ----------

    fun input(hint: String, type: Int = InputType.TYPE_CLASS_TEXT, text: String = "", lines: Int = 1): EditText = EditText(ctx).apply {
        this.hint = hint
        inputType = type
        setText(text)
        textSize = 14f
        setTextColor(c(R.color.bx_text))
        setHintTextColor(c(R.color.bx_text2))
        background = rounded(c(R.color.bx_surface_alt), 12)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        minLines = lines
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(4))
        }
    }

    /** title (and optional explanation) on the left, a switch on the right */
    fun switchRow(title: String, desc: String? = null, checked: Boolean = false): Pair<LinearLayout, Switch> {
        val sw = Switch(ctx).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(c(R.color.bx_on_primary), c(R.color.bx_surface))
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(c(R.color.bx_primary), c(R.color.bx_outline))
            )
        }
        val text = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 14f))
            if (desc != null) addView(hint(desc).apply { setPadding(0, dp(2), 0, 0) })
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(16) })
            addView(sw)
            setOnClickListener { sw.toggle() }
        }
        return Pair(row, sw)
    }

    fun radio(text: String, id: Int): RadioButton = RadioButton(ctx).apply {
        this.text = text
        this.id = id
        textSize = 14f
        setTextColor(c(R.color.bx_text))
        buttonTintList = ColorStateList.valueOf(c(R.color.bx_primary))
        minHeight = dp(44)
        setPadding(dp(8), dp(4), dp(4), dp(4))
    }

    /** a tap-to-open explanation, so long instructions do not push the controls off the screen */
    fun expandable(title: String, body: String): LinearLayout {
        val bodyView = hint(body).apply { visibility = View.GONE; setPadding(0, dp(2), 0, dp(10)) }
        val toggle = TextView(ctx).apply {
            text = "Show"
            textSize = 12f
            setTextColor(c(R.color.bx_text2))
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(label(title, 13f, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle)
            setOnClickListener {
                val open = bodyView.visibility == View.VISIBLE
                bodyView.visibility = if (open) View.GONE else View.VISIBLE
                toggle.text = if (open) "Show" else "Hide"
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(head)
            addView(bodyView)
        }
    }

    /** one line of a checklist: a name, a status chip (Ready / Pending) and, while it is not done, a button that fixes it */
    inner class StatusRow(private val name: String, private val check: () -> Boolean, private val fix: (() -> Unit)?, private val required: Boolean = true) {
        private val pillView = TextView(ctx)
        private val fixBtn: TextView = TextView(ctx).apply {
            text = "Grant"
            textSize = 12.5f
            typeface = medium
            setTextColor(c(R.color.bx_text))
            background = rounded(Color.TRANSPARENT, 20, c(R.color.bx_outline))
            setPadding(dp(14), dp(5), dp(14), dp(5))
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { fix?.invoke() }
        }
        val view: LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            addView(label(name, 14f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
            addView(pillView)
            addView(fixBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        }

        var ok: Boolean = false
            private set

        fun refresh() {
            ok = try { check() } catch (_: Exception) { false }
            setPill(pillView, if (ok) "Ready" else "Pending", if (ok) Kind.OK else if (required) Kind.BAD else Kind.WARN)
            fixBtn.visibility = if (!ok && fix != null) View.VISIBLE else View.GONE
        }

        val label: String get() = name
        val isRequired: Boolean get() = required
    }

    // ---------- home status mark ----------

    /** the status mark on the home screen: a thin ring with a dot, green while listening */
    inner class Orb {
        private val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), c(R.color.bx_outline))
        }
        private val core = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(c(R.color.bx_text2))
        }
        val view: FrameLayout = FrameLayout(ctx).apply {
            background = ring
            addView(View(ctx).apply { background = core }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(88)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, dp(12), 0, dp(16))
            }
        }

        fun set(on: Boolean) {
            ring.setStroke(dp(2), if (on) c(R.color.bx_ok) else c(R.color.bx_outline))
            core.setColor(if (on) c(R.color.bx_ok) else c(R.color.bx_text2))
            view.invalidate()
        }
    }

    // ---------- tab bar ----------

    /** text-only tabs: the active one is darker and heavier, with a short bar above it */
    class TabBar(private val ui: Ui, items: List<String>, private val onSelect: (Int) -> Unit) {
        private val bars = ArrayList<View>()
        private val labels = ArrayList<TextView>()
        val view: LinearLayout = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ui.c(R.color.bx_bg))
            setPadding(ui.dp(8), 0, ui.dp(8), 0)
        }

        init {
            items.forEachIndexed { i, name ->
                val bar = View(ui.ctx).apply { background = ui.rounded(ui.c(R.color.bx_primary), 2) }
                val lb = TextView(ui.ctx).apply {
                    text = name
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, ui.dp(12), 0, ui.dp(14))
                }
                bars.add(bar); labels.add(lb)
                val col = LinearLayout(ui.ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(bar, LinearLayout.LayoutParams(ui.dp(20), ui.dp(2)))
                    addView(lb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    setOnClickListener { select(i); onSelect(i) }
                }
                view.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            select(0)
        }

        fun select(index: Int) {
            for (i in labels.indices) {
                val on = i == index
                bars[i].visibility = if (on) View.VISIBLE else View.INVISIBLE
                labels[i].setTextColor(if (on) ui.c(R.color.bx_text) else ui.c(R.color.bx_text2))
                labels[i].typeface = if (on) ui.medium else Typeface.DEFAULT
            }
        }
    }
}
