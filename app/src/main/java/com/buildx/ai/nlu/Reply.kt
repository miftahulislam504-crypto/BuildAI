package com.buildx.ai.nlu

/** Understanding short answers to a question the assistant just asked: yes/no and "which one". */
object Reply {

    enum class YesNo { YES, NO, UNKNOWN }

    private fun set(vararg w: String): Set<String> = w.map { TextNormalizer.normalize(it) }.toSet()

    private val yes = set(
        "হ্যাঁ", "হ্যা", "হাঁ", "হা", "জি", "জ্বি", "ঠিক", "করো", "কর", "করুন", "দাও", "দিন", "অবশ্যই", "ওকে", "অকে",
        "yes", "yeah", "yep", "yup", "ok", "okay", "sure", "go", "haan", "ha"
    )
    private val no = set(
        "না", "নাহ", "নয়", "থাক", "বাদ", "থামো", "থামুন", "ক্যান্সেল", "কোরো",
        "no", "nope", "nah", "dont", "stop", "cancel"
    )

    /** "না" or "no" anywhere wins ("করো না" = don't) */
    fun yesNo(text: String?): YesNo {
        if (text.isNullOrBlank()) return YesNo.UNKNOWN
        val words = TextNormalizer.normalize(text).split(' ')
        if (words.any { it in no }) return YesNo.NO
        if (words.any { it in yes }) return YesNo.YES
        return YesNo.UNKNOWN
    }

    private val ordinals: Map<String, Int> = mapOf(
        "প্রথম" to 1, "১ম" to 1, "1ম" to 1, "first" to 1,
        "দ্বিতীয়" to 2, "২য়" to 2, "2য়" to 2, "second" to 2,
        "তৃতীয়" to 3, "৩য়" to 3, "3য়" to 3, "third" to 3,
        "চতুর্থ" to 4, "৪র্থ" to 4, "4র্থ" to 4, "fourth" to 4,
        "পঞ্চম" to 5, "৫ম" to 5, "5ম" to 5, "fifth" to 5
    ).mapKeys { TextNormalizer.normalize(it.key) }

    private val digitsWithSuffix = Regex("^(\\d{1,2})(?:" + Numbers.suffixes.joinToString("|") + ")?$")
    private val trailing = listOf("টাকে", "টিকে", "টাই", "টা", "টি").map { TextNormalizer.nfc(it) }

    /** which option (0-based) the user chose: "২", "দুই নম্বর", "প্রথমটা", "second", "শেষটা"; null if unclear */
    fun pick(text: String?, count: Int): Int? {
        if (text.isNullOrBlank() || count <= 0) return null
        val norm = TextNormalizer.normalize(text)
        for (t in Canonicalizer.tokenize(norm)) {
            val m = digitsWithSuffix.find(t.canon)
            if (m != null) {
                val n = m.groupValues[1].toInt()
                if (n in 1..count) return n - 1
                continue
            }
            var w = t.raw
            for (suf in trailing) if (w.length > suf.length && w.endsWith(suf)) { w = w.dropLast(suf.length); break }
            ordinals[w]?.let { if (it in 1..count) return it - 1 }
            if (w == "শেষ" || w == "last") return count - 1
        }
        return null
    }
}
