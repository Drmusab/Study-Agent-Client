package com.studyagent.client.core.models

data class StudySession(
    val sessionId: String,
    val deckName: String,
    val currentCard: StudyCard? = null,
    val cardNumber: Int = 0,
    val remainingCards: Int = 0,
    val totalReviewedInSession: Int = 0,
    val lastEvaluation: Evaluation? = null,
    val isPaused: Boolean = false,
    /** Total cards queued for this session, when reported by the server. */
    val totalCardsInQueue: Int? = null,
    /** Live recall rate (percent) as reported by session_progress/session_stats. */
    val recallRate: Double? = null,
    /** Client clock when the session started; used for elapsed-time display. */
    val startedAtEpochMs: Long = 0L,
    /** Per-rating counts accumulated from real rating_saved/session_progress events. */
    val ratingCounts: Map<Rating, Int> = emptyMap()
) {
    fun ratingCount(rating: Rating): Int = ratingCounts[rating] ?: 0

    fun withRatingCounted(rating: Rating): StudySession =
        copy(ratingCounts = ratingCounts + (rating to (ratingCount(rating) + 1)))
}
