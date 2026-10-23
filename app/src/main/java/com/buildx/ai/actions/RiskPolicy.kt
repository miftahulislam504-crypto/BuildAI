package com.buildx.ai.actions

/**
 * Phase 6: ONE place that says which commands may hurt, and when the phone must ask first. No Android classes.
 *
 * Three kinds of command:
 *  - [Level.SELF_CONFIRMED]: the action's own flow always asks before the real thing happens (it reads a message back,
 *    says the name and number before dialing, asks before deleting, asks before tapping Send/Pay on the screen).
 *    They must never be reachable without that question, so they are listed here and a test pins the list.
 *  - [Level.ASK_WHEN_LLM]: harmless when the person said it themselves, but not when a server-side model chose it:
 *    the pipeline asks "…করব?" first (see [mustAsk]).
 *  - [Level.NONE]: everything else (time, volume, torch, open an app ...).
 *
 * A routine that starts by itself (daily time, Bluetooth, charger) never runs a SELF_CONFIRMED step, because nobody is
 * there to answer: see RoutineBook.autoSafe and [unattendedAllowed]. (A routine's steps are the person's own words, never
 * marked as coming from the server, so ASK_WHEN_LLM does not apply to them.)
 */
object RiskPolicy {

    enum class Level { NONE, SELF_CONFIRMED, ASK_WHEN_LLM }

    /** the slot every step from the server carries (see LlmPlanParser.VIA) */
    const val VIA = "via"
    const val VIA_LLM = "llm"

    /** set on a step after the person said yes, so the question is not asked twice */
    const val CONFIRMED = "confirmed"

    private val selfConfirmed = setOf(
        "call", "whatsapp_call", // CallFlow: says who and which number, then "কল করব?"
        "send_sms", "send_whatsapp", // MessageFlow: reads the text back, sends only after a spoken yes
        "ui_task" // UiAgent: asks before every Send / Pay / Delete / Buy button
    )

    private val askWhenLlm = setOf("airplane", "hotspot", "mobile_data", "dnd")

    fun level(intent: String): Level = when (intent) {
        in selfConfirmed -> Level.SELF_CONFIRMED
        in askWhenLlm -> Level.ASK_WHEN_LLM
        else -> Level.NONE
    }

    fun selfConfirmedIntents(): Set<String> = selfConfirmed

    fun askWhenLlmIntents(): Set<String> = askWhenLlm

    /** true = the pipeline must ask the person before running this step */
    fun mustAsk(intent: String, slots: Map<String, String>): Boolean =
        level(intent) == Level.ASK_WHEN_LLM && slots[VIA] == VIA_LLM && slots[CONFIRMED] != "1"

    /** whether a call has to be confirmed: the person's own switch, or always when a model picked the call */
    fun callNeedsConfirm(userSwitchOn: Boolean, slots: Map<String, String>): Boolean =
        userSwitchOn || slots[VIA] == VIA_LLM

    /** a routine that starts by itself may not run a command whose flow has to ask something or that reaches other people */
    fun unattendedAllowed(intent: String): Boolean = level(intent) != Level.SELF_CONFIRMED
}
