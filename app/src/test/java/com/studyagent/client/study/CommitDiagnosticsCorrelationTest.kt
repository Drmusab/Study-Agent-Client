package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeCommitMode
import com.studyagent.client.core.anki.CommitPhaseSink
import com.studyagent.client.core.anki.ReviewCommitId
import com.studyagent.client.core.anki.ReviewCommitStatus
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.AnkiStudyEffectExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

/**
 * GATE 11 checkpoint 19 — diagnostics correlation.
 *
 * Every commit phase marker must carry the transaction identity (session, turn, commit, backend),
 * the attempt count and the state, so a support bundle can join the ledger, the backend and the
 * session timeline without any card content. The phase sink is the durable marker path the
 * production container feeds into the diagnostics timeline.
 */
class CommitDiagnosticsCorrelationTest {

    private data class PhaseMark(val phase: String, val attempt: Int, val commitId: ReviewCommitId)

    @Test
    fun `phase markers cover created prepared call entered and local commit with correlation`() = runTest {
        val marks = mutableListOf<PhaseMark>()
        val harness = AnkiCommitHarness(wrap = { it })
        val executor = AnkiStudyEffectExecutor(
            registry = com.studyagent.client.core.anki.AnkiBackendRegistry(listOf(harness.backend)),
            ledger = harness.ledger,
            clock = { harness.now },
            phases = CommitPhaseSink { phase, attempt, commitId -> marks += PhaseMark(phase, attempt, commitId) }
        )
        harness.loadToRating()
        harness.rate(Rating.GOOD)
        val effect = harness.takeCommitEffect()
        executor.execute(effect) { harness.send(it) }
        harness.drain()

        assertEquals(
            listOf("CREATED", "INTENT_PERSISTED", "MUTATION_BOUNDARY_ENTERED", "FINAL_STATUS_PERSISTED"),
            marks.map { it.phase }
        )
        val commit = harness.ledger.snapshot().single()
        assertTrue("every marker carries the same transaction identity", marks.all { it.commitId == commit.commitId })
        assertEquals("the first marker is the creation of the intent row", 0, marks.first().attempt)
        assertTrue("attempt markers carry the attempt count", marks.drop(1).all { it.attempt >= 1 })
    }

    @Test
    fun `a safe failure keeps the same correlation and never claims committed`() = runTest {
        val marks = mutableListOf<PhaseMark>()
        val harness = AnkiCommitHarness(fakeMode = FakeCommitMode.FAIL_BEFORE_MUTATION)
        val executor = AnkiStudyEffectExecutor(
            registry = com.studyagent.client.core.anki.AnkiBackendRegistry(listOf(harness.backend)),
            ledger = harness.ledger,
            clock = { harness.now },
            phases = CommitPhaseSink { phase, attempt, commitId -> marks += PhaseMark(phase, attempt, commitId) }
        )
        harness.loadToRating()
        harness.rate(Rating.HARD)
        val effect = harness.takeCommitEffect()
        executor.execute(effect) { harness.send(it) }
        harness.drain()

        val row = harness.ledger.snapshot().single()
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, row.status)
        assertTrue(row.status == ReviewCommitStatus.RETRY_ALLOWED)
        assertEquals("the backend was called once and never mutated", 1, harness.fake.deliveryCount)
        assertEquals(0, harness.fake.mutationAttemptCount)
        assertEquals(0, harness.fake.backendEffectCount)
        assertEquals("every marker belongs to this transaction",
            listOf(row.commitId), marks.map { it.commitId }.distinct())
        // This mode returns a typed refusal before the mutation-boundary callback. A delivery
        // alone therefore does not count as a mutation attempt; only the validated ledger state
        // authorizes retrying with this same commit identity.
        assertEquals("CREATED first", "CREATED", marks.first().phase)
    }
}
