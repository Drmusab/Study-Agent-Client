package com.studyagent.client.anki

import com.studyagent.client.anki.fake.CardBrowserTestFixtures
import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardQuery
import com.studyagent.client.core.anki.AnkiCardScope
import com.studyagent.client.core.anki.AnkiCardSort
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiPageRequest
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.SortDirection
import com.studyagent.client.core.anki.SuspensionFilter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 15 — the collection-scale, read-only properties of the browser contract on the deterministic
 * fake backend: a 1,200-card multilingual collection, backend-wide search, stable bounded paging and
 * the guarantee that browsing never crosses the mutation boundary.
 *
 * The reusable §75/§76 semantic suite lives in `anki/contract`; this class covers the scale and
 * read-only evidence that a small fixture cannot show.
 */
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
    fun `deck scoped pages stay bounded and cursor pages append distinct identities`() = runBlocking {
        val backend = backend()
        val scope = AnkiCardScope.Deck(deckA.deckId, includeChildren = false)
        val first = (backend.browseCards(AnkiCardQuery(
            scope = scope,
            page = AnkiPageRequest(limit = 25)
        )) as AnkiResult.Success).value
        val second = (backend.browseCards(AnkiCardQuery(
            scope = scope,
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
    fun `search is collection wide over backend text and never over tags or deck names`() = runBlocking {
        val backend = backend()
        val search = (backend.browseCards(AnkiCardQuery(
            text = "myocarditis",
            page = AnkiPageRequest(limit = 100)
        )) as AnkiResult.Success).value
        assertTrue(search.items.isNotEmpty())
        assertTrue(search.items.all {
            it.questionText?.contains("myocarditis", ignoreCase = true) == true ||
                it.answerText?.contains("myocarditis", ignoreCase = true) == true
        })

        val arabicTag = (backend.browseCards(AnkiCardQuery(
            filters = AnkiCardFilters(tags = setOf("طب")),
            page = AnkiPageRequest(limit = 100)
        )) as AnkiResult.Success).value
        assertTrue(arabicTag.items.isNotEmpty())
        assertTrue(arabicTag.items.all { "طب" in it.tags })

        // "pharmacology" is a tag on some cards and never appears in question/answer text, so a
        // tag-only match must not leak into text search.
        val tagOnly = (backend.browseCards(AnkiCardQuery(
            text = "pharmacology",
            page = AnkiPageRequest(limit = 100)
        )) as AnkiResult.Success).value
        assertTrue(tagOnly.items.isEmpty())
    }

    @Test
    fun `tri-state suspension filtering is exact and read only`() = runBlocking {
        val backend = backend()
        val suspended = (backend.browseCards(AnkiCardQuery(
            filters = AnkiCardFilters(suspension = SuspensionFilter.SuspendedOnly),
            page = AnkiPageRequest(limit = 100)
        )) as AnkiResult.Success).value
        assertTrue(suspended.items.isNotEmpty())
        assertTrue(suspended.items.all { it.suspended == true })

        val notSuspended = (backend.browseCards(AnkiCardQuery(
            filters = AnkiCardFilters(suspension = SuspensionFilter.NotSuspended),
            page = AnkiPageRequest(limit = 100)
        )) as AnkiResult.Success).value
        assertTrue(notSuspended.items.all { it.suspended == false })
        assertEquals(0, backend.commitInvocations)
        assertEquals(0, backend.backendEffectCount)
    }

    @Test
    fun `duplicate looking questions keep distinct refs and sorting uses backend fields`() = runBlocking {
        val backend = backend()
        val duplicateQuestionRows = (backend.browseCards(AnkiCardQuery(
            text = "Duplicate-looking",
            page = AnkiPageRequest(limit = 100)
        )) as AnkiResult.Success).value.items
        assertTrue(duplicateQuestionRows.size >= 2)
        assertEquals(1, duplicateQuestionRows.map { it.questionText }.distinct().size)
        assertTrue(duplicateQuestionRows.map { it.cardRef }.distinct().size >= 2)
        assertNotEquals(duplicateQuestionRows[0].cardRef, duplicateQuestionRows[1].cardRef)

        val sorted = (backend.browseCards(AnkiCardQuery(
            scope = AnkiCardScope.Deck(deckA.deckId),
            sort = AnkiCardSort.Reps(SortDirection.ASCENDING),
            page = AnkiPageRequest(limit = 30)
        )) as AnkiResult.Success).value
        assertEquals(
            sorted.items.mapNotNull { it.scheduling?.reps }.sorted(),
            sorted.items.mapNotNull { it.scheduling?.reps }
        )
        val descending = (backend.browseCards(AnkiCardQuery(
            scope = AnkiCardScope.Deck(deckA.deckId),
            sort = AnkiCardSort.Reps(SortDirection.DESCENDING),
            page = AnkiPageRequest(limit = 30)
        )) as AnkiResult.Success).value
        val ascendingKeys = sorted.items.mapNotNull { it.scheduling?.reps }
        val descendingKeys = descending.items.mapNotNull { it.scheduling?.reps }
        assertEquals(ascendingKeys.sorted(), ascendingKeys)
        assertEquals(descendingKeys.sortedDescending(), descendingKeys)
        assertNotEquals(ascendingKeys.first(), descendingKeys.first())
        assertFalse(sorted.items.any { it.questionText.isNullOrBlank() })
    }

    @Test
    fun `pages never overlap and the last page advertises no continuation`() = runBlocking {
        val backend = backend(count = 1_000)
        val scope = AnkiCardScope.AllCards
        val seen = LinkedHashSet<String>()
        var cursor: com.studyagent.client.core.anki.AnkiPageCursor? = null
        var pages = 0
        while (true) {
            val page = (backend.browseCards(AnkiCardQuery(
                scope = scope,
                sort = AnkiCardSort.Lapses(SortDirection.ASCENDING),
                page = AnkiPageRequest(limit = AnkiPageRequest.MAX_LIMIT, cursor = cursor)
            )) as AnkiResult.Success).value
            page.items.forEach { seen += it.cardRef.stableKey }
            pages += 1
            cursor = page.nextCursor ?: break
            assertTrue("Paging terminates", pages < 1_000)
        }
        assertEquals(1_000, seen.size)
        assertEquals(10, backend.browseCalls)
        assertEquals(0, backend.commitInvocations)
        assertEquals(0, backend.mutationBoundaryCrossingCount)
    }

    @Test
    fun `a cursor from another query is refused rather than restarting the sequence`() = runBlocking {
        val backend = backend(count = 1_000)
        val first = (backend.browseCards(AnkiCardQuery(
            scope = AnkiCardScope.AllCards,
            page = AnkiPageRequest(limit = 10)
        )) as AnkiResult.Success).value
        val cursor = first.nextCursor ?: error("expected a continuation")

        val changedScope = backend.browseCards(AnkiCardQuery(
            scope = AnkiCardScope.Deck(deckA.deckId),
            page = AnkiPageRequest(limit = 10, cursor = cursor)
        ))
        assertTrue(changedScope is AnkiResult.Failure)
        assertEquals(
            com.studyagent.client.core.anki.AnkiError.InvalidCursor("cursor_query_mismatch"),
            (changedScope as AnkiResult.Failure).error
        )
        // The unchanged query still resumes from the same cursor: the refusal was about identity.
        val resumed = backend.browseCards(AnkiCardQuery(
            scope = AnkiCardScope.AllCards,
            page = AnkiPageRequest(limit = 10, cursor = cursor)
        ))
        assertEquals(10, (resumed as AnkiResult.Success).value.items.size)
    }
}
