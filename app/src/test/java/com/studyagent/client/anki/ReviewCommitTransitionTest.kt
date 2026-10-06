package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.CommitAttemptPhase
import com.studyagent.client.core.anki.CommitRatingResult
import com.studyagent.client.core.anki.CommitResponseEvidence
import com.studyagent.client.core.anki.CommitResponseKind
import com.studyagent.client.core.anki.CommitRecoveryAction
import com.studyagent.client.core.anki.ReconcileCommitResult
import com.studyagent.client.core.anki.ReviewCommitFailure
import com.studyagent.client.core.anki.ReviewCommitId
import com.studyagent.client.core.anki.ReviewCommitRecord
import com.studyagent.client.core.anki.ReviewCommitState
import com.studyagent.client.core.anki.ReviewCommitTransition
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.anki.ReviewCommitTransitions
import com.studyagent.client.core.models.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 11 checkpoint 2 — the pure commit transition engine.
 *
 * No coroutines, no storage and no backend: this class pins the *only* legal ways a
 * [ReviewCommitRecord] may move, including every transition the gate forbids. The ledger adds
 * durability and version checks on top; it must not add new states of its own.
 */
class ReviewCommitTransitionTest {

    private fun record(
        state: ReviewCommitState,
        phase: CommitAttemptPhase? = null,
        attemptCount: Int = if (state == ReviewCommitState.NOT_STARTED) 0 else 1,
        response: CommitResponseEvidence? = null,
        failure: ReviewCommitFailure? = null,
        version: Long = 0
    ) = ReviewCommitRecord(
        commitId = ReviewCommitId(AnkiBackendId.Fake(), "study-1", ReviewTurnId("turn-1")),
        card = AnkiCardRef(AnkiBackendId.Fake(), cardId = "c1"),
        rating = Rating.GOOD,
        state = state,
        attemptCount = attemptCount,
        phase = phase,
        response = response,
        failure = failure,
        createdAtEpochMs = 1_000,
        updatedAtEpochMs = 1_000,
        ratedAtEpochMs = 900,
        version = version
    )

    private val now = 5_000L

    // ------------------------------------------------------------ required transitions

    @Test
    fun `NOT_STARTED to SUBMITTING is the only legal first step`() {
        val before = record(ReviewCommitState.NOT_STARTED)
        val after = before.copy(
            state = ReviewCommitState.SUBMITTING,
            phase = CommitAttemptPhase.PREPARED,
            attemptCount = 1,
            version = 1
        )
        assertTrue(ReviewCommitTransitions.allowed(before, after))
    }

    @Test
    fun `SUBMITTING to COMMITTED requires the durably recorded response`() {
        val entered = record(ReviewCommitState.SUBMITTING, CommitAttemptPhase.MUTATION_CALL_ENTERED)
        val committed = CommitRatingResult.Committed()
        val proof = ReviewCommitTransitions.responseEvidence(entered, committed)!!
        val received = entered.copy(
            phase = CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED, response = proof, version = 1)
        assertTrue(ReviewCommitTransitions.allowed(entered, received))
        val final = received.copy(
            state = ReviewCommitState.COMMITTED,
            phase = CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
            version = 2
        )
        assertTrue(ReviewCommitTransitions.allowed(received, final))
    }

    @Test
    fun `SUBMITTING to FAILED_SAFE_TO_RETRY is legal only before the mutation call is entered`() {
        val prepared = record(ReviewCommitState.SUBMITTING, CommitAttemptPhase.PREPARED)
        val failed = ReviewCommitTransitions.apply(
            prepared, ReviewCommitTransition.MarkSafeToRetry("preflight_refused"), now)!!
        assertEquals(ReviewCommitState.FAILED, failed.state)
        assertTrue(failed.failure!!.safeToRetry)
        assertTrue(ReviewCommitTransitions.allowed(prepared, failed.copy(version = 1)))
    }

    @Test
    fun `SUBMITTING to AMBIGUOUS is legal from PREPARED and from CALL_ENTERED`() {
        for (phase in listOf(CommitAttemptPhase.PREPARED, CommitAttemptPhase.MUTATION_CALL_ENTERED)) {
            val before = record(ReviewCommitState.SUBMITTING, phase)
            val after = ReviewCommitTransitions.apply(
                before, ReviewCommitTransition.MarkAmbiguous("outcome_unknown", "backend_ambiguous"), now)!!
            assertEquals(ReviewCommitState.AMBIGUOUS, after.state)
            assertFalse("ambiguity is never retryable on its own", after.safeToRetry)
            assertTrue(ReviewCommitTransitions.allowed(before, after.copy(version = 1)))
        }
    }

    @Test
    fun `FAILED_SAFE_TO_RETRY to SUBMITTING is legal and keeps the identity and rating`() {
        val failed = ReviewCommitTransitions.apply(
            record(ReviewCommitState.SUBMITTING, CommitAttemptPhase.PREPARED),
            ReviewCommitTransition.MarkSafeToRetry("preflight_refused"), now)!!.copy(version = 1)
        val retry = failed.copy(
            state = ReviewCommitState.SUBMITTING,
            phase = CommitAttemptPhase.PREPARED,
            failure = null,
            attemptCount = failed.attemptCount + 1,
            version = 2
        )
        assertTrue(ReviewCommitTransitions.allowed(failed, retry))
        assertEquals(failed.commitId, retry.commitId)
        assertEquals(failed.rating, retry.rating)
    }

    @Test
    fun `AMBIGUOUS to COMMITTED is legal only through reconciliation`() {
        val ambiguous = ReviewCommitTransitions.apply(
            record(ReviewCommitState.SUBMITTING, CommitAttemptPhase.MUTATION_CALL_ENTERED),
            ReviewCommitTransition.MarkAmbiguous("lost_response", "backend_ambiguous"), now)!!.copy(version = 1)
        // Directly assigning COMMITTED is not a transition the engine offers at all.
        assertNull(ReviewCommitTransitions.apply(
            ambiguous, ReviewCommitTransition.MarkCommitted(CommitRatingResult.Committed(), "fabricated"), now))
        val reconciled = ReviewCommitTransitions.apply(
            ambiguous,
            ReviewCommitTransition.MarkReconciled(ReconcileCommitResult.Applied("proof"), CommitRecoveryAction.ResumeCommitted),
            now)!!
        assertEquals(ReviewCommitState.COMMITTED, reconciled.state)
        assertTrue(ReviewCommitTransitions.allowed(ambiguous, reconciled.copy(version = 2)))
    }

    @Test
    fun `AMBIGUOUS to FAILED_SAFE_TO_RETRY requires reconciliation proof that it was not applied`() {
        val ambiguous = ReviewCommitTransitions.apply(
            record(ReviewCommitState.SUBMITTING, CommitAttemptPhase.MUTATION_CALL_ENTERED),
            ReviewCommitTransition.MarkAmbiguous("lost_response", "backend_ambiguous"), now)!!.copy(version = 1)
        val reconciled = ReviewCommitTransitions.apply(
            ambiguous,
            ReviewCommitTransition.MarkReconciled(
                ReconcileCommitResult.NotApplied("not applied", safeToRetry = true), CommitRecoveryAction.RetryAllowed),
            now)!!
        assertEquals(ReviewCommitState.FAILED, reconciled.state)
        assertTrue(reconciled.safeToRetry)
        assertTrue(ReviewCommitTransitions.allowed(ambiguous, reconciled.copy(version = 2)))
    }

    // ------------------------------------------------------------ rejected transitions

    @Test
    fun `COMMITTED is terminal for every state-changing command`() {
        val committed = record(
            ReviewCommitState.COMMITTED, CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
            response = CommitResponseEvidence(CommitResponseKind.CONFIRMED_COMMITTED))
        for (command in listOf(
            ReviewCommitTransition.MarkMutationCallEntered,
            ReviewCommitTransition.MarkResponseReceived(CommitRatingResult.Committed()),
            ReviewCommitTransition.MarkCommitted(CommitRatingResult.Committed(), "again"),
            ReviewCommitTransition.MarkSafeToRetry("late"),
            ReviewCommitTransition.MarkNotApplied("late", true),
            ReviewCommitTransition.MarkAmbiguous("late", "late"),
            ReviewCommitTransition.MarkReconciled(
                ReconcileCommitResult.Applied("late"), CommitRecoveryAction.ResumeCommitted)
        )) {
            assertNull("$command must not move a COMMITTED record", ReviewCommitTransitions.apply(committed, command, now))
        }
        // Metadata-only notes are the only thing a COMMITTED record accepts.
        assertNotNull(ReviewCommitTransitions.apply(
            committed, ReviewCommitTransition.NoteAbandoned(9_000), now))
    }

    @Test
    fun `AMBIGUOUS cannot go back to SUBMITTING directly`() {
        val ambiguous = record(ReviewCommitState.AMBIGUOUS, CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
            failure = ReviewCommitFailure("lost_response", false))
        val directRetry = ambiguous.copy(
            state = ReviewCommitState.SUBMITTING,
            phase = CommitAttemptPhase.PREPARED,
            attemptCount = ambiguous.attemptCount + 1,
            version = 1
        )
        assertFalse(ReviewCommitTransitions.allowed(ambiguous, directRetry))
        assertNull(ReviewCommitTransitions.apply(ambiguous, ReviewCommitTransition.MarkSafeToRetry("invented"), now))
    }

    @Test
    fun `COMMITTED cannot be downgraded and a second attempt cannot be invented`() {
        val committed = record(ReviewCommitState.COMMITTED, CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
            response = CommitResponseEvidence(CommitResponseKind.CONFIRMED_COMMITTED))
        val downgraded = committed.copy(state = ReviewCommitState.FAILED,
            failure = ReviewCommitFailure("late", false), attemptCount = 2, version = 1)
        assertFalse(ReviewCommitTransitions.allowed(committed, downgraded))
    }

    @Test
    fun `a not-applied command is refused for a non-retryable failure`() {
        val rejected = record(ReviewCommitState.FAILED, CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
            failure = ReviewCommitFailure("rejected", false))
        assertNull(ReviewCommitTransitions.apply(rejected, ReviewCommitTransition.MarkNotApplied("again", true), now))
        val retry = rejected.copy(state = ReviewCommitState.SUBMITTING, phase = CommitAttemptPhase.PREPARED,
            attemptCount = 2, version = 1)
        assertFalse(ReviewCommitTransitions.allowed(rejected, retry))
    }

    @Test
    fun `the response evidence never fabricates a committed receipt for a different rating`() {
        val record = record(ReviewCommitState.SUBMITTING, CommitAttemptPhase.MUTATION_CALL_ENTERED)
        val foreign = CommitRatingResult.Committed(
            receipt = com.studyagent.client.core.anki.CommitReceipt(
                backendId = AnkiBackendId.PcAgent("other"),
                backendReceiptId = "r-1", committedRating = Rating.GOOD))
        assertNull("a foreign backend receipt is not evidence for this commit",
            ReviewCommitTransitions.responseEvidence(record, foreign))
    }

    @Test
    fun `every OUTCOME_UNKNOWN answer is recorded as unknown and never as applied`() {
        val record = record(ReviewCommitState.SUBMITTING, CommitAttemptPhase.MUTATION_CALL_ENTERED)
        val proof = ReviewCommitTransitions.responseEvidence(
            record, CommitRatingResult.Ambiguous(AnkiError.QueryFailure("timeout")))
        assertEquals(CommitResponseKind.OUTCOME_UNKNOWN, proof!!.kind)
        assertFalse(proof.failure!!.safeToRetry)
    }

    @Test
    fun `an illegal transition is rejected with a typed absence before any state change`() {
        val fresh = record(ReviewCommitState.NOT_STARTED)
        // No command may jump from NOT_STARTED straight to a terminal state.
        assertNull(ReviewCommitTransitions.apply(
            fresh, ReviewCommitTransition.MarkCommitted(CommitRatingResult.Committed(), "skip"), now))
        assertNull(ReviewCommitTransitions.apply(fresh, ReviewCommitTransition.MarkMutationCallEntered, now))
        assertNull(ReviewCommitTransitions.apply(fresh,
            ReviewCommitTransition.MarkAmbiguous("before_dispatch", "backend_ambiguous"), now))
        // And nothing may reopen a turn with a different identity.
        val other = fresh.copy(
            commitId = ReviewCommitId(AnkiBackendId.Fake(), "study-1", ReviewTurnId("turn-2")), version = 1)
        assertFalse(ReviewCommitTransitions.allowed(fresh, other))
    }

    @Test
    fun `metadata-only notes keep the state and never mean not committed`() {
        val committed = record(ReviewCommitState.COMMITTED, CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
            response = CommitResponseEvidence(CommitResponseKind.CONFIRMED_COMMITTED))
        val noted = ReviewCommitTransitions.apply(committed, ReviewCommitTransition.NoteAbandoned(9_000), now)!!
        assertEquals(ReviewCommitState.COMMITTED, noted.state)
        assertTrue(ReviewCommitTransitions.allowed(committed, noted.copy(version = committed.version + 1)))
    }

    @Test
    fun `an attempt count may only grow by exactly one and only into SUBMITTING`() {
        val prepared = record(ReviewCommitState.SUBMITTING, CommitAttemptPhase.PREPARED)
        val twoAttempts = prepared.copy(attemptCount = 3, version = 1)
        assertFalse(ReviewCommitTransitions.allowed(prepared, twoAttempts))
        // An attempt count may not move backwards either (a two-attempt row back to one).
        val two = prepared.copy(attemptCount = 2, version = 1)
        val backwards = two.copy(state = ReviewCommitState.SUBMITTING,
            phase = CommitAttemptPhase.PREPARED, attemptCount = 1, version = 2)
        assertFalse(ReviewCommitTransitions.allowed(two, backwards))
    }
}
