package com.studyagent.client.ui

import androidx.lifecycle.ViewModelStore
import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.LibraryTestFixtures
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckCounts
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.data.anki.AnkiLibraryRepository
import com.studyagent.client.ui.screens.library.LibraryUiState
import com.studyagent.client.ui.screens.library.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val viewModelStores = mutableListOf<ViewModelStore>()
    private val backendId = AnkiBackendId.AnkiDroidLocal

    private fun capabilities() = AnkiCapabilities(
        review = true,
        scheduledReview = true,
        deckListing = true,
        deckCounts = true
    )

    private fun deck(id: String, name: String, counts: AnkiDeckCounts? = null) =
        AnkiDeck(AnkiDeckRef(backendId, id), name, counts = counts)

    private fun newViewModel(
        decks: List<AnkiDeck>,
        capabilities: AnkiCapabilities = capabilities(),
        availability: AnkiAvailability = AnkiAvailability.Ready(capabilities),
        decksError: AnkiError? = null,
        latencyMs: Long = 0
    ): Triple<LibraryViewModel, FakeAnkiBackend, AnkiLibraryRepository> {
        val backend = FakeAnkiBackend(
            id = backendId,
            decks = decks,
            initialCapabilities = capabilities,
            initialAvailability = availability,
            decksError = decksError,
            latencyMs = latencyMs
        )
        val repository = AnkiLibraryRepository(backend)
        return Triple(track(LibraryViewModel(backend, repository)), backend, repository)
    }

    // `UnconfinedTestDispatcher(..)` is a factory function, not a type: the created dispatcher is a
// `TestDispatcher`, which is what `Dispatchers.setMain` accepts.
private fun installMain(dispatcher: TestDispatcher) = Dispatchers.setMain(dispatcher)

    private fun track(viewModel: LibraryViewModel): LibraryViewModel {
        val store = ViewModelStore()
        store.put("library-test", viewModel)
        viewModelStores += store
        return viewModel
    }

    private fun clearViewModels() {
        viewModelStores.forEach { it.clear() }
        viewModelStores.clear()
    }

    @Test
    fun `ready library maps backend decks and summaries once`() = runTest {
        installMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val (viewModel, backend) = newViewModel(
                LibraryTestFixtures.threeDecks(backendId)
            )
            advanceUntilIdle()
            val state = viewModel.uiState.value as LibraryUiState.Ready
            assertEquals(3, state.decks.size)
            assertEquals(backendId, state.backend)
            assertEquals(1, backend.getDecksCalls)
            assertEquals(1, backend.getSelectedDeckCalls)
            assertEquals(listOf("Language::العربية English", "Medicine", "Medicine::Cardiology"),
                state.decks.map { it.deck.name }.sorted())
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `ready empty collection is Empty rather than unavailable or error`() = runTest {
        installMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val (viewModel, backend) = newViewModel(emptyList())
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is LibraryUiState.Empty)
            assertEquals(1, backend.getDecksCalls)
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `failed refresh preserves empty snapshot but marks it stale`() = runTest {
        installMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val (viewModel, backend) = newViewModel(emptyList())
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is LibraryUiState.Empty)

            backend.decksError = AnkiError.QueryFailure("refresh_failed")
            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.uiState.value as LibraryUiState.Empty
            assertTrue(state.staleError is AnkiError.QueryFailure)
            assertEquals(2, backend.getDecksCalls)
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `permission failure is unavailable and does not query decks`() = runTest {
        installMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val (viewModel, backend) = newViewModel(
                decks = listOf(deck("1", "Deck")),
                availability = AnkiAvailability.PermissionRequired()
            )
            advanceUntilIdle()
            val state = viewModel.uiState.value as LibraryUiState.Unavailable
            assertTrue(state.reason is AnkiAvailability.PermissionRequired)
            assertEquals(0, backend.getDecksCalls)
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `collection unavailable failure is distinct from an empty library`() = runTest {
        installMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val (viewModel) = newViewModel(
                decks = emptyList(),
                decksError = AnkiError.CollectionUnavailable()
            )
            advanceUntilIdle()
            val state = viewModel.uiState.value as LibraryUiState.Unavailable
            assertTrue(state.reason is AnkiAvailability.TemporarilyUnavailable)
            assertEquals("collection_unavailable", (state.reason as AnkiAvailability.TemporarilyUnavailable).reason)
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `read failure is Error and is not converted to empty`() = runTest {
        installMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val (viewModel) = newViewModel(
                decks = emptyList(),
                decksError = AnkiError.QueryFailure("test_failure")
            )
            advanceUntilIdle()
            val state = viewModel.uiState.value as LibraryUiState.Error
            assertTrue(state.error is AnkiError.QueryFailure)
            assertFalse(viewModel.uiState.value is LibraryUiState.Empty)
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `unknown counts remain unavailable while actual zero remains zero`() = runTest {
        installMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val (viewModel) = newViewModel(
                listOf(
                    deck("1", "Unknown"),
                    deck("2", "Zero", AnkiDeckCounts(new = 0, learning = 0, review = 0))
                )
            )
            advanceUntilIdle()
            val decks = (viewModel.uiState.value as LibraryUiState.Ready).decks.associateBy { it.deck.ref.deckId }
            assertNull(decks.getValue("1").summary.counts)
            assertEquals(0, decks.getValue("2").summary.counts?.new)
            assertEquals(0, decks.getValue("2").summary.counts?.review)
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `local search filters the loaded hierarchy without another backend call`() = runTest {
        installMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val (viewModel, backend) = newViewModel(
                listOf(deck("1", "Medicine"), deck("2", "Medicine::Neurology"), deck("3", "Language::Arabic"))
            )
            advanceUntilIdle()
            viewModel.setSearchQuery("Neurology")
            val state = viewModel.uiState.value as LibraryUiState.Ready
            assertEquals(3, state.decks.size)
            assertEquals("Medicine::Neurology", state.tree.single().fullName.let { node ->
                if (node.contains("Neurology")) node else state.tree.single().children.single().fullName
            })
            assertEquals(1, backend.getDecksCalls)
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `large collection is projected with a bounded number of reads`() = runTest {
        installMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fixture = LibraryTestFixtures.largeCollection(backendId)
            val (viewModel, backend) = newViewModel(fixture)
            advanceUntilIdle()
            val state = viewModel.uiState.value as LibraryUiState.Ready
            assertEquals(420, state.decks.size)
            assertTrue(state.tree.isNotEmpty())
            assertEquals(1, backend.getDecksCalls)
            assertEquals(1, backend.getSelectedDeckCalls)
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a snapshot from a different backend identity is never rendered`() = runTest {
        installMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val foreignId = AnkiBackendId.Fake("foreign-backend")
            val foreignCaps = AnkiCapabilities(deckListing = true)
            val foreignBackend = FakeAnkiBackend(
                id = foreignId,
                decks = listOf(AnkiDeck(AnkiDeckRef(foreignId, "1"), "Foreign deck")),
                initialCapabilities = foreignCaps,
                initialAvailability = AnkiAvailability.Ready(foreignCaps)
            )
            val repository = AnkiLibraryRepository(foreignBackend)
            repository.refresh() // cached snapshot is scoped to the foreign backend

            val ownBackend = FakeAnkiBackend(
                id = backendId,
                decks = emptyList(),
                initialCapabilities = capabilities(),
                initialAvailability = AnkiAvailability.Ready(capabilities())
            )
            val viewModel = track(LibraryViewModel(ownBackend, repository))
            val observed = mutableListOf<LibraryUiState>()
            backgroundScope.launch { viewModel.uiState.collect { observed += it } }
            advanceUntilIdle()

            // No emitted state may ever present foreign data as this backend's library; the
            // identity guard fails closed instead of mixing backends (INV-14-12 / audit 7).
            assertTrue(observed.none {
                it is LibraryUiState.Ready && it.decks.any { deck -> deck.deck.ref.backendId == foreignId }
            })
            val finalState = viewModel.uiState.value
            assertTrue(finalState is LibraryUiState.Error)
            assertTrue((finalState as LibraryUiState.Error).error is AnkiError.BackendUnavailable)
            assertEquals(backendId, finalState.backend)
            assertEquals(0, ownBackend.getDecksCalls) // the mismatch is caught before any read
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `rapid refresh taps use single flight`() = runTest {
        installMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val (viewModel, backend) = newViewModel(
                decks = listOf(deck("1", "Deck")),
                latencyMs = 40
            )
            advanceUntilIdle()
            assertEquals(1, backend.getDecksCalls)
            viewModel.refresh()
            viewModel.refresh()
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(2, backend.getDecksCalls)
            assertTrue(viewModel.uiState.value is LibraryUiState.Ready)
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }
}
