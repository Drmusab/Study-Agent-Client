package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.InMemoryReviewerActionStore
import com.studyagent.client.core.anki.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 13 PART III VERIFICATION 3-13 — the coordinator, driven directly against the real ledger and
 * the real fake backend.
 *
 * These are transaction-level assertions: durable ordering (AUDIT 4), exactly-one mutation per
 * logical action, response-loss ambiguity, restart behaviour and the rating/action exclusion. The
 * study-layer projection (turn stays open, next card only after a confirmed turn-invalidating
 * action) is asserted in [ReviewerActionMachineTest].
 */
class ReviewerActionCoordinatorTest {

    private val backend = AnkiBackendId.AnkiDroidLocal
    private val session = "study-13"
    private val card = AnkiCardRef(backend, cardId = "42", collectionKey = "collection")

    private fun request(
        action: ReviewerAction = ReviewerAction.BuryCard,
        turn: String = "turn-1",
        sessionId: String = session
    ) = ReviewerActionRequest(
        sessionId = sessionId,
        turnId = ReviewTurnId(turn),
        cardRef = card,
        action = action,
        backendId = backend,
        collectionRef = AnkiCollectionIdentity(backend, "collection"),
        semantics = ReviewerActionSemantics(
            supportsIdempotentReplay = true, supportsAuthoritativeReconciliation = true)
    )

    private class Fixture(
        val store: InMemoryReviewerActionStore = InMemoryReviewerActionStore()
    ) {
        var now = 1_000L
        val clock: () -> Long = { now }
        // The fake models bury/suspend *and* flag writes here, so the whole family can be driven
        // through the coordinator; its per-action semantics still mirror the pinned contract rules
        // (bury/suspend verified in both directions, a flag not reconcilable).
        val fake = FakeAnkiBackend(
            id = AnkiBackendId.AnkiDroidLocal,
            decks = emptyList(),
            cards = emptyList(),
            initialCapabilities = FakeAnkiBackend.REVIEW_ACTION_CAPABILITIES.copy(flags = true),
            instanceId = "gate13-coordinator",
            flagWrites = true
        )
        var ledger = store.restart(clock)
        val registry = AnkiBackendRegistry(listOf(fake))
        var coordinator = DefaultReviewerActionCoordinator(ledger, null, registry, clock)

        fun restart() {
            ledger = store.restart(clock)
            coordinator = DefaultReviewerActionCoordinator(ledger, null, registry, clock)
        }
    }

    /**
     * Records the durable action status **at the moment the backend is about to cross the
     * mutation boundary** — the ordering assertion of AUDIT 4, taken from inside the boundary.
     */
    private class BoundaryObserver(
        private val delegate: AnkiBackend,
        private val store: InMemoryReviewerActionStore
    ) : AnkiBackend by delegate {
        val statusAtEntry = mutableListOf<ReviewerActionStatus?>()

        override suspend fun performReviewerAction(
            cardRef: AnkiCardRef,
            action: ReviewerAction,
            mutationEntry: suspend () -> Boolean
        ): ReviewerActionBackendResult = delegate.performReviewerAction(cardRef, action) {
            val entered = mutationEntry()
            // Read the disk at the instant the backend was authorized to mutate: the durable
            // `SUBMITTING` marker must already be there (INV-13-08, AUDIT 4).
            statusAtEntry += store.durableRecords().singleOrNull()?.status
            entered
        }
    }

    // ------------------------------------------------------------------ VERIFICATION 3/4/5

    @Test
    fun `flag success is applied, durable and carries the flag receipt`() = runBlocking {
        val fixture = Fixture()
        val flag = ReviewerAction.SetFlag(AnkiFlag.RED)
        val outcome = fixture.coordinator.perform(request(flag))
        assertTrue("expected APPLIED, got $outcome", outcome is ReviewerActionOutcome.Applied)
        val record = (outcome as ReviewerActionOutcome.Applied).record
        assertEquals(ReviewerActionStatus.APPLIED, record.status)
        assertEquals(flag, record.action)
        assertEquals(AnkiFlag.RED, record.backendReceipt?.flag)
        // The durable record and the returned outcome are the same truth.
        assertEquals(ReviewerActionStatus.APPLIED, fixture.store.durableRecords().single().status)
    }

    @Test
    fun `bury success crosses the boundary exactly once and is durable`() = runBlocking {
        val fixture = Fixture()
        val outcome = fixture.coordinator.perform(request(ReviewerAction.BuryCard))
        assertEquals(ReviewerActionStatus.APPLIED, outcome.statusOrNull)
        assertEquals(1, fixture.fake.reviewerActionInvocations)
        assertEquals(1, fixture.fake.reviewerActionBoundaryCrossings)
        val durable = fixture.store.durableRecords().single()
        assertEquals(ReviewerActionStatus.APPLIED, durable.status)
        assertEquals(1, durable.attemptCount)
    }

    @Test
    fun `suspend success is the same transaction shape as bury`() = runBlocking {
        val fixture = Fixture()
        val outcome = fixture.coordinator.perform(request(ReviewerAction.SuspendCard))
        assertEquals(ReviewerActionStatus.APPLIED, outcome.statusOrNull)
        assertEquals(1, fixture.fake.reviewerActionBoundaryCrossings)
        assertEquals(ReviewerAction.SuspendCard, fixture.store.durableRecords().single().action)
    }

    @Test
    fun `durable ordering - submitting is on disk before the backend crosses the boundary`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val observer = BoundaryObserver(
            FakeAnkiBackend(
                id = backend,
                initialCapabilities = FakeAnkiBackend.REVIEW_ACTION_CAPABILITIES.copy(flags = true),
                instanceId = "gate13-ordering",
                flagWrites = true
            ),
            store
        )
        var now = 1_000L
        val ledger = store.restart(clock = { now })
        val coordinator = DefaultReviewerActionCoordinator(
            ledger, null, AnkiBackendRegistry(listOf(observer)), { now })

        val outcome = coordinator.perform(request())
        assertEquals(ReviewerActionStatus.APPLIED, outcome.statusOrNull)
        assertEquals(
            "the durable SUBMITTING marker must precede the real backend mutation (INV-13-08)",
            listOf(ReviewerActionStatus.SUBMITTING), observer.statusAtEntry
        )
        // The persistence log proves the same order: PREPARED first, SUBMITTING second.
        val persistedStatuses = store.writes.map { raw ->
            ReviewerActionLedgerCodec.decode(raw).let { decoded ->
                (decoded as ReviewerActionLedgerCodec.Decoded.Records).records.single().status
            }
        }
        assertEquals(
            listOf(ReviewerActionStatus.PREPARED, ReviewerActionStatus.SUBMITTING, ReviewerActionStatus.APPLIED),
            persistedStatuses
        )
    }

    // ------------------------------------------------------------------- VERIFICATION 6/7/8

    @Test
    fun `a lost bury response is ambiguous and never replayed`() = runBlocking {
        val fixture = Fixture()
        fixture.fake.scriptActions(
            FakeAnkiBackend.ActionStep(ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("timeout")))
        )
        val outcome = fixture.coordinator.perform(request(ReviewerAction.BuryCard))
        assertTrue("expected AMBIGUOUS, got $outcome", outcome is ReviewerActionOutcome.Ambiguous)
        assertEquals(ReviewerActionStatus.AMBIGUOUS, fixture.store.durableRecords().single().status)
        assertEquals("exactly one backend call", 1, fixture.fake.reviewerActionInvocations)

        // A second perform() for the same logical action answers from the ledger: no replay.
        val again = fixture.coordinator.perform(request(ReviewerAction.BuryCard))
        assertTrue(again is ReviewerActionOutcome.Ambiguous)
        assertEquals("never replayed", 1, fixture.fake.reviewerActionInvocations)

        // Recovery with unresolved evidence stays AMBIGUOUS and still does not touch the backend.
        val recovered = fixture.coordinator.recover(request(ReviewerAction.BuryCard).actionId)
        assertTrue(recovered is ReviewerActionRecoveryOutcome.Recovered)
        assertEquals(
            ReviewerActionRecoveryAction.RemainBlocked,
            (recovered as ReviewerActionRecoveryOutcome.Recovered).decision
        )
        assertEquals(ReviewerActionStatus.AMBIGUOUS, recovered.record.status)
        assertEquals("recovery is read-only", 1, fixture.fake.reviewerActionInvocations)
        assertEquals(1, fixture.fake.reviewerActionReconciliationCount)
    }

    @Test
    fun `a lost suspend response is ambiguous too`() = runBlocking {
        val fixture = Fixture()
        fixture.fake.scriptActions(
            FakeAnkiBackend.ActionStep(ReviewerActionBackendResult.OutcomeUnknown(AnkiError.Unknown("provider_died")))
        )
        val outcome = fixture.coordinator.perform(request(ReviewerAction.SuspendCard))
        assertTrue(outcome is ReviewerActionOutcome.Ambiguous)
        assertEquals(ReviewerActionStatus.AMBIGUOUS, fixture.store.durableRecords().single().status)
    }

    @Test
    fun `a lost flag response is ambiguous because the backend claims no reconciliation`() = runBlocking {
        val fixture = Fixture()
        fixture.fake.scriptActions(
            FakeAnkiBackend.ActionStep(ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost")))
        )
        val flag = ReviewerAction.SetFlag(AnkiFlag.BLUE)
        assertTrue(fixture.coordinator.perform(request(flag)) is ReviewerActionOutcome.Ambiguous)
        // The fake's flag semantics claim no idempotent replay and no reconciliation (§28/§29), so
        // recovery can only keep it unresolved.
        val recovered = fixture.coordinator.recover(request(flag).actionId)
        assertEquals(
            ReviewerActionRecoveryAction.RemainBlocked,
            (recovered as ReviewerActionRecoveryOutcome.Recovered).decision
        )
        assertEquals(1, fixture.fake.reviewerActionInvocations)
    }

    // --------------------------------------------------------------------- VERIFICATION 9/10

    @Test
    fun `restarting from submitting replays nothing and requires reconciliation`() = runBlocking {
        val fixture = Fixture()
        fixture.fake.scriptActions(
            FakeAnkiBackend.ActionStep(ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost")))
        )
        fixture.coordinator.perform(request(ReviewerAction.BuryCard))
        val before = fixture.fake.reviewerActionInvocations

        fixture.restart()
        val actionId = request(ReviewerAction.BuryCard).actionId
        val outcome = fixture.coordinator.perform(request(ReviewerAction.BuryCard))
        assertTrue("a restarted process must answer from the ledger", outcome is ReviewerActionOutcome.Ambiguous)
        assertEquals("no backend replay after restart", before, fixture.fake.reviewerActionInvocations)

        // Recovery reconciles read-only, and an unresolved answer keeps the record blocked.
        val recovered = fixture.coordinator.recover(actionId)
        assertTrue(recovered is ReviewerActionRecoveryOutcome.Recovered)
        assertEquals(ReviewerActionStatus.AMBIGUOUS, (recovered as ReviewerActionRecoveryOutcome.Recovered).record.status)
        assertEquals("reconciliation is a read", before + 0, fixture.fake.reviewerActionInvocations)
        assertEquals(1, fixture.fake.reviewerActionReconciliationCount)
    }

    @Test
    fun `reconciliation with positive evidence resolves an ambiguous bury to applied`() = runBlocking {
        val fixture = Fixture()
        val receipt = ReviewerActionReceipt(
            backendId = backend,
            actionKey = ReviewerAction.BuryCard.key,
            cardState = ReviewerCardState.fromQueue(ReviewerCardState.QUEUE_MANUALLY_BURIED),
            detail = "reconciled_by_card_state"
        )
        fixture.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost")),
                reconciliation = ReviewerActionReconciliationResult.ConfirmedApplied(receipt)
            )
        )
        fixture.coordinator.perform(request(ReviewerAction.BuryCard))
        val recovered = fixture.coordinator.recover(request(ReviewerAction.BuryCard).actionId)
        val recoveredRecord = (recovered as ReviewerActionRecoveryOutcome.Recovered)
        assertEquals(ReviewerActionRecoveryAction.ResumeApplied, recoveredRecord.decision)
        assertEquals(ReviewerActionStatus.APPLIED, recoveredRecord.record.status)
        assertEquals(ReviewerActionResolution.RECONCILED_APPLIED, recoveredRecord.record.resolution)
        assertEquals("reconciliation never mutates", 1, fixture.fake.reviewerActionBoundaryCrossings)
    }

    @Test
    fun `restarting from applied never replays and answers from the ledger`() = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.perform(request(ReviewerAction.BuryCard))
        val calls = fixture.fake.reviewerActionInvocations
        fixture.restart()
        val outcome = fixture.coordinator.perform(request(ReviewerAction.BuryCard))
        assertTrue(outcome is ReviewerActionOutcome.Applied)
        assertEquals("no replay of a confirmed action (§12)", calls, fixture.fake.reviewerActionInvocations)
        // A retry request for an applied action is answered from truth as well.
        assertTrue(fixture.coordinator.retry(request().actionId) is ReviewerActionOutcome.Applied)
        assertEquals(calls, fixture.fake.reviewerActionInvocations)
    }

    @Test
    fun `a restored prepared action may be retried with the same identity`() = runBlocking {
        val fixture = Fixture()
        // Crash after the durable PREPARED intent but before the boundary: model it by creating the
        // record directly (the coordinator only reaches the boundary through the backend callback).
        fixture.ledger.create(request(ReviewerAction.SuspendCard).toRecord(1_000L))
        fixture.restart()
        val actionId = request(ReviewerAction.SuspendCard).actionId
        val retried = fixture.coordinator.retry(actionId)
        assertEquals(ReviewerActionStatus.APPLIED, retried.statusOrNull)
        assertEquals(actionId, retried.actionId)
        assertEquals(1, fixture.fake.reviewerActionInvocations)
        assertEquals(actionId, fixture.store.durableRecords().single().actionId)
    }

    // ------------------------------------------------------------------- VERIFICATION 11/12/13

    @Test
    fun `a rating transaction for the turn blocks a reviewer action`() = runBlocking {
        val fixture = Fixture()
        val commitStore = com.studyagent.client.anki.fake.InMemoryReviewCommitStore()
        val commitLedger = commitStore.restart(clock = { 1_000L })
        val registry = AnkiBackendRegistry(listOf(fixture.fake))
        // A rating transaction exists for this turn (any status blocks: §23).
        commitLedger.prepare(CommitRatingRequest(
            commitId = ReviewCommitId(backend, session, ReviewTurnId("turn-1")),
            card = card,
            rating = com.studyagent.client.core.models.Rating.GOOD,
            ratedAtEpochMs = 1_000L
        ))
        val coordinator = DefaultReviewerActionCoordinator(fixture.ledger, commitLedger, registry, { 1_000L })
        val outcome = coordinator.perform(request(ReviewerAction.BuryCard))
        assertTrue("expected a refusal, got $outcome", outcome is ReviewerActionOutcome.Conflict)
        assertEquals(
            "rating_commit_prepared",
            (outcome as ReviewerActionOutcome.Conflict).error.let { (it as AnkiError.ActionConflict).detail }
        )
        assertNull("nothing may be recorded before the exclusion is checked", outcome.record)
        assertEquals(0, fixture.fake.reviewerActionInvocations)
        assertTrue(fixture.ledger.snapshot().isEmpty())
    }

    @Test
    fun `two different actions for one turn never both reach the backend`() = runBlocking {
        val fixture = Fixture()
        // The first action ends unresolved (it may already be applied), which is exactly the state
        // §15 protects: while a turn has an active action, no other action may begin.
        fixture.fake.scriptActions(
            FakeAnkiBackend.ActionStep(ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost")))
        )
        val first = fixture.coordinator.perform(request(ReviewerAction.BuryCard))
        assertEquals(ReviewerActionStatus.AMBIGUOUS, first.statusOrNull)
        val second = fixture.coordinator.perform(request(ReviewerAction.SuspendCard))
        assertTrue("the second action of one turn must be refused, got $second",
            second is ReviewerActionOutcome.Conflict)
        assertEquals(AnkiError.ActionConflict("action_slot_taken"),
            (second as ReviewerActionOutcome.Conflict).error)
        assertEquals("only the first action reached the backend", 1, fixture.fake.reviewerActionInvocations)
        assertEquals(1, fixture.ledger.snapshot().size)
    }

    @Test
    fun `concurrent different actions leave exactly one logical action behind`() = runBlocking {
        val fixture = Fixture()
        // Whatever gets in first stays unresolved, so the other request meets the §15 rule: one
        // active action per turn, at most one backend mutation.
        fixture.fake.scriptActions(
            FakeAnkiBackend.ActionStep(ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost")))
        )
        val outcomes = coroutineScope {
            listOf(
                async { fixture.coordinator.perform(request(ReviewerAction.BuryCard)) },
                async { fixture.coordinator.perform(request(ReviewerAction.SuspendCard)) }
            ).awaitAll()
        }
        assertEquals("exactly one logical action may exist", 1, fixture.ledger.snapshot().size)
        assertTrue("at most one backend mutation: ${fixture.fake.reviewerActionInvocations}",
            fixture.fake.reviewerActionInvocations <= 1)
        assertTrue("the loser must be told why: $outcomes", outcomes.any { it is ReviewerActionOutcome.Conflict })
    }

    @Test
    fun `one hundred concurrent identical requests are one logical action and at most one mutation`() = runBlocking {
        val fixture = Fixture()
        val outcomes = coroutineScope {
            (1..100).map { async { fixture.coordinator.perform(request(ReviewerAction.BuryCard)) } }.awaitAll()
        }
        assertEquals(1, fixture.ledger.snapshot().size)
        assertEquals(1, fixture.store.durableRecords().size)
        assertTrue("at most one backend mutation: ${fixture.fake.reviewerActionInvocations}",
            fixture.fake.reviewerActionInvocations <= 1)
        assertEquals(1, fixture.fake.reviewerActionBoundaryCrossings)
        assertTrue(
            "every caller must see the durable truth",
            outcomes.all { it.statusOrNull == ReviewerActionStatus.APPLIED || it is ReviewerActionOutcome.Conflict }
        )
    }

    // ----------------------------------------------------------------------------- refusals

    @Test
    fun `the frozen capability snapshot comes from the backend's own contract`() {
        val refusing = FakeAnkiBackend(
            initialCapabilities = FakeAnkiBackend.REVIEW_ACTION_CAPABILITIES,
            instanceId = "gate13-caps-none")
        val snapshot = refusing.reviewerActionCapabilities()
        assertEquals(
            listOf(ReviewerActionKind.BURY, ReviewerActionKind.SUSPEND),
            snapshot.availableKinds
        )
        assertTrue(snapshot.semanticsOf(ReviewerActionKind.BURY).supportsAuthoritativeReconciliation)
        assertFalse("a flag is not offered by a contract without a flag write",
            snapshot.supports(ReviewerAction.SetFlag(AnkiFlag.RED)))

        val writing = FakeAnkiBackend(
            initialCapabilities = FakeAnkiBackend.REVIEW_ACTION_CAPABILITIES.copy(flags = true),
            instanceId = "gate13-caps-flags",
            flagWrites = true)
        val withFlags = writing.reviewerActionCapabilities()
        assertTrue(withFlags.supports(ReviewerAction.SetFlag(AnkiFlag.RED)))
        assertFalse(
            "a flag that cannot be reconciled must not claim it",
            withFlags.semanticsOf(ReviewerActionKind.FLAG).supportsAuthoritativeReconciliation
        )
    }

    @Test
    fun `an unsupported action is refused before anything durable exists`() = runBlocking {
        // A backend that advertises no flag write (the pinned AnkiDroid contract today).
        val store = InMemoryReviewerActionStore()
        var now = 1_000L
        val ledger = store.restart(clock = { now })
        val fake = FakeAnkiBackend(
            id = backend,
            initialCapabilities = FakeAnkiBackend.REVIEW_ACTION_CAPABILITIES,
            instanceId = "gate13-no-flags"
        )
        val coordinator = DefaultReviewerActionCoordinator(
            ledger, null, AnkiBackendRegistry(listOf(fake)), { now })
        val outcome = coordinator.perform(request(ReviewerAction.SetFlag(AnkiFlag.RED)))
        assertTrue("expected a refusal, got $outcome", outcome is ReviewerActionOutcome.Conflict)
        val conflict = outcome as ReviewerActionOutcome.Conflict
        assertTrue(conflict.error is AnkiError.UnsupportedAction)
        assertNull(conflict.record)
        assertTrue("nothing durable may exist", ledger.snapshot().isEmpty())
        assertEquals(0, fake.reviewerActionInvocations)
    }

    @Test
    fun `a ledger that cannot record refuses the action before the backend is called`() = runBlocking {
        val fixture = Fixture()
        fixture.store.failNextWrites = 1
        val outcome = fixture.coordinator.perform(request(ReviewerAction.BuryCard))
        assertTrue(outcome is ReviewerActionOutcome.Conflict)
        assertTrue((outcome as ReviewerActionOutcome.Conflict).error is AnkiError.ActionLedgerUnavailable)
        assertNull(outcome.record)
        assertEquals("no dispatch without a durable record (INV-13-08)", 0, fixture.fake.reviewerActionInvocations)
    }

    @Test
    fun `a boundary write failure refuses the mutation and reports proven non-application`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        var now = 1_000L
        val ledger = store.restart(clock = { now })
        val fake = FakeAnkiBackend(
            id = backend,
            initialCapabilities = FakeAnkiBackend.REVIEW_ACTION_CAPABILITIES.copy(flags = true),
            instanceId = "gate13-boundary-failure",
            flagWrites = true
        )
        val coordinator = DefaultReviewerActionCoordinator(ledger, null, AnkiBackendRegistry(listOf(fake)), { now })
        // Arm exactly one failure for the write *after* the durable PREPARED intent: the SUBMITTING
        // marker cannot be persisted, so the backend must be refused the boundary.
        store.onDurableWrite = { store.failNextWrites = 1 }
        val outcome = coordinator.perform(request(ReviewerAction.BuryCard))
        assertTrue("expected a proven not-applied outcome, got $outcome",
            outcome is ReviewerActionOutcome.RetryAllowed)
        assertEquals(0, fake.reviewerActionBoundaryCrossings)
        // The durable record is still PREPARED: the boundary was never entered.
        assertEquals(ReviewerActionStatus.PREPARED, ledger.get(request().actionId)?.status)
    }

    @Test
    fun `an unreadable ledger is indeterminate, never no transaction`() = runBlocking {
        val store = InMemoryReviewerActionStore().apply { unreadable = true }
        var now = 1_000L
        val ledger = store.restart(clock = { now })
        val coordinator = DefaultReviewerActionCoordinator(
            ledger, null, AnkiBackendRegistry(listOf(FakeAnkiBackend(id = backend))), { now })
        val actionId = request().actionId
        assertTrue(coordinator.recover(actionId) is ReviewerActionRecoveryOutcome.Indeterminate)
        assertTrue(coordinator.perform(request()) is ReviewerActionOutcome.Conflict)
    }

    @Test
    fun `recovery of an unknown action is explicitly no transaction`() = runBlocking {
        val fixture = Fixture()
        val recovered = fixture.coordinator.recover(request().actionId)
        assertTrue(recovered is ReviewerActionRecoveryOutcome.NoTransaction)
    }

    @Test
    fun `an explicit retry of a proven-not-applied action uses the same action id`() = runBlocking {
        val fixture = Fixture()
        fixture.fake.scriptActions(
            // Step 1: the first attempt is refused. Step 2: the explicit retry applies.
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.ConfirmedNotApplied(AnkiError.QueryFailure("refused"))),
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.ConfirmedApplied(
                    ReviewerActionReceipt(backend, ReviewerAction.BuryCard.key, detail = "retried")))
        )
        val actionId = request(ReviewerAction.BuryCard).actionId
        val refused = fixture.coordinator.perform(request(ReviewerAction.BuryCard))
        assertTrue(refused is ReviewerActionOutcome.RetryAllowed)
        assertEquals(ReviewerActionStatus.RETRY_ALLOWED, fixture.store.durableRecords().single().status)

        val retried = fixture.coordinator.retry(actionId)
        assertEquals(ReviewerActionStatus.APPLIED, retried.statusOrNull)
        assertEquals("the retry reuses the identity (INV-13-11)", actionId, retried.actionId)
        assertEquals(actionId, fixture.store.durableRecords().single().actionId)
        assertEquals(2, fixture.fake.reviewerActionInvocations)
    }

    @Test
    fun `a retry from an applied action is refused without touching the backend`() = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.perform(request(ReviewerAction.BuryCard))
        val calls = fixture.fake.reviewerActionInvocations
        val retried = fixture.coordinator.retry(request().actionId)
        assertTrue(retried is ReviewerActionOutcome.Applied)
        assertEquals(calls, fixture.fake.reviewerActionInvocations)
    }
}
