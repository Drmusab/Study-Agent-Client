package com.studyagent.client.core.models

data class StudySession(
    val sessionId: String,
    val deckName: String,
    val currentCard: StudyCard? = null,
    val cardNumber: Int = 0,
    val remainingCards: Int = 0,
    val totalReviewedInSession: Int = 0,
    val lastEvaluation: Evaluation? = null,
    val isPaused: Boolean = false
)
