package com.buildx.ai.llm

import com.buildx.ai.nlu.IntentParser
import com.buildx.ai.nlu.Parsed
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** What goes to the server: the words the user said, never contacts or anything else from the phone. */
data class LlmRequest(val text: String, val lang: String, val now: String, val agent: Boolean = false)

/** What the server decided: steps to run (in order) and/or something to say. */
data class LlmPlan(val say: String?, val steps: List<Parsed>)

enum class Failure { NETWORK, TIMEOUT, AUTH, BUSY, SERVER, BAD_RESPONSE }

sealed class LlmOutcome {
    data class Planned(val plan: LlmPlan) : LlmOutcome()
    data class Failed(val why: Failure, val detail: String = "") : LlmOutcome()
}

object LlmRequestJson {
    fun build(req: LlmRequest): String =
        JSONObject()
            .put("text", req.text)
            .put("lang", req.lang)
            .put("now", req.now)
            .put("tools", LlmTools.toJson(req.agent))
            .toString()
}

/**
 * The server's answer is untrusted input. Nothing in it is run unless it passes here:
 * the intent must be one of [LlmTools], every slot is checked against its kind, range or choices,
 * a required slot must be present, and the number of steps is capped. One bad step rejects the whole plan,
 * because running "the rest" of a chain whose middle went missing does the wrong thing.
 * No Android classes (org.json is on the JVM test classpath).
 */
object LlmPlanParser {
    const val MAX_STEPS = 5
    private const val MAX_TEXT = 120
    private const val MAX_SAY = 400

    /** marks every step that came from the server (logged; also makes calls ask for confirmation) */
    const val VIA = "via"
    const val VIA_LLM = "llm"

    private val phone = Regex("^[+0-9\u09E6-\u09EF][0-9\u09E6-\u09EF \\-]{2,19}$")

    fun fromHttp(code: Int, body: String, original: String): LlmOutcome = when {
        code in 200..299 ->
            parse(body, original)?.let { LlmOutcome.Planned(it) }
                ?: LlmOutcome.Failed(Failure.BAD_RESPONSE, "unusable answer")
        code == 401 || code == 403 -> LlmOutcome.Failed(Failure.AUTH, "HTTP $code")
        code == 429 -> LlmOutcome.Failed(Failure.BUSY, "HTTP $code")
        code == 408 || code == 504 -> LlmOutcome.Failed(Failure.TIMEOUT, "HTTP $code")
        else -> LlmOutcome.Failed(Failure.SERVER, "HTTP $code")
    }

    /** null = not usable */
    fun parse(body: String, original: String): LlmPlan? {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return null
        }
        val say = (root.opt("say") as? String)?.let { clean(it, MAX_SAY) }?.takeIf { it.isNotEmpty() }
        val raw = root.opt("steps")
        val arr = when (raw) {
            null, JSONObject.NULL -> JSONArray()
            is JSONArray -> raw
            else -> return null
        }
        if (arr.length() > MAX_STEPS) return null
        val steps = ArrayList<Parsed>()
        for (i in 0 until arr.length()) {
            val o = arr.opt(i) as? JSONObject ?: return null
            steps.add(step(o, original) ?: return null)
        }
        if (steps.isEmpty() && say == null) return null
        return LlmPlan(say, steps)
    }

    private fun step(o: JSONObject, original: String): Parsed? {
        val name = o.opt("intent") as? String ?: return null
        val tool = LlmTools.get(name) ?: return null
        val given = o.opt("slots") as? JSONObject ?: JSONObject()

        val slots = LinkedHashMap<String, String>()
        for (spec in tool.slots) {
            val raw = given.opt(spec.name)
            if (raw == null || raw == JSONObject.NULL) {
                if (spec.required) return null
                continue
            }
            slots[spec.name] = value(spec, raw) ?: return null
        }
        if (!fix(name, slots)) return null
        slots[VIA] = VIA_LLM
        return Parsed(name, slots, original)
    }

    internal fun value(spec: Slot, raw: Any): String? {
        val s = asText(raw) ?: return null
        return when (spec.kind) {
            Kind.TEXT -> clean(s, if (spec.max > 0) spec.max else MAX_TEXT).takeIf { it.isNotEmpty() }
            Kind.NUMBER -> s.trim().toIntOrNull()?.takeIf { it in spec.min..spec.max }?.toString()
            Kind.CHOICE -> s.trim().lowercase().takeIf { it in spec.choices }
        }
    }

    /**
     * Strings as they are, whole numbers as digits; anything else (booleans, objects, fractions) is not a value.
     * Any [Number] type: Android's org.json hands back Int/Long/Double, the JVM test library BigDecimal for 7.0.
     */
    internal fun asText(raw: Any): String? = when (raw) {
        is String -> raw
        is Number -> raw.toDouble().let { if (it == Math.floor(it) && Math.abs(it) < 1e9) it.toLong().toString() else null }
        else -> null
    }

    /** per-intent shaping so the action sees exactly what the offline parser would have given it; false = reject */
    private fun fix(intent: String, slots: MutableMap<String, String>): Boolean {
        when (intent) {
            "alarm" -> {
                if (slots.containsKey("in_minutes")) {
                    slots.remove("hour")
                    slots.remove("minute")
                } else if (slots.containsKey("hour")) {
                    if (!slots.containsKey("minute")) slots["minute"] = "0"
                    slots["ambiguous"] = "false" // the model gives 24-hour time, so there is nothing left to guess
                }
                slots.putIfAbsent("when", "auto")
            }
            "call", "whatsapp_call" -> {
                val n = slots["number"]
                if (n != null && !phone.matches(n)) return false
            }
            "open_app" -> {
                // same rule the app uses for a spoken name: a known app key if there is one, else search installed apps by label
                val said = slots["app"].orEmpty()
                val p = IntentParser.parse("open $said")
                slots.clear()
                if (p.intent == "open_app" && !p.slots["app"].isNullOrBlank()) {
                    slots.putAll(p.slots - "chain")
                } else {
                    slots["app"] = said
                    slots["known"] = "false"
                }
            }
            "calc" -> if (!Regex("^(\\d+|plus|minus|times|divided|point)( (\\d+|plus|minus|times|divided|point))*$").matches(slots["expr"].orEmpty())) return false
        }
        return true
    }

    /** no control characters, single spaces, at most [max] characters */
    internal fun clean(s: String, max: Int): String {
        val sb = StringBuilder()
        var gap = false
        for (ch in s) {
            if (ch.isISOControl() || ch.isWhitespace()) {
                gap = sb.isNotEmpty()
            } else {
                if (gap) sb.append(' ')
                gap = false
                sb.append(ch)
            }
        }
        return sb.toString().take(max)
    }
}
