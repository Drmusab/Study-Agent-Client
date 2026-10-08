package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiCardBrowserCapabilities
import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardQuery
import com.studyagent.client.core.anki.AnkiCardSort
import com.studyagent.client.core.anki.AnkiCardType
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.AnkiPageRequest
import com.studyagent.client.core.anki.normalizeAnkiCardSearchText
import com.studyagent.client.core.anki.unsupportedFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnkiCardQueryTest {
    @Test
    fun `search normalization trims and collapses unicode whitespace without case folding`() {
        assertNull(normalizeAnkiCardSearchText(" \t\n\u00a0 "))
        assertEquals("QRS duration α", normalizeAnkiCardSearchText("  QRS\t duration\nα  "))
        assertEquals("ألم القلب", normalizeAnkiCardSearchText("ألم\u2003القلب"))
        assertEquals("Case-Sensitive", normalizeAnkiCardSearchText(" Case-Sensitive "))
    }

    @Test
    fun `page request is explicitly bounded and cursor remains opaque`() {
        assertEquals(AnkiPageRequest.DEFAULT_LIMIT, AnkiPageRequest.DEFAULT.limit)
        assertEquals("backend:opaque:cursor", AnkiPageRequest(limit = 12, cursor = "backend:opaque:cursor").cursor)
        assertBoundedFailure { AnkiPageRequest(limit = 0) }
        assertBoundedFailure { AnkiPageRequest(limit = AnkiPageRequest.MAX_LIMIT + 1) }
        assertBoundedFailure { AnkiPageRequest(cursor = "  ") }
    }

    @Test
    fun `query validates and snapshots its neutral request pieces`() {
        val query = AnkiCardQuery(
            deckId = "deck-id",
            text = "ECG term",
            filters = AnkiCardFilters(
                flags = setOf(AnkiFlag.RED),
                tags = setOf("cardiology", "طب"),
                cardTypes = setOf(AnkiCardType.REVIEW),
                suspended = false,
                buried = true
            ),
            sort = AnkiCardSort.Lapses,
            page = AnkiPageRequest(limit = 25)
        )
        assertEquals("deck-id", query.deckId)
        assertEquals(setOf("cardiology", "طب"), query.filters.tags)
        assertEquals(AnkiCardSort.Lapses, query.sort)
        assertEquals(25, query.page.limit)
        assertEquals("card_filter_flags", AnkiCardQuery(filters = AnkiCardFilters(flags = setOf(AnkiFlag.BLUE)))
            .unsupportedFeature(AnkiCardBrowserCapabilities(browse = true)))
    }

    @Test
    fun `unsupported query components are explicit and never dropped`() {
        val browse = AnkiCardBrowserCapabilities(browse = true, deckScope = true, textSearch = true)
        assertEquals("card_filter_tags", AnkiCardQuery(filters = AnkiCardFilters(tags = setOf("x")))
            .unsupportedFeature(browse))
        assertEquals("card_filter_types", AnkiCardQuery(filters = AnkiCardFilters(cardTypes = setOf(AnkiCardType.NEW)))
            .unsupportedFeature(browse))
        assertEquals("card_filter_suspended", AnkiCardQuery(filters = AnkiCardFilters(suspended = true))
            .unsupportedFeature(browse))
        assertEquals("card_filter_buried", AnkiCardQuery(filters = AnkiCardFilters(buried = false))
            .unsupportedFeature(browse))
        assertEquals("card_sort_due", AnkiCardQuery(sort = AnkiCardSort.Due).unsupportedFeature(browse))
        assertEquals("card_search", AnkiCardQuery(text = "query").unsupportedFeature(browse.copy(textSearch = false)))
        assertEquals("card_browser_deck_scope", AnkiCardQuery(deckId = "d").unsupportedFeature(browse.copy(deckScope = false)))
    }

    private fun assertBoundedFailure(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected invalid page request")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
