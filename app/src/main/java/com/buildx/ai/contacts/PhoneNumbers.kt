package com.buildx.ai.contacts

/** Phone number helpers (Bangladesh-aware but not limited to it). */
object PhoneNumbers {

    /** Digits only, with a leading "+" kept. null if it cannot be a dialable number (letters, *, #, too short/long). */
    fun dialable(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val plus = s.startsWith("+")
        val digits = StringBuilder()
        for (ch in s) {
            when {
                ch.isDigit() -> digits.append(ch)
                ch == '+' && digits.isEmpty() -> {}
                ch == ' ' || ch == '-' || ch == '(' || ch == ')' || ch == '.' || ch == '\u00A0' -> {}
                else -> return null
            }
        }
        var d = digits.toString()
        if (d.length !in 3..15) return null
        if (!plus && d.startsWith("00") && d.length > 5) return "+" + d.substring(2)
        if (!plus && d.startsWith("880") && d.length == 13) return "+$d"
        return if (plus) "+$d" else d
    }

    /** same subscriber, whatever the formatting (+8801711223344 = 01711223344 = 8801711-223344) */
    fun same(a: String, b: String): Boolean {
        val x = a.filter { it.isDigit() }
        val y = b.filter { it.isDigit() }
        if (x.isEmpty() || y.isEmpty()) return false
        if (x.length < 7 || y.length < 7) return x == y
        return x.takeLast(10) == y.takeLast(10)
    }

    private val emergency = setOf("999", "112", "911")

    fun isEmergency(dialable: String): Boolean = dialable.removePrefix("+") in emergency

    /** "০ ১ ৭ ১ ..." so text-to-speech reads digit by digit instead of one huge number */
    fun spell(number: String, bn: Boolean): String =
        number.filter { it.isDigit() }.map { if (bn) '০' + (it - '0') else it }.joinToString(" ")
}
