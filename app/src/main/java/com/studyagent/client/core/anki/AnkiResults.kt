package com.studyagent.client.core.anki

/** Failures cross the domain as stable categories, never Throwable or exception text. */
sealed interface AnkiResult<out T> {
    data class Success<T>(val value: T) : AnkiResult<T>
    data class Failure(val error: AnkiError) : AnkiResult<Nothing>
}

sealed interface NextCardResult {
    data class Card(val turn: AnkiReviewTurn) : NextCardResult
    data object Finished : NextCardResult
    data class BackendUnavailable(val error: AnkiError) : NextCardResult
    data class Failure(val error: AnkiError) : NextCardResult
}

/**
 * GATE 11 mutation-boundary classification of one commit attempt:
 *
 * | Result | Scheduler state | Ledger | Next card | Retry |
 * |---|---|---|---|---|
 * | [Committed] | applied (proven) | COMMITTED | exactly once | never |
 * | [RetryableFailure] | NOT applied (proven) | FAILED, safeToRetry | no | manual, same commit id + rating |
 * | [Rejected] | NOT applied (proven) | FAILED, not safe | no | no — end the session |
 * | [Ambiguous] | unknown | AMBIGUOUS | blocked | never blind; reconcile first |
 */
sealed interface CommitRatingResult {
    data class Committed(
        val scheduling: AnkiSchedulingInfo? = null,
        /** Only a backend can issue a backend receipt; this is null for AnkiDroid. */
        val receipt: CommitReceipt? = null
    ) : CommitRatingResult
    /** Refused before mutation. Surface/reconcile, do not automatically retry. */
    data class Rejected(val error: AnkiError) : CommitRatingResult
    /** Proven not applied. Retry exactly the same identity and payload. */
    data class RetryableFailure(val error: AnkiError) : CommitRatingResult
    /** May have been applied. Never reinterpret as a safe failure based on error category. */
    data class Ambiguous(val error: AnkiError? = null) : CommitRatingResult
}

/** GATE 11 — outcome of the read-only [AnkiBackend.prepareCommit] step. */
sealed interface CommitPreparation {
    /** Ready to dispatch. [evidence] is `null` when the backend has no verification baseline. */
    data class Ready(val evidence: ReviewCommitEvidence?) : CommitPreparation
    /** Refused before any mutation. [retryable] = the same commit may be attempted again later. */
    data class Refused(val error: AnkiError, val retryable: Boolean) : CommitPreparation
}

/**
 * GATE 11 — outcome of [AnkiBackend.reconcileCommit]. Only [Applied] and [NotApplied] resolve an
 * AMBIGUOUS commit; every other answer leaves it AMBIGUOUS (never fabricated certainty).
 */
sealed interface ReconcileCommitResult {
    /** Evidence shows exactly one answer attributable to this commit's mutation window. */
    data class Applied(val detail: String) : ReconcileCommitResult
    /** Evidence shows the rating was not applied. [safeToRetry] = the card is still in the state it was rated in. */
    data class NotApplied(val detail: String, val safeToRetry: Boolean) : ReconcileCommitResult
    /** Evidence exists but cannot be attributed (external activity, missing baseline, ...). */
    data class StillAmbiguous(val detail: String) : ReconcileCommitResult
    /** The backend cannot observe enough state to reconcile at all. */
    data class Unsupported(val detail: String = "reconciliation_unsupported") : ReconcileCommitResult
    /** Evidence could not be read right now (backend unavailable). Try again later. */
    data class Unavailable(val error: AnkiError) : ReconcileCommitResult
}
