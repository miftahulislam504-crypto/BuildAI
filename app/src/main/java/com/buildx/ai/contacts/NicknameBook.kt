package com.buildx.ai.contacts

import com.buildx.ai.nlu.TextNormalizer

/**
 * The user's own nicknames, one per line: `বাবা = রহিম উদ্দিন` (alias = name of a contact).
 * Keys are normalized so spelling/case/punctuation differences do not matter.
 */
object NicknameBook {
    fun parse(text: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (line in text.lines()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val alias = TextNormalizer.normalize(line.substring(0, i))
            val target = line.substring(i + 1).trim()
            if (alias.isNotEmpty() && target.isNotEmpty()) m[alias] = target
        }
        return m
    }
}
