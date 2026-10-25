package com.buildx.ai.wake

import android.content.Context
import android.os.SystemClock
import com.buildx.ai.LogStore
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/** Anything that can listen for the wake phrase. Swap the implementation to change engines. */
interface WakeEngine {
    fun start()
    fun pause()
    fun release()
}

/**
 * Wake word via a Vosk recognizer restricted to a tiny grammar, so it only has to tell
 * the wake phrase ("hey build" unless the person chose another) apart from everything else ([unk]). Offline, free, no account, no training.
 *
 * The constructor loads the model (slow, ~300 MB RAM): create it on a background thread.
 * Callbacks from SpeechService arrive on the main thread.
 */
class VoskWakeEngine(
    private val ctx: Context,
    modelPath: String,
    spkPath: String?,
    private val sensitivity: WakeSensitivity,
    @Volatile var voice: VoiceLock?,
    private val phrase: WakePhrase = WakePhrase.DEFAULT,
    private val lowPower: Boolean = false,
    private val onWake: () -> Unit
) : WakeEngine, RecognitionListener {

    private val model = Model(modelPath)
    private val spkModel: SpeakerModel? = spkPath?.let {
        try {
            SpeakerModel(it)
        } catch (e: Exception) {
            LogStore.add(ctx, "ERROR voice model: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
    private var service: SpeechService? = null
    private var gated: GatedLoop? = null
    private var lastFire = 0L

    /** the phrase (for "hey build" also the X forms, so "Hey BuildX" is not thrown away as unknown speech) */
    private val grammar = phrase.grammarJson()

    /** collecting voice samples for "only my voice" */
    class Enrollment(val needed: Int, val onProgress: (Int, Double?) -> Unit, val onDone: (DoubleArray?, List<DoubleArray>, String) -> Unit) {
        val samples = ArrayList<DoubleArray>()
    }

    @Volatile
    private var enrollment: Enrollment? = null

    val canHearVoices: Boolean get() = spkModel != null

    /** false if there is no speaker model */
    fun beginEnroll(e: Enrollment): Boolean {
        if (spkModel == null) return false
        enrollment = e
        return true
    }

    fun cancelEnroll() {
        enrollment = null
    }

    private fun newRecognizer(): Recognizer {
        val rec = Recognizer(model, 16000.0f, grammar)
        rec.setWords(true) // word confidences, used by the strict level
        spkModel?.let { rec.setSpeakerModel(it) } // adds a voice-print ("spk") to every final result
        return rec
    }

    override fun start() {
        if (service != null || gated != null) return
        try {
            val rec = newRecognizer()
            if (lowPower) {
                val g = GatedLoop(ctx, rec)
                if (g.start(this)) {
                    gated = g
                    LogStore.add(ctx, "LISTENING: low-power mode (recognizes only when a voice is detected)")
                    return
                }
                LogStore.add(ctx, "Low-power mode did not start, falling back to normal listening")
            }
            val s = SpeechService(rec, 16000.0f)
            s.startListening(this)
            service = s
        } catch (e: Exception) {
            LogStore.add(ctx, "ERROR Vosk start: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Releases the microphone (needed for STT/TTS). Call start() again to resume. */
    override fun pause() {
        gated?.let {
            gated = null
            it.stop()
        }
        val s = service ?: return
        service = null
        try {
            s.cancel()
            s.shutdown()
        } catch (e: Exception) {
            LogStore.add(ctx, "ERROR Vosk pause: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun release() {
        pause()
        try {
            spkModel?.close()
            model.close()
        } catch (_: Exception) {
        }
    }

    // ---------- RecognitionListener ----------

    override fun onPartialResult(hypothesis: String?) {
        // a voice-print only exists for a finished utterance, so with "only my voice" (or while enrolling) wait for it
        if (voice != null && spkModel != null || enrollment != null) return
        val p = field(hypothesis, "partial")
        if (p.isNotEmpty() && WakePolicy.shouldFire(sensitivity, false, p, null, phrase)) fire("partial \"$p\"")
    }

    override fun onResult(hypothesis: String?) {
        val t = field(hypothesis, "text")
        val spk = spkVector(hypothesis)

        enrollment?.let { e ->
            if (phrase.matches(t) && spk != null) handleEnrollSample(e, spk)
            else if (phrase.matches(t)) LogStore.add(ctx, "Voice enrollment: no voice data found (too short or too quiet?), say it again")
            return
        }

        if (t.isNotEmpty() && WakePolicy.shouldFire(sensitivity, true, t, avgConf(hypothesis), phrase)) {
            val lock = voice
            if (lock != null && spkModel != null) {
                val d = spk?.let { SpeakerMath.cosineDistance(lock.print, it) }
                val frames = try { JSONObject(hypothesis ?: "{}").optInt("spk_frames", -1) } catch (_: Exception) { -1 }
                val ok = d != null && d <= lock.maxDistance
                LogStore.add(ctx, "WAKE-VOICE distance=${d?.let { "%.2f".format(it) } ?: "?"} limit=${"%.2f".format(lock.maxDistance)} frames=$frames -> ${if (ok) "my voice" else "rejected"}")
                if (!ok) return
            }
            fire("final \"$t\"")
        }
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(exception: Exception?) {
        LogStore.add(ctx, "ERROR Vosk: ${exception?.javaClass?.simpleName}: ${exception?.message}")
    }

    override fun onTimeout() {}

    private fun spkVector(json: String?): DoubleArray? = try {
        val arr = JSONObject(json ?: "{}").optJSONArray("spk")
        if (arr == null || arr.length() == 0) null else DoubleArray(arr.length()) { arr.getDouble(it) }
    } catch (_: Exception) {
        null
    }

    private fun handleEnrollSample(e: Enrollment, spk: DoubleArray) {
        e.samples.add(spk)
        val mean = SpeakerMath.mean(e.samples)
        val d = if (e.samples.size > 1 && mean != null) SpeakerMath.cosineDistance(spk, mean) else null
        e.onProgress(e.samples.size, d)
        if (e.samples.size >= e.needed) {
            enrollment = null
            val m = SpeakerMath.mean(e.samples)
            e.onDone(m, e.samples.toList(), if (m != null) "ok" else "গড় বের করা যায়নি")
        }
    }

    private fun avgConf(json: String?): Double? = try {
        val arr = JSONObject(json ?: "{}").optJSONArray("result")
        if (arr == null || arr.length() == 0) null
        else (0 until arr.length()).map { arr.getJSONObject(it).optDouble("conf", 1.0) }.average()
    } catch (_: Exception) {
        null
    }

    private fun field(json: String?, key: String): String = try {
        JSONObject(json ?: "{}").optString(key, "")
    } catch (_: Exception) {
        ""
    }

    private fun fire(why: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFire < 3000) return // partial + final of the same utterance
        lastFire = now
        LogStore.add(ctx, "WAKE (vosk, $sensitivity): $why")
        onWake()
    }
}
