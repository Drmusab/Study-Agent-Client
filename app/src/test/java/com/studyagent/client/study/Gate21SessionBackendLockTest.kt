package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21 PART III — VER-21-07 … VER-21-12 and VER-21-29: the immutable session backend lock.
 *
 * One StudySession, one backend, for its whole life. The run below is deliberately adversarial: the
 * preferred mode is flipped, the locked backend is taken away while a *second* backend stays ready,
 * and the process is restarted — and every one of those must leave the session's backend alone
 * (INV-21-01/02/05, DECISION-21-11/12).
 *
 * The second backend is a real, ready, PC-identity fake in the same registry. That is the point: a
 * failover was *possible* on every turn, and must never happen.
 */
class Gate21SessionBackendLockTest {

    private val localId = AnkiCommitHarness.BACKEND
    private val pcId = AnkiBackendId.PcAgent("gate21-desktop")

    private fun pcBackend() = FakeAnkiBackend(
        id = pcId,
        decks = listOf(AnkiDeck(AnkiCommitHarness.deckFor(pcId), "PC Deck")),
        cards = listOf(AnkiCommitHarness.card("pc-1", pcId), AnkiCommitHarness.card("pc-2", pcId)),
        instanceId = "gate21-pc"
    )

    // -------------------------------------------------------------------------- VER-21-07

    @Test
    fun `session start captures the backend identity and publishes it as a lock`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        val harness = AnkiCommitHarness(sessionLocks = locks)
        harness.startAndLoad()

        val session = harness.state.anki?.reviewSession
        assertNotNull("a review session must exist", session)
        assertEquals(localId, session!!.context.backendId)
        // The lock is the same identity the session carries, keyed by the same study session id.
        assertEquals(localId, locks.lockedBackendFor(session.context.studySessionId))
        assertEquals(localId, locks.locks.value.values.single().backendId)
    }

    @Test
    fun `a refused start publishes no lock`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        val harness = AnkiCommitHarness(
            sessionLocks = locks,
            // A deck that is not in the collection: the start must fail rather than half-bind.
        )
        harness.send(
            AnkiStudyEvent.Start(
                AnkiStudyRequest(
                    studySessionId = "study-missing-deck",
                    preference = AnkiBackendMode.ANKIDROID_LOCAL,
                    deck = AnkiDeckRef(localId, "no-such-deck")
                )
            )
        )
        harness.drain()
        assertTrue("no lock may be published for a refused start: ${locks.locks.value}", locks.locks.value.isEmpty())
    }

    // -------------------------------------------------------------------------- VER-21-08

    @Test
    fun `a preference change during study never re-routes an active session`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        val pc = pcBackend()
        val harness = AnkiCommitHarness(sessionLocks = locks, extraBackends = listOf(pc))
        harness.loadToRating()
        val sessionId = harness.state.anki!!.reviewSession!!.context.studySessionId
        val pcDecksBefore = pc.getDecksCalls

        // The user switches their global preference to the PC agent mid-session.
        val resolver = AnkiBackendResolver(AnkiBackendRegistry(listOf(harness.backend, pc)))
        val afterSwitch = resolver.resolve(
            BackendResolutionRequest(AnkiBackendMode.PC_AGENT, setOf(AnkiRequiredCapability.REVIEW))
        )
        assertEquals(pcId, (afterSwitch as BackendResolutionResult.Resolved).backendId)

        // …and the session keeps committing to the backend it locked at start.
        harness.rate(Rating.GOOD)
        val effect = harness.takeCommitEffect()
        assertEquals(localId, effect.request.commitId.backendId)
        harness.run(effect)
        assertEquals(localId, harness.state.anki!!.reviewSession!!.context.backendId)
        assertEquals(localId, locks.lockedBackendFor(sessionId))
        assertEquals("the PC backend must not be consulted for this session", pcDecksBefore, pc.getDecksCalls)
        assertEquals(0, pc.physicalCommitCalls)
    }

    // ------------------------------------------------------------------ VER-21-09 / VER-21-12

    @Test
    fun `losing the locked backend blocks the session instead of failing over`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        val pc = pcBackend()
        val harness = AnkiCommitHarness(sessionLocks = locks, extraBackends = listOf(pc))
        harness.loadToRating()
        val sessionId = harness.state.anki!!.reviewSession!!.context.studySessionId

        // The locked backend disappears while the PC backend stays ready.
        harness.fake.setAvailability(AnkiAvailability.TemporarilyUnavailable())

        // Resolution *outside* the session now answers PC — that is correct and irrelevant here.
        val resolver = AnkiBackendResolver(AnkiBackendRegistry(listOf(harness.backend, pc)))
        assertTrue(
            resolver.resolve(BackendResolutionRequest(AnkiBackendMode.AUTO))
                is BackendResolutionResult.Resolved
        )

        harness.rate(Rating.GOOD)
        val effect = harness.takeCommitEffect()
        harness.run(effect)

        // The session is still owned by the vanished backend, and the PC backend saw nothing.
        assertEquals(localId, harness.state.anki!!.reviewSession!!.context.backendId)
        assertEquals(localId, locks.lockedBackendFor(sessionId))
        assertEquals(0, pc.physicalCommitCalls)
        assertEquals(0, pc.hydrateCalls)
    }

    // -------------------------------------------------------------------------- VER-21-29

    @Test
    fun `1000 turns with preference and availability churn never move a session between backends`() =
        runTest {
            val locks = AnkiStudyBackendLockTracker()
            val pc = pcBackend()
            val turns = 1000
            val harness = AnkiCommitHarness(
                // Enough cards for the whole run; the fake cycles its queue deterministically.
                cards = (1..turns).map { AnkiCommitHarness.card("card-$it", localId) },
                sessionLocks = locks,
                extraBackends = listOf(pc)
            )
            harness.startAndLoad()
            val sessionId = harness.state.anki!!.reviewSession!!.context.studySessionId
            val resolver = AnkiBackendResolver(AnkiBackendRegistry(listOf(harness.backend, pc)))

            var switches = 0
            var crossBackendCommits = 0
            var wrongBackendNextCard = 0

            repeat(turns) { index ->
                // Churn: alternate the *preferred* mode and the locked backend's availability. Both
                // are exactly the inputs a failover would key on.
                val preferred = if (index % 2 == 0) AnkiBackendMode.AUTO else AnkiBackendMode.PC_AGENT
                if (index % 7 == 3) {
                    harness.fake.setAvailability(AnkiAvailability.TemporarilyUnavailable())
                } else if (index % 7 == 4) {
                    harness.fake.setAvailability(
                        AnkiAvailability.Ready(harness.backend.capabilities.value)
                    )
                }
                // A pre-binding resolution is always free to answer something else…
                resolver.resolve(BackendResolutionRequest(preferred, setOf(AnkiRequiredCapability.REVIEW)))

                val before = harness.state.anki!!.reviewSession!!.context.backendId
                harness.now += 1_000L
                harness.send(StudyEvent.UserRequestAnswer(harness.state.currentCardId))
                harness.drain(limit = 10)
                if (harness.state.phase == SessionPhase.WaitingForRating) {
                    // Rate first: the rating is what produces the commit effect.
                    harness.rate(if (index % 3 == 0) Rating.GOOD else Rating.HARD)
                    val effect = harness.takeCommitEffect()
                    if (effect.request.commitId.backendId != localId) crossBackendCommits++
                    harness.run(effect)
                    harness.drain(limit = 10)
                }
                val after = harness.state.anki!!.reviewSession!!.context.backendId
                if (before != after) switches++
                if (locks.lockedBackendFor(sessionId) != localId) switches++
                // A next card that ever came from the other backend would have hydrated there.
                wrongBackendNextCard += if (pc.hydrateCalls > 0) 1 else 0
            }

            assertEquals("mid-session backend switches", 0, switches)
            assertEquals("cross-backend rating commits", 0, crossBackendCommits)
            assertEquals("next-card reads on the wrong backend", 0, wrongBackendNextCard)
            assertEquals("cross-backend scheduler mutations", 0, pc.physicalCommitCalls)
            assertEquals(localId, harness.state.anki!!.reviewSession!!.context.backendId)
            assertEquals(localId, locks.lockedBackendFor(sessionId))
        }

    // ------------------------------------------------------------------ lock lifecycle

    @Test
    fun `ending a session releases its lock and only ending releases it`() = runTest {
        val locks = AnkiStudyBackendLockTracker()
        val harness = AnkiCommitHarness(sessionLocks = locks)
        harness.startAndLoad()
        val session = harness.state.anki!!.reviewSession!!
        assertEquals(localId, locks.lockedBackendFor(session.context.studySessionId))

        // An availability change is not a release.
        harness.fake.setAvailability(AnkiAvailability.TemporarilyUnavailable())
        assertEquals(localId, locks.lockedBackendFor(session.context.studySessionId))

        harness.run(AnkiStudyEffect.EndReview(session))
        assertNull(locks.lockedBackendFor(session.context.studySessionId))
    }

    @Test
    fun `recording a lock twice for the same session keeps one identity`() {
        val locks = AnkiStudyBackendLockTracker()
        val context = AnkiSessionContext(
            backendId = localId,
            collection = AnkiCollectionIdentity(localId, "collection"),
            deckRef = AnkiCommitHarness.deckFor(localId),
            startedAtEpochMs = 1_000L,
            capabilities = AnkiCapabilities(review = true),
            studySessionId = "study-1"
        )
        locks.locked(context)
        locks.locked(context.copy(startedAtEpochMs = 2_000L))
        assertEquals(1, locks.locks.value.size)
        assertEquals(localId, locks.locks.value.values.single().backendId)
    }
}
