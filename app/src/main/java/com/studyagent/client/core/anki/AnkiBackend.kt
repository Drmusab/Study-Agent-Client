package com.studyagent.client.core.anki

import com.studyagent.client.core.models.Rating
import kotlinx.coroutines.flow.StateFlow

/**
 * GATE 01 contract — the single Anki gateway boundary (§14-§15).
 *
 * Everything above this interface speaks Anki *domain*; everything below it
 * (AnkiDroid integration API, PC Study Agent protocol) is translated inside
 * the concrete backend. Study-Agent code never touches backend-native types
 * (INV-ANKI-06).
 *
 * Intentionally minimal: review, decks, card actions. Deliberately ABSENT and
 * owned elsewhere or delegated (§73): sync, template editing, import/export,
 * note browser editing, statistics. If this contract grows, the documented
 * decomposition is `AnkiReviewBackend` / `AnkiDeckSource` / `AnkiNoteEditor` /
 * `AnkiSearchSource` — not a god interface (§15).
 */
interface AnkiBackend {

    val id: AnkiBackendId

    /** Unified availability; emits on every probe change (§17). */
    val availability: StateFlow<AnkiAvailability>

    /** Last probed capability set; the whole session consults this snapshot (§16). */
    val capabilities: StateFlow<AnkiCapabilities>

    /** Short-lived-cache eligible (§25). Deck identity is backend-qualified. */
    suspend fun getDecks(): Result<List<AnkiDeck>>

    suspend fun getDeckSummary(deck: AnkiDeckRef): Result<AnkiDeckSummary>

    /**
     * Open a review stream for [BeginReviewRequest.context]. The context's
     * backend/collection/deck triple is the write-lock for the session
     * (INV-ANKI-01); the backend must refuse a request whose context names a
     * different backend than itself.
     */
    suspend fun beginReview(request: BeginReviewRequest): Result<AnkiReviewSession>

    /**
     * The next due turn, or `null` success when the queue is exhausted.
     * Cards come from the scheduler — never from any Study-Agent cache
     * (INV-ANKI-04, INV-ANKI-09). Not called while a commit for the previous
     * turn is undecided (§27 transaction boundary).
     */
    suspend fun nextCard(session: AnkiReviewSession): Result<AnkiReviewTurn?>

    /**
     * The only scheduling mutation Study-Agent ever requests (INV-ANKI-04).
     * [CommitRatingRequest.commitId] is the exactly-once key: re-sending the
     * same commit id must produce at most one scheduler mutation (INV-ANKI-02).
     * The result distinguishes COMMITTED / REJECTED / FAILED_SAFE_TO_RETRY /
     * AMBIGUOUS (§28); AMBIGUOUS blocks session progression until reconciled
     * (INV-ANKI-08).
     */
    suspend fun commitRating(request: CommitRatingRequest): Result<CommitRatingResult>

    suspend fun bury(request: CardActionRequest): Result<CardActionResult>

    /** Named `suspendCard` because `suspend` is a Kotlin keyword. */
    suspend fun suspendCard(request: CardActionRequest): Result<CardActionResult>
}

/** `context.deckRef` is the review target; [limit] bounds the stream when set. */
data class BeginReviewRequest(
    val context: AnkiSessionContext,
    val limit: Int? = null
)

/**
 * Handle for one open review stream. [context] is echoed verbatim so callers
 * can verify the session's ownership lock was honored; [backendSessionRef] is
 * opaque per backend (protocol session id, provider session token...).
 */
data class AnkiReviewSession(
    val context: AnkiSessionContext,
    val backendSessionRef: String? = null
)

/** One scheduler-served appearance of one card (turn ≠ card, INV-ANKI-03). */
data class AnkiReviewTurn(
    val turnId: ReviewTurnId,
    val card: AnkiRenderedCard,
    val position: Int? = null,
    val remaining: Int? = null
)

/** §28 — the four deterministic outcomes of [AnkiBackend.commitRating]. */
enum class CommitStatus {
    /** Scheduler mutation applied (and acknowledged). */
    COMMITTED,

    /** Deterministically refused before any mutation. */
    REJECTED,

    /** Mutation provably never happened; the same commit id may be retried. */
    FAILED_SAFE_TO_RETRY,

    /** Outcome unknown (e.g. no ACK after the write was issued). Stop and reconcile. */
    AMBIGUOUS
}

/**
 * The rating the *user* selected for this turn — never the AI suggestion
 * (INV-ANKI-05, INV-ANKI-13). [rating] uses the existing domain [Rating]
 * (AGAIN/HARD/GOOD/EASY), which backends map to their ease values internally.
 */
data class CommitRatingRequest(
    val commitId: ReviewCommitId,
    val card: AnkiCardRef,
    val rating: Rating,
    val ratedAtEpochMs: Long,
    /** How long the answer phase of this turn took, when measured. */
    val answerDurationMs: Long? = null
)

data class CommitRatingResult(
    val status: CommitStatus,
    val error: AnkiError? = null,
    /** e.g. the new interval label, when the backend reports one. */
    val intervalLabel: String? = null
)

/** Bury/suspend share a request shape; commit correlation is option-scoped. */
data class CardActionRequest(
    val card: AnkiCardRef,
    val session: AnkiReviewSession,
    val commitId: ReviewCommitId? = null
)

data class CardActionResult(
    val status: CommitStatus,
    val error: AnkiError? = null
)
