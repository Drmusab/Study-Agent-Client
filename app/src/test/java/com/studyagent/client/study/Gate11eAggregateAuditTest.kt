package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.InMemoryReviewCommitStore
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.anki.CommitFaultException
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 11E — the aggregate audit that the lock decision rests on.
 *
 * GATE 11A-11D proved the pieces. This class re-proves, in one place and against the real
 * reducer + executor + durable ledger, the properties a release decision actually needs:
 *
 * * **the five restart states** — no restart sequence may replay, guess or advance (PART III
 *   VERIFICATION 6-10, INV-11E-27);
 * * **the next-card barrier** — one rule, honoured by the reducer for every outcome kind
 *   (PART I §6, INV-11E-18);
 * * **identity locks** — a backend-preference change never redirects an active transaction
 *   (INV-11E-21/22, VERIFICATION 15/16);
 * * **source-of-truth conflicts** — a stale projection loses to the ledger in both directions
 *   (INV-11E-06/08, VERIFICATION 20).
 *
 * Every assertion is about *observable behaviour* of the production pipeline. The durable counters
 * (`deliveryCount`, `mutationAttemptCount`, `backendEffectCount`, `nextCardCount`) are the
 * scheduler-mutation truth: a test that only inspects the UI could pass while the scheduler is
 * mutated twice.
 */
class Gate11eAggregateAuditTest {

    // ------------------------------------------------------- PART III VERIFICATION 6-10 — restarts

    /**
     * Drives one transaction until the durable row holds [status], without resolving it further.
     *
     * A crash is modelled the way the rest of the suite models it — a [CommitFaultException] that
     * leaves the executor with no cleanup, exactly like a process death — so the durable row is
     * what a real interruption would leave behind, not what a cooperative cancellation would.
     */
    private suspend fun TestScope.transactionAt(status: ReviewCommitStatus): Pair<AnkiCommitHarness, ReviewCommitId> {
        val crash: CommitFaultInjector = when (status) {
            ReviewCommitStatus.PREPARED -> ThrowingCommitFault(CommitFaultPoint.AFTER_PREPARED)
            ReviewCommitStatus.SUBMITTING -> ThrowingCommitFault(CommitFaultPoint.AFTER_CALL_ENTERED)
            else -> NoCommitFaults
        }
        val store = InMemoryReviewCommitStore()
        val h = AnkiCommitHarness(
            store = store,
            faults = crash,
            commitSteps = when (status) {
                ReviewCommitStatus.RETRY_ALLOWED ->
                    listOf(FakeAnkiBackend.CommitStep(BackendCommitResult.ConfirmedNotCommitted(AnkiError.QueryFailure("not_applied"))))
                ReviewCommitStatus.AMBIGUOUS ->
                    listOf(FakeAnkiBackend.CommitStep(BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("lost_response"))))
                else -> emptyList()
            }
        )
        h.loadToRating()
        val commitId = requireNotNull(h.turn).commitId
        h.rate()
        val effect = h.takeCommitEffect()
        if (crash === NoCommitFaults) {
            h.run(effect)
        } else {
            try {
                h.run(effect)
                error("the injected crash must leave the executor")
            } catch (fault: CommitFaultException) {
                // The durable row is the only thing that survives: no cleanup, no classification.
            }
        }
        assertEquals("the transaction did not reach $status", status, h.ledger.get(commitId)!!.status)
        return h to commitId
    }

    /** A new process over the same durable store: the ledger is rebuilt, nothing else is restored. */
    private suspend fun restartInto(store: InMemoryReviewCommitStore): AnkiCommitHarness {
        val restored = AnkiCommitHarness(store = store)
        restored.startAndLoad()
        return restored
    }

    @Test fun `restart from PREPARED keeps the same transaction and offers no automatic mutation`() = runTest {
        val (h, commitId) = transactionAt(ReviewCommitStatus.PREPARED)
        assertEquals("the crash happened before the backend was invoked", 0, h.fake.deliveryCount)
        val restored = restartInto(h.store)
        assertEquals("the same logical transaction survives", commitId, restored.ledger.get(commitId)!!.commitId)
        assertEquals(ReviewCommitStatus.PREPARED, restored.ledger.get(commitId)!!.status)
        assertEquals("no backend call merely because the app restarted", 0, restored.fake.deliveryCount)
        assertEquals(0, restored.fake.mutationAttemptCount)
        assertEquals("no next card before COMMITTED", 0, restored.fake.nextCardCount)
        val ui = RatingCommitRecoveryUi.from(restored.state)
        assertTrue("a provably un-entered transaction offers a retry", ui!!.canRetry)
        assertFalse("rating buttons stay closed on a restored transaction", ui.ratingControlsEnabled)
    }

    @Test fun `restart from SUBMITTING never replays and requires verification`() = runTest {
        val (h, commitId) = transactionAt(ReviewCommitStatus.SUBMITTING)
        assertEquals("the crash happened after the boundary: the scheduler may have been mutated",
            0, h.fake.backendEffectCount)
        val restored = restartInto(h.store)
        assertEquals("an interrupted submission is normalized into explicit uncertainty",
            ReviewCommitStatus.AMBIGUOUS, restored.ledger.get(commitId)!!.status)
        assertEquals("restart is not a mutation", 0, h.fake.mutationAttemptCount)
        assertEquals(0, restored.fake.mutationAttemptCount)
        assertEquals(0, restored.fake.nextCardCount)
        assertEquals(SessionPhase.ReconciliationRequired, restored.state.phase)
        val ui = RatingCommitRecoveryUi.from(restored.state)!!
        assertFalse("a mutation may already have happened: no retry", ui.canRetry)
        assertFalse(ui.ratingControlsEnabled)
    }

    @Test fun `restart from RETRY_ALLOWED offers the same commit id and never dispatches alone`() = runTest {
        val (h, commitId) = transactionAt(ReviewCommitStatus.RETRY_ALLOWED)
        val restored = restartInto(h.store)
        val record = restored.ledger.get(commitId)
        assertEquals("the retry reuses the logical commit", commitId, record!!.commitId)
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, record.status)
        assertEquals(0, restored.fake.deliveryCount)
        assertEquals(0, restored.fake.nextCardCount)
        val ui = RatingCommitRecoveryUi.from(restored.state)!!
        assertTrue("a proven-not-applied transaction may be retried", ui.canRetry)
        assertFalse("the retry is the user's decision, never automatic", ui.ratingControlsEnabled)
    }

    @Test fun `restart from AMBIGUOUS blocks retry rating buttons and the next card`() = runTest {
        val (h, commitId) = transactionAt(ReviewCommitStatus.AMBIGUOUS)
        val mutationsBefore = h.fake.mutationAttemptCount
        val restored = restartInto(h.store)
        assertEquals(ReviewCommitStatus.AMBIGUOUS, restored.ledger.get(commitId)!!.status)
        assertEquals("nothing was re-sent", mutationsBefore, h.fake.mutationAttemptCount)
        assertEquals(0, restored.fake.deliveryCount)
        assertEquals(0, restored.fake.nextCardCount)
        assertEquals(SessionPhase.ReconciliationRequired, restored.state.phase)
        val ui = RatingCommitRecoveryUi.from(restored.state)!!
        assertFalse("an unknown outcome is never blindly retried", ui.canRetry)
        assertFalse(ui.ratingControlsEnabled)
        // Even the user asking for a retry is refused: pressing a button creates no evidence.
        assertFalse(restored.send(AnkiStudyEvent.RetryRatingCommit(restored.state.epoch, commitId)).accepted)
    }

    @Test fun `restart from COMMITTED never replays the mutation and allows a fresh scheduler query`() = runTest {
        val (h, commitId) = transactionAt(ReviewCommitStatus.COMMITTED)
        val effectsBefore = h.fake.backendEffectCount
        val restored = restartInto(h.store)
        assertEquals(ReviewCommitStatus.COMMITTED, restored.ledger.get(commitId)!!.status)
        assertEquals("the mutation is not replayed", effectsBefore, h.fake.backendEffectCount)
        assertEquals("no delivery for a terminal transaction", 0, restored.fake.deliveryCount)
        assertEquals("a fresh scheduler query is the one legal read", 1, restored.fake.nextCardCount)
    }

    // ------------------------------------------------ PART I §6 — the next-card barrier, behaviour

    @Test fun `the reducer emits a next-card effect exactly when the barrier allows it`() = runTest {
        // Committed: the only outcome that advances the session.
        val committed = AnkiCommitHarness()
        committed.loadToRating()
        committed.rate()
        committed.run(committed.takeCommitEffect())
        assertEquals(ReviewCommitStatus.COMMITTED, committed.commit!!.status)
        assertTrue("the committed outcome advances", committed.pending.any { it is AnkiStudyEffect.Next })
        assertNull("the turn is released only by COMMITTED", committed.turn)

        // Proven not applied: no next card, the turn stays the user's current card.
        val failed = AnkiCommitHarness(commitSteps = listOf(
            FakeAnkiBackend.CommitStep(BackendCommitResult.ConfirmedNotCommitted(AnkiError.QueryFailure("not_applied")))))
        failed.loadToRating()
        val failedTurn = requireNotNull(failed.turn).turnId
        failed.rate()
        failed.run(failed.takeCommitEffect())
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, failed.commit!!.status)
        assertFalse("RETRY_ALLOWED never advances", failed.pending.any { it is AnkiStudyEffect.Next })
        assertEquals("the turn is still the one being rated", failedTurn, requireNotNull(failed.turn).turnId)

        // Unknown outcome: no next card, and no retry either.
        val ambiguous = AnkiCommitHarness(commitSteps = listOf(
            FakeAnkiBackend.CommitStep(BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("lost")))))
        ambiguous.loadToRating()
        ambiguous.rate()
        val nextCardsBefore = ambiguous.fake.nextCardCount
        ambiguous.run(ambiguous.takeCommitEffect())
        assertEquals(ReviewCommitStatus.AMBIGUOUS, ambiguous.commit!!.status)
        assertFalse(ambiguous.pending.any { it is AnkiStudyEffect.Next })
        assertEquals(nextCardsBefore, ambiguous.fake.nextCardCount)
        assertFalse(ambiguous.send(AnkiStudyEvent.RetryRatingCommit(
            ambiguous.state.epoch, ambiguous.commit!!.commitId)).accepted)
    }

    // --------------------------------- INV-11E-21/22 — identity locks (VERIFICATION 15/16)

    @Test fun `a backend preference change never redirects an active transaction`() = runTest {
        val pc = FakeAnkiBackend(id = AnkiBackendId.PcAgent("gate11e-other"),
            decks = listOf(AnkiDeck(AnkiDeckRef(AnkiBackendId.PcAgent("gate11e-other"), "9", "other"), "Other")),
            cards = listOf(AnkiCommitHarness.card("X", AnkiBackendId.PcAgent("gate11e-other"))))
        // The session was started against AnkiDroid while another backend is also installed: a
        // preference-driven resolution *could* now pick the other one, and must not.
        val h = AnkiCommitHarness(extraBackends = listOf(pc))
        h.loadToRating()
        val commitId = requireNotNull(h.turn).commitId
        assertEquals(AnkiBackendId.AnkiDroidLocal, commitId.backendId)
        h.rate()
        h.run(h.takeCommitEffect())
        assertEquals("the commit went to the backend the turn belongs to",
            ReviewCommitStatus.COMMITTED, h.ledger.get(commitId)!!.status)
        assertEquals("the other installed backend was never asked", 0, pc.commitInvocations)
        assertEquals(1, h.fake.commitInvocations)
    }

    @Test fun `an unresolved transaction is never rebound to the newly preferred backend`() = runTest {
        val pc = FakeAnkiBackend(id = AnkiBackendId.PcAgent("gate11e-other"),
            decks = listOf(AnkiDeck(AnkiDeckRef(AnkiBackendId.PcAgent("gate11e-other"), "9", "other"), "Other")),
            cards = listOf(AnkiCommitHarness.card("X", AnkiBackendId.PcAgent("gate11e-other"))))
        val h = AnkiCommitHarness(
            extraBackends = listOf(pc),
            commitSteps = listOf(FakeAnkiBackend.CommitStep(
                BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("lost_response"))))
        )
        h.loadToRating()
        val commitId = requireNotNull(h.turn).commitId
        h.rate()
        h.run(h.takeCommitEffect())
        val versionBefore = h.ledger.get(commitId)!!.version

        // "Preference changed": a future session now resolves the other backend. The unresolved
        // transaction is not re-owned, not redirected and not resolved by that change.
        val other = AnkiCommitHarness(store = h.store, backendId = AnkiBackendId.PcAgent("gate11e-other"),
            mode = AnkiBackendMode.PC_AGENT, cards = listOf(AnkiCommitHarness.card("X", AnkiBackendId.PcAgent("gate11e-other"))))
        other.startAndLoad()
        val record = other.ledger.get(commitId)
        assertEquals("the transaction keeps the backend it was created under",
            AnkiBackendId.AnkiDroidLocal, record!!.backendId)
        assertEquals("no transaction rebinding", versionBefore, record.version)
        assertEquals("no blind resolution on a preference change", ReviewCommitStatus.AMBIGUOUS, record.status)
        assertEquals("the other backend was never consulted", 0, pc.reconcileCalls)
        assertEquals(0, pc.commitInvocations)
    }

    // ------------------------------- INV-11E-06/08 — source-of-truth conflicts (VERIFICATION 20)

    @Test fun `a stale Saving projection loses to a durable COMMITTED record`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val staleProjection = h.state // the screen still shows "Saving"
        h.run(h.takeCommitEffect())
        val record = h.ledger.get(requireNotNull(h.commit).commitId)!!
        assertEquals(ReviewCommitStatus.COMMITTED, record.status)

        val snapshot = CommitTruthDiagnostics.snapshot(machine = staleProjection, record = record)
        assertEquals("Saving", snapshot.studyCommitProjection)
        assertEquals("COMMITTED", snapshot.ledgerCommitState)
        assertTrue("the divergence is recorded, never hidden", snapshot.divergent)
        assertEquals("ResumeCommitted", snapshot.recoveryAction)
        assertTrue("the runtime truth is the durable one", nextCardAllowed(record.status))
        // Adopting it through the reducer re-projects the screen and advances the session.
        assertTrue(RatingCommitRecoveryUi.from(h.state)!!.commitUiState is RatingCommitUiState.Saved)
    }

    @Test fun `a stale Saved projection loses to a durable AMBIGUOUS record`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        h.run(h.takeCommitEffect())
        val savedProjection = h.state // the screen shows "Saved"
        val committed = h.ledger.get(requireNotNull(h.commit).commitId)!!

        // What an inconclusive reconciliation would have written instead: the durable row is the
        // only authority, and here it says the outcome could not be proven.
        val ambiguous = committed.copy(
            status = ReviewCommitStatus.AMBIGUOUS,
            committedRating = null,
            response = CommitResponseEvidence(CommitResponseKind.OUTCOME_UNKNOWN,
                ReviewCommitFailure(ReviewCommitTransitions.UNKNOWN_OUTCOME_CATEGORY)),
            failure = ReviewCommitFailure(ReviewCommitTransitions.UNKNOWN_OUTCOME_CATEGORY),
            resolution = ReviewCommitResolution.RECONCILIATION_INCONCLUSIVE
        )
        val snapshot = CommitTruthDiagnostics.snapshot(machine = savedProjection, record = ambiguous)
        assertEquals("Saved", snapshot.studyCommitProjection)
        assertEquals("AMBIGUOUS", snapshot.ledgerCommitState)
        assertTrue(snapshot.divergent)
        assertEquals("Reconcile", snapshot.recoveryAction)
        assertFalse("the screen may say saved; the transaction may not advance",
            nextCardAllowed(ambiguous.status))
    }
}
