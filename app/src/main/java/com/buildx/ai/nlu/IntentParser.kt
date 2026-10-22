package com.buildx.ai.nlu

/**
 * [intent]: time, call, end_call, alarm, flashlight, wifi, bluetooth, volume, screen_wake, screen_lock,
 * back, home, recents, notifications, screenshot, read_screen, tap, type_text,
 * open_app, cancel, unknown.  [slots] depend on the intent (see parse()).
 * [normalized] is the cleaned utterance, handy for fuzzy contact matching in Phase 3.
 * Slot "chain" (only when the sentence holds more than one command): intents in the order spoken.
 */
data class Parsed(val intent: String, val slots: Map<String, String>, val normalized: String) {
    fun slot(key: String): String? = slots[key]
}

/** Step 3: tokens -> intent + slots. Rule based, offline, no Android classes. */
object IntentParser {

    private val priority = listOf(
        "end_call", "who_calling", "call", "alarm", "take_photo", "media", "swipe", "calc", "flashlight",
        "hotspot", "airplane", "mobile_data", "dnd", "wifi", "bluetooth",
        "brightness", "ringer", "volume", "screen", "battery", "date", "time", "navigate", "web_search",
        "youtube_play", "open_feed", "read_notifications", "read_sms", "open_app", "cancel",
        "back", "home", "recents", "notifications", "screenshot", "read_screen", "type_text", "tap"
    )
    private val phoneNumber = Regex("^\\+?\\d+$")
    private val smallNumber = Regex("^\\d{1,3}$")

    private class Ctx(val toks: List<Tok>) {
        val c: List<String> = toks.map { it.canon }
        fun has(vararg k: String) = c.any { it in k }
        fun idx(vararg k: String) = c.indexOfFirst { it in k }
    }

    /** one command (several in one sentence are flagged in the "chain" slot; see [parseChain] to split them) */
    fun parse(text: String): Parsed {
        val norm = TextNormalizer.normalize(text)
        return parseCtx(Ctx(Canonicalizer.tokenize(norm)), norm, text)
    }

    /**
     * "ইউটিউব খুলে ভলিউম কমাও" -> [open_app youtube, volume down]. One element when the sentence is a single command.
     * Steps come in the order spoken.
     */
    fun parseChain(text: String): List<Parsed> {
        val norm = TextNormalizer.normalize(text)
        val toks = Canonicalizer.tokenize(norm)
        // a message, note, reminder... swallows the rest of the sentence ("রুটি আর ডিম নোট করো" is one note)
        if (FreeText.parse(toks) != null) {
            val pos = FreeText.triggerPos(toks)
            if (toks.subList(0, pos).none { ChainSplitter.isTrigger(it.canon) }) return listOf(parseCtx(Ctx(toks), norm, text))
        }
        val segments = ChainSplitter.split(toks)
        if (segments.size <= 1) return listOf(parseCtx(Ctx(toks), norm, text))
        return segments.map { seg ->
            val raw = seg.joinToString(" ") { it.raw }
            val p = parseCtx(Ctx(seg), raw, raw)
            p.copy(slots = p.slots - "chain")
        }
    }

    private fun parseCtx(x: Ctx, norm: String, text: String): Parsed {
        val c = x.c
        FreeText.parse(x.toks)?.let { return Parsed(it.intent, it.slots, norm) }

        // ---- which intents are present, and where ----
        val hits = LinkedHashMap<String, Int>()
        fun add(name: String, pos: Int) { if (pos >= 0 && name !in hits) hits[name] = pos }

        val callPos = x.idx("call")
        if (x.has("hangup") || (callPos >= 0 && x.has("cut", "off"))) {
            add("end_call", maxOf(callPos, x.idx("hangup")))
        } else {
            add("call", callPos)
        }

        val strictClock = TimeParser.clock(c, allowFallback = false)
        val alarmWord = x.idx("alarm")
        val wakeMe = x.idx("wake", "wakeme")
        val alarmFromWake = alarmWord < 0 && wakeMe >= 0 && !x.has("screen") && strictClock != null
        add("alarm", if (alarmWord >= 0) alarmWord else if (alarmFromWake) wakeMe else -1)

        val photoPos = x.idx("takephoto", "selfie")
        add("take_photo", photoPos)

        // ---- everyday things ----
        val contentNoSong = queryRaw(x, keepSong = false)
        val hasPlay = x.has("play")
        val hasSong = x.has("song")
        val ytWord = x.has("youtube")
        val mediaPos = x.idx("mediaplay", "mediapause", "medianext", "mediaprev")
        add("media", mediaPos)
        add("swipe", x.idx("reelnext", "reelprev"))
        val opPos = x.idx("plus", "minus", "times", "divided")
        if (opPos >= 0 && c.count { digitToken.matches(it) } >= 2) add("calc", opPos)
        add("brightness", x.idx("brightness"))
        add("ringer", x.idx("silentmode", "vibratemode", "ringmode"))
        add("battery", x.idx("battery"))
        add("date", x.idx("askdate"))

        add("flashlight", x.idx("flashlight"))
        add("hotspot", x.idx("hotspot"))
        add("airplane", x.idx("airplane"))
        add("mobile_data", x.idx("mobiledata"))
        add("dnd", x.idx("dnd"))
        add("who_calling", x.idx("whocalls"))
        if (x.has("notifs") && x.has("readword", "newword")) add("read_notifications", x.idx("notifs"))
        if (x.has("messages") && x.has("readword", "newword") && !x.has("open", "app")) add("read_sms", x.idx("messages"))
        add("wifi", x.idx("wifi"))
        add("bluetooth", x.idx("bluetooth"))
        add("volume", x.idx("volume", "mute", "unmute", "louder", "quieter"))

        val screenName = screenDecision(x, includeWake = !alarmFromWake)
        if (screenName != null) {
            add("screen", x.idx("screen", "lock", "unlock", "wake"))
        }

        if ("alarm" !in hits) add("time", x.idx("whattime"))

        val others = hits.keys.any { it != "open_app" }
        val navigateWord = x.has("navigate") || (x.has("maps") && contentNoSong.isNotEmpty() && !x.has("open"))
        if (navigateWord) add("navigate", maxOf(x.idx("navigate"), x.idx("maps")))
        if ((x.has("search") || x.has("google")) && !ytWord && !navigateWord) add("web_search", maxOf(x.idx("search"), x.idx("google")))
        val ytIntent = when {
            ytWord && contentNoSong.isNotEmpty() && !x.has("open") -> true
            hasSong && (hasPlay || x.has("on")) && !others -> true
            hasPlay && contentNoSong.isNotEmpty() && !others -> true
            else -> false
        }
        if (ytIntent) add("youtube_play", maxOf(x.idx("song"), x.idx("youtube"), x.idx("play")))
        // plain "প্লে করো" with nothing to play: carry on with the current media
        if (hasPlay && contentNoSong.isEmpty() && !hasSong && !ytWord && !others) add("media", x.idx("play"))
        if (x.has("shorts", "reels") && x.has("open", "on", "show", "play") && "swipe" !in hits) add("open_feed", x.idx("shorts", "reels"))

        navOnly(x)?.let { (name, pos) -> add(name, pos) }

        val appPos = c.indices.firstOrNull {
            c[it] in Lexicon.appKeys &&
                !(c[it] == "settings" && ("wifi" in hits || "bluetooth" in hits)) &&
                !(c[it] == "camera" && photoPos >= 0) // "ক্যামেরা দিয়ে ছবি তোলো" is one command
        } ?: -1
        if (appPos >= 0) add("open_app", appPos)
        else if (hits.isEmpty() && x.has("open")) add("open_app", x.idx("open"))

        // "tap Send" / "type hello": only when nothing else matched, and the word sits at an edge of the sentence
        var onScreenText: String? = null
        if (hits.isEmpty()) {
            onScreen(x)?.let { (name, words) ->
                add(name, 0)
                onScreenText = words
            }
        }

        if (hits.isEmpty()) add("cancel", x.idx("cancel"))

        // ---- primary intent ----
        val primaryKey = priority.firstOrNull { it in hits }
        fun realName(key: String) = if (key == "screen") screenName!! else key
        var intent = primaryKey?.let { realName(it) } ?: "unknown"
        // a call that names WhatsApp, or a video call, goes through WhatsApp
        if (intent == "call" && (x.has("whatsapp") || x.has("video"))) intent = "whatsapp_call"

        val slots = LinkedHashMap<String, String>()
        when (intent) {
            "call" -> callSlots(x, slots)
            "whatsapp_call" -> {
                callSlots(x, slots)
                if (x.has("video")) slots["video"] = "1"
            }
            "take_photo" -> slots["camera"] = if (x.has("front", "selfie")) "front" else "back"
            "alarm" -> alarmSlots(x, slots)
            "flashlight" -> slots["state"] = onOff(x, openAsOn = true) ?: "toggle"
            "wifi", "bluetooth" -> slots["state"] = onOff(x, openAsOn = false) ?: "open"
            "dnd", "airplane", "mobile_data", "hotspot" ->
                slots["state"] = onOff(x, openAsOn = false) ?: if (x.has("open")) "open" else "toggle"
            "volume" -> volumeSlots(x, slots)
            "screen_wake" -> if (x.has("unlock")) slots["unlock"] = "1"
            "open_app" -> appSlots(x, slots)
            "media" -> slots["action"] = mediaAction(x)
            "swipe" -> slots["dir"] = if (x.c.indexOfLast { it == "reelprev" } > x.c.indexOfLast { it == "reelnext" }) "prev" else "next"
            "calc" -> slots["expr"] = c.filter { digitToken.matches(it) || it in setOf("plus", "minus", "times", "divided", "point") }.joinToString(" ")
            "brightness" -> levelSlots(x, slots)
            "ringer" -> slots["mode"] = when {
                x.has("silentmode") -> "silent"
                x.has("vibratemode") -> "vibrate"
                else -> "normal"
            }
            "navigate", "web_search" -> slots["query"] = queryRaw(x, keepSong = true).joinToString(" ")
            "youtube_play" -> slots["query"] = queryRaw(x, keepSong = true).joinToString(" ").let { q ->
                if (contentNoSong.isEmpty()) "" else q // only "গান": which song is not known
            }
            "open_feed" -> slots["feed"] = if (x.has("shorts")) "shorts" else "reels"
            "tap", "type_text" -> onScreenText?.takeIf { it.isNotEmpty() }?.let { slots["text"] = it }
            "unknown" -> slots["text"] = text.trim()
        }
        if (hits.size >= 2 && primaryKey != "call" && primaryKey != null) {
            val order = hits.entries.filter { it.key != "cancel" }.sortedBy { it.value }.map { realName(it.key) }
            if (order.size >= 2) slots["chain"] = order.joinToString(",")
        }
        return Parsed(intent, slots, norm)
    }

    /** name/number from a short spoken reply such as "রহিমকে" or "০১৭১১২২৩৩৪৪" (slots "target" / "number") */
    fun callTarget(text: String): Map<String, String> {
        val x = Ctx(Canonicalizer.tokenize(TextNormalizer.normalize(text)))
        val m = LinkedHashMap<String, String>()
        callSlots(x, m)
        return m
    }

    // ---------- system buttons, tap, type ----------

    private val navIntent = mapOf(
        "goback" to "back", "gohome" to "home", "recents" to "recents", "notifs" to "notifications",
        "screenshot" to "screenshot", "readscreen" to "read_screen"
    )
    private val navCanonOk = setOf("_f", "_v", "show", "open", "screen", "tapword")
    private val navRawOk = setOf("যাও", "যান", "go", "চলো", "একটা", "একটি")

    /**
     * Back, Home, Recents, notifications, screenshot, read the screen: only when that is the WHOLE sentence
     * (plus polite filler). "I want to go back to my home town" must stay unknown, not press Back.
     */
    private fun navOnly(x: Ctx): Pair<String, Int>? {
        val found = x.c.withIndex().filter { it.value in navIntent }
        if (found.map { it.value }.distinct().size != 1) return null
        if (x.toks.any { it.canon !in navIntent && it.canon !in navCanonOk && it.raw !in navRawOk }) return null
        return navIntent.getValue(found[0].value) to found[0].index
    }

    private val tapTrim = setOf("_f", "_v", "on", "sep")

    /**
     * "ট্যাপ করো সেন্ড", "tap on Send", "সেন্ড এ ট্যাপ করো", "টাইপ করো hello", "hello টাইপ করো".
     * The tap / type word must be the first or the last real word. Returns the intent and the words it is about.
     */
    private fun onScreen(x: Ctx): Pair<String, String>? {
        val toks = x.toks
        fun filler(t: Tok) = t.canon == "_f" || t.canon == "_v"
        val first = toks.indexOfFirst { !filler(it) }
        val last = toks.indexOfLast { !filler(it) }
        if (first < 0) return null
        val (name, around) = when {
            toks[first].canon == "typeword" -> "type_text" to toks.subList(first + 1, toks.size)
            toks[first].canon == "tapword" -> "tap" to toks.subList(first + 1, toks.size)
            toks[last].canon == "typeword" -> "type_text" to toks.subList(0, last)
            toks[last].canon == "tapword" -> "tap" to toks.subList(0, last)
            else -> return null
        }
        if (around.any { it.canon == "tapword" || it.canon == "typeword" }) return null
        // typed words are kept as said (only the do-verb "করো" is dropped: "আমি আসছি" must keep its আমি);
        // a tap target also loses "on", "the" and the like at its edges
        var a = 0
        var b = around.size
        val drop: (Tok) -> Boolean = if (name == "tap") { t -> t.canon in tapTrim } else { t -> t.canon == "_v" }
        while (a < b && drop(around[a])) a++
        while (b > a && drop(around[b - 1])) b--
        // a button label is a few words; a longer sentence that merely has "tap" in it is not a command
        if (name == "tap" && b - a > 5) return null
        return name to around.subList(a, b).joinToString(" ") { it.raw }
    }

    // ---------- screen ----------

    private fun screenDecision(x: Ctx, includeWake: Boolean): String? {
        val hasScreen = x.has("screen")
        val wake = includeWake && x.has("wake")
        val showOrOn = x.has("show", "on", "open")
        return when {
            x.has("unlock") -> "screen_wake"
            x.has("lock") -> if (hasScreen && showOrOn) "screen_wake" else "screen_lock"
            hasScreen && x.has("off") -> "screen_lock"
            wake -> "screen_wake"
            hasScreen && showOrOn -> "screen_wake"
            else -> null
        }
    }

    // ---------- state words ----------

    /** the LAST of on/off wins ("চালু ... বন্ধ করো"); [openAsOn]: খোলো counts as on (flashlight) */
    private fun onOff(x: Ctx, openAsOn: Boolean): String? {
        var state: String? = null
        for (t in x.c) {
            when (t) {
                "on" -> state = "on"
                "off" -> state = "off"
                "open" -> if (openAsOn) state = "on"
            }
        }
        return state
    }

    // ---------- call ----------

    private val notName = setOf("call", "phone", "number", "_f", "_v", "sep", "cut", "hangup", "whatsapp", "video", "on", "again")

    private fun callSlots(x: Ctx, slots: MutableMap<String, String>) {
        // phone number: the longest run of digit tokens (digits spoken one by one are joined)
        var best = ""
        var cur = StringBuilder()
        for (t in x.c + "") {
            if (phoneNumber.matches(t)) {
                cur.append(t)
            } else {
                if (cur.length > best.length) best = cur.toString()
                cur = StringBuilder()
            }
        }
        if (best.filter { it.isDigit() }.length >= 3) slots["number"] = best

        // name: what is left after call words/fillers; raw words are used so names are never "translated"
        val words = x.toks.filter { it.canon !in notName && !phoneNumber.matches(it.canon) }.map { it.raw }.toMutableList()
        if (words.isNotEmpty()) {
            val last = words.last()
            val kay = TextNormalizer.nfc("কে")
            if (last.length > kay.length && last.endsWith(kay)) words[words.lastIndex] = last.dropLast(kay.length)
        }
        val name = words.joinToString(" ").trim()
        if (name.isNotEmpty()) slots["target"] = name
    }

    // ---------- alarm ----------

    private fun alarmSlots(x: Ctx, slots: MutableMap<String, String>) {
        val clock = TimeParser.clock(x.c, allowFallback = true)
        if (clock != null) {
            slots["hour"] = clock.hour.toString()
            slots["minute"] = clock.minute.toString()
            slots["ambiguous"] = clock.ambiguous.toString()
        } else {
            TimeParser.relativeMinutes(x.c)?.let { slots["in_minutes"] = it.toString() }
        }
        slots["when"] = when {
            x.has("dayafter") -> "day_after"
            x.has("tomorrow") -> "tomorrow"
            x.has("today") -> "today"
            else -> "auto"
        }
    }

    // ---------- everyday helpers ----------

    private val digitToken = Regex("^\\d+$")
    private val queryDrop = setOf(
        "youtube", "app", "on", "off", "open", "play", "google", "search", "navigate", "maps", "sep", "cut", "hangup",
        "whatsapp", "phone", "number", "call", "shorts", "reels", "show"
    )
    private val trimLeadingLatin = setOf("for", "to", "the", "a", "an", "please", "plz", "me", "my", "can", "could", "would", "hey", "set", "it", "now")
    private val trimTrailingLatin = setOf("please", "plz", "now", "it")

    private fun isBengali(s: String) = s.any { it in '\u0980'..'\u09FF' }

    /** filler at the edge of a query: Bengali ones always, English only the polite/command-ish ones */
    private fun edgeFiller(t: Tok, leading: Boolean): Boolean {
        if (t.canon != "_f" && t.canon != "_v") return false
        if (isBengali(t.raw)) return true
        return t.raw in (if (leading) trimLeadingLatin else trimTrailingLatin)
    }

    /** the words that say WHAT (a song, a place, a search): command words are dropped, inner small words are kept ("Shape of You") */
    private fun queryRaw(x: Ctx, keepSong: Boolean): List<String> {
        val toks = x.toks.filter { it.canon !in queryDrop && (keepSong || it.canon != "song") }
        var a = 0
        var b = toks.size
        while (a < b && edgeFiller(toks[a], leading = true)) a++
        while (b > a && edgeFiller(toks[b - 1], leading = false)) b--
        return toks.subList(a, b).map { it.raw }
    }

    /** what to say about the text of a reply such as "আরিজিত সিংয়ের গান" or "ঢাকা" (for follow-up questions) */
    fun queryOf(text: String): String = queryRaw(Ctx(Canonicalizer.tokenize(TextNormalizer.normalize(text))), keepSong = true).joinToString(" ")

    private fun mediaAction(x: Ctx): String = when {
        x.has("mediapause") -> "pause"
        x.has("medianext") -> "next"
        x.has("mediaprev") -> "prev"
        else -> "play"
    }

    private fun levelSlots(x: Ctx, slots: MutableMap<String, String>) {
        val c = x.c
        val upPos = c.indexOfLast { it == "up" || it == "louder" }
        val downPos = c.indexOfLast { it == "down" || it == "quieter" }
        val number = c.firstOrNull { smallNumber.matches(it) }?.toInt()?.coerceIn(0, 100)
        when {
            x.has("max") -> { slots["action"] = "set"; slots["level"] = "100" }
            x.has("min") -> { slots["action"] = "set"; slots["level"] = "0" }
            upPos >= 0 || downPos >= 0 -> {
                slots["action"] = if (upPos > downPos) "up" else "down"
                if (number != null) slots["delta"] = number.toString()
            }
            number != null -> { slots["action"] = "set"; slots["level"] = number.toString() }
            else -> slots["action"] = "none"
        }
    }

    // ---------- volume ----------

    private fun volumeSlots(x: Ctx, slots: MutableMap<String, String>) {
        val c = x.c
        val upPos = c.indexOfLast { it == "up" || it == "louder" }
        val downPos = c.indexOfLast { it == "down" || it == "quieter" }
        val number = c.firstOrNull { smallNumber.matches(it) }?.toInt()?.coerceIn(0, 100)
        val noun = x.has("volume")
        when {
            x.has("unmute") -> slots["action"] = "unmute"
            x.has("mute") || (noun && x.has("off") && upPos < 0 && downPos < 0) -> slots["action"] = "mute"
            noun && x.has("on") && upPos < 0 && downPos < 0 && number == null -> slots["action"] = "unmute"
            x.has("max") -> { slots["action"] = "set"; slots["level"] = "100" }
            x.has("min") -> { slots["action"] = "set"; slots["level"] = "0" }
            upPos >= 0 || downPos >= 0 -> {
                slots["action"] = if (upPos > downPos) "up" else "down"
                if (number != null) slots["delta"] = number.toString()
            }
            number != null -> { slots["action"] = "set"; slots["level"] = number.toString() }
            else -> slots["action"] = "none"
        }
    }

    // ---------- open app ----------

    private fun appSlots(x: Ctx, slots: MutableMap<String, String>) {
        val key = x.c.firstOrNull { it in Lexicon.appKeys }
        if (key != null) {
            slots["app"] = key
            slots["known"] = "true"
            return
        }
        if (x.has("phone")) {
            slots["app"] = "dialer"
            slots["known"] = "true"
            return
        }
        val skip = setOf("open", "on", "off", "app", "_f", "_v", "sep")
        val raw = x.toks.filter { it.canon !in skip }.joinToString(" ") { it.raw }.trim()
        slots["app"] = raw
        slots["known"] = "false"
    }
}
