package com.buildx.ai.nlu

/**
 * Time expressions on canonical tokens (see [Canonicalizer]):
 *   সকাল ৬টা / ৬টা ৩০ মিনিটে / সাড়ে ছয়টা / সওয়া সাতটা / পৌনে আটটা / দেড়টা / আড়াইটা /
 *   6:30 pm / 6 am / at 6 / রাত ১০টা   and relative ones: ১০ মিনিট পরে / আধা ঘণ্টা / দেড় ঘণ্টা.
 */
object TimeParser {

    /** [ambiguous] = no am/pm/period was given, so [hour] is as spoken (1-12) and the caller picks the next occurrence. */
    data class Clock(val hour: Int, val minute: Int, val ambiguous: Boolean)

    private val hourMark = Regex("^(\\d{1,2})(" + Numbers.suffixes.joinToString("|") + ")$")
    private val digits = Regex("^\\d{1,2}$")
    private val digits3 = Regex("^\\d{1,3}$")
    private val periods = setOf("am", "pm", "morning", "dawn", "noon", "afternoon", "evening", "night")
    private val der = TextNormalizer.nfc("দেড়")
    private val arai = TextNormalizer.nfc("আড়াই")
    private val plainTa = TextNormalizer.nfc("টা")

    private fun hourOf(t: String?): Int? {
        if (t == null) return null
        hourMark.find(t)?.let { return it.groupValues[1].toInt() }
        if (digits.matches(t)) return t.toInt()
        return null
    }

    private fun minuteOf(t: String?): Int? = if (t != null && digits.matches(t) && t.toInt() in 0..59) t.toInt() else null

    /** index of the first bare number that is not a quantity ("10 মিনিট", "50 percent") */
    private fun bareDigitIndex(c: List<String>): Int =
        c.indices.firstOrNull { digits.matches(c[it]) && c.getOrNull(it + 1) !in setOf("minute", "hour", "percent") } ?: -1

    fun clock(c: List<String>, allowFallback: Boolean): Clock? {
        val period = c.firstOrNull { it in periods }
        var h: Int? = null
        var m = 0

        // 1. সাড়ে / সওয়া / পৌনে  (+ English half past / quarter past / quarter to)
        for (i in c.indices) {
            val n = hourOf(c.getOrNull(i + 1)) ?: continue
            when (c[i]) {
                "half_past" -> { h = n; m = 30 }
                "quarter_past" -> { h = n; m = 15 }
                "quarter_to" -> { h = if (n <= 1) 12 else n - 1; m = 45 }
            }
            if (h != null) break
        }
        // 2. দেড়টা / আড়াইটা (but "দেড় ঘণ্টা" is a duration)
        if (h == null) {
            for (i in c.indices) {
                if (c.getOrNull(i + 1) == "hour") continue
                if (c[i].startsWith(der)) { h = 1; m = 30; break }
                if (c[i].startsWith(arai)) { h = 2; m = 30; break }
            }
        }
        // 3. "6টা", "6টায়" (+ minutes after it)
        if (h == null) {
            for (i in c.indices) {
                val mm = hourMark.find(c[i]) ?: continue
                // "একটা অ্যালার্ম দাও" = "an alarm", not 1 o'clock
                if (mm.groupValues[1] == "1" && mm.groupValues[2] == plainTa && c.getOrNull(i + 1) == "alarm") continue
                h = mm.groupValues[1].toInt()
                minuteOf(c.getOrNull(i + 1))?.let { m = it }
                break
            }
        }
        // 4. "6 am", "6 30 pm"
        if (h == null) {
            val j = c.indexOfFirst { it == "am" || it == "pm" }
            if (j > 0 && digits.matches(c[j - 1])) {
                val a = c[j - 1].toInt()
                val b = c.getOrNull(j - 2)
                if (b != null && digits.matches(b) && b.toInt() in 1..12 && a in 0..59) {
                    h = b.toInt(); m = a
                } else {
                    h = a
                }
            }
        }
        // 5. "সকাল 6", "at 6", "alarm 6 30"
        if (h == null && (period != null || allowFallback)) {
            val j = bareDigitIndex(c)
            if (j >= 0 && c[j].toInt() <= 23) {
                h = c[j].toInt()
                minuteOf(c.getOrNull(j + 1))?.let { m = it }
            }
        }

        val hh = h ?: return null
        if (hh !in 0..23 || m !in 0..59) return null
        val (h24, ambiguous) = resolve(hh, period)
        return Clock(h24, m, ambiguous)
    }

    private fun resolve(h: Int, period: String?): Pair<Int, Boolean> {
        if (h >= 13 || h == 0) return Pair(h, false)
        return when (period) {
            "am", "morning", "dawn" -> Pair(h % 12, false)
            "pm" -> Pair(h % 12 + 12, false)
            "noon" -> Pair(if (h == 12 || h in 10..11) h else h + 12, false)
            "afternoon", "evening" -> Pair(if (h == 12) 12 else h + 12, false)
            "night" -> Pair(if (h == 12) 0 else if (h in 6..11) h + 12 else h, false)
            else -> Pair(h, true)
        }
    }

    /** total minutes for "১০ মিনিট", "১ ঘণ্টা ৩০ মিনিট", "আধা ঘণ্টা", "দেড় ঘণ্টা"; null if no duration */
    fun relativeMinutes(c: List<String>): Int? {
        var total = 0
        var found = false
        var i = 0
        while (i < c.size) {
            val t = c[i]
            val unit = c.getOrNull(i + 1)
            val perUnit = if (unit == "hour") 60 else if (unit == "minute") 1 else 0
            when {
                perUnit > 0 && digits3.matches(t) -> { total += t.toInt() * perUnit; found = true; i += 2 }
                unit == "hour" && t == "half" -> { total += 30; found = true; i += 2 }
                unit == "hour" && t.startsWith(der) -> { total += 90; found = true; i += 2 }
                unit == "hour" && t.startsWith(arai) -> { total += 150; found = true; i += 2 }
                else -> i++
            }
        }
        return if (found) total else null
    }
}
