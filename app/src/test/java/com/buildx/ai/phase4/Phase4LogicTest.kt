package com.buildx.ai.phase4

import com.buildx.ai.actions.ActionResult
import com.buildx.ai.actions.RoutineBuilder
import com.buildx.ai.nlu.IntentParser
import com.buildx.ai.nlu.Reference
import com.buildx.ai.system.ConversationMemory
import com.buildx.ai.system.Routine
import com.buildx.ai.system.RoutineBook
import com.buildx.ai.system.Trigger
import org.junit.Assert
import org.junit.Test

class ConversationMemoryTest {
    private var now = 0L
    private fun mem() = ConversationMemory({ now }, 10 * 60_000L)

    @Test fun remembersAndForgets() {
        val m = mem()
        m.record("call", mapOf("target" to "রহিম"))
        m.record("wifi", mapOf("state" to "on"))
        m.record("open_app", mapOf("app" to "youtube"))
        Assert.assertEquals("রহিম", m.lastContact()!!.slots["target"])
        Assert.assertEquals("wifi", m.lastToggle()!!.intent)
        Assert.assertEquals("youtube", m.lastOpened()!!.slots["app"])
        Assert.assertEquals("open_app", m.lastStep()!!.intent)
        now += 11 * 60_000L
        Assert.assertNull(m.lastContact()); Assert.assertNull(m.lastToggle()); Assert.assertNull(m.lastStep())
    }
    @Test fun skipsWhatIsNotWorthRepeating() {
        val m = mem()
        m.record("wifi", mapOf("state" to "on"))
        m.record("unknown", emptyMap()); m.record("cancel", emptyMap()); m.record("routine", mapOf("op" to "run"))
        Assert.assertEquals("wifi", m.lastStep()!!.intent)
    }
    @Test fun doesNotKeepWhoSuggestedIt() {
        val m = mem()
        m.record("call", mapOf("target" to "রহিম", "via" to "llm"))
        Assert.assertNull(m.lastContact()!!.slots["via"])
    }
    @Test fun contactNeedsAName() {
        val m = mem()
        m.record("call", emptyMap())
        Assert.assertNull(m.lastContact())
    }
    @Test fun lastTextAndClear() {
        val m = mem()
        m.recordText("  ওয়াইফাই অন করো ")
        Assert.assertEquals("ওয়াইফাই অন করো", m.lastText())
        m.clear()
        Assert.assertNull(m.lastText())
    }
}

class ReferenceTest {
    private fun resolve(text: String, m: ConversationMemory) = Reference.resolve(IntentParser.parseChain(text), text, m)

    @Test fun himAgain() {
        val m = ConversationMemory()
        m.record("call", mapOf("target" to "রহিম"))
        val s = resolve("ওকে আবার কল করো", m)
        Assert.assertEquals("call", s[0].intent); Assert.assertEquals("রহিম", s[0].slots["target"])
    }
    @Test fun himByNumber() {
        val m = ConversationMemory()
        m.record("call", mapOf("number" to "01711223344"))
        val s = resolve("তাকে আবার ফোন করো", m)
        Assert.assertEquals("01711223344", s[0].slots["number"]); Assert.assertNull(s[0].slots["target"])
    }
    @Test fun againWithoutNameUsesTheLastContact() {
        val m = ConversationMemory()
        m.record("whatsapp_call", mapOf("target" to "করিম"))
        val s = resolve("আবার হোয়াটসঅ্যাপে কল করো", m)
        Assert.assertEquals("whatsapp_call", s[0].intent); Assert.assertEquals("করিম", s[0].slots["target"])
    }
    @Test fun messageToHim() {
        val m = ConversationMemory()
        m.record("call", mapOf("target" to "রহিম"))
        val s = resolve("ওকে মেসেজ পাঠাও আমি আসছি", m)
        Assert.assertEquals("send_sms", s[0].intent); Assert.assertEquals("রহিম", s[0].slots["target"]); Assert.assertEquals("আমি আসছি", s[0].slots["text"])
    }
    @Test fun nothingRemembered() {
        val s = resolve("ওকে আবার কল করো", ConversationMemory())
        Assert.assertTrue(Reference.isPronoun(s[0].slots["target"])) // left alone: the call flow will ask who
    }
    @Test fun namedTargetIsNeverReplaced() {
        val m = ConversationMemory()
        m.record("call", mapOf("target" to "রহিম"))
        val s = resolve("করিমকে কল করো", m)
        Assert.assertEquals("করিম", s[0].slots["target"])
    }
    @Test fun repeat() {
        val m = ConversationMemory()
        m.record("volume", mapOf("action" to "set", "level" to "50"))
        val s = resolve("আবার করো", m)
        Assert.assertEquals("volume", s[0].intent); Assert.assertEquals("50", s[0].slots["level"])
        Assert.assertEquals("volume", resolve("do it again", m)[0].intent)
        Assert.assertEquals("unknown", resolve("আবার করো", ConversationMemory())[0].intent)
    }
    @Test fun turnItOff() {
        val m = ConversationMemory()
        m.record("flashlight", mapOf("state" to "on"))
        val s = resolve("ওটা বন্ধ করো", m)
        Assert.assertEquals("flashlight", s[0].intent); Assert.assertEquals("off", s[0].slots["state"])
        Assert.assertEquals("on", resolve("ওটা চালু করো", m)[0].slots["state"])
        Assert.assertEquals("off", resolve("turn that off", m)[0].slots["state"])
    }
    @Test fun opposite() {
        val m = ConversationMemory()
        m.record("wifi", mapOf("state" to "on"))
        Assert.assertEquals("off", resolve("উল্টোটা করো", m)[0].slots["state"])
        val n = ConversationMemory(); n.record("bluetooth", mapOf("state" to "toggle"))
        Assert.assertEquals("unknown", resolve("উল্টোটা করো", n)[0].intent)
    }
    @Test fun openIt() {
        val m = ConversationMemory()
        m.record("open_app", mapOf("app" to "youtube"))
        val s = resolve("ওটা খোলো", m)
        Assert.assertEquals("open_app", s[0].intent); Assert.assertEquals("youtube", s[0].slots["app"])
    }
    @Test fun otherSentencesAreNotHijacked() {
        val m = ConversationMemory()
        m.record("flashlight", mapOf("state" to "on"))
        Assert.assertEquals("unknown", resolve("আজকের ম্যাচের খবর কী", m)[0].intent)
        Assert.assertEquals("wifi", resolve("ওয়াইফাই অন করো", m)[0].intent)
        Assert.assertEquals("unknown", resolve("ওটা ভালো লাগে", m)[0].intent)
    }
}

class RoutineBookTest {
    private val morning = Routine("গুড মর্নিং", listOf("ওয়াইফাই অন করো", "ভলিউম ৫০ করো"), Trigger.Daily(7, 0))
    private val car = Routine("Car Mode", listOf("ব্লুটুথ অন করো"), Trigger.Bluetooth("AA:BB", "JBL", true))
    private val night = Routine("নাইট", listOf("ডু নট ডিস্টার্ব চালু করো"), Trigger.Charger(true))

    @Test fun storageRoundTrip() {
        val l = listOf(morning, car, night, Routine("plain", listOf("টর্চ জ্বালাও")))
        Assert.assertEquals(l, RoutineBook.decode(RoutineBook.encode(l)))
        Assert.assertTrue(RoutineBook.decode("{").isEmpty())
        Assert.assertTrue(RoutineBook.decode("[{\"name\":\"x\",\"steps\":[]}]").isEmpty())
    }
    @Test fun findingByName() {
        val l = listOf(morning, car)
        Assert.assertEquals(morning, RoutineBook.find(l, "গুডমর্নিং"))
        Assert.assertEquals(morning, RoutineBook.find(l, "  গুড  মর্নিং "))
        Assert.assertEquals(car, RoutineBook.find(l, "car mode"))
        Assert.assertEquals(car, RoutineBook.find(l, "carmod")) // one letter off
        Assert.assertNull(RoutineBook.find(l, "কাজ"))
        Assert.assertNull(RoutineBook.find(l, ""))
    }
    @Test fun shortNamesNeedToBeExact() {
        val l = listOf(Routine("কাজ", listOf("x")))
        Assert.assertNull(RoutineBook.find(l, "কাল"))
        Assert.assertNotNull(RoutineBook.find(l, "কাজ"))
    }
    @Test fun matchingInsideASentence() {
        val l = listOf(morning, Routine("মর্নিং ওয়াক", listOf("x")))
        Assert.assertEquals(morning, RoutineBook.match(l, "গুড মর্নিংটা"))
        Assert.assertEquals("মর্নিং ওয়াক", RoutineBook.match(l, "মর্নিং ওয়াক চালাবে")!!.name)
        Assert.assertNull(RoutineBook.match(l, "ইউটিউব"))
    }
    @Test fun putReplacesAndKeepsTheTrigger() {
        val l = RoutineBook.put(listOf(morning), Routine("গুডমর্নিং", listOf("টর্চ জ্বালাও")))
        Assert.assertEquals(1, l.size)
        Assert.assertEquals(listOf("টর্চ জ্বালাও"), l[0].steps)
        Assert.assertEquals(Trigger.Daily(7, 0), l[0].trigger)
        Assert.assertTrue(RoutineBook.remove(l, "গুড মর্নিং").isEmpty())
        Assert.assertNull(RoutineBook.withTrigger(l, "গুড মর্নিং", null)[0].trigger)
    }
    @Test fun limits() {
        var l = emptyList<Routine>()
        for (i in 1..40) l = RoutineBook.put(l, Routine("r$i", listOf("x")))
        Assert.assertEquals(RoutineBook.MAX_ROUTINES, l.size)
        Assert.assertEquals("r40", l.last().name)
    }
    @Test fun whatMayRunByItself() {
        for (i in listOf("wifi", "bluetooth", "volume", "ringer", "brightness", "flashlight", "dnd", "airplane", "mobile_data", "open_app", "weather", "battery", "read_notifications", "media")) Assert.assertTrue(i, RoutineBook.autoSafe(i))
        for (i in listOf("call", "whatsapp_call", "send_sms", "send_whatsapp", "remind", "note", "ui_task", "tap", "unknown", "routine", "take_photo")) Assert.assertFalse(i, RoutineBook.autoSafe(i))
    }
    @Test fun saying() {
        Assert.assertEquals("প্রতিদিন সকাল ৭টা", RoutineBook.sayTrigger(Trigger.Daily(7, 0), true))
        Assert.assertEquals("every day at 7:30 AM", RoutineBook.sayTrigger(Trigger.Daily(7, 30), false))
        Assert.assertEquals("JBL কানেক্ট হলে", RoutineBook.sayTrigger(Trigger.Bluetooth("a", "JBL", true), true))
        Assert.assertEquals("when plugged in", RoutineBook.sayTrigger(Trigger.Charger(true), false))
        Assert.assertEquals("২টা রুটিন। গুড মর্নিং (প্রতিদিন সকাল ৭টা)। নাইট (চার্জে দিলে)", RoutineBook.sayList(listOf(morning, night), true))
        Assert.assertTrue(RoutineBook.sayList(emptyList(), true).startsWith("কোনো রুটিন নেই"))
    }
}

class RoutineBuilderTest {
    private var saved: List<Routine> = emptyList()
    private val mem = ConversationMemory()
    private fun builder() = RoutineBuilder(true, { saved }, { saved = it }, mem)
    private fun say(r: ActionResult, text: String?): ActionResult {
        Assert.assertNotNull("expected a question, got \"${r.message}\"", r.followUp)
        return r.followUp!!.onReply(text)
    }

    @Test fun buildsStepByStep() {
        var r = builder().start("গুড মর্নিং", false)
        Assert.assertTrue(r.message.contains("প্রথম কাজ"))
        r = say(r, "ওয়াইফাই অন করো")
        Assert.assertTrue(r.message.startsWith("যোগ করেছি: "))
        r = say(r, "ভলিউম ৫০ করো আর টর্চ জ্বালাও")
        r = say(r, "শেষ")
        Assert.assertTrue(r.success)
        Assert.assertTrue(r.message, r.message.contains("সেভ হয়েছে, ২টা ধাপ"))
        Assert.assertEquals("গুড মর্নিং", saved[0].name)
        Assert.assertEquals(listOf("ওয়াইফাই অন করো", "ভলিউম ৫০ করো আর টর্চ জ্বালাও"), saved[0].steps)
    }
    @Test fun asksForTheName() {
        var r = builder().start("", false)
        Assert.assertEquals("রুটিনের নাম কী?", r.message)
        r = say(r, "নাইট")
        r = say(r, "টর্চ জ্বালাও")
        r = say(r, "done")
        Assert.assertEquals("নাইট", saved[0].name)
    }
    @Test fun stepsAreNotRunWhileBuilding() {
        // nothing but parsing: a call step is accepted into the routine without calling anyone
        var r = builder().start("x", false)
        r = say(r, "রহিমকে কল করো")
        r = say(r, "শেষ")
        Assert.assertEquals(listOf("রহিমকে কল করো"), saved[0].steps)
    }
    @Test fun notUnderstoodTwiceCancels() {
        var r = builder().start("x", false)
        r = say(r, "আজকের ম্যাচের খবর কী")
        Assert.assertTrue(r.message.contains("বুঝতে পারিনি"))
        r = say(r, "আজকের ম্যাচের খবর কী")
        Assert.assertFalse(r.success)
        Assert.assertTrue(saved.isEmpty())
    }
    @Test fun aNotUnderstoodStepIsNotSavedButTheRetryWorks() {
        var r = builder().start("x", false)
        r = say(r, "আজকের ম্যাচের খবর কী")
        r = say(r, "ওয়াইফাই অন করো")
        r = say(r, "শেষ")
        Assert.assertEquals(listOf("ওয়াইফাই অন করো"), saved[0].steps)
    }
    @Test fun routinesCannotContainRoutines() {
        var r = builder().start("x", false)
        r = say(r, "গুড মর্নিং রুটিন চালাও")
        Assert.assertTrue(r.message.contains("বুঝতে পারিনি"))
    }
    @Test fun nothingAddedMeansNoRoutine() {
        val r = say(builder().start("x", false), "শেষ")
        Assert.assertFalse(r.success)
        Assert.assertTrue(saved.isEmpty())
    }
    @Test fun noCancelsAndSilenceKeepsWhatWeHave() {
        var r = builder().start("x", false)
        r = say(r, "না")
        Assert.assertTrue(saved.isEmpty())
        r = builder().start("y", false)
        r = say(r, "টর্চ জ্বালাও")
        r = say(r, null)
        Assert.assertEquals(listOf("টর্চ জ্বালাও"), saved[0].steps)
    }
    @Test fun theLastCommand() {
        mem.recordText("ওয়াইফাই অন করো")
        var r = builder().start("কাজ", true)
        r = say(r, "ভলিউম ৫০ করো")
        r = say(r, "শেষ")
        Assert.assertEquals(listOf("ওয়াইফাই অন করো", "ভলিউম ৫০ করো"), saved[0].steps)
        Assert.assertFalse(RoutineBuilder(true, { saved }, { saved = it }, ConversationMemory()).start("কাজ", true).success)
    }
    @Test fun sayingLastInsideTheLoop() {
        mem.recordText("টর্চ জ্বালাও")
        var r = builder().start("z", false)
        r = say(r, "আগেরটা")
        r = say(r, "শেষ")
        Assert.assertEquals(listOf("টর্চ জ্বালাও"), saved[0].steps)
    }
    @Test fun replacingAnExistingRoutine() {
        saved = listOf(Routine("কাজ", listOf("old"), Trigger.Charger(true)))
        var r = builder().start("কাজ", false)
        r = say(r, "টর্চ জ্বালাও")
        r = say(r, "শেষ")
        Assert.assertTrue(r.message.contains("বদলে দিলাম"))
        Assert.assertEquals(1, saved.size)
        Assert.assertEquals(Trigger.Charger(true), saved[0].trigger)
    }
    @Test fun stopsAtTheStepLimit() {
        var r = builder().start("লম্বা", false)
        for (i in 1..RoutineBook.MAX_STEPS) r = say(r, "টর্চ জ্বালাও")
        Assert.assertTrue(r.message, r.message.contains("সেভ হয়েছে"))
        Assert.assertEquals(RoutineBook.MAX_STEPS, saved[0].steps.size)
    }
}
