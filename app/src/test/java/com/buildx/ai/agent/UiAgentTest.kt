package com.buildx.ai.agent

import com.buildx.ai.a11y.ElKind
import com.buildx.ai.a11y.ScreenElement
import com.buildx.ai.a11y.ScreenSnapshot
import com.buildx.ai.actions.ActionResult
import com.buildx.ai.llm.Failure
import org.junit.Assert
import org.junit.Test

class UiAgentTest {

    private fun btn(id: Int, label: String, risky: Boolean = false) = ScreenElement(id, ElKind.BUTTON, label, risky = risky)
    private fun field(id: Int, label: String) = ScreenElement(id, ElKind.FIELD, label)
    private fun snap(vararg e: ScreenElement, pkg: String = "com.whatsapp") = ScreenSnapshot(pkg, e.toList())

    /** a phone, a server and a clock the test controls; everything runs at once, in order */
    private class World(var screen: () -> ScreenSnapshot? = { null }) {
        val sent = ArrayList<AgentRequest>()
        val done = ArrayList<AgentStep>()
        val reports = ArrayList<ActionResult>()
        val answers = ArrayDeque<AgentOutcome>()
        var onAnswer: () -> Unit = {}
        var uiOk = true
        var clock = 0L
        var finishes = 0
        var captures = 0

        val screenPort = object : ScreenPort {
            override fun capture(): ScreenSnapshot? { captures++; return screen() }
        }
        val uiPort = object : UiPort {
            override fun perform(step: AgentStep, done: (Boolean, String) -> Unit) {
                this@World.done.add(step)
                done(uiOk, if (uiOk) "" else "refused")
            }
        }
        val backend = object : AgentBackend {
            override fun next(req: AgentRequest): AgentOutcome {
                sent.add(req)
                onAnswer()
                return answers.removeFirstOrNull() ?: AgentOutcome.Next(AgentStep.Wait)
            }
        }
        fun say(vararg s: AgentStep) = s.forEach { answers.add(AgentOutcome.Next(it)) }

        fun agent(bn: Boolean = true) = UiAgent(
            goal = "send hi to Rahim", lang = if (bn) "bn-BD" else "en-US", bn = bn,
            screen = screenPort, ui = uiPort, backend = backend,
            io = { it() }, main = { it() }, later = { _, b -> b() },
            clock = { clock }, nowIso = { "2026-10-20T14:03+06:00" }, onFinish = { finishes++ }
        )

        /** runs a whole task; every report is kept */
        fun run(agent: UiAgent = agent()): ActionResult {
            val first = agent.start()
            reports.add(first)
            first.deferred!!.run { reports.add(it) }
            return reports.last()
        }
    }

    // ---------- the normal job ----------

    @Test fun looksActsAndStopsWhenTheModelSaysDone() {
        val w = World { snap(field(1, "Message"), btn(2, "Attach"), btn(3, "Voice")) }
        w.say(AgentStep.Type("hi", 1), AgentStep.Done("পাঠিয়েছি"))
        val r = w.run()
        Assert.assertEquals(listOf<AgentStep>(AgentStep.Type("hi", 1)), w.done)
        Assert.assertTrue(r.success)
        Assert.assertEquals("পাঠিয়েছি", r.message)
        Assert.assertEquals(1, w.finishes)
    }

    @Test fun theFirstReplyIsTheHoldingLine() {
        val w = World { snap(btn(1, "OK")) }
        w.say(AgentStep.Done("x"))
        w.run()
        Assert.assertEquals("ঠিক আছে, করছি", w.reports.first().message)
    }

    @Test fun whatWasDoneSoFarIsSentWithEachRequest() {
        val w = World { snap(btn(1, "Rahim"), btn(2, "Back")) }
        w.say(AgentStep.Tap(1), AgentStep.Tap(2), AgentStep.Done("ok"))
        w.run()
        Assert.assertEquals(3, w.sent.size)
        Assert.assertEquals(emptyList<String>(), w.sent[0].history)
        Assert.assertEquals(listOf("tap [1] \"Rahim\" -> ok"), w.sent[1].history)
        Assert.assertEquals(2, w.sent[2].history.size)
        Assert.assertEquals("com.whatsapp", w.sent[0].pkg)
        Assert.assertEquals("[1] button \"Rahim\"\n[2] button \"Back\"\n", w.sent[0].screen)
        Assert.assertEquals("send hi to Rahim", w.sent[0].goal)
    }

    @Test fun theModelCanGiveUpAndTheUserHearsWhy() {
        val w = World { snap(btn(1, "x")) }
        w.say(AgentStep.Fail("রহিম নামে কোনো চ্যাট নেই"))
        val r = w.run()
        Assert.assertFalse(r.success)
        Assert.assertEquals("রহিম নামে কোনো চ্যাট নেই", r.message)
        Assert.assertEquals(1, w.finishes)
    }

    // ---------- the leash ----------

    @Test fun aScreenThatWasNotReadableEndsTheTaskWithoutAskingTheServer() {
        val w = World { ScreenSnapshot("com.bkash.customerapp", emptyList(), blocked = "money app") }
        val r = w.run()
        Assert.assertFalse(r.success)
        Assert.assertTrue(w.sent.isEmpty())
        Assert.assertTrue(w.done.isEmpty())
        Assert.assertEquals(1, w.finishes)
    }

    @Test fun anUnreadableScreenEndsTheTask() {
        val w = World { null }
        val r = w.run()
        Assert.assertFalse(r.success)
        Assert.assertTrue(w.sent.isEmpty())
    }

    @Test fun neverMoreThanTwelveSteps() {
        val w = World { snap(btn(1, "x")) } // the model just keeps waiting
        val r = w.run()
        Assert.assertFalse(r.success)
        Assert.assertEquals(UiAgent.MAX_STEPS, w.sent.size)
        Assert.assertEquals(1, w.finishes)
    }

    @Test fun neverMoreThanTwoMinutes() {
        val w = World { snap(btn(1, "x")) }
        w.onAnswer = { w.clock += 50_000 }
        val r = w.run()
        Assert.assertFalse(r.success)
        Assert.assertTrue("stopped on time, not on steps", w.sent.size < UiAgent.MAX_STEPS)
    }

    @Test fun theSameStepFailingThreeTimesEndsTheTask() {
        val w = World { snap(btn(1, "Next")) }
        w.uiOk = false
        for (i in 1..10) w.say(AgentStep.Tap(1))
        val r = w.run()
        Assert.assertFalse(r.success)
        Assert.assertEquals(3, w.sent.size)
        Assert.assertEquals(3, w.done.size)
    }

    @Test fun stoppingBeforeTheFirstLookStopsEverything() {
        val w = World { snap(btn(1, "x")) }
        val a = w.agent()
        a.cancel()
        val r = w.run(a)
        Assert.assertFalse(r.success)
        Assert.assertTrue(w.sent.isEmpty())
        Assert.assertEquals(1, w.finishes)
    }

    @Test fun stoppingWhileTheServerThinksMeansTheAnswerIsNotActedOn() {
        val w = World { snap(btn(1, "Send later")) }
        val a = w.agent()
        w.onAnswer = { a.cancel() }
        w.say(AgentStep.Tap(1), AgentStep.Done("never"))
        val r = w.run(a)
        Assert.assertTrue(w.done.isEmpty())
        Assert.assertFalse(r.success)
    }

    @Test fun aServerProblemEndsTheTaskInPlainWords() {
        val w = World { snap(btn(1, "x")) }
        w.answers.add(AgentOutcome.Failed(Failure.NETWORK))
        val r = w.run(w.agent(bn = false))
        Assert.assertFalse(r.success)
        Assert.assertEquals("No connection, so I stopped", r.message)
        Assert.assertEquals(1, w.finishes)
    }

    @Test fun aBackendThatThrowsIsAProblemNotACrash() {
        val w = World { snap(btn(1, "x")) }
        val a = UiAgent("g", "en-US", false, w.screenPort, w.uiPort, object : AgentBackend {
            override fun next(req: AgentRequest): AgentOutcome = throw IllegalStateException("boom")
        }, { it() }, { it() }, { _, b -> b() }, { 0L }, { "now" })
        val reports = ArrayList<ActionResult>()
        a.start().deferred!!.run { reports.add(it) }
        Assert.assertFalse(reports.single().success)
    }

    // ---------- checking before acting ----------

    @Test fun anElementThatIsNotThereIsAFailedTryNotACrash() {
        val w = World { snap(btn(1, "x")) }
        w.say(AgentStep.Tap(99), AgentStep.Done("ok"))
        val r = w.run()
        Assert.assertTrue(w.done.isEmpty())
        Assert.assertTrue(r.success)
        Assert.assertEquals("tap 99 -> failed: no such element", w.sent[1].history.single())
    }

    @Test fun aFieldIdThatIsNotAFieldIsRefused() {
        val w = World { snap(btn(1, "x"), field(2, "Message")) }
        w.say(AgentStep.Type("hi", 1), AgentStep.Done("ok"))
        w.run()
        Assert.assertTrue(w.done.isEmpty())
    }

    @Test fun ifTheScreenChangedWhileTheServerThoughtNothingIsTapped() {
        var calls = 0
        val w = World { if (calls++ == 0) snap(btn(1, "Rahim")) else snap(btn(1, "Karim")) }
        w.say(AgentStep.Tap(1), AgentStep.Done("ok"))
        w.run()
        Assert.assertTrue("the tap was held back", w.done.isEmpty())
        Assert.assertTrue(w.sent[1].history.single().contains("the screen changed"))
    }

    @Test fun ifTheAppChangedNothingIsTapped() {
        var calls = 0
        val w = World { if (calls++ == 0) snap(btn(1, "OK")) else snap(btn(1, "OK"), pkg = "com.other") }
        w.say(AgentStep.Tap(1), AgentStep.Done("ok"))
        w.run()
        Assert.assertTrue(w.done.isEmpty())
    }

    // ---------- asking before something that cannot be undone ----------

    private fun askedWorld(): World {
        val w = World { snap(field(1, "Message"), btn(2, "Send", risky = true)) }
        w.say(AgentStep.Tap(2), AgentStep.Done("পাঠিয়েছি"))
        return w
    }

    @Test fun aRiskyButtonIsNotPressedUntilThePersonSaysYes() {
        val w = askedWorld()
        w.run()
        val q = w.reports.last()
        Assert.assertTrue(q.message.contains("Send"))
        Assert.assertNotNull(q.followUp)
        Assert.assertTrue("nothing pressed while waiting", w.done.isEmpty())
        Assert.assertEquals(0, w.finishes)

        val after = q.followUp!!.onReply("হ্যাঁ")
        after.deferred!!.run { w.reports.add(it) }
        Assert.assertEquals(listOf<AgentStep>(AgentStep.Tap(2)), w.done)
        Assert.assertTrue(w.reports.last().success)
        Assert.assertEquals("পাঠিয়েছি", w.reports.last().message)
        Assert.assertEquals(1, w.finishes)
    }

    @Test fun noMeansStopHere() {
        val w = askedWorld()
        w.run()
        val r = w.reports.last().followUp!!.onReply("না")
        Assert.assertTrue(r.success)
        Assert.assertTrue(w.done.isEmpty())
        Assert.assertNull(r.deferred)
        Assert.assertEquals(1, w.finishes)
    }

    @Test fun silenceOrAnythingUnclearMeansNo() {
        for (reply in listOf(null, "", "আচ্ছা দেখি", "hmm")) {
            val w = askedWorld()
            w.run()
            w.reports.last().followUp!!.onReply(reply)
            Assert.assertTrue("reply=$reply", w.done.isEmpty())
            Assert.assertEquals(1, w.finishes)
        }
    }

    @Test fun yesAfterTheScreenChangedPressesNothing() {
        var changed = false
        val w = World { if (changed) snap(field(1, "Message"), btn(2, "Delete chat", risky = true)) else snap(field(1, "Message"), btn(2, "Send", risky = true)) }
        w.say(AgentStep.Tap(2), AgentStep.Done("ok"))
        w.run()
        changed = true
        w.reports.last().followUp!!.onReply("yes").deferred!!.run { w.reports.add(it) }
        Assert.assertTrue("the person said yes to Send, not to Delete chat", w.done.isEmpty())
    }

    @Test fun cancelWhileWaitingForTheAnswerStops() {
        val w = World { snap(btn(2, "Send", risky = true)) }
        val a = w.agent()
        w.say(AgentStep.Tap(2))
        w.run(a)
        a.cancel()
        val r = w.reports.last().followUp!!.onReply("হ্যাঁ")
        Assert.assertTrue(w.done.isEmpty())
        Assert.assertNull(r.deferred)
    }

    @Test fun englishMessagesWhenTheUserSpeaksEnglish() {
        val w = World { snap(btn(2, "Send", risky = true)) }
        w.say(AgentStep.Tap(2))
        w.run(w.agent(bn = false))
        Assert.assertEquals("Press “Send”? Say yes or no", w.reports.last().message)
    }

    @Test fun finishIsCalledOnceNoMatterHowTheTaskEnds() {
        val ends = listOf<(World) -> Unit>(
            { it.say(AgentStep.Done("a")) },
            { it.say(AgentStep.Fail("b")) },
            { it.answers.add(AgentOutcome.Failed(Failure.SERVER)) },
        )
        for (setup in ends) {
            val w = World { snap(btn(1, "x")) }
            setup(w)
            w.run()
            Assert.assertEquals(1, w.finishes)
        }
    }
}
