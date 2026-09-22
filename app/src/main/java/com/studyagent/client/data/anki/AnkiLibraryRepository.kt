package com.studyagent.client.data.anki

import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckOrder
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiDeckTreeBuilder
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.LibraryDataState
import com.studyagent.client.core.anki.LibraryFreshness
import com.studyagent.client.core.anki.LibrarySnapshot
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.SystemAppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GATE 05 — backend-neutral Library data source (INV-ANKI-DECK-08/09/10).
 *
 * Observes one [AnkiBackend] and exposes a single [LibraryDataState] flow future
 * `LibraryViewModel`s can collect. Responsibilities that justify this type over calling
 * `getDecks()` directly:
 *
 * - in-memory last-good snapshot, scoped by backend id (and collection key when known);
 * - stale-while-refresh / stale-while-error so a failed refresh does not blank the Library;
 * - single-flight so Dashboard + Library + Settings cannot stampede the provider;
 * - generation so a slow older refresh cannot overwrite a newer snapshot;
 * - tree built once per snapshot, not per collector.
 *
 * Not scheduling authority (INV-ANKI-DECK-07). Not persisted. Does not start a review session
 * and does not write AnkiDroid's selected deck (INV-ANKI-DECK-11/12).
 *
 * Refresh is event-driven: the caller invokes [refresh] when Library opens, the user asks, or
 * the backend becomes ready. There is no poll loop.
 */
class AnkiLibraryRepository(
    private val backend: AnkiBackend,
    private val clock: AppClock = SystemAppClock
) {
    private val _state = MutableStateFlow<LibraryDataState>(LibraryDataState.Idle)
    val state: StateFlow<LibraryDataState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var generation: Long = 0L
    private var inFlight: CompletableDeferred<LibraryDataState>? = null

    @Volatile
    private var cached: LibrarySnapshot? = null

    suspend fun refresh(): LibraryDataState {
        val slot = mutex.withLock {
            val current = inFlight
            if (current != null && current.isActive) {
                current to false
            } else {
                generation += 1
                val created = CompletableDeferred<LibraryDataState>()
                inFlight = created
                created to true
            }
        }
        val produced = slot.first
        if (!slot.second) return produced.await()

        val requestGen = mutex.withLock { generation }
        markRefreshing()
        return try {
            val next = performRefresh(requestGen)
            produced.complete(next)
            next
        } catch (cancellation: CancellationException) {
            produced.cancel(cancellation)
            throw cancellation
        } catch (throwable: Throwable) {
            val failed = publishFailure(
                requestGen,
                AnkiError.Unknown(cause = throwable::class.java.simpleName)
            )
            produced.complete(failed)
            failed
        } finally {
            mutex.withLock { if (inFlight === produced) inFlight = null }
        }
    }

    private fun markRefreshing() {
        when (val current = _state.value) {
            is LibraryDataState.Ready -> _state.value = current.copy(isRefreshing = true)
            is LibraryDataState.Failed -> _state.value = current.copy(isRefreshing = true)
            LibraryDataState.Idle, LibraryDataState.Loading -> _state.value = LibraryDataState.Loading
        }
    }

    private suspend fun performRefresh(requestGen: Long): LibraryDataState {
        val startedAt = clock.nowMillis()
        AppLogger.i(TAG, "ANKI_DECK_REFRESH_STARTED backend=${backend.id.stableId} gen=$requestGen")

        val decksResult = try {
            backend.getDecks()
        } catch (cancellation: CancellationException) {
            throw cancellation
        }

        val selected = try {
            when (val result = backend.getSelectedDeck()) {
                is AnkiResult.Success -> result.value
                is AnkiResult.Failure -> null
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

        val latency = (clock.nowMillis() - startedAt).coerceAtLeast(0L)
        return when (decksResult) {
            is AnkiResult.Success -> {
                val snapshot = snapshotOf(decksResult.value, selected, latency)
                publishReady(requestGen, snapshot).also {
                    AppLogger.i(
                        TAG,
                        "ANKI_DECK_REFRESH_SUCCEEDED backend=${backend.id.stableId} count=${snapshot.deckCount} durationMs=$latency"
                    )
                }
            }
            is AnkiResult.Failure -> {
                AppLogger.w(
                    TAG,
                    "ANKI_DECK_REFRESH_FAILED backend=${backend.id.stableId} error=${decksResult.error::class.simpleName} durationMs=$latency"
                )
                publishFailure(requestGen, decksResult.error)
            }
        }
    }

    private fun snapshotOf(
        decks: List<AnkiDeck>,
        selected: AnkiDeckRef?,
        latencyMs: Long
    ): LibrarySnapshot {
        val ordered = decks.sortedWith(AnkiDeckOrder)
        val treeStart = clock.nowMillis()
        val tree = AnkiDeckTreeBuilder.build(ordered)
        val treeMs = (clock.nowMillis() - treeStart).coerceAtLeast(0L)
        if (decks.size >= LARGE_COLLECTION_LOG_THRESHOLD) {
            AppLogger.i(TAG, "ANKI_DECK_TREE_BUILT count=${decks.size} durationMs=$treeMs depth=${tree.maxDepth}")
        }
        return LibrarySnapshot(
            backendId = backend.id,
            collection = null,
            decks = ordered,
            tree = tree,
            selectedDeck = selected?.takeIf { it.backendId == backend.id },
            refreshedAtMs = clock.nowMillis(),
            freshness = LibraryFreshness.FRESH,
            lastRefreshError = null,
            lastRefreshLatencyMs = latencyMs
        )
    }

    private suspend fun publishReady(requestGen: Long, snapshot: LibrarySnapshot): LibraryDataState {
        val accepted = mutex.withLock {
            if (requestGen != generation) {
                false
            } else {
                cached = snapshot
                true
            }
        }
        if (!accepted) {
            AppLogger.i(TAG, "ANKI_DECK_REFRESH_STALE_DISCARDED gen=$requestGen current=$generation")
            return _state.value
        }
        val ready = LibraryDataState.Ready(snapshot, isRefreshing = false)
        _state.value = ready
        return ready
    }

    private suspend fun publishFailure(requestGen: Long, error: AnkiError): LibraryDataState {
        val accepted = mutex.withLock { requestGen == generation }
        if (!accepted) {
            AppLogger.i(TAG, "ANKI_DECK_REFRESH_STALE_DISCARDED gen=$requestGen current=$generation")
            return _state.value
        }
        val cachedSnapshot = cached?.takeIf { it.backendId == backend.id }
        val next = if (cachedSnapshot != null) {
            LibraryDataState.Ready(cachedSnapshot.markStale(error), isRefreshing = false)
        } else {
            LibraryDataState.Failed(error = error, failedAtMs = clock.nowMillis(), isRefreshing = false)
        }
        _state.value = next
        return next
    }

    /**
     * Drop the in-memory snapshot when the effective backend (or collection) is no longer the
     * one this cache was built for. Called by composition when the selector changes; GATE 05
     * wires a single AnkiDroid backend so this is a no-op unless tests rotate identity.
     */
    fun invalidateIfBackendChanged(expected: AnkiBackendId) {
        val snapshot = cached ?: return
        if (snapshot.backendId != expected) {
            cached = null
            _state.value = LibraryDataState.Idle
        }
    }

    private companion object {
        const val TAG = "AnkiLibraryRepository"
        const val LARGE_COLLECTION_LOG_THRESHOLD = 250
    }
}
