package com.studyagent.client.anki

import com.studyagent.client.core.anki.*
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 13 PART III VERIFICATION 1/2 — the closed transition table.
 *
 * Every legal row of §10/§11 is exercised, and every §12/§13 forbidden edge is rejected *before* it
 * could reach a durable write. The engine is pure, so the matrix is asserted directly, without a
 * ledger or a backend.
 */
class ReviewerActionTransitionTest {

    private val backend = AnkiBackendId.AnkiDroidLocal
    private val session = "study-13"
    private val turn = ReviewTurnId("turn-1")
    private val card = AnkiCardRef(backend, cardId = "42", collectionKey = "collection")

    private fun request(action: ReviewerAction = ReviewerAction.BuryCard) = ReviewerActionRequest(
        sessionId = session,
        turnId = turn,
        cardRef = card,
        action = action,
        collectionRef = AnkiCollectionIdentity(backend, "collection"),
        semantics = ReviewerActionSemantics(
            supportsIdempotentReplay = true, supportsAuthoritativeReconciliation = true)
    )

    private fun prepared(action: ReviewerAction = ReviewerAction.BuryCard): ReviewerActionRecord =
        request(action).toRecord(1_000L)

    private fun apply(
        record: ReviewerActionRecord,
        transition: ReviewerActionTransition,
        now: Long = 2_000L
    ): ReviewerActionRecord {
        val result = ReviewerActionTransitions.transition(record, transition, now)
        assertTrue("expected $transition to be legal from ${record.status}", result is ReviewerActionTransitionResult.Applied)
        return (result as ReviewerActionTransitionResult.Applied).record
    }

    private fun reject(record: ReviewerActionRecord, transition: ReviewerActionTransition): ReviewerActionTransitionRejection {
        val result = ReviewerActionTransitions.transition(record, transition, 2_000L)
        assertTrue("expected $transition to be illegal from ${record.status}", result is ReviewerActionTransitionResult.Rejected)
        return (result as ReviewerActionTransitionResult.Rejected).reason
    }

    private fun submitting(action: ReviewerAction = ReviewerAction.BuryCard): ReviewerActionRecord =
        apply(prepared(action), ReviewerActionTransition.EnterMutationBoundary)

    private fun applied(action: ReviewerAction = ReviewerAction.BuryCard): ReviewerActionRecord =
        apply(submitting(action), ReviewerActionTransition.BackendConfirmedApplied(receipt(action)))

    private fun retryAllowed(action: ReviewerAction = ReviewerAction.BuryCard): ReviewerActionRecord =
        apply(submitting(action), ReviewerActionTransition.BackendConfirmedNotApplied())

    private fun ambiguous(action: ReviewerAction = ReviewerAction.BuryCard): ReviewerActionRecord =
        apply(submitting(action), ReviewerActionTransition.BackendOutcomeUnknown())

    private fun receipt(action: ReviewerAction = ReviewerAction.BuryCard) = ReviewerActionReceipt(
        backendId = backend,
        actionKey = action.key,
        cardState = ReviewerCardState.fromQueue(ReviewerCardState.QUEUE_MANUALLY_BURIED),
        detail = "confirmed"
    )

    // ------------------------------------------------------------ VERIFICATION 1: legal transitions

    @Test
    fun `prepared becomes submitting and stamps the mutation window`() {
        val record = apply(prepared(), ReviewerActionTransition.EnterMutationBoundary, now = 5_000L)
        assertEquals(ReviewerActionStatus.SUBMITTING, record.status)
        assertEquals(1, record.attemptCount)
        assertEquals(5_000L, record.submittedAtEpochMs)
        assertNull(record.resolvedAtEpochMs)
        assertNull(record.failure)
    }

    @Test
    fun `submitting becomes applied with the backend receipt`() {
        val record = apply(submitting(), ReviewerActionTransition.BackendConfirmedApplied(receipt()))
        assertEquals(ReviewerActionStatus.APPLIED, record.status)
        assertEquals(receipt(), record.backendReceipt)
        assertNull(record.failure)
        assertEquals(ReviewerActionResolution.BACKEND_CONFIRMED, record.resolution)
        assertNotNull(record.resolvedAtEpochMs)
    }

    @Test
    fun `submitting becomes retry allowed when non-application is proven`() {
        val record = apply(submitting(), ReviewerActionTransition.BackendConfirmedNotApplied(
            reason = AnkiError.CardNotFound(card)))
        assertEquals(ReviewerActionStatus.RETRY_ALLOWED, record.status)
        assertNotNull(record.failure)
        assertNull(record.backendReceipt)
    }

    @Test
    fun `submitting becomes ambiguous when the outcome is unknown`() {
        val record = apply(submitting(), ReviewerActionTransition.BackendOutcomeUnknown(
            reason = AnkiError.QueryFailure("timeout"), category = "action_timeout"))
        assertEquals(ReviewerActionStatus.AMBIGUOUS, record.status)
        assertEquals("action_timeout", record.failure?.category)
    }

    @Test
    fun `retry allowed returns to prepared with the same identity`() {
        val before = retryAllowed()
        val after = apply(before, ReviewerActionTransition.RetryRequested)
        assertEquals(ReviewerActionStatus.PREPARED, after.status)
        assertEquals(before.actionId, after.actionId)
        assertEquals(before.action, after.action)
        assertEquals(before.cardRef, after.cardRef)
        assertNull(after.failure)
        assertNull(after.submittedAtEpochMs)
        // The attempt counter survives the retry: the next boundary crossing counts the next attempt.
        assertEquals(before.attemptCount, after.attemptCount)
        assertEquals(2, apply(after, ReviewerActionTransition.EnterMutationBoundary).attemptCount)
    }

    @Test
    fun `ambiguous is resolved by authoritative reconciliation evidence`() {
        val appliedByEvidence = apply(ambiguous(), ReviewerActionTransition.ReconciliationConfirmedApplied(receipt()))
        assertEquals(ReviewerActionStatus.APPLIED, appliedByEvidence.status)
        assertEquals(ReviewerActionResolution.RECONCILED_APPLIED, appliedByEvidence.resolution)

        val notAppliedByEvidence = apply(ambiguous(), ReviewerActionTransition.ReconciliationConfirmedNotApplied)
        assertEquals(ReviewerActionStatus.RETRY_ALLOWED, notAppliedByEvidence.status)
        assertEquals(ReviewerActionResolution.RECONCILED_NOT_APPLIED, notAppliedByEvidence.resolution)
    }

    @Test
    fun `an inconclusive reconciliation leaves ambiguous ambiguous and only refreshes the reason`() {
        val before = ambiguous()
        val after = apply(before, ReviewerActionTransition.ReconciliationUnresolved("reconciliation_inconclusive"))
        assertEquals(ReviewerActionStatus.AMBIGUOUS, after.status)
        assertEquals("reconciliation_inconclusive", after.failure?.category)
        assertEquals(ReviewerActionResolution.RECONCILIATION_UNRESOLVED, after.resolution)
        // §11 last row: AMBIGUOUS → AMBIGUOUS is legal and is not an error.
        assertEquals(before.attemptCount, after.attemptCount)
    }

    @Test
    fun `a recovered submitting is normalized to ambiguous by an unresolved reconciliation`() {
        // §26: after process death a SUBMITTING record is reconciled, and if that proves nothing it
        // is normalized into explicit uncertainty rather than left looking unfinished.
        val after = apply(submitting(), ReviewerActionTransition.ReconciliationUnresolved("recovered_submitting"))
        assertEquals(ReviewerActionStatus.AMBIGUOUS, after.status)
        assertEquals("recovered_submitting", after.failure?.category)
    }

    @Test
    fun `a reconciliation can also confirm or deny a recovered submitting`() {
        val confirmed = apply(submitting(), ReviewerActionTransition.ReconciliationConfirmedApplied(receipt()))
        assertEquals(ReviewerActionStatus.APPLIED, confirmed.status)
        val denied = apply(submitting(), ReviewerActionTransition.ReconciliationConfirmedNotApplied)
        assertEquals(ReviewerActionStatus.RETRY_ALLOWED, denied.status)
    }

    // ---------------------------------------------------------- VERIFICATION 2: illegal transitions

    @Test
    fun `applied is terminal for every mutation event`() {
        val terminal = applied()
        val events = listOf(
            ReviewerActionTransition.EnterMutationBoundary,
            ReviewerActionTransition.BackendConfirmedApplied(receipt()),
            ReviewerActionTransition.BackendConfirmedNotApplied(),
            ReviewerActionTransition.BackendOutcomeUnknown(),
            ReviewerActionTransition.RetryRequested,
            ReviewerActionTransition.ReconciliationConfirmedApplied(receipt()),
            ReviewerActionTransition.ReconciliationConfirmedNotApplied,
            ReviewerActionTransition.ReconciliationUnresolved("late")
        )
        events.forEach { event ->
            assertEquals(
                "APPLIED must be terminal (§12) for $event",
                ReviewerActionTransitionRejection.APPLIED_TERMINAL,
                reject(terminal, event)
            )
        }
    }

    @Test
    fun `ambiguous can never retry or resubmit directly`() {
        val uncertain = ambiguous()
        assertEquals(
            ReviewerActionTransitionRejection.ILLEGAL_STATUS_TRANSITION,
            reject(uncertain, ReviewerActionTransition.EnterMutationBoundary))
        assertEquals(
            ReviewerActionTransitionRejection.ILLEGAL_STATUS_TRANSITION,
            reject(uncertain, ReviewerActionTransition.RetryRequested))
    }

    @Test
    fun `retry allowed can never go straight to submitting`() {
        assertEquals(
            ReviewerActionTransitionRejection.ILLEGAL_STATUS_TRANSITION,
            reject(retryAllowed(), ReviewerActionTransition.EnterMutationBoundary))
    }

    @Test
    fun `prepared cannot jump to applied without crossing the boundary`() {
        assertEquals(
            ReviewerActionTransitionRejection.ILLEGAL_STATUS_TRANSITION,
            reject(prepared(), ReviewerActionTransition.BackendConfirmedApplied(receipt())))
    }

    @Test
    fun `a receipt must name this backend and this action`() {
        val foreignBackend = ReviewerActionReceipt(
            backendId = AnkiBackendId.Fake("other"), actionKey = ReviewerAction.BuryCard.key)
        assertEquals(
            ReviewerActionTransitionRejection.INVALID_RECEIPT,
            reject(submitting(), ReviewerActionTransition.BackendConfirmedApplied(foreignBackend)))
        val foreignAction = ReviewerActionReceipt(
            backendId = backend, actionKey = ReviewerAction.SuspendCard.key)
        assertEquals(
            ReviewerActionTransitionRejection.INVALID_RECEIPT,
            reject(submitting(), ReviewerActionTransition.BackendConfirmedApplied(foreignAction)))
    }

    @Test
    fun `blank resolutions are refused instead of persisted`() {
        assertEquals(
            ReviewerActionTransitionRejection.INVALID_RESULT_RECORD,
            reject(submitting(), ReviewerActionTransition.BackendConfirmedApplied(receipt(), resolution = " ")))
        assertEquals(
            ReviewerActionTransitionRejection.INVALID_RESULT_RECORD,
            reject(ambiguous(), ReviewerActionTransition.ReconciliationUnresolved(" ")))
    }

    @Test
    fun `creation is not an unrestricted status transition`() {
        val submittingRecord = submitting()
        assertTrue(
            ReviewerActionTransitions.validateInitial(submittingRecord)
                is ReviewerActionTransitionResult.Rejected
        )
        assertTrue(
            ReviewerActionTransitions.validateInitial(prepared())
                is ReviewerActionTransitionResult.Applied
        )
    }

    // ------------------------------------------------------------------- §9 mapping is the only one

    @Test
    fun `the backend result to status mapping is exactly the normative table`() {
        assertEquals(
            ReviewerActionStatus.APPLIED,
            ReviewerActionBackendResult.ConfirmedApplied(receipt()).toStatus())
        assertEquals(
            ReviewerActionStatus.RETRY_ALLOWED,
            ReviewerActionBackendResult.ConfirmedNotApplied(AnkiError.CardNotFound(card)).toStatus())
        assertEquals(
            ReviewerActionStatus.AMBIGUOUS,
            ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("timeout")).toStatus())
    }

    // ------------------------------------------------------------------------- §22 next-card rule

    @Test
    fun `only a confirmed turn-invalidating action may open a fresh scheduler query`() {
        val matrix = listOf(
            Triple(ReviewerAction.SetFlag(AnkiFlag.RED), ReviewerActionStatus.APPLIED, false),
            Triple(ReviewerAction.BuryCard, ReviewerActionStatus.APPLIED, true),
            Triple(ReviewerAction.SuspendCard, ReviewerActionStatus.APPLIED, true),
            Triple(ReviewerAction.BuryCard, ReviewerActionStatus.PREPARED, false),
            Triple(ReviewerAction.BuryCard, ReviewerActionStatus.SUBMITTING, false),
            Triple(ReviewerAction.BuryCard, ReviewerActionStatus.RETRY_ALLOWED, false),
            Triple(ReviewerAction.BuryCard, ReviewerActionStatus.AMBIGUOUS, false),
            Triple(ReviewerAction.SuspendCard, ReviewerActionStatus.AMBIGUOUS, false)
        )
        matrix.forEach { (action, status, allowed) ->
            assertEquals(
                "nextCardAllowed(${action.key}, $status)",
                allowed, nextCardAllowed(action, status)
            )
        }
        // The classification itself lives on the action, never in a UI `when`.
        assertFalse(ReviewerAction.SetFlag(AnkiFlag.RED).invalidatesCurrentTurn)
        assertTrue(ReviewerAction.BuryCard.invalidatesCurrentTurn)
        assertTrue(ReviewerAction.SuspendCard.invalidatesCurrentTurn)
    }

    // ------------------------------------------------------------- identity is derived, not random

    @Test
    fun `action identity is stable for one logical action and distinct across actions and turns`() {
        val base = ReviewerActionId.of(backend, session, turn, ReviewerAction.BuryCard)
        assertEquals(base, ReviewerActionId.of(backend, session, turn, ReviewerAction.BuryCard))
        assertNotEquals(base, ReviewerActionId.of(backend, session, turn, ReviewerAction.SuspendCard))
        assertNotEquals(base, ReviewerActionId.of(backend, session, ReviewTurnId("turn-2"), ReviewerAction.BuryCard))
        assertNotEquals(base, ReviewerActionId.of(backend, "other-session", turn, ReviewerAction.BuryCard))
        assertNotEquals(
            base,
            ReviewerActionId.of(backend, session, turn, ReviewerAction.SetFlag(AnkiFlag.BLUE))
        )
        // A different flag value is a different logical action (§12).
        assertNotEquals(
            ReviewerActionId.of(backend, session, turn, ReviewerAction.SetFlag(AnkiFlag.RED)),
            ReviewerActionId.of(backend, session, turn, ReviewerAction.SetFlag(AnkiFlag.BLUE))
        )
    }
}
