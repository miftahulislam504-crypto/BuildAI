package com.buildx.ai.nlu

import java.text.Normalizer

/**
 * Step 1 of understanding a command: turn whatever the speech engine produced into one clean,
 * predictable string. Pure Kotlin (no Android classes) so it can be unit-tested on the JVM.
 *
 * - Unicode NFC (Bengali "য়" etc. can arrive in different forms; NFC makes them identical)
 * - lower case, Bengali digits -> ASCII digits, zero-width joiners removed
 * - "6:30" -> "6টা 30", "%" -> " percent ", "6pm" -> "6 pm", apostrophes removed (o'clock, ক'টা)
 * - everything else that is not a letter/digit becomes a space; whitespace collapsed
 */
object TextNormalizer {

    fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    private val clockColon = Regex("(\\d{1,2}):(\\d{2})")
    private val digitLetterEdge = Regex("(?<=\\d)(?=[a-z])|(?<=[a-z])(?=\\d)")
    private val loosePlus = Regex("\\+(?!\\d)")
    private val spaces = Regex("\\s+")

    fun normalize(input: String): String {
        val src = nfc(input).lowercase()
        val sb = StringBuilder(src.length + 8)
        for (ch in src) {
            when {
                ch == '\u200c' || ch == '\u200d' -> {} // zero-width (non-)joiner
                ch == '\'' || ch == '\u2019' || ch == '`' -> {} // o'clock, ক'টা -> joined
                ch == '%' -> sb.append(" percent ")
                ch in '০'..'৯' -> sb.append('0' + (ch - '০'))
                ch == '+' || ch == ':' -> sb.append(ch)
                ch in '\u0980'..'\u09FF' -> sb.append(ch) // Bengali block: letters, vowel signs, virama, nukta
                ch.isLetterOrDigit() -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        var s = sb.toString()
        s = clockColon.replace(s) { "${it.groupValues[1]}টা ${it.groupValues[2]}" }
        s = s.replace(':', ' ')
        s = loosePlus.replace(s, " plus ")
        s = digitLetterEdge.replace(s, " ")
        return s.trim().replace(spaces, " ")
    }
}
