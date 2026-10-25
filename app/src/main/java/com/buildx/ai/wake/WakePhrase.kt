package com.buildx.ai.wake

/**
 * Phase 5: the wake phrase is no longer fixed to "hey build". The recognizer is an English model restricted to a tiny
 * grammar, so a custom phrase must be made of English words the model knows (the person writes how it sounds,
 * e.g. "hey jarvis", "ok buddy"). Pure: no Android classes.
 */
class WakePhrase private constructor(val words: List<String>) {

    val text: String get() = words.joinToString(" ")
    val isDefault: Boolean get() = words == DEFAULT_WORDS

    /** what the person sees: "Hey Build" */
    val display: String get() = words.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** the strings the recognizer may output: the phrase (the default also with the "x" tail) and [unk] for everything else */
    fun grammarList(): List<String> {
        val v = ArrayList<String>()
        v.add(text)
        if (isDefault) {
            v.add("$text x")
            v.add("$text ex")
        }
        v.add("[unk]")
        return v
    }

    /** the JSON array Vosk wants as a grammar */
    fun grammarJson(): String = grammarList().joinToString(",", "[", "]") { "\"" + it + "\"" }

    /** whether [heard] contains the phrase as whole words, in order ("hey build x" does, "hey builder" does not) */
    fun matches(heard: String): Boolean {
        val h = heard.lowercase().split(' ').filter { it.isNotEmpty() }
        if (h.size < words.size) return false
        for (i in 0..h.size - words.size) {
            var ok = true
            for (j in words.indices) if (h[i + j] != words[j]) { ok = false; break }
            if (ok) return true
        }
        return false
    }

    override fun equals(other: Any?) = other is WakePhrase && other.words == words
    override fun hashCode() = words.hashCode()
    override fun toString() = text

    sealed class Parse {
        data class Ok(val phrase: WakePhrase) : Parse()
        /** [reason] is a short English sentence for the person */
        data class Bad(val reason: String) : Parse()
    }

    companion object {
        private val DEFAULT_WORDS = listOf("hey", "build")
        val DEFAULT = WakePhrase(DEFAULT_WORDS)

        const val MIN_WORDS = 2
        const val MAX_WORDS = 4
        const val MIN_LETTERS = 6

        /** from the saved setting; anything unusable becomes the default so listening never breaks */
        fun fromPref(saved: String?): WakePhrase = (parse(saved, null) as? Parse.Ok)?.phrase ?: DEFAULT

        /**
         * [vocabulary]: every word the model knows (graph/words.txt), or null if unknown, in which case the words are not checked.
         * A blank text means the default phrase.
         */
        fun parse(raw: String?, vocabulary: Set<String>?): Parse {
            val t = raw.orEmpty().trim().lowercase().replace(Regex("\\s+"), " ")
            if (t.isEmpty()) return Parse.Ok(DEFAULT)
            if (!t.all { it in 'a'..'z' || it == '\'' || it == ' ' }) {
                return Parse.Bad("Use English letters only, spelled the way it sounds, for example: hey jarvis")
            }
            val w = t.split(' ')
            if (w.size < MIN_WORDS) return Parse.Bad("Use at least 2 words (a single word triggers falsely too often)")
            if (w.size > MAX_WORDS) return Parse.Bad("Use at most $MAX_WORDS words")
            if (w.sumOf { it.length } < MIN_LETTERS) return Parse.Bad("Too short, it would trigger falsely. It needs at least $MIN_LETTERS letters")
            if (vocabulary != null) {
                val unknown = w.filter { it !in vocabulary }
                if (unknown.isNotEmpty()) return Parse.Bad("The model does not know these words: ${unknown.joinToString(", ")}. Use common English words")
            }
            return Parse.Ok(WakePhrase(w))
        }

        /** graph/words.txt lines look like "word 123": keep the words that are plain a-z */
        fun vocabularyFrom(lines: Sequence<String>): Set<String> {
            val s = HashSet<String>()
            for (l in lines) {
                val w = l.substringBefore(' ').trim()
                if (w.isNotEmpty() && w.all { it in 'a'..'z' || it == '\'' }) s.add(w)
            }
            return s
        }
    }
}
