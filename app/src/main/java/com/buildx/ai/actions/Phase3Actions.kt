package com.buildx.ai.actions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telephony.TelephonyManager
import com.buildx.ai.LogStore
import com.buildx.ai.a11y.BuildXAccessibilityService
import com.buildx.ai.a11y.ToggleLabels
import com.buildx.ai.contacts.PhoneNumbers
import com.buildx.ai.llm.AndroidLlm
import com.buildx.ai.nlu.Canonicalizer
import com.buildx.ai.nlu.FreeText
import com.buildx.ai.nlu.Reply
import com.buildx.ai.nlu.TextNormalizer
import com.buildx.ai.notif.BuildXNotificationListener
import com.buildx.ai.reminder.ReminderScheduler
import com.buildx.ai.system.BackgroundLauncher
import com.buildx.ai.system.NoteBook
import com.buildx.ai.system.Places
import com.buildx.ai.system.Rates
import com.buildx.ai.system.Reminders
import com.buildx.ai.system.SmsItem
import com.buildx.ai.system.Speakable
import com.buildx.ai.system.Units
import com.buildx.ai.system.Weather
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

private fun ActionContext.t(bnText: String, enText: String) = if (bn) bnText else enText

private fun prefs(ac: ActionContext) = ac.context.applicationContext.getSharedPreferences("buildx", Context.MODE_PRIVATE)

/** ask a question and hand the (non-blank) answer on; silence ends it */
private fun askText(ac: ActionContext, question: String, then: (String) -> ActionResult): ActionResult =
    ActionResult(true, question, FollowUp { reply ->
        if (reply.isNullOrBlank()) ActionResult(false, ac.t("উত্তর পাইনি", "No answer")) else then(reply.trim())
    })

private fun yesNo(ac: ActionContext, question: String, no: String, yes: () -> ActionResult): ActionResult =
    ActionResult(true, question, FollowUp { reply ->
        when (Reply.yesNo(reply)) {
            Reply.YesNo.YES -> yes()
            Reply.YesNo.NO -> ActionResult(true, no)
            Reply.YesNo.UNKNOWN -> ActionResult(false, if (reply.isNullOrBlank()) ac.t("উত্তর পাইনি", "No answer") else ac.t("বুঝতে পারিনি, কিছু করছি না", "Didn't get that, not doing it"))
        }
    })

private fun httpGet(url: String): String? {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 8000
    conn.readTimeout = 8000
    conn.setRequestProperty("User-Agent", "BuildX-AI/1.0")
    return try {
        if (conn.responseCode !in 200..299) null else conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

// ---------- units and money ----------

/** "১০ কিলোমিটার কত মাইল", "১০০ ডলার কত টাকা" */
object ConvertAction : Action {
    override val name = "convert"
    private const val RATES_KEY = "ratesJson"
    private const val RATES_AT = "ratesAt"
    private const val FRESH_MS = 6 * 3_600_000L

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val value = Units.parseValue(params["value"]) ?: return ActionResult(false, ac.t("সংখ্যাটা বুঝতে পারিনি", "I didn't get the number"))
        val from = params["from"].orEmpty()
        if (!Units.known(from)) return ActionResult(false, ac.t("এই একক চিনি না", "I don't know that unit"))
        val to = params["to"]?.takeIf { it.isNotBlank() } ?: Units.defaultTarget(from)
            ?: return ActionResult(false, ac.t("কীসে বদলাব বলুন", "Tell me what to convert it to"))
        if (!Units.known(to) || !Units.sameKind(from, to)) {
            return ActionResult(false, ac.t("এগুলো একটা থেকে আরেকটায় বদলানো যায় না", "Those can't be converted into each other"))
        }
        if (!Units.isMoney(from)) {
            val r = Units.convert(value, from, to) ?: return ActionResult(false, ac.t("বদলাতে পারিনি", "Couldn't convert"))
            return ActionResult(true, Units.say(value, from, r, to, ac.bn))
        }
        return money(ac, value, from, to)
    }

    private fun money(ac: ActionContext, value: Double, from: String, to: String): ActionResult {
        val p = prefs(ac)
        val cached = Rates.parse(p.getString(RATES_KEY, "") ?: "")
        val age = System.currentTimeMillis() - p.getLong(RATES_AT, 0L)
        if (cached != null && age in 0..FRESH_MS) return finish(ac, value, from, to, cached, stale = false)
        return ActionResult(true, "", deferred = Deferred { report ->
            AndroidLlm.runIo {
                val body = try { httpGet(Rates.URL) } catch (e: Exception) { LogStore.add(ac.context, "RATES failed: ${e.javaClass.simpleName}: ${e.message}"); null }
                val fresh = body?.let { Rates.parse(it) }
                if (fresh != null) p.edit().putString(RATES_KEY, body).putLong(RATES_AT, System.currentTimeMillis()).apply()
                AndroidLlm.runUi {
                    report(
                        when {
                            fresh != null -> finish(ac, value, from, to, fresh, stale = false)
                            cached != null -> finish(ac, value, from, to, cached, stale = true)
                            else -> ActionResult(false, ac.t("রেট আনতে পারিনি, ইন্টারনেট দেখুন", "Couldn't get the rate, check the internet"))
                        }
                    )
                }
            }
        })
    }

    private fun finish(ac: ActionContext, value: Double, from: String, to: String, rates: Map<String, Double>, stale: Boolean): ActionResult {
        val r = Rates.convert(rates, value, from, to) ?: return ActionResult(false, ac.t("এই মুদ্রার রেট পাইনি", "I have no rate for that currency"))
        val note = if (stale) ac.t(" (পুরনো রেট, ইন্টারনেট পাইনি)", " (old rate, no internet)") else ac.t(" (আনুমানিক)", " (approximate)")
        return ActionResult(true, Units.say(value, from, r, to, ac.bn) + note)
    }
}

// ---------- weather ----------

/** "ঢাকার আবহাওয়া কেমন", "কাল বৃষ্টি হবে" */
object WeatherAction : Action {
    override val name = "weather"
    private const val CITY_KEY = "weatherCity"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val tomorrow = params["when"] == "tomorrow"
        val said = params["city"].orEmpty().trim()
        if (said.isNotEmpty()) return go(ac, said, tomorrow, remember = prefs(ac).getString(CITY_KEY, "").isNullOrBlank())
        val saved = prefs(ac).getString(CITY_KEY, "").orEmpty()
        if (saved.isNotBlank()) return go(ac, saved, tomorrow, remember = false)
        return askText(ac, ac.t("কোন শহরের আবহাওয়া? এটাই মনে রাখব", "Which city? I'll remember it")) { go(ac, it, tomorrow, remember = true) }
    }

    private fun go(ac: ActionContext, spoken: String, tomorrow: Boolean, remember: Boolean): ActionResult {
        val place = Places.base(spoken)
        val query = Places.english(spoken)
        return ActionResult(true, "", deferred = Deferred { report ->
            AndroidLlm.runIo {
                val out: ActionResult = try {
                    val geo = httpGet(Weather.geoUrl(query))?.let { Weather.parseGeo(it) }
                    if (geo == null) {
                        ActionResult(false, ac.t("$place নামে জায়গা পাইনি", "I couldn't find a place called $place"))
                    } else {
                        val body = httpGet(Weather.forecastUrl(geo))
                        val said = body?.let { Weather.say(it, if (ac.bn) place else geo.name, tomorrow, ac.bn) }
                        if (said == null) ActionResult(false, ac.t("আবহাওয়ার তথ্য আনতে পারিনি", "Couldn't get the forecast"))
                        else {
                            if (remember) prefs(ac).edit().putString(CITY_KEY, place).apply()
                            ActionResult(true, said)
                        }
                    }
                } catch (e: Exception) {
                    LogStore.add(ac.context, "WEATHER failed: ${e.javaClass.simpleName}: ${e.message}")
                    ActionResult(false, ac.t("আবহাওয়া আনতে পারিনি, ইন্টারনেট দেখুন", "Couldn't get the weather, check the internet"))
                }
                AndroidLlm.runUi { report(out) }
            }
        })
    }
}

// ---------- notes ----------

/** "নোট করো দুধ কিনতে হবে", "নোটগুলো পড়ো", "নোট মুছে ফেলো" */
object NoteAction : Action {
    override val name = "note"
    private const val KEY = "notes"

    private fun load(ac: ActionContext) = NoteBook.decode(prefs(ac).getString(KEY, ""))
    private fun save(ac: ActionContext, notes: List<String>) = prefs(ac).edit().putString(KEY, NoteBook.encode(notes)).apply()

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult = when (params["op"]) {
        "read" -> ActionResult(true, NoteBook.say(load(ac), 5, ac.bn))
        "clear" -> {
            val n = load(ac).size
            if (n == 0) ActionResult(true, ac.t("কোনো নোট নেই", "You have no notes"))
            else yesNo(ac, ac.t("সব $n টা নোট মুছে ফেলব?", "Delete all $n notes?"), ac.t("ঠিক আছে, রেখে দিলাম", "Okay, keeping them")) {
                save(ac, emptyList())
                ActionResult(true, ac.t("সব নোট মুছে ফেলেছি", "All notes deleted"))
            }
        }
        else -> {
            val text = params["text"].orEmpty().trim()
            if (text.isEmpty()) askText(ac, ac.t("কী নোট করব?", "What should I note?")) { add(ac, it) } else add(ac, text)
        }
    }

    private fun add(ac: ActionContext, text: String): ActionResult {
        save(ac, NoteBook.add(load(ac), text))
        return ActionResult(true, ac.t("নোট করেছি: $text", "Noted: $text"))
    }
}

// ---------- reminders ----------

/** "কাল সকাল ৯টায় মিটিংয়ের কথা মনে করিয়ে দাও", "১০ মিনিট পরে মনে করিয়ে দাও" */
object RemindAction : Action {
    override val name = "remind"

    private val timeKeys = listOf("hour", "minute", "ambiguous", "in_minutes", "when")

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val text = params["text"].orEmpty().trim()
        val slots = params.filterKeys { it in timeKeys }
        if (slots["hour"] == null && slots["in_minutes"] == null) return askTime(ac, text, retry = true)
        return needText(ac, text, slots)
    }

    private fun askTime(ac: ActionContext, text: String, retry: Boolean): ActionResult =
        askText(ac, ac.t("কখন মনে করাব?", "When should I remind you?")) { reply ->
            val s = FreeText.timeSlots(Canonicalizer.tokenize(TextNormalizer.normalize(reply)))
            when {
                s["hour"] != null || s["in_minutes"] != null -> needText(ac, text, s)
                retry -> askTime(ac, text, retry = false)
                else -> ActionResult(false, ac.t("সময় বুঝতে পারিনি, রিমাইন্ডার দিইনি", "Didn't get the time, no reminder set"))
            }
        }

    private fun needText(ac: ActionContext, text: String, slots: Map<String, String>): ActionResult {
        if (text.isNotEmpty()) return set(ac, text, slots)
        return askText(ac, ac.t("কী মনে করাব?", "What should I remind you about?")) { set(ac, it, slots) }
    }

    private fun set(ac: ActionContext, text: String, slots: Map<String, String>): ActionResult {
        val now = Calendar.getInstance()
        val at = Reminders.triggerAt(now, slots)
            ?: return ActionResult(false, ac.t("সময়টা ঠিক হয়নি বা পার হয়ে গেছে", "That time isn't valid or has already passed"))
        val r = ReminderScheduler.add(ac.context, at, text)
            ?: return ActionResult(false, ac.t("রিমাইন্ডার বসাতে পারিনি", "Couldn't set the reminder"))
        val ctx = ac.context
        val noPerm = Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val warn = if (noPerm) ac.t("। কিন্তু নোটিফিকেশনের পারমিশন নেই, অ্যাপ খুলে দিন", ". But notifications are not allowed, open the app and allow them") else ""
        LogStore.add(ctx, "REMIND #${r.id} \"$text\"")
        return ActionResult(true, ac.t("ঠিক আছে, ${Reminders.sayWhen(at, now, true)} মনে করিয়ে দেব: $text$warn", "Okay, I'll remind you ${Reminders.sayWhen(at, now, false)}: $text$warn"))
    }
}

// ---------- calendar ----------

/** "ক্যালেন্ডারে মিটিং যোগ করো কাল বিকাল ৪টায়": opens the calendar's new-event screen filled in. */
object CalendarEventAction : Action {
    override val name = "calendar_event"

    private val timeKeys = listOf("hour", "minute", "ambiguous", "when")

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val title = params["title"].orEmpty().trim()
        val slots = params.filterKeys { it in timeKeys }
        if (title.isEmpty()) return askText(ac, ac.t("ইভেন্টের নাম কী?", "What is the event called?")) { withTitle(ac, it, slots) }
        return withTitle(ac, title, slots)
    }

    private fun withTitle(ac: ActionContext, title: String, slots: Map<String, String>): ActionResult {
        if (slots["hour"] != null) return open(ac, title, slots)
        return askText(ac, ac.t("কখন?", "At what time?")) { reply ->
            val s = FreeText.timeSlots(Canonicalizer.tokenize(TextNormalizer.normalize(reply)))
            if (s["hour"] != null) open(ac, title, s) else ActionResult(false, ac.t("সময় বুঝতে পারিনি, ইভেন্ট বানাইনি", "Didn't get the time, no event created"))
        }
    }

    private fun open(ac: ActionContext, title: String, slots: Map<String, String>): ActionResult {
        val ctx = ac.context.applicationContext
        val now = Calendar.getInstance()
        val begin = Reminders.triggerAt(now, slots + ("hour" to (slots["hour"] ?: "9")))
            ?: return ActionResult(false, ac.t("সময়টা ঠিক হয়নি বা পার হয়ে গেছে", "That time isn't valid or has already passed"))
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 3_600_000L)
        val ok = BackgroundLauncher.start(ctx, intent)
        LogStore.add(ctx, "CALENDAR \"$title\" at=$begin ok=$ok")
        if (!ok) return ActionResult(false, ac.t("ক্যালেন্ডার খোলা যায়নি", "Couldn't open the calendar"))
        val when_ = Reminders.sayWhen(begin, now, ac.bn)
        val svc = BuildXAccessibilityService.instance
        if (svc == null) {
            return ActionResult(true, ac.t("ক্যালেন্ডারে ‘$title’, $when_ লিখে খুলেছি। সেভ চাপুন", "I opened the calendar with “$title”, $when_. Tap Save"))
        }
        return ActionResult(true, "", deferred = Deferred { report ->
            ScreenWait.tapButton(ctx, svc, listOf("save", "সেভ", "সংরক্ষণ", "সংরক্ষণ করুন"), tries = 8, gapMs = 800, expectPkg = null) { tapped ->
                report(
                    if (tapped) ActionResult(true, ac.t("ক্যালেন্ডারে ‘$title’, $when_ সেভ করার বোতামে চেপেছি। ক্যালেন্ডারে একবার দেখে নিন", "Tapped Save for “$title”, $when_. Please check the calendar"))
                    else ActionResult(true, ac.t("ক্যালেন্ডারে ‘$title’, $when_ লিখে খুলেছি। সেভ বোতামটা পাইনি, নিজে চাপুন", "I opened the calendar with “$title”, $when_. I couldn't find Save, please tap it"))
                )
            }
        })
    }
}

// ---------- do not disturb ----------

/** "ডু নট ডিস্টার্ব চালু করো" */
object DndAction : Action {
    override val name = "dnd"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val ctx = ac.context.applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            return ActionResult(false, ac.t("এটা করতে \"Do Not Disturb অ্যাক্সেস\" দিতে হবে। অ্যাপ খুলে সেই বাটন চাপুন", "This needs \"Do Not Disturb access\". Open the app and allow it"))
        }
        val isOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val want = when (params["state"]) {
            "on" -> true
            "off" -> false
            else -> !isOn
        }
        nm.setInterruptionFilter(if (want) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        val nowOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        LogStore.add(ctx, "DND want=$want now=$nowOn")
        if (nowOn != want) return ActionResult(false, ac.t("ডু নট ডিস্টার্ব বদলায়নি", "Do Not Disturb didn't change"))
        return ActionResult(true, if (want) ac.t("ডু নট ডিস্টার্ব চালু করেছি", "Do Not Disturb is on") else ac.t("ডু নট ডিস্টার্ব বন্ধ করেছি", "Do Not Disturb is off"))
    }
}

// ---------- airplane mode, mobile data, hotspot ----------
// Android does not let apps flip these. Same approach as Wi-Fi: open the right settings screen, and with Accessibility
// tap the switch and check the phone's own state. The hotspot state cannot be read, so that one only opens the screen.

object AirplaneAction : Action {
    override val name = "airplane"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val ctx = ac.context.applicationContext
        return panel(
            ac, "এয়ারপ্লেন মোড", "Airplane mode", ToggleLabels.Kind.AIRPLANE,
            { try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 } catch (e: Exception) { null } },
            params["state"], Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        )
    }
}

object MobileDataAction : Action {
    override val name = "mobile_data"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val tm = ac.context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return panel(
            ac, "মোবাইল ডেটা", "Mobile data", ToggleLabels.Kind.MOBILE_DATA,
            { try { tm.isDataEnabled } catch (e: Exception) { null } },
            params["state"], Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), Intent("android.settings.DATA_USAGE_SETTINGS")
        )
    }
}

object HotspotAction : Action {
    override val name = "hotspot"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val ctx = ac.context.applicationContext
        val tries = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent().setClassName("com.samsung.android.settings", "com.samsung.android.settings.wifi.mobileap.WifiApSettings"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        val ok = tries.any { BackgroundLauncher.start(ctx, it) }
        LogStore.add(ctx, "HOTSPOT settings state=${params["state"]} ok=$ok")
        if (!ok) return ActionResult(false, ac.t("হটস্পটের সেটিংস খোলা যায়নি", "Couldn't open the hotspot settings"))
        val doing = when (params["state"]) {
            "on" -> ac.t("চালু করতে", "to turn it on")
            "off" -> ac.t("বন্ধ করতে", "to turn it off")
            else -> ac.t("বদলাতে", "to change it")
        }
        return ActionResult(true, ac.t("হটস্পটের সেটিংস খুলেছি, $doing নিজে টগলে ট্যাপ করুন। এটা আমি নিজে করতে পারি না", "I opened the hotspot settings, tap the toggle $doing yourself. I can't do that one"))
    }
}

// ---------- reading things aloud ----------

private fun noListener(ac: ActionContext) = ActionResult(
    false,
    ac.t("এটা করতে \"নোটিফিকেশন অ্যাক্সেস\" দিতে হবে। অ্যাপ খুলে সেই বাটন চাপুন", "This needs \"Notification access\". Open the app and allow it")
)

/** "নোটিফিকেশন পড়ে শোনাও" */
object ReadNotificationsAction : Action {
    override val name = "read_notifications"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val l = BuildXNotificationListener.instance ?: return noListener(ac)
        return ActionResult(true, Speakable.notifications(l.readable(), 5, 120, ac.bn))
    }
}

/** "কে কল করছে" */
object WhoCallingAction : Action {
    override val name = "who_calling"

    @Suppress("DEPRECATION")
    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val ctx = ac.context.applicationContext
        val ringing = try {
            ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                (ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).callState == TelephonyManager.CALL_STATE_RINGING
        } catch (e: Exception) {
            false
        }
        val l = BuildXNotificationListener.instance
        if (l == null) {
            return if (ringing) noListener(ac) else ActionResult(true, Speakable.caller(null, false, ac.bn))
        }
        return ActionResult(true, Speakable.caller(l.ringingCaller(), ringing, ac.bn))
    }
}

/** "নতুন মেসেজ পড়ো": the unread SMS in the inbox. Reads them; does not mark them read. */
object ReadSmsAction : Action {
    override val name = "read_sms"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val ctx = ac.context.applicationContext
        if (ctx.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return ActionResult(false, ac.t("এসএমএস পড়ার পারমিশন নেই। অ্যাপ খুলে পারমিশন দিন", "No permission to read SMS. Open the app and allow it"))
        }
        val items = ArrayList<SmsItem>()
        try {
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.READ} = 0", null, "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext() && items.size < 20) {
                    val addr = c.getString(0).orEmpty()
                    items.add(SmsItem(nameOf(ctx, addr, ac.bn), c.getString(1).orEmpty(), c.getLong(2)))
                }
            }
        } catch (e: Exception) {
            LogStore.add(ctx, "SMS read failed: ${e.javaClass.simpleName}: ${e.message}")
            return ActionResult(false, ac.t("মেসেজ পড়তে পারিনি", "Couldn't read the messages"))
        }
        return ActionResult(true, Speakable.sms(items, 3, 200, ac.bn))
    }

    /** the contact's name for [number], or the number spelled out */
    private fun nameOf(ctx: Context, number: String, bn: Boolean): String {
        if (number.isBlank()) return if (bn) "অজানা" else "unknown"
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try {
                ctx.contentResolver.query(
                    Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it } }
            } catch (_: Exception) {
            }
        }
        return if (number.any { it.isDigit() }) PhoneNumbers.spell(number.filter { it.isDigit() || it == '+' }, bn) else number
    }
}
