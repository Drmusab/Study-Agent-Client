package com.studyagent.client.core.anki

/**
 * GATE 01 contract — session-bound Anki ownership (§8, §41).
 *
 * Created exactly once, at study-session start, after [AnkiBackendSelector]
 * resolves the effective backend. It is the proof that *this* session belongs
 * to *this* backend and collection, and it travels with every Anki operation
 * of the session (`beginReview`, `nextCard`, `commitRating`, actions).
 *
 * INV-ANKI-01: one active review session has exactly one writable Anki backend —
 * this object is that one backend.
 * INV-ANKI-07: it never mutates mid-session; if the backend disappears the
 * session pauses/reconciles against *this* context instead of switching to
 * another backend.
 */
data class AnkiSessionContext(
    /** The resolved (effective) backend — never `AUTO`, always concrete. */
    val backendId: AnkiBackendId,

    /** Collection as identified by the backend at session start; null when unprovable (§24). */
    val collection: AnkiCollectionIdentity?,

    /** The deck being reviewed (backend-qualified identity, not a name). */
    val deckRef: AnkiDeckRef,

    /** Session-start wall clock, epoch milliseconds (device). */
    val startedAtEpochMs: Long,

    /** Capabilities as probed at session start; consulted for the whole session. */
    val capabilities: AnkiCapabilities,

    /** Study-Agent study session id this context is locked to (existing session id). */
    val studySessionId: String?
)

/**
 * Review-turn identity (§10, INV-ANKI-03).
 *
 * Distinct from card identity: the same card legitimately appears multiple
 * times in one collection's lifetime (relearning, lapses, filtered decks), and
 * every appearance is its own turn. The machine-level `CardTurn.turnId`
 * (server-provided `review_turn_id`, otherwise `epoch:cardId:generation`) is
 * the existing embodiment of this concept; [ReviewTurnId] wraps it at the Anki
 * boundary so commit correlation never degenerates to card id (§30).
 */
data class ReviewTurnId(val value: String) {
    init {
        require(value.isNotBlank()) { "ReviewTurnId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Exactly-once commit identity (§29, §30, INV-ANKI-02).
 *
 * `backend + study session + review turn`. Deliberately NOT card-scoped: one
 * card reviewed twice is two commits with two distinct ids, and one turn's
 * commit retried N times is one id seen N times (which the backend-side
 * idempotency mechanism collapses to at most one scheduler mutation).
 */
data class ReviewCommitId(
    val backendId: AnkiBackendId,
    val studySessionId: String,
    val turnId: ReviewTurnId
) {
    init {
        require(studySessionId.isNotBlank()) { "ReviewCommitId.studySessionId must not be blank" }
    }

    /** Content-free idempotency key; this is what backends deduplicate on. */
    val stableKey: String
        get() = "${backendId.stableId}|$studySessionId|${turnId.value}"
}
