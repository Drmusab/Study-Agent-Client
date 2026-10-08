package com.studyagent.client.core.study

import com.studyagent.client.core.anki.ReviewerAction
import com.studyagent.client.core.anki.ReviewerActionBlockReason
import com.studyagent.client.core.anki.ReviewerActionId
import com.studyagent.client.core.anki.ReviewerActionStatus

/**
 * GATE 13 §7 — the **presentation projection** of one reviewer action.
 *
 * This is not transaction truth (§6): every variant is derived from a durable
 * [ReviewerActionStatus], and the mapping lives in exactly one place ([from]). There is deliberately
 * no `Applying` / `Failed` / `Blocked` *business* state here — an application is either `Saving`
 * (a durable record exists and the mutation is not resolved) or one of the two honest "something
 * needs a decision" states:
 *
 * | Durable status | Projection |
 * |---|---|
 * | no action record | [Idle] |
 * | `PREPARED` (live attempt) | [Saving] |
 * | `PREPARED` (found at restore) | [RetryAvailable] |
 * | `SUBMITTING` (live attempt) | [Saving] |
 * | `SUBMITTING` (found at restore) | [VerificationRequired] |
 * | `RETRY_ALLOWED` | [RetryAvailable] |
 * | `AMBIGUOUS` | [VerificationRequired] |
 * | `APPLIED` | [Idle] after the resulting card projection / session transition |
 *
 * The UI is never transaction authority (§7): it renders this and dispatches intents. A request the
 * policy refused before any durable record existed is *not* a state of this type — it is a
 * [ReviewerActionRefusal], because nothing happened at all.
 */
sealed interface ReviewerActionUiState {

    /** Nothing in flight; no failed or unresolved action. The normal state. */
    data object Idle : ReviewerActionUiState

    /** A durable action exists and is being applied; a second action must not be dispatched. */
    data class Saving(
        val actionId: ReviewerActionId,
        val action: ReviewerAction
    ) : ReviewerActionUiState

    /**
     * The action is proven not applied (`RETRY_ALLOWED`), or provably un-entered after a restore
     * (`PREPARED`): the same action identity may be submitted again (§13, INV-13-11).
     */
    data class RetryAvailable(
        val actionId: ReviewerActionId,
        val action: ReviewerAction
    ) : ReviewerActionUiState

    /**
     * The action may have been applied and cannot currently be proven either way (`AMBIGUOUS`), or a
     * `SUBMITTING` record restored after process death still needs its read-only reconciliation
     * (§26/§31). No automatic progression, no replay (INV-13-12).
     */
    data class VerificationRequired(
        val actionId: ReviewerActionId,
        val action: ReviewerAction
    ) : ReviewerActionUiState

    /** The action being applied right now, if any (duplicate suppression, §15). */
    val inFlightActionId: ReviewerActionId?
        get() = (this as? Saving)?.actionId

    /** The action awaiting an explicit retry, if any. */
    val retryActionId: ReviewerActionId?
        get() = (this as? RetryAvailable)?.actionId

    /** The action awaiting verification/reconciliation, if any. */
    val verificationActionId: ReviewerActionId?
        get() = (this as? VerificationRequired)?.actionId

    companion object {
        /** The one durable status → presentation mapping (§7/§31). */
        fun from(
            status: ReviewerActionStatus?,
            actionId: ReviewerActionId,
            action: ReviewerAction,
            /** True when the record was found by the startup recovery scan, not created live. */
            recovered: Boolean = false
        ): ReviewerActionUiState = when (status) {
            null, ReviewerActionStatus.APPLIED -> Idle
            ReviewerActionStatus.PREPARED ->
                if (recovered) RetryAvailable(actionId, action) else Saving(actionId, action)
            ReviewerActionStatus.SUBMITTING ->
                if (recovered) VerificationRequired(actionId, action) else Saving(actionId, action)
            ReviewerActionStatus.RETRY_ALLOWED -> RetryAvailable(actionId, action)
            ReviewerActionStatus.AMBIGUOUS -> VerificationRequired(actionId, action)
        }
    }
}

/**
 * The presentation of a request the policy refused before anything durable existed (§15/§23).
 *
 * Deliberately **not** a [ReviewerActionUiState]: nothing was sent, nothing changed, and the next
 * request is re-evaluated from scratch. The UI shows it as a message, never as a saved state.
 */
data class ReviewerActionRefusal(
    val action: ReviewerAction,
    val reason: ReviewerActionBlockReason,
    val detail: String
)
