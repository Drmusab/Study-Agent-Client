package com.studyagent.client.study

import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.SessionPhase
import com.studyagent.client.core.study.StudyReducer
import com.studyagent.client.testutil.TestCards
import com.studyagent.client.testutil.newHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-card sessions at integration level (§14/§137).
 *
 * The reducer's own rules are covered in `StudyReducerTest`; what this suite adds is *duration*.
 * A hundred turns is where the interesting classes of bug appear: state that is only almost
 * bounded, a metric window that grew, a timer that was never cancelled, an event that is only
 * rejected the second hundred times, a debug log buffer that collected the whole session.
 *
 * A hundred cards at the user's pace is a couple of hours; here it is virtual time, so the whole
 * suite still finishes in seconds (§8/§18).
 */
class StudySessionSimulationTest {

    private val cardCount = 100

    @Test
    fun `one hundred cards each produce exactly one answer, one rating and one turn`() = runTest {
        val h = newHarness(serverDeckSize = cardCount)
        h.startSession()

        val answered = mutableListOf<String>()
        repeat(cardCount) { index ->
            val cardId = h.playCard(rating = if (index % 3 == 0) Rating.AGAIN else Rating.GOOD)
            if (cardId != null) answered += cardId
            h.assertInvariants("card ${index + 1}")
        }

        assertEquals("every card must be answered", cardCount, answered.size)
        assertEquals("the deck must not repeat a card", cardCount, answered.toSet().size)
        assertEquals(cardCount, h.sentAnswers().size)
        assertEquals(cardCount, h.sentRatings().size)
        assertEquals(cardCount, h.server.ratingsReceived)
        assertEquals(cardCount, h.server.cardIndex)
        assertEquals("one turn per card", cardCount.toLong(), h.machineState.value.cardGeneration)

        // Every utterance and every listening window belongs to exactly one card turn.
        assertEquals(cardCount, h.speech.spokenPurposes().count { it.name == "QUESTION" })
        assertEquals(cardCount, h.speech.spokenPurposes().count { it.name == "FEEDBACK" })
        assertEquals(cardCount, h.recognition.startedPurposes().count { it.name == "ANSWER" })
        assertEquals(cardCount, h.recognition.startedPurposes().count { it.name == "RATING" })
        assertFalse("half-duplex must hold for the whole run", h.overlapDetected)
        assertEquals(0L, h.machineInvariantViolations())
    }

    @Test
    fun `a long session keeps every bounded structure bounded`() = runTest {
        // Capacity deliberately smaller than a hundred cards' worth of events (~13 per turn) so
        // the rotation the assertions below demand actually happens.
        val h = newHarness(serverDeckSize = cardCount, timelineCapacity = 500)
        h.startSession()

        var maxTimers = 0
        repeat(cardCount) {
            h.playCard()
            val resources = h.resources()
            maxTimers = maxOf(maxTimers, resources.pendingTimers)
            assertTrue(
                "the dedup window is bounded at 200, saw ${resources.recentServerMessageIds}",
                resources.recentServerMessageIds <= 200
            )
            assertTrue(
                "the ledger must not grow with the number of cards, saw ${resources.ledgerEntries}",
                resources.ledgerEntries <= 32
            )
            assertTrue(
                "the turn history must not grow with the number of cards, saw ${resources.cardTurnHistory}",
                resources.cardTurnHistory <= StudyReducer.CARD_TURN_HISTORY_LIMIT
            )
            assertTrue(
                "the transition ring buffer is bounded at 200, saw ${resources.transitionHistory}",
                resources.transitionHistory <= 200
            )
            assertTrue("at most one timer per in-flight action, saw $maxTimers", maxTimers <= 4)
        }

        // The diagnostic timeline rotates rather than grows.
        assertTrue("timeline exceeded its capacity", h.timeline.size <= h.timeline.capacity)
        assertTrue("the timeline must have recorded far more than it retains", h.timeline.recordedTotal > h.timeline.size)

        // The process-wide log buffer is bounded too: a hundred cards must not keep a hundred
        // cards' worth of log rows (§20/§21).
        assertTrue("log buffer grew past its bound", AppLogger.size <= AppLogger.MAX_LOG_ENTRIES)
    }

    @Test
    fun `aggregate performance counters describe the whole run`() = runTest {
        val h = newHarness(serverDeckSize = cardCount)
        h.startSession()
        repeat(cardCount) { h.playCard() }

        val snapshot = h.performance.snapshot()
        assertEquals(cardCount.toLong(), snapshot.session.turns)
        assertEquals(cardCount.toLong(), snapshot.session.answersSubmitted)
        assertEquals(cardCount.toLong(), snapshot.session.ratingsSubmitted)
        // Two utterances per card: the question and the feedback. The next card's question may
        // already have started, so the count is "at least" for the last turn and exact overall.
        assertEquals(cardCount * 2L, snapshot.tts.requestsCompleted)
        // The answer window completes through the recognizer (one per card). The rating window is
        // closed by the user's tap, which cancels the turn instead of finalizing it.
        assertEquals("one completed transcript per answered card", cardCount.toLong(), snapshot.stt.turnsCompleted)

        // Latency families are *measured*, not defaulted: an average of `-` would mean the hooks
        // are dead, and a 0ms average would mean the clock is (this is the assertion that would
        // have caught a metrics object that was never wired).
        assertTrue("question→speech must be measured", snapshot.session.questionToSpeechStart.measured)
        assertTrue("speech→listen must be measured", snapshot.session.speechDoneToListen.measured)
        assertTrue("evaluation RTT must be measured", snapshot.session.evaluationRoundTrip.measured)
        assertTrue(
            "p95 must exist once there are samples",
            snapshot.session.speechDoneToListen.p95Ms >= snapshot.session.speechDoneToListen.minMs
        )
        assertTrue("session duration must be reported", snapshot.session.sessionDurationMs >= 0L)
        assertEquals("no TTS failures on a happy run", 0L, snapshot.tts.requestsFailed)
    }

    @Test
    fun `a deck that ends finishes the session and frees every resource`() = runTest {
        val deck = 3
        val h = newHarness(serverDeckSize = deck)
        h.startSession()
        repeat(deck) { h.playCard() }

        assertTrue("the server's finished message must terminate the session", h.awaitPhaseIs(SessionPhase.Finished))
        h.advance(20_000)

        val resources = h.resources()
        assertTrue("a completed deck must leave nothing running: ${resources.render()}", resources.quiescent)
        assertEquals(0, resources.pendingTimers)
        assertEquals(deck, h.sentAnswers().size)
        assertEquals(deck, h.sentRatings().size)
        assertFalse(h.recognition.hasActiveTurn)
        assertFalse(h.speech.isActivelySpeaking)
        h.assertInvariants("deck complete")
    }

    @Test
    fun `cards flow in the order the server sends them`() = runTest {
        val h = newHarness(serverDeckSize = cardCount)
        h.startSession()
        val seen = mutableListOf<String>()
        repeat(cardCount) {
            h.awaitWaitingForAnswer()
            h.currentCardId?.let { id -> seen += id }
            h.answer()
            h.awaitWaitingForRating()
            h.rate()
        }
        val expected = (1..cardCount).map { TestCards.numbered(it).id }
        assertEquals("cards must be presented in server order, exactly once", expected, seen)
    }
}
