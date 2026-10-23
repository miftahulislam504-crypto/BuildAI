package com.buildx.ai.stt

import org.json.JSONObject

/** Which recognizer hears the person's command. */
enum class SttMode(val key: String, val label: String) {
    SYSTEM("system", "ফোনের নিজস্ব (ডিফল্ট)"),
    WHISPER("whisper", "Whisper (সার্ভার, বাংলায় ভালো)"),
    AUTO("auto", "অটো (বাংলায় Whisper, ইংরেজিতে ফোনের)");

    companion object {
        fun from(key: String?): SttMode = values().firstOrNull { it.key == key } ?: SYSTEM

        /** whether Whisper should hear this command first; if it then fails the phone's own recognizer takes over */
        fun useWhisper(mode: SttMode, langTag: String, configured: Boolean): Boolean = configured && when (mode) {
            SYSTEM -> false
            WHISPER -> true
            AUTO -> langTag.startsWith("bn", ignoreCase = true)
        }
    }
}

/** 16-bit mono PCM as a WAV file (what the server hands to Whisper). */
object Wav {
    fun encode(pcm: ShortArray, sampleRate: Int = 16000): ByteArray {
        val dataLen = pcm.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataLen)
        for (s in pcm) b.putShort(s)
        return b.array()
    }
}

/** The transcription address is derived from the assistant proxy's address: …/api/assist becomes …/api/transcribe. */
object SttEndpoint {
    fun fromAssistUrl(url: String?): String? {
        val u = url?.trim().orEmpty()
        if (!u.startsWith("https://")) return null
        val noQuery = u.substringBefore('?').trimEnd('/')
        return if (noQuery.endsWith("/api/assist")) noQuery.removeSuffix("/api/assist") + "/api/transcribe"
        else noQuery + "/api/transcribe"
    }

    /** the language hint Whisper wants: "bn" or "en" */
    fun languageOf(tag: String): String = tag.substringBefore('-').lowercase().ifBlank { "bn" }
}

/** What came back from the transcription server. */
sealed class Transcript {
    data class Text(val text: String) : Transcript()
    object Silence : Transcript()
    data class Failed(val why: String) : Transcript()

    companion object {
        /** the server answers {"text":"…"}; an empty text means nothing was understood */
        fun parse(code: Int, body: String): Transcript {
            if (code != 200) return Failed("HTTP $code")
            return try {
                val t = JSONObject(body).optString("text", "").trim()
                if (t.length < 2 || isFiller(t)) Silence else Text(t)
            } catch (e: Exception) {
                Failed("উত্তর পড়া যায়নি")
            }
        }

        /** what Whisper invents from silence or noise */
        private val fillers = setOf("thank you", "thanks for watching", "you", "bye", "ধন্যবাদ", "...", ".")
        fun isFiller(t: String): Boolean = t.lowercase().trim(' ', '.', '!', ',') in fillers
    }
}
