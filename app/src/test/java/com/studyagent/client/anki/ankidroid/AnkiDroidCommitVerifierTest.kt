package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.ReviewCommitEvidence
import com.studyagent.client.core.models.Rating
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCardState
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCommitVerifier
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCommitVerifier.Verdict
import com.studyagent.client.data.anki.ankidroid.AnkiDroidRatingContract
import org.junit.Assert.*
import org.junit.Test

/** GATE 11 — the one ease mapping, the evidence codec and an advisory immediate observation. */
class AnkiDroidCommitVerifierTest {

    @Test fun `the ease mapping is the verified v2_24_1 provider contract`() {
        // answer_ease → Ease.fromValue → CardAnswer.Rating.forNumber(value - 1): AGAIN=0 … EASY=3.
        assertEquals(1, AnkiDroidRatingContract.easeFor(Rating.AGAIN))
        assertEquals(2, AnkiDroidRatingContract.easeFor(Rating.HARD))
        assertEquals(3, AnkiDroidRatingContract.easeFor(Rating.GOOD))
        assertEquals(4, AnkiDroidRatingContract.easeFor(Rating.EASY))
        assertEquals(4, Rating.entries.map(AnkiDroidRatingContract::easeFor).distinct().size)
    }

    private val base = AnkiDroidCardState(
        cardId = 11, noteId = 1700000000123, cardOrd = 0, deckId = 1, originalDeckId = 0,
        reps = 5, lapses = 1, intervalDays = 3, type = 2, queue = 2, due = 19_000, lastReviewEpochSeconds = 1_699_000_000
    )
    private val windowStart = 1_700_000_000_000L
    private val windowEnd = 1_700_000_000_300L

    private fun answered(state: AnkiDroidCardState = base, at: Long = windowStart / 1000) =
        state.copy(reps = state.reps + 1, intervalDays = 7, due = 19_004, lastReviewEpochSeconds = at)

    @Test fun `evidence round trips and carries identifiers and counters only`() {
        val evidence = base.toEvidence(capturedAtMs = 42)
        assertEquals(AnkiDroidCardState.FORMAT, evidence.format)
        assertEquals(base, AnkiDroidCardState.fromEvidence(evidence))
        assertTrue(evidence.token.length < ReviewCommitEvidence.MAX_TOKEN_LENGTH)
        assertNull(AnkiDroidCardState.fromEvidence(ReviewCommitEvidence("other-format", evidence.token)))
        assertNull(AnkiDroidCardState.fromEvidence(ReviewCommitEvidence(AnkiDroidCardState.FORMAT, "c=1;n=x")))
        assertNull(AnkiDroidCardState.fromEvidence(null))
        val noReview = base.copy(lastReviewEpochSeconds = null)
        assertEquals(noReview, AnkiDroidCardState.fromEvidence(noReview.toEvidence(1)))
    }

    @Test fun `a single review in the window is consistent but not a transaction receipt`() {
        val verdict = AnkiDroidCommitVerifier.classify(base, answered(), windowStart, windowEnd, tightWindow = true)
        assertTrue(verdict is Verdict.ConsistentWithAnswer)
    }

    @Test fun `no change on a normal card is not proof a lost call will not land`() {
        val verdict = AnkiDroidCommitVerifier.classify(base, base, windowStart, windowEnd, tightWindow = true)
        assertEquals(Verdict.Unchanged("no_state_change_observed"), verdict)
    }

    @Test fun `a timestamp outside the window cannot attribute the answer`() {
        val later = answered(at = windowEnd / 1000 + 600)
        assertEquals(Verdict.Unattributable("review_time_outside_window"),
            AnkiDroidCommitVerifier.classify(base, later, windowStart, windowEnd, tightWindow = false))
    }

    @Test fun `extra reviews, lost counters or identity changes are never attributed`() {
        val twice = answered().copy(reps = base.reps + 2)
        val reset = base.copy(reps = 0)
        val moved = base.copy(cardId = 99)
        val noTime = base.copy(reps = base.reps + 1, lastReviewEpochSeconds = null)
        val rescheduled = base.copy(due = 20_000)
        for (after in listOf(twice, reset, moved, noTime, rescheduled)) {
            assertTrue("$after", AnkiDroidCommitVerifier.classify(base, after, windowStart, windowEnd, true) is Verdict.Unattributable)
        }
    }

    @Test fun `filtered deck cards are conservative because preview answers do not move reps`() {
        val filtered = base.copy(deckId = 77, originalDeckId = 1, queue = 4)
        // Preview answer: queue/due/deck change without reps (rslib preview.rs).
        val previewed = filtered.copy(due = 1_700_000_600)
        assertTrue(AnkiDroidCommitVerifier.classify(filtered, previewed, windowStart, windowEnd, tightWindow = true)
            is Verdict.Unattributable)
        assertTrue("never attributed after the fact",
            AnkiDroidCommitVerifier.classify(filtered, previewed, windowStart, windowEnd, tightWindow = false) is Verdict.Unattributable)
        assertTrue("no change is not proof for a possible preview card",
            AnkiDroidCommitVerifier.classify(filtered, filtered, windowStart, windowEnd, true) is Verdict.Unattributable)
        // A rescheduling filtered deck still moves reps normally.
        assertTrue(AnkiDroidCommitVerifier.classify(filtered, answered(filtered), windowStart, windowEnd, true) is Verdict.ConsistentWithAnswer)
    }
}
