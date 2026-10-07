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
 * GATE 11B PART IV — the normative **source-of-truth recovery matrix**, plus the purity of the
 * recovery policy and the PART V divergence diagnostics.
 *
 * The matrix is read as `durable ledger state × interrupted study snapshot → required runtime
 * state`, and every row is implemented by the same three facts:
 *
 * 1. the ledger decides the transaction truth,
 * 2. the backend decides the scheduler truth,
 * 3. the machine re-projects both and the UI renders that projection.
 */
class Gate11bRecoveryMatrixTest {

    // ------------------------------------------------------------ policy purity (PART I §12)

    @Test fun `matrix the recovery policy is a pure function of the durable status`() = runTest {
        val policy = ReviewCommitRecoveryPolicy()
        assertEquals(ReviewCommitRecoveryAction.OfferRetry, policy.classifyStatus(ReviewCommitStatus.PREPARED))
        assertEquals(ReviewCommitRecoveryAction.Reconcile, policy.classifyStatus(ReviewCommitStatus.SUBMITTING))
        assertEquals(ReviewCommitRecoveryAction.OfferRetry, policy.classifyStatus(ReviewCommitStatus.RETRY_ALLOWED))
        assertEquals(ReviewCommitRecoveryAction.Reconcile, policy.classifyStatus(ReviewCommitStatus.AMBIGUOUS))
        assertEquals(ReviewCommitRecoveryAction.ResumeCommitted, policy.classifyStatus(ReviewCommitStatus.COMMITTED))
        // Deterministic: the same status, asked twice, is the same decision, and no proof is needed.
        assertEquals(policy.classifyStatus(ReviewCommitStatus.AMBIGUOUS),
            policy.classifyStatus(ReviewCommitStatus.AMBIGUOUS))
        // …and the record-level entry point agrees with the status-level table without proof.
        ReviewCommitStatus.values().forEach { status ->
            assertEquals(status.name, policy.classifyStatus(status), policy.classify(record(status)))
        }
    }

    @Test fun `matrix reconciliation proof is only meaningful for an ambiguous record`() = runTest {
        val policy = ReviewCommitRecoveryPolicy()
        val committed = record(ReviewCommitStatus.COMMITTED)
        val proof = ReconcileCommitResult.Applied("evidence")
        assertTrue("proof for a non-ambiguous record is an integrity failure, never a shortcut",
            policy.classify(committed, proof) is ReviewCommitRecoveryAction.IntegrityFailure)
        assertEquals(ReviewCommitRecoveryAction.ResumeCommitted, policy.classify(committed))
        val ambiguous = record(ReviewCommitStatus.AMBIGUOUS)
        assertEquals(ReviewCommitRecoveryAction.ResumeCommitted, policy.classify(ambiguous, proof))
        assertEquals(ReviewCommitRecoveryAction.OfferRetry,
            policy.classify(ambiguous, ReconcileCommitResult.NotApplied("evidence")))
        assertEquals(ReviewCommitRecoveryAction.RemainBlocked,
            policy.classify(ambiguous, ReconcileCommitResult.StillAmbiguous("evidence")))
        assertEquals(ReviewCommitRecoveryAction.RemainBlocked,
            policy.classify(ambiguous, ReconcileCommitResult.Unsupported("evidence")))
        assertEquals(ReviewCommitRecoveryAction.RemainBlocked,
            policy.classify(ambiguous, ReconcileCommitResult.Unavailable(AnkiError.BackendUnavailable())))
    }

    // ---------------------------------------------------------------- matrix rows

    @Test fun `matrix COMMITTED durable with a stale submitting snapshot resumes committed`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        // Durable COMMITTED, the session's snapshot still says "submitting": the executor ran but
        // the result never reached the reducer.
        h.executor.execute(effect)
        assertEquals(SessionPhase.SubmittingRating, h.state.phase)

        val record = h.store.durableRecords().single()
        assertEquals(ReviewCommitStatus.COMMITTED, record.status)
        assertEquals(ReviewCommitRecoveryAction.ResumeCommitted, ReviewCommitRecoveryPolicy().classifyStatus(record.status))

        h.restartLedger()
        val replay = h.executor.execute(effect) as AnkiStudyEvent.RatingCommitResolved
        assertTrue(replay.outcome is AnkiCommitOutcome.Committed)
        h.send(replay)
        assertEquals(SessionPhase.WaitingForFirstCard, h.state.phase)
        assertEquals(1, h.fake.deliveryCount)
    }

    @Test fun `matrix COMMITTED durable with a waiting snapshot never resubmits`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        h.drain()
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()
        h.restartLedger()

        // A new session in the same collection: a COMMITTED record is not a blocker at all.
        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-2", AnkiBackendMode.ANKIDROID_LOCAL, h.deck)))
        h.drain()
        assertTrue("the session resumes normally", h.state.phase.isActive)
        assertNull("no restored transaction is projected", h.state.anki!!.commit)
        assertEquals(1, h.fake.deliveryCount)
        assertEquals("the finished transaction was never re-sent", 1, h.fake.backendEffectCount)
        assertEquals(ReviewCommitStatus.COMMITTED, h.store.durableRecords().single().status)
    }

    @Test fun `matrix RETRY_ALLOWED durable with a stale submitting snapshot is retryable`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        h.fake.prepareRefusal = CommitPreparation.Refused(AnkiError.BackendUnavailable(), retryable = true)
        h.drain()
        val record = h.store.durableRecords().single()
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, record.status)
        assertEquals(ReviewCommitRecoveryAction.OfferRetry, ReviewCommitRecoveryPolicy().classifyStatus(record.status))

        // In the same process the turn is still live, so the retry mutation is offered and legal.
        assertTrue(RatingCommitRecoveryUi.from(h.state)!!.canRetry)
        h.fake.prepareRefusal = null
        assertTrue(h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, record.commitId)).accepted)
        h.drain()
        assertEquals(ReviewCommitStatus.COMMITTED, h.store.durableRecords().single().status)
        assertEquals(1, h.fake.backendEffectCount)
    }

    @Test fun `matrix AMBIGUOUS durable with a waiting snapshot stays blocked`() = runTest {
        val h = AnkiCommitHarness(commitSteps = listOf(
            FakeAnkiBackend.CommitStep(BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("timeout")))))
        h.loadToRating()
        h.rate()
        h.drain()
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()
        h.restartLedger()

        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-2", AnkiBackendMode.ANKIDROID_LOCAL, h.deck)))
        h.drain()
        val record = h.store.durableRecords().single()
        assertEquals(ReviewCommitStatus.AMBIGUOUS, record.status)
        assertEquals(ReviewCommitRecoveryAction.Reconcile, ReviewCommitRecoveryPolicy().classifyStatus(record.status))
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
        assertTrue(RatingCommitRecoveryUi.from(h.state)!!.commitUiState is RatingCommitUiState.VerificationRequired)
        assertFalse(RatingCommitRecoveryUi.from(h.state)!!.ratingControlsEnabled)
        assertNull("no scheduler query in a collection with an unresolved mutation", h.turn)
        assertEquals(1, h.fake.deliveryCount)
    }

    @Test fun `matrix AMBIGUOUS durable with a stale submitting snapshot stays blocked`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.fake.mutationGate = CompletableDeferred()
        val job = launch { h.executor.execute(effect) }
        runCurrent()
        job.cancelAndJoin()

        val record = h.store.durableRecords().single()
        assertEquals(ReviewCommitStatus.AMBIGUOUS, record.status)
        assertEquals(ReviewCommitRecoveryAction.Reconcile, ReviewCommitRecoveryPolicy().classifyStatus(record.status))
        assertEquals(SessionPhase.SubmittingRating, h.state.phase) // the stale snapshot

        h.restartLedger()
        val replay = h.executor.execute(effect) as AnkiStudyEvent.RatingCommitResolved
        assertTrue(replay.outcome is AnkiCommitOutcome.Ambiguous)
        h.send(replay)
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
        assertFalse(h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, record.commitId)).accepted)
        assertTrue(h.pending.none { it is AnkiStudyEffect.Next })
        assertEquals(1, h.fake.deliveryCount)
    }

    @Test fun `matrix SUBMITTING durable marked before the boundary is safe to retry`() = runTest {
        // The study snapshot says "submitting"; the durable record is PREPARED, i.e. provably
        // un-entered. The boundary — not the snapshot — decides (GATE 11B §27).
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.fake.commitGate = CompletableDeferred() // never completes: the process dies in preflight
        val job = launch { h.executor.execute(effect) }
        runCurrent()
        job.cancelAndJoin()
        h.fake.commitGate = null

        val record = h.store.durableRecords().single()
        assertEquals(ReviewCommitStatus.PREPARED, record.status)
        assertEquals(ReviewCommitPhase.INTENT_PERSISTED, record.phase)
        assertEquals(ReviewCommitRecoveryAction.OfferRetry, ReviewCommitRecoveryPolicy().classifyStatus(record.status))
        assertEquals(SessionPhase.SubmittingRating, h.state.phase) // the stale snapshot

        h.restartLedger()
        val resumed = h.executor.execute(effect) as AnkiStudyEvent.RatingCommitResolved
        assertTrue(resumed.outcome is AnkiCommitOutcome.Committed)
        assertEquals(1, h.fake.mutationBoundaryCrossingCount)
    }

    @Test fun `matrix SUBMITTING durable after the boundary requires reconciliation`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.fake.mutationGate = CompletableDeferred() // inside the mutation window
        val job = launch { h.executor.execute(effect) }
        runCurrent()
        // The record is durable SUBMITTING while the process still lives: the outcome is unknown.
        val record = h.store.durableRecords().single()
        assertEquals(ReviewCommitStatus.SUBMITTING, record.status)
        assertEquals(ReviewCommitPhase.MUTATION_BOUNDARY_ENTERED, record.phase)
        assertEquals(ReviewCommitRecoveryAction.Reconcile, ReviewCommitRecoveryPolicy().classifyStatus(record.status))
        job.cancelAndJoin()

        // A new process must reconcile, never retry: the coordinator refuses a second dispatch.
        h.restartLedger()
        assertTrue(h.executor.retry(record.commitId) is ReviewCommitOutcome.Ambiguous)
        assertEquals(1, h.fake.deliveryCount)
    }

    @Test fun `matrix a corrupt durable record is an integrity failure and refuses everything`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.store.overwrite("{ not the ledger")
        h.restartLedger()

        // Unknown is not absent: recovery refuses to classify anything, and no mutation happens.
        val recovery = h.executor.recover(effect.request.commitId)
        assertTrue("a corrupt ledger is indeterminate, never 'no transaction': $recovery",
            recovery is ReviewCommitRecoveryResult.Indeterminate)
        val resolved = h.executor.execute(effect) as AnkiStudyEvent.RatingCommitResolved
        assertTrue(resolved.outcome is AnkiCommitOutcome.PersistenceFailure)
        assertEquals(0, h.fake.deliveryCount)
        assertEquals(0, h.fake.backendEffectCount)
        assertTrue(ReviewCommitLedgerCodec.decode("{ not the ledger") is ReviewCommitLedgerCodec.Decoded.Unreadable)
    }

    // ------------------------------------------------------------ PART V divergence diagnostics

    @Test fun `matrix the commit truth snapshot names one owner per domain`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.run(effect)
        val record = h.store.durableRecords().single()

        val truth = CommitTruthDiagnostics.snapshot(h.state, record, backendSchedulerAvailability = "ready")
        assertEquals(SessionPhase.serverPhaseName(h.state.phase), truth.studyState)   // interaction
        assertEquals("Saved", truth.studyCommitProjection)                            // projection
        assertEquals(ReviewCommitStatus.COMMITTED.name, truth.ledgerCommitState)      // transaction
        assertEquals(ReviewCommitPhase.FINAL_STATUS_PERSISTED.name, truth.attemptPhase)
        assertEquals("ready", truth.backendSchedulerAvailability)                     // scheduler
        assertEquals(ReviewCommitRecoveryAction.ResumeCommitted.label, truth.recoveryAction)
        assertFalse(truth.divergent)
        assertEquals(7, truth.rows().size)
    }

    @Test fun `matrix a projection that contradicts the ledger is divergent and the ledger wins`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.executor.execute(effect) // durable COMMITTED, the session still projects "saving"
        val record = h.store.durableRecords().single()

        val truth = CommitTruthDiagnostics.snapshot(h.state, record)
        assertTrue("the divergence is visible instead of silent", truth.divergent)
        assertEquals("Saving", truth.studyCommitProjection)
        val mismatch = CommitTruthDiagnostics.divergence(h.state, record)
        assertNotNull(mismatch)
        assertEquals(ReviewCommitStatus.PREPARED.name, mismatch!!.studyProjection)
        assertEquals(ReviewCommitStatus.COMMITTED.name, mismatch.ledgerState)

        val metadata = CommitTruthDiagnostics.metadata(mismatch)
        assertEquals(ReviewCommitStatus.COMMITTED.name, metadata["ledgerState"])
        assertEquals(ReviewCommitStatus.PREPARED.name, metadata["studyProjection"])
        assertEquals(CommitProjectionMismatch.LEDGER_WINS, metadata["resolution"])
        assertEquals(mismatch.commitId, metadata["commit"])
    }

    @Test fun `matrix the machine reports a recorded divergence exactly on introduction`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.executor.execute(effect) // durable COMMITTED, the session still projects "saving"
        val replay = h.executor.execute(effect) as AnkiStudyEvent.RatingCommitResolved

        val before = h.state
        assertNull(CommitTruthDiagnostics.newlyRecordedMismatch(before, before))
        h.send(replay) // the reducer adopts durable truth and records the override
        val mismatch = CommitTruthDiagnostics.newlyRecordedMismatch(before, h.state)
        assertNotNull("the introduction edge is what the machine reports", mismatch)
        assertEquals(ReviewCommitStatus.PREPARED.name, mismatch!!.studyProjection)
        assertEquals(ReviewCommitStatus.COMMITTED.name, mismatch.ledgerState)
        assertNull("the same divergence is never reported twice",
            CommitTruthDiagnostics.newlyRecordedMismatch(h.state, h.state))
    }

    @Test fun `matrix the executor never exposes a transaction decision through its read-only probes`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.run(effect)
        val record = h.store.durableRecords().single()
        // Diagnostics may read durable truth; they may not write it, and the scheduler question is
        // answered by the backend, never by the ledger.
        assertEquals(record.status, h.executor.durableRecord(record.commitId)?.status)
        assertEquals("ready", h.executor.schedulerAvailabilityOf(record.commitId.backendId))
        assertEquals(1, h.fake.nextCardCount)
        assertEquals(ReviewCommitStatus.COMMITTED, h.store.durableRecords().single().status)
    }

    // ---------------------------------------------------------------- helpers

    private fun record(status: ReviewCommitStatus): ReviewCommitRecord {
        val backend = AnkiCommitHarness.BACKEND
        val card = AnkiCardRef(backend, cardId = "A", collectionKey = "collection")
        val commitId = ReviewCommitId(backend, "study-1", ReviewTurnId("1:A:1"))
        return ReviewCommitRecord(
            commitId = commitId,
            card = card,
            rating = Rating.GOOD,
            status = status,
            attemptCount = 1,
            phase = when (status) {
                ReviewCommitStatus.PREPARED -> ReviewCommitPhase.INTENT_PERSISTED
                ReviewCommitStatus.SUBMITTING -> ReviewCommitPhase.MUTATION_BOUNDARY_ENTERED
                else -> ReviewCommitPhase.FINAL_STATUS_PERSISTED
            },
            failure = if (status == ReviewCommitStatus.AMBIGUOUS || status == ReviewCommitStatus.RETRY_ALLOWED)
                ReviewCommitFailure("test") else null,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
            ratedAtEpochMs = 1L
        )
    }
}
