package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardQuery
import com.studyagent.client.core.anki.AnkiCardSort
import com.studyagent.client.core.anki.AnkiCardType
import com.studyagent.client.core.anki.AnkiCardScope
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.AnkiPageRequest
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.BurialFilter
import com.studyagent.client.core.anki.SortDirection
import com.studyagent.client.core.anki.SuspensionFilter
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCardQueryMapping
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCardQueryMapper
import com.studyagent.client.data.anki.ankidroid.UnsupportedAnkiDroidCardBrowserGateway
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCompatibilityPolicy
import com.studyagent.client.data.anki.ankidroid.CapabilitySupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiDroidCardQueryMapperTest {
    private fun refusal(query: AnkiCardQuery): String =
        (AnkiDroidCardQueryMapper.map(query) as AnkiDroidCardQueryMapping.Unsupported).feature

    @Test
    fun `deck scope and the unfiltered collection request fail explicitly as item-only provider`() {
        assertEquals(
            "card_browser_deck_scope",
            refusal(AnkiCardQuery(scope = AnkiCardScope.Deck("deck-42")))
        )
        assertEquals(
            "card_browser_scope_all",
            refusal(AnkiCardQuery(scope = AnkiCardScope.AllCards))
        )
    }

    @Test
    fun `text search flags filters and sorts are not silently omitted`() {
        assertEquals("card_search", refusal(AnkiCardQuery(text = "QRS")))
        assertEquals(
            "card_filter_flags",
            refusal(AnkiCardQuery(filters = AnkiCardFilters(flags = setOf(AnkiFlag.RED))))
        )
        assertEquals(
            "card_filter_tags",
            refusal(AnkiCardQuery(filters = AnkiCardFilters(tags = setOf("cardiology"))))
        )
        assertEquals(
            "card_filter_types",
            refusal(AnkiCardQuery(filters = AnkiCardFilters(cardTypes = setOf(AnkiCardType.REVIEW))))
        )
        assertEquals(
            "card_filter_suspended",
            refusal(AnkiCardQuery(filters = AnkiCardFilters(suspension = SuspensionFilter.SuspendedOnly)))
        )
        assertEquals(
            "card_filter_buried",
            refusal(AnkiCardQuery(filters = AnkiCardFilters(burial = BurialFilter.BuriedOnly)))
        )
        // §22 — explicit sorts are refused by their own token, and the direction is part of the query.
        assertEquals("card_sort_due", refusal(AnkiCardQuery(sort = AnkiCardSort.Due(SortDirection.ASCENDING))))
        assertEquals("card_sort_reps", refusal(AnkiCardQuery(sort = AnkiCardSort.Reps(SortDirection.DESCENDING))))
        assertEquals("card_sort_lapses", refusal(AnkiCardQuery(sort = AnkiCardSort.Lapses(SortDirection.ASCENDING))))
    }

    @Test
    fun `unsupported gateway returns a typed read error without provider traffic`() = runBlocking {
        val gateway = UnsupportedAnkiDroidCardBrowserGateway()
        val result = gateway.browseCards(AnkiCardQuery())
        assertTrue(result is AnkiResult.Failure)
        val error = (result as AnkiResult.Failure).error
        // §20/§51 — a valid but unimplementable semantic is a typed refusal, never a silently
        // degraded or empty result.
        assertTrue(error is AnkiError.UnsupportedQueryFeature)
        assertEquals("card_browser_scope_all", (error as AnkiError.UnsupportedQueryFeature).feature)
    }

    @Test
    fun `pinned capability matrix never advertises card browse or card search`() {
        val api = AnkiDroidCompatibilityPolicy.apiCapabilitiesForSpec(2)
        val implemented = AnkiDroidCompatibilityPolicy.implementedCapabilitiesFor(2, isReady = true)
        assertEquals(CapabilitySupport.UNSUPPORTED, api.cardBrowser)
        assertEquals(CapabilitySupport.UNSUPPORTED, api.cardSearch)
        assertTrue(!implemented.cardBrowser.canBrowse)
        assertTrue(!implemented.cardBrowser.canSearchText)
        assertTrue(!implemented.cardBrowser.canBrowseDeck)
        assertTrue(!implemented.cardBrowser.canIncludeChildDecks)
        assertTrue(implemented.cardBrowser.supportedFilters.isEmpty())
        assertTrue(implemented.cardBrowser.supportedSorts.isEmpty())
        assertTrue(!implemented.cardBrowser.supportsTotalCount)
    }
}
