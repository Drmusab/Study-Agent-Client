package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.InMemoryReviewCommitStore
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 11E PART III — VERIFICATION 24 (endurance) and VERIFICATION 25 (repeated restart chaos).
 *
 * One long, deterministic run through the real reducer + executor + durable ledger + fake backend,
 * with every fault class the gate names injected on a fixed cycle:
 *
 * | Turn | Injected fault | What it must prove |
 * |---|---|---|
 * | `i % 5 == 0` | none | the happy path stays exactly one effect |
 * | `i % 5 == 1` | safe pre-mutation failure | `RETRY_ALLOWED` → same commit id → one effect |
 * | `i % 5 == 2` | response loss (`OutcomeUnknown`) | `AMBIGUOUS` → read-only reconcile → retry, still one effect |
 * | `i % 5 == 3` | process restart before dispatch | restart never creates a second logical commit |
 * | `i % 5 == 4` | stale callback after resolution | a late duplicate delivery mutates nothing |
 * | **every turn** | duplicate rating input | one accepted rating per turn |
 *
 * The four zeros are asserted continuously, not only at the end: a ledger that ever holds two rows
 * for one turn, a scheduler that ever applies two effects for one turn, a next card that ever loads
 * before a durable COMMITTED, or any illegal status transition fails the run at that turn.
 *
 * The backend identity is the PC-class fake **only** because it is the one that can authoritatively
 * reconcile (`AnkiCommitHarness.RECONCILABLE_BACKEND`); the AnkiDroid identity cannot, which is the
 * documented ceiling measured in `Gate11dAnkiDroidCapabilityTest`, not a weakness of this run.
 */
class Gate11eEnduranceTest {

    private val turns = 1000

    /** Five fault families, in a fixed cycle: 200 turns each. */
    private fun faultOf(index: Int): Int = index % 5

    private fun stepsFor(turns: Int): List<FakeAnkiBackend.CommitStep> = buildList {
        repeat(turns) { index ->
            when (faultOf(index)) {
                1 -> {
                    // Safe failure: the backend proves no scheduler mutation was dispatched…
                    add(FakeAnkiBackend.CommitStep(
                        BackendCommitResult.ConfirmedNotCommitted(AnkiError.QueryFailure("fake_not_applied"))))
                    add(FakeAnkiBackend.CommitStep(BackendCommitResult.ConfirmedCommitted())) // …then the retry
                }
                2 -> {
                    // Response loss with no effect applied, resolved by read-only reconciliation…
                    add(FakeAnkiBackend.CommitStep(
                        BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("fake_response_lost")),
                        appliedWhenAmbiguous = false))
                    add(FakeAnkiBackend.CommitStep(BackendCommitResult.ConfirmedCommitted())) // …then the retry
                }
                else -> add(FakeAnkiBackend.CommitStep(BackendCommitResult.ConfirmedCommitted()))
            }
        }
    }

    @Test fun `one thousand mixed-fault review turns keep all four zeros`() = runTest {
        val backend = AnkiCommitHarness.RECONCILABLE_BACKEND
        val h = AnkiCommitHarness(
            backendId = backend,
            mode = AnkiBackendMode.PC_AGENT,
            cards = List(turns + 4) { AnkiCommitHarness.card("E$it", backend) },
            commitSteps = stepsFor(turns)
        )
        h.loadToRating()
        val ratedTurns = mutableSetOf<ReviewTurnId>()
        var effects = 0
        repeat(turns) { index ->
            val turn = requireNotNull(h.turn) { "no turn at $index" }
            val commitId = turn.commitId
            assertTrue("turn ${turn.turnId} was rated twice", ratedTurns.add(turn.turnId))
            val rating = Rating.entries[index % Rating.entries.size]

            // ---- duplicate input: a second rating for the same turn never reaches the backend.
            assertTrue("the first rating was refused at $index", h.rate(rating).accepted)
            val duplicate = h.send(StudyEvent.UserRateCard(rating, requireNotNull(h.state.currentCardId)))
            assertFalse("a duplicate rating was accepted at $index", duplicate.accepted)

            val effect = h.takeCommitEffect()
            if (faultOf(index) == 3) {
                h.restartLedger() // process death before the dispatch
                val health = h.ledger.health()
                assertTrue("the durable ledger must survive a restart at $index: $health",
                    health is ReviewCommitLedger.Health.Ready)
            }
            // The store's write log is a test-only ordering aid (used by the durability-order
            // suite); a run this long would otherwise hold every snapshot it ever wrote in memory
            // and the host runs out of heap long before the ledger does.
            if (index % 25 == 24) h.store.writes.clear()
            h.run(effect)

            if (faultOf(index) == 4) {
                // ---- stale callback: the same effect delivered again after it was resolved.
                val deliveries = h.fake.deliveryCount
                val mutations = h.fake.mutationAttemptCount
                h.run(effect)
                assertEquals("a stale callback must not reach the backend at $index",
                    deliveries, h.fake.deliveryCount)
                assertEquals("a stale callback must not mutate at $index",
                    mutations, h.fake.mutationAttemptCount)
            }

            when (faultOf(index)) {
                1 -> {
                    assertEquals("a proven no-mutation failure is retryable at $index",
                        ReviewCommitStatus.RETRY_ALLOWED, h.ledger.get(commitId)!!.status)
                    assertTrue("the retry targets the same logical commit at $index",
                        h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, commitId)).accepted)
                    h.drain()
                }
                2 -> {
                    assertEquals("a lost response is unknown, never retryable at $index",
                        ReviewCommitStatus.AMBIGUOUS, h.ledger.get(commitId)!!.status)
                    assertFalse("no blind retry at $index",
                        h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, commitId)).accepted)
                    val before = h.fake.mutationAttemptCount
                    assertTrue("verification is offered at $index",
                        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId)).accepted)
                    h.drain()
                    assertEquals("reconciliation is read-only at $index", before, h.fake.mutationAttemptCount)
                    assertEquals("proven no-mutation, so the same commit may be retried at $index",
                        ReviewCommitStatus.RETRY_ALLOWED, h.ledger.get(commitId)!!.status)
                    assertTrue(h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, commitId)).accepted)
                    h.drain()
                }
            }

            val record = requireNotNull(h.ledger.get(commitId)) {
                "no record for turn $index (fault=${faultOf(index)}) rows=${h.ledger.snapshot().map { it.status }}"
            }
            assertEquals("turn $index did not commit", ReviewCommitStatus.COMMITTED, record.status)
            assertEquals("the rating that was selected is the rating that was recorded", rating, record.rating)
            assertEquals("one logical commit per rated turn", index + 1, h.ledger.snapshot().size)
            assertEquals("one scheduler effect per turn", ++effects, h.fake.backendEffectCount)

            // ---- the next card is only ever requested after a durable COMMITTED.
            h.drain()
            assertNotEquals("the next card must be a different turn", turn.turnId,
                requireNotNull(h.turn) { "no next turn after $index" }.turnId)
            assertEquals("no unresolved row survives a committed turn", 0,
                h.ledger.snapshot().count { it.status != ReviewCommitStatus.COMMITTED })

            h.now += 4_000L
            h.send(StudyEvent.UserRequestAnswer(requireNotNull(h.state.currentCardId)))
            h.drain()
            assertEquals("turn $index did not reach the rating UI", SessionPhase.WaitingForRating, h.state.phase)
        }

        val rows = h.ledger.snapshot()
        assertEquals(turns, rows.size)
        assertEquals("one logical commit per turn", turns, rows.map { it.commitId }.distinct().size)
        assertEquals("one turn, one commit", turns, ratedTurns.size)
        assertEquals("every turn produced exactly one scheduler effect", turns, h.fake.backendEffectCount)
        assertEquals("no logical commit was duplicated", turns, h.fake.logicalCommitCount)
        assertEquals("every rating was exercised", 4, rows.map { it.rating }.toSet().size)
        assertEquals(turns, h.state.session!!.totalReviewedInSession)
        // Attempts: 200 plain + 200×(failure+retry) + 200×(loss+retry) + 200 restart + 200 stale.
        assertEquals("a stale callback never re-enters the mutation boundary",
            200 * (1 + 2 + 2 + 1 + 1), h.fake.mutationAttemptCount)
    }

    // --------------------------------------------- VERIFICATION 24, second half: next-card failures

    @Test fun `a failed next-card read is retryable only when nothing unresolved is open`() = runTest {
        val h = AnkiCommitHarness(nextErrors = listOf(AnkiError.QueryFailure("fake_next_failed")))
        h.startAndLoad()
        assertTrue("the failed scheduler read surfaces as an error", h.state.phase is SessionPhase.Error)
        assertEquals("the failed read is a read, never a mutation", 0, h.fake.backendEffectCount)
        assertTrue("a read-only retry of the scheduler query is legal",
            h.send(AnkiStudyEvent.RetryNextCard(h.state.epoch)).accepted)
        h.drain()
        assertEquals("the failed read does not count as a presented card", 1, h.fake.nextCardCount)
        assertNotNull("the retry loads the card", h.turn)

        // With an unresolved transaction open, the same retry is refused: the barrier is not a
        // per-screen decision, and the read may not paper over an unknown scheduler state.
        val blocked = AnkiCommitHarness(commitSteps = listOf(FakeAnkiBackend.CommitStep(
            BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("fake_response_lost")))))
        blocked.loadToRating()
        blocked.rate()
        blocked.run(blocked.takeCommitEffect())
        assertEquals(ReviewCommitStatus.AMBIGUOUS, blocked.commit!!.status)
        assertEquals("no scheduler query while the outcome is unknown", 1, blocked.fake.nextCardCount)
        assertFalse(blocked.send(AnkiStudyEvent.RetryNextCard(blocked.state.epoch)).accepted)
    }

    // ---------------------------------------------------- VERIFICATION 25: repeated restart chaos

    /** Leaves one durable row at [status] with a simulated crash, exactly as a real death would. */
    private suspend fun TestScope.unresolvedAt(status: ReviewCommitStatus): Pair<AnkiCommitHarness, ReviewCommitId> {
        val crash: CommitFaultInjector = when (status) {
            ReviewCommitStatus.PREPARED -> ThrowingCommitFault(CommitFaultPoint.AFTER_PREPARED)
            ReviewCommitStatus.SUBMITTING -> ThrowingCommitFault(CommitFaultPoint.AFTER_CALL_ENTERED)
            else -> NoCommitFaults
        }
        val h = AnkiCommitHarness(
            faults = crash,
            commitSteps = when (status) {
                ReviewCommitStatus.RETRY_ALLOWED -> listOf(FakeAnkiBackend.CommitStep(
                    BackendCommitResult.ConfirmedNotCommitted(AnkiError.QueryFailure("fake_not_applied"))))
                ReviewCommitStatus.AMBIGUOUS -> listOf(FakeAnkiBackend.CommitStep(
                    BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("fake_response_lost"))))
                else -> emptyList()
            }
        )
        h.loadToRating()
        val commitId = requireNotNull(h.turn).commitId
        h.rate()
        val effect = h.takeCommitEffect()
        if (crash === NoCommitFaults) h.run(effect) else try {
            h.run(effect)
            error("the injected crash must leave the executor")
        } catch (fault: CommitFaultException) { /* the durable row is all that survives */ }
        assertEquals(status, h.ledger.get(commitId)!!.status)
        return h to commitId
    }

    /**
     * Restart is not an event in the recovery table (GATE 11D §26): no sequence of restarts may
     * create a new logical commit, dispatch a mutation, or resolve an unknown outcome on its own.
     */
    @Test fun `five restarts from each durable status never duplicate a scheduler mutation`() = runTest {
        val expectedAfterRestart = mapOf(
            ReviewCommitStatus.PREPARED to ReviewCommitStatus.PREPARED,
            ReviewCommitStatus.SUBMITTING to ReviewCommitStatus.AMBIGUOUS,
            ReviewCommitStatus.RETRY_ALLOWED to ReviewCommitStatus.RETRY_ALLOWED,
            ReviewCommitStatus.AMBIGUOUS to ReviewCommitStatus.AMBIGUOUS,
            ReviewCommitStatus.COMMITTED to ReviewCommitStatus.COMMITTED
        )
        expectedAfterRestart.forEach { (status, settled) ->
            val (h, commitId) = unresolvedAt(status)
            val mutations = h.fake.mutationAttemptCount
            val effects = h.fake.backendEffectCount
            repeat(5) { round ->
                h.restartLedger()
                val record = h.ledger.get(commitId)
                assertEquals("$status after restart ${round + 1}: the transaction survived",
                    commitId, record!!.commitId)
                assertEquals("$status after restart ${round + 1}", settled, record.status)
                assertEquals("$status after restart ${round + 1}: restart is not a mutation",
                    mutations, h.fake.mutationAttemptCount)
                assertEquals("$status after restart ${round + 1}: no new scheduler effect",
                    effects, h.fake.backendEffectCount)
                assertEquals("$status after restart ${round + 1}: one logical commit",
                    1, h.ledger.snapshot().size)
            }
        }
    }
}
