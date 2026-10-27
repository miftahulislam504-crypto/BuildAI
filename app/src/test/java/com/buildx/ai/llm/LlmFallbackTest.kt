package com.buildx.ai.llm

import com.buildx.ai.actions.ActionResult
import com.buildx.ai.nlu.Parsed
import org.junit.Assert
import org.junit.Test

class LlmFallbackTest {
    private val said = "মাকে বলো আমি দেরি করব"

    private class FakeBackend(private val answer: () -> LlmOutcome) : LlmBackend {
        var calls = 0
        var lastRequest: LlmRequest? = null
        override fun plan(req: LlmRequest): LlmOutcome {
            calls++
            lastRequest = req
            return answer()
        }
    }

    /** io and main run later, when the test says so: the answer can come before or after the conversation asks for it */
    private class Loop {
        val ioQueue = ArrayList<() -> Unit>()
        val mainQueue = ArrayList<() -> Unit>()
        val io: (() -> Unit) -> Unit = { ioQueue.add(it) }
        val main: (() -> Unit) -> Unit = { mainQueue.add(it) }
        fun drain() {
            while (ioQueue.isNotEmpty() || mainQueue.isNotEmpty()) {
                if (ioQueue.isNotEmpty()) ioQueue.removeAt(0)()
                if (mainQueue.isNotEmpty()) mainQueue.removeAt(0)()
            }
        }
    }

    private fun planned(say: String? = null, vararg intents: String) =
        LlmOutcome.Planned(LlmPlan(say, intents.map { Parsed(it, mapOf("via" to "llm"), said) }))

    private fun fallback(loop: Loop, b: LlmBackend, log: MutableList<String> = ArrayList()) =
        LlmFallback(b, loop.io, loop.main, { log.add(it) }, { "2026-10-20T14:03+06:00" })

    private class Reports {
        val got = ArrayList<ActionResult>()
        val report: (ActionResult) -> Unit = { got.add(it) }
    }

    @Test fun holdingLineFirstThenTheRealResult() {
        val loop = Loop()
        val b = FakeBackend { planned("পাঁচটা বাজে") }
        val r = fallback(loop, b).resolve(said, "bn-BD", true) { ActionResult(true, "ran") }
        Assert.assertEquals("দেখছি", r.message)
        Assert.assertTrue(r.success)
        Assert.assertNotNull(r.deferred)
    }

    @Test fun theRequestStartsBeforeTheConversationAsks() {
        val loop = Loop()
        val b = FakeBackend { planned("x") }
        fallback(loop, b).resolve(said, "bn-BD", true) { ActionResult(true, "ran") }
        loop.ioQueue.removeAt(0)() // the background thread gets going while the holding line is still being spoken
        Assert.assertEquals(1, b.calls)
        Assert.assertEquals(said, b.lastRequest!!.text)
        Assert.assertEquals("bn-BD", b.lastRequest!!.lang)
        Assert.assertEquals("2026-10-20T14:03+06:00", b.lastRequest!!.now)
    }

    @Test fun stepsRunThroughThePipelineAndTheirResultIsReported() {
        val loop = Loop()
        var ran: List<Parsed>? = null
        val rep = Reports()
        val r = fallback(loop, FakeBackend { planned(null, "flashlight", "volume") })
            .resolve(said, "bn-BD", true) { ran = it; ActionResult(true, "ঠিক আছে") }
        r.deferred!!.run(rep.report)
        loop.drain()
        Assert.assertEquals(listOf("flashlight", "volume"), ran!!.map { it.intent })
        Assert.assertEquals(listOf("ঠিক আছে"), rep.got.map { it.message })
    }

    @Test fun whenThereAreStepsTheSpokenAnswerIsNotAlsoSpoken() {
        val loop = Loop()
        val rep = Reports()
        val r = fallback(loop, FakeBackend { planned("আমি ফ্ল্যাশলাইট জ্বালাচ্ছি", "flashlight") })
            .resolve(said, "bn-BD", true) { ActionResult(true, "ফ্ল্যাশলাইট চালু করেছি") }
        r.deferred!!.run(rep.report)
        loop.drain()
        Assert.assertEquals(listOf("ফ্ল্যাশলাইট চালু করেছি"), rep.got.map { it.message })
    }

    @Test fun aSpokenAnswerWithNoStepsIsJustSpoken() {
        val loop = Loop()
        val rep = Reports()
        var ranAny = false
        val r = fallback(loop, FakeBackend { planned("ফ্রান্সের রাজধানী প্যারিস") })
            .resolve(said, "bn-BD", true) { ranAny = true; ActionResult(true, "") }
        r.deferred!!.run(rep.report)
        loop.drain()
        Assert.assertFalse(ranAny)
        Assert.assertEquals(1, rep.got.size)
        Assert.assertTrue(rep.got[0].success)
        Assert.assertEquals("ফ্রান্সের রাজধানী প্যারিস", rep.got[0].message)
    }

    @Test fun theAnswerMayArriveAfterTheConversationStartsWaiting() {
        val loop = Loop()
        val rep = Reports()
        val r = fallback(loop, FakeBackend { planned("উত্তর") }).resolve(said, "bn-BD", true) { ActionResult(true, "") }
        r.deferred!!.run(rep.report)
        Assert.assertTrue("nothing yet: the server has not answered", rep.got.isEmpty())
        loop.drain()
        Assert.assertEquals(1, rep.got.size)
    }

    @Test fun theAnswerMayArriveBeforeTheConversationAsks() {
        val loop = Loop()
        val rep = Reports()
        val r = fallback(loop, FakeBackend { planned("উত্তর") }).resolve(said, "bn-BD", true) { ActionResult(true, "") }
        loop.drain() // the holding line was slow to finish: the answer is already here
        r.deferred!!.run(rep.report)
        Assert.assertEquals(1, rep.got.size)
        Assert.assertEquals("উত্তর", rep.got[0].message)
    }

    @Test fun resultIsReportedExactlyOnce() {
        val loop = Loop()
        val rep = Reports()
        val r = fallback(loop, FakeBackend { planned("উত্তর") }).resolve(said, "bn-BD", true) { ActionResult(true, "") }
        r.deferred!!.run(rep.report)
        loop.drain()
        loop.drain()
        Assert.assertEquals(1, rep.got.size)
    }

    @Test fun aFailureSoundsLikeBeforeWithTheReasonInFront() {
        fun say(f: Failure, bn: Boolean): ActionResult {
            val loop = Loop()
            val rep = Reports()
            val r = fallback(loop, FakeBackend { LlmOutcome.Failed(f) }).resolve(said, if (bn) "bn-BD" else "en-US", bn) { ActionResult(true, "") }
            r.deferred!!.run(rep.report)
            loop.drain()
            return rep.got.single()
        }
        val offline = say(Failure.NETWORK, true)
        Assert.assertFalse(offline.success)
        Assert.assertEquals("ইন্টারনেটে সমস্যা। বুঝতে পারিনি: $said", offline.message)
        Assert.assertEquals("সার্ভারের টোকেন ঠিক নেই। বুঝতে পারিনি: $said", say(Failure.AUTH, true).message)
        Assert.assertEquals("No connection. I didn't understand: $said", say(Failure.TIMEOUT, false).message)
        Assert.assertEquals("The server is busy. I didn't understand: $said", say(Failure.BUSY, false).message)
    }

    @Test fun aBackendThatThrowsIsAFailureNotACrash() {
        val loop = Loop()
        val rep = Reports()
        val r = fallback(loop, FakeBackend { throw IllegalStateException("boom") }).resolve(said, "en-US", false) { ActionResult(true, "") }
        r.deferred!!.run(rep.report)
        loop.drain()
        Assert.assertFalse(rep.got.single().success)
    }

    @Test fun whatHappenedIsLogged() {
        val loop = Loop()
        val log = ArrayList<String>()
        val r = fallback(loop, FakeBackend { planned(null, "flashlight") }, log).resolve(said, "bn-BD", true) { ActionResult(true, "") }
        r.deferred!!.run { }
        loop.drain()
        Assert.assertTrue(log.single().contains("flashlight"))
    }

    // ----- asking one question back -----

    private fun asking(q: String) = LlmOutcome.Planned(LlmPlan(null, emptyList(), q))

    @Test fun theRecentLinesGoToTheServer() {
        val loop = Loop()
        val b = FakeBackend { planned("x") }
        fallback(loop, b).resolve(said, "bn-BD", true, recent = listOf("wifi state=on")) { ActionResult(true, "ran") }
        loop.ioQueue.removeAt(0)()
        Assert.assertEquals(listOf("wifi state=on"), b.lastRequest!!.recent)
        Assert.assertNull(b.lastRequest!!.clarify)
    }

    @Test fun aQuestionIsSpokenAndTheAnswerStartsASecondRoundWithBoth() {
        val loop = Loop()
        val rep = Reports()
        var ran: List<Parsed>? = null
        // the fake answers by call count: first a question, then steps
        var n = 0
        val backend = FakeBackend { n++; if (n == 1) asking("কাকে পাঠাব?") else planned(null, "send_sms") }
        val r = fallback(loop, backend).resolve(said, "bn-BD", true) { ran = it; ActionResult(true, "পাঠানো হয়েছে") }
        r.deferred!!.run(rep.report)
        loop.drain()
        val question = rep.got.single()
        Assert.assertEquals("কাকে পাঠাব?", question.message)
        Assert.assertNotNull(question.followUp)
        Assert.assertNull("nothing runs before the answer", ran)

        val second = question.followUp!!.onReply("রহিমকে")
        Assert.assertEquals("দেখছি", second.message)
        val rep2 = Reports()
        second.deferred!!.run(rep2.report)
        loop.drain()
        Assert.assertEquals(2, backend.calls)
        Assert.assertEquals("কাকে পাঠাব?" to "রহিমকে", backend.lastRequest!!.clarify)
        Assert.assertEquals(said, backend.lastRequest!!.text)
        Assert.assertEquals(listOf("send_sms"), ran!!.map { it.intent })
        Assert.assertEquals("পাঠানো হয়েছে", rep2.got.single().message)
    }

    @Test fun aSecondQuestionIsOnlySpokenNeverAnswered() {
        val loop = Loop()
        var n = 0
        val backend = FakeBackend { n++; asking("আরেকটা প্রশ্ন $n") }
        val rep = Reports()
        fallback(loop, backend).resolve(said, "bn-BD", true) { ActionResult(true, "") }.deferred!!.run(rep.report)
        loop.drain()
        val second = rep.got.single().followUp!!.onReply("রহিমকে")
        val rep2 = Reports()
        second.deferred!!.run(rep2.report)
        loop.drain()
        Assert.assertNull(rep2.got.single().followUp)
        Assert.assertEquals(2, backend.calls)
    }

    @Test fun noOrNeverMindDropsTheQuestionWithoutAnotherServerCall() {
        for (reply in listOf("না", "থাক", "no")) {
            val loop = Loop()
            val backend = FakeBackend { asking("কাকে পাঠাব?") }
            val rep = Reports()
            fallback(loop, backend).resolve(said, "bn-BD", true) { ActionResult(true, "") }.deferred!!.run(rep.report)
            loop.drain()
            val next = rep.got.single().followUp!!.onReply(reply)
            Assert.assertNull(next.deferred)
            Assert.assertEquals("ঠিক আছে, করিনি", next.message)
            Assert.assertEquals(1, backend.calls)
        }
    }

    @Test fun silenceDropsTheQuestionToo() {
        val loop = Loop()
        val backend = FakeBackend { asking("কাকে পাঠাব?") }
        val rep = Reports()
        fallback(loop, backend).resolve(said, "bn-BD", true) { ActionResult(true, "") }.deferred!!.run(rep.report)
        loop.drain()
        val next = rep.got.single().followUp!!.onReply(null)
        Assert.assertFalse(next.success)
        Assert.assertNull(next.deferred)
        Assert.assertEquals(1, backend.calls)
    }

    @Test fun aLongAnswerIsCappedBeforeItIsSent() {
        val loop = Loop()
        val backend = FakeBackend { asking("কাকে?") }
        val rep = Reports()
        fallback(loop, backend).resolve(said, "bn-BD", true) { ActionResult(true, "") }.deferred!!.run(rep.report)
        loop.drain()
        rep.got.single().followUp!!.onReply("ক".repeat(1000))
        loop.ioQueue.removeAt(0)()
        Assert.assertEquals(300, backend.lastRequest!!.clarify!!.second.length)
    }
}
