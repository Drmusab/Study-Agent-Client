package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend.CommitStep
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 11 — rating commit, scheduler progression and exactly-once reliability, end to end below
 * the UI: pure reducer + real executor + durable ledger + scripted backend (Part V A–Z, Part VI
 * chaos, Part VII persistence).
 */
class AnkiRatingCommitFlowTest {

    // ---------------------------------------------------------------- A. the happy path

    @Test fun `A rating commits then advances to the scheduler's next card exactly once`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        val firstTurn = h.turn!!
        val selected = h.rate(Rating.GOOD)
        assertEquals(SessionPhase.SubmittingRating, h.state.phase)
        assertTrue("never RatingSelected → next card", selected.effects.none { it is AnkiStudyEffect.Next })
        val effect = h.takeCommitEffect()
        assertEquals(firstTurn.commitId, effect.request.commitId)
        assertEquals(Rating.GOOD, effect.request.rating)

        val events = h.run(effect)
        assertTrue(events.first() is AnkiStudyEvent.RatingCommitStarted)
        assertTrue((events.last() as AnkiStudyEvent.RatingCommitResolved).outcome is AnkiCommitOutcome.Committed)
        assertEquals(SessionPhase.WaitingForFirstCard, h.state.phase)
        assertEquals(1, h.pending.count { it is AnkiStudyEffect.Next })
        assertEquals(1, h.state.session!!.totalReviewedInSession)
        assertEquals(ReviewCommitState.COMMITTED, h.ledger.get(firstTurn.commitId)!!.state)

        h.drain()
        assertNotEquals(firstTurn.turnId, h.turn!!.turnId)
        assertEquals("B", h.turn!!.cardRef.cardId)
        assertNull("the new turn starts a new transaction", h.commit)
        assertEquals(1, h.fake.physicalCommitCalls)
    }

    @Test fun `B NOT_STARTED then SUBMITTING are durable before the backend is called`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        val gate = CompletableDeferred<Unit>()
        h.fake.commitGate = gate
        val job = launch { h.run(effect) }
        runCurrent()
        // The backend call is in progress (suspended at the gate): look at the disk.
        val observed = h.store.durableRecords().single()
        val firstWrite = ReviewCommitLedgerCodec.decode(h.store.writes.first()) as ReviewCommitLedgerCodec.Decoded.Records
        assertEquals(ReviewCommitState.NOT_STARTED, firstWrite.records.single().state)
        assertEquals(ReviewCommitState.SUBMITTING, observed.state)
        assertEquals(CommitAttemptPhase.MUTATION_CALL_ENTERED, observed.phase)
        assertNotNull("baseline evidence is persisted before the call", observed.evidence)
        assertEquals(ReviewCommitState.SUBMITTING, h.commit!!.state)
        gate.complete(Unit)
        job.join()
        assertEquals(ReviewCommitState.COMMITTED, h.store.durableRecords().single().state)
    }

    @Test fun `C answer time is measured from question presentation to rating selection`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating() // presented at t, rated 4 s later
        h.rate()
        assertEquals(4_000L, h.takeCommitEffect().request.answerDurationMs)
    }

    // ---------------------------------------------------------------- D. duplicate input safety

    @Test fun `D touch voice headset and keyboard duplicates produce one mutation and the first rating wins`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        val turnId = h.turn!!.turnId
        assertTrue(h.rate(Rating.GOOD).accepted)                                            // touch
        assertFalse(h.send(StudyEvent.UserRateCard(Rating.GOOD, h.state.currentCardId!!)).accepted) // voice
        assertFalse(h.send(AnkiStudyEvent.SelectRating(h.state.epoch, turnId, Rating.GOOD)).accepted) // headset
        assertFalse(h.send(AnkiStudyEvent.SelectRating(h.state.epoch, turnId, Rating.EASY)).accepted) // keyboard, conflicting
        assertEquals(listOf("duplicate-anki-rating", "duplicate-anki-rating", "anki-rating-locked-first-wins"), h.rejected)
        assertEquals(1, h.pending.count { it is AnkiStudyEffect.CommitRating })
        h.drain()
        assertEquals(1, h.fake.physicalCommitCalls)
        assertEquals(Rating.GOOD, h.fake.recordedCommits().single().request.rating)
    }

    @Test fun `D2 a duplicated commit effect executed concurrently dispatches once`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        val results = List(8) { async { h.executor.execute(effect) } }.awaitAll()
        assertEquals(1, h.fake.physicalCommitCalls)
        val outcomes = results.map { (it as AnkiStudyEvent.RatingCommitResolved?)?.outcome }
        // Exactly one attempt reached the backend; every other delivery either found it in flight
        // (no event) or answered from the ledger — never a second dispatch.
        assertEquals(1, outcomes.count { it == AnkiCommitOutcome.Committed("backend_confirmed") })
        assertTrue(outcomes.all { it == null || it is AnkiCommitOutcome.Committed })
        assertEquals(ReviewCommitState.COMMITTED, h.ledger.get(effect.request.commitId)!!.state)
    }

    // ---------------------------------------------------------------- E. correlation / stale

    @Test fun `E a duplicate COMMITTED result never advances twice and a stale one never touches the new turn`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        val resolved = h.run(effect).last() as AnkiStudyEvent.RatingCommitResolved
        assertEquals(1, h.pending.count { it is AnkiStudyEffect.Next })
        val duplicate = h.send(resolved)
        assertFalse(duplicate.accepted)
        assertEquals("duplicate-anki-commit-result", duplicate.rejectionReason)
        assertEquals(1, h.pending.count { it is AnkiStudyEffect.Next })
        h.drain() // next card B presented
        val newTurn = h.turn!!
        val stale = h.send(resolved)
        assertFalse(stale.accepted)
        assertEquals("stale-anki-commit-result", stale.rejectionReason)
        assertEquals(newTurn, h.turn)
        assertEquals(1, h.state.session!!.totalReviewedInSession)
    }

    @Test fun `E2 results for another session epoch or commit id are rejected`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val id = h.commit!!.commitId
        val committed = AnkiCommitOutcome.Committed("backend_confirmed")
        for (event in listOf(
            AnkiStudyEvent.RatingCommitResolved(h.state.epoch + 1, id, committed),
            AnkiStudyEvent.RatingCommitResolved(h.state.epoch, id.copy(turnId = ReviewTurnId("other")), committed),
            AnkiStudyEvent.RatingCommitResolved(h.state.epoch, id.copy(studySessionId = "other"), committed)
        )) {
            assertFalse(h.send(event).accepted)
        }
        assertEquals(SessionPhase.SubmittingRating, h.state.phase)
        assertTrue(h.pending.none { it is AnkiStudyEffect.Next })
    }

    // ---------------------------------------------------------------- F/G. known failures

    @Test fun `F a proven not applied failure blocks and retries with the same commit id and rating`() = runTest {
        val h = AnkiCommitHarness(commitSteps = listOf(
            CommitStep(CommitRatingResult.RetryableFailure(AnkiError.BackendUnavailable()))))
        h.loadToRating()
        h.rate(Rating.HARD)
        val original = h.takeCommitEffect()
        h.run(original)
        assertEquals(SessionPhase.RatingCommitFailed, h.state.phase)
        assertTrue(h.commit!!.safeToRetry)
        assertTrue(h.pending.none { it is AnkiStudyEffect.Next })
        assertEquals(0, h.state.session!!.totalReviewedInSession)
        val ui = RatingCommitRecoveryUi.from(h.state)!!
        assertEquals(RatingCommitRecoveryUi.Status.NOT_SAVED, ui.status)
        assertTrue(ui.canRetry)
        assertFalse(ui.ratingControlsEnabled)
        // A new rating is not a retry.
        assertFalse(h.send(StudyEvent.UserRateCard(Rating.EASY, h.state.currentCardId!!)).accepted)

        h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, h.commit!!.commitId))
        val retry = h.takeCommitEffect()
        assertTrue(retry.retry)
        assertEquals(original.request, retry.request)
        h.run(retry)
        assertEquals(SessionPhase.WaitingForFirstCard, h.state.phase)
        val recorded = h.fake.recordedCommits().single()
        assertEquals(2, recorded.attempts)
        assertEquals(1, recorded.mutationCount)
        assertEquals(Rating.HARD, recorded.request.rating)
        assertEquals(2, h.ledger.get(original.request.commitId)!!.attemptCount)
    }

    @Test fun `G a rejected commit cannot be retried and the session can be ended safely`() = runTest {
        val h = AnkiCommitHarness(commitSteps = listOf(
            CommitStep(CommitRatingResult.Rejected(AnkiError.CommitConflict()))))
        h.loadToRating()
        h.rate()
        h.drain()
        assertEquals(SessionPhase.RatingCommitFailed, h.state.phase)
        assertFalse(h.commit!!.safeToRetry)
        assertEquals("anki-retry-not-safe",
            h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, h.commit!!.commitId)).rejectionReason)
        h.send(StudyEvent.UserEndRequested("end"))
        assertEquals(SessionPhase.Finished, h.state.phase)
        assertEquals(1, h.pending.count { it is AnkiStudyEffect.EndReview })
        assertEquals(1, h.fake.physicalCommitCalls)
    }

    // ---------------------------------------------------------------- H. ambiguity

    @Test fun `H an ambiguous commit blocks progression retry and re-rating`() = runTest {
        val h = AnkiCommitHarness(commitSteps = listOf(
            CommitStep(CommitRatingResult.Ambiguous(AnkiError.Unknown("timeout")), appliedWhenAmbiguous = true)))
        h.loadToRating()
        h.rate()
        h.drain()
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
        assertEquals(ReviewCommitState.AMBIGUOUS, h.ledger.get(h.commit!!.commitId)!!.state)
        assertTrue(h.pending.isEmpty())
        assertFalse(h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, h.commit!!.commitId)).accepted)
        assertFalse(h.send(StudyEvent.UserRateCard(Rating.GOOD, h.state.currentCardId!!)).accepted)
        assertEquals(0, h.state.session!!.totalReviewedInSession)
        val ui = RatingCommitRecoveryUi.from(h.state)!!
        assertEquals(RatingCommitRecoveryUi.Status.UNCONFIRMED, ui.status)
        assertFalse(ui.canRetry)
        assertTrue(ui.canCheckAgain)
        assertTrue(ui.canEndSession)
        // Re-executing the original effect (redelivery) answers from the ledger, no second call.
        assertEquals(1, h.fake.physicalCommitCalls)
    }

    @Test fun `H2 reconciliation that proves the write applied commits and advances once`() = runTest {
        // Only a backend whose *frozen* semantics include authoritative reconciliation may be asked.
        val h = AnkiCommitHarness.reconcilable(commitSteps = listOf(
            CommitStep(CommitRatingResult.Ambiguous(AnkiError.Unknown("ack-lost")), appliedWhenAmbiguous = true)))
        h.loadToRating()
        h.rate()
        h.drain()
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, h.commit!!.commitId))
        assertEquals(RatingCommitRecoveryUi.Status.CHECKING, RatingCommitRecoveryUi.from(h.state)!!.status)
        assertFalse("one reconciliation at a time",
            h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, h.commit!!.commitId)).accepted)
        h.drain()
        assertEquals(1, h.fake.reconcileCalls)
        assertEquals("B", h.turn!!.cardRef.cardId)
        assertEquals(1, h.state.session!!.totalReviewedInSession)
        assertEquals(ReviewCommitResolution.RECONCILED_APPLIED, h.store.durableRecords().first().resolution)
        assertEquals(1, h.fake.physicalCommitCalls)
    }

    @Test fun `H3 reconciliation that proves not applied allows an explicit retry only`() = runTest {
        val h = AnkiCommitHarness.reconcilable(commitSteps = listOf(
            CommitStep(CommitRatingResult.Ambiguous(AnkiError.Unknown("lost")), appliedWhenAmbiguous = false)))
        h.loadToRating()
        h.rate()
        h.drain()
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, h.commit!!.commitId))
        h.drain()
        assertEquals(SessionPhase.RatingCommitFailed, h.state.phase)
        assertTrue(h.commit!!.safeToRetry)
        assertTrue(h.pending.isEmpty())
        h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, h.commit!!.commitId))
        h.drain()
        assertEquals("B", h.turn!!.cardRef.cardId)
        assertEquals(1, h.fake.recordedCommits().first().mutationCount)
    }

    @Test fun `H2b AnkiDroid identity never consults reconciliation even if an adapter claims it`() = runTest {
        // The fake claims authoritative reconciliation, but AnkiDroid semantics are clamped to
        // AT_MOST_ONCE_FAIL_CLOSED at freeze time: the transaction stays AMBIGUOUS, backend untouched.
        val h = AnkiCommitHarness(commitSteps = listOf(
            CommitStep(CommitRatingResult.Ambiguous(AnkiError.Unknown("ack-lost")), appliedWhenAmbiguous = true)))
        h.loadToRating()
        h.rate()
        h.drain()
        val record = h.ledger.get(h.commit!!.commitId)!!
        assertEquals(CommitGuaranteeLevel.LOCAL_DEDUP_ONLY, record.frozenGuarantee)
        assertFalse(record.frozenAuthoritativeReconciliation)
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, h.commit!!.commitId))
        h.drain()
        assertEquals(0, h.fake.reconcileCalls)
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
        assertEquals(ReviewCommitState.AMBIGUOUS, h.ledger.get(h.commit!!.commitId)!!.state)
        assertEquals("A", h.turn!!.cardRef.cardId)
        assertEquals(1, h.fake.physicalCommitCalls)
    }

    @Test fun `H5 a hung reconciliation times out as still ambiguous and can be checked again`() = runTest {
        val h = AnkiCommitHarness.reconcilable(commitSteps = listOf(
            CommitStep(CommitRatingResult.Ambiguous(AnkiError.Unknown("ack-lost")), appliedWhenAmbiguous = true)))
        h.loadToRating()
        h.rate()
        h.drain()
        h.fake.reconcileGate = CompletableDeferred() // never answers
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, h.commit!!.commitId))
        val started = testScheduler.currentTime
        h.drain()
        assertEquals(AnkiStudyEffectExecutor.DEFAULT_RECONCILE_TIMEOUT_MS, testScheduler.currentTime - started)
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
        assertFalse(h.commit!!.reconciling)
        val record = h.ledger.get(h.commit!!.commitId)!!
        assertEquals(ReviewCommitState.AMBIGUOUS, record.state)
        assertEquals(ReviewCommitResolution.RECONCILIATION_INCONCLUSIVE, record.resolution)
        assertEquals("A", h.turn!!.cardRef.cardId) // no advance on an unknown outcome
        assertEquals(1, h.fake.physicalCommitCalls) // and never a second mutation

        h.fake.reconcileGate = null // the provider answers on the next explicit check
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, h.commit!!.commitId))
        h.drain()
        assertEquals("B", h.turn!!.cardRef.cardId)
        assertEquals(1, h.state.session!!.totalReviewedInSession)
        assertEquals(1, h.fake.physicalCommitCalls)
    }

    @Test fun `H4 inconclusive or unsupported reconciliation stays AMBIGUOUS`() = runTest {
        val h = AnkiCommitHarness(commitSteps = listOf(CommitStep(CommitRatingResult.Ambiguous())))
        h.loadToRating()
        h.rate()
        h.drain()
        h.fake.reconcileResults.add(ReconcileCommitResult.StillAmbiguous("external_review_activity"))
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, h.commit!!.commitId))
        h.drain()
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
        assertFalse(h.commit!!.reconciling)
        assertEquals(ReviewCommitState.AMBIGUOUS, h.ledger.get(h.commit!!.commitId)!!.state)
    }

    @Test fun `I an exception escaping the backend after dispatch is AMBIGUOUS not failed`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        h.fake.commitThrowable = IllegalStateException("binder died")
        h.drain()
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
        assertEquals("unknown:commit_threw", h.commit!!.failureCategory)
    }

    @Test fun `J cancellation after SUBMITTING persists AMBIGUOUS`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.fake.commitGate = CompletableDeferred()
        val job = launch { h.executor.execute(effect) }
        runCurrent()
        assertEquals(ReviewCommitState.SUBMITTING, h.store.durableRecords().single().state)
        job.cancelAndJoin()
        val record = h.store.durableRecords().single()
        assertEquals(ReviewCommitState.AMBIGUOUS, record.state)
        assertEquals("cancelled_after_dispatch", record.failure!!.category)
    }

    // ---------------------------------------------------------------- K. stop during commit

    @Test fun `K ending the session mid commit never duplicates and a late result cannot advance`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        val gate = CompletableDeferred<Unit>()
        h.fake.commitGate = gate
        val inFlight = async { h.executor.execute(effect) }
        runCurrent()
        h.send(StudyEvent.UserEndRequested("stop"))
        assertEquals(SessionPhase.Finished, h.state.phase)
        gate.complete(Unit)
        val late = inFlight.await()!!
        assertFalse(h.send(late).accepted)
        assertTrue(h.pending.none { it is AnkiStudyEffect.Next })
        assertEquals("the ledger still learns the outcome", ReviewCommitState.COMMITTED,
            h.ledger.get(effect.request.commitId)!!.state)
        assertEquals(1, h.fake.physicalCommitCalls)
        // Background / UI recreation never produce a mutation.
        assertTrue(h.send(StudyEvent.UiRecreated).effects.none { it is AnkiStudyEffect.CommitRating })
    }

    @Test fun `K2 new session in unresolved collection is blocked before nextCard`() = runTest {
        val h = AnkiCommitHarness(commitSteps = listOf(CommitStep(CommitRatingResult.Ambiguous())))
        h.loadToRating()
        h.rate()
        h.drain()
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain() // EndReview releases the backend handle (never a mutation)
        assertEquals(1, h.fake.endReviewCalls)
        h.restartLedger() // a new process only has the durable record
        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-2", AnkiBackendMode.ANKIDROID_LOCAL, AnkiCommitHarness.DECK)))
        h.drain()
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
        assertEquals(RatingCommitRecoveryUi.Status.UNCONFIRMED, RatingCommitRecoveryUi.from(h.state)!!.status)
        assertFalse(RatingCommitRecoveryUi.from(h.state)!!.ratingControlsEnabled)
        assertNull("do not start with nextCard()", h.turn)
        assertEquals("old commit is never re-sent", 1, h.fake.physicalCommitCalls)
    }

    // ---------------------------------------------------------------- L. process death

    @Test fun `L1 process death while SUBMITTING restores AMBIGUOUS and never calls the backend again`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.fake.commitGate = CompletableDeferred()
        val job = launch { h.executor.execute(effect) }
        runCurrent()
        job.cancelAndJoin() // the process dies with the call in flight
        h.store.overwrite(h.store.writes[2]) // …and the disk only has CALL_ENTERED, no response
        h.restartLedger()
        val replay = h.executor.execute(effect) as AnkiStudyEvent.RatingCommitResolved
        assertTrue(replay.outcome is AnkiCommitOutcome.Ambiguous)
        assertEquals(1, h.fake.commitInvocations)
    }

    @Test fun `L2 process death after COMMITTED replays from the ledger without a second call`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.run(effect)
        h.restartLedger()
        val replay = h.executor.execute(effect) as AnkiStudyEvent.RatingCommitResolved
        assertEquals(AnkiCommitOutcome.Committed("ledger_replay"), replay.outcome)
        assertEquals(1, h.fake.commitInvocations)
    }

    @Test fun `L3 process death at NOT_STARTED is restorable and dispatches exactly once`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.ledger.prepare(effect.request) // NOT_STARTED persisted, then the process dies
        h.restartLedger()
        assertEquals(1, h.ledger.recoveryReport().restorable)
        val resumed = h.executor.execute(effect) as AnkiStudyEvent.RatingCommitResolved
        assertTrue(resumed.outcome is AnkiCommitOutcome.Committed)
        assertEquals(1, h.fake.physicalCommitCalls)
    }

    // ---------------------------------------------------------------- M. next-card failure

    @Test fun `M next card failure after COMMITTED never replays the rating`() = runTest {
        val h = AnkiCommitHarness(nextErrors = listOf())
        h.loadToRating()
        h.rate()
        h.run(h.takeCommitEffect())
        val next = h.pending.removeFirst() as AnkiStudyEffect.Next
        h.send(AnkiStudyEvent.Scheduled(h.state.epoch, NextCardResult.Failure(AnkiError.QueryFailure("provider"))))
        assertTrue(h.state.phase is SessionPhase.Error)
        assertEquals(ReviewCommitState.COMMITTED, h.commit!!.state)
        assertEquals(1, h.state.session!!.totalReviewedInSession)
        // Retrying the *read* is allowed; it can never re-send the committed rating.
        h.send(AnkiStudyEvent.RetryNextCard(h.state.epoch))
        assertTrue(h.pending.single() is AnkiStudyEffect.Next)
        h.drain()
        assertEquals("B", h.turn!!.cardRef.cardId)
        assertEquals(1, h.fake.physicalCommitCalls)
        assertEquals(next.session, (h.state.anki!!.reviewSession))
    }

    // ---------------------------------------------------------------- N/O. refused before dispatch

    @Test fun `N a refused preparation fails before dispatch and is safe to retry when transient`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        h.fake.prepareRefusal = CommitPreparation.Refused(AnkiError.BackendUnavailable(), retryable = true)
        h.drain()
        assertEquals(SessionPhase.RatingCommitFailed, h.state.phase)
        assertTrue(h.commit!!.safeToRetry)
        assertEquals(0, h.fake.physicalCommitCalls)
        h.fake.prepareRefusal = null
        h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, h.commit!!.commitId))
        h.drain()
        assertEquals("B", h.turn!!.cardRef.cardId)
        assertEquals(1, h.fake.physicalCommitCalls)
    }

    @Test fun `O an unreadable ledger fails closed and nothing is dispatched`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.store.unreadable = true
        h.restartLedger()
        h.rate()
        h.drain()
        assertEquals(SessionPhase.CommitPersistenceFailure, h.state.phase)
        assertEquals("ledger_unavailable", h.commit!!.failureCategory)
        assertFalse(h.commit!!.safeToRetry)
        assertEquals(0, h.fake.commitInvocations)
    }

    @Test fun `O2 without a ledger commits are refused rather than unguarded`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        val bare = AnkiStudyEffectExecutor(AnkiBackendRegistry(listOf(h.fake)))
        val result = bare.execute(effect) as AnkiStudyEvent.RatingCommitResolved
        assertEquals(AnkiCommitOutcome.PersistenceFailure("ledger_unavailable"), result.outcome)
        assertEquals(0, h.fake.commitInvocations)
    }

    @Test fun `persistence fault after backend success blocks next until the durable record is repaired`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        val gate = CompletableDeferred<Unit>()
        h.fake.commitGate = gate
        val job = launch { h.run(effect) }
        runCurrent()
        assertEquals(CommitAttemptPhase.MUTATION_CALL_ENTERED, h.store.durableRecords().single().phase)
        h.store.failNextWrites = 1 // response write fails, AFTER backend success
        gate.complete(Unit)
        job.join()
        assertEquals(1, h.fake.backendEffectCount)
        assertEquals(1, h.fake.deliveryCount)
        assertEquals(SessionPhase.CommitPersistenceFailure, h.state.phase)
        assertTrue(h.pending.none { it is AnkiStudyEffect.Next })
        assertFalse(RatingCommitRecoveryUi.from(h.state)!!.canRetry)
        assertFalse(RatingCommitRecoveryUi.from(h.state)!!.ratingControlsEnabled)
        assertEquals(ReviewCommitState.SUBMITTING, h.store.durableRecords().single().state)

        // This retries a ledger write, NOT commitRating(). A new process with no in-memory
        // response would classify CALL_ENTERED as AMBIGUOUS rather than replay the mutation.
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, h.commit!!.commitId))
        h.drain()
        assertEquals(ReviewCommitState.COMMITTED, h.store.durableRecords().single().state)
        assertEquals(1, h.fake.backendEffectCount)
        assertEquals(1, h.fake.deliveryCount)
        assertEquals("B", h.turn!!.cardRef.cardId)
    }

    @Test fun `failure to persist intent refuses mutation rather than offering a speculative retry`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        h.store.failNextWrites = 1
        h.drain()
        assertEquals(SessionPhase.CommitPersistenceFailure, h.state.phase)
        assertEquals(0, h.fake.deliveryCount)
        assertEquals(0, h.fake.backendEffectCount)
        assertEquals(0, h.store.durableRecords().size)
        assertTrue(h.pending.none { it is AnkiStudyEffect.Next })
    }

    @Test fun `committed restore never shows rating buttons or re-enters mutation`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.run(effect) // COMMITTED durable, Next not yet executed
        assertEquals(RatingCommitRecoveryUi.Status.SAVED, RatingCommitRecoveryUi.from(h.state)!!.status)
        assertFalse(RatingCommitRecoveryUi.from(h.state)!!.ratingControlsEnabled)
        h.restartLedger()
        val replay = h.executor.execute(effect) as AnkiStudyEvent.RatingCommitResolved
        assertTrue(replay.outcome is AnkiCommitOutcome.Committed)
        assertEquals(1, h.fake.deliveryCount)
        assertEquals(1, h.fake.backendEffectCount)
    }

    // ---------------------------------------------------------------- P. scheduler options

    @Test fun `P only ratings the scheduler offered can be committed`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        val turn = h.turn!!
        val content = turn.content as AnkiReviewTurnContent.Rendered
        h.state = h.state.copy(anki = h.state.anki!!.copy(turn = turn.copy(content = content.copy(
            scheduledCard = content.scheduledCard.copy(ratingOptions = AnkiRatingOptions.Unmapped(2))))))
        assertEquals("anki-rating-options-unmapped", h.rate(Rating.GOOD).rejectionReason)
        assertTrue(h.pending.isEmpty())
    }
}
