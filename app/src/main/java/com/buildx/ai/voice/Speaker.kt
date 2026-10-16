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
 *
 * Samsung's own engine (com.samsung.SMT) has no Bengali, so Google's engine is tried first,
 * then the phone's default engine. For each engine: bn-BD, then bn-IN, then plain bn.
 */
class Speaker(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var initDone = false
    private var ready = false
    private var counter = 0
    private val callbacks = HashMap<String, () -> Unit>()
    private val pending = ArrayList<Pair<String, () -> Unit>>()

    // null = the phone's default engine
    private val engines: List<String?> = listOf("com.google.android.tts", null)

    fun init(langTag: String, onReady: () -> Unit = {}) {
        val tags = if (langTag.startsWith("bn")) listOf(langTag, "bn-IN", "bn") else listOf(langTag)
        tryEngine(0, tags, onReady)
    }

    private fun tryEngine(index: Int, tags: List<String>, onReady: () -> Unit) {
        if (index >= engines.size) {
            finishInit(false, onReady)
            return
        }
        val engine = engines[index]
        val name = engine ?: "default"
        var created: TextToSpeech? = null
        created = TextToSpeech(
            ctx.applicationContext,
            TextToSpeech.OnInitListener { status ->
                main.post { onEngineInit(created, status, name, index, tags, onReady) }
            },
            engine
        )
    }

    private fun onEngineInit(
        t: TextToSpeech?,
        status: Int,
        name: String,
        index: Int,
        tags: List<String>,
        onReady: () -> Unit
    ) {
        if (status != TextToSpeech.SUCCESS || t == null) {
            LogStore.add(ctx, "TTS[$name]: init ব্যর্থ (status=$status)")
            try {
                t?.shutdown()
            } catch (_: Exception) {
            }
            tryEngine(index + 1, tags, onReady)
            return
        }

        for (tag in tags) {
            val locale = Locale.forLanguageTag(tag)
            val avail = t.isLanguageAvailable(locale)
            LogStore.add(ctx, "TTS[$name]: $tag available=$avail")
            if (avail >= TextToSpeech.LANG_AVAILABLE) {
                t.setLanguage(locale)
                attach(t)
                LogStore.add(ctx, "TTS: ব্যবহার হচ্ছে engine=$name lang=$tag")
                finishInit(true, onReady)
                return
            }
        }

        try {
            t.shutdown()
        } catch (_: Exception) {
        }
        tryEngine(index + 1, tags, onReady)
    }

    private fun attach(t: TextToSpeech) {
        tts = t
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
    }

    private fun finishInit(ok: Boolean, onReady: () -> Unit) {
        ready = ok
        initDone = true
        if (!ok) {
            LogStore.add(
                ctx,
                "TTS: কোনো ইঞ্জিনে এই ভাষা নেই। Settings > TTS > Google ইঞ্জিন > Install voice data > বাংলা (বাংলাদেশ)"
            )
        }
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
