package com.buildx.ai.system

import com.buildx.ai.nlu.Fuzzy
import com.buildx.ai.nlu.TextNormalizer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** When a routine starts by itself. */
sealed class Trigger {
    data class Daily(val hour: Int, val minute: Int) : Trigger()
    /** a Bluetooth device (identified by its address) connecting or disconnecting */
    data class Bluetooth(val address: String, val name: String, val connect: Boolean) : Trigger()
    /** the charger being plugged in or taken out */
    data class Charger(val plugged: Boolean) : Trigger()
}

/** A named list of things to say to the assistant, one sentence per step ("ওয়াইফাই অন করো আর ভলিউম ৫০ করো" is one step). */
data class Routine(val name: String, val steps: List<String>, val trigger: Trigger? = null)

/** The saved routines: storage, finding one by what was said, and what may run on its own. No Android classes. */
object RoutineBook {
    const val MAX_ROUTINES = 30
    const val MAX_STEPS = 15
    const val MAX_NAME = 40

    /** the name as compared: normalized, no spaces ("গুড মর্নিং" = "গুডমর্নিং") */
    fun key(name: String): String = TextNormalizer.normalize(name).replace(" ", "")

    // ---------- storage ----------

    fun encode(list: List<Routine>): String {
        val a = JSONArray()
        for (r in list) {
            val o = JSONObject().put("name", r.name).put("steps", JSONArray(r.steps))
            when (val t = r.trigger) {
                is Trigger.Daily -> o.put("trigger", JSONObject().put("type", "daily").put("h", t.hour).put("m", t.minute))
                is Trigger.Bluetooth -> o.put("trigger", JSONObject().put("type", "bt").put("addr", t.address).put("dev", t.name).put("on", t.connect))
                is Trigger.Charger -> o.put("trigger", JSONObject().put("type", "power").put("on", t.plugged))
                null -> {}
            }
            a.put(o)
        }
        return a.toString()
    }

    fun decode(s: String?): List<Routine> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name", "").trim()
                val st = o.optJSONArray("steps") ?: return@mapNotNull null
                val steps = (0 until st.length()).map { st.optString(it, "") }.filter { it.isNotBlank() }
                if (name.isEmpty() || steps.isEmpty()) return@mapNotNull null
                val t = o.optJSONObject("trigger")?.let {
                    when (it.optString("type")) {
                        "daily" -> Trigger.Daily(it.optInt("h", 0), it.optInt("m", 0))
                        "bt" -> Trigger.Bluetooth(it.optString("addr"), it.optString("dev"), it.optBoolean("on", true))
                        "power" -> Trigger.Charger(it.optBoolean("on", true))
                        else -> null
                    }
                }
                Routine(name, steps, t)
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    // ---------- changing the list ----------

    /** adds [r], or replaces the routine with the same name (keeping its trigger unless [r] has one) */
    fun put(list: List<Routine>, r: Routine): List<Routine> {
        val k = key(r.name)
        val old = list.firstOrNull { key(it.name) == k }
        val merged = if (old != null && r.trigger == null) r.copy(trigger = old.trigger) else r
        return (list.filter { key(it.name) != k } + merged).takeLast(MAX_ROUTINES)
    }

    fun remove(list: List<Routine>, name: String): List<Routine> = list.filter { key(it.name) != key(name) }

    fun withTrigger(list: List<Routine>, name: String, t: Trigger?): List<Routine> =
        list.map { if (key(it.name) == key(name)) it.copy(trigger = t) else it }

    // ---------- finding one ----------

    /** exact (spaces and case ignored), or one letter off for names of 6+ letters (speech recognition is not exact) */
    fun find(list: List<Routine>, spoken: String): Routine? {
        val k = key(spoken)
        if (k.isEmpty()) return null
        list.firstOrNull { key(it.name) == k }?.let { return it }
        val near = list.filter { key(it.name).length >= 6 && Fuzzy.distance(k, key(it.name)) <= 1 }
        return near.singleOrNull()
    }

    /**
     * [words] is what was said around the routine word ("গুড মর্নিংটা", "good morning please"): the saved routine whose
     * name appears inside it, the longest name first; else the same as [find].
     */
    fun match(list: List<Routine>, words: String): Routine? {
        val k = key(words)
        if (k.isEmpty()) return null
        val inside = list.filter { key(it.name).isNotEmpty() && k.contains(key(it.name)) }.maxByOrNull { key(it.name).length }
        return inside ?: find(list, words)
    }

    // ---------- what may run unattended ----------

    /** these ask questions, call or message people, or drive the screen: nobody is there to answer when a trigger fires */
    private val needsPerson = setOf(
        "call", "whatsapp_call", "send_sms", "send_whatsapp", "remind", "calendar_event", "note", "ui_task", "tap", "type_text",
        "swipe", "take_photo", "routine", "unknown", "end_call", "read_screen", "screenshot", "web_search", "youtube_play", "navigate"
    )

    fun autoSafe(intent: String): Boolean = intent !in needsPerson

    // ---------- saying ----------

    private fun bnDigits(s: String) = s.map { if (it in '0'..'9') '০' + (it - '0') else it }.joinToString("")

    fun sayTrigger(t: Trigger?, bn: Boolean): String = when (t) {
        null -> if (bn) "নিজে চলে না" else "manual"
        is Trigger.Daily -> {
            val clock = AlarmWords.clock(t.hour, t.minute, bn)
            if (bn) "প্রতিদিন $clock" else "every day at $clock"
        }
        is Trigger.Bluetooth -> if (bn) "${t.name} ${if (t.connect) "কানেক্ট" else "ডিসকানেক্ট"} হলে" else "when ${t.name} ${if (t.connect) "connects" else "disconnects"}"
        is Trigger.Charger -> if (bn) (if (t.plugged) "চার্জে দিলে" else "চার্জার খুললে") else (if (t.plugged) "when plugged in" else "when unplugged")
    }

    fun sayList(list: List<Routine>, bn: Boolean): String {
        if (list.isEmpty()) return if (bn) "কোনো রুটিন নেই। \"রুটিন বানাও\" বলুন" else "You have no routines. Say \"create a routine\""
        val n = list.size.toString().let { if (bn) bnDigits(it) else it }
        val body = list.joinToString(if (bn) "। " else ". ") { r ->
            if (r.trigger == null) r.name else "${r.name} (${sayTrigger(r.trigger, bn)})"
        }
        return if (bn) "${n}টা রুটিন। $body" else "$n routine${if (list.size == 1) "" else "s"}. $body"
    }
}
