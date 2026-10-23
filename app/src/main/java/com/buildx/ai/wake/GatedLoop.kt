package com.buildx.ai.wake

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.buildx.ai.LogStore
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener

/**
 * Phase 5 (low battery): the same job as Vosk's SpeechService, but the recognizer only gets audio while [VadGate]
 * hears speech, so it sleeps through silence. Results reach the listener on the main thread, like SpeechService.
 * If anything goes wrong at start, [start] returns false and the caller falls back to SpeechService.
 */
class GatedLoop(private val ctx: Context, private val rec: Recognizer) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null
    private var audio: AudioRecord? = null

    fun start(l: RecognitionListener): Boolean {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val ar = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 2 * 2))
        } catch (e: Exception) {
            LogStore.add(ctx, "GATE AudioRecord: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); return false }
        try {
            ar.startRecording()
        } catch (e: Exception) {
            ar.release(); return false
        }
        if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) { ar.release(); return false }
        audio = ar
        running = true
        thread = Thread({ loop(ar, l) }, "buildx-gated").also { it.start() }
        return true
    }

    private fun loop(ar: AudioRecord, l: RecognitionListener) {
        val gate = VadGate()
        val frame = ShortArray(FRAME)
        var lastLog = System.currentTimeMillis()
        var sinceUtterance = 0
        try {
            while (running) {
                var got = 0
                while (running && got < FRAME) {
                    val n = ar.read(frame, got, FRAME - got)
                    if (n < 0) throw IllegalStateException("AudioRecord.read=$n")
                    got += n
                }
                if (!running) break
                val out = gate.feed(frame.copyOf())
                for (f in out.frames) {
                    val bytes = toBytes(f)
                    if (rec.acceptWaveForm(bytes, bytes.size)) {
                        val r = rec.result
                        main.post { if (running) l.onResult(r) }
                        sinceUtterance = 0
                    } else if (++sinceUtterance % 5 == 0) {
                        val p = rec.partialResult
                        main.post { if (running) l.onPartialResult(p) }
                    }
                }
                if (out.ended) {
                    val r = rec.finalResult
                    main.post { if (running) l.onResult(r) }
                    rec.reset()
                    sinceUtterance = 0
                }
                val now = System.currentTimeMillis()
                if (now - lastLog > 120_000L) {
                    lastLog = now
                    LogStore.add(ctx, "GATE শোনা ${"%.1f".format(gate.speechShare() * 100)}% ফ্রেমে (বাকিটা ঘুমিয়ে), শব্দের মেঝে=${gate.noiseFloor.toInt()}")
                }
            }
        } catch (e: Throwable) {
            main.post { l.onError(Exception("gated loop: ${e.javaClass.simpleName}: ${e.message}")) }
        } finally {
            LogStore.add(ctx, "GATE বন্ধ: ${"%.1f".format(gate.speechShare() * 100)}% ফ্রেমে শোনা হয়েছিল")
        }
    }

    /** stops listening and frees the microphone; safe to call twice */
    fun stop() {
        running = false
        try { thread?.join(800) } catch (_: InterruptedException) { }
        thread = null
        audio?.let {
            try { it.stop() } catch (_: Exception) { }
            it.release()
        }
        audio = null
        try { rec.close() } catch (_: Exception) { }
    }

    private fun toBytes(f: ShortArray): ByteArray {
        val b = ByteArray(f.size * 2)
        for (i in f.indices) {
            b[i * 2] = (f[i].toInt() and 0xFF).toByte()
            b[i * 2 + 1] = (f[i].toInt() shr 8).toByte()
        }
        return b
    }

    companion object { const val FRAME = 320 } // 20 ms at 16 kHz
}
