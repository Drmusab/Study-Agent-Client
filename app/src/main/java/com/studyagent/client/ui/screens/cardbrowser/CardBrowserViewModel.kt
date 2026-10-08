package com.studyagent.client.ui.screens.cardbrowser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardPage
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.AnkiPageRequest
import com.studyagent.client.core.anki.unsupportedFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stable-identity navigation events; no card payload crosses the navigation boundary. */
sealed interface CardBrowserEvent {
    data class OpenCardDetails(val cardRef: AnkiCardRef) : CardBrowserEvent
}

/**
 * Owns one backend-bound browser query. Search is debounced; every query change invalidates its
 * cursor and cancels prior work. A one-at-a-time request mutex also prevents a backend that ignores
 * cancellation from receiving overlapping page requests. Generation + backend + query + cursor
 * checks remain the final authority before a response can update state.
 */
class CardBrowserViewModel(
    initialBackend: AnkiBackend,
    deckId: String? = null,
    private val pageSize: Int = AnkiPageRequest.DEFAULT_LIMIT,
    private val searchDebounceMs: Long = SEARCH_DEBOUNCE_MS
) : ViewModel() {
    private data class BackendSnapshot(
        val availability: AnkiAvailability,
        val capabilities: com.studyagent.client.core.anki.AnkiCapabilities
    )

    private data class RequestToken(
        val backendId: AnkiBackendId,
        val generation: Long,
        val query: CardBrowserQueryUi,
        val cursor: String?
    )

    private var backend: AnkiBackend = initialBackend
    private var backendSnapshot = BackendSnapshot(backend.availability.value, backend.capabilities.value)
    private var query = CardBrowserQueryUi(deckId = deckId)
    private var generation = 0L
    private var nextCursor: String? = null
    private var backendObservation: Job? = null
    private var firstPageJob: Job? = null
    private var appendPageJob: Job? = null
    private val requestMutex = Mutex()
    private val eventChannel = Channel<CardBrowserEvent>(Channel.BUFFERED)

    private val _uiState = MutableStateFlow<CardBrowserUiState>(
        CardBrowserUiState.Loading(query, backendSnapshot.capabilities.cardBrowser)
    )
    val uiState: StateFlow<CardBrowserUiState> = _uiState.asStateFlow()
    val events = eventChannel.receiveAsFlow()

    init {
        require(deckId == null || deckId.isNotBlank())
        require(pageSize in AnkiPageRequest.MIN_LIMIT..AnkiPageRequest.MAX_LIMIT)
        require(searchDebounceMs in MIN_SEARCH_DEBOUNCE_MS..MAX_SEARCH_DEBOUNCE_MS)
        observeBackend(backend)
        restartQuery(debounceMs = 0L)
    }

    /** Called for every keystroke; blank/whitespace normalizes to a null backend search. */
    fun setSearchText(value: String) {
        if (query.searchText == value) return
        query = query.copy(searchText = value)
        restartQuery(debounceMs = searchDebounceMs)
    }

    /** Filter changes keep text/sort and always restart from the first page. */
    fun setFilters(filters: com.studyagent.client.core.anki.AnkiCardFilters) {
        if (query.filters == filters) return
        query = query.copy(filters = filters.copy(
            flags = filters.flags.toSet(),
            tags = filters.tags.toSet(),
            cardTypes = filters.cardTypes.toSet()
        ))
        restartQuery(debounceMs = 0L)
    }

    /** Sort changes keep search/filters and always restart from the first page. */
    fun setSort(sort: com.studyagent.client.core.anki.AnkiCardSort) {
        if (query.sort == sort) return
        query = query.copy(sort = sort)
        restartQuery(debounceMs = 0L)
    }

    /** Read retry preserves search, filters, sort, deck scope and the current backend. */
    fun refresh() = restartQuery(debounceMs = 0L)

    /**
     * Load the next bounded page once. Stable card identity suppresses overlap/duplicate rows;
     * identical-looking questions with distinct AnkiCardRefs are retained.
     */
    fun loadMore() {
        val current = _uiState.value as? CardBrowserUiState.Ready ?: return
        val cursor = nextCursor ?: return
        if (!current.hasMore || current.isLoadingMore || appendPageJob?.isActive == true) return
        val token = RequestToken(backend.id, generation, query, cursor)
        _uiState.value = current.copy(isLoadingMore = true, appendError = null)
        val requestBackend = backend
        appendPageJob = viewModelScope.launch {
            val result = execute(requestBackend, token)
            if (!isCurrent(token, requestBackend) || nextCursor != cursor) return@launch
            when (result) {
                is AnkiResult.Failure -> {
                    val state = _uiState.value as? CardBrowserUiState.Ready ?: return@launch
                    _uiState.value = state.copy(isLoadingMore = false, appendError = result.error)
                }
                is AnkiResult.Success -> publishNextPage(result.value, token, current)
            }
        }
    }

    /**
     * Explicit backend rebinding for the future browser backend selector. It does not touch or
     * replace any StudySession lock; this ViewModel owns browse reads only.
     */
    fun switchBackend(next: AnkiBackend) {
        if (next === backend) return
        cancelRequests()
        backendObservation?.cancel()
        backend = next
        backendSnapshot = BackendSnapshot(next.availability.value, next.capabilities.value)
        generation += 1
        nextCursor = null
        observeBackend(next)
        restartQuery(debounceMs = 0L)
    }

    /** Emit only for a row currently present in the visible page set. */
    fun openCardDetails(cardRef: AnkiCardRef) {
        val ready = _uiState.value as? CardBrowserUiState.Ready ?: return
        if (cardRef.backendId != backend.id || ready.rows.none { it.cardRef == cardRef }) return
        eventChannel.trySend(CardBrowserEvent.OpenCardDetails(cardRef))
    }

    private fun observeBackend(observed: AnkiBackend) {
        backendObservation = viewModelScope.launch {
            combine(observed.availability, observed.capabilities) { availability, capabilities ->
                BackendSnapshot(availability, capabilities)
            }.collect { snapshot ->
                if (backend !== observed || snapshot == backendSnapshot) return@collect
                backendSnapshot = snapshot
                restartQuery(debounceMs = 0L)
            }
        }
    }

    private fun restartQuery(debounceMs: Long) {
        generation += 1
        nextCursor = null
        cancelRequests()
        when (val unavailable = unavailableFeature()) {
            null -> {
                _uiState.value = CardBrowserUiState.Loading(query, backendSnapshot.capabilities.cardBrowser)
                val token = RequestToken(backend.id, generation, query, cursor = null)
                val requestBackend = backend
                firstPageJob = viewModelScope.launch {
                    if (debounceMs > 0) delay(debounceMs)
                    val result = execute(requestBackend, token)
                    if (!isCurrent(token, requestBackend)) return@launch
                    when (result) {
                        is AnkiResult.Failure ->
                            _uiState.value = CardBrowserUiState.Error(
                                query, backend.id, result.error, backendSnapshot.capabilities.cardBrowser
                            )
                        is AnkiResult.Success -> publishFirstPage(result.value, token)
                    }
                }
            }
            is UnavailableReason.NotReady ->
                _uiState.value = if (unavailable.availability == AnkiAvailability.Checking) {
                    CardBrowserUiState.Loading(query, backendSnapshot.capabilities.cardBrowser)
                } else {
                    CardBrowserUiState.Unavailable(
                        query, backend.id, unavailable.availability, backendSnapshot.capabilities.cardBrowser
                    )
                }
            is UnavailableReason.Unsupported ->
                _uiState.value = CardBrowserUiState.Unavailable(
                    query = query,
                    backendId = backend.id,
                    availability = backendSnapshot.availability,
                    capabilities = backendSnapshot.capabilities.cardBrowser,
                    unsupportedFeature = unavailable.feature
                )
        }
    }

    private sealed interface UnavailableReason {
        data class NotReady(val availability: AnkiAvailability) : UnavailableReason
        data class Unsupported(val feature: String) : UnavailableReason
    }

    private fun unavailableFeature(): UnavailableReason? {
        val availability = backendSnapshot.availability
        if (availability !is AnkiAvailability.Ready) return UnavailableReason.NotReady(availability)
        val domainQuery = CardBrowserQueryMapper.toDomain(query, pageSize)
        val unsupported = domainQuery.unsupportedFeature(backendSnapshot.capabilities.cardBrowser)
        return unsupported?.let(UnavailableReason::Unsupported)
    }

    private suspend fun execute(
        targetBackend: AnkiBackend,
        token: RequestToken
    ): AnkiResult<AnkiCardPage> {
        return try {
            requestMutex.withLock {
                if (!isCurrent(token, targetBackend)) return@withLock null
                val request = CardBrowserQueryMapper.toDomain(query = token.query, pageSize = pageSize, cursor = token.cursor)
                targetBackend.browseCards(request)
            } ?: return AnkiResult.Failure(AnkiError.InvalidRequest("stale_card_browser_request"))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            AnkiResult.Failure(AnkiError.Unknown(cause = failure::class.java.simpleName))
        }
    }

    private fun publishFirstPage(page: AnkiCardPage, token: RequestToken) {
        if (page.items.size > pageSize) {
            _uiState.value = CardBrowserUiState.Error(
                query, backend.id, AnkiError.MalformedResponse("card_page_over_limit"),
                backendSnapshot.capabilities.cardBrowser
            )
            return
        }
        val rows = rowsFor(page, token) ?: return
        val total = page.totalCount.takeIf { backendSnapshot.capabilities.cardBrowser.totalCount }
        if (rows.isEmpty()) {
            _uiState.value = CardBrowserUiState.Empty(
                query = query,
                reason = if (query.hasSearchOrFilters) {
                    CardBrowserUiState.Empty.Reason.NO_MATCHES
                } else {
                    CardBrowserUiState.Empty.Reason.NO_CARDS_IN_SCOPE
                },
                capabilities = backendSnapshot.capabilities.cardBrowser,
                totalCount = total
            )
            nextCursor = null
            return
        }
        nextCursor = page.nextCursor
        _uiState.value = CardBrowserUiState.Ready(
            rows = rows,
            query = query,
            capabilities = backendSnapshot.capabilities.cardBrowser,
            hasMore = page.nextCursor != null,
            isLoadingMore = false,
            totalCount = total
        )
    }

    private fun publishNextPage(
        page: AnkiCardPage,
        token: RequestToken,
        previous: CardBrowserUiState.Ready
    ) {
        if (page.items.size > pageSize) {
            val current = _uiState.value as? CardBrowserUiState.Ready ?: return
            _uiState.value = current.copy(
                isLoadingMore = false,
                appendError = AnkiError.MalformedResponse("card_page_over_limit")
            )
            return
        }
        val rows = rowsFor(page, token) ?: return
        val byRef = LinkedHashMap<com.studyagent.client.core.anki.AnkiCardRef, CardBrowserRow>()
        previous.rows.forEach { byRef[it.cardRef] = it }
        rows.forEach { byRef.putIfAbsent(it.cardRef, it) }
        val next = page.nextCursor?.takeUnless { it == token.cursor }
        nextCursor = next
        _uiState.value = CardBrowserUiState.Ready(
            rows = byRef.values.toList(),
            query = query,
            capabilities = backendSnapshot.capabilities.cardBrowser,
            hasMore = next != null && page.items.isNotEmpty(),
            isLoadingMore = false,
            totalCount = page.totalCount.takeIf { backendSnapshot.capabilities.cardBrowser.totalCount }
                ?: previous.totalCount
        )
    }

    private fun rowsFor(page: AnkiCardPage, token: RequestToken): List<CardBrowserRow>? {
        val seen = LinkedHashSet<AnkiCardRef>()
        val rows = ArrayList<CardBrowserRow>(page.items.size)
        for (item in page.items) {
            if (item.cardRef.backendId != token.backendId) {
                _uiState.value = CardBrowserUiState.Error(
                    query, backend.id, AnkiError.MalformedResponse("foreign_card_ref"),
                    backendSnapshot.capabilities.cardBrowser
                )
                return null
            }
            if (token.query.deckId != null && item.deckRef?.deckId != token.query.deckId) {
                _uiState.value = CardBrowserUiState.Error(
                    query, backend.id, AnkiError.MalformedResponse("card_deck_scope_mismatch"),
                    backendSnapshot.capabilities.cardBrowser
                )
                return null
            }
            if (seen.add(item.cardRef)) {
                rows += CardBrowserRow.from(
                    item = item,
                    answerPreviewAllowed = backendSnapshot.capabilities.cardBrowser.answerPreview
                )
            }
        }
        return rows
    }

    private fun isCurrent(token: RequestToken, targetBackend: AnkiBackend): Boolean =
        backend === targetBackend && backend.id == token.backendId && generation == token.generation &&
            query == token.query && (token.cursor == null || nextCursor == token.cursor) &&
            targetBackend.availability.value == backendSnapshot.availability &&
            targetBackend.capabilities.value == backendSnapshot.capabilities

    private fun cancelRequests() {
        firstPageJob?.cancel()
        appendPageJob?.cancel()
        firstPageJob = null
        appendPageJob = null
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS: Long = 300L
        private const val MIN_SEARCH_DEBOUNCE_MS: Long = 250L
        private const val MAX_SEARCH_DEBOUNCE_MS: Long = 400L
    }
}
