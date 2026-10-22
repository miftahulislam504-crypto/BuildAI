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
        // ---- photo / video ----
        "takephoto" to "ছবি তুলে|ফটো তুলে|ছবি তোলো|ছবি তুলো|ছবি তুলুন|ছবি তোল|ছবি তুলে দাও|ছবি তুলে দিন|ছবি তুলবে|ছবি নাও|ছবি নিন|ছবি ক্লিক|ফটো তোলো|ফটো তুলো|ফটো তুলুন|ফটো তোল|ফটো নাও|take photo|take a photo|take the photo|take picture|take a picture|take the picture|take pic|click photo|click a photo|click picture|capture photo|capture a photo|snap a photo",
        "selfie" to "সেলফি|selfie",
        "front" to "ফ্রন্ট|ফ্রন্টের|front|সামনের",
        "video" to "ভিডিও|video",
        // ---- media, scrolling, everyday things ----
        "song" to "গান|গানটা|গানটি|গানগুলো|song|songs|মিউজিক|music",
        "play" to "বাজাও|বাজান|শোনাও|শোনান|প্লে|play",
        "mediaplay" to "গান চালু করো|গান চালু|resume|আবার চালু করো|মিউজিক চালু করো|ভিডিও চালু করো|continue playing",
        "mediapause" to "গান থামাও|গান বন্ধ করো|গান বন্ধ|মিউজিক থামাও|মিউজিক বন্ধ করো|ভিডিও থামাও|ভিডিও পজ|pause|পজ|পজ করো|pause music|stop music|stop the music",
        "medianext" to "পরের গান|পরের গানটা|পরের গানে যাও|next song|next track|skip song|skip",
        "mediaprev" to "আগের গান|আগের গানটা|আগের গানে যাও|previous song|previous track|prev song|prev track",
        "reelnext" to "পরের রিল|পরের রিলস|পরের শর্টস|পরের শর্ট|পরের ভিডিও|পরেরটা|next reel|next reels|next short|next shorts|next video|স্ক্রল করো|স্ক্রল|নিচে স্ক্রল করো|স্ক্রল ডাউন|scroll down|scroll|swipe up|সোয়াইপ আপ",
        "reelprev" to "আগের রিল|আগের রিলস|আগের শর্টস|আগের শর্ট|আগের ভিডিও|আগেরটা|previous reel|prev reel|previous short|উপরে স্ক্রল করো|স্ক্রল আপ|scroll up|swipe down|সোয়াইপ ডাউন",
        "shorts" to "শর্টস|শর্ট|shorts|short",
        "reels" to "রিলস|রিল|reels|reel",
        "google" to "গুগল|google",
        "search" to "সার্চ|search|খুঁজো|খুঁজে|খুঁজুন|খোঁজো|খোঁজ",
        "navigate" to "নেভিগেট|navigate|navigation|রাস্তা|রাস্তাটা|রুট|route|directions|ডিরেকশন|দিকনির্দেশ",
        "battery" to "ব্যাটারি|battery|চার্জ|charge",
        "askdate" to "কত তারিখ|তারিখ|কী বার|কি বার|কোন বার|বার কত|what date|todays date|date today|date|what day|day today",
        "brightness" to "ব্রাইটনেস|ব্রাইট|brightness|উজ্জ্বলতা",
        "silentmode" to "সাইলেন্ট মোড|সাইলেন্ট মুড|ফোন সাইলেন্ট|নীরব মোড|silent mode",
        "vibratemode" to "ভাইব্রেট মোড|ভাইব্রেশন মোড|ভাইব্রেট|ভাইব্রেশন|vibrate mode|vibrate",
        "ringmode" to "নরমাল মোড|রিং মোড|রিংটোন মোড|রিংটোন চালু|রিং চালু|normal mode|ring mode",
        "plus" to "যোগ|প্লাস|plus|add",
        "minus" to "বিয়োগ|মাইনাস|minus|subtract",
        "times" to "গুণ|গুন|times|multiply|multiplied by|into",
        "divided" to "ভাগ|divided by|divide|divided",
        "point" to "দশমিক|point",
        // ---- screen ----
        "wake" to "জাগাও|জাগান|জাগিয়ে|জাগিও|জাগো|wake|wake up",
        "screen" to "স্ক্রিন|স্ক্রীন|স্ক্রিণ|screen|ডিসপ্লে|display",
        "lock" to "লক|lock|লকড",
        "unlock" to "আনলক|unlock",
        "show" to "দেখাও|দেখান|দেখি|show",
        // ---- system buttons and on-screen actions (only when they are the whole sentence; see IntentParser.navOnly) ----
        "goback" to "ব্যাক|back|ব্যাক যাও|ব্যাকে যাও|ব্যাক করো|ব্যাক চাপো|পেছনে যাও|পেছনে যান|পিছনে যাও|পিছনে যান|পেছনে|পিছনে|go back|press back|back button|ব্যাক বাটন",
        "gohome" to "হোম|home|হোমে যাও|হোমে যান|হোম স্ক্রিন|হোম স্ক্রীন|হোম স্ক্রিনে যাও|হোম স্ক্রিনে যান|home screen|homescreen|go home|go to home|go to home screen|press home|home button|হোম বাটন",
        "recents" to "রিসেন্ট অ্যাপস|রিসেন্ট অ্যাপ|রিসেন্ট এপ|রিসেন্টস|রিসেন্ট|recent apps|recent app|recents|recent|app switcher|চলমান অ্যাপ|সাম্প্রতিক অ্যাপ|সাম্প্রতিক অ্যাপস",
        "notifs" to "নোটিফিকেশন|নোটিফিকেশনস|নোটিফিকেশন প্যানেল|নোটিফিকেশন বার|notifications|notification|notification panel|notification bar|notification shade",
        "screenshot" to "স্ক্রিনশট|স্ক্রিন শট|স্ক্রিনশট নাও|স্ক্রিনশট নিন|স্ক্রিনশট তোলো|স্ক্রিনশট তুলুন|স্ক্রিন শট নাও|screenshot|screen shot|take screenshot|take a screenshot|take the screenshot|capture screen",
        "readscreen" to "স্ক্রিনে কী আছে|স্ক্রিনে কি আছে|স্ক্রিন পড়ো|স্ক্রিন পড়ে শোনাও|স্ক্রিন পড়ে শোনান|read screen|read the screen|read my screen|whats on screen|whats on the screen|whats on my screen|what is on the screen|what is on my screen",
        "tapword" to "ট্যাপ|tap|ক্লিক|click|চাপো|চাপ দাও|চাপুন|press|প্রেস",
        "typeword" to "টাইপ|type",
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
        "on" to "চালু|চালাও|চালান|চালিয়ে|অন|on|জ্বালাও|জ্বালান|জ্বালিয়ে|জ্বেলে|জ্বলাও|জ্বালো|enable|start|run|স্টার্ট|শুরু",
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
        // ---- Phase 3: messages, notes, reminders, weather, conversion, more switches ----
        "sendword" to "পাঠাও|পাঠান|পাঠিয়ে দাও|পাঠিয়ে দিন|পাঠিয়ে দেবে|পাঠিয়ে|send|sent",
        "readword" to "পড়ো|পড়ুন|পড়ে শোনাও|পড়ে শোনান|পড়ে দাও|পড়ে দিন|read|read out",
        "newword" to "নতুন|new|unread|আনরিড",
        "note" to "নোট|নোটস|নোটগুলো|note|notes|লিখে রাখো|লিখে রাখুন|মনে রাখো|মনে রেখো|remember",
        "erase" to "মুছে ফেলো|মুছে ফেলুন|মুছে দাও|মুছো|মুছুন|মুছে|delete|clear|erase|remove",
        "remind" to "মনে করিয়ে দাও|মনে করিয়ে দিও|মনে করিয়ে দিন|মনে করিয়ে|মনে করাও|মনে করিও|রিমাইন্ডার|রিমাইন্ড|reminder|remind me|remind",
        "event" to "ইভেন্ট|event|অ্যাপয়েন্টমেন্ট|appointment",
        "weather" to "আবহাওয়া|ওয়েদার|weather|forecast|তাপমাত্রা|temperature|বৃষ্টি|rain",
        "howmuch" to "কত|কতটুকু|how much|how many|convert|কনভার্ট|রূপান্তর",
        "dnd" to "ডু নট ডিস্টার্ব|ডু নট ডিস্টার্ভ|do not disturb|dont disturb|dnd|ডিএনডি",
        "airplane" to "এয়ারপ্লেন মোড|এরোপ্লেন মোড|ফ্লাইট মোড|ফ্লাইট|এয়ারপ্লেন|airplane mode|aeroplane mode|flight mode|airplane|aeroplane",
        "mobiledata" to "মোবাইল ডেটা|মোবাইল ডাটা|ইন্টারনেট ডেটা|ডেটা|ডাটা|mobile data|cellular data|data",
        "hotspot" to "ওয়াইফাই হটস্পট|ওয়াই ফাই হটস্পট|হটস্পট|টিথারিং|wifi hotspot|hotspot|hot spot|tethering",
        "whocalls" to "কে কল করছে|কে ফোন করছে|কে কল করেছে|কে ফোন করেছে|কার কল এসেছে|কার ফোন এসেছে|কার কল|কার ফোন|who is calling|whos calling|who called me|who called",
        // ---- Phase 4: references to what was just said or done, routines, automatic triggers ----
        "again" to "আবার|আরেকবার|আরও একবার|পুনরায়|again|once more|one more time",
        "it_ref" to "ওটা|ওটাকে|এটা|এটাকে|that one|this one|that|this",
        "undo" to "উল্টোটা|উল্টো|undo|opposite|revert",
        "routine" to "রুটিন|রুটিনটা|রুটিনগুলো|রুটিনে|routine|routines|macro|ম্যাক্রো",
        "routinemake" to "বানাও|বানান|তৈরি করো|তৈরি করুন|create|make|সেভ করো|সেভ করুন|save",
        "listw" to "কী কী|কি কি|সবগুলো|list|তালিকা|কতগুলো|all",
        "daily" to "প্রতিদিন|রোজ|প্রতি দিন|every day|everyday|daily|each day",
        "charger" to "চার্জে দিলে|চার্জে লাগালে|চার্জার লাগালে|চার্জার লাগলে|চার্জ দিলে|plugged in|plug in|charger connected|when charging",
        "chargeroff" to "চার্জার খুললে|চার্জ থেকে খুললে|চার্জার খুলে নিলে|unplugged|charger removed",
        "connectw" to "কানেক্ট হলে|কানেক্ট হবে|কানেক্ট করলে|সংযোগ হলে|connected|connects|connect",
        "disconnectw" to "ডিসকানেক্ট হলে|ডিসকানেক্ট হবে|বিচ্ছিন্ন হলে|disconnected|disconnects",
        "triggerw" to "ট্রিগার|trigger|অটোমেটিক|automatic|auto",
        // length
        "u_km" to "কিলোমিটার|কিমি|কি মি|km|kilometer|kilometers|kilometre|kilometres",
        "u_m" to "মিটার|meter|meters|metre|metres",
        "u_cm" to "সেন্টিমিটার|সেমি|cm|centimeter|centimeters|centimetre|centimetres",
        "u_mm" to "মিলিমিটার|mm|millimeter|millimeters",
        "u_mi" to "মাইল|মাইলস|mile|miles",
        "u_ft" to "ফুট|foot|feet|ft",
        "u_inch" to "ইঞ্চি|inch|inches",
        // mass (মণ / সের / ভরি are the everyday Bangladeshi units)
        "u_kg" to "কেজি|কিলো|কিলোগ্রাম|কেজিতে|kg|kgs|kilogram|kilograms|kilo|kilos",
        "u_g" to "গ্রাম|gram|grams",
        "u_lb" to "পাউন্ড|pound|pounds|lb|lbs",
        "u_oz" to "আউন্স|ounce|ounces|oz",
        "u_maund" to "মণ|maund",
        "u_ser" to "সের|ser|seer",
        "u_bhori" to "ভরি|bhori|vori",
        // volume
        "u_l" to "লিটার|liter|liters|litre|litres",
        "u_ml" to "মিলিলিটার|এমএল|ml|milliliter|milliliters|millilitre|millilitres",
        "u_gal" to "গ্যালন|gallon|gallons",
        // temperature
        "u_c" to "সেলসিয়াস|ডিগ্রি সেলসিয়াস|celsius|centigrade",
        "u_f" to "ফারেনহাইট|ডিগ্রি ফারেনহাইট|fahrenheit",
        // data
        "u_kb" to "কিলোবাইট|কেবি|kb|kilobyte|kilobytes",
        "u_mb" to "মেগাবাইট|এমবি|mb|megabyte|megabytes",
        "u_gb" to "গিগাবাইট|জিবি|gb|gigabyte|gigabytes",
        // money
        "c_usd" to "ডলার|মার্কিন ডলার|dollar|dollars|usd|bucks",
        "c_bdt" to "টাকা|টাকায়|taka|bdt|tk",
        "c_eur" to "ইউরো|euro|euros|eur",
        "c_gbp" to "স্টার্লিং|পাউন্ড স্টার্লিং|sterling|gbp",
        "c_inr" to "রুপি|রুপী|রুপিতে|ভারতীয় রুপি|rupee|rupees|inr",
        "c_sar" to "রিয়াল|সৌদি রিয়াল|riyal|riyals|sar",
        "c_aed" to "দিরহাম|dirham|dirhams|aed",
        "c_jpy" to "ইয়েন|yen|jpy",
        "c_cny" to "ইউয়ান|yuan|rmb|cny",
        "c_myr" to "রিঙ্গিত|রিংগিত|ringgit|myr",
        "c_kwd" to "দিনার|কুয়েতি দিনার|dinar|kwd",
        // ---- filler / do-verbs / "and then": carry no meaning of their own, but mark where one command ends ----
        "_v" to "করো|কর|করুন|করেন|করবে|করে|করতে|করি|দাও|দিন|দে|দিয়ে|দিও|দেবে|দেন|লাগাও|লাগান",
        "_f" to "প্লিজ|please|plz|একটু|আমাকে|আমার|আমি|তুমি|আপনি|এখন|now|কে|এ|এবার|তো|ওই|এই|the|a|an|to|my|me|can|you|could|would|set|সেট|at|for|of|it|hey|হে|যাওয়ার|যেতে|যাব|যাবো|যাই",
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
