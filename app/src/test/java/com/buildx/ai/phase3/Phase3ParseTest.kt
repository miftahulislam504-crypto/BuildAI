package com.buildx.ai.phase3

import com.buildx.ai.nlu.IntentParser
import com.buildx.ai.nlu.T.ok
import org.junit.Assert
import org.junit.Test

class MessageParseTest {
    @Test fun bengaliSms() {
        ok("রহিমকে মেসেজ পাঠাও আমি আসছি", "send_sms", "target=রহিম", "text=আমি আসছি")
        ok("রহিমকে মেসেজ করো যে আমি দেরি করব", "send_sms", "target=রহিম", "text=আমি দেরি করব")
        ok("রহিমকে এসএমএস পাঠাও আমি রাস্তায়", "send_sms", "target=রহিম", "text=আমি রাস্তায়")
    }
    @Test fun whatsapp() {
        ok("রহিমকে হোয়াটসঅ্যাপে মেসেজ পাঠাও আমি আসছি", "send_whatsapp", "target=রহিম", "text=আমি আসছি")
        ok("হোয়াটসঅ্যাপে রহিমকে পাঠাও আমি আসছি", "send_whatsapp", "target=রহিম", "text=আমি আসছি")
    }
    @Test fun english() {
        ok("send message to Rahim saying I am late", "send_sms", "target=rahim", "text=i am late")
        ok("send a whatsapp message to Rahim saying hello", "send_whatsapp", "target=rahim", "text=hello")
        ok("send message to rahim on whatsapp saying see you", "send_whatsapp", "target=rahim", "text=see you")
    }
    @Test fun missingPieces() {
        val p = IntentParser.parse("মেসেজ পাঠাও")
        Assert.assertEquals("send_sms", p.intent)
        Assert.assertNull(p.slots["target"])
        Assert.assertNull(p.slots["text"])
        ok("রহিমকে মেসেজ পাঠাও", "send_sms", "target=রহিম")
    }
    @Test fun numberTarget() = ok("০১৭১১২২৩৩৪৪ নম্বরে মেসেজ পাঠাও আমি আসছি", "send_sms", "number=01711223344", "text=আমি আসছি")
    @Test fun bodyKeepsSeparatorWords() {
        val steps = IntentParser.parseChain("রহিমকে মেসেজ পাঠাও রুটি আর ডিম আনবে")
        Assert.assertEquals(1, steps.size)
        Assert.assertEquals("রুটি আর ডিম আনবে", steps[0].slots["text"])
    }
    @Test fun notMessages() {
        ok("মেসেজ অ্যাপ খোলো", "open_app", "app=messages")
        ok("মেসেজ খোলো", "open_app", "app=messages")
        ok("রহিমকে ফোন করো", "call", "target=রহিম")
        ok("রহিমকে হোয়াটসঅ্যাপে কল করো", "whatsapp_call", "target=রহিম")
        ok("হোয়াটসঅ্যাপ খোলো", "open_app", "app=whatsapp")
    }
    @Test fun chainBeforeMessage() {
        val steps = IntentParser.parseChain("ভলিউম কমাও আর রহিমকে মেসেজ পাঠাও আমি আসছি")
        Assert.assertEquals(listOf("volume", "send_sms"), steps.map { it.intent })
    }
    @Test fun readMessages() {
        ok("নতুন মেসেজ পড়ো", "read_sms")
        ok("মেসেজ পড়ে শোনাও", "read_sms")
        ok("read my messages", "read_sms")
    }
}

class NoteRemindParseTest {
    @Test fun notes() {
        ok("নোট করো দুধ কিনতে হবে", "note", "op=add", "text=দুধ কিনতে হবে")
        ok("make a note buy milk", "note", "op=add", "text=buy milk")
        ok("আমার নোটগুলো পড়ো", "note", "op=read")
        ok("নোট দেখাও", "note", "op=read")
        ok("সব নোট মুছে ফেলো", "note", "op=clear")
    }
    @Test fun noteKeepsSeparatorWords() {
        val steps = IntentParser.parseChain("রুটি আর ডিম নোট করো")
        Assert.assertEquals(1, steps.size)
        Assert.assertEquals("note", steps[0].intent)
        Assert.assertEquals("রুটি আর ডিম", steps[0].slots["text"])
    }
    @Test fun reminders() {
        ok("কাল সকাল ৯টায় মিটিংয়ের কথা মনে করিয়ে দাও", "remind", "hour=9", "minute=0", "when=tomorrow", "ambiguous=false", "text=মিটিংয়ের কথা")
        ok("১০ মিনিট পরে মনে করিয়ে দাও চা বসাতে", "remind", "in_minutes=10", "text=চা বসাতে")
        ok("remind me to call mom at 5 pm", "remind", "hour=17", "minute=0", "text=call mom")
        ok("remind me in 30 minutes to take the clothes", "remind", "in_minutes=30", "text=take the clothes")
    }
    @Test fun reminderWithoutTime() {
        val p = IntentParser.parse("রহিমকে ফোন করতে মনে করিয়ে দাও")
        Assert.assertEquals("remind", p.intent)
        Assert.assertNull(p.slots["hour"])
        Assert.assertNull(p.slots["in_minutes"])
    }
    @Test fun calendar() {
        ok("ক্যালেন্ডারে মিটিং যোগ করো কাল বিকাল ৪টায়", "calendar_event", "hour=16", "minute=0", "when=tomorrow", "title=মিটিং")
        ok("add meeting to calendar tomorrow 4 pm", "calendar_event", "hour=16", "when=tomorrow", "title=meeting")
        ok("ক্যালেন্ডার খোলো", "open_app", "app=calendar")
    }
}

class WeatherConvertParseTest {
    @Test fun weather() {
        ok("আজকের আবহাওয়া কেমন", "weather", "when=today")
        ok("ঢাকার আবহাওয়া কেমন", "weather", "city=ঢাকার")
        ok("কাল সিরাজগঞ্জে বৃষ্টি হবে", "weather", "city=সিরাজগঞ্জে", "when=tomorrow")
        ok("what is the weather in dhaka", "weather", "city=dhaka")
        ok("গুগলে আবহাওয়া সার্চ করো", "web_search")
    }
    @Test fun units() {
        ok("১০ কিলোমিটার কত মাইল", "convert", "value=10", "from=km", "to=mi")
        ok("৫ কেজি কত পাউন্ড", "convert", "value=5", "from=kg", "to=lb")
        ok("convert 5 km to miles", "convert", "value=5", "from=km", "to=mi")
        ok("৩০ ডিগ্রি সেলসিয়াস কত ফারেনহাইট", "convert", "value=30", "from=c", "to=f")
        ok("আধা কেজি কত গ্রাম", "convert", "value=0.5", "from=kg", "to=g")
        ok("২ দশমিক ৫ মণ কত কেজি", "convert", "value=2.5", "from=maund", "to=kg")
    }
    @Test fun money() {
        ok("১০০ ডলার কত টাকা", "convert", "value=100", "from=usd", "to=bdt")
        ok("১০০ ডলার কত", "convert", "value=100", "from=usd")
        ok("কত টাকা ৫০০ রিয়াল", "convert", "value=500", "from=sar", "to=bdt")
        ok("১০ পাউন্ড কত টাকা", "convert", "value=10", "from=gbp", "to=bdt")
    }
    @Test fun notConversions() {
        ok("ভলিউম ৫০ করো", "volume", "action=set", "level=50")
        ok("ব্যাটারি কত", "battery")
        ok("৫ মিনিট পরে অ্যালার্ম দাও", "alarm", "in_minutes=5")
    }
}

class SwitchParseTest {
    @Test fun switches() {
        ok("ডু নট ডিস্টার্ব চালু করো", "dnd", "state=on")
        ok("DND off", "dnd", "state=off")
        ok("এয়ারপ্লেন মোড অন করো", "airplane", "state=on")
        ok("flight mode off", "airplane", "state=off")
        ok("মোবাইল ডেটা বন্ধ করো", "mobile_data", "state=off")
        ok("turn on mobile data", "mobile_data", "state=on")
        ok("হটস্পট চালু করো", "hotspot", "state=on")
        ok("ওয়াইফাই হটস্পট অন করো", "hotspot", "state=on")
        ok("ওয়াইফাই চালু করো", "wifi", "state=on")
    }
    @Test fun readNotificationsAndCaller() {
        ok("নোটিফিকেশন পড়ে শোনাও", "read_notifications")
        ok("read my notifications", "read_notifications")
        ok("নোটিফিকেশন দেখাও", "notifications")
        ok("কে কল করছে", "who_calling")
        ok("who is calling", "who_calling")
    }
}
