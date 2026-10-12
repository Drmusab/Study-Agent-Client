package com.studyagent.client.anki

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.InMemoryReviewCommitStore
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiRenderedCard
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.AnkiReviewSession
import com.studyagent.client.core.anki.AnkiReviewTurnContent
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.AnkiStudyBackendLockTracker
import com.studyagent.client.core.anki.CommitRatingRequest
import com.studyagent.client.core.anki.DecodedSessionLock
import com.studyagent.client.core.anki.InMemoryStudySessionLockStore
import com.studyagent.client.core.anki.NextCardResult
import com.studyagent.client.core.anki.ReviewCommitId
import com.studyagent.client.core.anki.ReviewCommitLedger
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.anki.ReviewerAction
import com.studyagent.client.core.anki.StudySessionBackendLock
import com.studyagent.client.core.anki.StudySessionLockStore
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.AnkiCommitOutcome
import com.studyagent.client.core.study.AnkiReviewerActionOutcome
import com.studyagent.client.core.study.AnkiStudyEffect
import com.studyagent.client.core.study.AnkiStudyEffectExecutor
import com.studyagent.client.core.study.AnkiStudyEvent
import com.studyagent.client.core.study.AnkiStudyRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21G — the bound session keeps its backend across persistence, staleness, unavailability
 * and teardown (VER-21G-17…VER-21G-29).
 *
 * One session binds one exact backend before scheduler work; afterwards the lock routes, and a
 * stale or foreign effect is refused before any backend or ledger is touched — never rerouted,
 * never repaired through a preference.
 */
class Gate21gSessionContinuityTest {

    private val local = AnkiBackendId.AnkiDroidLocal
    private val pc = AnkiBackendId.PcAgent("p1")
    private val deck = AnkiDeckRef(local, "1", "collection")
    private val pcDeck = AnkiDeckRef(pc, "9")

    private fun card(id: String, backend: AnkiBackendId = local): AnkiRenderedCard {
        // The fake's scheduler only queues cards filed under the session's deck and collection.
        val cardDeck = if (backend == local) deck else pcDeck
        return AnkiRenderedCard(
            ref = AnkiCardRef(backend, cardId = id, collectionKey = cardDeck.collectionKey),
            questionHtml = "q$id",
            answerHtml = "a$id",
            questionText = "q$id",
            answerText = "a$id",
            pureAnswerText = "a$id",
            deckRef = cardDeck
        )
    }

    private class Setup(
        val fake: FakeAnkiBackend,
        val tracker: AnkiStudyBackendLockTracker,
        val store: InMemoryStudySessionLockStore,
        val executor: AnkiStudyEffectExecutor
    )

    private fun setup(
        extraBackends: List<FakeAnkiBackend> = emptyList(),
        liveKey: String? = null
    ): Setup {
        val fake = FakeAnkiBackend(
            id = local,
            decks = listOf(AnkiDeck(deck, "Deck")),
            cards = listOf(card("c1"))
        )
        fake.currentCollectionIdentity = liveKey?.let { AnkiCollectionIdentity(local, it) }
        val tracker = AnkiStudyBackendLockTracker()
        val store = InMemoryStudySessionLockStore()
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock = { 1_000L })
        val executor = AnkiStudyEffectExecutor(
            registry = AnkiBackendRegistry(listOf(fake) + extraBackends),
            ledger = ledger,
            clock = { 1_000L },
            sessionLocks = tracker,
            sessionLockStore = store
        )
        return Setup(fake, tracker, store, executor)
    }

    private suspend fun begin(
        executor: AnkiStudyEffectExecutor,
        request: AnkiStudyRequest = AnkiStudyRequest("s1", AnkiBackendMode.ANKIDROID_LOCAL, deck)
    ): AnkiStudyEvent.Begun =
        executor.execute(AnkiStudyEffect.Begin(1L, request, 100L)) as AnkiStudyEvent.Begun

    private fun begunSession(begun: AnkiStudyEvent.Begun): AnkiReviewSession =
        (begun.result as AnkiResult.Success).value

    // ------------------------------------------------------- durable ownership at the bind

    @Test fun `begin persists the exact lock and publishes the tracker entry`() = runTest {
        val s = setup()
        val session = begunSession(begin(s.executor))
        assertEquals(local, session.context.backendId)

        val stored = s.store.load() as DecodedSessionLock.Valid
        assertEquals("s1", stored.lock.studySessionId)
        assertEquals(local, stored.lock.backendId)
        // The mute backend reported nothing, so the lock records unknown — the deck label is not
        // promoted into bound evidence.
        assertEquals(null, stored.lock.collection)
        assertEquals(deck, stored.lock.deckRef)
        assertEquals(local, s.tracker.lockedBackendFor("s1"))
    }

    @Test fun `begin records a reporting backend's live collection`() = runTest {
        val keyedDeck = AnkiDeckRef(local, "1", "live-9")
        val fake = FakeAnkiBackend(
            id = local,
            decks = listOf(AnkiDeck(keyedDeck, "Deck")),
            cards = listOf(card("c1"))
        )
        fake.currentCollectionIdentity = AnkiCollectionIdentity(local, "live-9")
        val store = InMemoryStudySessionLockStore()
        val executor = AnkiStudyEffectExecutor(
            registry = AnkiBackendRegistry(listOf(fake)),
            sessionLocks = AnkiStudyBackendLockTracker(),
            sessionLockStore = store
        )
        val begun = begin(executor, AnkiStudyRequest("s9", AnkiBackendMode.ANKIDROID_LOCAL, keyedDeck))
        assertTrue(begun.result is AnkiResult.Success)
        val stored = store.load() as DecodedSessionLock.Valid
        assertEquals("live-9", stored.lock.collection?.collectionKey)
    }

    @Test fun `begin refuses a deck that contradicts the live collection`() = runTest {
        val s = setup(liveKey = "B")
        val begun = begin(s.executor)
        val error = (begun.result as AnkiResult.Failure).error as AnkiError.InvalidRequest
        assertEquals("collection_mismatch", error.detail)
        assertEquals(null, s.store.load())
        assertEquals(null, s.tracker.lockedBackendFor("s1"))
        assertEquals(0, s.fake.nextCardCount)
        assertEquals(0, s.fake.endReviewCalls)
    }

    @Test fun `an unpersistable start is refused and the backend handle released`() = runTest {
        val failing = object : StudySessionLockStore {
            override suspend fun save(lock: StudySessionBackendLock): Boolean = false
            override suspend fun load(): DecodedSessionLock? = null
            override suspend fun clear(studySessionId: String): Boolean = true
        }
        val tracker = AnkiStudyBackendLockTracker()
        val fake = FakeAnkiBackend(
            id = local, decks = listOf(AnkiDeck(deck, "Deck")), cards = listOf(card("c1"))
        )
        val executor = AnkiStudyEffectExecutor(
            registry = AnkiBackendRegistry(listOf(fake)),
            sessionLocks = tracker,
            sessionLockStore = failing
        )
        val begun = begin(executor)
        val error = (begun.result as AnkiResult.Failure).error as AnkiError.Unknown
        assertEquals("session_lock_persistence_failure", error.cause)
        assertEquals(1, fake.endReviewCalls)
        assertEquals(null, tracker.lockedBackendFor("s1"))
    }

    // ------------------------------------------------- stale effects never move the session

    @Test fun `a stale next for another backend never touches a backend`() = runTest {
        val s = setup()
        begunSession(begin(s.executor))
        val forged = AnkiReviewSession(
            AnkiSessionContext(pc, null, null, 100L, FakeAnkiBackend.REVIEW_CAPABILITIES, "s1"),
            "forged-ref"
        )
        val event = s.executor.execute(AnkiStudyEffect.Next(2L, forged)) as AnkiStudyEvent.Scheduled
        val error = (event.result as NextCardResult.Failure).error as AnkiError.InvalidRequest
        assertEquals("session_backend_mismatch", error.detail)
        assertEquals(0, s.fake.nextCardCount)
        // The refusal changes nothing: the lock and the tracker still name the bound backend.
        assertEquals(local, (s.store.load() as DecodedSessionLock.Valid).lock.backendId)
        assertEquals(local, s.tracker.lockedBackendFor("s1"))
    }

    @Test fun `next on the locked backend routes normally`() = runTest {
        val s = setup()
        val session = begunSession(begin(s.executor))
        val event = s.executor.execute(AnkiStudyEffect.Next(2L, session)) as AnkiStudyEvent.Scheduled
        val turn = (event.result as NextCardResult.Card).turn
        assertEquals("s1", turn.studySessionId)
        assertEquals(1, s.fake.nextCardCount)
    }

    @Test fun `an unavailable locked backend fails on its own identity, never failing over`() =
        runTest {
            val pcFake = FakeAnkiBackend(
                id = pc, decks = listOf(AnkiDeck(pcDeck, "PC deck")), cards = listOf(card("p1", pc))
            )
            val s = setup(extraBackends = listOf(pcFake))
            val session = begunSession(
                begin(s.executor, AnkiStudyRequest("s1", AnkiBackendMode.AUTO, deck))
            )
            assertEquals(local, session.context.backendId)
            s.fake.setAvailability(AnkiAvailability.TemporarilyUnavailable("storm"))
            val event = s.executor.execute(AnkiStudyEffect.Next(2L, session)) as AnkiStudyEvent.Scheduled
            assertTrue(event.result is NextCardResult.BackendUnavailable)
            assertEquals(0, pcFake.nextCardCount)
            assertEquals(local, s.tracker.lockedBackendFor("s1"))
        }

    @Test fun `a stale hydrate for another backend is refused without backend traffic`() = runTest {
        val s = setup()
        val session = begunSession(begin(s.executor))
        val scheduled = s.executor.execute(AnkiStudyEffect.Next(2L, session)) as AnkiStudyEvent.Scheduled
        val turn = (scheduled.result as NextCardResult.Card).turn
        val forged = turn.copy(
            content = AnkiReviewTurnContent.Scheduled(
                turn.scheduledCard.copy(
                    ref = AnkiCardRef(pc, cardId = "evil"),
                    deckRef = pcDeck
                )
            )
        )
        val event = s.executor.execute(AnkiStudyEffect.Hydrate(3L, forged)) as AnkiStudyEvent.Hydrated
        val error = (event.result as AnkiResult.Failure).error as AnkiError.InvalidRequest
        assertEquals("session_backend_mismatch", error.detail)
        assertEquals(0, s.fake.hydrateCalls)
    }

    @Test fun `a commit for another backend under a locked session is refused undispatched`() =
        runTest {
            val s = setup()
            begunSession(begin(s.executor))
            val request = CommitRatingRequest(
                commitId = ReviewCommitId(pc, "s1", ReviewTurnId("t1")),
                card = AnkiCardRef(pc, cardId = "c9"),
                rating = Rating.GOOD,
                ratedAtEpochMs = 200L
            )
            val event = s.executor.execute(
                AnkiStudyEffect.CommitRating(2L, request)
            ) as AnkiStudyEvent.RatingCommitResolved
            val outcome = event.outcome as AnkiCommitOutcome.Failed
            assertEquals("session_backend_mismatch", outcome.category)
            assertEquals(false, outcome.dispatched)
            assertEquals(0, s.fake.logicalCommitCount)
        }

    @Test fun `a reviewer action for another backend is not recorded`() = runTest {
        val s = setup()
        begunSession(begin(s.executor))
        val event = s.executor.execute(
            AnkiStudyEffect.PerformReviewerAction(
                epoch = 2L,
                sessionId = "s1",
                turnId = ReviewTurnId("t1"),
                cardRef = AnkiCardRef(pc, cardId = "c9"),
                action = ReviewerAction.SuspendCard
            )
        ) as AnkiStudyEvent.ReviewerActionResolved
        val outcome = event.outcome as AnkiReviewerActionOutcome.NotRecorded
        // Without the lock guard this would be LEDGER_UNAVAILABLE (no coordinator is wired), so
        // the mismatch category also proves the guard runs before anything durable exists.
        assertEquals("session_backend_mismatch", outcome.category)
    }

    // ------------------------------------------------------------- teardown and succession

    @Test fun `end clears the persisted lock and releases the tracker`() = runTest {
        val s = setup()
        val session = begunSession(begin(s.executor))
        s.executor.execute(AnkiStudyEffect.EndReview(session))
        assertEquals(null, s.store.load())
        assertEquals(null, s.tracker.lockedBackendFor("s1"))
        assertEquals(1, s.fake.endReviewCalls)
    }

    @Test fun `a second session binds independently while the store keeps the latest`() = runTest {
        val pcFake = FakeAnkiBackend(
            id = pc, decks = listOf(AnkiDeck(pcDeck, "PC deck")), cards = listOf(card("p1", pc))
        )
        val tracker = AnkiStudyBackendLockTracker()
        val store = InMemoryStudySessionLockStore()
        val executor = AnkiStudyEffectExecutor(
            registry = AnkiBackendRegistry(
                listOf(
                    FakeAnkiBackend(
                        id = local, decks = listOf(AnkiDeck(deck, "Deck")),
                        cards = listOf(card("c1"))
                    ),
                    pcFake
                )
            ),
            sessionLocks = tracker,
            sessionLockStore = store
        )
        val first = begin(executor)
        assertTrue(first.result is AnkiResult.Success)
        val second = begin(executor, AnkiStudyRequest("s2", AnkiBackendMode.PC_AGENT, pcDeck))
        assertTrue(second.result is AnkiResult.Success)
        assertEquals(local, tracker.lockedBackendFor("s1"))
        assertEquals(pc, tracker.lockedBackendFor("s2"))
        // Single lock slot: the store owns the latest session; each session's tracker entry is
        // unaffected by the other session's bind.
        assertEquals("s2", (store.load() as DecodedSessionLock.Valid).lock.studySessionId)
    }
}
