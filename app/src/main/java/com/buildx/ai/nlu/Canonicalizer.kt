package com.buildx.ai.nlu

/** [raw] = what was said (kept so names survive), [canon] = the canonical meaning token (digits for numbers). */
data class Tok(val raw: String, val canon: String)

/**
 * Step 2: normalized text -> list of tokens.
 * Per position: longest phrase from the lexicon, else lexicon word (suffix-tolerant), else number word,
 * else typo-tolerant Latin keyword, else the word itself (names, unknown apps).
 */
object Canonicalizer {

    private val digitsOnly = Regex("^\\d+$")
    private val suffixTokens: Set<String> = (Numbers.suffixes + "oclock").toSet()
    private val defaultSuffix = Numbers.suffixes.first { it == TextNormalizer.nfc("টা") }

    fun tokenize(normalized: String): List<Tok> {
        val w = normalized.split(' ').filter { it.isNotEmpty() }
        val toks = ArrayList<Tok>()
        var i = 0
        while (i < w.size) {
            // 1. longest phrase
            var matched = false
            var len = minOf(Lexicon.maxPhraseLen, w.size - i)
            while (len >= 1) {
                val canon = Lexicon.phrases[w.subList(i, i + len)]
                if (canon != null) {
                    toks.add(Tok(w.subList(i, i + len).joinToString(" "), canon))
                    i += len
                    matched = true
                    break
                }
                len--
            }
            if (matched) continue

            val t = w[i]
            // 2. english "twenty five"
            val tens = Numbers.enTens[t]
            val unit = if (i + 1 < w.size) Numbers.enUnits[w[i + 1]] else null
            if (tens != null && unit != null) {
                toks.add(Tok("$t ${w[i + 1]}", (tens + unit).toString()))
                i += 2
                continue
            }
            // 3. lexicon word with Bengali suffix, 4. number word, 5. typo-tolerant Latin word, 6. unknown
            val canon = Lexicon.lookupWord(t) ?: Numbers.parse(t) ?: Lexicon.fuzzyWord(t) ?: t
            toks.add(Tok(t, canon))
            i++
        }
        return mergeHourSuffix(toks)
    }

    /** "6" + "টায়" / "6" + "oclock" -> one token "6টায়" / "6টা" */
    private fun mergeHourSuffix(toks: List<Tok>): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            val next = toks.getOrNull(i + 1)
            if (next != null && digitsOnly.matches(t.canon) && next.raw in suffixTokens) {
                val suf = if (next.raw == "oclock") defaultSuffix else next.raw
                out.add(Tok(t.raw + " " + next.raw, t.canon + suf))
                i += 2
            } else {
                out.add(t)
                i++
            }
        }
        return out
    }
}
