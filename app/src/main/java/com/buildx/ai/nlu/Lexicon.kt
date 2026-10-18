package com.buildx.ai.nlu

/**
 * Word tables. Many Bengali / English / mixed spellings map to ONE canonical Latin token
 * (e.g. টর্চ, ফ্ল্যাশ লাইট, torch -> "flashlight"). Aliases may be several words; the longest match wins.
 *
 * This is the part that grows: copy the app log, look at the `STT "..."` lines that were misheard
 * or not understood, and add the spelling here.
 */
object Lexicon {

    /** canonical token -> aliases separated by '|' */
    private val groups: List<Pair<String, String>> = listOf(
        // ---- calls ----
        "call" to "কল|call|ডায়াল|dial|ফোন করো|ফোন কর|ফোন করুন|ফোন করে দাও|ফোন করে দিন|ফোন করে|ফোন দাও|ফোন দিন|ফোন লাগাও|ফোন লাগান|ফোন করবে|ফোন করেন",
        "phone" to "ফোন|phone|মোবাইল|mobile|হ্যান্ডসেট",
        "number" to "নম্বর|নাম্বার|নং|number",
        "hangup" to "hang up|hangup|hang",
        "cut" to "কেটে|কাটো|কাট|কাটুন|কাটেন|cut|disconnect|end",
        // ---- alarm / time ----
        "alarm" to "অ্যালার্ম|এ্যালার্ম|এলার্ম|আলার্ম|অ্যালারাম|এলারাম|অ্যালারম|alarm|টাইমার|timer",
        "wakeme" to "ঘুম থেকে তুলে দিও|ঘুম থেকে ডেকে দিও|তুলে দিও|ডেকে দিও|তুলে দাও|ডেকে দাও|তুলে দিন|ডেকে দিন",
        "whattime" to "কয়টা বাজে|কটা বাজে|কত বাজে|কয়টা|কটা|সময় কত|সময়|টাইম|time|what time|whats the time|time now",
        "tomorrow" to "আগামীকাল|আগামী কাল|কাল|কালকে|tomorrow|পরদিন",
        "today" to "আজ|আজকে|আজকের|today",
        "dayafter" to "পরশু|পরশুদিন|day after tomorrow",
        "minute" to "মিনিট|minute|minutes|min|mins",
        "hour" to "ঘণ্টা|ঘন্টা|hour|hours|hr|hrs",
        "half" to "আধা|আধ|half",
        "half_past" to "সাড়ে|half past",
        "quarter_past" to "সওয়া|সোয়া|quarter past",
        "quarter_to" to "পৌনে|quarter to",
        "am" to "am|a m",
        "pm" to "pm|p m",
        "morning" to "সকাল|morning",
        "dawn" to "ভোর|dawn",
        "noon" to "দুপুর|noon",
        "afternoon" to "বিকাল|বিকেল|afternoon",
        "evening" to "সন্ধ্যা|সন্ধ্যে|evening",
        "night" to "রাত|রাত্রি|night|tonight",
        // ---- screen ----
        "wake" to "জাগাও|জাগান|জাগিয়ে|জাগিও|জাগো|wake|wake up",
        "screen" to "স্ক্রিন|স্ক্রীন|স্ক্রিণ|screen|ডিসপ্লে|display",
        "lock" to "লক|lock|লকড",
        "unlock" to "আনলক|unlock",
        "show" to "দেখাও|দেখান|দেখি|show",
        // ---- things ----
        "flashlight" to "ফ্ল্যাশলাইট|ফ্ল্যাশ লাইট|ফ্ল্যাশ|ফ্লাশলাইট|ফ্লাশ লাইট|ফ্লাশ|ফ্লেশলাইট|টর্চ|টর্চলাইট|টর্চ লাইট|torch|torchlight|torch light|flashlight|flash light|flash|লাইট|light|আলো",
        "wifi" to "ওয়াইফাই|ওয়াই ফাই|wifi|wi fi",
        "bluetooth" to "ব্লুটুথ|ব্লু টুথ|ব্লুটূথ|bluetooth|blue tooth",
        "settings" to "সেটিংস|সেটিং|settings|setting|প্যানেল|panel",
        // ---- volume ----
        "volume" to "ভলিউম|ভল্যুম|ভলুম|volume|সাউন্ড|sound|আওয়াজ|শব্দ",
        "mute" to "মিউট|mute|সাইলেন্ট|silent|নিঃশব্দ",
        "unmute" to "আনমিউট|unmute",
        "louder" to "louder",
        "quieter" to "quieter|softer",
        "up" to "বাড়াও|বাড়ান|বাড়িয়ে|বাড়া|বাড়ানো|বেশি|বেশী|জোরে|জোর|up|increase|raise|higher|more",
        "down" to "কমাও|কমান|কমিয়ে|কমা|কমানো|কম|আস্তে|ধীরে|down|decrease|lower|less|reduce",
        "max" to "ফুল|full|max|maximum|সর্বোচ্চ|ম্যাক্সিমাম|ম্যাক্স",
        "min" to "মিনিমাম|minimum|সর্বনিম্ন",
        "percent" to "percent|পার্সেন্ট|পারসেন্ট|শতাংশ",
        // ---- on / off / open ----
        "on" to "চালু|চালাও|চালান|চালিয়ে|অন|on|জ্বালাও|জ্বালান|জ্বালিয়ে|জ্বেলে|জ্বলাও|জ্বালো|enable|start|স্টার্ট|শুরু",
        "off" to "বন্ধ|অফ|off|নেভাও|নেভান|নিভাও|নিভান|নিভিয়ে|নেভানো|disable",
        "open" to "খোলো|খোল|খুলুন|খুলে|খুল|খুলো|খুলবে|ওপেন|open|launch|লঞ্চ",
        "toggle" to "টগল|toggle",
        "cancel" to "ক্যান্সেল|cancel|থাক|থাক থাক|বাদ দাও|বাদ দিন|কিছু না|না থাক|থামো|থামুন|থামাও|stop|স্টপ|never mind|nevermind|forget it|ভুলে যাও",
        "app" to "অ্যাপ|এ্যাপ|এপ|app|অ্যাপ্লিকেশন|application",
        // ---- apps (canonical token = app key) ----
        "youtube" to "ইউটিউব|ইউটুব|ইউ টিউব|youtube|you tube",
        "whatsapp" to "হোয়াটসঅ্যাপ|হোয়াটসএপ|হোয়াটস অ্যাপ|হোয়াটস এপ|হোয়াটস্যাপ|whatsapp|whats app",
        "facebook" to "ফেসবুক|ফেইসবুক|ফেস বুক|facebook|face book|fb",
        "messenger" to "মেসেঞ্জার|messenger",
        "chrome" to "ক্রোম|গুগল ক্রোম|chrome|google chrome|ব্রাউজার|browser",
        "camera" to "ক্যামেরা|camera",
        "gallery" to "গ্যালারি|গ্যালারী|gallery|ফটো|photos|photo",
        "calculator" to "ক্যালকুলেটর|ক্যালকুলেটার|calculator",
        "maps" to "ম্যাপ|ম্যাপস|গুগল ম্যাপ|গুগল ম্যাপস|মানচিত্র|maps|map|google maps",
        "gmail" to "জিমেইল|জি মেইল|gmail",
        "playstore" to "প্লে স্টোর|প্লেস্টোর|গুগল প্লে|play store|playstore",
        "dialer" to "ডায়ালার|dialer",
        "messages" to "মেসেজ|মেসেজেস|messages|message|এসএমএস|sms",
        "clock" to "ঘড়ি|ক্লক|clock",
        "calendar" to "ক্যালেন্ডার|calendar",
        "telegram" to "টেলিগ্রাম|telegram",
        "instagram" to "ইনস্টাগ্রাম|ইন্সটাগ্রাম|ইনস্টা|instagram|insta",
        "bkash" to "বিকাশ|bkash",
        "nagad" to "নগদ|nagad",
        "spotify" to "স্পটিফাই|spotify",
        "contacts" to "কন্টাক্টস|কন্টাক্ট|contacts|contact|ফোনবুক",
        "files" to "ফাইল|ফাইলস|ফাইল ম্যানেজার|files|file manager",
        // ---- filler / do-verbs / "and then": carry no meaning of their own, but mark where one command ends ----
        "_v" to "করো|কর|করুন|করেন|করবে|করে|করতে|করি|দাও|দিন|দে|দিয়ে|দিও|দেবে|দেন|লাগাও|লাগান",
        "_f" to "প্লিজ|please|plz|একটু|আমাকে|আমার|আমি|তুমি|আপনি|এখন|now|কে|এ|এবার|তো|ওই|এই|the|a|an|to|my|me|can|you|could|would|set|সেট|at|for|of|it|hey|হে",
        "sep" to "আর|এবং|তারপর|তারপরে|এরপর|এরপরে|then|and|also"
    )

    /** alias words -> canonical, for aliases of any length (longest-match phrases) */
    val phrases: Map<List<String>, String>
    val maxPhraseLen: Int
    /** only the one-word aliases: used for suffix stripping and typo matching */
    val single: Map<String, String>
    /** app key tokens ("youtube", "camera", ...) */
    val appKeys: Set<String>
    /** ASCII keywords long enough for 1-2 typo tolerance */
    val fuzzyKeys: List<String>
    /** aliases claimed by two different canonical tokens; must stay empty (checked by a unit test) */
    val duplicates: List<String>

    private val suffixes: List<String> = listOf(
        "গুলোকে", "গুলোর", "গুলো", "গুলি", "টাকে", "টিকে", "টার", "টির", "টায়", "টাতে", "টিতে", "টা", "টি",
        "য়ের", "য়ে", "ের", "কে", "তে", "র", "ে", "য়"
    ).map { TextNormalizer.nfc(it) }

    init {
        val p = HashMap<List<String>, String>()
        val dup = ArrayList<String>()
        var maxLen = 1
        for ((canon, aliases) in groups) {
            for (alias in aliases.split('|')) {
                val key = TextNormalizer.normalize(alias).split(' ').filter { it.isNotEmpty() }
                if (key.isEmpty()) continue
                val old = p[key]
                if (old != null && old != canon) dup.add("${key.joinToString(" ")}: $old / $canon")
                p[key] = canon
                if (key.size > maxLen) maxLen = key.size
            }
        }
        phrases = p
        maxPhraseLen = maxLen
        single = p.filterKeys { it.size == 1 }.mapKeys { it.key[0] }
        appKeys = setOf(
            "youtube", "whatsapp", "facebook", "messenger", "chrome", "camera", "gallery", "calculator", "maps",
            "gmail", "playstore", "dialer", "messages", "clock", "calendar", "telegram", "instagram", "bkash",
            "nagad", "spotify", "contacts", "files", "settings"
        )
        fuzzyKeys = single.keys.filter { k -> k.length >= 6 && k.all { it in 'a'..'z' } }
        duplicates = dup
    }

    /** exact one-word match, else the word with a Bengali suffix removed (ফোনটা, ফোনে, বাবাকে ...) */
    fun lookupWord(w: String): String? {
        single[w]?.let { return it }
        for (suf in suffixes) {
            if (w.length > suf.length && w.endsWith(suf)) {
                single[w.dropLast(suf.length)]?.let { return it }
            }
        }
        return null
    }

    /** typo tolerance for long Latin words only (flashligt -> flashlight) */
    fun fuzzyWord(w: String): String? {
        if (w.length < 6 || !w.all { it in 'a'..'z' }) return null
        val key = Fuzzy.closest(w, fuzzyKeys, if (w.length >= 8) 2 else 1) ?: return null
        return single[key]
    }
}
