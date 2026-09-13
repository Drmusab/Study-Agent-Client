package com.studyagent.client.core.study

import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.StudyCard
import com.studyagent.client.core.models.StudySession

/**
 * Authoritative server snapshot for reconciliation (§37-§41).
 *
 * v1 servers emit `session_stats` or `session_started` only; v2 servers may
 * emit a dedicated `session_snapshot` or enriched `session_status`. All fields
 * are nullable so the snapshot degrades gracefully across versions.
 */
data class StudySnapshot(
    val sessionId: String,
    val phase: ServerSessionPhase,
    val currentCard: StudyCard?,
    val evaluation: Evaluation?,
    val remainingCards: Int?,
    val reviewedCards: Int?,
    val totalCards: Int?,
    val deckName: String?,
    val isPaused: Boolean = false,
    val isFinished: Boolean = false,
    val serverRevision: Long? = null,
    val serverTurnId: String? = null,
    val suggestedRating: Rating? = null,
    val summary: String? = null
) {
    companion object {
        fun fromSession(session: StudySession, phase: ServerSessionPhase = ServerSessionPhase.AWAITING_ANSWER): StudySnapshot =
            StudySnapshot(
                sessionId = session.sessionId,
                phase = phase,
                currentCard = session.currentCard,
                evaluation = session.lastEvaluation,
                remainingCards = session.remainingCards,
                reviewedCards = session.totalReviewedInSession,
                totalCards = session.totalCardsInQueue,
                deckName = session.deckName
            )
    }
}

enum class ServerSessionPhase {
    AWAITING_FIRST_CARD,
    AWAITING_ANSWER,
    AWAITING_EVALUATION,
    AWAITING_RATING,
    PAUSED,
    FINISHED,
    UNKNOWN
}

data class StudySessionSnapshot(
    val sessionId: String,
    val deckName: String,
    val currentCard: StudyCard? = null,
    val cardNumber: Int = 0,
    val remainingCards: Int = 0,
    val totalReviewedInSession: Int = 0,
    val lastEvaluation: Evaluation? = null,
    val isPaused: Boolean = false,
    val totalCardsInQueue: Int? = null,
    val recallRate: Double? = null,
    val startedAtEpochMs: Long = 0L,
    val ratingCounts: Map<Rating, Int> = emptyMap(),
    val serverRevision: Long? = null,
    val serverTurnId: String? = null
)
