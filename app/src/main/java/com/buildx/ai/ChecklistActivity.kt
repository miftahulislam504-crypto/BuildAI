package com.buildx.ai

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.buildx.ai.device.Checklist
import com.buildx.ai.device.OemGuide
import com.buildx.ai.ui.Ui

/**
 * The phase 0-12 pass checklist for THIS phone. Results are saved per phone model, so the same APK can be tried on
 * several phones (copy each result with the button and keep it). Built from the same blocks as the main screen.
 */
class ChecklistActivity : Activity() {

    private val device = "${Build.MANUFACTURER} ${Build.MODEL}"
    private val prefs by lazy { getSharedPreferences("checklist", Context.MODE_PRIVATE) }
    private lateinit var summary: TextView

    private fun key(id: String) = "$device:$id"

    private fun results(): Map<String, Checklist.Result> = Checklist.items.associate {
        it.id to when (prefs.getString(key(it.id), "")) {
            "pass" -> Checklist.Result.PASS
            "fail" -> Checklist.Result.FAIL
            else -> Checklist.Result.NONE
        }
    }

    private fun refreshSummary() {
        val (p, f, n) = Checklist.summary(results())
        summary.text = "Passed $p    Failed $f    Not tried $n    (total ${Checklist.items.size})"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Checklist"
        val ui = Ui(this)
        val (sv, col) = ui.page()

        col.addView(ui.label("Device checklist", 28f, bold = true).apply {
            letterSpacing = -0.02f
            setPadding(0, ui.dp(24), 0, ui.dp(12))
        })

        val info = ui.card(device, OemGuide.forDevice(Build.MANUFACTURER, Build.BRAND).title)
        summary = ui.statusText()
        info.addView(summary)
        col.addView(info)

        var phase = -1
        var card: LinearLayout = ui.card()   // replaced as soon as the first item is reached
        for (item in Checklist.items) {
            if (item.phase != phase) {
                phase = item.phase
                card = ui.card(Checklist.phaseName(phase))
                col.addView(card)
            } else {
                card.addView(ui.divider())
            }
            card.addView(ui.label(item.title, 14f))
            card.addView(ui.hint(item.how).apply { setPadding(0, ui.dp(3), 0, ui.dp(4)) })

            val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
            val ids = intArrayOf(viewId(item.id, 0), viewId(item.id, 1), viewId(item.id, 2))
            val labels = listOf("Not tried", "Pass", "Fail")
            val values = listOf("", "pass", "fail")
            for (i in 0..2) {
                group.addView(ui.radio(labels[i], ids[i]).apply {
                    layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
            val saved = prefs.getString(key(item.id), "") ?: ""
            group.check(ids[values.indexOf(saved).coerceAtLeast(0)])
            group.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putString(key(item.id), values[ids.indexOf(checked)]).apply()
                refreshSummary()
            }
            card.addView(group)
        }

        col.addView(ui.gap(8))
        col.addView(ui.primary("Copy report") {
            val text = Checklist.format(device, results())
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("BuildX checklist", text))
            Toast.makeText(this@ChecklistActivity, "Copied", Toast.LENGTH_SHORT).show()
        })
        col.addView(ui.quiet("Clear all results for this phone") {
            val e = prefs.edit()
            for (entry in Checklist.items) e.remove(key(entry.id))
            e.apply()
            recreate()
        })
        refreshSummary()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.c(R.color.bx_bg))
            addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        // the system bars are drawn over the app on new phones; keep the content clear of them
        root.setOnApplyWindowInsetsListener { v, ins ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(WindowInsets.Type.systemBars())
                top = i.top; bottom = i.bottom
            } else {
                @Suppress("DEPRECATION") run { top = ins.systemWindowInsetTop; bottom = ins.systemWindowInsetBottom }
            }
            v.setPadding(0, top, 0, bottom)
            ins
        }
        setContentView(root)
    }

    /** a stable view id per item and choice */
    private fun viewId(itemId: String, choice: Int): Int = 10_000 + Checklist.items.indexOfFirst { it.id == itemId } * 3 + choice
}
