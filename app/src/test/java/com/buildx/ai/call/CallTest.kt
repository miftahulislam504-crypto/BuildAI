package com.buildx.ai.call

import com.buildx.ai.actions.ActionResult
import com.buildx.ai.contacts.ContactEntry
import com.buildx.ai.contacts.PhoneEntry
import com.buildx.ai.nlu.Reply
import org.junit.Assert
import org.junit.Test

class ReplyTest {
    private fun yn(s: String?) = Reply.yesNo(s)

    @Test fun yes() {
        for (s in listOf("হ্যাঁ", "জি", "হ্যা", "ঠিক আছে", "করো", "yes", "okay", "sure", "জি হ্যাঁ", "অবশ্যই"))
            Assert.assertEquals(s, Reply.YesNo.YES, yn(s))
    }
    @Test fun no() {
        for (s in listOf("না", "থাক", "no", "cancel", "করো না", "না না", "বাদ দাও"))
            Assert.assertEquals(s, Reply.YesNo.NO, yn(s))
    }
    @Test fun unknown() {
        Assert.assertEquals(Reply.YesNo.UNKNOWN, yn("আবহাওয়া"))
        Assert.assertEquals(Reply.YesNo.UNKNOWN, yn(null))
        Assert.assertEquals(Reply.YesNo.UNKNOWN, yn("  "))
    }
    @Test fun pickByNumber() {
        Assert.assertEquals(0, Reply.pick("১", 3))
        Assert.assertEquals(1, Reply.pick("2", 3))
        Assert.assertEquals(1, Reply.pick("দুই", 3))
        Assert.assertEquals(2, Reply.pick("তিন নম্বর", 3))
        Assert.assertEquals(1, Reply.pick("দুইটা", 3))
    }
    @Test fun pickByOrdinal() {
        Assert.assertEquals(0, Reply.pick("প্রথম", 3))
        Assert.assertEquals(0, Reply.pick("প্রথমটা", 3))
        Assert.assertEquals(1, Reply.pick("দ্বিতীয়টা", 3))
        Assert.assertEquals(2, Reply.pick("third", 3))
        Assert.assertEquals(2, Reply.pick("শেষটা", 3))
    }
    @Test fun pickOutOfRangeOrUnclear() {
        Assert.assertNull(Reply.pick("৫", 3))
        Assert.assertNull(Reply.pick("রহিম", 3))
        Assert.assertNull(Reply.pick(null, 3))
    }
}

class CallTrackerTest {
    @Test fun reportsDurationAfterAnswer() {
        val t = CallTracker()
        t.expectOutgoing("রহিম", true, 0)
        Assert.assertNull(t.onState(CallTracker.OFFHOOK, 3_000))
        Assert.assertEquals("কল শেষ। মোট ২ মিনিট ১০ সেকেন্ড", t.onState(CallTracker.IDLE, 3_000 + 130_000))
        Assert.assertTrue(!t.waiting())
    }
    @Test fun veryShortMeansFailed() {
        val t = CallTracker()
        t.expectOutgoing("রহিম", true, 0)
        t.onState(CallTracker.OFFHOOK, 1_000)
        Assert.assertEquals("রহিম কে কল লাগেনি, বা সাথে সাথে কেটে গেছে", t.onState(CallTracker.IDLE, 2_500))
    }
    @Test fun english() {
        val t = CallTracker()
        t.expectOutgoing("Rahim", false, 0)
        t.onState(CallTracker.OFFHOOK, 0)
        Assert.assertEquals("Call ended. 1 min 5 sec", t.onState(CallTracker.IDLE, 65_000))
    }
    @Test fun incomingCallsAreIgnored() {
        val t = CallTracker()
        Assert.assertNull(t.onState(CallTracker.RINGING, 0))
        Assert.assertNull(t.onState(CallTracker.OFFHOOK, 1_000))
        Assert.assertNull(t.onState(CallTracker.IDLE, 90_000))
    }
    @Test fun idleBeforeAnythingHappenedIsSilent() {
        val t = CallTracker()
        t.expectOutgoing("রহিম", true, 0)
        Assert.assertNull(t.onState(CallTracker.IDLE, 500))
        Assert.assertTrue(t.waiting())
    }
    @Test fun neverStarted() {
        val t = CallTracker()
        t.expectOutgoing("রহিম", true, 0)
        Assert.assertNull(t.checkNotStarted(5_000))
        Assert.assertEquals("রহিম কে কল শুরু হয়নি", t.checkNotStarted(13_000))
        Assert.assertNull(t.checkNotStarted(20_000))
    }
    @Test fun startedCallIsNotReportedAsNotStarted() {
        val t = CallTracker()
        t.expectOutgoing("রহিম", true, 0)
        t.onState(CallTracker.OFFHOOK, 4_000)
        Assert.assertNull(t.checkNotStarted(20_000))
    }
}

class CallFlowTest {
    private fun c(id: String, name: String, vararg nums: Pair<String, String>) =
        ContactEntry(id, name, nums.map { PhoneEntry(it.first, it.second) })

    private class Source(val list: List<ContactEntry>?) : ContactSource { override fun all() = list }
    private class Placer(val ok: Boolean = true, val note: String? = null) : CallPlacer {
        val placed = ArrayList<String>()
        override fun place(number: String, label: String): PlaceOutcome { placed.add("$label|$number"); return PlaceOutcome(ok, note) }
    }

    private val book = listOf(
        c("1", "রহিম উদ্দিন", "01711000001" to "mobile"),
        c("2", "Karim Hossain", "01711000002" to "mobile", "031-123456" to "home"),
        c("3", "Rakib Hasan", "01711000003" to "mobile"),
        c("4", "Nasir Hasan", "01711000004" to "mobile"),
        c("5", "আব্বু", "01711000005" to "mobile")
    )

    private fun flow(p: Placer, confirm: Boolean = true, bn: Boolean = true, src: List<ContactEntry>? = book, nicks: Map<String, String> = emptyMap()) =
        CallFlow(bn, Source(src), p, nicks, confirm)

    private fun reply(r: ActionResult, text: String?): ActionResult {
        Assert.assertNotNull("expected a question but got \"${r.message}\"", r.followUp)
        return r.followUp!!.onReply(text)
    }

    @Test fun uniqueNameConfirmYes() {
        val p = Placer()
        val q = flow(p).start("রহিম", null)
        Assert.assertEquals("রহিম উদ্দিনকে কল করব?", q.message)
        Assert.assertTrue(p.placed.isEmpty())
        val done = reply(q, "হ্যাঁ")
        Assert.assertEquals(listOf("রহিম উদ্দিন|01711000001"), p.placed)
        Assert.assertEquals("রহিম উদ্দিনকে কল করছি", done.message)
        Assert.assertTrue(done.success)
    }

    @Test fun confirmNoDoesNotCall() {
        val p = Placer()
        val done = reply(flow(p).start("রহিম", null), "না")
        Assert.assertTrue(p.placed.isEmpty())
        Assert.assertEquals("ঠিক আছে, কল করছি না", done.message)
    }

    @Test fun confirmSilenceDoesNotCall() {
        val p = Placer()
        val done = reply(flow(p).start("রহিম", null), null)
        Assert.assertTrue(p.placed.isEmpty())
        Assert.assertEquals("উত্তর পাইনি, কল করছি না", done.message)
    }

    @Test fun confirmUnclearAsksOnceMore() {
        val p = Placer()
        val again = reply(flow(p).start("রহিম", null), "আবহাওয়া")
        Assert.assertNotNull(again.followUp)
        reply(again, "হ্যাঁ")
        Assert.assertEquals(1, p.placed.size)
    }

    @Test fun confirmOffCallsAtOnce() {
        val p = Placer()
        val done = flow(p, confirm = false).start("Karim", null)
        // Karim has two numbers, so it asks which one first
        Assert.assertNotNull(done.followUp)
        val r = reply(done, "১")
        Assert.assertEquals(listOf("Karim Hossain|01711000002"), p.placed)
        Assert.assertEquals("Karim Hossainকে কল করছি", r.message)
    }

    @Test fun confirmOffSingleNumber() {
        val p = Placer()
        val r = flow(p, confirm = false).start("আব্বু", null)
        Assert.assertEquals(listOf("আব্বু|01711000005"), p.placed)
        Assert.assertNull(r.followUp)
    }

    @Test fun familyWordFindsContact() {
        val p = Placer()
        reply(flow(p).start("বাবা", null), "হ্যাঁ")
        Assert.assertEquals(listOf("আব্বু|01711000005"), p.placed)
    }

    @Test fun twoMatchesAskWhichByNumber() {
        val p = Placer()
        val q = flow(p).start("হাসান", null)
        Assert.assertTrue(q.message, q.message.startsWith("হাসান নামে ২ জন আছে"))
        Assert.assertTrue(q.message.contains("Rakib Hasan") && q.message.contains("Nasir Hasan"))
        val confirmQ = reply(q, "২")
        Assert.assertTrue(confirmQ.message.endsWith("কল করব?"))
        reply(confirmQ, "জি")
        Assert.assertEquals(1, p.placed.size)
        Assert.assertTrue(p.placed[0].endsWith("|01711000003") || p.placed[0].endsWith("|01711000004"))
    }

    @Test fun twoMatchesPickByNamePart() {
        val p = Placer()
        val q = flow(p).start("হাসান", null)
        val confirmQ = reply(q, "রাকিব")
        reply(confirmQ, "হ্যাঁ")
        Assert.assertEquals(listOf("Rakib Hasan|01711000003"), p.placed)
    }

    @Test fun twoMatchesUnclearAnswerThenGiveUp() {
        val p = Placer()
        val q = flow(p).start("হাসান", null)
        val again = reply(q, "আবহাওয়া")
        Assert.assertNotNull(again.followUp)
        val done = reply(again, "আবহাওয়া")
        Assert.assertNull(done.followUp)
        Assert.assertTrue(p.placed.isEmpty())
    }

    @Test fun twoNumbersAskWhichOne() {
        val p = Placer()
        val q = flow(p).start("Karim", null)
        Assert.assertTrue(q.message, q.message.contains("২টি নম্বর"))
        val confirmQ = reply(q, "বাসা")
        reply(confirmQ, "হ্যাঁ")
        Assert.assertEquals(listOf("Karim Hossain|031-123456"), p.placed)
    }

    @Test fun byNumberSpellsDigitsAndConfirms() {
        val p = Placer()
        val q = flow(p).start(null, "01711223344")
        Assert.assertEquals("০ ১ ৭ ১ ১ ২ ২ ৩ ৩ ৪ ৪কে কল করব?", q.message)
        reply(q, "হ্যাঁ")
        Assert.assertEquals(listOf("০ ১ ৭ ১ ১ ২ ২ ৩ ৩ ৪ ৪|01711223344"), p.placed)
    }

    @Test fun badNumberRefused() {
        val r = flow(Placer()).start(null, "12")
        Assert.assertTrue(!r.success)
        Assert.assertNull(r.followUp)
    }

    @Test fun emergencyNumberAlwaysAsks() {
        val p = Placer()
        val q = flow(p, confirm = false).start(null, "999")
        Assert.assertNotNull(q.followUp)
        Assert.assertTrue(p.placed.isEmpty())
        reply(q, "হ্যাঁ")
        Assert.assertEquals(1, p.placed.size)
    }

    @Test fun noContactsPermission() {
        val r = flow(Placer(), src = null).start("রহিম", null)
        Assert.assertTrue(!r.success)
        Assert.assertTrue(r.message.contains("পারমিশন"))
    }

    @Test fun contactNotFound() {
        val r = flow(Placer()).start("Zubayer", null)
        Assert.assertTrue(!r.success)
        Assert.assertEquals("Zubayer নামে কন্টাক্ট পাইনি", r.message)
    }

    @Test fun contactWithoutNumber() {
        val r = flow(Placer(), src = listOf(ContactEntry("1", "রহিম", emptyList()))).start("রহিম", null)
        Assert.assertTrue(!r.success)
    }

    @Test fun noTargetAsksWho() {
        val p = Placer()
        val q = flow(p).start(null, null)
        Assert.assertEquals("কাকে কল করব?", q.message)
        val confirmQ = reply(q, "রহিমকে")
        Assert.assertEquals("রহিম উদ্দিনকে কল করব?", confirmQ.message)
        reply(confirmQ, "হ্যাঁ")
        Assert.assertEquals(1, p.placed.size)
    }

    @Test fun noTargetThenNumber() {
        val p = Placer()
        val q = flow(p, confirm = false).start(null, null)
        reply(q, "০১৭১১২২৩৩৪৪")
        Assert.assertEquals(1, p.placed.size)
        Assert.assertTrue(p.placed[0].endsWith("|01711223344"))
    }

    @Test fun noTargetThenCancel() {
        val r = reply(flow(Placer()).start(null, null), "থাক")
        Assert.assertEquals("ঠিক আছে, কল করছি না", r.message)
    }

    @Test fun placerFailureIsReported() {
        val r = reply(flow(Placer(ok = false, note = "কল করার পারমিশন নেই")).start("আব্বু", null), "হ্যাঁ")
        Assert.assertTrue(!r.success)
        Assert.assertEquals("কল করার পারমিশন নেই", r.message)
    }

    @Test fun englishMode() {
        val p = Placer()
        val q = flow(p, bn = false).start("Rakib", null)
        Assert.assertEquals("Call Rakib Hasan?", q.message)
        val done = reply(q, "yes")
        Assert.assertEquals("Calling Rakib Hasan", done.message)
    }

    @Test fun userNickname() {
        val p = Placer()
        val q = flow(p, nicks = mapOf("বস" to "Rakib Hasan")).start("বস", null)
        Assert.assertEquals("Rakib Hasanকে কল করব?", q.message)
    }
}
