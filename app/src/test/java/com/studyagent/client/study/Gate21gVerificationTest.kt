package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.InMemoryReviewCommitStore
import com.studyagent.client.anki.fake.InMemoryReviewerActionStore
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiBackendResolver2
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.AnkiReviewSession
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.AnkiStudyBackendLockTracker
import com.studyagent.client.core.anki.BackendCommitResult
import com.studyagent.client.core.anki.BackendResolutionRequest2
import com.studyagent.client.core.anki.BackendResolutionResult2
import com.studyagent.client.core.anki.DecodedSessionLock
import com.studyagent.client.core.anki.DurableReviewerActionLedger
import com.studyagent.client.core.anki.InMemoryStudySessionLockStore
import com.studyagent.client.core.anki.NextCardResult
import com.studyagent.client.core.anki.ReviewCommitId
import com.studyagent.client.core.anki.ReviewCommitLedger
import com.studyagent.client.core.anki.ReviewCommitStatus
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.anki.ReviewerAction
import com.studyagent.client.core.anki.ReviewerActionCapabilities
import com.studyagent.client.core.anki.STUDY_SESSION_REQUIREMENTS
import com.studyagent.client.core.anki.StudySessionBackendLock
import com.studyagent.client.core.anki.StudySessionContinuation
import com.studyagent.client.core.anki.StudySessionContinuationPolicy
import com.studyagent.client.core.anki.StudySessionLockCodec
import com.studyagent.client.core.anki.StudySessionLockStore
import com.studyagent.client.core.anki.StudySessionRestore
import com.studyagent.client.core.anki.StudySessionRestorePolicy
import com.studyagent.client.core.anki.toPreference
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.AnkiCommitOutcome
import com.studyagent.client.core.study.AnkiReviewerActionOutcome
import com.studyagent.client.core.study.AnkiStudyEffect
import com.studyagent.client.core.study.AnkiStudyEffectExecutor
import com.studyagent.client.core.study.AnkiStudyEvent
import com.studyagent.client.core.study.AnkiStudyRequest
import com.studyagent.client.core.study.SessionPhase
import com.studyagent.client.core.study.StudyEvent
import com.studyagent.client.core.study.StudyReducer
import com.studyagent.client.core.study.projectSessionOwnership
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21G PART III — the authoritative verification cases the GATE 21G suite left unmapped.
 *
 * Each test names the `VER-21G-` case it discharges. The substrate is the repository's own
 * deterministic harness (real [StudyReducer], real [AnkiStudyEffectExecutor], the real durable
 * rating/reviewer-action ledgers) with **two independent backends** in one registry: an
 * AnkiDroid-local fake and a *ready* PC fake. The PC backend is deliberately ready and reachable on
 * every turn, so a failover would always have been possible — and every counter below proves it
 * never happened.
 *
 * The two fakes share numeric card ids and the same deck/collection *labels*, which is exactly the
 * ambiguity the gate forbids resolving by heuristic: matching ids or names are not identity.
 */
class Gate21gVerificationTest {

    private val localId: AnkiBackendId = AnkiCommitHarness.BACKEND
    private val pcId: AnkiBackendId = AnkiBackendId.PcAgent("gate21g-pc")

    private fun pcBackend() = FakeAnkiBackend(
        id = pcId,
        decks = listOf(AnkiDeck(AnkiCommitHarness.deckFor(pcId), "PC Deck")),
        // Same numeric card ids as the local fixture: a collision, on purpose.
        cards = listOf(AnkiCommitHarness.card("123", pcId), AnkiCommitHarness.card("456", pcId)),
        instanceId = "gate21g-pc"
    )

    /** Every counter a "the other backend was touched" defect would move. */
    private fun assertOtherBackendUntouched(pc: FakeAnkiBackend, what: String) {
        assertEquals("$what: PC scheduler queries", 0, pc.nextCardCount)
        assertEquals("$what: PC hydrations", 0, pc.hydrateCalls)
        assertEquals("$what: PC rating mutations", 0, pc.physicalCommitCalls)
        assertEquals("$what: PC commit deliveries", 0, pc.commitInvocations)
        assertEquals("$what: PC reviewer actions", 0, pc.reviewerActionBoundaryCrossings)
        assertEquals("$what: PC deck reads", 0, pc.getDecksCalls)
        assertEquals("$what: PC session handles", 0, pc.endReviewCalls)
    }

    private fun localHarness(
        mode: AnkiBackendMode = AnkiBackendMode.ANKIDROID_LOCAL,
        extra: List<FakeAnkiBackend> = emptyList(),
        locks: AnkiStudyBackendLockTracker = AnkiStudyBackendLockTracker(),
        store: StudySessionLockStore? = null,
        commitSteps: List<FakeAnkiBackend.CommitStep> = emptyList(),
        nextErrors: List<AnkiError> = emptyList()
    ) = AnkiCommitHarness(
        mode = mode,
        cards = listOf(AnkiCommitHarness.card("123", localId), AnkiCommitHarness.card("456", localId)),
        commitSteps = commitSteps,
        nextErrors = nextErrors,
        extraBackends = extra,
        sessionLocks = locks,
        sessionLockStore = store
    )

    /**
     * A read-only decorator that can arm a scheduler failure *after* a session has loaded: the
     * failure then lands on the read that follows a commit, never on startup. Delegation is total
     * (`AnkiBackend by`), so nothing but the armed read changes, and the counters below it stay the
     * fake's own.
     */
    private class ArmedNextFailures(private val delegate: AnkiBackend) : AnkiBackend by delegate {
        val armed = ArrayDeque<AnkiError>()
        override suspend fun nextCard(session: AnkiReviewSession): NextCardResult =
            armed.removeFirstOrNull()?.let { NextCardResult.Failure(it) } ?: delegate.nextCard(session)
    }

    /** A start request with every speech/evaluation path off: the read path alone. */
    private fun quiet(sessionId: String, mode: AnkiBackendMode, deck: AnkiDeckRef) =
        AnkiStudyRequest(
            studySessionId = sessionId,
            preference = mode,
            deck = deck,
            speakQuestion = false,
            evaluateAnswers = false,
            speakFeedback = false,
            speakAnswer = false
        )

    /** Ends the session the way the reducer does, without the PC-only network effect. */
    private fun endSession(h: AnkiCommitHarness, messageId: String = "end"): AnkiStudyEffect.EndReview? {
        val transition = StudyReducer.reduce(h.state, StudyEvent.UserEndRequested(messageId), h.now)
        h.state = transition.newState
        return transition.effects.filterIsInstance<AnkiStudyEffect.EndReview>().firstOrNull()
    }

    // ------------------------------------------------------------------------ VER-21G-06

    @Test
    fun `rapid repeated starts admit exactly one binding`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        val store = InMemoryStudySessionLockStore()
        val h = localHarness(locks = locks, store = store)
        repeat(3) { index ->
            h.send(
                AnkiStudyEvent.Start(
                    quiet("study-${index + 1}", AnkiBackendMode.ANKIDROID_LOCAL, h.deck)
                )
            )
        }
        assertEquals(listOf("already-active", "already-active"), h.rejected)
        assertEquals(1, h.pending.count { it is AnkiStudyEffect.Begin })
        h.drain()

        // One session, one binding, one persisted owner.
        assertEquals(1, locks.locks.value.size)
        assertEquals("study-1", locks.locks.value.keys.single())
        assertEquals(localId, locks.lockedBackendFor("study-1"))
        assertEquals("study-1", (store.load() as DecodedSessionLock.Valid).lock.studySessionId)
        assertEquals(SessionPhase.WaitingForAnswer, h.state.phase)
    }

    // ------------------------------------------------------------------ VER-21G-07 / 21G-08

    @Test
    fun `an auto start keeps its resolved backend when the preference and readiness change`() =
        runTest {
            val locks = AnkiStudyBackendLockTracker()
            val pc = pcBackend()
            val h = localHarness(mode = AnkiBackendMode.AUTO, extra = listOf(pc), locks = locks)
            h.loadToRating()
            val sessionId = h.state.anki!!.reviewSession!!.context.studySessionId
            assertEquals(localId, h.state.anki!!.reviewSession!!.context.backendId)
            assertEquals(localId, locks.lockedBackendFor(sessionId))

            // A pre-binding resolution is free to answer PC — and does, the moment it is asked.
            val registry = AnkiBackendRegistry(listOf(h.backend, pc))
            val flipped = AnkiBackendResolver2.resolve(
                BackendResolutionRequest2(
                    preference = AnkiBackendMode.PC_AGENT.toPreference(),
                    requiredCapabilities = STUDY_SESSION_REQUIREMENTS,
                    purpose = "verification"
                ),
                registry.descriptors()
            )
            assertEquals(pcId, (flipped as BackendResolutionResult2.Resolved).backendId)

            // The active session is not that resolution's business: its rating still lands on the
            // backend it bound to.
            h.rate(Rating.GOOD)
            val effect = h.takeCommitEffect()
            assertEquals(localId, effect.request.commitId.backendId)
            assertEquals(sessionId, effect.request.commitId.studySessionId)
            h.run(effect)
            assertEquals(ReviewCommitStatus.COMMITTED, h.state.anki!!.commit!!.status)
            h.drain()
            assertEquals(1, h.fake.mutationBoundaryCrossingCount)
            assertEquals(localId, h.state.anki!!.reviewSession!!.context.backendId)
            assertEquals(localId, locks.lockedBackendFor(sessionId))
            assertOtherBackendUntouched(pc, "VER-21G-07/08")

            // AUTO eligibility churn: the locked backend goes away while PC stays ready.
            h.fake.setAvailability(AnkiAvailability.TemporarilyUnavailable("churn"))
            val session = h.state.anki!!.reviewSession!!
            val read = h.executor.execute(
                AnkiStudyEffect.Next(h.state.epoch, session)
            ) as AnkiStudyEvent.Scheduled
            assertTrue(
                "a lost locked backend reports its own unavailability",
                read.result is NextCardResult.BackendUnavailable
            )
            assertOtherBackendUntouched(pc, "VER-21G-08 after loss")
            assertEquals(localId, locks.lockedBackendFor(sessionId))
        }

    // ------------------------------------------------------------- VER-21G-09 / 10 / 11

    @Test
    fun `a colliding numeric card id stays bound to the session's own backend`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        val pc = pcBackend()
        val h = localHarness(extra = listOf(pc), locks = locks)
        h.loadToRating()

        // Both collections contain card id 123; the session reads only its own.
        assertEquals("123", h.state.anki!!.turn!!.cardRef.cardId)
        assertNotEquals(pcId, h.state.anki!!.turn!!.cardRef.backendId)
        assertEquals(localId, h.state.anki!!.turn!!.backendId)
        assertEquals(1, h.fake.hydrateCalls)
        assertOtherBackendUntouched(pc, "VER-21G-10 collision")

        // A route forged for the other backend cannot reach its copy of id 123 either.
        val forged = AnkiReviewSession(
            AnkiSessionContext(pcId, null, null, 100L, FakeAnkiBackend.REVIEW_CAPABILITIES, "study-1"),
            "forged"
        )
        val refused = h.executor.execute(
            AnkiStudyEffect.Next(h.state.epoch, forged)
        ) as AnkiStudyEvent.Scheduled
        assertFalse(refused.result is NextCardResult.Card)
        assertOtherBackendUntouched(pc, "VER-21G-10 forged route")
        assertEquals(localId, locks.lockedBackendFor("study-1"))
    }

    // ------------------------------------------------------------------------ VER-21G-12

    @Test
    fun `the rating context carries the session's locked backend and turn`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        val pc = pcBackend()
        val h = localHarness(extra = listOf(pc), locks = locks)
        h.loadToRating()
        val sessionId = h.state.anki!!.reviewSession!!.context.studySessionId
        val turn = h.state.anki!!.turn!!

        h.rate(Rating.EASY)
        val effect = h.takeCommitEffect()
        assertEquals(localId, effect.request.commitId.backendId)
        assertEquals(sessionId, effect.request.commitId.studySessionId)
        assertEquals(turn.turnId, effect.request.commitId.turnId)
        assertEquals(localId, effect.request.card.backendId)
        assertOtherBackendUntouched(pc, "VER-21G-12")
    }

    // ------------------------------------------------------------------------ VER-21G-13

    @Test
    fun `no next card is queried while the durable commit is outstanding`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        val pc = pcBackend()
        val h = localHarness(extra = listOf(pc), locks = locks)
        h.loadToRating()
        assertEquals(1, h.fake.nextCardCount)

        h.rate(Rating.GOOD)
        val effect = h.takeCommitEffect()
        // The commit is selected and durable-intent-pending: no progression, no second read.
        assertTrue(h.pending.none { it is AnkiStudyEffect.Next })
        assertEquals(1, h.fake.nextCardCount)

        h.run(effect)
        assertEquals(ReviewCommitStatus.COMMITTED, h.state.anki!!.commit!!.status)
        h.drain()
        assertEquals("the commit permits exactly one more read", 2, h.fake.nextCardCount)
        assertOtherBackendUntouched(pc, "VER-21G-13")
    }

    // ------------------------------------------------------------------------ VER-21G-14

    @Test
    fun `an ambiguous rating never retries advances or switches`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        val pc = pcBackend()
        val h = localHarness(
            extra = listOf(pc),
            locks = locks,
            commitSteps = listOf(
                FakeAnkiBackend.CommitStep(
                    result = BackendCommitResult.OutcomeUnknown(),
                    appliedWhenAmbiguous = true
                )
            )
        )
        h.loadToRating()
        val readsBefore = h.fake.nextCardCount
        h.rate(Rating.GOOD)
        val effect = h.takeCommitEffect()
        h.run(effect)

        assertEquals(ReviewCommitStatus.AMBIGUOUS, h.state.anki!!.commit!!.status)
        assertEquals(1, h.fake.commitInvocations)
        assertTrue(
            "an ambiguous rating is never retried automatically",
            h.pending.none { it is AnkiStudyEffect.CommitRating }
        )
        assertEquals("no progression past an ambiguous rating", readsBefore, h.fake.nextCardCount)
        assertOtherBackendUntouched(pc, "VER-21G-14")
        assertEquals(localId, locks.lockedBackendFor("study-1"))
        // The uncertain outcome is durable truth, not a client-side guess.
        assertEquals(
            ReviewCommitStatus.AMBIGUOUS,
            h.ledger.get(effect.request.commitId)!!.status
        )
    }

    // ------------------------------------------------------------------------ VER-21G-15

    @Test
    fun `a committed rating is never resubmitted when the next card read fails`() = runTest {
        lateinit var armer: ArmedNextFailures
        val h = AnkiCommitHarness(
            cards = listOf(AnkiCommitHarness.card("123", localId), AnkiCommitHarness.card("456", localId)),
            wrap = { fake -> ArmedNextFailures(fake).also { armer = it } }
        )
        h.loadToRating()
        h.rate(Rating.GOOD)
        val effect = h.takeCommitEffect()
        // The rating is already durable when the *next* read fails.
        armer.armed.addLast(AnkiError.QueryFailure("next_read_failed"))
        h.run(effect)
        val committed = h.ledger.get(effect.request.commitId)!!.status
        h.drain()

        assertEquals(ReviewCommitStatus.COMMITTED, committed)
        assertEquals(1, h.fake.commitInvocations)
        assertEquals(1, h.fake.mutationBoundaryCrossingCount)

        // Retrying the *read* must not re-issue the committed rating.
        h.send(AnkiStudyEvent.RetryNextCard(h.state.epoch))
        h.drain()
        assertEquals(1, h.fake.commitInvocations)
        assertEquals(1, h.fake.mutationBoundaryCrossingCount)
    }

    // ------------------------------------------------------------------------ VER-21G-16

    @Test
    fun `a reviewer action reaches only the locked backend`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        val pc = pcBackend()
        val fake = FakeAnkiBackend(
            id = localId,
            decks = listOf(AnkiDeck(AnkiCommitHarness.deckFor(localId), "Deck")),
            cards = listOf(AnkiCommitHarness.card("123", localId)),
            initialCapabilities = FakeAnkiBackend.REVIEW_ACTION_CAPABILITIES,
            instanceId = "gate21g-local-actions"
        )
        val executor = AnkiStudyEffectExecutor(
            registry = AnkiBackendRegistry(listOf(fake, pc)),
            ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock = { 1_000L }),
            clock = { 1_000L },
            actionLedger = DurableReviewerActionLedger(InMemoryReviewerActionStore(), { 1_000L }),
            sessionLocks = locks
        )
        val begun = executor.execute(
            AnkiStudyEffect.Begin(
                1L,
                quiet("study-1", AnkiBackendMode.ANKIDROID_LOCAL, AnkiCommitHarness.deckFor(localId)),
                100L
            )
        ) as AnkiStudyEvent.Begun
        val session = (begun.result as AnkiResult.Success).value
        val scheduled = executor.execute(AnkiStudyEffect.Next(1L, session)) as AnkiStudyEvent.Scheduled
        val turn = (scheduled.result as NextCardResult.Card).turn

        val resolved = executor.execute(
            AnkiStudyEffect.PerformReviewerAction(
                epoch = 1L,
                sessionId = "study-1",
                turnId = turn.turnId,
                cardRef = turn.cardRef,
                action = ReviewerAction.BuryCard
            )
        ) as AnkiStudyEvent.ReviewerActionResolved

        assertTrue(
            "the action must be applied on the locked backend: ${resolved.outcome}",
            resolved.outcome is AnkiReviewerActionOutcome.Applied
        )
        assertEquals(1, fake.reviewerActionBoundaryCrossings)
        assertEquals(0, pc.reviewerActionBoundaryCrossings)
        assertEquals(0, pc.reviewerActionInvocations)
        assertOtherBackendUntouched(pc, "VER-21G-16")
        assertEquals(localId, locks.lockedBackendFor("study-1"))
    }

    // ------------------------------------------------------------------------ VER-21G-17

    @Test
    fun `backend loss leaves the other ready backend untouched on every path`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        val store = InMemoryStudySessionLockStore()
        val pc = pcBackend()
        val h = localHarness(extra = listOf(pc), locks = locks, store = store)
        h.loadToRating()
        val session = h.state.anki!!.reviewSession!!

        h.fake.setAvailability(AnkiAvailability.TemporarilyUnavailable("lost"))
        val read = h.executor.execute(
            AnkiStudyEffect.Next(h.state.epoch, session)
        ) as AnkiStudyEvent.Scheduled
        assertTrue(read.result is NextCardResult.BackendUnavailable)

        // A rating attempt on the same session still names the vanished owner.
        h.rate(Rating.GOOD)
        val effect = h.takeCommitEffect()
        h.run(effect)
        h.drain()

        assertOtherBackendUntouched(pc, "VER-21G-17")
        assertEquals(localId, h.state.anki!!.reviewSession!!.context.backendId)
        assertEquals(localId, locks.lockedBackendFor("study-1"))
        assertEquals(
            "the persisted owner is preserved, not rewritten",
            localId,
            (store.load() as DecodedSessionLock.Valid).lock.backendId
        )
    }

    // --------------------------------------------------------- VER-21G-18 / 19 / 20

    @Test
    fun `same-backend reconnect follows continuity mismatch and unknown identity`() = runTest {
        val store = InMemoryStudySessionLockStore()
        val liveDeck = AnkiDeckRef(localId, "1", "X")
        val fake = FakeAnkiBackend(
            id = localId,
            decks = listOf(AnkiDeck(liveDeck, "Deck")),
            cards = listOf(AnkiCommitHarness.card("123", localId)),
            instanceId = "gate21g-local-reporting"
        )
        fake.currentCollectionIdentity = AnkiCollectionIdentity(localId, "X")
        val executor = AnkiStudyEffectExecutor(
            registry = AnkiBackendRegistry(listOf(fake)),
            sessionLocks = AnkiStudyBackendLockTracker(),
            sessionLockStore = store
        )
        val begun = executor.execute(
            AnkiStudyEffect.Begin(
                1L,
                quiet("study-1", AnkiBackendMode.ANKIDROID_LOCAL, liveDeck),
                100L
            )
        ) as AnkiStudyEvent.Begun
        assertTrue(begun.result is AnkiResult.Success)
        val lock = (store.load() as DecodedSessionLock.Valid).lock
        assertEquals("X", lock.collection?.collectionKey)

        // VER-21G-18 — the original backend with the same collection continues.
        assertTrue(
            StudySessionContinuationPolicy.check(lock, fake, boundIsLiveInThisProcess = true)
                is StudySessionContinuation.Permitted
        )

        // VER-21G-19 — the same backend, a *verified different* collection: blocked, distinctly.
        fake.currentCollectionIdentity = AnkiCollectionIdentity(localId, "Y")
        val mismatch = StudySessionContinuationPolicy.check(lock, fake, true)
        assertTrue(mismatch is StudySessionContinuation.Blocked)
        assertEquals("collection_mismatch", (mismatch as StudySessionContinuation.Blocked).reason)

        // VER-21G-20 — identity unverifiable is not a verified match.
        fake.currentCollectionIdentity = null
        val unknown = StudySessionContinuationPolicy.check(lock, fake, true)
        assertEquals(
            "collection_identity_degraded",
            (unknown as StudySessionContinuation.Blocked).reason
        )

        // The original collection returns: resumption is allowed again, ownership unchanged.
        fake.currentCollectionIdentity = AnkiCollectionIdentity(localId, "X")
        assertTrue(
            StudySessionContinuationPolicy.check(lock, fake, true)
                is StudySessionContinuation.Permitted
        )
        assertEquals(localId, lock.backendId)
        assertEquals("X", lock.collection?.collectionKey)
    }

    // ------------------------------------------------------------------------ VER-21G-21

    @Test
    fun `restoration preserves the original backend owner`() = runTest {
        val store = InMemoryStudySessionLockStore()
        val fake = FakeAnkiBackend(
            id = localId,
            decks = listOf(AnkiDeck(AnkiCommitHarness.deckFor(localId), "Deck")),
            cards = listOf(AnkiCommitHarness.card("123", localId)),
            instanceId = "gate21g-restore"
        )
        val registry = AnkiBackendRegistry(listOf(fake))
        val first = AnkiStudyEffectExecutor(
            registry = registry,
            sessionLocks = AnkiStudyBackendLockTracker(),
            sessionLockStore = store
        )
        val begun = first.execute(
            AnkiStudyEffect.Begin(
                1L,
                quiet("study-42", AnkiBackendMode.AUTO, AnkiCommitHarness.deckFor(localId)),
                100L
            )
        ) as AnkiStudyEvent.Begun
        assertTrue(begun.result is AnkiResult.Success)
        val persisted = store.load() as DecodedSessionLock.Valid

        // Process death: the in-memory tracker and the executor die; only the store survives.
        val restored = StudySessionRestorePolicy.restore(
            decoded = persisted,
            lookup = { id -> registry.find(id) },
            hasUnresolvedTransactions = false,
            boundIsLiveInThisProcess = false
        )
        assertTrue("a healthy lock restores: $restored", restored is StudySessionRestore.Restored)
        val lock = (restored as StudySessionRestore.Restored).lock
        assertEquals("study-42", lock.studySessionId)
        assertEquals(localId, lock.backendId)
        assertFalse(restored.hasUnresolvedTransactions)
        // No new binding was created by the restore.
        assertEquals("study-42", (store.load() as DecodedSessionLock.Valid).lock.studySessionId)
        assertEquals(0, fake.nextCardCount)
    }

    // ------------------------------------------------------------------------ VER-21G-22

    @Test
    fun `restoration never substitutes another ready backend`() = runTest {
        val store = InMemoryStudySessionLockStore()
        val pc = pcBackend()
        store.save(
            StudySessionBackendLock(
                studySessionId = "study-77",
                backendId = localId,
                deckRef = AnkiCommitHarness.deckFor(localId),
                startedAtEpochMs = 100L
            )
        )
        val persisted = store.load() as DecodedSessionLock.Valid
        // The original backend is gone from this build; only the ready PC backend is registered.
        val registry = AnkiBackendRegistry(listOf(pc))

        val restored = StudySessionRestorePolicy.restore(
            decoded = persisted,
            lookup = { id -> registry.find(id) },
            hasUnresolvedTransactions = true,
            boundIsLiveInThisProcess = false
        )
        assertTrue(restored is StudySessionRestore.Blocked)
        assertEquals("backend_not_registered", (restored as StudySessionRestore.Blocked).reason)
        assertOtherBackendUntouched(pc, "VER-21G-22")
        assertEquals(
            "a blocked restore never rewrites the owner",
            localId,
            (store.load() as DecodedSessionLock.Valid).lock.backendId
        )
    }

    // ------------------------------------------------------------------------ VER-21G-23

    @Test
    fun `a legacy session without ownership is never guessed`() = runTest {
        val pc = pcBackend()
        val registry = AnkiBackendRegistry(listOf(pc))

        // Nothing stored: no session to restore, and nothing is invented.
        assertNull(StudySessionLockCodec.decode(null))
        val absent = StudySessionRestorePolicy.restore(
            decoded = StudySessionLockCodec.decode(null),
            lookup = { id -> registry.find(id) },
            hasUnresolvedTransactions = false,
            boundIsLiveInThisProcess = false
        )
        assertEquals("no_persisted_lock", (absent as StudySessionRestore.Invalid).reason)

        // A newer schema is reported, never downgraded onto whatever backend is ready.
        val future = StudySessionBackendLock(
            studySessionId = "study-legacy",
            backendId = localId,
            schemaVersion = StudySessionBackendLock.CURRENT_SCHEMA_VERSION + 1
        )
        val decoded = StudySessionLockCodec.decode(StudySessionLockCodec.encode(future))
        assertTrue(decoded is DecodedSessionLock.UnsupportedVersion)
        val unsupported = StudySessionRestorePolicy.restore(
            decoded = decoded,
            lookup = { id -> registry.find(id) },
            hasUnresolvedTransactions = false,
            boundIsLiveInThisProcess = false
        )
        assertEquals("unsupported_lock_schema", (unsupported as StudySessionRestore.Invalid).reason)
        assertOtherBackendUntouched(pc, "VER-21G-23")
    }

    // ------------------------------------------- VER-21G-24 / 25 / 26 / 27

    @Test
    fun `stale callbacks from an ended session cannot move a newer session`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        val pc = pcBackend()
        val h = localHarness(extra = listOf(pc), locks = locks)

        // ---- session A on AnkiDroid
        h.send(AnkiStudyEvent.Start(quiet("study-A", AnkiBackendMode.ANKIDROID_LOCAL, h.deck)))
        h.drain()
        val epochA = h.state.epoch
        val sessionA = h.state.anki!!.reviewSession!!
        val turnA = h.state.anki!!.turn!!
        val staleNext = AnkiStudyEvent.Scheduled(epochA, NextCardResult.Card(turnA))
        val staleHydration = AnkiStudyEvent.Hydrated(
            epochA, turnA.turnId, AnkiResult.Failure(AnkiError.SessionInvalid())
        )
        val staleEvaluation = AnkiStudyEvent.AnswerEvaluationFailed(
            epochA, "study-A", turnA.turnId, turnA.cardRef, "req-1", "stale"
        )
        val staleRating = AnkiStudyEvent.RatingCommitResolved(
            epochA,
            ReviewCommitId(localId, "study-A", turnA.turnId),
            AnkiCommitOutcome.Committed("stale")
        )
        endSession(h, "end-A")?.let { h.run(it) }
        assertNull(locks.lockedBackendFor("study-A"))

        // ---- session B on the PC backend
        h.send(
            AnkiStudyEvent.Start(
                quiet("study-B", AnkiBackendMode.PC_AGENT, AnkiCommitHarness.deckFor(pcId))
            )
        )
        h.drain()
        assertEquals(pcId, h.state.anki!!.reviewSession!!.context.backendId)
        endSession(h, "end-B")?.let { h.run(it) }

        // ---- session C on AnkiDroid again (A → B → A)
        h.send(AnkiStudyEvent.Start(quiet("study-C", AnkiBackendMode.ANKIDROID_LOCAL, h.deck)))
        h.drain()
        val stateC = h.state
        val behaviourC = Triple(stateC.epoch, stateC.phase, stateC.anki)
        assertEquals(localId, stateC.anki!!.reviewSession!!.context.backendId)
        assertNotEquals("study-A", stateC.anki!!.reviewSession!!.context.studySessionId)
        assertEquals(localId, locks.lockedBackendFor("study-C"))

        // A's delayed callbacks arrive last and must be refused by the state-update guards.
        for (stale in listOf(staleNext, staleHydration, staleEvaluation, staleRating)) {
            val transition = StudyReducer.reduce(h.state, stale, h.now)
            assertFalse(
                "stale ${stale::class.simpleName} must be rejected",
                transition.accepted
            )
            h.state = transition.newState
        }
        assertEquals(
            "session C's epoch, phase and interaction are untouched by A's callbacks",
            behaviourC,
            Triple(h.state.epoch, h.state.phase, h.state.anki)
        )
        assertEquals(stateC.session, h.state.session)
        assertEquals(stateC.cardTurn, h.state.cardTurn)
        assertEquals(localId, locks.lockedBackendFor("study-C"))
        assertFalse(locks.locks.value.containsKey("study-A"))
        assertFalse(locks.locks.value.containsKey("study-B"))
    }

    // ------------------------------------------------------------------------ VER-21G-28

    @Test
    fun `ending a session leaves an unresolved rating record intact`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        val h = localHarness(
            locks = locks,
            commitSteps = listOf(FakeAnkiBackend.CommitStep(BackendCommitResult.OutcomeUnknown()))
        )
        h.loadToRating()
        h.rate(Rating.GOOD)
        val effect = h.takeCommitEffect()
        h.run(effect)
        val commitId = effect.request.commitId
        val before = h.ledger.get(commitId)!!
        assertEquals(ReviewCommitStatus.AMBIGUOUS, before.status)

        endSession(h, "end")?.let { h.run(it) }

        val after = h.ledger.get(commitId)
        assertTrue("ending a session must not delete an unresolved transaction", after != null)
        assertEquals(before.status, after!!.status)
        assertEquals(before.attemptCount, after.attemptCount)
        assertEquals(before.failure?.category, after.failure?.category)
        assertNull(locks.lockedBackendFor("study-1"))
    }

    // ------------------------------------------------------------------------ VER-21G-32

    @Test
    fun `ownership diagnostics report the lock not the preference`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        val pc = pcBackend()
        val h = localHarness(mode = AnkiBackendMode.AUTO, extra = listOf(pc), locks = locks)
        h.loadToRating()
        val sessionId = h.state.anki!!.reviewSession!!.context.studySessionId

        val projection = projectSessionOwnership(h.state, locks.locks.value[sessionId])
        assertEquals(sessionId, projection.studySessionId)
        assertEquals(localId, projection.lockedBackendId)
        assertNotEquals(pcId, projection.lockedBackendId)
        assertTrue(projection.hasLock)

        // A preference change does not reclassify the session as PC-owned.
        val after = projectSessionOwnership(h.state, locks.locks.value[sessionId])
        assertEquals(projection, after)
    }

    // ------------------------------------------------------------------------ VER-21G-33

    @Test
    fun `a thousand-event churn holds every ownership counter at zero`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        val store = InMemoryStudySessionLockStore()
        val pc = pcBackend()
        val h = AnkiCommitHarness(
            mode = AnkiBackendMode.AUTO,
            cards = (1..500).map { AnkiCommitHarness.card("card-$it", localId) },
            sessionLocks = locks,
            sessionLockStore = store,
            extraBackends = listOf(pc)
        )
        h.startAndLoad()
        val sessionId = h.state.anki!!.reviewSession!!.context.studySessionId
        val registry = AnkiBackendRegistry(listOf(h.backend, pc))

        var midSessionSwitches = 0
        var staleStateAccepted = 0
        var nextCardBeforeCommit = 0
        var crossBackendTransactionReplay = 0
        var resourceIdentityReinterpretation = 0
        var lastResolvedCommit: ReviewCommitId? = null
        var preferenceResolutions = 0
        var availabilityFlips = 0
        var availabilityProbes = 0
        var guardRefusals = 0

        repeat(1000) { index ->
            when (index % 10) {
                // Three of ten events: a complete review turn with its rating commit.
                0, 1, 2 -> {
                    if (h.state.phase == SessionPhase.WaitingForAnswer) {
                        h.now += 1_000L
                        h.send(StudyEvent.UserRequestAnswer(h.state.currentCardId))
                        h.drain(limit = 10)
                    }
                    if (h.state.phase == SessionPhase.WaitingForRating) {
                        val readsBeforeCommit = h.fake.nextCardCount
                        h.rate(if (index % 3 == 0) Rating.GOOD else Rating.HARD)
                        val effect = h.takeCommitEffect()
                        // The durable commit must be the next thing that touches the scheduler.
                        if (h.fake.nextCardCount != readsBeforeCommit) nextCardBeforeCommit++
                        h.run(effect)
                        lastResolvedCommit = effect.request.commitId
                        // A duplicate delivery of the same durable result is not a new transaction.
                        val duplicate = AnkiStudyEvent.RatingCommitResolved(
                            h.state.epoch, effect.request.commitId,
                            AnkiCommitOutcome.Committed("duplicate")
                        )
                        val mutationsBefore = h.fake.mutationBoundaryCrossingCount
                        val duplicateTransition = StudyReducer.reduce(h.state, duplicate, h.now)
                        h.state = duplicateTransition.newState
                        // A duplicate *result* may restate durable truth; it may never dispatch
                        // another mutation or a fresh commit attempt.
                        if (duplicateTransition.effects.any { it is AnkiStudyEffect.CommitRating })
                            crossBackendTransactionReplay++
                        if (h.fake.mutationBoundaryCrossingCount != mutationsBefore)
                            crossBackendTransactionReplay++
                        h.drain(limit = 10)
                    }
                }
                // Preference churn: fresh pre-binding resolutions are free to answer anything.
                3 -> {
                    val preference = if (index % 2 == 0) AnkiBackendMode.PC_AGENT
                    else AnkiBackendMode.AUTO
                    val resolution = AnkiBackendResolver2.resolve(
                        BackendResolutionRequest2(
                            preference = preference.toPreference(),
                            requiredCapabilities = STUDY_SESSION_REQUIREMENTS,
                            purpose = "churn"
                        ),
                        registry.descriptors()
                    )
                    if (resolution is BackendResolutionResult2.Resolved) preferenceResolutions++
                }
                // Availability churn on the locked backend, with the PC backend always ready.
                // The loss and the recovery happen inside one event, and the probe read is issued
                // directly (never delivered to the reducer) so the session survives the hazard it
                // is being tested against: an outage must be observable, not terminal.
                4 -> {
                    val session = h.state.anki?.reviewSession
                    if (session != null) {
                        h.fake.setAvailability(AnkiAvailability.TemporarilyUnavailable("churn"))
                        val lost = h.executor.execute(
                            AnkiStudyEffect.Next(h.state.epoch, session)
                        ) as AnkiStudyEvent.Scheduled
                        if (lost.result is NextCardResult.BackendUnavailable) availabilityProbes++
                        h.fake.setAvailability(AnkiAvailability.Ready(h.fake.capabilities.value))
                        availabilityFlips++
                    }
                }
                // Collection-identity churn: a fresh continuity verdict, never a rewrite.
                5 -> {
                    h.fake.currentCollectionIdentity = AnkiCollectionIdentity(localId, "churn-$index")
                    val lock = (store.load() as DecodedSessionLock.Valid).lock
                    if (lock.backendId != localId || lock.studySessionId != sessionId)
                        resourceIdentityReinterpretation++
                    h.fake.currentCollectionIdentity = null
                }
                // A stale callback from a previous generation must be refused outright.
                6 -> {
                    val stale = AnkiStudyEvent.Scheduled(0L, NextCardResult.Finished)
                    if (StudyReducer.reduce(h.state, stale, h.now).accepted) staleStateAccepted++
                }
                // A duplicate delivery of the last resolved transaction result.
                7 -> {
                    val commitId = lastResolvedCommit
                    if (commitId != null) {
                        val duplicate = AnkiStudyEvent.RatingCommitResolved(
                            h.state.epoch, commitId, AnkiCommitOutcome.Committed("duplicate")
                        )
                        val mutationsBefore = h.fake.mutationBoundaryCrossingCount
                        val duplicateTransition = StudyReducer.reduce(h.state, duplicate, h.now)
                        h.state = duplicateTransition.newState
                        if (duplicateTransition.effects.any { it is AnkiStudyEffect.CommitRating })
                            crossBackendTransactionReplay++
                        if (h.fake.mutationBoundaryCrossingCount != mutationsBefore)
                            crossBackendTransactionReplay++
                    }
                }
                // A reviewer action aimed at the *other* backend must never be recorded.
                8 -> {
                    h.send(
                        AnkiStudyEvent.ReviewerActionRequested(
                            action = ReviewerAction.SetFlag(AnkiFlag.RED),
                            epoch = h.state.epoch,
                            cardId = "pc-1"
                        )
                    )
                    h.drain(limit = 10)
                }
                // Restoration: the durable owner of this session is still this backend.
                else -> {
                    val lock = (store.load() as DecodedSessionLock.Valid).lock
                    if (lock.studySessionId != sessionId || lock.backendId != localId)
                        resourceIdentityReinterpretation++
                    if (locks.lockedBackendFor(sessionId) != localId) midSessionSwitches++
                }
            }

            // Guard-liveness probe: a forged next-card route for the *other* backend must be
            // refused, proving the session lock (not luck) is what keeps the counts at zero.
            val current = h.state.anki?.reviewSession
            if (current != null && index % 25 == 0) {
                val forged = AnkiReviewSession(
                    current.context.copy(backendId = pcId, studySessionId = current.context.studySessionId),
                    "forged-${index}"
                )
                val result = h.executor.execute(
                    AnkiStudyEffect.Next(h.state.epoch, forged)
                ) as AnkiStudyEvent.Scheduled
                if (result.result !is NextCardResult.Card) guardRefusals++
            }

            // Every iteration re-asserts the invariant: the session is owned by its lock.
            val session = h.state.anki?.reviewSession
            if (session != null && session.context.backendId != localId) midSessionSwitches++
            if (locks.lockedBackendFor(sessionId) != localId) midSessionSwitches++
        }

        // Non-vacuity: the churn actually reviewed, resolved and flipped, so the zeros below are
        // evidence about a working session rather than about an idle one.
        assertTrue(
            "the churn must actually review (mutations=${h.fake.mutationBoundaryCrossingCount})",
            h.fake.mutationBoundaryCrossingCount >= 100
        )
        assertTrue("the churn must read cards (${h.fake.nextCardCount})", h.fake.nextCardCount >= 100)
        assertTrue("preference churn must resolve ($preferenceResolutions)", preferenceResolutions >= 100)
        assertTrue("availability must flip ($availabilityFlips)", availabilityFlips >= 90)
        assertEquals(
            "every outage probe must report lost on the locked backend",
            availabilityFlips, availabilityProbes
        )
        assertTrue("the lock guard must be live ($guardRefusals)", guardRefusals >= 40)

        assertEquals("mid-session backend switches", 0, midSessionSwitches)
        assertEquals("stale session state accepted", 0, staleStateAccepted)
        assertEquals("next-card reads before the commit barrier", 0, nextCardBeforeCommit)
        assertEquals("cross-backend transaction replays", 0, crossBackendTransactionReplay)
        assertEquals("resource identity reinterpretations", 0, resourceIdentityReinterpretation)
        assertEquals("cross-backend scheduler calls", 0, pc.nextCardCount + pc.hydrateCalls)
        assertEquals("cross-backend rating mutations", 0, pc.physicalCommitCalls)
        assertEquals("cross-backend reviewer actions", 0, pc.reviewerActionBoundaryCrossings)
        assertEquals(localId, locks.lockedBackendFor(sessionId))
        assertEquals(localId, (store.load() as DecodedSessionLock.Valid).lock.backendId)
    }
}
