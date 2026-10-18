package com.buildx.ai.actions

import android.app.AlarmManager
import android.app.KeyguardManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.buildx.ai.LogStore
import com.buildx.ai.a11y.BuildXAccessibilityService
import com.buildx.ai.a11y.ToggleLabels
import com.buildx.ai.a11y.ToggleTapper
import com.buildx.ai.nlu.IntentParser
import com.buildx.ai.system.AlarmMath
import com.buildx.ai.system.AlarmWords
import com.buildx.ai.system.AppCatalog
import com.buildx.ai.system.BackgroundLauncher
import com.buildx.ai.system.Launchable
import com.buildx.ai.system.MainScheduler
import com.buildx.ai.system.Verdict
import com.buildx.ai.system.Verify
import com.buildx.ai.system.TorchController
import com.buildx.ai.system.VolumeMath
import java.util.Calendar

private fun ActionContext.t(bnText: String, enText: String) = if (bn) bnText else enText

private fun ActionContext.digits(n: Int): String =
    if (bn) n.toString().map { '০' + (it - '0') }.joinToString("") else n.toString()

// ---------- flashlight ----------

object FlashlightAction : Action {
    override val name = "flashlight"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val ctx = ac.context
        val want = when (params["state"]) {
            "on" -> true
            "off" -> false
            else -> TorchController.current(ctx)?.not() ?: true // toggle
        }
        val err = TorchController.set(ctx, want)
        LogStore.add(ctx, "TORCH want=$want err=${err ?: "-"}")
        val onMsg = ac.t("ফ্ল্যাশলাইট চালু করেছি", "Flashlight on")
        val offMsg = ac.t("ফ্ল্যাশলাইট বন্ধ করেছি", "Flashlight off")
        return when (err) {
            null -> ActionResult(true, "", deferred = Deferred { report ->
                // ask the camera service what the torch is really doing
                Verify.poll(MainScheduler, 150, 8, {
                    when (TorchController.isOn) {
                        null -> Verdict.UNKNOWN
                        want -> Verdict.YES
                        else -> Verdict.NO
                    }
                }) { v ->
                    LogStore.add(ctx, "TORCH verify want=$want -> $v")
                    report(
                        when (v) {
                            Verdict.YES -> ActionResult(true, if (want) onMsg else offMsg)
                            Verdict.UNKNOWN -> ActionResult(true, (if (want) onMsg else offMsg) + ac.t(" (যাচাই করতে পারিনি)", " (couldn't verify)"))
                            Verdict.NO -> ActionResult(false, if (want) ac.t("ফ্ল্যাশলাইট চালু হয়নি", "The flashlight did not turn on") else ac.t("ফ্ল্যাশলাইট বন্ধ হয়নি", "The flashlight did not turn off"))
                        }
                    )
                }
            })
            "no_flash" -> ActionResult(false, ac.t("এই ফোনে ফ্ল্যাশ পাইনি", "This phone has no flash"))
            "in_use" -> ActionResult(false, ac.t("ক্যামেরা অন্য অ্যাপ ব্যবহার করছে, ফ্ল্যাশলাইট জ্বালানো গেল না", "The camera is in use by another app"))
            else -> ActionResult(false, ac.t("ফ্ল্যাশলাইট চালানো যায়নি ($err)", "Couldn't switch the flashlight ($err)"))
        }
    }
}

// ---------- volume (media) ----------

object VolumeAction : Action {
    override val name = "volume"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val ctx = ac.context.applicationContext
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        val cur = audio.getStreamVolume(stream)
        val prefs = ctx.getSharedPreferences("buildx", Context.MODE_PRIVATE)
        val action = params["action"] ?: "none"
        val level = params["level"]?.toIntOrNull()
        val delta = params["delta"]?.toIntOrNull()

        if (action == "none") {
            return ActionResult(true, ac.t("ভলিউম এখন ${ac.digits(VolumeMath.indexToPercent(cur, max))} শতাংশ। কত করব?", "Volume is ${VolumeMath.indexToPercent(cur, max)} percent. To what?"),
                FollowUp { reply ->
                    if (reply.isNullOrBlank()) {
                        ActionResult(false, ac.t("উত্তর পাইনি", "No answer"))
                    } else {
                        val p = IntentParser.parse("ভলিউম $reply")
                        if (p.intent == "volume" && p.slots["action"] != "none") execute(ac, p.slots)
                        else ActionResult(false, ac.t("বুঝতে পারিনি", "Didn't get that"))
                    }
                })
        }

        val newIdx = when (action) {
            "mute" -> {
                if (cur > 0) prefs.edit().putInt("volBeforeMute", cur).apply()
                0
            }
            "unmute" -> VolumeMath.restore(prefs.getInt("volBeforeMute", 0), max)
            else -> {
                if ((action == "up" && cur >= max) ) {
                    return ActionResult(true, ac.t("ভলিউম আগেই সর্বোচ্চ", "Volume is already at maximum"))
                }
                if (action == "down" && cur <= 0) {
                    return ActionResult(true, ac.t("ভলিউম আগেই শূন্য", "Volume is already at zero"))
                }
                VolumeMath.target(cur, max, action, level, delta)
            }
        }
        return try {
            audio.setStreamVolume(stream, newIdx, AudioManager.FLAG_SHOW_UI)
            val actual = audio.getStreamVolume(stream) // read it back: some phones refuse (e.g. Bluetooth absolute volume)
            val pct = VolumeMath.indexToPercent(actual, max)
            LogStore.add(ctx, "VOLUME $cur -> want $newIdx got $actual (max $max)")
            if (actual != newIdx) {
                ActionResult(false, ac.t("ভলিউম এখন ${ac.digits(pct)} শতাংশ, কিন্তু চাওয়া মান বসেনি", "Volume is $pct percent, but the change didn't fully apply"))
            } else if (action == "mute" || actual == 0) {
                ActionResult(true, ac.t("ভলিউম মিউট করেছি", "Muted"))
            } else {
                ActionResult(true, ac.t("ভলিউম এখন ${ac.digits(pct)} শতাংশ", "Volume is $pct percent"))
            }
        } catch (e: SecurityException) {
            ActionResult(false, ac.t("ভলিউম বদলানো যায়নি (ডু-নট-ডিসটার্ব বাধা দিচ্ছে?)", "Couldn't change volume (Do Not Disturb?)"))
        }
    }
}

// ---------- open an app ----------

object OpenAppAction : Action {
    override val name = "open_app"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val app = params["app"].orEmpty()
        if (app.isBlank()) {
            return ActionResult(true, ac.t("কোন অ্যাপ খুলব?", "Which app?"), FollowUp { reply ->
                if (reply.isNullOrBlank()) ActionResult(false, ac.t("উত্তর পাইনি", "No answer"))
                else {
                    val p = IntentParser.parse("open $reply")
                    execute(ac, if (p.intent == "open_app") p.slots else mapOf("app" to reply.trim(), "known" to "false"))
                }
            })
        }
        val ctx = ac.context.applicationContext
        if (params["known"] == "true") {
            val spec = AppCatalog.spec(app)
            val intent = spec?.let { intentFor(ctx, it) }
            if (spec != null && intent != null) return launch(ac, ctx, intent, AppCatalog.name(app, ac.bn), intent.resolveActivity(ctx.packageManager)?.packageName)
        }

        val apps = launchables(ctx)
        val query = if (params["known"] == "true") AppCatalog.name(app, false) else app
        val found = AppCatalog.findByLabel(query, apps)
        return when {
            found.isEmpty() -> ActionResult(false, ac.t("$app নামে অ্যাপ পাইনি", "I couldn't find an app called $app"))
            found.size == 1 -> launchPackage(ac, ctx, found[0])
            else -> askWhich(ac, ctx, app, found)
        }
    }

    private fun askWhich(ac: ActionContext, ctx: Context, app: String, options: List<Launchable>): ActionResult {
        val list = options.mapIndexed { i, o -> "${ac.digits(i + 1)}. ${o.label}" }.joinToString("। ")
        return ActionResult(true, ac.t("$app নামে ${ac.digits(options.size)}টি অ্যাপ আছে। $list। কোনটা?", "Several apps match $app: $list. Which one?"),
            FollowUp { reply ->
                val i = com.buildx.ai.nlu.Reply.pick(reply, options.size)
                if (i == null) ActionResult(false, ac.t("বুঝতে পারিনি, খুলছি না", "Didn't get that, not opening"))
                else launchPackage(ac, ctx, options[i])
            })
    }

    private fun launchPackage(ac: ActionContext, ctx: Context, app: Launchable): ActionResult {
        val intent = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
            ?: return ActionResult(false, ac.t("${app.label} খোলা যায়নি", "Couldn't open ${app.label}"))
        return launch(ac, ctx, intent, app.label, app.pkg)
    }

    /** start it, then check with the Accessibility service which app is really in front (if that service is on) */
    private fun launch(ac: ActionContext, ctx: Context, intent: Intent, label: String, expectedPkg: String?): ActionResult {
        val ok = BackgroundLauncher.start(ctx, intent)
        LogStore.add(ctx, "OPEN $label ok=$ok overlay=${BackgroundLauncher.canOverlay(ctx)}")
        if (!ok) return ActionResult(false, ac.t("$label খোলা যায়নি", "Couldn't open $label"))

        val svc = BuildXAccessibilityService.instance
        var lastPkg: String? = null
        return ActionResult(true, "", deferred = Deferred { report ->
            Verify.poll(MainScheduler, 500, 6, {
                if (svc == null || expectedPkg == null || expectedPkg == "android") {
                    Verdict.UNKNOWN
                } else {
                    lastPkg = svc.rootInActiveWindow?.packageName?.toString()
                    if (lastPkg == expectedPkg) Verdict.YES else Verdict.NO
                }
            }) { v ->
                val locked = (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
                LogStore.add(ctx, "OPEN verify $label expected=$expectedPkg front=$lastPkg locked=$locked -> $v")
                report(
                    when {
                        v == Verdict.YES -> ActionResult(true, ac.t("$label খুলেছি", "$label is open"))
                        v == Verdict.UNKNOWN -> ActionResult(true, ac.t("$label খোলার অনুরোধ পাঠিয়েছি (যাচাই করতে পারিনি)", "I asked to open $label (couldn't verify)"))
                        locked -> ActionResult(true, ac.t("$label খোলার অনুরোধ পাঠিয়েছি, কিন্তু ফোন লক করা। আনলক করলে দেখবেন", "I asked to open $label, but the phone is locked. Unlock to see it"))
                        BackgroundLauncher.canOverlay(ctx) -> ActionResult(false, ac.t("$label সামনে আসেনি", "$label did not come to the front"))
                        else -> ActionResult(false, ac.t("$label সামনে আসেনি। ব্যাকগ্রাউন্ড থেকে খোলা আটকে থাকতে পারে, অ্যাপে \"অন্য অ্যাপের উপর দেখানো\" পারমিশন দিন", "$label did not come to the front. Background starts may be blocked; allow \"display over other apps\""))
                    }
                )
            }
        })
    }

    /** a launch intent for a known app: installed package first, then the system's default for that kind */
    private fun intentFor(ctx: Context, spec: AppCatalog.Spec): Intent? {
        val pm = ctx.packageManager
        for (p in spec.packages) pm.getLaunchIntentForPackage(p)?.let { return it }
        return when (spec.kind) {
            "dialer" -> Intent(Intent.ACTION_DIAL)
            "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "clock" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            "gallery" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
            "calculator" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
            "calendar" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
            "contacts" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS)
            "messages" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)
            "maps" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS)
            "email" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL)
            "browser" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
            else -> null
        }
    }

    private fun launchables(ctx: Context): List<Launchable> {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0).map {
            Launchable(it.loadLabel(pm).toString(), it.activityInfo.packageName)
        }.distinctBy { it.pkg }
    }
}

// ---------- alarm / timer ----------

object AlarmAction : Action {
    override val name = "alarm"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val ctx = ac.context.applicationContext
        params["in_minutes"]?.toIntOrNull()?.let { return timer(ac, ctx, it) }
        val hour = params["hour"]?.toIntOrNull()
        if (hour == null) {
            return ActionResult(true, ac.t("কখনের জন্য অ্যালার্ম দেব?", "For what time?"), FollowUp { reply ->
                if (reply.isNullOrBlank()) {
                    ActionResult(false, ac.t("উত্তর পাইনি, অ্যালার্ম দিচ্ছি না", "No answer, no alarm set"))
                } else {
                    val p = IntentParser.parse("অ্যালার্ম $reply")
                    if (p.intent == "alarm" && (p.slots["hour"] != null || p.slots["in_minutes"] != null)) execute(ac, p.slots)
                    else ActionResult(false, ac.t("সময়টা বুঝতে পারিনি, অ্যালার্ম দিচ্ছি না", "Didn't get the time, no alarm set"))
                }
            })
        }
        val minute = params["minute"]?.toIntOrNull() ?: 0
        val now = Calendar.getInstance()
        val plan = AlarmMath.plan(
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), hour, minute,
            params["ambiguous"] == "true", params["when"] ?: "auto"
        )
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, plan.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, plan.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "BuildX")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val expectedAt = System.currentTimeMillis() + plan.minutesUntil * 60_000L
        val ok = BackgroundLauncher.start(ctx, intent, wake = false)
        LogStore.add(ctx, "ALARM ${plan.hour}:${plan.minute} until=${plan.minutesUntil}min today=${plan.firesToday} mismatch=${plan.mismatch} ok=$ok")
        if (!ok) return ActionResult(false, ac.t("অ্যালার্ম দেওয়া যায়নি", "Couldn't set the alarm"))

        val day = if (plan.firesToday) ac.t("আজ ", "today ") else ac.t("আগামীকাল ", "tomorrow ")
        var msg = ac.t(
            "$day${AlarmWords.clock(plan.hour, plan.minute, true)}-এ অ্যালার্ম দিয়েছি, ${AlarmWords.duration(plan.minutesUntil, true)}",
            "Alarm set for $day${AlarmWords.clock(plan.hour, plan.minute, false)}, ${AlarmWords.duration(plan.minutesUntil, false)}"
        )
        if (plan.mismatch) {
            msg += when (params["when"]) {
                "tomorrow" -> ac.t("। সতর্কতা: এটা আজকেই বাজবে, কালকের জন্য নয়", ". Note: this rings today, not tomorrow")
                "day_after" -> ac.t("। সতর্কতা: পরশুর তারিখ ঠিক করা যায় না, পরের ঐ সময়ে বাজবে", ". Note: I can't pick the day after tomorrow; it rings at the next such time")
                else -> ac.t("। সতর্কতা: সময় পেরিয়ে গেছে, তাই কাল বাজবে", ". Note: that time has passed, so it rings tomorrow")
            }
        }
        val final = msg
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return ActionResult(true, "", deferred = Deferred { report ->
            // the system keeps one "next alarm"; if it is ours it will be at the expected minute
            Verify.poll(MainScheduler, 400, 8, {
                val next = am.nextAlarmClock?.triggerTime
                when {
                    next == null -> Verdict.NO
                    Math.abs(next - expectedAt) <= 90_000L -> Verdict.YES
                    next < expectedAt -> Verdict.UNKNOWN // an earlier alarm hides ours
                    else -> Verdict.NO
                }
            }) { v ->
                LogStore.add(ctx, "ALARM verify -> $v")
                report(
                    when (v) {
                        Verdict.YES -> ActionResult(true, final)
                        Verdict.UNKNOWN -> ActionResult(true, final + ac.t(" (আগের আরেকটা অ্যালার্ম থাকায় যাচাই করতে পারিনি)", " (an earlier alarm exists, couldn't verify)"))
                        Verdict.NO -> ActionResult(false, ac.t("অ্যালার্মের অনুরোধ পাঠিয়েছি, কিন্তু সিস্টেমে বসেছে দেখতে পাচ্ছি না। ক্লক অ্যাপে দেখে নিন", "I sent the alarm request but can't see it in the system. Please check the Clock app"))
                    }
                )
            }
        })
    }

    private fun timer(ac: ActionContext, ctx: Context, minutes: Int): ActionResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "BuildX")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val ok = BackgroundLauncher.start(ctx, intent, wake = false)
        LogStore.add(ctx, "TIMER ${minutes}min ok=$ok")
        return if (ok) ActionResult(true, ac.t("${AlarmWords.duration(minutes, true)} বাজবে, টাইমারের অনুরোধ পাঠিয়েছি (যাচাই করা যায় না)", "Timer requested, ${AlarmWords.duration(minutes, false)} (can't verify)"))
        else ActionResult(false, ac.t("টাইমার দেওয়া যায়নি", "Couldn't set the timer"))
    }
}

// ---------- Wi-Fi / Bluetooth ----------
// Android 10+ does not let apps switch them. Without the Accessibility service we open the panel and say so;
// with it (and "auto toggle" on) we also tap the switch and confirm with the phone's own state.

private fun panel(
    ac: ActionContext,
    thingBn: String,
    thingEn: String,
    kind: ToggleLabels.Kind,
    isOn: () -> Boolean?,
    state: String?,
    intent: Intent
): ActionResult {
    val ctx = ac.context.applicationContext
    val enabled = isOn()
    val wantOn = state == "on"
    val wantOff = state == "off"
    if (enabled != null && ((wantOn && enabled) || (wantOff && !enabled))) {
        return ActionResult(true, if (enabled) ac.t("$thingBn আগেই চালু আছে", "$thingEn is already on") else ac.t("$thingBn আগেই বন্ধ আছে", "$thingEn is already off"))
    }
    val ok = BackgroundLauncher.start(ctx, intent)
    LogStore.add(ctx, "PANEL $thingEn state=$state now=$enabled ok=$ok")
    if (!ok) return ActionResult(false, ac.t("$thingBn সেটিংস খোলা যায়নি", "Couldn't open $thingEn settings"))

    val svc = BuildXAccessibilityService.instance
    val auto = ctx.getSharedPreferences("buildx", Context.MODE_PRIVATE).getBoolean("autoToggle", true)
    if ((wantOn || wantOff) && svc != null && auto) {
        return ActionResult(true, "", deferred = Deferred { report ->
            ToggleTapper.run(svc, kind, wantOn, isOn) { out ->
                report(
                    when (out) {
                        ToggleTapper.Outcome.VERIFIED, ToggleTapper.Outcome.ALREADY ->
                            ActionResult(true, if (wantOn) ac.t("$thingBn চালু করেছি", "$thingEn is on") else ac.t("$thingBn বন্ধ করেছি", "$thingEn is off"))
                        ToggleTapper.Outcome.UNVERIFIED ->
                            ActionResult(false, ac.t("$thingBn-এর টগলে ট্যাপ করেছি, কিন্তু বদলেছে কি না নিশ্চিত নই", "I tapped the $thingEn switch, but I'm not sure it changed"))
                        ToggleTapper.Outcome.NOT_FOUND ->
                            ActionResult(false, ac.t("$thingBn সেটিংস খুলেছি, কিন্তু টগলটা খুঁজে পাইনি। নিজে টগল করুন", "I opened $thingEn settings but couldn't find the switch. Please toggle it yourself"))
                    }
                )
            }
        })
    }

    val doing = when {
        wantOn -> ac.t("চালু করতে", "to turn it on")
        wantOff -> ac.t("বন্ধ করতে", "to turn it off")
        else -> ac.t("বদলাতে", "to change it")
    }
    val nowText = when (enabled) {
        true -> ac.t("এখন চালু। ", "It is on now. ")
        false -> ac.t("এখন বন্ধ। ", "It is off now. ")
        null -> ""
    }
    return ActionResult(true, ac.t("$thingBn: $nowText" + "সেটিংস খুলেছি, $doing টগলে ট্যাপ করুন", "$thingEn: $nowText" + "I opened the settings, tap the toggle $doing"))
}

object WifiAction : Action {
    override val name = "wifi"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val wm = ac.context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return panel(ac, "ওয়াইফাই", "Wi-Fi", ToggleLabels.Kind.WIFI,
            { try { wm.isWifiEnabled } catch (e: Exception) { null } },
            params["state"], Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
    }
}

object BluetoothAction : Action {
    override val name = "bluetooth"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val bm = ac.context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return panel(ac, "ব্লুটুথ", "Bluetooth", ToggleLabels.Kind.BLUETOOTH,
            { try { bm.adapter?.isEnabled } catch (e: Exception) { null } },
            params["state"], Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }
}
