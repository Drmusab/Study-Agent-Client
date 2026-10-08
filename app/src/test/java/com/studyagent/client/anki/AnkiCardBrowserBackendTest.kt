package com.studyagent.client.anki

import com.studyagent.client.anki.fake.CardBrowserTestFixtures
import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardQuery
import com.studyagent.client.core.anki.AnkiCardSort
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiPageRequest
import com.studyagent.client.core.anki.AnkiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiCardBrowserBackendTest {
    private val backendId = AnkiBackendId.Fake("card-browser")
    private val deckA = AnkiDeckRef(backendId, "deck-a", "collection")
    private val deckB = AnkiDeckRef(backendId, "deck-b", "collection")
    private val decks = listOf(AnkiDeck(deckA, "Medicine::Cardiology"), AnkiDeck(deckB, "Languages::العربية"))

    private fun backend(count: Int = 1_200) = FakeAnkiBackend(
        id = backendId,
        decks = decks,
        cards = CardBrowserTestFixtures.largeCardSet(backendId, listOf(deckA, deckB), count)
    )

    @Test
    fun `deck scoped pages stay bounded and cursor pages append distinct identities`() = runTest {
        val backend = backend()
        val first = (backend.browseCards(AnkiCardQuery(
            deckId = deckA.deckId,
            page = AnkiPageRequest(limit = 25)
        )) as AnkiResult.Success).value
        val second = (backend.browseCards(AnkiCardQuery(
            deckId = deckA.deckId,
            page = AnkiPageRequest(limit = 25, cursor = first.nextCursor)
        )) as AnkiResult.Success).value

        assertEquals(25, first.items.size)
        assertEquals(25, second.items.size)
        assertTrue(first.items.all { it.deckRef == deckA })
        assertTrue(second.items.all { it.deckRef == deckA })
        assertTrue(first.nextCursor != null)
        assertTrue((first.items + second.items).map { it.cardRef }.distinct().size == 50)
        assertEquals(600, first.totalCount)
        assertEquals(listOf(25, 25), backend.browseLimits)
        assertEquals(2, backend.browseCalls)
        assertEquals(0, backend.commitInvocations)
        assertEquals(0, backend.mutationBoundaryCrossingCount)
    }

    @Test
    fun `search is backend wide and Arabic tags and read only filters are authoritative fixture data`() = runTest {
        val backend = backend()
        val search = (backend.browseCards(AnkiCardQuery(text = "myocarditis")) as AnkiResult.Success).value
        assertTrue(search.items.isNotEmpty())
        assertTrue(search.items.all {
            it.questionText?.contains("myocarditis", ignoreCase = true) == true
        })

        val arabicTag = (backend.browseCards(AnkiCardQuery(
            filters = AnkiCardFilters(tags = setOf("طب")),
            page = AnkiPageRequest(limit = 100)
        )) as AnkiResult.Success).value
        assertTrue(arabicTag.items.isNotEmpty())
        assertTrue(arabicTag.items.all { "طب" in it.tags })

        val suspended = (backend.browseCards(AnkiCardQuery(
            filters = AnkiCardFilters(suspended = true),
            page = AnkiPageRequest(limit = 100)
        )) as AnkiResult.Success).value
        assertTrue(suspended.items.isNotEmpty())
        assertTrue(suspended.items.all { it.suspended == true })
        assertEquals(0, backend.commitInvocations)
        assertEquals(0, backend.backendEffectCount)
    }

    @Test
    fun `duplicate looking questions keep distinct refs and sort uses backend fields`() = runTest {
        val backend = backend()
        val duplicateQuestionRows = (backend.browseCards(AnkiCardQuery(text = "Duplicate-looking"))
            as AnkiResult.Success).value.items
        assertTrue(duplicateQuestionRows.size >= 2)
        assertEquals(1, duplicateQuestionRows.map { it.questionText }.distinct().size)
        assertTrue(duplicateQuestionRows.map { it.cardRef }.distinct().size >= 2)
        assertNotEquals(duplicateQuestionRows[0].cardRef, duplicateQuestionRows[1].cardRef)

        val sorted = (backend.browseCards(AnkiCardQuery(
            deckId = deckA.deckId,
            sort = AnkiCardSort.Reps,
            page = AnkiPageRequest(limit = 30)
        )) as AnkiResult.Success).value
        assertEquals(
            sorted.items.mapNotNull { it.scheduling?.reps }.sorted(),
            sorted.items.mapNotNull { it.scheduling?.reps }
        )
        assertFalse(sorted.items.any { it.questionText.isNullOrBlank() })
    }
}
