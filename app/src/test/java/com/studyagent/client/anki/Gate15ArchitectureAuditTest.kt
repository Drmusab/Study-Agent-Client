package com.studyagent.client.anki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 15 source audit for the locked read-only, backend-neutral Card Browser contract.
 *
 * The contract fixes one entry point (`browseCards(AnkiCardQuery): AnkiCardPage`), an explicit scope
 * model, literal search text, explicit filter/sort categories, bounded opaque paging, typed refusal
 * of every unsupported component and a read-only boundary. This audit inspects the implementation
 * *text* for the structures that carry those guarantees, because several of them (sealed scope,
 * capability-driven affordances, no parallel query verb, no invented identity) can regress silently
 * while still compiling.
 */
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

    /** All production Kotlin sources, used for prohibitions that must hold repository-wide. */
    private val allMainSources: String by lazy {
        clientRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .joinToString("\n") { it.readText() }
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

    private fun browserUiSource(): String = BROWSER_UI_FILES.joinToString("\n") { source(it) }

    private fun noForbidden(source: String, pattern: Regex, description: String) {
        assertFalse("$description must remain absent", pattern.containsMatchIn(source))
    }

    private fun queryContract(): String = source("core/anki/AnkiCardQuery.kt")

    private fun pagingContract(): String = source("core/anki/AnkiCardPaging.kt")

    private fun fakeBackend(): String = testSource("anki/fake/FakeAnkiBackend.kt")

    @Test
    fun `INV-15-01 browser UI is backend neutral and free of provider or storage access`() {
        val ui = browserUiSource()
        noForbidden(
            ui,
            Regex("ContentResolver|FlashCardsContract|AnkiConnect|java\\.io\\.File|Uri\\.parse"),
            "the browser presentation and ViewModel"
        )
        assertTrue(ui.contains("AnkiBackend"))
    }

    @Test
    fun `INV-15-02 one browse entry point exists and no parallel query verb does`() {
        noForbidden(
            allMainSources,
            Regex("fun (searchCards|filterCards|sortCards|browseDeckCards|getCardsPage)\\s*\\("),
            "any production card-query verb other than browseCards (§1)"
        )
        val backend = source("core/anki/AnkiBackend.kt")
        assertTrue(backend.contains("suspend fun browseCards(query: AnkiCardQuery): AnkiResult<AnkiCardPage>"))
        assertTrue(backend.contains("UnsupportedQueryFeature(feature = \"card_browser\")"))
        val query = queryContract()
        assertTrue(query.contains("data class AnkiCardQuery("))
        for (component in listOf(
            "val scope: AnkiCardScope",
            "val text: String?",
            "val filters: AnkiCardFilters",
            "val sort: AnkiCardSort",
            "val page: AnkiPageRequest"
        )) assertTrue("query component missing: $component", query.contains(component))
    }

    @Test
    fun `INV-15-03 scope is explicit and deck identity is never nullable`() {
        val query = queryContract()
        assertTrue(query.contains("sealed interface AnkiCardScope"))
        assertTrue(query.contains("data object AllCards"))
        assertTrue(query.contains("data class Deck("))
        assertTrue(query.contains("val includeChildren: Boolean = true"))
        noForbidden(query, Regex("deckId: String\\?"), "an ambiguously nullable deck scope")
    }

    @Test
    fun `INV-15-04 search stays literal user text and normalization only trims`() {
        val normalizer = queryContract()
            .substringAfter("fun normalizeAnkiCardSearchText")
            .substringBefore("internal fun canonicalToken")
        assertTrue(normalizer.contains("Character.isSpaceChar"))
        assertTrue(normalizer.contains("isWhitespace"))
        noForbidden(
            normalizer,
            Regex("lowercase\\(|uppercase\\(|normalize\\(|tokenize"),
            "search normalization"
        )
        val mapper = source("ui/screens/cardbrowser/CardBrowserQueryMapper.kt")
        assertTrue(mapper.contains("normalizeAnkiCardSearchText"))
        assertTrue(mapper.contains("scope = query.scope"))
        // The UI may never send backend query syntax through the free-text field (§10).
        noForbidden(
            browserUiSource(),
            Regex("\"(deck|tag|is|prop|note|card|flag):"),
            "backend query syntax in the browser UI"
        )
    }

    @Test
    fun `INV-15-05 filters are explicit categories never nullable booleans or queue numbers`() {
        val filters = queryContract()
            .substringAfter("data class AnkiCardFilters(")
            .substringBefore("\n}")
        for (field in listOf(
            "val flags: Set<AnkiFlag>",
            "val tags: Set<String>",
            "val cardTypes: Set<AnkiCardType>",
            "val suspension: SuspensionFilter",
            "val burial: BurialFilter"
        )) assertTrue("filter field missing: $field", filters.contains(field))
        noForbidden(filters, Regex("Boolean\\?"), "a nullable boolean filter")
        val query = queryContract()
        assertTrue(query.contains("enum class SuspensionFilter"))
        assertTrue(query.contains("enum class BurialFilter"))
        for (state in listOf("Any", "SuspendedOnly", "NotSuspended", "BuriedOnly", "NotBuried")) {
            assertTrue("missing tri-state value $state", query.contains(state))
        }
        assertTrue(query.contains("suspension == SuspensionFilter.Any"))
        noForbidden(
            source("ui/components/anki/CardBrowserFilters.kt"),
            Regex("queueState|queueNumber"),
            "queue-number inference inside the filter UI"
        )
    }

    @Test
    fun `INV-15-06 unsupported components are refused by token after structural validation`() {
        val query = queryContract()
        for (token in listOf(
            "card_browser", "card_browser_scope_all", "card_browser_deck_scope",
            "card_browser_child_decks", "card_page_limit", "card_search",
            "card_filter_flags", "card_filter_tags", "card_filter_types",
            "card_filter_suspended", "card_filter_buried", "card_sort_"
        )) assertTrue("missing unsupported token $token", query.contains(token))
        assertTrue(query.contains("fun unsupportedFeature(capabilities: AnkiCardBrowserCapabilities): String?"))
        assertTrue(query.contains("structuralError() ?: unsupportedFeature(capabilities)"))
        assertTrue(source("data/anki/ankidroid/AnkiDroidCardQueryMapper.kt").contains("AnkiDroidCardQueryMapping.Unsupported"))
        assertTrue(
            source("data/anki/ankidroid/AnkiDroidCardBrowserGateway.kt")
                .contains("AnkiError.UnsupportedQueryFeature(feature = mapping.feature)")
        )
        assertTrue(source("data/anki/ankidroid/AnkiDroidBackend.kt").contains("normalized.preflightError("))
    }

    @Test
    fun `INV-15-07 sorts are explicit directional keys with a deterministic backend tie-breaker`() {
        val query = queryContract()
        assertTrue(query.contains("sealed interface AnkiCardSort"))
        assertTrue(query.contains("data object Default"))
        assertTrue(query.contains("data class Due(val direction: SortDirection)"))
        assertTrue(query.contains("enum class SortDirection"))
        assertTrue(query.contains("ASCENDING"))
        assertTrue(query.contains("DESCENDING"))
        noForbidden(query, Regex("Random"), "a random sort key")
        val fake = fakeBackend()
        assertTrue(fake.contains("keyComparator"))
        assertTrue(fake.contains("first.ref.stableKey.compareTo(second.ref.stableKey)"))
        for (backendReported in listOf(
            "dueEpochSeconds", "noteCreatedEpochSeconds", "noteModifiedEpochSeconds"
        )) assertTrue("sort key not backend-reported: $backendReported", fake.contains(backendReported))
        // The UI never re-sorts a partially loaded page (§26/§64).
        noForbidden(
            browserUiSource(),
            Regex("sortedWith|sortedBy \\{|sortedByDescending|sortedDescending|sortedBy \\{ it"),
            "client-side card sorting"
        )
    }

    @Test
    fun `INV-15-08 page bounds are explicit and cursors stay opaque to the UI`() {
        val paging = pagingContract()
        assertTrue(paging.contains("const val MIN_LIMIT: Int = 1"))
        assertTrue(paging.contains("const val DEFAULT_LIMIT: Int = 50"))
        assertTrue(paging.contains("const val MAX_LIMIT: Int = 100"))
        assertTrue(paging.contains("value class AnkiPageCursor(val value: String)"))
        assertTrue(paging.contains("value class AnkiQuerySnapshotToken(val value: String)"))
        val query = queryContract()
        assertTrue(query.contains("card_page_limit_below_minimum"))
        assertTrue(query.contains("card_page_limit_above_maximum"))
        noForbidden(
            query + paging,
            Regex("coerceIn|coerceAtMost"),
            "silent clamping of a requested page size"
        )
        val ui = browserUiSource()
        assertTrue(
            source("ui/screens/cardbrowser/CardBrowserViewModel.kt")
                .contains("private var nextCursor: AnkiPageCursor?")
        )
        noForbidden(
            ui,
            Regex("AnkiPageCursor\\(|offsetFor|v1:o="),
            "cursor construction or parsing outside the backend adapter"
        )
    }

    @Test
    fun `INV-15-09 unknown totals and exhausted pages stay distinguishable`() {
        val paging = pagingContract()
        assertTrue(paging.contains("require(items.isNotEmpty() || nextCursor == null)"))
        assertTrue(paging.contains("nextCursor = if (items.isEmpty()) null else nextCursor"))
        assertTrue(paging.contains("val totalCount: Int? = null"))
        val viewModel = source("ui/screens/cardbrowser/CardBrowserViewModel.kt")
        assertTrue(viewModel.contains("page.nextCursor != null"))
        assertTrue(viewModel.contains("supportsTotalCount"))
        noForbidden(browserUiSource(), Regex("totalCount \\?:"), "a fabricated zero total")
        val models = source("ui/screens/cardbrowser/CardBrowserModels.kt")
        assertTrue(models.contains("NO_CARDS_IN_SCOPE"))
        assertTrue(models.contains("NO_MATCHES"))
    }

    @Test
    fun `INV-15-10 rows are lightweight text only and never hydrated in the list`() {
        val models = source("ui/screens/cardbrowser/CardBrowserModels.kt")
        assertTrue(models.contains("private fun safePreview"))
        assertTrue(models.contains("PREVIEW_MAX_CHARS = 220"))
        assertTrue(models.contains("answerPreviewAllowed"))
        val listItem = source("core/anki/AnkiModels.kt")
            .substringAfter("data class AnkiCardListItem(")
            .substringBefore("\n}\n")
        for (identity in listOf("val cardRef: AnkiCardRef", "val noteRef: AnkiNoteRef?", "val deckRef: AnkiDeckRef?")) {
            assertTrue("list identity missing: $identity", listItem.contains(identity))
        }
        noForbidden(
            listItem,
            Regex("questionHtml|answerHtml|AnkiMediaRef|ContentResolver|Uri\\b"),
            "the lightweight list projection"
        )
        noForbidden(
            source("ui/components/anki/CardBrowserRow.kt"),
            Regex("AndroidView|WebView\\s*\\(|hydrateCardContent\\s*\\(|AnkiRenderedCard|import .*AnkiBackend"),
            "the list-row Composable"
        )
    }

    @Test
    fun `INV-15-11 browsing stays read-only and cancellation is never an error`() {
        noForbidden(
            browserUiSource(),
            Regex(
                "\\.commitRating\\(|\\.performReviewerAction\\(|\\.dispatchReviewerAction\\(" +
                    "|\\.hydrateCardContent\\(|\\.beginReview\\(|\\.nextCard\\("
            ),
            "browser UI actions"
        )
        val viewModel = source("ui/screens/cardbrowser/CardBrowserViewModel.kt")
        assertTrue(viewModel.contains("targetBackend.browseCards(request)"))
        assertTrue(viewModel.contains("catch (cancellation: CancellationException)"))
        assertTrue(viewModel.contains("throw cancellation"))
        val errors = source("core/anki/AnkiErrors.kt")
        assertTrue(errors.contains("INV-15-Q13"))
        assertTrue(errors.contains("INV-15-Q14"))
        assertTrue(errors.contains("INV-15-Q19"))
    }

    @Test
    fun `INV-15-12 stale pages and query changes invalidate paging without a silent restart`() {
        val viewModel = source("ui/screens/cardbrowser/CardBrowserViewModel.kt")
        for (guard in listOf(
            "generation += 1",
            "invalidatePaging()",
            "nextCursor = null",
            "nextCursor == token.cursor",
            "backend === targetBackend",
            "query == token.query",
            "val cursor = nextCursor ?: return",
            "AnkiError.InvalidQuery(detail = \"stale_card_browser_request\")"
        )) assertTrue("stale-page guard missing: $guard", viewModel.contains(guard))
        val fake = fakeBackend()
        assertTrue(fake.contains("AnkiError.InvalidCursor(\"malformed_cursor\")"))
        assertTrue(fake.contains("AnkiError.InvalidCursor(\"cursor_query_mismatch\")"))
        assertTrue(fake.contains("cursor_offset_out_of_range"))
    }

    @Test
    fun `INV-15-13 missing row identity is a typed data integrity failure never an invented ref`() {
        val paging = pagingContract()
        for (token in listOf(
            "foreign_card_ref", "card_row_missing_note_identity",
            "card_row_missing_deck_identity", "card_row_out_of_scope"
        )) assertTrue("missing identity token $token", paging.contains(token))
        val viewModel = source("ui/screens/cardbrowser/CardBrowserViewModel.kt")
        assertTrue(viewModel.contains("item.pageIdentityError("))
        assertTrue(viewModel.contains("AnkiError.DataIntegrityFailure("))
        assertTrue(fakeBackend().contains("pageIdentityError"))
    }

    @Test
    fun `INV-15-14 the backend validates the whole query before slicing one bounded page`() {
        val fake = fakeBackend()
        val browse = fake.substringAfter("private suspend fun performBrowse")
            .substringBefore("override suspend fun getSelectedDeck()")
        assertTrue(browse.indexOf("query.structuralError()") < browse.indexOf("query.unsupportedFeature(capabilities)"))
        assertTrue(browse.indexOf("query.unsupportedFeature(capabilities)") < browse.indexOf("pager::offsetFor"))
        assertTrue(browse.indexOf("cardData.filter") < browse.indexOf(".drop(offset)"))
        assertTrue(browse.indexOf("val ordered = orderBy(") < browse.indexOf(".drop(offset)"))
        // §6 — a missing deck is a typed NotFound, never an empty page.
        assertTrue(browse.contains("AnkiError.DeckNotFound(AnkiDeckRef(id, scope.deckId, collectionKey))"))
        // §19 — tag filters are AND (all requested tags), never OR.
        assertTrue(browse.contains("filters.tags.any { it !in card.metadata.tags }"))
    }

    @Test
    fun `INV-15-15 capabilities are structured per feature and drive every UI affordance`() {
        val capabilities = source("core/anki/AnkiCapabilities.kt")
        assertTrue(capabilities.contains("val cardBrowser: AnkiCardBrowserCapabilities"))
        for (field in listOf(
            "val canBrowseAllCards: Boolean = false",
            "val canBrowseDeck: Boolean = false",
            "val canIncludeChildDecks: Boolean = false",
            "val canSearchText: Boolean = false",
            "val supportedFilters: Set<AnkiCardFilterCapability> = emptySet()",
            "val supportedSorts: Set<AnkiCardSortCapability> = emptySet()",
            "val supportsTotalCount: Boolean = false",
            "val maxPageSize: Int = AnkiPageRequest.MAX_LIMIT"
        )) assertTrue("capability field missing: $field", capabilities.contains(field))
        noForbidden(
            capabilities.substringAfter("data class AnkiCardBrowserCapabilities(").substringBefore("\n}"),
            Regex("cardFilters"),
            "a coarse cardFilters capability"
        )
        assertTrue(source("ui/screens/cardbrowser/CardBrowserScreen.kt").contains("state.capabilities.canSearchText"))
        assertTrue(source("ui/components/anki/CardBrowserFilters.kt").contains("filterChipGroups(capabilities, filters)"))
        assertTrue(source("ui/components/anki/CardBrowserSortMenu.kt").contains("sortOptions(capabilities)"))
        assertTrue(source("ui/screens/library/DeckDetailsScreen.kt").contains("current.capabilities.cardBrowser.canBrowseDeck"))
        val diagnostics = source("data/repository/DiagnosticsRepository.kt")
        assertTrue(diagnostics.contains("capabilities.cardBrowser.canBrowse"))
        assertTrue(diagnostics.contains("capabilities.cardBrowser.canSearchText"))
        val policy = source("data/anki/ankidroid/AnkiDroidCompatibilityPolicy.kt")
        assertTrue(policy.contains("implemented.cardBrowser.canBrowse"))
        assertTrue(policy.contains("implemented.cardBrowser.canSearchText"))
    }

    @Test
    fun `INV-15-16 browser states stay canonical accessible and RTL safe`() {
        val models = source("ui/screens/cardbrowser/CardBrowserModels.kt")
        for (state in listOf("Loading", "Ready", "Empty", "Unavailable", "Error")) {
            assertTrue("missing UI state $state", models.contains("data class $state("))
        }
        val screen = source("ui/screens/cardbrowser/CardBrowserScreen.kt")
        for (state in listOf("Loading", "Ready", "Empty", "Unavailable", "Error")) {
            assertTrue("screen does not render $state", screen.contains("is CardBrowserUiState.$state"))
        }
        val row = source("ui/components/anki/CardBrowserRow.kt")
        val filters = source("ui/components/anki/CardBrowserFilters.kt")
        for (text in listOf(screen, row, filters)) {
            assertTrue(text.contains("TextDirection.ContentOrLtr"))
            assertTrue(text.contains("contentDescription"))
            assertTrue(text.contains("semantics"))
        }
        assertTrue(row.contains("mergeDescendants = true"))
        assertTrue(screen.contains("key = { it.stableKey }"))
        assertTrue(screen.contains("onOpenCard(row.cardRef)"))
    }

    @Test
    fun `INV-15-17 details navigation carries identity only and GATE 16 owns hydration`() {
        val routes = source("ui/navigation/Screen.kt")
        val navHost = source("ui/navigation/AppNavHost.kt")
        val placeholder = source("ui/screens/cardbrowser/CardDetailsPlaceholderScreen.kt")
        assertTrue(routes.contains("fun createRoute(cardRef: AnkiCardRef)"))
        assertTrue(navHost.contains("CardBrowserViewModel(initialBackend = container.ankiDroidBackend"))
        noForbidden(routes, Regex("AnkiRenderedCard|questionHtml"), "the details route payload")
        assertTrue(placeholder.contains("GATE 16 owns one-card hydration"))
        noForbidden(
            placeholder,
            Regex("hydrateCardContent\\(|AnkiRenderedCard|WebView"),
            "the GATE 15 Card Details placeholder"
        )
    }

    @Test
    fun `INV-15-18 every query selection restarts from a fresh first page`() {
        val viewModel = source("ui/screens/cardbrowser/CardBrowserViewModel.kt")
        assertTrue(viewModel.contains("fun refresh() = restartQuery(debounceMs = 0L)"))
        assertTrue(viewModel.contains("restartQuery(debounceMs = searchDebounceMs)"))
        assertTrue(viewModel.contains("fun setFilters"))
        assertTrue(viewModel.contains("fun setSort"))
        assertTrue(viewModel.contains("fun setScope"))
        assertTrue(viewModel.contains("fun setSearchText"))
        assertTrue(viewModel.contains("private fun restartQuery(debounceMs: Long)"))
    }

    @Test
    fun `INV-15-19 browser capability truth comes from one structured source`() {
        val preflight = queryContract()
            .substringAfter("fun unsupportedFeature(capabilities: AnkiCardBrowserCapabilities): String?")
            .substringBefore("fun preflightError")
        noForbidden(
            preflight,
            Regex("AnkiCapabilities\\b"),
            "a second capability source inside the browser preflight (§62)"
        )
        assertTrue(
            source("ui/screens/cardbrowser/CardBrowserViewModel.kt")
                .contains("backendSnapshot.capabilities.cardBrowser")
        )
        assertTrue(source("data/anki/ankidroid/AnkiDroidBackend.kt").contains("capabilities.cardBrowser"))
        assertTrue(
            source("data/anki/ankidroid/AnkiDroidCompatibilityPolicy.kt")
                .contains("implemented.cardBrowser.canSearchText")
        )
    }

    private companion object {
        val BROWSER_UI_FILES = listOf(
            "ui/screens/cardbrowser/CardBrowserScreen.kt",
            "ui/screens/cardbrowser/CardBrowserViewModel.kt",
            "ui/screens/cardbrowser/CardBrowserModels.kt",
            "ui/screens/cardbrowser/CardBrowserQueryMapper.kt",
            "ui/screens/cardbrowser/CardDetailsPlaceholderScreen.kt",
            "ui/components/anki/CardBrowserFilters.kt",
            "ui/components/anki/CardBrowserSortMenu.kt",
            "ui/components/anki/CardBrowserRow.kt"
        )
    }
}
