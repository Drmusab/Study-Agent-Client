package com.studyagent.client.anki

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.FakeAnkiBackend.CommitStep
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FakeAnkiBackendTest {
    private fun fake(
        steps: List<CommitStep> = emptyList(), nextErrors: List<AnkiError> = emptyList(),
        latency: Long = 0, maxLedger: Int = 10_000, cards: List<AnkiRenderedCard> = listOf(card("A"), card("B"))
    ) = FakeAnkiBackend(fakeId, listOf(deck()), cards, latencyMs = latency, nextErrors = nextErrors,
        commitSteps = steps, maxLedgerEntries = maxLedger, instanceId = "fake-test")

    @Test fun `commit records exact identity rating and one mutation`() = runTest {
        val backend = fake()
        val session = backend.begin()
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        val request = turn.request(Rating.GOOD)
        backend.commitRating(request)
        backend.commitRating(request)
        val record = backend.recordedCommits().single()
        assertEquals(request, record.request)
        assertEquals(1, record.attempts)
        assertEquals(1, record.mutationCount)
    }

    @Test fun `ambiguous applied write blocks advance and blind duplicate mutation`() = runTest {
        val ambiguous = CommitRatingResult.Ambiguous(AnkiError.QueryFailure("ack-lost"))
        val backend = fake(listOf(CommitStep(ambiguous, appliedWhenAmbiguous = true)))
        val session = backend.begin()
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        assertEquals(ambiguous, backend.commitRating(turn.request()))
        assertEquals(ambiguous, backend.commitRating(turn.request()))
        assertTrue(backend.nextCard(session) is NextCardResult.Failure)
        assertEquals(1, backend.recordedCommits().single().mutationCount)
        assertEquals(1, backend.recordedCommits().single().attempts)
        assertTrue(backend.beginReview(BeginReviewRequest(context(sessionId = "other"))) is AnkiResult.Failure)
    }

    @Test fun `ambiguous unapplied write still cannot become safe retry`() = runTest {
        val outcome = CommitRatingResult.Ambiguous(AnkiError.BackendUnavailable())
        val backend = fake(listOf(CommitStep(outcome)))
        val session = backend.begin()
        val request = (backend.nextCard(session) as NextCardResult.Card).turn.request()
        assertEquals(outcome, backend.commitRating(request))
        assertEquals(outcome, backend.commitRating(request))
        assertEquals(0, backend.recordedCommits().single().mutationCount)
        assertTrue(backend.nextCard(session) is NextCardResult.Failure)
    }

    @Test fun `safe failure retries same payload once without consuming turn`() = runTest {
        val failure = CommitRatingResult.RetryableFailure(AnkiError.BackendUnavailable())
        val backend = fake(listOf(CommitStep(failure)))
        val session = backend.begin()
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        assertEquals(failure, backend.commitRating(turn.request()))
        assertEquals(NextCardResult.Card(turn), backend.nextCard(session))
        val conflict = backend.commitRating(turn.request(Rating.EASY)) as CommitRatingResult.Rejected
        assertTrue(conflict.error is AnkiError.CommitConflict)
        assertTrue(backend.commitRating(turn.request()) is CommitRatingResult.Committed)
        assertEquals(2, backend.recordedCommits().single().attempts)
        assertEquals(1, backend.recordedCommits().single().mutationCount)
    }

    @Test fun `rejected commit neither mutates nor advances`() = runTest {
        val rejected = CommitRatingResult.Rejected(AnkiError.CommitConflict())
        val backend = fake(listOf(CommitStep(rejected)))
        val session = backend.begin()
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        assertEquals(rejected, backend.commitRating(turn.request()))
        assertEquals(rejected, backend.commitRating(turn.request()))
        assertTrue(backend.nextCard(session) is NextCardResult.Failure)
        assertEquals(0, backend.recordedCommits().single().mutationCount)
    }

    @Test fun `backend loss preserves context and never switches backend`() = runTest {
        val backend = fake()
        val session = backend.begin()
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        backend.setAvailability(AnkiAvailability.TemporarilyUnavailable())
        assertEquals(fakeId, session.context.backendId)
        assertTrue(backend.nextCard(session) is NextCardResult.BackendUnavailable)
        assertTrue(backend.commitRating(turn.request()) is CommitRatingResult.RetryableFailure)
        assertEquals(0, backend.recordedCommits().single().mutationCount)
        assertTrue(backend.commitRating(turn.request(Rating.EASY)) is CommitRatingResult.Rejected)
        backend.setAvailability(AnkiAvailability.Ready(reviewCaps))
        assertEquals(NextCardResult.Card(turn), backend.nextCard(session))
        assertTrue(backend.commitRating(turn.request()) is CommitRatingResult.Committed)
    }

    @Test fun `known acknowledgment survives temporary unavailability`() = runTest {
        val backend = fake()
        val session = backend.begin()
        val request = (backend.nextCard(session) as NextCardResult.Card).turn.request()
        val result = backend.commitRating(request)
        backend.setAvailability(AnkiAvailability.TemporarilyUnavailable())
        assertEquals(result, backend.commitRating(request))
        assertEquals(1, backend.recordedCommits().single().mutationCount)
    }

    @Test fun `next failure does not consume queue`() = runTest {
        val error = AnkiError.QueryFailure("injected")
        val backend = fake(nextErrors = listOf(error))
        val session = backend.begin()
        assertEquals(NextCardResult.Failure(error), backend.nextCard(session))
        assertEquals(card("A").ref, (backend.nextCard(session) as NextCardResult.Card).turn.cardRef)
    }

    @Test fun `deck and begin errors are typed`() = runTest {
        val backend = FakeAnkiBackend(fakeId, listOf(deck()), listOf(card()),
            decksError = AnkiError.QueryFailure("deck-fault"), beginError = AnkiError.CollectionUnavailable())
        assertEquals(AnkiResult.Failure(AnkiError.QueryFailure("deck-fault")), backend.getDecks())
        assertEquals(AnkiResult.Failure(AnkiError.CollectionUnavailable()), backend.beginReview(BeginReviewRequest(context())))
    }

    @Test fun `capability loss gates operations without rewriting bound snapshot`() = runTest {
        val backend = fake()
        val session = backend.begin()
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        backend.setCapabilities(AnkiCapabilities.NONE)
        assertTrue((backend.getDecks() as AnkiResult.Failure).error is AnkiError.UnsupportedAction)
        assertTrue((backend.nextCard(session) as NextCardResult.Failure).error is AnkiError.UnsupportedAction)
        assertTrue(backend.commitRating(turn.request()) is CommitRatingResult.Rejected)
        assertTrue(session.context.capabilities.review)
        assertFalse(backend.availability.value.isReadyForReview)
        assertTrue(backend.beginReview(BeginReviewRequest(context(sessionId = "new"))) is AnkiResult.Failure)
    }

    @Test fun `permission failure is actionable rather than exhaustion`() = runTest {
        val backend = fake()
        backend.setAvailability(AnkiAvailability.PermissionRequired())
        assertEquals(AnkiResult.Failure(AnkiError.PermissionRequired()), backend.getDecks())
        assertEquals(AnkiResult.Failure(AnkiError.PermissionRequired()), backend.beginReview(BeginReviewRequest(context())))
    }

    @Test fun `unknown deck and wrong backend cannot open session`() = runTest {
        val backend = fake()
        val wrongDeck = context().copy(deckRef = AnkiDeckRef(fakeId, "absent"))
        assertTrue((backend.beginReview(BeginReviewRequest(wrongDeck)) as AnkiResult.Failure).error is AnkiError.DeckNotFound)
        assertTrue((backend.beginReview(BeginReviewRequest(context(AnkiBackendId.PcAgent("p"))))
            as AnkiResult.Failure).error is AnkiError.SessionInvalid)
    }

    @Test fun `context or limit cannot be changed by replaying begin`() = runTest {
        val backend = fake()
        backend.begin()
        assertTrue(backend.beginReview(BeginReviewRequest(context(), limit = 1)) is AnkiResult.Failure)
        assertTrue(backend.beginReview(BeginReviewRequest(context().copy(startedAtEpochMs = 101))) is AnkiResult.Failure)
    }

    @Test fun `limit and empty queue finish explicitly`() = runTest {
        val backend = fake()
        val session = (backend.beginReview(BeginReviewRequest(context(), 1)) as AnkiResult.Success).value
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        backend.commitRating(turn.request())
        assertEquals(NextCardResult.Finished, backend.nextCard(session))
        val empty = fake(cards = emptyList())
        assertEquals(NextCardResult.Finished, empty.nextCard(empty.begin()))
    }

    @Test fun `collection and deck filtering cannot return foreign collection cards`() = runTest {
        val backend = fake(cards = listOf(card("other", key = "other-collection"), card("A")))
        val session = backend.begin()
        assertEquals(card("A").ref, (backend.nextCard(session) as NextCardResult.Card).turn.cardRef)
    }

    @Test fun `wrong card and wrong session rating are rejected before mutation`() = runTest {
        val backend = fake()
        val session = backend.begin()
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        val wrongCard = turn.request().copy(card = card("B").ref)
        assertTrue((backend.commitRating(wrongCard) as CommitRatingResult.Rejected).error is AnkiError.StaleTurn)
        val wrongSession = turn.request().copy(commitId = turn.commitId.copy(studySessionId = "other"))
        assertTrue((backend.commitRating(wrongSession) as CommitRatingResult.Rejected).error is AnkiError.SessionInvalid)
        assertTrue(backend.recordedCommits().isEmpty())
    }

    @Test fun `bounded ledger fails closed rather than evicting deduplication keys`() = runTest {
        val backend = fake(maxLedger = 1)
        val session = backend.begin()
        val first = (backend.nextCard(session) as NextCardResult.Card).turn
        backend.commitRating(first.request())
        val second = (backend.nextCard(session) as NextCardResult.Card).turn
        assertTrue(backend.commitRating(second.request()) is CommitRatingResult.Rejected)
        assertTrue(backend.commitRating(first.request()) is CommitRatingResult.Committed)
        assertEquals(1, backend.recordedCommits().size)
    }

    @Test fun `reset bounds memory invalidates handles and does not reuse turn ids`() = runTest {
        val backend = fake()
        val old = backend.begin()
        val oldTurn = (backend.nextCard(old) as NextCardResult.Card).turn
        backend.commitRating(oldTurn.request())
        backend.reset()
        assertTrue(backend.recordedCommits().isEmpty())
        assertTrue(backend.nextCard(old) is NextCardResult.Failure)
        val new = backend.begin()
        val newTurn = (backend.nextCard(new) as NextCardResult.Card).turn
        assertNotEquals(oldTurn.turnId, newTurn.turnId)
        assertTrue(backend.commitRating(oldTurn.request()) is CommitRatingResult.Rejected)
    }

    @Test fun `latency uses virtual time and cancellation cannot apply a commit`() = runTest {
        val backend = fake(latency = 1_000)
        val session = backend.begin()
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        val job = launch { backend.commitRating(turn.request()) }
        runCurrent()
        advanceTimeBy(999)
        assertTrue(backend.recordedCommits().isEmpty())
        job.cancelAndJoin()
        assertTrue(backend.recordedCommits().isEmpty())
        assertTrue(backend.commitRating(turn.request()) is CommitRatingResult.Committed)
    }

    @Test fun `availability is checked after simulated I O latency`() = runTest {
        val backend = fake(latency = 100)
        val session = backend.begin()
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        val pending = async { backend.commitRating(turn.request()) }
        runCurrent()
        backend.setAvailability(AnkiAvailability.TemporarilyUnavailable())
        assertTrue(pending.await() is CommitRatingResult.RetryableFailure)
        assertEquals(0, backend.recordedCommits().single().mutationCount)
    }

    @Test fun `parallel dispatcher calls serialize one scheduler mutation`() = runTest {
        val backend = fake()
        val session = backend.begin()
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        val results = List(50) { async(Dispatchers.Default) { backend.commitRating(turn.request()) } }.awaitAll()
        assertTrue(results.all { it is CommitRatingResult.Committed })
        assertEquals(1, backend.recordedCommits().single().mutationCount)
        assertEquals(1, backend.recordedCommits().single().attempts)
    }

    @Test fun `caller collections cannot rewrite queued card facts`() = runTest {
        val tags = mutableSetOf("original")
        val times = mutableMapOf(Rating.GOOD to "4d")
        val input = card().copy(metadata = AnkiCardMetadata(tags = tags),
            scheduling = AnkiSchedulingInfo(nextReviewTimes = times))
        val cards = mutableListOf(input)
        val backend = fake(cards = cards)
        cards.clear()
        tags += "late"
        times[Rating.GOOD] = "wrong"
        val turn = (backend.nextCard(backend.begin()) as NextCardResult.Card).turn
        assertEquals(setOf("original"), turn.renderedCard?.metadata?.tags)
        assertEquals("4d", turn.renderedCard?.scheduling?.nextReviewTimes?.get(Rating.GOOD))
    }

    // ---------------------------------------------------------------- GATE 06 scheduled review

    @Test fun `an exhausted deck is Finished rather than a failure`() = runTest {
        val backend = fake(cards = emptyList())
        val session = backend.begin()
        assertEquals(NextCardResult.Finished, backend.nextCard(session))
        assertEquals(NextCardResult.Finished, backend.nextCard(session))
        // "nothing due" and "backend down" stay different facts (§74/§127).
        assertTrue(backend.availability.value is AnkiAvailability.Ready)
    }

    @Test fun `a repeated read before commit never advances to the next card`() = runTest {
        val backend = fake()
        val session = backend.begin()
        val first = (backend.nextCard(session) as NextCardResult.Card).turn
        repeat(5) {
            assertEquals(first, (backend.nextCard(session) as NextCardResult.Card).turn)
        }
        assertEquals("A", first.cardRef.noteId)
        // The stand-in mirrors the real backend's one-active-turn rule, which is what makes GATE
        // 10's StudySession tests meaningful without AnkiDroid (§102/§167).
        assertEquals(backend.capabilities.value.scheduledReview, true)
    }

    @Test fun `a rated turn advances once and only once`() = runTest {
        val backend = fake()
        val session = backend.begin()
        val first = (backend.nextCard(session) as NextCardResult.Card).turn
        assertTrue(backend.commitRating(first.request(Rating.GOOD)) is CommitRatingResult.Committed)

        val second = (backend.nextCard(session) as NextCardResult.Card).turn
        assertEquals("B", second.cardRef.noteId)
        assertNotEquals("a new presentation is a new turn identity", first.turnId, second.turnId)
        assertEquals("B", (backend.nextCard(session) as NextCardResult.Card).turn.cardRef.noteId)
        assertTrue(backend.commitRating(second.request(Rating.GOOD)) is CommitRatingResult.Committed)
        assertEquals(NextCardResult.Finished, backend.nextCard(session))
    }
}
