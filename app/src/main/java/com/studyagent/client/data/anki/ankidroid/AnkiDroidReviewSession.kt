package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiReviewSession
import com.studyagent.client.core.anki.AnkiReviewTurn
import com.studyagent.client.core.anki.BeginReviewRequest
import com.studyagent.client.core.anki.ReviewTurnId
import java.util.concurrent.atomic.AtomicLong

/**
 * GATE 06 — the runtime state of one open review session, held by the backend and nowhere else
 * (§61/§62/§94).
 *
 * What it is: a **turn-scoped transient record** — which deck this session reviews, its own card
 * limit, the one unresolved turn, how many cards this session has presented, and whether the
 * scheduler said it was done. It is not a queue, not a cache of future cards and not a shadow
 * scheduler (§14/§96): it holds at most one card, and only the one the scheduler already
 * selected.
 *
 * What it is not: persistence. Nothing here survives process death (§97/§98) and nothing here is
 * serialisable — a `MutableMap` of records is a runtime handle, not logical state. When recovery
 * is designed (a later gate) it will persist identity only: session ref, deck ref, turn ref, card
 * ref, commit state (§134).
 */
internal class ReviewSessionRecord(
    val session: AnkiReviewSession,
    val request: BeginReviewRequest,
    val deckRef: AnkiDeckRef,
    val limit: Int?
) {
    /** The single unresolved turn. Never a list: one session has at most one (§65). */
    var activeTurn: AnkiReviewTurn? = null

    /** Study-Agent's own count of presentations. Separate from AnkiDroid's daily limits (§34). */
    var presentedCount: Int = 0

    /** The scheduler answered "nothing more for this deck" (§31/§32). */
    var schedulerExhausted: Boolean = false
}

/**
 * Session-scoped progress the future coordinator reads (§33/§34).
 *
 * The *count* is Study-Agent's; the *cards* remain AnkiDroid's. Nothing here may be used to
 * decide which card comes next.
 */
data class AnkiReviewSessionProgress(
    val backendSessionRef: String,
    val deckRef: AnkiDeckRef,
    val limit: Int?,
    val presentedTurnCount: Int,
    val hasActiveTurn: Boolean,
    val schedulerExhausted: Boolean
)

/**
 * Content-free review diagnostics (§82/§84).
 *
 * Identifiers, counts and a status token — never a question, an answer, a note field, a media
 * filename or a collection path (§83/§136). A note id and an ordinal are the backend's own opaque
 * numbers, which is exactly what makes them safe and useful for support: they let a session be
 * followed end-to-end without ever reading the user's cards.
 */
data class AnkiReviewDiagnostics(
    val lastStatus: String,
    val backendId: String? = null,
    val sessionRef: String? = null,
    val deckRef: AnkiDeckRef? = null,
    val turnId: String? = null,
    val cardRef: AnkiCardRef? = null,
    val buttonCount: Int? = null,
    val lastAtMs: Long? = null
) {
    companion object {
        val NONE = AnkiReviewDiagnostics(lastStatus = "NONE")
    }
}

/**
 * Where Study-Agent's review-turn identities come from (§56/§57).
 *
 * AnkiDroid identifies the *card*; Study-Agent identifies the *presentation*, so turn ids are
 * minted here and must be injectable — a test that cannot pin a turn id cannot assert that the
 * same card appearing twice produced two turns (INV-ANKI-REV-04/05).
 */
fun interface ReviewTurnIdSource {
    fun next(): ReviewTurnId
}

/**
 * Default source: a per-instance prefix plus a monotonic counter.
 *
 * The prefix is what makes ids unique across process restarts, backends and sessions — a counter
 * alone would re-issue `turn:1` after every restart, and a persisted ledger (GATE 11) could not
 * tell yesterday's first turn from today's. The counter is what makes them deterministic and
 * cheap; it is deliberately *not* random, so a session's turn order stays readable in diagnostics
 * and reproducible in tests.
 */
class SequentialReviewTurnIdSource(
    private val instancePrefix: String = "anki",
    private val counter: AtomicLong = AtomicLong(0L)
) : ReviewTurnIdSource {

    override fun next(): ReviewTurnId = ReviewTurnId("$instancePrefix:turn:${counter.incrementAndGet()}")
}
