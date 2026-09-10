package com.studyagent.client.core.models

sealed interface StudyState {
    data object Idle : StudyState

    data class Loading(
        val message: String
    ) : StudyState

    data class SpeakingQuestion(
        val card: StudyCard
    ) : StudyState

    data class Listening(
        val card: StudyCard,
        val partialTranscript: String = "",
        val isHandsFree: Boolean = true
    ) : StudyState

    data class Evaluating(
        val card: StudyCard,
        val userTranscript: String
    ) : StudyState

    data class ShowingFeedback(
        val card: StudyCard,
        val evaluation: Evaluation,
        val isSpeaking: Boolean = true
    ) : StudyState

    data class WaitingForRating(
        val card: StudyCard,
        val evaluation: Evaluation,
        val suggestedRating: Rating? = null
    ) : StudyState

    data class HintShowing(
        val card: StudyCard,
        val hintText: String,
        val isSpeaking: Boolean = true
    ) : StudyState

    data class ExplanationShowing(
        val card: StudyCard,
        val explanationText: String,
        val isSpeaking: Boolean = true
    ) : StudyState

    data class Paused(
        val previousState: StudyState
    ) : StudyState

    data class SessionFinished(
        val summary: String? = null,
        val cardsReviewed: Int = 0
    ) : StudyState

    data class Error(
        val message: String,
        val recoverable: Boolean = true
    ) : StudyState

    val currentCardOrNull: StudyCard?
        get() = when (this) {
            is SpeakingQuestion -> card
            is Listening -> card
            is Evaluating -> card
            is ShowingFeedback -> card
            is WaitingForRating -> card
            is HintShowing -> card
            is ExplanationShowing -> card
            is Paused -> previousState.currentCardOrNull
            else -> null
        }
}
