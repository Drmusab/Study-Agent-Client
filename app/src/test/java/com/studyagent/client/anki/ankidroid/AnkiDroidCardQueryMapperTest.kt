package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardQuery
import com.studyagent.client.core.anki.AnkiCardSort
import com.studyagent.client.core.anki.AnkiCardType
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.AnkiPageRequest
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCardQueryMapping
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCardQueryMapper
import com.studyagent.client.data.anki.ankidroid.UnsupportedAnkiDroidCardBrowserGateway
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCompatibilityPolicy
import com.studyagent.client.data.anki.ankidroid.CapabilitySupport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiDroidCardQueryMapperTest {
    @Test
    fun `deck scope and paging fail explicitly because public card collection is item-only`() {
        val scoped = AnkiCardQuery(deckId = "deck-42", page = AnkiPageRequest(limit = 17, cursor = "cursor"))
        assertEquals(
            AnkiDroidCardQueryMapping.Unsupported("card_browser_deck_scope"),
            AnkiDroidCardQueryMapper.map(scoped)
        )
        assertEquals(
            AnkiDroidCardQueryMapping.Unsupported("card_browser_public_api"),
            AnkiDroidCardQueryMapper.map(AnkiCardQuery(page = AnkiPageRequest(limit = 17)))
        )
    }

    @Test
    fun `text search flags filters and sorts are not silently omitted`() {
        assertEquals(
            "card_search",
            (AnkiDroidCardQueryMapper.map(AnkiCardQuery(text = "QRS")) as AnkiDroidCardQueryMapping.Unsupported).feature
        )
        assertEquals(
            "card_filter_flags",
            (AnkiDroidCardQueryMapper.map(AnkiCardQuery(filters = AnkiCardFilters(flags = setOf(AnkiFlag.RED))))
                as AnkiDroidCardQueryMapping.Unsupported).feature
        )
        assertEquals(
            "card_filter_tags",
            (AnkiDroidCardQueryMapper.map(AnkiCardQuery(filters = AnkiCardFilters(tags = setOf("cardiology"))))
                as AnkiDroidCardQueryMapping.Unsupported).feature
        )
        assertEquals(
            "card_filter_types",
            (AnkiDroidCardQueryMapper.map(AnkiCardQuery(filters = AnkiCardFilters(cardTypes = setOf(AnkiCardType.REVIEW))))
                as AnkiDroidCardQueryMapping.Unsupported).feature
        )
        assertEquals(
            "card_sort",
            (AnkiDroidCardQueryMapper.map(AnkiCardQuery(sort = AnkiCardSort.Reps))
                as AnkiDroidCardQueryMapping.Unsupported).feature
        )
    }

    @Test
    fun `unsupported gateway returns a typed read error without provider traffic`() = runTest {
        val gateway = UnsupportedAnkiDroidCardBrowserGateway()
        val result = gateway.browseCards(AnkiCardQuery())
        assertTrue(result is AnkiResult.Failure)
        assertEquals(
            AnkiError.UnsupportedAction("card_browser_public_api"),
            (result as AnkiResult.Failure).error
        )
    }

    @Test
    fun `pinned capability matrix never advertises card browse or card search`() {
        val api = AnkiDroidCompatibilityPolicy.apiCapabilitiesForSpec(2)
        val implemented = AnkiDroidCompatibilityPolicy.implementedCapabilitiesFor(2, isReady = true)
        assertEquals(CapabilitySupport.UNSUPPORTED, api.cardBrowser)
        assertEquals(CapabilitySupport.UNSUPPORTED, api.cardSearch)
        assertTrue(!implemented.cardBrowser.browse)
        assertTrue(!implemented.cardBrowser.textSearch)
    }
}
