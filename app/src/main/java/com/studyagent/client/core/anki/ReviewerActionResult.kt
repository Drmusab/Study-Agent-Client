package com.studyagent.client.core.anki

/**
 * GATE 13 STEP 4/§31 — the typed outcome of one reviewer action.
 *
 * The result is deliberately **not** a `Boolean` and deliberately **not** [BackendCommitResult]:
 *
 * - a `Boolean` cannot distinguish "the backend refused before touching anything" from
 *   "the response was lost after a mutation may have been dispatched", and those two demand
 *   opposite recovery policies (STEP 31/§33);
 * - reusing [BackendCommitResult] would let a reviewer action be classified as a rating outcome,
 *   which is the exact collapse INV-13-01/INV-13-02 forbid.
 *
 * | Outcome | Meaning | Recovery |
 * |---|---|---|
 * | [Applied] | the backend's own state now shows the requested state | turn may continue (flag) or close (bury/suspend) |
 * | [Rejected] | **proven** not applied; the same turn continues unchanged | the user may retry the same action |
 * | [OutcomeUnknown] | may have been applied; the response was lost | nothing is retried automatically (STEP 31/§40) |
 */
sealed interface ReviewerActionResult {

    /**
     * The backend's state shows the action took effect.
     *
     * [updatedCard] is the card reference as the backend now identifies it — for the actions this
     * build models, identity does not change, so it is the same logical card; it stays nullable
     * because the contract must not force a caller to fabricate an identity a backend did not
     * report (STEP 38: only a minimal projection, never a rehydrated card).
     *
     * [cardState] carries the minimal post-action card projection (queue/availability) when the
     * backend reported one — enough for the study layer to know the card is no longer reviewable
     * without re-reading the whole card.
     *
     * [detail] is a small, stable, content-free token for diagnostics and tests
     * (for example `already_in_desired_state`) — never provider text.
     */
    data class Applied(
        val updatedCard: AnkiCardRef?,
        val cardState: ReviewerCardState? = null,
        val detail: String? = null
    ) : ReviewerActionResult

    /** Proven not applied, with a typed reason. The current turn is untouched (STEP 39). */
    data class Rejected(val reason: AnkiError) : ReviewerActionResult

    /**
     * The mutation may have been applied; the response was lost or could not be attributed.
     * Callers must not retry blindly (STEP 31/§40, INV-13-13).
     */
    data class OutcomeUnknown(
        val reason: AnkiError?,
        val detail: String? = null
    ) : ReviewerActionResult
}

/**
 * A stable, content-free token for a reviewer-action result.
 *
 * Diagnostics must never log card content, provider text or exception text; a token is enough to
 * correlate a failure report with the code path that produced it.
 */
fun ReviewerActionResult.token(): String = when (this) {
    is ReviewerActionResult.Applied -> "applied:${detail ?: "confirmed"}"
    is ReviewerActionResult.Rejected -> "rejected:${reason.commitCategory()}"
    is ReviewerActionResult.OutcomeUnknown -> "unknown:${detail ?: reason?.commitCategory() ?: "unattributed"}"
}
