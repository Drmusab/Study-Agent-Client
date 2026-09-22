package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiRatingOptions
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.AnkiScheduledCard
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.SystemAppClock
import com.studyagent.client.core.common.elapsedSince
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GATE 06 — the scheduled-review gateway (INV-ANKI-REV-01/07/12).
 *
 * One job, stated so it cannot drift: ask AnkiDroid's scheduler which card this deck should
 * review next, and translate the answer into domain values **inside this boundary**. Callers
 * receive [AnkiScheduledCard] or a typed [AnkiError] — never a `Cursor`, a selection string, a
 * `JSONArray` or an interval label they might be tempted to do arithmetic on.
 *
 * What this type does **not** do, on purpose:
 * - it does not order, filter or prioritise cards (the scheduler does — INV-ANKI-REV-01/02);
 * - it does not cache a queue, a "next card", or anything else across calls (§14/§39/§79/§96);
 * - it does not own the active review turn. It *returns data*; the session that owns the turn
 *   lives in the backend above it (§60/§61);
 * - it never writes. No rating, no bury, no suspend, no selected-deck change of its own
 *   (§35/§37/INV-ANKI-REV-07/15). The provider temporarily selects the requested deck internally
 *   and restores it; that is AnkiDroid's own documented behaviour, not a Study-Agent action.
 *
 * Upper layers depend on `AnkiBackend`, not on this type.
 */
interface AnkiDroidReviewGateway {

    /**
     * The next card AnkiDroid's scheduler wants reviewed in [deckRef], or [NoCardDue].
     *
     * [limit] is the maximum number of rows to request and must be positive; this gate always asks
     * for exactly one card (§13/§40). A rejected [deckRef] (foreign backend, unmappable id) is
     * refused here, before any provider traffic (§8/§42/§43/§44).
     */
    suspend fun queryNextScheduledCard(
        authority: String,
        deckRef: AnkiDeckRef,
        limit: Int = AnkiDroidApiContract.REVIEW_DEFAULT_LIMIT
    ): AnkiResult<AnkiDroidScheduledCardQuery>

    /** Content-free facts about the last completed query, for diagnostics. Never card content. */
    fun lastQueryDiagnostics(): AnkiDroidReviewQueryDiagnostics
}

/**
 * The outcome of one scheduled-card read.
 *
 * [NoCardDue] is a *successful* answer ("the scheduler has nothing for this deck"), deliberately
 * distinct from `AnkiResult.Failure`: an exhausted deck and an unreachable provider produce very
 * different user-facing states and must never be folded together (§31/§32/INV-ANKI-REV-06/§74).
 */
sealed interface AnkiDroidScheduledCardQuery {
    data class Scheduled(
        val card: AnkiScheduledCard,
        val queryDurationMs: Long,
        val mappingDurationMs: Long
    ) : AnkiDroidScheduledCardQuery

    data class NoCardDue(val queryDurationMs: Long) : AnkiDroidScheduledCardQuery
}

/**
 * Content-free review-query facts (§81/§82/§84/§143).
 *
 * Identifiers and counts only: a note id and an ordinal are opaque backend numbers, and a deck id
 * is a backend number too. No question, no answer, no note text, no filename, no collection path.
 */
data class AnkiDroidReviewQueryDiagnostics(
    val lastStatus: String,
    val lastDeckId: String? = null,
    val lastLimit: Int? = null,
    val lastNoteId: String? = null,
    val lastCardOrd: Int? = null,
    val lastButtonCount: Int? = null,
    val lastMediaRefCount: Int? = null,
    val lastDegradations: List<String> = emptyList(),
    val lastQueryDurationMs: Long? = null,
    val lastMappingDurationMs: Long? = null,
    val lastErrorCategory: String? = null,
    val lastAtMs: Long? = null,
    /** How many provider queries this gateway has issued since construction (§143). */
    val providerQueryCount: Long = 0L
) {
    companion object {
        val NONE = AnkiDroidReviewQueryDiagnostics(lastStatus = "NONE")
    }
}

class DefaultAnkiDroidReviewGateway(
    private val providerClient: AnkiDroidProviderClient,
    private val clock: AppClock = SystemAppClock,
    private val backendId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal
) : AnkiDroidReviewGateway {

    /**
     * Serialises provider traffic (§63/§139).
     *
     * One `nextCard()` must not become two scheduler reads because a UI double-tap, a voice
     * command and a reconnect callback all fired. The turn-ownership rule lives in the backend
     * (one active uncommitted turn); this mutex is the narrower guarantee that the *provider* is
     * never entered concurrently by this gateway.
     */
    private val mutex = Mutex()
    private var diagnostics: AnkiDroidReviewQueryDiagnostics = AnkiDroidReviewQueryDiagnostics.NONE

    override fun lastQueryDiagnostics(): AnkiDroidReviewQueryDiagnostics = diagnostics

    override suspend fun queryNextScheduledCard(
        authority: String,
        deckRef: AnkiDeckRef,
        limit: Int
    ): AnkiResult<AnkiDroidScheduledCardQuery> {
        validate(deckRef, limit)?.let { return AnkiResult.Failure(it) }
        val nativeDeckId = deckRef.deckId.toLongOrNull()
            ?: return AnkiResult.Failure(
                AnkiError.InvalidRequest(detail = "deck_id_unmappable")
            )

        return try {
            mutex.withLock { performQuery(authority, deckRef, nativeDeckId, limit) }
        } catch (cancellation: CancellationException) {
            // Cancellation is never a domain failure (§140/INV-ANKI-REV-13).
            throw cancellation
        } catch (throwable: Throwable) {
            fail(
                AnkiDroidErrorMapper.mapThrowable(throwable, "schedule", AnkiDroidOperationStage.PROVIDER_QUERY),
                deckRef,
                limit
            )
        }
    }

    private fun validate(deckRef: AnkiDeckRef, limit: Int): AnkiError? = when {
        deckRef.backendId != backendId ->
            AnkiError.InvalidRequest(detail = "review_deck_foreign_backend")
        limit <= 0 -> AnkiError.InvalidRequest(detail = "review_limit_not_positive")
        limit > AnkiDroidApiContract.REVIEW_MAX_LIMIT ->
            AnkiError.InvalidRequest(detail = "review_limit_above_maximum")
        else -> null
    }

    private suspend fun performQuery(
        authority: String,
        deckRef: AnkiDeckRef,
        nativeDeckId: Long,
        limit: Int
    ): AnkiResult<AnkiDroidScheduledCardQuery> {
        val startedAt = clock.nowMillis()
        AppLogger.i(TAG, "ANKI_NEXT_CARD_REQUESTED deck=${deckRef.deckId} limit=$limit")

        val query = providerClient.safeQuery(
            authority = authority,
            path = AnkiDroidApiContract.SCHEDULE_PATH,
            projection = AnkiDroidApiContract.REVIEW_PROJECTION,
            selection = selectionFor(nativeDeckId, limit),
            selectionArgs = null,
            sortOrder = null,
            mapper = { row -> AnkiDroidReviewMapper.mapScheduledRow(row, deckRef, backendId) }
        )
        val queryMs = clock.elapsedSince(startedAt)

        return when (query) {
            is ProviderQueryResult.Failure ->
                fail(AnkiDroidErrorMapper.mapQueryResultFailure(query), deckRef, limit, queryMs)

            // The provider answers an unknown deck id with an empty cursor, which is exactly what
            // it answers for "nothing due". The backend resolves that ambiguity against the deck
            // list; mapping it to DeckNotFound here would be a guess (§31/§46/§51).
            is ProviderQueryResult.Empty -> {
                recordEmpty(deckRef, limit, queryMs)
                AnkiResult.Success(AnkiDroidScheduledCardQuery.NoCardDue(queryMs))
            }

            is ProviderQueryResult.Success -> foldRows(query.data, deckRef, limit, queryMs)
        }
    }

    private fun foldRows(
        outcomes: List<AnkiDroidReviewRowOutcome>,
        deckRef: AnkiDeckRef,
        limit: Int,
        queryMs: Long
    ): AnkiResult<AnkiDroidScheduledCardQuery> {
        val mapStart = clock.nowMillis()
        // Exactly one row is requested, so more than one is the provider ignoring `limit`: take
        // the first in the scheduler's own order and never present a second card as "the" current
        // one (§13/§65).
        for (outcome in outcomes) {
            when (outcome) {
                // Structural (a required column is missing) and identity (an unreadable note id
                // or ordinal) problems are equally fatal here: a card without a trustworthy ref
                // must never be presented, and a missing column means the pinned contract is not
                // what this build expects (§51).
                is AnkiDroidReviewRowOutcome.Malformed ->
                    return fail(
                        AnkiError.MalformedResponse(detail = outcome.problem.token),
                        deckRef, limit, queryMs
                    )
                is AnkiDroidReviewRowOutcome.Valid -> {
                    val mappingMs = clock.elapsedSince(mapStart)
                    recordCard(outcome.card, deckRef, limit, queryMs, mappingMs)
                    AppLogger.i(
                        TAG,
                        "ANKI_NEXT_CARD_AVAILABLE buttons=${buttonCountOf(outcome.card)} " +
                            "media=${outcome.card.media.size} durationMs=$queryMs"
                    )
                    return AnkiResult.Success(
                        AnkiDroidScheduledCardQuery.Scheduled(outcome.card, queryMs, mappingMs)
                    )
                }
            }
        }
        return fail(AnkiError.MalformedResponse(detail = "review_no_usable_row"), deckRef, limit, queryMs)
    }

    /**
     * The provider's argument syntax, reproduced exactly: `key=value` entries separated by
     * commas, with the value supplied literally (this gateway never builds placeholder arguments,
     * so `selectionArgs` stays null and cannot desynchronise from the selection).
     */
    private fun selectionFor(nativeDeckId: Long, limit: Int): String = listOf(
        AnkiDroidApiContract.REVIEW_LIMIT_SELECTION_KEY + AnkiDroidApiContract.REVIEW_SELECTION_KEY_VALUE_SEPARATOR + limit,
        AnkiDroidApiContract.REVIEW_DECK_ID_SELECTION_KEY + AnkiDroidApiContract.REVIEW_SELECTION_KEY_VALUE_SEPARATOR + nativeDeckId
    ).joinToString(AnkiDroidApiContract.REVIEW_SELECTION_ENTRY_SEPARATOR)

    private fun buttonCountOf(card: AnkiScheduledCard): Int = when (val options = card.ratingOptions) {
        is AnkiRatingOptions.Known -> options.buttonCount
        is AnkiRatingOptions.Unmapped -> options.buttonCount
    }

    private fun recordCard(
        card: AnkiScheduledCard,
        deckRef: AnkiDeckRef,
        limit: Int,
        queryMs: Long,
        mappingMs: Long
    ) {
        diagnostics = AnkiDroidReviewQueryDiagnostics(
            lastStatus = "SCHEDULED",
            lastDeckId = deckRef.deckId,
            lastLimit = limit,
            lastNoteId = card.ref.noteId,
            lastCardOrd = card.ref.cardOrd,
            lastButtonCount = buttonCountOf(card),
            lastMediaRefCount = card.media.size,
            lastDegradations = card.degradations,
            lastQueryDurationMs = queryMs,
            lastMappingDurationMs = mappingMs,
            lastErrorCategory = null,
            lastAtMs = clock.nowMillis(),
            providerQueryCount = diagnostics.providerQueryCount + 1
        )
    }

    private fun recordEmpty(deckRef: AnkiDeckRef, limit: Int, queryMs: Long) {
        diagnostics = AnkiDroidReviewQueryDiagnostics(
            lastStatus = "NO_CARD",
            lastDeckId = deckRef.deckId,
            lastLimit = limit,
            lastQueryDurationMs = queryMs,
            lastAtMs = clock.nowMillis(),
            providerQueryCount = diagnostics.providerQueryCount + 1
        )
        AppLogger.i(TAG, "ANKI_REVIEW_QUEUE_EMPTY deck=${deckRef.deckId} durationMs=$queryMs")
    }

    private fun <T> fail(
        error: AnkiError,
        deckRef: AnkiDeckRef?,
        limit: Int?,
        queryMs: Long? = null
    ): AnkiResult<T> {
        diagnostics = AnkiDroidReviewQueryDiagnostics(
            lastStatus = "FAILED",
            lastDeckId = deckRef?.deckId,
            lastLimit = limit,
            lastQueryDurationMs = queryMs,
            lastErrorCategory = error::class.simpleName,
            lastAtMs = clock.nowMillis(),
            providerQueryCount = diagnostics.providerQueryCount + 1
        )
        AppLogger.w(TAG, "ANKI_NEXT_CARD_FAILED error=${error::class.simpleName}")
        return AnkiResult.Failure(error)
    }

    private companion object {
        const val TAG = "AnkiDroidReviewGateway"
    }
}

/**
 * Scripted review gateway for JVM tests: no Android, no provider, no scheduling.
 *
 * [results] is consumed in order; the last entry repeats, so a test can say "one card, then
 * nothing is due" without scripting every future call. [throwable] short-circuits every call,
 * which is how cancellation propagation is exercised.
 */
class FakeAnkiDroidReviewGateway(
    var results: MutableList<AnkiResult<AnkiDroidScheduledCardQuery>> = mutableListOf(),
    var throwable: Throwable? = null,
    var diagnostics: AnkiDroidReviewQueryDiagnostics = AnkiDroidReviewQueryDiagnostics.NONE
) : AnkiDroidReviewGateway {

    var queryCalls: Int = 0
        private set
    val requestedDeckIds: MutableList<String> = mutableListOf()
    val requestedLimits: MutableList<Int> = mutableListOf()
    val queriedAuthorities: MutableList<String> = mutableListOf()

    override fun lastQueryDiagnostics(): AnkiDroidReviewQueryDiagnostics = diagnostics

    override suspend fun queryNextScheduledCard(
        authority: String,
        deckRef: AnkiDeckRef,
        limit: Int
    ): AnkiResult<AnkiDroidScheduledCardQuery> {
        queryCalls++
        queriedAuthorities.add(authority)
        requestedDeckIds.add(deckRef.deckId)
        requestedLimits.add(limit)
        throwable?.let { throw it }
        if (results.isEmpty()) {
            return AnkiResult.Success(AnkiDroidScheduledCardQuery.NoCardDue(queryDurationMs = 0L))
        }
        val result = results.removeAt(0)
        if (results.isEmpty()) results.add(result)
        return result
    }
}
