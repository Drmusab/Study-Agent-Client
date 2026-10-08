package com.studyagent.client.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.LibraryDataState
import com.studyagent.client.core.anki.LibraryFreshness
import com.studyagent.client.data.anki.AnkiLibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Library presentation coordinator. All collection reads go through [AnkiBackend] via the shared
 * [AnkiLibraryRepository]; row rendering and search are entirely in-memory.
 */
class LibraryViewModel(
    private val backend: AnkiBackend,
    private val libraryRepository: AnkiLibraryRepository
) : ViewModel() {
    private var availability: AnkiAvailability = backend.availability.value
    private var capabilities: AnkiCapabilities = backend.capabilities.value
    private var dataState: LibraryDataState = libraryRepository.state.value
    private var query: String = ""
    private var operationError: AnkiError? = null
    private var backendUnavailableSinceLastRead: Boolean = false
    private var isRefreshing: Boolean = false
    private val refreshMutex = Mutex()

    private val _uiState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        publish()
        viewModelScope.launch {
            var wasReady = availability is AnkiAvailability.Ready
            backend.availability.collect { next ->
                val becameReady = !wasReady && next is AnkiAvailability.Ready
                availability = next
                wasReady = next is AnkiAvailability.Ready
                if (next !is AnkiAvailability.Ready && next != AnkiAvailability.Checking) {
                    backendUnavailableSinceLastRead = true
                }
                publish()
                val cached = dataState.snapshot
                val needsLoad = backendUnavailableSinceLastRead || cached == null || cached.freshness != LibraryFreshness.FRESH
                if (becameReady && needsLoad && !isRefreshing) refreshDecksWhenAvailable()
            }
        }
        viewModelScope.launch {
            var hadListing = capabilities.deckListing
            backend.capabilities.collect { next ->
                val gainedListing = !hadListing && next.deckListing
                capabilities = next
                hadListing = next.deckListing
                publish()
                val cached = dataState.snapshot
                val needsLoad = cached == null || cached.freshness != LibraryFreshness.FRESH
                if (gainedListing && availability is AnkiAvailability.Ready && needsLoad && !isRefreshing) {
                    refreshDecksWhenAvailable()
                }
            }
        }
        viewModelScope.launch {
            libraryRepository.state.collect { next ->
                dataState = next
                publish()
            }
        }
        refresh()
    }

    /** A cheap local filter. It never causes an Anki/backend call. */
    fun setSearchQuery(value: String) {
        query = value
        publish()
    }

    /**
     * Navigation return is a no-op while the in-memory snapshot is fresh. First load, failed load
     * and stale snapshots may be retried safely because the operation is read-only.
     */
    fun onScreenActive() {
        if (availability !is AnkiAvailability.Ready) return
        val snapshot = dataState.snapshot
        if (snapshot == null || snapshot.freshness != LibraryFreshness.FRESH) refresh()
    }

    /** Explicit read-only refresh. Concurrent taps are coalesced by this VM and repository. */
    fun refresh() {
        viewModelScope.launch {
            if (!refreshMutex.tryLock()) return@launch
            isRefreshing = true
            operationError = null
            publish()
            try {
                backend.refreshAvailability()
                availability = backend.availability.value
                capabilities = backend.capabilities.value
                if (availability is AnkiAvailability.Ready && capabilities.deckListing) {
                    dataState = libraryRepository.refresh()
                    operationError = null
                    if ((dataState as? LibraryDataState.Ready)?.snapshot?.freshness == LibraryFreshness.FRESH) {
                        backendUnavailableSinceLastRead = false
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                operationError = AnkiError.Unknown(cause = failure::class.java.simpleName)
            } finally {
                dataState = libraryRepository.state.value
                isRefreshing = false
                publish()
                refreshMutex.unlock()
            }
        }
    }

    private fun refreshDecksWhenAvailable() {
        viewModelScope.launch {
            if (!refreshMutex.tryLock()) return@launch
            isRefreshing = true
            operationError = null
            publish()
            try {
                if (availability is AnkiAvailability.Ready && capabilities.deckListing) {
                    dataState = libraryRepository.refresh()
                    if ((dataState as? LibraryDataState.Ready)?.snapshot?.freshness == LibraryFreshness.FRESH) {
                        backendUnavailableSinceLastRead = false
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                operationError = AnkiError.Unknown(cause = failure::class.java.simpleName)
            } finally {
                dataState = libraryRepository.state.value
                isRefreshing = false
                publish()
                refreshMutex.unlock()
            }
        }
    }

    private fun publish() {
        _uiState.value = projectState(
            backendId = backend.id,
            availability = availability,
            capabilities = capabilities,
            dataState = dataState,
            query = query,
            isRefreshing = isRefreshing,
            operationError = operationError
        )
    }

    private fun projectState(
        backendId: com.studyagent.client.core.anki.AnkiBackendId,
        availability: AnkiAvailability,
        capabilities: AnkiCapabilities,
        dataState: LibraryDataState,
        query: String,
        isRefreshing: Boolean,
        operationError: AnkiError?
    ): LibraryUiState {
        if (availability == AnkiAvailability.Checking) {
            return operationError?.let { LibraryUiState.Error(backendId, it, isRetrying = isRefreshing) }
                ?: LibraryUiState.Loading
        }
        if (availability !is AnkiAvailability.Ready) {
            return LibraryUiState.Unavailable(backendId, availability, isRetrying = isRefreshing)
        }
        if (!capabilities.deckListing) {
            return LibraryUiState.Error(
                backendId,
                AnkiError.UnsupportedAction("deckListing"),
                isRetrying = isRefreshing
            )
        }

        val snapshot = dataState.snapshot
        if (snapshot != null && snapshot.backendId != backendId) {
            // Never display a cache whose backend identity does not match the currently observed one.
            return LibraryUiState.Error(backendId, AnkiError.BackendUnavailable())
        }
        if (snapshot != null) {
            val summaries = snapshot.summaries()
            if (summaries.isEmpty()) {
                return LibraryUiState.Empty(
                    backend = backendId,
                    availability = availability,
                    capabilities = capabilities,
                    isRefreshing = isRefreshing || dataState.isRefreshing,
                    staleError = operationError ?: snapshot.lastRefreshError
                )
            }
            val items = summaries.map(::DeckListItem)
            return LibraryUiState.Ready(
                decks = items,
                tree = DeckTreeUiMapper.build(items, query),
                backend = backendId,
                availability = availability,
                capabilities = capabilities,
                isRefreshing = isRefreshing || dataState.isRefreshing,
                staleError = operationError ?: snapshot.lastRefreshError
            )
        }

        val error = when (dataState) {
            is LibraryDataState.Failed -> dataState.error
            else -> operationError
        }
        if (error != null) {
            error.asAvailability()?.let { return LibraryUiState.Unavailable(backendId, it, isRetrying = isRefreshing) }
            return LibraryUiState.Error(backendId, error, isRetrying = isRefreshing || dataState.isRefreshing)
        }
        return LibraryUiState.Loading
    }

    private fun AnkiError.asAvailability(): AnkiAvailability? = when (this) {
        is AnkiError.PermissionRequired -> AnkiAvailability.PermissionRequired()
        is AnkiError.ProviderUnavailable -> AnkiAvailability.ProviderUnavailable(detail)
        is AnkiError.UnsupportedApi -> AnkiAvailability.Unsupported("unsupported_api")
        is AnkiError.CollectionUnavailable -> AnkiAvailability.TemporarilyUnavailable("collection_unavailable")
        is AnkiError.BackendUnavailable -> AnkiAvailability.TemporarilyUnavailable()
        else -> null
    }
}
