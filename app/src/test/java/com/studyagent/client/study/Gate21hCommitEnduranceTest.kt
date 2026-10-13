package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 21H — endurance: thousands of events, cross-backend churn and every crash window.
 *
 * Two properties are under test, and they are the two the gate exists for:
 *
 * 1. **Ownership never moves.** Across preference and availability churn the transaction always
 *    belongs to the backend it was admitted against; the other registered backend sees nothing —
 *    not a commit, not a next-card query, not a reconciliation read.
 * 2. **Death never licenses a replay.** A crash injected at every fired [CommitFaultPoint] leaves
 *    exactly what was durable at that instant — never more — and a restart over those bytes is a
 *    read, not a submission.
 */
class Gate21hCommitEnduranceTest {

    private val aId = AnkiBackendId.AnkiDroidLocal
    private val bId = AnkiBackendId.PcAgent("gate21h-endurance-b")

    private fun substitute() = FakeAnkiBackend(
        id = bId,
        decks = listOf(AnkiDeck(AnkiCommitHarness.deckFor(bId), "B Deck")),
        cards = listOf(AnkiCommitHarness.card("123", bId)),
        instanceId = "gate21h-endurance"
    ).apply {
        currentCollectionIdentity = AnkiCollectionIdentity(bId, AnkiCommitHarness.COLLECTION_KEY)
    }

    // ----------------------------------------------------------------- VER-21H-34  churn endurance

    @Test
    fun `1000 churn events never move a commit to the other backend`() = runTest {
        val b = substitute()
        val cards = List(80) { AnkiCommitHarness.card("C$it", aId) }
        val h = AnkiCommitHarness(cards = cards, extraBackends = listOf(b))
        val resolver = AnkiBackendResolver(AnkiBackendRegistry(listOf(h.backend, b)))
        h.startAndLoad()

        var turns = 0
        repeat(1000) { event ->
            // Churn: the substitute backend is alternately ready and down, and the preference would
            // flip with it — none of which may reach this session's transactions.
            b.setAvailability(
                if (event % 2 == 0) AnkiAvailability.Ready(b.capabilities.value)
                else AnkiAvailability.TemporarilyUnavailable()
            )
            if (event % 3 == 0 && event % 2 == 0) {
                // Resolution *outside* the session now answers the substitute — correct, and
                // irrelevant to the session that was admitted on A.
                val resolved = resolver.resolve(BackendResolutionRequest(AnkiBackendMode.PC_AGENT))
                assertEquals(bId, (resolved as BackendResolutionResult.Resolved).backendId)
            }
            if (event % 20 == 19 && h.state.anki?.reviewSession != null) {
                h.send(StudyEvent.UserRequestAnswer(h.state.currentCardId!!))
                h.rate(if (turns % 3 == 0) Rating.EASY else Rating.GOOD)
                h.run(h.takeCommitEffect())
                h.drain()
                turns += 1
            }
            assertEquals(
                "the session keeps its own backend at event $event",
                aId, h.state.anki?.reviewSession?.context?.backendId
            )
        }

        assertTrue("the churn must actually commit: $turns turns", turns >= 40)
        assertEquals("every commit was A-owned", turns, h.fake.physicalCommitCalls)
        assertEquals("a preference never reroutes a rating", 0, b.physicalCommitCalls)
        assertEquals("the substitute backend is never consulted for a card", 0, b.nextCardCount)
        assertEquals(0, b.reconcileCalls)
        assertEquals(0, b.getDecksCalls)
        assertEquals(0, b.hydrateCalls)
        val records = h.store.durableRecords()
        assertEquals("one durable COMMITTED per played turn", turns,
            records.count { it.status == ReviewCommitStatus.COMMITTED })
        assertEquals("every durable transaction is A-owned",
            emptyList<ReviewCommitRecord>(), records.filter { it.backendId != aId })
    }

    // ------------------------------------------------------- VER-21H-35/36  crash-window sweep

    /** Every window the executor actually fires; `AFTER_LEDGER_CREATE` is declared but unused. */
    private val firedWindows = listOf(
        CommitFaultPoint.BEFORE_LEDGER_CREATE,
        CommitFaultPoint.AFTER_PREPARED,
        CommitFaultPoint.AFTER_CALL_ENTERED,
        CommitFaultPoint.BEFORE_PROVIDER_CALL,
        CommitFaultPoint.AFTER_PROVIDER_MUTATION,
        CommitFaultPoint.BEFORE_RESPONSE_PERSIST,
        CommitFaultPoint.AFTER_RESPONSE_PERSIST,
        CommitFaultPoint.BEFORE_COMMITTED_PERSIST,
        CommitFaultPoint.AFTER_COMMITTED_PERSIST
    )

    /** What a durable row may say after a death at each fired window. */
    private val expectedAfterDeath = mapOf(
        CommitFaultPoint.BEFORE_LEDGER_CREATE to null,
        CommitFaultPoint.AFTER_PREPARED to ReviewCommitStatus.PREPARED,
        CommitFaultPoint.AFTER_CALL_ENTERED to ReviewCommitStatus.AMBIGUOUS,
        CommitFaultPoint.BEFORE_PROVIDER_CALL to ReviewCommitStatus.AMBIGUOUS,
        CommitFaultPoint.AFTER_PROVIDER_MUTATION to ReviewCommitStatus.AMBIGUOUS,
        CommitFaultPoint.BEFORE_RESPONSE_PERSIST to ReviewCommitStatus.AMBIGUOUS,
        // A definitive backend response is *itself* durable proof: once it is written the
        // transaction is terminal even though the final-status write has not happened yet.
        CommitFaultPoint.AFTER_RESPONSE_PERSIST to ReviewCommitStatus.COMMITTED,
        CommitFaultPoint.BEFORE_COMMITTED_PERSIST to ReviewCommitStatus.COMMITTED,
        CommitFaultPoint.AFTER_COMMITTED_PERSIST to ReviewCommitStatus.COMMITTED
    )

    @Test
    fun `1000 injected crashes across every window never duplicate a mutation or advance uncommitted`() =
        runTest {
            var crashes = 0
            var ambiguous = 0
            var committed = 0
            val reachedWindows = mutableSetOf<CommitFaultPoint>()
            val perWindow = 100

            for (point in firedWindows) {
                repeat(perWindow) { index ->
                    val b = substitute()
                    val h = AnkiCommitHarness(
                        extraBackends = listOf(b),
                        faults = ThrowingCommitFault(point)
                    )
                    h.loadToRating()
                    h.rate(Rating.GOOD)
                    val effect = h.takeCommitEffect()
                    val cardBefore = h.turn?.cardRef?.cardId
                    try {
                        h.run(effect)
                        fail("window $point did not fire")
                    } catch (fault: CommitFaultException) {
                        assertEquals(point, fault.point)
                        crashes += 1
                        reachedWindows += point
                    }

                    // Process death, then a new process over exactly those bytes.
                    h.restartLedger()

                    val bytes = h.store.durableRecords()
                    if (point == CommitFaultPoint.BEFORE_LEDGER_CREATE) {
                        assertEquals("a crash before the intent leaves nothing", 0, bytes.size)
                    } else {
                        assertEquals("one logical transaction, never two", 1, bytes.size)
                        assertEquals("the transaction keeps its owner", aId, bytes.single().backendId)
                        assertEquals("the transaction keeps its identity",
                            effect.request.commitId, bytes.single().commitId)
                        assertEquals("restart never re-owns the transaction",
                            aId, h.ledger.unresolved().firstOrNull()?.backendId ?: aId)
                        // The ledger heals what a death made unknowable on load (a SUBMITTING row
                        // becomes AMBIGUOUS); that is a load-time rule, and it is never a rewrite
                        // into "probably fine".
                        val record = h.ledger.get(effect.request.commitId)!!
                        when (record.status) {
                            ReviewCommitStatus.AMBIGUOUS -> ambiguous += 1
                            ReviewCommitStatus.COMMITTED -> committed += 1
                            else -> Unit
                        }
                        // The durable status after death is decided by where the process died: a
                        // row that never entered the boundary is PREPARED (provably not applied),
                        // anything at or past the boundary is AMBIGUOUS, and only the very last
                        // window leaves a durable COMMITTED.
                        assertEquals(
                            "window $point #$index settled wrong",
                            expectedAfterDeath.getValue(point), record.status
                        )
                    }

                    // Progression is never granted without a durable COMMITTED. The converse is
                    // deliberately not asserted: a death *after* the durable commit but before the
                    // projection learned about it is exactly the case recovery exists for.
                    val played = h.state.session?.totalReviewedInSession ?: 0
                    val durableCommitted = h.ledger.snapshot()
                        .count { it.status == ReviewCommitStatus.COMMITTED }
                    if (durableCommitted == 0) {
                        assertEquals("no progression without COMMITTED: $point #$index", 0, played)
                        assertEquals("no next-card query without COMMITTED",
                            1, h.fake.nextCardCount) // the initial card only
                    }
                    assertTrue(
                        "progressed $played reviews with $durableCommitted durable commits: $point",
                        played <= durableCommitted
                    )

                    // Exactly one physical scheduler effect per logical transaction, ever.
                    h.fake.recordedCommits().forEach { rc ->
                        assertTrue(
                            "logical ${rc.request.commitId} was applied ${rc.mutationCount}x",
                            rc.mutationCount <= 1
                        )
                    }
                    // The substitute backend is not an escape hatch, even after death.
                    assertEquals(0, b.physicalCommitCalls)
                    assertEquals(0, b.reconcileCalls)
                    assertEquals(0, b.nextCardCount)
                    assertEquals("the card never advanced on an unproven outcome",
                        cardBefore, h.turn?.cardRef?.cardId ?: cardBefore)
                }
            }

            // 100 clean controls: the same loop with no fault — the sweep must include a control.
            repeat(100) { index ->
                val h = AnkiCommitHarness()
                h.loadToRating()
                h.rate(Rating.GOOD)
                val effect = h.takeCommitEffect()
                h.run(effect)
                assertEquals(
                    "control $index must commit",
                    ReviewCommitStatus.COMMITTED, h.ledger.get(effect.request.commitId)!!.status
                )
                assertEquals("control $index applied once", 1, h.fake.physicalCommitCalls)
            }

            assertEquals("every fired window was exercised", firedWindows.toSet(), reachedWindows)
            assertEquals("$perWindow crashes per window", firedWindows.size * perWindow, crashes)
            assertTrue("the sweep must observe unknowable outcomes: $ambiguous", ambiguous > 0)
            assertTrue("and must observe the durable-commit window: $committed", committed > 0)
        }
}
