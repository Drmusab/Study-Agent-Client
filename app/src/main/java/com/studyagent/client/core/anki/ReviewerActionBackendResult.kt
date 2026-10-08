package com.studyagent.client.core.anki

/**
 * GATE 13 §8 — the **backend evidence** layer for one reviewer action.
 *
 * Never a `Boolean` (§8) and never [BackendCommitResult]:
 *
 * - a `Boolean` cannot distinguish "the backend refused before touching anything" from "the
 *   response was lost after a mutation may have been dispatched", and those two demand opposite
 *   recovery policies (§5/§26);
 * - reusing [BackendCommitResult] would let a reviewer action be classified as a *rating* outcome,
 *   which is exactly the collapse INV-13-04/INV-13-05 forbid.
 *
 * The three variants are the whole vocabulary, and §9 fixes their mapping to durable truth with no
 * alternative permitted ([toStatus]):
 *
 * | Backend evidence | Durable status | Meaning |
 * |---|---|---|
 * | [ConfirmedApplied] | [ReviewerActionStatus.APPLIED] | the backend's own state shows the action took effect |
 * | [ConfirmedNotApplied] | [ReviewerActionStatus.RETRY_ALLOWED] | **proven** not applied; the same action may be retried |
 * | [OutcomeUnknown] | [ReviewerActionStatus.AMBIGUOUS] | may have been applied; nothing is replayed (INV-13-12) |
 */
sealed interface ReviewerActionBackendResult {

    /**
     * The backend's own observable state shows the action took effect.
     *
     * [receipt] is the content-free evidence ([ReviewerActionReceipt]); it is nullable because a
     * contract without receipts must not be forced to fabricate one — but a receipt that *is*
     * supplied must name this backend and this action, or the transition engine rejects it.
     */
    data class ConfirmedApplied(
        val receipt: ReviewerActionReceipt? = null
    ) : ReviewerActionBackendResult

    /**
     * Proven **not** applied: the backend refused before any mutation, or its own state proves the
     * mutation did not take effect. The current turn is untouched, and §9 makes this the only
     * evidence that may grant a retry.
     */
    data class ConfirmedNotApplied(
        val reason: AnkiError? = null
    ) : ReviewerActionBackendResult

    /**
     * The mutation may have been applied; the response was lost, timed out or cannot be attributed.
     * Callers must not retry blindly (§13/§29, INV-13-10/INV-13-12). [detail] is a small stable
     * token for diagnostics — never provider text, never an exception message.
     */
    data class OutcomeUnknown(
        val reason: AnkiError? = null,
        val detail: String? = null
    ) : ReviewerActionBackendResult
}

/**
 * GATE 13 §9 — the exact backend → status mapping, in one place. No alternate mapping is
 * permitted, and no call site may re-derive it from a `when` of its own.
 */
fun ReviewerActionBackendResult.toStatus(): ReviewerActionStatus = when (this) {
    is ReviewerActionBackendResult.ConfirmedApplied -> ReviewerActionStatus.APPLIED
    is ReviewerActionBackendResult.ConfirmedNotApplied -> ReviewerActionStatus.RETRY_ALLOWED
    is ReviewerActionBackendResult.OutcomeUnknown -> ReviewerActionStatus.AMBIGUOUS
}

/** The one durable command that carries this backend evidence into the ledger (§10/§11). */
fun ReviewerActionBackendResult.toTransition(): ReviewerActionTransition = when (this) {
    is ReviewerActionBackendResult.ConfirmedApplied ->
        ReviewerActionTransition.BackendConfirmedApplied(receipt)
    is ReviewerActionBackendResult.ConfirmedNotApplied ->
        ReviewerActionTransition.BackendConfirmedNotApplied(reason)
    is ReviewerActionBackendResult.OutcomeUnknown ->
        ReviewerActionTransition.BackendOutcomeUnknown(reason, detail)
}

/**
 * A stable, content-free token for diagnostics and tests.
 *
 * Diagnostics must never log card content, provider text or exception text; a token is enough to
 * correlate a failure report with the code path that produced it.
 */
fun ReviewerActionBackendResult.token(): String = when (this) {
    is ReviewerActionBackendResult.ConfirmedApplied -> "applied:${receipt?.detail ?: "confirmed"}"
    is ReviewerActionBackendResult.ConfirmedNotApplied -> "not_applied:${reason?.commitCategory() ?: "unspecified"}"
    is ReviewerActionBackendResult.OutcomeUnknown ->
        "unknown:${detail ?: reason?.commitCategory() ?: "unattributed"}"
}
