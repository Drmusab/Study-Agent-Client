package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.ProcessDeathSimulation
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 21I PART IV — endurance: the reviewer-action family under long, adversarial churn and
 * process death, in the topology that makes mis-routing observable (a ready, keyed substitute
 * backend B registered beside the session's keyless backend A, holding *duplicate* card ids).
 *
 * Two exercises:
 *
 * 1. **Churn** — a thousand-event session in which B's availability flips every event, the global
 *    backend preference is flipped to B every third event (and resolves to B), and turns alternate
 *    bury / suspend / rating. The session's identity, every durable action record, and every
 *    backend counter must stay A-exclusive for the whole run.
 *
 * 2. **Crash sweep** — the six crash/fault windows of the reviewer-action submission sequence,
 *    each run 100 times on fresh processes, plus 100 clean controls. Every run must end in the
 *    window's exact durable truth with the mutation applied exactly once (or provably never).
 */
class Gate21iActionEnduranceTest {

    private val aId = AnkiBackendId.AnkiDroidLocal
    private val bId = AnkiBackendId.PcAgent("gate21i-b")

    /** The substitute: registered, keyed, holding a card id that A's queue also carries. */
    private fun backendB() = FakeAnkiBackend(
        id = bId,
        decks = listOf(AnkiDeck(ReviewerActionHarness.deckFor(bId), "B Deck")),
        cards = listOf(ReviewerActionHarness.card("123", bId)),
        initialCapabilities = FakeAnkiBackend.REVIEW_ACTION_CAPABILITIES,
        instanceId = "gate21i-b"
    ).apply {
        currentCollectionIdentity = AnkiCollectionIdentity(bId, ReviewerActionHarness.COLLECTION_KEY)
    }

    // ------------------------------------------------------------------ churn

    @Test
    fun `a thousand-event session stays on the admitted backend under preference and availability churn`() =
        runTest {
            val b = backendB()
            val cards = List(150) { i -> ReviewerActionHarness.card("C$i", aId) }
            val h = ReviewerActionHarness(cards = cards, extraBackends = listOf(b))
            val resolver = AnkiBackendResolver(h.registry)
            h.startAndLoad()

            var turns = 0
            var actions = 0
            repeat(1000) { event ->
                // Availability churn on the substitute — it must never become the destination.
                b.setAvailability(
                    if (event % 2 == 0) {
                        AnkiAvailability.Ready(b.capabilities.value)
                    } else {
                        AnkiAvailability.TemporarilyUnavailable()
                    })
                // Preference churn: when B is ready, the global preference now resolves to B.
                if (event % 3 == 0 && event % 2 == 0) {
                    val resolved = resolver.resolve(
                        BackendResolutionRequest(AnkiBackendMode.PC_AGENT))
                    assertEquals(
                        "the churn really pointed the preference at B",
                        bId, (resolved as BackendResolutionResult.Resolved).backendId)
                }
                // A full turn whenever the scheduler has a card: reveal, then act or rate.
                if (h.state.anki?.turn != null && h.state.phase == SessionPhase.WaitingForAnswer) {
                    h.send(StudyEvent.UserRequestAnswer(h.state.currentCardId))
                    h.drain()
                    when (turns % 3) {
                        0 -> { h.requestAction(ReviewerAction.BuryCard); actions++ }
                        1 -> { h.requestAction(ReviewerAction.SuspendCard); actions++ }
                        else -> h.rate(Rating.GOOD)
                    }
                    h.drain()
                    turns++
                }
                // The invariants hold on *every* event, not just at the end.
                assertEquals(
                    "event $event: the session's backend identity never moves",
                    aId, h.state.anki?.reviewSession?.context?.backendId)
                h.state.anki?.turn?.let { turn ->
                    assertEquals("event $event: the turn's card is A-qualified",
                        aId, turn.cardRef.backendId)
                }
            }

            assertTrue("the churn produced real turns: $turns", turns >= 40)
            val records = h.store.durableRecords()
            assertTrue("the durable cell holds action records", records.isNotEmpty())
            assertEquals("every durable action record is A-owned",
                records.size, records.count { it.backendId == aId })
            assertEquals("no record belongs to any other backend",
                0, records.count { it.backendId != aId })
            assertEquals("every surviving durable record is APPLIED",
                records.size, records.count { it.status == ReviewerActionStatus.APPLIED })
            assertEquals("A's crossings equal its actions", actions,
                h.fake.reviewerActionBoundaryCrossings)
            // B's whole ledger of traffic, after a thousand events of churn:
            assertEquals(0, b.reviewerActionInvocations)
            assertEquals(0, b.reviewerActionBoundaryCrossings)
            assertEquals(0, b.reviewerActionReconciliationCount)
            assertEquals(0, b.nextCardCount)
            assertEquals(0, b.physicalCommitCalls)
            assertEquals(0, b.getDecksCalls)
            assertEquals(0, b.hydrateCalls)
        }

    // ------------------------------------------------------------------ crash sweep

    private data class WindowOutcome(
        val afterCrash: ReviewerActionStatus?,
        val afterRecovery: ReviewerActionStatus?,
        val aInvocations: Int,
        val aCrossings: Int,
        val aReconciliations: Int,
        val bInvocations: Int
    )

    private suspend fun runWindow(window: Int): WindowOutcome {
        val b = backendB()
        val h = ReviewerActionHarness(extraBackends = listOf(b))
        h.loadToRating()
        val receipt = ReviewerActionReceipt(aId, "bury", detail = "confirmed_by_fake")
        val stateReceipt = ReviewerActionReceipt(aId, "bury", detail = "synthetic_current_state_observation")
        when (window) {
            1 -> h.store.failNextWrites = 1 // W1: death before the durable PREPARED write
            2 -> h.store.onDurableWrite = { // W2: death after the durable PREPARED write
                if (h.store.writes.size == 1) throw ProcessDeathSimulation("after_prepared")
            }
            3 -> h.fake.scriptActions( // W3: death after the boundary, before the effect
                FakeAnkiBackend.ActionStep(
                    ReviewerActionBackendResult.ConfirmedApplied(receipt),
                    crashAfterBoundary = true))
            4 -> h.fake.scriptActions( // W4: death after the effect, before the response
                FakeAnkiBackend.ActionStep(
                    ReviewerActionBackendResult.ConfirmedApplied(receipt),
                    crashAfterApply = true,
                    reconciliation = ReviewerActionReconciliationResult.ConfirmedApplied(stateReceipt)))
            5 -> h.fake.scriptActions( // W5: the response is lost, no crash
                FakeAnkiBackend.ActionStep(
                    ReviewerActionBackendResult.OutcomeUnknown(AnkiError.QueryFailure("lost"))))
            6 -> { // W6: the APPLIED write fails; the effect was applied
                h.fake.scriptActions(
                    FakeAnkiBackend.ActionStep(
                        ReviewerActionBackendResult.ConfirmedApplied(receipt),
                        reconciliation = ReviewerActionReconciliationResult.ConfirmedApplied(stateReceipt)))
                h.store.onDurableWrite = {
                    if (h.store.writes.size == 2) h.store.failNextWrites = 1
                }
            }
            else -> { /* control: a clean, confirmed action */ }
        }
        h.requestAction(ReviewerAction.BuryCard)
        var crashed = false
        try {
            h.drain()
        } catch (death: ProcessDeathSimulation) {
            crashed = true
        }
        val afterCrash = h.store.durableRecords().singleOrNull()?.status
        val aInvocationsAfterCrash = h.fake.reviewerActionInvocations
        val aCrossingsAfterCrash = h.fake.reviewerActionBoundaryCrossings

        // New process over exactly those bytes, then the family's recovery pass.
        h.restart()
        h.store.durableRecords().singleOrNull()?.let { record ->
            when (window) {
                // W1 never produced a record; nothing to recover, nothing to classify.
                1 -> Unit
                else -> h.coordinator().recover(record.actionId)
            }
        }
        val afterRecovery = h.store.durableRecords().singleOrNull()?.status

        return WindowOutcome(
            afterCrash = afterCrash,
            afterRecovery = afterRecovery,
            aInvocations = h.fake.reviewerActionInvocations,
            aCrossings = h.fake.reviewerActionBoundaryCrossings,
            aReconciliations = h.fake.reviewerActionReconciliationCount,
            bInvocations = b.reviewerActionInvocations
        ).also {
            // The crash happened exactly where the window says it happened.
            val expectedCrash = window == 2 || window == 3 || window == 4
            assertEquals("window $window: crash expectation", expectedCrash, crashed)
            // The mutation is never replayed: invocation counts freeze at the crash point.
            if (window != 1 && window != 2) {
                assertEquals("window $window: no replay after restart",
                    aInvocationsAfterCrash, h.fake.reviewerActionInvocations)
            }
            // B is never a destination, in any window.
            assertEquals("window $window: B receives nothing", 0, b.reviewerActionInvocations)
            assertEquals("window $window: keyless OP-M3 never reads current card state",
                0, h.fake.reviewerActionReconciliationCount)
            assertEquals(0, b.reviewerActionBoundaryCrossings)
            assertEquals(0, b.nextCardCount)
            assertEquals(0, b.physicalCommitCalls)
            // The durable truth is the window's, in every run.
            assertEquals("window $window: durable state after the crash",
                expectedAfterCrash(window), afterCrash)
            assertEquals("window $window: durable state after recovery",
                expectedAfterRecovery(window), afterRecovery)
            // Where the window crossed the boundary, it crossed it exactly once, ever.
            when (window) {
                0, 3, 4, 5, 6 -> assertEquals(
                    "window $window: exactly one boundary crossing, ever",
                    1, h.fake.reviewerActionBoundaryCrossings)
                1, 2 -> assertEquals(
                    "window $window: the boundary was never crossed",
                    0, aCrossingsAfterCrash)
            }
        }
    }

    private fun expectedAfterCrash(window: Int) = when (window) {
        1 -> null // no durable intent exists
        2 -> ReviewerActionStatus.PREPARED
        3 -> ReviewerActionStatus.SUBMITTING
        4 -> ReviewerActionStatus.SUBMITTING
        5 -> ReviewerActionStatus.AMBIGUOUS
        6 -> ReviewerActionStatus.SUBMITTING
        else -> ReviewerActionStatus.APPLIED
    }

    private fun expectedAfterRecovery(window: Int) = when (window) {
        1 -> null
        2 -> ReviewerActionStatus.PREPARED // provably un-entered: offered as a safe retry
        3 -> ReviewerActionStatus.AMBIGUOUS // keyless OP-M3 is blocked: explicit uncertainty
        4 -> ReviewerActionStatus.AMBIGUOUS // current card state is not historical proof
        5 -> ReviewerActionStatus.AMBIGUOUS // no evidence either way
        6 -> ReviewerActionStatus.AMBIGUOUS // lost APPLIED write cannot be reconstructed from state
        else -> ReviewerActionStatus.APPLIED
    }

    @Test
    fun `the six crash windows hold their durable truth across one hundred fresh processes each`() =
        runTest {
            for (window in 1..6) {
                repeat(100) { runWindow(window) }
            }
            // The clean control: a confirmed action is APPLIED with exactly one mutation.
            repeat(100) {
                val outcome = runWindow(0)
                assertEquals(1, outcome.aInvocations)
                assertEquals(1, outcome.aCrossings)
                assertEquals(0, outcome.aReconciliations)
                assertEquals(0, outcome.bInvocations)
            }
        }

    @Test
    fun `keyless ambiguous recovery is blocked before any card-state read across a hundred crashes`() =
        runTest {
            repeat(100) {
                val outcome = runWindow(3)
                assertEquals(
                    "W3 OP-M3 has no authoritative collection match",
                    0, outcome.aReconciliations)
                assertEquals("W3 leaves explicit uncertainty, never a guess",
                    ReviewerActionStatus.AMBIGUOUS, outcome.afterRecovery)
            }
        }

    @Test
    fun `an applied-then-lost effect stays ambiguous without historical evidence`() =
        runTest {
            repeat(100) {
                val outcome = runWindow(4)
                assertEquals(ReviewerActionStatus.AMBIGUOUS, outcome.afterRecovery)
                assertEquals("the effect happened exactly once", 1, outcome.aInvocations)
                assertEquals(1, outcome.aCrossings)
                assertEquals("the current queue state is not a historical receipt", 0,
                    outcome.aReconciliations)
                assertEquals(0, outcome.bInvocations)
            }
        }
}
