package com.studyagent.client.data.anki

import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckOrder
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.AnkiReadRequestIdentity
import com.studyagent.client.core.anki.LibraryDataState
import com.studyagent.client.core.anki.LibraryFreshness
import com.studyagent.client.core.anki.LibrarySnapshot
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.SystemAppClock
import com.studyagent.client.data.anki.context.AnkiReadContextAccess
import com.studyagent.client.data.anki.context.AnkiReadContextBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GATE 05/21F — backend-neutral Library data source, now owned by one immutable read context.
 *
 * Responsibilities retained from GATE 05:
 * - last-good snapshot, single-flight refresh, and stale-while-error inside this exact context;
 * - generation-safe publication and one deck tree per accepted snapshot;
 * - no scheduler/session authority, persistence, mutations or AnkiDroid selected-deck writes.
 *
 * This repository is created per bound read context. A new backend/collection/generation gets a new
 * instance; `cached` is never moved or reinterpreted. A backend/collection safety failure clears
 * this context's snapshot rather than showing data whose ownership could no longer be proved.
 */
class AnkiLibraryRepository(
    val binding: AnkiReadContextBinding,
    private val clock: AppClock = SystemAppClock,
    private val readAccess: AnkiReadContextAccess = AnkiReadContextAccess()
) {
    /** Compatibility for existing isolated tests/callers that already chose one exact backend. */
    constructor(
        backend: AnkiBackend,
        clock: AppClock = SystemAppClock
    ) : this(AnkiReadContextBinding.forLegacyBackend(backend), clock)

    private val backend: AnkiBackend get() = binding.backend

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

        val requestIdentity = mutex.withLock {
            AnkiReadRequestIdentity(
                context = binding.context,
                queryGeneration = 0L,
                requestGeneration = generation
            )
        }
        markRefreshing(requestIdentity)
        return try {
            val next = performRefresh(requestIdentity)
            produced.complete(next)
            next
        } catch (cancellation: CancellationException) {
            produced.cancel(cancellation)
            throw cancellation
        } catch (throwable: Throwable) {
            val failed = publishFailure(
                requestIdentity,
                AnkiError.Unknown(cause = throwable::class.java.simpleName)
            )
            produced.complete(failed)
            failed
        } finally {
            mutex.withLock { if (inFlight === produced) inFlight = null }
        }
    }

    private suspend fun markRefreshing(requestIdentity: AnkiReadRequestIdentity) {
        mutex.withLock {
            if (requestIdentity.context != binding.context ||
                requestIdentity.requestGeneration != generation || !binding.isValid
            ) return@withLock
            when (val current = _state.value) {
                is LibraryDataState.Ready -> _state.value = current.copy(isRefreshing = true)
                is LibraryDataState.Failed -> _state.value = current.copy(isRefreshing = true)
                LibraryDataState.Idle, LibraryDataState.Loading -> _state.value = LibraryDataState.Loading
            }
        }
    }

    private suspend fun performRefresh(requestIdentity: AnkiReadRequestIdentity): LibraryDataState {
        val startedAt = clock.nowMillis()
        AppLogger.i(
            TAG,
            "ANKI_DECK_REFRESH_STARTED backend=${backend.id.stableId} gen=${requestIdentity.requestGeneration}"
        )

        val decksResult = readAccess.read(
            binding = binding,
            requiredCapabilities = setOf(AnkiRequiredCapability.DECKS),
            operation = { it.getDecks() },
            validateResult = ::validateDecks
        )
        if (decksResult is AnkiResult.Failure) {
            return publishFailure(requestIdentity, decksResult.error).also {
                AppLogger.w(
                    TAG,
                    "ANKI_DECK_REFRESH_FAILED backend=${backend.id.stableId} error=${decksResult.error::class.simpleName}"
                )
            }
        }
        val decks = (decksResult as AnkiResult.Success).value

        // Selected-deck identity is informational, but this second read is still context-guarded.
        // Ordinary failure merely leaves the selected marker unknown; an ownership failure blocks
        // publication of the otherwise successful deck listing.
        val selectedResult = readAccess.read(
            binding = binding,
            requiredCapabilities = setOf(AnkiRequiredCapability.DECKS),
            operation = { it.getSelectedDeck() },
            validateResult = { selected -> selected?.let(::validateDeckRef) }
        )
        val selected = when (selectedResult) {
            is AnkiResult.Success -> selectedResult.value
            is AnkiResult.Failure -> {
                if (AnkiReadContextAccess.isContextSafetyFailure(selectedResult.error)) {
                    return publishFailure(requestIdentity, selectedResult.error)
                }
                null
            }
        }

        // Invalidation may race with the final callback / snapshot construction.
        readAccess.validateBinding(binding)?.let { return publishFailure(requestIdentity, it) }
        val latency = (clock.nowMillis() - startedAt).coerceAtLeast(0L)
        val snapshot = snapshotOf(decks, selected, latency)
        return publishReady(requestIdentity, snapshot).also {
            AppLogger.i(
                TAG,
                "ANKI_DECK_REFRESH_SUCCEEDED backend=${backend.id.stableId} count=${snapshot.deckCount} durationMs=$latency"
            )
        }
    }

    private fun validateDecks(decks: List<AnkiDeck>): AnkiError? {
        decks.forEach { deck ->
            validateDeckRef(deck.ref)?.let { return it }
            deck.parentRef?.let { validateDeckRef(it) }?.let { return it }
        }
        return null
    }

    private fun validateDeckRef(ref: AnkiDeckRef): AnkiError? =
        readAccess.validateResource(binding, ref.backendId, ref.collectionKey)

    private fun snapshotOf(
        decks: List<AnkiDeck>,
        selected: AnkiDeckRef?,
        latencyMs: Long
    ): LibrarySnapshot {
        val ordered = decks.sortedWith(AnkiDeckOrder)
        val treeStart = clock.nowMillis()
        val tree = com.studyagent.client.core.anki.AnkiDeckTreeBuilder.build(ordered)
        val treeMs = (clock.nowMillis() - treeStart).coerceAtLeast(0L)
        if (decks.size >= LARGE_COLLECTION_LOG_THRESHOLD) {
            AppLogger.i(TAG, "ANKI_DECK_TREE_BUILT count=${decks.size} durationMs=$treeMs depth=${tree.maxDepth}")
        }
        return LibrarySnapshot(
            backendId = backend.id,
            collection = binding.context.backendContext.collection,
            decks = ordered,
            tree = tree,
            selectedDeck = selected,
            refreshedAtMs = clock.nowMillis(),
            freshness = LibraryFreshness.FRESH,
            lastRefreshError = null,
            lastRefreshLatencyMs = latencyMs,
            readContext = binding.context
        )
    }

    private suspend fun publishReady(
        requestIdentity: AnkiReadRequestIdentity,
        snapshot: LibrarySnapshot
    ): LibraryDataState {
        val ready = LibraryDataState.Ready(snapshot, isRefreshing = false)
        val accepted = mutex.withLock {
            if (requestIdentity.context != binding.context ||
                requestIdentity.requestGeneration != generation ||
                !binding.isValid || snapshot.readContext != requestIdentity.context
            ) {
                false
            } else {
                cached = snapshot
                // Publish under the same lock as the generation check; a newer request cannot race
                // between accepting this result and exposing its state.
                _state.value = ready
                true
            }
        }
        if (!accepted) {
            AppLogger.i(
                TAG,
                "ANKI_DECK_REFRESH_STALE_DISCARDED gen=${requestIdentity.requestGeneration} current=$generation"
            )
            return _state.value
        }
        return ready
    }

    private suspend fun publishFailure(
        requestIdentity: AnkiReadRequestIdentity,
        error: AnkiError
    ): LibraryDataState {
        val contextFailure = AnkiReadContextAccess.isContextSafetyFailure(error)
        var published: LibraryDataState? = null
        val accepted = mutex.withLock {
            if (requestIdentity.context != binding.context) {
                false
            } else if ((error as? AnkiError.InvalidRequest)?.detail ==
                AnkiReadContextAccess.READ_CONTEXT_INVALIDATED
            ) {
                // Owner disposal/registry removal clears the scoped cache but is not a backend
                // failure to publish. The screen boundary projects the invalidated-context error.
                generation += 1L
                cached = null
                _state.value = LibraryDataState.Idle
                published = LibraryDataState.Idle
                true
            } else if (contextFailure) {
                // A confirmed identity-integrity failure invalidates this entire fixed-context cache,
                // even if the request itself was superseded. It is safety evidence, not an ordinary stale error.
                generation += 1L
                cached = null
                LibraryDataState.Failed(error = error, failedAtMs = clock.nowMillis(), isRefreshing = false)
                    .also {
                        _state.value = it
                        published = it
                    }
                true
            } else if (requestIdentity.requestGeneration != generation) {
                false
            } else {
                val cachedSnapshot = cached?.takeIf {
                    it.backendId == backend.id && it.readContext == binding.context
                }
                val next = if (cachedSnapshot != null) {
                    LibraryDataState.Ready(cachedSnapshot.markStale(error), isRefreshing = false)
                } else {
                    LibraryDataState.Failed(error = error, failedAtMs = clock.nowMillis(), isRefreshing = false)
                }
                _state.value = next
                published = next
                true
            }
        }
        if (!accepted) {
            AppLogger.i(
                TAG,
                "ANKI_DECK_REFRESH_STALE_DISCARDED gen=${requestIdentity.requestGeneration} current=$generation"
            )
            return _state.value
        }
        return checkNotNull(published)
    }

    /** Drop cache and reject outstanding results when this screen's context owner is disposed. */
    suspend fun invalidateContext() {
        mutex.withLock {
            generation += 1
            cached = null
            binding.invalidateLease()
            _state.value = LibraryDataState.Idle
        }
    }

    /** Legacy compatibility. Backend identity changes revoke this repository; collection is fixed. */
    fun invalidateIfBackendChanged(expected: AnkiBackendId) {
        if (binding.context.backendId != expected) {
            cached = null
            binding.invalidateLease()
            _state.value = LibraryDataState.Idle
        }
    }

    private companion object {
        const val TAG = "AnkiLibraryRepository"
        const val LARGE_COLLECTION_LOG_THRESHOLD = 250
    }
}
