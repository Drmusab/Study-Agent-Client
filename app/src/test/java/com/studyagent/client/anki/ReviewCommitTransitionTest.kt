package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.ReviewCommitPhase
import com.studyagent.client.core.anki.BackendCommitResult
import com.studyagent.client.core.anki.CommitReceipt
import com.studyagent.client.core.anki.CommitResponseEvidence
import com.studyagent.client.core.anki.CommitResponseKind
import com.studyagent.client.core.anki.ReconcileCommitResult
import com.studyagent.client.core.anki.ReviewCommitEvidence
import com.studyagent.client.core.anki.ReviewCommitFailure
import com.studyagent.client.core.anki.ReviewCommitId
import com.studyagent.client.core.anki.ReviewCommitRecord
import com.studyagent.client.core.anki.ReviewCommitResolution
import com.studyagent.client.core.anki.ReviewCommitStatus
import com.studyagent.client.core.anki.ReviewCommitTransition
import com.studyagent.client.core.anki.ReviewCommitTransitionRejection
import com.studyagent.client.core.anki.ReviewCommitTransitionResult
import com.studyagent.client.core.anki.ReviewCommitTransitions
import com.studyagent.client.core.anki.toReviewCommitStatus
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.models.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 11B — the pure commit transition engine, in the canonical vocabulary of GATE 11B §19/§21.
 *
 * No coroutines, no storage and no backend: this class pins the *only* legal ways a
 * [ReviewCommitRecord] may move, including every transition the gate forbids. The ledger adds
 * durability and version checks on top; it must not add statuses of its own.
 *
 * Test names are the canonical ones from GATE 11B §46.
 */
class ReviewCommitTransitionTest {

    private val backendId = AnkiBackendId.Fake()

    private fun record(
        status: ReviewCommitStatus,
        phase: ReviewCommitPhase? = null,
        attemptCount: Int = if (status == ReviewCommitStatus.PREPARED) 0 else 1,
        response: CommitResponseEvidence? = null,
        failure: ReviewCommitFailure? = null,
        submittedAtEpochMs: Long? = if (status == ReviewCommitStatus.PREPARED) null else 1_500,
        version: Long = 0
    ) = ReviewCommitRecord(
        commitId = ReviewCommitId(backendId, "study-1", ReviewTurnId("turn-1")),
        card = AnkiCardRef(backendId, cardId = "c1"),
        rating = Rating.GOOD,
        status = status,
        attemptCount = attemptCount,
        phase = phase,
        response = response,
        failure = failure,
        committedRating = if (status == ReviewCommitStatus.COMMITTED) Rating.GOOD else null,
        createdAtEpochMs = 1_000,
        updatedAtEpochMs = 1_000,
        ratedAtEpochMs = 900,
        submittedAtEpochMs = submittedAtEpochMs,
        version = version
    )

    /** A record that has reached the durable `SUBMITTING / MUTATION_BOUNDARY_ENTERED` marker. */
    private fun submitted(attempt: Int = 1, version: Long = 0) =
        record(ReviewCommitStatus.SUBMITTING, ReviewCommitPhase.MUTATION_BOUNDARY_ENTERED,
            attemptCount = attempt, submittedAtEpochMs = 1_500, version = version)

    private val now = 5_000L

    private fun assertIsApplied(result: ReviewCommitTransitionResult): ReviewCommitRecord = when (result) {
        is ReviewCommitTransitionResult.Applied -> result.record
        is ReviewCommitTransitionResult.Rejected -> error("unexpected rejection: ${result.reason}")
    }

    /** Test shorthand; rejection assertions that care about the reason use the typed engine directly. */
    private fun apply(
        record: ReviewCommitRecord,
        command: ReviewCommitTransition,
        at: Long = now
    ): ReviewCommitRecord? = when (val result = ReviewCommitTransitions.transition(record, command, at)) {
        is ReviewCommitTransitionResult.Applied -> result.record
        is ReviewCommitTransitionResult.Rejected -> null
    }

    // ------------------------------------------------- GATE 11B §46 canonical transition names

    @Test fun `prepared_commit_can_enter_submitting`() {
        val claimed = assertIsApplied(ReviewCommitTransitions.transition(
            record(ReviewCommitStatus.PREPARED), ReviewCommitTransition.BeginAttempt(null), now))
        // The durable claim is *still* PREPARED: the mutation boundary has not been entered.
        assertEquals(ReviewCommitStatus.PREPARED, claimed.status)
        assertEquals(ReviewCommitPhase.INTENT_PERSISTED, claimed.phase)
        assertEquals(1, claimed.attemptCount)
        assertNull(claimed.submittedAtEpochMs)

        val entered = assertIsApplied(ReviewCommitTransitions.transition(
            claimed, ReviewCommitTransition.EnterMutationBoundary, now + 1))
        assertEquals(ReviewCommitStatus.SUBMITTING, entered.status)
        assertEquals(ReviewCommitPhase.MUTATION_BOUNDARY_ENTERED, entered.phase)
        assertEquals(1, entered.attemptCount)
        assertEquals(now + 1, entered.submittedAtEpochMs)
        assertTrue(ReviewCommitTransitions.allowed(claimed, entered.copy(version = 1)))
    }

    @Test fun `submitting_commit_can_become_committed`() {
        val entered = submitted()
        val result = BackendCommitResult.ConfirmedCommitted()
        val proof = ReviewCommitTransitions.responseEvidence(entered, result)!!
        val received = assertIsApplied(ReviewCommitTransitions.transition(
            entered, ReviewCommitTransition.BackendResponseReceived(result), now))
        assertEquals(ReviewCommitPhase.BACKEND_RESPONSE_RECEIVED, received.phase)
        assertEquals(ReviewCommitStatus.SUBMITTING, received.status)
        assertEquals(proof, received.response)
        assertNull(received.committedRating)

        val committed = assertIsApplied(ReviewCommitTransitions.transition(received,
            ReviewCommitTransition.BackendCommitted(result, ReviewCommitResolution.BACKEND_CONFIRMED), now + 1))
        assertEquals(ReviewCommitStatus.COMMITTED, committed.status)
        assertEquals(ReviewCommitPhase.FINAL_STATUS_PERSISTED, committed.phase)
        assertEquals(Rating.GOOD, committed.committedRating)
        assertNull(committed.failure)
    }

    @Test fun `submitting_commit_can_become_retry_allowed`() {
        // The boundary was entered, so only an authoritative "not committed" fact may move it.
        val entered = submitted()
        val retryAllowed = apply(entered,
            ReviewCommitTransition.BackendConfirmedNoMutation(
                reason = AnkiError.ProviderUnavailable(),
                resolution = ReviewCommitResolution.BACKEND_NOT_APPLIED), now)!!
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, retryAllowed.status)
        assertEquals(ReviewCommitPhase.FINAL_STATUS_PERSISTED, retryAllowed.phase)
        assertNotNull(retryAllowed.failure)
        assertNull(retryAllowed.committedRating)
        assertTrue(ReviewCommitTransitions.allowed(entered, retryAllowed.copy(version = 1)))
    }

    @Test fun `submitting_commit_can_become_ambiguous`() {
        val entered = submitted()
        val ambiguous = apply(entered, ReviewCommitTransition.BackendOutcomeUnknown(
            category = "lost_response", resolution = ReviewCommitResolution.BACKEND_AMBIGUOUS), now)!!
        assertEquals(ReviewCommitStatus.AMBIGUOUS, ambiguous.status)
        assertEquals(ReviewCommitPhase.FINAL_STATUS_PERSISTED, ambiguous.phase)
        assertNotNull(ambiguous.failure)
        assertNull(ambiguous.committedRating)
        assertTrue(ReviewCommitTransitions.allowed(entered, ambiguous.copy(version = 1)))
    }

    @Test fun `retry_allowed_commit_can_be_prepared_again`() {
        val retryAllowed = apply(submitted(),
            ReviewCommitTransition.BackendConfirmedNoMutation(category = "preflight_refused"), now)!!
            .copy(version = 1)
        // RETRY_ALLOWED → PREPARED, never straight to SUBMITTING (INV-11B-09).
        val prepared = apply(retryAllowed, ReviewCommitTransition.BeginRetry, now + 1)!!
        assertEquals(ReviewCommitStatus.PREPARED, prepared.status)
        assertEquals(ReviewCommitPhase.INTENT_PERSISTED, prepared.phase)
        assertNull(prepared.failure)
        assertNull(prepared.response)
        assertNull(prepared.submittedAtEpochMs)
        assertEquals(retryAllowed.attemptCount, prepared.attemptCount)
        assertEquals(retryAllowed.commitId, prepared.commitId)
        assertEquals(retryAllowed.rating, prepared.rating)
        assertNull("RETRY_ALLOWED may never jump to SUBMITTING",
            apply(retryAllowed, ReviewCommitTransition.EnterMutationBoundary, now + 1))
    }

    @Test fun `ambiguous_commit_cannot_retry_directly`() {
        val ambiguous = apply(submitted(), ReviewCommitTransition.BackendOutcomeUnknown(
            category = "lost_response", resolution = ReviewCommitResolution.BACKEND_AMBIGUOUS), now)!!
        // No command other than reconciliation evidence may move it, and never towards a submission.
        assertNull(apply(ambiguous, ReviewCommitTransition.BeginRetry, now))
        assertNull(apply(ambiguous, ReviewCommitTransition.BeginAttempt(null), now))
        assertNull(apply(ambiguous, ReviewCommitTransition.EnterMutationBoundary, now))
        assertNull(apply(ambiguous, ReviewCommitTransition.BackendConfirmedNoMutation(category = "invented"), now))
        for (phase in ReviewCommitPhase.values()) {
            val direct = ambiguous.copy(status = ReviewCommitStatus.SUBMITTING, phase = phase,
                attemptCount = ambiguous.attemptCount + 1, version = 1, failure = null,
                response = if (phase == ReviewCommitPhase.BACKEND_RESPONSE_RECEIVED)
                    CommitResponseEvidence(CommitResponseKind.OUTCOME_UNKNOWN,
                        ReviewCommitFailure("invented")) else null)
            assertFalse("AMBIGUOUS → SUBMITTING/$phase is forbidden",
                ReviewCommitTransitions.allowed(ambiguous, direct))
        }
        // Reconciliation evidence is the only way out.
        val proven = apply(ambiguous, ReviewCommitTransition.ReconciliationConfirmedNotCommitted, now + 1)!!
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, proven.status)
        assertTrue(ReviewCommitTransitions.allowed(ambiguous, proven.copy(version = 1)))
    }

    @Test fun `committed_commit_is_terminal`() {
        val committed = record(ReviewCommitStatus.COMMITTED, ReviewCommitPhase.FINAL_STATUS_PERSISTED,
            response = CommitResponseEvidence(CommitResponseKind.CONFIRMED_COMMITTED))
        for (command in listOf(
            ReviewCommitTransition.EnterMutationBoundary,
            ReviewCommitTransition.BeginAttempt(null),
            ReviewCommitTransition.BeginRetry,
            ReviewCommitTransition.BackendResponseReceived(BackendCommitResult.ConfirmedCommitted()),
            ReviewCommitTransition.BackendCommitted(BackendCommitResult.ConfirmedCommitted(), "again"),
            ReviewCommitTransition.BackendConfirmedNoMutation(category = "late"),
            ReviewCommitTransition.BackendOutcomeUnknown(category = "late"),
            ReviewCommitTransition.ReconciliationConfirmedCommitted(),
            ReviewCommitTransition.ReconciliationConfirmedNotCommitted,
            ReviewCommitTransition.ReconciliationInconclusive("late")
        )) {
            assertNull("$command must not move a COMMITTED record", apply(committed, command, now))
        }
        // Metadata-only notes are the only thing a COMMITTED record accepts.
        assertNotNull(apply(committed, ReviewCommitTransition.NoteAbandoned(9_000), now))
        assertNotNull(apply(committed, ReviewCommitTransition.Acknowledge, now))
    }

    // ------------------------------------------------------------ additional required coverage

    @Test fun `a prepared record may only claim an attempt, never reach a terminal status`() {
        val fresh = record(ReviewCommitStatus.PREPARED)
        assertNull("no terminal jump from PREPARED",
            apply(fresh, ReviewCommitTransition.BackendCommitted(BackendCommitResult.ConfirmedCommitted(), "skip"), now))
        assertNull("the boundary needs a claimed attempt first",
            apply(fresh, ReviewCommitTransition.EnterMutationBoundary, now))
        assertNotNull(apply(fresh, ReviewCommitTransition.BeginAttempt(
            ReviewCommitEvidence("test-v1", "baseline=0")), now))
    }

    @Test fun `a backend refusal before the boundary is proven not applied, never ambiguous`() {
        val claimed = assertIsApplied(ReviewCommitTransitions.transition(
            record(ReviewCommitStatus.PREPARED), ReviewCommitTransition.BeginAttempt(null), now))
        val refused = apply(claimed, ReviewCommitTransition.BackendConfirmedNoMutation(
            category = "preflight_refused", resolution = ReviewCommitResolution.REFUSED_BEFORE_DISPATCH), now)!!
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, refused.status)
        assertEquals(ReviewCommitResolution.REFUSED_BEFORE_DISPATCH, refused.resolution)
    }

    @Test fun `a backend that succeeds without entering the boundary is fail-closed as ambiguous`() {
        val claimed = assertIsApplied(ReviewCommitTransitions.transition(
            record(ReviewCommitStatus.PREPARED), ReviewCommitTransition.BeginAttempt(null), now))
        // Claiming success, or an unknown outcome, without crossing the boundary violates the
        // contract: the only safe reading is "we cannot prove what happened".
        for (result in listOf(
            BackendCommitResult.ConfirmedCommitted(),
            BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("no_boundary"))
        )) {
            assertNull("a backend result without the boundary callback is never accepted as a response",
                apply(claimed, ReviewCommitTransition.BackendResponseReceived(result), now))
        }
    }

    @Test fun `reconciliation evidence decides an ambiguous commit in both directions`() {
        val ambiguous = apply(submitted(), ReviewCommitTransition.BackendOutcomeUnknown(
            category = "lost_response", resolution = ReviewCommitResolution.BACKEND_AMBIGUOUS), now)!!
        // Directly assigning COMMITTED is not a transition the engine offers at all.
        assertNull(apply(ambiguous,
            ReviewCommitTransition.BackendCommitted(BackendCommitResult.ConfirmedCommitted(), "fabricated"), now))
        val reconciled = apply(ambiguous, ReviewCommitTransition.ReconciliationConfirmedCommitted(
            CommitReceipt(backendId, "receipt", Rating.GOOD)), now + 1)!!
        assertEquals(ReviewCommitStatus.COMMITTED, reconciled.status)
        assertEquals(Rating.GOOD, reconciled.committedRating)
        assertEquals(ReviewCommitResolution.RECONCILED_APPLIED, reconciled.resolution)

        val notApplied = apply(ambiguous, ReviewCommitTransition.ReconciliationConfirmedNotCommitted, now + 1)!!
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, notApplied.status)
        assertEquals(ReviewCommitResolution.RECONCILED_NOT_APPLIED, notApplied.resolution)
    }

    @Test fun `inconclusive reconciliation keeps the commit ambiguous`() {
        val ambiguous = apply(submitted(), ReviewCommitTransition.BackendOutcomeUnknown(
            category = "lost_response", resolution = ReviewCommitResolution.BACKEND_AMBIGUOUS), now)!!
        val stillUnknown = apply(ambiguous,
            ReviewCommitTransition.ReconciliationInconclusive("reconciliation_inconclusive"), now + 1)!!
        assertEquals(ReviewCommitStatus.AMBIGUOUS, stillUnknown.status)
        assertEquals(ReviewCommitResolution.RECONCILIATION_INCONCLUSIVE, stillUnknown.resolution)
        assertEquals("reconciliation_inconclusive", stillUnknown.failure!!.category)
    }

    @Test fun `COMMITTED cannot be downgraded and a second attempt cannot be invented`() {
        val committed = record(ReviewCommitStatus.COMMITTED, ReviewCommitPhase.FINAL_STATUS_PERSISTED,
            response = CommitResponseEvidence(CommitResponseKind.CONFIRMED_COMMITTED))
        val downgraded = committed.copy(status = ReviewCommitStatus.RETRY_ALLOWED,
            committedRating = null, response = null, failure = ReviewCommitFailure("late"),
            attemptCount = 2, version = 1)
        assertFalse(ReviewCommitTransitions.allowed(committed, downgraded))
    }

    @Test fun `an attempt count never moves backwards`() {
        val claimed = assertIsApplied(ReviewCommitTransitions.transition(
            record(ReviewCommitStatus.PREPARED), ReviewCommitTransition.BeginAttempt(null), now))
        val two = claimed.copy(attemptCount = 2, version = 1)
        val backwards = two.copy(attemptCount = 1, version = 2)
        assertFalse(ReviewCommitTransitions.allowed(two, backwards))
    }

    @Test fun `the response evidence never fabricates a committed receipt for a different rating`() {
        val entered = submitted()
        val foreign = BackendCommitResult.ConfirmedCommitted(
            receipt = CommitReceipt(backendId = AnkiBackendId.PcAgent("other"),
                backendReceiptId = "r-1", committedRating = Rating.GOOD))
        assertNull("a foreign backend receipt is not evidence for this commit",
            ReviewCommitTransitions.responseEvidence(entered, foreign))
        val wrongRating = BackendCommitResult.ConfirmedCommitted(
            receipt = CommitReceipt(backendId, "r-1", Rating.HARD))
        assertNull("a receipt for another rating is not evidence for this commit",
            ReviewCommitTransitions.responseEvidence(entered, wrongRating))
    }

    @Test fun `recorded receipt can be read before terminal finalization without fabricating committedRating`() {
        val entered = submitted()
        val proof = confirmedCommitted(entered, Rating.GOOD)
        val received = assertIsApplied(ReviewCommitTransitions.transition(
            entered, ReviewCommitTransition.BackendResponseReceived(proof), now))
        assertNull(received.committedRating)
        assertEquals(Rating.GOOD, received.receipt?.committedRating)
    }

    @Test fun `every outcome unknown answer is recorded as unknown and never as not applied`() {
        val entered = submitted()
        val proof = ReviewCommitTransitions.responseEvidence(
            entered, BackendCommitResult.OutcomeUnknown(AnkiError.QueryFailure("timeout")))
        assertEquals(CommitResponseKind.OUTCOME_UNKNOWN, proof!!.kind)
        assertNotNull(proof.failure)
    }

    @Test fun `an illegal transition is rejected with a typed reason before any status change`() {
        val fresh = record(ReviewCommitStatus.PREPARED)
        assertEquals(ReviewCommitTransitionRejection.ILLEGAL_STATUS_TRANSITION,
            (ReviewCommitTransitions.transition(fresh, ReviewCommitTransition.EnterMutationBoundary, now)
                as ReviewCommitTransitionResult.Rejected).reason)
        // And no write may reopen a transaction with a different identity.
        val other = fresh.copy(
            commitId = ReviewCommitId(backendId, "study-1", ReviewTurnId("turn-2")), version = 1)
        assertFalse("identity is part of the write gate, not only of the status machine",
            ReviewCommitTransitions.validWrite(fresh, other))
    }

    @Test fun `metadata-only notes keep the status and never mean not committed`() {
        val committed = record(ReviewCommitStatus.COMMITTED, ReviewCommitPhase.FINAL_STATUS_PERSISTED,
            response = CommitResponseEvidence(CommitResponseKind.CONFIRMED_COMMITTED))
        val noted = apply(committed, ReviewCommitTransition.NoteAbandoned(9_000), now)!!
        assertEquals(ReviewCommitStatus.COMMITTED, noted.status)
        assertTrue(ReviewCommitTransitions.allowed(committed, noted.copy(version = committed.version + 1)))
    }

    @Test fun `identity fields are immutable even when collection identity was initially unknown`() {
        val before = record(ReviewCommitStatus.PREPARED)
        val changedCollection = before.copy(
            collectionRef = AnkiCollectionIdentity(backendId, "collection"), version = before.version + 1)
        assertFalse(ReviewCommitTransitions.validWrite(before, changedCollection))
        val changedDeck = before.copy(
            deckRef = AnkiDeckRef(backendId, "deck", "collection"), version = before.version + 1)
        assertFalse(ReviewCommitTransitions.validWrite(before, changedDeck))
        // A legal status move keeps the identity untouched, so it is admitted.
        val claimed = apply(before, ReviewCommitTransition.BeginAttempt(null), now)!!
        assertTrue(ReviewCommitTransitions.validWrite(before, claimed.copy(version = before.version + 1)))
    }

    @Test fun `a receipt for a different rating is a typed backend-result rejection`() {
        val entered = submitted()
        val result = confirmedCommitted(entered, Rating.HARD)
        val rejection = ReviewCommitTransitions.transition(
            entered, ReviewCommitTransition.BackendResponseReceived(result), now)
        assertEquals(ReviewCommitTransitionRejection.INVALID_BACKEND_RESULT,
            (rejection as ReviewCommitTransitionResult.Rejected).reason)
    }

    @Test fun `every backend result maps to exactly one durable status`() {
        assertEquals(ReviewCommitStatus.COMMITTED, BackendCommitResult.ConfirmedCommitted().toReviewCommitStatus())
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED,
            BackendCommitResult.ConfirmedNotCommitted(AnkiError.StaleTurn()).toReviewCommitStatus())
        assertEquals(ReviewCommitStatus.AMBIGUOUS,
            BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("x")).toReviewCommitStatus())
    }

    @Test fun `reconciliation proof is only ever meaningful for an ambiguous commit`() {
        // Guards against a coordinator that "reconciles" a record into a convenient status.
        val prepared = record(ReviewCommitStatus.PREPARED)
        assertNull(apply(prepared, ReviewCommitTransition.ReconciliationConfirmedCommitted(), now))
        assertNull(apply(prepared, ReviewCommitTransition.ReconciliationConfirmedNotCommitted, now))
        assertNull(apply(prepared, ReviewCommitTransition.ReconciliationInconclusive("x"), now))
    }

    private fun confirmedCommitted(record: ReviewCommitRecord, rating: Rating) =
        BackendCommitResult.ConfirmedCommitted(
            receipt = CommitReceipt(record.backendId, "receipt", rating))
}
