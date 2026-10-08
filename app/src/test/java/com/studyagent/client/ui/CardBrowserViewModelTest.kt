package com.studyagent.client.ui

import com.studyagent.client.anki.fake.CardBrowserTestFixtures
import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardListItem
import com.studyagent.client.core.anki.AnkiCardMetadata
import com.studyagent.client.core.anki.AnkiCardPage
import com.studyagent.client.core.anki.AnkiCardQuery
import com.studyagent.client.core.anki.AnkiCardQueueState
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiCardScope
import com.studyagent.client.core.anki.AnkiCardSort
import com.studyagent.client.core.anki.AnkiCardType
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.AnkiPageCursor
import com.studyagent.client.core.anki.AnkiPageRequest
import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.AnkiSchedulingInfo
import com.studyagent.client.core.anki.SortDirection
import com.studyagent.client.ui.screens.cardbrowser.CardBrowserEvent
import com.studyagent.client.ui.screens.cardbrowser.CardBrowserUiState
import com.studyagent.client.ui.screens.cardbrowser.CardBrowserViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ViewModel-level GATE 15 tests.
 *
 * These tests drive the ViewModel from `runBlocking` and await *state predicates* instead of
 * advancing a virtual clock: the ViewModel's only real suspension points are the debounce delay
 * and the backend call, both of which the assertions wait for explicitly.
 *
 * `viewModelScope` is `Dispatchers.Main.immediate`-backed, so the class installs an unconfined
 * Main dispatcher for its lifetime (the same thing [com.studyagent.client.ui.LibraryViewModelTest]
 * and [com.studyagent.client.ui.DeckDetailsViewModelTest] do per test). Without it, constructing
 * the ViewModel fails before any assertion runs.
 */
class CardBrowserViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val id = AnkiBackendId.Fake("browser-vm")
    private val deckA = AnkiDeckRef(id, "deck-a", "collection")
    private val deckB = AnkiDeckRef(id, "deck-b", "collection")

    @Test
    fun `initial page is deck scoped and backend request is bounded`() = runBrowserTest(
        backend = backend(
            id, listOf(deckA, deckB), listOf(
                card(id, deckA, "A question", "A answer"),
                card(id, deckB, "B question", "B answer"),
                card(id, deckA, "A second", "Second answer")
            )
        ),
        deckId = deckA.deckId,
        pageSize = 1
    ) { viewModel, sourceBackend ->
        val state = viewModel.awaitReady()
        assertEquals(1, state.rows.size)
        assertEquals("A question", state.rows.single().questionPreview)
        assertEquals("deck-a", state.query.deckId)
        val source = sourceBackend as FakeAnkiBackend
        assertEquals(listOf(1), source.browseLimits)
        assertEquals(AnkiCardScope.Deck("deck-a", includeChildren = true), source.browseQueries.single().scope)
    }

    @Test
    fun `search is normalized and rapid edits are debounced`() = runBrowserTest(
        backend = backend(
            id, listOf(deckA), listOf(
                card(id, deckA, "ECG QRS duration", "QRS is the ventricular complex"),
                card(id, deckA, "Different question", "Another answer")
            )
        ),
        deckId = deckA.deckId,
        searchDebounceMs = 120
    ) { viewModel, sourceBackend ->
        val source = sourceBackend as FakeAnkiBackend
        viewModel.awaitReady()
        assertEquals(1, source.browseCalls)

        viewModel.setSearchText("ECG")
        viewModel.setSearchText("  QRS\t duration ")
        viewModel.setSearchText("QRS duration")
        assertEquals("no backend request should run before the debounce expires", 1, source.browseCalls)

        val state = viewModel.awaitState { it is CardBrowserUiState.Ready && it.query.searchText == "QRS duration" }
                as CardBrowserUiState.Ready
        // The intermediate keystrokes were superseded, not executed: exactly one extra request.
        assertEquals(2, source.browseCalls)
        assertEquals("QRS duration", source.browseQueries.last().text)
        assertEquals(1, state.rows.size)
        assertTrue(state.rows.single().questionPreview!!.contains("QRS"))
    }

    @Test
    fun `filter and sort changes reset paging while preserving other query selections`() = runBrowserTest(
        backend = backend(
            id, listOf(deckA), listOf(
                card(id, deckA, "QRS duration", "Answer one", tags = setOf("cardiology"), reps = 4),
                card(id, deckA, "QRS complex", "Answer two", tags = setOf("ecg"), reps = 2),
                card(id, deckA, "QRS wave", "Answer three", tags = setOf("cardiology"), reps = 1)
            )
        ),
        deckId = deckA.deckId,
        pageSize = 1
    ) { viewModel, sourceBackend ->
        val source = sourceBackend as FakeAnkiBackend
        viewModel.awaitReady()
        viewModel.setSearchText("QRS")
        viewModel.awaitState { it is CardBrowserUiState.Ready && it.query.searchText == "QRS" }
        viewModel.setFilters(AnkiCardFilters(tags = setOf("cardiology")))
        viewModel.awaitState { it is CardBrowserUiState.Ready && it.query.filters.tags == setOf("cardiology") }
        viewModel.setSort(AnkiCardSort.Reps(SortDirection.ASCENDING))
        val state = viewModel.awaitState {
            it is CardBrowserUiState.Ready && it.query.sort == AnkiCardSort.Reps(SortDirection.ASCENDING)
        } as CardBrowserUiState.Ready

        assertEquals("QRS", state.query.searchText)
        assertEquals(setOf("cardiology"), state.query.filters.tags)
        assertNull(source.browseQueries.last().page.cursor)
        assertEquals(1, source.browseQueries.last().page.limit)
        assertTrue(state.rows.all { "cardiology" in it.tags })
    }

    @Test
    fun `pagination appends once suppresses overlap by card ref and keeps duplicate looking cards`() {
        val requests = mutableListOf<AnkiCardQuery>()
        val scripted = scriptedBackend(id, deckA, requests)
        runBrowserTest(scripted, deckA.deckId, pageSize = 2) { viewModel, _ ->
            val first = viewModel.awaitReady()
            assertEquals(2, first.rows.size)
            assertTrue(first.hasMore)

            viewModel.loadMore()
            viewModel.loadMore() // an extra request while the page is exhausted is ignored
            val state = viewModel.awaitState { it is CardBrowserUiState.Ready && !it.isLoadingMore }
                    as CardBrowserUiState.Ready

            assertEquals(3, state.rows.size)
            assertEquals(3, state.rows.map { it.cardRef }.distinct().size)
            assertEquals(1, state.rows.map { it.questionPreview }.distinct().size)
            assertFalse(state.hasMore)
            assertEquals(2, requests.size)
            assertEquals(AnkiPageCursor("overlap-cursor"), requests.last().page.cursor)
        }
    }

    @Test
    fun `stale page response cannot overwrite a newer search`() = runBrowserTest(
        backend = backend(
            id, listOf(deckA), listOf(
                card(id, deckA, "QRS first", "one", tags = setOf("ecg")),
                card(id, deckA, "Other page", "two", tags = setOf("other")),
                card(id, deckA, "QRS third", "three", tags = setOf("ecg")),
                card(id, deckA, "QRS fourth", "four", tags = setOf("ecg"))
            )
        ),
        deckId = deckA.deckId,
        pageSize = 2
    ) { viewModel, sourceBackend ->
        val source = sourceBackend as FakeAnkiBackend
        viewModel.awaitReady()
        val gate = CompletableDeferred<Unit>()
        source.browseGate = gate
        source.ignoreBrowseCancellation = true

        viewModel.loadMore()
        viewModel.awaitState { it is CardBrowserUiState.Ready && it.isLoadingMore }
        assertEquals(2, source.browseCalls)

        viewModel.setSearchText("QRS")
        viewModel.awaitState { it.query.searchText == "QRS" || it is CardBrowserUiState.Loading }
        gate.complete(Unit)

        val state = viewModel.awaitState {
            it is CardBrowserUiState.Ready && it.query.searchText == "QRS" && !it.isLoadingMore
        } as CardBrowserUiState.Ready
        assertTrue(state.rows.isNotEmpty())
        assertTrue(state.rows.all { it.questionPreview?.contains("QRS") == true })
        assertEquals(3, source.browseCalls)
        assertNull(source.browseQueries.last().page.cursor)
        assertEquals(0, source.commitInvocations)
        assertEquals(0, source.backendEffectCount)
    }

    @Test
    fun `refresh preserves current query and resets cursor`() = runBrowserTest(
        backend = backend(
            id, listOf(deckA), listOf(
                card(id, deckA, "QRS a", "answer a", tags = setOf("ecg")),
                card(id, deckA, "QRS b", "answer b", tags = setOf("ecg")),
                card(id, deckA, "QRS c", "answer c", tags = setOf("ecg"))
            )
        ),
        deckId = deckA.deckId,
        pageSize = 1
    ) { viewModel, sourceBackend ->
        val source = sourceBackend as FakeAnkiBackend
        viewModel.awaitReady()
        viewModel.setSearchText("QRS")
        viewModel.awaitState { it is CardBrowserUiState.Ready && it.query.searchText == "QRS" }
        viewModel.setFilters(AnkiCardFilters(tags = setOf("ecg")))
        viewModel.awaitState { it is CardBrowserUiState.Ready && it.query.filters.tags == setOf("ecg") }
        viewModel.setSort(AnkiCardSort.Lapses(SortDirection.DESCENDING))
        viewModel.awaitState {
            it is CardBrowserUiState.Ready && it.query.sort == AnkiCardSort.Lapses(SortDirection.DESCENDING)
        }
        viewModel.loadMore()
        viewModel.awaitState { it is CardBrowserUiState.Ready && it.rows.size == 2 }

        viewModel.refresh()
        val state = viewModel.awaitState {
            it is CardBrowserUiState.Ready && it.query.sort == AnkiCardSort.Lapses(SortDirection.DESCENDING) &&
                it.rows.size == 1
        } as CardBrowserUiState.Ready
        assertEquals("QRS", state.query.searchText)
        assertEquals(setOf("ecg"), state.query.filters.tags)
        assertNull(source.browseQueries.last().page.cursor)
    }

    @Test
    fun `backend switch discards old identity and loads only the newly bound backend`() = runBrowserTest(
        backend = backend(id, listOf(deckA), listOf(card(id, deckA, "Old backend", "old")))
    ) { viewModel, _ ->
        viewModel.awaitReady()
        val newId = AnkiBackendId.Fake("new-browser")
        val newDeck = AnkiDeckRef(newId, "deck-a", "collection-b")
        val next = backend(newId, listOf(newDeck), listOf(card(newId, newDeck, "New backend", "new")))

        viewModel.switchBackend(next)
        val state = viewModel.awaitState {
            it is CardBrowserUiState.Ready && it.rows.singleOrNull()?.cardRef?.backendId == newId
        } as CardBrowserUiState.Ready
        assertEquals(newId, state.rows.single().cardRef.backendId)
        assertEquals("New backend", state.rows.single().questionPreview)
        assertEquals(1, next.browseCalls)
    }

    @Test
    fun `late response from the previous backend cannot publish after a backend switch`() = runBrowserTest(
        backend = backend(id, listOf(deckA), listOf(card(id, deckA, "Old response", "old")))
    ) { viewModel, sourceBackend ->
        val oldBackend = sourceBackend as FakeAnkiBackend
        viewModel.awaitReady()
        val gate = CompletableDeferred<Unit>()
        oldBackend.browseGate = gate
        oldBackend.ignoreBrowseCancellation = true
        viewModel.refresh()
        assertEquals(2, oldBackend.browseCalls)

        val newId = AnkiBackendId.Fake("switched-browser")
        val newDeck = AnkiDeckRef(newId, "deck-a", "collection-new")
        val newBackend = backend(newId, listOf(newDeck), listOf(card(newId, newDeck, "New response", "new")))
        viewModel.switchBackend(newBackend)
        gate.complete(Unit)

        val state = viewModel.awaitState {
            it is CardBrowserUiState.Ready && it.rows.singleOrNull()?.cardRef?.backendId == newId
        } as CardBrowserUiState.Ready
        assertEquals("New response", state.rows.single().questionPreview)
        assertEquals(1, newBackend.browseCalls)
        assertEquals(0, oldBackend.commitInvocations)
    }

    @Test
    fun `consumed capability degradation rejects unsupported search without querying the backend`() = runBrowserTest(
        backend = backend(
            id,
            listOf(deckA),
            listOf(card(id, deckA, "Original", "Answer")),
            capabilities = FakeAnkiBackend.REVIEW_CAPABILITIES.copy(
                cardBrowser = FakeAnkiBackend.CARD_BROWSER_CAPABILITIES.copy(canSearchText = false)
            )
        ),
        deckId = deckA.deckId
    ) { viewModel, sourceBackend ->
        val source = sourceBackend as FakeAnkiBackend
        viewModel.awaitReady()
        viewModel.setSearchText("term")
        val state = viewModel.awaitState { it is CardBrowserUiState.Unavailable } as CardBrowserUiState.Unavailable
        assertEquals("card_search", state.unsupportedFeature)
        assertFalse(state.capabilities.canSearchText)
        assertEquals(1, source.browseCalls)
    }

    @Test
    fun `a missing deck is a typed error and never an empty page`() = runBrowserTest(
        backend = backend(id, listOf(deckA), listOf(card(id, deckA, "Question", "Answer"))),
        deckId = "deck-missing"
    ) { viewModel, _ ->
        val state = viewModel.awaitState { it is CardBrowserUiState.Error } as CardBrowserUiState.Error
        // §6/§75 — a missing deck is a typed failure; an empty page would have been a silent lie.
        assertTrue(state.error is AnkiError.DeckNotFound)
    }

    @Test
    fun `empty deck and empty search are distinct states`() = runBrowserTest(
        backend = backend(id, listOf(deckA), emptyList()),
        deckId = deckA.deckId
    ) { viewModel, _ ->
        val emptyCollection = viewModel.awaitState { it is CardBrowserUiState.Empty }
                as CardBrowserUiState.Empty
        assertEquals(CardBrowserUiState.Empty.Reason.NO_CARDS_IN_SCOPE, emptyCollection.reason)

        viewModel.setSearchText("not found")
        val noMatches = viewModel.awaitState {
            it is CardBrowserUiState.Empty && it.query.searchText == "not found"
        } as CardBrowserUiState.Empty
        assertEquals(CardBrowserUiState.Empty.Reason.NO_MATCHES, noMatches.reason)
    }

    @Test
    fun `an invalid continuation cursor is surfaced and never silently restarts paging`() {
        val requests = mutableListOf<AnkiCardQuery>()
        val backend = invalidCursorBackend(id, deckA, requests)
        runBrowserTest(backend, deckA.deckId, pageSize = 1) { viewModel, _ ->
            val first = viewModel.awaitReady()
            assertTrue(first.hasMore)

            viewModel.loadMore()
            val state = viewModel.awaitState {
                it is CardBrowserUiState.Ready && it.appendError is AnkiError.InvalidCursor
            } as CardBrowserUiState.Ready
            // §33 — a refused cursor is reported; the loaded page is kept and no first page is refetched.
            assertEquals(1, state.rows.size)
            assertEquals(2, requests.size)
            assertFalse(state.isLoadingMore)
        }
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
        val first = viewModel.awaitReady()
        assertEquals(50, first.rows.size)
        assertTrue(first.hasMore)
        viewModel.loadMore()
        val state = viewModel.awaitState {
            it is CardBrowserUiState.Ready && it.rows.size == 100
        } as CardBrowserUiState.Ready
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
        val state = viewModel.awaitReady()
        val cardRef = state.rows.single().cardRef
        viewModel.openCardDetails(cardRef)
        val event = withTimeout(TIMEOUT_MS) { viewModel.events.first() }
        assertEquals(CardBrowserEvent.OpenCardDetails(cardRef), event)
    }

    private fun runBrowserTest(
        backend: AnkiBackend,
        deckId: String? = null,
        pageSize: Int = AnkiPageRequest.DEFAULT_LIMIT,
        searchDebounceMs: Long = 0L,
        test: suspend (CardBrowserViewModel, AnkiBackend) -> Unit
    ) = runBlocking {
        val viewModel = CardBrowserViewModel(
            initialBackend = backend,
            deckId = deckId,
            pageSize = pageSize,
            searchDebounceMs = searchDebounceMs
        )
        test(viewModel, backend)
    }

    private suspend fun CardBrowserViewModel.awaitReady(): CardBrowserUiState.Ready =
        awaitState { it is CardBrowserUiState.Ready } as CardBrowserUiState.Ready

    private suspend fun CardBrowserViewModel.awaitState(
        predicate: (CardBrowserUiState) -> Boolean
    ): CardBrowserUiState = withTimeout(TIMEOUT_MS) { uiState.first(predicate) }

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
            ref = AnkiCardRef(
                backendId, cardId = "card-$rawId", noteId = noteId, cardOrd = 0,
                collectionKey = deckRef.collectionKey
            ),
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
                        AnkiCardPage(listOf(first, second), AnkiPageCursor("overlap-cursor"), 3)
                    } else {
                        AnkiCardPage(listOf(second, third), null, 3)
                    }
                )
            }
        }
    }

    private fun invalidCursorBackend(
        backendId: AnkiBackendId,
        deckRef: AnkiDeckRef,
        requests: MutableList<AnkiCardQuery>
    ): AnkiBackend {
        val delegate = backend(backendId, listOf(deckRef), emptyList())
        val first = listItem(backendId, deckRef, "Only row", "card-1")
        return object : AnkiBackend by delegate {
            override suspend fun browseCards(query: AnkiCardQuery): AnkiResult<AnkiCardPage> {
                requests += query
                return if (query.page.cursor == null) {
                    AnkiResult.Success(AnkiCardPage(listOf(first), AnkiPageCursor("stale-cursor"), 1))
                } else {
                    AnkiResult.Failure(AnkiError.InvalidCursor("cursor_query_mismatch"))
                }
            }
        }
    }

    private fun listItem(id: AnkiBackendId, deck: AnkiDeckRef, question: String, cardId: String) =
        AnkiCardListItem(
            cardRef = AnkiCardRef(
                id, cardId = cardId, noteId = "note-$cardId", cardOrd = 0,
                collectionKey = deck.collectionKey
            ),
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

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
