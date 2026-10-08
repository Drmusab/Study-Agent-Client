package com.studyagent.client.ui.screens.carddetails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardHydration
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
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
    initialBackend: AnkiBackend,
    initialCardRef: AnkiCardRef
) : ViewModel() {
    private data class BackendSnapshot(
        val availability: AnkiAvailability,
        val cardDetailsSupported: Boolean
    )

    private data class RequestToken(
        val backend: AnkiBackend,
        val backendId: AnkiBackendId,
        val collectionKey: String?,
        val cardRef: AnkiCardRef,
        val generation: Long
    )

    private var backend: AnkiBackend = initialBackend
    private var cardRef: AnkiCardRef = initialCardRef
    private var backendSnapshot = snapshot(backend)
    private var generation = 0L
    private var backendObservation: Job? = null
    private var requestJob: Job? = null

    private val _uiState = MutableStateFlow<CardDetailsUiState>(CardDetailsUiState.Loading)
    val uiState: StateFlow<CardDetailsUiState> = _uiState.asStateFlow()

    init {
        observeBackend(initialBackend)
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

    /**
     * Backend changes never reinterpret an old reference. A different [AnkiBackendId] is refused
     * before any read; the new backend can serve details only after navigation supplies a
     * reference qualified for its identity (INV-16-15).
     */
    fun switchBackend(next: AnkiBackend) {
        if (next === backend) return
        cancelRequest()
        backendObservation?.cancel()
        backend = next
        backendSnapshot = snapshot(next)
        generation += 1
        observeBackend(next)
        when {
            next.id != cardRef.backendId -> _uiState.value = CardDetailsUiState.Error(
                AnkiError.InvalidRequest(detail = "card_ref_foreign_backend")
            )
            // An unknown collection key is not a wildcard. If the backend instance/context was
            // replaced and this public backend cannot identify its collection, close the old
            // details context rather than reinterpret a possibly-colliding numeric card ID.
            cardRef.collectionKey == null -> _uiState.value = CardDetailsUiState.Error(
                AnkiError.InvalidRequest(detail = "card_details_collection_identity_unavailable")
            )
            else -> reload()
        }
    }

    private fun observeBackend(observed: AnkiBackend) {
        backendObservation = viewModelScope.launch {
            combine(observed.availability, observed.capabilities) { availability, capabilities ->
                BackendSnapshot(availability, capabilities.cardDetails)
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

        if (cardRef.backendId != backend.id) {
            _uiState.value = CardDetailsUiState.Error(
                AnkiError.InvalidRequest(detail = "card_ref_foreign_backend")
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
                _uiState.value = CardDetailsUiState.Unavailable(availability)
                return
            }
        }

        if (!backendSnapshot.cardDetailsSupported) {
            _uiState.value = CardDetailsUiState.Error(
                AnkiError.UnsupportedAction(action = "card_details")
            )
            return
        }

        _uiState.value = CardDetailsUiState.Loading
        val token = RequestToken(
            backend = backend,
            backendId = backend.id,
            collectionKey = cardRef.collectionKey,
            cardRef = cardRef,
            generation = generation
        )
        requestJob = viewModelScope.launch {
            val result = try {
                token.backend.getCardDetails(token.cardRef)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                AnkiResult.Failure(AnkiError.Unknown(cause = failure::class.java.simpleName))
            }
            if (!isCurrent(token)) return@launch
            when (result) {
                is AnkiResult.Failure -> _uiState.value = CardDetailsUiState.Error(result.error)
                is AnkiResult.Success -> {
                    if (result.value.cardRef.backendId != token.backendId ||
                        result.value.cardRef.collectionKey != token.collectionKey ||
                        !AnkiCardHydration.identityMatches(token.cardRef, result.value.cardRef)
                    ) {
                        _uiState.value = CardDetailsUiState.Error(
                            AnkiError.StaleCardReference(
                                card = token.cardRef,
                                detail = "card_details_identity_mismatch"
                            )
                        )
                        return@launch
                    }
                    _uiState.value = CardDetailsUiState.Ready(CardDetailsMapper.map(result.value))
                }
            }
        }
    }

    private fun isCurrent(token: RequestToken): Boolean =
        generation == token.generation && backend === token.backend && backend.id == token.backendId &&
            cardRef == token.cardRef && cardRef.collectionKey == token.collectionKey

    private fun cancelRequest() {
        requestJob?.cancel()
        requestJob = null
    }

    private fun snapshot(target: AnkiBackend) = BackendSnapshot(
        availability = target.availability.value,
        cardDetailsSupported = target.capabilities.value.cardDetails
    )
}
