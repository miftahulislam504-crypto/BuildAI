package com.buildx.ai.llm

import java.net.URI

/** Where to ask. Only built when the feature is on and the address is a real https URL. */
data class LlmConfig(val url: String, val token: String) {
    companion object {
        /** null = feature off or address unusable. http:// is refused: the token and the user's words would travel in the clear. */
        fun of(enabled: Boolean, url: String, token: String): LlmConfig? {
            if (!enabled) return null
            val u = url.trim()
            if (u.length > 300) return null
            val ok = try {
                val uri = URI(u)
                uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
            } catch (e: Exception) {
                false
            }
            return if (ok) LlmConfig(u, token.trim()) else null
        }
    }
}
