package com.buildx.ai.agent

import com.buildx.ai.llm.Failure
import com.buildx.ai.llm.Kind
import com.buildx.ai.llm.LlmPlanParser
import com.buildx.ai.llm.Slot
import com.buildx.ai.llm.Tool
import com.buildx.ai.llm.LlmTools
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One thing the model asks the phone to do next. */
sealed class AgentStep {
    data class Tap(val id: Int) : AgentStep()
    data class Type(val text: String, val id: Int?) : AgentStep()
    data class Scroll(val down: Boolean) : AgentStep()
    object Back : AgentStep() { override fun toString() = "Back" }
    object Home : AgentStep() { override fun toString() = "Home" }
    object Wait : AgentStep() { override fun toString() = "Wait" }
    data class Done(val summary: String) : AgentStep()
    data class Fail(val reason: String) : AgentStep()
}

/** What is sent for each step: the goal, what has been done so far, and the current screen as text. No images. */
data class AgentRequest(
    val goal: String,
    val lang: String,
    val now: String,
    val pkg: String,
    val screen: String,
    val history: List<String>
)

sealed class AgentOutcome {
    data class Next(val step: AgentStep) : AgentOutcome()
    data class Failed(val why: Failure, val detail: String = "") : AgentOutcome()
}

/** Asks the server for the next step. Blocking, so only ever called off the main thread. */
interface AgentBackend {
    fun next(req: AgentRequest): AgentOutcome
}

/** The menu of things the agent may do, and the allow-list its answers are checked against. */
object AgentTools {
    const val MAX_ID = 200

    val all: List<Tool> = listOf(
        Tool(
            "tap", "Tap the element with this number.",
            listOf(Slot("id", "Element number from the screen list.", Kind.NUMBER, min = 1, max = MAX_ID, required = true))
        ),
        Tool(
            "type", "Type text into a field, replacing what is in it. Never for passwords, PINs, codes or card numbers.",
            listOf(
                Slot("text", "The text to type.", Kind.TEXT, max = 300, required = true),
                Slot("id", "Field number. Omit to use the field that is focused.", Kind.NUMBER, min = 1, max = MAX_ID)
            )
        ),
        Tool(
            "scroll", "Scroll the main list on the screen.",
            listOf(Slot("dir", "down shows content further down the page, up goes back.", Kind.CHOICE, listOf("down", "up"), required = true))
        ),
        Tool("back", "Press the Back button."),
        Tool("home", "Go to the home screen."),
        Tool("wait", "Wait a moment for the screen to finish loading, then look again."),
        Tool(
            "done", "The goal has been reached. Say what was done in one short sentence.",
            listOf(Slot("summary", "One short sentence for the user.", Kind.TEXT, max = 200))
        ),
        Tool(
            "fail", "Give up: the goal cannot be done, or it needs something the assistant must not do (passwords, payments).",
            listOf(Slot("reason", "One short sentence for the user.", Kind.TEXT, max = 200))
        )
    )

    private val byName = all.associateBy { it.name }

    fun get(name: String): Tool? = byName[name]
}

object AgentRequestJson {
    fun build(r: AgentRequest): String =
        JSONObject()
            .put("mode", "agent")
            .put("goal", r.goal)
            .put("lang", r.lang)
            .put("now", r.now)
            .put("package", r.pkg)
            .put("screen", r.screen)
            .put("history", JSONArray(r.history))
            .put("tools", LlmTools.toolsJson(AgentTools.all))
            .toString()
}

/** The server's answer is untrusted: exactly one step, from [AgentTools], every value checked. */
object AgentStepParser {

    fun fromHttp(code: Int, body: String): AgentOutcome = when {
        code in 200..299 ->
            parse(body)?.let { AgentOutcome.Next(it) } ?: AgentOutcome.Failed(Failure.BAD_RESPONSE, "unusable answer")
        code == 401 || code == 403 -> AgentOutcome.Failed(Failure.AUTH, "HTTP $code")
        code == 429 -> AgentOutcome.Failed(Failure.BUSY, "HTTP $code")
        code == 408 || code == 504 -> AgentOutcome.Failed(Failure.TIMEOUT, "HTTP $code")
        else -> AgentOutcome.Failed(Failure.SERVER, "HTTP $code")
    }

    fun parse(body: String): AgentStep? {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return null
        }
        val arr = root.opt("steps") as? JSONArray ?: return null
        if (arr.length() != 1) return null
        val o = arr.opt(0) as? JSONObject ?: return null
        val name = o.opt("intent") as? String ?: return null
        val tool = AgentTools.get(name) ?: return null
        val given = o.opt("slots") as? JSONObject ?: JSONObject()

        val v = HashMap<String, String>()
        for (spec in tool.slots) {
            val raw = given.opt(spec.name)
            if (raw == null || raw == JSONObject.NULL) {
                if (spec.required) return null
                continue
            }
            v[spec.name] = LlmPlanParser.value(spec, raw) ?: return null
        }
        return when (name) {
            "tap" -> AgentStep.Tap(v.getValue("id").toInt())
            "type" -> AgentStep.Type(v.getValue("text"), v["id"]?.toInt())
            "scroll" -> AgentStep.Scroll(v.getValue("dir") == "down")
            "back" -> AgentStep.Back
            "home" -> AgentStep.Home
            "wait" -> AgentStep.Wait
            "done" -> AgentStep.Done(v["summary"].orEmpty())
            "fail" -> AgentStep.Fail(v["reason"].orEmpty())
            else -> null
        }
    }
}
