package com.buildx.ai.wake

import android.content.Context
import android.os.SystemClock
import com.buildx.ai.LogStore
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
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
 * "hey build x" apart from everything else ([unk]). Offline, free, no account, no training.
 *
 * The constructor loads the model (slow, ~300 MB RAM): create it on a background thread.
 * Callbacks from SpeechService arrive on the main thread.
 */
class VoskWakeEngine(
    private val ctx: Context,
    modelPath: String,
    private val sensitivity: WakeSensitivity,
    private val onWake: () -> Unit
) : WakeEngine, RecognitionListener {

    private val model = Model(modelPath)
    private var service: SpeechService? = null
    private var lastFire = 0L

    // Words missing from the model's vocabulary are ignored by Vosk, so "x" may be dropped.
    private val grammar = "[\"hey build x\", \"hey build ex\", \"[unk]\"]"

    override fun start() {
        if (service != null) return
        try {
            val rec = Recognizer(model, 16000.0f, grammar)
            rec.setWords(true) // word confidences, used by the strict level
            val s = SpeechService(rec, 16000.0f)
            s.startListening(this)
            service = s
        } catch (e: Exception) {
            LogStore.add(ctx, "ERROR Vosk start: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Releases the microphone (needed for STT/TTS). Call start() again to resume. */
    override fun pause() {
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
            model.close()
        } catch (_: Exception) {
        }
    }

    // ---------- RecognitionListener ----------

    override fun onPartialResult(hypothesis: String?) {
        val p = field(hypothesis, "partial")
        if (p.isNotEmpty() && WakePolicy.shouldFire(sensitivity, false, p, null)) fire("partial \"$p\"")
    }

    override fun onResult(hypothesis: String?) {
        val t = field(hypothesis, "text")
        if (t.isNotEmpty() && WakePolicy.shouldFire(sensitivity, true, t, avgConf(hypothesis))) fire("final \"$t\"")
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(exception: Exception?) {
        LogStore.add(ctx, "ERROR Vosk: ${exception?.javaClass?.simpleName}: ${exception?.message}")
    }

    override fun onTimeout() {}

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
