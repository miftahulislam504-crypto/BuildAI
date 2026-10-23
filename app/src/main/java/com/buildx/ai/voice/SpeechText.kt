package com.buildx.ai.voice

/**
 * Phase 5 (speaking): turns a reply into something a TTS voice reads well, and cuts long text into sentences so no
 * engine truncates it. Pure: no Android classes.
 */
object SpeechText {

    const val MAX_CHUNK = 300

    /** arrows, emoji-like marks, markdown and repeated punctuation make engines stumble or spell them out */
    fun prepare(text: String): String {
        var t = text
        t = t.replace("→", ", ").replace("•", ", ").replace("—", ", ").replace("–", ", ")
        t = t.replace(Regex("[\\u2190-\\u21FF\\u2600-\\u27BF\\u2B00-\\u2BFF\\uD83C-\\uDBFF\\uDC00-\\uDFFF]"), " ")
        t = t.replace(Regex("[*_`#]+"), "")
        t = t.replace(Regex("\\.{2,}|…"), ".")
        t = t.replace(Regex("([!?।.,])\\1+"), "$1")
        t = t.replace(Regex("[ \\t]+"), " ")
        t = t.replace(Regex(" ?\\n ?"), ". ")
        t = t.replace(Regex("\\s+([,.!?।])"), "$1")
        return t.trim().trim(',')
    }

    /** sentences in order, none longer than [max]; short neighbours are joined so the voice does not stop after every few words */
    fun chunks(text: String, max: Int = MAX_CHUNK): List<String> {
        val clean = prepare(text)
        if (clean.isEmpty()) return emptyList()
        val sentences = ArrayList<String>()
        val sb = StringBuilder()
        for (c in clean) {
            sb.append(c)
            if (c == '।' || c == '.' || c == '!' || c == '?') {
                sentences.add(sb.toString().trim()); sb.setLength(0)
            }
        }
        if (sb.isNotBlank()) sentences.add(sb.toString().trim())

        val parts = ArrayList<String>()
        for (s in sentences) parts.addAll(splitLong(s, max))

        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (p in parts) {
            if (cur.isNotEmpty() && cur.length + 1 + p.length > max) { out.add(cur.toString()); cur.setLength(0) }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(p)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun splitLong(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val out = ArrayList<String>()
        var rest = s
        while (rest.length > max) {
            var cut = rest.lastIndexOf(',', max).takeIf { it > max / 3 } ?: rest.lastIndexOf(' ', max)
            if (cut <= 0) cut = max
            out.add(rest.substring(0, cut).trim().trimEnd(','))
            rest = rest.substring(cut).trim().trimStart(',').trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    /** speed and pitch are stored as percent (100 = normal); engines misbehave outside this range */
    fun rate(percent: Int): Float = percent.coerceIn(60, 150) / 100f
    fun pitch(percent: Int): Float = percent.coerceIn(70, 130) / 100f
}
