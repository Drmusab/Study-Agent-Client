package com.studyagent.client.study

import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.study.SessionPhase
import com.studyagent.client.testutil.newHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reconnection and process-level reconstruction (§38-§42/§95/§96).
 *
 * A study session spends most of its life in a pocket with the screen off, so "the app was killed
 * and came back" is not an exotic path — it is the normal one. The requirements this suite pins
 * down are:
 *
 * - the **server is authoritative** for session, card and progression;
 * - the client never *invents* progress to look consistent: no answer, no rating, no card that the
 *   server did not report;
 * - a **terminal** session is terminal — a late status frame cannot revive it;
 * - voice is torn down and rebuilt deliberately, never left half-open across a reconnect;
 * - a session that was mid-question when the app died can still be studied afterwards, without the
 *   user having to press push-to-talk to get unstuck.
 */
class SessionReconstructionTest {

    // ------------------------------------------------------------------ server authority

    @Test
    fun `the server snapshot wins over stale local state`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())

        // The server says this card is awaiting a *rating*, e.g. because the answer was submitted
        // from another device. The client must follow, not argue.
        h.connection.deliver(
            ServerMessage.SessionStatus(
                sessionId = "s1",
                currentCardId = "card-1",
                awaiting = "rating",
                isPaused = false,
                isFinished = false
            )
        )
        h.advance(2_000)

        assertEquals(
            "the server's view of the turn must win",
            SessionPhase.WaitingForRating,
            h.currentPhase
        )
        assertEquals("reconciliation must not invent an answer", 0, h.sentAnswers().size)
        h.assertInvariants("server says rating")
    }

    @Test
    fun `a reconnect while the user is answering does not wedge the session`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())
        val questionsBefore = h.speech.spokenPurposes().count { it.name == "QUESTION" }

        // The socket drops while the microphone window is open, and the app comes back.
        h.connection.loseConnection()
        h.advance(500)
        assertTrue("the drop must be visible", h.currentPhase is SessionPhase.Recovering)
        h.connection.restoreConnection()
        h.advance(500)

        // The server still says "awaiting answer". The client has no way to know whether the user
        // actually heard the question, so it re-issues it and goes through the normal
        // speak → silence → listen path again. What it must NOT do is sit in a speaking phase
        // forever (which is exactly what happened before this behaviour existed: the phase could
        // only be left by a speech-completion callback that no speech effect had been started for).
        assertTrue(
            "the question must be re-issued after reconnect",
            h.speech.spokenPurposes().count { it.name == "QUESTION" } >= questionsBefore + 1
        )
        assertTrue(
            "the session must reach a usable answer window on its own, without push-to-talk",
            h.awaitWaitingForAnswer(maxSteps = 20)
        )
        assertTrue("the client must not talk to a dead session", h.connection.sentOfType("submit_answer").isEmpty())
        h.assertInvariants("reconnect during answer")
    }

    @Test
    fun `voice is torn down while disconnected and rebuilt after`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())
        assertTrue("the microphone window must be open before the drop", h.recognition.hasActiveTurn)

        h.connection.loseConnection()
        h.advance(2_000)

        assertFalse("nothing may keep talking into a dead session", h.speech.isActivelySpeaking)
        assertFalse("the microphone must be released when the session freezes", h.recognition.hasActiveTurn)

        h.connection.restoreConnection()
        h.advance(5_000)
        assertFalse("half-duplex must survive a reconnect", h.overlapDetected)
        h.assertInvariants("voice torn down")
    }

    // ------------------------------------------------------------------ terminal phases

    @Test
    fun `a stale snapshot cannot revive a finished session`() = runTest {
        val h = newHarness(serverDeckSize = 3)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())
        h.end()
        assertTrue(h.awaitPhaseIs(SessionPhase.Finished))
        val epochAtFinish = h.epoch
        val answersAtFinish = h.sentAnswers().size
        val ratingsAtFinish = h.sentRatings().size

        // The classic race: a `request_session_status` that was sent *before* the finish is
        // answered *after* it, and its `awaiting` field still describes the old turn. Two
        // independent rules keep the session terminal — the reducer rejects any event once the
        // phase is `Finished`, and reconciliation refuses to undo a terminal intent — and the
        // assertion is on the behaviour, not on which of the two caught it.
        h.connection.deliver(
            ServerMessage.SessionStatus(
                sessionId = "s1",
                currentCardId = "card-1",
                awaiting = "answer",
                isPaused = false,
                isFinished = false
            )
        )
        h.advance(10_000)

        assertEquals("a finished session stays finished", SessionPhase.Finished, h.currentPhase)
        assertEquals("and it stays in the same epoch", epochAtFinish, h.epoch)
        assertEquals("no answer may be resurrected", answersAtFinish, h.sentAnswers().size)
        assertEquals("no rating may be resurrected", ratingsAtFinish, h.sentRatings().size)
        assertFalse("no microphone may be opened for a finished session", h.recognition.hasActiveTurn)
        assertFalse("no speech may be started for a finished session", h.speech.isActivelySpeaking)
        h.assertInvariants("stale snapshot after finish")
    }

    @Test
    fun `ending a session cannot be undone by a stale status frame`() = runTest {
        val h = newHarness(serverDeckSize = 5)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())
        val utterancesBefore = h.speech.started.size
        val windowsBefore = h.recognition.started.size

        // The agent stops answering, so the end request is *in flight* rather than confirmed. This
        // is the state a flaky connection leaves a user in, and unlike `Finished` nothing else
        // guards it.
        h.server.holdReplies = true
        h.end()
        assertEquals("the end request must be in flight", SessionPhase.Finishing, h.currentPhase)

        // A status reply that was already on its way still describes the card as awaiting an answer.
        h.connection.deliver(
            ServerMessage.SessionStatus(
                sessionId = "s1",
                currentCardId = "card-1",
                awaiting = "answer",
                isPaused = false,
                isFinished = false
            )
        )
        h.advance(2_000)

        assertEquals(
            "a stale status frame must not cancel the user's end",
            SessionPhase.Finishing,
            h.currentPhase
        )
        assertEquals("and must not re-issue the question", utterancesBefore, h.speech.started.size)
        assertEquals("nor reopen the microphone", windowsBefore, h.recognition.started.size)

        // The user is not held hostage by a silent agent: when the end watchdog expires the
        // session ends locally and *says* that it was never confirmed (§82). What must not happen
        // is the stale frame above taking the session back to a running card.
        h.advance(10_000)
        assertTrue(
            "the end must complete even without a server confirmation, was ${h.currentPhase}",
            h.awaitPhaseIs(SessionPhase.Finished, maxSteps = 15)
        )
        assertTrue(
            "the client must be honest that the end was local-only",
            h.machineState.value.finishingIsLocalOnly
        )
        h.assertInvariants("stale status while finishing")
    }

    @Test
    fun `a paused session stays paused across a reconnect`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())
        h.pause()
        assertTrue("the server confirms the pause", h.awaitPhaseIs(SessionPhase.Paused))
        val windowsBefore = h.recognition.started.size

        h.connection.loseConnection()
        h.advance(1_000)
        h.connection.restoreConnection()
        h.advance(5_000)

        assertEquals("a reconnect must not resume the user's session", SessionPhase.Paused, h.currentPhase)
        assertEquals("and it must not open a microphone", windowsBefore, h.recognition.started.size)
        h.assertInvariants("paused across reconnect")
    }

    // ------------------------------------------------------------------ generations

    @Test
    fun `callbacks from the previous epoch cannot mutate the new session`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())
        val oldTurnId = h.lastTurnId()
        assertNotNull(oldTurnId)
        h.end()
        assertTrue(h.awaitPhaseIs(SessionPhase.Finished))

        // A brand-new session: a new epoch, a new turn, the same deck.
        h.startSession(deck = "Toronto Notes")
        assertTrue(h.awaitWaitingForAnswer())
        val newTurnId = h.lastTurnId()
        assertNotNull(newTurnId)
        assertEquals("the epoch must advance for a new session", 2L, h.epoch)
        assertFalse("a new session must not reuse the previous turn id", newTurnId == oldTurnId)

        val answersBefore = h.sentAnswers().size
        val cardBefore = h.currentCardId
        // A recognizer that was torn down while the old session ended finally reports.
        h.recognition.deliverStale("ghost-from-epoch-1", "my patient has a subdural hematoma")
        h.advance(2_000)

        assertEquals("a ghost transcript must not become an answer", answersBefore, h.sentAnswers().size)
        assertEquals("nor move the session to another card", cardBefore, h.currentCardId)
        assertEquals("the new session must still be the second epoch", 2L, h.epoch)
        h.assertInvariants("cross-epoch ghost")
    }

    @Test
    fun `a session that was interrupted mid card can still be completed afterwards`() = runTest {
        val h = newHarness(serverDeckSize = 3)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())
        val firstCard = h.currentCardId

        // Drop, come back, and study the interrupted card for real: the whole point of
        // reconciliation is that the user's next action works (§41).
        h.connection.loseConnection()
        h.advance(1_000)
        h.connection.restoreConnection()
        h.advance(2_000)
        assertTrue(h.awaitWaitingForAnswer(maxSteps = 20))

        assertNotNull("the interrupted card must still be answerable", firstCard)
        assertEquals("the card must not be swapped by the reconnect", firstCard, h.currentCardId)
        h.speakAnswer()
        assertTrue("the answer must reach the server", h.awaitWaitingForRating(maxSteps = 20))
        h.rate(Rating.GOOD)
        h.advance(5_000)

        assertEquals("exactly one answer for the interrupted card", 1, h.sentAnswers().size)
        assertEquals("exactly one rating for the interrupted card", 1, h.sentRatings().size)
        assertEquals(
            "the answer must carry the interrupted card's id",
            firstCard,
            (h.sentAnswers().single() as ClientMessage.SubmitAnswer).cardId
        )
        assertTrue("the session must move on to the next card", h.server.cardIndex >= 2)
        h.assertInvariants("interrupted card completed")
    }
}
