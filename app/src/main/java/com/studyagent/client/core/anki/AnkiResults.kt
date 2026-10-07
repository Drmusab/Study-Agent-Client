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
 * GATE 11B §11 — **backend evidence**, not ledger truth.
 *
 * A backend reports *facts about its own execution*. It never reports a transaction policy: there
 * is deliberately no `RetryAllowed` here, because "may be submitted again" is a conclusion the
 * [ReviewCommitCoordinator] draws (GATE 11B §12).
 *
 * | Backend result | Meaning | Maps to |
 * |---|---|---|
 * | [ConfirmedCommitted] | the scheduler applied it (proven) | [ReviewCommitStatus.COMMITTED] |
 * | [ConfirmedNotCommitted] | the scheduler did **not** apply it (proven) | [ReviewCommitStatus.RETRY_ALLOWED] |
 * | [OutcomeUnknown] | may or may not have been applied | [ReviewCommitStatus.AMBIGUOUS] |
 *
 * The mapping is total and lives in [BackendCommitResult.toReviewCommitStatus].
 */
sealed interface BackendCommitResult {

    /** The backend has proof the scheduler applied the rating. */
    data class ConfirmedCommitted(
        val scheduling: AnkiSchedulingInfo? = null,
        /** Only a backend can issue a backend receipt; this is null for AnkiDroid. */
        val receipt: CommitReceipt? = null
    ) : BackendCommitResult

    /**
     * The backend has proof the scheduler did **not** apply it. This covers both a transient
     * failure and an outright refusal: the canonical status set has one "proven not applied"
     * status, and re-submitting a permanently refused rating is safe (it fails the same way).
     */
    data class ConfirmedNotCommitted(val reason: AnkiError? = null) : BackendCommitResult

    /** May have been applied. Never reinterpret as a safe failure based on error category. */
    data class OutcomeUnknown(val reason: AnkiError? = null) : BackendCommitResult
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
    /**
     * Evidence shows exactly one answer attributable to this commit's mutation window.
     * [receipt] carries a backend-issued receipt when the backend provides one; it stays `null`
     * (never fabricated) for backends with no public receipt concept.
     */
    data class Applied(val detail: String, val receipt: CommitReceipt? = null) : ReconcileCommitResult
    /** Evidence shows the rating was not applied, so the same logical commit may be submitted again. */
    data class NotApplied(val detail: String) : ReconcileCommitResult
    /** Evidence exists but cannot be attributed (external activity, missing baseline, ...). */
    data class StillAmbiguous(val detail: String) : ReconcileCommitResult
    /** The backend cannot observe enough state to reconcile at all. */
    data class Unsupported(val detail: String = "reconciliation_unsupported") : ReconcileCommitResult
    /** Evidence could not be read right now (backend unavailable). Try again later. */
    data class Unavailable(val error: AnkiError) : ReconcileCommitResult
}
