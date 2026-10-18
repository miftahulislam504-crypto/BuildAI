package com.buildx.ai.nlu

/** Small edit-distance helpers. Used for typo-tolerant keywords now and for contact names in Phase 3. */
object Fuzzy {

    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 1.0 = identical, 0.0 = nothing in common. */
    fun similarity(a: String, b: String): Double {
        val m = maxOf(a.length, b.length)
        return if (m == 0) 1.0 else 1.0 - distance(a, b).toDouble() / m
    }

    /** The candidate closest to [query], if within [maxDist] edits; ties go to the earlier candidate. */
    fun closest(query: String, candidates: Collection<String>, maxDist: Int): String? {
        var best: String? = null
        var bestD = Int.MAX_VALUE
        for (c in candidates) {
            val d = distance(query, c)
            if (d <= maxDist && d < bestD) {
                best = c
                bestD = d
            }
        }
        return best
    }
}
