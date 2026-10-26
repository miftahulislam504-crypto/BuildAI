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
    private lateinit var homeOrb: Ui.Orb
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

        headerTitle = ui.label("BuildX AI", 28f, bold = true).apply {
            letterSpacing = -0.02f
            setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(12))
        }
        tabBar = Ui.TabBar(ui, listOf("Home", "Setup", "Settings", "Test")) { showTab(it) }

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
        headerTitle.text = listOf("BuildX AI", "Setup", "Settings", "Tests and log")[currentTab]
        if (currentTab == 3) logView.text = LogStore.read(this)
    }

    // ---------- tab 1: home ----------

    private fun buildHome(): View {
        val (sv, col) = ui.page()

        val hero = ui.card()
        homeOrb = ui.Orb()
        hero.addView(homeOrb.view)
        homePill = ui.pill("Idle", Ui.Kind.INFO).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        hero.addView(homePill)
        homeHint = ui.hint("").apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(12), 0, ui.dp(16))
        }
        hero.addView(homeHint)
        homeToggle = ui.primary("Start listening") { toggleListening() }
        hero.addView(homeToggle)
        hero.addView(ui.pair(ui.tonal("Speak") { talkNow() }, ui.quiet("Play sample") { testTts() }))
        col.addView(hero)

        val cmd = ui.card("Type a command", "Runs through the same pipeline as voice, in the language chosen in Settings")
        cmdInput = ui.input("Type a command").apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> runTextCommand(); true }
            setSingleLine(true)
        }
        cmd.addView(cmdInput)
        cmd.addView(ui.primary("Run") { runTextCommand() })
        col.addView(cmd)

        val ready = ui.card("Readiness")
        homeReady = ui.statusText()
        ready.addView(homeReady)
        ready.addView(ui.tonal("Open setup") { showTab(1) })
        col.addView(ready)

        val rec = ui.card("Recent activity")
        homeLog = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(ui.c(R.color.bx_log_text))
            background = ui.rounded(ui.c(R.color.bx_log_bg), 12)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        }
        rec.addView(homeLog)
        rec.addView(ui.quiet("View full log") { showTab(3) })
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
        homeOrb.set(on)
        ui.setPill(homePill, if (on) "Listening" else "Idle", if (on) Ui.Kind.OK else Ui.Kind.INFO)
        homeToggle.text = if (on) "Stop listening" else "Start listening"
        val phrase = WakePhrase.fromPref(prefs.getString("wakePhrase", ""))
        homeHint.text = if (on) "Say \"${phrase.display}\" to wake the assistant" else "Start listening to wake the assistant with \"${phrase.display}\""
        val missing = statusRows.filter { it.isRequired && !it.ok }.map { it.label }
        homeReady.text = if (missing.isEmpty()) "Everything required is ready" else "Still needed: ${missing.joinToString(", ")}"
    }

    private fun lastLines(all: String): String =
        all.takeLast(3000).trimEnd().lines().takeLast(8).joinToString("\n").ifBlank { "Nothing yet" }

    // ---------- tab 2: setup (permissions and system switches) ----------

    private fun addRow(card: LinearLayout, name: String, required: Boolean, fix: (() -> Unit)?, check: () -> Boolean) {
        val row = ui.StatusRow(name, check, fix, required)
        statusRows.add(row)
        card.addView(row.view)
    }

    private fun buildSetup(): View {
        val (sv, col) = ui.page()
        fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

        val must = ui.card("Required", "The wake word cannot work without these")
        addRow(must, "Microphone", true, { ensurePerms { refreshPermStatus() } }) { has(Manifest.permission.RECORD_AUDIO) }
        addRow(must, "Notifications", true, { ensurePerms { refreshPermStatus() } }) {
            Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
        }
        addRow(must, "Battery optimization exemption", true, { requestBatteryExemption() }) {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        }
        addRow(must, "Wake word model (about 40 MB, one time)", true, { downloadModel() }) { ModelManager.isReady(this) }
        modelStatus = ui.statusText()
        must.addView(modelStatus)
        col.addView(must)

        val people = ui.card("Calls, messages and contacts", "Grant only what you plan to use")
        for ((name, perm) in listOf(
            "Make calls" to Manifest.permission.CALL_PHONE,
            "Read contacts" to Manifest.permission.READ_CONTACTS,
            "Phone state (SIM)" to Manifest.permission.READ_PHONE_STATE,
            "Camera (torch)" to Manifest.permission.CAMERA,
            "Send SMS" to Manifest.permission.SEND_SMS,
            "Read SMS" to Manifest.permission.READ_SMS
        )) addRow(people, name, false, { requestCallPerms() }) { has(perm) }
        col.addView(people)

        val special = ui.card("Special access", "These are granted from system settings")
        addRow(special, "Display over other apps", false, { requestOverlay() }) { BackgroundLauncher.canOverlay(this) }
        addRow(special, "Modify system settings (brightness)", false, { openSpecialPermission(Settings.ACTION_MANAGE_WRITE_SETTINGS) }) { Settings.System.canWrite(this) }
        addRow(special, "Do Not Disturb control", false, { openSpecialPermission(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).isNotificationPolicyAccessGranted
        }
        addRow(special, "Notification access (who is calling)", false, { openSpecialPermission(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }) {
            BuildXNotificationListener.isEnabledInSettings(this)
        }
        col.addView(special)

        val a11y = ui.card("Accessibility", "Screen lock, Wi-Fi and Bluetooth toggles, tapping and typing")
        a11yStatus = ui.statusText()
        a11y.addView(a11yStatus)
        a11y.addView(ui.primary("Open Accessibility settings") { openAccessibilitySettings() })
        a11y.addView(ui.pair(ui.quiet("App info") { openAppInfo() }, ui.quiet("Force enable") { forceEnableA11y() }))
        a11y.addView(ui.expandable("How to turn it on",
            "1. Tap \"Open Accessibility settings\", open \"Installed apps\" (or \"Downloaded apps\"), choose BuildX AI and turn it on.\n\n" +
                "2. If the phone blocks it with \"Restricted setting\": tap OK, come back, tap \"App info\", open the menu at the top right, choose \"Allow restricted settings\", confirm with your PIN or fingerprint, then repeat step 1. This option only appears after the block message has been shown once.\n\n" +
                "3. If that still fails, the screen lock card below can at least lock the screen.\n\n" +
                "Without Accessibility, only locking the screen and toggling Wi-Fi or Bluetooth automatically will not work. Everything else will."))
        val (autoRow, autoSw) = ui.switchRow("Toggle Wi-Fi and Bluetooth automatically", "Needs Accessibility", prefs.getBoolean("autoToggle", true))
        autoToggleBox = autoSw
        a11y.addView(autoRow)
        col.addView(a11y)

        val assist = ui.card("Digital assistant", "Enables the popup and lets the system keep the app alive")
        assistStatus = ui.statusText()
        assist.addView(assistStatus)
        assist.addView(ui.primary("Open default assistant settings") { openAssistantSettings() })
        assist.addView(ui.expandable("What does this do?",
            "Choose BuildX AI there. Then: (1) pressing and holding the side key or home gesture starts listening and shows the popup, (2) the wake word shows the popup even over the lock screen, (3) the system keeps this app running from the moment the phone starts.\n\n" +
                "Note: being the assistant does not let it unlock the phone (only your PIN or fingerprint can), and the system does not provide the wake word engine. BuildX's own service does that."))
        val (popRow, popSw) = ui.switchRow("Show popup on the wake word", null, prefs.getBoolean("assistPopup", true))
        popupBox = popSw
        assist.addView(popRow)
        val (autoStartRow, autoStartSw) = ui.switchRow("Start listening when the assistant starts", null, prefs.getBoolean("assistAutoStart", true))
        autoStartBox = autoStartSw
        assist.addView(autoStartRow)
        assist.addView(ui.quiet("Test popup") { testPopup() })
        col.addView(assist)

        val admin = ui.card("Screen lock", "An alternative to Accessibility for locking the screen")
        adminStatus = ui.statusText()
        admin.addView(adminStatus)
        admin.addView(ui.pair(ui.tonal("Enable") { enableDeviceAdmin() }, ui.quiet("Disable") { disableDeviceAdmin() }))
        col.addView(admin)

        val stab = ui.card("Device and stability", "Keeps the phone from killing the service")
        stabilityStatus = ui.statusText()
        stab.addView(stabilityStatus)
        stab.addView(ui.tonal("Keep-alive guide for this phone") { showOemGuide() })
        col.addView(stab)
        return sv
    }

    // ---------- tab 3: settings ----------

    private fun saveOn(sw: CompoundButton) = sw.setOnCheckedChangeListener { _, _ -> saveSettings() }

    private fun buildSettings(): View {
        val (sv, col) = ui.page()

        val lang = ui.card("Language", "The language of your commands and the spoken replies")
        langGroup = RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.radio("Bangla", idBangla).apply { layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(ui.radio("English", idEnglish).apply { layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f) })
            check(if (prefs.getString("lang", "bn-BD") == "en-US") idEnglish else idBangla)
            setOnCheckedChangeListener { _, _ -> saveSettings() }
        }
        lang.addView(langGroup)
        col.addView(lang)

        // --- wake word ---
        val wake = ui.card("Wake word", "The phrase that wakes the assistant. Stop and restart listening for changes to apply")
        phraseInput = ui.input("Default: hey build", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, prefs.getString("wakePhrase", "") ?: "")
        wake.addView(phraseInput)
        phraseStatus = ui.statusText().apply { text = "2 to 4 English words, spelled the way they sound, for example: hey jarvis, ok buddy" }
        wake.addView(phraseStatus)
        wake.addView(ui.tonal("Validate and save") { savePhrase() })
        wake.addView(ui.divider())
        wake.addView(ui.label("Sensitivity", 14f, bold = true))
        sensGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        for (lv in WakeSensitivity.values()) sensGroup.addView(ui.radio(lv.label, 3000 + lv.ordinal))
        sensGroup.check(3000 + prefs.getInt("wakeSens", 1).coerceIn(0, 2))
        sensGroup.setOnCheckedChangeListener { _, checked -> prefs.edit().putInt("wakeSens", checked - 3000).apply() }
        wake.addView(sensGroup)
        wake.addView(ui.divider())
        val (wlRow, wlSw) = ui.switchRow("Keep listening with the screen off", "Holds a wake lock", prefs.getBoolean("wakeLock", true))
        wakeLockBox = wlSw; saveOn(wlSw)
        wake.addView(wlRow)
        val (lpRow, lpSw) = ui.switchRow("Low-power listening (experimental)", "Recognition runs only when a voice is detected and sleeps the rest of the time. Turn it off if the wake word is missed", prefs.getBoolean("lowPower", false))
        lowPowerBox = lpSw
        lpSw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("lowPower", on).apply()
            LogStore.add(this, "Low-power listening: ${if (on) "on" else "off"} (applies when listening restarts)")
        }
        wake.addView(lpRow)
        val (vbRow, vbSw) = ui.switchRow("Vibrate when woken", null, prefs.getBoolean("wakeVibrate", false))
        vibrateBox = vbSw
        vbSw.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("wakeVibrate", on).apply() }
        wake.addView(vbRow)
        col.addView(wake)

        // --- how it hears you (STT) ---
        val stt = ui.card("Speech recognition", "The engine that turns your command into text")
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
        stt.addView(ui.hint("If you choose Whisper, the audio of your commands is sent to your own proxy server (https only). If that fails, the phone's own engine takes over. Enter the address and token in the Smart assistant card below."))
        col.addView(stt)

        // --- how it talks (TTS) ---
        val tts = ui.card("Voice", "Voice, speed and pitch of the spoken replies")
        ttsInfo = ui.statusText()
        tts.addView(ttsInfo)
        tts.addView(ui.tonal("Choose voice") { pickVoice() })
        val rateLabel = ui.label("Speed: ${prefs.getInt("ttsRate", 100)}%", 13f)
        tts.addView(rateLabel)
        tts.addView(seek(0, 90, prefs.getInt("ttsRate", 100).coerceIn(60, 150) - 60, { rateLabel.text = "Speed: ${it + 60}%" }) {
            prefs.edit().putInt("ttsRate", it + 60).apply(); speaker?.reapplyTuning()
        })
        val pitchLabel = ui.label("Pitch: ${prefs.getInt("ttsPitch", 100)}%", 13f)
        tts.addView(pitchLabel)
        tts.addView(seek(0, 60, prefs.getInt("ttsPitch", 100).coerceIn(70, 130) - 70, { pitchLabel.text = "Pitch: ${it + 70}%" }) {
            prefs.edit().putInt("ttsPitch", it + 70).apply(); speaker?.reapplyTuning()
        })
        tts.addView(ui.pair(ui.primary("Play sample") { testTts() }, ui.quiet("Engine settings") { openTtsSettings() }))
        col.addView(tts)

        // --- voice lock ---
        val vl = ui.card("My voice", "Respond only when I say the wake word")
        voiceStatus = ui.statusText()
        vl.addView(voiceStatus)
        vl.addView(ui.expandable("How to set it up",
            "(1) Download the voice model (once, about 13 MB, needs internet). (2) Start listening so the service is running. (3) Tap Enroll, then say the wake word 5 times in a quiet place, pausing between each. (4) Turn on the switch below.\n\n" +
                "Your voice is matched only while you say the wake word, so other people cannot wake it, but someone speaking a command after you have woken it will not be blocked. If you change the wake word, enroll again. If it misfires, switch between Strict and Loose below and look for the \"WAKE-VOICE distance\" line in the log."))
        vl.addView(ui.pair(ui.quiet("Download model") { downloadSpeakerModel() }, ui.tonal("Enroll") { enrollVoice() }))
        val (vRow, vSw) = ui.switchRow("Respond to my voice only", null, prefs.getBoolean("voiceLock", false))
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
        vl.addView(ui.quiet("Delete my voice") {
            VoiceLockStore.clear(this)
            voiceBox.isChecked = false
            WakeService.instance?.reloadVoiceLock()
            refreshVoice()
        })
        col.addView(vl)

        // --- calls ---
        val calls = ui.card("Calls and contacts")
        val (cfRow, cfSw) = ui.switchRow("Ask before calling", "Asks \"Call?\" first. Calls chosen by the server always ask", prefs.getBoolean("confirmCall", true))
        confirmBox = cfSw; saveOn(cfSw)
        calls.addView(cfRow)
        simStatus = ui.statusText()
        calls.addView(simStatus)
        simGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        calls.addView(simGroup)
        calls.addView(ui.label("Nicknames", 14f, bold = true))
        nickInput = ui.input("One per line: Dad = Rahim Uddin", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, prefs.getString("nicknames", "") ?: "", 2)
        calls.addView(nickInput)
        calls.addView(ui.tonal("Save nicknames") { saveSettings(); LogStore.add(this, "Nicknames saved") })
        col.addView(calls)

        // --- smart assistant (LLM) ---
        val llm = ui.card("Smart assistant", "Optional, needs internet. Runs through your own proxy server and sends nothing while it is off")
        val (lRow, lSw) = ui.switchRow("Ask the LLM when a command is not understood", "Only the sentence that could not be understood offline is sent. No contacts or other data", prefs.getBoolean(AndroidLlm.KEY_ENABLED, false))
        llmBox = lSw
        llm.addView(lRow)
        llmUrlInput = ui.input("https://.../api/assist", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, prefs.getString(AndroidLlm.KEY_URL, "") ?: "")
        llm.addView(llmUrlInput)
        llmTokenInput = ui.input("Token (the proxy's APP_TOKEN)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, prefs.getString(AndroidLlm.KEY_TOKEN, "") ?: "")
        llm.addView(llmTokenInput)
        llm.addView(ui.divider())
        val (aRow, aSw) = ui.switchRow("Screen agent", "For bigger tasks, such as \"send Rahim hi on WhatsApp\": reads the screen and taps and types step by step. While it is on, screen text is sent to your server during a task", prefs.getBoolean(AndroidLlm.KEY_AGENT, false))
        agentBox = aSw
        llm.addView(aRow)
        llm.addView(ui.expandable("Safety", "Password screens and money apps (bKash, Nagad, banks) are never read or sent, and long numbers are masked. Before pressing a button for send, buy, delete or call, it asks you first. The notification has a Stop button. Accessibility must be on."))
        llmStatus = ui.statusText()
        llm.addView(llmStatus)
        llm.addView(ui.pair(ui.primary("Save") {
            saveSettings()
            llmStatus.text = "LLM " + (if (llmBox.isChecked) "on" else "off") + ", screen agent " + (if (agentBox.isChecked) "on" else "off") + " (saved)"
            refreshSttNote()
        }, ui.quiet("Test connection") {
            saveSettings()
            llmStatus.text = "Testing..."
            AndroidLlm.test(this) { llmStatus.text = it }
        }))
        col.addView(llm)

        val safe = ui.card("Safety", "Always done only after asking you")
        safe.addView(ui.hint("- SMS and WhatsApp messages: reads the message back, asks \"Send?\", and sends only after you say yes\n- Calls: says the name and number and asks first (calls chosen by the server ask even when the switch is off)\n- Airplane mode, hotspot, mobile data and Do Not Disturb chosen by the server: asks first\n- Routines that run on their own never place calls or send messages"))
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
                phraseStatus.text = "Saved: \"${r.phrase.display}\"" +
                    (if (WakeService.running) ". Stop and restart listening to apply it" else "") +
                    (if (VoiceLockStore.hasPrint(this) && !r.phrase.isDefault) ".\nIf voice lock is on, enroll your voice again with the new wake word" else "") +
                    (if (ModelManager.vocabulary(this) == null) ".\n(The model's word list was not found, so the words were not checked. See in the log whether it triggers)" else "")
                LogStore.add(this, "Wake word saved: ${r.phrase.text}")
                refreshHome()
            }
            is WakePhrase.Parse.Bad -> phraseStatus.text = r.reason
        }
    }

    private fun refreshSttNote() {
        val mode = SttMode.from(prefs.getString("sttMode", "system"))
        val configured = WhisperStt.configFrom(this) != null
        sttNote.text = when {
            mode == SttMode.SYSTEM -> "Using the phone's own engine"
            !configured -> "Whisper needs an https address and token in the card below. Until then the phone's own engine is used"
            else -> "Whisper on: ${SttEndpoint.fromAssistUrl(prefs.getString(AndroidLlm.KEY_URL, ""))}"
        }
    }

    private fun refreshTtsInfo() {
        val v = prefs.getString("ttsVoice", "").orEmpty()
        ttsInfo.text = if (v.isEmpty()) "Voice: Auto (best offline voice)" else "Voice: $v"
    }

    /** lists the installed voices of the chosen language; the person picks one, or "auto" */
    private fun pickVoice() {
        val sp = Speaker(this)
        sp.init(currentLang()) {
            if (isDestroyed) { sp.shutdown(); return@init }
            val list = sp.voices()
            if (list.isEmpty()) {
                Toast.makeText(this, "No voices found for this language. Download voice data from Engine settings", Toast.LENGTH_LONG).show()
                sp.shutdown()
                return@init
            }
            val names = listOf("Auto (best offline voice)") + list.map { it.label + (if (it.needsNetwork) " (needs internet)" else "") }
            AlertDialog.Builder(this)
                .setTitle("Choose voice")
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

        val quick = ui.card("Quick tests")
        quick.addView(ui.tonal("Speech output test (can you hear it?)") { testTts() })
        quick.addView(ui.tonal("Talk (full flow without the wake word)") { talkNow() })
        quick.addView(ui.tonal("Speech input test (recognition only)") { runSttTest() })
        col.addView(quick)

        val an = ui.card("Command analysis", "See how a sentence is understood without running it")
        testInput = ui.input("Type a sentence or a name")
        an.addView(testInput)
        an.addView(ui.pair(ui.primary("Analyze") { dryRun() }, ui.quiet("Match contact") { testContactMatch() }))
        col.addView(an)

        val rep = ui.card("Checklist and reports")
        rep.addView(ui.primary("Open device checklist") { startActivity(Intent(this, ChecklistActivity::class.java)) })
        rep.addView(ui.pair(ui.quiet("Copy device report") { copyDeviceReport() }, ui.quiet("Copy crash log") { copyCrashLog() }))
        rep.addView(ui.quiet("Clear crash log") { CrashLog.clear(this); refreshStability() })
        col.addView(rep)

        val log = ui.card("Log")
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(ui.c(R.color.bx_log_text))
            background = ui.rounded(ui.c(R.color.bx_log_bg), 12)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            setTextIsSelectable(true)
        }
        log.addView(logView)
        log.addView(ui.pair(ui.quiet("Copy log") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("buildx-log", LogStore.read(this)))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }, ui.quiet("Clear log") { LogStore.clear(this) }))
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
            LogStore.add(this, "Model is already downloaded")
            return
        }
        if (ModelManager.downloading) {
            LogStore.add(this, "Model is downloading, please wait")
            return
        }
        ModelManager.download(this) {
            if (!isDestroyed) refreshModelStatus()
        }
        refreshModelStatus()
    }

    private fun startListening() {
        if (!ModelManager.isReady(this)) {
            LogStore.add(this, "Download the wake word model first")
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
                LogStore.add(this, "Stop listening first (the microphone cannot be shared)")
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
                LogStore.add(this, "Stop listening first (the microphone cannot be shared)")
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
                ActionResult(false, if (lang.startsWith("bn")) "কাজটি করতে পারিনি: ${e.message}" else "I could not do that: ${e.message}")
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
        LogStore.add(this, "Text reply: ${result.message}${if (result.followUp != null) "  (waiting for your answer: type it here)" else ""}")
        ensureSpeaker(lang).speak(result.message) { }
    }

    /** Shows how a sentence would be split into steps and what each step means. Nothing is run. */
    private fun dryRun() {
        val text = testInput.text.toString().trim()
        if (text.isEmpty()) {
            LogStore.add(this, "Type a sentence in the box first, then press this button")
            return
        }
        val steps = IntentParser.parseChain(text)
        LogStore.add(this, "Parsed: \"$text\" -> ${steps.size} step(s)")
        steps.forEachIndexed { i, p ->
            LogStore.add(this, " ${i + 1}. ${p.intent} ${p.slots}  — ${Describe.say(p, true)}")
        }
    }

    /** Shows which contact a spoken/typed name would pick and how sure it is. Nothing is called. */
    private fun testContactMatch() {
        val name = testInput.text.toString().trim()
        if (name.isEmpty()) {
            LogStore.add(this, "Type a name in the box first, then press this button")
            return
        }
        saveSettings()
        val all = AndroidContacts(this).all()
        if (all == null) {
            LogStore.add(this, "No permission to read contacts")
            return
        }
        val found = NameMatcher.find(name, all, NicknameBook.parse(prefs.getString("nicknames", "") ?: ""))
        val group = NameMatcher.bestGroup(found)
        if (found.isEmpty()) {
            LogStore.add(this, "MATCH \"$name\": no match (among ${all.size} contacts)")
            return
        }
        LogStore.add(this, "MATCH \"$name\": ${if (group.size == 1) "one confident match" else "${group.size} equal matches, will ask"}")
        for (m in found.take(5)) {
            val mark = if (m in group) ">" else " "
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
            LogStore.add(this, "Call and contact permissions are already granted")
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
                "SIM: grant the permission to choose a SIM here" else "SIM: single (default)"
            return
        }
        simStatus.text = "SIM to call with:"
        val saved = prefs.getString("simId", "") ?: ""
        val ids = ArrayList<String>()
        val labels = ArrayList<String>()
        ids.add(""); labels.add("Let the phone decide (default)")
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
            LogStore.add(this, "SIM selected: ${labels[checked - 2000]}")
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
            LogStore.add(this, "Could not open TTS settings directly; look under Settings > General management > Text-to-speech")
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
        assistStatus.text = if (active) "Default assistant: BuildX AI" else "Default assistant: not BuildX (choose it with the button below)"
        val admin = BuildXDeviceAdmin.isActive(this)
        adminStatus.text = if (admin) "Device admin: on (can lock the screen)" else "Device admin: off"
    }

    private fun openAssistantSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                LogStore.add(this, "Could not open assistant settings: ${e2.message}")
            }
        }
    }

    private fun testPopup() {
        val svc = BuildXVoiceInteractionService.instance
        if (svc == null) {
            LogStore.add(this, "Popup: BuildX is not the default assistant yet (or the system has not connected yet)")
            return
        }
        AssistantBridge.clear()
        if (svc.show(fromWake = true)) {
            // a short pretend exchange, so the popup can be seen in each of its states
            val h = window.decorView
            AssistantBridge.post("Listening...")
            h.postDelayed({ AssistantBridge.post("Heard: \"What time is it\"") }, 1200)
            h.postDelayed({ AssistantBridge.post("Result: It is 10:30") }, 2200)
            h.postDelayed({ AssistantBridge.post("Listening...") }, 3400)
            AssistantBridge.hideLater(6500)
        }
    }

    private fun enableDeviceAdmin() {
        try {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, BuildXDeviceAdmin.component(this))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Only for the \"lock the screen\" command. It cannot do anything else. To turn it off, press \"Disable\" in the app.")
            )
        } catch (e: Exception) {
            LogStore.add(this, "Could not open the Device admin screen: ${e.message}")
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
        voiceStatus.text = "Voice model: ${if (model) "ready" else "not downloaded"}\nEnrolled: ${if (enrolled) "yes" else "no"}\nVoice lock: ${if (on) "on" else "off"}"
    }

    private fun downloadSpeakerModel() {
        if (ModelManager.isSpkReady(this)) {
            LogStore.add(this, "Voice model is already downloaded")
            return
        }
        LogStore.add(this, "Voice model downloading...")
        ModelManager.downloadSpk(this) { ok ->
            LogStore.add(this, if (ok) "Voice model ready. Stop and restart listening, then enroll" else "Voice model download failed (no internet?)")
            refreshVoice()
        }
    }

    private fun enrollVoice() {
        val svc = WakeService.instance
        if (svc == null) {
            LogStore.add(this, "Press \"Start listening\" first, then enroll")
            return
        }
        LogStore.add(this, "Voice enrollment: say \"${WakePhrase.fromPref(prefs.getString("wakePhrase", "")).display}\" 5 times, pausing between each (90 seconds)")
        svc.startEnroll(5, { n, d ->
            LogStore.add(this, "Voice sample $n/5${d?.let { " (distance from average ${"%.2f".format(it)})" } ?: ""}")
        }, { ok, msg ->
            LogStore.add(this, msg)
            voiceBox.isChecked = VoiceLockStore.load(this) != null
            refreshVoice()
        })
    }

    private fun openSpecialPermission(action: String) {
        try {
            val i = if (action == Settings.ACTION_MANAGE_WRITE_SETTINGS) Intent(action, Uri.parse("package:$packageName")) else Intent(action)
            startActivity(i)
        } catch (e: Exception) {
            LogStore.add(this, "Could not open settings: ${e.message}")
        }
    }

    private fun refreshStability() {
        val maker = OemGuide.detect(Build.MANUFACTURER, Build.BRAND)
        val died = prefs.getBoolean("svcDied", false)
        val crash = CrashLog.read(this) != null
        stabilityStatus.text = "${Build.MANUFACTURER} ${Build.MODEL} ($maker)\n" +
            (if (died) "The service was stopped abnormally last time (${prefs.getInt("svcKills", 0)} times in total). See the guide\n" else "Service: stopped normally last time, or this is the first run\n") +
            (if (crash) "A crash log exists. Press \"Copy crash log\"" else "Crashes: none")
    }

    private fun showOemGuide() {
        val guide = OemGuide.forDevice(Build.MANUFACTURER, Build.BRAND)
        AlertDialog.Builder(this)
            .setTitle("Keep-alive guide")
            .setMessage(OemGuide.format(guide))
            .setPositiveButton("Open settings") { _, _ ->
                val specific = OemSettings.open(this, guide)
                if (!specific) LogStore.add(this, "Could not find the phone's own screen, opened app info instead. Follow the guide steps by hand")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun permissionMap(): Map<String, Boolean> {
        fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return linkedMapOf(
            "Microphone" to has(Manifest.permission.RECORD_AUDIO),
            "Notifications" to (Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)),
            "Calls" to has(Manifest.permission.CALL_PHONE),
            "Contacts" to has(Manifest.permission.READ_CONTACTS),
            "Send SMS" to has(Manifest.permission.SEND_SMS),
            "Read SMS" to has(Manifest.permission.READ_SMS),
            "Notification access" to BuildXNotificationListener.isEnabledInSettings(this),
            "Phone state" to has(Manifest.permission.READ_PHONE_STATE),
            "Battery exemption" to pm.isIgnoringBatteryOptimizations(packageName),
            "Overlay" to BackgroundLauncher.canOverlay(this),
            "Accessibility (connected)" to (BuildXAccessibilityService.instance != null)
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
        copyToClipboard("BuildX report", DeviceReport.format(info))
    }

    private fun copyCrashLog() {
        val c = CrashLog.read(this)
        if (c == null) Toast.makeText(this, "No crash log", Toast.LENGTH_SHORT).show()
        else copyToClipboard("BuildX crash", c)
    }

    private fun copyToClipboard(label: String, text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied. Paste it into a message", Toast.LENGTH_SHORT).show()
    }

    private fun refreshA11y() {
        val connected = BuildXAccessibilityService.instance != null
        val enabled = BuildXAccessibilityService.isEnabledInSettings(this)
        a11yStatus.text = when {
            connected -> "Accessibility: on and connected"
            enabled -> "Accessibility: enabled, waiting to connect (check again in a few seconds)"
            else -> "Accessibility: off (see the steps below)"
        }
    }

    private fun forceEnableA11y() {
        if (A11yEnabler.enable(this)) {
            LogStore.add(this, "Accessibility turned on (it will show as connected in a few seconds)")
            refreshA11y()
        } else {
            LogStore.add(this, "No permission. Connect the phone to a computer by USB or wireless debugging and run once: adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            LogStore.add(this, "Could not open Accessibility settings: ${e.message}")
        }
    }

    private fun openAppInfo() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            LogStore.add(this, "Could not open app info: ${e.message}")
        }
    }

    private fun requestOverlay() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            LogStore.add(this, "Could not open overlay settings: ${e.message}")
        }
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            LogStore.add(this, "Could not open battery settings: ${e.message}")
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
            if (audioOk) pendingAction?.invoke() else LogStore.add(this, "This will not work without the microphone permission")
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
            ModelManager.downloading -> "Model downloading... (see the log for progress)"
            else -> ""
        }
        refreshPermStatus()
    }

    /** the home tab shows the last few lines; the full log (can be 200 KB) is only put on screen while its tab is open */
    private fun refreshLog() {
        val all = LogStore.read(this)
        homeLog.text = lastLines(all)
        if (currentTab == 3) logView.text = all
        refreshHome()
    }
}
