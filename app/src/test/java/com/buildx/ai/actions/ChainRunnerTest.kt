package com.buildx.ai.actions

import com.buildx.ai.nlu.IntentParser
import com.buildx.ai.nlu.Parsed
import com.buildx.ai.system.Scheduler
import com.buildx.ai.system.Verdict
import com.buildx.ai.system.Verify
import org.junit.Assert
import org.junit.Test

/** A clock that only moves when the test lets it. */
class FakeScheduler : Scheduler {
    val queue = ArrayList<Pair<Long, () -> Unit>>()
    val delays = ArrayList<Long>()
    override fun after(ms: Long, task: () -> Unit) { queue.add(ms to task); delays.add(ms) }
    fun drain() { while (queue.isNotEmpty()) queue.removeAt(0).second() }
}

/**
 * Plays what the Conversation does with an ActionResult: speak the message, answer questions from [replies],
 * wait for deferred results, and collect everything that was spoken.
 */
private fun play(first: ActionResult, sched: FakeScheduler, replies: List<String?> = emptyList()): List<String> {
    val spoken = ArrayList<String>()
    val rs = replies.toMutableList()
    fun deliver(r: ActionResult) {
        if (r.message.isNotBlank()) spoken.add(r.message)
        val d = r.deferred
        val f = r.followUp
        when {
            d != null -> d.run { fin -> deliver(fin) }
            f != null -> deliver(f.onReply(if (rs.isEmpty()) null else rs.removeAt(0)))
        }
    }
    deliver(first)
    sched.drain()
    return spoken
}

class ChainRunnerTest {
    private fun steps(text: String): List<Parsed> = IntentParser.parseChain(text)

    private fun runner(sched: FakeScheduler, ran: MutableList<String>, exec: (Parsed) -> ActionResult) =
        ChainRunner(true, { p -> ran.add(p.intent); exec(p) }, sched)

    @Test fun allStepsRunInOrderAndEachIsSpoken() {
        val sched = FakeScheduler()
        val ran = ArrayList<String>()
        val r = runner(sched, ran) { ActionResult(true, "${it.intent} ঠিক আছে") }
        val spoken = play(r.start(steps("টর্চ জ্বালাও আর ভলিউম পঞ্চাশ করো তারপর ইউটিউব খোলো")), sched)
        Assert.assertEquals(listOf("flashlight", "volume", "open_app"), ran)
        Assert.assertEquals(listOf("flashlight ঠিক আছে", "volume ঠিক আছে", "open_app ঠিক আছে"), spoken)
    }

    @Test fun gapsFollowTheStepThatJustRan() {
        val sched = FakeScheduler()
        val r = runner(sched, ArrayList()) { ActionResult(true, "ok") }
        play(r.start(steps("ইউটিউব খুলে ভলিউম কমাও")), sched)
        Assert.assertEquals(listOf(1500L), sched.delays) // after open_app, none after the last step
    }

    @Test fun failureInTheMiddleStopsAndSaysSo() {
        val sched = FakeScheduler()
        val ran = ArrayList<String>()
        val r = runner(sched, ran) {
            if (it.intent == "volume") ActionResult(false, "ভলিউম বদলানো যায়নি") else ActionResult(true, "${it.intent} ok")
        }
        val spoken = play(r.start(steps("টর্চ জ্বালাও আর ভলিউম পঞ্চাশ করো তারপর ইউটিউব খোলো")), sched)
        Assert.assertEquals(listOf("flashlight", "volume"), ran) // step 3 never ran
        Assert.assertEquals("flashlight ok", spoken[0])
        Assert.assertEquals("ভলিউম বদলানো যায়নি। ধাপ ২/৩-এ আটকে গেছি (ভলিউম ৫০), বাকি ১টি ধাপ করিনি", spoken[1])
        Assert.assertEquals(2, spoken.size)
    }

    @Test fun failureOfTheFirstStep() {
        val sched = FakeScheduler()
        val ran = ArrayList<String>()
        val r = runner(sched, ran) { ActionResult(false, "ফ্ল্যাশ পাইনি") }
        val spoken = play(r.start(steps("টর্চ জ্বালাও আর ভলিউম কমাও")), sched)
        Assert.assertEquals(listOf("flashlight"), ran)
        Assert.assertTrue(spoken.single().contains("ধাপ ১/২-এ আটকে গেছি"))
        Assert.assertTrue(spoken.single().contains("বাকি ১টি ধাপ করিনি"))
    }

    @Test fun failureOfTheLastStepHasNoSkippedPart() {
        val sched = FakeScheduler()
        val r = runner(sched, ArrayList()) { if (it.intent == "volume") ActionResult(false, "x") else ActionResult(true, "ok") }
        val spoken = play(r.start(steps("টর্চ জ্বালাও আর ভলিউম কমাও")), sched)
        Assert.assertTrue(spoken.last().contains("ধাপ ২/২-এ আটকে গেছি"))
        Assert.assertTrue(!spoken.last().contains("বাকি"))
    }

    @Test fun anExceptionCountsAsAFailure() {
        val sched = FakeScheduler()
        val r = runner(sched, ArrayList()) { if (it.intent == "volume") throw IllegalStateException("boom") else ActionResult(true, "ok") }
        val spoken = play(r.start(steps("টর্চ জ্বালাও আর ভলিউম কমাও আর ইউটিউব খোলো")), sched)
        Assert.assertTrue(spoken.last().contains("ধাপ ২/৩-এ আটকে গেছি"))
    }

    @Test fun aQuestionInTheMiddleIsAskedThenTheChainContinues() {
        val sched = FakeScheduler()
        val ran = ArrayList<String>()
        val r = runner(sched, ran) { p ->
            if (p.intent == "call") ActionResult(true, "কাকে?", FollowUp { reply ->
                if (reply == "রহিম") ActionResult(true, "রহিমকে কল করছি") else ActionResult(false, "কল করছি না")
            }) else ActionResult(true, "${p.intent} ok")
        }
        val spoken = play(r.start(steps("টর্চ জ্বালাও আর রহিমকে ফোন করো আর ভলিউম কমাও")), sched, replies = listOf("রহিম"))
        Assert.assertEquals(listOf("flashlight", "call", "volume"), ran)
        Assert.assertEquals(listOf("flashlight ok", "কাকে?", "রহিমকে কল করছি", "volume ok"), spoken)
    }

    @Test fun aDeniedQuestionStopsTheChain() {
        val sched = FakeScheduler()
        val ran = ArrayList<String>()
        val r = runner(sched, ran) { p ->
            if (p.intent == "call") ActionResult(true, "কল করব?", FollowUp { ActionResult(false, "ঠিক আছে, কল করছি না") })
            else ActionResult(true, "${p.intent} ok")
        }
        val spoken = play(r.start(steps("রহিমকে ফোন করো আর ভলিউম কমাও")), sched, replies = listOf("না"))
        Assert.assertEquals(listOf("call"), ran)
        Assert.assertTrue(spoken.last().contains("ধাপ ১/২-এ আটকে গেছি"))
    }

    @Test fun deferredStepsAreAwaitedAndCanFail() {
        val sched = FakeScheduler()
        val ran = ArrayList<String>()
        val r = runner(sched, ran) { p ->
            if (p.intent == "flashlight") ActionResult(true, "", deferred = Deferred { report -> sched.after(300) { report(ActionResult(false, "ফ্ল্যাশলাইট চালু হয়নি")) } })
            else ActionResult(true, "${p.intent} ok")
        }
        val spoken = play(r.start(steps("টর্চ জ্বালাও আর ভলিউম কমাও")), sched)
        Assert.assertEquals(listOf("flashlight"), ran)
        Assert.assertTrue(spoken.single().startsWith("ফ্ল্যাশলাইট চালু হয়নি। ধাপ ১/২-এ"))
    }

    @Test fun deferredStepThatSucceedsContinues() {
        val sched = FakeScheduler()
        val ran = ArrayList<String>()
        val r = runner(sched, ran) { p ->
            if (p.intent == "flashlight") ActionResult(true, "", deferred = Deferred { report -> sched.after(300) { report(ActionResult(true, "টর্চ চালু")) } })
            else ActionResult(true, "${p.intent} ok")
        }
        val spoken = play(r.start(steps("টর্চ জ্বালাও আর ভলিউম কমাও")), sched)
        Assert.assertEquals(listOf("flashlight", "volume"), ran)
        Assert.assertEquals(listOf("টর্চ চালু", "volume ok"), spoken)
    }

    @Test fun englishFailureMessage() {
        val sched = FakeScheduler()
        val r = ChainRunner(false, { if (it.intent == "volume") ActionResult(false, "Couldn't change volume") else ActionResult(true, "ok") }, sched)
        val spoken = play(r.start(steps("turn on the flashlight and set volume to 30 and open youtube")), sched)
        Assert.assertEquals("Couldn't change volume. Stopped at step 2 of 3 (volume 30), skipped the remaining 1", spoken.last())
    }
}

class VerifyTest {
    private fun run(vararg answers: Verdict, maxTries: Int = 5): Pair<Verdict?, Int> {
        val sched = FakeScheduler()
        var i = 0
        var result: Verdict? = null
        Verify.poll(sched, 100, maxTries, { answers[minOf(i++, answers.size - 1)] }) { result = it }
        sched.drain()
        return result to i
    }

    @Test fun yesAtOnce() = Assert.assertEquals(Verdict.YES to 1, run(Verdict.YES))
    @Test fun yesAfterAFewTries() = Assert.assertEquals(Verdict.YES to 3, run(Verdict.NO, Verdict.NO, Verdict.YES))
    @Test fun neverYesEndsWithNo() = Assert.assertEquals(Verdict.NO to 5, run(Verdict.NO))
    @Test fun unknownAtTheStartDoesNotWait() = Assert.assertEquals(Verdict.UNKNOWN to 1, run(Verdict.UNKNOWN))
    @Test fun unknownLaterEndsAsUnknown() = Assert.assertEquals(Verdict.UNKNOWN to 5, run(Verdict.NO, Verdict.UNKNOWN))
}
