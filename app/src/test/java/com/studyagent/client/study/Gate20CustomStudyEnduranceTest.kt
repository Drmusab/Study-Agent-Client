package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiBackendResolver
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.study.AnkiStudyRequest
import com.studyagent.client.core.study.StudyEvent
import com.studyagent.client.core.anki.BackendResolutionRequest
import com.studyagent.client.core.anki.BackendResolutionResult
import com.studyagent.client.core.anki.ReviewCommitStatus
import com.studyagent.client.core.anki.customstudy.AnkiCustomStudyCapabilities
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.AnkiStudyEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 20 — VER-20-28: a 500-turn deterministic endurance run over a fake backend that has a
 * **filtered deck in its collection**, while another backend churns alongside it.
 *
 * The four counters the gate requires, each incremented only by *observing* the fakes and the
 * durable ledger — never by asking a component whether it thinks it behaved:
 *
 * | Required zero | How it is observed |
 * |---|---|
 * | 0 local scheduler selections | every presented card id must be the next id in the fake scheduler's own queue order; the app never picks, sorts or skips |
 * | 0 backend switches | the substitute backend's call counters stay 0 and every session context keeps backend A |
 * | 0 rating bypasses | one physical backend mutation per durable `COMMITTED` record, and no mutation without one |
 * | 0 next-card-before-commit violations | after a rating, the presented card does not change until the commit is durable and the scheduler is asked again |
 *
 * Plus the custom-study property this gate adds: **0 filtered sessions started** — every attempt to
 * start on the collection's filtered deck is refused, and no card from it is ever presented
 * (INV-20-14/20-18).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Gate20CustomStudyEnduranceTest {

    private val aId = AnkiBackendId.AnkiDroidLocal
    private val bId = AnkiBackendId.PcAgent("gate20-endurance-b")

    /** Deterministic LCG: the whole 500-event run is reproducible from the seed alone. */
    private class Seeded(private val seed: Long) {
        private var state = seed
        fun next(bound: Int): Int {
            state = state * 6364136223846793005L + 1442695040888963407L
            return (((state ushr 33).toInt() and 0x7fffffff) % bound)
        }
    }

    private class Violations {
        var localSchedulerSelections = 0
        var backendSwitches = 0
        var ratingBypasses = 0
        var nextCardBeforeCommit = 0
        var filteredSessionsStarted = 0
        var unknownBackendRecords = 0

        fun total(): Int = localSchedulerSelections + backendSwitches + ratingBypasses +
            nextCardBeforeCommit + filteredSessionsStarted + unknownBackendRecords
    }

    private val violations = Violations()

    /** Decks of the A collection: one ordinary deck and one the backend reports as filtered. */
    private val ordinaryDeck = AnkiDeck(AnkiDeckRef(aId, "1", "collection"), "Medicine", isFiltered = false)
    private val filteredDeck = AnkiDeck(AnkiDeckRef(aId, "2", "collection"), "Custom Study Session", isFiltered = true)

    private fun substitute(): FakeAnkiBackend = FakeAnkiBackend(
        id = bId,
        decks = listOf(AnkiDeck(AnkiDeckRef(bId, "1", "collection"), "B Deck")),
        cards = listOf(AnkiCommitHarness.card("B1", bId)),
        instanceId = "gate20-endurance"
    ).apply { currentCollectionIdentity = AnkiCollectionIdentity(bId, AnkiCommitHarness.COLLECTION_KEY) }

    @Test
    fun `500 events - filtered decks never start and the backend stays the only scheduler`() = runTest {
        val b = substitute()
        val cards = List(80) { AnkiCommitHarness.card("C$it", aId) }
        val a = FakeAnkiBackend(
            id = aId,
            decks = listOf(ordinaryDeck, filteredDeck),
            cards = cards,
            instanceId = "gate20-endurance-a"
        )
        val h = AnkiCommitHarness(customBackend = a, extraBackends = listOf(b))
        val resolver = AnkiBackendResolver(AnkiBackendRegistry(listOf(h.backend, b)))
        val random = Seeded(0x20_0C_20_28L)

        h.startAndLoad()

        var turns = 0
        var filteredStartAttempts = 0
        var restarts = 0
        val presented = mutableListOf<String>()

        repeat(500) { event ->
            // ---- churn: the substitute backend and the preference move; the session must not.
            b.setAvailability(
                if (event % 2 == 0) {
                    com.studyagent.client.core.anki.AnkiAvailability.Ready(b.capabilities.value)
                } else {
                    com.studyagent.client.core.anki.AnkiAvailability.TemporarilyUnavailable()
                }
            )
            if (event % 3 == 0 && event % 2 == 0) {
                val resolved = resolver.resolve(BackendResolutionRequest(AnkiBackendMode.PC_AGENT))
                if ((resolved as? BackendResolutionResult.Resolved)?.backendId == bId) Unit
            }

            // ---- custom-study pressure: someone keeps asking to study the filtered deck.
            if (event % 17 == 5) {
                filteredStartAttempts += 1
                h.send(
                    AnkiStudyEvent.Start(
                        AnkiStudyRequest(
                            "gate20-filtered-$event",
                            AnkiBackendMode.ANKIDROID_LOCAL,
                            filteredDeck.ref,
                            studyMode = com.studyagent.client.core.anki.customstudy.AnkiStudyMode.Normal
                        )
                    )
                )
                h.drain()
                // A refused start must not disturb the session that is already running.
                if (h.state.anki?.reviewSession?.context?.deckRef == filteredDeck.ref) {
                    violations.filteredSessionsStarted += 1
                }
            }

            // ---- a rating turn, with the "no advance before the commit is durable" check.
            val cardBefore = h.state.currentCardId
            if (cardBefore != null && event % 5 != 4) {
                h.send(StudyEvent.UserRequestAnswer(cardBefore))
                // The scheduler's own identity for the card being rated — not the machine's
                // composite turn id — so "the same card the backend served" is checkable exactly.
                val ratedCardId = h.state.anki?.turn?.cardRef?.cardId
                h.rate(if (turns % 3 == 0) Rating.EASY else Rating.GOOD)
                // Between the rating and the durable commit the scheduler must not be asked for a
                // new card: the presented card is still the one being rated.
                if (h.state.currentCardId != cardBefore) violations.nextCardBeforeCommit += 1
                val effect = runCatching { h.takeCommitEffect() }.getOrNull()
                if (effect != null) {
                    h.run(effect)
                    h.drain()
                    if (h.state.currentCardId != cardBefore) {
                        presented += ratedCardId ?: cardBefore
                        turns += 1
                    }
                }
            }

            // ---- duplicate deliveries and stale presses: a second rating of the same turn.
            val duplicateTarget = h.state.currentCardId
            if (event % 11 == 7 && duplicateTarget != null) {
                h.send(StudyEvent.UserRateCard(Rating.GOOD, duplicateTarget))
                h.drain(limit = 6)
            }

            // ---- process death: a new ledger over the same durable bytes; the lock keeps backend A.
            if (event % 97 == 61) {
                restarts += 1
                h.restartLedger()
            }

            val session = h.state.anki?.reviewSession
            if (session != null && session.context.backendId != aId) violations.backendSwitches += 1
            if (session != null && session.context.studyMode.isFiltered) {
                violations.filteredSessionsStarted += 1
            }
        }

        // ---- the session's own backend never lost ownership, and the substitute saw nothing.
        assertEquals(0, b.physicalCommitCalls)
        assertEquals(0, b.nextCardCount)
        assertEquals(0, b.getDecksCalls)
        assertEquals(0, b.hydrateCalls)
        assertEquals(0, b.reconcileCalls)

        // ---- every presented card came from the scheduler's own queue, in its own order.
        val queue = a.schedulerQueueCardIds
        presented.forEachIndexed { index, cardId ->
            if (index >= queue.size || queue[index] != cardId) violations.localSchedulerSelections += 1
        }

        // ---- one durable committed record per physical mutation, all owned by A.
        val records = h.store.durableRecords()
        val committed = records.count { it.status == ReviewCommitStatus.COMMITTED }
        if (committed != a.physicalCommitCalls) violations.ratingBypasses += 1
        if (records.any { it.backendId != aId }) violations.unknownBackendRecords += 1

        // ---- the run actually did the work it claims to have done.
        assertTrue("the run must play turns: $turns", turns >= 60)
        assertTrue("the run must attempt filtered starts: $filteredStartAttempts", filteredStartAttempts >= 25)
        assertTrue("the run must include restarts: $restarts", restarts >= 4)
        assertTrue("the scheduler must have been asked for cards", a.nextCardCount >= turns)
        assertEquals("browsing is never a scheduler", 0, a.browseCalls)

        assertEquals(
            "GATE 20 violations: local=${violations.localSchedulerSelections}, " +
                "switch=${violations.backendSwitches}, bypass=${violations.ratingBypasses}, " +
                "nextBeforeCommit=${violations.nextCardBeforeCommit}, " +
                "filteredStarted=${violations.filteredSessionsStarted}, " +
                "foreignRecords=${violations.unknownBackendRecords}",
            0,
            violations.total()
        )
    }

    /**
     * The other half of the same property: a backend that **claims** filtered review but cannot
     * disclose rescheduling is refused too, and a filtered start never reaches the scheduler even
     * when the backend says it could handle one.
     */
    @Test
    fun `a filtered start is refused even when the backend claims every custom-study capability`() = runTest {
        val capable = AnkiCustomStudyCapabilities(
            canReviewFilteredDeck = true,
            canProveFilteredRatingCommit = true,
            canExposeReschedulingSemantics = true,
            canFilterByDeck = true,
            canFilterByTag = true,
            canLimitCardCount = true,
            canSelectOrdering = true,
            canSelectNoReschedule = true,
            canPreviewOnly = true
        )
        val backend = FakeAnkiBackend(
            id = aId,
            decks = listOf(filteredDeck),
            cards = listOf(AnkiCommitHarness.card("C1", aId)),
            customStudyCapabilities = capable,
            filteredRescheduling = null
        )
        val h = AnkiCommitHarness(customBackend = backend)
        h.send(
            AnkiStudyEvent.Start(
                AnkiStudyRequest("gate20-capable", AnkiBackendMode.ANKIDROID_LOCAL, filteredDeck.ref)
            )
        )
        h.drain()

        assertEquals("no session may exist on an undisclosed filtered deck", null, h.state.anki?.reviewSession)
        assertEquals(0, backend.nextCardCount)
        assertEquals(0, backend.physicalCommitCalls)
    }
}
