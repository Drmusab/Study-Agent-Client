package com.studyagent.client.anki

import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import org.junit.Assert.*
import org.junit.Test

/** Normative recovery table, independent of Android storage/UI/backend. */
class ReviewCommitRecoveryPolicyTest {
    private val backend = AnkiBackendId.Fake("policy")
    private val id = ReviewCommitId(backend, "session", ReviewTurnId("turn"))
    private val card = AnkiCardRef(backend, cardId = "card")
    private val policy = ReviewCommitRecoveryPolicy()

    private fun record(state: ReviewCommitState, phase: CommitAttemptPhase? = null,
        response: CommitResponseEvidence? = null, safe: Boolean = false) = ReviewCommitRecord(
        commitId = id, card = card, rating = Rating.GOOD, state = state,
        attemptCount = if (state == ReviewCommitState.NOT_STARTED) 0 else 1,
        phase = phase, response = response,
        createdAtEpochMs = 1, updatedAtEpochMs = 2, ratedAtEpochMs = 1,
        failure = if (state == ReviewCommitState.FAILED) ReviewCommitFailure("no_effect", safe) else null
    )

    @Test fun `prepared no boundary is retryable, entered without proof requires reconciliation`() {
        assertEquals(CommitRecoveryAction.RetryAllowed, policy.classify(record(ReviewCommitState.NOT_STARTED)))
        assertEquals(CommitRecoveryAction.RetryAllowed,
            policy.classify(record(ReviewCommitState.SUBMITTING, CommitAttemptPhase.PREPARED)))
        assertEquals(CommitRecoveryAction.ReconciliationRequired,
            policy.classify(record(ReviewCommitState.SUBMITTING, CommitAttemptPhase.MUTATION_CALL_ENTERED)))
        // Old rows with no phase never grant retry.
        assertEquals(CommitRecoveryAction.ReconciliationRequired,
            policy.classify(record(ReviewCommitState.SUBMITTING)))
    }

    @Test fun `durable response is proof without new delivery`() {
        val phase = CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED
        assertEquals(CommitRecoveryAction.ResumeCommitted, policy.classify(record(
            ReviewCommitState.SUBMITTING, phase, CommitResponseEvidence(CommitResponseKind.CONFIRMED_COMMITTED))))
        assertEquals(CommitRecoveryAction.RetryAllowed, policy.classify(record(
            ReviewCommitState.SUBMITTING, phase, CommitResponseEvidence(CommitResponseKind.CONFIRMED_NOT_APPLIED,
                ReviewCommitFailure("no_effect", true)))))
        assertEquals(CommitRecoveryAction.BlockedUnresolved, policy.classify(record(
            ReviewCommitState.SUBMITTING, phase, CommitResponseEvidence(CommitResponseKind.CONFIRMED_NOT_APPLIED,
                ReviewCommitFailure("conflict", false)))))
        assertEquals(CommitRecoveryAction.ReconciliationRequired, policy.classify(record(
            ReviewCommitState.SUBMITTING, phase, CommitResponseEvidence(CommitResponseKind.OUTCOME_UNKNOWN,
                ReviewCommitFailure("timeout", false)))))
        assertEquals(CommitRecoveryAction.IntegrityError, policy.classify(record(ReviewCommitState.SUBMITTING, phase)))
    }

    @Test fun `ambiguous has no implicit retry or success`() {
        val ambiguous = record(ReviewCommitState.AMBIGUOUS, CommitAttemptPhase.LOCAL_RESULT_PERSISTED)
        assertEquals(CommitRecoveryAction.BlockedUnresolved, policy.classify(ambiguous))
        assertEquals(CommitRecoveryAction.BlockedUnresolved,
            policy.classify(ambiguous, ReconcileCommitResult.StillAmbiguous("no_receipt")))
        assertEquals(CommitRecoveryAction.ResumeCommitted,
            policy.classify(ambiguous, ReconcileCommitResult.Applied("authoritative_receipt")))
        assertEquals(CommitRecoveryAction.RetryAllowed,
            policy.classify(ambiguous, ReconcileCommitResult.NotApplied("authoritative_receipt", true)))
    }

    @Test fun `committed is terminal, unsafe failures never retry, illegal phase fails closed`() {
        assertEquals(CommitRecoveryAction.ResumeCommitted,
            policy.classify(record(ReviewCommitState.COMMITTED, CommitAttemptPhase.LOCAL_RESULT_PERSISTED)))
        assertEquals(CommitRecoveryAction.RetryAllowed,
            policy.classify(record(ReviewCommitState.FAILED, CommitAttemptPhase.LOCAL_RESULT_PERSISTED, safe = true)))
        assertEquals(CommitRecoveryAction.BlockedUnresolved,
            policy.classify(record(ReviewCommitState.FAILED, CommitAttemptPhase.LOCAL_RESULT_PERSISTED, safe = false)))
        assertEquals(CommitRecoveryAction.IntegrityError,
            policy.classify(record(ReviewCommitState.COMMITTED, CommitAttemptPhase.PREPARED)))
        assertEquals(CommitRecoveryAction.IntegrityError,
            policy.classify(record(ReviewCommitState.COMMITTED, CommitAttemptPhase.LOCAL_RESULT_PERSISTED),
                ReconcileCommitResult.NotApplied("invalid", true)))
    }
}
