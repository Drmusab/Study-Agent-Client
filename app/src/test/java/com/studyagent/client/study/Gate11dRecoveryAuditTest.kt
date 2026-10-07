package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend.CommitStep
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
 * GATE 11D — Real Recovery & Reconciliation Audit (PART III verification steps).
 *
 * Every test is fail-closed: the assertions state what may NEVER happen (no second mutation, no
 * next card before truth, no retry for unproven states) as loudly as what must happen. The fake
 * backend is truthful about which commits it applied, so reconciliation evidence here is
 * transaction-correlated (by [ReviewCommitId]) — exactly the standard a real backend's
 * reconciliation must meet. AnkiDroid itself cannot meet it (UNSUPPORTED, see
 * [Gate11dAnkiDroidCapabilityTest]); the default harness's `AnkiDroidLocal` identity clamps the
 * frozen semantics, so its records prove the UNSUPPORTED path end-to-end.
 */
class Gate11dRecoveryAuditTest {

    // ---------------------------------------------------------------- VER 1 — restart per status

    @Test fun `ver1 PREPARED restart in the real topology offers a retry that is refused before any mutation`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate(Rating.HARD)
        val effect = h.takeCommitEffect()
        h.ledger.prepare(effect.request) // durable PREPARED, then the process dies
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()
        h.fullRestart()
        val nextCardsBefore = h.fake.nextCardCount

        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.ANKIDROID_LOCAL, h.deck)))
        h.drain()

        val record = h.store.durableRecords().single()
        assertEquals(ReviewCommitStatus.PREPARED, record.status)
        assertEquals(ReviewCommitRecoveryAction.OfferRetry, ReviewCommitRecoveryPolicy().classifyStatus(record.status))
        assertEquals(SessionPhase.RatingCommitFailed, h.state.phase)
        assertTrue("a restored PREPARED (provably un-entered) offers the same-commit retry",
            RatingCommitRecoveryUi.from(h.state)!!.commitUiState is RatingCommitUiState.RetryAvailable)
        assertTrue(RatingCommitRecoveryUi.from(h.state)!!.canRetry)
        assertEquals(effect.request.commitId, h.commit!!.commitId)

        // The user retries; the real topology has no live turn handle, so the backend refuses
        // BEFORE the mutation boundary — the record becomes RETRY_ALLOWED with zero effects.
        val before = h.fake
        h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, h.commit!!.commitId))
        h.drain()

        val after = h.store.durableRecords().single()
        assertEquals("the retry was refused before dispatch, not lost in flight",
            ReviewCommitStatus.RETRY_ALLOWED, after.status)
        assertEquals("the refusal is the dead-session identity check, not a scheduler answer",
            "session_invalid", after.failure?.category)
        assertEquals("no mutation ever crossed the boundary", 0, before.physicalCommitCalls)
        assertEquals("no scheduler effect at all", 0, before.backendEffectCount)
        assertEquals("the refusal did not advance the scheduler", nextCardsBefore, before.nextCardCount)
        assertTrue("the user can still retry explicitly", RatingCommitRecoveryUi.from(h.state)!!.canRetry)
    }

    @Test fun `ver1 PREPARED restart with a surviving backend session completes the retry once with the same commit id`() = runTest {
        // PC backend model: the backend process (and its review session) survives the client
        // restart — only the client's ledger is rebuilt. The coordinator resumes the same
        // transaction (GATE 11B §37: PREPARED = resume, no new id), so a second mutation is
        // structurally impossible.
        val h = AnkiCommitHarness.reconcilable()
        h.loadToRating()
        h.rate(Rating.HARD)
        val effect = h.takeCommitEffect()
        h.ledger.prepare(effect.request) // durable PREPARED, then the client process dies
        h.restartLedger() // the backend (PC agent) keeps its session
        assertEquals(ReviewCommitStatus.PREPARED, h.ledger.get(effect.request.commitId)?.status)

        val outcome = h.executor.commit(effect.request) // explicit retry of the same ReviewCommitId
        assertTrue("the resumed transaction commits", outcome is ReviewCommitOutcome.Committed)
        val record = h.store.durableRecords().single()
        assertEquals("the retry reused the original ReviewCommitId", effect.request.commitId, record.commitId)
        assertEquals(ReviewCommitStatus.COMMITTED, record.status)
        assertEquals("exactly one scheduler effect for the rating", 1, h.fake.physicalCommitCalls)
        assertEquals(1, h.fake.backendEffectCount)
        assertEquals(Rating.HARD, record.selectedRating)
    }

    @Test fun `ver1 COMMITTED restart resumes without replay and a fresh scheduler query is legal`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        h.run(h.takeCommitEffect())
        val commitId = h.commit!!.commitId
        assertEquals(1, h.fake.physicalCommitCalls)
        h.send(StudyEvent.UserEndRequested("end")) // end cleanly; the next-card read is cancelled
        h.drain()
        h.fullRestart()
        val nextCardsBefore = h.fake.nextCardCount

        // A different session id: the committed record must NOT block a new session…
        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-2", AnkiBackendMode.ANKIDROID_LOCAL, h.deck, speakQuestion = false)))
        h.drain()

        assertEquals("a COMMITTED record never blocks progression",
            SessionPhase.WaitingForAnswer, h.state.phase)
        assertEquals("exactly one fresh scheduler query after the durable COMMIT",
            nextCardsBefore + 1, h.fake.nextCardCount)
        assertEquals("no replay: the mutation count is untouched", 1, h.fake.physicalCommitCalls)
        val record = h.store.durableRecords().single { it.commitId == commitId }
        assertEquals(ReviewCommitStatus.COMMITTED, record.status)
        assertEquals(1, record.attemptCount)
        assertEquals(ReviewCommitRecoveryAction.ResumeCommitted, ReviewCommitRecoveryPolicy().classifyStatus(record.status))
    }

    @Test fun `ver1 the recovery table maps each durable status to exactly one action`() {
        val policy = ReviewCommitRecoveryPolicy()
        assertEquals(ReviewCommitRecoveryAction.OfferRetry, policy.classifyStatus(ReviewCommitStatus.PREPARED))
        assertEquals(ReviewCommitRecoveryAction.Reconcile, policy.classifyStatus(ReviewCommitStatus.SUBMITTING))
        assertEquals(ReviewCommitRecoveryAction.OfferRetry, policy.classifyStatus(ReviewCommitStatus.RETRY_ALLOWED))
        assertEquals(ReviewCommitRecoveryAction.Reconcile, policy.classifyStatus(ReviewCommitStatus.AMBIGUOUS))
        assertEquals(ReviewCommitRecoveryAction.ResumeCommitted, policy.classifyStatus(ReviewCommitStatus.COMMITTED))
    }

    // ---------------------------------------------------------------- VER 2 — SUBMITTING restart

    @Test fun `ver2 SUBMITTING restart becomes AMBIGUOUS with Reconcile, no auto-replay and no next card`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.fake.mutationGate = CompletableDeferred() // suspended inside the mutation window
        val job = launch { h.executor.execute(effect) }
        runCurrent()
        job.cancelAndJoin() // the process dies with the call in flight
        h.store.overwrite(h.store.writes[2])
        h.send(StudyEvent.UserEndRequested("end")) // end the session; EndReview is never a mutation
        h.drain() // disk only has MUTATION_BOUNDARY_ENTERED
        h.fullRestart()
        val nextCardsBefore = h.fake.nextCardCount

        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.ANKIDROID_LOCAL, h.deck)))
        h.drain()

        val record = h.store.durableRecords().single()
        assertEquals("a durable SUBMITTING is unknown after restart, never a retry",
            ReviewCommitStatus.AMBIGUOUS, record.status)
        assertEquals(ReviewCommitRecoveryAction.Reconcile, ReviewCommitRecoveryPolicy().classifyStatus(record.status))
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
        assertTrue(RatingCommitRecoveryUi.from(h.state)!!.commitUiState is RatingCommitUiState.VerificationRequired)
        assertFalse("no retry is ever offered for an unknown outcome",
            RatingCommitRecoveryUi.from(h.state)!!.canRetry)
        assertEquals("no auto-replay", 1, h.fake.physicalCommitCalls)
        assertEquals("no next card while the mutation is unresolved", nextCardsBefore, h.fake.nextCardCount)
        assertTrue("no scheduler query: the interrupted turn is restored by identity",
            h.turn!!.scheduledCard.degradations.contains("restored_from_ledger"))
    }

    // ---------------------------------------------------------------- VER 3 — reconciliation outcomes (SUPPORTED backend)

    @Test fun `ver3 reconciliation proves committed — COMMITTED without a second mutation`() = runTest {
        val h = AnkiCommitHarness.reconcilable(
            listOf(CommitStep(BackendCommitResult.OutcomeUnknown(), appliedWhenAmbiguous = true)))
        h.loadToRating()
        h.rate()
        h.drain() // response lost: the mutation DID apply, the record is AMBIGUOUS
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit!!.status)
        h.restartLedger()
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()

        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.PC_AGENT, h.deck)))
        h.drain()
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)

        val commitId = h.commit!!.commitId
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        val reconcileEffect = h.pending.single { it is AnkiStudyEffect.ReconcileCommit }
        h.run(reconcileEffect) // deliver only the reconciliation outcome; the resume Begin stays pending

        val record = h.store.durableRecords().single()
        assertEquals(ReviewCommitStatus.COMMITTED, record.status)
        assertEquals(commitId, record.commitId)
        assertEquals("the resolution names the reconciliation",
            ReviewCommitResolution.RECONCILED_APPLIED, record.resolution)
        assertEquals("read-only proof: no second scheduler mutation", 1, h.fake.physicalCommitCalls)
        assertEquals(SessionPhase.Starting, h.state.phase)
        assertNull("the resolved restore is retired, not re-projected", h.commit)
        assertTrue("the resume is a new read-only scheduler session",
            h.pending.any { it is AnkiStudyEffect.Begin })
    }

    @Test fun `ver3 reconciliation proves not committed — RETRY_ALLOWED and the retry completes the same commit`() = runTest {
        // Live session (no restart): the backend still holds the turn, so the proven-not-applied
        // retry may complete. The restart variant of the same restore is covered by
        // Gate11bSourceOfTruthTest.retryable_ledger_restores_retryable_study_projection.
        val h = AnkiCommitHarness.reconcilable(
            listOf(CommitStep(BackendCommitResult.OutcomeUnknown(), appliedWhenAmbiguous = false)))
        h.loadToRating()
        h.rate()
        h.drain() // nothing applied, response lost: AMBIGUOUS with the turn still live
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit!!.status)
        val commitId = h.commit!!.commitId

        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        h.drain()
        assertEquals("negative proof is authoritative", ReviewCommitStatus.RETRY_ALLOWED,
            h.store.durableRecords().single().status)
        assertEquals(SessionPhase.RatingCommitFailed, h.state.phase)
        assertTrue("the proven-not-applied transaction offers the same-commit retry",
            RatingCommitRecoveryUi.from(h.state)!!.commitUiState is RatingCommitUiState.RetryAvailable)
        assertTrue(RatingCommitRecoveryUi.from(h.state)!!.canRetry)

        h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, h.commit!!.commitId))
        h.drain()
        val record = h.store.durableRecords().single()
        assertEquals("the retry reused the original ReviewCommitId", commitId, record.commitId)
        assertEquals(ReviewCommitStatus.COMMITTED, record.status)
        assertEquals("exactly one scheduler effect for the rating", 1, h.fake.backendEffectCount)
        assertEquals("the unknown attempt + the retry crossed the boundary twice", 2, h.fake.physicalCommitCalls)
        assertEquals("post-commit resume proceeds to the next scheduled card",
            SessionPhase.WaitingForAnswer, h.state.phase)
        assertNull("the committed transaction is gone; the new presentation owns a new one", h.commit)
    }

    @Test fun `ver3 reconciliation cannot decide — the record stays AMBIGUOUS and blocked`() = runTest {
        val h = AnkiCommitHarness.reconcilable(
            listOf(CommitStep(BackendCommitResult.OutcomeUnknown(), appliedWhenAmbiguous = false)))
        h.loadToRating()
        h.rate()
        h.drain()
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit!!.status)
        h.restartLedger()
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()

        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.PC_AGENT, h.deck)))
        h.drain()
        val commitId = h.commit!!.commitId
        h.fake.reconcileResults += ReconcileCommitResult.StillAmbiguous("no_conclusive_evidence")
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        h.drain()

        assertEquals("inconclusive evidence never invents a status",
            ReviewCommitStatus.AMBIGUOUS, h.store.durableRecords().single().status)
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
        assertFalse(RatingCommitRecoveryUi.from(h.state)!!.canRetry)
        assertTrue("the user may check again later", RatingCommitRecoveryUi.from(h.state)!!.canCheckAgain)
        assertEquals("the ambiguous attempt applied nothing", 0, h.fake.backendEffectCount)
    }

    // ---------------------------------------------------------------- VER 4 — AnkiDroid-class UNSUPPORTED

    @Test fun `ver4 AnkiDroid-class backend — reconciliation unsupported, no backend call, record stays blocked`() = runTest {
        val h = AnkiCommitHarness() // AnkiDroidLocal: frozen semantics clamp reconciliation off
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.fake.mutationGate = CompletableDeferred()
        val job = launch { h.executor.execute(effect) }
        runCurrent()
        job.cancelAndJoin()
        h.store.overwrite(h.store.writes[2])
        h.send(StudyEvent.UserEndRequested("end")) // end the session; EndReview is never a mutation
        h.drain()
        h.fullRestart()

        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.ANKIDROID_LOCAL, h.deck)))
        h.drain()
        val commitId = h.commit!!.commitId

        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        h.drain()

        assertEquals("without authoritative capability the record stays AMBIGUOUS",
            ReviewCommitStatus.AMBIGUOUS, h.store.durableRecords().single().status)
        assertEquals("the capability gate is before the provider: zero backend calls", 0, h.fake.reconcileCalls)
        assertEquals("never demoted to RETRY_ALLOWED without proof",
            SessionPhase.ReconciliationRequired, h.state.phase)
        assertFalse(RatingCommitRecoveryUi.from(h.state)!!.canRetry)
        assertEquals("diagnostics record the unsupported outcome", "unresolved",
            h.executor.reconciliationDiagnostics(commitId)?.resultToken)
    }

    // ---------------------------------------------------------------- VER 5 — PARTIAL support

    @Test fun `ver5 PARTIAL backend — a positive observation is demoted, the record stays AMBIGUOUS`() = runTest {
        val h = partialHarness(listOf(CommitStep(BackendCommitResult.OutcomeUnknown(), appliedWhenAmbiguous = true)))
        h.loadToRating()
        h.rate()
        h.drain()
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit!!.status)
        h.restartLedger()
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()
        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.PC_AGENT, h.deck)))
        h.drain()
        val commitId = h.commit!!.commitId

        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        h.drain()

        assertEquals("PARTIAL cannot confirm a commit — the applied observation is demoted",
            ReviewCommitStatus.AMBIGUOUS, h.store.durableRecords().single().status)
        assertEquals(1, h.fake.reconcileCalls)
        assertEquals("the demotion is diagnostics-visible, not a silent guess",
            "unresolved", h.executor.reconciliationDiagnostics(commitId)?.resultToken)
        assertFalse(RatingCommitRecoveryUi.from(h.state)!!.canRetry)
    }

    @Test fun `ver5 PARTIAL backend — negative evidence is trusted and becomes RETRY_ALLOWED`() = runTest {
        val h = partialHarness(listOf(CommitStep(BackendCommitResult.OutcomeUnknown(), appliedWhenAmbiguous = false)))
        h.loadToRating()
        h.rate()
        h.drain()
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit!!.status)
        h.restartLedger()
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()
        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.PC_AGENT, h.deck)))
        h.drain()
        val commitId = h.commit!!.commitId

        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        h.drain()

        assertEquals("proving non-application is within PARTIAL's promise",
            ReviewCommitStatus.RETRY_ALLOWED, h.store.durableRecords().single().status)
        assertTrue(RatingCommitRecoveryUi.from(h.state)!!.canRetry)
        assertEquals("the ambiguous attempt applied nothing", 0, h.fake.backendEffectCount)
    }

    // ---------------------------------------------------------------- VER 6 — external activity

    @Test fun `ver6 external scheduler change — never attributed to the commit, stays AMBIGUOUS`() = runTest {
        val h = AnkiCommitHarness.reconcilable(
            listOf(CommitStep(BackendCommitResult.OutcomeUnknown(), appliedWhenAmbiguous = false)))
        h.loadToRating()
        h.rate()
        h.drain()
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit!!.status)
        h.restartLedger()
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()
        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.PC_AGENT, h.deck)))
        h.drain()
        val commitId = h.commit!!.commitId

        // A scheduler observation (reps/due/next-card moved) that the backend cannot correlate to
        // this ReviewCommitId — e.g. the user reviewed in Anki itself.
        h.fake.reconcileResults += ReconcileCommitResult.StillAmbiguous("external_change_indistinguishable")
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        h.drain()

        assertEquals("an unattributable change is not evidence in either direction",
            ReviewCommitStatus.AMBIGUOUS, h.store.durableRecords().single().status)
        assertEquals("and it is never laundered into RETRY_ALLOWED",
            SessionPhase.ReconciliationRequired, h.state.phase)
        assertFalse(RatingCommitRecoveryUi.from(h.state)!!.canRetry)
        assertEquals("the external change is not this transaction's effect", 0, h.fake.backendEffectCount)
    }

    // ---------------------------------------------------------------- VER 7 — collection change

    @Test fun `ver7 the collection changed — reconciliation is blocked, the record is never redirected`() = runTest {
        val h = AnkiCommitHarness.reconcilable(
            listOf(CommitStep(BackendCommitResult.OutcomeUnknown(), appliedWhenAmbiguous = false)))
        h.loadToRating()
        h.rate()
        h.drain()
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit!!.status)
        val commitId = h.commit!!.commitId
        h.restartLedger()
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()
        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.PC_AGENT, h.deck)))
        h.drain()

        // The backend's collection is no longer the one the transaction belongs to.
        h.fake.currentCollectionKey = "other-collection"
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        h.drain()

        val record = h.store.durableRecords().single()
        assertEquals("a collection mismatch is Unresolved, never a redirect",
            ReviewCommitStatus.AMBIGUOUS, record.status)
        assertEquals("the transaction keeps its original collection identity",
            "collection", record.collectionKey)
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
    }

    // ---------------------------------------------------------------- VER 8 — missing card

    @Test fun `ver8 the card no longer exists — Unresolved, never RETRY_ALLOWED`() = runTest {
        val h = AnkiCommitHarness.reconcilable(
            listOf(CommitStep(BackendCommitResult.OutcomeUnknown(), appliedWhenAmbiguous = false)))
        h.loadToRating()
        h.rate()
        h.drain()
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit!!.status)
        val commitId = h.commit!!.commitId
        h.restartLedger()
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()
        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.PC_AGENT, h.deck)))
        h.drain()

        h.fake.deleteCard(h.turn!!.cardRef) // the card was deleted in Anki itself
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        h.drain()

        assertEquals("a missing card proves nothing about the mutation",
            ReviewCommitStatus.AMBIGUOUS, h.store.durableRecords().single().status)
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
        assertFalse(RatingCommitRecoveryUi.from(h.state)!!.canRetry)
    }

    // ---------------------------------------------------------------- VER 9 — backend unavailable

    @Test fun `ver9 the backend is unavailable — Unresolved, never RETRY_ALLOWED`() = runTest {
        val h = AnkiCommitHarness.reconcilable(
            listOf(CommitStep(BackendCommitResult.OutcomeUnknown(), appliedWhenAmbiguous = false)))
        h.loadToRating()
        h.rate()
        h.drain()
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit!!.status)
        val commitId = h.commit!!.commitId
        h.restartLedger()
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()
        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.PC_AGENT, h.deck)))
        h.drain()

        h.fake.setAvailability(AnkiAvailability.TemporarilyUnavailable())
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        h.drain()

        assertEquals("an unreachable backend proves nothing in either direction",
            ReviewCommitStatus.AMBIGUOUS, h.store.durableRecords().single().status)
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
        assertFalse("unavailability is not a permission to retry", RatingCommitRecoveryUi.from(h.state)!!.canRetry)
        assertTrue("recovery stays available once the backend is back",
            RatingCommitRecoveryUi.from(h.state)!!.canCheckAgain)
    }

    @Test fun `ver9 a hung reconciliation query times out and stays AMBIGUOUS`() = runTest {
        val h = AnkiCommitHarness.reconcilable(
            listOf(CommitStep(BackendCommitResult.OutcomeUnknown(), appliedWhenAmbiguous = false)))
        h.loadToRating()
        h.rate()
        h.drain()
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit!!.status)
        val commitId = h.commit!!.commitId
        h.restartLedger()
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()
        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.PC_AGENT, h.deck)))
        h.drain()

        h.fake.reconcileGate = CompletableDeferred() // the provider never answers
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        h.drain()

        assertEquals("a timeout is 'still unknown', never 'not applied'",
            ReviewCommitStatus.AMBIGUOUS, h.store.durableRecords().single().status)
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
    }

    // ---------------------------------------------------------------- VER 10 — multiple unresolved per session

    @Test fun `ver10 two unresolved commits for one session — integrity failure, fail closed everywhere`() = runTest {
        val h = AnkiCommitHarness()
        val backend = AnkiCommitHarness.BACKEND
        fun ambiguous(turn: String, card: String) = ReviewCommitRecord(
            commitId = ReviewCommitId(backend, "study-1", ReviewTurnId(turn)),
            card = AnkiCardRef(backend, cardId = card, collectionKey = "collection"),
            rating = Rating.GOOD,
            status = ReviewCommitStatus.AMBIGUOUS,
            attemptCount = 1,
            phase = ReviewCommitPhase.FINAL_STATUS_PERSISTED,
            failure = ReviewCommitFailure("test"),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
            ratedAtEpochMs = 1L,
            submittedAtEpochMs = 1L,
            resolvedAtEpochMs = 1L,
            deckRef = AnkiCommitHarness.deckFor(backend)
        )

        // The codec refuses the structural anomaly…
        val snapshot = ReviewCommitLedgerCodec.encode(listOf(ambiguous("1:A:1", "A"), ambiguous("1:B:1", "B")))
        val decoded = ReviewCommitLedgerCodec.decode(snapshot)
        assertTrue("the snapshot is a structural integrity anomaly",
            decoded is ReviewCommitLedgerCodec.Decoded.Unreadable)
        assertEquals(ReviewCommitLedgerCodec.MULTIPLE_UNRESOLVED_PER_SESSION,
            (decoded as ReviewCommitLedgerCodec.Decoded.Unreadable).reason)

        // …and the restarted ledger carries the anomaly into begin() and recover().
        h.store.overwrite(snapshot)
        h.restartLedger()
        val health = h.ledger.health()
        assertTrue(health is ReviewCommitLedger.Health.Unavailable)
        assertEquals(ReviewCommitLedgerCodec.MULTIPLE_UNRESOLVED_PER_SESSION,
            (health as ReviewCommitLedger.Health.Unavailable).reason)

        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.ANKIDROID_LOCAL, h.deck)))
        h.drain()
        assertTrue("begin fails closed", h.state.phase is SessionPhase.Error)
        assertEquals(SessionProblem.ANKI_COMMIT_INTEGRITY,
            (h.state.phase as SessionPhase.Error).problem)
        assertEquals(0, h.fake.nextCardCount)
        assertEquals(0, h.fake.deliveryCount)

        val recovered = h.executor.recover(ambiguous("1:A:1", "A").commitId)
        assertTrue("recover names the integrity failure, never guesses a record",
            recovered is ReviewCommitRecoveryResult.IntegrityFailure)
        assertEquals(ReviewCommitLedgerCodec.MULTIPLE_UNRESOLVED_PER_SESSION,
            (recovered as ReviewCommitRecoveryResult.IntegrityFailure).reason)
    }

    // ---------------------------------------------------------------- VER 11 — scoping & source of truth

    @Test fun `ver11 blocking is scoped — another backend or another collection is never blocked`() = runTest {
        val h = AnkiCommitHarness()
        val backend = AnkiCommitHarness.BACKEND
        val ambiguous = ReviewCommitRecord(
            commitId = ReviewCommitId(backend, "study-1", ReviewTurnId("1:A:1")),
            card = AnkiCardRef(backend, cardId = "A", collectionKey = "collection"),
            rating = Rating.GOOD,
            status = ReviewCommitStatus.AMBIGUOUS,
            attemptCount = 1,
            phase = ReviewCommitPhase.FINAL_STATUS_PERSISTED,
            failure = ReviewCommitFailure("test"),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
            ratedAtEpochMs = 1L,
            submittedAtEpochMs = 1L,
            resolvedAtEpochMs = 1L,
            deckRef = AnkiCommitHarness.deckFor(backend)
        )
        h.store.overwrite(ReviewCommitLedgerCodec.encode(listOf(ambiguous)))
        h.restartLedger()

        // Same backend, same collection, another session: blocked.
        assertNotNull(h.ledger.recoveryBlocker(backend, "collection", "study-2"))
        // Same backend, a different known collection: never blocked.
        assertNull("collection scoping: a different collection is unaffected",
            h.ledger.recoveryBlocker(backend, "other-collection", "study-2"))
        // A different backend entirely: never blocked (the fake backend stays usable).
        assertNull("backend scoping: an AnkiDroid ambiguity must not disable another backend",
            h.ledger.recoveryBlocker(AnkiBackendId.Fake("other"), "collection", "study-2"))
        // PREPARED is provably un-entered and never blocks another session.
        val prepared = ambiguous.copy(status = ReviewCommitStatus.PREPARED, failure = null,
            phase = ReviewCommitPhase.INTENT_PERSISTED, submittedAtEpochMs = null, resolvedAtEpochMs = null)
        h.store.overwrite(ReviewCommitLedgerCodec.encode(listOf(prepared)))
        h.restartLedger()
        assertNull("a PREPARED record from another session never blocks",
            h.ledger.recoveryBlocker(backend, "collection", "study-2"))
    }

    @Test fun `ver11 durable truth wins — a new session in an unresolved collection is blocked before nextCard`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.fake.mutationGate = CompletableDeferred()
        val job = launch { h.executor.execute(effect) }
        runCurrent()
        job.cancelAndJoin()
        h.store.overwrite(h.store.writes[2])
        h.send(StudyEvent.UserEndRequested("end")) // end the session; EndReview is never a mutation
        h.drain()
        h.fullRestart()
        val nextCardsBefore = h.fake.nextCardCount
        val deliveriesBefore = h.fake.deliveryCount

        // A brand-new session, new session id, same collection: the durable record wins.
        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-2", AnkiBackendMode.ANKIDROID_LOCAL, h.deck)))
        h.drain()

        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
        assertTrue(RatingCommitRecoveryUi.from(h.state)!!.commitUiState is RatingCommitUiState.VerificationRequired)
        assertEquals("the blocker is the other session's record", "study-1", h.commit!!.commitId.studySessionId)
        assertEquals("no nextCard query while the mutation is unresolved", nextCardsBefore, h.fake.nextCardCount)
        assertEquals("no new commit delivery after restart", deliveriesBefore, h.fake.deliveryCount)
        assertFalse(RatingCommitRecoveryUi.from(h.state)!!.canRetry)
    }

    // ---------------------------------------------------------------- VER 12 — read-only proof

    @Test fun `ver12 reconciliation is read-only — zero mutations, zero scheduler queries, identity unchanged`() = runTest {
        val h = AnkiCommitHarness.reconcilable(
            listOf(CommitStep(BackendCommitResult.OutcomeUnknown(), appliedWhenAmbiguous = false)))
        h.loadToRating()
        h.rate()
        h.drain()
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit!!.status)
        val commitId = h.commit!!.commitId
        val ratingBefore = h.commit!!.selectedRating
        h.restartLedger()
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()
        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.PC_AGENT, h.deck)))
        h.drain()

        val nextCardsBefore = h.fake.nextCardCount
        val deliveriesBefore = h.fake.deliveryCount
        val commitsBefore = h.fake.physicalCommitCalls
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        h.drain()

        assertEquals("no scheduler mutation", commitsBefore, h.fake.physicalCommitCalls)
        assertEquals("no commit delivery", deliveriesBefore, h.fake.deliveryCount)
        assertEquals("no scheduler query (no nextCard, no re-begin)", nextCardsBefore, h.fake.nextCardCount)
        val record = h.store.durableRecords().single()
        assertEquals("the transaction identity is untouched", commitId, record.commitId)
        assertEquals("the recorded rating is untouched", ratingBefore, record.selectedRating)
        assertEquals("the card identity is untouched", h.turn!!.cardRef, record.card)
    }

    // ---------------------------------------------------------------- VER 13 — next-card barrier

    @Test fun `ver13 the next-card barrier holds for every non-COMMITTED restored state`() = runTest {
        // AMBIGUOUS restore: no next card.
        val h1 = AnkiCommitHarness()
        h1.loadToRating(); h1.rate()
        val e1 = h1.takeCommitEffect()
        h1.fake.mutationGate = CompletableDeferred()
        val j1 = launch { h1.executor.execute(e1) }
        runCurrent()
        j1.cancelAndJoin()
        h1.store.overwrite(h1.store.writes[2])
        h1.send(StudyEvent.UserEndRequested("end"))
        h1.drain()
        h1.restartLedger()
        h1.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.ANKIDROID_LOCAL, h1.deck)))
        h1.drain()
        val next1 = h1.fake.nextCardCount
        assertEquals(SessionPhase.ReconciliationRequired, h1.state.phase)
        assertTrue(h1.pending.none { it is AnkiStudyEffect.Next })
        assertEquals(next1, h1.fake.nextCardCount)

        // RETRY_ALLOWED restore: no next card.
        val h2 = AnkiCommitHarness()
        h2.loadToRating(); h2.rate()
        h2.fake.prepareRefusal = CommitPreparation.Refused(AnkiError.BackendUnavailable(), retryable = true)
        h2.drain()
        h2.send(StudyEvent.UserEndRequested("end"))
        h2.drain()
        h2.restartLedger()
        h2.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.ANKIDROID_LOCAL, h2.deck)))
        h2.drain()
        val next2 = h2.fake.nextCardCount
        assertEquals(SessionPhase.RatingCommitFailed, h2.state.phase)
        assertTrue(h2.pending.none { it is AnkiStudyEffect.Next })
        assertEquals(next2, h2.fake.nextCardCount)
    }

    // ---------------------------------------------------------------- VER 14 — repeated reconciliation

    @Test fun `ver14 repeated reconciliation is idempotent — no flip, no mutation, then resolves once`() = runTest {
        val h = AnkiCommitHarness.reconcilable(
            listOf(CommitStep(BackendCommitResult.OutcomeUnknown(), appliedWhenAmbiguous = false)))
        h.loadToRating()
        h.rate()
        h.drain()
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit!!.status)
        h.restartLedger()
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()
        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.PC_AGENT, h.deck)))
        h.drain()
        val commitId = h.commit!!.commitId

        // Two inconclusive passes…
        h.fake.reconcileResults += ReconcileCommitResult.StillAmbiguous("inconclusive")
        h.fake.reconcileResults += ReconcileCommitResult.StillAmbiguous("inconclusive")
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        h.drain()
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.store.durableRecords().single().status)
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        h.drain()
        assertEquals("repeating the probe never flips the status",
            ReviewCommitStatus.AMBIGUOUS, h.store.durableRecords().single().status)
        assertEquals(2, h.fake.reconcileCalls)
        assertEquals("the probes added no scheduler effect", 0, h.fake.backendEffectCount)

        // …then a conclusive pass resolves it exactly once.
        h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commitId))
        h.drain()
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, h.store.durableRecords().single().status)
        assertEquals(3, h.fake.reconcileCalls)
    }

    // ---------------------------------------------------------------- VER 15 — exit / reopen

    @Test fun `ver15 ending the session during AMBIGUOUS preserves the record and it still blocks after restart`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.fake.mutationGate = CompletableDeferred()
        val job = launch { h.executor.execute(effect) }
        runCurrent()
        job.cancelAndJoin()
        h.store.overwrite(h.store.writes[2])
        h.send(StudyEvent.UserEndRequested("end")) // end the session; EndReview is never a mutation
        h.drain()
        h.restartLedger()

        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-1", AnkiBackendMode.ANKIDROID_LOCAL, h.deck)))
        h.drain()
        assertEquals(SessionPhase.ReconciliationRequired, h.state.phase)
        val beforeExit = h.store.durableRecords().single()

        // The user gives up on the session. EndReview releases a handle; it is never a mutation
        // and never rewrites the transaction.
        h.send(StudyEvent.UserEndRequested("quit"))
        h.drain()
        val afterExit = h.store.durableRecords().single()
        assertEquals("session exit preserves the ledger record", beforeExit.commitId, afterExit.commitId)
        assertEquals(ReviewCommitStatus.AMBIGUOUS, afterExit.status)
        assertFalse("abandonment never becomes 'not committed'",
            afterExit.status == ReviewCommitStatus.RETRY_ALLOWED)

        val nextCardsBefore = h.fake.nextCardCount
        h.fullRestart()
        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-2", AnkiBackendMode.ANKIDROID_LOCAL, h.deck)))
        h.drain()
        assertEquals("the reopened app is still blocked by the unresolved mutation",
            SessionPhase.ReconciliationRequired, h.state.phase)
        assertEquals(1, h.fake.physicalCommitCalls)
        assertEquals("no nextCard query after reopen", nextCardsBefore, h.fake.nextCardCount)
    }

    // ---------------------------------------------------------------- VER 16 — restart endurance

    @Test fun `ver16 restart endurance — three restarts, zero duplicate mutation, record stable`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate()
        val effect = h.takeCommitEffect()
        h.fake.mutationGate = CompletableDeferred()
        val job = launch { h.executor.execute(effect) }
        runCurrent()
        job.cancelAndJoin()
        h.store.overwrite(h.store.writes[2])
        h.send(StudyEvent.UserEndRequested("end")) // end the session; EndReview is never a mutation
        h.drain()
        val original = h.store.durableRecords().single().commitId
        val nextCardsBefore = h.fake.nextCardCount

        for (attempt in 1..3) {
            h.restartLedger()
            h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-$attempt", AnkiBackendMode.ANKIDROID_LOCAL, h.deck)))
            h.drain()
            assertEquals("restart $attempt: blocked before any scheduler access",
                SessionPhase.ReconciliationRequired, h.state.phase)
            val records = h.store.durableRecords()
            assertEquals("restart $attempt: the durable truth is the single stable record", 1, records.size)
            assertEquals(original, records.single().commitId)
            assertEquals(ReviewCommitStatus.AMBIGUOUS, records.single().status)
            assertEquals("restart $attempt: no duplicate mutation", 1, h.fake.physicalCommitCalls)
            assertEquals("restart $attempt: no nextCard query", nextCardsBefore, h.fake.nextCardCount)
            h.send(StudyEvent.UserEndRequested("end-$attempt")) // the machine must be idle to Start again
            h.drain()
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A harness whose backend's live capability is exactly PARTIAL (negative proofs only). */
    private fun partialHarness(commitSteps: List<CommitStep> = emptyList()) = AnkiCommitHarness(
        backendId = AnkiCommitHarness.RECONCILABLE_BACKEND,
        mode = AnkiBackendMode.PC_AGENT,
        commitSteps = commitSteps,
        wrap = { fake ->
            object : AnkiBackend by fake {
                override fun reconciliationSupport(): ReconciliationSupport = ReconciliationSupport.PARTIAL
            }
        }
    )
}
