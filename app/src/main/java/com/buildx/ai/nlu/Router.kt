package com.buildx.ai.nlu

import com.buildx.ai.LogStore
import com.buildx.ai.actions.ActionContext
import com.buildx.ai.actions.ActionRegistry
import com.buildx.ai.actions.ActionResult

/** Placeholder router: two keyword rules. Phase 2 replaces this with the real normalizer + parser. */
object SimpleRouter {
    data class Route(val actionName: String, val params: Map<String, String>)

    private val timeWords = listOf("সময়", "কয়টা", "কটা", "টাইম", "time")

    fun route(text: String): Route {
        val t = text.trim().lowercase()
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
