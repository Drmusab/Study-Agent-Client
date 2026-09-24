package com.studyagent.client.study

import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.SessionPhase
import com.studyagent.client.testutil.newHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repeated user intent is not repeated work (§24-§26).
 *
 * Every one of these cases happens in the real app: a double tap on the rating buttons, a spoken
 * "good" that arrives while the finger is already on the button, an impatient tap on *Pause*, a
 * *Skip* that is pressed twice because nothing appeared to happen, a voice command repeated by a
 * user who is not sure it was heard. The client's contract is that the *server* sees one intent.
 *
 * "Exactly once" here means: one wire message per accepted intent, the message keeps the same
 * identity if it is retried, and a duplicate never advances the session.
 */
class SessionIdempotencyTest {

    @Test
    fun `rating twice sends one rating`() = runTest {
        val h = newHarness()
        h.startSession()
        h.awaitWaitingForAnswer()
        h.answer()
        h.awaitWaitingForRating()

        h.rate(Rating.GOOD)
        h.rate(Rating.GOOD)
        h.rate(Rating.EASY)
        h.advance(1_000)

        assertEquals("the second and third taps must be ignored", 1, h.sentRatings().size)
        assertEquals(Rating.GOOD, (h.sentRatings().single() as com.studyagent.client.core.models.ClientMessage.RateCard).rating)
        h.assertInvariants("double rating")
    }

    @Test
    fun `answering twice sends one answer`() = runTest {
        val h = newHarness()
        h.startSession()
        h.awaitWaitingForAnswer()

        h.answer("first transcript")
        h.answer("second transcript")
        h.advance(500)

        assertEquals("the second submission belongs to no open turn", 1, h.sentAnswers().size)
        assertEquals(
            "the first answer is the one that counts",
            "first transcript",
            (h.sentAnswers().single() as com.studyagent.client.core.models.ClientMessage.SubmitAnswer).text
        )
        h.assertInvariants("double answer")
    }

    @Test
    fun `pausing twice sends one pause and resuming twice sends one resume`() = runTest {
        val h = newHarness()
        h.startSession()
        h.awaitWaitingForAnswer()

        h.pause()
        h.pause()
        h.advance(500)
        assertEquals("a second pause intent while pausing must be ignored", 1, h.connection.sentOfType("pause_session").size)
        assertTrue(h.awaitPhaseIs(SessionPhase.Paused))

        h.resume()
        h.resume()
        h.advance(500)
        assertEquals("a second resume intent while resuming must be ignored", 1, h.connection.sentOfType("resume_session").size)
        h.assertInvariants("double pause/resume")
    }

    @Test
    fun `ending twice sends one end`() = runTest {
        val h = newHarness()
        h.startSession()
        h.awaitWaitingForAnswer()

        h.end()
        h.end()
        h.advance(500)

        assertEquals("the session may only be ended once", 1, h.connection.sentOfType("end_session").size)
        assertTrue(h.awaitPhaseIs(SessionPhase.Finished))
        h.assertInvariants("double end")
    }

    @Test
    fun `skipping twice asks the server once per press, but never answers the card`() = runTest {
        val h = newHarness()
        h.startSession()
        h.awaitWaitingForAnswer()

        h.skip()
        h.skip()
        h.advance(2_000)

        assertTrue("skip must reach the server", h.connection.sentOfType("skip_card").isNotEmpty())
        assertEquals("a skipped card must not be answered", 0, h.sentAnswers().size)
        assertEquals("a skipped card must not be rated", 0, h.sentRatings().size)
        h.assertInvariants("double skip")
    }

    @Test
    fun `a repeated start request cannot open a second session`() = runTest {
        val h = newHarness()
        h.startSession()
        h.awaitWaitingForAnswer()

        // The user taps Start again, or the notification action fires twice.
        h.startSession()
        h.advance(1_000)

        assertEquals("one start_session per session", 1, h.sentStarts().size)
        assertEquals("the session id must not change", "s1", h.machineState.value.session?.sessionId)
        h.assertInvariants("double start")
    }

    @Test
    fun `a rating ack for a different rating is not accepted`() = runTest {
        val h = newHarness()
        h.startSession()
        h.awaitWaitingForAnswer()
        h.answer()
        h.awaitWaitingForRating()

        h.rate(Rating.GOOD)
        // The server acknowledges a *different* rating for the same card: a buggy/rewound agent.
        h.connection.deliver(
            com.studyagent.client.core.models.ServerMessage.RatingSaved(
                sessionId = "s1",
                cardId = requireNotNull(h.currentCardId),
                rating = Rating.EASY,
                messageId = "wrong-ack-1"
            )
        )
        h.advance(500)

        assertFalse(
            "an acknowledgement for another rating must not advance the session",
            h.currentPhase is SessionPhase.WaitingForFirstCard
        )
        h.assertInvariants("mismatched ack")
    }
}
