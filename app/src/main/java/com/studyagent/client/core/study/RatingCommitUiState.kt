package com.studyagent.client.core.study

import com.studyagent.client.core.anki.ReviewCommitId
import com.studyagent.client.core.anki.ReviewCommitStatus
import com.studyagent.client.core.models.Rating

/**
 * GATE 11B §13 — the **presentation** projection of one rating transaction.
 *
 * It is derived, never stored as truth, and it never redefines durable semantics (INV-11B-13):
 *
 * | Durable `ReviewCommitStatus` | UI projection |
 * |---|---|
 * | no record | [AwaitingRating] |
 * | `PREPARED` | [Saving] |
 * | `SUBMITTING` | [Saving] |
 * | `RETRY_ALLOWED` | [RetryAvailable] |
 * | `AMBIGUOUS` | [VerificationRequired] |
 * | `COMMITTED` | [Saved] |
 *
 * The names describe what the interface may show or permit, not transaction theory: the user cares
 * that a rating is being saved, that a retry is available, or that Anki has to be checked — never
 * that a transaction is `AMBIGUOUS`. That is why the projection has no `Committed`, `Ambiguous` or
 * `Retryable` case: those words belong to the ledger.
 */
sealed interface RatingCommitUiState {

    /** No transaction exists for this turn: the rating controls are live. */
    data object AwaitingRating : RatingCommitUiState

    /** The transaction exists and has not reached a terminal status. Controls stay disabled. */
    data class Saving(val commitId: ReviewCommitId) : RatingCommitUiState

    /** Proven not applied: the interface may offer submitting the same rating again. */
    data class RetryAvailable(val commitId: ReviewCommitId) : RatingCommitUiState

    /** The outcome is unknown: the interface must ask for verification, never offer a retry. */
    data class VerificationRequired(val commitId: ReviewCommitId) : RatingCommitUiState

    /** Durable success. The turn may advance. */
    data class Saved(val commitId: ReviewCommitId, val rating: Rating) : RatingCommitUiState

    /** The transaction id, when there is one. */
    val commitIdOrNull: ReviewCommitId?
        get() = when (this) {
            is AwaitingRating -> null
            is Saving -> commitId
            is RetryAvailable -> commitId
            is VerificationRequired -> commitId
            is Saved -> commitId
        }

    /** Whether the rating controls may accept input. Nothing about safety depends on it. */
    val ratingControlsEnabled: Boolean get() = this is AwaitingRating
}

/** GATE 11B §14 — the one mapping from durable status to projection. */
fun AnkiRatingCommit?.ratingCommitUiState(): RatingCommitUiState =
    this?.commitUiState ?: RatingCommitUiState.AwaitingRating

/** Convenience for callers that hold the durable status directly. */
fun ReviewCommitStatus?.projectForUi(commitId: ReviewCommitId, rating: Rating): RatingCommitUiState = when (this) {
    null -> RatingCommitUiState.AwaitingRating
    ReviewCommitStatus.PREPARED, ReviewCommitStatus.SUBMITTING -> RatingCommitUiState.Saving(commitId)
    ReviewCommitStatus.RETRY_ALLOWED -> RatingCommitUiState.RetryAvailable(commitId)
    ReviewCommitStatus.AMBIGUOUS -> RatingCommitUiState.VerificationRequired(commitId)
    ReviewCommitStatus.COMMITTED -> RatingCommitUiState.Saved(commitId, rating)
}

/**
 * GATE 11D §39 — the **recovery** projection, derived from the durable status of a restored or
 * otherwise recovered transaction:
 *
 * ```text
 * PREPARED      → RetryAvailable       (boundary provably un-entered; retry offered, never automatic)
 * SUBMITTING    → VerificationRequired (mutation may have occurred; verify, never blind-retry)
 * RETRY_ALLOWED → RetryAvailable       (proven not applied)
 * AMBIGUOUS     → VerificationRequired (cannot prove either direction)
 * COMMITTED     → Saved                (durable success)
 * ```
 *
 * This is the projection recovery surfaces; the live pipeline projection above ([projectForUi],
 * GATE 11B §14) keeps PREPARED/SUBMITTING as `Saving` while a submission the user started is in
 * flight. Either way the projection is a pure function of the durable status: UI actions cannot
 * cause a reverse transition, and the projection never becomes transaction truth.
 */
fun ReviewCommitStatus.recoveryUiProjection(commitId: ReviewCommitId, rating: Rating): RatingCommitUiState =
    when (this) {
        ReviewCommitStatus.PREPARED, ReviewCommitStatus.RETRY_ALLOWED ->
            RatingCommitUiState.RetryAvailable(commitId)
        ReviewCommitStatus.SUBMITTING, ReviewCommitStatus.AMBIGUOUS ->
            RatingCommitUiState.VerificationRequired(commitId)
        ReviewCommitStatus.COMMITTED -> RatingCommitUiState.Saved(commitId, rating)
    }
