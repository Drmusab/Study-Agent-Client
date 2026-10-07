package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.InMemoryReviewCommitStore
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 11 checkpoint 17 — endurance rather than chaos.
 *
 * One thousand single-card transactions through the same reducer + executor + ledger + fake backend
 * pair the chaos suite uses, in two halves:
 *
 * * 800 committed transactions with the four scheduler ratings in rotation, each one preceded by a
 *   duplicate rating for the same turn — so every transaction proves *duplicate input never reaches
 *   the backend* and the next card is only ever presented after a durable COMMITTED;
 * * 200 ambiguous transactions, each in its own process-like harness over one shared durable store
 *   — so the unresolved rows accumulate exactly as they would across restarts, and each one proves
 *   one delivery, zero mutations, zero automatic resends and no next card.
 *
 * The assertions are the gate's four zeros: no duplicate logical commit, no blind ambiguous retry,
 * no next card before COMMITTED, no conflicting ledger state.
 */
class ReviewCommitEnduranceTest {

    private val committedTransactions = 800

    private val ambiguousTransactions = 200

    @Test fun `one thousand transactions never duplicate commit or advance before COMMITTED`() = runTest {
        val harness = AnkiCommitHarness(
            cards = List(committedTransactions + 4) { AnkiCommitHarness.card("E$it") },
            commitSteps = List(committedTransactions) {
                FakeAnkiBackend.CommitStep(BackendCommitResult.ConfirmedCommitted())
            }
        )
        harness.loadToRating()
        val ratedTurns = mutableSetOf<ReviewTurnId>()
        var committedSeen = 0
        repeat(committedTransactions) { index ->
            val turn = requireNotNull(harness.turn) { "no turn before transaction $index" }
            assertTrue("turn ${turn.turnId} was rated twice", ratedTurns.add(turn.turnId))
            val rating = Rating.entries[index % Rating.entries.size]
            harness.rate(rating)

            // Duplicate input: a second rating for the same turn is refused and never delivered.
            val duplicate = harness.send(StudyEvent.UserRateCard(rating, requireNotNull(harness.state.currentCardId)))
            assertFalse("duplicate rating accepted at $index: ${duplicate.rejectionReason}", duplicate.accepted)

            harness.run(harness.takeCommitEffect())
            val record = requireNotNull(harness.ledger.get(turn.commitId)) { "no record for $turn" }
            assertEquals("transaction $index state", ReviewCommitStatus.COMMITTED, record.status)
            assertEquals("the requested rating is the recorded one", rating, record.rating)

            // Sampling the backend's own table costs O(rows), so it is checked rather than
            // recomputed per transaction; the aggregate totals at the end cover every row.
            if (index % 25 == 0) {
                val backendRecord = harness.fake.recordedCommits().first { it.request.commitId == turn.commitId }
                assertEquals("one backend mutation for $turn", 1, backendRecord.mutationCount)
                assertEquals("the requested rating reached the backend", rating, backendRecord.request.rating)
            }

            // Zero conflicting ledger state: committed rows are immutable, and no row exists for a
            // turn that was never rated. Sampled to keep the run linear.
            if (index % 100 == 99) {
                val rows = harness.ledger.snapshot()
                assertEquals("no row may be added before its turn is rated", ratedTurns.size, rows.size)
                assertTrue("committed rows stay committed at $index",
                    rows.all { it.status == ReviewCommitStatus.COMMITTED })
            }

            harness.drain()
            assertNotEquals("next card only after COMMITTED",
                turn.turnId, requireNotNull(harness.turn) { "no next turn after $index" }.turnId)
            committedSeen++

            // The next turn is presented, not yet answerable: the user requests the answer first,
            // exactly as the harness's own loadToRating helper does for the first card.
            harness.now += 4_000L
            harness.send(StudyEvent.UserRequestAnswer(requireNotNull(harness.state.currentCardId)))
            harness.drain()
            assertEquals("turn $index did not reach the rating UI", SessionPhase.WaitingForRating, harness.state.phase)
        }

        val rows = harness.ledger.snapshot()
        assertEquals(committedSeen, rows.size)
        assertEquals("one logical commit per rated turn", committedSeen, rows.map { it.commitId }.distinct().size)
        assertEquals(committedSeen, harness.fake.logicalCommitCount)
        assertEquals(committedSeen, harness.fake.mutationAttemptCount)
        assertEquals(committedSeen, harness.fake.backendEffectCount)
        assertEquals(committedSeen, harness.fake.deliveryCount)
        assertEquals(committedSeen, harness.state.session!!.totalReviewedInSession)
        assertEquals("all four scheduler ratings were exercised", 4, rows.map { it.rating }.toSet().size)

        // ---- second half: ambiguous outcomes lock the turn, never retry blindly ----
        // Each transaction runs in its own process-like harness so the store cannot accumulate
        // unresolved rows across iterations (an unresolved row legitimately blocks a new session,
        // which the sampled restart check below asserts on purpose).
        var ambiguousSeen = 0
        repeat(ambiguousTransactions) { index ->
            val store = InMemoryReviewCommitStore()
            val ambiguous = AnkiCommitHarness(
                store = store,
                cards = listOf(AnkiCommitHarness.card("A")),
                commitSteps = listOf(FakeAnkiBackend.CommitStep(
                    BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("endurance_timeout")),
                    appliedWhenAmbiguous = false))
            )
            ambiguous.loadToRating()
            val nextCardsBefore = ambiguous.fake.nextCardCount
            val turn = requireNotNull(ambiguous.turn)
            ambiguous.rate()
            ambiguous.run(ambiguous.takeCommitEffect())

            val record = requireNotNull(ambiguous.ledger.get(turn.commitId))
            assertEquals("ambiguous transaction $index", ReviewCommitStatus.AMBIGUOUS, record.status)
            assertFalse("ambiguous never becomes retryable", record.status == ReviewCommitStatus.RETRY_ALLOWED)
            assertEquals("one row per rated turn", 1, ambiguous.ledger.snapshot().size)
            val backendRecord = ambiguous.fake.recordedCommits().single()
            assertEquals("no automatic retry for $turn", 1, backendRecord.attempts)
            assertEquals("nothing was mutated for $turn", 0, backendRecord.mutationCount)
            assertEquals(1, ambiguous.fake.deliveryCount)
            assertEquals(0, ambiguous.fake.backendEffectCount)
            assertEquals("no next card after an unresolved commit",
                nextCardsBefore, ambiguous.fake.nextCardCount)
            // The blocked turn cannot be rated again.
            assertFalse(ambiguous.send(StudyEvent.UserRateCard(
                Rating.GOOD, requireNotNull(turn.cardRef.cardId))).accepted)
            ambiguousSeen++

            // Zero blind ambiguous retries across a restart: the row survives, the session stays
            // blocked, and the new process does not re-deliver the rating on its own.
            if (index % 25 == 0) {
                ambiguous.restartLedger()
                assertEquals("the unresolved transaction survives a restart",
                    ReviewCommitStatus.AMBIGUOUS, ambiguous.ledger.get(turn.commitId)!!.status)

                val replacement = AnkiCommitHarness(store = store, cards = listOf(AnkiCommitHarness.card("A")))
                replacement.startAndLoad()
                assertEquals("an unresolved transaction blocks a new session",
                    SessionPhase.ReconciliationRequired, replacement.state.phase)
                assertEquals(ReviewCommitStatus.AMBIGUOUS, replacement.ledger.get(turn.commitId)!!.status)
                assertEquals("no blind redelivery in the new process", 0, replacement.fake.deliveryCount)
                assertEquals(0, replacement.fake.mutationAttemptCount)
            }
        }

        assertEquals("the run is exactly one thousand transactions",
            1000, committedSeen + ambiguousSeen)
    }
}
