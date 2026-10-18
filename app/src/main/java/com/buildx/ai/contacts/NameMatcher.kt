package com.buildx.ai.contacts

import com.buildx.ai.nlu.Fuzzy
import com.buildx.ai.nlu.TextNormalizer

data class PhoneEntry(val number: String, val label: String) // label: mobile / home / work / other

/** [nick] = the contact's own "Nickname" field, matched like a second name. */
data class ContactEntry(val id: String, val name: String, val numbers: List<PhoneEntry>, val nick: String = "")

data class Match(val contact: ContactEntry, val score: Double)

/**
 * Spoken name -> contacts. Handles Bengali/English spelling differences (রহিম = Rahim), several words,
 * family words (বাবা = আব্বু = Dad), the contact's nickname field and the user's own nickname list.
 */
object NameMatcher {

    private const val THRESHOLD = 0.6
    private const val TIE = 0.02
    private const val KIN_FACTOR = 0.95
    private const val NICK_BONUS = 0.3

    private fun tokens(s: String): List<String> =
        TextNormalizer.normalize(s).split(' ').filter { it.isNotEmpty() }

    private fun tokenScore(q: String, c: String): Double {
        if (q == c) return 1.0
        val qs = Phonetic.skeleton(q)
        val cs = Phonetic.skeleton(c)
        if (qs.length < 2 || cs.length < 2) return 0.0
        if (qs == cs) return 0.88 + 0.07 * Fuzzy.similarity(Phonetic.romanize(q), Phonetic.romanize(c))
        if (qs.length >= 3 && cs.startsWith(qs)) return 0.72
        if (qs.length >= 4 && cs.length >= 4 && Fuzzy.distance(qs, cs) <= 1) return 0.62
        return 0.0
    }

    /** every query word must match some word of the name; names that are fully covered score higher */
    private fun nameScore(query: List<String>, name: String): Double {
        val ct = tokens(name)
        if (query.isEmpty() || ct.isEmpty()) return 0.0
        val used = BooleanArray(ct.size)
        var min = 1.0
        for (q in query) {
            var best = 0.0
            var bestIdx = -1
            for (i in ct.indices) {
                if (used[i]) continue
                val s = tokenScore(q, ct[i])
                if (s > best) { best = s; bestIdx = i }
            }
            if (best == 0.0) return 0.0
            used[bestIdx] = true
            if (best < min) min = best
        }
        val coverage = used.count { it }.toDouble() / ct.size
        return min * 0.9 + 0.1 * coverage
    }

    private class Variant(val words: List<String>, val factor: Double, val bonus: Double)

    private fun variants(query: String, nicknames: Map<String, String>): List<Variant> {
        val q = tokens(query)
        val out = ArrayList<Variant>()
        out.add(Variant(q, 1.0, 0.0))
        if (q.size == 1) {
            for (sib in KinAliases.siblingsOf(q[0])) out.add(Variant(listOf(sib), KIN_FACTOR, 0.0))
        }
        nicknames[q.joinToString(" ")]?.let { out.add(Variant(tokens(it), 1.0, NICK_BONUS)) }
        return out
    }

    /** all contacts that fit, best first (empty if none) */
    fun find(query: String, contacts: List<ContactEntry>, nicknames: Map<String, String> = emptyMap()): List<Match> {
        if (tokens(query).isEmpty()) return emptyList()
        val vs = variants(query, nicknames)
        val out = ArrayList<Match>()
        for (c in contacts) {
            var best = 0.0
            for (v in vs) {
                for (name in listOf(c.name, c.nick)) {
                    if (name.isBlank()) continue
                    val s = nameScore(v.words, name)
                    if (s > 0.0) best = maxOf(best, s * v.factor + v.bonus)
                }
            }
            if (best >= THRESHOLD) out.add(Match(c, best))
        }
        return dedupe(out.sortedByDescending { it.score })
    }

    /** the same person saved twice (same number) counts once */
    private fun dedupe(sorted: List<Match>): List<Match> {
        val kept = ArrayList<Match>()
        for (m in sorted) {
            val dup = kept.any { k -> k.contact.numbers.any { a -> m.contact.numbers.any { b -> PhoneNumbers.same(a.number, b.number) } } }
            if (!dup) kept.add(m)
        }
        return kept
    }

    /** the leading group of equally good matches (1 = clear winner, more = ask the user), at most [max] */
    fun bestGroup(matches: List<Match>, max: Int = 5): List<Match> {
        if (matches.isEmpty()) return matches
        val top = matches[0].score
        return matches.filter { top - it.score <= TIE }.take(max)
    }
}
