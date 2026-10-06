package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.FakeCommitMode
import com.studyagent.client.core.anki.CommitGuaranteeLevel
import com.studyagent.client.core.anki.ReviewCommitState
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.AnkiStudyEvent
import com.studyagent.client.core.study.SessionPhase
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 11 checkpoint 4 — fake-backend failure laboratory.
 *
 * Each mode is a deterministic scheduler behaviour; the assertions are about the *transaction*
 * layer: which outcome is claimed, how many physical effects happened, whether the next card was
 * ever requested, and whether a redelivery was the client's automatic idea or a user action.
 *
 * The mandatory scenario is [mutation happens then the response disappears]: the effect is real, the
 * answer is lost, and the app must stop rather than guess.
 */
class FakeCommitLaboratoryTest {

    private suspend fun AnkiCommitHarness.commitOnce() {
        val effect = takeCommitEffect()
        run(effect)
        drain()
    }

    private fun AnkiCommitHarness.assertNoAutomaticResend(nextCardsBefore: Int) {
        assertEquals("no backend delivery beyond the first attempt", 0, fake.redeliveryCount)
        assertEquals("no next card before COMMITTED", nextCardsBefore, fake.nextCardCount)
    }

    @Test
    fun `mandatory - mutation happens then the response disappears yields AMBIGUOUS without retry or next card`() = runTest {
        val harness = AnkiCommitHarness(fakeMode = FakeCommitMode.MUTATE_THEN_DROP_RESPONSE)
        harness.loadToRating()
        val nextCardsBefore = harness.fake.nextCardCount
        assertTrue(harness.rate(Rating.GOOD).accepted)
        harness.commitOnce()

        assertEquals(ReviewCommitState.AMBIGUOUS, harness.commit?.state)
        assertEquals(1, harness.ledger.snapshot().single().attemptCount)
        assertEquals(SessionPhase.ReconciliationRequired, harness.state.phase)
        assertEquals("delivered exactly once", 1, harness.fake.deliveryCount)
        assertEquals("one durable mutation-boundary crossing", 1, harness.fake.mutationBoundaryCrossingCount)
        assertEquals("the physical effect is observable", 1, harness.fake.backendEffectCount)
        harness.assertNoAutomaticResend(nextCardsBefore)

        // A late duplicate of the same rating cannot start a second transaction.
        val duplicate = harness.rate(Rating.GOOD)
        assertFalse("re-rating an ambiguous turn must be rejected", duplicate.accepted)
        harness.drain()
        assertEquals(1, harness.fake.deliveryCount)
        assertEquals(1, harness.fake.backendEffectCount)
        harness.assertNoAutomaticResend(nextCardsBefore)
    }

    @Test
    fun `OUTCOME_UNKNOWN with no effect still fails closed as AMBIGUOUS`() = runTest {
        val harness = AnkiCommitHarness(fakeMode = FakeCommitMode.OUTCOME_UNKNOWN)
        harness.loadToRating()
        val nextCardsBefore = harness.fake.nextCardCount
        harness.rate(Rating.HARD)
        harness.commitOnce()

        assertEquals(ReviewCommitState.AMBIGUOUS, harness.commit?.state)
        assertEquals(1, harness.ledger.snapshot().single().attemptCount)
        assertEquals(0, harness.fake.backendEffectCount)
        assertEquals(1, harness.fake.deliveryCount)
        assertEquals(1, harness.fake.mutationBoundaryCrossingCount)
        harness.assertNoAutomaticResend(nextCardsBefore)
    }

    @Test
    fun `FAIL_BEFORE_MUTATION is a proven safe retry and keeps the same commit id`() = runTest {
        val harness = AnkiCommitHarness(fakeMode = FakeCommitMode.FAIL_BEFORE_MUTATION)
        harness.loadToRating()
        val nextCardsBefore = harness.fake.nextCardCount
        harness.rate(Rating.AGAIN)
        val original = harness.commit!!.commitId
        harness.commitOnce()

        assertEquals(ReviewCommitState.FAILED_SAFE_TO_RETRY, harness.commit?.state)
        assertTrue(harness.commit!!.safeToRetry)
        assertEquals(SessionPhase.RatingCommitFailed, harness.state.phase)
        assertEquals("the request was delivered", 1, harness.fake.deliveryCount)
        assertEquals("pre-mutation failure never crossed the durable boundary", 0,
            harness.fake.mutationBoundaryCrossingCount)
        assertEquals(0, harness.fake.backendEffectCount)
        assertEquals("one durable prepared attempt", 1,
            harness.ledger.snapshot().single().attemptCount)
        harness.assertNoAutomaticResend(nextCardsBefore)

        // The retry is explicit, keeps the identity, and the same turn stays active.
        val retry = harness.send(AnkiStudyEvent.RetryRatingCommit(harness.state.epoch, original))
        assertTrue(retry.accepted)
        harness.drain()
        assertEquals(ReviewCommitState.FAILED_SAFE_TO_RETRY, harness.commit?.state)
        assertEquals("attempt count grows only when a new prepared submission is durable", 2,
            harness.ledger.snapshot().single().attemptCount)
        assertEquals("the retry re-sent the same logical commit", 2, harness.fake.deliveryCount)
        assertEquals(original, harness.commit!!.commitId)
        assertEquals("the resend was the user's, not an automatic retry", 1, harness.fake.redeliveryCount)
        assertEquals("and it still cannot pass the commit barrier", nextCardsBefore, harness.fake.nextCardCount)
    }

    @Test
    fun `BACKEND_UNAVAILABLE refuses before dispatch and never crosses the boundary`() = runTest {
        val harness = AnkiCommitHarness(fakeMode = FakeCommitMode.BACKEND_UNAVAILABLE)
        harness.loadToRating()
        val nextCardsBefore = harness.fake.nextCardCount
        harness.rate(Rating.GOOD)
        harness.commitOnce()

        assertEquals(ReviewCommitState.FAILED_SAFE_TO_RETRY, harness.commit?.state)
        assertTrue(harness.commit!!.safeToRetry)
        assertEquals("read-only preparation refused before a safe submission attempt", 0,
            harness.ledger.snapshot().single().attemptCount)
        assertEquals("refused in prepare, so commitRating was never called", 0, harness.fake.deliveryCount)
        assertEquals(0, harness.fake.mutationBoundaryCrossingCount)
        assertEquals(0, harness.fake.backendEffectCount)
    }

    @Test
    fun `DELAYED_SUCCESS commits only after the response arrives and advances once`() = runTest {
        val harness = AnkiCommitHarness(fakeMode = FakeCommitMode.DELAYED_SUCCESS)
        harness.loadToRating()
        val nextCardsBefore = harness.fake.nextCardCount
        harness.rate(Rating.GOOD)
        val effect = harness.takeCommitEffect()
        val call = async { harness.run(effect) }
        advanceUntilIdle() // the mutation is entered and the fake is waiting for its response

        // The mutation is in flight: the machine may not advance and nothing is committed yet.
        harness.assertNoAutomaticResend(nextCardsBefore)
        assertEquals(SessionPhase.SubmittingRating, harness.state.phase)
        assertFalse("COMMITTED is not claimed while the response is missing",
            harness.commit?.state == ReviewCommitState.COMMITTED)
        assertEquals("one durable prepared submission", 1, harness.ledger.snapshot().single().attemptCount)
        assertEquals("the delivery occurred", 1, harness.fake.deliveryCount)
        assertEquals("the mutation boundary was entered", 1, harness.fake.mutationBoundaryCrossingCount)
        assertEquals("but no effect is visible until the response arrives", 0, harness.fake.backendEffectCount)

        harness.fake.releaseDelayedSuccess()
        call.await()
        harness.drain()

        assertEquals(ReviewCommitState.COMMITTED, harness.ledger.snapshot().single().state)
        assertEquals(1, harness.fake.backendEffectCount)
    }

    @Test
    fun `IDEMPOTENT_REPLAY answers a redelivery from the backend table with one effect`() = runTest {
        val harness = AnkiCommitHarness(
            backendId = AnkiCommitHarness.RECONCILABLE_BACKEND,
            mode = AnkiBackendMode.PC_AGENT, fakeMode = FakeCommitMode.IDEMPOTENT_REPLAY)
        assertEquals(CommitGuaranteeLevel.IDEMPOTENT_REPLAY_SUPPORTED, harness.fake.commitSemantics.guaranteeLevel)
        harness.loadToRating()
        harness.rate(Rating.GOOD)
        val effect = harness.takeCommitEffect()
        harness.run(effect)
        harness.drain()
        assertEquals(ReviewCommitState.COMMITTED, harness.ledger.snapshot().single().state)
        assertEquals(1, harness.fake.backendEffectCount)

        // Re-delivering the identical request (a resend, not a new turn) applies nothing new.
        val replay = harness.executor.execute(effect)
        assertEquals(1, harness.fake.backendEffectCount)
        assertEquals(1, harness.fake.deliveryCount)
        assertEquals(1, harness.fake.mutationBoundaryCrossingCount)
        assertNotNull(replay)
    }

    @Test
    fun `NON_IDEMPOTENT_REPLAY would apply twice and only the ledger prevents the second effect`() = runTest {
        val harness = AnkiCommitHarness(
            backendId = AnkiCommitHarness.RECONCILABLE_BACKEND,
            mode = AnkiBackendMode.PC_AGENT, fakeMode = FakeCommitMode.NON_IDEMPOTENT_REPLAY)
        assertEquals(CommitGuaranteeLevel.LOCAL_DEDUP_ONLY, harness.fake.commitSemantics.guaranteeLevel)
        harness.loadToRating()
        harness.rate(Rating.GOOD)
        val effect = harness.takeCommitEffect()
        harness.run(effect)
        harness.drain()
        assertEquals(1, harness.fake.backendEffectCount)
        assertTrue(harness.fake.commitSemantics.guaranteeLevel == CommitGuaranteeLevel.LOCAL_DEDUP_ONLY)

        // The transaction layer answers COMMITTED from the durable ledger: no second effect even
        // though this backend would happily apply the same commit id again.
        val before = harness.fake.deliveryCount
        harness.executor.execute(effect)
        assertEquals("the ledger stopped the replay before the backend", before, harness.fake.deliveryCount)
        assertEquals(1, harness.fake.backendEffectCount)
    }

    @Test
    fun `SUCCESS is the reference path and keeps every counter honest`() = runTest {
        val harness = AnkiCommitHarness(fakeMode = FakeCommitMode.SUCCESS)
        harness.loadToRating()
        val nextCardsBefore = harness.fake.nextCardCount
        harness.rate(Rating.EASY)
        harness.commitOnce()

        assertEquals(ReviewCommitState.COMMITTED, harness.ledger.snapshot().single().state)
        assertEquals(1, harness.fake.deliveryCount)
        assertEquals(1, harness.fake.mutationBoundaryCrossingCount)
        assertEquals(1, harness.fake.backendEffectCount)
        assertEquals("exactly one next card, after COMMITTED", nextCardsBefore + 1, harness.fake.nextCardCount)
        assertEquals(0, harness.fake.redeliveryCount)
    }

    @Test
    fun `the mode table is complete and each mode declares its guarantee`() {
        val expected = setOf(
            "SUCCESS", "FAIL_BEFORE_MUTATION", "MUTATE_THEN_DROP_RESPONSE", "OUTCOME_UNKNOWN",
            "DELAYED_SUCCESS", "IDEMPOTENT_REPLAY", "NON_IDEMPOTENT_REPLAY", "BACKEND_UNAVAILABLE"
        )
        assertEquals(expected, FakeCommitMode.entries.map { it.name }.toSet())
        assertEquals(CommitGuaranteeLevel.LOCAL_DEDUP_ONLY, FakeCommitMode.NON_IDEMPOTENT_REPLAY.guarantee)
        assertEquals(CommitGuaranteeLevel.IDEMPOTENT_REPLAY_SUPPORTED, FakeCommitMode.IDEMPOTENT_REPLAY.guarantee)
        assertFalse(FakeCommitMode.FAIL_BEFORE_MUTATION.appliesEffect)
        assertFalse(FakeCommitMode.OUTCOME_UNKNOWN.appliesEffect)
        assertFalse(FakeCommitMode.BACKEND_UNAVAILABLE.appliesEffect)
        assertTrue(FakeCommitMode.MUTATE_THEN_DROP_RESPONSE.appliesEffect)
    }

    @Test
    fun `a scripted step still wins over the mode so existing scenarios are unchanged`() = runTest {
        val harness = AnkiCommitHarness(
            fakeMode = FakeCommitMode.OUTCOME_UNKNOWN,
            commitSteps = listOf(FakeAnkiBackend.CommitStep(
                com.studyagent.client.core.anki.CommitRatingResult.Committed())))
        harness.loadToRating()
        harness.rate(Rating.GOOD)
        harness.commitOnce()
        assertEquals(ReviewCommitState.COMMITTED, harness.ledger.snapshot().single().state)
        assertEquals(1, harness.fake.backendEffectCount)
    }
}
