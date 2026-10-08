package com.studyagent.client.ui

import androidx.lifecycle.ViewModelStore
import com.studyagent.client.anki.fake.CardBrowserTestFixtures
import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardListItem
import com.studyagent.client.core.anki.AnkiCardPage
import com.studyagent.client.core.anki.AnkiCardQuery
import com.studyagent.client.core.anki.AnkiCardQueueState
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiCardSort
import com.studyagent.client.core.anki.AnkiCardType
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.AnkiPageRequest
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.AnkiSchedulingInfo
import com.studyagent.client.core.anki.AnkiCardMetadata
import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.ui.screens.cardbrowser.CardBrowserEvent
import com.studyagent.client.ui.screens.cardbrowser.CardBrowserUiState
import com.studyagent.client.ui.screens.cardbrowser.CardBrowserViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CardBrowserViewModelTest {
    private val id = AnkiBackendId.Fake("browser-vm")
    private val deckA = AnkiDeckRef(id, "deck-a", "collection")
    private val deckB = AnkiDeckRef(id, "deck-b", "collection")

    @Test
    fun `initial page is deck scoped and backend request is bounded`() = runBrowserTest(
        backend = backend(id, listOf(deckA, deckB), listOf(
            card(id, deckA, "A question", "A answer"),
            card(id, deckB, "B question", "B answer"),
            card(id, deckA, "A second", "Second answer")
        )),
        deckId = deckA.deckId,
        pageSize = 1
    ) { viewModel, sourceBackend ->
        advanceUntilIdle()
        val state = viewModel.uiState.value as CardBrowserUiState.Ready
        assertEquals(1, state.rows.size)
        assertEquals("A question", state.rows.single().questionPreview)
        assertEquals("deck-a", state.query.deckId)
        val source = sourceBackend as FakeAnkiBackend
        assertEquals(listOf(1), source.browseLimits)
        assertEquals("deck-a", source.browseQueries.single().deckId)
    }

    @Test
    fun `search is normalized and rapid edits are debounced`() = runBrowserTest(
        backend = backend(id, listOf(deckA), listOf(
            card(id, deckA, "ECG QRS duration", "QRS is the ventricular complex"),
            card(id, deckA, "Different question", "Another answer")
        )),
        deckId = deckA.deckId
    ) { viewModel, sourceBackend ->
        val source = sourceBackend as FakeAnkiBackend
        advanceUntilIdle()
        assertEquals(1, source.browseCalls)

        viewModel.setSearchText("ECG")
        viewModel.setSearchText("  QRS\t duration ")
        viewModel.setSearchText("QRS duration")
        advanceTimeBy(CardBrowserViewModel.SEARCH_DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals("No backend request should run before the debounce expires", 1, source.browseCalls)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, source.browseCalls)
        assertEquals("QRS duration", source.browseQueries.last().text)
        val state = viewModel.uiState.value as CardBrowserUiState.Ready
        assertEquals("QRS duration", state.query.searchText)
        assertEquals(1, state.rows.size)
        assertTrue(state.rows.single().questionPreview!!.contains("QRS"))
    }

    @Test
    fun `filter and sort changes reset paging while preserving other query selections`() = runBrowserTest(
        backend = backend(id, listOf(deckA), listOf(
            card(id, deckA, "QRS duration", "Answer one", tags = setOf("cardiology"), reps = 4),
            card(id, deckA, "QRS complex", "Answer two", tags = setOf("ecg"), reps = 2),
            card(id, deckA, "QRS wave", "Answer three", tags = setOf("cardiology"), reps = 1)
        )),
        deckId = deckA.deckId,
        pageSize = 1
    ) { viewModel, sourceBackend ->
        val source = sourceBackend as FakeAnkiBackend
        advanceUntilIdle()
        viewModel.setSearchText("QRS")
        advanceTimeBy(CardBrowserViewModel.SEARCH_DEBOUNCE_MS)
        runCurrent()
        viewModel.setFilters(AnkiCardFilters(tags = setOf("cardiology")))
        advanceUntilIdle()
        viewModel.setSort(AnkiCardSort.Reps)
        advanceUntilIdle()

        val state = viewModel.uiState.value as CardBrowserUiState.Ready
        assertEquals("QRS", state.query.searchText)
        assertEquals(setOf("cardiology"), state.query.filters.tags)
        assertEquals(AnkiCardSort.Reps, state.query.sort)
        assertNull(source.browseQueries.last().page.cursor)
        assertEquals(1, source.browseQueries.last().page.limit)
        assertTrue(state.rows.all { "cardiology" in it.tags })
    }

    @Test
    fun `pagination appends once suppresses overlap by card ref and keeps duplicate looking cards`() {
        val requests = mutableListOf<AnkiCardQuery>()
        val scriptedBackend = scriptedBackend(id, deckA, requests)
        runBrowserTest(scriptedBackend, deckA.deckId, pageSize = 2) { viewModel, _ ->
            advanceUntilIdle()
            val first = viewModel.uiState.value as CardBrowserUiState.Ready
            assertEquals(2, first.rows.size)
            assertTrue(first.hasMore)

            viewModel.loadMore()
            viewModel.loadMore() // double tap while the page is in flight is ignored
            advanceUntilIdle()

            val state = viewModel.uiState.value as CardBrowserUiState.Ready
            assertEquals(3, state.rows.size)
            assertEquals(3, state.rows.map { it.cardRef }.distinct().size)
            assertEquals(1, state.rows.map { it.questionPreview }.distinct().size)
            assertFalse(state.hasMore)
            assertFalse(state.isLoadingMore)
            assertEquals(2, requests.size)
            assertEquals("overlap-cursor", requests.last().page.cursor)
        }
    }

    @Test
    fun `stale page response cannot overwrite a newer search`() = runBrowserTest(
        backend = backend(id, listOf(deckA), listOf(
            card(id, deckA, "QRS first", "one", tags = setOf("ecg")),
            card(id, deckA, "Other page", "two", tags = setOf("other")),
            card(id, deckA, "QRS third", "three", tags = setOf("ecg")),
            card(id, deckA, "QRS fourth", "four", tags = setOf("ecg"))
        )),
        deckId = deckA.deckId,
        pageSize = 2
    ) { viewModel, sourceBackend ->
        val source = sourceBackend as FakeAnkiBackend
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        source.browseGate = gate
        source.ignoreBrowseCancellation = true

        viewModel.loadMore()
        runCurrent()
        assertTrue((viewModel.uiState.value as CardBrowserUiState.Ready).isLoadingMore)
        assertEquals(2, source.browseCalls)

        viewModel.setSearchText("QRS")
        advanceTimeBy(CardBrowserViewModel.SEARCH_DEBOUNCE_MS)
        runCurrent() // the replacement waits behind the still-running read
        gate.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value as CardBrowserUiState.Ready
        assertEquals("QRS", state.query.searchText)
        assertTrue(state.rows.isNotEmpty())
        assertTrue(state.rows.all { it.questionPreview?.contains("QRS") == true })
        assertEquals(3, source.browseCalls)
        assertNull(source.browseQueries.last().page.cursor)
        assertEquals(0, source.commitInvocations)
        assertEquals(0, source.backendEffectCount)
    }

    @Test
    fun `refresh preserves current query and resets cursor`() = runBrowserTest(
        backend = backend(id, listOf(deckA), listOf(
            card(id, deckA, "QRS a", "answer a", tags = setOf("ecg")),
            card(id, deckA, "QRS b", "answer b", tags = setOf("ecg")),
            card(id, deckA, "QRS c", "answer c", tags = setOf("ecg"))
        )),
        deckId = deckA.deckId,
        pageSize = 1
    ) { viewModel, sourceBackend ->
        val source = sourceBackend as FakeAnkiBackend
        advanceUntilIdle()
        viewModel.setSearchText("QRS")
        advanceTimeBy(CardBrowserViewModel.SEARCH_DEBOUNCE_MS)
        runCurrent()
        viewModel.setFilters(AnkiCardFilters(tags = setOf("ecg")))
        advanceUntilIdle()
        viewModel.setSort(AnkiCardSort.Lapses)
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value as CardBrowserUiState.Ready
        assertEquals("QRS", state.query.searchText)
        assertEquals(setOf("ecg"), state.query.filters.tags)
        assertEquals(AnkiCardSort.Lapses, state.query.sort)
        assertNull(source.browseQueries.last().page.cursor)
        assertEquals(1, state.rows.size)
    }

    @Test
    fun `backend switch rejects old identity and loads only the newly bound backend`() = runBrowserTest(
        backend = backend(id, listOf(deckA), listOf(card(id, deckA, "Old backend", "old")))
    ) { viewModel, _ ->
        advanceUntilIdle()
        val newId = AnkiBackendId.Fake("new-browser")
        val newDeck = AnkiDeckRef(newId, "deck-a", "collection-b")
        val next = backend(newId, listOf(newDeck), listOf(card(newId, newDeck, "New backend", "new")))

        viewModel.switchBackend(next)
        advanceUntilIdle()

        val state = viewModel.uiState.value as CardBrowserUiState.Ready
        assertEquals(newId, state.rows.single().cardRef.backendId)
        assertEquals("New backend", state.rows.single().questionPreview)
        assertEquals(1, next.browseCalls)
    }

    @Test
    fun `late response from the previous backend cannot publish after a backend switch`() = runBrowserTest(
        backend = backend(id, listOf(deckA), listOf(card(id, deckA, "Old response", "old")))
    ) { viewModel, sourceBackend ->
        val oldBackend = sourceBackend as FakeAnkiBackend
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        oldBackend.browseGate = gate
        oldBackend.ignoreBrowseCancellation = true
        viewModel.refresh()
        runCurrent()
        assertEquals(2, oldBackend.browseCalls)

        val newId = AnkiBackendId.Fake("switched-browser")
        val newDeck = AnkiDeckRef(newId, "deck-a", "collection-new")
        val newBackend = backend(newId, listOf(newDeck), listOf(card(newId, newDeck, "New response", "new")))
        viewModel.switchBackend(newBackend)
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value as CardBrowserUiState.Ready
        assertEquals(newId, state.rows.single().cardRef.backendId)
        assertEquals("New response", state.rows.single().questionPreview)
        assertEquals(1, newBackend.browseCalls)
        assertEquals(0, oldBackend.commitInvocations)
    }

    @Test
    fun `capability degradation rejects unsupported search without querying backend`() = runBrowserTest(
        backend = backend(
            id,
            listOf(deckA),
            listOf(card(id, deckA, "Original", "Answer")),
            capabilities = FakeAnkiBackend.REVIEW_CAPABILITIES.copy(
                cardBrowser = FakeAnkiBackend.CARD_BROWSER_CAPABILITIES.copy(textSearch = false)
            )
        ),
        deckId = deckA.deckId
    ) { viewModel, sourceBackend ->
        val source = sourceBackend as FakeAnkiBackend
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is CardBrowserUiState.Ready)
        viewModel.setSearchText("term")
        runCurrent()
        val state = viewModel.uiState.value as CardBrowserUiState.Unavailable
        assertEquals("card_search", state.unsupportedFeature)
        assertFalse(state.capabilities.textSearch)
        assertEquals(1, source.browseCalls)
    }

    @Test
    fun `empty deck and empty search are distinct states`() = runBrowserTest(
        backend = backend(id, listOf(deckA), emptyList()),
        deckId = deckA.deckId
    ) { viewModel, _ ->
        advanceUntilIdle()
        val emptyCollection = viewModel.uiState.value as CardBrowserUiState.Empty
        assertEquals(CardBrowserUiState.Empty.Reason.NO_CARDS_IN_SCOPE, emptyCollection.reason)

        viewModel.setSearchText("not found")
        advanceTimeBy(CardBrowserViewModel.SEARCH_DEBOUNCE_MS)
        runCurrent()
        val noMatches = viewModel.uiState.value as CardBrowserUiState.Empty
        assertEquals(CardBrowserUiState.Empty.Reason.NO_MATCHES, noMatches.reason)
    }

    @Test
    fun `large collection loads in bounded pages without row hydration or mutations`() = runBrowserTest(
        backend = backend(
            id,
            listOf(deckA, deckB),
            CardBrowserTestFixtures.largeCardSet(id, listOf(deckA, deckB), count = 1_200)
        ),
        pageSize = 50
    ) { viewModel, sourceBackend ->
        val source = sourceBackend as FakeAnkiBackend
        advanceUntilIdle()
        var state = viewModel.uiState.value as CardBrowserUiState.Ready
        assertEquals(50, state.rows.size)
        assertTrue(state.hasMore)
        viewModel.loadMore()
        advanceUntilIdle()
        state = viewModel.uiState.value as CardBrowserUiState.Ready
        assertEquals(100, state.rows.size)
        assertEquals(2, source.browseCalls)
        assertEquals(listOf(50, 50), source.browseLimits)
        assertEquals(0, source.hydrateCalls)
        assertEquals(0, source.commitInvocations)
        assertEquals(0, source.backendEffectCount)
        assertEquals(1_200, state.totalCount)
    }

    @Test
    fun `open details event carries only the selected stable card reference`() = runBrowserTest(
        backend = backend(id, listOf(deckA), listOf(card(id, deckA, "Question", "Answer")))
    ) { viewModel, _ ->
        advanceUntilIdle()
        val state = viewModel.uiState.value as CardBrowserUiState.Ready
        val received = async { viewModel.events.first() }
        viewModel.openCardDetails(state.rows.single().cardRef)
        advanceUntilIdle()
        assertEquals(CardBrowserEvent.OpenCardDetails(state.rows.single().cardRef), received.await())
    }

    private fun runBrowserTest(
        backend: AnkiBackend,
        deckId: String? = null,
        pageSize: Int = AnkiPageRequest.DEFAULT_LIMIT,
        test: suspend TestScope.(CardBrowserViewModel, AnkiBackend) -> Unit
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val viewModel = CardBrowserViewModel(backend, deckId, pageSize)
            store.put("card-browser-test", viewModel)
            test(viewModel, backend)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private fun backend(
        backendId: AnkiBackendId,
        deckRefs: List<AnkiDeckRef>,
        cards: List<AnkiRenderedCard>,
        capabilities: com.studyagent.client.core.anki.AnkiCapabilities = FakeAnkiBackend.REVIEW_CAPABILITIES
    ) = FakeAnkiBackend(
        id = backendId,
        decks = deckRefs.mapIndexed { index, ref -> AnkiDeck(ref, "Deck ${index + 1}") },
        cards = cards,
        initialCapabilities = capabilities,
        initialAvailability = AnkiAvailability.Ready(capabilities)
    )

    private fun card(
        backendId: AnkiBackendId,
        deckRef: AnkiDeckRef,
        question: String,
        answer: String?,
        tags: Set<String> = emptySet(),
        reps: Int = 1,
        lapses: Int = 0,
        flag: AnkiFlag? = AnkiFlag.NONE,
        queueState: AnkiCardQueueState = AnkiCardQueueState.REVIEW
    ): AnkiRenderedCard {
        val rawId = "${deckRef.deckId}:${question.hashCode()}"
        val noteId = "note-$rawId"
        return AnkiRenderedCard(
            ref = AnkiCardRef(backendId, cardId = "card-$rawId", noteId = noteId, cardOrd = 0,
                collectionKey = deckRef.collectionKey),
            questionHtml = null,
            answerHtml = null,
            questionText = question,
            answerText = answer,
            pureAnswerText = answer,
            scheduling = AnkiSchedulingInfo(reps = reps, lapses = lapses),
            metadata = AnkiCardMetadata(deckName = "Deck ${deckRef.deckId}", tags = tags, queueState = queueState),
            noteRef = AnkiNoteRef(backendId, noteId, deckRef.collectionKey),
            deckRef = deckRef,
            flag = flag
        )
    }

    private fun scriptedBackend(
        backendId: AnkiBackendId,
        deckRef: AnkiDeckRef,
        requests: MutableList<AnkiCardQuery>
    ): AnkiBackend {
        val delegate = backend(backendId, listOf(deckRef), emptyList())
        val first = listItem(backendId, deckRef, "Duplicate-looking question", "card-1")
        val second = listItem(backendId, deckRef, "Duplicate-looking question", "card-2")
        val third = listItem(backendId, deckRef, "Duplicate-looking question", "card-3")
        return object : AnkiBackend by delegate {
            override suspend fun browseCards(query: AnkiCardQuery): AnkiResult<AnkiCardPage> {
                requests += query
                return AnkiResult.Success(
                    if (query.page.cursor == null) {
                        AnkiCardPage(listOf(first, second), "overlap-cursor", 3)
                    } else {
                        AnkiCardPage(listOf(second, third), null, 3)
                    }
                )
            }
        }
    }

    private fun listItem(id: AnkiBackendId, deck: AnkiDeckRef, question: String, cardId: String) =
        AnkiCardListItem(
            cardRef = AnkiCardRef(id, cardId = cardId, noteId = "note-$cardId", cardOrd = 0,
                collectionKey = deck.collectionKey),
            noteRef = AnkiNoteRef(id, "note-$cardId", deck.collectionKey),
            deckRef = deck,
            deckName = "Deck ${deck.deckId}",
            questionText = question,
            answerText = "Answer",
            tags = listOf("tag"),
            flag = AnkiFlag.RED,
            type = AnkiCardType.REVIEW,
            scheduling = AnkiSchedulingInfo(reps = 1, lapses = 0),
            suspended = false,
            buried = false
        )
}
