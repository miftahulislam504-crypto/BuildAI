package com.buildx.ai.device

data class CheckItem(val id: String, val phase: Int, val title: String, val how: String)

/**
 * The pass checklist for phases 0-12, run by hand on each phone (the app cannot judge "did it hear me?" itself).
 * [Result]: pass / fail / not tried. The quoted commands are what you say to the phone, so they stay in Bangla.
 */
object Checklist {

    enum class Result { PASS, FAIL, NONE }

    val items: List<CheckItem> = listOf(
        CheckItem("wake10", 0, "\"Hey BuildX\" wakes it at least 8 times out of 10", "Screen off, phone on a table, say it 10 times from arm's length; count WAKE in the log"),
        CheckItem("wake_off", 0, "Responds with the screen off (even after 30 minutes)", "Turn the screen off, leave it for 30 minutes, then call it"),
        CheckItem("false_trig", 0, "No more than 2 false triggers per hour", "Leave it on for 1 hour during normal conversation or TV; count WAKE in the log"),
        CheckItem("stt_bn", 0, "Bangla speech recognition: at least 16 of 20 correct", "Say 20 sentences with the \"Speech input test\" button and check the log"),
        CheckItem("service", 1, "Starts and stops from the notification and the tile", "Try the notification button and the Quick Settings tile"),
        CheckItem("time", 1, "Time query: it tells the time", "Say Hey BuildX, then \"এখন কয়টা বাজে\""),
        CheckItem("parse", 2, "Understands 10 sentences correctly", "Type various sentences with the \"Analyze\" button"),
        CheckItem("call_name", 3, "Calls by name", "\"আব্বুকে ফোন করো\", then \"হ্যাঁ\"; the call goes through"),
        CheckItem("call_ask", 3, "Asks when two contacts share a name", "Create two contacts with the same name and try"),
        CheckItem("call_number", 3, "Calls by number", "\"০১৭… নম্বরে কল দাও\""),
        CheckItem("wa_call", 3, "WhatsApp calls work (voice and video)", "\"রহিমকে হোয়াটসঅ্যাপে কল করো\", \"রহিমকে হোয়াটসঅ্যাপে ভিডিও কল করো\""),
        CheckItem("flash", 4, "Flashlight on and off (from a screen-off state)", "\"টর্চ জ্বালাও\", \"টর্চ নিভাও\""),
        CheckItem("volume", 4, "Volume goes up, goes down, and can be set", "\"ভলিউম ৫০ করো\", \"ভলিউম কমাও\", \"মিউট করো\""),
        CheckItem("open_app", 4, "Opens apps (from a screen-off state)", "\"ইউটিউব খোলো\"; the screen wakes and the app comes to the front"),
        CheckItem("photo", 4, "Takes photos (rear camera and selfie)", "\"ছবি তোলো\", \"সেলফি তোলো\"; check the BuildX folder in the gallery"),
        CheckItem("alarm", 4, "Sets an alarm in the clock app", "\"কাল সকাল ৬টায় অ্যালার্ম দাও\"; check the clock app"),
        CheckItem("panel", 4, "Opens the Wi-Fi and Bluetooth panel", "With Accessibility off, say \"ওয়াইফাই খোলো\""),
        CheckItem("lock", 5, "Locks the screen", "With Accessibility on, say \"স্ক্রিন লক করো\""),
        CheckItem("lock_admin", 5, "Locks the screen through Device admin (with Accessibility off)", "Enable Device admin and say \"স্ক্রিন লক করো\"; note whether it opens with a fingerprint or asks for the PIN"),
        CheckItem("wake_screen", 5, "Wakes the screen and tells you to unlock", "\"স্ক্রিন জাগাও\", \"ফোন আনলক করো\""),
        CheckItem("toggle_wifi", 5, "Toggles Wi-Fi by itself", "\"ওয়াইফাই বন্ধ করো\", \"ওয়াইফাই চালু করো\""),
        CheckItem("toggle_bt", 5, "Toggles Bluetooth by itself", "\"ব্লুটুথ চালু করো\", \"ব্লুটুথ বন্ধ করো\""),
        CheckItem("chain3", 6, "Runs a 3-step chain", "\"টর্চ জ্বালাও আর ভলিউম পঞ্চাশ করো তারপর ইউটিউব খোলো\""),
        CheckItem("chain_fail", 6, "Reports a failure in a chain correctly", "Keep the camera open in another app and say \"টর্চ জ্বালাও আর ভলিউম কমাও\""),
        CheckItem("survive", 7, "The service did not stop on its own (no \"stopped abnormally\" in the log)", "Leave it on all day and open the app in the evening"),
        CheckItem("assist_key", 8, "Default assistant: the side key or gesture shows the popup and listens", "Set BuildX as the assistant, then press and hold the side key (or the home gesture)"),
        CheckItem("assist_popup", 8, "Saying \"Hey BuildX\" shows the popup (even on the lock screen)", "Lock the screen and say \"Hey BuildX\""),
        CheckItem("assist_auto", 8, "Listens after a restart without opening the app", "Call it after a restart without opening the app; look for the \"ASSISTANT auto-start\" line in the log"),
        CheckItem("voice_me", 9, "Voice lock: it wakes for me (8 or more out of 10)", "Turn on voice lock and say \"Hey Build\" 10 times; count WAKE-VOICE in the log"),
        CheckItem("voice_other", 9, "It does not wake for someone else", "Ask another person to say \"Hey Build\" 10 times; look for \"rejected\" in the log"),
        CheckItem("yt_play", 9, "Plays music on YouTube", "\"ইউটিউবে আরিজিত সিংয়ের গান চালাও\""),
        CheckItem("media_keys", 9, "Pause, next and previous track", "While music plays: \"গান থামাও\", \"পরের গান\", \"আগের গান\", \"গান চালু করো\""),
        CheckItem("scroll", 9, "Scrolls reels and shorts", "Open Shorts or Reels and say \"পরের রিল\", \"আগের রিল\" (needs Accessibility)"),
        CheckItem("everyday", 9, "Battery, date, calculation, directions, search, brightness, silent mode", "\"ব্যাটারি কত\", \"আজ কত তারিখ\", \"৫ গুণ ৭ কত\", \"গুগলে আবহাওয়া সার্চ করো\", \"ব্রাইটনেস ৫০ করো\", \"ভাইব্রেট মোড করো\""),

        // Roadmap phase 5: listening and speaking
        CheckItem("wake_custom", 11, "A custom wake word (for example hey jarvis) wakes it 8 or more out of 10", "Save it in Settings > Wake word, restart the service, say it 10 times; count WAKE in the log"),
        CheckItem("lowpower_wake", 11, "The wake word still works 8 of 10 times in low-power mode", "Turn on Settings > Low-power listening; look for the \"low-power mode\" line in the log, then say it 10 times"),
        CheckItem("lowpower_battery", 11, "Low-power mode uses less battery over 1 hour of listening", "Screen off, unplugged, same room, 1 hour in each mode; compare the listening % in the GATE log line"),
        CheckItem("whisper_bn", 11, "Whisper understands Bangla commands better than the phone's engine", "Settings > Speech recognition > Whisper; say the same 10 sentences with both engines and compare STT[whisper] with STT in the log"),
        CheckItem("whisper_fallback", 11, "Without internet, a failed Whisper falls back to the phone's recognizer", "With Whisper on and data off, give a command; look for \"falling back to the phone's recognizer\" in the log"),
        CheckItem("tts_tuning", 11, "Changing the voice, speed and pitch applies to the next reply", "Change them under Settings > Voice and press \"Play sample\", then give a command and listen"),
        CheckItem("tts_long", 11, "A long reply is not cut off midway", "Give a command with a long answer such as \"আজ আবহাওয়া কেমন\"; check that the whole reply is heard"),

        // Roadmap phase 6: small fixes
        CheckItem("alarm_dayafter", 12, "\"পরশু সকাল ৬টায় অ্যালার্ম দাও\" rings on the right date", "Give the command and check the next alarm date in the notification shade or settings; to check it rings on time, set one 2 to 3 minutes ahead"),
        CheckItem("alarm_buttons", 12, "The Stop and Snooze buttons work on a ringing alarm", "When your own alarm rings, press each button (after 5 minutes of snooze it must ring again)"),
        CheckItem("alarm_reboot", 12, "Your own alarm still rings after a restart", "Set the day-after-tomorrow alarm and restart the phone; check the next alarm is unchanged"),
        CheckItem("risk_llm", 12, "Server-chosen airplane mode, hotspot, data and Do Not Disturb ask first", "Keep the LLM on and say something the rules cannot parse, such as \"ইন্টারনেট ছাড়া থাকতে চাই\"; look for \"RISK ask first\" in the log")
    )

    fun phaseName(p: Int) = when (p) {
        11 -> "Roadmap phase 5: listening and speaking"
        12 -> "Roadmap phase 6: small fixes"
        else -> "Phase $p"
    }

    fun summary(results: Map<String, Result>): Triple<Int, Int, Int> {
        var pass = 0
        var fail = 0
        var none = 0
        for (it in items) when (results[it.id] ?: Result.NONE) {
            Result.PASS -> pass++
            Result.FAIL -> fail++
            Result.NONE -> none++
        }
        return Triple(pass, fail, none)
    }

    /** the text to paste when reporting a device */
    fun format(device: String, results: Map<String, Result>): String {
        val (pass, fail, none) = summary(results)
        val sb = StringBuilder()
        sb.append("Checklist: $device\n")
        sb.append("Passed $pass, failed $fail, not tried $none (total ${items.size})\n")
        var phase = -1
        for (item in items) {
            if (item.phase != phase) {
                phase = item.phase
                sb.append("\n${phaseName(phase)}\n")
            }
            val mark = when (results[item.id] ?: Result.NONE) {
                Result.PASS -> "[pass]"
                Result.FAIL -> "[fail]"
                Result.NONE -> "[ - ]"
            }
            sb.append(" $mark ${item.title}\n")
        }
        return sb.toString().trimEnd()
    }
}
