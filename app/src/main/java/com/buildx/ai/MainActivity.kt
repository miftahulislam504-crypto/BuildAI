package com.buildx.ai

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.buildx.ai.actions.ActionContext
import com.buildx.ai.nlu.CommandPipeline
import com.buildx.ai.voice.Conversation
import com.buildx.ai.voice.Speaker
import com.buildx.ai.wake.ModelManager

/** Test/setup screen. Plain Views on purpose: no extra dependencies, fewer things to break. */
class MainActivity : Activity() {

    private val reqPerm = 12
    private val idBangla = 1001
    private val idEnglish = 1002

    private lateinit var prefs: SharedPreferences
    private lateinit var cmdInput: EditText
    private lateinit var modelStatus: TextView
    private lateinit var permStatus: TextView
    private lateinit var logView: TextView
    private lateinit var langGroup: RadioGroup
    private lateinit var wakeLockBox: CheckBox

    private var pendingAction: (() -> Unit)? = null
    private var stt: SttRunner? = null
    private var speaker: Speaker? = null
    private var speakerLang = ""
    private var conversation: Conversation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("buildx", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "BuildX AI — ধাপ ১"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })

        permStatus = TextView(this)
        root.addView(permStatus)
        root.addView(button("ব্যাটারি অপ্টিমাইজেশন থেকে ছাড় দিন") { requestBatteryExemption() })

        modelStatus = TextView(this)
        root.addView(modelStatus)
        root.addView(button("wake word মডেল নামান (~৪০ MB, একবারই)") { downloadModel() })

        langGroup = RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(RadioButton(this@MainActivity).apply {
                text = "বাংলা (bn-BD)"
                id = idBangla
            })
            addView(RadioButton(this@MainActivity).apply {
                text = "English (en-US)"
                id = idEnglish
            })
            check(if (prefs.getString("lang", "bn-BD") == "en-US") idEnglish else idBangla)
        }
        root.addView(langGroup)

        wakeLockBox = CheckBox(this).apply {
            text = "WakeLock ব্যবহার করো (স্ক্রিন অফে শোনার জন্য)"
            isChecked = prefs.getBoolean("wakeLock", true)
        }
        root.addView(wakeLockBox)

        root.addView(button("▶ শোনা শুরু (\"Hey BuildX\")") { startListening() })
        root.addView(button("■ শোনা বন্ধ") {
            startService(Intent(this, WakeService::class.java).setAction(WakeService.ACTION_STOP))
        })

        root.addView(TextView(this).apply {
            text = "টেস্ট"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(button("🔊 TTS টেস্ট (আওয়াজ আসছে?)") { testTts() })
        root.addView(button("TTS সেটিংস খুলুন (ইঞ্জিন/ভয়েজ ডেটা)") { openTtsSettings() })
        root.addView(button("🎤 কথা বলো (wake word ছাড়াই পুরো ধারা)") { talkNow() })
        root.addView(button("STT টেস্ট (শুধু বাক্য চেনা)") { runSttTest() })

        cmdInput = EditText(this).apply {
            hint = "লিখে কমান্ড দিন (যেমন: এখন কয়টা বাজে)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(cmdInput)
        root.addView(button("লিখে কমান্ড চালাও") { runTextCommand() })

        root.addView(button("লগ কপি") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("buildx-log", LogStore.read(this)))
        })
        root.addView(button("লগ মুছুন") { LogStore.clear(this) })

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(logView)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshModelStatus()
        refreshPermStatus()
        refreshLog()
        LogStore.listener = { runOnUiThread { refreshLog() } }
    }

    override fun onPause() {
        LogStore.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        conversation?.cancel()
        conversation = null
        stt?.cancel()
        stt = null
        speaker?.shutdown()
        speaker = null
        super.onDestroy()
    }

    // ---------- actions ----------

    private fun downloadModel() {
        if (ModelManager.isReady(this)) {
            LogStore.add(this, "মডেল আগেই নামানো আছে")
            return
        }
        if (ModelManager.downloading) {
            LogStore.add(this, "মডেল নামছে, অপেক্ষা করুন")
            return
        }
        ModelManager.download(this) {
            if (!isDestroyed) refreshModelStatus()
        }
        refreshModelStatus()
    }

    private fun startListening() {
        if (!ModelManager.isReady(this)) {
            LogStore.add(this, "আগে wake word মডেল নামান")
            return
        }
        ensurePerms {
            saveSettings()
            startForegroundService(Intent(this, WakeService::class.java))
        }
    }

    /** Whole conversation without the wake word: prompt -> STT -> command -> spoken result. */
    private fun talkNow() {
        ensurePerms {
            saveSettings()
            if (WakeService.running) {
                LogStore.add(this, "আগে শোনা বন্ধ করুন (মাইক একসাথে দুইজন পায় না)")
            } else {
                val lang = currentLang()
                conversation?.cancel()
                val c = Conversation(this, lang, ensureSpeaker(lang))
                conversation = c
                c.run { }
            }
        }
    }

    private fun runSttTest() {
        ensurePerms {
            saveSettings()
            if (WakeService.running) {
                LogStore.add(this, "আগে শোনা বন্ধ করুন (মাইক একসাথে দুইজন পায় না)")
            } else {
                val runner = SttRunner(this)
                stt = runner
                runner.start(currentLang()) { }
            }
        }
    }

    /** Runs a typed command through the same pipeline the voice flow uses (no mic needed). */
    private fun runTextCommand() {
        val text = cmdInput.text.toString().trim()
        if (text.isEmpty()) return
        saveSettings()
        val lang = currentLang()
        val result = CommandPipeline.run(ActionContext(this, lang), text)
        ensureSpeaker(lang).speak(result.message) { }
    }

    /** Speaks a fixed sentence. Always re-creates the speaker, so it picks up a newly chosen engine/voice. */
    private fun testTts() {
        saveSettings()
        val lang = currentLang()
        speaker?.shutdown()
        speaker = null
        val sentence = if (lang.startsWith("bn")) "হ্যালো, আমি বিল্ডএক্স" else "Hello, I am BuildX"
        ensureSpeaker(lang).speak(sentence) { }
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: Exception) {
            LogStore.add(this, "TTS সেটিংস সরাসরি খোলা যায়নি; Settings > General management > Text-to-speech খুঁজুন")
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun ensureSpeaker(lang: String): Speaker {
        val existing = speaker
        if (existing != null && speakerLang == lang) return existing
        existing?.shutdown()
        val s = Speaker(this).also { it.init(lang) }
        speaker = s
        speakerLang = lang
        return s
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("lang", currentLang())
            .putBoolean("wakeLock", wakeLockBox.isChecked)
            .apply()
    }

    private fun currentLang(): String =
        if (langGroup.checkedRadioButtonId == idEnglish) "en-US" else "bn-BD"

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            LogStore.add(this, "ব্যাটারি সেটিংস খোলা যায়নি: ${e.message}")
        }
    }

    // ---------- permissions ----------

    private fun ensurePerms(then: () -> Unit) {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) {
            then()
        } else {
            pendingAction = then
            requestPermissions(need.toTypedArray(), reqPerm)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqPerm) {
            val audioOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (audioOk) pendingAction?.invoke() else LogStore.add(this, "মাইক পারমিশন ছাড়া চলবে না")
            pendingAction = null
            refreshPermStatus()
        }
    }

    // ---------- UI helpers ----------

    private fun refreshPermStatus() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        permStatus.text = "মাইক: ${tick(mic)}   নোটিফিকেশন: ${tick(notif)}   ব্যাটারি ছাড়: ${tick(battery)}"
    }

    private fun refreshModelStatus() {
        modelStatus.text = when {
            ModelManager.isReady(this) -> "wake word মডেল: প্রস্তুত ✓"
            ModelManager.downloading -> "wake word মডেল: নামছে... (লগে অগ্রগতি দেখুন)"
            else -> "wake word মডেল: নেই ✗"
        }
    }

    private fun tick(ok: Boolean): String = if (ok) "✓" else "✗"

    private fun refreshLog() {
        logView.text = LogStore.read(this)
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
