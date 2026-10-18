package com.buildx.ai.nlu

/**
 * Cuts one sentence holding several commands into one token list per command.
 * Bengali puts the verb last, so a command ends at its verb ("খুলে", "বন্ধ করে", "কমাও", "করো"):
 * after a command has had its trigger word and then a verb, the next trigger word starts a new command.
 * Words like "আর / তারপর / and / then" cut immediately. Words that came after the last verb but before the
 * next trigger ("রহিমকে" in "ভলিউম কমাও রহিমকে ফোন করো") belong to the NEW command.
 */
object ChainSplitter {

    private fun triggerOf(canon: String): String? = when (canon) {
        "call" -> "call"
        "hangup" -> "end_call"
        "alarm" -> "alarm"
        "flashlight" -> "flashlight"
        "wifi" -> "wifi"
        "bluetooth" -> "bluetooth"
        "volume", "mute", "unmute", "louder", "quieter" -> "volume"
        "screen", "lock", "unlock", "wake" -> "screen"
        "whattime" -> "time"
        else -> if (canon in Lexicon.appKeys) "open_app" else null
    }

    private val verbish = setOf("_v", "open", "on", "off", "up", "down", "cut", "wake", "max", "min", "toggle", "show")
    private val structural = setOf("_f", "_v", "sep")

    /** a trigger that already contains its own verb ("ফোন করো", "কয়টা বাজে") ends its command by itself */
    private fun selfClosing(t: Tok) = (t.canon == "call" || t.canon == "whattime") && t.raw.contains(' ')

    fun split(toks: List<Tok>): List<List<Tok>> {
        val out = ArrayList<List<Tok>>()
        var cur = ArrayList<Tok>()
        var hasTrigger = false
        var closed = false
        var lastClose = -1 // index in [cur] of the token that ended the current command (its verb)

        fun flush() {
            if (cur.isNotEmpty()) out.add(cur)
            cur = ArrayList()
            hasTrigger = false
            closed = false
            lastClose = -1
        }

        for (t in toks) {
            if (t.canon == "sep") {
                flush()
                continue
            }
            val trig = triggerOf(t.canon)
            if (trig != null && hasTrigger && closed) {
                // cut after the last verb; what followed it belongs to the new command
                val head = ArrayList(cur.subList(0, lastClose + 1))
                val tail = ArrayList(cur.subList(lastClose + 1, cur.size))
                out.add(head)
                cur = tail
                hasTrigger = false
                closed = false
                lastClose = -1
            }
            cur.add(t)
            if (trig != null) {
                hasTrigger = true
                closed = selfClosing(t)
                if (closed) lastClose = cur.size - 1
            } else if (hasTrigger && t.canon in verbish) {
                closed = true
                lastClose = cur.size - 1
            }
        }
        flush()
        return out.filter { seg -> seg.any { it.canon !in structural } }
    }
}
