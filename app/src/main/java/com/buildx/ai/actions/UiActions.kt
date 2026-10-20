package com.buildx.ai.actions

import com.buildx.ai.a11y.BuildXAccessibilityService
import com.buildx.ai.a11y.ElKind
import com.buildx.ai.a11y.ScreenMatch
import com.buildx.ai.a11y.ScreenOps
import com.buildx.ai.agent.AgentNotifier
import com.buildx.ai.agent.AgentRunner
import com.buildx.ai.llm.AndroidLlm
import com.buildx.ai.nlu.IntentParser

private fun ActionContext.t(bnText: String, enText: String) = if (bn) bnText else enText

private fun noService(ac: ActionContext) = ActionResult(
    false,
    ac.t("এটা করতে Accessibility চালু করতে হবে। অ্যাপ খুলে গাইড দেখুন", "This needs the Accessibility service. Open the app for the guide")
)

/** Back, Home, Recents, notifications, screenshot: one system button each. */
private fun system(ac: ActionContext, okBn: String, okEn: String, press: (BuildXAccessibilityService) -> Boolean): ActionResult {
    val svc = BuildXAccessibilityService.instance ?: return noService(ac)
    return if (press(svc)) ActionResult(true, ac.t(okBn, okEn))
    else ActionResult(false, ac.t("পারিনি। Accessibility একবার বন্ধ করে আবার চালু করুন", "Couldn't do it. Switch Accessibility off and on once"))
}

object BackAction : Action {
    override val name = "back"
    override fun execute(ac: ActionContext, params: Map<String, String>) =
        system(ac, "পেছনে গেছি", "Went back") { it.goBack() }
}

object HomeAction : Action {
    override val name = "home"
    override fun execute(ac: ActionContext, params: Map<String, String>) =
        system(ac, "হোমে এসেছি", "Home") { it.goHome() }
}

object RecentsAction : Action {
    override val name = "recents"
    override fun execute(ac: ActionContext, params: Map<String, String>) =
        system(ac, "রিসেন্ট অ্যাপ দেখাচ্ছি", "Showing recent apps") { it.openRecents() }
}

object NotificationsAction : Action {
    override val name = "notifications"
    override fun execute(ac: ActionContext, params: Map<String, String>) =
        system(ac, "নোটিফিকেশন দেখাচ্ছি", "Showing notifications") { it.openNotifications() }
}

object ScreenshotAction : Action {
    override val name = "screenshot"
    override fun execute(ac: ActionContext, params: Map<String, String>) =
        system(ac, "স্ক্রিনশট নিয়েছি", "Screenshot taken") { it.takeScreenshot() }
}

/** "ট্যাপ করো সেন্ড": taps what the person names. Their own direct command, so it is not held back by the agent's rules. */
object TapAction : Action {
    override val name = "tap"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val text = params["text"].orEmpty().trim()
        if (text.isEmpty()) {
            return ActionResult(true, ac.t("কোনটায় ট্যাপ করব?", "What should I tap?"), FollowUp { reply ->
                val said = if (reply.isNullOrBlank()) "" else IntentParser.queryOf(reply).ifBlank { reply.trim() }
                if (said.isBlank()) ActionResult(false, ac.t("উত্তর পাইনি", "No answer")) else tapNow(ac, said)
            })
        }
        return tapNow(ac, text)
    }

    private fun tapNow(ac: ActionContext, text: String): ActionResult {
        val svc = BuildXAccessibilityService.instance ?: return noService(ac)
        val built = ScreenOps.capture(svc, ac.context, allowSensitive = true)
            ?: return ActionResult(false, ac.t("স্ক্রিন পড়তে পারিনি", "I couldn't read the screen"))
        val found = ScreenMatch.find(built.snapshot.elements, text)
        return when {
            found.isEmpty() -> ActionResult(false, ac.t("‘$text’ নামে কিছু পাইনি", "I can't see “$text” on the screen"))
            found.size > 1 -> ActionResult(
                false,
                ac.t("‘$text’ নামে ${found.size}টা আছে: ${found.take(3).joinToString(", ") { it.label }}। আরও নির্দিষ্ট করে বলুন",
                    "There are ${found.size} matches for “$text”: ${found.take(3).joinToString(", ") { it.label }}. Be more specific")
            )
            else -> {
                val node = built.nodes[found[0].id] ?: return ActionResult(false, ac.t("পারিনি", "Couldn't"))
                ActionResult(true, "", deferred = Deferred { report ->
                    ScreenOps.tap(svc, node) { ok ->
                        AndroidLlm.runUi {
                            report(
                                if (ok) ActionResult(true, ac.t("‘${found[0].label}’-এ ট্যাপ করেছি", "Tapped “${found[0].label}”"))
                                else ActionResult(false, ac.t("ট্যাপ করতে পারিনি", "The tap didn't go through"))
                            )
                        }
                    }
                })
            }
        }
    }
}

/** "টাইপ করো hello": types into the field that has focus (or the only field on screen). Never into a password field. */
object TypeTextAction : Action {
    override val name = "type_text"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val text = params["text"].orEmpty().trim()
        if (text.isEmpty()) {
            return ActionResult(true, ac.t("কী টাইপ করব?", "What should I type?"), FollowUp { reply ->
                if (reply.isNullOrBlank()) ActionResult(false, ac.t("উত্তর পাইনি", "No answer")) else typeNow(ac, reply.trim())
            })
        }
        return typeNow(ac, text)
    }

    private fun typeNow(ac: ActionContext, text: String): ActionResult {
        val svc = BuildXAccessibilityService.instance ?: return noService(ac)
        var field = ScreenOps.focusedField(svc)
        if (field == null) {
            val built = ScreenOps.capture(svc, ac.context, allowSensitive = true)
            val fields = built?.snapshot?.elements.orEmpty().filter { it.kind == ElKind.FIELD }
            if (fields.size == 1) field = built?.nodes?.get(fields[0].id)
            else if (fields.size > 1) return ActionResult(false, ac.t("কোন ঘরে টাইপ করব? ঘরটায় একবার ট্যাপ করুন", "Which box? Tap it once first"))
        }
        return if (ScreenOps.typeInto(field, text)) ActionResult(true, ac.t("টাইপ করেছি", "Typed"))
        else ActionResult(false, ac.t("টাইপ করতে পারিনি: লেখার ঘর নেই, বা এটা পাসওয়ার্ডের ঘর", "I couldn't type: there is no text box, or it is a password box"))
    }
}

/** "স্ক্রিনে কী আছে": reads the visible text aloud. Nothing leaves the phone. */
object ReadScreenAction : Action {
    override val name = "read_screen"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val svc = BuildXAccessibilityService.instance ?: return noService(ac)
        val built = ScreenOps.capture(svc, ac.context, allowSensitive = true)
            ?: return ActionResult(false, ac.t("স্ক্রিন পড়তে পারিনি", "I couldn't read the screen"))
        val said = ScreenMatch.readAloud(built.snapshot.elements)
        return if (said.isEmpty()) ActionResult(true, ac.t("স্ক্রিনে পড়ার মতো কিছু পাইনি", "There is nothing to read on the screen"))
        else ActionResult(true, ac.t("স্ক্রিনে আছে: $said", "On the screen: $said"))
    }
}

/**
 * Only the model starts this (see the LLM fallback): a multi-step job done by looking at the screen, tapping and typing.
 * Off unless the person turned on the screen agent in the app, because it sends what is on the screen to their server.
 */
object UiTaskAction : Action {
    override val name = "ui_task"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val goal = params["goal"].orEmpty().trim()
        if (goal.isEmpty()) return ActionResult(false, ac.t("কী করতে হবে বুঝতে পারিনি", "I didn't get what to do"))
        val backend = AndroidLlm.agentBackend(ac.context)
            ?: return ActionResult(false, ac.t("স্ক্রিন-এজেন্ট বন্ধ আছে। অ্যাপের সেটিংসে চালু করুন", "The screen agent is off. Turn it on in the app settings"))
        val svc = BuildXAccessibilityService.instance ?: return noService(ac)
        return AgentRunner.start(ac, svc, goal, backend).start()
    }
}
