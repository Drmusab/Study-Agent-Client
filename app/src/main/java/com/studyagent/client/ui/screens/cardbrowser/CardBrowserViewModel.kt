package com.studyagent.client.ui.screens.cardbrowser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardPage
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiCardScope
import com.studyagent.client.core.anki.AnkiCardSort
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiPageCursor
import com.studyagent.client.core.anki.AnkiPageRequest
import com.studyagent.client.core.anki.AnkiReadContext
import com.studyagent.client.core.anki.AnkiReadRequestIdentity
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.pageIdentityError
import com.studyagent.client.data.anki.context.AnkiReadContextAccess
import com.studyagent.client.data.anki.context.AnkiReadContextBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stable-identity navigation events; no card payload crosses the navigation boundary. */
sealed interface CardBrowserEvent {
    data class OpenCardDetails(
        val cardRef: AnkiCardRef,
        val readContext: AnkiReadContext
    ) : CardBrowserEvent
}

/**
 * Owns one immutable read context and one browser query (§73/21F).
 * Query generation and request generation are distinct; page cursors remain private, opaque values
 * and are valid only inside the exact backend/collection/context/query tuple that created them.
 */
class CardBrowserViewModel(
    initialBinding: AnkiReadContextBinding,
    deckRef: com.studyagent.client.core.anki.AnkiDeckRef? = null,
    private val pageSize: Int = AnkiPageRequest.DEFAULT_LIMIT,
    private val searchDebounceMs: Long = SEARCH_DEBOUNCE_MS,
    /** §46 — answer preview is a *UI policy*, so it is not part of the backend query contract. */
    private val answerPreviewEnabled: Boolean = ANSWER_PREVIEW_POLICY
) : ViewModel() {
    /** Compatibility for isolated tests that inject an already-chosen backend directly. */
    constructor(
        initialBackend: AnkiBackend,
        deckId: String? = null,
        pageSize: Int = AnkiPageRequest.DEFAULT_LIMIT,
        searchDebounceMs: Long = SEARCH_DEBOUNCE_MS,
        answerPreviewEnabled: Boolean = ANSWER_PREVIEW_POLICY
    ) : this(
        initialBinding = AnkiReadContextBinding.forLegacyBackend(initialBackend),
        deckRef = deckId?.let {
            com.studyagent.client.core.anki.AnkiDeckRef(
                initialBackend.id,
                it,
                initialBackend.currentCollectionIdentity?.collectionKey
            )
        },
        pageSize = pageSize,
        searchDebounceMs = searchDebounceMs,
        answerPreviewEnabled = answerPreviewEnabled
    )

    private data class BackendSnapshot(
        val availability: AnkiAvailability,
        val capabilities: AnkiCapabilities
    )

    private data class RequestToken(
        val binding: AnkiReadContextBinding,
        val identity: AnkiReadRequestIdentity,
        val query: CardBrowserQueryUi
    ) {
        val backendId: AnkiBackendId get() = identity.context.backendId
        val generation: Long get() = identity.queryGeneration
        val cursor: AnkiPageCursor? get() = identity.cursor
    }

    private val binding: AnkiReadContextBinding = initialBinding
    private val readAccess = AnkiReadContextAccess()
    private val backend: AnkiBackend = binding.backend
    private var backendSnapshot = BackendSnapshot(backend.availability.value, backend.capabilities.value)
    private var query = deckRef?.let { CardBrowserQueryUi.forDeck(it.deckId) } ?: CardBrowserQueryUi()
    private var generation = 0L
    private var requestGeneration = 0L
    private var latestRequestGeneration = 0L
    private var nextCursor: AnkiPageCursor? = null

    /**
     * §56 — a page's collection must match the immutable bound context. This secondary check keeps
     * the existing pagination defense while never treating a raw ID as collection identity.
     */
    private var loadedCollectionKey: String? = binding.context.collectionKey
    private var backendObservation: Job? = null
    private var firstPageJob: Job? = null
    private var appendPageJob: Job? = null
    private val requestMutex = Mutex()
    private val eventChannel = Channel<CardBrowserEvent>(Channel.BUFFERED)

    private val _uiState = MutableStateFlow<CardBrowserUiState>(
        CardBrowserUiState.Loading(query, backendSnapshot.capabilities.cardBrowser, binding.context)
    )
    val uiState: StateFlow<CardBrowserUiState> = _uiState.asStateFlow()
    val events = eventChannel.receiveAsFlow()

    init {
        require(deckRef == null || deckRef.deckId.isNotBlank())
        require(deckRef == null || deckRef.backendId == binding.context.backendId)
        require(deckRef == null || readAccess.validateResource(
            binding, deckRef.backendId, deckRef.collectionKey
        ) == null) { "A card-browser deck must belong to the bound read context" }
        require(pageSize in AnkiPageRequest.MIN_LIMIT..AnkiPageRequest.MAX_LIMIT)
        require(searchDebounceMs >= 0)
        observeBackend(backend)
        restartQuery(debounceMs = 0L)
    }

    /** Called for every keystroke; blank/whitespace normalizes to a null backend search. */
    fun setSearchText(value: String) {
        if (query.searchText == value) return
        query = query.copy(searchText = value)
        restartQuery(debounceMs = searchDebounceMs)
    }

    /**
     * Scope changes are new query identities (§57): AllCards ⇄ Deck, and `includeChildren`
     * true ⇄ false. The UI only offers a scope the active backend advertises (§21).
     */
    fun setScope(scope: AnkiCardScope) {
        if (query.scope == scope) return
        query = query.copy(scope = scope)
        restartQuery(debounceMs = 0L)
    }

    /** Filter changes keep text/sort and always restart from the first page (§59). */
    fun setFilters(filters: AnkiCardFilters) {
        if (query.filters == filters) return
        query = query.copy(filters = filters.canonical())
        restartQuery(debounceMs = 0L)
    }

    /** Sort changes keep search/filters and always restart from the first page (§60). */
    fun setSort(sort: AnkiCardSort) {
        if (query.sort == sort) return
        query = query.copy(sort = sort)
        restartQuery(debounceMs = 0L)
    }

    /** Returning from Card Details revalidates the owner; it never resolves a replacement backend. */
    fun onScreenActive() {
        val error = readAccess.validateBinding(binding)
        if (error != null && AnkiReadContextAccess.isContextSafetyFailure(error)) {
            generation += 1L
            cancelRequests()
            invalidatePaging()
            _uiState.value = CardBrowserUiState.Error(
                query, backend.id, error, backendSnapshot.capabilities.cardBrowser, binding.context
            )
        }
    }

    /** Read retry preserves scope, search, filters and sort, and starts a new first page. */
    fun refresh() = restartQuery(debounceMs = 0L)

    /**
     * Load the next bounded page once. Stable card identity suppresses overlap/duplicate rows;
     * identical-looking questions with distinct AnkiCardRefs are retained (§39).
     */
    fun loadMore() {
        val current = _uiState.value as? CardBrowserUiState.Ready ?: return
        val cursor = nextCursor ?: return
        if (!current.hasMore || current.isLoadingMore || appendPageJob?.isActive == true) return
        if (current.readContext != binding.context || !binding.isValid) return
        val token = newRequestToken(cursor)
        _uiState.value = current.copy(isLoadingMore = true, appendError = null)
        val requestBackend = backend
        appendPageJob = viewModelScope.launch {
            val result = execute(requestBackend, token)
            if (!isCurrent(token, requestBackend) || nextCursor != cursor) return@launch
            when (result) {
                is AnkiResult.Failure -> {
                    val state = _uiState.value as? CardBrowserUiState.Ready ?: return@launch
                    // Context-integrity failures invalidate the entire row/cursor set; ordinary
                    // page failures keep the clearly scoped already-loaded rows available.
                    if (AnkiReadContextAccess.isContextSafetyFailure(result.error)) {
                        invalidatePaging()
                        _uiState.value = CardBrowserUiState.Error(
                            query, backend.id, result.error, backendSnapshot.capabilities.cardBrowser,
                            binding.context
                        )
                    } else {
                        _uiState.value = state.copy(isLoadingMore = false, appendError = result.error)
                    }
                }
                is AnkiResult.Success -> publishNextPage(result.value, token, current)
            }
        }
    }


    /** Emit only for a row currently present in the visible page set. */
    fun openCardDetails(cardRef: AnkiCardRef) {
        val ready = _uiState.value as? CardBrowserUiState.Ready ?: return
        if (!binding.isValid || ready.readContext != binding.context ||
            cardRef.backendId != backend.id || ready.rows.none { it.cardRef == cardRef }
        ) return
        eventChannel.trySend(CardBrowserEvent.OpenCardDetails(cardRef, binding.context))
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
        invalidatePaging()
        cancelRequests()
        if (!binding.isValid) {
            _uiState.value = CardBrowserUiState.Error(
                query, backend.id, AnkiError.InvalidRequest("read_context_invalidated"),
                backendSnapshot.capabilities.cardBrowser, binding.context
            )
            return
        }
        when (val unavailable = unavailableReason()) {
            null -> {
                _uiState.value = CardBrowserUiState.Loading(
                    query, backendSnapshot.capabilities.cardBrowser, binding.context
                )
                val token = newRequestToken(cursor = null)
                val requestBackend = backend
                firstPageJob = viewModelScope.launch {
                    if (debounceMs > 0) delay(debounceMs)
                    val result = execute(requestBackend, token)
                    if (!isCurrent(token, requestBackend)) return@launch
                    when (result) {
                        is AnkiResult.Failure ->
                            _uiState.value = CardBrowserUiState.Error(
                                query, backend.id, result.error, backendSnapshot.capabilities.cardBrowser,
                                binding.context
                            )
                        is AnkiResult.Success -> publishFirstPage(result.value, token)
                    }
                }
            }
            is UnavailableReason.NotReady ->
                _uiState.value = if (unavailable.availability == AnkiAvailability.Checking) {
                    CardBrowserUiState.Loading(
                        query, backendSnapshot.capabilities.cardBrowser, binding.context
                    )
                } else {
                    CardBrowserUiState.Unavailable(
                        query, backend.id, unavailable.availability, backendSnapshot.capabilities.cardBrowser,
                        readContext = binding.context
                    )
                }
            is UnavailableReason.Unsupported ->
                _uiState.value = CardBrowserUiState.Unavailable(
                    query = query,
                    backendId = backend.id,
                    availability = backendSnapshot.availability,
                    capabilities = backendSnapshot.capabilities.cardBrowser,
                    unsupportedFeature = unavailable.feature,
                    readContext = binding.context
                )
            is UnavailableReason.Invalid ->
                _uiState.value = CardBrowserUiState.Error(
                    query, backend.id, unavailable.error, backendSnapshot.capabilities.cardBrowser,
                    binding.context
                )
        }
    }

    private fun newRequestToken(cursor: AnkiPageCursor?): RequestToken {
        requestGeneration += 1L
        latestRequestGeneration = requestGeneration
        return RequestToken(
            binding = binding,
            identity = AnkiReadRequestIdentity(
                context = binding.context,
                queryGeneration = generation,
                requestGeneration = requestGeneration,
                cursor = cursor
            ),
            query = query
        )
    }

    private sealed interface UnavailableReason {
        data class NotReady(val availability: AnkiAvailability) : UnavailableReason
        data class Unsupported(val feature: String) : UnavailableReason
        data class Invalid(val error: AnkiError) : UnavailableReason
    }

    /**
     * §63 preflight with the backend's own advertised capabilities. This is UI convenience only —
     * the backend still validates every request; the gate exists so a user never taps a control the
     * backend would refuse.
     */
    private fun unavailableReason(): UnavailableReason? {
        val availability = backendSnapshot.availability
        if (availability !is AnkiAvailability.Ready) return UnavailableReason.NotReady(availability)
        val domainQuery = CardBrowserQueryMapper.toDomain(query, pageSize)
        return when (val error = domainQuery.preflightError(backendSnapshot.capabilities.cardBrowser)) {
            null -> null
            is AnkiError.UnsupportedQueryFeature -> UnavailableReason.Unsupported(error.feature)
            else -> UnavailableReason.Invalid(error)
        }
    }

    private suspend fun execute(
        targetBackend: AnkiBackend,
        token: RequestToken
    ): AnkiResult<AnkiCardPage> {
        return try {
            requestMutex.withLock {
                if (!isCurrent(token, targetBackend)) return@withLock null
                val request = CardBrowserQueryMapper.toDomain(
                    query = token.query,
                    pageSize = pageSize,
                    cursor = token.cursor
                )
                readAccess.read(
                    binding = token.binding,
                    requiredCapabilities = setOf(AnkiRequiredCapability.CARD_BROWSER),
                    operation = { it.browseCards(request) },
                    validateResult = ::validatePageContext
                )
            } ?: return AnkiResult.Failure(AnkiError.InvalidQuery(detail = "stale_card_browser_request"))
        } catch (cancellation: CancellationException) {
            // §54 — obsolete queries are cancelled silently; cancellation is never a query error.
            throw cancellation
        } catch (failure: Throwable) {
            AnkiResult.Failure(AnkiError.Unknown(cause = failure::class.java.simpleName))
        }
    }

    private fun validatePageContext(page: AnkiCardPage): AnkiError? {
        page.items.forEach { item ->
            listOfNotNull(
                item.cardRef.let { it.backendId to it.collectionKey },
                item.noteRef?.let { it.backendId to it.collectionKey },
                item.deckRef?.let { it.backendId to it.collectionKey }
            ).forEach { (backendId, collectionKey) ->
                readAccess.validateResource(binding, backendId, collectionKey)?.let { return it }
            }
        }
        return null
    }

    private fun contextError(): AnkiError? = readAccess.validateBinding(binding)

    private fun publishFirstPage(page: AnkiCardPage, token: RequestToken) {
        contextError()?.let { error ->
            invalidatePaging()
            _uiState.value = CardBrowserUiState.Error(
                query, backend.id, error, backendSnapshot.capabilities.cardBrowser, binding.context
            )
            return
        }
        if (page.items.size > pageSize) {
            _uiState.value = CardBrowserUiState.Error(
                query, backend.id, AnkiError.DataIntegrityFailure("card_page_over_limit"),
                backendSnapshot.capabilities.cardBrowser, binding.context
            )
            return
        }
        val rows = rowsFor(page, token) ?: return
        loadedCollectionKey = binding.context.collectionKey
        val total = page.totalCount.takeIf { backendSnapshot.capabilities.cardBrowser.supportsTotalCount }
        if (rows.isEmpty()) {
            _uiState.value = CardBrowserUiState.Empty(
                query = query,
                reason = if (query.hasSearchOrFilters) {
                    CardBrowserUiState.Empty.Reason.NO_MATCHES
                } else {
                    CardBrowserUiState.Empty.Reason.NO_CARDS_IN_SCOPE
                },
                capabilities = backendSnapshot.capabilities.cardBrowser,
                totalCount = total,
                readContext = binding.context
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
            totalCount = total,
            readContext = binding.context
        )
    }

    private fun publishNextPage(
        page: AnkiCardPage,
        token: RequestToken,
        previous: CardBrowserUiState.Ready
    ) {
        contextError()?.let { error ->
            invalidatePaging()
            _uiState.value = CardBrowserUiState.Error(
                query, backend.id, error, backendSnapshot.capabilities.cardBrowser, binding.context
            )
            return
        }
        if (page.items.size > pageSize) {
            val current = _uiState.value as? CardBrowserUiState.Ready ?: return
            _uiState.value = current.copy(
                isLoadingMore = false,
                appendError = AnkiError.DataIntegrityFailure("card_page_over_limit")
            )
            return
        }
        // §56 — the collection identity moved between pages: the old paging sequence is invalid.
        val pageCollectionKey = page.items.firstOrNull()?.cardRef?.collectionKey
        if (pageCollectionKey != null && loadedCollectionKey != null &&
            pageCollectionKey != loadedCollectionKey
        ) {
            restartQuery(debounceMs = 0L)
            return
        }
        val rows = rowsFor(page, token) ?: return
        val byRef = LinkedHashMap<AnkiCardRef, CardBrowserRow>()
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
            totalCount = page.totalCount.takeIf { backendSnapshot.capabilities.cardBrowser.supportsTotalCount }
                ?: previous.totalCount,
            readContext = binding.context
        )
    }

    /**
     * Defensive row validation (§39/§43/§44): a page that contains a foreign backend reference, a
     * row without usable identity or a row outside the requested deck scope fails as a data
     * integrity error instead of being published with invented identity.
     */
    private fun rowsFor(page: AnkiCardPage, token: RequestToken): List<CardBrowserRow>? {
        val seen = LinkedHashSet<AnkiCardRef>()
        val rows = ArrayList<CardBrowserRow>(page.items.size)
        // Only an exact-deck scope can be checked here; a child-inclusive scope is verified by the
        // backend, which owns the authoritative deck hierarchy (§5/§66). The identity rules
        // themselves are shared with the backend (single source of truth, §44).
        val requiredDeckId = (token.query.scope as? AnkiCardScope.Deck)
            ?.takeIf { !it.includeChildren }
            ?.deckId
        for (item in page.items) {
            val detail = item.pageIdentityError(token.backendId, requiredDeckId)
            if (detail != null) {
                _uiState.value = CardBrowserUiState.Error(
                    query, backend.id, AnkiError.DataIntegrityFailure(detail),
                    backendSnapshot.capabilities.cardBrowser, binding.context
                )
                return null
            }
            if (seen.add(item.cardRef)) {
                rows += CardBrowserRow.from(item = item, answerPreviewAllowed = answerPreviewEnabled)
            }
        }
        return rows
    }

    private fun isCurrent(token: RequestToken, targetBackend: AnkiBackend): Boolean =
        backend === targetBackend && binding === token.binding &&
            targetBackend.id == token.backendId && token.identity.context == binding.context &&
            generation == token.generation &&
            token.identity.requestGeneration == latestRequestGeneration && query == token.query &&
            (token.cursor == null || nextCursor == token.cursor) &&
            targetBackend.availability.value == backendSnapshot.availability &&
            targetBackend.capabilities.value == backendSnapshot.capabilities

    /** §55/§56/§58-§60 — every invalidation drops the cursor and the collection binding with it. */
    private fun invalidatePaging() {
        nextCursor = null
        loadedCollectionKey = null
    }

    private fun cancelRequests() {
        firstPageJob?.cancel()
        appendPageJob?.cancel()
        firstPageJob = null
        appendPageJob = null
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS: Long = 300L

        /**
         * §46 — whether rows show an answer preview is a display policy, not a backend capability:
         * the backend does not need a second query contract for it.
         */
        const val ANSWER_PREVIEW_POLICY: Boolean = true
    }
}
