package com.studyagent.client.ui

import androidx.lifecycle.ViewModelStore
import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.*
import com.studyagent.client.ui.screens.carddetails.CardDetailsUiState
import com.studyagent.client.ui.screens.carddetails.CardDetailsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class CardDetailsViewModelTest {
    private val backendId = AnkiBackendId.Fake("card-details-vm")
    private val noteRef = AnkiNoteRef(backendId, "note-7", "collection-A")
    private val cardA = AnkiCardRef(backendId, "card-A", noteRef.noteId, 0, "collection-A")
    private val cardB = AnkiCardRef(backendId, "card-B", noteRef.noteId, 1, "collection-A")
    private val deckRef = AnkiDeckRef(backendId, "deck-1", "collection-A")
    private val viewModelStores = mutableListOf<ViewModelStore>()

    @After fun tearDown() {
        viewModelStores.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    private fun details(ref: AnkiCardRef, reps: Int? = 0) = AnkiCardDetails(
        cardRef = ref,
        noteRef = noteRef,
        cardOrd = ref.cardOrd,
        deckRef = deckRef,
        deckName = "Deck",
        noteTypeId = "model-1",
        noteTypeName = "Basic",
        templateName = "Card ${ref.cardOrd}",
        questionHtml = "<b>Q ${ref.cardOrd}</b>",
        answerHtml = "A ${ref.cardOrd}",
        questionText = "Q ${ref.cardOrd}",
        answerText = "A ${ref.cardOrd}",
        pureAnswerText = "A ${ref.cardOrd}",
        fields = listOf(AnkiNoteField("Front", "Q ${ref.cardOrd}", 0)),
        tags = emptyList(),
        flag = null,
        cardType = AnkiCardType.NEW,
        queueState = AnkiCardQueueState.NEW,
        scheduling = reps?.let { AnkiSchedulingInfo(reps = it, lapses = 0) },
        originalDeckRef = null,
        noteCreatedEpochSeconds = null,
        noteModifiedEpochSeconds = null
    )

    private fun vm(backend: AnkiBackend, ref: AnkiCardRef): CardDetailsViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(null))
        return CardDetailsViewModel(backend, ref).also { model ->
            ViewModelStore().also { store ->
                store.put("details", model)
                viewModelStores += store
            }
        }
    }

    @Test
    fun `normal exact card loads and shows explicit zero metadata`() = runTest {
        val backend = FakeAnkiBackend(
            id = backendId,
            cardDetails = listOf(details(cardA, reps = 0))
        )
        val viewModel = vm(backend, cardA)
        val state = viewModel.uiState.value as CardDetailsUiState.Ready
        assertEquals(cardA, state.details.cardRef)
        assertEquals("note-7", state.details.noteId)
        assertEquals(0, state.details.schedulingRows.first { it.label == "Reviews" }.value?.toInt())
        assertEquals(1, backend.cardDetailsCalls)
        assertEquals(listOf(cardA), backend.requestedCardDetailsRefs)
    }

    @Test
    fun `missing card remains a typed NotFound error`() = runTest {
        val backend = FakeAnkiBackend(id = backendId, cardDetails = emptyList())
        val viewModel = vm(backend, cardA)
        val error = (viewModel.uiState.value as CardDetailsUiState.Error).error
        assertTrue(error is AnkiError.CardNotFound)
    }

    @Test
    fun `backend unavailable is represented by canonical availability state`() = runTest {
        val backend = FakeAnkiBackend(
            id = backendId,
            cardDetails = listOf(details(cardA)),
            initialAvailability = AnkiAvailability.AgentDisconnected
        )
        val viewModel = vm(backend, cardA)
        assertEquals(AnkiAvailability.AgentDisconnected,
            (viewModel.uiState.value as CardDetailsUiState.Unavailable).reason)
        assertEquals(0, backend.cardDetailsCalls)
    }

    @Test
    fun `refresh re-fetches current details and does not keep the previous snapshot`() = runTest {
        val backend = FakeAnkiBackend(id = backendId, cardDetails = listOf(details(cardA, reps = 0)))
        val viewModel = vm(backend, cardA)
        assertEquals("0", (viewModel.uiState.value as CardDetailsUiState.Ready)
            .details.schedulingRows.first { it.label == "Reviews" }.value)
        assertTrue(backend.replaceCardDetails(details(cardA, reps = 3)))
        viewModel.refresh()
        val ready = viewModel.uiState.value as CardDetailsUiState.Ready
        assertEquals("3", ready.details.schedulingRows.first { it.label == "Reviews" }.value)
        assertEquals(2, backend.cardDetailsCalls)
    }

    @Test
    fun `late response for card A cannot overwrite card B`() = runTest {
        val base = FakeAnkiBackend(
            id = backendId,
            cardDetails = listOf(details(cardA), details(cardB))
        )
        val controlled = ControlledBackend(base)
        val viewModel = vm(controlled, cardA)
        assertTrue(controlled.pending.containsKey(cardA))

        viewModel.loadCard(cardB)
        assertTrue(controlled.pending.containsKey(cardB))

        controlled.complete(cardA, AnkiResult.Success(details(cardA)))
        controlled.complete(cardB, AnkiResult.Success(details(cardB)))
        val ready = viewModel.uiState.value as CardDetailsUiState.Ready
        assertEquals(cardB, ready.details.cardRef)
        assertFalse(ready.details.title.contains("0"))
    }

    @Test
    fun `backend switch cannot reinterpret the old card reference`() = runTest {
        val original = FakeAnkiBackend(id = backendId, cardDetails = listOf(details(cardA)))
        val otherId = AnkiBackendId.Fake("other-backend")
        val other = FakeAnkiBackend(id = otherId, cardDetails = listOf(detailsFor(otherId)))
        val viewModel = vm(original, cardA)
        viewModel.switchBackend(other)
        val error = (viewModel.uiState.value as CardDetailsUiState.Error).error
        assertTrue(error is AnkiError.InvalidRequest)
        assertEquals(0, other.cardDetailsCalls)
    }

    @Test
    fun `unsupported card details capability is not inferred from backend readiness`() = runTest {
        val backend = FakeAnkiBackend(
            id = backendId,
            cardDetails = listOf(details(cardA)),
            initialCapabilities = FakeAnkiBackend.REVIEW_CAPABILITIES.copy(cardDetails = false)
        )
        val viewModel = vm(backend, cardA)
        val error = (viewModel.uiState.value as CardDetailsUiState.Error).error
        assertTrue(error is AnkiError.UnsupportedAction)
        assertEquals(0, backend.cardDetailsCalls)
    }

    @Test
    fun `backend context replacement with unknown collection identity is refused`() = runTest {
        val oldRef = AnkiCardRef(backendId, cardId = "card-A", noteId = noteRef.noteId, cardOrd = 0)
        val oldDetails = details(cardA).copy(cardRef = oldRef)
        val original = FakeAnkiBackend(id = backendId, cardDetails = listOf(oldDetails))
        val replacement = FakeAnkiBackend(id = backendId, cardDetails = listOf(oldDetails))
        val viewModel = vm(original, oldRef)
        viewModel.switchBackend(replacement)
        val error = (viewModel.uiState.value as CardDetailsUiState.Error).error
        assertEquals("card_details_collection_identity_unavailable", (error as AnkiError.InvalidRequest).detail)
        assertEquals(0, replacement.cardDetailsCalls)
    }

    @Test
    fun `two cards from the same note remain distinct by ref and ordinal`() = runTest {
        val backend = FakeAnkiBackend(id = backendId, cardDetails = listOf(details(cardA), details(cardB)))
        val vmA = vm(backend, cardA)
        val vmB = vm(backend, cardB)
        val a = (vmA.uiState.value as CardDetailsUiState.Ready).details
        val b = (vmB.uiState.value as CardDetailsUiState.Ready).details
        assertEquals(a.noteId, b.noteId)
        assertEquals(cardA, a.cardRef)
        assertEquals(cardB, b.cardRef)
        assertEquals(0, a.cardOrd)
        assertEquals(1, b.cardOrd)
    }

    @Test
    fun `corrupt card note relationship is not replaced with empty fields`() = runTest {
        val backend = FakeAnkiBackend(
            id = backendId,
            cardDetails = listOf(details(cardA)),
            initialCapabilities = FakeAnkiBackend.REVIEW_CAPABILITIES.copy(cardDetails = true)
        ).also { it.cardDetailsError = AnkiError.DataIntegrityFailure("card_note_identity_unreadable") }
        val viewModel = vm(backend, cardA)
        val error = (viewModel.uiState.value as CardDetailsUiState.Error).error
        assertEquals("card_note_identity_unreadable", (error as AnkiError.DataIntegrityFailure).detail)
    }

    private fun detailsFor(id: AnkiBackendId): AnkiCardDetails {
        val note = AnkiNoteRef(id, "note-7", "collection-A")
        val ref = AnkiCardRef(id, "card-A", note.noteId, 0, "collection-A")
        return AnkiCardDetails(
            cardRef = ref,
            noteRef = note,
            cardOrd = 0,
            deckRef = AnkiDeckRef(id, "deck-1", "collection-A"),
            deckName = "Deck",
            noteTypeId = "model-1",
            noteTypeName = "Basic",
            templateName = "Card 0",
            questionHtml = "Q",
            answerHtml = "A",
            questionText = "Q",
            answerText = "A",
            pureAnswerText = "A",
            fields = listOf(AnkiNoteField("Front", "Q", 0)),
            tags = emptyList(),
            flag = null,
            cardType = AnkiCardType.NEW,
            queueState = AnkiCardQueueState.NEW,
            scheduling = null,
            originalDeckRef = null,
            noteCreatedEpochSeconds = null,
            noteModifiedEpochSeconds = null
        )
    }

    /** Deliberately non-cancellable transport seam: stale-response guard, not cancellation, must win. */
    private class ControlledBackend(private val delegate: AnkiBackend) : AnkiBackend by delegate {
        val pending = linkedMapOf<AnkiCardRef, Continuation<AnkiResult<AnkiCardDetails>>>()

        override suspend fun getCardDetails(cardRef: AnkiCardRef): AnkiResult<AnkiCardDetails> =
            suspendCoroutine { continuation -> pending[cardRef] = continuation }

        fun complete(ref: AnkiCardRef, result: AnkiResult<AnkiCardDetails>) {
            pending.remove(ref)?.resume(result) ?: error("No pending details request for ${ref.stableKey}")
        }
    }
}
