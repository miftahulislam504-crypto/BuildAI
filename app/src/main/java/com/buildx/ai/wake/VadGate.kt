package com.buildx.ai.wake

import java.util.ArrayDeque

/**
 * Phase 5 (low battery): decides frame by frame whether anyone is talking, so the heavy recognizer only runs while
 * there is speech and sleeps through silence. Energy based, with a noise floor that follows the room.
 * Also used to find the end of a spoken command for the Whisper recorder. Pure: no Android classes.
 *
 * Feed fixed-size frames (default 20 ms at 16 kHz). [feed] returns what to hand to the recognizer now:
 * when speech starts, the pre-roll (the last few quiet frames, so the first syllable is not cut) and the frame itself;
 * while speech goes on, each frame; when it ends ([Out.ended]) the last frame, after which the recognizer must be finalized.
 */
class VadGate(
    private val preRollFrames: Int = 15, // 300 ms before the speech
    private val hangoverFrames: Int = 30, // 600 ms of quiet ends an utterance
    private val startFrames: Int = 2, // 40 ms of loud frames in a row starts one
    private val maxUtteranceFrames: Int = 400, // 8 s: cut a very long sound (TV, music) so it adapts as noise
    private val minRms: Double = 350.0,
    private val ratio: Double = 3.0
) {
    class Out(val frames: List<ShortArray>, val ended: Boolean) {
        companion object { val NOTHING = Out(emptyList(), false) }
    }

    private var noise = 150.0
    private var inSpeech = false
    private var run = 0
    private var quiet = 0
    private var uttFrames = 0
    private var uttRmsSum = 0.0
    private val ring = ArrayDeque<ShortArray>()

    var totalFrames = 0L
        private set
    var speechFrames = 0L
        private set

    val speaking: Boolean get() = inSpeech
    val noiseFloor: Double get() = noise

    /** share of frames that were passed on, 0..1 (what is saved is the rest) */
    fun speechShare(): Double = if (totalFrames == 0L) 0.0 else speechFrames.toDouble() / totalFrames

    fun feed(frame: ShortArray): Out {
        totalFrames++
        val rms = rms(frame)
        val loud = rms > maxOf(minRms, noise * ratio)

        if (!inSpeech) {
            ring.addLast(frame)
            while (ring.size > preRollFrames) ring.removeFirst()
            if (loud) run++ else {
                run = 0
                noise = (noise * 0.98 + rms * 0.02).coerceIn(50.0, 3000.0)
            }
            if (run < startFrames) return Out.NOTHING
            inSpeech = true
            quiet = 0
            uttFrames = ring.size
            uttRmsSum = rms * ring.size
            val out = ArrayList(ring)
            ring.clear()
            speechFrames += out.size
            return Out(out, false)
        }

        speechFrames++
        uttFrames++
        uttRmsSum += rms
        if (loud) quiet = 0 else quiet++
        val tooLong = uttFrames >= maxUtteranceFrames
        if (quiet >= hangoverFrames || tooLong) {
            if (tooLong) noise = (uttRmsSum / uttFrames * 0.8).coerceIn(50.0, 3000.0) // a constant sound becomes the new floor
            inSpeech = false
            run = 0
            quiet = 0
            return Out(listOf(frame), true)
        }
        return Out(listOf(frame), false)
    }

    fun reset() {
        inSpeech = false; run = 0; quiet = 0; uttFrames = 0; uttRmsSum = 0.0
        ring.clear()
    }

    companion object {
        fun rms(f: ShortArray): Double {
            if (f.isEmpty()) return 0.0
            var s = 0.0
            for (x in f) s += x.toDouble() * x
            return Math.sqrt(s / f.size)
        }
    }
}
