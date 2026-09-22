package com.studyagent.client.anki

import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Reusable JVM contract for a seeded, ready review/deck backend. Future adapter suites supply
 * an isolated fixture with [A, B, A] scheduled and no injected faults. No transport/platform mocks
 * belong here. This foundation tests at-most-once effects via backend-observable progression;
 * implementation suites additionally inspect their mutation boundary/ledger.
 */
abstract class AnkiBackendContract {
    data class Fixture(
        val backend: AnkiBackend,
        val context: AnkiSessionContext,
        val deck: AnkiDeck,
        val cards: List<AnkiRenderedCard>
    )
    protected abstract fun fixture(): Fixture

    @Test fun `backend identity and health remain stable across refresh`() = runTest {
        val f = fixture()
        val id = f.backend.id
        f.backend.refreshAvailability()
        assertEquals(id, f.backend.id)
        assertEquals(f.context.backendId, id)
        assertTrue(f.backend.availability.value.isReadyForReview)
        assertTrue(f.backend.capabilities.value.review)
        assertFalse(f.backend.capabilities.value.editNotes)
    }

    @Test fun `deck retrieval is backend qualified`() = runTest {
        val f = fixture()
        val decks = (f.backend.getDecks() as AnkiResult.Success).value
        assertEquals(listOf(f.deck), decks)
        assertTrue(decks.all { it.ref.backendId == f.backend.id })
    }

    @Test fun `begin binds context and repeated begin returns same handle`() = runTest {
        val f = fixture()
        val first = f.begin()
        assertEquals(f.context, first.context)
        assertEquals(first, f.begin())
        assertTrue(first.backendSessionRef.isNotBlank())
    }

    @Test fun `next retry retains presentation and does not consume a card`() = runTest {
        val f = fixture()
        val session = f.begin()
        val first = f.backend.nextCard(session) as NextCardResult.Card
        assertEquals(first, f.backend.nextCard(session))
        assertEquals(f.context.studySessionId, first.turn.studySessionId)
        assertEquals(f.context.backendId, first.turn.backendId)
    }

    @Test fun `scheduled sequence commits then finishes without generating new due cards`() = runTest {
        val f = fixture()
        val session = f.begin()
        val turns = mutableListOf<AnkiReviewTurn>()
        f.cards.forEachIndexed { index, expected ->
            val turn = (f.backend.nextCard(session) as NextCardResult.Card).turn
            assertEquals(expected.ref, turn.card.ref)
            assertTrue(f.backend.commitRating(turn.request(if (index == 1) Rating.AGAIN else Rating.GOOD))
                is CommitRatingResult.Committed)
            turns += turn
        }
        assertEquals(f.cards.size, turns.map { it.turnId }.distinct().size)
        assertEquals(turns.first().card.ref, turns.last().card.ref)
        assertNotEquals(turns.first().commitId, turns.last().commitId)
        assertEquals(NextCardResult.Finished, f.backend.nextCard(session))
        assertEquals(NextCardResult.Finished, f.backend.nextCard(session))
    }

    @Test fun `identical duplicate acknowledged but changed payload rejected`() = runTest {
        val f = fixture()
        val session = f.begin()
        val turn = (f.backend.nextCard(session) as NextCardResult.Card).turn
        val request = turn.request()
        val committed = f.backend.commitRating(request)
        assertTrue(committed is CommitRatingResult.Committed)
        assertEquals(committed, f.backend.commitRating(request))
        val conflict = f.backend.commitRating(request.copy(rating = Rating.EASY)) as CommitRatingResult.Rejected
        assertTrue(conflict.error is AnkiError.CommitConflict)
        val second = f.backend.nextCard(session) as NextCardResult.Card
        assertEquals(f.cards[1].ref, second.turn.card.ref)
        assertEquals(committed, f.backend.commitRating(request)) // late ACK retry, never advances twice
        assertEquals(second, f.backend.nextCard(session))
    }

    @Test fun `concurrent duplicate commits cannot advance twice`() = runTest {
        val f = fixture()
        val session = f.begin()
        val turn = (f.backend.nextCard(session) as NextCardResult.Card).turn
        val results = List(20) { async { f.backend.commitRating(turn.request()) } }.awaitAll()
        assertTrue(results.all { it is CommitRatingResult.Committed })
        val second = f.backend.nextCard(session) as NextCardResult.Card
        assertEquals(f.cards[1].ref, second.turn.card.ref)
    }

    @Test fun `forged or stale turn is rejected as domain error`() = runTest {
        val f = fixture()
        val session = f.begin()
        val turn = (f.backend.nextCard(session) as NextCardResult.Card).turn
        val stale = turn.request().copy(commitId = turn.commitId.copy(turnId = ReviewTurnId("not-active")))
        val result = f.backend.commitRating(stale) as CommitRatingResult.Rejected
        assertTrue(result.error is AnkiError.StaleTurn)
        assertEquals(NextCardResult.Card(turn), f.backend.nextCard(session))
    }

    @Test fun `forged session handle rejected and original context unchanged`() = runTest {
        val f = fixture()
        val session = f.begin()
        val result = f.backend.nextCard(session.copy(backendSessionRef = "forged")) as NextCardResult.Failure
        assertTrue(result.error is AnkiError.SessionInvalid)
        assertEquals(f.context, session.context)
        assertTrue(f.backend.nextCard(session) is NextCardResult.Card)
    }

    private suspend fun Fixture.begin(): AnkiReviewSession =
        (backend.beginReview(BeginReviewRequest(context)) as AnkiResult.Success).value
}

internal fun AnkiReviewTurn.request(rating: Rating = Rating.GOOD) =
    CommitRatingRequest(commitId, card.ref, rating, ratedAtEpochMs = 100, answerDurationMs = 10)
