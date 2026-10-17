package com.buildx.ai.nlu

import com.buildx.ai.nlu.T.ok
import org.junit.Assert
import org.junit.Test

/** Phase 2 pass rule: at least 10 different phrasings per intent. */
class IntentParserTest {

    @Test fun time() {
        for (s in listOf(
            "এখন কয়টা বাজে", "কয়টা বাজে", "কটা বাজে এখন", "সময় কত", "এখন সময় কত", "কত বাজে",
            "টাইম কত", "what time is it", "what is the time", "time please", "এখন কটা বাজে বলো", "সময় বলো"
        )) ok(s, "time")
    }

    @Test fun callByName() {
        ok("বাবাকে ফোন করো", "call", "target=বাবা")
        ok("রহিমকে কল দাও", "call", "target=রহিম")
        ok("আব্বুকে কল করুন", "call", "target=আব্বু")
        ok("call Rahim", "call", "target=rahim")
        ok("Rahim কে call করো", "call", "target=rahim")
        ok("মাকে ফোন দাও", "call", "target=মা")
        ok("ছোট ভাইকে কল করো", "call", "target=ছোট ভাই")
        ok("ফোন লাগাও রহিমকে", "call", "target=রহিম")
        ok("আমাকে একটু করিমকে ফোন করে দাও", "call", "target=করিম")
        ok("please call Karim", "call", "target=karim")
        ok("ফোন করো", "call")
    }

    @Test fun callByNumber() {
        ok("০১৭১১২২৩৩৪৪ নম্বরে কল দাও", "call", "number=01711223344")
        ok("01711223344 এ ফোন করো", "call", "number=01711223344")
        ok("01711-223344 নাম্বারে কল করো", "call", "number=01711223344")
        ok("dial 999", "call", "number=999")
        ok("+8801711223344 কল করো", "call", "number=+8801711223344")
        ok("শূন্য এক সাত এক এক দুই দুই তিন তিন চার চার নম্বরে কল দাও", "call", "number=01711223344")
    }

    @Test fun callNamesThatLookLikeKeywords() {
        ok("আলোকে ফোন করো", "call", "target=আলো")        // আলো is also "flashlight"
        ok("বিকাশকে কল দাও", "call", "target=বিকাশ")       // বিকাশ is also the bKash app
    }

    @Test fun endCallIsRecognisedSoItCanBeRefused() {
        for (s in listOf("কল কেটে দাও", "কল বন্ধ করো", "hang up", "call cut করো")) ok(s, "end_call")
    }

    @Test fun alarmClock() {
        ok("আগামীকাল সকাল ছয়টায় অ্যালার্ম দাও", "alarm", "hour=6", "minute=0", "when=tomorrow", "ambiguous=false")
        ok("কাল সকাল ৬টায় অ্যালার্ম সেট করো", "alarm", "hour=6", "when=tomorrow")
        ok("সকাল ৭টা ৩০ মিনিটে অ্যালার্ম দাও", "alarm", "hour=7", "minute=30", "when=auto")
        ok("রাত ১০টায় অ্যালার্ম দাও", "alarm", "hour=22")
        ok("set alarm for 6 am", "alarm", "hour=6", "minute=0")
        ok("alarm at 6:30 pm", "alarm", "hour=18", "minute=30")
        ok("সাড়ে ছয়টায় অ্যালার্ম দাও", "alarm", "hour=6", "minute=30", "ambiguous=true")
        ok("বিকাল ৪টায় এলার্ম দাও", "alarm", "hour=16")
        ok("আজ রাত ১১টায় অ্যালার্ম", "alarm", "hour=23", "when=today")
        ok("দুপুর ২টায় অ্যালার্ম দাও", "alarm", "hour=14")
        ok("পৌনে সাতটায় অ্যালার্ম", "alarm", "hour=6", "minute=45")
        ok("সওয়া আটটায় অ্যালার্ম দাও", "alarm", "hour=8", "minute=15")
        ok("দেড়টায় অ্যালার্ম দাও", "alarm", "hour=1", "minute=30")
        ok("পরশু ভোর ৫টায় অ্যালার্ম দাও", "alarm", "hour=5", "when=day_after")
    }

    @Test fun alarmWakeMeUp() {
        ok("ভোর ৫টায় আমাকে জাগিয়ে দিও", "alarm", "hour=5")
        ok("wake me up at 7 am", "alarm", "hour=7")
        ok("আমাকে সকাল ৬টায় ঘুম থেকে ডেকে দিও", "alarm", "hour=6")
    }

    @Test fun alarmRelative() {
        ok("১০ মিনিট পরে অ্যালার্ম দাও", "alarm", "in_minutes=10")
        ok("আধা ঘণ্টা পর অ্যালার্ম দাও", "alarm", "in_minutes=30")
        ok("১ ঘণ্টা পরে অ্যালার্ম", "alarm", "in_minutes=60")
        ok("alarm in 15 minutes", "alarm", "in_minutes=15")
        ok("পাঁচ মিনিটের টাইমার", "alarm", "in_minutes=5")
    }

    @Test fun alarmWithoutTimeStillAlarm() {
        val r = T.p("একটা অ্যালার্ম দাও")
        Assert.assertEquals("alarm", r.intent)
        Assert.assertNull(r.slots["hour"])
        Assert.assertNull(r.slots["in_minutes"])
    }

    @Test fun volume() {
        ok("ভলিউম বাড়াও", "volume", "action=up")
        ok("ভলিউম কমাও", "volume", "action=down")
        ok("আওয়াজ কমিয়ে দাও", "volume", "action=down")
        ok("ভলিউম ৫০ করো", "volume", "action=set", "level=50")
        ok("ভলিউম ফুল করো", "volume", "action=set", "level=100")
        ok("মিউট করো", "volume", "action=mute")
        ok("volume up", "volume", "action=up")
        ok("volume down", "volume", "action=down")
        ok("set volume to 30", "volume", "action=set", "level=30")
        ok("আওয়াজ বন্ধ করো", "volume", "action=mute")
        ok("সাউন্ড বাড়িয়ে দাও", "volume", "action=up")
        ok("ভলিউম ১০ কমাও", "volume", "action=down", "delta=10")
        ok("ভলিউম পঞ্চাশ শতাংশ করো", "volume", "action=set", "level=50")
        ok("unmute", "volume", "action=unmute")
        ok("আওয়াজ জোরে করো", "volume", "action=up")
        ok("ভলিউম আস্তে করো", "volume", "action=down")
        ok("volume 70 percent", "volume", "action=set", "level=70")
        ok("ভলিউম বেশি করো", "volume", "action=up")
        ok("louder", "volume", "action=up")
        ok("ভলিউম মিনিমাম করো", "volume", "action=set", "level=0")
    }

    @Test fun flashlight() {
        ok("ফ্ল্যাশলাইট চালু করো", "flashlight", "state=on")
        ok("ফ্ল্যাশলাইট বন্ধ করো", "flashlight", "state=off")
        ok("টর্চ জ্বালাও", "flashlight", "state=on")
        ok("টর্চ নিভাও", "flashlight", "state=off")
        ok("torch on", "flashlight", "state=on")
        ok("flashlight off", "flashlight", "state=off")
        ok("লাইট জ্বালাও", "flashlight", "state=on")
        ok("ফ্ল্যাশ লাইট অন করো", "flashlight", "state=on")
        ok("টর্চলাইট বন্ধ করে দাও", "flashlight", "state=off")
        ok("turn on the flashlight", "flashlight", "state=on")
        ok("আলো জ্বালাও", "flashlight", "state=on")
        ok("ফ্ল্যাশলাইট খোলো", "flashlight", "state=on")
        ok("ফ্ল্যাশলাইটটা নিভিয়ে দাও", "flashlight", "state=off")
        ok("flashligt on", "flashlight", "state=on") // typo
        ok("ফ্ল্যাশলাইট", "flashlight", "state=toggle")
    }

    @Test fun wifi() {
        ok("ওয়াইফাই চালু করো", "wifi", "state=on")
        ok("ওয়াইফাই বন্ধ করো", "wifi", "state=off")
        ok("wifi on", "wifi", "state=on")
        ok("wi-fi off", "wifi", "state=off")
        ok("ওয়াইফাই সেটিংস খোলো", "wifi", "state=open")
        ok("ওয়াই ফাই অন করো", "wifi", "state=on")
        ok("ওয়াইফাইটা বন্ধ করে দাও", "wifi", "state=off")
        ok("turn on wifi", "wifi", "state=on")
        ok("ওয়াইফাই খোলো", "wifi", "state=open")
        ok("ওয়াইফাই অফ করো", "wifi", "state=off")
        ok("Wi-Fi চালু করো", "wifi", "state=on")
    }

    @Test fun bluetooth() {
        ok("ব্লুটুথ চালু করো", "bluetooth", "state=on")
        ok("ব্লুটুথ বন্ধ করো", "bluetooth", "state=off")
        ok("bluetooth on", "bluetooth", "state=on")
        ok("bluetooth off", "bluetooth", "state=off")
        ok("ব্লুটুথ সেটিংস খোলো", "bluetooth", "state=open")
        ok("ব্লু টুথ অন করো", "bluetooth", "state=on")
        ok("ব্লুটুথটা বন্ধ করে দাও", "bluetooth", "state=off")
        ok("turn on bluetooth", "bluetooth", "state=on")
        ok("ব্লুটুথ খোলো", "bluetooth", "state=open")
        ok("ব্লুটুথ অফ করো", "bluetooth", "state=off")
        ok("blue tooth on", "bluetooth", "state=on")
    }

    @Test fun screenWake() {
        for (s in listOf(
            "স্ক্রিন জাগাও", "স্ক্রিন চালু করো", "স্ক্রিন অন করো", "ফোন জাগাও", "wake up the screen",
            "turn on the screen", "লক স্ক্রিন দেখাও", "ডিসপ্লে অন করো", "screen on", "স্ক্রিন জ্বালাও", "মোবাইল জাগাও"
        )) ok(s, "screen_wake")
    }

    @Test fun unlockOnlyWakesAndShowsTheLockScreen() {
        ok("ফোন আনলক করো", "screen_wake", "unlock=1")
        ok("unlock the phone", "screen_wake", "unlock=1")
    }

    @Test fun screenLock() {
        for (s in listOf(
            "স্ক্রিন লক করো", "ফোন লক করো", "লক করো", "lock the screen", "lock phone", "স্ক্রিন বন্ধ করো",
            "স্ক্রিন অফ করো", "ডিসপ্লে বন্ধ করো", "মোবাইল লক করে দাও", "lock", "turn off the screen", "screen off",
            "ফোনটা লক করে দাও"
        )) ok(s, "screen_lock")
    }

    @Test fun openApp() {
        ok("ইউটিউব খোলো", "open_app", "app=youtube", "known=true")
        ok("ইউটিউব ওপেন করো", "open_app", "app=youtube")
        ok("open YouTube", "open_app", "app=youtube")
        ok("ফেসবুক চালু করো", "open_app", "app=facebook")
        ok("হোয়াটসঅ্যাপ খুলে দাও", "open_app", "app=whatsapp")
        ok("ক্যামেরা খোলো", "open_app", "app=camera")
        ok("ক্রোম চালাও", "open_app", "app=chrome")
        ok("open calculator", "open_app", "app=calculator")
        ok("সেটিংস খোলো", "open_app", "app=settings")
        ok("গ্যালারি ওপেন করো", "open_app", "app=gallery")
        ok("প্লে স্টোর খোলো", "open_app", "app=playstore")
        ok("ম্যাপ খোলো", "open_app", "app=maps")
        ok("অ্যাপ খোলো ইউটিউব", "open_app", "app=youtube")
        ok("ইউটিউব অ্যাপটা খুলে দাও", "open_app", "app=youtube")
        ok("জিমেইল খোলো", "open_app", "app=gmail")
        ok("ফোন খোলো", "open_app", "app=dialer")
    }

    @Test fun openUnknownAppKeepsTheSpokenName() {
        ok("ফুডপান্ডা খোলো", "open_app", "app=ফুডপান্ডা", "known=false")
    }

    @Test fun cancel() {
        for (s in listOf("থাক", "বাদ দাও", "কিছু না", "cancel", "never mind", "থামো", "না থাক")) ok(s, "cancel")
    }

    @Test fun unknown() {
        ok("আজকের আবহাওয়া কেমন", "unknown", "text=আজকের আবহাওয়া কেমন")
        ok("hello there", "unknown")
        ok("", "unknown")
        ok("   ", "unknown")
    }

    @Test fun twoCommandsInOneSentenceAreFlaggedForPhase6() {
        val r = T.p("ইউটিউব খুলে ভলিউম কমাও")
        Assert.assertEquals("open_app,volume", r.slots["chain"])
        val r2 = T.p("ফ্ল্যাশলাইট জ্বালাও আর ভলিউম কমাও")
        Assert.assertEquals("flashlight,volume", r2.slots["chain"])
    }

    @Test fun singleCommandHasNoChain() {
        Assert.assertNull(T.p("ওয়াইফাই সেটিংস খোলো").slots["chain"])
        Assert.assertNull(T.p("ভোর ৫টায় আমাকে জাগিয়ে দিও").slots["chain"])
        Assert.assertNull(T.p("আলোকে ফোন করো").slots["chain"])
    }

    @Test fun describeSaysWhatWasUnderstood() {
        Assert.assertEquals("ফ্ল্যাশলাইট চালু", Describe.say(T.p("টর্চ জ্বালাও"), true))
        Assert.assertEquals("flashlight off", Describe.say(T.p("flashlight off"), false))
        Assert.assertEquals("অ্যালার্ম আগামীকাল ৬:০০", Describe.say(T.p("কাল সকাল ৬টায় অ্যালার্ম দাও"), true))
        Assert.assertEquals("কল: রহিম", Describe.say(T.p("রহিমকে কল দাও"), true))
    }
}
