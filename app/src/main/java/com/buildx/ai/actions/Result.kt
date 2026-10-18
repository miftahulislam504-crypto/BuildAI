package com.buildx.ai.actions

/**
 * What an action says back. If [followUp] is set the assistant asks something (the message is the question),
 * listens once more, and hands the reply (null = silence) to [FollowUp.onReply], which returns the next result.
 * If [deferred] is set the real answer is not known yet (waiting for the phone to react): [message] is spoken
 * first if it is not blank, then [Deferred.run] is called and must report the final result exactly once.
 */
data class ActionResult(
    val success: Boolean,
    val message: String,
    val followUp: FollowUp? = null,
    val deferred: Deferred? = null
)

class FollowUp(val onReply: (String?) -> ActionResult)

class Deferred(val run: ((ActionResult) -> Unit) -> Unit)
