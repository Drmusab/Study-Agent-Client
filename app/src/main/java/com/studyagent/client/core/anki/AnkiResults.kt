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

sealed interface CommitRatingResult {
    data class Committed(val scheduling: AnkiSchedulingInfo? = null) : CommitRatingResult
    /** Refused before mutation. Surface/reconcile, do not automatically retry. */
    data class Rejected(val error: AnkiError) : CommitRatingResult
    /** Proven not applied. Retry exactly the same identity and payload. */
    data class RetryableFailure(val error: AnkiError) : CommitRatingResult
    /** May have been applied. Never reinterpret as a safe failure based on error category. */
    data class Ambiguous(val error: AnkiError? = null) : CommitRatingResult
}
