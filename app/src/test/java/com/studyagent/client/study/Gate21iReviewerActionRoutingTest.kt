package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.InMemoryReviewerActionStore
import com.studyagent.client.anki.fake.ProcessDeathSimulation
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 21I PART III — reviewer-action routing, ownership, identity and stale-input safety.
 *
 * The topology is always "more than one backend", because that is the only way "the action went
 * somewhere else" is observable at all: A is the session's locked backend, B is registered and
 * ready (sometimes holding the *same* numeric card ids), and every assertion includes B's
 * counters. Nothing here re-tests GATE 13's arithmetic; each case pins a 21I invariant against the
 * real reducer, the real ledgers, the real executor and the real routing policy.
 */
class Gate21iReviewerActionRoutingTest {

    private val aId = AnkiBackendId.AnkiDroidLocal
    private val bId = AnkiBackendId.PcAgent("gate21i-b")

    /** The substitute backend: registered, ready, keyed, holding the *same* numeric card ids. */
    private fun backendB(vararg cardIds: String = arrayOf("123")) = FakeAnkiBackend(
        id = bId,
        decks = listOf(AnkiDeck(ReviewerActionHarness.deckFor(bId), "B Deck")),
        cards = cardIds.map { ReviewerActionHarness.card(it, bId) },
        initialCapabilities = FakeAnkiBackend.REVIEW_ACTION_CAPABILITIES,
        instanceId = "gate21i-b",
        authoritativeReviewerActionReconciliation = true
    ).apply {
        currentCollectionIdentity = AnkiCollectionIdentity(bId, ReviewerActionHarness.COLLECTION_KEY)
    }

    /**
     * A synthetic keyed A with an explicit historical-reconciliation contract. This is a policy
     * fixture, not the production AnkiDroid adapter (which is keyless and cannot reconcile OP-M3).
     */
    private fun keyedLocalHarness(b: AnkiBackend = backendB()) = ReviewerActionHarness(
        extraBackends = listOf(b),
        liveCollectionKey = ReviewerActionHarness.COLLECTION_KEY,
        authoritativeReviewerActionReconciliation = true
    )

    // ------------------------------------------------- VER-21I-03  bury on AnkiDroid (backend A)

    @Test
    fun `a bury bound to A executes on A only, with every B counter at zero`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()

        assertNull("APPLIED returns the projection to idle (GATE 13)", h.action)
        val record = h.store.durableRecords().single()
        assertEquals(aId, record.backendId)
        assertEquals(ReviewerActionStatus.APPLIED, record.status)
        assertEquals(1, h.fake.reviewerActionBoundaryCrossings)
        assertEquals("B must receive zero action calls", 0, b.reviewerActionInvocations)
        assertEquals("B must receive zero boundary crossings", 0, b.reviewerActionBoundaryCrossings)
        assertEquals("B must receive zero reconciliation reads", 0, b.reviewerActionReconciliationCount)
        assertEquals("B must receive zero next-card calls", 0, b.nextCardCount)
        assertEquals("B must receive zero commit calls", 0, b.physicalCommitCalls)
        assertEquals("B must receive zero deck reads", 0, b.getDecksCalls)
        assertEquals("B must receive zero hydration reads", 0, b.hydrateCalls)
    }

    // ------------------------------------------------------------------- VER-21I-02  flag on PC

    @Test
    fun `a flag bound to a keyed PC A executes on A only`() = runTest {
        val pcA = AnkiBackendId.PcAgent("gate21i-pca")
        val b = FakeAnkiBackend(
            id = AnkiBackendId.Fake("gate21i-pcb"),
            decks = listOf(AnkiDeck(ReviewerActionHarness.deckFor(AnkiBackendId.Fake("gate21i-pcb")), "B Deck")),
            cards = listOf(ReviewerActionHarness.card("123", AnkiBackendId.Fake("gate21i-pcb"))),
            instanceId = "gate21i-pcb"
        ).apply { currentCollectionIdentity = AnkiCollectionIdentity(AnkiBackendId.Fake("gate21i-pcb"), "collection") }
        val h = ReviewerActionHarness(
            backendId = pcA,
            mode = AnkiBackendMode.PC_AGENT,
            capabilities = FakeAnkiBackend.REVIEW_ACTION_CAPABILITIES.copy(flags = true),
            flagWrites = true,
            extraBackends = listOf(b)
        )
        h.loadToRating()
        h.requestAction(ReviewerAction.SetFlag(AnkiFlag.RED))
        h.drain()

        assertNull("APPLIED returns the projection to idle (GATE 13)", h.action)
        val record = h.store.durableRecords().single()
        assertEquals(pcA, record.backendId)
        assertEquals(ReviewerAction.SetFlag(AnkiFlag.RED), record.action)
        assertEquals(AnkiFlag.RED, record.backendReceipt?.flag)
        assertEquals(1, h.fake.reviewerActionBoundaryCrossings)
        assertEquals("the substitute receives zero flag calls", 0, b.reviewerActionInvocations)
        assertEquals(0, b.reviewerActionBoundaryCrossings)
        assertEquals(0, b.nextCardCount)
    }

    // ------------------------------------------------------------------- VER-21I-04  bury on PC

    /**
     * A *keyed* PC-owned session: unlike the keyless AnkiDroid binding, this backend can present a
     * collection key, so the durable record carries real binding evidence and the identity contract
     * answers with an authoritative match. The routing conclusion must be identical anyway — the
     * action goes to the backend it was admitted on, and nowhere else.
     */
    @Test
    fun `a bury bound to a keyed PC backend executes on that backend only`() = runTest {
        val pcA = AnkiBackendId.PcAgent("gate21i-pca")
        // The substitute is *not* a second PC profile: two `PcAgent` instances would make the
        // pre-binding session-start resolution ambiguous, which is not what this case is about.
        val bId = AnkiBackendId.Fake("gate21i-pcb")
        val b = FakeAnkiBackend(
            id = bId,
            decks = listOf(AnkiDeck(ReviewerActionHarness.deckFor(bId), "B Deck")),
            cards = listOf(ReviewerActionHarness.card("123", bId)),
            initialCapabilities = FakeAnkiBackend.REVIEW_ACTION_CAPABILITIES,
            instanceId = "gate21i-pcb"
        ).apply {
            currentCollectionIdentity = AnkiCollectionIdentity(bId, ReviewerActionHarness.COLLECTION_KEY)
        }
        val h = ReviewerActionHarness(
            backendId = pcA,
            mode = AnkiBackendMode.PC_AGENT,
            extraBackends = listOf(b)
        )
        h.loadToRating()
        assertEquals(pcA, h.state.anki!!.reviewSession!!.context.backendId)
        val nextCardsBefore = h.fake.nextCardCount

        h.requestAction(ReviewerAction.BuryCard)
        h.drain()

        val record = h.store.durableRecords().single()
        assertEquals("the durable owner is the PC instance the session locked", pcA, record.backendId)
        assertEquals(ReviewerAction.BuryCard, record.action)
        assertEquals(ReviewerActionStatus.APPLIED, record.status)
        assertEquals("the target stays PC-qualified", pcA, record.cardRef.backendId)
        assertEquals(
            "a keyed backend supplies real binding evidence",
            ReviewerActionHarness.COLLECTION_KEY, record.collectionRef?.collectionKey)
        assertEquals(1, h.fake.reviewerActionBoundaryCrossings)
        assertEquals(
            "exactly one fresh scheduler query, on the owner",
            nextCardsBefore + 1, h.fake.nextCardCount)

        // The identity contract's own verdict for this record: routed to the exact instance.
        val route = ReviewerActionRoutingPolicy(h.registry)
            .route(record, boundIsLiveInThisProcess = true) as ReviewerActionRoute.Routed
        assertEquals(pcA, route.backend.id)
        assertEquals(
            ReviewerActionHarness.COLLECTION_KEY,
            (route.assurance as AnkiIdentityAssurance.AuthoritativeMatch).collectionKey)

        assertEquals("the substitute receives zero action calls", 0, b.reviewerActionInvocations)
        assertEquals(0, b.reviewerActionBoundaryCrossings)
        assertEquals(0, b.reviewerActionReconciliationCount)
        assertEquals(0, b.nextCardCount)
        assertEquals(0, b.physicalCommitCalls)
    }

    // ------------------------------------------------------------------- VER-21I-05  suspend A

    @Test
    fun `a suspend bound to A executes on A only with the card scope`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        h.requestAction(ReviewerAction.SuspendCard)
        h.drain()

        assertEquals(
            ReviewerActionStatus.APPLIED, h.store.durableRecords().single().status)
        assertEquals(ReviewerAction.SuspendCard, h.store.durableRecords().single().action)
        assertEquals(1, h.fake.reviewerActionBoundaryCrossings)
        assertEquals(0, b.reviewerActionInvocations)
        assertEquals(0, b.reviewerActionBoundaryCrossings)
        assertEquals(0, b.nextCardCount)
    }

    // ----------------------------------------------------------------- VER-21I-06  suspend on PC

    /**
     * Suspend on a PC-owned session, with the scope question asked explicitly: the modelled action
     * is [ReviewerAction.SuspendCard] (card scope) and this build models **no** note/sibling-scoped
     * suspend at all (`ReviewerAction` has exactly `SetFlag`/`BuryCard`/`SuspendCard`), so there is
     * nothing for an adapter to silently widen into. The assertion is that the dispatched action is
     * byte-for-byte the admitted one, on the admitted backend, for the admitted card.
     */
    @Test
    fun `a suspend bound to a keyed PC backend keeps its card scope and its owner`() = runTest {
        val pcA = AnkiBackendId.PcAgent("gate21i-pca")
        val bId = AnkiBackendId.Fake("gate21i-pcb")
        val b = FakeAnkiBackend(
            id = bId,
            decks = listOf(AnkiDeck(ReviewerActionHarness.deckFor(bId), "B Deck")),
            // Same numeric card id as the PC session's card: scope confusion must still be impossible.
            cards = listOf(ReviewerActionHarness.card("A", bId)),
            initialCapabilities = FakeAnkiBackend.REVIEW_ACTION_CAPABILITIES,
            instanceId = "gate21i-pcb"
        ).apply {
            currentCollectionIdentity = AnkiCollectionIdentity(bId, ReviewerActionHarness.COLLECTION_KEY)
        }
        val h = ReviewerActionHarness(
            backendId = pcA,
            mode = AnkiBackendMode.PC_AGENT,
            cards = listOf(
                ReviewerActionHarness.card("A", pcA),
                ReviewerActionHarness.card("A-sibling", pcA)
            ),
            extraBackends = listOf(b)
        )
        h.loadToRating()
        assertEquals("A", h.turn!!.cardRef.cardId)

        h.requestAction(ReviewerAction.SuspendCard)
        h.drain()

        val record = h.store.durableRecords().single()
        assertEquals(pcA, record.backendId)
        assertEquals(
            "the dispatched action is the admitted one — no note/sibling widening exists to fall into",
            ReviewerAction.SuspendCard, record.action)
        assertEquals(ReviewerActionKind.SUSPEND, record.action.kind)
        assertEquals("the suspended resource is the turn's own card", "A", record.cardRef.cardId)
        assertEquals(pcA, record.cardRef.backendId)
        assertEquals(ReviewerActionStatus.APPLIED, record.status)
        assertEquals(1, h.fake.reviewerActionBoundaryCrossings)
        assertEquals("the sibling card on the same backend was never a target", 1,
            h.fake.reviewerActionInvocations)
        assertEquals("the substitute receives zero action calls", 0, b.reviewerActionInvocations)
        assertEquals(0, b.reviewerActionBoundaryCrossings)
        assertEquals(0, b.reviewerActionReconciliationCount)
        assertEquals(0, b.nextCardCount)
    }

    // ------------------------- VER-21I-01 (flag on AnkiDroid) + VER-21I-07  unsupported capability

    /**
     * VER-21I-01 is *capability-blocked*, not skipped: the pinned AnkiDroid public provider contract
     * (v2.24.1) exposes no flag column, so `AnkiCapabilities.flags = false` always
     * (`docs/BACKEND_CAPABILITY_MATRIX.md`, GATE 21A §capability). The honest verification of
     * "flag on AnkiDroid" is therefore exactly this: a typed refusal with zero backend calls and
     * nothing durable — never a substitute operation on any backend.
     */
    @Test
    fun `an unsupported action is refused with a typed refusal, never a substitute`() = runTest {
        val b = backendB()
        // A is keyless AnkiDroid: no flag write path at the pin (capabilities.flags = false).
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        h.requestAction(ReviewerAction.SetFlag(AnkiFlag.RED))
        h.drain()

        assertNotNull("the refusal is typed and visible", h.state.anki?.reviewerActionRefusal)
        assertEquals(
            "no perform effect was ever enqueued",
            0, h.pending.count { it is AnkiStudyEffect.PerformReviewerAction })
        assertEquals("no backend call on A", 0, h.fake.reviewerActionInvocations)
        assertEquals("no substitute operation on B", 0, b.reviewerActionInvocations)
        assertEquals("nothing durable exists", 0, h.store.durableRecords().size)
    }

    // ------------------------------------------------------------------- VER-21I-08  scope

    @Test
    fun `a card-scoped bury never reaches a sibling carrying the same id on B`() = runTest {
        val b = backendB("123") // B also contains cardId 123
        val h = ReviewerActionHarness(
            cards = listOf(ReviewerActionHarness.card("123", aId), ReviewerActionHarness.card("A2", aId)),
            extraBackends = listOf(b)
        )
        h.loadToRating()
        assertEquals("123", h.turn!!.cardRef.cardId)
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()

        assertEquals(1, h.fake.reviewerActionBoundaryCrossings)
        assertEquals("the matching card id on B is never mutated", 0, b.reviewerActionInvocations)
        assertEquals(0, b.reviewerActionBoundaryCrossings)
        assertEquals("no wrong-scope mutation exists", 0, b.reviewerActionInvocations)
        val record = h.store.durableRecords().single()
        assertEquals("the target stays A-qualified", aId, record.cardRef.backendId)
        assertEquals("123", record.cardRef.cardId)
    }

    // ------------------------------------------------------------------- VER-21I-09  mismatch

    @Test
    fun `an action effect naming another backend than the session lock is refused before dispatch`() =
        runTest {
            val locks = AnkiStudyBackendLockTracker()
            val b = backendB()
            val h = ReviewerActionHarness(extraBackends = listOf(b), sessionLocks = locks)
            h.loadToRating()
            val sessionId = h.state.anki!!.reviewSession!!.context.studySessionId
            assertEquals(aId, locks.lockedBackendFor(sessionId))

            // A transaction whose ownership says B, presented while the session is locked to A.
            val bCard = ReviewerActionHarness.card("123", bId)
            val event = h.executor.execute(
                AnkiStudyEffect.PerformReviewerAction(
                    epoch = h.state.epoch,
                    sessionId = sessionId,
                    turnId = h.turn!!.turnId,
                    cardRef = bCard.ref,
                    action = ReviewerAction.BuryCard,
                    collectionRef = AnkiCollectionIdentity(bId, ReviewerActionHarness.COLLECTION_KEY)
                )
            ) as AnkiStudyEvent.ReviewerActionResolved

            val outcome = event.outcome as AnkiReviewerActionOutcome.NotRecorded
            assertEquals(AnkiStudyEffectExecutor.SESSION_BACKEND_MISMATCH, outcome.category)
            assertEquals("refused, not dispatched", 0, b.reviewerActionInvocations)
            assertEquals("A was not touched either", 0, h.fake.reviewerActionInvocations)
            assertEquals(
                "no durable intent for a backend the session does not own",
                0, h.store.durableRecords().size)
        }

    // ------------------------------------------------------------------- VER-21I-10  collision

    @Test
    fun `a matching card id on two backends never authorizes a B mutation`() = runTest {
        val b = backendB("123")
        val h = ReviewerActionHarness(
            cards = listOf(ReviewerActionHarness.card("123", aId)),
            extraBackends = listOf(b)
        )
        h.loadToRating()
        assertEquals("123", h.turn!!.cardRef.cardId)
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()

        assertEquals(1, h.fake.reviewerActionBoundaryCrossings)
        assertEquals("a matching card id is not identity", 0, b.reviewerActionInvocations)
        val record = h.store.durableRecords().single()
        assertEquals("A/123 stays A-owned", aId, record.backendId)
        assertEquals("123", record.cardRef.cardId)
    }

    // ------------------------------------------------------------------- VER-21I-11  preference

    @Test
    fun `a preference change mid-session cannot move a pending action`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()

        // The user switches the global preference to the PC backend mid-session…
        val resolver = AnkiBackendResolver(h.registry)
        val resolved = resolver.resolve(BackendResolutionRequest(AnkiBackendMode.PC_AGENT))
        assertEquals(
            "the preference now resolves to B — and that is irrelevant to this session",
            bId, (resolved as BackendResolutionResult.Resolved).backendId)

        // …and the action still goes to the backend the session was admitted on.
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()
        assertEquals(1, h.fake.reviewerActionBoundaryCrossings)
        assertEquals(0, b.reviewerActionInvocations)
        assertEquals(aId, h.store.durableRecords().single().backendId)
        assertEquals(aId, h.state.anki!!.reviewSession!!.context.backendId)
    }

    // ------------------------------------------------------------------- VER-21I-12  AUTO

    @Test
    fun `AUTO eligibility changing while an action is pending leaves the owner unchanged`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()

        // The action's response is lost: the record is AMBIGUOUS and A-owned.
        h.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost"))))
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()
        assertEquals(ReviewerActionStatus.AMBIGUOUS, h.action?.status)

        // AUTO would now pick B: A reports itself unavailable and B is ready.
        h.fake.setAvailability(AnkiAvailability.TemporarilyUnavailable())
        val resolver = AnkiBackendResolver(h.registry)
        assertTrue(
            resolver.resolve(BackendResolutionRequest(AnkiBackendMode.AUTO))
                is BackendResolutionResult.Resolved)

        // Reconciliation is offered — and it still targets A, the recorded owner:
        // an unreachable A stays an ambiguous A, never a recovered B.
        h.send(AnkiStudyEvent.RecoverReviewerAction(h.state.epoch, h.action!!.actionId))
        h.drain()

        val record = h.store.durableRecords().single()
        assertEquals(aId, record.backendId)
        assertEquals("unreachable is not resolved by guessing",
            ReviewerActionStatus.AMBIGUOUS, record.status)
        assertEquals("an AUTO change never reroutes a durable action", 0, b.reviewerActionInvocations)
        assertEquals(0, b.reviewerActionReconciliationCount)
        assertEquals(0, b.nextCardCount)
    }

    // ------------------------------------------------------------------- VER-21I-13  library

    @Test
    fun `browsing B's library while an action is pending on A does not redirect it`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        h.requestAction(ReviewerAction.BuryCard)

        // The user browses the Library on B while the action effect is still pending.
        b.getDecks()
        assertEquals(1, b.getDecksCalls)

        h.run(h.takePerformEffect())
        h.drain()

        assertEquals(
            ReviewerActionStatus.APPLIED, h.store.durableRecords().single().status)
        assertEquals(aId, h.store.durableRecords().single().backendId)
        assertEquals("the library read never became a mutation destination", 0, b.reviewerActionInvocations)
        assertEquals(0, b.reviewerActionBoundaryCrossings)
        assertEquals(0, b.nextCardCount)
    }

    // ------------------------------------------------------------------- VER-21I-14  mismatch

    @Test
    fun `a verified collection mismatch blocks a new action before dispatch`() = runTest {
        val b = backendB()
        val h = keyedLocalHarness(b)
        h.loadToRating()
        assertEquals(
            ReviewerActionHarness.COLLECTION_KEY, h.state.anki!!.reviewSession!!.context.collection?.collectionKey)

        // The backend reconnects against a *different*, verifiably keyed collection.
        h.fake.currentCollectionIdentity = AnkiCollectionIdentity(aId, "key-2")

        h.requestAction(ReviewerAction.BuryCard)
        val effect = h.takePerformEffect()
        val events = h.run(effect)
        val outcome = events.filterIsInstance<AnkiStudyEvent.ReviewerActionResolved>().single().outcome
        val notRecorded = outcome as AnkiReviewerActionOutcome.NotRecorded
        assertEquals("collection_mismatch", notRecorded.category)

        assertEquals("a verified mismatch dispatches nothing on A", 0, h.fake.reviewerActionInvocations)
        assertEquals("…and nothing on B", 0, b.reviewerActionInvocations)
        assertEquals("no durable intent exists", 0, h.store.durableRecords().size)
    }

    // ------------------------------------------------------------------- VER-21I-15  degraded

    @Test
    fun `a degraded key blocks a proven-not-applied retry before dispatch`() = runTest {
        val b = backendB()
        val h = keyedLocalHarness(b)
        h.loadToRating()

        // First attempt: refused before dispatch by the backend → RETRY_ALLOWED (durable).
        h.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.ConfirmedNotApplied(AnkiError.QueryFailure("denied")),
                callsMutationEntry = false))
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()
        assertEquals(ReviewerActionStatus.RETRY_ALLOWED, h.action?.status)
        val actionId = h.action!!.actionId

        // The backend reconnected but can no longer name its collection: DEGRADED.
        h.fake.currentCollectionIdentity = null

        // OP-M2 — the retry is refused before dispatch, with the stable token.
        h.send(AnkiStudyEvent.RetryReviewerAction(h.state.epoch, actionId))
        h.drain()
        assertEquals("collection_identity_degraded", h.action?.failureCategory)
        assertEquals("only the first refusal reached the backend", 1, h.fake.reviewerActionInvocations)

        // The durable record keeps its owner, its target and its status — never rewritten.
        val record = h.store.durableRecords().single()
        assertEquals(aId, record.backendId)
        assertEquals(ReviewerActionStatus.RETRY_ALLOWED, record.status)
        assertEquals(0, b.reviewerActionInvocations)
        assertEquals(0, b.reviewerActionReconciliationCount)
    }

    @Test
    fun `a degraded key blocks a brand-new action before dispatch`() = runTest {
        val b = backendB()
        val h = keyedLocalHarness(b)
        h.loadToRating()

        // The backend reconnected but can no longer name its collection: DEGRADED.
        h.fake.currentCollectionIdentity = null

        h.requestAction(ReviewerAction.BuryCard)
        val effect = h.takePerformEffect()
        val events = h.run(effect)
        val outcome = events.filterIsInstance<AnkiStudyEvent.ReviewerActionResolved>().single().outcome
        val notRecorded = outcome as AnkiReviewerActionOutcome.NotRecorded
        assertEquals("a lost key is mismatch-grade, never 'no key, therefore fine'",
            "collection_identity_degraded", notRecorded.category)

        assertEquals("the backend was never called", 0, h.fake.reviewerActionInvocations)
        assertEquals("no substitute operation on B", 0, b.reviewerActionInvocations)
        assertEquals("no durable intent exists", 0, h.store.durableRecords().size)
    }

    @Test
    fun `a degraded key blocks reconciliation with zero provider calls`() = runTest {
        val h = keyedLocalHarness(backendB())
        h.loadToRating()
        h.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost"))))
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()
        assertEquals(ReviewerActionStatus.AMBIGUOUS, h.action?.status)
        val actionId = h.action!!.actionId

        h.fake.currentCollectionIdentity = null // key lost: DEGRADED

        h.send(AnkiStudyEvent.RecoverReviewerAction(h.state.epoch, actionId))
        h.drain()

        assertEquals("degraded identity never becomes a match", ReviewerActionStatus.AMBIGUOUS,
            h.store.durableRecords().single().status)
        assertEquals("a blocked cell issues zero reconciliation calls", 0, h.fake.reviewerActionReconciliationCount)
    }

    /**
     * VER-21I-15 — the **unknown** collection cell, distinct from degraded. The fake explicitly
     * advertises synthetic historical-reconciliation capability so this test isolates the locked
     * identity rule: neither live session continuity nor nominal unverifiability is an authoritative
     * collection match. Both OP-M3 routes must block before any provider call.
     */
    @Test
    fun `unknown collection blocks OP-M3 under continuity and after process death`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(
            extraBackends = listOf(b),
            authoritativeReviewerActionReconciliation = true
        ) // A remains keyless, matching production AnkiDroid identity
        h.loadToRating()
        assertNull("the backend presents no collection key", h.fake.currentCollectionIdentity)
        assertNull("…so the session bound none",
            h.state.anki!!.reviewSession!!.context.collection?.collectionKey)

        h.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost"))))
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()
        assertEquals(ReviewerActionStatus.AMBIGUOUS, h.action?.status)
        val record = h.store.durableRecords().single()
        assertNull("the durable record claims no collection it never had",
            record.collectionRef?.collectionKey)

        val policy = ReviewerActionRoutingPolicy(h.registry)
        val live = policy.reconcileRoute(record, boundIsLiveInThisProcess = true)
            as ReviewerActionRoute.Blocked
        assertEquals("continuity cannot stand in for collection proof",
            "continuity_is_not_collection_evidence", live.token)
        assertEquals(AnkiIdentityAssurance.SessionContinuityVerified, live.assurance)
        val restored = policy.reconcileRoute(record, boundIsLiveInThisProcess = false)
            as ReviewerActionRoute.Blocked
        assertEquals("nominally unverifiable identity cannot authorize OP-M3",
            "reconciliation_requires_authoritative_match", restored.token)
        assertEquals(
            AnkiIdentityAssurance.IdentityUnverifiable(AnkiUnverifiabilityQualifier.NOMINAL),
            restored.assurance)

        h.send(AnkiStudyEvent.RecoverReviewerAction(h.state.epoch, record.actionId))
        h.drain()
        assertEquals(ReviewerActionStatus.AMBIGUOUS, h.store.durableRecords().single().status)
        assertEquals("no current-state probe is allowed without a collection match",
            0, h.fake.reviewerActionReconciliationCount)

        // Unknown identity is not a blind-retry grant either; owner and parameters stay immutable.
        val invocations = h.fake.reviewerActionInvocations
        h.send(AnkiStudyEvent.RetryReviewerAction(h.state.epoch, record.actionId))
        assertTrue(h.rejected.contains("anki-action-retry-not-safe"))
        assertEquals(invocations, h.fake.reviewerActionInvocations)
        val after = h.store.durableRecords().single()
        assertEquals(ReviewerActionStatus.AMBIGUOUS, after.status)
        assertEquals(aId, after.backendId)
        assertEquals(ReviewerAction.BuryCard, after.action)
        assertEquals(0, b.reviewerActionInvocations)
        assertEquals(0, b.reviewerActionReconciliationCount)
    }

    // ------------------------------------------------------------------- VER-21I-16  prepared

    @Test
    fun `a failed durable preparation blocks the backend call entirely`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()

        h.store.failNextWrites = 1 // the PREPARED write cannot become durable
        h.requestAction(ReviewerAction.BuryCard)
        val effect = h.takePerformEffect()
        val events = h.run(effect)
        h.drain()

        val outcome = events.filterIsInstance<AnkiStudyEvent.ReviewerActionResolved>().single().outcome
        assertTrue(outcome is AnkiReviewerActionOutcome.NotRecorded)
        assertEquals("no record: the preparation failed", 0, h.store.durableRecords().size)
        assertEquals("the backend was never called", 0, h.fake.reviewerActionInvocations)
        assertEquals(0, h.fake.reviewerActionBoundaryCrossings)
        assertEquals(0, b.reviewerActionInvocations)
    }

    // ------------------------------------------------------------------- VER-21I-17  submitting

    @Test
    fun `a failed durable boundary write refuses the mutation and leaves PREPARED`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()

        // The PREPARED write succeeds; the SUBMITTING write fails.
        h.store.onDurableWrite = {
            if (h.store.writes.size == 1) h.store.failNextWrites = 1
        }
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()

        assertEquals(
            "the backend was invoked but told not to mutate",
            0, h.fake.reviewerActionBoundaryCrossings)
        assertEquals(1, h.fake.reviewerActionInvocations)
        val record = h.store.durableRecords().single()
        assertEquals("the durable row provably never entered the boundary",
            ReviewerActionStatus.PREPARED, record.status)
        assertEquals("the projection offers the same identity for retry",
            ReviewerActionStatus.RETRY_ALLOWED, h.action?.status)
        assertEquals(0, b.reviewerActionInvocations)
    }

    // ------------------------------------------------------------------- VER-21I-18  crash

    @Test
    fun `a crash before the provider call leaves SUBMITTING and recovery assumes nothing`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        h.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.ConfirmedApplied(
                    ReviewerActionReceipt(aId, "bury", detail = "confirmed_by_fake")),
                crashAfterBoundary = true))
        h.requestAction(ReviewerAction.BuryCard)
        val effect = h.takePerformEffect()
        var crashed = false
        try {
            h.run(effect)
        } catch (death: ProcessDeathSimulation) {
            crashed = true
            assertEquals("crash_after_boundary", death.window)
        }
        assertTrue(crashed)
        assertEquals(1, h.fake.reviewerActionInvocations)
        assertEquals(1, h.fake.reviewerActionBoundaryCrossings)

        // New process over exactly those bytes.
        h.restart()
        val record = h.ledger.get(h.store.durableRecords().single().actionId)!!
        assertEquals("death at the boundary leaves SUBMITTING — never proof of non-application",
            ReviewerActionStatus.SUBMITTING, record.status)
        assertEquals(aId, record.backendId)

        // Recovery cannot use current AnkiDroid card state as historical proof: it remains
        // explicitly uncertain, with no replay and no reconciliation provider call.
        val recovered = h.coordinator().recover(record.actionId)
        val rec = recovered as ReviewerActionRecoveryOutcome.Recovered
        assertEquals(ReviewerActionRecoveryAction.RemainBlocked, rec.decision)
        assertEquals(
            "no authoritative evidence: the record normalizes to explicit uncertainty",
            ReviewerActionStatus.AMBIGUOUS, rec.record.status)
        assertEquals("recovery never replays the mutation", 1, h.fake.reviewerActionInvocations)
        assertEquals(1, h.fake.reviewerActionBoundaryCrossings)
        assertEquals("unknown collection blocks the reconciliation read", 0, h.fake.reviewerActionReconciliationCount)
        assertEquals(0, b.reviewerActionInvocations)
        assertEquals(0, b.reviewerActionReconciliationCount)
    }

    // ------------------------------------------------------------------- VER-21I-19  lost response

    @Test
    fun `an applied action with a lost response is ambiguous and never replayed`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        h.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost"))))
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()

        assertEquals(ReviewerActionStatus.AMBIGUOUS, h.action?.status)
        val actionId = h.action!!.actionId

        h.restart()
        val record = h.store.durableRecords().single()
        assertEquals(ReviewerActionStatus.AMBIGUOUS, record.status)

        h.coordinator().recover(actionId)
        assertEquals("no blind replay after restart", 1, h.fake.reviewerActionInvocations)
        assertEquals(1, h.fake.reviewerActionBoundaryCrossings)
        assertEquals(ReviewerActionStatus.AMBIGUOUS, h.store.durableRecords().single().status)
        assertEquals(0, b.reviewerActionInvocations)
        assertEquals(0, b.nextCardCount)
    }

    // ------------------------------------------------------------------- VER-21I-20  persist fail

    @Test
    fun `a confirmed action whose APPLIED persistence failed stays ambiguous after process death`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        h.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.ConfirmedApplied(
                    ReviewerActionReceipt(aId, "bury", detail = "confirmed_by_fake")),
                reconciliation = ReviewerActionReconciliationResult.ConfirmedApplied(
                    ReviewerActionReceipt(aId, "bury", detail = "authoritative_fake_receipt"))))
        // Writes 1 (PREPARED) and 2 (SUBMITTING) succeed; write 3 (APPLIED) fails.
        h.store.onDurableWrite = {
            if (h.store.writes.size == 2) h.store.failNextWrites = 1
        }
        h.requestAction(ReviewerAction.BuryCard)
        val events = mutableListOf<AnkiStudyEvent>()
        events += h.run(h.takePerformEffect())
        h.drain()

        val outcome = events.filterIsInstance<AnkiStudyEvent.ReviewerActionResolved>().single().outcome
        assertTrue("a storage failure is reported, never as success",
            outcome is AnkiReviewerActionOutcome.NotRecorded)
        assertEquals("the durable row is still SUBMITTING",
            ReviewerActionStatus.SUBMITTING, h.store.durableRecords().single().status)
        assertEquals(1, h.fake.reviewerActionInvocations)
        assertEquals(1, h.fake.reviewerActionBoundaryCrossings)
        val nextEffects = events.filterIsInstance<AnkiStudyEffect.Next>()
        assertEquals("no progression without a durable APPLIED", 0, nextEffects.size)

        // Restart loses the in-memory confirmation. The keyless backend cannot prove this
        // historical request from current card state, so the record remains unresolved.
        h.restart()
        val recovered = h.coordinator().recover(h.store.durableRecords().single().actionId)
        val rec = recovered as ReviewerActionRecoveryOutcome.Recovered
        assertEquals(ReviewerActionStatus.AMBIGUOUS, rec.record.status)
        assertEquals("the action is never resubmitted", 1, h.fake.reviewerActionInvocations)
        assertEquals("AnkiDroid state is not historical reconciliation evidence",
            0, h.fake.reviewerActionReconciliationCount)
        assertEquals(0, b.reviewerActionInvocations)
        assertEquals(0, b.reviewerActionReconciliationCount)
    }

    // ------------------------------------------------------------------- VER-21I-21  durable applied

    @Test
    fun `a confirmed bury is durable and produces exactly one fresh scheduler query`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        val before = h.fake.nextCardCount
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()

        assertEquals(ReviewerActionStatus.APPLIED, h.store.durableRecords().single().status)
        assertEquals("one fresh next-card query on A, backend-authoritative",
            before + 1, h.fake.nextCardCount)
        assertEquals(0, b.nextCardCount)
        assertEquals("the action is not a rating", 0, b.physicalCommitCalls)
        assertEquals(0, h.fake.physicalCommitCalls)
    }

    // ------------------------------------------------------------------- VER-21I-22  restart

    @Test
    fun `an ambiguous action keeps backend target parameters and ambiguity after death`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        val cardRef = h.turn!!.cardRef
        h.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost"))))
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()
        val before = h.store.durableRecords().single()

        h.restart()
        val after = h.store.durableRecords().single()
        assertEquals(before.actionId, after.actionId)
        assertEquals(aId, after.backendId)
        assertEquals(cardRef, after.cardRef)
        assertEquals(ReviewerAction.BuryCard, after.action)
        assertEquals(ReviewerActionStatus.AMBIGUOUS, after.status)
        assertEquals("the ambiguity survives process death", before.sessionId, after.sessionId)
        assertEquals(0, b.reviewerActionInvocations)
    }

    // ------------------------------------------------------------------- VER-21I-23  retry evidence

    @Test
    fun `a retry from proven non-application reuses the identity, and a blind retry is refused`() =
        runTest {
            val b = backendB()
            val h = ReviewerActionHarness(extraBackends = listOf(b))
            h.loadToRating()
            h.fake.scriptActions(
                FakeAnkiBackend.ActionStep(
                    ReviewerActionBackendResult.ConfirmedNotApplied(AnkiError.QueryFailure("denied")),
                    callsMutationEntry = false))
            h.requestAction(ReviewerAction.SuspendCard)
            h.drain()
            assertEquals(ReviewerActionStatus.RETRY_ALLOWED, h.action?.status)
            val actionId = h.action!!.actionId
            val attemptsBefore = h.fake.reviewerActionInvocations

            // Proven non-application: the explicit retry is the same logical action.
            h.fake.scriptActions(
                FakeAnkiBackend.ActionStep(
                    ReviewerActionBackendResult.ConfirmedApplied(
                        ReviewerActionReceipt(aId, "suspend", detail = "confirmed_by_fake"))))
            h.send(AnkiStudyEvent.RetryReviewerAction(h.state.epoch, actionId))
            h.drain()
            assertEquals(ReviewerActionStatus.APPLIED, h.store.durableRecords().single().status)
            assertEquals("the retry reuses the identity", actionId,
                h.store.durableRecords().single().actionId)
            assertEquals("exactly one more mutation", attemptsBefore + 1,
                h.fake.reviewerActionInvocations)

            // Now an AMBIGUOUS action: a blind retry is refused by the machine itself.
            h.fake.scriptActions(
                FakeAnkiBackend.ActionStep(
                    ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost2"))))
            h.send(StudyEvent.UserRequestAnswer(h.state.currentCardId!!))
            h.requestAction(ReviewerAction.BuryCard)
            h.drain()
            assertEquals(ReviewerActionStatus.AMBIGUOUS, h.action?.status)
            val ambiguousId = h.action!!.actionId
            val invocationsBeforeBlindRetry = h.fake.reviewerActionInvocations
            h.send(AnkiStudyEvent.RetryReviewerAction(h.state.epoch, ambiguousId))
            assertTrue(
                "AMBIGUOUS is never offered an accidental retry",
                h.rejected.contains("anki-action-retry-not-safe"))
            assertEquals(invocationsBeforeBlindRetry, h.fake.reviewerActionInvocations)
            assertEquals(0, b.reviewerActionInvocations)
        }

    // ------------------------------------------------------------------- VER-21I-24  reconcile A

    @Test
    fun `supported reconciliation resolves through the original backend alone`() = runTest {
        val b = backendB()
        val h = keyedLocalHarness(b)
        h.loadToRating()
        h.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost")),
                reconciliation = ReviewerActionReconciliationResult.ConfirmedApplied(
                    ReviewerActionReceipt(aId, "bury", detail = "authoritative_fake_receipt"))))
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()
        assertEquals(ReviewerActionStatus.AMBIGUOUS, h.action?.status)

        h.send(AnkiStudyEvent.RecoverReviewerAction(h.state.epoch, h.action!!.actionId))
        h.drain()

        assertEquals(ReviewerActionStatus.APPLIED, h.store.durableRecords().single().status)
        assertEquals("the original backend alone was reconciled", 1, h.fake.reviewerActionReconciliationCount)
        assertEquals(0, b.reviewerActionReconciliationCount)
        assertEquals(0, b.reviewerActionInvocations)
    }

    @Test
    fun `an authoritative collection match still needs the frozen reconciliation capability`() = runTest {
        val b = backendB()
        val store = InMemoryReviewerActionStore()
        val request = ReviewerActionRequest(
            sessionId = "study-frozen-capability",
            turnId = ReviewTurnId("turn-frozen-capability"),
            cardRef = ReviewerActionHarness.card("A", aId).ref,
            action = ReviewerAction.BuryCard,
            collectionRef = AnkiCollectionIdentity(aId, ReviewerActionHarness.COLLECTION_KEY),
            semantics = ReviewerActionSemantics(
                supportsIdempotentReplay = true,
                supportsAuthoritativeReconciliation = false
            )
        )
        val seedLedger = store.restart(clock = { 1_000L })
        seedLedger.create(request.toRecord(1_000L))
        seedLedger.transition(
            request.actionId, ReviewerActionStatus.PREPARED, ReviewerActionTransition.EnterMutationBoundary)
        seedLedger.transition(
            request.actionId, ReviewerActionStatus.SUBMITTING,
            ReviewerActionTransition.BackendOutcomeUnknown())

        val h = ReviewerActionHarness(
            store = store,
            extraBackends = listOf(b),
            liveCollectionKey = ReviewerActionHarness.COLLECTION_KEY,
            authoritativeReviewerActionReconciliation = true
        )
        val record = h.store.durableRecords().single()
        assertFalse("the record did not freeze reconciliation support",
            record.frozenAuthoritativeReconciliation)
        val blocked = ReviewerActionRoutingPolicy(h.registry).reconcileRoute(record)
            as ReviewerActionRoute.Blocked
        assertEquals("reconciliation_requires_authoritative_match", blocked.token)
        assertEquals(ReviewerActionHarness.COLLECTION_KEY,
            (blocked.assurance as AnkiIdentityAssurance.AuthoritativeMatch).collectionKey)

        val recovered = h.coordinator().recover(record.actionId) as ReviewerActionRecoveryOutcome.Recovered
        assertEquals(ReviewerActionRecoveryAction.RemainBlocked, recovered.decision)
        assertEquals(ReviewerActionStatus.AMBIGUOUS, recovered.record.status)
        assertEquals("the live capability cannot widen the frozen promise", 0,
            h.fake.reviewerActionReconciliationCount)
        assertEquals(0, b.reviewerActionReconciliationCount)
    }

    // ------------------------------------------------------------------- VER-21I-25  unsupported

    @Test
    fun `unsupported reconciliation leaves the outcome unresolved with zero provider calls`() =
        runTest {
            val b = backendB()
            // A models the flag write path but NOT authoritative flag reconciliation (the pinned
            // contract has no flag read/write at all): the frozen promise is false for flag.
            val h = ReviewerActionHarness(
                extraBackends = listOf(b),
                capabilities = FakeAnkiBackend.REVIEW_ACTION_CAPABILITIES.copy(flags = true),
                flagWrites = true
            )
            h.loadToRating()
            h.fake.scriptActions(
                FakeAnkiBackend.ActionStep(
                    ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost"))))
            h.requestAction(ReviewerAction.SetFlag(AnkiFlag.RED))
            h.drain()
            assertEquals(ReviewerActionStatus.AMBIGUOUS, h.action?.status)
            val actionId = h.action!!.actionId

            h.send(AnkiStudyEvent.RecoverReviewerAction(h.state.epoch, actionId))
            h.drain()

            assertEquals("no capability: the outcome remains unresolved",
                ReviewerActionStatus.AMBIGUOUS, h.store.durableRecords().single().status)
            assertEquals(
                "the capability gate precedes the provider: zero reconciliation calls",
                0, h.fake.reviewerActionReconciliationCount)
            assertEquals(0, b.reviewerActionReconciliationCount)
            assertEquals(0, b.reviewerActionInvocations)
        }

    // ------------------------------------------------------------------- VER-21I-26  removed

    @Test
    fun `a removed original backend blocks its actions and lends nothing to the ready backend`() =
        runTest {
            val b = backendB()
            val h = ReviewerActionHarness(extraBackends = listOf(b))
            h.loadToRating()
            h.fake.scriptActions(
                FakeAnkiBackend.ActionStep(
                    ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost"))))
            h.requestAction(ReviewerAction.BuryCard)
            h.drain()
            assertEquals(ReviewerActionStatus.AMBIGUOUS, h.action?.status)
            val actionId = h.action!!.actionId

            // Backend A's configuration is deleted; B stays ready.
            h.registry.remove(aId)
            assertEquals(bId, h.registry.ids.single())

            val plan = h.executor.actionRecoveryPlan()
            assertEquals(
                BackendTransactionRoute.Blocked(aId, BackendTransactionBlockReason.BACKEND_NOT_REGISTERED),
                plan[aId])
            // A's record is still there, still A-owned: the plan reads the ledger.
            assertEquals(1, h.store.durableRecords().size)
            assertEquals(aId, h.store.durableRecords().single().backendId)

            // Recovery: the owner is gone, so the record stays exactly as durable uncertainty —
            // never redirected to B, never "recovered" by the ready backend.
            val recovered = h.coordinator().recover(actionId)
            val rec = recovered as ReviewerActionRecoveryOutcome.Recovered
            assertEquals(ReviewerActionStatus.AMBIGUOUS, rec.record.status)
            assertEquals(aId, rec.record.backendId)
            assertEquals("the gate precedes the provider: zero reconciliation calls",
                0, b.reviewerActionReconciliationCount)
            assertEquals(0, b.reviewerActionInvocations)
            assertEquals(0, b.nextCardCount)
        }

    // ------------------------------------------- VER-21I-27 reconnect + VER-21I-28 new collection

    @Test
    fun `reconnect validates identity before safe recovery and a changed collection stays blocked`() =
        runTest {
            val b = backendB()
            val h = keyedLocalHarness(b)
            h.loadToRating()
            h.fake.scriptActions(
                FakeAnkiBackend.ActionStep(
                    ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost")),
                    reconciliation = ReviewerActionReconciliationResult.ConfirmedApplied(
                        ReviewerActionReceipt(aId, "bury", detail = "authoritative_fake_receipt"))))
            h.requestAction(ReviewerAction.BuryCard)
            h.drain()
            assertEquals(ReviewerActionStatus.AMBIGUOUS, h.action?.status)
            val actionId = h.action!!.actionId

            // Reconnect against a different verified collection: recovery stays blocked, zero calls.
            h.fake.currentCollectionIdentity = AnkiCollectionIdentity(aId, "key-2")
            h.send(AnkiStudyEvent.RecoverReviewerAction(h.state.epoch, actionId))
            h.drain()
            assertEquals(ReviewerActionStatus.AMBIGUOUS, h.store.durableRecords().single().status)
            assertEquals("a mismatched reconnect issues zero reconciliation calls",
                0, h.fake.reviewerActionReconciliationCount)
            assertEquals(0, b.reviewerActionReconciliationCount)

            // Reconnect against the original collection: the same read resolves it.
            h.fake.currentCollectionIdentity = AnkiCollectionIdentity(
                aId, ReviewerActionHarness.COLLECTION_KEY)
            h.send(AnkiStudyEvent.RecoverReviewerAction(h.state.epoch, actionId))
            h.drain()
            assertEquals(ReviewerActionStatus.APPLIED, h.store.durableRecords().single().status)
            assertEquals(1, h.fake.reviewerActionReconciliationCount)
        }

    // ------------------------------------------------------------------- VER-21I-29  isolation

    @Test
    fun `actions for A and B recover independently during a recovery pass`() = runTest {
        val b = backendB("123")
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()

        // X → A: lost response, AMBIGUOUS.
        h.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost"))))
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()
        val xId = h.action!!.actionId

        // Y → B: driven through the same coordinator, lost response, scripted reconciliation.
        b.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost-b")),
                reconciliation = ReviewerActionReconciliationResult.ConfirmedApplied(
                    ReviewerActionReceipt(bId, "bury", detail = "authoritative_fake_receipt"))))
        val yRecord = h.coordinator().perform(
            ReviewerActionRequest(
                sessionId = "study-B",
                turnId = ReviewTurnId("turn-b"),
                cardRef = ReviewerActionHarness.card("123", bId).ref,
                action = ReviewerAction.BuryCard,
                collectionRef = AnkiCollectionIdentity(bId, ReviewerActionHarness.COLLECTION_KEY),
                semantics = ReviewerActionSemantics(true, true)
            )
        )
        assertTrue(yRecord is ReviewerActionOutcome.Ambiguous)
        val yId = yRecord.actionId!!

        // A goes down; B stays ready.
        h.fake.setAvailability(AnkiAvailability.TemporarilyUnavailable())
        val plan = h.executor.actionRecoveryPlan()
        assertEquals(
            BackendTransactionRoute.Blocked(aId, BackendTransactionBlockReason.BACKEND_UNREACHABLE),
            plan[aId])
        assertTrue(plan[bId] is BackendTransactionRoute.Routed)

        // B's group recovers; A's record is untouched by it.
        val yRecovered = h.coordinator().recover(yId)
        val yRec = yRecovered as ReviewerActionRecoveryOutcome.Recovered
        assertEquals(ReviewerActionStatus.APPLIED, yRec.record.status)
        assertEquals(
            "recovering Y never rewrites X",
            ReviewerActionStatus.AMBIGUOUS, h.ledger.get(xId)!!.status)
        assertEquals(aId, h.ledger.get(xId)!!.backendId)
        assertEquals(1, b.reviewerActionInvocations)
        assertEquals(1, b.reviewerActionReconciliationCount)
        assertEquals(1, h.fake.reviewerActionInvocations)
        assertEquals(0, h.fake.reviewerActionReconciliationCount)
    }

    // ------------------------------------------------------------------- VER-21I-30  duplicate

    @Test
    fun `repeated bury input produces one logical action and at most one mutation`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        h.requestAction(ReviewerAction.BuryCard)
        h.requestAction(ReviewerAction.BuryCard) // the user tapped twice

        h.drain()
        assertEquals("one logical action, one durable record", 1, h.store.durableRecords().size)
        assertEquals("at most one mutation", 1, h.fake.reviewerActionBoundaryCrossings)
        assertEquals(1, h.fake.reviewerActionInvocations)
        assertEquals(0, b.reviewerActionInvocations)
    }

    // ------------------------------------------------------------------- VER-21I-31  conflicting

    @Test
    fun `conflicting actions on one turn obey admission ordering`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        h.requestAction(ReviewerAction.BuryCard)
        h.requestAction(ReviewerAction.SuspendCard) // incompatible, same turn

        h.drain()
        assertEquals("exactly one logical action survives", 1, h.store.durableRecords().size)
        assertEquals(ReviewerAction.BuryCard, h.store.durableRecords().single().action)
        assertEquals("one mutation, never two", 1, h.fake.reviewerActionBoundaryCrossings)
        assertEquals(0, b.reviewerActionInvocations)
    }

    // ------------------------------------------------------------------- VER-21I-32  rating+action

    @Test
    fun `a rating cannot bypass an unresolved reviewer action`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        h.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost"))))
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()
        assertEquals(ReviewerActionStatus.AMBIGUOUS, h.action?.status)

        h.rate(Rating.GOOD)
        h.drain()
        assertEquals("the rating is refused below the UI, typed by the action's status",
            "anki-rating-blocked-by-action_outcome_unresolved",
            h.rejected.last())
        assertNull("no optimistic commit was ever created", h.commit)
        assertEquals("the rating never reached the backend", 0, h.fake.physicalCommitCalls)
        assertEquals(0, b.physicalCommitCalls)
        assertEquals("no durable commit intent exists", 0, h.commitStore.durableRecords().size)
        assertEquals("the action's own truth is untouched",
            ReviewerActionStatus.AMBIGUOUS, h.action?.status)
    }

    // ------------------------------------------------------------------- VER-21I-33  action+rating

    @Test
    fun `an action cannot bypass an active conflicting rating transaction`() = runTest {
        val b = backendB()
        // The rating's response is lost: an AMBIGUOUS commit owns this turn's mutation slot.
        val h = ReviewerActionHarness(
            extraBackends = listOf(b),
            commitSteps = listOf(
                FakeAnkiBackend.CommitStep(BackendCommitResult.OutcomeUnknown()))
        )
        h.loadToRating()
        h.rate(Rating.GOOD)
        h.drain()
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit?.status)

        // While the rating is under reconciliation, even the action *request* is refused…
        h.requestAction(ReviewerAction.BuryCard)
        assertTrue("…by the machine itself, before any effect exists",
            h.rejected.contains("illegal-phase-for-anki-reviewer-action"))
        assertEquals(
            "no perform effect was ever enqueued",
            0, h.pending.count { it is AnkiStudyEffect.PerformReviewerAction })

        // …and a stray perform effect for the same turn is refused below the UI, before dispatch.
        val stray = AnkiStudyEffect.PerformReviewerAction(
            epoch = h.state.epoch,
            sessionId = h.state.anki!!.reviewSession!!.context.studySessionId,
            turnId = h.turn!!.turnId,
            cardRef = h.turn!!.cardRef,
            action = ReviewerAction.BuryCard
        )
        val events = h.run(stray)
        val outcome = events.filterIsInstance<AnkiStudyEvent.ReviewerActionResolved>().single().outcome
        assertTrue("the slot owner wins at the coordinator",
            outcome is AnkiReviewerActionOutcome.NotRecorded)
        assertEquals("the action never reached the backend", 0, h.fake.reviewerActionInvocations)
        assertEquals(0, b.reviewerActionInvocations)
        assertEquals("the commit's truth is untouched", ReviewCommitStatus.AMBIGUOUS,
            h.commit?.status)
    }

    // ------------------------------------------------------------------- VER-21I-34  flag effect

    @Test
    fun `a confirmed flag keeps the turn without fabricated progression`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(
            extraBackends = listOf(b),
            capabilities = FakeAnkiBackend.REVIEW_ACTION_CAPABILITIES.copy(flags = true),
            flagWrites = true
        )
        h.loadToRating()
        val turnBefore = h.turn!!
        h.requestAction(ReviewerAction.SetFlag(AnkiFlag.RED))
        h.drain()

        assertEquals(
            ReviewerActionStatus.APPLIED, h.store.durableRecords().single().status)
        assertNull("APPLIED returns the projection to idle (GATE 13)", h.action)
        assertEquals("a flag keeps the same turn", turnBefore.turnId, h.turn?.turnId)
        assertEquals("the flag is projected onto the turn", AnkiFlag.RED, h.turn?.renderedCard?.flag)
        assertEquals("no next-card query for a flag", 1, h.fake.nextCardCount)
        assertEquals(SessionPhase.WaitingForRating, h.state.phase)
        assertEquals("no rating transaction was created", null, h.commit)
        assertEquals(0, b.nextCardCount)
    }

    // ------------------------------------------------------------------- VER-21I-35  bury effect

    @Test
    fun `a confirmed bury closes the turn and asks for exactly one fresh card`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        val oldCard = h.state.currentCardId!!
        val nextBefore = h.fake.nextCardCount
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()

        assertEquals(ReviewerActionStatus.APPLIED, h.store.durableRecords().single().status)
        assertEquals("exactly one fresh, backend-authoritative query",
            nextBefore + 1, h.fake.nextCardCount)
        assertEquals(0, b.nextCardCount)
        assertNotEquals("the buried card left the scheduler; a fresh card is presented",
            oldCard, h.state.currentCardId)
    }

    // ------------------------------------------------------------------- VER-21I-36  suspend effect

    @Test
    fun `a confirmed suspend behaves exactly like bury at the session level`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        val oldCard = h.state.currentCardId!!
        val nextBefore = h.fake.nextCardCount
        h.requestAction(ReviewerAction.SuspendCard)
        h.drain()

        assertEquals(ReviewerActionStatus.APPLIED, h.store.durableRecords().single().status)
        assertEquals(nextBefore + 1, h.fake.nextCardCount)
        assertEquals(0, b.nextCardCount)
        assertNotEquals("the suspended card left the scheduler", oldCard, h.state.currentCardId)
    }

    // ------------------------------------------------------------------- VER-21I-37  stale

    @Test
    fun `an old action callback cannot advance or modify a newer session`() = runTest {
        val b = backendB("999")
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        h.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost"))))
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()
        assertEquals(ReviewerActionStatus.AMBIGUOUS, h.action?.status)
        val staleEpoch = h.state.epoch
        val actionId = h.action!!.actionId

        // Session A ends; a new session starts on B.
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()
        h.send(AnkiStudyEvent.Start(AnkiStudyRequest(
            studySessionId = "study-2",
            preference = AnkiBackendMode.PC_AGENT,
            deck = ReviewerActionHarness.deckFor(bId),
            speakQuestion = false,
            reviewerActions = b.reviewerActionCapabilities()
        )))
        h.drain()
        assertNotNull("session B is live", h.state.anki?.reviewSession)
        assertEquals(bId, h.state.anki!!.reviewSession!!.context.backendId)
        val sessionBCardId = h.state.currentCardId

        // The stale A callback arrives now.
        val stale = AnkiStudyEvent.ReviewerActionResolved(
            staleEpoch, actionId,
            AnkiReviewerActionOutcome.Applied(AnkiReviewerActionOutcome.SOURCE_BACKEND_CONFIRMED))
        h.send(stale)

        assertTrue(
            "the stale callback is refused against the new session",
            h.rejected.contains("stale-anki-action-result"))
        assertEquals("session B's card is untouched", sessionBCardId, h.state.currentCardId)
        assertEquals("session B's backend identity is untouched", bId,
            h.state.anki!!.reviewSession!!.context.backendId)
        // The valid durable evidence for A's action is not discarded — it remains for A's owner.
        assertEquals(ReviewerActionStatus.AMBIGUOUS, h.store.durableRecords().single().status)
        assertEquals(aId, h.store.durableRecords().single().backendId)
        assertEquals(0, b.reviewerActionInvocations)
    }

    // ------------------------------------------------------------------- VER-21I-38  session end

    @Test
    fun `an unresolved action survives session termination and keeps its owner`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        h.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost"))))
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()
        assertEquals(ReviewerActionStatus.AMBIGUOUS, h.action?.status)

        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()

        val record = h.store.durableRecords().single()
        assertEquals("ending the session never erases the transaction truth",
            ReviewerActionStatus.AMBIGUOUS, record.status)
        assertEquals(aId, record.backendId)
        assertEquals(0, b.reviewerActionInvocations)
        assertEquals(0, b.reviewerActionReconciliationCount)
    }

    // ------------------------------------------------------------------- VER-21I-39  legacy

    @Test
    fun `an unreadable action ledger routes nothing and fabricates no owner`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        h.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost"))))
        h.requestAction(ReviewerAction.BuryCard)
        h.drain()
        val actionId = h.action!!.actionId
        h.restart()

        // The durable bytes are corrupt: the ledger is unreadable, never "empty".
        h.store.overwrite("not-json")
        h.restart()

        val recovered = h.coordinator().recover(actionId)
        assertTrue("unknown is not absent", recovered is ReviewerActionRecoveryOutcome.Indeterminate)
        assertEquals("an unreadable ledger produces an empty plan, never a guessed owner",
            emptyMap<AnkiBackendId, BackendTransactionRoute>(), h.executor.actionRecoveryPlan())
        assertEquals("the bytes are preserved, not erased", "not-json", h.store.snapshot)
        assertEquals(0, b.reviewerActionInvocations)
    }

    // ------------------------------------------------------------------- VER-21I-40  legacy ok

    @Test
    fun `a legacy record with a full identity routes to its own backend`() = runTest {
        val b = backendB()
        // Seed a historical record for A in a fresh durable cell (schema v1, full identity).
        val store = InMemoryReviewerActionStore()
        val seed = ReviewerActionRequest(
            sessionId = "study-legacy",
            turnId = ReviewTurnId("turn-legacy"),
            cardRef = ReviewerActionHarness.card("77", aId).ref,
            action = ReviewerAction.BuryCard
        ).toRecord(1_000L)
        store.restart(clock = { 1_000L }).create(seed)

        val h = ReviewerActionHarness(store = store, extraBackends = listOf(b))
        val plan = h.executor.actionRecoveryPlan()
        assertTrue("the legacy record routes to the backend it names", plan[aId] is BackendTransactionRoute.Routed)
        assertEquals(aId, h.store.durableRecords().single().backendId)
        assertEquals(0, b.reviewerActionInvocations)
    }

    // ----------------------------------------- VER-21I-41  keyless historical state is not proof

    @Test
    fun `current card state cannot resolve a keyless ambiguous historical action`() = runTest {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        // Applied on the fake, response lost by process death: the effect is real.
        h.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.ConfirmedApplied(
                    ReviewerActionReceipt(aId, "bury", detail = "confirmed_by_fake")),
                crashAfterApply = true,
                reconciliation = ReviewerActionReconciliationResult.ConfirmedApplied(
                    ReviewerActionReceipt(aId, "bury", detail = "synthetic_current_state_observation"))))
        h.requestAction(ReviewerAction.BuryCard)
        val effect = h.takePerformEffect()
        var crashed = false
        try {
            h.run(effect)
        } catch (death: ProcessDeathSimulation) {
            crashed = true
        }
        assertTrue(crashed)
        assertEquals(1, h.fake.reviewerActionInvocations)
        assertEquals(1, h.fake.reviewerActionBoundaryCrossings)

        h.restart()
        val record = h.store.durableRecords().single()
        assertEquals(ReviewerActionStatus.SUBMITTING, record.status)
        h.coordinator().recover(record.actionId)

        assertEquals("the ambiguous historical request remains unresolved",
            ReviewerActionStatus.AMBIGUOUS, h.store.durableRecords().single().status)
        assertEquals("recovery does not resubmit the action", 1, h.fake.reviewerActionInvocations)
        assertEquals(1, h.fake.reviewerActionBoundaryCrossings)
        assertEquals("no card-state probe is permitted without an authoritative collection match",
            0, h.fake.reviewerActionReconciliationCount)
        assertEquals(0, b.reviewerActionInvocations)
        assertEquals(0, b.reviewerActionBoundaryCrossings)
        assertEquals(0, b.reviewerActionReconciliationCount)
    }

    // ------------------------------------------------------------------- restore barrier

    @Test
    fun `an unresolved action holds the session-restore barrier like a commit would`() = runTest {
        // Seed a SUBMITTING action for the session the lock belongs to.
        val store = InMemoryReviewerActionStore()
        val request = ReviewerActionRequest(
            sessionId = "study-1",
            turnId = ReviewTurnId("turn-1"),
            cardRef = ReviewerActionHarness.card("A", aId).ref,
            action = ReviewerAction.BuryCard
        ).toRecord(1_000L)
        val seedLedger = store.restart(clock = { 1_000L })
        seedLedger.create(request)
        seedLedger.transition(request.actionId, ReviewerActionStatus.PREPARED,
            ReviewerActionTransition.EnterMutationBoundary)

        val h = ReviewerActionHarness(store = store)
        val lock = StudySessionBackendLock(
            studySessionId = "study-1",
            backendId = aId,
            collection = null,
            deckRef = h.deck,
            startedAtEpochMs = 1_000L
        )
        val decoded = StudySessionLockCodec.decode(StudySessionLockCodec.encode(lock))
        val restored = h.executor.restoreSession(decoded)
        val restoredState = restored as StudySessionRestore.Restored
        assertEquals(
            "the unresolved action holds the next-card barrier",
            true, restoredState.hasUnresolvedTransactions)

        // Control: a fresh process with no unresolved actions.
        val h2 = ReviewerActionHarness()
        val lock2 = StudySessionBackendLock(
            studySessionId = "study-1",
            backendId = aId,
            collection = null,
            deckRef = h2.deck,
            startedAtEpochMs = 1_000L
        )
        val decoded2 = StudySessionLockCodec.decode(StudySessionLockCodec.encode(lock2))
        val restored2 = h2.executor.restoreSession(decoded2) as StudySessionRestore.Restored
        assertEquals(false, restored2.hasUnresolvedTransactions)
    }
}
