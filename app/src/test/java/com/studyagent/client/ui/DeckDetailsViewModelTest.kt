package com.studyagent.client.ui

import androidx.lifecycle.ViewModelStore
import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiBackendSelector
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckCounts
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.core.study.AnkiStudyRequest
import com.studyagent.client.data.anki.AnkiLibraryRepository
import com.studyagent.client.data.repository.AnkiLocalStudyStarter
import com.studyagent.client.ui.screens.library.DeckDetailsEvent
import com.studyagent.client.ui.screens.library.DeckDetailsUiState
import com.studyagent.client.ui.screens.library.DeckDetailsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeckDetailsViewModelTest {
    private val viewModelStores = mutableListOf<ViewModelStore>()
    private val backendId = AnkiBackendId.AnkiDroidLocal
    private val capabilities = AnkiCapabilities(
        review = true,
        scheduledReview = true,
        deckListing = true,
        deckCounts = true
    )

    private fun deck(id: String, name: String, counts: AnkiDeckCounts? = null) =
        AnkiDeck(AnkiDeckRef(backendId, id), name, counts = counts)

    @Test
    fun `details are resolved by exact deck ID even for duplicate names`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val first = deck("101", "Medicine::Cardiology", AnkiDeckCounts(new = 1))
            val second = deck("202", "Medicine::Cardiology", AnkiDeckCounts(new = 8))
            val backend = FakeAnkiBackend(
                id = backendId,
                decks = listOf(first, second),
                initialCapabilities = capabilities,
                initialAvailability = AnkiAvailability.Ready(capabilities)
            )
            val repository = AnkiLibraryRepository(backend)
            repository.refresh()
            val (viewModel, requests) = detailsViewModel(backend, repository, "202", backgroundScope)
            val state = viewModel.uiState.value as DeckDetailsUiState.Ready
            assertEquals("202", state.summary.deck.ref.deckId)
            assertEquals(8, state.summary.counts?.new)
            assertTrue(state.canStudy)
            assertEquals(1, backend.getDecksCalls)
            assertEquals(1, backend.getSelectedDeckCalls)
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `study start passes the stable deck ID into the normal start pipeline only`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val selected = deck("202", "Medicine::Cardiology", AnkiDeckCounts(new = 0, learning = 0, review = 0))
            val backend = FakeAnkiBackend(
                id = backendId,
                decks = listOf(deck("101", "Medicine::Cardiology"), selected),
                initialCapabilities = capabilities,
                initialAvailability = AnkiAvailability.Ready(capabilities)
            )
            val repository = AnkiLibraryRepository(backend)
            repository.refresh()
            val (viewModel, requests) = detailsViewModel(backend, repository, "202", backgroundScope)
            val started = async { viewModel.events.first() }

            viewModel.startStudy()
            advanceUntilIdle()

            assertEquals(DeckDetailsEvent.StudyStarted, started.await())
            val request = requests.single()
            assertEquals(backendId, request.deck.backendId)
            assertEquals("202", request.deck.deckId)
            assertEquals(AnkiBackendId.AnkiDroidLocal, request.deck.backendId)
            assertEquals(2, backend.getDecksCalls) // one Library listing + one live ID validation at start
            assertEquals(0, backend.nextCardCount) // the scheduler is first queried by StudySession
            assertEquals("library-test-session", request.studySessionId)
            assertFalse((viewModel.uiState.value as DeckDetailsUiState.Ready).isStartingStudy)
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `study is disabled when review capability is unsupported`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val noReview = capabilities.copy(review = false)
            val backend = FakeAnkiBackend(
                id = backendId,
                decks = listOf(deck("1", "Browse only")),
                initialCapabilities = noReview,
                initialAvailability = AnkiAvailability.Ready(noReview)
            )
            val repository = AnkiLibraryRepository(backend)
            repository.refresh()
            val (viewModel) = detailsViewModel(backend, repository, "1", backgroundScope)
            val state = viewModel.uiState.value as DeckDetailsUiState.Ready
            assertFalse(state.canStudy)
            assertEquals(1, backend.getDecksCalls)
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `details stop presenting cached data as browseable when listing capability is lost`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val backend = FakeAnkiBackend(
                id = backendId,
                decks = listOf(deck("1", "Cached deck")),
                initialCapabilities = capabilities,
                initialAvailability = AnkiAvailability.Ready(capabilities)
            )
            val repository = AnkiLibraryRepository(backend)
            repository.refresh()
            val (viewModel) = detailsViewModel(backend, repository, "1", backgroundScope)
            backend.setCapabilities(capabilities.copy(deckListing = false))
            advanceUntilIdle()

            val state = viewModel.uiState.value as DeckDetailsUiState.Error
            assertTrue(state.error is com.studyagent.client.core.anki.AnkiError.UnsupportedAction)
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `study is disabled rather than switching to a different ready backend`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val browserId = AnkiBackendId.Fake("browser-only")
            val browserBackend = FakeAnkiBackend(
                id = browserId,
                decks = listOf(AnkiDeck(AnkiDeckRef(browserId, "1"), "Browser deck")),
                initialCapabilities = capabilities,
                initialAvailability = AnkiAvailability.Ready(capabilities)
            )
            val localBackend = FakeAnkiBackend(
                id = AnkiBackendId.AnkiDroidLocal,
                decks = listOf(deck("1", "Different local deck")),
                initialCapabilities = capabilities,
                initialAvailability = AnkiAvailability.Ready(capabilities)
            )
            val repository = AnkiLibraryRepository(browserBackend)
            repository.refresh()
            val registry = AnkiBackendRegistry(listOf(localBackend))
            val starter = AnkiLocalStudyStarter(
                registry = registry,
                selector = AnkiBackendSelector(registry),
                scope = backgroundScope,
                dispatch = {}
            )
            val viewModel = DeckDetailsViewModel(
                deckId = "1",
                backend = browserBackend,
                libraryRepository = repository,
                studyStarter = starter,
                studyState = MutableStateFlow(StudyState.Idle)
            )
            ViewModelStore().also { store ->
                store.put("backend-guard-test", viewModel)
                viewModelStores += store
            }
            val state = viewModel.uiState.value as DeckDetailsUiState.Ready
            assertFalse(state.canStudy)
            assertEquals(browserId, state.summary.deck.ref.backendId)
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `details refuse a snapshot from a different backend identity`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val backendAId = AnkiBackendId.Fake("source-a")
            val backendBId = AnkiBackendId.Fake("source-b")
            val aCaps = AnkiCapabilities(deckListing = true)
            val backendA = FakeAnkiBackend(
                id = backendAId,
                decks = listOf(AnkiDeck(AnkiDeckRef(backendAId, "1"), "Backend A")),
                initialCapabilities = aCaps,
                initialAvailability = AnkiAvailability.Ready(aCaps)
            )
            val bCaps = AnkiCapabilities(deckListing = true)
            val backendB = FakeAnkiBackend(
                id = backendBId,
                decks = listOf(AnkiDeck(AnkiDeckRef(backendBId, "1"), "Backend B")),
                initialCapabilities = bCaps,
                initialAvailability = AnkiAvailability.Ready(bCaps)
            )
            val repositoryA = AnkiLibraryRepository(backendA)
            repositoryA.refresh()
            val (details) = detailsViewModel(backendB, repositoryA, "1", backgroundScope)
            val state = details.uiState.value as DeckDetailsUiState.Error
            assertTrue(state.error is com.studyagent.client.core.anki.AnkiError.DeckNotFound)
            assertEquals(backendBId, (state.error as com.studyagent.client.core.anki.AnkiError.DeckNotFound).deck?.backendId)
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `stale list response cannot replace the target of another deck details owner`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val backend = FakeAnkiBackend(
                id = backendId,
                decks = listOf(
                    deck("a", "Same visible name", AnkiDeckCounts(new = 1)),
                    deck("b", "Same visible name", AnkiDeckCounts(new = 2))
                ),
                initialCapabilities = capabilities,
                initialAvailability = AnkiAvailability.Ready(capabilities)
            )
            val repository = AnkiLibraryRepository(backend)
            repository.refresh()
            val (detailsA) = detailsViewModel(backend, repository, "a", backgroundScope)
            val (detailsB) = detailsViewModel(backend, repository, "b", backgroundScope)

            val stateA = detailsA.uiState.value as DeckDetailsUiState.Ready
            val stateB = detailsB.uiState.value as DeckDetailsUiState.Ready
            assertEquals("a", stateA.summary.deck.ref.deckId)
            assertEquals("b", stateB.summary.deck.ref.deckId)
            assertEquals(1, stateA.summary.counts?.new)
            assertEquals(2, stateB.summary.counts?.new)
        } finally {
            clearViewModels()
            Dispatchers.resetMain()
        }
    }

    private fun detailsViewModel(
        backend: FakeAnkiBackend,
        repository: AnkiLibraryRepository,
        deckId: String,
        scope: CoroutineScope
    ): Pair<DeckDetailsViewModel, MutableList<AnkiStudyRequest>> {
        val requests = mutableListOf<AnkiStudyRequest>()
        val registry = AnkiBackendRegistry(listOf(backend))
        val starter = AnkiLocalStudyStarter(
            registry = registry,
            selector = AnkiBackendSelector(registry),
            scope = scope,
            dispatch = { request -> requests.add(request) },
            studySessionIdFactory = { "library-test-session" }
        )
        val viewModel = DeckDetailsViewModel(
            deckId = deckId,
            backend = backend,
            libraryRepository = repository,
            studyStarter = starter,
            studyState = MutableStateFlow(StudyState.Idle)
        )
        val store = ViewModelStore()
        store.put("deck-details-test-$deckId", viewModel)
        viewModelStores += store
        return viewModel to requests
    }

    private fun clearViewModels() {
        viewModelStores.forEach { it.clear() }
        viewModelStores.clear()
    }
}
