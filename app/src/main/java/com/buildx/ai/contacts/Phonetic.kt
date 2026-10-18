package com.buildx.ai.contacts

/**
 * Bengali <-> English spelling of the same name ("রহিম" / "Rahim") compared through a consonant skeleton.
 * Both spellings are reduced to the same short key: রহিম -> "rhm", Rahim -> "rhm".
 * Pure Kotlin, no Android classes.
 */
object Phonetic {

    private val bnVowels = mapOf(
        'অ' to "o", 'আ' to "a", 'ই' to "i", 'ঈ' to "i", 'উ' to "u", 'ঊ' to "u", 'ঋ' to "ri",
        'এ' to "e", 'ঐ' to "oi", 'ও' to "o", 'ঔ' to "ou",
        'া' to "a", 'ি' to "i", 'ী' to "i", 'ু' to "u", 'ূ' to "u", 'ৃ' to "ri",
        'ে' to "e", 'ৈ' to "oi", 'ো' to "o", 'ৌ' to "ou"
    )
    private val bnConsonants = mapOf(
        'ক' to "k", 'খ' to "kh", 'গ' to "g", 'ঘ' to "gh", 'ঙ' to "ng",
        'চ' to "ch", 'ছ' to "ch", 'জ' to "j", 'ঝ' to "jh", 'ঞ' to "n",
        'ট' to "t", 'ঠ' to "th", 'ড' to "d", 'ঢ' to "dh", 'ণ' to "n",
        'ত' to "t", 'থ' to "th", 'দ' to "d", 'ধ' to "dh", 'ন' to "n",
        'প' to "p", 'ফ' to "f", 'ব' to "b", 'ভ' to "bh", 'ম' to "m",
        'য' to "j", 'র' to "r", 'ল' to "l", 'শ' to "sh", 'ষ' to "sh", 'স' to "s", 'হ' to "h",
        'ৎ' to "t", 'ং' to "ng", 'ঃ' to "h"
    )
    private const val NUKTA = '\u09BC'
    private const val VIRAMA = '\u09CD'

    /** Rough Latin spelling. Latin input passes through (lower-cased). Vowels are kept so similarity can use them. */
    fun romanize(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val next = if (i + 1 < s.length) s[i + 1] else ' '
            when {
                next == NUKTA && (ch == 'ড' || ch == 'ঢ') -> { sb.append('r'); i++ }
                next == NUKTA && ch == 'য' -> { sb.append('y'); i++ }
                ch == VIRAMA || ch == NUKTA || ch == '\u0981' -> {}
                bnVowels.containsKey(ch) -> sb.append(bnVowels[ch])
                bnConsonants.containsKey(ch) -> sb.append(bnConsonants[ch])
                ch in 'a'..'z' || ch in 'A'..'Z' -> sb.append(ch.lowercaseChar())
                else -> {}
            }
            i++
        }
        return sb.toString()
    }

    /** consonant skeleton of ONE word */
    fun skeleton(word: String): String {
        var r = romanize(word)
        r = r.replace("ch", "#").replace("sh", "s").replace("kh", "k").replace("gh", "g")
            .replace("th", "t").replace("dh", "d").replace("ph", "f").replace("bh", "b")
            .replace("jh", "j").replace("zh", "j")
        r = r.replace('c', 'k').replace('q', 'k').replace('z', 'j').replace('v', 'b').replace("x", "ks")
            .replace('#', 'c')
        val sb = StringBuilder()
        for (ch in r) {
            if (ch in "aeiouyw") continue
            if (sb.isNotEmpty() && sb.last() == ch) continue
            sb.append(ch)
        }
        return sb.toString()
    }
}
