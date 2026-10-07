package com.studyagent.client.anki

import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 11B §45 — the normative recovery decision table, independent of storage, UI and backend.
 *
 * The classification reads the durable [ReviewCommitStatus] alone (GATE 11B §27): no `status +
 * phase` inference, no guessing. Test names are the canonical ones from GATE 11B §46.
 */
class ReviewCommitRecoveryPolicyTest {
    private val backend = AnkiBackendId.Fake("policy")
    private val id = ReviewCommitId(backend, "session", ReviewTurnId("turn"))
    private val card = AnkiCardRef(backend, cardId = "card")
    private val policy = ReviewCommitRecoveryPolicy()

    private fun record(
        status: ReviewCommitStatus,
        phase: ReviewCommitPhase? = null,
        attemptCount: Int = if (status == ReviewCommitStatus.PREPARED) 0 else 1,
        submittedAtEpochMs: Long? = if (status == ReviewCommitStatus.PREPARED) null else 1_500,
        response: CommitResponseEvidence? = null
    ) = ReviewCommitRecord(
        commitId = id, card = card, rating = Rating.GOOD, status = status,
        attemptCount = attemptCount, phase = phase, submittedAtEpochMs = submittedAtEpochMs,
        response = when {
            response != null -> response
            phase == ReviewCommitPhase.BACKEND_RESPONSE_RECEIVED ->
                CommitResponseEvidence(CommitResponseKind.OUTCOME_UNKNOWN, ReviewCommitFailure("unknown"))
            else -> null
        },
        createdAtEpochMs = 1, updatedAtEpochMs = 2, ratedAtEpochMs = 1,
        failure = when (status) {
            ReviewCommitStatus.RETRY_ALLOWED -> ReviewCommitFailure("no_effect")
            ReviewCommitStatus.AMBIGUOUS -> ReviewCommitFailure("unknown")
            else -> null
        },
        committedRating = if (status == ReviewCommitStatus.COMMITTED) Rating.GOOD else null
    )

    @Test fun `prepared_commit_is_safe_to_offer_retry_after_restart`() {
        // PREPARED means the mutation boundary was never entered, whatever the attempt count is.
        assertEquals(ReviewCommitRecoveryAction.OfferRetry,
            policy.classify(record(ReviewCommitStatus.PREPARED, ReviewCommitPhase.INTENT_PERSISTED)))
        assertEquals(ReviewCommitRecoveryAction.OfferRetry, policy.classify(
            record(ReviewCommitStatus.PREPARED, ReviewCommitPhase.INTENT_PERSISTED, attemptCount = 3)))
        // A proven-not-applied transaction is equally safe to submit again.
        assertEquals(ReviewCommitRecoveryAction.OfferRetry, policy.classify(
            record(ReviewCommitStatus.RETRY_ALLOWED, ReviewCommitPhase.FINAL_STATUS_PERSISTED)))
    }

    @Test fun `submitting_commit_requires_reconciliation_after_restart`() {
        // SUBMITTING means the boundary was entered, so the outcome is unknown until proven.
        for (phase in listOf(ReviewCommitPhase.MUTATION_BOUNDARY_ENTERED,
            ReviewCommitPhase.BACKEND_RESPONSE_RECEIVED)) {
            assertEquals("phase $phase must not change the decision",
                ReviewCommitRecoveryAction.Reconcile,
                policy.classify(record(ReviewCommitStatus.SUBMITTING, phase)))
        }
    }

    @Test fun `ambiguous_commit_requires_reconciliation`() {
        val ambiguous = record(ReviewCommitStatus.AMBIGUOUS, ReviewCommitPhase.FINAL_STATUS_PERSISTED)
        assertEquals(ReviewCommitRecoveryAction.Reconcile, policy.classify(ambiguous))
        // Evidence that cannot decide leaves it blocked, never "probably fine".
        assertEquals(ReviewCommitRecoveryAction.RemainBlocked,
            policy.classify(ambiguous, ReconcileCommitResult.StillAmbiguous("no_receipt")))
        assertEquals(ReviewCommitRecoveryAction.RemainBlocked,
            policy.classify(ambiguous, ReconcileCommitResult.Unsupported()))
        assertEquals(ReviewCommitRecoveryAction.RemainBlocked,
            policy.classify(ambiguous, ReconcileCommitResult.Unavailable(AnkiError.BackendUnavailable())))
        // Authoritative evidence resolves it in exactly one of two directions.
        assertEquals(ReviewCommitRecoveryAction.ResumeCommitted,
            policy.classify(ambiguous, ReconcileCommitResult.Applied("authoritative_receipt")))
        assertEquals(ReviewCommitRecoveryAction.OfferRetry,
            policy.classify(ambiguous, ReconcileCommitResult.NotApplied("authoritative_receipt")))
    }

    @Test fun `committed_commit_resumes_without_replay`() {
        assertEquals(ReviewCommitRecoveryAction.ResumeCommitted,
            policy.classify(record(ReviewCommitStatus.COMMITTED, ReviewCommitPhase.FINAL_STATUS_PERSISTED)))
    }

    @Test fun `reconciliation proof for a non-ambiguous record is an integrity failure`() {
        // A proof must never be used to skip the ambiguity check.
        for (status in listOf(ReviewCommitStatus.PREPARED, ReviewCommitStatus.COMMITTED,
            ReviewCommitStatus.RETRY_ALLOWED)) {
            val action = policy.classify(record(status, ReviewCommitPhase.FINAL_STATUS_PERSISTED),
                ReconcileCommitResult.Applied("proof"))
            assertTrue("proof for $status must be refused: $action",
                action is ReviewCommitRecoveryAction.IntegrityFailure)
        }
    }

    @Test fun `the recovery action vocabulary is disjoint from the durable status vocabulary`() {
        // INV-11B-14: an engineer reading a log line can tell the layers apart by name alone.
        val statuses = ReviewCommitStatus.values().map { it.name }.toSet()
        val actions = setOf("ResumeCommitted", "OfferRetry", "Reconcile", "RemainBlocked", "IntegrityFailure")
        assertTrue("no recovery action may reuse a durable status name",
            statuses.intersect(actions).isEmpty())
        val phases = ReviewCommitPhase.values().map { it.name }.toSet()
        assertTrue("no attempt phase may reuse a durable status name", statuses.intersect(phases).isEmpty())
    }
}
