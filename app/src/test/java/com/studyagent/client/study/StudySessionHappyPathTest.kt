package com.studyagent.client.study

import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.SessionPhase
import com.studyagent.client.testutil.TestTranscripts
import com.studyagent.client.testutil.newHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mandatory end-to-end acceptance test (§14/§15/§16).
 *
 * One card, driven through the real machine, the real reducer, the real turn gate and the real
 * repository, with only the voice engines, the transport and the PC agent replaced by doubles.
 *
 * The claim it proves, in the words the requirements use:
 *
 * > a happy-path session produces **exactly 1 answer, 1 rating, 1 card transition, 0 stale
 * > callbacks and 0 TTS/STT overlap**.
 *
 * Everything asserted below is an outcome a user or a server could observe — no private field is
 * inspected to make the test pass.
 */
class StudySessionHappyPathTest {

    @Test
    fun `a full card turn sends exactly one answer and one rating and advances one card`() = runTest {
        val h = newHarness()
        h.startSession()

        // The question was spoken and the microphone opened for the answer — in that order.
        assertTrue("a question must be spoken first", h.speech.spokenPurposes().any { it.name == "QUESTION" })
        assertTrue("the session must reach the answer window", h.awaitWaitingForAnswer())
        assertEquals(1, h.sentStarts().size)

        val answerTurnId = h.recognition.started.first().id
        val answeredCard = h.playCard()
        assertEquals("card-1", answeredCard)

        // ---- 1 answer, 1 rating -------------------------------------------------
        assertEquals("exactly one answer may be submitted", 1, h.sentAnswers().size)
        assertEquals("exactly one rating may be submitted", 1, h.sentRatings().size)
        assertEquals(
            "the answer must carry the card that was asked",
            "card-1",
            (h.sentAnswers().single() as ClientMessage.SubmitAnswer).cardId
        )
        assertEquals(
            "the rating the user chose must be the rating the server receives",
            Rating.GOOD,
            (h.sentRatings().single() as ClientMessage.RateCard).rating
        )

        // ---- 1 card transition --------------------------------------------------
        assertEquals("the server asked exactly one follow-up question", 2, h.server.cardIndex)
        assertEquals("the machine advanced exactly one turn", 2L, h.machineState.value.cardGeneration)

        // ---- 0 TTS/STT overlap, measured on the executed boundary ---------------
        assertFalse("the microphone must never open while the app is talking", h.overlapDetected)
        assertFalse("the voice timeline must be clean", h.speech.isActivelySpeaking)
        assertEquals(
            "one question + one feedback utterance",
            listOf("QUESTION", "FEEDBACK"),
            h.speech.spokenPurposes().map { it.name }
        )
        assertEquals(
            "one answer window + one rating window",
            listOf("ANSWER", "RATING"),
            h.recognition.startedPurposes().map { it.name }
        )

        // ---- 0 stale callbacks --------------------------------------------------
        // A platform recognizer routinely calls back after the app cancelled a turn. The client's
        // contract is that such a result changes nothing: no second answer, no second rating, and
        // no new microphone window that the user did not ask for.
        h.recognition.deliverStale(answerTurnId, "this arrived too late")
        assertEquals("the suite must actually have injected a stale callback", 1, h.recognition.injectedStaleCallbacks)
        h.advance(1_000)
        assertEquals("a stale callback must not produce a second answer", 1, h.sentAnswers().size)
        assertEquals("a stale callback must not produce a second rating", 1, h.sentRatings().size)
        assertEquals(
            "a stale callback must not drag the session back to the card that was already rated",
            "card-2",
            h.currentCardId
        )

        // ---- the machine's own invariants --------------------------------------
        h.assertInvariants("after one happy card")
        assertEquals(0L, h.machineInvariantViolations())
    }

    @Test
    fun `the executed event order is question, speech, silence, microphone, answer, feedback, rating`() = runTest {
        val h = newHarness()
        h.startSession()
        h.playCard()

        val names = h.timelineEventNames()
        val order = listOf(
            "SESSION_START_REQUESTED",
            "START_SESSION_SENT",
            "SESSION_STARTED",
            "QUESTION_RECEIVED",
            "TTS_START",
            "TTS_DONE",
            "STT_READY",
            "STT_FINAL",
            "ANSWER_SENT",
            "EVALUATION_RECEIVED",
            "RATING_SENT",
            "RATING_SAVED",
            "QUESTION_RECEIVED"
        )
        var cursor = 0
        for (expected in order) {
            cursor = names.indexOf(expected, cursor)
            assertTrue(
                "event '$expected' is missing or out of order.\nactual=${names.joinToString()}",
                cursor >= 0
            )
            cursor++
        }
    }

    @Test
    fun `a duplicated question frame cannot speak or answer twice`() = runTest {
        val h = newHarness()
        h.server.duplicateQuestions = 1
        h.startSession()

        h.awaitWaitingForAnswer()
        assertEquals("a retransmitted question must not be spoken twice", 1, h.speech.spokenPurposes().count { it.name == "QUESTION" })
        assertEquals("the same card must not open two answer windows", 1, h.recognition.startedPurposes().count { it.name == "ANSWER" })

        h.answer()
        h.awaitWaitingForRating()
        assertEquals(1, h.sentAnswers().size)
        h.assertInvariants("duplicate question")
    }

    @Test
    fun `a duplicated evaluation cannot replay the feedback`() = runTest {
        val h = newHarness()
        h.server.duplicateEvaluations = 1
        h.startSession()
        h.awaitWaitingForAnswer()
        h.answer()
        h.awaitWaitingForRating()

        assertEquals(
            "feedback must be spoken exactly once even if the server repeats it",
            1,
            h.speech.spokenPurposes().count { it.name == "FEEDBACK" }
        )
        assertEquals(
            "the rating window must open exactly once",
            1,
            h.recognition.startedPurposes().count { it.name == "RATING" }
        )
        h.assertInvariants("duplicate evaluation")
    }

    @Test
    fun `a paused session never opens the microphone`() = runTest {
        val h = newHarness()
        h.startSession()
        h.awaitWaitingForAnswer()

        val windowsBefore = h.recognition.started.size
        h.pause()
        assertTrue("the pause must reach the server", h.connection.sentOfType("pause_session").isNotEmpty())
        assertTrue("the server confirms the pause", h.awaitPhaseIs(SessionPhase.Paused))
        assertFalse("a paused session must not hold an open microphone", h.recognition.hasActiveTurn)

        h.advance(30_000)
        assertEquals("no listening window may open while paused", windowsBefore, h.recognition.started.size)
        assertEquals(SessionPhase.Paused, h.currentPhase)
        h.assertInvariants("paused")
    }

    @Test
    fun `finishing a session leaves no in-flight resources behind`() = runTest {
        val h = newHarness()
        h.startSession()
        h.awaitWaitingForAnswer()
        h.end()
        assertTrue("the server confirms the end", h.awaitPhaseIs(SessionPhase.Finished))

        h.advance(20_000)
        val resources = h.resources()
        assertTrue(
            "a finished session must be quiescent, but was: ${resources.render()}",
            resources.quiescent
        )
        assertEquals("no timer may outlive the session", 0, resources.pendingTimers)
        assertEquals(0, resources.activeSpeechEffects)
        assertEquals(0, resources.activeRecognitionEffects)
        assertFalse(h.speech.isActivelySpeaking)
        assertFalse(h.recognition.hasActiveTurn)
        h.assertInvariants("finished")
    }

    @Test
    fun `an answer typed while the app is still asking is rejected, not queued`() = runTest {
        val h = newHarness()
        h.speech.speakDurationMs = 10_000L
        h.startSession()

        // The question is still being spoken: there is no listening window yet.
        assertTrue(h.currentPhase is SessionPhase.SpeakingQuestion || h.currentPhase is SessionPhase.Starting)
        val sentBefore = h.sentAnswers().size
        h.answer(TestTranscripts.CLINICAL_ANSWER)
        assertEquals("an answer outside the answer window must not be sent", sentBefore, h.sentAnswers().size)
        h.assertInvariants("early answer")
    }
}
