package com.studyagent.client.ui

import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardScope
import com.studyagent.client.core.anki.AnkiCardSort
import com.studyagent.client.core.anki.AnkiCardType
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.AnkiPageCursor
import com.studyagent.client.core.anki.BurialFilter
import com.studyagent.client.core.anki.SortDirection
import com.studyagent.client.core.anki.SuspensionFilter
import com.studyagent.client.ui.screens.cardbrowser.CardBrowserQueryMapper
import com.studyagent.client.ui.screens.cardbrowser.CardBrowserQueryUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardBrowserQueryMapperTest {
    @Test
    fun `search deck scope filters and sort map to backend neutral domain query`() {
        val query = CardBrowserQueryMapper.toDomain(
            CardBrowserQueryUi(
                scope = AnkiCardScope.Deck(deckId = "deck-id", includeChildren = false),
                searchText = "  QRS\n duration ",
                filters = AnkiCardFilters(
                    flags = setOf(AnkiFlag.BLUE),
                    tags = setOf("cardiology", "طب"),
                    cardTypes = setOf(AnkiCardType.REVIEW),
                    suspension = SuspensionFilter.NotSuspended,
                    burial = BurialFilter.BuriedOnly
                ),
                sort = AnkiCardSort.Lapses(SortDirection.DESCENDING)
            ),
            pageSize = 32
        )
        assertEquals(AnkiCardScope.Deck("deck-id", includeChildren = false), query.scope)
        // §9 — normalization trims only; case, Unicode and internal whitespace survive verbatim.
        assertEquals("QRS\n duration", query.text)
        assertEquals(setOf(AnkiFlag.BLUE), query.filters.flags)
        assertEquals(setOf("cardiology", "طب"), query.filters.tags)
        assertEquals(setOf(AnkiCardType.REVIEW), query.filters.cardTypes)
        assertEquals(SuspensionFilter.NotSuspended, query.filters.suspension)
        assertEquals(BurialFilter.BuriedOnly, query.filters.burial)
        assertEquals(AnkiCardSort.Lapses(SortDirection.DESCENDING), query.sort)
        assertEquals(32, query.page.limit)
        assertNull(query.page.cursor)
    }

    @Test
    fun `blank search maps to null instead of an empty literal and keeps the scope`() {
        val query = CardBrowserQueryMapper.toDomain(
            CardBrowserQueryUi(
                scope = AnkiCardScope.Deck(deckId = "d", includeChildren = true),
                searchText = " \n\t"
            ),
            pageSize = 40
        )
        assertNull(query.text)
        assertEquals(AnkiCardScope.Deck("d", includeChildren = true), query.scope)
        assertNull(query.page.cursor)
    }

    @Test
    fun `pagination cursor is carried only when the ViewModel asks for a continuation`() {
        val first = CardBrowserQueryMapper.toDomain(CardBrowserQueryUi(), pageSize = 10)
        val next = CardBrowserQueryMapper.toDomain(
            CardBrowserQueryUi(), pageSize = 10, cursor = AnkiPageCursor("opaque-next")
        )
        assertNull(first.page.cursor)
        assertEquals(AnkiPageCursor("opaque-next"), next.page.cursor)
        assertEquals(AnkiPageCursor("opaque-next").value, next.page.cursor?.value)
    }
}
