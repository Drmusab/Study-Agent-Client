package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.FakeCommitMode
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Evaluation
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
 * GATE 11B PART III — the **mandatory source-of-truth tests**.
 *
 * Each test pins one ownership boundary:
 *
 * ```text
 * interaction truth → StudySessionMachine
 * transaction truth → ReviewCommitLedger
 * scheduler truth   → AnkiBackend
 * presentation      → a projection, owned by nobody
 * AI suggestion     → advisory, owned by nobody
 * ```
 *
 * The tests deliberately drive the system through *stale* study projections, because a projection
 * that disagrees with the durable ledger is the failure the gate exists to make impossible:
 * the ledger decides, the machine re-projects it, the UI renders the re-projected state.
 */
class Gate11bSourceOfTruthTest {

    // ---------------------------------------------------------------- 1

    @Test fun `stale_study_projection_is_overridden_by_committed_ledger`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate(Rating.GOOD)
        val effect = h.takeCommitEffect()

        // The backend commits and the ledger is durable — but this session never hears about it.
        val first = h.executor.execute(effect) as AnkiStudyEvent.RatingCommitResolved
        assertTrue(first.outcome is AnkiCommitOutcome.Committed)
        assertEquals(ReviewCommitStatus.PREPARED, h.commit!!.status)          // stale projection
        assertEquals(SessionPhase.SubmittingRating, h.state.phase)

        // A new process: only the durable record survives, and it says COMMITTED.
        h.restartLedger()
        val replay = h.executor.execute(effect) as AnkiStudyEvent.RatingCommitResolved
        assertEquals(AnkiCommitOutcome.Committed(AnkiCommitOutcome.SOURCE_LEDGER_REPLAY), replay.outcome)

        // The ledger wins: the stale projection is re-projected, never re-submitted.
        h.send(replay)
        assertEquals(ReviewCommitStatus.COMMITTED, h.commit!!.status)
        assertEquals(SessionPhase.WaitingForFirstCard, h.state.phase)
        assertTrue(RatingCommitRecoveryUi.from(h.state)!!.commitUiState is RatingCommitUiState.Saved)
        assertEquals(1, h.pending.count { it is AnkiStudyEffect.Next })

        // …and the divergence was recorded rather than silently absorbed (PART V).
        val mismatch = h.state.anki!!.projectionMismatch
        assertNotNull("a stale projection is a recorded divergence, not a silent fix", mismatch)
        assertEquals(ReviewCommitStatus.PREPARED.name, mismatch!!.studyProjection)
        assertEquals(ReviewCommitStatus.COMMITTED.name, mismatch.ledgerState)
        assertEquals("ledger_overrides_study_projection", CommitProjectionMismatch.LEDGER_WINS)

        assertEquals(1, h.fake.deliveryCount)
        assertEquals(1, h.fake.backendEffectCount)
    }

    // ---------------------------------------------------------------- 2

    @Test fun `stale_submitting_projection_is_overridden_by_ambiguous_ledger`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate(Rating.GOOD)
        val effect = h.takeCommitEffect()

        // The process dies inside the mutation window: durable SUBMITTING → recovery AMBIGUOUS.
        h.fake.mutationGate = CompletableDeferred()
        val job = launch { h.executor.execute(effect) }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.store.durableRecords().single().status)
        assertEquals(ReviewCommitStatus.PREPARED, h.commit!!.status)          // stale projection

        // A new process reads the durable record: the projection is seen to disagree with it …
        h.restartLedger()
        val replay = h.executor.execute(effect) as AnkiStudyEvent.RatingCommitResolved
        assertTrue(replay.outcome is AnkiCommitOutcome.Ambiguous)
        val divergence = CommitTruthDiagnostics.divergence(h.state, h.ledger.get(effect.request.commitId))
        assertNotNull("the stale 'saving' projection contradicts the durable AMBIGUOUS record", divergence)
        assertEquals(ReviewCommitStatus.PREPARED.name, divergence!!.studyProjection)
        assertEquals(ReviewCommitStatus.AMBIGUOUS.name, divergence.ledgerState)

        // …and the durable truth overrides it: blocked, never resolved by guessing.
        h.send(replay)
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit!!.status)
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
        assertTrue(RatingCommitRecoveryUi.from(h.state)!!.commitUiState is RatingCommitUiState.VerificationRequired)
        assertFalse(RatingCommitRecoveryUi.from(h.state)!!.canRetry)
        assertTrue(h.pending.none { it is AnkiStudyEffect.Next })
        assertNull("no divergence once the projection mirrors the ledger",
            CommitTruthDiagnostics.divergence(h.state, h.ledger.get(effect.request.commitId)))
        assertEquals(1, h.fake.commitInvocations)
    }

    // ---------------------------------------------------------------- 3

    @Test fun `retryable_ledger_restores_retryable_study_projection`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate(Rating.HARD)
        h.fake.prepareRefusal = CommitPreparation.Refused(AnkiError.BackendUnavailable(), retryable = true)
        h.drain()
        val commitId = h.commit!!.commitId
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, h.commit!!.status)
        assertEquals(0, h.fake.deliveryCount) // refused before dispatch

        // A new process, same study session: the durable transaction is restored as the projection.
        val nextCardsBefore = h.fake.nextCardCount
        h.restartLedger()
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()
        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.ANKIDROID_LOCAL, h.deck)))
        h.drain()

        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, h.commit!!.status)
        assertEquals(SessionPhase.RatingCommitFailed, h.state.phase)
        assertTrue("the durable decision is re-projected, not re-decided",
            RatingCommitRecoveryUi.from(h.state)!!.commitUiState is RatingCommitUiState.VerificationRequired)
        assertFalse("a restored turn has no live AnkiDroid handle, so no retry mutation is offered",
            RatingCommitRecoveryUi.from(h.state)!!.canRetry)
        assertEquals(commitId, h.commit!!.commitId)
        assertEquals(Rating.HARD, h.commit!!.selectedRating)
        assertNull("no scheduler query before the unresolved transaction is dealt with", h.turn)
        assertEquals(0, h.fake.deliveryCount)
        assertEquals("the restore issued no scheduler query", nextCardsBefore, h.fake.nextCardCount)
    }

    // ---------------------------------------------------------------- 4

    @Test fun `ui_state_cannot_create_commit_without_reducer_event`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()

        // Writing a "saved" projection straight into the state creates nothing at all: no ledger
        // row, no backend call, no effect. The UI owns no business truth (brief PART II).
        val liveTurn = h.turn!!
        val forged = AnkiRatingCommit(
            CommitRatingRequest(liveTurn.commitId, liveTurn.cardRef, Rating.GOOD, h.now),
            ReviewCommitStatus.COMMITTED
        )
        h.state = h.state.copy(anki = h.state.anki!!.copy(commit = forged))
        assertTrue(forged.commitUiState is RatingCommitUiState.Saved)
        assertFalse("a 'Saved' projection never re-opens the rating controls", forged.commitUiState.ratingControlsEnabled)
        assertTrue("a hand-written projection emits no effect", h.pending.none { it is AnkiStudyEffect.Next })
        assertEquals("the ledger is untouched by a UI projection", 0, h.store.durableRecords().size)
        assertEquals(0, h.fake.commitInvocations)
        assertEquals(0, h.state.session!!.totalReviewedInSession)

        // The only path that creates a transaction is the reducer's rating event.
        h.state = h.state.copy(anki = h.state.anki!!.copy(commit = null))
        assertTrue(h.rate(Rating.GOOD).accepted)
        assertEquals(1, h.pending.count { it is AnkiStudyEffect.CommitRating })
        h.drain()
        assertEquals(1, h.store.durableRecords().size)
        assertEquals(1, h.fake.backendEffectCount)
    }

    // ---------------------------------------------------------------- 5

    @Test fun `selected_rating_does_not_equal_committed_rating`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        // The evaluation suggests HARD; the user selects GOOD.
        h.state = h.state.copy(cardTurn = h.state.cardTurn!!.withEvaluation(
            Evaluation(score = 80, shortFeedback = "mostly correct", suggestedRating = Rating.HARD)))
        val suggested = h.state.cardTurn!!.suggestedRating
        assertEquals(Rating.HARD, suggested)

        assertTrue(h.rate(Rating.GOOD).accepted)
        val commitId = h.commit!!.commitId

        // Selected != suggested, and selected != committed until the backend confirms.
        assertEquals(Rating.GOOD, h.commit!!.selectedRating)
        assertNotEquals("an AI suggestion is never the selection", suggested, h.commit!!.selectedRating)
        assertNull("a selection is a user choice, not a scheduler fact", h.commit!!.committedRating)
        assertNull(h.state.anki!!.committedRating)

        // Suspend inside the mutation window: the durable record exists and still separates them.
        h.fake.mutationGate = CompletableDeferred()
        val job = launch { h.drain() }
        runCurrent()
        val durable = h.store.durableRecords().single()
        assertEquals(Rating.GOOD, durable.selectedRating)
        assertNull("the durable record keeps selection and commitment apart", durable.committedRating)
        assertNull(h.ledger.get(commitId)!!.committedRating)

        h.fake.mutationGate!!.complete(Unit)
        job.join()
        val committed = h.store.durableRecords().single()
        assertEquals(Rating.GOOD, committed.committedRating)
        assertEquals(Rating.GOOD, committed.selectedRating)
        assertEquals("the suggestion is never what the scheduler received",
            Rating.GOOD, h.fake.recordedCommits().single().request.rating)
    }

    // ---------------------------------------------------------------- 6

    @Test fun `ledger_commit_does_not_predict_next_card`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        h.run(h.takeCommitEffect())

        val record = h.store.durableRecords().single()
        assertEquals(ReviewCommitStatus.COMMITTED, record.status)
        assertEquals("the record is evidence about the card that was rated", "A", record.card.cardId)
        assertEquals("the ledger never names a successor", 1, h.store.durableRecords().size)

        // The next card is a scheduler question: the effect carries a session, never a card.
        val next = h.pending.singleOrNull { it is AnkiStudyEffect.Next } as AnkiStudyEffect.Next
        assertEquals(h.state.anki!!.reviewSession, next.session)
        h.pending.remove(next)

        // The scheduler answers — and the answer is a failure. The ledger said COMMITTED, so the
        // commit is not undone, but there is still no next card: only Anki chooses one.
        h.send(AnkiStudyEvent.Scheduled(h.state.epoch,
            NextCardResult.Failure(AnkiError.QueryFailure("provider"))))
        assertTrue(h.state.phase is SessionPhase.Error)
        assertNull("no card was chosen", h.turn)
        assertEquals(ReviewCommitStatus.COMMITTED, h.store.durableRecords().single().status)
        assertEquals(1, h.fake.deliveryCount) // the failure never replays the rating

        // Asking again is a read: the scheduler, not the ledger, produces card B.
        h.send(AnkiStudyEvent.RetryNextCard(h.state.epoch))
        h.drain()
        assertEquals("B", h.turn!!.cardRef.cardId)
        assertEquals("still only the one transaction record", 1, h.store.durableRecords().size)
        assertEquals(1, h.fake.deliveryCount)
    }

    // ---------------------------------------------------------------- 7

    @Test fun `scheduler_change_does_not_mark_commit_committed`() = runTest {
        // The scheduler state really did change: the stand-in applied the mutation and then lost
        // the response. That is not proof of anything for this commit id (brief PART II: scheduler change proves no commit).
        val h = AnkiCommitHarness(fakeMode = FakeCommitMode.MUTATE_THEN_DROP_RESPONSE)
        h.loadToRating()
        h.rate()
        h.drain()
        assertEquals("the collection changed", 1, h.fake.backendEffectCount)
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.store.durableRecords().single().status)
        val commitId = h.commit!!.commitId

        // Under this backend identity reconciliation cannot prove it, so it stays unknown.
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        h.drain()
        assertEquals(0, h.fake.reconcileCalls)
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.store.durableRecords().single().status)
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)

        // Even a backend that *can* reconcile only resolves on commit-correlated evidence:
        // inconclusive proof leaves the transaction ambiguous no matter what the scheduler shows.
        val r = AnkiCommitHarness.reconcilable(commitSteps = listOf(
            FakeAnkiBackend.CommitStep(BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("lost")),
                appliedWhenAmbiguous = true)))
        r.loadToRating()
        r.rate()
        r.drain()
        assertEquals("the scheduler has the mutation", 1, r.fake.backendEffectCount)
        r.fake.reconcileResults.add(ReconcileCommitResult.StillAmbiguous("counters_unreadable"))
        r.send(AnkiStudyEvent.ReconcileRatingCommit(r.state.epoch, r.commit!!.commitId))
        r.drain()
        assertEquals(1, r.fake.reconcileCalls)
        assertEquals(ReviewCommitStatus.AMBIGUOUS, r.store.durableRecords().single().status)
        assertEquals(SessionPhase.ReconciliationRequired, r.state.phase)
        val stuckTurn = r.turn
        assertNotNull("the turn is still the one that is unresolved", stuckTurn)
        assertEquals("no card advanced on a scheduler change alone", "A", stuckTurn?.cardRef?.cardId)
    }

    // ---------------------------------------------------------------- 8

    @Test fun `committed_ledger_allows_next_card_query_but_does_not_choose_card`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        h.run(h.takeCommitEffect())

        // COMMITTED makes the query legal …
        val next = h.pending.singleOrNull { it is AnkiStudyEffect.Next } as? AnkiStudyEffect.Next
        assertNotNull("nextCard is legal only after durable COMMITTED", next)
        assertEquals("the effect carries a session, never a card choice",
            h.state.anki!!.reviewSession, next!!.session)

        // …but the scheduler decides the answer, and "no more cards" is a legal answer.
        h.send(AnkiStudyEvent.Scheduled(h.state.epoch, NextCardResult.Finished))
        assertEquals(SessionPhase.Finished, h.state.phase)
        assertNull("the ledger did not choose this", h.turn)
        assertEquals(1, h.fake.nextCardCount)
        assertEquals(ReviewCommitStatus.COMMITTED, h.store.durableRecords().single().status)
        assertEquals(1, h.fake.deliveryCount)
    }
}
