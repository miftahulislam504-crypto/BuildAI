package com.buildx.ai.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.buildx.ai.LogStore
import java.util.Locale

/**
 * Text-to-speech with a completion callback (so the wake word is only resumed after speech ends,
 * otherwise the mic would hear our own voice). Use from the main thread only.
 *
 * Samsung's own engine (com.samsung.SMT) has no Bengali, so Google's engine is tried first,
 * then the phone's default engine. For each engine: bn-BD, then bn-IN, then plain bn.
 *
 * Phase 5: the voice, speed and pitch come from Settings (ttsVoice / ttsRate / ttsPitch), the reply is cleaned and cut
 * into sentences ([SpeechText]) so long answers are not truncated, and [voices] lists what the engine offers.
 */
class Speaker(private val ctx: Context) {

    /** one selectable voice */
    data class VoiceInfo(val name: String, val label: String, val needsNetwork: Boolean, val quality: Int)

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
            LogStore.add(ctx, "TTS[$name]: init failed (status=$status)")
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
                LogStore.add(ctx, "TTS: using engine=$name lang=$tag")
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
        applyTuning(t)
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

    private val prefs get() = ctx.applicationContext.getSharedPreferences("buildx", Context.MODE_PRIVATE)

    private fun usable(v: Voice): Boolean = !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)

    /** the voices of the language now in use, best first (installed ones only) */
    fun voices(): List<VoiceInfo> {
        val t = tts ?: return emptyList()
        val lang = t.voice?.locale?.language ?: return emptyList()
        return try {
            t.voices.orEmpty().filter { it.locale.language == lang && usable(it) }
                .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired }.thenBy { it.name })
                .map { VoiceInfo(it.name, it.name + " · " + it.locale.displayCountry.ifBlank { it.locale.language }, it.isNetworkConnectionRequired, it.quality) }
        } catch (e: Exception) {
            LogStore.add(ctx, "TTS voices(): ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    /** speed, pitch and voice from Settings; with no voice chosen, the best installed one that works offline */
    private fun applyTuning(t: TextToSpeech) {
        try {
            t.setSpeechRate(SpeechText.rate(prefs.getInt("ttsRate", 100)))
            t.setPitch(SpeechText.pitch(prefs.getInt("ttsPitch", 100)))
            val lang = t.voice?.locale?.language ?: return
            val all = t.voices.orEmpty().filter { it.locale.language == lang && usable(it) }
            val saved = prefs.getString("ttsVoice", "").orEmpty()
            val pick = all.firstOrNull { saved.isNotEmpty() && it.name == saved }
                ?: all.filter { !it.isNetworkConnectionRequired }.maxByOrNull { it.quality }
            if (pick != null && pick.name != t.voice?.name) {
                t.voice = pick
                LogStore.add(ctx, "TTS voice: ${pick.name} (quality ${pick.quality})")
            }
        } catch (e: Exception) {
            LogStore.add(ctx, "TTS tuning: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** call after Settings changed the voice, speed or pitch */
    fun reapplyTuning() {
        tts?.let { applyTuning(it) }
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
            LogStore.add(ctx, "TTS silent (no voice or language): $text")
            main.post { onDone() }
            return
        }
        val parts = SpeechText.chunks(text)
        if (parts.isEmpty()) {
            main.post { onDone() }
            return
        }
        LogStore.add(ctx, "SAY: $text")
        applyTuning(t) // cheap, and picks up a change made in Settings while the service was running
        var lastId = ""
        for ((i, part) in parts.withIndex()) {
            val id = "u${counter++}"
            lastId = id
            if (i == parts.lastIndex) synchronized(callbacks) { callbacks[id] = onDone } // only the end of the last sentence counts
            val r = t.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            if (r != TextToSpeech.SUCCESS) {
                LogStore.add(ctx, "TTS speak() failed ($r)")
                if (i == parts.lastIndex) fire(id)
            }
        }
        if (lastId.isEmpty()) main.post { onDone() }
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
