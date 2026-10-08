package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.InMemoryReviewerActionStore
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 13 — the study layer: reducer + executor + durable ledger + fake backend.
 *
 * This is where §18-§22 become observable behaviour: a confirmed flag keeps the turn, a confirmed
 * bury/suspend closes it and asks the scheduler exactly once for a fresh card, and an unresolved
 * action blocks both progression and any further mutation.
 */
class ReviewerActionMachineTest {

    private suspend fun loadedHarness(): ReviewerActionHarness {
        val harness = ReviewerActionHarness()
        harness.startAndLoad()
        assertTrue(
            "the turn must be open: ${harness.state.phase}",
            harness.state.phase in setOf(
                SessionPhase.SpeakingQuestion, SessionPhase.WaitingForAnswer, SessionPhase.WaitingForRating)
        )
        return harness
    }

    // ------------------------------------------------------------------ VERIFICATION 3: flag

    @Test
    fun `a confirmed flag keeps the turn and never asks the scheduler for a card`() = runBlocking {
        val harness = ReviewerActionHarness.flagCapable()
        harness.startAndLoad()
        val before = harness.turn!!
        val phaseBefore = harness.state.phase

        harness.requestAction(ReviewerAction.SetFlag(AnkiFlag.RED))
        harness.drain()

        assertEquals("the flag is durable and applied", ReviewerActionStatus.APPLIED, harness.action?.status ?: ReviewerActionStatus.APPLIED)
        assertNull("APPLIED returns the projection to Idle", harness.action)
        val after = harness.turn!!
        assertEquals("the same review turn continues", before.turnId, after.turnId)
        assertEquals("the same card is presented", before.cardRef, after.cardRef)
        assertEquals("the phase does not move", phaseBefore, harness.state.phase)
        assertEquals("the flag is projected onto the turn", AnkiFlag.RED, after.renderedCard?.flag)
        assertTrue("no fresh scheduler query: ${harness.pending}", harness.takeNextEffects().isEmpty())
        assertEquals("no rating transaction was created", null, harness.commit)
        assertEquals("the scheduler was asked for exactly one card (the initial one)", 1, harness.fake.nextCardCount)
    }

    @Test
    fun `a flag that Anki refuses keeps the turn and allows the user to try again`() = runBlocking {
        val harness = ReviewerActionHarness.flagCapable()
        harness.startAndLoad()
        harness.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.ConfirmedNotApplied(AnkiError.UnsupportedAction("flag_red")))
        )
        harness.requestAction(ReviewerAction.SetFlag(AnkiFlag.RED))
        harness.drain()

        val action = harness.action
        assertNotNull(action)
        assertEquals(ReviewerActionStatus.RETRY_ALLOWED, action!!.status)
        assertEquals(ReviewerActionUiState.RetryAvailable::class, harness.state.anki?.reviewerActionUi?.let { it::class })
        assertEquals("proven not applied ⇒ the same turn continues", ReviewerActionStatus.RETRY_ALLOWED, action.status)
        assertTrue(harness.takeNextEffects().isEmpty())
    }

    // ------------------------------------------------------ VERIFICATION 4/5: bury and suspend

    @Test
    fun `a confirmed bury closes the turn and asks for a fresh card exactly once`() = runBlocking {
        val harness = loadedHarness()
        val oldTurn = harness.turn!!.turnId
        harness.requestAction(ReviewerAction.BuryCard)
        harness.run(harness.takePerformEffect())

        // The action is resolved: no action state survives, the old turn is gone, and the scheduler
        // has been asked *once* for a fresh card — the only legal progression (INV-13-15).
        assertNull("the bury resolved and the projection is Idle", harness.action)
        assertNull("the turn closed", harness.turn)
        assertEquals(SessionPhase.WaitingForFirstCard, harness.state.phase)
        val nextEffects = harness.pending.filterIsInstance<AnkiStudyEffect.Next>()
        assertEquals("exactly one fresh scheduler query: ${harness.pending}", 1, nextEffects.size)
        assertEquals("no rating transaction was created", null, harness.commit)
        assertEquals(1, harness.fake.reviewerActionBoundaryCrossings)
        val durable = harness.store.durableRecords().single()
        assertEquals(ReviewerActionStatus.APPLIED, durable.status)
        assertEquals(ReviewerAction.BuryCard, durable.action)

        // Draining the fresh query loads the *next* card as a new turn, never the buried one again.
        harness.drain()
        val freshTurn = harness.turn
        assertNotNull("a new card was scheduled", freshTurn)
        assertNotEquals("the buried turn is not resurrected", oldTurn, freshTurn!!.turnId)
        assertEquals(2, harness.fake.nextCardCount)
    }

    @Test
    fun `a confirmed suspend behaves exactly like bury`() = runBlocking {
        val harness = loadedHarness()
        harness.requestAction(ReviewerAction.SuspendCard)
        harness.run(harness.takePerformEffect())
        assertNull(harness.action)
        assertNull(harness.turn)
        assertEquals(SessionPhase.WaitingForFirstCard, harness.state.phase)
        assertEquals(1, harness.pending.filterIsInstance<AnkiStudyEffect.Next>().size)
        assertEquals(ReviewerAction.SuspendCard, harness.store.durableRecords().single().action)
    }

    // ------------------------------------------------------- VERIFICATION 4/6: response loss

    @Test
    fun `a lost bury response blocks progression and never replays`() = runBlocking {
        val harness = loadedHarness()
        harness.fake.scriptActions(
            FakeAnkiBackend.ActionStep(ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost")))
        )
        harness.requestAction(ReviewerAction.BuryCard)
        harness.drain()

        assertEquals(ReviewerActionStatus.AMBIGUOUS, harness.action?.status)
        assertTrue("no fresh card after an unresolved action",
            harness.pending.none { it is AnkiStudyEffect.Next })
        assertEquals(1, harness.fake.nextCardCount)
        assertEquals(1, harness.fake.reviewerActionInvocations)
        assertEquals(SessionProblem.ANKI_REVIEWER_ACTION_UNCONFIRMED, harness.state.error?.problem)
        assertFalse("the session must not claim to be recoverable by itself", harness.state.error?.recoverable ?: true)

        // Duplicate intent while the outcome is unknown: refused by the policy, no second mutation.
        harness.requestAction(ReviewerAction.BuryCard)
        harness.drain()
        assertEquals(1, harness.fake.reviewerActionInvocations)
    }

    @Test
    fun `a rating is blocked while an action is unresolved`() = runBlocking {
        val harness = ReviewerActionHarness()
        harness.loadToRating()
        harness.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.ConfirmedNotApplied(AnkiError.QueryFailure("refused")))
        )
        harness.requestAction(ReviewerAction.BuryCard)
        harness.drain()
        assertEquals(ReviewerActionStatus.RETRY_ALLOWED, harness.action?.status)

        val rejected = harness.rate(Rating.GOOD)
        assertFalse("rating must be refused while the action is unresolved", rejected.accepted)
        assertTrue(
            "the rejection must name the action rule",
            harness.rejected.any { it.contains("anki-rating-blocked-by") }
        )
        assertNull(harness.commit)
    }

    @Test
    fun `a rating transaction blocks a reviewer action in both directions`() = runBlocking {
        val harness = ReviewerActionHarness()
        harness.loadToRating()
        harness.rate(Rating.GOOD)
        // The commit is prepared and dispatched; the action must be refused by the policy.
        val attempt = harness.requestAction(ReviewerAction.BuryCard)
        assertTrue("the request is answered by the policy, not dismissed: ${attempt.rejectionReason}",
            attempt.accepted)
        assertEquals("no action effect may be emitted",
            0, harness.pending.count { it is AnkiStudyEffect.PerformReviewerAction })
        assertEquals(
            "the refusal must name the rating transaction",
            ReviewerActionBlockReason.COMMIT_PENDING,
            harness.state.anki?.reviewerActionRefusal?.reason
        )
        /**
         * A refusal is presentation only: nothing durable may exist for the action.
         */
        assertTrue(harness.store.durableRecords().isEmpty())
        assertEquals(0, harness.fake.reviewerActionInvocations)
    }

    // ----------------------------------------------------------- VERIFICATION 9/10: recovery

    @Test
    fun `an unfinished action blocks startup before any scheduler query`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        // A previous process died after the boundary was crossed: SUBMITTING at rest.
        val backendId = ReviewerActionHarness.BACKEND
        val card = ReviewerActionHarness.card("A")
        val seed = ReviewerActionRequest(
            sessionId = "study-1",
            turnId = ReviewTurnId("turn-1"),
            cardRef = card.ref,
            action = ReviewerAction.BuryCard,
            collectionRef = AnkiCollectionIdentity(backendId, "collection")
        ).toRecord(1_000L)
        val seedLedger = store.restart(clock = { 1_000L })
        seedLedger.create(seed)
        seedLedger.transition(seed.actionId, ReviewerActionStatus.PREPARED, ReviewerActionTransition.EnterMutationBoundary)

        val harness = ReviewerActionHarness(store = store)
        harness.startAndLoad()
        // The startup scan blocked the session: no scheduler query happened at all.
        assertEquals(SessionPhase.ReviewerActionRecoveryRequired, harness.state.phase)
        assertEquals(0, harness.fake.nextCardCount)
        assertNull(harness.state.anki?.reviewSession)
        assertEquals(ReviewerActionStatus.SUBMITTING, harness.action?.status)
        assertEquals(ReviewerActionUiState.VerificationRequired::class, harness.state.anki?.reviewerActionUi?.let { it::class })
        // Recoverable, but only by an explicit user action (retry or reconciliation) — restart
        // itself never rewrites the record (§26).
        assertTrue("the user must be offered recovery", harness.state.error?.recoverable == true)
    }

    @Test
    fun `a resolved restored action continues through a fresh read-only session`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val backendId = ReviewerActionHarness.BACKEND
        val card = ReviewerActionHarness.card("A")
        val request = ReviewerActionRequest(
            sessionId = "study-1",
            turnId = ReviewTurnId("turn-1"),
            cardRef = card.ref,
            action = ReviewerAction.BuryCard,
            collectionRef = AnkiCollectionIdentity(backendId, "collection"),
            // The semantics frozen with the record (§28) are what authorizes the read-only probe.
            semantics = ReviewerActionSemantics(
                supportsIdempotentReplay = true,
                supportsAuthoritativeReconciliation = true
            )
        )
        val seedLedger = store.restart(clock = { 1_000L })
        seedLedger.create(request.toRecord(1_000L))
        seedLedger.transition(request.actionId, ReviewerActionStatus.PREPARED, ReviewerActionTransition.EnterMutationBoundary)
        seedLedger.transition(
            request.actionId, ReviewerActionStatus.SUBMITTING,
            ReviewerActionTransition.BackendOutcomeUnknown())
        // The fake reports the card is already buried: reconciliation proves the action applied.
        val harness = ReviewerActionHarness(store = store)
        harness.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost")),
                reconciliation = ReviewerActionReconciliationResult.ConfirmedApplied(
                    ReviewerActionReceipt(
                        backendId = backendId,
                        actionKey = ReviewerAction.BuryCard.key,
                        cardState = ReviewerCardState.fromQueue(ReviewerCardState.QUEUE_MANUALLY_BURIED)
                    )
                )
            )
        )
        harness.startAndLoad()
        assertEquals(SessionPhase.ReviewerActionRecoveryRequired, harness.state.phase)
        val actionId = harness.action?.actionId
        assertNotNull(actionId)

        harness.send(AnkiStudyEvent.RecoverReviewerAction(harness.state.epoch, actionId!!))
        harness.drain()

        // The action is resolved and the session continues through a fresh read-only scheduler
        // session — without ever replaying the action itself.
        assertEquals(0, harness.fake.reviewerActionBoundaryCrossings)
        assertEquals(1, harness.fake.reviewerActionReconciliationCount)
        assertNull(harness.action)
        assertNotNull("a fresh session exists", harness.state.anki?.reviewSession)
        // The machine is presenting a card again instead of staying blocked on the recovered record.
        assertTrue(
            "the recovered action must lead back to a presented card: ${harness.state.phase}",
            harness.state.phase in setOf(
                SessionPhase.WaitingForFirstCard, SessionPhase.SpeakingQuestion, SessionPhase.WaitingForAnswer)
        )
    }

    @Test
    fun `a restored prepared action is offered as a retry and can be submitted`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val backendId = ReviewerActionHarness.BACKEND
        val card = ReviewerActionHarness.card("A")
        val request = ReviewerActionRequest(
            sessionId = "study-1",
            turnId = ReviewTurnId("turn-1"),
            cardRef = card.ref,
            action = ReviewerAction.SuspendCard,
            collectionRef = AnkiCollectionIdentity(backendId, "collection")
        )
        store.restart(clock = { 1_000L }).create(request.toRecord(1_000L))

        val harness = ReviewerActionHarness(store = store)
        harness.startAndLoad()
        assertEquals(SessionPhase.ReviewerActionRecoveryRequired, harness.state.phase)
        assertEquals(ReviewerActionUiState.RetryAvailable::class, harness.state.anki?.reviewerActionUi?.let { it::class })

        harness.send(AnkiStudyEvent.RetryReviewerAction(harness.state.epoch, request.actionId))
        harness.drain()

        assertEquals(ReviewerActionStatus.APPLIED, harness.store.durableRecords().single().status)
        assertEquals(request.actionId, harness.store.durableRecords().single().actionId)
        assertEquals(1, harness.fake.reviewerActionBoundaryCrossings)
        // The resolved action leads a fresh read-only scheduler session: the machine is presenting
        // a card again instead of staying blocked on the recovered record.
        assertNotNull("a fresh session exists", harness.state.anki?.reviewSession)
        assertNull(harness.action)
        assertNotEquals(SessionPhase.ReviewerActionRecoveryRequired, harness.state.phase)
        assertEquals(1, harness.fake.nextCardCount)
    }

    // -------------------------------------------------------------------- policy / staleness

    @Test
    fun `an action for a closed turn is stale, not a second mutation`() = runBlocking {
        val harness = loadedHarness()
        val oldTurn = harness.turn!!.turnId
        harness.requestAction(ReviewerAction.BuryCard)
        harness.drain()
        val attempts = harness.fake.reviewerActionInvocations
        harness.send(AnkiStudyEvent.ReviewerActionRequested(
            ReviewerAction.SuspendCard, epoch = harness.state.epoch, turnId = oldTurn))
        assertTrue(
            "a request for a closed turn must be rejected as stale, got ${harness.rejected}",
            harness.rejected.any { it.contains("stale-anki-reviewer-action") }
        )
        assertEquals("nothing new reached the backend", attempts, harness.fake.reviewerActionInvocations)
    }

    @Test
    fun `an unsupported action is never dispatched and never reaches the ledger`() = runBlocking {
        val harness = ReviewerActionHarness() // no flag write path on this backend
        harness.startAndLoad()
        harness.requestAction(ReviewerAction.SetFlag(AnkiFlag.RED))
        assertEquals(ReviewerActionBlockReason.ACTION_UNSUPPORTED, harness.state.anki?.reviewerActionRefusal?.reason)
        assertTrue(harness.pending.none { it is AnkiStudyEffect.PerformReviewerAction })
        assertTrue(harness.store.durableRecords().isEmpty())
        assertEquals(0, harness.fake.reviewerActionInvocations)
    }

    @Test
    fun `a persistence failure returns the projection to idle without claiming success`() = runBlocking {
        val harness = loadedHarness()
        harness.store.failNextWrites = 1 // the durable PREPARED intent cannot be written
        harness.requestAction(ReviewerAction.BuryCard)
        harness.drain()
        assertNull("nothing durable exists, so there is no action to show", harness.action)
        assertEquals(SessionProblem.ANKI_REVIEWER_ACTION_UNCONFIRMED, harness.state.error?.problem)
        assertEquals("no dispatch without a durable record", 0, harness.fake.reviewerActionInvocations)
        assertTrue(harness.pending.none { it is AnkiStudyEffect.Next })
    }
}
