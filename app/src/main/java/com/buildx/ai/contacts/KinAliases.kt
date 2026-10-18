package com.buildx.ai.contacts

/** Family words people use interchangeably: saying "বাবা" should also find a contact saved as "আব্বু" or "Dad". */
object KinAliases {

    private val groups: List<List<String>> = listOf(
        listOf("বাবা", "আব্বু", "আব্বা", "আব্বাজান", "বাপ", "বাপি", "পাপা", "ড্যাড", "ড্যাডি", "dad", "daddy", "father", "baba", "abbu", "abba", "abbo", "papa", "abbajan"),
        listOf("মা", "আম্মু", "আম্মা", "আম্মি", "আম্মাজান", "মাম্মি", "মামনি", "মম", "mom", "mommy", "mother", "mum", "mama", "ammu", "amma", "ammi", "maa"),
        listOf("ভাই", "ভাইয়া", "ভাইজান", "bhai", "bhaiya", "bhaia", "brother", "bro"),
        listOf("আপু", "আপা", "আপি", "বোন", "apu", "apa", "api", "sister", "sis"),
        listOf("দাদা", "দাদু", "দাদাজান", "grandpa", "dada", "dadu"),
        listOf("নানা", "নানু", "নানাভাই", "nana", "nanu"),
        listOf("দাদি", "ঠাকুমা", "dadi"),
        listOf("নানি", "নানী", "nani"),
        listOf("বউ", "বৌ", "স্ত্রী", "wife", "bou"),
        listOf("স্বামী", "husband")
    )

    private fun key(s: String) = com.buildx.ai.nlu.TextNormalizer.normalize(s)

    private val index: Map<String, List<String>> = HashMap<String, List<String>>().also { m ->
        for (g in groups) for (w in g) m[key(w)] = g.map { key(it) }.filter { it != key(w) }
    }

    /** other words meaning the same family member (empty if [word] is not a family word) */
    fun siblingsOf(word: String): List<String> = index[key(word)] ?: emptyList()
}
