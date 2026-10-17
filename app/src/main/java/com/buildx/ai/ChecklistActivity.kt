package com.buildx.ai

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.buildx.ai.device.Checklist
import com.buildx.ai.device.OemGuide

/**
 * The phase 0-7 pass checklist for THIS phone. Results are saved per phone model, so the same APK can be tried on
 * several phones (copy each result with the button and keep it).
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
        summary.text = "পাস $p   ফেল $f   বাকি $n   (মোট ${Checklist.items.size})"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "চেকলিস্ট"
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "$device\n${OemGuide.forDevice(Build.MANUFACTURER, Build.BRAND).title}"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        summary = TextView(this)
        root.addView(summary)

        var phase = -1
        for (item in Checklist.items) {
            if (item.phase != phase) {
                phase = item.phase
                root.addView(TextView(this).apply {
                    text = Checklist.phaseName(phase)
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, (14 * dp).toInt(), 0, 0)
                })
            }
            root.addView(TextView(this).apply {
                text = item.title
                setPadding(0, (8 * dp).toInt(), 0, 0)
            })
            root.addView(TextView(this).apply {
                text = item.how
                textSize = 12f
                setTextColor(Color.GRAY)
            })
            val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
            val ids = intArrayOf(View_id(item.id, 0), View_id(item.id, 1), View_id(item.id, 2))
            val labels = listOf("—", "✓ পাস", "✗ ফেল")
            val values = listOf("", "pass", "fail")
            for (i in 0..2) group.addView(RadioButton(this).apply { text = labels[i]; id = ids[i] })
            val saved = prefs.getString(key(item.id), "") ?: ""
            group.check(ids[values.indexOf(saved).coerceAtLeast(0)])
            group.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putString(key(item.id), values[ids.indexOf(checked)]).apply()
                refreshSummary()
            }
            root.addView(group)
        }

        root.addView(Button(this).apply {
            text = "রিপোর্ট কপি করো"
            setOnClickListener {
                val text = Checklist.format(device, results())
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("BuildX checklist", text))
                Toast.makeText(this@ChecklistActivity, "কপি হয়েছে", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "এই ফোনের সব ফলাফল মুছুন"
            setOnClickListener {
                val e = prefs.edit()
                for (it in Checklist.items) e.remove(key(it.id))
                e.apply()
                recreate()
            }
        })
        refreshSummary()
        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** a stable view id per item and choice */
    @Suppress("FunctionName")
    private fun View_id(itemId: String, choice: Int): Int = 10_000 + Checklist.items.indexOfFirst { it.id == itemId } * 3 + choice
}
