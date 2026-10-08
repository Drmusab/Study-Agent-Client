package com.studyagent.client.ui

import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardSort
import com.studyagent.client.core.anki.AnkiCardType
import com.studyagent.client.core.anki.AnkiFlag
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
                deckId = "deck-id",
                searchText = "  QRS\n duration ",
                filters = AnkiCardFilters(
                    flags = setOf(AnkiFlag.BLUE),
                    tags = setOf("cardiology", "طب"),
                    cardTypes = setOf(AnkiCardType.REVIEW),
                    suspended = false,
                    buried = true
                ),
                sort = AnkiCardSort.Lapses
            ),
            pageSize = 32
        )
        assertEquals("deck-id", query.deckId)
        assertEquals("QRS duration", query.text)
        assertEquals(setOf(AnkiFlag.BLUE), query.filters.flags)
        assertEquals(setOf("cardiology", "طب"), query.filters.tags)
        assertEquals(setOf(AnkiCardType.REVIEW), query.filters.cardTypes)
        assertEquals(false, query.filters.suspended)
        assertEquals(true, query.filters.buried)
        assertEquals(AnkiCardSort.Lapses, query.sort)
        assertEquals(32, query.page.limit)
        assertNull(query.page.cursor)
    }

    @Test
    fun `page reset and blank search are explicit`() {
        val query = CardBrowserQueryMapper.toDomain(
            CardBrowserQueryUi(deckId = "d", searchText = " \n\t"),
            pageSize = 40,
            cursor = null
        )
        assertNull(query.text)
        assertNull(query.page.cursor)
    }

    @Test
    fun `pagination cursor is carried only when the ViewModel requests a continuation`() {
        val first = CardBrowserQueryMapper.toDomain(CardBrowserQueryUi(deckId = "d"), pageSize = 10)
        val next = CardBrowserQueryMapper.toDomain(
            CardBrowserQueryUi(deckId = "d"), pageSize = 10, cursor = "opaque-next"
        )
        assertNull(first.page.cursor)
        assertEquals("opaque-next", next.page.cursor)
    }
}
