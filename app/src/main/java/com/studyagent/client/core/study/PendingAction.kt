package com.studyagent.client.core.study

import com.studyagent.client.core.models.Rating

/**
 * Tracks a client action that has been sent but not yet acknowledged.
 *
 * §23, §48-§49: Every important async action gets an ID that survives timeouts.
 * Late timeout events validate this ID before mutating state.
 */
data class PendingAction(
    val messageId: String,
    val type: ActionType,
    val sessionEpoch: Long,
    val cardTurnId: String?,
    val cardId: String?,
    val createdAtMs: Long,
    val timeoutMs: Long,
    val attempt: Int = 1,
    /** Expected value for acknowledgements when the protocol provides one. */
    val expectedRating: Rating? = null
) {
    fun isExpired(nowMs: Long): Boolean = nowMs - createdAtMs >= timeoutMs

    enum class ActionType {
        START_SESSION,
        SUBMIT_ANSWER,
        RATE_CARD,
        PAUSE_SESSION,
        RESUME_SESSION,
        END_SESSION,
        SKIP_CARD,
        REQUEST_HINT,
        REQUEST_EXPLANATION,
        REQUEST_ANSWER,
        REQUEST_SESSION_STATUS,
        REPEAT_QUESTION
    }

    companion object {
        fun timeoutFor(type: ActionType): Long = when (type) {
            ActionType.START_SESSION -> 15_000L
            ActionType.SUBMIT_ANSWER -> 30_000L
            ActionType.RATE_CARD -> 15_000L
            ActionType.PAUSE_SESSION -> 8_000L
            ActionType.RESUME_SESSION -> 8_000L
            ActionType.END_SESSION -> 8_000L
            ActionType.SKIP_CARD -> 10_000L
            ActionType.REQUEST_HINT -> 10_000L
            ActionType.REQUEST_EXPLANATION -> 12_000L
            ActionType.REQUEST_ANSWER -> 10_000L
            ActionType.REQUEST_SESSION_STATUS -> 10_000L
            ActionType.REPEAT_QUESTION -> 10_000L
        }
    }
}
