package com.buildx.ai.phase3

import com.buildx.ai.actions.ActionResult
import com.buildx.ai.call.ContactSource
import com.buildx.ai.call.MessageFlow
import com.buildx.ai.call.MessageSender
import com.buildx.ai.call.WaNumber
import com.buildx.ai.contacts.ContactEntry
import com.buildx.ai.contacts.PhoneEntry
import com.buildx.ai.system.NoteBook
import com.buildx.ai.system.NotifItem
import com.buildx.ai.system.Places
import com.buildx.ai.system.Rates
import com.buildx.ai.system.Reminder
import com.buildx.ai.system.Reminders
import com.buildx.ai.system.SmsItem
import com.buildx.ai.system.Speakable
import com.buildx.ai.system.Units
import com.buildx.ai.system.Weather
import org.junit.Assert
import org.junit.Test
import java.util.Calendar

class UnitsTest {
    @Test fun lengthMassTemperature() {
        Assert.assertEquals(6.2137, Units.convert(10.0, "km", "mi")!!, 0.001)
        Assert.assertEquals(11.0231, Units.convert(5.0, "kg", "lb")!!, 0.001)
        Assert.assertEquals(86.0, Units.convert(30.0, "c", "f")!!, 0.0001)
        Assert.assertEquals(0.0, Units.convert(32.0, "f", "c")!!, 0.0001)
        Assert.assertEquals(93.3105, Units.convert(2.5, "maund", "kg")!!, 0.001)
        Assert.assertEquals(500.0, Units.convert(0.5, "kg", "g")!!, 0.0001)
        Assert.assertEquals(2.0, Units.convert(2048.0, "kb", "mb")!!, 0.0001)
    }
    @Test fun mismatchAndMoneyAreNotConvertedHere() {
        Assert.assertNull(Units.convert(1.0, "km", "kg"))
        Assert.assertNull(Units.convert(1.0, "usd", "bdt"))
        Assert.assertNull(Units.convert(1.0, "km", "nope"))
        Assert.assertFalse(Units.sameKind("km", "usd"))
        Assert.assertTrue(Units.sameKind("usd", "bdt"))
    }
    @Test fun defaults() {
        Assert.assertEquals("mi", Units.defaultTarget("km"))
        Assert.assertEquals("bdt", Units.defaultTarget("usd"))
        Assert.assertEquals("usd", Units.defaultTarget("bdt"))
        Assert.assertEquals("kg", Units.defaultTarget("maund"))
    }
    @Test fun saying() {
        Assert.assertEquals("১০ কিলোমিটার = ৬.২১ মাইল", Units.say(10.0, "km", 6.2137, "mi", true))
        Assert.assertEquals("1 kilometer = 0.62 miles", Units.say(1.0, "km", 0.6214, "mi", false))
        Assert.assertEquals("5 kilograms = 11.02 pounds", Units.say(5.0, "kg", 11.0231, "lb", false))
        Assert.assertEquals("0", Units.format(0.0, false))
        Assert.assertEquals("১২২০০", Units.format(12200.0, true))
        Assert.assertEquals("0.0004", Units.format(0.0004, false))
    }
    @Test fun parseValue() {
        Assert.assertEquals(2.5, Units.parseValue("2.5")!!, 0.0)
        Assert.assertNull(Units.parseValue("abc"))
        Assert.assertNull(Units.parseValue(null))
    }
}

class RatesTest {
    private val body = """{"result":"success","base_code":"USD","rates":{"USD":1,"BDT":122.5,"EUR":0.92,"GBP":0.79}}"""
    @Test fun parseAndConvert() {
        val r = Rates.parse(body)!!
        Assert.assertEquals(12250.0, Rates.convert(r, 100.0, "usd", "bdt")!!, 0.001)
        Assert.assertEquals(100.0 / 0.92 * 122.5, Rates.convert(r, 100.0, "eur", "bdt")!!, 0.001)
        Assert.assertNull(Rates.convert(r, 1.0, "usd", "kwd"))
    }
    @Test fun rejectsBadAnswers() {
        Assert.assertNull(Rates.parse("not json"))
        Assert.assertNull(Rates.parse("""{"result":"error"}"""))
        Assert.assertNull(Rates.parse("""{"result":"success","rates":{"BDT":1}}"""))
    }
}

class PlacesTest {
    @Test fun districtsAndSuffixes() {
        Assert.assertEquals("Dhaka", Places.english("ঢাকা"))
        Assert.assertEquals("Dhaka", Places.english("ঢাকার"))
        Assert.assertEquals("Dhaka", Places.english("ঢাকায়"))
        Assert.assertEquals("Sirajganj", Places.english("সিরাজগঞ্জে"))
        Assert.assertEquals("Sirajganj", Places.english("সিরাজগঞ্জের"))
        Assert.assertEquals("Chittagong", Places.english("চট্টগ্রামে"))
        Assert.assertEquals("paris", Places.english("paris"))
        Assert.assertEquals("ঢাকা", Places.base("ঢাকার"))
        Assert.assertEquals("কোথাও", Places.base("কোথাও"))
    }
}

class WeatherTest {
    private val forecast = """{"current":{"temperature_2m":29.4,"weather_code":2},
        "daily":{"weather_code":[61,3],"temperature_2m_max":[32.2,30.1],"temperature_2m_min":[25.0,24.6],"precipitation_probability_max":[40,80]}}"""
    @Test fun today() {
        Assert.assertEquals("ঢাকা-এ এখন ২৯ ডিগ্রি, আংশিক মেঘলা। আজ সর্বোচ্চ ৩২, সর্বনিম্ন ২৫ ডিগ্রি। বৃষ্টির সম্ভাবনা ৪০ শতাংশ।", Weather.say(forecast, "ঢাকা", false, true))
        Assert.assertEquals("In Dhaka right now 29 degrees, partly cloudy. Today: High 32, low 25 degrees. Chance of rain 40 percent.", Weather.say(forecast, "Dhaka", false, false))
    }
    @Test fun tomorrow() {
        Assert.assertEquals("ঢাকা-এ আগামীকাল মেঘলা। সর্বোচ্চ ৩০, সর্বনিম্ন ২৫ ডিগ্রি। বৃষ্টির সম্ভাবনা ৮০ শতাংশ।", Weather.say(forecast, "ঢাকা", true, true))
    }
    @Test fun badBody() {
        Assert.assertNull(Weather.say("nope", "x", false, true))
        Assert.assertNull(Weather.say("{}", "x", false, true))
    }
    @Test fun geo() {
        val g = Weather.parseGeo("""{"results":[{"name":"Dhaka","latitude":23.7,"longitude":90.4,"country":"Bangladesh"}]}""")!!
        Assert.assertEquals("Dhaka", g.name)
        Assert.assertEquals(23.7, g.lat, 0.0)
        Assert.assertNull(Weather.parseGeo("""{"generationtime_ms":0.1}"""))
        Assert.assertTrue(Weather.geoUrl("Cox's Bazar").contains("name=Cox%27s+Bazar"))
    }
}

class RemindersTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Calendar =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, 0) }

    private fun slots(vararg p: Pair<String, String>) = mapOf(*p)

    @Test fun relative() {
        val now = at(2026, 10, 21, 10, 0)
        Assert.assertEquals(now.timeInMillis + 10 * 60_000L, Reminders.triggerAt(now, slots("in_minutes" to "10")))
        Assert.assertNull(Reminders.triggerAt(now, slots("in_minutes" to "0")))
    }
    @Test fun tomorrowMorning() {
        val now = at(2026, 10, 21, 22, 30)
        val t = Reminders.triggerAt(now, slots("hour" to "9", "minute" to "0", "ambiguous" to "false", "when" to "tomorrow"))!!
        Assert.assertEquals(at(2026, 10, 22, 9, 0).timeInMillis, t)
    }
    @Test fun dayAfter() {
        val now = at(2026, 10, 21, 10, 0)
        val t = Reminders.triggerAt(now, slots("hour" to "8", "minute" to "30", "ambiguous" to "false", "when" to "day_after"))!!
        Assert.assertEquals(at(2026, 10, 23, 8, 30).timeInMillis, t)
    }
    @Test fun autoPicksTheNextOccurrence() {
        val now = at(2026, 10, 21, 10, 0)
        Assert.assertEquals(at(2026, 10, 21, 17, 0).timeInMillis, Reminders.triggerAt(now, slots("hour" to "17", "minute" to "0", "ambiguous" to "false", "when" to "auto")))
        Assert.assertEquals(at(2026, 10, 22, 9, 0).timeInMillis, Reminders.triggerAt(now, slots("hour" to "9", "minute" to "0", "ambiguous" to "false", "when" to "auto")))
        // "5টায়" said at 10:00 without am/pm: 5 pm today
        Assert.assertEquals(at(2026, 10, 21, 17, 0).timeInMillis, Reminders.triggerAt(now, slots("hour" to "5", "minute" to "0", "ambiguous" to "true", "when" to "auto")))
    }
    @Test fun todayThatHasPassedIsRefused() {
        val now = at(2026, 10, 21, 22, 0)
        Assert.assertNull(Reminders.triggerAt(now, slots("hour" to "9", "minute" to "0", "ambiguous" to "false", "when" to "today")))
        Assert.assertNull(Reminders.triggerAt(now, slots("minute" to "0")))
        Assert.assertNull(Reminders.triggerAt(now, slots("hour" to "30")))
    }
    @Test fun sayingWhen() {
        val now = at(2026, 10, 21, 10, 0)
        Assert.assertEquals("আগামীকাল সকাল ৯টা", Reminders.sayWhen(at(2026, 10, 22, 9, 0).timeInMillis, now, true))
        Assert.assertEquals("today 5:30 PM", Reminders.sayWhen(at(2026, 10, 21, 17, 30).timeInMillis, now, false))
        Assert.assertEquals("পরশু রাত ১০টা", Reminders.sayWhen(at(2026, 10, 23, 22, 0).timeInMillis, now, true))
    }
    @Test fun storage() {
        val list = listOf(Reminder(1, 1000, "চা"), Reminder(3, 500, "call \"mom\""))
        Assert.assertEquals(list, Reminders.decode(Reminders.encode(list)))
        Assert.assertEquals(4, Reminders.nextId(list))
        Assert.assertEquals(1, Reminders.nextId(emptyList()))
        Assert.assertTrue(Reminders.decode("garbage").isEmpty())
        Assert.assertEquals(listOf(1), Reminders.upcoming(list, 700).map { it.id })
    }
}

class NoteBookTest {
    @Test fun addAndRead() {
        var n = emptyList<String>()
        n = NoteBook.add(n, "  দুধ কিনতে হবে ")
        n = NoteBook.add(n, "বিল দিতে হবে")
        n = NoteBook.add(n, "   ")
        Assert.assertEquals(2, n.size)
        Assert.assertEquals("মোট ২টা নোট। ১. বিল দিতে হবে। ২. দুধ কিনতে হবে", NoteBook.say(n, 5, true))
        Assert.assertEquals("2 notes. 1. বিল দিতে হবে. 2. দুধ কিনতে হবে", NoteBook.say(n, 5, false))
        Assert.assertEquals("কোনো নোট নেই", NoteBook.say(emptyList(), 5, true))
    }
    @Test fun limitsAndStorage() {
        var n = emptyList<String>()
        for (i in 1..150) n = NoteBook.add(n, "n$i")
        Assert.assertEquals(NoteBook.MAX_NOTES, n.size)
        Assert.assertEquals("n150", n.last())
        Assert.assertEquals(n, NoteBook.decode(NoteBook.encode(n)))
        Assert.assertTrue(NoteBook.decode("{").isEmpty())
        Assert.assertEquals(NoteBook.MAX_LEN, NoteBook.add(emptyList(), "x".repeat(900))[0].length)
    }
}

class SpeakableTest {
    @Test fun sms() {
        val items = listOf(SmsItem("রহিম", "আসছি", 100), SmsItem("Bank", "Your OTP is 123456", 200))
        Assert.assertEquals("২টা মেসেজ। Bank লিখেছেন: Your OTP is 123456। রহিম লিখেছেন: আসছি।", Speakable.sms(items, 3, 200, true))
        Assert.assertEquals("One message. রহিম says: আসছি.", Speakable.sms(items.take(1), 3, 200, false))
        Assert.assertEquals("নতুন কোনো মেসেজ নেই", Speakable.sms(emptyList(), 3, 200, true))
    }
    @Test fun smsIsCutAndLimited() {
        val items = (1..5).map { SmsItem("A$it", "x".repeat(300), it.toLong()) }
        val s = Speakable.sms(items, 2, 20, false)
        Assert.assertTrue(s.startsWith("5 messages. The newest 2: A5 says: "))
        Assert.assertTrue(s.contains("…"))
        Assert.assertFalse(s.contains("A3"))
    }
    @Test fun notifications() {
        val items = listOf(NotifItem("WhatsApp", "রহিম", "কোথায় তুমি"), NotifItem("Gmail", "", ""), NotifItem("Facebook", "Karim", ""))
        Assert.assertEquals("২টা নোটিফিকেশন. WhatsApp, রহিম: কোথায় তুমি. Facebook, Karim.", Speakable.notifications(items, 5, 120, true).replace("।", "."))
        Assert.assertEquals("কোনো নতুন নোটিফিকেশন নেই", Speakable.notifications(listOf(NotifItem("a", "", "")), 5, 120, true))
    }
    @Test fun caller() {
        Assert.assertEquals("রহিম কল করছেন", Speakable.caller("রহিম", true, true))
        Assert.assertEquals("Someone is calling, but I couldn't get the name", Speakable.caller(null, true, false))
        Assert.assertEquals("এখন কোনো কল আসছে না", Speakable.caller("", false, true))
    }
}

class WaNumberTest {
    @Test fun numbers() {
        Assert.assertEquals("8801711223344", WaNumber.digits("01711223344"))
        Assert.assertEquals("8801711223344", WaNumber.digits("+880 1711-223344"))
        Assert.assertEquals("8801711223344", WaNumber.digits("8801711223344"))
        Assert.assertEquals("8801711223344", WaNumber.digits("008801711223344"))
        Assert.assertEquals("14155550100", WaNumber.digits("+1 415 555 0100"))
        Assert.assertNull(WaNumber.digits("123"))
    }
}

class MessageFlowTest {
    private fun c(id: String, name: String, vararg nums: Pair<String, String>) = ContactEntry(id, name, nums.map { PhoneEntry(it.first, it.second) })
    private class Source(val list: List<ContactEntry>?) : ContactSource { override fun all() = list }
    private class Sender(val ok: Boolean = true) : MessageSender {
        val sent = ArrayList<String>()
        override fun send(number: String, label: String, text: String): ActionResult { sent.add("$label|$number|$text"); return ActionResult(ok, if (ok) "পাঠিয়েছি" else "যায়নি") }
    }
    private val book = listOf(
        c("1", "রহিম উদ্দিন", "01711000001" to "mobile"),
        c("2", "Karim Hossain", "01711000002" to "mobile", "031-123456" to "home"),
        c("3", "Rakib Hasan", "01711000003" to "mobile"),
        c("4", "Nasir Hasan", "01711000004" to "mobile")
    )
    private fun flow(s: Sender, bn: Boolean = true, src: List<ContactEntry>? = book) = MessageFlow(bn, Source(src), s)
    private fun reply(r: ActionResult, text: String?): ActionResult {
        Assert.assertNotNull("expected a question but got \"${r.message}\"", r.followUp)
        return r.followUp!!.onReply(text)
    }

    @Test fun readsBackAndSendsOnYes() {
        val s = Sender()
        val q = flow(s).start("রহিম", null, "আমি আসছি")
        Assert.assertEquals("রহিম উদ্দিনকে এসএমএস লিখব: আমি আসছি। পাঠাব?", q.message)
        Assert.assertTrue(s.sent.isEmpty())
        val done = reply(q, "হ্যাঁ")
        Assert.assertTrue(done.success)
        Assert.assertEquals(listOf("রহিম উদ্দিন|01711000001|আমি আসছি"), s.sent)
    }
    @Test fun neverSendsOnNo() {
        val s = Sender()
        val done = reply(flow(s).start("রহিম", null, "আমি আসছি"), "না")
        Assert.assertEquals("ঠিক আছে। মেসেজ পাঠাইনি", done.message)
        Assert.assertTrue(s.sent.isEmpty())
    }
    @Test fun neverSendsOnSilenceOrNonsense() {
        val s = Sender()
        Assert.assertFalse(reply(flow(s).start("রহিম", null, "hi"), null).success)
        val again = reply(flow(s).start("রহিম", null, "hi"), "আবহাওয়া")
        Assert.assertNotNull(again.followUp) // asks once more
        Assert.assertFalse(reply(again, "আবহাওয়া").success)
        Assert.assertTrue(s.sent.isEmpty())
    }
    @Test fun asksWhatToWrite() {
        val s = Sender()
        val ask = flow(s).start("রহিম", null, null)
        Assert.assertEquals("কী লিখব?", ask.message)
        val q = reply(ask, "দেরি হবে")
        Assert.assertTrue(q.message.contains("দেরি হবে"))
        Assert.assertTrue(reply(q, "জি").success)
        Assert.assertEquals(1, s.sent.size)
    }
    @Test fun asksWho() {
        val s = Sender()
        val ask = flow(s).start(null, null, "hello")
        Assert.assertEquals("কাকে মেসেজ পাঠাব?", ask.message)
        val q = reply(ask, "রহিম")
        Assert.assertTrue(q.message.startsWith("রহিম উদ্দিনকে"))
    }
    @Test fun severalNumbersAsksWhich() {
        val s = Sender()
        val ask = flow(s).start("করিম", null, "hello")
        Assert.assertTrue(ask.message.contains("২টি নম্বর"))
        val q = reply(ask, "১")
        Assert.assertTrue(q.message.contains("hello"))
        reply(q, "yes")
        Assert.assertEquals(listOf("Karim Hossain|01711000002|hello"), s.sent)
    }
    @Test fun severalContactsAsksWhich() {
        val s = Sender()
        val ask = flow(s).start("হাসান", null, "hello")
        Assert.assertTrue("got: ${ask.message}", ask.message.contains("জন আছে"))
        val q = reply(ask, "২")
        Assert.assertTrue(reply(q, "হ্যাঁ").success)
        Assert.assertEquals(1, s.sent.size)
    }
    @Test fun numberGivenDirectly() {
        val s = Sender()
        val q = flow(s).start(null, "01711223344", "hi")
        Assert.assertTrue(q.message.contains("পাঠাব?"))
        reply(q, "হ্যাঁ")
        Assert.assertTrue(s.sent[0], s.sent[0].endsWith("|01711223344|hi"))
    }
    @Test fun failures() {
        val s = Sender()
        Assert.assertEquals("কন্টাক্ট দেখার পারমিশন নেই। অ্যাপ খুলে পারমিশন দিন", flow(s, src = null).start("রহিম", null, "hi").message)
        Assert.assertEquals("রাজু নামে কন্টাক্ট পাইনি", flow(s).start("রাজু", null, "hi").message)
        Assert.assertFalse(flow(s).start("রহিম", "12", "hi").success)
        val tooLong = flow(s).start("রহিম", null, "x".repeat(600))
        Assert.assertFalse(tooLong.success)
        Assert.assertTrue(s.sent.isEmpty())
    }
    @Test fun failedSendIsReported() {
        val s = Sender(ok = false)
        val done = reply(flow(s).start("রহিম", null, "hi"), "হ্যাঁ")
        Assert.assertFalse(done.success)
    }
    @Test fun whatsappWording() {
        val s = Sender()
        val f = MessageFlow(false, Source(book), s, channelBn = "WhatsApp-এ", channelEn = "on WhatsApp")
        Assert.assertEquals("Message রহিম উদ্দিন on WhatsApp: hi. Send it?", f.start("রহিম", null, "hi").message)
    }
}
