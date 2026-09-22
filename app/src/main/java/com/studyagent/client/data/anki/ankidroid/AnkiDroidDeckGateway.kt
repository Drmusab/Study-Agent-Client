package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckOrder
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.SystemAppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GATE 05 — dedicated deck gateway (INV-ANKI-DECK-02/06/12).
 *
 * Owns the `decks` and `selected_deck` public-provider reads and maps them to domain types
 * *inside* this boundary. Callers receive [AnkiDeck] / [AnkiDeckRef], never a Cursor, Uri or
 * JSON blob. Card, note and review queries do not live here. Nothing is written.
 *
 * Upper layers depend on [com.studyagent.client.core.anki.AnkiBackend], not on this type.
 */
interface AnkiDroidDeckGateway {

    suspend fun queryDecks(authority: String): AnkiResult<AnkiDroidDeckListing>

    /**
     * AnkiDroid's own currently-selected deck, if the provider exposes one. Informational only:
     * Study-Agent never writes this value and never treats it as the session deck
     * (INV-ANKI-DECK-11). `Success(null)` means the row was empty or the id was not usable.
     */
    suspend fun querySelectedDeck(authority: String): AnkiResult<AnkiDeckRef?>

    /** Last completed listing, for diagnostics. Never includes deck names. */
    fun lastListingDiagnostics(): AnkiDeckQueryDiagnostics
}

/**
 * One successful `decks` query after mapping, skip-policy and deterministic sort.
 *
 * [decks] is immutable and ordered by [AnkiDeckOrder]. [skippedRowCount] counts optional-row
 * failures that were dropped (invalid id / blank name); a structural failure (required column
 * missing) never produces this object — the query fails instead (GATE 05 §38).
 */
data class AnkiDroidDeckListing(
    val decks: List<AnkiDeck>,
    val skippedRowCount: Int,
    val skippedByReason: Map<String, Int>,
    val rowsSeen: Int,
    val countsParsed: Int,
    val filteredParsed: Int,
    val queryDurationMs: Long,
    val mappingDurationMs: Long
) {
    init {
        require(skippedRowCount >= 0 && rowsSeen >= 0 && countsParsed >= 0 && filteredParsed >= 0)
        require(queryDurationMs >= 0L && mappingDurationMs >= 0L)
        require(skippedRowCount == skippedByReason.values.sum())
    }
}

/** Content-free deck-query facts for diagnostics (GATE 05 §116/§118). */
data class AnkiDeckQueryDiagnostics(
    val lastStatus: String,
    val lastDeckCount: Int? = null,
    val lastSkippedRows: Int? = null,
    val lastFilteredCount: Int? = null,
    val lastHierarchyDepth: Int? = null,
    val lastQueryDurationMs: Long? = null,
    val lastMappingDurationMs: Long? = null,
    val lastErrorCategory: String? = null,
    val lastAtMs: Long? = null
) {
    companion object {
        val NONE = AnkiDeckQueryDiagnostics(lastStatus = "NONE")
    }
}

class DefaultAnkiDroidDeckGateway(
    private val providerClient: AnkiDroidProviderClient,
    private val clock: AppClock = SystemAppClock
) : AnkiDroidDeckGateway {

    private val mutex = Mutex()
    private var inFlightDecks: CompletableDeferred<AnkiResult<AnkiDroidDeckListing>>? = null
    private var diagnostics: AnkiDeckQueryDiagnostics = AnkiDeckQueryDiagnostics.NONE

    override fun lastListingDiagnostics(): AnkiDeckQueryDiagnostics = diagnostics

    override suspend fun queryDecks(authority: String): AnkiResult<AnkiDroidDeckListing> {
        val slot = mutex.withLock {
            val current = inFlightDecks
            if (current != null && current.isActive) {
                current to false
            } else {
                val created = CompletableDeferred<AnkiResult<AnkiDroidDeckListing>>()
                inFlightDecks = created
                created to true
            }
        }
        val produced = slot.first
        if (!slot.second) return produced.await()
        try {
            val result = performDeckQuery(authority)
            produced.complete(result)
            return result
        } catch (cancellation: CancellationException) {
            produced.cancel(cancellation)
            throw cancellation
        } catch (throwable: Throwable) {
            val failure = AnkiResult.Failure(
                AnkiDroidErrorMapper.mapThrowable(throwable, "decks", AnkiDroidOperationStage.PROVIDER_QUERY)
            )
            produced.complete(failure)
            return failure
        } finally {
            mutex.withLock { if (inFlightDecks === produced) inFlightDecks = null }
        }
    }

    override suspend fun querySelectedDeck(authority: String): AnkiResult<AnkiDeckRef?> {
        try {
            val result = providerClient.safeQuery(
                authority = authority,
                path = AnkiDroidApiContract.SELECTED_DECK_PATH,
                projection = AnkiDroidApiContract.SELECTED_DECK_PROJECTION,
                selection = null,
                selectionArgs = null,
                sortOrder = null,
                mapper = { row -> AnkiDroidDeckMapper.mapSelectedDeckRow(row) }
            )
            return when (result) {
                is ProviderQueryResult.Empty -> AnkiResult.Success(null)
                is ProviderQueryResult.Failure -> AnkiResult.Failure(AnkiDroidErrorMapper.mapQueryResultFailure(result))
                is ProviderQueryResult.Success -> AnkiResult.Success(result.data.firstOrNull())
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            return AnkiResult.Failure(AnkiDroidErrorMapper.mapThrowable(throwable, "selected_deck", AnkiDroidOperationStage.PROVIDER_QUERY))
        }
    }

    private suspend fun performDeckQuery(authority: String): AnkiResult<AnkiDroidDeckListing> {
        val startedAt = clock.nowMillis()
        AppLogger.i(TAG, "ANKI_DECK_REFRESH_STARTED")
        return try {
            val result = providerClient.safeQuery(
                authority = authority,
                path = AnkiDroidApiContract.DECKS_PATH,
                projection = AnkiDroidApiContract.DECK_LIST_PROJECTION,
                selection = null,
                selectionArgs = null,
                sortOrder = null,
                mapper = { row -> AnkiDroidDeckMapper.mapDeckRow(row) }
            )
            val queryMs = (clock.nowMillis() - startedAt).coerceAtLeast(0L)
            val mapped = when (result) {
                is ProviderQueryResult.Empty ->
                    AnkiResult.Success(emptyListing(queryMs))
                is ProviderQueryResult.Failure ->
                    AnkiResult.Failure(AnkiDroidErrorMapper.mapQueryResultFailure(result))
                is ProviderQueryResult.Success ->
                    foldRows(result.data, queryMs)
            }
            record(mapped, startedAt)
            when (mapped) {
                is AnkiResult.Success -> AppLogger.i(
                    TAG,
                    "ANKI_DECK_REFRESH_SUCCEEDED count=${mapped.value.decks.size} skipped=${mapped.value.skippedRowCount} durationMs=$queryMs"
                )
                is AnkiResult.Failure -> AppLogger.w(
                    TAG,
                    "ANKI_DECK_REFRESH_FAILED error=${mapped.error::class.simpleName} durationMs=$queryMs"
                )
            }
            mapped
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            val queryMs = (clock.nowMillis() - startedAt).coerceAtLeast(0L)
            val failure = AnkiResult.Failure(
                AnkiDroidErrorMapper.mapThrowable(throwable, "decks", AnkiDroidOperationStage.PROVIDER_QUERY)
            )
            record(failure, startedAt)
            AppLogger.w(TAG, "ANKI_DECK_REFRESH_FAILED error=${throwable::class.java.simpleName} durationMs=$queryMs")
            failure
        }
    }

    private fun foldRows(
        outcomes: List<AnkiDroidDeckRowOutcome>,
        queryMs: Long
    ): AnkiResult<AnkiDroidDeckListing> {
        val mapStart = clock.nowMillis()
        val valid = ArrayList<AnkiDeck>(outcomes.size)
        val skipped = linkedMapOf<String, Int>()
        var countsParsed = 0
        var filteredParsed = 0
        val seenIds = HashSet<String>(outcomes.size)

        for (outcome in outcomes) {
            when (outcome) {
                is AnkiDroidDeckRowOutcome.Malformed -> {
                    if (outcome.problem.structural) {
                        return AnkiResult.Failure(AnkiError.MalformedResponse(detail = outcome.problem.token))
                    }
                    skipped[outcome.problem.token] = (skipped[outcome.problem.token] ?: 0) + 1
                }
                is AnkiDroidDeckRowOutcome.Valid -> {
                    val id = outcome.deck.ref.deckId
                    if (!seenIds.add(id)) {
                        skipped["duplicate_deck_id"] = (skipped["duplicate_deck_id"] ?: 0) + 1
                        continue
                    }
                    valid.add(outcome.deck)
                    if (outcome.countsParsed) countsParsed += 1
                    if (outcome.filteredParsed) filteredParsed += 1
                }
            }
        }

        if (valid.isEmpty() && skipped.isNotEmpty()) {
            return AnkiResult.Failure(AnkiError.MalformedResponse(detail = "all_deck_rows_unusable"))
        }

        val sorted = valid.sortedWith(AnkiDeckOrder)
        val mappingMs = (clock.nowMillis() - mapStart).coerceAtLeast(0L)
        return AnkiResult.Success(
            AnkiDroidDeckListing(
                decks = sorted,
                skippedRowCount = skipped.values.sum(),
                skippedByReason = skipped.toMap(),
                rowsSeen = outcomes.size,
                countsParsed = countsParsed,
                filteredParsed = filteredParsed,
                queryDurationMs = queryMs,
                mappingDurationMs = mappingMs
            )
        )
    }

    private fun emptyListing(queryMs: Long) = AnkiDroidDeckListing(
        decks = emptyList(),
        skippedRowCount = 0,
        skippedByReason = emptyMap(),
        rowsSeen = 0,
        countsParsed = 0,
        filteredParsed = 0,
        queryDurationMs = queryMs,
        mappingDurationMs = 0L
    )

    private fun record(result: AnkiResult<AnkiDroidDeckListing>, startedAt: Long) {
        diagnostics = when (result) {
            is AnkiResult.Success -> AnkiDeckQueryDiagnostics(
                lastStatus = "SUCCEEDED",
                lastDeckCount = result.value.decks.size,
                lastSkippedRows = result.value.skippedRowCount,
                lastFilteredCount = result.value.decks.count { it.isFiltered == true },
                lastHierarchyDepth = result.value.decks.maxOfOrNull { (it.path.size - 1).coerceAtLeast(0) },
                lastQueryDurationMs = result.value.queryDurationMs,
                lastMappingDurationMs = result.value.mappingDurationMs,
                lastErrorCategory = null,
                lastAtMs = startedAt
            )
            is AnkiResult.Failure -> AnkiDeckQueryDiagnostics(
                lastStatus = "FAILED",
                lastErrorCategory = result.error::class.simpleName,
                lastQueryDurationMs = (clock.nowMillis() - startedAt).coerceAtLeast(0L),
                lastAtMs = startedAt
            )
        }
    }

    private companion object {
        const val TAG = "AnkiDroidDeckGateway"
    }
}

/**
 * Scripted deck gateway for JVM tests. [queryDecks] / [querySelectedDeck] return [decksResult] /
 * [selectedResult] unless [throwable] is set (cancellation tests).
 */
class FakeAnkiDroidDeckGateway(
    var decksResult: AnkiResult<AnkiDroidDeckListing> = AnkiResult.Success(
        AnkiDroidDeckListing(
            decks = emptyList(),
            skippedRowCount = 0,
            skippedByReason = emptyMap(),
            rowsSeen = 0,
            countsParsed = 0,
            filteredParsed = 0,
            queryDurationMs = 0L,
            mappingDurationMs = 0L
        )
    ),
    var selectedResult: AnkiResult<AnkiDeckRef?> = AnkiResult.Success(null),
    var throwable: Throwable? = null,
    var diagnostics: AnkiDeckQueryDiagnostics = AnkiDeckQueryDiagnostics.NONE
) : AnkiDroidDeckGateway {

    var queryDecksCalls: Int = 0
        private set
    var querySelectedCalls: Int = 0
        private set
    val queriedAuthorities: MutableList<String> = mutableListOf()

    override fun lastListingDiagnostics(): AnkiDeckQueryDiagnostics = diagnostics

    override suspend fun queryDecks(authority: String): AnkiResult<AnkiDroidDeckListing> {
        queryDecksCalls++
        queriedAuthorities.add(authority)
        throwable?.let { throw it }
        return decksResult
    }

    override suspend fun querySelectedDeck(authority: String): AnkiResult<AnkiDeckRef?> {
        querySelectedCalls++
        queriedAuthorities.add(authority)
        throwable?.let { throw it }
        return selectedResult
    }

    fun succeed(decks: List<AnkiDeck>, selected: AnkiDeckRef? = null) {
        decksResult = AnkiResult.Success(
            AnkiDroidDeckListing(
                decks = decks,
                skippedRowCount = 0,
                skippedByReason = emptyMap(),
                rowsSeen = decks.size,
                countsParsed = decks.count { it.counts != null },
                filteredParsed = decks.count { it.isFiltered != null },
                queryDurationMs = 0L,
                mappingDurationMs = 0L
            )
        )
        selectedResult = AnkiResult.Success(selected)
    }
}
