package com.studyagent.client.core.anki

import com.studyagent.client.core.models.Rating
import kotlinx.coroutines.flow.StateFlow

/**
 * Backend-neutral Anki boundary. Implementations translate framework/protocol failures into
 * typed outcomes, but propagate coroutine cancellation. No AI, voice or Study UI ownership.
 * Scheduling belongs to Anki. No real production implementation is registered in GATE 03.
 */
interface AnkiBackend {
    val id: AnkiBackendId
    val availability: StateFlow<AnkiAvailability>
    val capabilities: StateFlow<AnkiCapabilities>

    suspend fun refreshAvailability()

    /**
     * Read-only deck listing (GATE 05). `Success(emptyList())` means the collection really has
     * no decks; every other outcome is a typed failure — never an empty list standing in for one
     * (INV-ANKI-DECK-06). Decks carry backend-qualified identity, the original full name, and
     * nullable counts/filtered flags (`null` = the backend did not say, never a guessed zero).
     */
    suspend fun getDecks(): AnkiResult<List<AnkiDeck>>

    /**
     * The deck the *backend* currently has selected (AnkiDroid's own "current deck"), if the
     * backend exposes that notion. `Success(null)` = exposed but not determinable right now.
     * This is informational: it is not Study-Agent's session deck and is never written (§14/§43).
     */
    suspend fun getSelectedDeck(): AnkiResult<AnkiDeckRef?>

    suspend fun beginReview(request: BeginReviewRequest): AnkiResult<AnkiReviewSession>

    /**
     * A read retry returns the same uncommitted turn, not a new presentation. An ambiguous
     * or rejected commit blocks progression with a typed failure until recovery decides otherwise.
     */
    suspend fun nextCard(session: AnkiReviewSession): NextCardResult

    /**
     * Same commit ID and payload must not mutate twice; different payload is a conflict.
     * Ambiguous writes block progression and blind resubmission until reconciled.
     * This interface supplies correlation, NOT a claim of distributed exactly-once delivery.
     */
    suspend fun commitRating(request: CommitRatingRequest): CommitRatingResult
}

/** Scheduled review only. Null deck means backend-defined collection-wide review. */
data class BeginReviewRequest(val context: AnkiSessionContext, val limit: Int? = null) {
    init { require(limit == null || limit > 0) }
}

/** Opaque backend stream handle, separate from the existing user study-session ID. */
data class AnkiReviewSession(val context: AnkiSessionContext, val backendSessionRef: String) {
    init { require(backendSessionRef.isNotBlank()) }
}

/** Immutable binding of a presentation to the user session and backend; card fields stay nested. */
data class AnkiReviewTurn(
    val turnId: ReviewTurnId,
    val studySessionId: String,
    val card: AnkiRenderedCard,
    val position: Int? = null,
    val remaining: Int? = null
) {
    init {
        require(studySessionId.isNotBlank())
        require(position == null || position > 0)
        require(remaining == null || remaining >= 0)
    }
    val backendId: AnkiBackendId get() = card.ref.backendId
    val commitId: ReviewCommitId get() = ReviewCommitId(backendId, studySessionId, turnId)
}

/** Existing Rating has exactly AGAIN/HARD/GOOD/EASY semantics; no parallel AnkiRating enum. */
data class CommitRatingRequest(
    val commitId: ReviewCommitId,
    val card: AnkiCardRef,
    val rating: Rating,
    val ratedAtEpochMs: Long,
    val answerDurationMs: Long? = null
) {
    init {
        require(commitId.backendId == card.backendId)
        require(ratedAtEpochMs >= 0)
        require(answerDurationMs == null || answerDurationMs >= 0)
    }
}

/** Future typed bury/suspend/flag methods may share identity, not string commands. */
data class CardActionRequest(
    val card: AnkiCardRef,
    val session: AnkiReviewSession,
    val turnId: ReviewTurnId? = null
) {
    init {
        require(card.backendId == session.context.backendId)
        val collectionKey = session.context.collection?.collectionKey ?: session.context.deckRef?.collectionKey
        require(collectionKey == null || card.collectionKey == null || collectionKey == card.collectionKey)
    }
}
