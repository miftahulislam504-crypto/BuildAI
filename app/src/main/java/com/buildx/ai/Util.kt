package com.buildx.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.buildx.ai.stt.SttMode
import com.buildx.ai.stt.Transcript
import com.buildx.ai.stt.WhisperStt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Appends test results to a file and notifies the UI. Used by both the Activity and the Service. */
object LogStore {
    @Volatile
    var listener: (() -> Unit)? = null

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(ctx: Context, msg: String) {
        val line = "${fmt.format(Date())} $msg\n"
        try {
            val f = File(ctx.filesDir, "phase0.log")
            if (f.length() > 200_000) f.writeText(f.readText().takeLast(100_000).substringAfter('\n')) // keep the log small
            f.appendText(line)
        } catch (_: Exception) {
        }
        listener?.invoke()
    }

    @Synchronized
    fun read(ctx: Context): String = try {
        File(ctx.filesDir, "phase0.log").readText()
    } catch (_: Exception) {
        ""
    }

    @Synchronized
    fun clear(ctx: Context) {
        File(ctx.filesDir, "phase0.log").delete()
        listener?.invoke()
    }
}

/**
 * One-shot speech recognition. Must be created and started on the main thread.
 * [start]'s callback gets the best transcript, or null on error/timeout/no speech.
 */
class SttRunner(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var done = false
    private var onDone: ((String?) -> Unit)? = null

    private val watchdog = Runnable {
        LogStore.add(ctx, "STT: টাইমআউট (watchdog ১৫ সেকেন্ড)")
        finish(null)
    }

    private var whisper: WhisperStt? = null

    fun start(lang: String, onDone: (String?) -> Unit) {
        this.onDone = onDone

        // Phase 5: Whisper first when the person chose it (and the proxy is set); any failure falls back to the phone's recognizer
        val prefs = ctx.applicationContext.getSharedPreferences("buildx", Context.MODE_PRIVATE)
        val mode = SttMode.from(prefs.getString("sttMode", "system"))
        val cfg = WhisperStt.configFrom(ctx)
        if (cfg != null && SttMode.useWhisper(mode, lang, true)) {
            val w = WhisperStt(ctx, cfg)
            whisper = w
            main.postDelayed(watchdog, 30000)
            w.start(lang) { t ->
                whisper = null
                main.removeCallbacks(watchdog)
                when (t) {
                    is Transcript.Text -> finish(t.text)
                    is Transcript.Silence -> finish(null)
                    is Transcript.Failed -> {
                        LogStore.add(ctx, "STT[whisper] ব্যর্থ (${t.why}), ফোনের রিকগনাইজারে ফিরলাম")
                        if (!done) startSystem(lang)
                    }
                }
            }
            return
        }
        startSystem(lang)
    }

    private fun startSystem(lang: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            LogStore.add(ctx, "STT: রিকগনিশন সার্ভিস পাওয়া যায়নি")
            finish(null)
            return
        }

        val t0 = SystemClock.elapsedRealtime()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                LogStore.add(ctx, "STT: বলুন ($lang)")
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                val ms = SystemClock.elapsedRealtime() - t0
                LogStore.add(ctx, "STT error: ${errorName(error)} (${ms}ms)")
                finish(null)
            }

            override fun onResults(results: Bundle?) {
                val ms = SystemClock.elapsedRealtime() - t0
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                val top = list?.getOrNull(0) ?: ""
                LogStore.add(ctx, "STT [$lang] \"$top\" conf=${conf?.getOrNull(0)} (${ms}ms)")
                list?.drop(1)?.forEach { LogStore.add(ctx, "   alt: \"$it\"") }
                finish(top.ifBlank { null })
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        main.postDelayed(watchdog, 15000)
        r.startListening(intent)
    }

    private fun finish(result: String?) {
        if (done) return
        done = true
        main.removeCallbacks(watchdog)
        whisper?.cancel() // a timeout must also free the microphone
        whisper = null
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        onDone?.invoke(result)
    }

    /** Stop without calling the callback (used when the service is being destroyed). */
    fun cancel() {
        done = true
        main.removeCallbacks(watchdog)
        whisper?.cancel()
        whisper = null
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private fun errorName(e: Int): String = when (e) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        12 -> "LANGUAGE_NOT_SUPPORTED"
        13 -> "LANGUAGE_UNAVAILABLE"
        else -> "CODE_$e"
    }
}
