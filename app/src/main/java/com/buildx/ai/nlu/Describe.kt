package com.buildx.ai.nlu

/** One short phrase saying what was understood. Used for logs and for "understood, but not built yet" replies. */
object Describe {

    private fun bnDigits(s: String) = s.map { if (it in '0'..'9') '০' + (it - '0') else it }.joinToString("")

    fun say(p: Parsed, bn: Boolean): String {
        val s = p.slots
        return when (p.intent) {
            "time" -> if (bn) "সময় জানা" else "tell the time"
            "call" -> {
                val who = s["target"] ?: s["number"]
                if (bn) (if (who != null) "কল: ${bnDigits(who)}" else "কল") else (if (who != null) "call $who" else "call")
            }
            "whatsapp_call" -> {
                val who = s["target"] ?: s["number"]
                val kind = if (s["video"] == "1") (if (bn) "ভিডিও কল" else "video call") else (if (bn) "কল" else "call")
                if (bn) "WhatsApp $kind${if (who != null) ": ${bnDigits(who)}" else ""}" else "WhatsApp $kind${if (who != null) " to $who" else ""}"
            }
            "take_photo" -> if (s["camera"] == "front") (if (bn) "সেলফি তোলা" else "take a selfie") else (if (bn) "ছবি তোলা" else "take a photo")
            "media" -> when (s["action"]) {
                "pause" -> if (bn) "গান থামানো" else "pause the music"
                "next" -> if (bn) "পরের গান" else "next song"
                "prev" -> if (bn) "আগের গান" else "previous song"
                else -> if (bn) "গান চালু করা" else "resume playing"
            }
            "swipe" -> if (s["dir"] == "prev") (if (bn) "আগেরটায় স্ক্রল" else "scroll back") else (if (bn) "পরেরটায় স্ক্রল" else "scroll to the next")
            "calc" -> if (bn) "হিসাব: ${s["expr"]}" else "calculate ${s["expr"]}"
            "brightness" -> volume(s, bn).replace(if (bn) "ভলিউম" else "volume", if (bn) "ব্রাইটনেস" else "brightness").replace(if (bn) "মিউট" else "mute", if (bn) "ব্রাইটনেস" else "brightness")
            "ringer" -> when (s["mode"]) {
                "silent" -> if (bn) "সাইলেন্ট মোড" else "silent mode"
                "vibrate" -> if (bn) "ভাইব্রেট মোড" else "vibrate mode"
                else -> if (bn) "নরমাল মোড" else "normal mode"
            }
            "battery" -> if (bn) "ব্যাটারি কত" else "battery level"
            "date" -> if (bn) "আজকের তারিখ" else "today's date"
            "navigate" -> if (bn) "রাস্তা দেখানো: ${s["query"]}" else "navigate to ${s["query"]}"
            "web_search" -> if (bn) "গুগলে খোঁজা: ${s["query"]}" else "search for ${s["query"]}"
            "youtube_play" -> if (bn) "ইউটিউবে চালানো: ${s["query"]}" else "play on YouTube: ${s["query"]}"
            "open_feed" -> if (s["feed"] == "shorts") (if (bn) "ইউটিউব শর্টস" else "YouTube Shorts") else (if (bn) "ইনস্টাগ্রাম রিলস" else "Instagram Reels")
            "end_call" -> if (bn) "কল কাটা" else "hang up"
            "alarm" -> alarm(s, bn)
            "flashlight" -> thing(if (bn) "ফ্ল্যাশলাইট" else "flashlight", s["state"], bn)
            "wifi" -> thing(if (bn) "ওয়াইফাই" else "Wi-Fi", s["state"], bn)
            "bluetooth" -> thing(if (bn) "ব্লুটুথ" else "Bluetooth", s["state"], bn)
            "volume" -> volume(s, bn)
            "screen_wake" -> if (bn) "স্ক্রিন জাগানো" else "wake the screen"
            "screen_lock" -> if (bn) "স্ক্রিন লক" else "lock the screen"
            "open_app" -> if (bn) "অ্যাপ খোলা: ${s["app"] ?: ""}" else "open ${s["app"] ?: "an app"}"
            "cancel" -> if (bn) "বাতিল" else "cancel"
            "back" -> if (bn) "পেছনে যাওয়া" else "go back"
            "home" -> if (bn) "হোমে যাওয়া" else "go home"
            "recents" -> if (bn) "রিসেন্ট অ্যাপ" else "recent apps"
            "notifications" -> if (bn) "নোটিফিকেশন দেখানো" else "show notifications"
            "screenshot" -> if (bn) "স্ক্রিনশট নেওয়া" else "take a screenshot"
            "read_screen" -> if (bn) "স্ক্রিন পড়ে শোনানো" else "read the screen"
            "tap" -> if (bn) "ট্যাপ: ${s["text"] ?: ""}" else "tap ${s["text"] ?: ""}"
            "type_text" -> if (bn) "টাইপ: ${s["text"] ?: ""}" else "type ${s["text"] ?: ""}"
            "send_sms", "send_whatsapp" -> {
                val who = s["target"] ?: s["number"]
                val ch = if (p.intent == "send_sms") (if (bn) "এসএমএস" else "SMS") else "WhatsApp"
                if (bn) "$ch পাঠানো${if (who != null) ": ${bnDigits(who)}" else ""}" else "send $ch${if (who != null) " to $who" else ""}"
            }
            "read_sms" -> if (bn) "মেসেজ পড়ে শোনানো" else "read messages"
            "read_notifications" -> if (bn) "নোটিফিকেশন পড়ে শোনানো" else "read notifications"
            "who_calling" -> if (bn) "কে কল করছে" else "who is calling"
            "note" -> when (s["op"]) {
                "read" -> if (bn) "নোট পড়া" else "read notes"
                "clear" -> if (bn) "নোট মোছা" else "delete notes"
                else -> if (bn) "নোট: ${s["text"] ?: ""}" else "note: ${s["text"] ?: ""}"
            }
            "remind" -> {
                val rel = s["in_minutes"]
                val at = when {
                    rel != null -> if (bn) "${bnDigits(rel)} মিনিট পরে" else "in $rel min"
                    s["hour"] != null -> "${s["hour"]}:${(s["minute"] ?: "0").padStart(2, '0')}".let { if (bn) bnDigits(it) else it }
                    else -> if (bn) "সময় বলা হয়নি" else "no time"
                }
                if (bn) "রিমাইন্ডার ($at): ${s["text"] ?: ""}" else "remind ($at): ${s["text"] ?: ""}"
            }
            "calendar_event" -> if (bn) "ক্যালেন্ডার: ${s["title"] ?: ""}" else "calendar: ${s["title"] ?: ""}"
            "weather" -> if (bn) "আবহাওয়া${s["city"]?.let { ": $it" } ?: ""}" else "weather${s["city"]?.let { " in $it" } ?: ""}"
            "convert" -> if (bn) "বদলানো: ${bnDigits(s["value"] ?: "")} ${s["from"] ?: ""} → ${s["to"] ?: "?"}" else "convert ${s["value"]} ${s["from"]} to ${s["to"] ?: "?"}"
            "dnd" -> thing(if (bn) "ডু নট ডিস্টার্ব" else "Do Not Disturb", s["state"], bn)
            "airplane" -> thing(if (bn) "এয়ারপ্লেন মোড" else "airplane mode", s["state"], bn)
            "mobile_data" -> thing(if (bn) "মোবাইল ডেটা" else "mobile data", s["state"], bn)
            "hotspot" -> thing(if (bn) "হটস্পট" else "hotspot", s["state"], bn)
            "routine" -> {
                val n = s["name"] ?: ""
                when (s["op"]) {
                    "list" -> if (bn) "রুটিনের তালিকা" else "list routines"
                    "save" -> if (bn) "রুটিন বানানো: $n" else "create routine: $n"
                    "delete" -> if (bn) "রুটিন মোছা: $n" else "delete routine: $n"
                    "schedule", "power", "bt", "untrigger" -> if (bn) "রুটিনের অটো-স্টার্ট: $n" else "routine trigger: $n"
                    else -> if (bn) "রুটিন চালানো: $n" else "run routine: $n"
                }
            }
            "ui_task" -> if (bn) "স্ক্রিনে কাজ: ${s["goal"] ?: ""}" else "do on screen: ${s["goal"] ?: ""}"
            else -> s["text"] ?: ""
        }
    }

    private fun thing(name: String, state: String?, bn: Boolean): String = when (state) {
        "on" -> if (bn) "$name চালু" else "$name on"
        "off" -> if (bn) "$name বন্ধ" else "$name off"
        "open" -> if (bn) "$name সেটিংস" else "$name settings"
        else -> if (bn) "$name টগল" else "toggle $name"
    }

    private fun alarm(s: Map<String, String>, bn: Boolean): String {
        val rel = s["in_minutes"]
        if (rel != null) return if (bn) "অ্যালার্ম ${bnDigits(rel)} মিনিট পরে" else "alarm in $rel minutes"
        val h = s["hour"] ?: return if (bn) "অ্যালার্ম (সময় বলা হয়নি)" else "alarm (no time given)"
        val m = (s["minute"] ?: "0").padStart(2, '0')
        val day = when (s["when"]) {
            "tomorrow" -> if (bn) "আগামীকাল " else "tomorrow "
            "today" -> if (bn) "আজ " else "today "
            "day_after" -> if (bn) "পরশু " else "day after tomorrow "
            else -> ""
        }
        val t = "$h:$m"
        return if (bn) "অ্যালার্ম $day${bnDigits(t)}" else "alarm $day$t"
    }

    private fun volume(s: Map<String, String>, bn: Boolean): String {
        val level = s["level"]
        val delta = s["delta"]
        return when (s["action"]) {
            "up" -> if (bn) "ভলিউম বাড়ানো" else "volume up"
            "down" -> if (bn) "ভলিউম কমানো" else "volume down"
            "mute" -> if (bn) "ভলিউম মিউট" else "mute"
            "unmute" -> if (bn) "ভলিউম আনমিউট" else "unmute"
            "set" -> if (bn) "ভলিউম ${bnDigits(level ?: "")}" else "volume $level"
            else -> if (bn) "ভলিউম" else "volume"
        } + if (delta != null) (if (bn) " (${bnDigits(delta)})" else " by $delta") else ""
    }
}
