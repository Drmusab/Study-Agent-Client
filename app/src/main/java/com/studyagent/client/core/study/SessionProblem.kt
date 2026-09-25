package com.studyagent.client.core.study

/**
 * Classified session problems (§52). A generic "Error" string is no longer
 * the authoritative machine state; typed problems drive recovery strategy
 * and degraded-mode decisions.
 */
enum class SessionProblem {
    NETWORK_LOST,
    AUTH_FAILED,
    SESSION_NOT_FOUND,
    PROTOCOL_MISMATCH,
    ANKI_UNAVAILABLE,
    ANSWER_SUBMIT_FAILED,
    RATING_SUBMIT_FAILED,
    EVALUATION_TIMEOUT,
    RATING_TIMEOUT,
    START_TIMEOUT,
    PAUSE_TIMEOUT,
    RESUME_TIMEOUT,
    END_TIMEOUT,
    STATUS_TIMEOUT,
    VOICE_ONLY_FAILURE,
    RECOGNIZER_UNAVAILABLE,
    TTS_UNAVAILABLE,
    /** GATE 11 — the Anki rating is known NOT to have been saved (ledger FAILED). */
    ANKI_RATING_NOT_SAVED,
    /** GATE 11 — whether the Anki rating was saved cannot be confirmed (ledger AMBIGUOUS). */
    ANKI_RATING_UNCONFIRMED,
    /** Backend result known in-process but durable transaction update failed. */
    ANKI_COMMIT_PERSISTENCE_FAILURE,
    /** Unreadable or contradictory ledger; mutation is refused before dispatch. */
    ANKI_COMMIT_INTEGRITY,
    UNKNOWN
}

enum class SessionProblemSeverity {
    /** Session cannot continue. */
    FATAL,
    /** Retry or reconcile; can continue. */
    RECOVERABLE,
    /** Feature degraded but session progresses. */
    DEGRADED
}

fun SessionProblem.severity(): SessionProblemSeverity = when (this) {
    SessionProblem.NETWORK_LOST -> SessionProblemSeverity.RECOVERABLE
    SessionProblem.SESSION_NOT_FOUND -> SessionProblemSeverity.FATAL
    SessionProblem.AUTH_FAILED -> SessionProblemSeverity.FATAL
    SessionProblem.PROTOCOL_MISMATCH -> SessionProblemSeverity.RECOVERABLE
    SessionProblem.ANKI_UNAVAILABLE -> SessionProblemSeverity.RECOVERABLE
    SessionProblem.ANSWER_SUBMIT_FAILED -> SessionProblemSeverity.RECOVERABLE
    SessionProblem.RATING_SUBMIT_FAILED -> SessionProblemSeverity.RECOVERABLE
    SessionProblem.EVALUATION_TIMEOUT,
    SessionProblem.RATING_TIMEOUT,
    SessionProblem.START_TIMEOUT,
    SessionProblem.PAUSE_TIMEOUT,
    SessionProblem.RESUME_TIMEOUT,
    SessionProblem.END_TIMEOUT,
    SessionProblem.STATUS_TIMEOUT -> SessionProblemSeverity.RECOVERABLE
    SessionProblem.VOICE_ONLY_FAILURE,
    SessionProblem.RECOGNIZER_UNAVAILABLE,
    SessionProblem.TTS_UNAVAILABLE -> SessionProblemSeverity.DEGRADED
    SessionProblem.ANKI_RATING_NOT_SAVED,
    SessionProblem.ANKI_RATING_UNCONFIRMED,
    SessionProblem.ANKI_COMMIT_PERSISTENCE_FAILURE,
    SessionProblem.ANKI_COMMIT_INTEGRITY -> SessionProblemSeverity.RECOVERABLE
    SessionProblem.UNKNOWN -> SessionProblemSeverity.RECOVERABLE
}
