package com.buildx.ai.nlu

/**
 * [intent]: time, call, end_call, alarm, flashlight, wifi, bluetooth, volume, screen_wake, screen_lock,
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
        "end_call", "call", "alarm", "flashlight", "wifi", "bluetooth", "volume", "screen", "time", "open_app", "cancel"
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

        add("flashlight", x.idx("flashlight"))
        add("wifi", x.idx("wifi"))
        add("bluetooth", x.idx("bluetooth"))
        add("volume", x.idx("volume", "mute", "unmute", "louder", "quieter"))

        val screenName = screenDecision(x, includeWake = !alarmFromWake)
        if (screenName != null) {
            add("screen", x.idx("screen", "lock", "unlock", "wake"))
        }

        if ("alarm" !in hits) add("time", x.idx("whattime"))

        val appPos = c.indices.firstOrNull { c[it] in Lexicon.appKeys && !(c[it] == "settings" && ("wifi" in hits || "bluetooth" in hits)) } ?: -1
        if (appPos >= 0) add("open_app", appPos)
        else if (hits.isEmpty() && x.has("open")) add("open_app", x.idx("open"))

        if (hits.isEmpty()) add("cancel", x.idx("cancel"))

        // ---- primary intent ----
        val primaryKey = priority.firstOrNull { it in hits }
        fun realName(key: String) = if (key == "screen") screenName!! else key
        val intent = primaryKey?.let { realName(it) } ?: "unknown"

        val slots = LinkedHashMap<String, String>()
        when (intent) {
            "call" -> callSlots(x, slots)
            "alarm" -> alarmSlots(x, slots)
            "flashlight" -> slots["state"] = onOff(x, openAsOn = true) ?: "toggle"
            "wifi", "bluetooth" -> slots["state"] = onOff(x, openAsOn = false) ?: "open"
            "volume" -> volumeSlots(x, slots)
            "screen_wake" -> if (x.has("unlock")) slots["unlock"] = "1"
            "open_app" -> appSlots(x, slots)
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

    private val notName = setOf("call", "phone", "number", "_f", "_v", "sep", "cut", "hangup")

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
