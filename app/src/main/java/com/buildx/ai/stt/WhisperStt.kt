package com.buildx.ai.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.buildx.ai.LogStore
import com.buildx.ai.llm.AndroidLlm
import com.buildx.ai.llm.LlmConfig
import com.buildx.ai.wake.VadGate
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Phase 5: one spoken command heard by Whisper on your own proxy (/api/transcribe), which is much better at Bangla than
 * the phone's recognizer. It records until the person stops talking ([VadGate]), sends the audio over HTTPS and gives
 * back the text. Audio leaves the phone only when the person has chosen Whisper in Settings.
 * The callback gets a [Transcript] on the main thread; [Transcript.Failed] means the caller should use the phone's recognizer.
 */
class WhisperStt(private val ctx: Context, private val cfg: LlmConfig) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    @Volatile private var finished = false
    private var thread: Thread? = null

    fun start(lang: String, onDone: (Transcript) -> Unit) {
        val endpoint = SttEndpoint.fromAssistUrl(cfg.url)
        if (endpoint == null) {
            deliver(onDone, Transcript.Failed("ঠিকানা ঠিক নয়"))
            return
        }
        thread = Thread({
            val result = try {
                val pcm = record()
                when {
                    cancelled -> null
                    pcm == null -> Transcript.Failed("মাইক খোলা যায়নি")
                    pcm.isEmpty() -> Transcript.Silence
                    else -> send(endpoint, SttEndpoint.languageOf(lang), pcm)
                }
            } catch (e: Throwable) {
                Transcript.Failed(e.javaClass.simpleName)
            }
            if (result != null) deliver(onDone, result)
        }, "buildx-whisper").also { it.start() }
    }

    fun cancel() {
        cancelled = true
        finished = true
    }

    private fun deliver(onDone: (Transcript) -> Unit, t: Transcript) {
        main.post {
            if (finished && cancelled) return@post
            finished = true
            onDone(t)
        }
    }

    /** speech from the first loud frame until 0.9 s of quiet; empty = nobody spoke; null = the microphone would not start */
    private fun record(): ShortArray? {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val ar = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        } catch (e: Exception) {
            return null
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); return null }
        val gate = VadGate(preRollFrames = 12, hangoverFrames = 45, startFrames = 2, maxUtteranceFrames = 700, minRms = 250.0)
        val all = ArrayList<ShortArray>()
        try {
            ar.startRecording()
            if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) return null
            LogStore.add(ctx, "STT[whisper]: speak now")
            val frame = ShortArray(FRAME)
            var waited = 0
            while (!cancelled) {
                var got = 0
                while (!cancelled && got < FRAME) {
                    val n = ar.read(frame, got, FRAME - got)
                    if (n < 0) return null
                    got += n
                }
                if (cancelled) return ShortArray(0)
                val out = gate.feed(frame.copyOf())
                all.addAll(out.frames)
                if (out.ended) break
                if (!gate.speaking && all.isEmpty() && ++waited > MAX_WAIT_FRAMES) break // nobody started talking
                if (all.size > MAX_FRAMES) break
            }
        } finally {
            try { ar.stop() } catch (_: Exception) { }
            ar.release()
        }
        val pcm = ShortArray(all.size * FRAME)
        for ((i, f) in all.withIndex()) System.arraycopy(f, 0, pcm, i * FRAME, FRAME)
        return pcm
    }

    private fun send(endpoint: String, lang: String, pcm: ShortArray): Transcript {
        val wav = Wav.encode(pcm)
        val t0 = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$endpoint?lang=$lang").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 15_000
                doOutput = true
                instanceFollowRedirects = false // a redirect could carry the token somewhere else
                setRequestProperty("Content-Type", "audio/wav")
                setRequestProperty("Accept", "application/json")
                if (cfg.token.isNotEmpty()) setRequestProperty("Authorization", "Bearer ${cfg.token}")
                setFixedLengthStreamingMode(wav.size)
            }
            conn.outputStream.use { it.write(wav) }
            val code = conn.responseCode
            val body = readCapped(if (code in 200..299) conn.inputStream else conn.errorStream)
            val t = Transcript.parse(code, body)
            LogStore.add(ctx, "STT[whisper] [$lang] ${pcm.size / 16}ms of audio -> ${if (t is Transcript.Text) "\"${t.text}\"" else t} (${System.currentTimeMillis() - t0}ms)")
            t
        } catch (e: SocketTimeoutException) {
            Transcript.Failed("timeout")
        } catch (e: Exception) {
            Transcript.Failed(e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun readCapped(s: java.io.InputStream?): String {
        if (s == null) return ""
        s.use {
            val buf = ByteArray(4096)
            val out = ByteArrayOutputStream()
            while (out.size() < 32_768) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        }
    }

    companion object {
        const val FRAME = 320
        private const val MAX_WAIT_FRAMES = 300 // 6 s without a word
        private const val MAX_FRAMES = 750 // 15 s
        fun configFrom(ctx: Context): LlmConfig? {
            val p = ctx.applicationContext.getSharedPreferences("buildx", Context.MODE_PRIVATE)
            return LlmConfig.of(true, p.getString(AndroidLlm.KEY_URL, "").orEmpty(), p.getString(AndroidLlm.KEY_TOKEN, "").orEmpty())
        }
    }
}
