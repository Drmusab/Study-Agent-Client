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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The long-session endurance run (§137/§140).
 *
 * A thousand cards with a thousand spoken questions, a thousand spoken evaluations and two
 * thousand listening windows is a multi-hour session for a human — the kind of session where
 * every "almost bounded" structure eventually matters. It runs here in virtual time, so the suite
 * costs seconds instead of hours, and every resource claim is *measured* rather than assumed.
 *
 * This suite deliberately does **not** assert wall-clock performance numbers as a budget: it only
 * guards that the run stays bounded (a session that needed a real clock to advance would show up
 * as a timeout in CI, which is the honest signal, §168).
 */
class StudySessionEnduranceTest {

    private val deckSize = TestCards.ENDURANCE_DECK_SIZE

    @Test
    fun `a thousand cards complete with bounded resources and no leaks`() = runTest {
        val h = newHarness(serverDeckSize = deckSize, autoAnswer = true, timelineCapacity = 2_000)
        val startedAtMs = System.currentTimeMillis()

        h.startSession()
        val turnIds = HashSet<String>(deckSize * 2)

        repeat(deckSize) { index ->
            h.currentCardId?.let { card ->
                h.machineState.value.cardTurn?.turnId?.let { turnIds += it }
                check(card.isNotBlank())
            }
            h.playCard(rating = if (index % 2 == 0) Rating.GOOD else Rating.AGAIN)

            if (index % 100 == 0) {
                val resources = h.resources()
                assertTrue(
                    "resources must stay bounded at card $index: ${resources.render()}",
                    resources.ledgerEntries <= 32 &&
                        resources.recentServerMessageIds <= 200 &&
                        resources.cardTurnHistory <= StudyReducer.CARD_TURN_HISTORY_LIMIT &&
                        resources.transitionHistory <= 200
                )
                h.assertInvariants("endurance card $index")
            }
        }

        // ---- exactly once, a thousand times -------------------------------------
        assertEquals(deckSize, h.sentAnswers().size)
        assertEquals(deckSize, h.sentRatings().size)
        assertEquals(deckSize, h.server.answersReceived)
        assertEquals(deckSize, h.server.ratingsReceived)
        assertEquals(deckSize.toLong(), h.machineState.value.cardGeneration)
        assertEquals("every card turn had its own identity", deckSize, turnIds.size)

        // ---- the voice pipeline was exercised at scale --------------------------
        assertEquals(deckSize, h.speech.spokenPurposes().count { it.name == "QUESTION" })
        assertEquals(deckSize, h.speech.spokenPurposes().count { it.name == "FEEDBACK" })
        assertTrue(
            "responses==requests: every utterance must terminate",
            h.speech.spoken.size + h.speech.pendingCount == deckSize * 2
        )
        assertTrue("no recognition turn may be left behind", !h.recognition.hasActiveTurn)
        assertFalse("half-duplex must hold for a thousand cards", h.overlapDetected)
        assertEquals("no invariant may be violated in a thousand turns", 0L, h.machineInvariantViolations())

        // ---- bounded structures at the end --------------------------------------
        val resources = h.resources()
        assertTrue("ledger bounded", resources.ledgerEntries <= 32)
        assertTrue("dedup window bounded", resources.recentServerMessageIds <= 200)
        assertTrue("turn history bounded", resources.cardTurnHistory <= StudyReducer.CARD_TURN_HISTORY_LIMIT)
        assertTrue("transition history bounded", resources.transitionHistory <= 200)
        assertTrue("timeline bounded", h.timeline.size <= 2_000)
        assertTrue(
            "the log buffer must not hold a session's worth of rows",
            AppLogger.size <= AppLogger.MAX_LOG_ENTRIES
        )

        // ---- metrics describe a session, not a leak -----------------------------
        val snapshot = h.performance.snapshot()
        assertEquals(deckSize.toLong(), snapshot.session.turns)
        assertEquals(deckSize.toLong(), snapshot.session.answersSubmitted)
        assertEquals(deckSize.toLong(), snapshot.session.ratingsSubmitted)
        // Question + feedback per card, all completed: a thousand cards is two thousand turns.
        assertEquals(deckSize * 2L, snapshot.tts.requestsCompleted)
        assertEquals("one completed transcript per answered card", deckSize.toLong(), snapshot.stt.turnsCompleted)
        assertTrue("latency windows stay bounded", snapshot.session.speechDoneToListen.windowSamples <= 128)
        assertTrue("p95 must be a real number", snapshot.session.speechDoneToListen.p95Ms >= 0L)
        assertNotNull("heap must be reportable on the JVM", snapshot.memory.usedHeapBytes)
        assertNotNull("peak heap must be tracked across the session", snapshot.memory.peakUsedHeapBytes)

        // ---- a guard against accidental real waiting, not a benchmark -----------
        val wallClockMs = System.currentTimeMillis() - startedAtMs
        assertTrue(
            "the virtual-time run took ${wallClockMs}ms of real time; " +
                "a simulation that needs minutes of wall clock is a broken simulation",
            wallClockMs < ENDURANCE_WALL_CLOCK_BUDGET_MS
        )
    }

    @Test
    fun `ending a thousand-card session leaves the machine quiescent`() = runTest {
        val h = newHarness(serverDeckSize = deckSize, autoAnswer = true)
        h.startSession()
        repeat(deckSize) { h.playCard() }
        assertTrue(h.awaitPhaseIs(SessionPhase.Finished))

        h.advance(30_000)
        val resources = h.resources()
        assertTrue("nothing may outlive the session: ${resources.render()}", resources.quiescent)
        assertEquals(0, resources.pendingTimers)
        assertEquals(0, resources.eventsAwaitingProcessing)
        assertEquals(0, resources.activeSpeechEffects)
        assertEquals(0, resources.activeRecognitionEffects)
        assertFalse(h.speech.isActivelySpeaking)
        assertFalse(h.recognition.hasActiveTurn)
    }

    @Test
    fun `a storm of late callbacks after a long session changes nothing`() = runTest {
        val h = newHarness(serverDeckSize = 40, autoAnswer = true)
        h.startSession()
        repeat(40) { h.playCard() }

        val answersBefore = h.sentAnswers().size
        val ratingsBefore = h.sentRatings().size
        val requestsBefore = h.recognition.started.size

        // A recognizer that was restarted, a socket that flushed late, a UI lambda that survived
        // a rotation: all of them look like this.
        repeat(200) { index ->
            h.recognition.deliverStale("stale-stt-$index", "late transcript $index")
        }
        h.advance(5_000)

        assertEquals("late transcripts must never submit an answer", answersBefore, h.sentAnswers().size)
        assertEquals("late transcripts must never rate a card", ratingsBefore, h.sentRatings().size)
        assertEquals("late transcripts must not open a microphone", requestsBefore, h.recognition.started.size)
        h.assertInvariants("stale storm")
    }

    private companion object {
        /**
         * Generous on purpose: this is a *guard*, not a budget. It fails only when a test starts
         * depending on real time (sleeps, wall-clock waits), which is exactly the flakiness the
         * testing strategy forbids (§148).
         */
        const val ENDURANCE_WALL_CLOCK_BUDGET_MS = 120_000L
    }
}
