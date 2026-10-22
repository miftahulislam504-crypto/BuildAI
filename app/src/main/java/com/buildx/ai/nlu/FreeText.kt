package com.buildx.ai.nlu

/**
 * Commands that carry free words: a message to send, a note, a reminder, a calendar event, a place for the weather,
 * a unit conversion. Everything is rule based and offline; a sentence that does not fit falls through to the normal
 * parser (and then to the LLM). No Android classes.
 *
 * Intents and slots:
 *  send_sms / send_whatsapp : target | number, text
 *  note                     : op = add | read | clear, text (add)
 *  remind                   : text, hour+minute+ambiguous | in_minutes, when
 *  calendar_event           : title, hour+minute+ambiguous, when
 *  weather                  : city, when
 *  convert                  : value, from, to
 *  routine                  : op = save | run | list | delete | schedule | power | bt | untrigger, name, (save) source=last,
 *                             (schedule) hour+minute+ambiguous, (power) event=plug|unplug, (bt) event=connect|disconnect
 */
object FreeText {

    data class Hit(val intent: String, val slots: Map<String, String>)

    private val digits = Regex("^\\d+$")
    private val hourMark = Regex("^(\\d{1,2})(" + Numbers.suffixes.joinToString("|") + ")$")
    private val der = TextNormalizer.nfc("দেড়")
    private val arai = TextNormalizer.nfc("আড়াই")

    fun parse(toks: List<Tok>): Hit? =
        routine(toks) ?: convert(toks) ?: message(toks) ?: calendar(toks) ?: remind(toks) ?: note(toks) ?: weather(toks)

    /** where the first free-text trigger word sits, or -1: used to decide whether the whole sentence is one command */
    fun triggerPos(toks: List<Tok>): Int {
        if (parse(toks) == null) return -1
        val c = toks.map { it.canon }
        return c.indexOfFirst { it in triggerCanons || it.startsWith("u_") || it.startsWith("c_") }.coerceAtLeast(0)
    }

    private val triggerCanons = setOf("messages", "whatsapp", "sendword", "note", "remind", "calendar", "weather", "howmuch", "routine")

    // ---------- routines ----------

    private val routineDrop = setOf(
        "routine", "routinemake", "erase", "listw", "triggerw", "off", "on", "bluetooth", "connectw", "disconnectw",
        "charger", "chargeroff", "daily", "show", "open", "it_ref"
    )
    private val lastWords = setOf("আগের", "আগেরটা", "শেষ", "শেষেরটা", "last", "previous")

    private fun routine(toks: List<Tok>): Hit? {
        val c = toks.map { it.canon }
        if ("routine" !in c) return null
        fun has(k: String) = k in c
        val time = timeSlots(toks, whenToo = false).filterKeys { it != "in_minutes" }
        val slots = LinkedHashMap<String, String>()
        val op = when {
            has("erase") -> "delete"
            has("listw") -> "list"
            has("triggerw") && has("off") -> "untrigger"
            has("bluetooth") && (has("connectw") || has("disconnectw")) -> {
                slots["event"] = if (has("disconnectw")) "disconnect" else "connect"; "bt"
            }
            has("charger") || has("chargeroff") -> {
                slots["event"] = if (has("chargeroff")) "unplug" else "plug"; "power"
            }
            has("daily") && time["hour"] != null -> { slots.putAll(time); "schedule" }
            has("routinemake") -> "save"
            else -> "run"
        }
        slots["op"] = op
        if (op == "save" && toks.any { it.raw in lastWords }) slots["source"] = "last"
        if (op != "list") {
            val name = raw(wordsWithoutTime(toks.filter { it.raw !in lastWords || op != "save" }, routineDrop))
            if (name.isNotEmpty()) slots["name"] = name
        }
        return Hit("routine", slots)
    }

    // ---------- shared helpers ----------

    private fun edgeTrim(t: List<Tok>): List<Tok> {
        var a = 0
        var b = t.size
        fun skip(k: Tok) = k.canon == "_f" || k.canon == "_v" || k.canon == "sep"
        while (a < b && skip(t[a])) a++
        while (b > a && skip(t[b - 1])) b--
        return t.subList(a, b)
    }

    private fun raw(t: List<Tok>) = t.joinToString(" ") { it.raw }.trim()

    private val timeCanons = setOf(
        "tomorrow", "today", "dayafter", "minute", "hour", "half", "half_past", "quarter_past", "quarter_to",
        "am", "pm", "morning", "dawn", "noon", "afternoon", "evening", "night"
    )
    private val timeRaw = setOf("পরে", "পর", "later", "after", "in", "within", "from", "now")

    /** time slots for a reminder / event. Empty map = no time said. */
    fun timeSlots(toks: List<Tok>, whenToo: Boolean = true): Map<String, String> {
        val c = toks.map { it.canon }
        val at = toks.indices.any { toks[it].raw == "at" && c.getOrNull(it + 1)?.let { n -> digits.matches(n) } == true }
        val out = LinkedHashMap<String, String>()
        val clock = TimeParser.clock(c, allowFallback = at)
        if (clock != null) {
            out["hour"] = clock.hour.toString()
            out["minute"] = clock.minute.toString()
            out["ambiguous"] = clock.ambiguous.toString()
        } else {
            TimeParser.relativeMinutes(c)?.let { out["in_minutes"] = it.toString() }
        }
        if (whenToo) {
            out["when"] = when {
                "dayafter" in c -> "day_after"
                "tomorrow" in c -> "tomorrow"
                "today" in c -> "today"
                else -> "auto"
            }
        }
        return out
    }

    /** the words of [toks] that are not part of the time expression or in [dropCanon] */
    private fun wordsWithoutTime(toks: List<Tok>, dropCanon: Set<String>): List<Tok> {
        val c = toks.map { it.canon }
        val keep = ArrayList<Tok>()
        for (i in toks.indices) {
            val k = c[i]
            val t = toks[i]
            val next = c.getOrNull(i + 1)
            val next2 = c.getOrNull(i + 2)
            val prev = c.getOrNull(i - 1)
            val drop = when {
                k in dropCanon || k in timeCanons -> true
                t.raw in timeRaw -> true
                hourMark.matches(k) -> true
                k.startsWith(der) || k.startsWith(arai) -> true
                digits.matches(k) -> next in setOf("minute", "hour", "am", "pm") ||
                    (next != null && digits.matches(next) && next2 in setOf("am", "pm", "minute")) ||
                    prev in setOf("half_past", "quarter_past", "quarter_to") ||
                    toks.getOrNull(i - 1)?.raw == "at"
                else -> false
            }
            if (!drop) keep.add(t)
        }
        return edgeTrim(keep)
    }

    // ---------- send a message ----------

    private val messageCluster = setOf("messages", "whatsapp", "sendword", "_v", "on")
    private val clusterRaw = setOf("a", "an", "the", "new", "one")
    private val bodyDelims = setOf("যে", "বলে", "saying", "that", "লিখো", "লিখে")
    private val blockBefore = setOf("open", "app", "show", "readword", "call", "hangup")

    private fun message(toks: List<Tok>): Hit? {
        val c = toks.map { it.canon }
        if ("messages" !in c && "whatsapp" !in c) return null
        // the first group of command words that really asks to send something
        var i = 0
        while (i < toks.size) {
            if (toks[i].canon !in messageCluster && toks[i].raw !in clusterRaw) { i++; continue }
            var j = i
            while (j + 1 < toks.size && (toks[j + 1].canon in messageCluster || toks[j + 1].raw in clusterRaw)) j++
            val cluster = c.subList(i, j + 1)
            val firstMsg = cluster.indexOfFirst { it == "messages" }
            val sends = "sendword" in cluster ||
                (firstMsg >= 0 && cluster.drop(firstMsg + 1).any { it == "_v" })
            if (sends) {
                if (toks.subList(0, i).any { it.canon in blockBefore }) return null
                return buildMessage(toks, i, j)
            }
            i = j + 1
        }
        return null
    }

    private fun buildMessage(toks: List<Tok>, start: Int, end: Int): Hit {
        val whatsapp = toks.any { it.canon == "whatsapp" }
        val before = edgeTrim(toks.subList(0, start).filter { it.canon != "whatsapp" && it.canon != "on" })
        var after = toks.subList(end + 1, toks.size)
        var targetToks: List<Tok> = before
        var body: List<Tok> = after

        if (before.isEmpty()) {
            val afterNoWa = after
            if (afterNoWa.firstOrNull()?.raw == "to") {
                // English: "to Rahim saying I'm late", "to Rahim on WhatsApp saying ..."
                val rest = afterNoWa.drop(1)
                val d = rest.indexOfFirst { it.raw in bodyDelims }
                if (d >= 0) {
                    targetToks = rest.subList(0, d)
                    body = rest.subList(d + 1, rest.size)
                } else {
                    targetToks = rest.take(1)
                    body = rest.drop(1)
                }
            } else if (afterNoWa.isNotEmpty() && (afterNoWa[0].raw.endsWith(TextNormalizer.nfc("কে")) && afterNoWa[0].raw.length > 2)) {
                targetToks = afterNoWa.take(1)
                body = afterNoWa.drop(1)
            } else if (afterNoWa.size >= 2 && afterNoWa[1].raw == TextNormalizer.nfc("কে")) {
                targetToks = afterNoWa.take(1)
                body = afterNoWa.drop(2)
            } else {
                targetToks = emptyList()
                body = afterNoWa
            }
        }
        // "on WhatsApp" / "WhatsApp-এ" words inside what is left of the target
        targetToks = edgeTrim(targetToks.filter { it.canon != "whatsapp" && it.canon != "on" })
        // the body: drop a leading "যে / saying / that"
        var b = 0
        while (b < body.size && (body[b].raw in bodyDelims || body[b].canon == "on" && body[b].raw == "on")) b++
        val bodyText = raw(body.subList(b, body.size))

        val slots = LinkedHashMap<String, String>()
        if (targetToks.isNotEmpty()) {
            val who = IntentParser.callTarget(raw(targetToks))
            who["number"]?.let { slots["number"] = it }
            who["target"]?.let { slots["target"] = it }
        }
        if (bodyText.isNotEmpty()) slots["text"] = bodyText
        return Hit(if (whatsapp) "send_whatsapp" else "send_sms", slots)
    }

    // ---------- notes ----------

    private fun note(toks: List<Tok>): Hit? {
        val c = toks.map { it.canon }
        val p = c.indexOf("note")
        if (p < 0 || c.any { it == "open" || it == "app" }) return null
        val op = when {
            c.any { it == "readword" || it == "show" } -> "read"
            c.any { it == "erase" } -> "clear"
            else -> "add"
        }
        val slots = linkedMapOf("op" to op)
        if (op == "add") {
            // "নোট করো দুধ কিনতে হবে" / "make a note buy milk": the words after the note word; "দুধ কিনতে হবে নোট করো": the ones before
            var after = edgeTrim(toks.subList(p + 1, toks.size))
            while (after.isNotEmpty() && after[0].raw in setOf("down", "that")) after = edgeTrim(after.drop(1))
            val text = raw(if (after.isNotEmpty()) after else edgeTrim(toks.subList(0, p)))
            if (text.isNotEmpty()) slots["text"] = text
        }
        return Hit("note", slots)
    }

    // ---------- reminders ----------

    private fun remind(toks: List<Tok>): Hit? {
        val c = toks.map { it.canon }
        if ("remind" !in c) return null
        val slots = LinkedHashMap<String, String>()
        slots.putAll(timeSlots(toks))
        val text = raw(wordsWithoutTime(toks, setOf("remind")))
        if (text.isNotEmpty()) slots["text"] = text
        return Hit("remind", slots)
    }

    // ---------- calendar ----------

    private fun calendar(toks: List<Tok>): Hit? {
        val c = toks.map { it.canon }
        if ("calendar" !in c || !("plus" in c || "event" in c) || "open" in c) return null
        val slots = LinkedHashMap<String, String>()
        slots.putAll(timeSlots(toks))
        slots.remove("in_minutes")
        val title = raw(wordsWithoutTime(toks, setOf("calendar", "plus", "event")))
        if (title.isNotEmpty()) slots["title"] = title
        return Hit("calendar_event", slots)
    }

    // ---------- weather ----------

    private val weatherStop = setOf(
        "কেমন", "কী", "কি", "কত", "কিরকম", "হবে", "আছে", "how", "hows", "whats", "what", "is", "will", "be", "like",
        "outside", "বাইরে", "এখন", "কালকের", "আগামীকালের", "the", "in", "of", "for", "today", "tomorrow", "there", "weather", "forecast"
    )

    private fun weather(toks: List<Tok>): Hit? {
        val c = toks.map { it.canon }
        if ("weather" !in c || c.any { it == "open" || it == "search" || it == "google" || it == "youtube" }) return null
        val slots = LinkedHashMap<String, String>()
        val rest = toks.filter { it.canon != "weather" && it.canon != "_f" && it.canon != "_v" && it.canon !in timeCanons && it.raw !in weatherStop }
        val city = raw(rest)
        if (city.isNotEmpty()) slots["city"] = city
        slots["when"] = if ("tomorrow" in c) "tomorrow" else "today"
        return Hit("weather", slots)
    }

    // ---------- conversion ----------

    private fun numberAt(toks: List<Tok>, from: Int): Pair<String, Int>? {
        val c = toks.map { it.canon }
        for (i in from until toks.size) {
            if (digits.matches(c[i])) {
                if (c.getOrNull(i + 1) == "point" && c.getOrNull(i + 2)?.let { digits.matches(it) } == true) return "${c[i]}.${c[i + 2]}" to i
                return c[i] to i
            }
            if (c[i] == "half" && c.getOrNull(i + 1)?.let { it.startsWith("u_") } == true) return "0.5" to i
            if (c[i].startsWith(der) && c.getOrNull(i + 1)?.let { it.startsWith("u_") } == true) return "1.5" to i
            if (c[i].startsWith(arai) && c.getOrNull(i + 1)?.let { it.startsWith("u_") } == true) return "2.5" to i
        }
        return null
    }

    private fun convert(toks: List<Tok>): Hit? {
        val c = toks.map { it.canon }
        val unitIdx = c.indices.filter { c[it].startsWith("u_") || c[it].startsWith("c_") }
        if (unitIdx.isEmpty()) return null
        if (!("howmuch" in c || unitIdx.size >= 2)) return null
        if (c.any { it == "sendword" || it == "messages" || it == "whatsapp" || it == "alarm" || it == "remind" }) return null
        val (value, numPos) = numberAt(toks, 0) ?: return null
        val unitAfter = unitIdx.firstOrNull { it > numPos }
        val fromIdx = unitAfter ?: unitIdx.last { it < numPos }
        val toIdx = unitIdx.firstOrNull { it != fromIdx }
        var from = c[fromIdx].substring(2)
        var to = toIdx?.let { c[it].substring(2) }
        // "পাউন্ড" is a weight unless the other side is money
        val money = setOf("usd", "bdt", "eur", "gbp", "inr", "sar", "aed", "jpy", "cny", "myr", "kwd")
        if (from == "lb" && to in money) from = "gbp"
        if (to == "lb" && from in money) to = "gbp"
        if (to == from) to = null
        val slots = linkedMapOf("value" to value, "from" to from)
        if (to != null) slots["to"] = to
        return Hit("convert", slots)
    }
}
