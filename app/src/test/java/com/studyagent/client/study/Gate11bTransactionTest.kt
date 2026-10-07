package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 11B PART III — the **mandatory transaction tests**.
 *
 * They run the real pipeline: pure reducer → [AnkiStudyEffectExecutor] (the app's
 * [ReviewCommitCoordinator]) → durable [ReviewCommitLedger] → [FakeAnkiBackend]. Nothing here
 * asserts on a UI field where a durable fact exists.
 */
class Gate11bTransactionTest {

    /** Records the order of durable ledger writes against the order of backend mutations. */
    private fun tracingHarness(
        trace: MutableList<String>,
        commitSteps: List<FakeAnkiBackend.CommitStep> = emptyList()
    ): AnkiCommitHarness {
        val h = AnkiCommitHarness(commitSteps = commitSteps, wrap = { fake ->
            object : AnkiBackend by fake {
                override suspend fun commitRating(
                    request: CommitRatingRequest,
                    mutationEntry: suspend () -> Boolean
                ): BackendCommitResult = fake.commitRating(request) {
                    // The durable boundary marker is written inside this callback, before the
                    // scheduler mutation, so the trace records the mutation after it returns.
                    val allowed = mutationEntry()
                    if (allowed) trace += "mutation"
                    allowed
                }
            }
        })
        h.store.onDurableWrite = { raw ->
            val decoded = ReviewCommitLedgerCodec.decode(raw)
            val status = (decoded as? ReviewCommitLedgerCodec.Decoded.Records)?.records?.firstOrNull()?.status
            trace += "ledger:$status"
        }
        return h
    }

    // ---------------------------------------------------------------- 9

    @Test fun `duplicate_rating_events_create_one_commit_effect`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        val turnId = h.turn!!.turnId

        assertTrue(h.rate(Rating.GOOD).accepted)                                                  // touch
        assertFalse(h.send(StudyEvent.UserRateCard(Rating.GOOD, h.state.currentCardId!!)).accepted) // voice, same
        assertFalse(h.send(StudyEvent.UserRateCard(Rating.GOOD, h.state.currentCardId!!)).accepted) // headset
        assertFalse(h.send(AnkiStudyEvent.SelectRating(h.state.epoch, turnId, Rating.GOOD)).accepted) // keyboard

        assertEquals(1, h.pending.count { it is AnkiStudyEffect.CommitRating })
        h.drain()
        assertEquals(1, h.fake.deliveryCount)
        assertEquals(1, h.fake.backendEffectCount)
        assertEquals(Rating.GOOD, h.fake.recordedCommits().single().request.rating)
        assertEquals(1, h.store.durableRecords().size)
    }

    // ---------------------------------------------------------------- 10

    @Test fun `conflicting_rating_events_accept_only_one_rating`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        val ratedTurnId = h.turn!!.turnId
        val first = h.rate(Rating.GOOD)
        val second = h.send(StudyEvent.UserRateCard(Rating.EASY, h.state.currentCardId!!))
        val third = h.send(AnkiStudyEvent.SelectRating(h.state.epoch, h.turn!!.turnId, Rating.HARD))

        assertTrue(first.accepted)
        assertFalse(second.accepted)
        assertFalse(third.accepted)
        assertEquals("anki-rating-locked-first-wins", second.rejectionReason)

        assertEquals(1, h.pending.count { it is AnkiStudyEffect.CommitRating })
        h.drain()
        assertEquals(1, h.fake.deliveryCount)
        assertEquals(Rating.GOOD, h.fake.recordedCommits().single().request.rating)
        assertEquals("only one transaction exists for the turn",
            1, h.store.durableRecords().count { it.turnId == ratedTurnId })
    }

    // ---------------------------------------------------------------- 11

    @Test fun `safe_failure_allows_retry_with_same_commit_id`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate(Rating.HARD)
        h.fake.prepareRefusal = CommitPreparation.Refused(AnkiError.BackendUnavailable(), retryable = true)
        h.drain()

        val commitId = h.commit!!.commitId
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, h.commit!!.status)
        assertEquals("refused before dispatch", 0, h.fake.deliveryCount)

        h.fake.prepareRefusal = null
        assertTrue(h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, commitId)).accepted)
        h.drain()

        val record = h.store.durableRecords().single()
        assertEquals("the retry keeps the original transaction identity", commitId, record.commitId)
        assertEquals(Rating.HARD, record.rating)
        assertEquals("only the attempt that entered the mutation boundary counts", 1, record.attemptCount)
        assertEquals(ReviewCommitStatus.COMMITTED, record.status)
        assertEquals(1, h.fake.backendEffectCount)
        assertEquals(1, h.fake.recordedCommits().size)
        assertEquals("B", h.turn!!.cardRef.cardId)
    }

    // ---------------------------------------------------------------- 12

    @Test fun `ambiguous_failure_blocks_retry`() = runTest {
        val h = AnkiCommitHarness(commitSteps = listOf(
            FakeAnkiBackend.CommitStep(BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("timeout")))))
        h.loadToRating()
        h.rate()
        h.drain()
        val commitId = h.commit!!.commitId
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit!!.status)

        assertFalse(h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, commitId)).accepted)
        // The coordinator refuses too, with no backend call, even if the reducer is bypassed:
        assertTrue(h.executor.retry(commitId) is ReviewCommitOutcome.Ambiguous)
        assertEquals(1, h.fake.deliveryCount)
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.store.durableRecords().single().status)
    }

    // ---------------------------------------------------------------- 13

    @Test fun `ambiguous_failure_blocks_next_card`() = runTest {
        val h = AnkiCommitHarness(commitSteps = listOf(
            FakeAnkiBackend.CommitStep(BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("timeout")))))
        h.loadToRating()
        val turnId = h.turn!!.turnId
        h.rate()
        h.drain()

        assertTrue(h.pending.none { it is AnkiStudyEffect.Next })
        assertFalse(h.send(AnkiStudyEvent.RetryNextCard(h.state.epoch)).accepted)
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
        assertEquals("the turn stays open and unresolved", turnId, h.turn!!.turnId)
        assertEquals(0, h.state.session!!.totalReviewedInSession)

        val ui = RatingCommitRecoveryUi.from(h.state)!!
        assertTrue(ui.commitUiState is RatingCommitUiState.VerificationRequired)
        assertFalse(ui.canRetry)
        assertFalse(ui.ratingControlsEnabled)
        assertEquals(1, h.fake.deliveryCount)
    }

    // ---------------------------------------------------------------- 14

    @Test fun `committed_persistence_precedes_next_card`() = runTest {
        val trace = mutableListOf<String>()
        val h = tracingHarness(trace)
        h.loadToRating()
        h.rate()
        h.run(h.takeCommitEffect())

        // Every durable write up to and including COMMITTED precedes the next-card effect, and the
        // mutation is preceded by the durable boundary marker.
        val committedIndex = trace.indexOfFirst { it == "ledger:COMMITTED" }
        assertTrue("COMMITTED is durable before anything progresses: $trace", committedIndex >= 0)
        assertEquals("the mutation boundary is durable before the scheduler is touched",
            "ledger:SUBMITTING", trace[trace.indexOf("mutation") - 1])
        assertTrue("COMMITTED is the last durable write of the transaction",
            trace.last() == "ledger:COMMITTED")
        assertEquals(SessionPhase.WaitingForFirstCard, h.state.phase)
        assertEquals(1, h.pending.count { it is AnkiStudyEffect.Next })
    }

    // ---------------------------------------------------------------- 15

    @Test fun `committed_restore_never_replays_rating`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.run(effect)
        assertEquals(ReviewCommitStatus.COMMITTED, h.store.durableRecords().single().status)

        // A restarted process has only the durable record, and it answers from it.
        h.restartLedger()
        val replay = h.executor.execute(effect) as AnkiStudyEvent.RatingCommitResolved
        assertEquals(AnkiCommitOutcome.Committed(AnkiCommitOutcome.SOURCE_LEDGER_REPLAY), replay.outcome)
        assertEquals("no second delivery", 1, h.fake.deliveryCount)
        assertEquals("no second scheduler mutation", 1, h.fake.backendEffectCount)
        assertEquals(ReviewCommitStatus.COMMITTED, h.store.durableRecords().single().status)

        // And the restored session neither shows rating buttons nor re-enters mutation.
        h.send(replay)
        assertFalse(RatingCommitRecoveryUi.from(h.state)!!.ratingControlsEnabled)
        assertTrue(h.pending.single() is AnkiStudyEffect.Next)
        h.drain()
        assertEquals(1, h.fake.deliveryCount)
        assertEquals("B", h.turn!!.cardRef.cardId)
    }

    // ---------------------------------------------------------------- 16

    @Test fun `ambiguous_restore_never_replays_rating`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.fake.mutationGate = CompletableDeferred()
        val job = launch { h.executor.execute(effect) }
        runCurrent()
        job.cancelAndJoin() // the process dies with the mutation in flight
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.store.durableRecords().single().status)

        h.restartLedger()
        val replay = h.executor.execute(effect) as AnkiStudyEvent.RatingCommitResolved
        assertTrue(replay.outcome is AnkiCommitOutcome.Ambiguous)
        assertEquals("no mutation replay after an interrupted dispatch", 1, h.fake.commitInvocations)
        assertEquals("the interrupted attempt entered the boundary exactly once", 1, h.fake.mutationAttemptCount)
        h.send(replay)
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
        assertTrue(h.pending.none { it is AnkiStudyEffect.Next })
    }

    // ---------------------------------------------------------------- 17

    @Test fun `reconciliation_committed_does_not_mutate_again`() = runTest {
        val h = AnkiCommitHarness.reconcilable(commitSteps = listOf(
            FakeAnkiBackend.CommitStep(BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("ack-lost")),
                appliedWhenAmbiguous = true)))
        h.loadToRating()
        h.rate()
        h.drain()
        val commitId = h.commit!!.commitId
        val attemptsBefore = h.fake.mutationAttemptCount
        val deliveriesBefore = h.fake.deliveryCount

        h.fake.reconcileResults.add(ReconcileCommitResult.Applied("fake_applied"))
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        h.drain()

        assertEquals(ReviewCommitStatus.COMMITTED, h.store.durableRecords().single().status)
        assertEquals(1, h.fake.reconcileCalls)
        assertEquals("reconciliation is read-only", attemptsBefore, h.fake.mutationAttemptCount)
        assertEquals("no second delivery", deliveriesBefore, h.fake.deliveryCount)
        assertEquals(ReviewCommitResolution.RECONCILED_APPLIED, h.store.durableRecords().single().resolution)
        assertEquals("B", h.turn!!.cardRef.cardId)
        assertEquals(1, h.state.session!!.totalReviewedInSession)
    }

    // ---------------------------------------------------------------- 18

    @Test fun `reconciliation_not_committed_enables_safe_retry`() = runTest {
        val h = AnkiCommitHarness.reconcilable(commitSteps = listOf(
            FakeAnkiBackend.CommitStep(BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("lost")),
                appliedWhenAmbiguous = false)))
        h.loadToRating()
        h.rate()
        h.drain()
        val commitId = h.commit!!.commitId
        val attemptsBefore = h.fake.mutationAttemptCount

        h.fake.reconcileResults.add(ReconcileCommitResult.NotApplied("fake_not_applied"))
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        h.drain()

        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, h.store.durableRecords().single().status)
        assertEquals("the reconciliation itself mutated nothing", attemptsBefore, h.fake.mutationAttemptCount)
        assertEquals(1, h.fake.reconcileCalls)
        assertEquals(0, h.fake.backendEffectCount)

        // Only an explicit retry of the *same* transaction may mutate now.
        h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, commitId))
        h.drain()
        assertEquals(ReviewCommitStatus.COMMITTED, h.store.durableRecords().single().status)
        assertEquals(commitId, h.store.durableRecords().single().commitId)
        assertEquals(1, h.fake.backendEffectCount)
        assertEquals(1, h.fake.recordedCommits().single().mutationCount)
        assertEquals("B", h.turn!!.cardRef.cardId)
    }

    // ---------------------------------------------------------------- 19

    @Test fun `ledger_persistence_failure_after_backend_success_blocks_progression`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        val gate = CompletableDeferred<Unit>()
        h.fake.mutationGate = gate // inside the mutation window: the boundary is already durable
        val job = launch { h.run(effect) }
        runCurrent()
        h.store.failNextWrites = 1 // the response write fails, AFTER backend success
        gate.complete(Unit)
        job.join()

        assertEquals("the scheduler was mutated", 1, h.fake.backendEffectCount)
        assertEquals(1, h.fake.deliveryCount)
        assertEquals(SessionPhase.CommitPersistenceFailure, h.state.phase)
        assertTrue("no next card", h.pending.none { it is AnkiStudyEffect.Next })
        assertFalse("no speculative retry", RatingCommitRecoveryUi.from(h.state)!!.canRetry)
        assertFalse(RatingCommitRecoveryUi.from(h.state)!!.ratingControlsEnabled)
        assertEquals(ReviewCommitStatus.SUBMITTING, h.store.durableRecords().single().status)

        // Repairing the record is a ledger write, never a second rating mutation.
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, h.commit!!.commitId))
        h.drain()
        assertEquals(ReviewCommitStatus.COMMITTED, h.store.durableRecords().single().status)
        assertEquals(1, h.fake.backendEffectCount)
        assertEquals(1, h.fake.deliveryCount)
        assertEquals("B", h.turn!!.cardRef.cardId)
    }
}
