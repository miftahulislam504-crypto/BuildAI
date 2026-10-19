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
