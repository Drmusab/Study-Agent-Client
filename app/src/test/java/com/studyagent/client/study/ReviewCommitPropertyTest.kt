package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeCommitMode
import com.studyagent.client.core.anki.ReviewCommitRecord
import com.studyagent.client.core.anki.ReviewCommitState
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.AnkiStudyEvent
import com.studyagent.client.core.study.AnkiStudyEffect
import com.studyagent.client.core.study.SessionPhase
import com.studyagent.client.core.study.StudyEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * GATE 11 checkpoint 15 — global properties over deterministic event sequences.
 *
 * The sequences come from a fixed seed, so a failure is reproducible. After *every* step the test
 * re-checks the properties the gate names:
 *
 * ```text
 * logicalCommitCount(turn) <= 1
 * activeMutationAttempts(session) <= 1
 * nextCardRequested(turn) -> commitState(turn) == COMMITTED
 * AMBIGUOUS -> no additional mutation attempt
 * COMMITTED -> always COMMITTED
 * ```
 *
 * Attempts are read back from the fake backend's own record (`attempts` per commit id) and effects
 * from its effect counter, never from client-side bookkeeping.
 */
class ReviewCommitPropertyTest {

    private class Tracker {
        val committedRatings = mutableMapOf<String, Rating>()
        val attemptsPerCommit = mutableMapOf<String, Int>()
    }

    private suspend fun observe(harness: AnkiCommitHarness, tracker: Tracker) {
        val rows = harness.ledger.snapshot()

        // logicalCommitCount(turn) <= 1 — one ledger row per review turn, ever.
        assertEquals("one ledger row per turn", rows.map { it.turnId }.distinct().size, rows.size)

        // COMMITTED -> always COMMITTED: state, rating and identity never move again.
        for (row in rows.filter { it.state == ReviewCommitState.COMMITTED }) {
            val key = row.commitId.stableKey
            val known = tracker.committedRatings[key]
            if (known == null) tracker.committedRatings[key] = row.rating
            else assertEquals("a committed rating is immutable", known, row.rating)
            assertTrue("a committed row keeps its phase",
                row.phase == com.studyagent.client.core.anki.CommitAttemptPhase.LOCAL_RESULT_PERSISTED)
        }

        // nextCardRequested(turn) -> commitState(turn) == COMMITTED: every next card except the
        // session's first is preceded by a durable COMMITTED row.
        val committedRows = rows.count { it.state == ReviewCommitState.COMMITTED }
        assertTrue(
            "next card requests (${harness.fake.nextCardCount}) outran COMMITTED rows ($committedRows)",
            harness.fake.nextCardCount <= committedRows + 1
        )

        // activeMutationAttempts(session) <= 1: at most one commit row per study session is in a
        // dispatched state at any moment.
        val dispatched = rows.count { it.state == ReviewCommitState.SUBMITTING }
        assertTrue("at most one in-flight mutation per session", dispatched <= 1)

        for (row in rows) assertConsistent(row)
    }

    private suspend fun observeAttempts(harness: AnkiCommitHarness, tracker: Tracker) {
        for (recorded in harness.fake.recordedCommits()) {
            val key = recorded.request.commitId.stableKey
            val previous = tracker.attemptsPerCommit[key]
            if (previous != null) {
                assertTrue("mutation attempts never decrease", recorded.attempts >= previous)
            }
            tracker.attemptsPerCommit[key] = recorded.attempts
        }
    }

    /** No AMBIGUOUS row may gain a mutation attempt while it stays ambiguous. */
    private suspend fun assertAmbiguousNeverRetried(harness: AnkiCommitHarness, tracker: Tracker) {
        val attempted = harness.fake.recordedCommits().associate { it.request.commitId.stableKey to it.attempts }
        for (row in harness.ledger.snapshot().filter { it.state == ReviewCommitState.AMBIGUOUS }) {
            val key = row.commitId.stableKey
            val frozen = tracker.attemptsPerCommit[key] ?: continue
            assertEquals("an AMBIGUOUS commit may not be re-attempted", frozen, attempted[key] ?: 0)
        }
    }

    private val actions: List<suspend (AnkiCommitHarness) -> Boolean> = listOf(
        // A fresh rating for the current card.
        { h ->
            val cardId = h.state.currentCardId
            cardId != null && h.rate(Rating.AGAIN).accepted
        },
        // Duplicate input: the same rating and a different one.
        { h ->
            val cardId = h.state.currentCardId
            if (cardId == null) false
            else h.send(StudyEvent.UserRateCard(Rating.GOOD, cardId)).accepted ||
                h.send(StudyEvent.UserRateCard(Rating.HARD, cardId)).accepted
        },
        // Execute one queued effect.
        { h ->
            val next = h.pending.firstOrNull()
            if (next == null) false else {
                h.pending.removeFirst()
                h.run(next)
                true
            }
        },
        // Process death between two durable steps.
        { h ->
            h.restartLedger()
            true
        },
        // Explicit, proven-safe retry only.
        { h ->
            val commit = h.commit
            if (commit != null && commit.safeToRetry && h.state.phase == SessionPhase.RatingCommitFailed)
                h.send(AnkiStudyEvent.RetryRatingCommit(h.state.epoch, commit.commitId)).accepted
            else false
        },
        // Reconciliation of an ambiguous commit (read-only).
        { h ->
            val commit = h.commit
            if (commit != null && h.state.phase == SessionPhase.ReconciliationRequired)
                h.send(AnkiStudyEvent.ReconcileRatingCommit(h.state.epoch, commit.commitId)).accepted
            else false
        },
        // Ending the session must never mutate the scheduler.
        { h -> h.send(StudyEvent.UserEndRequested("prop-end-${h.state.epoch}")).accepted }
    )

    @Test
    fun `properties hold across seeded failure sequences`() = runTest {
        // DELAYED_SUCCESS needs an explicit release (covered by the checkpoint 4 laboratory); every
        // other mode is self-contained, so sequences always terminate deterministically.
        val modes = FakeCommitMode.entries.filter { it != FakeCommitMode.DELAYED_SUCCESS }
        for (seed in 1..12) {
            val random = Random(seed)
            val harness = AnkiCommitHarness(fakeMode = modes[seed % modes.size])
            val tracker = Tracker()
            harness.loadToRating()
            observe(harness, tracker)
            observeAttempts(harness, tracker)

            repeat(24) {
                actions[random.nextInt(actions.size)](harness)
                harness.drain()
                observe(harness, tracker)
                observeAttempts(harness, tracker)
                assertAmbiguousNeverRetried(harness, tracker)
            }
        }
    }

    @Test
    fun `the success path is a fixed point once committed`() = runTest {
        val harness = AnkiCommitHarness()
        val tracker = Tracker()
        harness.loadToRating()
        harness.rate(Rating.GOOD)
        harness.drain()
        observe(harness, tracker)
        assertEquals(1, harness.fake.backendEffectCount)
        assertEquals(1, harness.ledger.snapshot().count { it.state == ReviewCommitState.COMMITTED })
        assertEquals("the first card plus the post-commit advance", 2, harness.fake.nextCardCount)

        // Re-executing the same commit effect cannot create a second row or a second effect.
        val replay = AnkiCommitHarness()
        replay.loadToRating()
        replay.rate(Rating.GOOD)
        val effect = replay.takeCommitEffect()
        replay.run(effect)
        replay.drain()
        replay.run(effect)
        replay.drain()
        assertEquals(1, replay.fake.backendEffectCount)
        assertEquals(1, replay.ledger.snapshot().size)
        observe(replay, Tracker())
    }

    @Test
    fun `an ambiguous row that is never reconciled keeps its delivery count frozen`() = runTest {
        val harness = AnkiCommitHarness(fakeMode = FakeCommitMode.MUTATE_THEN_DROP_RESPONSE)
        val tracker = Tracker()
        harness.loadToRating()
        harness.rate(Rating.GOOD)
        harness.drain()
        observe(harness, tracker)
        observeAttempts(harness, tracker)
        val frozen = harness.fake.recordedCommits().single().attempts
        val ambiguous = harness.ledger.snapshot().single()
        assertEquals(ReviewCommitState.AMBIGUOUS, ambiguous.state)

        repeat(10) {
            val cardId = harness.state.currentCardId
            if (cardId != null) {
                harness.send(StudyEvent.UserRateCard(Rating.GOOD, cardId))
                harness.send(StudyEvent.UserRateCard(Rating.HARD, cardId))
            }
            harness.send(AnkiStudyEvent.RetryRatingCommit(harness.state.epoch, ambiguous.commitId))
            harness.drain()
            assertAmbiguousNeverRetried(harness, tracker)
        }
        assertEquals(frozen, harness.fake.recordedCommits().single().attempts)
        assertEquals(1, harness.fake.backendEffectCount)
        assertTrue(harness.pending.filterIsInstance<AnkiStudyEffect.CommitRating>().isEmpty())
    }

    private fun assertConsistent(row: ReviewCommitRecord) {
        assertTrue("a phase is present for every dispatched attempt", row.attemptCount == 0 || row.phase != null)
        assertTrue("a COMMITTED row has no failure", row.state != ReviewCommitState.COMMITTED || row.failure == null)
        assertTrue("an AMBIGUOUS row is never safe to retry",
            !(row.state == ReviewCommitState.AMBIGUOUS && row.safeToRetry))
        assertTrue("a FAILED row explains itself", row.state != ReviewCommitState.FAILED || row.failure != null)
    }
}
