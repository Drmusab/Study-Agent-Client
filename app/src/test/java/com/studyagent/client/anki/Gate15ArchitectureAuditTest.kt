package com.studyagent.client.anki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** GATE 15 source audit for the read-only backend-neutral Card Browser boundary. */
class Gate15ArchitectureAuditTest {
    private val clientRoot: File by lazy {
        val candidates = listOf(
            File("src/main/java/com/studyagent/client"),
            File("app/src/main/java/com/studyagent/client"),
            File("../app/src/main/java/com/studyagent/client")
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot locate app source tree from ${File(".").absolutePath}")
    }

    private val testClientRoot: File by lazy {
        val candidates = listOf(
            File("src/test/java/com/studyagent/client"),
            File("app/src/test/java/com/studyagent/client"),
            File("../app/src/test/java/com/studyagent/client")
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot locate app test tree from ${File(".").absolutePath}")
    }

    private fun source(relativePath: String): String {
        val file = File(clientRoot, relativePath)
        check(file.isFile) { "Missing GATE 15 source: $relativePath" }
        return file.readText()
    }

    private fun testSource(relativePath: String): String {
        val file = File(testClientRoot, relativePath)
        check(file.isFile) { "Missing GATE 15 test source: $relativePath" }
        return file.readText()
    }

    private fun noForbidden(source: String, pattern: Regex, description: String) {
        assertFalse("$description must remain absent", pattern.containsMatchIn(source))
    }

    @Test
    fun `INV-15-01 browser UI has no provider or private-storage dependency`() {
        val ui = listOf(
            "ui/screens/cardbrowser/CardBrowserScreen.kt",
            "ui/screens/cardbrowser/CardDetailsPlaceholderScreen.kt",
            "ui/screens/cardbrowser/CardBrowserViewModel.kt",
            "ui/components/anki/CardBrowserFilters.kt",
            "ui/components/anki/CardBrowserSortMenu.kt",
            "ui/components/anki/CardBrowserRow.kt"
        ).joinToString("\n") { source(it) }
        noForbidden(
            ui,
            Regex("ContentResolver|FlashCardsContract|AnkiConnect|java\\.io\\.File|Uri\\.parse"),
            "the browser presentation and ViewModel"
        )
        assertTrue(ui.contains("AnkiBackend"))
    }

    @Test
    fun `INV-15-02 query contract is backend neutral plain text and identity only`() {
        val query = source("core/anki/AnkiCardQuery.kt")
        val models = source("core/anki/AnkiModels.kt").substringAfter("data class AnkiCardListItem(")
            .substringBefore("/**\n * Backend-normalized content")
        assertTrue(query.contains("data class AnkiCardQuery"))
        assertTrue(query.contains("val text: String?"))
        assertTrue(query.contains("val cursor: String?"))
        assertTrue(models.contains("val cardRef: AnkiCardRef"))
        noForbidden(models, Regex("questionHtml|answerHtml|AnkiMediaRef|ContentResolver|Uri\\b"),
            "the lightweight list projection")
    }

    @Test
    fun `INV-15-03 search normalization trims and collapses without case folding`() {
        val query = source("core/anki/AnkiCardQuery.kt")
        val normalizer = query.substringAfter("fun normalizeAnkiCardSearchText")
            .substringBefore("fun AnkiCardQuery.unsupportedFeature")
        assertTrue(normalizer.contains("Character.isSpaceChar"))
        assertTrue(normalizer.contains("isWhitespace"))
        assertTrue(normalizer.contains("append(' ')"))
        noForbidden(normalizer, Regex("lowercase\\(|uppercase\\(|normalize\\("),
            "search normalization")
    }

    @Test
    fun `INV-15-04 backend owns global search before cursor slicing`() {
        val fake = testSource("anki/fake/FakeAnkiBackend.kt")
        val browse = fake.substringAfter("private suspend fun performBrowse")
            .substringBefore("private fun parseFakeCursor")
        assertTrue(browse.indexOf("cardData.asSequence()") < browse.indexOf(".drop(offset)"))
        assertTrue(browse.contains("questionText?.contains(normalizedText"))
        assertTrue(browse.contains("answerText?.contains(normalizedText"))
        val ankidroid = source("data/anki/ankidroid/AnkiDroidCardBrowserGateway.kt")
        assertTrue(ankidroid.contains("issues no provider") && ankidroid.contains("request:"))
        assertTrue(ankidroid.contains("AnkiResult.Failure(AnkiError.UnsupportedAction"))
    }

    @Test
    fun `INV-15-05 backend capability matrix is explicit and AnkiDroid remains fail closed`() {
        val capabilities = source("core/anki/AnkiCapabilities.kt")
        assertTrue(capabilities.contains("val cardBrowser: AnkiCardBrowserCapabilities"))
        assertTrue(capabilities.contains("val textSearch: Boolean = false"))
        val policy = source("data/anki/ankidroid/AnkiDroidCompatibilityPolicy.kt")
        assertTrue(policy.contains("cardBrowser = CapabilitySupport.UNSUPPORTED"))
        assertTrue(policy.contains("cardSearch = CapabilitySupport.UNSUPPORTED"))
    }

    @Test
    fun `INV-15-06 unsupported query fields and sorts are rejected not dropped`() {
        val contract = source("core/anki/AnkiCardQuery.kt")
        for (token in listOf(
            "card_filter_flags", "card_filter_tags", "card_filter_types",
            "card_filter_suspended", "card_filter_buried", "card_sort_"
        )) assertTrue("missing unsupported token $token", contract.contains(token))
        assertTrue(contract.contains("sort !in capabilities.sorts"))
        assertTrue(source("data/anki/ankidroid/AnkiDroidCardQueryMapper.kt").contains("AnkiDroidCardQueryMapping.Unsupported"))
    }

    @Test
    fun `INV-15-07 all pages are bounded and cursors stay opaque to UI`() {
        val query = source("core/anki/AnkiCardQuery.kt")
        val vm = source("ui/screens/cardbrowser/CardBrowserViewModel.kt")
        assertTrue(query.contains("const val MAX_LIMIT: Int = 100"))
        assertTrue(query.contains("limit in MIN_LIMIT..MAX_LIMIT"))
        assertTrue(vm.contains("pageSize in AnkiPageRequest.MIN_LIMIT..AnkiPageRequest.MAX_LIMIT"))
        assertTrue(vm.contains("nextCursor"))
        assertFalse(vm.contains("parseFakeCursor"))
    }

    @Test
    fun `INV-15-08 stable card reference is the row key and navigation payload`() {
        val models = source("ui/screens/cardbrowser/CardBrowserModels.kt")
        val screen = source("ui/screens/cardbrowser/CardBrowserScreen.kt")
        val routes = source("ui/navigation/Screen.kt")
        val navHost = source("ui/navigation/AppNavHost.kt")
        assertTrue(models.contains("val stableKey: String get() = cardRef.stableKey"))
        assertTrue(screen.contains("key = { it.stableKey }"))
        assertTrue(screen.contains("onOpenCard(row.cardRef)"))
        assertTrue(routes.contains("fun createRoute(cardRef: AnkiCardRef)"))
        assertTrue(navHost.contains("CardBrowserViewModel(initialBackend = container.ankiDroidBackend"))
        assertFalse(routes.contains("AnkiRenderedCard"))
    }

    @Test
    fun `INV-15-09 stale results are guarded by backend query generation and cursor`() {
        val vm = source("ui/screens/cardbrowser/CardBrowserViewModel.kt")
        assertTrue(vm.contains("requestMutex"))
        assertTrue(vm.contains("generation == token.generation"))
        assertTrue(vm.contains("query == token.query"))
        assertTrue(vm.contains("nextCursor == token.cursor"))
        assertTrue(vm.contains("backend === targetBackend"))
        assertTrue(vm.contains("backend.id == token.backendId"))
    }

    @Test
    fun `INV-15-10 rows are text only bounded and never hydrate media or HTML`() {
        val models = source("ui/screens/cardbrowser/CardBrowserModels.kt")
        val row = source("ui/components/anki/CardBrowserRow.kt")
        assertTrue(models.contains("private fun safePreview"))
        assertTrue(models.contains("PREVIEW_MAX_CHARS = 220"))
        assertTrue(models.contains("answerPreviewAllowed"))
        noForbidden(row, Regex("AndroidView|WebView\\s*\\(|hydrateCardContent\\s*\\(|AnkiRenderedCard|import .*AnkiBackend"),
            "the list-row Composable")
    }

    @Test
    fun `INV-15-11 browser is read only and performs no per-row backend operation`() {
        val vm = source("ui/screens/cardbrowser/CardBrowserViewModel.kt")
        val row = source("ui/components/anki/CardBrowserRow.kt")
        noForbidden(vm + row, Regex("\\.commitRating\\(|\\.performReviewerAction\\(|\\.dispatchReviewerAction\\(|\\.hydrateCardContent\\(|\\.beginReview\\(|\\.nextCard\\("),
            "browser UI actions")
        assertTrue(vm.contains("targetBackend.browseCards(request)"))
        assertTrue(row.contains("Pure row rendering"))
    }

    @Test
    fun `INV-15-12 deck scope is sent to backend and mismatched rows are rejected`() {
        val vm = source("ui/screens/cardbrowser/CardBrowserViewModel.kt")
        val mapper = source("ui/screens/cardbrowser/CardBrowserQueryMapper.kt")
        assertTrue(mapper.contains("deckId = query.deckId"))
        assertTrue(vm.contains("item.deckRef?.deckId != token.query.deckId"))
        assertTrue(vm.contains("card_deck_scope_mismatch"))
    }

    @Test
    fun `INV-15-13 Arabic and RTL text directions and accessible controls are present`() {
        val screen = source("ui/screens/cardbrowser/CardBrowserScreen.kt")
        val row = source("ui/components/anki/CardBrowserRow.kt")
        val filters = source("ui/components/anki/CardBrowserFilters.kt")
        assertTrue(screen.contains("TextDirection.ContentOrLtr"))
        assertTrue(row.contains("TextDirection.ContentOrLtr"))
        assertTrue(filters.contains("TextDirection.ContentOrLtr"))
        for (source in listOf(screen, row, filters)) {
            assertTrue(source.contains("contentDescription"))
            assertTrue(source.contains("semantics"))
        }
        assertTrue(row.contains("mergeDescendants = true"))
    }

    @Test
    fun `INV-15-14 details navigation has stable identity only and GATE 16 owns hydration`() {
        val routes = source("ui/navigation/Screen.kt")
        val placeholder = source("ui/screens/cardbrowser/CardDetailsPlaceholderScreen.kt")
        assertTrue(routes.contains("AnkiCardRef"))
        assertTrue(placeholder.contains("GATE 16 owns one-card hydration"))
        noForbidden(placeholder, Regex("hydrateCardContent\\(|AnkiRenderedCard|WebView"),
            "the GATE 15 Card Details placeholder")
    }

    @Test
    fun `INV-15-15 Deck Details entry requires both browse and deck scope capabilities`() {
        val deck = source("ui/screens/library/DeckDetailsScreen.kt")
        assertTrue(deck.contains("current.capabilities.cardBrowser.browse && current.capabilities.cardBrowser.deckScope"))
        assertTrue(deck.contains("Browse cards"))
        assertTrue(deck.contains("current.summary.deck.ref.deckId"))
    }

    @Test
    fun `INV-15-16 browser does not alter study-session or backend mutation locks`() {
        val vm = source("ui/screens/cardbrowser/CardBrowserViewModel.kt")
        val backend = source("data/anki/ankidroid/AnkiDroidBackend.kt")
            .substringAfter("override suspend fun browseCards(query: AnkiCardQuery)")
            .substringBefore("private fun guardDeckRead")
        noForbidden(vm + backend, Regex("writePermit|ratingGateway|reviewerActionGateway|commitRating\\(|sessionMutex|activeTurn"),
            "the Card Browser implementation")
    }

    @Test
    fun `INV-15-17 loading empty unavailable and error states remain distinct`() {
        val models = source("ui/screens/cardbrowser/CardBrowserModels.kt")
        for (state in listOf("Loading", "Ready", "Empty", "Unavailable", "Error")) {
            assertTrue("missing UI state $state", models.contains("data class $state("))
        }
        assertTrue(models.contains("NO_CARDS_IN_SCOPE"))
        assertTrue(models.contains("NO_MATCHES"))
    }

    @Test
    fun `INV-15-18 refresh and every query selection restart from a fresh first page`() {
        val vm = source("ui/screens/cardbrowser/CardBrowserViewModel.kt")
        assertTrue(vm.contains("fun refresh() = restartQuery(debounceMs = 0L)"))
        assertTrue(vm.contains("restartQuery(debounceMs = searchDebounceMs)"))
        assertTrue(vm.contains("fun setFilters"))
        assertTrue(vm.contains("fun setSort"))
        assertTrue(vm.contains("nextCursor = null"))
    }
}
