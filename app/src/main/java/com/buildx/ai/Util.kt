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
        LogStore.add(ctx, "STT: timed out (15 second watchdog)")
        finish(null)
    }

    private var whisper: WhisperStt? = null
    private var attempt = 0
    private var silenceMs = 0L
    private var spoke = false

    /** true when the person said nothing at all (not an error, and not something that was misunderstood) */
    @Volatile
    var silent = false
        private set

    /** fires when the microphone has been open for [silenceMs] and nobody has started speaking */
    private val silenceTimer = Runnable {
        if (!done && !spoke) {
            LogStore.add(ctx, "STT: no speech for ${silenceMs / 1000} seconds")
            silent = true
            finish(null)
        }
    }

    fun start(lang: String, silenceMs: Long = 0L, onDone: (String?) -> Unit) {
        this.silenceMs = silenceMs
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
                    is Transcript.Silence -> {
                        silent = true
                        finish(null)
                    }
                    is Transcript.Failed -> {
                        LogStore.add(ctx, "STT[whisper] failed (${t.why}), falling back to the phone's recognizer")
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
            LogStore.add(ctx, "STT: recognition service not found")
            finish(null)
            return
        }

        val t0 = SystemClock.elapsedRealtime()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                LogStore.add(ctx, "STT: speak now ($lang)")
                if (silenceMs > 0) {
                    main.removeCallbacks(silenceTimer)
                    main.postDelayed(silenceTimer, silenceMs)
                }
            }

            override fun onBeginningOfSpeech() {
                spoke = true
                main.removeCallbacks(silenceTimer)
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                val ms = SystemClock.elapsedRealtime() - t0
                LogStore.add(ctx, "STT error: ${errorName(error)} (${ms}ms)")
                if (!done && attempt < 1) {
                    val languageMissing = error == 12 || error == 13
                    val failedFast = ms < 2000 && error in FAST_RETRY_ERRORS
                    if (languageMissing && lang.startsWith("bn") && lang != "bn-IN") {
                        attempt++
                        LogStore.add(ctx, "STT: $lang is not available on this phone, trying bn-IN")
                        restart("bn-IN")
                        return
                    }
                    if (failedFast) {
                        attempt++
                        LogStore.add(ctx, "STT: failed right away, trying once more")
                        restart(lang)
                        return
                    }
                }
                if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT && !spoke) silent = true
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
            // do not cut the person off at the first short pause (some recognizers ignore these, the Google one honours them)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        }
        main.postDelayed(watchdog, 15000)
        r.startListening(intent)
    }

    /** drop the current recognizer and start a fresh one a moment later (used for the single quiet retry) */
    private fun restart(lang: String) {
        main.removeCallbacks(watchdog)
        main.removeCallbacks(silenceTimer)
        spoke = false
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        main.postDelayed({ if (!done) startSystem(lang) }, 400)
    }

    private fun finish(result: String?) {
        if (done) return
        done = true
        main.removeCallbacks(watchdog)
        main.removeCallbacks(silenceTimer)
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
        main.removeCallbacks(silenceTimer)
        whisper?.cancel()
        whisper = null
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private companion object {
        /** errors that, when they come within two seconds, usually mean "the recognizer was not ready", not "you said nothing" */
        val FAST_RETRY_ERRORS = setOf(
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        )
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
