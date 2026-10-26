package com.buildx.ai.assistant

/**
 * What the popup shows, worked out from the event lines the conversation posts ("Listening...", "Heard: ...",
 * "Result: ...", "Question: ...", "Failed: ...", "Closed: ..."). Kept free of Android so it can be unit tested.
 * The popup shows the LAST exchange: what was heard and the reply, plus a status word and a status colour.
 */
data class PopupState(
    val status: Status,
    val heard: String?,
    val reply: String?,
    val replyKind: ReplyKind,
    val closedReason: String?
) {
    enum class Status { LISTENING, THINKING, REPLIED, CLOSED }
    enum class ReplyKind { NONE, OK, QUESTION, FAILED }

    companion object {
        fun from(lines: List<String>): PopupState {
            var status = Status.LISTENING
            var heard: String? = null
            var reply: String? = null
            var kind = ReplyKind.NONE
            var closed: String? = null

            for (line in lines) {
                when {
                    // "Listening..." and "Listening for your answer..."
                    line.startsWith("Listening") || line == "Heard nothing" -> status = Status.LISTENING
                    line.startsWith("Heard: ") -> {
                        val text = line.removePrefix("Heard: ").trim().removeSurrounding("\"")
                        if (text.isNotBlank()) {
                            // a new sentence starts a new exchange; an empty answer to a question leaves the question on screen
                            heard = text
                            reply = null
                            kind = ReplyKind.NONE
                        }
                        status = Status.THINKING
                    }
                    line.startsWith("Result: ") -> {
                        reply = line.removePrefix("Result: ")
                        kind = ReplyKind.OK
                        status = Status.REPLIED
                    }
                    line.startsWith("Question: ") -> {
                        reply = line.removePrefix("Question: ")
                        kind = ReplyKind.QUESTION
                        status = Status.REPLIED
                    }
                    line.startsWith("Failed: ") -> {
                        reply = line.removePrefix("Failed: ")
                        kind = ReplyKind.FAILED
                        status = Status.REPLIED
                    }
                    line.startsWith("Closed: ") -> {
                        closed = line.removePrefix("Closed: ")
                        status = Status.CLOSED
                    }
                    line == "Done" -> status = Status.CLOSED
                }
            }
            return PopupState(status, heard, reply, kind, closed)
        }
    }
}
