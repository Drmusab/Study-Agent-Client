package com.studyagent.client.study

import com.studyagent.client.core.study.SessionConnectionStatus
import com.studyagent.client.core.study.SessionPhase
import com.studyagent.client.testutil.StudySessionHarness
import com.studyagent.client.testutil.newHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dropped connection in every phase the session can be in (§35-§41/§96).
 *
 * The reconnect path is the one place where two authorities meet: the client believes one thing
 * (a rating is in flight), the server believes another (the rating never arrived). The contract
 * this suite pins down:
 *
 * - losing the connection **freezes the voice pipeline immediately** — nobody keeps talking, and
 *   no microphone stays open into a session that cannot submit anything;
 * - the client asks the server for **authoritative state** instead of guessing;
 * - reconnection **never duplicates intent** — an answer or rating already sent is not sent again,
 *   and nothing is invented;
 * - the state the user returns to is a **legal resume state**, not `Recovering` forever;
 * - a reconnection storm does not accumulate pending actions or timers.
 */
class ReconnectAtEveryPhaseTest {

    /**
     * Drops the connection, checks the frozen-voice contract, then restores it and checks that the
     * client asks for authoritative state without resending anything.
     */
    private fun disconnectAndReconnect(h: StudySessionHarness) {
        val answersBefore = h.sentAnswers().size
        val ratingsBefore = h.sentRatings().size

        h.connection.loseConnection()
        h.advance(500)

        assertTrue(
            "a lost connection must be visible, was ${h.currentPhase}",
            h.currentPhase is SessionPhase.Recovering
        )
        assertFalse("nothing may keep speaking while disconnected", h.speech.isActivelySpeaking)
        assertFalse("no microphone may stay open while disconnected", h.recognition.hasActiveTurn)

        h.connection.restoreConnection()
        h.advance(1_000)

        assertTrue(
            "the client must ask the server for authoritative state",
            h.connection.sentOfType("request_session_status").isNotEmpty()
        )
        assertEquals("reconnecting must not resubmit an answer", answersBefore, h.sentAnswers().size)
        assertEquals("reconnecting must not resubmit a rating", ratingsBefore, h.sentRatings().size)
        h.assertInvariants("reconnect")
    }

    /**
     * Every phase the session may legitimately be in after the server's snapshot has been applied.
     *
     * The client does not get to choose: whichever state the server reports, the machine maps it.
     * What must never happen is landing somewhere that cannot progress — `Recovering`, `Idle` or a
     * speaking phase with no speech behind it.
     */
    private fun assertLegalResumeState(h: StudySessionHarness, description: String) {
        val phase = h.currentPhase
        val legal = phase is SessionPhase.WaitingForFirstCard ||
            phase is SessionPhase.SpeakingQuestion ||
            phase is SessionPhase.WaitingForAnswer ||
            phase is SessionPhase.SubmittingAnswer ||
            phase is SessionPhase.WaitingForEvaluation ||
            phase is SessionPhase.SpeakingFeedback ||
            phase is SessionPhase.WaitingForRating ||
            phase is SessionPhase.SubmittingRating ||
            phase is SessionPhase.Paused ||
            phase is SessionPhase.Finished
        assertTrue("$description left the session in ${SessionPhase.serverPhaseName(phase)}", legal)
        assertEquals(
            "the connection status must be honest after reconnecting",
            SessionConnectionStatus.CONNECTED,
            h.machineState.value.connection
        )
    }

    @Test
    fun `reconnecting while waiting for an answer returns to a legal state`() = runTest {
        val h = newHarness()
        h.startSession()
        h.awaitWaitingForAnswer()

        disconnectAndReconnect(h)

        assertLegalResumeState(h, "reconnect during the answer window")
    }

    @Test
    fun `reconnecting while the answer is in flight does not resend it`() = runTest {
        val h = newHarness()
        h.startSession()
        h.awaitWaitingForAnswer()

        // The agent goes quiet: the answer is written to the socket but never scored.
        h.server.holdReplies = true
        h.answer()
        assertEquals(SessionPhase.SubmittingAnswer, h.currentPhase)

        // The socket drops and the app comes back; from here on the agent answers again.
        h.server.holdReplies = false
        disconnectAndReconnect(h)

        assertEquals("the answer must exist exactly once on the wire", 1, h.sentAnswers().size)
        assertLegalResumeState(h, "reconnect during an in-flight answer")
    }

    @Test
    fun `reconnecting while waiting for an evaluation keeps the turn identity`() = runTest {
        val h = newHarness()
        h.startSession()
        h.awaitWaitingForAnswer()
        val turnId = h.lastTurnId()
        val cardId = h.currentCardId
        h.server.holdReplies = true
        h.answer()
        h.advance(2_000)

        h.server.holdReplies = false
        disconnectAndReconnect(h)
        h.advance(2_000)

        assertEquals("the answer must still exist exactly once", 1, h.sentAnswers().size)
        assertEquals(
            "the server reports the same card, so the client must keep the same turn",
            turnId,
            h.lastTurnId()
        )
        assertEquals("and the same card", cardId, h.currentCardId)
        assertLegalResumeState(h, "reconnect during an evaluation")
    }

    @Test
    fun `reconnecting while waiting for a rating opens a rating window again`() = runTest {
        val h = newHarness()
        h.startSession()
        h.awaitWaitingForAnswer()
        h.answer()
        h.awaitWaitingForRating()

        disconnectAndReconnect(h)

        assertEquals("no rating may be invented by reconnecting", 0, h.sentRatings().size)
        assertLegalResumeState(h, "reconnect during the rating window")
    }

    @Test
    fun `reconnecting while the rating is in flight does not rate again`() = runTest {
        val h = newHarness()
        h.startSession()
        h.awaitWaitingForAnswer()
        h.answer()
        h.awaitWaitingForRating()

        // The acknowledgement is lost while the client is in SubmittingRating.
        h.server.holdReplies = true
        h.rate()
        assertEquals(SessionPhase.SubmittingRating, h.currentPhase)

        h.server.holdReplies = false
        disconnectAndReconnect(h)
        h.advance(2_000)

        assertEquals("the rating must exist exactly once on the wire", 1, h.sentRatings().size)
        assertTrue(
            "the machine must not stay in SubmittingRating after recovery, was ${h.currentPhase}",
            h.currentPhase !is SessionPhase.SubmittingRating
        )
        assertLegalResumeState(h, "reconnect during a rating")
    }

    @Test
    fun `reconnecting while paused keeps the session paused`() = runTest {
        val h = newHarness()
        h.startSession()
        h.awaitWaitingForAnswer()
        h.pause()
        assertTrue(h.awaitPhaseIs(SessionPhase.Paused))
        val windowsBefore = h.recognition.started.size

        disconnectAndReconnect(h)
        h.advance(2_000)

        assertEquals("a paused session must not resume itself", SessionPhase.Paused, h.currentPhase)
        assertFalse("a paused session must not hold a microphone", h.recognition.hasActiveTurn)
        assertEquals("and must not open a new listening window", windowsBefore, h.recognition.started.size)
        assertEquals("and must not be rated or answered", 0, h.sentAnswers().size + h.sentRatings().size)
    }

    @Test
    fun `a reconnect storm does not accumulate pending actions or timers`() = runTest {
        val h = newHarness()
        h.startSession()
        h.awaitWaitingForAnswer()

        repeat(5) {
            h.connection.loseConnection()
            h.advance(200)
            h.connection.restoreConnection()
            h.advance(400)
        }

        // Each reconnect legitimately asks for state; what must not happen is a request per
        // *retry*, or timers that nobody ever cleans up.
        val statusRequests = h.connection.sentOfType("request_session_status").size
        assertTrue("no more than one state request per reconnect (was $statusRequests)", statusRequests <= 5)

        // Let every watchdog that was armed during the storm resolve.
        h.advance(15_000)

        val resources = h.resources()
        assertEquals(
            "watchdogs armed during the storm must all have been released: ${resources.render()}",
            0,
            resources.pendingTimers
        )
        assertNull("no pending action may be left behind", h.machineState.value.pendingAction)
        assertEquals("nothing may have been submitted", 0, h.sentAnswers().size)
        assertLegalResumeState(h, "reconnect storm")
        h.assertInvariants("reconnect storm")
    }

    @Test
    fun `a session stays usable after a long disconnection`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        h.startSession()
        h.awaitWaitingForAnswer()
        val cardId = h.currentCardId

        // The phone loses the network for two minutes of virtual time — a train tunnel.
        h.connection.setNetworkUnavailable()
        h.advance(120_000)
        assertFalse("nothing may keep talking to nobody", h.speech.isActivelySpeaking)
        assertFalse("and no microphone may stay open", h.recognition.hasActiveTurn)

        h.connection.restoreConnection()
        h.advance(5_000)

        assertTrue(
            "the session must be studiable again, was ${h.currentPhase}",
            h.awaitWaitingForAnswer(maxSteps = 25)
        )
        assertEquals("the server's card must be the one in front of the user", cardId, h.currentCardId)
        h.speakAnswer()
        assertTrue("and answering it must work", h.awaitWaitingForRating(maxSteps = 25))
        assertEquals("exactly one answer, after all that", 1, h.sentAnswers().size)
        h.assertInvariants("long disconnection")
    }
}
