package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.InMemoryReviewCommitStore
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
 * GATE 21H PART III — review-commit routing, ownership, identity and duplicate/stale-input safety.
 *
 * The topology is always "more than one backend", because that is the only way "the rating went
 * somewhere else" is observable at all: A is the session's locked backend, B is registered and
 * ready (sometimes holding the *same* numeric card ids), and every assertion includes B's counters.
 *
 * Nothing here re-tests GATE 11's arithmetic; each case pins a 21H invariant against the real
 * reducer, the real ledger, the real executor and real routing policy.
 */
class Gate21hReviewCommitRoutingTest {

    private val aId = AnkiBackendId.AnkiDroidLocal
    private val bId = AnkiBackendId.PcAgent("gate21h-b")

    /** The substitute backend: registered, ready, holding the *same* numeric card ids as A. */
    private fun backendB(vararg cardIds: String = arrayOf("123")) = FakeAnkiBackend(
        id = bId,
        decks = listOf(AnkiDeck(AnkiCommitHarness.deckFor(bId), "B Deck")),
        cards = cardIds.map { AnkiCommitHarness.card(it, bId) },
        instanceId = "gate21h-b"
    ).apply {
        // B is a keyed backend: it reports the collection its own card/deck refs name, so a record
        // it owns reaches an authoritative match with the collection it actually serves.
        currentCollectionIdentity = AnkiCollectionIdentity(bId, AnkiCommitHarness.COLLECTION_KEY)
    }

    private fun keyedBackend(id: AnkiBackendId, collectionKey: String?) = FakeAnkiBackend(
        id = id,
        decks = listOf(AnkiDeck(AnkiCommitHarness.deckFor(id), "$id Deck")),
        cards = listOf(AnkiCommitHarness.card("123", id)),
        instanceId = "$id"
    ).apply {
        currentCollectionIdentity = collectionKey?.let { AnkiCollectionIdentity(id, it) }
    }

    private fun requestFor(backendId: AnkiBackendId, sessionId: String = "study-1") = CommitRatingRequest(
        commitId = ReviewCommitId(backendId, sessionId, ReviewTurnId("turn-1")),
        card = AnkiCommitHarness.card("123", backendId).ref,
        rating = Rating.GOOD,
        ratedAtEpochMs = 1L,
        deckRef = AnkiCommitHarness.deckFor(backendId),
        collectionRef = AnkiCollectionIdentity(backendId, AnkiCommitHarness.COLLECTION_KEY)
    )

    // ------------------------------------------------------------------- VER-21H-01/02  ownership

    @Test
    fun `a rating bound to A commits on A only, with every B counter at zero`() = runTest {
        val b = backendB()
        val h = AnkiCommitHarness(extraBackends = listOf(b))
        h.loadToRating()
        h.rate(Rating.GOOD)
        h.run(h.takeCommitEffect())

        assertEquals(ReviewCommitStatus.COMMITTED, h.commit!!.status)
        assertEquals(aId, h.store.durableRecords().single().backendId)
        assertEquals(1, h.fake.physicalCommitCalls)
        assertEquals("B must receive zero commit calls", 0, b.physicalCommitCalls)
        assertEquals("B must receive zero next-card calls", 0, b.nextCardCount)
        assertEquals("B must receive zero reconciliation reads", 0, b.reconcileCalls)
        assertEquals("B must receive zero deck reads", 0, b.getDecksCalls)
        assertEquals("B must receive zero hydration reads", 0, b.hydrateCalls)
    }

    // ----------------------------------------------------------------------- VER-21H-03  preference

    @Test
    fun `a preference change before the rating cannot move the session's destination`() = runTest {
        val b = backendB()
        val h = AnkiCommitHarness(extraBackends = listOf(b))
        h.startAndLoad()

        // The user switches the global preference to the PC backend mid-session…
        val resolver = AnkiBackendResolver(AnkiBackendRegistry(listOf(h.backend, b)))
        val resolved = resolver.resolve(
            BackendResolutionRequest(AnkiBackendMode.PC_AGENT, setOf(AnkiRequiredCapability.REVIEW))
        )
        assertEquals(
            "the preference now resolves to B — and that is irrelevant to this session",
            bId, (resolved as BackendResolutionResult.Resolved).backendId
        )

        // …and the rating still goes to the backend the session was admitted on.
        h.send(StudyEvent.UserRequestAnswer(h.state.currentCardId))
        h.rate(Rating.EASY)
        h.drain()
        assertEquals(1, h.fake.physicalCommitCalls)
        assertEquals(0, b.physicalCommitCalls)
        assertEquals(aId, h.store.durableRecords().single().backendId)
        assertEquals(aId, h.state.anki!!.reviewSession!!.context.backendId)
    }

    // ------------------------------------------------------------------------------ VER-21H-04  AUTO

    @Test
    fun `AUTO eligibility changing while a rating is pending leaves the owner unchanged`() = runTest {
        val b = backendB()
        val h = AnkiCommitHarness(extraBackends = listOf(b))
        h.loadToRating()
        h.rate(Rating.GOOD)
        val effect = h.takeCommitEffect()

        // AUTO would now pick B: A reports itself unavailable and B is ready.
        h.fake.setAvailability(AnkiAvailability.TemporarilyUnavailable())
        val resolver = AnkiBackendResolver(AnkiBackendRegistry(listOf(h.backend, b)))
        assertTrue(
            resolver.resolve(BackendResolutionRequest(AnkiBackendMode.AUTO))
                is BackendResolutionResult.Resolved
        )

        h.run(effect)

        assertEquals(aId, h.store.durableRecords().single().backendId)
        assertEquals(aId, effect.request.commitId.backendId)
        assertEquals("an AUTO change never reroutes a durable transaction", 0, b.physicalCommitCalls)
    }

    // ---------------------------------------------------------------------------- VER-21H-05  cardId

    @Test
    fun `the same numeric card id on two backends never authorizes a B mutation`() = runTest {
        val b = backendB("123") // B also contains cardId 123
        val h = AnkiCommitHarness(
            cards = listOf(AnkiCommitHarness.card("123", aId)),
            extraBackends = listOf(b)
        )
        h.startAndLoad()
        assertEquals("123", h.turn!!.cardRef.cardId)

        h.send(StudyEvent.UserRequestAnswer(h.state.currentCardId))
        h.rate(Rating.HARD)
        h.drain()

        assertEquals(1, h.fake.physicalCommitCalls)
        assertEquals("a matching card id is not identity", 0, b.physicalCommitCalls)
        val record = h.store.durableRecords().single()
        assertEquals("A/123 stays A-owned", aId, record.backendId)
        assertEquals("123", record.card.cardId)
    }

    // ------------------------------------------------------------------- VER-21H-06  lock vs effect

    @Test
    fun `a commit effect naming another backend than the session lock is refused before dispatch`() =
        runTest {
            val locks = AnkiStudyBackendLockTracker()
            val b = backendB()
            val h = AnkiCommitHarness(extraBackends = listOf(b), sessionLocks = locks)
            h.loadToRating()
            val sessionId = h.state.anki!!.reviewSession!!.context.studySessionId
            assertEquals(aId, locks.lockedBackendFor(sessionId))

            // A transaction whose ownership says B, presented while the session is locked to A.
            val foreign = requestFor(bId)
            val event = h.executor.execute(
                AnkiStudyEffect.CommitRating(h.state.epoch, foreign)
            ) as AnkiStudyEvent.RatingCommitResolved

            val outcome = event.outcome as AnkiCommitOutcome.Failed
            assertEquals(AnkiStudyEffectExecutor.SESSION_BACKEND_MISMATCH, outcome.category)
            assertFalse("refused, not dispatched", outcome.dispatched)
            assertEquals(0, b.physicalCommitCalls)
            assertEquals("no durable intent for a backend the session does not own",
                emptyList<ReviewCommitRecord>(), h.store.durableRecords())
            assertEquals("the turn is still unsubmitted", 0, h.fake.physicalCommitCalls)
        }

    // ------------------------------------------------------------- VER-21H-07  verified mismatch

    @Test
    fun `a collection that verifiably changed refuses the mutation before it is dispatched`() = runTest {
        val pcId = AnkiBackendId.PcAgent("gate21h-keyed")
        val h = AnkiCommitHarness(backendId = pcId, mode = AnkiBackendMode.PC_AGENT)
        h.loadToRating()
        h.rate(Rating.GOOD)
        val effect = h.takeCommitEffect()
        assertEquals(
            "the record binds the collection it was admitted against",
            AnkiCommitHarness.COLLECTION_KEY, effect.request.collectionRef?.collectionKey
        )

        // The backend reconnects on a different collection.
        h.fake.currentCollectionIdentity = AnkiCollectionIdentity(pcId, "collection-b")
        h.run(effect)

        assertEquals("verified mismatch must not dispatch", 0, h.fake.physicalCommitCalls)
        val record = h.store.durableRecords().single()
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, record.status)
        assertEquals(ReviewCommitResolution.REFUSED_BEFORE_DISPATCH, record.resolution)
        assertEquals("collection_mismatch", record.failure?.category)
    }

    // -------------------------------------------------------------------- VER-21H-08  degraded key

    @Test
    fun `evidence that degraded from a known collection blocks instead of assuming equality`() = runTest {
        val pcId = AnkiBackendId.PcAgent("gate21h-keyed")
        val h = AnkiCommitHarness(backendId = pcId, mode = AnkiBackendMode.PC_AGENT)
        h.loadToRating()
        h.rate(Rating.GOOD)
        val effect = h.takeCommitEffect()

        // "We could tell before and cannot now": the key the record carries is no longer reported.
        h.fake.currentCollectionIdentity = null
        h.run(effect)

        assertEquals(0, h.fake.physicalCommitCalls)
        val record = h.store.durableRecords().single()
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, record.status)
        assertEquals(ReviewCommitResolution.REFUSED_BEFORE_DISPATCH, record.resolution)
        assertEquals("collection_identity_degraded", record.failure?.category)
    }

    // --------------------------------------------------------- VER-21H-09  unknown is not a match

    @Test
    fun `an unknown collection on both sides permits a mutation and never proofs a reconciliation`() =
        runTest {
            // AnkiDroid: no key on either side. Nothing is invented, a new mutation is permitted by
            // the matrix's NOMINAL column, and the sharpest cell stays blocked.
            val h = AnkiCommitHarness()
            h.loadToRating()
            h.rate(Rating.GOOD)
            h.run(h.takeCommitEffect())
            assertEquals(ReviewCommitStatus.COMMITTED, h.commit!!.status)
            assertNull("no collection identity is invented", h.store.durableRecords().single().collectionRef)

            val check = AnkiIdentityAssurancePolicy.check(
                bound = AnkiIdentityObservation(aId, null),
                live = AnkiIdentityObservation(aId, null),
                boundIsLiveInThisProcess = false
            )
            assertEquals(
                AnkiIdentityCheck.SameBackend(
                    AnkiIdentityAssurance.IdentityUnverifiable(AnkiUnverifiabilityQualifier.NOMINAL)
                ),
                check
            )
            val blocked = AnkiIdentityContinuation.continuationVerdict(AnkiOperationClass.OP_M3, check)
            assertTrue("OP-M3 is blocked without a collection proof: $blocked",
                blocked is AnkiContinuationVerdict.Blocked)
            assertEquals(
                AnkiContinuationBlockReason.RECONCILIATION_REQUIRES_AUTHORITATIVE_MATCH,
                (blocked as AnkiContinuationVerdict.Blocked).reason
            )
        }

    // ------------------------------------------------------------------ VER-21H-20  binding evidence

    @Test
    fun `binding evidence is the admitted collection, never a card or deck resource key`() = runTest {
        // AnkiDroid records carry no collection in the session context…
        val plain = AnkiCommitHarness()
        plain.loadToRating()
        plain.rate(Rating.GOOD)
        plain.drain()
        val plainRecord = plain.store.durableRecords().single()
        assertEquals("the card ref still carries a resource key",
            AnkiCommitHarness.COLLECTION_KEY, plainRecord.card.collectionKey)
        assertNull("…but it is not binding evidence", plainRecord.collectionRef)
        val policy = ReviewCommitRoutingPolicy(AnkiBackendRegistry(listOf(plain.backend)))
        assertNull(policy.bindingCollectionKey(plainRecord))

        // …while a keyed backend's record binds the collection it was admitted against.
        val pcId = AnkiBackendId.PcAgent("gate21h-keyed")
        val keyed = AnkiCommitHarness(backendId = pcId, mode = AnkiBackendMode.PC_AGENT)
        keyed.loadToRating()
        keyed.rate(Rating.GOOD)
        keyed.drain()
        val keyedRecord = keyed.store.durableRecords().single()
        assertEquals(
            AnkiCommitHarness.COLLECTION_KEY,
            ReviewCommitRoutingPolicy(AnkiBackendRegistry(listOf(keyed.backend)))
                .bindingCollectionKey(keyedRecord)
        )
    }

    // --------------------------------------------------------------- VER-21H-18  ambiguous stays put

    @Test
    fun `a preference change cannot move an ambiguous transaction off its backend`() = runTest {
        val b = backendB()
        val h = AnkiCommitHarness(
            extraBackends = listOf(b),
            commitSteps = listOf(
                FakeAnkiBackend.CommitStep(BackendCommitResult.OutcomeUnknown(), appliedWhenAmbiguous = true)
            )
        )
        h.loadToRating()
        h.rate(Rating.GOOD)
        h.drain()
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit!!.status)

        val resolver = AnkiBackendResolver(AnkiBackendRegistry(listOf(h.backend, b)))
        assertTrue(
            resolver.resolve(BackendResolutionRequest(AnkiBackendMode.PC_AGENT))
                is BackendResolutionResult.Resolved
        )

        val record = h.store.durableRecords().single()
        assertEquals(aId, record.backendId)
        assertEquals(ReviewCommitStatus.AMBIGUOUS, record.status)
        assertEquals(0, b.physicalCommitCalls)
        assertEquals(0, b.reconcileCalls)
    }

    // ------------------------------------------------------------- VER-21H-19  restart keeps truth

    @Test
    fun `restart preserves ambiguity, ownership and the blocked recovery plan`() = runTest {
        val b = backendB()
        val h = AnkiCommitHarness(
            extraBackends = listOf(b),
            commitSteps = listOf(FakeAnkiBackend.CommitStep(BackendCommitResult.OutcomeUnknown()))
        )
        h.loadToRating()
        h.rate(Rating.GOOD)
        h.drain()
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit!!.status)

        h.fullRestart()

        val record = h.store.durableRecords().single()
        assertEquals("ambiguity survives process death", ReviewCommitStatus.AMBIGUOUS, record.status)
        assertEquals("ownership survives process death", aId, record.backendId)
        val plan = h.executor.recoveryPlan()
        assertEquals(aId, plan.keys.single())
        assertTrue(
            "A is registered, so routing itself does not block it: $plan",
            plan.getValue(aId) is BackendTransactionRoute.Routed
        )
        assertEquals("the record is still unresolved", 1, h.ledger.unresolved().size)
        assertEquals("nothing was handed to B", emptyList<ReviewCommitRecord>(), h.ledger.pendingRecovery(bId))
    }

    // ------------------------------------------------------------------ VER-21H-20  no blind retry

    @Test
    fun `an ambiguous transaction is never offered an automatic or accidental retry`() = runTest {
        val h = AnkiCommitHarness(
            commitSteps = listOf(FakeAnkiBackend.CommitStep(BackendCommitResult.OutcomeUnknown()))
        )
        h.loadToRating()
        h.rate(Rating.GOOD)
        h.drain()
        val commitId = h.commit!!.commitId
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.ledger.get(commitId)!!.status)
        val ui = RatingCommitRecoveryUi.from(h.state)!!
        assertFalse("an ambiguous rating is not retryable", ui.canRetry)
        assertTrue("the session must be blocked for verification",
            h.state.phase == SessionPhase.ReconciliationRequired)

        // Neither UI projection nor a persistence failure can turn it into a retry.
        val refused = h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, commitId))
        assertFalse(refused.accepted)
        assertEquals(1, h.fake.physicalCommitCalls)
    }

    // ----------------------------------------------------- VER-21H-24  the owner is gone (removed)

    @Test
    fun `a removed original backend blocks its records and never lends another backend`() = runTest {
        val goneId = AnkiBackendId.PcAgent("gate21h-removed")
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock = { 1L })
        val request = requestFor(goneId)
        assertTrue(ledger.prepare(request) is ReviewCommitLedger.PrepareResult.Prepared)

        val b = backendB("123")
        // A process in which only B is registered.
        val executor = AnkiStudyEffectExecutor(
            registry = AnkiBackendRegistry(listOf(b)),
            ledger = ledger,
            clock = { 1L }
        )
        assertEquals(
            BackendTransactionRoute.Blocked(goneId, BackendTransactionBlockReason.BACKEND_NOT_REGISTERED),
            executor.recoveryPlan().getValue(goneId)
        )

        // The pending transaction is attempted again: refused before any dispatch, and B untouched.
        val event = executor.execute(
            AnkiStudyEffect.CommitRating(0L, request)
        ) as AnkiStudyEvent.RatingCommitResolved
        val outcome = event.outcome as AnkiCommitOutcome.Failed
        assertEquals("backend_not_registered", outcome.category)
        assertFalse(outcome.dispatched)
        val record = ledger.get(request.commitId)!!
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, record.status)
        assertEquals(ReviewCommitResolution.REFUSED_BEFORE_DISPATCH, record.resolution)
        assertEquals(0, b.physicalCommitCalls)
        assertEquals(0, b.reconcileCalls)
        assertEquals("the record is preserved, not migrated", goneId, store.durableRecords().single().backendId)
    }

    // ------------------------------------------------- VER-21H-25/26  reconnect is not the proof

    @Test
    fun `recovery is gated on the original backend returning with the same collection`() = runTest {
        val pcId = AnkiBackendId.PcAgent("gate21h-a")
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock = { 1L })
        val request = requestFor(pcId)
        assertTrue(ledger.prepare(request) is ReviewCommitLedger.PrepareResult.Prepared)

        val pc = keyedBackend(pcId, AnkiCommitHarness.COLLECTION_KEY)
        val executor = AnkiStudyEffectExecutor(
            registry = AnkiBackendRegistry(listOf(pc)),
            ledger = ledger,
            clock = { 1L }
        )

        // The original backend is down: waiting, never reassigned.
        pc.setAvailability(AnkiAvailability.TemporarilyUnavailable())
        assertEquals(
            BackendTransactionRoute.Blocked(pcId, BackendTransactionBlockReason.BACKEND_UNREACHABLE),
            executor.recoveryPlan().getValue(pcId)
        )

        // It reconnects but cannot name the collection the record was written against: waiting.
        pc.setAvailability(AnkiAvailability.Ready(pc.capabilities.value))
        pc.currentCollectionIdentity = null
        assertEquals(
            BackendTransactionRoute.Blocked(pcId, BackendTransactionBlockReason.COLLECTION_IDENTITY_UNAVAILABLE),
            executor.recoveryPlan().getValue(pcId)
        )

        // It names a *different* one: mismatch-grade, still blocked.
        pc.currentCollectionIdentity = AnkiCollectionIdentity(pcId, "collection-b")
        assertEquals(
            BackendTransactionRoute.Blocked(pcId, BackendTransactionBlockReason.COLLECTION_MISMATCH),
            executor.recoveryPlan().getValue(pcId)
        )

        // Same backend, same collection: routed — the record is still unresolved and needs its
        // family's evidence, which a reconnect never is.
        pc.currentCollectionIdentity = AnkiCollectionIdentity(pcId, AnkiCommitHarness.COLLECTION_KEY)
        assertTrue(executor.recoveryPlan().getValue(pcId) is BackendTransactionRoute.Routed)
        assertEquals(ReviewCommitStatus.PREPARED, ledger.get(request.commitId)!!.status)
        assertEquals("no scheduler read was issued by any of the routing", 0, pc.reconcileCalls)
        assertEquals(0, pc.physicalCommitCalls)
    }

    // --------------------------------------------------------- VER-21H-21/22/23  group isolation

    @Test
    fun `one backend's outage never blocks another backend's recovery group`() = runTest {
        val a = backendB("123") // the "other" registered backend, owned by its own record
        val downId = AnkiBackendId.PcAgent("gate21h-down")
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock = { 1L })
        assertTrue(ledger.prepare(requestFor(bId)) is ReviewCommitLedger.PrepareResult.Prepared)
        assertTrue(ledger.prepare(requestFor(downId)) is ReviewCommitLedger.PrepareResult.Prepared)

        val down = keyedBackend(downId, AnkiCommitHarness.COLLECTION_KEY)
        down.setAvailability(AnkiAvailability.TemporarilyUnavailable())
        val executor = AnkiStudyEffectExecutor(
            registry = AnkiBackendRegistry(listOf(a, down)),
            ledger = ledger,
            clock = { 1L }
        )

        val plan = executor.recoveryPlan()
        assertEquals(setOf(bId, downId), plan.keys)
        assertTrue("B's record is recoverable: $plan", plan.getValue(bId) is BackendTransactionRoute.Routed)
        assertEquals(
            BackendTransactionRoute.Blocked(downId, BackendTransactionBlockReason.BACKEND_UNREACHABLE),
            plan.getValue(downId)
        )
        assertEquals("both records are still unresolved", 2, ledger.unresolved().size)
    }

    // --------------------------------------------------------- VER-21H-27/28  duplicate input

    @Test
    fun `repeated and conflicting rating input produces exactly one mutation`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate(Rating.GOOD)
        // The user taps again (the same rating) and then a different one while the first is pending.
        h.rate(Rating.GOOD)
        h.rate(Rating.EASY)
        assertTrue(
            "the second tap is rejected, not queued: ${h.rejected}",
            h.rejected.contains("duplicate-anki-rating")
        )
        assertTrue(
            "a different rating never replaces the locked one: ${h.rejected}",
            h.rejected.contains("anki-rating-locked-first-wins")
        )
        assertEquals(
            "one commit effect, never two",
            1, h.pending.count { it is AnkiStudyEffect.CommitRating }
        )

        h.drain()
        assertEquals(1, h.fake.physicalCommitCalls)
        assertEquals(Rating.GOOD, h.store.durableRecords().single().rating)
    }

    @Test
    fun `a duplicate execution of the same commit effect is not a second mutation`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate(Rating.GOOD)
        val effect = h.takeCommitEffect()

        // The same effect delivered twice while the first execution sits at the durable boundary.
        h.fake.mutationGate = CompletableDeferred()
        val first = launch { h.executor.execute(effect) }
        runCurrent()
        val second = h.executor.execute(effect) // SUBMITTING: answered from the ledger, no dispatch
        h.fake.mutationGate!!.complete(Unit)
        first.join()

        assertNull("a duplicate returns no new event", second)
        assertEquals(1, h.fake.physicalCommitCalls)
        val record = h.store.durableRecords().single()
        assertEquals(ReviewCommitStatus.COMMITTED, record.status)
        assertEquals("one attempt, never a second", 1, record.attemptCount)
    }

    // ------------------------------------------------------------------ VER-21H-29  stale callback

    @Test
    fun `a stale commit result from an ended session cannot advance a new one`() = runTest {
        val h = AnkiCommitHarness()
        h.loadToRating()
        h.rate(Rating.GOOD)
        val effect = h.takeCommitEffect() // the transport is already in the user's hands
        val oldCommitId = effect.request.commitId

        // The user ends the session while that transaction is still unresolved.
        h.send(StudyEvent.UserEndRequested("done"))
        h.drain()

        // A new session starts…
        h.send(AnkiStudyEvent.Start(AnkiStudyRequest("study-2", AnkiBackendMode.ANKIDROID_LOCAL, h.deck)))
        h.drain()
        val newCardId = h.turn?.cardRef?.cardId

        // …and the old session's result arrives late.
        h.send(
            AnkiStudyEvent.RatingCommitResolved(
                effect.epoch, oldCommitId,
                AnkiCommitOutcome.Committed(AnkiCommitOutcome.SOURCE_BACKEND_CONFIRMED)
            )
        )

        assertTrue(
            "the stale result is rejected: ${h.rejected}",
            h.rejected.contains("stale-anki-commit-result")
        )
        assertEquals("the new session does not advance", newCardId, h.turn?.cardRef?.cardId)
    }

    // ------------------------------------------------------- VER-21H-30  ending keeps the record

    @Test
    fun `an unresolved transaction survives a session ending and keeps its owner`() = runTest {
        val b = backendB()
        val h = AnkiCommitHarness(
            extraBackends = listOf(b),
            commitSteps = listOf(FakeAnkiBackend.CommitStep(BackendCommitResult.OutcomeUnknown()))
        )
        h.loadToRating()
        h.rate(Rating.GOOD)
        h.run(h.takeCommitEffect())
        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.commit!!.status)

        // The user ends the session while the outcome is unknown. Ending is never a resolution.
        h.send(StudyEvent.UserEndRequested("end"))
        h.drain()

        val record = h.store.durableRecords().single()
        assertEquals(aId, record.backendId)
        assertEquals(aId, record.commitId.backendId)
        assertEquals(
            "the transaction is preserved unresolved: ${record.status}",
            ReviewCommitStatus.AMBIGUOUS, record.status
        )
        assertEquals(1, h.ledger.unresolved().size)
        assertEquals(0, b.physicalCommitCalls)
        assertEquals(0, b.reconcileCalls)
    }

    // --------------------------------------------------------------- VER-21H-32  unreadable bytes

    @Test
    fun `an unreadable ledger routes nothing and fabricates no owner`() = runTest {
        val store = InMemoryReviewCommitStore(
            """{"schemaVersion":99,"records":[],"tombstones":[]}"""
        )
        val ledger = store.restart({ 1L })
        assertTrue(ledger.health() is ReviewCommitLedger.Health.Unavailable)

        val b = backendB()
        val executor = AnkiStudyEffectExecutor(
            registry = AnkiBackendRegistry(listOf(b)),
            ledger = ledger,
            clock = { 1L }
        )
        assertTrue("nothing is routed to the current preference", executor.recoveryPlan().isEmpty())
        assertEquals(0, b.physicalCommitCalls)
        assertEquals(0, b.reconcileCalls)
    }

    // ------------------------------------------------------- VER-21H-33  legacy identity kept

    @Test
    fun `a record whose binding collection was dropped keeps its owner and stays unresolved`() = runTest {
        val pcId = AnkiBackendId.PcAgent("gate21h-keyed")
        val h = AnkiCommitHarness(backendId = pcId, mode = AnkiBackendMode.PC_AGENT)
        h.loadToRating()
        h.rate(Rating.GOOD)
        val effect = h.takeCommitEffect()
        h.ledger.prepare(effect.request) // durable PREPARED row, nothing dispatched
        val prepared = h.store.durableRecords().single()
        assertEquals(AnkiCommitHarness.COLLECTION_KEY, prepared.collectionRef?.collectionKey)

        // A record written by an older build: same identity, no binding collection reference (the
        // resource keys on the card and deck are *not* binding evidence).
        val legacy = prepared.copy(collectionRef = null)
        val raw = ReviewCommitLedgerCodec.encode(listOf(legacy))
        val restored = InMemoryReviewCommitStore(raw).restart({ 1L }).snapshot().single()
        assertEquals("the owner is preserved verbatim", pcId, restored.backendId)
        assertEquals(ReviewCommitStatus.PREPARED, restored.status)
        assertNull(restored.collectionRef)
        assertEquals("the card's resource key is still not binding evidence",
            AnkiCommitHarness.COLLECTION_KEY, restored.card.collectionKey)

        // With no binding evidence there is nothing to compare, so a keyed backend that no longer
        // reports a key is *not* a mismatch — and the record was never silently re-owned.
        val pc = keyedBackend(pcId, null)
        val policy = ReviewCommitRoutingPolicy(AnkiBackendRegistry(listOf(pc)))
        assertNull(policy.bindingCollectionKey(restored))

        val pending = InMemoryReviewCommitStore(ReviewCommitLedgerCodec.encode(listOf(legacy)))
            .restart({ 1L })
        val executor = AnkiStudyEffectExecutor(
            registry = AnkiBackendRegistry(listOf(pc)),
            ledger = pending,
            clock = { 1L }
        )
        assertEquals("no key recorded, nothing to compare: routed to its own backend",
            pcId, executor.recoveryPlan().keys.single())
        assertTrue(executor.recoveryPlan().getValue(pcId) is BackendTransactionRoute.Routed)
    }
}
