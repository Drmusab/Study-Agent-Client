package com.studyagent.client.core.study

import com.studyagent.client.core.models.StudyCard

/**
 * Immutable snapshot of the safe information to restore after pause or recovery.
 *
 * §33-§34: `Paused(previousState)` is convenient but risks nesting and transient
 * restoration (`Evaluating` halfway through, `SubmittingRating`, `SpeakingQuestion`
 * mid-utterance). This holds only the data safe to restore; the reducer maps it
 * to a deterministic restart point (§34).
 */
data class ResumeContext(
    val epoch: Long,
    val phaseBeforePause: SessionPhase,
    val cardTurn: CardTurn?,
    val pendingTranscript: String? = null,
    val capturedAtMs: Long = 0L
) {
    companion object {
        fun safeRestartPhase(context: ResumeContext?, fallback: SessionPhase = SessionPhase.WaitingForAnswer): SessionPhase {
            val before = context?.phaseBeforePause ?: return fallback
            return when (before) {
                SessionPhase.SpeakingQuestion -> SessionPhase.SpeakingQuestion
                SessionPhase.WaitingForAnswer,
                SessionPhase.PendingAnswerReview,
                SessionPhase.SpeakingHint,
                SessionPhase.ShowingAnswer -> SessionPhase.WaitingForAnswer
                SessionPhase.WaitingForEvaluation,
                SessionPhase.SubmittingAnswer,
                SessionPhase.SpeakingFeedback -> SessionPhase.WaitingForEvaluation
                SessionPhase.WaitingForRating,
                SessionPhase.SpeakingExplanation -> SessionPhase.WaitingForRating
                SessionPhase.SubmittingRating -> SessionPhase.WaitingForRating
                SessionPhase.Pausing, SessionPhase.Paused, SessionPhase.Resuming -> SessionPhase.WaitingForAnswer
                SessionPhase.Recovering -> SessionPhase.Recovering
                else -> SessionPhase.WaitingForAnswer
            }
        }
    }
}
