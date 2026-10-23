package com.buildx.ai.phase5

import com.buildx.ai.stt.SttEndpoint
import com.buildx.ai.stt.SttMode
import com.buildx.ai.stt.Transcript
import com.buildx.ai.stt.Wav
import com.buildx.ai.voice.SpeechText
import com.buildx.ai.wake.VadGate
import com.buildx.ai.wake.WakePhrase
import com.buildx.ai.wake.WakePolicy
import com.buildx.ai.wake.WakeSensitivity
import org.junit.Assert
import org.junit.Test

class WakePhraseTest {
    private fun ok(raw: String?, vocab: Set<String>? = null): WakePhrase {
        val p = WakePhrase.parse(raw, vocab)
        Assert.assertTrue("$raw -> $p", p is WakePhrase.Parse.Ok)
        return (p as WakePhrase.Parse.Ok).phrase
    }
    private fun bad(raw: String?, vocab: Set<String>? = null) =
        Assert.assertTrue("$raw should be rejected", WakePhrase.parse(raw, vocab) is WakePhrase.Parse.Bad)

    @Test fun blankIsTheDefault() {
        Assert.assertEquals(WakePhrase.DEFAULT, ok(""))
        Assert.assertEquals(WakePhrase.DEFAULT, ok(null))
        Assert.assertEquals(WakePhrase.DEFAULT, WakePhrase.fromPref("  "))
        Assert.assertEquals("Hey Build", WakePhrase.DEFAULT.display)
    }

    @Test fun defaultGrammarKeepsTheXTails() {
        Assert.assertEquals("[\"hey build\",\"hey build x\",\"hey build ex\",\"[unk]\"]", WakePhrase.DEFAULT.grammarJson())
    }

    @Test fun customPhraseIsNormalizedAndHasASmallGrammar() {
        val p = ok("  Hey   JARVIS ")
        Assert.assertEquals("hey jarvis", p.text)
        Assert.assertEquals("Hey Jarvis", p.display)
        Assert.assertEquals("[\"hey jarvis\",\"[unk]\"]", p.grammarJson())
        Assert.assertFalse(p.isDefault)
    }

    @Test fun tooShortTooLongOrNotEnglishIsRejected() {
        bad("jarvis")
        bad("ok go")
        bad("a b c d e")
        bad("হে বিল্ড")
        bad("hey build 2")
        bad("hey [unk]")
    }

    @Test fun wordsTheModelDoesNotKnowAreRejected() {
        val vocab = setOf("hey", "jarvis", "buddy")
        ok("hey jarvis", vocab)
        bad("hey bondhu", vocab)
        val msg = (WakePhrase.parse("hey bondhu", vocab) as WakePhrase.Parse.Bad).reason
        Assert.assertTrue(msg, msg.contains("bondhu"))
    }

    @Test fun badSavedValueFallsBackToDefault() {
        Assert.assertEquals(WakePhrase.DEFAULT, WakePhrase.fromPref("x"))
    }

    @Test fun matchesWholeWordsInOrder() {
        val p = WakePhrase.DEFAULT
        Assert.assertTrue(p.matches("hey build"))
        Assert.assertTrue(p.matches("hey build x"))
        Assert.assertTrue(p.matches("well hey build now"))
        Assert.assertFalse(p.matches("hey builder"))
        Assert.assertFalse(p.matches("build hey"))
        Assert.assertFalse(p.matches("hey"))
        Assert.assertFalse(p.matches(""))
    }

    @Test fun vocabularyIsReadFromWordsTxt() {
        val v = WakePhrase.vocabularyFrom(sequenceOf("<eps> 0", "hey 1", "jarvis 2", "don't 3", "#0 4", "ab1 5", ""))
        Assert.assertEquals(setOf("hey", "jarvis", "don't"), v)
    }

    @Test fun policyUsesTheChosenPhrase() {
        val j = WakePhrase.parse("hey jarvis", null).let { (it as WakePhrase.Parse.Ok).phrase }
        Assert.assertTrue(WakePolicy.shouldFire(WakeSensitivity.NORMAL, true, "hey jarvis", null, j))
        Assert.assertFalse(WakePolicy.shouldFire(WakeSensitivity.NORMAL, true, "hey build", null, j))
        Assert.assertFalse(WakePolicy.shouldFire(WakeSensitivity.NORMAL, false, "hey jarvis", null, j)) // not final yet
        Assert.assertTrue(WakePolicy.shouldFire(WakeSensitivity.HIGH, false, "hey jarvis", null, j))
    }
}

class VadGateTest {
    private fun frame(amp: Int) = ShortArray(320) { if (it % 2 == 0) amp.toShort() else (-amp).toShort() }
    private fun quiet() = frame(20)
    private fun loud() = frame(4000)

    @Test fun silenceNeverPassesAnything() {
        val g = VadGate()
        repeat(500) { Assert.assertTrue(g.feed(quiet()).frames.isEmpty()) }
        Assert.assertEquals(0L, g.speechFrames)
        Assert.assertEquals(500L, g.totalFrames)
    }

    @Test fun speechStartsWithPreRollThenEndsAfterTheHangover() {
        val g = VadGate(preRollFrames = 5, hangoverFrames = 10, startFrames = 2)
        repeat(20) { g.feed(quiet()) }
        Assert.assertTrue(g.feed(loud()).frames.isEmpty()) // one loud frame is not enough
        val start = g.feed(loud())
        Assert.assertEquals(5, start.frames.size) // pre-roll window holds the last 5 frames (incl. the 2 loud ones)
        Assert.assertFalse(start.ended)
        Assert.assertTrue(g.speaking)
        repeat(9) { Assert.assertFalse(g.feed(quiet()).ended) }
        val end = g.feed(quiet())
        Assert.assertTrue(end.ended)
        Assert.assertFalse(g.speaking)
    }

    @Test fun aShortPauseDoesNotEndTheUtterance() {
        val g = VadGate(hangoverFrames = 10, startFrames = 2)
        g.feed(loud()); g.feed(loud())
        repeat(5) { g.feed(quiet()) }
        Assert.assertFalse(g.feed(loud()).ended)
        repeat(9) { Assert.assertFalse(g.feed(quiet()).ended) }
        Assert.assertTrue(g.feed(quiet()).ended)
    }

    @Test fun noiseFloorFollowsTheRoom() {
        val g = VadGate()
        repeat(400) { g.feed(frame(300)) } // steady hum below the minimum threshold
        Assert.assertTrue(g.noiseFloor > 250)
        Assert.assertFalse(g.speaking)
    }

    @Test fun aConstantLoudSoundIsCutAndBecomesNoise() {
        val g = VadGate(maxUtteranceFrames = 50, hangoverFrames = 30)
        var ended = 0
        repeat(200) { if (g.feed(frame(2000)).ended) ended++ }
        Assert.assertTrue("should end at least once", ended >= 1)
        Assert.assertTrue("floor should have risen", g.noiseFloor > 1000)
        // after adapting, the same sound no longer counts as speech
        repeat(20) { g.feed(frame(2000)) }
        Assert.assertFalse(g.speaking)
    }

    @Test fun speechShareIsWhatTheRecognizerActuallyHeard() {
        val g = VadGate(hangoverFrames = 10)
        repeat(90) { g.feed(quiet()) }
        repeat(5) { g.feed(loud()) }
        repeat(15) { g.feed(quiet()) }
        val s = g.speechShare()
        Assert.assertTrue("share=$s", s in 0.05..0.3)
    }

    @Test fun rmsOfSilenceAndEmpty() {
        Assert.assertEquals(0.0, VadGate.rms(ShortArray(0)), 0.0001)
        Assert.assertEquals(100.0, VadGate.rms(ShortArray(10) { 100 }), 0.0001)
    }
}

class SpeechTextTest {
    @Test fun marksAndMarkdownAreRemoved() {
        Assert.assertEquals("ঠিক আছে, করেছি।", SpeechText.prepare("✓ ঠিক আছে… করেছি।".replace("…", ", ").replace("✓ ", "")))
        Assert.assertEquals("a, b", SpeechText.prepare("a → b"))
        Assert.assertEquals("hello world", SpeechText.prepare("**hello** _world_"))
        Assert.assertEquals("কী?", SpeechText.prepare("কী???"))
        Assert.assertEquals("one. two", SpeechText.prepare("one\ntwo"))
    }

    @Test fun shortTextIsOneChunk() {
        Assert.assertEquals(listOf("আজ আবহাওয়া ভালো। বৃষ্টি হবে না।"), SpeechText.chunks("আজ আবহাওয়া ভালো। বৃষ্টি হবে না।"))
    }

    @Test fun longTextIsSplitAtSentencesAndNothingIsLost() {
        val s = (1..40).joinToString(" ") { "এটা বাক্য নম্বর $it।" }
        val c = SpeechText.chunks(s, 100)
        Assert.assertTrue(c.size > 3)
        for (x in c) Assert.assertTrue("len=${x.length}", x.length <= 100)
        Assert.assertEquals(s.replace(" ", ""), c.joinToString("").replace(" ", ""))
    }

    @Test fun oneVeryLongSentenceIsStillCut() {
        val s = (1..100).joinToString(" ") { "শব্দ$it" }
        val c = SpeechText.chunks(s, 80)
        for (x in c) Assert.assertTrue(x.length <= 80)
        Assert.assertEquals(s.replace(" ", ""), c.joinToString("").replace(" ", ""))
    }

    @Test fun emptyHasNoChunks() {
        Assert.assertTrue(SpeechText.chunks("  ✓  ").isEmpty())
    }

    @Test fun rateAndPitchAreClamped() {
        Assert.assertEquals(1.0, SpeechText.rate(100).toDouble(), 0.001)
        Assert.assertEquals(0.6, SpeechText.rate(5).toDouble(), 0.001)
        Assert.assertEquals(1.5, SpeechText.rate(900).toDouble(), 0.001)
        Assert.assertEquals(0.7, SpeechText.pitch(0).toDouble(), 0.001)
        Assert.assertEquals(1.3, SpeechText.pitch(500).toDouble(), 0.001)
    }
}

class WhisperTest {
    @Test fun modeFromKeyDefaultsToSystem() {
        Assert.assertEquals(SttMode.SYSTEM, SttMode.from(null))
        Assert.assertEquals(SttMode.SYSTEM, SttMode.from("zzz"))
        Assert.assertEquals(SttMode.WHISPER, SttMode.from("whisper"))
        Assert.assertEquals(SttMode.AUTO, SttMode.from("auto"))
    }

    @Test fun whisperIsUsedOnlyWhenChosenAndConfigured() {
        Assert.assertFalse(SttMode.useWhisper(SttMode.SYSTEM, "bn-BD", true))
        Assert.assertFalse(SttMode.useWhisper(SttMode.WHISPER, "bn-BD", false))
        Assert.assertTrue(SttMode.useWhisper(SttMode.WHISPER, "en-US", true))
        Assert.assertTrue(SttMode.useWhisper(SttMode.AUTO, "bn-BD", true))
        Assert.assertFalse(SttMode.useWhisper(SttMode.AUTO, "en-US", true))
    }

    @Test fun endpointIsDerivedFromTheAssistUrl() {
        Assert.assertEquals("https://x.vercel.app/api/transcribe", SttEndpoint.fromAssistUrl("https://x.vercel.app/api/assist"))
        Assert.assertEquals("https://x.vercel.app/api/transcribe", SttEndpoint.fromAssistUrl("https://x.vercel.app/api/assist/"))
        Assert.assertEquals("https://x.vercel.app/api/transcribe", SttEndpoint.fromAssistUrl("https://x.vercel.app"))
        Assert.assertNull(SttEndpoint.fromAssistUrl("http://x.vercel.app/api/assist")) // audio is never sent without TLS
        Assert.assertNull(SttEndpoint.fromAssistUrl(""))
        Assert.assertNull(SttEndpoint.fromAssistUrl(null))
        Assert.assertEquals("bn", SttEndpoint.languageOf("bn-BD"))
        Assert.assertEquals("en", SttEndpoint.languageOf("en-US"))
    }

    @Test fun wavHeaderIsValid() {
        val w = Wav.encode(ShortArray(100) { it.toShort() }, 16000)
        Assert.assertEquals(244, w.size)
        Assert.assertEquals("RIFF", String(w, 0, 4))
        Assert.assertEquals("WAVE", String(w, 8, 4))
        Assert.assertEquals("data", String(w, 36, 4))
        val bb = java.nio.ByteBuffer.wrap(w).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        Assert.assertEquals(236, bb.getInt(4))
        Assert.assertEquals(16000, bb.getInt(24))
        Assert.assertEquals(200, bb.getInt(40))
        Assert.assertEquals(5, bb.getShort(44 + 10).toInt())
    }

    @Test fun parsesAnswers() {
        Assert.assertEquals(Transcript.Text("রহিমকে কল করো"), Transcript.parse(200, "{\"text\":\" রহিমকে কল করো \"}"))
        Assert.assertEquals(Transcript.Silence, Transcript.parse(200, "{\"text\":\"\"}"))
        Assert.assertEquals(Transcript.Silence, Transcript.parse(200, "{\"text\":\"Thank you.\"}"))
        Assert.assertTrue(Transcript.parse(500, "{}") is Transcript.Failed)
        Assert.assertTrue(Transcript.parse(200, "not json") is Transcript.Failed)
    }
}
