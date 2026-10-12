package com.studyagent.client.ui.screens.carddetails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardHydration
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiReadContext
import com.studyagent.client.core.anki.AnkiReadRequestIdentity
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.data.anki.context.AnkiReadContextAccess
import com.studyagent.client.data.anki.context.AnkiReadContextBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * GATE 16 — owns exactly one backend-qualified card details read (CHECKPOINT 14).
 *
 * What it may do: load one exact [AnkiCardRef], refresh that exact current card, observe backend
 * availability/capabilities and reject obsolete responses.
 *
 * What it must never do (INV-16-01/18/19): rate, edit a note/field/tag/deck, set a flag, bury,
 * suspend, delete, call `nextCard`, create a review session or otherwise advance the scheduler.
 * There is no mutation dependency in this class.
 *
 * Every request is correlated with the backend instance and logical id, collection key (if known),
 * exact card reference and monotonically increasing generation (INV-16-14/15). A coroutine backend
 * that ignores cancellation still cannot publish a response after another card/backend request
 * took ownership.
 */
class CardDetailsViewModel(
    private val binding: AnkiReadContextBinding,
    initialCardRef: AnkiCardRef
) : ViewModel() {
    /** Compatibility for isolated tests that provide an exact backend-qualified reference. */
    constructor(initialBackend: AnkiBackend, initialCardRef: AnkiCardRef) : this(
        AnkiReadContextBinding.forRouteReference(initialBackend, initialCardRef.collectionKey),
        initialCardRef
    ) { require(initialBackend.id == initialCardRef.backendId) }
    private data class BackendSnapshot(
        val availability: AnkiAvailability,
        val cardDetailsSupported: Boolean,
        /**
         * GATE 17 — capability truth for the editor entry only. This ViewModel performs no mutation
         * and holds no mutation dependency (INV-16-01); it reports whether the connected backend
         * offers any note-edit operation, and navigation decides what happens next.
         */
        val noteEditingOffered: Boolean
    )

    private data class RequestToken(
        val binding: AnkiReadContextBinding,
        val identity: AnkiReadRequestIdentity,
        val cardRef: AnkiCardRef,
        val noteEditingOffered: Boolean
    ) {
        val backend: AnkiBackend get() = binding.backend
        val backendId: AnkiBackendId get() = identity.context.backendId
        val collectionKey: String? get() = identity.context.collectionKey
        val generation: Long get() = identity.requestGeneration
    }

    private val backend: AnkiBackend = binding.backend
    private var cardRef: AnkiCardRef = initialCardRef
    private val readAccess = AnkiReadContextAccess()
    private var backendSnapshot = snapshot(backend)
    private var generation = 0L
    private var backendObservation: Job? = null
    private var requestJob: Job? = null

    private val _uiState = MutableStateFlow<CardDetailsUiState>(CardDetailsUiState.Loading)
    val uiState: StateFlow<CardDetailsUiState> = _uiState.asStateFlow()

    init {
        require(binding.context.backendId == initialCardRef.backendId)
        observeBackend(backend)
        reload()
    }

    /** Re-fetch current authoritative details for the same exact card; stale details are cleared. */
    fun refresh() = reload()

    /**
     * Route/ViewModel reuse seam for switching from Card A to Card B. Card identity changes before
     * the request begins, and every response for A is invalidated even if the old backend ignores
     * cancellation (INV-16-14).
     */
    fun loadCard(nextCardRef: AnkiCardRef) {
        if (nextCardRef == cardRef) {
            refresh()
            return
        }
        cardRef = nextCardRef
        reload()
    }

    private fun observeBackend(observed: AnkiBackend) {
        backendObservation = viewModelScope.launch {
            combine(observed.availability, observed.capabilities) { availability, capabilities ->
                BackendSnapshot(
                    availability = availability,
                    cardDetailsSupported = capabilities.cardDetails,
                    noteEditingOffered = capabilities.editNoteFields ||
                        capabilities.editNoteTags ||
                        capabilities.changeCardDeck
                )
            }.collect { next ->
                if (backend !== observed || next == backendSnapshot) return@collect
                backendSnapshot = next
                reload()
            }
        }
    }

    private fun reload() {
        generation += 1L
        cancelRequest()

        if (cardRef.backendId != binding.context.backendId || cardRef.backendId != backend.id) {
            _uiState.value = CardDetailsUiState.Error(
                AnkiError.InvalidRequest(detail = "read_context_backend_mismatch"), binding.context
            )
            return
        }
        when (val availability = backendSnapshot.availability) {
            AnkiAvailability.Checking -> {
                _uiState.value = CardDetailsUiState.Loading
                return
            }
            is AnkiAvailability.Ready -> Unit
            else -> {
                _uiState.value = CardDetailsUiState.Unavailable(availability, binding.context)
                return
            }
        }

        val bindingError = readAccess.validateBinding(binding)
        if (bindingError != null) {
            _uiState.value = CardDetailsUiState.Error(bindingError, binding.context)
            return
        }
        val resourceError = readAccess.validateResource(binding, cardRef.backendId, cardRef.collectionKey)
        if (resourceError != null) {
            _uiState.value = CardDetailsUiState.Error(resourceError, binding.context)
            return
        }

        if (!backendSnapshot.cardDetailsSupported) {
            _uiState.value = CardDetailsUiState.Error(
                AnkiError.UnsupportedAction(action = "card_details"), binding.context
            )
            return
        }

        _uiState.value = CardDetailsUiState.Loading
        val token = RequestToken(
            binding = binding,
            identity = AnkiReadRequestIdentity(
                context = binding.context,
                queryGeneration = 0L,
                requestGeneration = generation
            ),
            cardRef = cardRef,
            noteEditingOffered = backendSnapshot.noteEditingOffered
        )
        requestJob = viewModelScope.launch {
            val result = readAccess.read(
                binding = token.binding,
                requiredCapabilities = setOf(AnkiRequiredCapability.CARD_DETAILS),
                operation = { it.getCardDetails(token.cardRef) },
                validateResult = { details ->
                    listOfNotNull(
                        details.cardRef.let { it.backendId to it.collectionKey },
                        details.noteRef?.let { it.backendId to it.collectionKey },
                        details.deckRef?.let { it.backendId to it.collectionKey },
                        details.originalDeckRef?.let { it.backendId to it.collectionKey }
                    ).firstNotNullOfOrNull { (backendId, collectionKey) ->
                        readAccess.validateResource(token.binding, backendId, collectionKey)
                    }
                }
            )
            if (!isCurrent(token)) return@launch
            when (result) {
                is AnkiResult.Failure -> _uiState.value = CardDetailsUiState.Error(result.error, binding.context)
                is AnkiResult.Success -> {
                    if (!binding.isValid) {
                        _uiState.value = CardDetailsUiState.Error(
                            AnkiError.InvalidRequest("read_context_invalidated"), binding.context
                        )
                        return@launch
                    }
                    if (result.value.cardRef.backendId != token.backendId ||
                        result.value.cardRef.collectionKey != token.collectionKey ||
                        !AnkiCardHydration.identityMatches(token.cardRef, result.value.cardRef)
                    ) {
                        _uiState.value = CardDetailsUiState.Error(
                            AnkiError.StaleCardReference(
                                card = token.cardRef,
                                detail = "card_details_identity_mismatch"
                            ), binding.context
                        )
                        return@launch
                    }
                    _uiState.value = CardDetailsUiState.Ready(
                        details = CardDetailsMapper.map(
                            details = result.value,
                            canOpenNoteEditor = token.noteEditingOffered
                        ),
                        readContext = binding.context
                    )
                }
            }
        }
    }

    private fun isCurrent(token: RequestToken): Boolean =
        generation == token.generation && binding === token.binding &&
            token.identity.context == binding.context && backend === token.backend &&
            backend.id == token.backendId && cardRef == token.cardRef &&
            cardRef.collectionKey == token.collectionKey

    private fun cancelRequest() {
        requestJob?.cancel()
        requestJob = null
    }

    private fun snapshot(target: AnkiBackend): BackendSnapshot {
        val capabilities = target.capabilities.value
        return BackendSnapshot(
            availability = target.availability.value,
            cardDetailsSupported = capabilities.cardDetails,
            noteEditingOffered = capabilities.editNoteFields ||
                capabilities.editNoteTags ||
                capabilities.changeCardDeck
        )
    }
}
