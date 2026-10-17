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
import com.buildx.ai.actions.ActionResult
import com.buildx.ai.actions.FollowUp
import com.buildx.ai.a11y.BuildXAccessibilityService
import android.app.AlertDialog
import android.widget.Toast
import com.buildx.ai.device.CrashLog
import com.buildx.ai.device.DeviceInfo
import com.buildx.ai.device.DeviceReport
import com.buildx.ai.device.OemGuide
import com.buildx.ai.device.OemSettings
import com.buildx.ai.wake.WakeSensitivity
import com.buildx.ai.call.SimHelper
import com.buildx.ai.system.BackgroundLauncher
import com.buildx.ai.contacts.AndroidContacts
import com.buildx.ai.contacts.NameMatcher
import com.buildx.ai.contacts.NicknameBook
import com.buildx.ai.nlu.CommandPipeline
import com.buildx.ai.nlu.Describe
import com.buildx.ai.nlu.IntentParser
import com.buildx.ai.voice.Conversation
import com.buildx.ai.voice.Speaker
import com.buildx.ai.wake.ModelManager

/** Test/setup screen. Plain Views on purpose: no extra dependencies, fewer things to break. */
class MainActivity : Activity() {

    private val reqPerm = 12
    private val reqCallPerm = 13
    private val idBangla = 1001
    private val idEnglish = 1002

    private lateinit var prefs: SharedPreferences
    private lateinit var cmdInput: EditText
    private lateinit var modelStatus: TextView
    private lateinit var permStatus: TextView
    private lateinit var logView: TextView
    private lateinit var langGroup: RadioGroup
    private lateinit var wakeLockBox: CheckBox
    private lateinit var confirmBox: CheckBox
    private lateinit var autoToggleBox: CheckBox
    private lateinit var a11yStatus: TextView
    private lateinit var stabilityStatus: TextView
    private lateinit var sensGroup: RadioGroup
    private lateinit var nickInput: EditText
    private lateinit var simGroup: RadioGroup
    private lateinit var simStatus: TextView
    private var pendingFollowUp: FollowUp? = null

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
            text = "BuildX AI — ধাপ ৭"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })

        permStatus = TextView(this)
        root.addView(permStatus)
        root.addView(button("ব্যাটারি অপ্টিমাইজেশন থেকে ছাড় দিন") { requestBatteryExemption() })
        root.addView(button("\"অন্য অ্যাপের উপর দেখানো\" পারমিশন (ব্যাকগ্রাউন্ড থেকে অ্যাপ/সেটিংস খোলার জন্য)") { requestOverlay() })

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

        root.addView(TextView(this).apply {
            text = "Accessibility (স্ক্রিন লক, ওয়াইফাই/ব্লুটুথ টগল)"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        a11yStatus = TextView(this)
        root.addView(a11yStatus)
        root.addView(button("Accessibility সেটিংস খুলুন") { openAccessibilitySettings() })
        root.addView(button("অ্যাপ তথ্য খুলুন (\"Allow restricted settings\" দেওয়ার জন্য)") { openAppInfo() })
        root.addView(TextView(this).apply {
            text = "চালু করার ধাপ:\n" +
                "১. \"Accessibility সেটিংস খুলুন\" → \"ইনস্টল করা অ্যাপ\" (বা \"ডাউনলোড করা অ্যাপ\") → BuildX AI → চালু করুন।\n" +
                "২. যদি \"সীমাবদ্ধ সেটিং\" (Restricted setting) বলে চালু করতে না দেয়: \"অ্যাপ তথ্য খুলুন\" চাপুন → উপরে ডানের ⋮ মেনু → \"Allow restricted settings\" → PIN/ফিঙ্গারপ্রিন্ট দিন → আবার ধাপ ১।\n" +
                "৩. এটা চালু না করলে শুধু স্ক্রিন লক আর ওয়াইফাই/ব্লুটুথ নিজে টগল করা কাজ করবে না, বাকি সব কাজ করবে।"
            textSize = 13f
        })
        autoToggleBox = CheckBox(this).apply {
            text = "ওয়াইফাই/ব্লুটুথ নিজে টগল করার চেষ্টা করো (Accessibility লাগে)"
            isChecked = prefs.getBoolean("autoToggle", true)
        }
        root.addView(autoToggleBox)

        root.addView(TextView(this).apply {
            text = "ফোন ও স্থায়িত্ব"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        stabilityStatus = TextView(this)
        root.addView(stabilityStatus)
        root.addView(TextView(this).apply { text = "\"Hey BuildX\" কত সহজে ধরবে (সার্ভিস আবার চালু করলে কার্যকর):" })
        sensGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        for (lv in WakeSensitivity.values()) {
            sensGroup.addView(RadioButton(this).apply { text = lv.label; id = 3000 + lv.ordinal })
        }
        sensGroup.check(3000 + prefs.getInt("wakeSens", 1).coerceIn(0, 2))
        sensGroup.setOnCheckedChangeListener { _, checked -> prefs.edit().putInt("wakeSens", checked - 3000).apply() }
        root.addView(sensGroup)
        root.addView(button("এই ফোনে চালু রাখার গাইড (ব্যাটারি/Autostart)") { showOemGuide() })
        root.addView(button("চেকলিস্ট (প্রতি ফোনে ফেজ ০–৭)") { startActivity(Intent(this, ChecklistActivity::class.java)) })
        root.addView(button("ডিভাইস রিপোর্ট কপি করো") { copyDeviceReport() })
        root.addView(button("ক্র্যাশ লগ কপি করো") { copyCrashLog() })
        root.addView(button("ক্র্যাশ লগ মুছুন") { CrashLog.clear(this); refreshStability() })

        root.addView(TextView(this).apply {
            text = "কল ও কন্টাক্ট"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(button("কল/কন্টাক্ট/ফোন-অবস্থার পারমিশন দিন") { requestCallPerms() })
        confirmBox = CheckBox(this).apply {
            text = "কল করার আগে জিজ্ঞেস করো (\"কল করব?\")"
            isChecked = prefs.getBoolean("confirmCall", true)
        }
        root.addView(confirmBox)
        simStatus = TextView(this)
        root.addView(simStatus)
        simGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(simGroup)
        nickInput = EditText(this).apply {
            hint = "উপনাম, প্রতি লাইনে একটি: বাবা = রহিম উদ্দিন"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(prefs.getString("nicknames", ""))
            minLines = 2
        }
        root.addView(nickInput)
        root.addView(button("উপনাম সেভ") { saveSettings(); LogStore.add(this@MainActivity, "উপনাম সেভ হয়েছে") })

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
        root.addView(button("কী বুঝবে দেখাও (কিছু চালানো ছাড়া, ধাপ ভাগ সহ)") { dryRun() })
        root.addView(button("কন্টাক্ট মেলাও (লেখা নামটা কার সাথে মেলে, কল ছাড়া)") { testContactMatch() })

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
        refreshSim()
        refreshA11y()
        refreshStability()
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
        val waiting = pendingFollowUp
        pendingFollowUp = null
        val result = if (waiting != null) {
            try {
                waiting.onReply(text)
            } catch (e: Exception) {
                ActionResult(false, "কাজটি করতে পারিনি: ${e.message}")
            }
        } else {
            CommandPipeline.run(ActionContext(this, lang), text)
        }
        cmdInput.setText("")
        showTextResult(result, lang)
    }

    private fun showTextResult(result: ActionResult, lang: String) {
        val later = result.deferred
        if (later != null) {
            if (result.message.isNotBlank()) ensureSpeaker(lang).speak(result.message) { }
            later.run { final -> runOnUiThread { showTextResult(final, lang) } }
            return
        }
        pendingFollowUp = result.followUp
        LogStore.add(this, "টেক্সট উত্তর: ${result.message}${if (result.followUp != null) "  (উত্তরের অপেক্ষায়: এখানেই লিখুন)" else ""}")
        ensureSpeaker(lang).speak(result.message) { }
    }

    /** Shows how a sentence would be split into steps and what each step means. Nothing is run. */
    private fun dryRun() {
        val text = cmdInput.text.toString().trim()
        if (text.isEmpty()) {
            LogStore.add(this, "উপরের ঘরে একটা বাক্য লিখুন, তারপর এই বাটন")
            return
        }
        val steps = IntentParser.parseChain(text)
        LogStore.add(this, "বোঝা: \"$text\" → ${steps.size} ধাপ")
        steps.forEachIndexed { i, p ->
            LogStore.add(this, " ${i + 1}. ${p.intent} ${p.slots}  — ${Describe.say(p, true)}")
        }
    }

    /** Shows which contact a spoken/typed name would pick and how sure it is. Nothing is called. */
    private fun testContactMatch() {
        val name = cmdInput.text.toString().trim()
        if (name.isEmpty()) {
            LogStore.add(this, "উপরের ঘরে একটা নাম লিখুন, তারপর এই বাটন")
            return
        }
        saveSettings()
        val all = AndroidContacts(this).all()
        if (all == null) {
            LogStore.add(this, "কন্টাক্ট পড়ার পারমিশন নেই")
            return
        }
        val found = NameMatcher.find(name, all, NicknameBook.parse(prefs.getString("nicknames", "") ?: ""))
        val group = NameMatcher.bestGroup(found)
        if (found.isEmpty()) {
            LogStore.add(this, "MATCH \"$name\": কেউ নেই (${all.size}টি কন্টাক্টের মধ্যে)")
            return
        }
        LogStore.add(this, "MATCH \"$name\": ${if (group.size == 1) "একজন নিশ্চিত" else "${group.size} জন সমান, জিজ্ঞেস করবে"}")
        for (m in found.take(5)) {
            val mark = if (m in group) "→" else " "
            val last = m.contact.numbers.firstOrNull()?.number?.takeLast(4) ?: "-"
            LogStore.add(this, " $mark ${"%.2f".format(m.score)}  ${m.contact.name}  (…$last)")
        }
    }

    private fun requestCallPerms() {
        val need = listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) {
            LogStore.add(this, "কল ও কন্টাক্টের পারমিশন আগেই দেওয়া আছে")
        } else {
            requestPermissions(need.toTypedArray(), reqCallPerm)
        }
    }

    /** one radio button per SIM the phone can call with, plus "let the phone decide" */
    private fun refreshSim() {
        val accounts = SimHelper.accounts(this)
        simGroup.setOnCheckedChangeListener(null)
        simGroup.removeAllViews()
        if (accounts.size < 2) {
            simStatus.text = if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
                "SIM: পারমিশন দিলে এখানে SIM বেছে নেওয়া যাবে" else "SIM: একটাই (ডিফল্ট)"
            return
        }
        simStatus.text = "কোন SIM দিয়ে কল হবে:"
        val saved = prefs.getString("simId", "") ?: ""
        val ids = ArrayList<String>()
        val labels = ArrayList<String>()
        ids.add(""); labels.add("ফোন নিজে ঠিক করবে (ডিফল্ট)")
        for (a in accounts) { ids.add(SimHelper.idOf(a)); labels.add(SimHelper.label(this, a)) }
        var checkedId = -1
        for (i in ids.indices) {
            val rb = RadioButton(this).apply { text = labels[i]; id = 2000 + i }
            simGroup.addView(rb)
            if (ids[i] == saved) checkedId = rb.id
        }
        simGroup.check(if (checkedId != -1) checkedId else 2000)
        simGroup.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putString("simId", ids[checked - 2000]).apply()
            LogStore.add(this, "SIM বাছাই: ${labels[checked - 2000]}")
        }
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
            .putBoolean("confirmCall", confirmBox.isChecked)
            .putBoolean("autoToggle", autoToggleBox.isChecked)
            .putString("nicknames", nickInput.text.toString())
            .apply()
    }

    private fun currentLang(): String =
        if (langGroup.checkedRadioButtonId == idEnglish) "en-US" else "bn-BD"

    private fun refreshStability() {
        val maker = OemGuide.detect(Build.MANUFACTURER, Build.BRAND)
        val died = prefs.getBoolean("svcDied", false)
        val crash = CrashLog.read(this) != null
        stabilityStatus.text = "${Build.MANUFACTURER} ${Build.MODEL} ($maker)\n" +
            (if (died) "⚠ সার্ভিস গতবার নিজে বন্ধ হয়নি (মোট ${prefs.getInt("svcKills", 0)} বার), গাইড দেখুন\n" else "সার্ভিস: গতবার স্বাভাবিকভাবে বন্ধ হয়েছে বা প্রথমবার\n") +
            (if (crash) "⚠ ক্র্যাশ লগ আছে, \"ক্র্যাশ লগ কপি করো\" চাপুন" else "ক্র্যাশ: নেই")
    }

    private fun showOemGuide() {
        val guide = OemGuide.forDevice(Build.MANUFACTURER, Build.BRAND)
        AlertDialog.Builder(this)
            .setTitle("চালু রাখার গাইড")
            .setMessage(OemGuide.format(guide))
            .setPositiveButton("সেটিংস খুলুন") { _, _ ->
                val specific = OemSettings.open(this, guide)
                if (!specific) LogStore.add(this, "ফোনের নিজস্ব স্ক্রিন পাইনি, অ্যাপ তথ্য খুলেছি। গাইডের ধাপ হাতে করে করুন")
            }
            .setNegativeButton("বন্ধ", null)
            .show()
    }

    private fun permissionMap(): Map<String, Boolean> {
        fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return linkedMapOf(
            "মাইক" to has(Manifest.permission.RECORD_AUDIO),
            "নোটিফিকেশন" to (Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)),
            "কল" to has(Manifest.permission.CALL_PHONE),
            "কন্টাক্ট" to has(Manifest.permission.READ_CONTACTS),
            "ফোন-অবস্থা" to has(Manifest.permission.READ_PHONE_STATE),
            "ব্যাটারি ছাড়" to pm.isIgnoringBatteryOptimizations(packageName),
            "ওভারলে" to BackgroundLauncher.canOverlay(this),
            "Accessibility (সংযুক্ত)" to (BuildXAccessibilityService.instance != null)
        )
    }

    private fun copyDeviceReport() {
        val version = try {
            val i = packageManager.getPackageInfo(packageName, 0)
            "${i.versionName} (${i.longVersionCode})"
        } catch (e: Exception) {
            "?"
        }
        val info = DeviceInfo(
            Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.VERSION.SDK_INT, Build.VERSION.RELEASE, version,
            prefs.getString("lang", "bn-BD") ?: "bn-BD",
            WakeSensitivity.from(prefs.getInt("wakeSens", 1)).name,
            permissionMap(), WakeService.running, prefs.getBoolean("svcDied", false), prefs.getInt("svcKills", 0),
            CrashLog.read(this), LogStore.read(this).lines().filter { it.isNotBlank() }.takeLast(80)
        )
        copyToClipboard("BuildX রিপোর্ট", DeviceReport.format(info))
    }

    private fun copyCrashLog() {
        val c = CrashLog.read(this)
        if (c == null) Toast.makeText(this, "কোনো ক্র্যাশ লগ নেই", Toast.LENGTH_SHORT).show()
        else copyToClipboard("BuildX crash", c)
    }

    private fun copyToClipboard(label: String, text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "কপি হয়েছে, এখন মেসেজে পেস্ট করুন", Toast.LENGTH_SHORT).show()
    }

    private fun refreshA11y() {
        val connected = BuildXAccessibilityService.instance != null
        val enabled = BuildXAccessibilityService.isEnabledInSettings(this)
        a11yStatus.text = when {
            connected -> "Accessibility: ✓ চালু ও সংযুক্ত"
            enabled -> "Accessibility: চালু করা আছে, সংযোগের অপেক্ষায় (কয়েক সেকেন্ড পরে আবার দেখুন)"
            else -> "Accessibility: ✗ বন্ধ (নিচের ধাপ দেখুন)"
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            LogStore.add(this, "Accessibility সেটিংস খোলা যায়নি: ${e.message}")
        }
    }

    private fun openAppInfo() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            LogStore.add(this, "অ্যাপ তথ্য খোলা যায়নি: ${e.message}")
        }
    }

    private fun requestOverlay() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            LogStore.add(this, "ওভারলে সেটিংস খোলা যায়নি: ${e.message}")
        }
    }

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
        if (requestCode == reqCallPerm) {
            AndroidContacts.invalidate()
            refreshPermStatus()
            refreshSim()
        }
    }

    // ---------- UI helpers ----------

    private fun refreshPermStatus() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        permStatus.text = "মাইক: ${tick(mic)}   নোটিফিকেশন: ${tick(notif)}   ব্যাটারি ছাড়: ${tick(battery)}\n" +
            "কল: ${tick(has(Manifest.permission.CALL_PHONE))}   কন্টাক্ট: ${tick(has(Manifest.permission.READ_CONTACTS))}   " +
            "ফোন-অবস্থা: ${tick(has(Manifest.permission.READ_PHONE_STATE))}   ওভারলে: ${tick(BackgroundLauncher.canOverlay(this))}"
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
