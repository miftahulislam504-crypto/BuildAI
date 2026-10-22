package com.buildx.ai.system

import java.text.Normalizer

/**
 * A spoken place -> the Latin name the weather geocoder understands. Bangladesh's 64 districts and a few cities people
 * ask about are built in; anything else is passed on as said (the geocoder copes with many Bengali spellings itself).
 * No Android classes.
 */
object Places {

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

    private val raw: Map<String, String> = mapOf(
        "ঢাকা" to "Dhaka", "ফরিদপুর" to "Faridpur", "গাজীপুর" to "Gazipur", "গোপালগঞ্জ" to "Gopalganj", "কিশোরগঞ্জ" to "Kishoreganj",
        "মাদারীপুর" to "Madaripur", "মানিকগঞ্জ" to "Manikganj", "মুন্সিগঞ্জ" to "Munshiganj", "নারায়ণগঞ্জ" to "Narayanganj",
        "নরসিংদী" to "Narsingdi", "রাজবাড়ী" to "Rajbari", "শরীয়তপুর" to "Shariatpur", "টাঙ্গাইল" to "Tangail",
        "চট্টগ্রাম" to "Chittagong", "বান্দরবান" to "Bandarban", "ব্রাহ্মণবাড়িয়া" to "Brahmanbaria", "চাঁদপুর" to "Chandpur",
        "কুমিল্লা" to "Comilla", "কক্সবাজার" to "Cox's Bazar", "ফেনী" to "Feni", "খাগড়াছড়ি" to "Khagrachhari",
        "লক্ষ্মীপুর" to "Lakshmipur", "নোয়াখালী" to "Noakhali", "রাঙ্গামাটি" to "Rangamati", "বরিশাল" to "Barisal",
        "বরগুনা" to "Barguna", "ভোলা" to "Bhola", "ঝালকাঠি" to "Jhalokati", "পটুয়াখালী" to "Patuakhali", "পিরোজপুর" to "Pirojpur",
        "খুলনা" to "Khulna", "বাগেরহাট" to "Bagerhat", "চুয়াডাঙ্গা" to "Chuadanga", "যশোর" to "Jessore", "ঝিনাইদহ" to "Jhenaidah",
        "কুষ্টিয়া" to "Kushtia", "মাগুরা" to "Magura", "মেহেরপুর" to "Meherpur", "নড়াইল" to "Narail", "সাতক্ষীরা" to "Satkhira",
        "জামালপুর" to "Jamalpur", "ময়মনসিংহ" to "Mymensingh", "নেত্রকোনা" to "Netrokona", "শেরপুর" to "Sherpur",
        "বগুড়া" to "Bogra", "জয়পুরহাট" to "Joypurhat", "নওগাঁ" to "Naogaon", "নাটোর" to "Natore", "নবাবগঞ্জ" to "Chapai Nawabganj",
        "পাবনা" to "Pabna", "রাজশাহী" to "Rajshahi", "সিরাজগঞ্জ" to "Sirajganj", "দিনাজপুর" to "Dinajpur", "গাইবান্ধা" to "Gaibandha",
        "কুড়িগ্রাম" to "Kurigram", "লালমনিরহাট" to "Lalmonirhat", "নীলফামারী" to "Nilphamari", "পঞ্চগড়" to "Panchagarh",
        "রংপুর" to "Rangpur", "ঠাকুরগাঁও" to "Thakurgaon", "হবিগঞ্জ" to "Habiganj", "মৌলভীবাজার" to "Moulvibazar",
        "সুনামগঞ্জ" to "Sunamganj", "সিলেট" to "Sylhet",
        // outside Bangladesh
        "কলকাতা" to "Kolkata", "দিল্লি" to "Delhi", "মুম্বাই" to "Mumbai", "করাচি" to "Karachi", "দুবাই" to "Dubai",
        "রিয়াদ" to "Riyadh", "জেদ্দা" to "Jeddah", "মক্কা" to "Mecca", "মদিনা" to "Medina", "কুয়ালালামপুর" to "Kuala Lumpur",
        "সিঙ্গাপুর" to "Singapore", "লন্ডন" to "London", "নিউ ইয়র্ক" to "New York", "টোকিও" to "Tokyo", "দোহা" to "Doha",
        "কুয়েত" to "Kuwait City", "মাস্কাট" to "Muscat", "ইস্তানবুল" to "Istanbul"
    )

    private val map: Map<String, String> = raw.mapKeys { nfc(it.key) }

    private val suffixes = listOf("ের", "র", "য়", "ে", "তে", "এ").map { nfc(it) }

    /** the plain form of a spoken place ("ঢাকার" -> "ঢাকা"), for saying it back; unknown places stay as said */
    fun base(spoken: String): String {
        val s = nfc(spoken).trim()
        if (map.containsKey(s)) return s
        for (suf in suffixes) {
            if (s.length > suf.length && s.endsWith(suf) && map.containsKey(s.dropLast(suf.length))) return s.dropLast(suf.length)
        }
        return s
    }

    /** the Latin name for [spoken], or [spoken] itself (trimmed) when it is not a known place */
    fun english(spoken: String): String {
        val s = nfc(spoken).trim()
        if (s.isEmpty()) return s
        map[s]?.let { return it }
        for (suf in suffixes) {
            if (s.length > suf.length && s.endsWith(suf)) map[s.dropLast(suf.length)]?.let { return it }
        }
        return s
    }
}
