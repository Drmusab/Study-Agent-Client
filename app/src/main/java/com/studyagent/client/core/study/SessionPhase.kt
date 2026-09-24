package com.studyagent.client.core.study

/**
 * Formal session lifecycle phases.
 *
 * This is the authoritative logical progression (§10). UI may map several
 * phases to a generic "Loading" visual, but machine logic distinguishes them
 * so illegal transitions are explicit and timeouts are purpose-specific.
 */
sealed interface SessionPhase {
    /** No active session; Idle visual. */
    data object Idle : SessionPhase

    /** StartSession sent, awaiting SessionStarted from server. */
    data object Starting : SessionPhase

    /** Session exists but server has not yet delivered the first Question. */
    data object WaitingForFirstCard : SessionPhase

    /** Card received, question TTS in flight. */
    data object SpeakingQuestion : SessionPhase

    /** Microphone may be open for the current card's answer. */
    data object WaitingForAnswer : SessionPhase

    /** Transcript captured and parked for user review (autoSubmit=false). */
    data object PendingAnswerReview : SessionPhase

    /** Answer has been ownership-claimed and is being sent; awaiting Evaluation. */
    data object SubmittingAnswer : SessionPhase

    /** Answer acknowledged by transport, awaiting server EvaluationResponse. */
    data object WaitingForEvaluation : SessionPhase

    /** Server feedback TTS in flight. */
    data object SpeakingFeedback : SessionPhase

    /** Feedback complete, awaiting user's rating. */
    data object WaitingForRating : SessionPhase

    /**
     * Rating ownership-claimed and being sent; awaiting RatingSaved (PC) or the persisted commit
     * outcome (local Anki, GATE 11). The rating itself is data on the machine state, never a phase.
     */
    data object SubmittingRating : SessionPhase

    /**
     * GATE 11 — the rating is known NOT applied (ledger FAILED). The turn stays unresolved: the
     * user may retry the *same* commit when it is safe, or end the session. Never advances.
     */
    data object RatingCommitFailed : SessionPhase

    /**
     * GATE 11 — the rating may or may not have been applied (ledger AMBIGUOUS). Progression is
     * blocked; only reconciliation evidence or ending the session leaves this phase. No re-rate.
     */
    data object ReconciliationRequired : SessionPhase

    /** A hint TTS is playing / hint overlay visible. */
    data object SpeakingHint : SessionPhase

    /** An explanation TTS is playing / explanation overlay visible. */
    data object SpeakingExplanation : SessionPhase

    /** Answer reveal overlay for the current card. */
    data object ShowingAnswer : SessionPhase

    /** Local pause intent fired, transport not yet ACKed. */
    data object Pausing : SessionPhase

    /** Server has confirmed pause; voice is frozen. */
    data object Paused : SessionPhase

    /** Resume intent sent, awaiting server resumption. */
    data object Resuming : SessionPhase

    /** Connection dropped; awaiting authoritative snapshot. */
    data object Recovering : SessionPhase

    /** EndSession sent, awaiting terminal confirmation. */
    data object Finishing : SessionPhase

    /** Terminal; no further transitions except new epoch start. */
    data object Finished : SessionPhase

    /** Non-terminal error that preserves recovery context (§51/§52). */
    data class Error(val problem: SessionProblem) : SessionPhase

    val isActive: Boolean get() = when (this) {
        Idle, Finished -> false
        is Error -> false
        else -> true
    }
    val isTerminal: Boolean get() = this is Finished
    val isPaused: Boolean get() = this is Paused || this is Pausing
    val isRecovering: Boolean get() = this is Recovering

    companion object {
        fun serverPhaseName(phase: SessionPhase): String = when (phase) {
            Idle -> "Idle"
            Starting -> "Starting"
            WaitingForFirstCard -> "WaitingForCard"
            SpeakingQuestion -> "Question"
            WaitingForAnswer -> "WaitingForAnswer"
            PendingAnswerReview -> "PendingAnswerReview"
            SubmittingAnswer -> "SubmittingAnswer"
            WaitingForEvaluation -> "WaitingForEvaluation"
            SpeakingFeedback -> "Feedback"
            WaitingForRating -> "WaitingForRating"
            SubmittingRating -> "SubmittingRating"
            RatingCommitFailed -> "RatingCommitFailed"
            ReconciliationRequired -> "ReconciliationRequired"
            SpeakingHint -> "Hint"
            SpeakingExplanation -> "Explanation"
            ShowingAnswer -> "AnswerReveal"
            Pausing -> "Pausing"
            Paused -> "Paused"
            Resuming -> "Resuming"
            Recovering -> "Recovering"
            Finishing -> "Finishing"
            Finished -> "Finished"
            is Error -> "Error(${phase.problem.name})"
        }
    }
}
