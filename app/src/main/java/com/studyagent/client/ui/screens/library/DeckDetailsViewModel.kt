package com.studyagent.client.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiDeckSummary
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.LibraryDataState
import com.studyagent.client.core.anki.LibraryFreshness
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.data.anki.AnkiLibraryRepository
import com.studyagent.client.data.repository.AnkiLocalStudyStarter
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * A deck-details owner is bound to one backend instance and the stable deck ID from navigation.
 * Its result is always matched by (backendId, deckId); a late list refresh cannot replace the
 * selected deck with a similarly named deck.
 */
class DeckDetailsViewModel(
    private val deckId: String,
    private val backend: AnkiBackend,
    private val libraryRepository: AnkiLibraryRepository,
    private val studyStarter: AnkiLocalStudyStarter,
    private val studyState: StateFlow<StudyState>
) : ViewModel() {
    private data class Inputs(
        val availability: AnkiAvailability,
        val capabilities: AnkiCapabilities,
        val dataState: LibraryDataState,
        val localStudyBackendId: AnkiBackendId?,
        val studyState: StudyState
    )

    private var inputs = Inputs(
        availability = backend.availability.value,
        capabilities = backend.capabilities.value,
        dataState = libraryRepository.state.value,
        localStudyBackendId = studyStarter.localStudyBackendId.value,
        studyState = studyState.value
    )
    private var operationError: AnkiError? = null
    private var backendUnavailableSinceLastRead: Boolean = false
    private var isRefreshing = false
    private var isStartingStudy = false
    private var studyStartError: AnkiError? = null
    private val refreshMutex = Mutex()
    private val eventChannel = Channel<DeckDetailsEvent>(Channel.BUFFERED)

    private val _uiState = MutableStateFlow<DeckDetailsUiState>(DeckDetailsUiState.Loading)
    val uiState: StateFlow<DeckDetailsUiState> = _uiState.asStateFlow()
    val events = eventChannel.receiveAsFlow()

    init {
        require(deckId.isNotBlank()) { "Deck details require a stable deck ID" }
        publish()
        viewModelScope.launch {
            combine(
                backend.availability,
                backend.capabilities,
                libraryRepository.state,
                studyStarter.localStudyBackendId,
                studyState
            ) { availability, capabilities, dataState, localStudyBackendId, studyState ->
                Inputs(availability, capabilities, dataState, localStudyBackendId, studyState)
            }.collect { next ->
                val becameReady = inputs.availability !is AnkiAvailability.Ready &&
                    next.availability is AnkiAvailability.Ready
                if (next.availability !is AnkiAvailability.Ready &&
                    next.availability != AnkiAvailability.Checking
                ) {
                    backendUnavailableSinceLastRead = true
                }
                inputs = next
                publish()
                val snapshot = next.dataState.snapshot
                if (becameReady && !isRefreshing &&
                    (backendUnavailableSinceLastRead || snapshot == null || snapshot.freshness != LibraryFreshness.FRESH)
                ) {
                    refresh()
                }
            }
        }
        if (libraryRepository.state.value !is LibraryDataState.Ready) refresh()
    }

    /** Safe read retry. Summary reads are batched with the Library deck listing. */
    fun refresh() {
        viewModelScope.launch {
            if (!refreshMutex.tryLock()) return@launch
            isRefreshing = true
            operationError = null
            publish()
            try {
                backend.refreshAvailability()
                val now = backend.availability.value
                val caps = backend.capabilities.value
                inputs = inputs.copy(availability = now, capabilities = caps)
                if (now is AnkiAvailability.Ready && caps.deckListing) {
                    inputs = inputs.copy(dataState = libraryRepository.refresh())
                    if ((inputs.dataState as? LibraryDataState.Ready)?.snapshot?.freshness == LibraryFreshness.FRESH) {
                        backendUnavailableSinceLastRead = false
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                operationError = AnkiError.Unknown(cause = failure::class.java.simpleName)
            } finally {
                inputs = inputs.copy(dataState = libraryRepository.state.value)
                isRefreshing = false
                publish()
                refreshMutex.unlock()
            }
        }
    }

    /**
     * Delegates to the existing local Anki session-start site. It validates this deck ID against the
     * live backend, freezes session capabilities there, and dispatches into the Study state machine;
     * it does not ask the scheduler for a card here.
     */
    fun startStudy() {
        val ready = _uiState.value as? DeckDetailsUiState.Ready ?: return
        if (!ready.canStudy || ready.isStartingStudy) return
        isStartingStudy = true
        studyStartError = null
        publish()
        viewModelScope.launch {
            try {
                when (val result = studyStarter.start(AnkiLocalStudyStarter.Options(deckId = deckId))) {
                    is AnkiLocalStudyStarter.Result.Started -> {
                        isStartingStudy = false
                        publish()
                        eventChannel.send(DeckDetailsEvent.StudyStarted)
                    }
                    is AnkiLocalStudyStarter.Result.Refused -> {
                        isStartingStudy = false
                        studyStartError = result.error ?: when (result.reason) {
                            AnkiLocalStudyStarter.REASON_DECK_NOT_FOUND ->
                                AnkiError.DeckNotFound(AnkiDeckRef(backend.id, deckId))
                            AnkiLocalStudyStarter.REASON_NO_READY_BACKEND,
                            AnkiLocalStudyStarter.REASON_BACKEND_NOT_READY -> AnkiError.BackendUnavailable()
                            else -> AnkiError.Unknown(cause = result.reason)
                        }
                        publish()
                    }
                }
            } catch (cancelled: CancellationException) {
                isStartingStudy = false
                publish()
                throw cancelled
            } catch (failure: Throwable) {
                isStartingStudy = false
                studyStartError = AnkiError.Unknown(cause = failure::class.java.simpleName)
                publish()
            }
        }
    }

    private fun publish() {
        _uiState.value = project(
            backendId = backend.id,
            deckId = deckId,
            inputs = inputs,
            operationError = operationError,
            isRefreshing = isRefreshing,
            isStartingStudy = isStartingStudy,
            studyStartError = studyStartError
        )
    }

    private fun project(
        backendId: AnkiBackendId,
        deckId: String,
        inputs: Inputs,
        operationError: AnkiError?,
        isRefreshing: Boolean,
        isStartingStudy: Boolean,
        studyStartError: AnkiError?
    ): DeckDetailsUiState {
        if (inputs.availability is AnkiAvailability.Ready && !inputs.capabilities.deckListing) {
            return DeckDetailsUiState.Error(
                backendId,
                AnkiError.UnsupportedAction("deckListing"),
                isRetrying = isRefreshing
            )
        }
        val snapshot = inputs.dataState.snapshot?.takeIf { it.backendId == backendId }
        val selectedDeck = snapshot?.decks?.firstOrNull { deck ->
            deck.ref.backendId == backendId && deck.ref.deckId == deckId
        }
        if (selectedDeck != null && snapshot != null) {
            val summary = AnkiDeckSummary(
                deck = selectedDeck,
                counts = selectedDeck.counts,
                isSelectedByBackend = snapshot.isSelectedByBackend(selectedDeck.ref),
                isFiltered = selectedDeck.isFiltered
            )
            val activeSession = !inputs.studyState.canStartAnotherSession()
            val backendReady = inputs.availability is AnkiAvailability.Ready
            val canStudy = backendReady &&
                inputs.capabilities.deckListing &&
                inputs.capabilities.review &&
                inputs.capabilities.scheduledReview &&
                inputs.localStudyBackendId == backendId &&
                !activeSession &&
                !isStartingStudy
            return DeckDetailsUiState.Ready(
                summary = summary,
                backend = backendId,
                availability = inputs.availability,
                capabilities = inputs.capabilities,
                canStudy = canStudy,
                activeSessionPreventsStudy = activeSession,
                isSummaryStale = snapshot.freshness != LibraryFreshness.FRESH || !backendReady || operationError != null,
                isRefreshing = isRefreshing || inputs.dataState.isRefreshing,
                isStartingStudy = isStartingStudy,
                readError = operationError,
                studyStartError = studyStartError
            )
        }

        val availability = inputs.availability
        if (availability != AnkiAvailability.Checking && availability !is AnkiAvailability.Ready) {
            return DeckDetailsUiState.Unavailable(backendId, availability, isRetrying = isRefreshing)
        }
        if (operationError != null && snapshot == null) {
            operationError.asAvailability()?.let {
                return DeckDetailsUiState.Unavailable(backendId, it, isRetrying = isRefreshing)
            }
            return DeckDetailsUiState.Error(backendId, operationError, isRetrying = isRefreshing)
        }

        return when (val data = inputs.dataState) {
            LibraryDataState.Idle, LibraryDataState.Loading -> DeckDetailsUiState.Loading
            is LibraryDataState.Failed -> data.error.asAvailability()?.let {
                DeckDetailsUiState.Unavailable(backendId, it, isRetrying = isRefreshing)
            } ?: DeckDetailsUiState.Error(backendId, data.error, isRetrying = isRefreshing)
            is LibraryDataState.Ready -> {
                if (availability == AnkiAvailability.Checking) {
                    DeckDetailsUiState.Loading
                } else {
                    DeckDetailsUiState.Error(
                        backendId,
                        AnkiError.DeckNotFound(AnkiDeckRef(backendId, deckId)),
                        isRetrying = isRefreshing
                    )
                }
            }
        }
    }

    private fun StudyState.canStartAnotherSession(): Boolean =
        this is StudyState.Idle || this is StudyState.SessionFinished || this is StudyState.Error

    private fun AnkiError.asAvailability(): AnkiAvailability? = when (this) {
        is AnkiError.PermissionRequired -> AnkiAvailability.PermissionRequired()
        is AnkiError.ProviderUnavailable -> AnkiAvailability.ProviderUnavailable(detail)
        is AnkiError.UnsupportedApi -> AnkiAvailability.Unsupported("unsupported_api")
        is AnkiError.CollectionUnavailable -> AnkiAvailability.TemporarilyUnavailable("collection_unavailable")
        is AnkiError.BackendUnavailable -> AnkiAvailability.TemporarilyUnavailable()
        else -> null
    }
}
