package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardBrowserCapabilities
import com.studyagent.client.core.anki.AnkiCardFilterCapability
import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardListItem
import com.studyagent.client.core.anki.AnkiCardPage
import com.studyagent.client.core.anki.AnkiCardQuery
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiCardScope
import com.studyagent.client.core.anki.AnkiCardSort
import com.studyagent.client.core.anki.AnkiCardSortCapability
import com.studyagent.client.core.anki.AnkiCardType
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.AnkiOffsetCursorAdapter
import com.studyagent.client.core.anki.AnkiPageCursor
import com.studyagent.client.core.anki.AnkiPageRequest
import com.studyagent.client.core.anki.BurialFilter
import com.studyagent.client.core.anki.SortDirection
import com.studyagent.client.core.anki.SuspensionFilter
import com.studyagent.client.core.anki.normalizeAnkiCardSearchText
import com.studyagent.client.core.anki.pageIdentityError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 15 §2-§52 — the normative query model, its normalization, its validation order, its
 * canonical fingerprint and its paging/identity rules. These are pure domain tests: no backend is
 * involved, so a backend cannot "fix" a semantic problem here by accident.
 */
class AnkiCardQueryTest {

    private val backendId = AnkiBackendId.Fake("query-test")

    private val fullCapabilities = AnkiCardBrowserCapabilities(
        canBrowseAllCards = true,
        canBrowseDeck = true,
        canIncludeChildDecks = true,
        canSearchText = true,
        supportedFilters = AnkiCardFilterCapability.entries.toSet(),
        supportedSorts = AnkiCardSortCapability.entries.toSet(),
        supportsTotalCount = true,
        maxPageSize = AnkiPageRequest.MAX_LIMIT
    )

    // ------------------------------------------------------------------------------ §9 search text

    @Test
    fun `search normalization trims and maps empty to null without touching case or unicode`() {
        assertNull(normalizeAnkiCardSearchText(" \t\n\u00a0 "))
        assertNull(normalizeAnkiCardSearchText(""))
        assertEquals("Case-Sensitive", normalizeAnkiCardSearchText("  Case-Sensitive  "))
        assertEquals("ألم\u2003القلب", normalizeAnkiCardSearchText(" ألم\u2003القلب "))
        assertEquals(
            "Internal whitespace is preserved exactly: user text is literal (§9)",
            "two  spaces",
            normalizeAnkiCardSearchText("  two  spaces ")
        )
        assertEquals("QRS duration", normalizeAnkiCardSearchText("QRS duration"))
    }

    @Test
    fun `query normalization never rewrites the stored query object`() {
        val raw = AnkiCardQuery(text = "  spaced  ")
        assertEquals("spaced", raw.normalized().text)
        assertEquals("The original query keeps the user's text", "  spaced  ", raw.text)
        assertNull(AnkiCardQuery(text = "   ").normalizedText)
    }

    // --------------------------------------------------------------------------- §50 structural

    @Test
    fun `structural problems are typed InvalidQuery errors instead of exceptions`() {
        val belowMinimum = AnkiCardQuery(page = AnkiPageRequest(limit = 0))
        assertEquals(
            AnkiError.InvalidQuery(detail = "card_page_limit_below_minimum"),
            belowMinimum.structuralError()
        )
        val aboveMaximum = AnkiCardQuery(page = AnkiPageRequest(limit = AnkiPageRequest.MAX_LIMIT + 1))
        assertEquals(
            AnkiError.InvalidQuery(detail = "card_page_limit_above_maximum"),
            aboveMaximum.structuralError()
        )
        assertEquals(
            AnkiError.InvalidQuery(detail = "card_scope_deck_id_blank"),
            AnkiCardQuery(scope = AnkiCardScope.Deck("  ")).structuralError()
        )
        assertEquals(
            AnkiError.InvalidQuery(detail = "card_filter_tag_blank"),
            AnkiCardQuery(filters = AnkiCardFilters(tags = setOf("ok", " "))).structuralError()
        )
        assertEquals(
            AnkiError.InvalidQuery(detail = "card_cursor_blank"),
            AnkiCardQuery(page = AnkiPageRequest(cursor = AnkiPageCursor(""))).structuralError()
        )
        assertNull(AnkiCardQuery().structuralError())
        assertEquals(AnkiPageRequest.DEFAULT_LIMIT, AnkiPageRequest.firstPage().limit)
        assertEquals(50, AnkiPageRequest.DEFAULT_LIMIT)
    }

    // ---------------------------------------------------------------------- §20/§51 capability

    @Test
    fun `unsupported components produce an explicit feature refusal`() {
        val query = AnkiCardQuery(
            scope = AnkiCardScope.Deck("deck-1"),
            text = "query",
            filters = AnkiCardFilters(
                flags = setOf(AnkiFlag.RED),
                tags = setOf("cardiology"),
                cardTypes = setOf(AnkiCardType.NEW),
                suspension = SuspensionFilter.NotSuspended,
                burial = BurialFilter.BuriedOnly
            ),
            sort = AnkiCardSort.Lapses(SortDirection.DESCENDING)
        )
        assertNull(query.unsupportedFeature(fullCapabilities))

        val nothing = AnkiCardBrowserCapabilities.NONE
        assertEquals("card_browser", query.unsupportedFeature(nothing))

        val browseOnly = AnkiCardBrowserCapabilities(canBrowseAllCards = true)
        assertEquals("card_browser_deck_scope", query.unsupportedFeature(browseOnly))
        assertEquals(
            "card_browser_scope_all",
            AnkiCardQuery().unsupportedFeature(AnkiCardBrowserCapabilities(canBrowseDeck = true))
        )
        assertEquals(
            "card_browser_child_decks",
            AnkiCardQuery(scope = AnkiCardScope.Deck("d", includeChildren = true))
                .unsupportedFeature(
                    AnkiCardBrowserCapabilities(canBrowseDeck = true, canIncludeChildDecks = false)
                )
        )
        assertEquals(
            "card_search",
            AnkiCardQuery(text = "x").unsupportedFeature(browseOnly)
        )
        assertEquals(
            "card_filter_flags",
            AnkiCardQuery(filters = AnkiCardFilters(flags = setOf(AnkiFlag.BLUE)))
                .unsupportedFeature(browseOnly)
        )
        assertEquals(
            "card_filter_tags",
            AnkiCardQuery(filters = AnkiCardFilters(tags = setOf("x"))).unsupportedFeature(browseOnly)
        )
        assertEquals(
            "card_filter_types",
            AnkiCardQuery(filters = AnkiCardFilters(cardTypes = setOf(AnkiCardType.NEW)))
                .unsupportedFeature(browseOnly)
        )
        assertEquals(
            "card_filter_suspended",
            AnkiCardQuery(filters = AnkiCardFilters(suspension = SuspensionFilter.SuspendedOnly))
                .unsupportedFeature(browseOnly)
        )
        assertEquals(
            "card_filter_buried",
            AnkiCardQuery(filters = AnkiCardFilters(burial = BurialFilter.BuriedOnly))
                .unsupportedFeature(browseOnly)
        )
        assertEquals(
            "card_sort_due",
            AnkiCardQuery(sort = AnkiCardSort.Due(SortDirection.ASCENDING)).unsupportedFeature(browseOnly)
        )
        assertEquals(
            "card_sort_reps",
            AnkiCardQuery(sort = AnkiCardSort.Reps(SortDirection.ASCENDING)).unsupportedFeature(browseOnly)
        )
        assertEquals(
            "card_sort_created",
            AnkiCardQuery(sort = AnkiCardSort.Created(SortDirection.ASCENDING)).unsupportedFeature(browseOnly)
        )
        assertEquals(
            "card_sort_modified",
            AnkiCardQuery(sort = AnkiCardSort.Modified(SortDirection.ASCENDING)).unsupportedFeature(browseOnly)
        )
        assertEquals(
            "card_sort_lapses",
            AnkiCardQuery(sort = AnkiCardSort.Lapses(SortDirection.ASCENDING)).unsupportedFeature(browseOnly)
        )
        assertEquals(
            "card_page_limit",
            AnkiCardQuery(page = AnkiPageRequest(limit = 90))
                .unsupportedFeature(browseOnly.copy(maxPageSize = 50))
        )
    }

    @Test
    fun `preflight validates structure before capability exactly as the contract orders it`() {
        val bothWrong = AnkiCardQuery(
            text = "x",
            page = AnkiPageRequest(limit = 0)
        )
        assertEquals(
            "Structural validity is checked first (§63)",
            AnkiError.InvalidQuery(detail = "card_page_limit_below_minimum"),
            bothWrong.preflightError(AnkiCardBrowserCapabilities.NONE)
        )
        assertEquals(
            AnkiError.UnsupportedQueryFeature(feature = "card_search"),
            AnkiCardQuery(text = "x").preflightError(AnkiCardBrowserCapabilities(canBrowseAllCards = true))
        )
        assertNull(AnkiCardQuery().preflightError(fullCapabilities))
    }

    @Test
    fun `a backend that cannot browse reports the browser itself as unsupported`() {
        val capabilities = AnkiCardBrowserCapabilities.NONE
        assertTrue(!capabilities.canBrowse)
        assertEquals("card_browser", AnkiCardQuery().unsupportedFeature(capabilities))
    }

    // -------------------------------------------------------------------------------- §12-§19

    @Test
    fun `filter model is explicit about tri-state semantics`() {
        val filters = AnkiCardFilters(
            flags = setOf(AnkiFlag.RED, AnkiFlag.ORANGE),
            tags = setOf("neurosurgery", "high-yield"),
            suspension = SuspensionFilter.NotSuspended,
            burial = BurialFilter.Any
        )
        assertEquals(setOf(AnkiFlag.RED, AnkiFlag.ORANGE), filters.flags)
        assertEquals(setOf("neurosurgery", "high-yield"), filters.tags)
        assertEquals(SuspensionFilter.NotSuspended, filters.suspension)
        assertEquals(BurialFilter.Any, filters.burial)
        assertTrue(!filters.isEmpty)
        assertTrue(AnkiCardFilters().isEmpty)
        assertEquals(
            "Any never means 'only false'",
            SuspensionFilter.Any,
            AnkiCardFilters().suspension
        )
    }

    // --------------------------------------------------------------------------------- §42 keys

    @Test
    fun `the consistency key covers backend, collection, scope, text, filters and sort but not the cursor`() {
        val base = AnkiCardQuery(
            scope = AnkiCardScope.Deck("deck-42", includeChildren = true),
            text = "  subarachnoid hemorrhage ",
            filters = AnkiCardFilters(
                flags = setOf(AnkiFlag.RED, AnkiFlag.ORANGE),
                tags = setOf("neurosurgery", "high-yield"),
                suspension = SuspensionFilter.NotSuspended
            ),
            sort = AnkiCardSort.Reps(SortDirection.DESCENDING),
            page = AnkiPageRequest(limit = 50)
        )
        val key = base.consistencyKey(backendId, "collection-1")

        assertEquals(
            "The paging cursor is not part of the query identity",
            key,
            base.copy(page = base.page.copy(cursor = AnkiPageCursor("page-2"))).consistencyKey(backendId, "collection-1")
        )
        assertEquals(
            "Filter-set order is not part of the identity",
            key,
            base.copy(
                filters = base.filters.copy(flags = setOf(AnkiFlag.ORANGE, AnkiFlag.RED), tags = setOf("high-yield", "neurosurgery"))
            ).consistencyKey(backendId, "collection-1")
        )

        val variants = listOf(
            base.copy(scope = AnkiCardScope.AllCards),
            base.copy(scope = AnkiCardScope.Deck("deck-42", includeChildren = false)),
            base.copy(text = "subarachnoid hemorrhage x"),
            base.copy(filters = base.filters.copy(flags = setOf(AnkiFlag.RED))),
            base.copy(filters = base.filters.copy(tags = setOf("neurosurgery"))),
            base.copy(filters = base.filters.copy(suspension = SuspensionFilter.Any)),
            base.copy(sort = AnkiCardSort.Reps(SortDirection.ASCENDING))
        )
        variants.forEach { variant ->
            assertNotEquals(
                "Scope/text/filters/sort changes must change the query identity",
                key,
                variant.consistencyKey(backendId, "collection-1")
            )
        }
        assertEquals(
            "Normalization happens before the key is built, so only the meaningful text counts",
            key,
            base.copy(text = "subarachnoid hemorrhage").consistencyKey(backendId, "collection-1")
        )
        assertNotEquals(key, base.consistencyKey(AnkiBackendId.Fake("other"), "collection-1"))
        assertNotEquals(key, base.consistencyKey(backendId, "collection-2"))
        assertNotEquals(key, base.consistencyKey(backendId, null))
    }

    // -------------------------------------------------------------------------------- §28-§38

    @Test
    fun `page results keep pagination and count honesty`() {
        val item = listItem(cardId = "c1")
        val page = AnkiCardPage.of(listOf(item), nextCursor = AnkiPageCursor("next"), totalCount = null)
        assertEquals(AnkiPageCursor("next"), page.nextCursor)
        assertNull("An unknown total is null, never zero", page.totalCount)
        assertNull("No backend may claim snapshot isolation it cannot prove", page.snapshotToken)

        val empty = AnkiCardPage.of(emptyList(), nextCursor = AnkiPageCursor("next"), totalCount = 0)
        assertNull("§38: an empty page can never carry a cursor", empty.nextCursor)
        assertEquals(0, empty.totalCount)
    }

    @Test
    fun `cursor adaptation is opaque and bound to one query`() {
        val adapter = AnkiOffsetCursorAdapter("backend=fake|scope=all|text=\"x\"")
        val cursor = adapter.cursorFor(100)
        assertEquals(
            AnkiOffsetCursorAdapter.Resolution.Valid(100),
            adapter.offsetFor(cursor)
        )
        val other = AnkiOffsetCursorAdapter("backend=fake|scope=all|text=\"y\"")
        assertEquals(
            "A cursor from another query can never silently resume the wrong result set",
            AnkiOffsetCursorAdapter.Resolution.ForeignQuery,
            other.offsetFor(cursor)
        )
        assertEquals(
            AnkiOffsetCursorAdapter.Resolution.Malformed,
            adapter.offsetFor(AnkiPageCursor("nonsense"))
        )
        assertEquals(
            AnkiOffsetCursorAdapter.Resolution.Malformed,
            adapter.offsetFor(AnkiPageCursor("v1:o=-4:k=${cursor.value.substringAfter("k=")}"))
        )
        assertTrue("The cursor is not human readable query state", !cursor.value.contains("scope=all"))
    }

    // -------------------------------------------------------------------------------- §43-§44

    @Test
    fun `rows without usable identity are detected instead of published`() {
        val good = listItem(cardId = "c1")
        assertNull(good.pageIdentityError(backendId))

        val otherBackend = good.copy(
            cardRef = good.cardRef.copy(backendId = AnkiBackendId.Fake("someone-else")),
            noteRef = null,
            deckRef = null
        )
        assertEquals("foreign_card_ref", otherBackend.pageIdentityError(backendId))

        val noNote = good.copy(noteRef = null, cardRef = good.cardRef.copy(noteId = null, cardOrd = null, cardId = "c1"))
        assertEquals("card_row_missing_note_identity", noNote.pageIdentityError(backendId))

        val noDeck = good.copy(deckRef = null)
        assertEquals("card_row_missing_deck_identity", noDeck.pageIdentityError(backendId))

        val wrongDeck = good.copy(
            deckRef = AnkiDeckRef(backendId, "other-deck", "collection-1")
        )
        assertEquals("card_row_out_of_scope", wrongDeck.pageIdentityError(backendId, requiredDeckId = "deck-1"))
    }

    private fun listItem(cardId: String): AnkiCardListItem = AnkiCardListItem(
        cardRef = AnkiCardRef(backendId, cardId = cardId, noteId = "note-$cardId", cardOrd = 0, collectionKey = "collection-1"),
        noteRef = AnkiNoteRef(backendId, "note-$cardId", "collection-1"),
        deckRef = AnkiDeckRef(backendId, "deck-1", "collection-1"),
        deckName = "Deck",
        questionText = "Question $cardId",
        answerText = "Answer $cardId"
    )
}
