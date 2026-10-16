package com.buildx.ai.nlu

import com.buildx.ai.LogStore
import com.buildx.ai.actions.ActionContext
import com.buildx.ai.actions.ActionRegistry
import com.buildx.ai.actions.ActionResult
import java.text.Normalizer

/** Placeholder router: two keyword rules. Phase 2 replaces this with the real normalizer + parser. */
object SimpleRouter {
    data class Route(val actionName: String, val params: Map<String, String>)

    /**
     * The same Bengali letter can be stored in different Unicode forms (e.g. "য়" as one character
     * or as "য" + nukta). Keyboards and the speech engine do not always agree, so both the input
     * and the keywords are normalized to NFC before comparing.
     */
    private fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase().trim()

    private val timeWords = listOf("সময়", "কয়টা", "কটা", "টাইম", "time").map { norm(it) }

    fun route(text: String): Route {
        val t = norm(text)
        return if (timeWords.any { t.contains(it) }) {
            Route("time", emptyMap())
        } else {
            Route("echo", mapOf("text" to text.trim()))
        }
    }
}

/** text -> route -> action -> result. Always returns a result; never throws. */
object CommandPipeline {
    fun run(ac: ActionContext, text: String): ActionResult {
        val route = SimpleRouter.route(text)
        val action = ActionRegistry.get(route.actionName)

        val result = if (action == null) {
            ActionResult(false, if (ac.bn) "এই কাজ এখনো নেই" else "That action isn't available yet")
        } else {
            try {
                action.execute(ac, route.params)
            } catch (e: Exception) {
                LogStore.add(ac.context, "ACTION ERROR ${route.actionName}: ${e.message}")
                ActionResult(false, if (ac.bn) "কাজটি করতে পারিনি" else "That failed")
            }
        }

        LogStore.add(ac.context, "CMD \"$text\" -> ${route.actionName} ok=${result.success}")
        return result
    }
}
