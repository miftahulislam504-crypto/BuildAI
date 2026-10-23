package com.buildx.ai.ui

import android.content.Context
import android.content.res.ColorStateList
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
 * Phase 5/6 redesign: the small set of building blocks every screen is made of (cards, buttons, switches, status
 * pills, a bottom tab bar), so all four tabs look the same and a change of colour or spacing happens in one place.
 * Colours come from res/values/colors.xml and values-night/colors.xml, so light and dark mode both work.
 */
class Ui(val ctx: Context) {

    enum class Kind { OK, WARN, BAD, INFO }

    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun c(id: Int): Int = ctx.getColor(id)

    fun rounded(fill: Int, radiusDp: Int = 16, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun ripple(base: GradientDrawable, rippleColor: Int): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf((rippleColor and 0x00FFFFFF) or 0x33000000), base, null)

    // ---------- layout ----------

    /** a scrolling column of cards */
    fun page(): Pair<ScrollView, LinearLayout> {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val sv = ScrollView(ctx).apply {
            isFillViewport = false
            clipToPadding = false
            addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        return Pair(sv, col)
    }

    /** a rounded white (or dark) card with an optional title and one-line explanation */
    fun card(title: String? = null, subtitle: String? = null): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c(R.color.bx_surface), 18, c(R.color.bx_outline))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(6), 0, dp(6))
            }
        }
        if (title != null) card.addView(label(title, 16f, bold = true))
        if (subtitle != null) card.addView(hint(subtitle).apply { setPadding(0, dp(2), 0, dp(4)) })
        return card
    }

    fun gap(h: Int = 8): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    fun divider(): View = View(ctx).apply {
        setBackgroundColor(c(R.color.bx_outline))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(8), 0, dp(8)) }
    }

    // ---------- text ----------

    fun label(text: String, size: Float = 14f, bold: Boolean = false, color: Int = c(R.color.bx_text)): TextView = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun hint(text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(c(R.color.bx_text2))
        setLineSpacing(0f, 1.1f)
    }

    /** a line whose text the screen updates later (status lines) */
    fun statusText(): TextView = TextView(ctx).apply {
        textSize = 13f
        setTextColor(c(R.color.bx_text2))
        setPadding(0, dp(2), 0, dp(6))
    }

    fun pill(text: String, kind: Kind): TextView = TextView(ctx).apply { setPill(this, text, kind) }

    fun setPill(v: TextView, text: String, kind: Kind) {
        val (bg, fg) = when (kind) {
            Kind.OK -> c(R.color.bx_ok_container) to c(R.color.bx_ok)
            Kind.WARN -> c(R.color.bx_warn_container) to c(R.color.bx_warn)
            Kind.BAD -> c(R.color.bx_bad_container) to c(R.color.bx_bad)
            Kind.INFO -> c(R.color.bx_primary_container) to c(R.color.bx_on_primary_container)
        }
        v.text = text
        v.textSize = 12f
        v.setTextColor(fg)
        v.setTypeface(v.typeface, Typeface.BOLD)
        v.background = rounded(bg, 20)
        v.setPadding(dp(10), dp(3), dp(10), dp(3))
        v.gravity = Gravity.CENTER
    }

    // ---------- buttons ----------

    private fun styled(label: String, fill: Int, fg: Int, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(fg)
        stateListAnimator = null
        minHeight = dp(46)
        minimumHeight = dp(46)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = ripple(rounded(fill, 14), fg)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(4))
        }
    }

    fun primary(label: String, onClick: () -> Unit): Button = styled(label, c(R.color.bx_primary), c(R.color.bx_on_primary), onClick)
    fun tonal(label: String, onClick: () -> Unit): Button = styled(label, c(R.color.bx_primary_container), c(R.color.bx_on_primary_container), onClick)
    fun quiet(label: String, onClick: () -> Unit): Button = styled(label, c(R.color.bx_surface_alt), c(R.color.bx_text), onClick)

    /** two buttons side by side */
    fun pair(a: Button, b: Button): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        for (x in listOf(a, b)) {
            x.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) }
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
        background = rounded(c(R.color.bx_surface_alt), 12, c(R.color.bx_outline))
        setPadding(dp(14), dp(10), dp(14), dp(10))
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
                intArrayOf(c(R.color.bx_primary), c(R.color.bx_text2))
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(c(R.color.bx_primary_container), c(R.color.bx_outline))
            )
        }
        val text = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 14f))
            if (desc != null) addView(hint(desc))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) })
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
        setPadding(dp(6), dp(4), dp(4), dp(4))
    }

    /** a tap-to-open explanation, so long instructions do not push the controls off the screen */
    fun expandable(title: String, body: String): LinearLayout {
        val text = hint(body).apply { visibility = View.GONE; setPadding(0, dp(4), 0, dp(4)) }
        val head = TextView(ctx).apply {
            this.text = "ℹ  $title"
            textSize = 13f
            setTextColor(c(R.color.bx_primary))
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener {
                val open = text.visibility == View.VISIBLE
                text.visibility = if (open) View.GONE else View.VISIBLE
                this.text = (if (open) "ℹ  " else "▾  ") + title
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(head)
            addView(text)
        }
    }

    /** one line of a checklist: a name, a pill (ঠিক আছে / বাকি) and, while it is not done, a button that fixes it */
    inner class StatusRow(private val name: String, private val check: () -> Boolean, private val fix: (() -> Unit)?, private val required: Boolean = true) {
        private val pillView = TextView(ctx)
        private val fixBtn: TextView = TextView(ctx).apply {
            text = "দিন"
            textSize = 13f
            setTextColor(c(R.color.bx_on_primary))
            background = rounded(c(R.color.bx_primary), 20)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { fix?.invoke() }
        }
        val view: LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            addView(label(name, 14f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(pillView)
            addView(fixBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        }

        var ok: Boolean = false
            private set

        fun refresh() {
            ok = try { check() } catch (_: Exception) { false }
            setPill(pillView, if (ok) "ঠিক আছে" else "বাকি", if (ok) Kind.OK else if (required) Kind.BAD else Kind.WARN)
            fixBtn.visibility = if (!ok && fix != null) View.VISIBLE else View.GONE
        }

        val label: String get() = name
        val isRequired: Boolean get() = required
    }

    // ---------- bottom tab bar ----------

    class TabBar(private val ui: Ui, items: List<Pair<String, String>>, private val onSelect: (Int) -> Unit) {
        private val icons = ArrayList<TextView>()
        private val labels = ArrayList<TextView>()
        val view: LinearLayout = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ui.c(R.color.bx_surface))
            setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
        }

        init {
            items.forEachIndexed { i, (icon, name) ->
                val ic = TextView(ui.ctx).apply { text = icon; textSize = 20f; gravity = Gravity.CENTER; setPadding(ui.dp(18), ui.dp(2), ui.dp(18), ui.dp(2)) }
                val lb = TextView(ui.ctx).apply { text = name; textSize = 11f; gravity = Gravity.CENTER }
                icons.add(ic); labels.add(lb)
                val col = LinearLayout(ui.ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(ic); addView(lb)
                    setOnClickListener { select(i); onSelect(i) }
                }
                view.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            select(0)
        }

        fun select(index: Int) {
            for (i in icons.indices) {
                val on = i == index
                icons[i].background = if (on) ui.rounded(ui.c(R.color.bx_primary_container), 20) else null
                labels[i].setTextColor(if (on) ui.c(R.color.bx_primary) else ui.c(R.color.bx_text2))
                labels[i].setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }
}
