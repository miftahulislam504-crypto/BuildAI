package com.buildx.ai.nlu

/** Bengali and English number words (0-100) -> digits. */
object Numbers {

    private fun n(s: String) = TextNormalizer.nfc(s).lowercase()

    private val bnWords: List<Pair<Int, String>> = listOf(
        0 to "শূন্য|শুন্য", 1 to "এক", 2 to "দুই", 3 to "তিন", 4 to "চার", 5 to "পাঁচ|পাচ",
        6 to "ছয়", 7 to "সাত", 8 to "আট", 9 to "নয়", 10 to "দশ",
        11 to "এগারো|এগার", 12 to "বারো", 13 to "তেরো|তের", 14 to "চৌদ্দ", 15 to "পনেরো|পনের|পনরো",
        16 to "ষোলো|ষোল", 17 to "সতেরো|সতের", 18 to "আঠারো|আঠার", 19 to "উনিশ|ঊনিশ", 20 to "বিশ",
        21 to "একুশ", 22 to "বাইশ", 23 to "তেইশ", 24 to "চব্বিশ", 25 to "পঁচিশ|পচিশ",
        26 to "ছাব্বিশ", 27 to "সাতাশ", 28 to "আঠাশ", 29 to "উনত্রিশ|ঊনত্রিশ", 30 to "ত্রিশ",
        31 to "একত্রিশ", 32 to "বত্রিশ", 33 to "তেত্রিশ", 34 to "চৌত্রিশ", 35 to "পঁয়ত্রিশ|পয়ত্রিশ",
        36 to "ছত্রিশ", 37 to "সাঁইত্রিশ|সাইত্রিশ", 38 to "আটত্রিশ", 39 to "উনচল্লিশ|ঊনচল্লিশ", 40 to "চল্লিশ",
        41 to "একচল্লিশ", 42 to "বিয়াল্লিশ", 43 to "তেতাল্লিশ", 44 to "চুয়াল্লিশ", 45 to "পঁয়তাল্লিশ|পয়তাল্লিশ",
        46 to "ছেচল্লিশ", 47 to "সাতচল্লিশ", 48 to "আটচল্লিশ", 49 to "উনপঞ্চাশ|ঊনপঞ্চাশ", 50 to "পঞ্চাশ",
        51 to "একান্ন", 52 to "বাহান্ন", 53 to "তিপ্পান্ন", 54 to "চুয়ান্ন", 55 to "পঞ্চান্ন",
        56 to "ছাপ্পান্ন", 57 to "সাতান্ন", 58 to "আটান্ন", 59 to "উনষাট|ঊনষাট", 60 to "ষাট",
        61 to "একষট্টি", 62 to "বাষট্টি", 63 to "তেষট্টি", 64 to "চৌষট্টি", 65 to "পঁয়ষট্টি|পয়ষট্টি",
        66 to "ছেষট্টি", 67 to "সাতষট্টি", 68 to "আটষট্টি", 69 to "উনসত্তর|ঊনসত্তর", 70 to "সত্তর",
        71 to "একাত্তর", 72 to "বাহাত্তর", 73 to "তিয়াত্তর", 74 to "চুয়াত্তর", 75 to "পঁচাত্তর|পচাত্তর",
        76 to "ছিয়াত্তর", 77 to "সাতাত্তর", 78 to "আটাত্তর", 79 to "উনআশি|ঊনআশি", 80 to "আশি",
        81 to "একাশি", 82 to "বিরাশি", 83 to "তিরাশি", 84 to "চুরাশি", 85 to "পঁচাশি|পচাশি",
        86 to "ছিয়াশি", 87 to "সাতাশি", 88 to "অষ্টাশি", 89 to "উননব্বই|ঊননব্বই", 90 to "নব্বই",
        91 to "একানব্বই", 92 to "বিরানব্বই", 93 to "তিরানব্বই", 94 to "চুরানব্বই", 95 to "পঁচানব্বই|পচানব্বই",
        96 to "ছিয়ানব্বই", 97 to "সাতানব্বই", 98 to "আটানব্বই", 99 to "নিরানব্বই", 100 to "একশ|একশো|একশত|শত"
    )

    private val enUnitWords = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen", "twenty"
    )
    private val enTensWords = mapOf(
        "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )

    /** Short forms that only count as numbers when a suffix follows: ছটা (6), নটা (9), দুটো (2), বারটা (12). */
    private val contracted: Map<String, Int> = mapOf("ছ" to 6, "ন" to 9, "দু" to 2, "বার" to 12).mapKeys { n(it.key) }

    /** Suffixes Bengali attaches to a number when telling the time: ছয়টা, ছয়টায়, ছয়টার ... (longest first). */
    val suffixes: List<String> = listOf("টাতে", "টায়", "টার", "টা", "টি", "টে", "টো").map { n(it) }

    private val words: Map<String, Int> = HashMap<String, Int>().also { m ->
        for ((v, spellings) in bnWords) for (sp in spellings.split('|')) m[n(sp)] = v
        enUnitWords.forEachIndexed { i, w -> m[w] = i }
        m.putAll(enTensWords)
        m["hundred"] = 100
    }

    val enUnits: Map<String, Int> = (1..9).associate { enUnitWords[it] to it }
    /** twenty..ninety, for "twenty five" (two tokens -> 25) */
    val enTens: Map<String, Int> = enTensWords + ("twenty" to 20)

    /**
     * A single token -> digits, keeping a time suffix if there is one ("ছয়টায়" -> "6টায়").
     * Returns null when the token is not a number word.
     */
    fun parse(w: String): String? {
        words[w]?.let { return it.toString() }
        for (suf in suffixes) {
            if (w.length > suf.length && w.endsWith(suf)) {
                val base = w.dropLast(suf.length)
                words[base]?.let { return it.toString() + suf }
                contracted[base]?.let { return it.toString() + suf }
            }
        }
        return null
    }
}
