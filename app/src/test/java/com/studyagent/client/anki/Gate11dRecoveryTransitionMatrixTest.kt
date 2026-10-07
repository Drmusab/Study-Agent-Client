package com.studyagent.client.anki

import com.studyagent.client.anki.fake.InMemoryReviewCommitStore
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.BackendCommitResult
import com.studyagent.client.core.anki.CommitResponseEvidence
import com.studyagent.client.core.anki.CommitResponseKind
import com.studyagent.client.core.anki.CommitRatingRequest
import com.studyagent.client.core.anki.ReconcileCommitResult
import com.studyagent.client.core.anki.ReviewCommitFailure
import com.studyagent.client.core.anki.ReviewCommitId
import com.studyagent.client.core.anki.ReviewCommitLedger
import com.studyagent.client.core.anki.ReviewCommitLedgerCodec
import com.studyagent.client.core.anki.ReviewCommitPhase
import com.studyagent.client.core.anki.ReviewCommitRecoveryAction
import com.studyagent.client.core.anki.ReviewCommitRecoveryEvent
import com.studyagent.client.core.anki.ReviewCommitRecoveryPolicy
import com.studyagent.client.core.anki.ReviewCommitRecord
import com.studyagent.client.core.anki.ReviewCommitResolution
import com.studyagent.client.core.anki.ReviewCommitStatus
import com.studyagent.client.core.anki.ReviewCommitTransition
import com.studyagent.client.core.anki.ReviewCommitTransitionResult
import com.studyagent.client.core.anki.ReviewCommitTransitions
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.anki.nextCardAllowed
import com.studyagent.client.core.anki.recoveryEventOrNull
import com.studyagent.client.core.anki.recoveryTransition
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.RatingCommitUiState
import com.studyagent.client.core.study.projectForUi
import com.studyagent.client.core.study.recoveryUiProjection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 11D §36-§41 — the **closed recovery transition matrix**, pinned twice:
 *
 * 1. the one pure function [recoveryTransition] is exhausted over all 5 statuses × 7 events, so
 *    every unspecified pair is provably rejected;
 * 2. the durable engine ([ReviewCommitTransitions]) and the ledger (storage, restart, claims)
 *    agree with that table for every mandatory illegal (§40) and legal (§41) case.
 *
 * Test names are the canonical ones from GATE 11D §40/§41.
 */
class Gate11dRecoveryTransitionMatrixTest {

    private val backendId = AnkiBackendId.Fake()
    private val commitId = ReviewCommitId(backendId, "study-1", ReviewTurnId("turn-1"))

    private val allEvents = ReviewCommitRecoveryEvent.entries

    private fun record(
        status: ReviewCommitStatus,
        phase: ReviewCommitPhase? = when (status) {
            ReviewCommitStatus.PREPARED -> ReviewCommitPhase.INTENT_PERSISTED
            ReviewCommitStatus.SUBMITTING -> ReviewCommitPhase.MUTATION_BOUNDARY_ENTERED
            else -> ReviewCommitPhase.FINAL_STATUS_PERSISTED
        },
        attemptCount: Int = if (status == ReviewCommitStatus.PREPARED) 0 else 1
    ) = ReviewCommitRecord(
        commitId = commitId,
        card = AnkiCardRef(backendId, cardId = "c1"),
        rating = Rating.GOOD,
        status = status,
        attemptCount = attemptCount,
        phase = phase,
        failure = if (status == ReviewCommitStatus.RETRY_ALLOWED || status == ReviewCommitStatus.AMBIGUOUS) {
            ReviewCommitFailure("fixture_reason")
        } else null,
        response = null,
        createdAtEpochMs = 1_000,
        updatedAtEpochMs = 1_000,
        ratedAtEpochMs = 900,
        submittedAtEpochMs = if (status == ReviewCommitStatus.SUBMITTING) 1_500 else null,
        version = 0
    )

    private val now = 5_000L

    private fun <T> present(value: T?): T = value ?: error("unexpected null")

    private fun engine(record: ReviewCommitRecord, command: ReviewCommitTransition): ReviewCommitRecord? =
        when (val result = ReviewCommitTransitions.transition(record, command, now)) {
            is ReviewCommitTransitionResult.Applied -> result.record
            is ReviewCommitTransitionResult.Rejected -> null
        }

    private fun assertRejected(record: ReviewCommitRecord, command: ReviewCommitTransition) {
        val result = ReviewCommitTransitions.transition(record, command, now)
        assertTrue(
            "expected rejection of ${command::class.simpleName} from ${record.status}",
            result is ReviewCommitTransitionResult.Rejected
        )
    }

    // --------------------------------------------- GATE 11D §36/§37 — the closed table, exhausted

    @Test fun `the recovery transition table is closed over all status event pairs`() {
        // Re-stated from GATE 11D §37 so the test and the implementation cannot share a blind spot.
        val expected: Map<ReviewCommitStatus, Map<ReviewCommitRecoveryEvent, ReviewCommitStatus?>> = mapOf(
            ReviewCommitStatus.PREPARED to mapOf(
                ReviewCommitRecoveryEvent.RECOVERY_DETECTED to ReviewCommitStatus.PREPARED,
                ReviewCommitRecoveryEvent.RETRY_REQUESTED to ReviewCommitStatus.PREPARED,
                ReviewCommitRecoveryEvent.RECONCILIATION_STARTED to null,
                ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_COMMITTED to null,
                ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_NOT_COMMITTED to null,
                ReviewCommitRecoveryEvent.RECONCILIATION_UNRESOLVED to null,
                ReviewCommitRecoveryEvent.INTEGRITY_VIOLATION_DETECTED to ReviewCommitStatus.PREPARED
            ),
            ReviewCommitStatus.SUBMITTING to mapOf(
                ReviewCommitRecoveryEvent.RECOVERY_DETECTED to ReviewCommitStatus.SUBMITTING,
                ReviewCommitRecoveryEvent.RETRY_REQUESTED to null,
                ReviewCommitRecoveryEvent.RECONCILIATION_STARTED to ReviewCommitStatus.SUBMITTING,
                ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_COMMITTED to ReviewCommitStatus.COMMITTED,
                ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_NOT_COMMITTED to ReviewCommitStatus.RETRY_ALLOWED,
                ReviewCommitRecoveryEvent.RECONCILIATION_UNRESOLVED to ReviewCommitStatus.AMBIGUOUS,
                ReviewCommitRecoveryEvent.INTEGRITY_VIOLATION_DETECTED to ReviewCommitStatus.SUBMITTING
            ),
            ReviewCommitStatus.RETRY_ALLOWED to mapOf(
                ReviewCommitRecoveryEvent.RECOVERY_DETECTED to ReviewCommitStatus.RETRY_ALLOWED,
                ReviewCommitRecoveryEvent.RETRY_REQUESTED to ReviewCommitStatus.PREPARED,
                ReviewCommitRecoveryEvent.RECONCILIATION_STARTED to null,
                ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_COMMITTED to null,
                ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_NOT_COMMITTED to null,
                ReviewCommitRecoveryEvent.RECONCILIATION_UNRESOLVED to null,
                ReviewCommitRecoveryEvent.INTEGRITY_VIOLATION_DETECTED to ReviewCommitStatus.RETRY_ALLOWED
            ),
            ReviewCommitStatus.AMBIGUOUS to mapOf(
                ReviewCommitRecoveryEvent.RECOVERY_DETECTED to ReviewCommitStatus.AMBIGUOUS,
                ReviewCommitRecoveryEvent.RETRY_REQUESTED to null,
                ReviewCommitRecoveryEvent.RECONCILIATION_STARTED to ReviewCommitStatus.AMBIGUOUS,
                ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_COMMITTED to ReviewCommitStatus.COMMITTED,
                ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_NOT_COMMITTED to ReviewCommitStatus.RETRY_ALLOWED,
                ReviewCommitRecoveryEvent.RECONCILIATION_UNRESOLVED to ReviewCommitStatus.AMBIGUOUS,
                ReviewCommitRecoveryEvent.INTEGRITY_VIOLATION_DETECTED to ReviewCommitStatus.AMBIGUOUS
            ),
            ReviewCommitStatus.COMMITTED to mapOf(
                ReviewCommitRecoveryEvent.RECOVERY_DETECTED to ReviewCommitStatus.COMMITTED,
                ReviewCommitRecoveryEvent.RETRY_REQUESTED to null,
                ReviewCommitRecoveryEvent.RECONCILIATION_STARTED to ReviewCommitStatus.COMMITTED,
                ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_COMMITTED to ReviewCommitStatus.COMMITTED,
                ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_NOT_COMMITTED to null,
                ReviewCommitRecoveryEvent.RECONCILIATION_UNRESOLVED to ReviewCommitStatus.COMMITTED,
                ReviewCommitRecoveryEvent.INTEGRITY_VIOLATION_DETECTED to ReviewCommitStatus.COMMITTED
            )
        )
        assertEquals(5 * 7, expected.values.sumOf { it.size })
        for (status in ReviewCommitStatus.entries) {
            for (event in allEvents) {
                assertEquals(
                    "recoveryTransition($status, $event)",
                    expected.getValue(status).getValue(event),
                    recoveryTransition(status, event)
                )
            }
        }
    }

    @Test fun `every recovery command maps to exactly one canonical recovery event`() {
        assertEquals(
            ReviewCommitRecoveryEvent.RETRY_REQUESTED,
            ReviewCommitTransition.BeginRetry.recoveryEventOrNull())
        assertEquals(
            ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_COMMITTED,
            ReviewCommitTransition.ReconciliationConfirmedCommitted(null).recoveryEventOrNull())
        assertEquals(
            ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_NOT_COMMITTED,
            ReviewCommitTransition.ReconciliationConfirmedNotCommitted.recoveryEventOrNull())
        assertEquals(
            ReviewCommitRecoveryEvent.RECONCILIATION_UNRESOLVED,
            ReviewCommitTransition.ReconciliationInconclusive("x").recoveryEventOrNull())
        // Pipeline and metadata commands are not recovery events.
        assertNull(ReviewCommitTransition.EnterMutationBoundary.recoveryEventOrNull())
        assertNull(ReviewCommitTransition.BeginAttempt(null).recoveryEventOrNull())
        assertNull(ReviewCommitTransition.BackendCommitted(
            BackendCommitResult.ConfirmedCommitted(null)).recoveryEventOrNull())
        assertNull(ReviewCommitTransition.NoteAbandoned(1L).recoveryEventOrNull())
        assertNull(ReviewCommitTransition.Acknowledge.recoveryEventOrNull())
    }

    // --------------------------------------------- GATE 11D §40 — mandatory illegal transitions

    @Test fun `prepared_cannot_become_committed_without_backend_or_reconciliation`() {
        assertNull(recoveryTransition(ReviewCommitStatus.PREPARED,
            ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_COMMITTED))
        val prepared = record(ReviewCommitStatus.PREPARED)
        assertRejected(prepared, ReviewCommitTransition.ReconciliationConfirmedCommitted(null))
        assertRejected(prepared, ReviewCommitTransition.ReconciliationConfirmedNotCommitted)
        assertRejected(prepared, ReviewCommitTransition.BackendCommitted(
            BackendCommitResult.ConfirmedCommitted(null)))
        assertFalse(ReviewCommitTransitions.allowed(
            prepared, prepared.copy(
                status = ReviewCommitStatus.COMMITTED, attemptCount = 1, committedRating = Rating.GOOD)))
    }

    @Test fun `prepared_cannot_become_ambiguous_on_restart_alone`() = runTest {
        // Restart alone is not a recovery event of the closed table.
        assertTrue(allEvents.none {
            recoveryTransition(ReviewCommitStatus.PREPARED, it) == ReviewCommitStatus.AMBIGUOUS
        })
        // A real restart of a durable PREPARED record (even one carrying an attempt claim)
        // reloads PREPARED — the claim is released, the status is not reinterpreted.
        val store = InMemoryReviewCommitStore(
            ReviewCommitLedgerCodec.encode(listOf(
                record(ReviewCommitStatus.PREPARED).copy(claimedAtEpochMs = 1_234))))
        val reloaded = ReviewCommitLedger(store, { 9_000 })
        assertEquals(ReviewCommitStatus.PREPARED, reloaded.get(commitId)?.status)
    }

    @Test fun `submitting_cannot_retry_directly`() = runTest {
        assertNull(recoveryTransition(ReviewCommitStatus.SUBMITTING,
            ReviewCommitRecoveryEvent.RETRY_REQUESTED))
        val submitting = record(ReviewCommitStatus.SUBMITTING)
        assertRejected(submitting, ReviewCommitTransition.BeginRetry)
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, { 9_000 })
        liveSubmitting(ledger)
        val result = ledger.claim(commitId, null, allowRetry = true)
        assertTrue("a SUBMITTING record is in flight, never claimable",
            result is ReviewCommitLedger.ClaimResult.InFlight)
    }

    @Test fun `submitting_cannot_return_to_prepared`() {
        assertTrue(allEvents.none {
            recoveryTransition(ReviewCommitStatus.SUBMITTING, it) == ReviewCommitStatus.PREPARED
        })
        val submitting = record(ReviewCommitStatus.SUBMITTING)
        assertRejected(submitting, ReviewCommitTransition.BeginRetry)
        assertRejected(submitting, ReviewCommitTransition.BeginAttempt(null))
        assertFalse(ReviewCommitTransitions.allowed(
            submitting, submitting.copy(status = ReviewCommitStatus.PREPARED)))
    }

    @Test fun `retry_allowed_cannot_become_submitting_directly`() {
        assertTrue(allEvents.none {
            recoveryTransition(ReviewCommitStatus.RETRY_ALLOWED, it) == ReviewCommitStatus.SUBMITTING
        })
        val retryable = record(ReviewCommitStatus.RETRY_ALLOWED)
        assertRejected(retryable, ReviewCommitTransition.EnterMutationBoundary)
        assertRejected(retryable, ReviewCommitTransition.BeginAttempt(null))
        assertFalse(ReviewCommitTransitions.allowed(
            retryable, retryable.copy(
                status = ReviewCommitStatus.SUBMITTING, failure = null, submittedAtEpochMs = now)))
    }

    @Test fun `ambiguous_cannot_retry_directly`() = runTest {
        assertNull(recoveryTransition(ReviewCommitStatus.AMBIGUOUS,
            ReviewCommitRecoveryEvent.RETRY_REQUESTED))
        val ambiguous = record(ReviewCommitStatus.AMBIGUOUS)
        assertRejected(ambiguous, ReviewCommitTransition.BeginRetry)
        ReviewCommitLedger(InMemoryReviewCommitStore(
            ReviewCommitLedgerCodec.encode(listOf(ambiguous))), { 9_000 }).let { ledger ->
            val result = ledger.claim(commitId, null, allowRetry = true)
            assertTrue("user intent is not evidence: AMBIGUOUS is not claimable",
                result is ReviewCommitLedger.ClaimResult.NotClaimable)
        }
    }

    @Test fun `ambiguous_cannot_become_prepared`() {
        assertTrue(allEvents.none {
            recoveryTransition(ReviewCommitStatus.AMBIGUOUS, it) == ReviewCommitStatus.PREPARED
        })
        val ambiguous = record(ReviewCommitStatus.AMBIGUOUS)
        assertRejected(ambiguous, ReviewCommitTransition.BeginRetry)
        assertRejected(ambiguous, ReviewCommitTransition.BeginAttempt(null))
        assertFalse(ReviewCommitTransitions.allowed(
            ambiguous, ambiguous.copy(status = ReviewCommitStatus.PREPARED, failure = null)))
    }

    @Test fun `committed_cannot_leave_committed`() {
        for (event in allEvents) {
            val next = recoveryTransition(ReviewCommitStatus.COMMITTED, event)
            assertTrue("COMMITTED stays COMMITTED or rejects; never $next",
                next == ReviewCommitStatus.COMMITTED || next == null)
        }
        val committed = record(ReviewCommitStatus.COMMITTED)
        assertRejected(committed, ReviewCommitTransition.BeginRetry)
        assertRejected(committed, ReviewCommitTransition.BeginAttempt(null))
        assertRejected(committed, ReviewCommitTransition.EnterMutationBoundary)
        assertRejected(committed, ReviewCommitTransition.ReconciliationConfirmedCommitted(null))
        assertRejected(committed, ReviewCommitTransition.ReconciliationConfirmedNotCommitted)
        assertRejected(committed, ReviewCommitTransition.ReconciliationInconclusive("x"))
        assertRejected(committed, ReviewCommitTransition.BackendCommitted(
            BackendCommitResult.ConfirmedCommitted(null)))
    }

    @Test fun `restart_does_not_change_status_by_itself`() = runTest {
        // No durable status except a recovered SUBMITTING (normalized by §28 recovery
        // classification, not by restart alone) may move across a process restart.
        val before = mapOf(
            ReviewCommitStatus.PREPARED to record(ReviewCommitStatus.PREPARED),
            ReviewCommitStatus.RETRY_ALLOWED to record(ReviewCommitStatus.RETRY_ALLOWED),
            ReviewCommitStatus.AMBIGUOUS to record(ReviewCommitStatus.AMBIGUOUS),
            ReviewCommitStatus.COMMITTED to record(ReviewCommitStatus.COMMITTED)
        )
        for ((status, fixture) in before) {
            val store = InMemoryReviewCommitStore(ReviewCommitLedgerCodec.encode(listOf(fixture)))
            val reloaded = ReviewCommitLedger(store, { 9_000 })
            val after = present(reloaded.get(commitId))
            assertEquals("restart must not rewrite $status", status, after.status)
            assertEquals(status, store.durableRecords().single().status)
        }
        // SUBMITTING: restart never manufactures a verdict — it becomes AMBIGUOUS through the
        // locked §28 normalization, never PREPARED and never RETRY_ALLOWED without evidence.
        val submitting = record(ReviewCommitStatus.SUBMITTING)
        val store = InMemoryReviewCommitStore(ReviewCommitLedgerCodec.encode(listOf(submitting)))
        val reloaded = ReviewCommitLedger(store, { 9_000 })
        val after = present(reloaded.get(commitId))
        assertEquals(ReviewCommitStatus.AMBIGUOUS, after.status)
        assertEquals(ReviewCommitResolution.PROCESS_RESTART_WHILE_SUBMITTING, after.resolution)
    }

    @Test fun `ui_projection_cannot_change_durable_status`() = runTest {
        for (status in ReviewCommitStatus.entries) {
            val fixture = record(status)
            // Both projections are pure functions of the durable status…
            assertNotNull(fixture.status.projectForUi(fixture.commitId, fixture.selectedRating))
            assertNotNull(fixture.status.recoveryUiProjection(fixture.commitId, fixture.selectedRating))
            // …detection (the only event a projection could accompany) rewrites nothing.
            assertEquals(status, recoveryTransition(status, ReviewCommitRecoveryEvent.RECOVERY_DETECTED))
            assertEquals(status, fixture.status)
        }
        // The UI-owned ledger interactions (acknowledge, abandon) are metadata only.
        val store = InMemoryReviewCommitStore(
            ReviewCommitLedgerCodec.encode(listOf(record(ReviewCommitStatus.AMBIGUOUS))))
        val ledger = ReviewCommitLedger(store, { 9_000 })
        ledger.acknowledge(commitId)
        ledger.noteAbandoned(commitId, 9_100)
        val after = present(ledger.get(commitId))
        assertEquals("acknowledging and abandoning never change the truth",
            ReviewCommitStatus.AMBIGUOUS, after.status)
    }

    @Test fun `scheduler_change_cannot_change_commit_status`() = runTest {
        // External scheduler activity is observable but not transaction-correlated proof: it is
        // unresolved evidence, so every unfinished status stays what it is.
        val unresolved = ReconcileCommitResult.StillAmbiguous("external_change_unattributable")
        val policy = ReviewCommitRecoveryPolicy()
        assertEquals(ReviewCommitRecoveryAction.RemainBlocked,
            policy.classify(record(ReviewCommitStatus.AMBIGUOUS), unresolved))
        assertEquals(ReviewCommitRecoveryAction.RemainBlocked,
            policy.classify(record(ReviewCommitStatus.SUBMITTING), unresolved))
        val ambiguous = record(ReviewCommitStatus.AMBIGUOUS)
        val reconciled = present(engine(
            ambiguous, ReviewCommitTransition.ReconciliationInconclusive("external_change")))
        assertEquals("unresolved reconciliation never rewrites the status",
            ReviewCommitStatus.AMBIGUOUS, reconciled.status)
        assertEquals(ReviewCommitResolution.RECONCILIATION_INCONCLUSIVE, reconciled.resolution)
    }

    @Test fun `integrity_failure_does_not_rewrite_status`() {
        val policy = ReviewCommitRecoveryPolicy()
        // Proof for a status that provably needs none is a contradiction, never a shortcut.
        for (status in setOf(
            ReviewCommitStatus.PREPARED, ReviewCommitStatus.RETRY_ALLOWED, ReviewCommitStatus.COMMITTED
        )) {
            val fixture = record(status)
            val action = policy.classify(fixture, ReconcileCommitResult.Applied("reconciled"))
            assertTrue("proof for $status is an integrity failure",
                action is ReviewCommitRecoveryAction.IntegrityFailure)
            // And the record is preserved exactly as it was — no rewrite to escape the anomaly.
            assertEquals(status, fixture.status)
        }
        for (status in ReviewCommitStatus.entries) {
            assertEquals("an integrity violation never rewrites $status", status,
                recoveryTransition(status, ReviewCommitRecoveryEvent.INTEGRITY_VIOLATION_DETECTED))
        }
        // SUBMITTING without proof is not an anomaly — it classifies to Reconcile (§38).
        assertEquals(ReviewCommitRecoveryAction.Reconcile,
            policy.classify(record(ReviewCommitStatus.SUBMITTING), null))
    }

    // --------------------------------------------- GATE 11D §41 — mandatory legal transitions

    @Test fun `retry_allowed_retry_request_becomes_prepared`() = runTest {
        assertEquals(ReviewCommitStatus.PREPARED,
            recoveryTransition(ReviewCommitStatus.RETRY_ALLOWED, ReviewCommitRecoveryEvent.RETRY_REQUESTED))
        val retryable = record(ReviewCommitStatus.RETRY_ALLOWED)
        val prepared = present(engine(retryable, ReviewCommitTransition.BeginRetry))
        assertEquals(ReviewCommitStatus.PREPARED, prepared.status)
        // §13 — same transaction identity, frozen rating, the new attempt is counted at claim.
        assertEquals(retryable.commitId, prepared.commitId)
        assertEquals(retryable.turnId, prepared.turnId)
        assertEquals(retryable.cardRef, prepared.cardRef)
        assertEquals(retryable.backendId, prepared.backendId)
        assertEquals(retryable.selectedRating, prepared.selectedRating)
        assertNull(prepared.failure)
        val claimed = present(engine(prepared, ReviewCommitTransition.BeginAttempt(null)))
        assertEquals("attemptCount += 1 across the retry", retryable.attemptCount + 1, claimed.attemptCount)
        // Ledger level: the retry is durable before the attempt is claimed.
        val store = InMemoryReviewCommitStore(
            ReviewCommitLedgerCodec.encode(listOf(record(ReviewCommitStatus.RETRY_ALLOWED))))
        val ledger = ReviewCommitLedger(store, { 9_000 })
        val claim = ledger.claim(commitId, null, allowRetry = true)
        assertTrue(claim is ReviewCommitLedger.ClaimResult.Claimed)
        val durable = present(store.durableRecords().singleOrNull {
            it.status == ReviewCommitStatus.PREPARED })
        assertEquals(retryable.commitId, durable.commitId)
        assertEquals(retryable.selectedRating, durable.selectedRating)
    }

    @Test fun `submitting_reconciled_committed_becomes_committed`() = runTest {
        assertEquals(ReviewCommitStatus.COMMITTED, recoveryTransition(
            ReviewCommitStatus.SUBMITTING, ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_COMMITTED))
        // Pure engine.
        val committed = present(engine(
            record(ReviewCommitStatus.SUBMITTING),
            ReviewCommitTransition.ReconciliationConfirmedCommitted(null)))
        assertEquals(ReviewCommitStatus.COMMITTED, committed.status)
        assertEquals(Rating.GOOD, committed.committedRating)
        assertEquals(ReviewCommitResolution.RECONCILED_APPLIED, committed.resolution)
        // Durable ledger on a live SUBMITTING record (GATE 11D §7: reconciliation is the only
        // legal recovery action for SUBMITTING, and §23's mapping decides the outcome).
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, { 9_000 })
        liveSubmitting(ledger)
        val reconciled = present(ledger.reconcile(
            commitId, ReconcileCommitResult.Applied("reconciled")))
        assertEquals(ReviewCommitStatus.COMMITTED, reconciled.status)
        assertEquals(ReviewCommitStatus.COMMITTED, store.durableRecords().single().status)
    }

    @Test fun `submitting_reconciled_not_committed_becomes_retry_allowed`() = runTest {
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, recoveryTransition(
            ReviewCommitStatus.SUBMITTING, ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_NOT_COMMITTED))
        val retryable = present(engine(
            record(ReviewCommitStatus.SUBMITTING),
            ReviewCommitTransition.ReconciliationConfirmedNotCommitted))
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, retryable.status)
        assertNotNull(retryable.failure)
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, { 9_000 })
        liveSubmitting(ledger)
        val reconciled = present(ledger.reconcile(
            commitId, ReconcileCommitResult.NotApplied("reconciled")))
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, reconciled.status)
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, store.durableRecords().single().status)
    }

    @Test fun `submitting_unresolved_becomes_ambiguous`() = runTest {
        assertEquals(ReviewCommitStatus.AMBIGUOUS, recoveryTransition(
            ReviewCommitStatus.SUBMITTING, ReviewCommitRecoveryEvent.RECONCILIATION_UNRESOLVED))
        // Pure engine.
        val ambiguous = present(engine(
            record(ReviewCommitStatus.SUBMITTING),
            ReviewCommitTransition.ReconciliationInconclusive("reconcile_timeout")))
        assertEquals(ReviewCommitStatus.AMBIGUOUS, ambiguous.status)
        assertEquals("reconcile_timeout", ambiguous.failure?.category)
        // Live ledger: an unavailable reconciliation normalizes SUBMITTING into explicit uncertainty.
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, { 9_000 })
        liveSubmitting(ledger)
        val normalized = present(ledger.reconcile(
            commitId, ReconcileCommitResult.Unavailable(AnkiError.BackendUnavailable())))
        assertEquals(ReviewCommitStatus.AMBIGUOUS, normalized.status)
        // Restart path: a recovered SUBMITTING that cannot be classified becomes AMBIGUOUS (§28).
        val restarted = ReviewCommitLedger(InMemoryReviewCommitStore(
            ReviewCommitLedgerCodec.encode(listOf(record(ReviewCommitStatus.SUBMITTING)))), { 9_000 })
        assertEquals(ReviewCommitStatus.AMBIGUOUS, restarted.get(commitId)?.status)
    }

    @Test fun `ambiguous_reconciled_committed_becomes_committed`() = runTest {
        assertEquals(ReviewCommitStatus.COMMITTED, recoveryTransition(
            ReviewCommitStatus.AMBIGUOUS, ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_COMMITTED))
        val committed = present(engine(
            record(ReviewCommitStatus.AMBIGUOUS),
            ReviewCommitTransition.ReconciliationConfirmedCommitted(null)))
        assertEquals(ReviewCommitStatus.COMMITTED, committed.status)
        assertEquals(Rating.GOOD, committed.committedRating)
        val store = InMemoryReviewCommitStore(
            ReviewCommitLedgerCodec.encode(listOf(record(ReviewCommitStatus.AMBIGUOUS))))
        val ledger = ReviewCommitLedger(store, { 9_000 })
        val reconciled = present(ledger.reconcile(
            commitId, ReconcileCommitResult.Applied("reconciled")))
        assertEquals(ReviewCommitStatus.COMMITTED, reconciled.status)
        assertEquals(ReviewCommitStatus.COMMITTED, store.durableRecords().single().status)
    }

    @Test fun `ambiguous_reconciled_not_committed_becomes_retry_allowed`() = runTest {
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, recoveryTransition(
            ReviewCommitStatus.AMBIGUOUS, ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_NOT_COMMITTED))
        val retryable = present(engine(
            record(ReviewCommitStatus.AMBIGUOUS),
            ReviewCommitTransition.ReconciliationConfirmedNotCommitted))
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, retryable.status)
        val store = InMemoryReviewCommitStore(
            ReviewCommitLedgerCodec.encode(listOf(record(ReviewCommitStatus.AMBIGUOUS))))
        val ledger = ReviewCommitLedger(store, { 9_000 })
        val reconciled = present(ledger.reconcile(
            commitId, ReconcileCommitResult.NotApplied("reconciled")))
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, reconciled.status)
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, store.durableRecords().single().status)
    }

    @Test fun `ambiguous_unresolved_remains_ambiguous`() = runTest {
        assertEquals(ReviewCommitStatus.AMBIGUOUS, recoveryTransition(
            ReviewCommitStatus.AMBIGUOUS, ReviewCommitRecoveryEvent.RECONCILIATION_UNRESOLVED))
        assertEquals(ReviewCommitStatus.AMBIGUOUS, recoveryTransition(
            ReviewCommitStatus.AMBIGUOUS, ReviewCommitRecoveryEvent.RECONCILIATION_STARTED))
        val still = present(engine(
            record(ReviewCommitStatus.AMBIGUOUS),
            ReviewCommitTransition.ReconciliationInconclusive("reconcile_timeout")))
        assertEquals(ReviewCommitStatus.AMBIGUOUS, still.status)
        val store = InMemoryReviewCommitStore(
            ReviewCommitLedgerCodec.encode(listOf(record(ReviewCommitStatus.AMBIGUOUS))))
        val ledger = ReviewCommitLedger(store, { 9_000 })
        val reconciled = present(ledger.reconcile(
            commitId, ReconcileCommitResult.Unsupported("authoritative_reconciliation_unsupported")))
        assertEquals("an unresolved probe is not an error; uncertainty simply remains",
            ReviewCommitStatus.AMBIGUOUS, reconciled.status)
        assertEquals(ReviewCommitStatus.AMBIGUOUS, store.durableRecords().single().status)
    }

    @Test fun `committed_remains_committed_after_restart`() = runTest {
        for (event in allEvents) {
            assertEquals(ReviewCommitStatus.COMMITTED,
                recoveryTransition(ReviewCommitStatus.COMMITTED, event) ?: ReviewCommitStatus.COMMITTED)
        }
        val committed = record(ReviewCommitStatus.COMMITTED)
        val store = InMemoryReviewCommitStore(ReviewCommitLedgerCodec.encode(listOf(committed)))
        val reloaded = ReviewCommitLedger(store, { 9_000 })
        val after = present(reloaded.get(commitId))
        assertEquals(ReviewCommitStatus.COMMITTED, after.status)
        assertEquals(committed.attemptCount, after.attemptCount)
        assertEquals(committed.committedRating, after.committedRating)
        assertEquals("no replay on restart", committed.attemptCount, after.attemptCount)
    }

    // --------------------------------------------- GATE 11D §25/§38/§39 — derived rules

    @Test fun `next_card_is_allowed_from_committed_only`() {
        for (status in ReviewCommitStatus.entries) {
            assertEquals("nextCardAllowed($status)", status == ReviewCommitStatus.COMMITTED,
                nextCardAllowed(status))
        }
    }

    @Test fun `recovery_action_is_derived_from_the_durable_status`() {
        val policy = ReviewCommitRecoveryPolicy()
        assertEquals(ReviewCommitRecoveryAction.OfferRetry, policy.classifyStatus(ReviewCommitStatus.PREPARED))
        assertEquals(ReviewCommitRecoveryAction.Reconcile, policy.classifyStatus(ReviewCommitStatus.SUBMITTING))
        assertEquals(ReviewCommitRecoveryAction.OfferRetry, policy.classifyStatus(ReviewCommitStatus.RETRY_ALLOWED))
        assertEquals(ReviewCommitRecoveryAction.Reconcile, policy.classifyStatus(ReviewCommitStatus.AMBIGUOUS))
        assertEquals(ReviewCommitRecoveryAction.ResumeCommitted, policy.classifyStatus(ReviewCommitStatus.COMMITTED))
    }

    @Test fun `recovery_ui_projection_is_derived_from_the_durable_status`() {
        val rating = Rating.GOOD
        // GATE 11D §39 — the recovery projection…
        assertEquals(RatingCommitUiState.RetryAvailable(commitId),
            ReviewCommitStatus.PREPARED.recoveryUiProjection(commitId, rating))
        assertEquals(RatingCommitUiState.VerificationRequired(commitId),
            ReviewCommitStatus.SUBMITTING.recoveryUiProjection(commitId, rating))
        assertEquals(RatingCommitUiState.RetryAvailable(commitId),
            ReviewCommitStatus.RETRY_ALLOWED.recoveryUiProjection(commitId, rating))
        assertEquals(RatingCommitUiState.VerificationRequired(commitId),
            ReviewCommitStatus.AMBIGUOUS.recoveryUiProjection(commitId, rating))
        assertEquals(RatingCommitUiState.Saved(commitId, rating),
            ReviewCommitStatus.COMMITTED.recoveryUiProjection(commitId, rating))
        // …and the live pipeline projection of GATE 11B §14 is unchanged.
        assertEquals(RatingCommitUiState.Saving(commitId),
            ReviewCommitStatus.PREPARED.projectForUi(commitId, rating))
        assertEquals(RatingCommitUiState.Saving(commitId),
            ReviewCommitStatus.SUBMITTING.projectForUi(commitId, rating))
    }

    // ---------------------------------------------------------------- helpers

    /** A live in-process SUBMITTING record: prepared → claimed → mutation boundary entered. */
    private suspend fun liveSubmitting(ledger: ReviewCommitLedger) {
        assertTrue("prepare the transaction first",
            ledger.prepare(freshRequest()) is ReviewCommitLedger.PrepareResult.Prepared)
        assertTrue("claim the attempt first",
            ledger.claim(commitId, null, allowRetry = false) is ReviewCommitLedger.ClaimResult.Claimed)
        present(ledger.markMutationEntered(commitId))
    }

    private fun freshRequest() = CommitRatingRequest(
        commitId = commitId,
        card = AnkiCardRef(backendId, cardId = "c1"),
        rating = Rating.GOOD,
        ratedAtEpochMs = 900
    )
}
