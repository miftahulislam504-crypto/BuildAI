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
import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.buildx.ai.actions.ActionContext
import com.buildx.ai.actions.ActionResult
import com.buildx.ai.actions.FollowUp
import android.app.admin.DevicePolicyManager
import com.buildx.ai.a11y.A11yEnabler
import com.buildx.ai.a11y.BuildXAccessibilityService
import com.buildx.ai.admin.BuildXDeviceAdmin
import com.buildx.ai.assistant.AssistantBridge
import com.buildx.ai.assistant.BuildXVoiceInteractionService
import android.app.AlertDialog
import android.widget.Toast
import com.buildx.ai.device.CrashLog
import com.buildx.ai.device.DeviceInfo
import com.buildx.ai.device.DeviceReport
import com.buildx.ai.device.OemGuide
import com.buildx.ai.device.OemSettings
import com.buildx.ai.llm.AndroidLlm
import com.buildx.ai.wake.ModelManager
import com.buildx.ai.wake.VoiceLockStore
import com.buildx.ai.wake.VoiceStrictness
import com.buildx.ai.wake.WakePhrase
import com.buildx.ai.wake.WakeSensitivity
import com.buildx.ai.stt.SttEndpoint
import com.buildx.ai.stt.SttMode
import com.buildx.ai.stt.WhisperStt
import com.buildx.ai.ui.Ui
import com.buildx.ai.call.SimHelper
import com.buildx.ai.system.BackgroundLauncher
import com.buildx.ai.contacts.AndroidContacts
import com.buildx.ai.notif.BuildXNotificationListener
import com.buildx.ai.contacts.NameMatcher
import com.buildx.ai.contacts.NicknameBook
import com.buildx.ai.nlu.CommandPipeline
import com.buildx.ai.nlu.Describe
import com.buildx.ai.nlu.IntentParser
import com.buildx.ai.voice.Conversation
import com.buildx.ai.voice.Speaker

/** Test/setup screen. Plain Views on purpose: no extra dependencies, fewer things to break. */
class MainActivity : Activity() {

    private val reqPerm = 12
    private val reqCallPerm = 13
    private val idBangla = 1001
    private val idEnglish = 1002

    private lateinit var prefs: SharedPreferences
    private lateinit var cmdInput: EditText
    private lateinit var modelStatus: TextView
    private lateinit var logView: TextView
    private lateinit var langGroup: RadioGroup
    private lateinit var wakeLockBox: CompoundButton
    private lateinit var confirmBox: CompoundButton
    private lateinit var llmBox: CompoundButton
    private lateinit var llmUrlInput: EditText
    private lateinit var llmTokenInput: EditText
    private lateinit var llmStatus: TextView
    private lateinit var agentBox: CompoundButton
    private lateinit var autoToggleBox: CompoundButton
    private lateinit var a11yStatus: TextView
    private lateinit var assistStatus: TextView
    private lateinit var adminStatus: TextView
    private lateinit var popupBox: CompoundButton
    private lateinit var autoStartBox: CompoundButton
    private lateinit var stabilityStatus: TextView
    private lateinit var voiceStatus: TextView
    private lateinit var voiceBox: CompoundButton
    private lateinit var strictGroup: RadioGroup
    private lateinit var vibrateBox: CompoundButton
    private lateinit var sensGroup: RadioGroup
    private lateinit var nickInput: EditText
    private lateinit var simGroup: RadioGroup
    private lateinit var simStatus: TextView
    private lateinit var ui: Ui
    private lateinit var tabBar: Ui.TabBar
    private lateinit var headerTitle: TextView
    private val pages = ArrayList<View>()
    private var currentTab = 0
    private val statusRows = ArrayList<Ui.StatusRow>()
    private lateinit var homePill: TextView
    private lateinit var homeToggle: Button
    private lateinit var homeHint: TextView
    private lateinit var homeReady: TextView
    private lateinit var homeLog: TextView
    private lateinit var phraseInput: EditText
    private lateinit var phraseStatus: TextView
    private lateinit var lowPowerBox: CompoundButton
    private lateinit var sttGroup: RadioGroup
    private lateinit var sttNote: TextView
    private lateinit var ttsInfo: TextView
    private lateinit var testInput: EditText
    private var pendingFollowUp: FollowUp? = null

    private var pendingAction: (() -> Unit)? = null
    private var stt: SttRunner? = null
    private var speaker: Speaker? = null
    private var speakerLang = ""
    private var conversation: Conversation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("buildx", MODE_PRIVATE)
        ui = Ui(this)
        currentTab = savedInstanceState?.getInt("tab") ?: 0

        // every control the old single page had still exists (the logic below uses them); they are now grouped into four tabs
        pages.add(buildHome())
        pages.add(buildSetup())
        pages.add(buildSettings())
        pages.add(buildTools())

        val frame = FrameLayout(this)
        for (pg in pages) frame.addView(pg, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        headerTitle = ui.label("BuildX AI", 22f, bold = true).apply { setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(6)) }
        tabBar = Ui.TabBar(ui, listOf("🏠" to "হোম", "🛠" to "সেটআপ", "⚙" to "সেটিংস", "🧪" to "পরীক্ষা")) { showTab(it) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.c(R.color.bx_bg))
            addView(headerTitle)
            addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(View(this@MainActivity).apply { setBackgroundColor(ui.c(R.color.bx_outline)) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            addView(tabBar.view)
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
        showTab(currentTab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", currentTab)
    }

    private fun showTab(i: Int) {
        currentTab = i.coerceIn(0, pages.size - 1)
        for ((k, pg) in pages.withIndex()) pg.visibility = if (k == currentTab) View.VISIBLE else View.GONE
        tabBar.select(currentTab)
        headerTitle.text = listOf("BuildX AI", "সেটআপ", "সেটিংস", "পরীক্ষা ও লগ")[currentTab]
        if (currentTab == 3) logView.text = LogStore.read(this)
    }

    // ---------- tab 1: home ----------

    private fun buildHome(): View {
        val (sv, col) = ui.page()

        val hero = ui.card()
        homePill = ui.pill("বন্ধ", Ui.Kind.WARN)
        hero.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.label("শোনার অবস্থা", 16f, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(homePill)
        })
        homeHint = ui.hint("").apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) }
        hero.addView(homeHint)
        homeToggle = ui.primary("▶  শোনা শুরু করুন") { toggleListening() }
        hero.addView(homeToggle)
        hero.addView(ui.pair(ui.tonal("🎤  কথা বলুন") { talkNow() }, ui.quiet("🔊  কণ্ঠ শুনুন") { testTts() }))
        col.addView(hero)

        val cmd = ui.card("লিখে কমান্ড দিন", "যেমন: এখন কয়টা বাজে, রহিমকে কল করো")
        cmdInput = ui.input("কমান্ড লিখুন…").apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> runTextCommand(); true }
            setSingleLine(true)
        }
        cmd.addView(cmdInput)
        cmd.addView(ui.primary("চালাও") { runTextCommand() })
        col.addView(cmd)

        val ready = ui.card("প্রস্তুতি")
        homeReady = ui.statusText()
        ready.addView(homeReady)
        ready.addView(ui.tonal("সেটআপে যান") { showTab(1) })
        col.addView(ready)

        val rec = ui.card("সাম্প্রতিক")
        homeLog = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(ui.c(R.color.bx_log_text))
            background = ui.rounded(ui.c(R.color.bx_log_bg), 12)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        }
        rec.addView(homeLog)
        rec.addView(ui.quiet("পুরো লগ দেখুন") { showTab(3) })
        col.addView(rec)
        return sv
    }

    private fun toggleListening() {
        if (WakeService.running) {
            startService(Intent(this, WakeService::class.java).setAction(WakeService.ACTION_STOP))
        } else {
            startListening()
        }
        // the service starts and stops a moment later, so look again then
        window.decorView.postDelayed({ if (!isDestroyed) refreshHome() }, 900)
    }

    private fun refreshHome() {
        val on = WakeService.running
        ui.setPill(homePill, if (on) "শুনছে" else "বন্ধ", if (on) Ui.Kind.OK else Ui.Kind.WARN)
        homeToggle.text = if (on) "■  শোনা বন্ধ করুন" else "▶  শোনা শুরু করুন"
        val phrase = WakePhrase.fromPref(prefs.getString("wakePhrase", ""))
        homeHint.text = if (on) "\"${phrase.display}\" বলে ডাকুন" else "চালু করলে \"${phrase.display}\" বলে ডাকা যাবে"
        val missing = statusRows.filter { it.isRequired && !it.ok }.map { it.label }
        homeReady.text = if (missing.isEmpty()) "✓ জরুরি সবকিছু প্রস্তুত" else "বাকি আছে: ${missing.joinToString(", ")}"
    }

    private fun lastLines(all: String): String =
        all.takeLast(3000).trimEnd().lines().takeLast(8).joinToString("\n").ifBlank { "এখনো কিছু হয়নি" }

    // ---------- tab 2: setup (permissions and system switches) ----------

    private fun addRow(card: LinearLayout, name: String, required: Boolean, fix: (() -> Unit)?, check: () -> Boolean) {
        val row = ui.StatusRow(name, check, fix, required)
        statusRows.add(row)
        card.addView(row.view)
    }

    private fun buildSetup(): View {
        val (sv, col) = ui.page()
        fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

        val must = ui.card("জরুরি", "এগুলো ছাড়া \"হে বিল্ড\" শোনা চলবে না")
        addRow(must, "মাইক", true, { ensurePerms { refreshPermStatus() } }) { has(Manifest.permission.RECORD_AUDIO) }
        addRow(must, "নোটিফিকেশন", true, { ensurePerms { refreshPermStatus() } }) {
            Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
        }
        addRow(must, "ব্যাটারি অপ্টিমাইজেশন থেকে ছাড়", true, { requestBatteryExemption() }) {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        }
        addRow(must, "ওয়েক ওয়ার্ড মডেল (~৪০ MB, একবারই)", true, { downloadModel() }) { ModelManager.isReady(this) }
        modelStatus = ui.statusText()
        must.addView(modelStatus)
        col.addView(must)

        val people = ui.card("কল, মেসেজ ও কন্টাক্ট", "যে কাজ করাতে চান, শুধু তার অনুমতিই দিন")
        for ((name, perm) in listOf(
            "ফোন করা" to Manifest.permission.CALL_PHONE,
            "কন্টাক্ট পড়া" to Manifest.permission.READ_CONTACTS,
            "ফোনের অবস্থা (SIM)" to Manifest.permission.READ_PHONE_STATE,
            "ক্যামেরা (টর্চ)" to Manifest.permission.CAMERA,
            "এসএমএস পাঠানো" to Manifest.permission.SEND_SMS,
            "এসএমএস পড়া" to Manifest.permission.READ_SMS
        )) addRow(people, name, false, { requestCallPerms() }) { has(perm) }
        col.addView(people)

        val special = ui.card("বিশেষ অনুমতি", "এগুলো সেটিংসে গিয়ে নিজে চালু করতে হয়")
        addRow(special, "অন্য অ্যাপের উপর দেখানো", false, { requestOverlay() }) { BackgroundLauncher.canOverlay(this) }
        addRow(special, "সিস্টেম সেটিংস বদল (ব্রাইটনেস)", false, { openSpecialPermission(Settings.ACTION_MANAGE_WRITE_SETTINGS) }) { Settings.System.canWrite(this) }
        addRow(special, "সাইলেন্ট/DND নিয়ন্ত্রণ", false, { openSpecialPermission(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).isNotificationPolicyAccessGranted
        }
        addRow(special, "নোটিফিকেশন পড়া (\"কে কল করছে\")", false, { openSpecialPermission(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }) {
            BuildXNotificationListener.isEnabledInSettings(this)
        }
        col.addView(special)

        val a11y = ui.card("Accessibility", "স্ক্রিন লক, ওয়াইফাই/ব্লুটুথ টগল, ট্যাপ ও টাইপ করার জন্য")
        a11yStatus = ui.statusText()
        a11y.addView(a11yStatus)
        a11y.addView(ui.primary("Accessibility সেটিংস খুলুন") { openAccessibilitySettings() })
        a11y.addView(ui.pair(ui.quiet("অ্যাপ তথ্য") { openAppInfo() }, ui.quiet("জোর করে চালু") { forceEnableA11y() }))
        a11y.addView(ui.expandable("চালু করার ধাপ",
            "১. \"Accessibility সেটিংস খুলুন\" → \"ইনস্টল করা অ্যাপ\" (বা \"ডাউনলোড করা অ্যাপ\") → BuildX AI → চালু করুন।\n\n" +
                "২. \"Restricted setting\" বলে আটকালে: \"OK\" চেপে বেরিয়ে আসুন → \"অ্যাপ তথ্য\" চাপুন → উপরে ডানের ⋮ → \"Allow restricted settings\" → PIN/ফিঙ্গারপ্রিন্ট দিন → আবার ধাপ ১। (এই অপশন আটকানোর বার্তা একবার দেখার পরেই আসে)\n\n" +
                "৩. তাতেও না হলে নিচের \"স্ক্রিন লক\" কার্ড দিয়ে অন্তত স্ক্রিন লক চালানো যাবে।\n\n" +
                "এটা চালু না করলে শুধু স্ক্রিন লক আর ওয়াইফাই/ব্লুটুথ নিজে টগল করা কাজ করবে না, বাকি সব কাজ করবে।"))
        val (autoRow, autoSw) = ui.switchRow("ওয়াইফাই/ব্লুটুথ নিজে টগল করা", "Accessibility লাগে", prefs.getBoolean("autoToggle", true))
        autoToggleBox = autoSw
        a11y.addView(autoRow)
        col.addView(a11y)

        val assist = ui.card("ডিজিটাল অ্যাসিস্ট্যান্ট", "পপআপ, আর সিস্টেম নিজে অ্যাপকে চালু রাখে")
        assistStatus = ui.statusText()
        assist.addView(assistStatus)
        assist.addView(ui.primary("ডিফল্ট অ্যাসিস্ট্যান্ট সেটিংস খুলুন") { openAssistantSettings() })
        assist.addView(ui.expandable("এতে কী হয়?",
            "সেখানে BuildX AI বেছে নিন। তারপর: (১) সাইড-কী/হোম-জেসচার চেপে ধরলে সরাসরি শোনা শুরু ও পপআপ আসবে, (২) ওয়েক ওয়ার্ড বললে লক স্ক্রিনের উপরেও পপআপ আসবে, (৩) সিস্টেম এই অ্যাপকে ফোন চালু হতেই সচল রাখবে।\n\n" +
                "মনে রাখুন: অ্যাসিস্ট্যান্ট হলেও ফোন আনলক করা যায় না (সেটা শুধু আপনার PIN/ফিঙ্গারপ্রিন্ট), আর ওয়েক ওয়ার্ড শোনার ইঞ্জিন সিস্টেম দেয় না, সেটা BuildX-এর নিজের সার্ভিসই করে।"))
        val (popRow, popSw) = ui.switchRow("ওয়েক ওয়ার্ডে পপআপ দেখাও", null, prefs.getBoolean("assistPopup", true))
        popupBox = popSw
        assist.addView(popRow)
        val (autoStartRow, autoStartSw) = ui.switchRow("অ্যাসিস্ট্যান্ট চালু হলে নিজে শোনা শুরু করা", null, prefs.getBoolean("assistAutoStart", true))
        autoStartBox = autoStartSw
        assist.addView(autoStartRow)
        assist.addView(ui.quiet("পপআপ পরীক্ষা করো") { testPopup() })
        col.addView(assist)

        val admin = ui.card("স্ক্রিন লক (Accessibility ছাড়া বিকল্প)")
        adminStatus = ui.statusText()
        admin.addView(adminStatus)
        admin.addView(ui.pair(ui.tonal("চালু করুন") { enableDeviceAdmin() }, ui.quiet("বন্ধ করুন") { disableDeviceAdmin() }))
        col.addView(admin)

        val stab = ui.card("ফোন ও স্থায়িত্ব", "ফোন যেন সার্ভিসকে মেরে না ফেলে")
        stabilityStatus = ui.statusText()
        stab.addView(stabilityStatus)
        stab.addView(ui.tonal("এই ফোনে চালু রাখার গাইড (ব্যাটারি/Autostart)") { showOemGuide() })
        col.addView(stab)
        return sv
    }

    // ---------- tab 3: settings ----------

    private fun saveOn(sw: CompoundButton) = sw.setOnCheckedChangeListener { _, _ -> saveSettings() }

    private fun buildSettings(): View {
        val (sv, col) = ui.page()

        val lang = ui.card("ভাষা", "কমান্ড ও উত্তরের ভাষা")
        langGroup = RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.radio("বাংলা", idBangla).apply { layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(ui.radio("English", idEnglish).apply { layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f) })
            check(if (prefs.getString("lang", "bn-BD") == "en-US") idEnglish else idBangla)
            setOnCheckedChangeListener { _, _ -> saveSettings() }
        }
        lang.addView(langGroup)
        col.addView(lang)

        // --- wake word ---
        val wake = ui.card("ওয়েক ওয়ার্ড", "যে কথা বলে ডাকবেন। শোনা বন্ধ করে আবার চালু করলে নতুন সেটিং কাজ করে")
        phraseInput = ui.input("ডিফল্ট: hey build", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, prefs.getString("wakePhrase", "") ?: "")
        wake.addView(phraseInput)
        phraseStatus = ui.statusText().apply { text = "২–৪টা ইংরেজি শব্দ, যেমন যেভাবে শোনায়: hey jarvis, ok buddy" }
        wake.addView(phraseStatus)
        wake.addView(ui.tonal("যাচাই করে সেভ করুন") { savePhrase() })
        wake.addView(ui.divider())
        wake.addView(ui.label("কত সহজে ধরবে", 14f, bold = true))
        sensGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        for (lv in WakeSensitivity.values()) sensGroup.addView(ui.radio(lv.label, 3000 + lv.ordinal))
        sensGroup.check(3000 + prefs.getInt("wakeSens", 1).coerceIn(0, 2))
        sensGroup.setOnCheckedChangeListener { _, checked -> prefs.edit().putInt("wakeSens", checked - 3000).apply() }
        wake.addView(sensGroup)
        wake.addView(ui.divider())
        val (wlRow, wlSw) = ui.switchRow("স্ক্রিন অফেও শোনা", "WakeLock ধরে রাখে", prefs.getBoolean("wakeLock", true))
        wakeLockBox = wlSw; saveOn(wlSw)
        wake.addView(wlRow)
        val (lpRow, lpSw) = ui.switchRow("কম-ব্যাটারি শোনা (পরীক্ষামূলক)", "কণ্ঠ ধরলে তবেই চেনার কাজ চলে, বাকি সময় ঘুমায়। ওয়েক ওয়ার্ড কম ধরলে বন্ধ রাখুন", prefs.getBoolean("lowPower", false))
        lowPowerBox = lpSw
        lpSw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("lowPower", on).apply()
            LogStore.add(this, "কম-ব্যাটারি শোনা: ${if (on) "চালু" else "বন্ধ"} (শোনা আবার চালু করলে কার্যকর)")
        }
        wake.addView(lpRow)
        val (vbRow, vbSw) = ui.switchRow("ডাকলে ভাইব্রেট করা", null, prefs.getBoolean("wakeVibrate", false))
        vibrateBox = vbSw
        vbSw.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("wakeVibrate", on).apply() }
        wake.addView(vbRow)
        col.addView(wake)

        // --- how it hears you (STT) ---
        val stt = ui.card("আপনার কথা বোঝা", "কমান্ড কোন ইঞ্জিন দিয়ে লেখা হবে")
        sttGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        for (m in SttMode.values()) sttGroup.addView(ui.radio(m.label, 5000 + m.ordinal))
        sttGroup.check(5000 + SttMode.from(prefs.getString("sttMode", "system")).ordinal)
        sttNote = ui.statusText()
        sttGroup.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putString("sttMode", SttMode.values()[(checked - 5000).coerceIn(0, SttMode.values().size - 1)].key).apply()
            refreshSttNote()
        }
        stt.addView(sttGroup)
        stt.addView(sttNote)
        stt.addView(ui.hint("Whisper বেছে নিলে আপনার বলা কমান্ডের অডিও আপনার নিজের প্রক্সি সার্ভারে যায় (শুধু https)। ব্যর্থ হলে ফোনের নিজস্ব ইঞ্জিনে ফিরে যায়। ঠিকানা ও টোকেন নিচের \"স্মার্ট সহকারী\" কার্ডে দিন।"))
        col.addView(stt)

        // --- how it talks (TTS) ---
        val tts = ui.card("উত্তর বলার কণ্ঠ", "ভয়েস, গতি ও পিচ")
        ttsInfo = ui.statusText()
        tts.addView(ttsInfo)
        tts.addView(ui.tonal("ভয়েস বাছুন") { pickVoice() })
        val rateLabel = ui.label("গতি: ${prefs.getInt("ttsRate", 100)}%", 13f)
        tts.addView(rateLabel)
        tts.addView(seek(0, 90, prefs.getInt("ttsRate", 100).coerceIn(60, 150) - 60, { rateLabel.text = "গতি: ${it + 60}%" }) {
            prefs.edit().putInt("ttsRate", it + 60).apply(); speaker?.reapplyTuning()
        })
        val pitchLabel = ui.label("পিচ: ${prefs.getInt("ttsPitch", 100)}%", 13f)
        tts.addView(pitchLabel)
        tts.addView(seek(0, 60, prefs.getInt("ttsPitch", 100).coerceIn(70, 130) - 70, { pitchLabel.text = "পিচ: ${it + 70}%" }) {
            prefs.edit().putInt("ttsPitch", it + 70).apply(); speaker?.reapplyTuning()
        })
        tts.addView(ui.pair(ui.primary("🔊  শুনুন") { testTts() }, ui.quiet("ইঞ্জিন সেটিংস") { openTtsSettings() }))
        col.addView(tts)

        // --- voice lock ---
        val vl = ui.card("আমার কণ্ঠ", "শুধু আমার ডাকে সাড়া দেবে")
        voiceStatus = ui.statusText()
        vl.addView(voiceStatus)
        vl.addView(ui.expandable("কীভাবে চালু করবেন?",
            "(১) কণ্ঠ-মডেল নামান (একবার, ~১৩ MB, ইন্টারনেট লাগে)। (২) শোনা চালু করে সার্ভিস সচল রাখুন। (৩) \"রেজিস্টার\" চেপে শান্ত জায়গায় ৫ বার ওয়েক ওয়ার্ড বলুন, প্রতিবার একটু থেমে। (৪) নিচের সুইচ চালু করুন।\n\n" +
                "কণ্ঠ শুধু ওয়েক ওয়ার্ড বলার সময় মেলানো হয়। তাই অন্য কেউ ডাকলে সাড়া দেবে না, কিন্তু আপনি ডাকার পর অন্য কেউ কমান্ড বললে আটকাবে না। ওয়েক ওয়ার্ড বদলালে আবার রেজিস্টার করুন। ভুল হলে নিচের কড়া/ঢিলা বদলান, লগে \"WAKE-VOICE দূরত্ব\" লাইন দেখুন।"))
        vl.addView(ui.pair(ui.quiet("মডেল নামান") { downloadSpeakerModel() }, ui.tonal("রেজিস্টার") { enrollVoice() }))
        val (vRow, vSw) = ui.switchRow("শুধু আমার কণ্ঠে সাড়া দাও", null, prefs.getBoolean("voiceLock", false))
        voiceBox = vSw
        vSw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("voiceLock", on).apply()
            WakeService.instance?.reloadVoiceLock()
            refreshVoice()
        }
        vl.addView(vRow)
        strictGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        for (lv in VoiceStrictness.values()) strictGroup.addView(ui.radio(lv.label, 4000 + lv.ordinal))
        strictGroup.check(4000 + prefs.getInt("voiceStrict", 1).coerceIn(0, 2))
        strictGroup.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putInt("voiceStrict", checked - 4000).apply()
            WakeService.instance?.reloadVoiceLock()
        }
        vl.addView(strictGroup)
        vl.addView(ui.quiet("আমার কণ্ঠ মুছুন") {
            VoiceLockStore.clear(this)
            voiceBox.isChecked = false
            WakeService.instance?.reloadVoiceLock()
            refreshVoice()
        })
        col.addView(vl)

        // --- calls ---
        val calls = ui.card("কল ও কন্টাক্ট")
        val (cfRow, cfSw) = ui.switchRow("কল করার আগে জিজ্ঞেস করা", "\"কল করব?\"। সার্ভারের বেছে নেওয়া কল সবসময় জিজ্ঞেস করা হয়", prefs.getBoolean("confirmCall", true))
        confirmBox = cfSw; saveOn(cfSw)
        calls.addView(cfRow)
        simStatus = ui.statusText()
        calls.addView(simStatus)
        simGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        calls.addView(simGroup)
        calls.addView(ui.label("উপনাম", 14f, bold = true))
        nickInput = ui.input("প্রতি লাইনে একটি: বাবা = রহিম উদ্দিন", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, prefs.getString("nicknames", "") ?: "", 2)
        calls.addView(nickInput)
        calls.addView(ui.tonal("উপনাম সেভ") { saveSettings(); LogStore.add(this, "উপনাম সেভ হয়েছে") })
        col.addView(calls)

        // --- smart assistant (LLM) ---
        val llm = ui.card("স্মার্ট সহকারী (ঐচ্ছিক, ইন্টারনেট লাগে)", "আপনার নিজের প্রক্সি সার্ভার দিয়ে চলে। বন্ধ থাকলে কিছুই বাইরে যায় না")
        val (lRow, lSw) = ui.switchRow("না বুঝলে LLM-কে জিজ্ঞেস করা", "শুধু যে বাক্য অফলাইনে বোঝা যায়নি, সেটুকুর লেখা যায়। কন্টাক্ট বা অন্য কিছু যায় না", prefs.getBoolean(AndroidLlm.KEY_ENABLED, false))
        llmBox = lSw
        llm.addView(lRow)
        llmUrlInput = ui.input("https://.../api/assist", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, prefs.getString(AndroidLlm.KEY_URL, "") ?: "")
        llm.addView(llmUrlInput)
        llmTokenInput = ui.input("টোকেন (প্রক্সির APP_TOKEN)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, prefs.getString(AndroidLlm.KEY_TOKEN, "") ?: "")
        llm.addView(llmTokenInput)
        llm.addView(ui.divider())
        val (aRow, aSw) = ui.switchRow("স্ক্রিন-এজেন্ট", "বড় কাজ, যেমন \"WhatsApp-এ রহিমকে হাই পাঠাও\": স্ক্রিন দেখে ধাপে ধাপে ট্যাপ ও টাইপ করে। চালু থাকলে কাজ চলার সময় স্ক্রিনের লেখা আপনার সার্ভারে যায়", prefs.getBoolean(AndroidLlm.KEY_AGENT, false))
        agentBox = aSw
        llm.addView(aRow)
        llm.addView(ui.expandable("নিরাপত্তা", "পাসওয়ার্ডের স্ক্রিন ও টাকার অ্যাপ (বিকাশ, নগদ, ব্যাংক) কখনো পড়া বা পাঠানো হয় না, লম্বা সংখ্যা ঢেকে যায়। পাঠানো, কেনা, মোছা, কল: এসব বাটন চাপার আগে আপনাকে জিজ্ঞেস করে। নোটিফিকেশনে \"থামাও\" বোতাম থাকে। Accessibility চালু থাকতে হবে।"))
        llmStatus = ui.statusText()
        llm.addView(llmStatus)
        llm.addView(ui.pair(ui.primary("সেভ করুন") {
            saveSettings()
            llmStatus.text = (if (llmBox.isChecked) "LLM চালু" else "LLM বন্ধ") + ", " + (if (agentBox.isChecked) "স্ক্রিন-এজেন্ট চালু" else "স্ক্রিন-এজেন্ট বন্ধ") + " (সেভ হয়েছে)"
            refreshSttNote()
        }, ui.quiet("সংযোগ টেস্ট") {
            saveSettings()
            llmStatus.text = "পরীক্ষা করছি…"
            AndroidLlm.test(this) { llmStatus.text = it }
        }))
        col.addView(llm)

        val safe = ui.card("নিরাপত্তা", "যা সবসময় আপনাকে জিজ্ঞেস করে করা হয়")
        safe.addView(ui.hint("• এসএমএস/WhatsApp মেসেজ: পড়ে শুনিয়ে \"পাঠাব?\" জিজ্ঞেস করে, হ্যাঁ বললে তবেই যায়\n• কল: নাম ও নম্বর বলে জিজ্ঞেস করে (সুইচ বন্ধ থাকলেও সার্ভারের বেছে নেওয়া কল জিজ্ঞেস করে)\n• সার্ভারের বেছে নেওয়া এয়ারপ্লেন, হটস্পট, মোবাইল ডেটা, DND: আগে জিজ্ঞেস করে\n• নিজে নিজে চলা রুটিনে কল বা মেসেজ চলে না"))
        col.addView(safe)
        return sv
    }

    private fun seek(min: Int, max: Int, start: Int, onChange: (Int) -> Unit, onDone: (Int) -> Unit): SeekBar = SeekBar(this).apply {
        this.max = max - min
        progress = start
        progressTintList = ColorStateList.valueOf(ui.c(R.color.bx_primary))
        thumbTintList = ColorStateList.valueOf(ui.c(R.color.bx_primary))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = onChange(p)
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) = onDone(sb.progress)
        })
    }

    private fun savePhrase() {
        when (val r = WakePhrase.parse(phraseInput.text.toString(), ModelManager.vocabulary(this))) {
            is WakePhrase.Parse.Ok -> {
                prefs.edit().putString("wakePhrase", if (r.phrase.isDefault) "" else r.phrase.text).apply()
                phraseStatus.text = "✓ সেভ হয়েছে: \"${r.phrase.display}\"" +
                    (if (WakeService.running) "। শোনা বন্ধ করে আবার চালু করলে কাজ করবে" else "") +
                    (if (VoiceLockStore.hasPrint(this) && !r.phrase.isDefault) "।\nকণ্ঠ-লক চালু থাকলে নতুন ওয়েক ওয়ার্ডে আবার রেজিস্টার করুন" else "") +
                    (if (ModelManager.vocabulary(this) == null) "।\n(মডেলের শব্দ তালিকা না পাওয়ায় শব্দ যাচাই হয়নি, ধরছে কি না লগে দেখুন)" else "")
                LogStore.add(this, "ওয়েক ওয়ার্ড সেভ: ${r.phrase.text}")
                refreshHome()
            }
            is WakePhrase.Parse.Bad -> phraseStatus.text = "✗ ${r.reason}"
        }
    }

    private fun refreshSttNote() {
        val mode = SttMode.from(prefs.getString("sttMode", "system"))
        val configured = WhisperStt.configFrom(this) != null
        sttNote.text = when {
            mode == SttMode.SYSTEM -> "ফোনের নিজস্ব ইঞ্জিন ব্যবহার হচ্ছে"
            !configured -> "⚠ Whisper-এর জন্য নিচের কার্ডে https ঠিকানা ও টোকেন দিয়ে সেভ করুন, নাহলে ফোনের ইঞ্জিনই চলবে"
            else -> "Whisper চালু: ${SttEndpoint.fromAssistUrl(prefs.getString(AndroidLlm.KEY_URL, ""))}"
        }
    }

    private fun refreshTtsInfo() {
        val v = prefs.getString("ttsVoice", "").orEmpty()
        ttsInfo.text = if (v.isEmpty()) "ভয়েস: অটো (সবচেয়ে ভালো অফলাইন ভয়েস)" else "ভয়েস: $v"
    }

    /** lists the installed voices of the chosen language; the person picks one, or "auto" */
    private fun pickVoice() {
        val sp = Speaker(this)
        sp.init(currentLang()) {
            if (isDestroyed) { sp.shutdown(); return@init }
            val list = sp.voices()
            if (list.isEmpty()) {
                Toast.makeText(this, "এই ভাষার ভয়েস পাওয়া যায়নি। \"ইঞ্জিন সেটিংস\" থেকে ভয়েস ডেটা নামান", Toast.LENGTH_LONG).show()
                sp.shutdown()
                return@init
            }
            val names = listOf("অটো (সবচেয়ে ভালো অফলাইন ভয়েস)") + list.map { it.label + (if (it.needsNetwork) " · ইন্টারনেট লাগে" else "") }
            AlertDialog.Builder(this)
                .setTitle("ভয়েস বাছুন")
                .setItems(names.toTypedArray()) { _, i ->
                    prefs.edit().putString("ttsVoice", if (i == 0) "" else list[i - 1].name).apply()
                    speaker?.shutdown(); speaker = null
                    refreshTtsInfo()
                    testTts()
                }
                .setOnDismissListener { sp.shutdown() }
                .show()
        }
    }

    // ---------- tab 4: tests and log ----------

    private fun buildTools(): View {
        val (sv, col) = ui.page()

        val quick = ui.card("দ্রুত পরীক্ষা")
        quick.addView(ui.tonal("🔊  TTS টেস্ট (আওয়াজ আসছে?)") { testTts() })
        quick.addView(ui.tonal("🎤  কথা বলো (ওয়েক ওয়ার্ড ছাড়াই পুরো ধারা)") { talkNow() })
        quick.addView(ui.tonal("STT টেস্ট (শুধু বাক্য চেনা)") { runSttTest() })
        col.addView(quick)

        val an = ui.card("কমান্ড বিশ্লেষণ", "কিছু চালানো ছাড়া দেখুন কী বুঝবে")
        testInput = ui.input("একটা বাক্য বা নাম লিখুন")
        an.addView(testInput)
        an.addView(ui.pair(ui.primary("কী বুঝবে") { dryRun() }, ui.quiet("কন্টাক্ট মেলাও") { testContactMatch() }))
        col.addView(an)

        val rep = ui.card("চেকলিস্ট ও রিপোর্ট")
        rep.addView(ui.primary("চেকলিস্ট খুলুন (প্রতি ফোনে)") { startActivity(Intent(this, ChecklistActivity::class.java)) })
        rep.addView(ui.pair(ui.quiet("ডিভাইস রিপোর্ট কপি") { copyDeviceReport() }, ui.quiet("ক্র্যাশ লগ কপি") { copyCrashLog() }))
        rep.addView(ui.quiet("ক্র্যাশ লগ মুছুন") { CrashLog.clear(this); refreshStability() })
        col.addView(rep)

        val log = ui.card("লগ")
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(ui.c(R.color.bx_log_text))
            background = ui.rounded(ui.c(R.color.bx_log_bg), 12)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            setTextIsSelectable(true)
        }
        log.addView(logView)
        log.addView(ui.pair(ui.quiet("লগ কপি") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("buildx-log", LogStore.read(this)))
            Toast.makeText(this, "কপি হয়েছে", Toast.LENGTH_SHORT).show()
        }, ui.quiet("লগ মুছুন") { LogStore.clear(this) }))
        col.addView(log)
        return sv
    }

    override fun onResume() {
        super.onResume()
        refreshModelStatus()
        refreshPermStatus()
        refreshSim()
        refreshA11y()
        refreshAssistant()
        refreshStability()
        refreshVoice()
        refreshSttNote()
        refreshTtsInfo()
        refreshLog()
        LogStore.listener = { runOnUiThread { refreshLog() } }
    }

    override fun onPause() {
        LogStore.listener = null
        saveSettings()
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
        val text = testInput.text.toString().trim()
        if (text.isEmpty()) {
            LogStore.add(this, "ঘরে একটা বাক্য লিখুন, তারপর এই বাটন")
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
        val name = testInput.text.toString().trim()
        if (name.isEmpty()) {
            LogStore.add(this, "ঘরে একটা নাম লিখুন, তারপর এই বাটন")
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
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CAMERA,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS
        ).plus(if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
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
            .putBoolean("assistPopup", popupBox.isChecked)
            .putBoolean("assistAutoStart", autoStartBox.isChecked)
            .putString("nicknames", nickInput.text.toString())
            .putBoolean(AndroidLlm.KEY_ENABLED, llmBox.isChecked)
            .putBoolean(AndroidLlm.KEY_AGENT, agentBox.isChecked)
            .putString(AndroidLlm.KEY_URL, llmUrlInput.text.toString().trim())
            .putString(AndroidLlm.KEY_TOKEN, llmTokenInput.text.toString().trim())
            .apply()
    }

    private fun currentLang(): String =
        if (langGroup.checkedRadioButtonId == idEnglish) "en-US" else "bn-BD"

    private fun refreshAssistant() {
        val active = BuildXVoiceInteractionService.isActive(this)
        assistStatus.text = if (active) "ডিফল্ট অ্যাসিস্ট্যান্ট: ✓ BuildX AI" else "ডিফল্ট অ্যাসিস্ট্যান্ট: ✗ BuildX নয় (নিচের বাটনে বেছে নিন)"
        val admin = BuildXDeviceAdmin.isActive(this)
        adminStatus.text = if (admin) "Device admin: ✓ চালু (স্ক্রিন লক করতে পারবে)" else "Device admin: ✗ বন্ধ"
    }

    private fun openAssistantSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                LogStore.add(this, "অ্যাসিস্ট্যান্ট সেটিংস খোলা যায়নি: ${e2.message}")
            }
        }
    }

    private fun testPopup() {
        val svc = BuildXVoiceInteractionService.instance
        if (svc == null) {
            LogStore.add(this, "পপআপ: BuildX এখনো ডিফল্ট অ্যাসিস্ট্যান্ট নয় (বা সিস্টেম এখনো সংযোগ দেয়নি)")
            return
        }
        AssistantBridge.clear()
        if (svc.show(fromWake = true)) {
            AssistantBridge.post("🎙 পরীক্ষা: এটা পপআপের নমুনা")
            AssistantBridge.post("→ লগ এভাবে দেখাবে")
            AssistantBridge.hideLater(4000)
        }
    }

    private fun enableDeviceAdmin() {
        try {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, BuildXDeviceAdmin.component(this))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "শুধু \"স্ক্রিন লক করো\" কমান্ডের জন্য। আর কিছু করতে পারবে না। বন্ধ করতে অ্যাপের \"Device admin বন্ধ করুন\" বাটন চাপুন।")
            )
        } catch (e: Exception) {
            LogStore.add(this, "Device admin চালু করার স্ক্রিন খোলা যায়নি: ${e.message}")
        }
    }

    private fun disableDeviceAdmin() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.removeActiveAdmin(BuildXDeviceAdmin.component(this))
        refreshAssistant()
    }

    private fun refreshVoice() {
        val model = ModelManager.isSpkReady(this)
        val enrolled = VoiceLockStore.hasPrint(this)
        val on = VoiceLockStore.load(this) != null
        voiceStatus.text = "কণ্ঠ-মডেল: ${if (model) "✓" else "✗"}   রেজিস্টার: ${if (enrolled) "✓" else "✗"}   কণ্ঠ-লক: ${if (on) "চালু ✓" else "বন্ধ"}"
    }

    private fun downloadSpeakerModel() {
        if (ModelManager.isSpkReady(this)) {
            LogStore.add(this, "কণ্ঠ-মডেল আগেই নামানো আছে")
            return
        }
        LogStore.add(this, "কণ্ঠ-মডেল নামছে...")
        ModelManager.downloadSpk(this) { ok ->
            LogStore.add(this, if (ok) "কণ্ঠ-মডেল প্রস্তুত। শোনা বন্ধ করে আবার চালু করুন, তারপর রেজিস্টার করুন" else "কণ্ঠ-মডেল নামানো যায়নি (ইন্টারনেট?)")
            refreshVoice()
        }
    }

    private fun enrollVoice() {
        val svc = WakeService.instance
        if (svc == null) {
            LogStore.add(this, "আগে \"শোনা চালু করুন\" চাপুন, তারপর রেজিস্টার")
            return
        }
        LogStore.add(this, "কণ্ঠ রেজিস্টার: ৫ বার \"${WakePhrase.fromPref(prefs.getString("wakePhrase", "")).display}\" বলুন, প্রতিবার একটু থেমে (৯০ সেকেন্ড সময়)")
        svc.startEnroll(5, { n, d ->
            LogStore.add(this, "কণ্ঠ নমুনা $n/৫${d?.let { " (গড় থেকে দূরত্ব ${"%.2f".format(it)})" } ?: ""}")
        }, { ok, msg ->
            LogStore.add(this, (if (ok) "✓ " else "✗ ") + msg)
            voiceBox.isChecked = VoiceLockStore.load(this) != null
            refreshVoice()
        })
    }

    private fun openSpecialPermission(action: String) {
        try {
            val i = if (action == Settings.ACTION_MANAGE_WRITE_SETTINGS) Intent(action, Uri.parse("package:$packageName")) else Intent(action)
            startActivity(i)
        } catch (e: Exception) {
            LogStore.add(this, "সেটিংস খোলা যায়নি: ${e.message}")
        }
    }

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
            "এসএমএস পাঠানো" to has(Manifest.permission.SEND_SMS),
            "এসএমএস পড়া" to has(Manifest.permission.READ_SMS),
            "নোটিফিকেশন পড়া" to BuildXNotificationListener.isEnabledInSettings(this),
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

    private fun forceEnableA11y() {
        if (A11yEnabler.enable(this)) {
            LogStore.add(this, "Accessibility চালু করা হয়েছে (কয়েক সেকেন্ডে সংযুক্ত দেখাবে)")
            refreshA11y()
        } else {
            LogStore.add(this, "অনুমতি নেই। কম্পিউটারে ফোন USB বা ওয়্যারলেস ডিবাগিংয়ে যুক্ত করে একবার চালান: adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
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
        for (r in statusRows) r.refresh()
        refreshHome()
    }

    private fun refreshModelStatus() {
        modelStatus.text = when {
            ModelManager.isReady(this) -> ""
            ModelManager.downloading -> "মডেল নামছে... (লগে অগ্রগতি দেখুন)"
            else -> ""
        }
        refreshPermStatus()
    }

    private fun tick(ok: Boolean): String = if (ok) "✓" else "✗"

    /** the home tab shows the last few lines; the full log (can be 200 KB) is only put on screen while its tab is open */
    private fun refreshLog() {
        val all = LogStore.read(this)
        homeLog.text = lastLines(all)
        if (currentTab == 3) logView.text = all
        refreshHome()
    }
}
