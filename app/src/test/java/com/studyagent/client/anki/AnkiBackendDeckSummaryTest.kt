package com.studyagent.client.anki

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckCounts
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 14 checkpoint 02 — the deck-summary read is part of the backend contract.
 *
 * The default implementation derives from the single batched deck listing, so these tests pin
 * the contract every backend inherits: identity by stable deck ID, typed failures, bounded read
 * fan-out, and the unknown-is-not-zero count rule (INV-14-02/03/06/17).
 */
class AnkiBackendDeckSummaryTest {

    private val backendId = AnkiBackendId.Fake("summary")

    private fun backend(decks: List<AnkiDeck>, error: AnkiError? = null) = FakeAnkiBackend(
        id = backendId,
        decks = decks,
        initialCapabilities = AnkiCapabilities(deckListing = true),
        decksError = error
    )

    private fun deck(id: String, name: String, counts: AnkiDeckCounts? = null, filtered: Boolean? = null) =
        AnkiDeck(AnkiDeckRef(backendId, id), name, isFiltered = filtered, counts = counts)

    @Test
    fun `summary is resolved by stable deck ID and preserves zero and unknown counts`() = runTest {
        val backend = backend(
            listOf(
                deck("1", "Medicine", AnkiDeckCounts(new = 0, learning = 0, review = 0)),
                deck("2", "Medicine::Cardiology", null, filtered = true),
                deck("3", "طب::أمراض القلب", AnkiDeckCounts(new = 4, learning = null, review = 2))
            )
        )

        val zeros = backend.getDeckSummary("1") as AnkiResult.Success
        assertEquals(AnkiDeckRef(backendId, "1"), zeros.value.deck.ref)
        assertEquals(0, zeros.value.counts?.new)
        assertEquals(0, zeros.value.counts?.review)
        assertNull(zeros.value.totalCards)

        val unknown = backend.getDeckSummary("2") as AnkiResult.Success
        assertNull(unknown.value.counts)
        assertEquals(true, unknown.value.isFiltered)

        val partial = backend.getDeckSummary("3") as AnkiResult.Success
        assertEquals(4, partial.value.counts?.new)
        assertNull(partial.value.counts?.learning)
        assertEquals("طب::أمراض القلب", partial.value.deck.name)

        assertEquals(3, backend.getDecksCalls) // exactly one batched listing per summary read
    }

    @Test
    fun `missing deck is typed DeckNotFound never a fabricated empty summary`() = runTest {
        val backend = backend(listOf(deck("1", "Medicine")))
        val result = backend.getDeckSummary("999")
        assertTrue(result is AnkiResult.Failure)
        val error = (result as AnkiResult.Failure).error
        assertTrue(error is AnkiError.DeckNotFound)
        assertEquals(AnkiDeckRef(backendId, "999"), (error as AnkiError.DeckNotFound).deck)
    }

    @Test
    fun `listing failure propagates typed instead of becoming zero counts`() = runTest {
        val backend = backend(listOf(deck("1", "Medicine")), error = AnkiError.CollectionUnavailable())
        val result = backend.getDeckSummary("1")
        assertTrue(result is AnkiResult.Failure)
        assertTrue((result as AnkiResult.Failure).error is AnkiError.CollectionUnavailable)
    }

    @Test
    fun `permission failure stays permission failure`() = runTest {
        val backend = backend(listOf(deck("1", "Medicine")), error = AnkiError.PermissionRequired())
        val result = backend.getDeckSummary("1")
        assertTrue((result as AnkiResult.Failure).error is AnkiError.PermissionRequired)
    }

    @Test
    fun `missing listing capability is an honest unsupported failure`() = runTest {
        val backend = FakeAnkiBackend(
            id = backendId,
            decks = listOf(deck("1", "Medicine")),
            initialCapabilities = AnkiCapabilities(deckListing = false)
        )
        val result = backend.getDeckSummary("1")
        assertTrue((result as AnkiResult.Failure).error is AnkiError.UnsupportedAction)
    }

    @Test
    fun `blank deck ID is refused without touching the backend`() = runTest {
        val backend = backend(listOf(deck("1", "Medicine")))
        assertTrue((backend.getDeckSummary("   ") as AnkiResult.Failure).error is AnkiError.InvalidRequest)
        assertEquals(0, backend.getDecksCalls)
    }
}
