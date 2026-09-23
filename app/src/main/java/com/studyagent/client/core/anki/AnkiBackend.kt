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

    /**
     * Open a scheduled-review session bound immutably to this backend, one collection and one
     * deck (§10/§130). The deck is validated against the live collection before the session
     * exists, so a stale reference fails here rather than mid-session (§45/§46).
     *
     * Idempotent per user study session (§85): repeating the identical request returns the same
     * handle; a *different* request for a session that is already open is refused instead of
     * silently replacing the review context (§128/§129).
     */
    suspend fun beginReview(request: BeginReviewRequest): AnkiResult<AnkiReviewSession>

    /**
     * The next card **the backend's scheduler** currently wants reviewed — not the first card of
     * the deck, not a locally ordered due list (INV-ANKI-REV-01/02).
     *
     * One session has at most one active uncommitted turn (INV-ANKI-REV-03): while a turn is
     * unresolved this returns *that* turn again rather than asking the scheduler for another card,
     * so a UI double tap, a voice command and a reconnect callback cannot produce two active
     * presentations (§63-§66/§92/§93). A retry after a read that changed nothing is therefore
     * safe and returns the same uncommitted turn.
     *
     * `Finished` is a valid end state, not an error: it means the scheduler has nothing more for
     * this session. It is never conflated with a failed or unavailable backend
     * (INV-ANKI-REV-06, §31/§32/§74). An ambiguous or rejected commit blocks progression with a
     * typed failure until recovery decides otherwise.
     */
    suspend fun nextCard(session: AnkiReviewSession): NextCardResult

    /**
     * GATE 07 — read-only hydration of one *known* card identity into backend-neutral normalized
     * content (STEP 09/§10/§43).
     *
     * The input is a domain reference — never a `Cursor`, `Uri`, selection string or
     * `ContentResolver` (INV-ANKI-CARD-11/§10). The output is an [AnkiRenderedCard] carrying the
     * three separated content channels (visual / speech / evaluation — INV-ANKI-CARD-05) plus
     * lenient optional metadata, or a typed failure: [AnkiError.CardNotFound] (deleted between
     * scheduling and hydration — never a blank card, §44), [AnkiError.StaleCardReference] (the
     * answer is not the scheduled card — §47/§54), [AnkiError.MalformedResponse] (identity or
     * content cannot be trusted — §74/INV-ANKI-CARD-15), and the ordinary availability family
     * ([AnkiError.PermissionRequired], [AnkiError.BackendUnavailable],
     * [AnkiError.CollectionUnavailable], … — §48/§49).
     *
     * Read-only and idempotent (INV-ANKI-CARD-10/§25): it performs no rating, no edit and no
     * scheduler mutation, and repeating it returns equivalent *current* content (§45/§50) — the
     * latest authoritative state if the card was edited in AnkiDroid in between. It never
     * attaches content to a turn; `AnkiCardHydration.attach` owns the identity-verified
     * association (STEP 52/§53).
     */
    suspend fun hydrateCardContent(card: AnkiCardRef): AnkiResult<AnkiRenderedCard>

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

/**
 * What one review turn currently knows about its card (§27/§122/§123).
 *
 * A turn is created from scheduler identity alone ([Scheduled]) and only later carries card
 * content ([Rendered]). Modelling the two phases instead of filling an [AnkiRenderedCard] with
 * placeholder question/answer strings is what keeps "this card's answer is genuinely empty"
 * distinguishable from "this card has not been loaded yet" (§28).
 *
 * GATE 07 keeps the [AnkiScheduledCard] inside **both** phases (STEP 55/§57/INV-ANKI-CARD-23):
 * hydration must never erase the scheduler's rating options, button count or next-review labels,
 * so [Rendered] carries the rendered content *and* the scheduling context it was scheduled with.
 * The two surfaces stay separate objects with disjoint authoritative fields (see
 * [AnkiSchedulingInfo]) rather than being flattened into one ambiguous blob.
 */
sealed interface AnkiReviewTurnContent {
    /** Identity + scheduler metadata, exactly as GATE 06 received them. Survives hydration. */
    val scheduledCard: AnkiScheduledCard
    val ref: AnkiCardRef get() = scheduledCard.ref

    /** Presentation media: scheduled references until hydration, merged references after. */
    val media: List<AnkiMediaRef>

    /** Scheduler identity and metadata only; GATE 07 replaces this with [Rendered] on hydration. */
    data class Scheduled(override val scheduledCard: AnkiScheduledCard) : AnkiReviewTurnContent {
        override val media: List<AnkiMediaRef> get() = scheduledCard.media
    }

    data class Rendered(
        val card: AnkiRenderedCard,
        override val scheduledCard: AnkiScheduledCard
    ) : AnkiReviewTurnContent {
        override val media: List<AnkiMediaRef> get() = card.media
    }
}

/**
 * Immutable binding of one presentation to the user session and backend.
 *
 * A turn is a *presentation*, not a card: the same card legitimately produces many turns with
 * distinct [turnId]s (INV-ANKI-REV-04/05). AnkiDroid owns card identity; Study-Agent owns turn
 * identity, and a turn id is created only once a scheduled card has actually been accepted as
 * current — a failed provider read never leaves an orphaned turn behind (§58/§59).
 *
 * Hydration never changes [turnId] (INV-ANKI-CARD-22): loading content is not a new
 * presentation. `AnkiCardHydration.attach` performs the identity-verified attach and is the
 * only supported way to move from [AnkiReviewTurnContent.Scheduled] to
 * [AnkiReviewTurnContent.Rendered] (INV-ANKI-CARD-02).
 */
data class AnkiReviewTurn(
    val turnId: ReviewTurnId,
    val studySessionId: String,
    val content: AnkiReviewTurnContent,
    val position: Int? = null,
    val remaining: Int? = null
) {
    init {
        require(studySessionId.isNotBlank())
        require(position == null || position > 0)
        require(remaining == null || remaining >= 0)
    }
    val cardRef: AnkiCardRef get() = content.ref
    /** The scheduled identity + scheduler metadata. Always present, even after hydration. */
    val scheduledCard: AnkiScheduledCard get() = content.scheduledCard
    /** Non-null only after GATE 07 hydration; never a placeholder. */
    val renderedCard: AnkiRenderedCard? get() = (content as? AnkiReviewTurnContent.Rendered)?.card
    /** Scheduler rating options survive hydration (INV-ANKI-CARD-23). */
    val ratingOptions: AnkiRatingOptions get() = content.scheduledCard.ratingOptions
    val backendId: AnkiBackendId get() = cardRef.backendId
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
