package com.studyagent.client.core.study

import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.StudyCard

/**
 * Every meaningful state transition flows through one authoritative event.
 * Async inputs (voice completion, WebSocket messages, timers) become events
 * rather than directly mutating state (§5, §65-§67).
 */
sealed interface StudyEvent {
    // -- User intents (every UI/voice/notification action converges here §61-§62)
    data class UserStartRequested(
        val deck: String?,
        val messageId: String,
        /** Study mode wire value; defaults to the v1-compatible due-review mode. */
        val mode: String = "review_due",
        /** Optional Protocol v2 session configuration from the Control Center (§75). */
        val config: com.studyagent.client.core.models.SessionStartConfig? = null
    ) : StudyEvent
    data class UserSubmitAnswer(val cardId: String, val transcript: String) : StudyEvent
    data class UserSubmitPendingTranscript(val cardId: String, val transcript: String) : StudyEvent
    data class UserDiscardPendingTranscript(val cardId: String) : StudyEvent
    data class UserRateCard(val rating: Rating, val cardId: String) : StudyEvent
    data class UserRequestHint(val cardId: String?) : StudyEvent
    data class UserRequestExplanation(val cardId: String?) : StudyEvent
    data class UserRequestAnswer(val cardId: String?) : StudyEvent
    data class UserRequestRepeat(val cardId: String?) : StudyEvent
    data class UserSkipRequested(val cardId: String?) : StudyEvent
    data class UserPauseRequested(val messageId: String) : StudyEvent
    data class UserResumeRequested(val messageId: String) : StudyEvent
    data class UserEndRequested(val messageId: String) : StudyEvent
    data object UserStopSpeaking : StudyEvent

    // -- Manual PTT ownership (§79)
    data class PttStarted(val cardId: String?, val turnId: String?) : StudyEvent
    data class PttStopped(val cardId: String?, val turnId: String?) : StudyEvent

    // -- Server messages mapped to events (§66)
    data class ServerSessionStarted(val sessionId: String, val deck: String?, val totalCards: Int?, val messageId: String?) : StudyEvent
    data class ServerQuestionReceived(
        val sessionId: String?,
        val cardId: String,
        val question: String,
        val cardNumber: Int?,
        val remaining: Int?,
        val speak: Boolean,
        val messageId: String?,
        val serverTurnId: String? = null,
        val serverRevision: Long? = null
    ) : StudyEvent
    data class ServerEvaluationReceived(
        val sessionId: String?,
        val cardId: String,
        val evaluation: Evaluation,
        val speak: Boolean,
        val messageId: String?
    ) : StudyEvent
    data class ServerHintReceived(val cardId: String?, val hintText: String, val speak: Boolean, val messageId: String?) : StudyEvent
    data class ServerExplanationReceived(val cardId: String?, val explanationText: String, val speak: Boolean, val messageId: String?) : StudyEvent
    data class ServerAnswerReceived(val cardId: String?, val answerText: String, val speak: Boolean, val messageId: String?) : StudyEvent
    data class ServerRatingSaved(
        val sessionId: String?,
        val cardId: String,
        val rating: Rating,
        val nextInterval: String?,
        val messageId: String?
    ) : StudyEvent
    data class ServerSessionPaused(val sessionId: String?, val messageId: String?) : StudyEvent
    data class ServerSessionResumed(val sessionId: String?, val messageId: String?) : StudyEvent
    data class ServerSessionFinished(val sessionId: String?, val totalReviewed: Int, val summary: String?, val messageId: String?) : StudyEvent
    data class ServerSessionStats(val sessionId: String?, val cardsStudied: Int, val recallRate: Double?, val remainingDue: Int, val messageId: String?) : StudyEvent
    data class ServerSnapshotReceived(val snapshot: StudySnapshot, val messageId: String?) : StudyEvent
    data class ServerError(val code: String?, val message: String, val sessionId: String?, val messageId: String?) : StudyEvent

    // -- Voice subsystem completions (§64 §65)
    data class QuestionSpeechCompleted(val cardId: String, val effectId: String, val success: Boolean) : StudyEvent
    data class FeedbackSpeechCompleted(val cardId: String, val effectId: String, val success: Boolean) : StudyEvent
    data class HintSpeechCompleted(val cardId: String, val effectId: String, val success: Boolean) : StudyEvent
    data class ExplanationSpeechCompleted(val cardId: String, val effectId: String, val success: Boolean) : StudyEvent
    data class SpeechCancelled(val reason: String, val effectId: String?) : StudyEvent
    data class RecognitionCompleted(val cardId: String?, val turnId: String?, val transcript: String, val isCommand: Boolean, val requestId: String? = null) : StudyEvent
    data class RecognitionFailed(val cardId: String?, val reason: String, val requestId: String? = null) : StudyEvent
    data class VoiceFailure(val reason: String) : StudyEvent

    // -- Connection
    data class ConnectionLost(val reason: String?) : StudyEvent
    data class ConnectionRestored(val reason: String?) : StudyEvent
    data class SessionStatusReceived(val snapshot: StudySnapshot) : StudyEvent

    // -- Audio route
    data class AudioRouteLost(val cardId: String?) : StudyEvent
    data class AudioRouteRestored(val cardId: String?) : StudyEvent

    /**
     * The study audio preference cannot be satisfied right now — today only
     * `HEADSET_REQUIRED` without headphones (§7/§82). Phone Mode never produces this.
     */
    data class VoiceRouteBlocked(val reason: String) : StudyEvent

    // -- Settings
    data class SettingsChanged(val handsFree: Boolean, val autoPlayQuestion: Boolean, val autoSubmit: Boolean, val listenForSpokenRating: Boolean) : StudyEvent

    // -- Timeouts (§48-§49)
    data class ActionTimedOut(val messageId: String, val type: PendingAction.ActionType) : StudyEvent

    // -- System
    data class ProtocolError(val reason: String) : StudyEvent
    data object UiRecreated : StudyEvent
    data class RecoverPersistedSession(val sessionId: String) : StudyEvent

    val debugName: String get() = this::class.simpleName ?: "StudyEvent"
}

/**
 * Factory that maps raw server messages to StudyEvents (§66). Centralizes
 * sessionId/cardId validation concerns; the reducer then enforces legality.
 */
object StudyEventMapper {
    fun fromServerMessage(msg: ServerMessage): StudyEvent? = when (msg) {
        is ServerMessage.SessionStarted -> StudyEvent.ServerSessionStarted(msg.sessionId, msg.deck, msg.totalCards, msg.messageId)
        is ServerMessage.Question -> StudyEvent.ServerQuestionReceived(
            msg.sessionId, msg.cardId, msg.question, msg.cardNumber, msg.remaining, msg.speak, msg.messageId, msg.reviewTurnId, msg.sessionRevision
        )
        is ServerMessage.EvaluationResponse -> {
            val eval = Evaluation(
                score = msg.score,
                shortFeedback = msg.shortFeedback,
                correctPoints = msg.correctPoints,
                missingPoints = msg.missingPoints,
                incorrectPoints = msg.incorrectPoints,
                suggestedRating = msg.suggestedRating,
                confidence = msg.confidence
            )
            StudyEvent.ServerEvaluationReceived(msg.sessionId, msg.cardId, eval, msg.speak, msg.messageId)
        }
        is ServerMessage.Hint -> StudyEvent.ServerHintReceived(msg.cardId, msg.hintText, msg.speak, msg.messageId)
        is ServerMessage.Explanation -> StudyEvent.ServerExplanationReceived(msg.cardId, msg.explanationText, msg.speak, msg.messageId)
        is ServerMessage.Answer -> StudyEvent.ServerAnswerReceived(msg.cardId, msg.answerText, msg.speak, msg.messageId)
        is ServerMessage.RatingSaved -> StudyEvent.ServerRatingSaved(msg.sessionId, msg.cardId, msg.rating, msg.nextInterval, msg.messageId)
        is ServerMessage.SessionPaused -> StudyEvent.ServerSessionPaused(msg.sessionId, msg.messageId)
        is ServerMessage.SessionResumed -> StudyEvent.ServerSessionResumed(msg.sessionId, msg.messageId)
        is ServerMessage.SessionFinished -> StudyEvent.ServerSessionFinished(msg.sessionId, msg.totalReviewed, msg.summary, msg.messageId)
        is ServerMessage.SessionStats -> StudyEvent.ServerSessionStats(msg.sessionId, msg.cardsStudied, msg.recallRate, msg.remainingDue, msg.messageId)
        is ServerMessage.SessionSnapshot -> {
            val snap = StudySnapshot(
                sessionId = msg.sessionId ?: "",
                phase = mapAwaiting(msg.awaiting),
                currentCard = msg.currentCardId?.let { id -> StudyCard(id, msg.currentQuestion ?: "Question", msg.cardNumber, msg.remaining) },
                evaluation = null,
                remainingCards = msg.remaining ?: msg.remainingDue,
                reviewedCards = msg.cardsStudied,
                totalCards = null,
                deckName = null,
                isPaused = msg.isPaused,
                isFinished = msg.isFinished,
                serverRevision = msg.sessionRevision,
                serverTurnId = msg.reviewTurnId
            )
            StudyEvent.ServerSnapshotReceived(snap, msg.messageId)
        }
        is ServerMessage.SessionStatus -> {
            val snap = StudySnapshot(
                sessionId = msg.sessionId ?: "",
                phase = mapAwaiting(msg.awaiting),
                currentCard = msg.currentCardId?.let { id -> StudyCard(id, msg.currentQuestion ?: "Question", msg.cardNumber, msg.remaining) },
                evaluation = null,
                remainingCards = msg.remaining,
                reviewedCards = null,
                totalCards = null,
                deckName = null,
                isPaused = msg.isPaused,
                isFinished = msg.isFinished,
                serverRevision = msg.sessionRevision,
                serverTurnId = msg.reviewTurnId
            )
            StudyEvent.ServerSnapshotReceived(snap, msg.messageId)
        }
        is ServerMessage.ErrorMessage -> StudyEvent.ServerError(msg.code, msg.message, msg.sessionId, msg.messageId)
        else -> null // Pong, capabilities etc. are not study-machine events.
    }

    private fun mapAwaiting(awaiting: String?): ServerSessionPhase = when (awaiting?.lowercase()) {
        "answer" -> ServerSessionPhase.AWAITING_ANSWER
        "rating" -> ServerSessionPhase.AWAITING_RATING
        "evaluation" -> ServerSessionPhase.AWAITING_EVALUATION
        "finished" -> ServerSessionPhase.FINISHED
        "paused" -> ServerSessionPhase.PAUSED
        else -> ServerSessionPhase.UNKNOWN
    }
}
