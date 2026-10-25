package com.buildx.ai.actions

import android.app.NotificationManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import com.buildx.ai.LogStore
import com.buildx.ai.a11y.BuildXAccessibilityService
import com.buildx.ai.nlu.IntentParser
import com.buildx.ai.system.BackgroundLauncher
import com.buildx.ai.system.Calc
import com.buildx.ai.system.DateWords
import com.buildx.ai.system.MainScheduler
import com.buildx.ai.system.Verdict
import com.buildx.ai.system.Verify
import com.buildx.ai.system.VolumeMath
import com.buildx.ai.system.YoutubeResults
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

private fun ActionContext.t(bnText: String, enText: String) = if (bn) bnText else enText

private fun ActionContext.d(n: Int): String = if (bn) n.toString().map { '০' + (it - '0') }.joinToString("") else n.toString()

private val main = Handler(Looper.getMainLooper())

/** ask for the missing piece ("কোন গান?"), then continue with the answer */
private fun askFor(ac: ActionContext, question: String, then: (String) -> ActionResult): ActionResult =
    ActionResult(true, question, FollowUp { reply ->
        val text = if (reply.isNullOrBlank()) "" else IntentParser.queryOf(reply).ifBlank { reply.trim() }
        if (text.isBlank()) ActionResult(false, ac.t("উত্তর পাইনি", "No answer")) else then(text)
    })

// ---------- music / video keys ----------

/** play, pause, next, previous: sent as media keys to whatever app is playing (YouTube, Spotify, ...) */
object MediaAction : Action {
    override val name = "media"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val ctx = ac.context.applicationContext
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val action = params["action"] ?: "play"
        if (action == "pause" && !audio.isMusicActive) {
            return ActionResult(true, ac.t("এখন কিছু চলছে না", "Nothing is playing"))
        }
        val code = when (action) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        LogStore.add(ctx, "MEDIA $action sent")
        return when (action) {
            "next" -> ActionResult(true, ac.t("পরের গান", "Next"))
            "prev" -> ActionResult(true, ac.t("আগের গান", "Previous"))
            else -> ActionResult(true, "", deferred = Deferred { report ->
                val wantPlaying = action == "play"
                Verify.poll(MainScheduler, 300, 6, { if (audio.isMusicActive == wantPlaying) Verdict.YES else Verdict.NO }) { v ->
                    report(
                        if (v == Verdict.YES) ActionResult(true, if (wantPlaying) ac.t("চালু করেছি", "Playing") else ac.t("থামিয়েছি", "Paused"))
                        else ActionResult(false, if (wantPlaying) ac.t("চালাতে পারিনি, কোনো মিউজিক বা ভিডিও অ্যাপ খোলা আছে কি?", "Couldn't resume, is a music or video app open?") else ac.t("থামাতে পারিনি", "Couldn't pause"))
                    )
                }
            })
        }
    }
}

// ---------- reels / shorts / page scroll ----------

/** "পরের রিল", "স্ক্রল করো": one swipe, done by the Accessibility service */
object SwipeAction : Action {
    override val name = "swipe"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val svc = BuildXAccessibilityService.instance
            ?: return ActionResult(false, ac.t("স্ক্রল করতে Accessibility চালু করতে হবে। অ্যাপ খুলে গাইড দেখুন", "Scrolling needs the Accessibility service. Open the app for the guide"))
        val next = params["dir"] != "prev"
        return ActionResult(true, "", deferred = Deferred { report ->
            svc.swipe(next) { ok ->
                main.post {
                    report(
                        if (ok) ActionResult(true, if (next) ac.t("পরেরটায় গেছি", "Next") else ac.t("আগেরটায় গেছি", "Back"))
                        else ActionResult(false, ac.t("স্ক্রল করতে পারিনি। Accessibility একবার বন্ধ করে আবার চালু করুন (নতুন অনুমতির জন্য)", "Couldn't scroll. Switch Accessibility off and on once (for the new permission)"))
                    )
                }
            }
        })
    }
}

// ---------- YouTube ----------

/** "ইউটিউবে আরিজিত সিংয়ের গান চালাও": finds the first video for the words and opens it in the YouTube app */
object YoutubePlayAction : Action {
    override val name = "youtube_play"
    private const val YT = "com.google.android.youtube"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val q = params["query"].orEmpty().trim()
        if (q.isEmpty()) return askFor(ac, ac.t("কোন গান বাজাব?", "Which song?")) { play(ac, it) }
        return play(ac, q)
    }

    private fun play(ac: ActionContext, q: String): ActionResult {
        val ctx = ac.context.applicationContext
        return ActionResult(true, ac.t("ইউটিউবে ‘$q’ খুঁজছি", "Looking for “$q” on YouTube"), deferred = Deferred { report ->
            Thread {
                val id = try { firstVideo(q) } catch (e: Exception) { LogStore.add(ctx, "YOUTUBE search failed: ${e.javaClass.simpleName}: ${e.message}"); null }
                main.post { open(ac, ctx, q, id, report) }
            }.start()
        })
    }

    private fun firstVideo(q: String): String? {
        val conn = URL(YoutubeResults.searchUrl(q)).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36")
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        conn.setRequestProperty("Cookie", "CONSENT=YES+1")
        return try {
            YoutubeResults.firstVideoId(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun start(ctx: Context, uri: Uri, action: String): Boolean {
        val withApp = Intent(action, uri).setPackage(YT)
        return BackgroundLauncher.start(ctx, withApp) || BackgroundLauncher.start(ctx, Intent(action, uri))
    }

    private fun open(ac: ActionContext, ctx: Context, q: String, id: String?, report: (ActionResult) -> Unit) {
        val ok = if (id != null) start(ctx, Uri.parse("https://www.youtube.com/watch?v=$id"), Intent.ACTION_VIEW)
        else BackgroundLauncher.start(ctx, Intent(Intent.ACTION_SEARCH).setPackage(YT).putExtra("query", q)) ||
            start(ctx, Uri.parse(YoutubeResults.searchUrl(q)), Intent.ACTION_VIEW)
        LogStore.add(ctx, "YOUTUBE q=\"$q\" id=${id ?: "-"} ok=$ok")
        if (!ok) return report(ActionResult(false, ac.t("ইউটিউব খোলা যায়নি", "Couldn't open YouTube")))
        val svc = BuildXAccessibilityService.instance
        Verify.poll(MainScheduler, 700, 6, {
            if (svc == null) Verdict.UNKNOWN
            else if (svc.rootInActiveWindow?.packageName?.toString() == YT) Verdict.YES else Verdict.NO
        }) { v ->
            report(
                when {
                    id == null -> ActionResult(true, ac.t("‘$q’-র সার্চ রেজাল্ট খুলেছি, নিজে বেছে নিন", "I opened the search results for “$q”, pick one"))
                    v == Verdict.NO -> ActionResult(false, ac.t("ইউটিউব সামনে আসেনি। ফোন লক থাকলে আনলক করুন", "YouTube did not come up. Unlock the phone if it is locked"))
                    else -> ActionResult(true, ac.t("‘$q’ বাজাচ্ছি", "Playing “$q”"))
                }
            )
        }
    }
}

/** "শর্টস চালাও" / "রিলস খোলো" */
object OpenFeedAction : Action {
    override val name = "open_feed"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val ctx = ac.context.applicationContext
        val shorts = params["feed"] == "shorts"
        val uri = Uri.parse(if (shorts) "https://www.youtube.com/shorts" else "https://www.instagram.com/reels/")
        val pkg = if (shorts) "com.google.android.youtube" else "com.instagram.android"
        val ok = BackgroundLauncher.start(ctx, Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)) || BackgroundLauncher.start(ctx, Intent(Intent.ACTION_VIEW, uri))
        LogStore.add(ctx, "FEED ${params["feed"]} ok=$ok")
        val name = if (shorts) ac.t("ইউটিউব শর্টস", "YouTube Shorts") else ac.t("ইনস্টাগ্রাম রিলস", "Instagram Reels")
        return if (ok) ActionResult(true, ac.t("$name খুলছি", "Opening $name")) else ActionResult(false, ac.t("$name খোলা যায়নি", "Couldn't open $name"))
    }
}

// ---------- maps and search ----------

object NavigateAction : Action {
    override val name = "navigate"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val q = params["query"].orEmpty().trim()
        if (q.isEmpty()) return askFor(ac, ac.t("কোথায় যাবেন?", "Where to?")) { go(ac, it) }
        return go(ac, q)
    }

    private fun go(ac: ActionContext, q: String): ActionResult {
        val ctx = ac.context.applicationContext
        val nav = Uri.parse("google.navigation:q=" + Uri.encode(q))
        val ok = BackgroundLauncher.start(ctx, Intent(Intent.ACTION_VIEW, nav).setPackage("com.google.android.apps.maps")) ||
            BackgroundLauncher.start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
        LogStore.add(ctx, "NAVIGATE q=\"$q\" ok=$ok")
        return if (ok) ActionResult(true, ac.t("‘$q’-এর রাস্তা ম্যাপে খুলছি", "Opening directions to $q"))
        else ActionResult(false, ac.t("ম্যাপ খোলা যায়নি", "Couldn't open Maps"))
    }
}

object WebSearchAction : Action {
    override val name = "web_search"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val q = params["query"].orEmpty().trim()
        if (q.isEmpty()) return askFor(ac, ac.t("কী খুঁজব?", "What should I search for?")) { search(ac, it) }
        return search(ac, q)
    }

    private fun search(ac: ActionContext, q: String): ActionResult {
        val ctx = ac.context.applicationContext
        val ok = BackgroundLauncher.start(ctx, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))
        LogStore.add(ctx, "SEARCH q=\"$q\" ok=$ok")
        return if (ok) ActionResult(true, ac.t("গুগলে ‘$q’ খুঁজছি", "Searching for $q"))
        else ActionResult(false, ac.t("সার্চ খোলা যায়নি", "Couldn't start the search"))
    }
}

// ---------- phone facts ----------

object BatteryAction : Action {
    override val name = "battery"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val i = ac.context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return ActionResult(false, ac.t("ব্যাটারির তথ্য পাইনি", "Couldn't read the battery"))
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (level < 0 || scale <= 0) return ActionResult(false, ac.t("ব্যাটারির তথ্য পাইনি", "Couldn't read the battery"))
        val pct = level * 100 / scale
        return ActionResult(
            true,
            ac.t("ব্যাটারি ${ac.d(pct)} শতাংশ${if (charging) ", চার্জ হচ্ছে" else ""}", "Battery is $pct percent${if (charging) ", charging" else ""}")
        )
    }
}

object DateAction : Action {
    override val name = "date"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val c = Calendar.getInstance()
        return ActionResult(true, DateWords.say(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.DAY_OF_WEEK), ac.bn))
    }
}

object CalcAction : Action {
    override val name = "calc"

    private fun spoken(ac: ActionContext, expr: String): String = expr.split(' ').joinToString(" ") { tok ->
        when (tok) {
            "plus" -> ac.t("যোগ", "plus")
            "minus" -> ac.t("বিয়োগ", "minus")
            "times" -> ac.t("গুণ", "times")
            "divided" -> ac.t("ভাগ", "divided by")
            "point" -> ac.t("দশমিক", "point")
            else -> if (ac.bn) tok.map { if (it in '0'..'9') '০' + (it - '0') else it }.joinToString("") else tok
        }
    }

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val expr = params["expr"].orEmpty()
        val v = Calc.eval(expr)
            ?: return ActionResult(false, ac.t("হিসাবটা করতে পারিনি (অসম্পূর্ণ, বা শূন্য দিয়ে ভাগ)", "I couldn't work that out (incomplete, or dividing by zero)"))
        return ActionResult(true, "${spoken(ac, expr)} ${ac.t("সমান", "equals")} ${Calc.format(v, ac.bn)}")
    }
}

// ---------- settings that need a one-time permission ----------

object BrightnessAction : Action {
    override val name = "brightness"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val ctx = ac.context.applicationContext
        if (!Settings.System.canWrite(ctx)) {
            return ActionResult(false, ac.t("ব্রাইটনেস বদলাতে \"সেটিংস বদলানোর অনুমতি\" দিতে হবে। অ্যাপ খুলে সেই বাটন চাপুন", "Brightness needs the \"modify system settings\" permission. Open the app and allow it"))
        }
        val cr = ctx.contentResolver
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val action = params["action"] ?: "none"
        if (action == "none") {
            return askFor(ac, ac.t("ব্রাইটনেস এখন ${ac.d(VolumeMath.indexToPercent(cur, 255))} শতাংশ। কত করব?", "Brightness is ${VolumeMath.indexToPercent(cur, 255)} percent. To what?")) { reply ->
                val p = IntentParser.parse("ব্রাইটনেস $reply")
                if (p.intent == "brightness" && p.slots["action"] != "none") execute(ac, p.slots)
                else ActionResult(false, ac.t("বুঝতে পারিনি", "Didn't get that"))
            }
        }
        val target = VolumeMath.target(cur, 255, action, params["level"]?.toIntOrNull(), params["delta"]?.toIntOrNull()).coerceIn(1, 255)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
        val actual = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
        val pct = VolumeMath.indexToPercent(actual, 255)
        LogStore.add(ctx, "BRIGHTNESS $cur -> $target got $actual")
        return ActionResult(true, ac.t("ব্রাইটনেস এখন ${ac.d(pct)} শতাংশ", "Brightness is $pct percent"))
    }
}

object RingerAction : Action {
    override val name = "ringer"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val ctx = ac.context.applicationContext
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val mode = params["mode"] ?: "normal"
        val target = when (mode) {
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        val guide = ActionResult(false, ac.t("এটা করতে \"Do Not Disturb অ্যাক্সেস\" দিতে হবে। অ্যাপ খুলে সেই বাটন চাপুন", "This needs \"Do Not Disturb access\". Open the app and allow it"))
        if (mode != "normal" && !nm.isNotificationPolicyAccessGranted) return guide
        try {
            am.ringerMode = target
        } catch (e: SecurityException) {
            return guide
        }
        LogStore.add(ctx, "RINGER $mode now=${am.ringerMode}")
        if (am.ringerMode != target) return ActionResult(false, ac.t("মোড বদলায়নি", "The mode did not change"))
        return ActionResult(
            true,
            when (mode) {
                "silent" -> ac.t("সাইলেন্ট মোড চালু করেছি", "Silent mode on")
                "vibrate" -> ac.t("ভাইব্রেট মোড চালু করেছি", "Vibrate mode on")
                else -> ac.t("নরমাল মোড চালু করেছি", "Normal mode on")
            }
        )
    }
}
