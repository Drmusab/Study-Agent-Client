package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeCommitMode
import com.studyagent.client.core.anki.ReviewCommitLedger
import com.studyagent.client.core.anki.ReviewCommitState
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.AnkiStudyEffect
import com.studyagent.client.core.study.StudyEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 11 checkpoint 8 — concurrency protection.
 *
 * The transaction layer, not the button's disabled state, decides how many commits exist. These
 * tests fire simultaneous input (touch + voice + headset are all [StudyEvent.UserRateCard] at this
 * layer) and then assert on the ledger and the simulated collection, never on UI state.
 */
class ReviewCommitConcurrencyTest {

    @Test
    fun `one hundred simultaneous good ratings produce one commit id and one backend effect`() = runTest {
        val harness = AnkiCommitHarness()
        harness.loadToRating()
        val cardId = harness.state.currentCardId!!

        // 100 logical inputs: keyboard, touch, voice and headset all arrive as rating events.
        val events = (1..100).map { StudyEvent.UserRateCard(Rating.GOOD, cardId) }
        val accepted = events.count { harness.send(it).accepted }

        assertEquals("exactly one rating event may be accepted", 1, accepted)
        assertEquals("exactly one commit effect exists", 1,
            harness.pending.filterIsInstance<AnkiStudyEffect.CommitRating>().size)
        harness.drain()
        assertEquals("exactly one ledger row", 1, harness.ledger.snapshot().size)
        assertEquals(1, harness.fake.deliveryCount)
        assertEquals(1, harness.fake.mutationAttemptCount)
        assertEquals(1, harness.fake.backendEffectCount)
        assertEquals(1, harness.fake.logicalCommitCount)
        assertEquals("the ledger is the authority and it says COMMITTED",
            ReviewCommitState.COMMITTED, harness.ledger.snapshot().single().state)
    }

    @Test
    fun `concurrent commit effects for the same turn dispatch exactly once`() = runTest {
        val harness = AnkiCommitHarness()
        harness.loadToRating()
        harness.rate(Rating.GOOD)
        val effect = harness.takeCommitEffect()

        // Ten racing executors for one logical commit: the ledger CAS admits one.
        val results = (1..10).map { async { harness.executor.execute(effect) } }.awaitAll()

        assertEquals(1, harness.fake.deliveryCount)
        assertEquals(1, harness.fake.mutationAttemptCount)
        assertEquals(1, harness.fake.backendEffectCount)
        // Every racing caller gets an answer, but only one of them caused a scheduler effect.
        assertTrue(results.all { it != null })
        assertEquals(ReviewCommitState.COMMITTED, harness.ledger.snapshot().single().state)
    }

    @Test
    fun `good and hard arrive together and exactly one rating is accepted`() = runTest {
        val harness = AnkiCommitHarness()
        harness.loadToRating()
        val cardId = harness.state.currentCardId!!

        val good = harness.send(StudyEvent.UserRateCard(Rating.GOOD, cardId))
        val hard = harness.send(StudyEvent.UserRateCard(Rating.HARD, cardId))

        assertTrue(good.accepted)
        assertFalse("a different rating on the same turn is a rejection, never an overwrite", hard.accepted)
        val commit = harness.commit!!
        assertEquals(Rating.GOOD, commit.rating)
        harness.drain()
        assertEquals(1, harness.fake.backendEffectCount)
        assertEquals(1, harness.fake.logicalCommitCount)
        // And the backend never saw the losing rating.
        assertEquals(Rating.GOOD, harness.fake.recordedCommits().single().request.rating)
    }

    @Test
    fun `one hundred concurrent claims admit one mutation attempt per session`() = runTest {
        val harness = AnkiCommitHarness()
        harness.loadToRating()
        harness.rate(Rating.GOOD)
        val request = harness.commit!!.request
        // The intent row is written by the coordinator's first durable step.
        harness.ledger.prepare(request)
        val commitId = request.commitId

        val claims = (1..100).map { async { harness.ledger.claim(commitId, evidence = null, allowRetry = false) } }
            .awaitAll()
        val claimed = claims.count { it is ReviewCommitLedger.ClaimResult.Claimed }
        val inFlight = claims.count { it is ReviewCommitLedger.ClaimResult.InFlight }
        assertEquals(1, claimed)
        assertEquals(99, inFlight)
        assertEquals("no race may create two rows", 1, harness.ledger.snapshot().size)
    }

    @Test
    fun `no race creates two ledger rows for one turn`() = runTest {
        val harness = AnkiCommitHarness()
        harness.loadToRating()
        harness.rate(Rating.GOOD)
        val request = harness.commit!!.request

        // Fifty concurrent prepare calls for the same identity: one Prepared, the rest Existing.
        val prepares = (1..50).map { async { harness.ledger.prepare(request) } }.awaitAll()
        assertEquals(1, prepares.count { it is ReviewCommitLedger.PrepareResult.Prepared })
        assertEquals(49, prepares.count { it is ReviewCommitLedger.PrepareResult.Existing })
        assertEquals(1, harness.ledger.snapshot().size)
    }

    @Test
    fun `an ambiguous commit can never be turned into a second mutation by racing input`() = runTest {
        val harness = AnkiCommitHarness(fakeMode = FakeCommitMode.MUTATE_THEN_DROP_RESPONSE)
        harness.loadToRating()
        val cardId = harness.state.currentCardId!!
        harness.rate(Rating.GOOD)
        harness.drain()
        assertEquals(ReviewCommitState.AMBIGUOUS, harness.commit?.state)

        // Racing the same input again (and the losing ratings) must not reach the backend.
        val attempts = (1..100).map { async { harness.send(StudyEvent.UserRateCard(Rating.GOOD, cardId)).accepted } }
            .awaitAll()
        harness.drain()
        assertTrue(attempts.none { it })
        assertEquals(1, harness.fake.deliveryCount)
        assertEquals(1, harness.fake.backendEffectCount)
    }
}
