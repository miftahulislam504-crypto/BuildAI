package com.buildx.ai.assistant

import org.junit.Assert
import org.junit.Test

class PopupStateTest {
    private fun state(vararg lines: String) = PopupState.from(lines.toList())

    @Test fun noLinesMeansListeningWithNothingShown() {
        val s = state()
        Assert.assertEquals(PopupState.Status.LISTENING, s.status)
        Assert.assertNull(s.heard)
        Assert.assertNull(s.reply)
    }

    @Test fun heardThenResult() {
        val s = state("Listening...", "Heard: \"What time is it\"", "Result: It is 10:30")
        Assert.assertEquals("What time is it", s.heard)
        Assert.assertEquals("It is 10:30", s.reply)
        Assert.assertEquals(PopupState.ReplyKind.OK, s.replyKind)
        Assert.assertEquals(PopupState.Status.REPLIED, s.status)
    }

    @Test fun justHeardIsThinking() {
        val s = state("Listening...", "Heard: \"Open YouTube\"")
        Assert.assertEquals(PopupState.Status.THINKING, s.status)
        Assert.assertNull(s.reply)
    }

    @Test fun listeningAgainKeepsTheLastExchangeOnScreen() {
        val s = state("Heard: \"Torch on\"", "Result: Torch is on", "Listening...")
        Assert.assertEquals(PopupState.Status.LISTENING, s.status)
        Assert.assertEquals("Torch on", s.heard)
        Assert.assertEquals("Torch is on", s.reply)
    }

    @Test fun aNewSentenceClearsThePreviousReply() {
        val s = state("Heard: \"Torch on\"", "Result: Torch is on", "Listening...", "Heard: \"Volume 50\"")
        Assert.assertEquals("Volume 50", s.heard)
        Assert.assertNull(s.reply)
        Assert.assertEquals(PopupState.ReplyKind.NONE, s.replyKind)
    }

    @Test fun questionsAndFailuresAreMarked() {
        Assert.assertEquals(PopupState.ReplyKind.QUESTION, state("Heard: \"Call\"", "Question: Who?").replyKind)
        Assert.assertEquals(PopupState.ReplyKind.FAILED, state("Heard: \"x\"", "Failed: I didn't understand: x").replyKind)
    }

    @Test fun anEmptyAnswerDoesNotWipeTheQuestion() {
        val s = state("Heard: \"Call\"", "Question: Who?", "Listening for your answer...", "Heard: \"\"")
        Assert.assertEquals("Call", s.heard)
        Assert.assertEquals("Who?", s.reply)
        Assert.assertEquals(PopupState.Status.THINKING, s.status)
    }

    @Test fun closedKeepsTheLastExchangeAndGivesTheReason() {
        val s = state("Heard: \"Torch on\"", "Result: Torch is on", "Listening...", "Closed: no speech for 5 seconds", "Done")
        Assert.assertEquals(PopupState.Status.CLOSED, s.status)
        Assert.assertEquals("no speech for 5 seconds", s.closedReason)
        Assert.assertEquals("Torch is on", s.reply)
    }
}
