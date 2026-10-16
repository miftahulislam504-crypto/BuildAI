package com.buildx.ai.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.buildx.ai.LogStore
import java.util.Locale

/**
 * Text-to-speech with a completion callback (so the wake word is only resumed after speech ends,
 * otherwise the mic would hear our own voice). Use from the main thread only.
 */
class Speaker(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var initDone = false
    private var ready = false
    private var counter = 0
    private val callbacks = HashMap<String, () -> Unit>()
    private val pending = ArrayList<Pair<String, () -> Unit>>()

    fun init(langTag: String, onReady: () -> Unit = {}) {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            main.post { onInit(status, langTag, onReady) }
        }
    }

    private fun onInit(status: Int, langTag: String, onReady: () -> Unit) {
        val t = tts
        if (status == TextToSpeech.SUCCESS && t != null) {
            val r = t.setLanguage(Locale.forLanguageTag(langTag))
            ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            LogStore.add(ctx, "TTS: lang=$langTag result=$r ready=$ready engine=${t.defaultEngine}")
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    fire(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    fire(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    LogStore.add(ctx, "TTS error code=$errorCode")
                    fire(utteranceId)
                }
            })
        } else {
            LogStore.add(ctx, "TTS: init ব্যর্থ (status=$status)")
        }
        initDone = true

        val queued = ArrayList(pending)
        pending.clear()
        queued.forEach { (text, cb) -> speak(text, cb) }
        onReady()
    }

    /** Speaks [text], then calls [onDone] on the main thread (also when speech is unavailable). */
    fun speak(text: String, onDone: () -> Unit) {
        if (!initDone) {
            pending.add(Pair(text, onDone))
            return
        }
        val t = tts
        if (t == null || !ready) {
            LogStore.add(ctx, "TTS নীরব (ভয়েজ/ভাষা নেই): $text")
            main.post { onDone() }
            return
        }
        val id = "u${counter++}"
        synchronized(callbacks) { callbacks[id] = onDone }
        LogStore.add(ctx, "SAY: $text")
        val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS) {
            LogStore.add(ctx, "TTS speak() ব্যর্থ ($r)")
            fire(id)
        }
    }

    // Called from TTS binder threads, so hop to the main thread.
    private fun fire(id: String?) {
        val cb = synchronized(callbacks) { callbacks.remove(id) } ?: return
        main.post { cb() }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        synchronized(callbacks) { callbacks.clear() }
        pending.clear()
    }
}
