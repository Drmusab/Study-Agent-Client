package com.studyagent.client.study

import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.study.SessionPhase
import com.studyagent.client.testutil.TestTranscripts
import com.studyagent.client.testutil.newHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transport-level chaos against the real session machine (§27/§28/§93/§97/§147).
 *
 * The other chaos suite randomizes *user* actions; this one randomizes the *wire* while the user
 * behaves normally. That split matters, because the failure modes are different: user chaos finds
 * illegal state transitions, network chaos finds exactly-once violations — a duplicate card, a
 * rating submitted twice, a question answered that the server never asked, a lost acknowledgement
 * that silently ends the session, a reordered frame that is mistaken for the next one.
 *
 * Every scenario below asserts a *contract*, not an implementation:
 *
 * - a failed send never becomes a submitted answer;
 * - the client never invents progress the server did not report;
 * - a lost acknowledgement leaves a state the user can retry from, and the client does not retry
 *   on its own;
 * - latency and reordering change nothing observable;
 * - frames that are out of context (a `pong` in the middle of a turn, an unsupported-protocol
 *   error) are inert.
 */
class NetworkChaosTest {

    @Test
    fun `a failed send never becomes a submitted answer`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())
        val serverAnswersBefore = h.server.answersReceived

        // The socket is up but writes fail (a dead peer that has not been detected yet).
        h.connection.failAllSends = true
        h.speakAnswer()
        h.advance(1_000)

        assertEquals(
            "the server must not have received an answer that the transport refused",
            serverAnswersBefore,
            h.server.answersReceived
        )
        assertTrue(
            "a send failure must be visible in the diagnostics",
            h.timelineEventNames().contains("SEND_FAILED")
        )
        // A refused write is known immediately, so the machine must return to a retryable state
        // immediately — not sit in "submitting" for the 30-second watchdog while the user stares
        // at a spinner.
        assertEquals(
            "a refused send must roll back to the answer window at once, was ${h.currentPhase}",
            SessionPhase.WaitingForAnswer,
            h.currentPhase
        )
        assertEquals("the client must not resubmit an answer on its own", 0, h.server.answersReceived)

        // The user retries once the network is back — through push-to-talk, the path that is
        // always available when the automatic window is no longer open. The window opens behind
        // the route's acoustic gap (~560 ms on the phone speaker), so advance past it.
        h.connection.failAllSends = false
        h.pressToTalk()
        h.advance(1_000)
        h.speakAnswer()
        assertTrue(
            "the retry must reach the server",
            h.awaitWaitingForRating(maxSteps = 25)
        )
        assertEquals("exactly one answer reached the agent", 1, h.server.answersReceived)
        h.assertInvariants("failed send")
    }

    @Test
    fun `reordered replies cannot be mistaken for the next turn`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        // The scripted agent emits a `pong` before the evaluation it was asked for.
        h.server.reorderReplies = true
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())
        val cardId = h.currentCardId

        h.speakAnswer()
        assertTrue("the evaluation must still arrive exactly once", h.awaitWaitingForRating(maxSteps = 20))

        assertEquals("exactly one answer", 1, h.sentAnswers().size)
        assertEquals("exactly one feedback utterance", 1, h.speech.spokenPurposes().count { it.name == "FEEDBACK" })
        assertEquals("the same card is still the active turn", cardId, h.currentCardId)
        assertEquals("exactly one rating window", 1, h.recognition.startedPurposes().count { it.name == "RATING" })
        h.assertInvariants("reordered replies")
    }

    @Test
    fun `a duplicated rating acknowledgement cannot advance the deck twice`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        h.server.duplicateRatingAcks = 1
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())
        h.speakAnswer()
        assertTrue(h.awaitWaitingForRating(maxSteps = 20))

        h.rate(Rating.GOOD)
        h.advance(2_000)

        assertEquals("the rating is submitted once", 1, h.sentRatings().size)
        assertEquals(
            "a duplicated acknowledgement must not advance the deck twice",
            2,
            h.server.cardIndex
        )
        h.assertInvariants("duplicate rating ack")
    }

    @Test
    fun `a lossy reply stream never invents progress`() = runTest {
        val h = newHarness(serverDeckSize = 40)
        // Every third reply from the agent is lost. Some turns will therefore time out and be
        // retried; the invariant is that nothing is ever *invented* by the client.
        h.server.dropEveryNthReply = 3
        h.startSession()

        repeat(12) { h.playCard(rating = if (it % 2 == 0) Rating.GOOD else Rating.HARD) }
        h.advance(30_000)

        val answers = h.sentAnswers().map { it as ClientMessage.SubmitAnswer }
        val ratings = h.sentRatings().map { it as ClientMessage.RateCard }

        assertTrue(
            "every answer must carry a card the client was actually asked about",
            answers.all { it.cardId.startsWith("card-") }
        )
        assertTrue(
            "a rating may only exist for a card the server acknowledged an answer for",
            ratings.all { rating -> h.server.answeredCards.contains(rating.cardId) }
        )
        assertTrue(
            "the client can never have rated more cards than the server received answers for",
            ratings.map { it.cardId }.distinct().size <= h.server.answersReceived
        )
        assertFalse("half-duplex must hold through message loss", h.overlapDetected)
        assertEquals("no internal invariant violation", 0L, h.machineInvariantViolations())
        h.assertInvariants("lossy stream")
    }

    @Test
    fun `a slow transport keeps ordering and half duplex`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        // 250ms of write latency and 200ms of reply latency: the client sees a peer that is
        // consistently slow, which is the normal case on mobile networks.
        h.connection.sendDelayMs = 250L
        h.server.replyDelayMs = 200L
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer(maxSteps = 30))

        h.speakAnswer()
        assertTrue(h.awaitWaitingForRating(maxSteps = 30))

        val wireOrder = h.connection.sentOfType("submit_answer").size to h.connection.sentOfType("rate_card").size
        assertEquals("answer first, nothing else yet", 1 to 0, wireOrder)

        h.rate(Rating.GOOD)
        h.advance(5_000)

        assertEquals("the rating follows the answer", 1, h.sentRatings().size)
        val sent = h.connection.sentMessages.map { it.type }
        assertTrue(
            "the answer must be written before the rating (was: $sent)",
            sent.indexOf("submit_answer") < sent.indexOf("rate_card")
        )
        assertFalse("latency must not break half-duplex", h.overlapDetected)
        h.assertInvariants("slow transport")
    }

    @Test
    fun `frames that are out of context change nothing`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())
        val phaseBefore = h.currentPhase
        val cardBefore = h.currentCardId

        // A `pong` in the middle of a card turn: not a study event at all.
        h.connection.deliver(ServerMessage.Pong())
        // A protocol error the client does not support: it must be recorded as a rejection and
        // must not break the session (an English "unsupported protocol" string is not a reason to
        // lose the user's study session).
        h.connection.deliver(
            ServerMessage.ErrorMessage(
                code = "UNSUPPORTED_PROTOCOL",
                message = "This agent only speaks protocol v3"
            )
        )
        h.advance(2_000)

        assertEquals("an inert frame must not move the session", phaseBefore, h.currentPhase)
        assertEquals("nor change the card", cardBefore, h.currentCardId)
        assertEquals("nor submit anything", 0, h.sentAnswers().size)
        assertTrue(
            "the agent's error must be visible to the user",
            h.machineState.value.error != null || h.timelineEventNames().contains("EVENT_REJECTED")
        )

        // And the session is still usable afterwards.
        h.speakAnswer()
        assertTrue("the session must still be able to progress", h.awaitWaitingForRating(maxSteps = 25))
        h.assertInvariants("out-of-context frames")
    }

    @Test
    fun `a reconnect during an in-flight answer keeps exactly one answer on the wire`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())

        // The server stops answering (it is busy), then the socket drops.
        h.server.holdReplies = true
        h.speakAnswer()
        h.advance(500)
        assertEquals(1, h.sentAnswers().size)

        h.connection.loseConnection()
        h.advance(1_000)
        h.connection.restoreConnection()
        h.advance(5_000)

        assertEquals(
            "reconnecting must not duplicate the answer that was already submitted",
            1,
            h.sentAnswers().size
        )
        assertTrue(
            "the machine must never be stuck in an in-flight answer phase after recovery, was ${h.currentPhase}",
            h.currentPhase !is SessionPhase.SubmittingAnswer
        )
        h.assertInvariants("in-flight reconnect")
    }

    @Test
    fun `a duplicate card frame after the answer is ignored`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())
        val cardId = h.currentCardId

        h.speakAnswer()
        assertTrue(h.awaitWaitingForRating(maxSteps = 20))

        // A retransmitted question for the card the user just answered. Acting on it would reopen
        // an answer window for a card whose answer is already being scored.
        h.server.resendCurrentQuestion()
        h.advance(2_000)

        assertEquals("the card must not change", cardId, h.currentCardId)
        assertEquals("no second answer window", 1, h.recognition.startedPurposes().count { it.name == "ANSWER" })
        assertEquals("the rating window is still the one open", SessionPhase.WaitingForRating, h.currentPhase)
        h.assertInvariants("duplicate question after answer")
    }

    @Test
    fun `a malformed medical answer is still just an answer`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())

        // Hostile content — quotes, newlines, markup, a JSON-looking payload and a very long
        // sentence — must be submitted verbatim and must not corrupt the protocol, the diagnostics
        // or the timeline (the answer is *never* logged, only its length).
        val hostile = buildString {
            append("\"}],\"answer\":\"injected\",{\"a\":1}\n")
            append("<script>alert('x')</script> ")
            append("\u0000\u0007 ")
            append("x".repeat(4_000))
        }
        h.speakAnswer(text = hostile)
        assertTrue(h.awaitWaitingForRating(maxSteps = 25))

        val submitted = h.sentAnswers().single() as ClientMessage.SubmitAnswer
        assertEquals("the transcript must be submitted verbatim", hostile, submitted.text)
        assertEquals(
            "the timeline must record the shape, not the content",
            hostile.length.toString(),
            h.eventsOf("STT_FINAL").last().metadata["chars"]
        )
        val rendered = h.timelineEvents().joinToString("\n") { it.render() }
        assertFalse("a transcript must never reach the timeline", rendered.contains("script"))
        h.assertInvariants("hostile transcript")
    }

    @Test
    fun `a question belonging to another session cannot hijack this one`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())
        val activeCard = h.currentCardId
        val turnIdBefore = h.lastTurnId()
        val questionsBefore = h.speech.spokenPurposes().count { it.name == "QUESTION" }

        // Two study sessions on one device (or one agent serving two clients): a frame that
        // belongs to the *other* session must never be allowed to take over this one.
        h.connection.deliver(
            ServerMessage.Question(
                sessionId = "some-other-session",
                cardId = "card-from-elsewhere",
                question = "A question from another session",
                cardNumber = 999,
                remaining = 0,
                speak = true,
                reviewTurnId = "other-turn"
            )
        )
        h.advance(3_000)

        assertEquals(
            "a frame from another session must not change the active card",
            activeCard,
            h.currentCardId
        )
        assertEquals("nor the active turn", turnIdBefore, h.lastTurnId())
        assertEquals(
            "nor cause the question to be spoken again",
            questionsBefore,
            h.speech.spokenPurposes().count { it.name == "QUESTION" }
        )
        assertEquals("nor submit anything", 0, h.sentAnswers().size)
        assertTrue(
            "the rejection must be visible in the diagnostics",
            h.timelineEventNames().contains("EVENT_REJECTED")
        )
        h.assertInvariants("foreign session frame")
    }

    @Test
    fun `a failed start leaves no half-open session`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        h.connection.failAllSends = true

        h.startSession()
        h.advance(30_000)

        assertTrue(
            "a start that never left the device must not look like a running session, was ${h.currentPhase}",
            h.currentPhase is SessionPhase.Error || h.currentPhase is SessionPhase.Idle ||
                h.currentPhase is SessionPhase.Starting || h.currentPhase is SessionPhase.Finished
        )
        assertEquals("no session may exist on the server", 0, h.server.answersReceived)
        assertFalse("nothing may be listening for an answer", h.recognition.hasActiveTurn)

        // Recovering from a failed start must be possible: the user tries again once the network
        // is back.
        h.connection.failAllSends = false
        h.startSession(deck = "Toronto Notes")
        h.advance(2_000)
        assertTrue(
            "a second start after the network returns must actually reach the agent",
            h.server.receivedMessages.any { it is ClientMessage.StartSession }
        )
        assertTrue(
            "and it must reach an answer window",
            h.awaitWaitingForAnswer(maxSteps = 25)
        )
        h.assertInvariants("failed start")
    }

    @Test
    fun `an answer that the server never asks about is never rated`() = runTest {
        val h = newHarness(serverDeckSize = 40)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())

        // Answer, then immediately rate without waiting for the evaluation: the user is quick (or
        // the network is slow). The rating must be refused, because the absent score is exactly
        // what the scheduler would need — accepting it would rate the *previous* turn's feedback.
        h.speakAnswer()
        h.rate(Rating.EASY)
        assertEquals("a rating before the evaluation must be rejected outright", 0, h.sentRatings().size)

        h.advance(10_000)
        assertTrue("the evaluation must still arrive and open the rating window",
            h.currentPhase is SessionPhase.WaitingForRating || h.server.cardIndex >= 2)
        assertEquals(
            "the rejected early rating must not be replayed later",
            0,
            h.sentRatings().size
        )
        assertEquals("the answer itself must have been submitted exactly once", 1, h.sentAnswers().size)
        assertFalse("half-duplex must hold", h.overlapDetected)
        assertEquals(0L, h.machineInvariantViolations())
        h.assertInvariants("rate before evaluation")
    }

    @Test
    fun `a socket that drops every send mid-session cannot corrupt the ledger`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())
        h.speakAnswer()
        assertTrue(h.awaitWaitingForRating(maxSteps = 20))

        // From here on, nothing gets out.
        h.connection.failAllSends = true
        h.rate(Rating.GOOD)
        h.advance(2_000)
        h.rate(Rating.AGAIN)
        h.advance(2_000)

        val ledger = h.machineState.value.ledger
        val inFlightRatings = ledger.entries.values.count {
            it.ratingState == com.studyagent.client.core.study.SubmissionLedger.SubmissionState.IN_FLIGHT
        }
        assertTrue("no more than one rating may be in flight", inFlightRatings <= 1)
        assertTrue(
            "each turn may hold at most one rating submission",
            h.sentRatings().map { it as ClientMessage.RateCard }.map { it.cardId }.distinct().size <= 1
        )
        h.assertInvariants("dead socket mid session")
    }

    @Test
    fun `a reconnect storm does not multiply requests`() = runTest {
        val h = newHarness(serverDeckSize = 20)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())

        repeat(8) {
            h.connection.loseConnection()
            h.advance(300)
            h.connection.restoreConnection()
            h.advance(300)
        }
        h.advance(10_000)

        val statusRequests = h.connection.sentOfType("request_session_status").size
        assertTrue("each reconnect asks for state, but not more than once per reconnect", statusRequests in 1..8)
        assertEquals("no answer may be invented by reconnecting", 0, h.sentAnswers().size)
        assertEquals("no rating may be invented by reconnecting", 0, h.sentRatings().size)
        assertFalse("reconnects must not leave speech running", h.speech.isActivelySpeaking)
        assertTrue(
            "the session must be usable afterwards, was ${h.currentPhase}",
            h.awaitWaitingForAnswer(maxSteps = 25)
        )
        h.assertInvariants("reconnect storm")
    }

    @Test
    fun `chatty duplicate frames do not wake the voice pipeline`() = runTest {
        val h = newHarness(serverDeckSize = 10)
        h.startSession()
        assertTrue(h.awaitWaitingForAnswer())
        val windowsBefore = h.recognition.started.size
        val utterancesBefore = h.speech.started.size

        // 200 frames that carry no new information: pongs, out-of-session statistics, duplicates.
        repeat(50) {
            h.connection.deliver(ServerMessage.Pong())
            h.connection.deliver(ServerMessage.SessionStats(cardsStudied = 3, recallRate = 0.8, remainingDue = 7))
            h.connection.deliver(
                ServerMessage.SessionProgress(currentCardIndex = 3, totalCards = 10)
            )
            h.server.resendCurrentQuestion()
        }
        h.advance(5_000)

        assertTrue(
            "an inert frame flood must not open microphone turns " +
                "(was ${h.recognition.started.size}, before $windowsBefore)",
            h.recognition.started.size <= windowsBefore + 1
        )
        assertTrue(
            "and must not start new utterances (was ${h.speech.started.size}, before $utterancesBefore)",
            h.speech.started.size <= utterancesBefore + 1
        )
        assertEquals("the flood must not submit anything", 0, h.sentAnswers().size)
        assertTrue(
            "the log buffer must stay bounded through the flood",
            com.studyagent.client.core.common.AppLogger.size <= com.studyagent.client.core.common.AppLogger.MAX_LOG_ENTRIES
        )
        h.assertInvariants("frame flood")
    }
}
