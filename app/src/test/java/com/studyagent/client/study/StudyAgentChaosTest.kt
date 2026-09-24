package com.studyagent.client.study

import com.studyagent.client.core.audio.AudioRouteSnapshot
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.SessionPhase
import com.studyagent.client.testutil.FakeSpeechOrchestrator
import com.studyagent.client.testutil.StudySessionHarness
import com.studyagent.client.testutil.TestRoutes
import com.studyagent.client.testutil.TestScroll
import com.studyagent.client.testutil.TestTranscripts
import com.studyagent.client.testutil.newHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Machine-level chaos with deterministic seeds (§18/§147/§148).
 *
 * The STT layer already has its own chaos suite (`SttReliabilityChaosTest`) which proves the
 * recognizer survives a hostile backend. This suite attacks the layer above: the *whole session*,
 * where the failure modes are interactions — a pause that lands mid-utterance, a reconnect that
 * returns a question the client already answered, a retransmitted evaluation, a headset that
 * disappears while the microphone is open, a rating whose acknowledgement never arrives.
 *
 * Two rules make the results usable:
 *
 * 1. **Seeded and deterministic.** Every run is `Random(seed)`; a failure can be replayed by
 *    running the same seed again with no other context, and the report prints it (§148).
 * 2. **Invariants after every single event.** The point is not "the session eventually recovers"
 *    but "no intermediate state is illegal": TTS and STT never overlap, a finished session never
 *    becomes active again, the card the UI shows is the card the machine owns, at most one answer
 *    and one rating are in flight, a paused session holds no microphone, and an old generation
 *    never mutates a new one (§25).
 */
class StudyAgentChaosTest {

    /** What one chaos step does. Ordinary study actions are included so the session progresses. */
    private enum class Chaos {
        ADVANCE,
        ANSWER,
        RATE,
        PAUSE,
        RESUME,
        SKIP,
        HINT,
        REPEAT,
        STOP_SPEAKING,
        PTT_START,
        PTT_STOP,
        TTS_COMPLETES,
        TTS_FAILS,
        STT_RESULT,
        STT_NO_SPEECH,
        STT_FAILURE,
        STT_LATE_CALLBACK,
        ROUTE_LOST,
        ROUTE_RESTORED,
        CONNECTION_LOST,
        CONNECTION_RESTORED,
        DUPLICATE_QUESTION,
        DUPLICATE_EVALUATION,
        RATING_ACK,
        TIMEOUT
    }

    @Test
    fun `seeded chaos never breaks a session invariant`() = runTest {
        val seeds = chaosSeeds()
        for (seed in seeds) {
            runOneChaosSession(seed)
        }
    }

    /**
     * Replays one seed end to end. Any violation is reported with the seed, the event history,
     * the machine state, the turn id and the session epoch — everything needed to reproduce it
     * without a debugger (§172).
     */
    private suspend fun kotlinx.coroutines.test.TestScope.runOneChaosSession(seed: Long) {
        val random = Random(seed)
        val h = newHarness(
            route = TestRoutes.bluetooth,
            autoAnswer = false,
            speakDurationMs = 200L,
            timelineCapacity = 1_000,
            serverDeckSize = 50
        )
        h.speech.mode = FakeSpeechOrchestrator.Mode.PARK

        val steps = 30 + random.nextInt(20)
        val scroll = TestScroll()
        try {
            h.startSession(deck = "Chaos::Seed$seed")

            repeat(steps) { step ->
                val action = Chaos.entries[random.nextInt(Chaos.entries.size)]
                scroll.add("seed=$seed step=$step $action")

                applyChaos(h, action, random)
                h.advance(300)

                val problems = h.checkInvariants("seed=$seed step=$step $action")
                if (problems.isNotEmpty()) {
                    throw AssertionError(h.report(problems + scroll.lines(), seed))
                }
                assertFalse(
                    "TTS/STT overlap after $action\n" + h.report(scroll.lines(), seed),
                    h.overlapDetected
                )
            }

            // The session must still be describable: not lost in an undefined phase.
            val phase = h.currentPhase
            assertTrue(
                "session ended in an impossible phase: ${SessionPhase.serverPhaseName(phase)}\n" +
                    h.report(scroll.lines(), seed),
                phase is SessionPhase.Finished || phase.isActive || phase.isPaused ||
                    phase is SessionPhase.Error || phase is SessionPhase.Idle
            )
            assertEquals("no internal invariant violation", 0L, h.machineInvariantViolations())
        } catch (failure: Throwable) {
            if (failure is AssertionError && failure.message?.contains("seed=") == true) throw failure
            throw AssertionError(
                "chaos seed $seed failed after ${scroll.size} steps: ${failure.message}\n" +
                    h.report(emptyList(), seed),
                failure
            )
        }
    }

    // ------------------------------------------------------------------ the chaos itself

    private suspend fun applyChaos(h: StudySessionHarness, action: Chaos, random: Random) {
        // Every once in a while the engine is allowed to be slow, so the *controllable* TTS states
        // are exercised as well as the fast path. An utterance that is already parked is also
        // completed now and then — a real engine always terminates eventually.
        h.speech.mode = if (random.nextInt(5) == 0) FakeSpeechOrchestrator.Mode.PARK else FakeSpeechOrchestrator.Mode.COMPLETE
        if (h.speech.pendingCount > 0 && random.nextBoolean()) h.speech.completeNext()

        when (action) {
            Chaos.ADVANCE -> h.advance(1_000L + random.nextInt(5_000))

            Chaos.ANSWER -> {
                if (h.currentPhase is SessionPhase.WaitingForAnswer) h.answer(TestTranscripts.MEDICAL_ANSWER)
            }

            Chaos.RATE -> {
                if (h.currentPhase is SessionPhase.WaitingForRating) {
                    h.rate(Rating.entries[random.nextInt(Rating.entries.size)])
                }
            }

            // "Pause and resume repeatedly": the user's own chaos, and the one that races the
            // most: a pause during speech, a resume during the pause request.
            Chaos.PAUSE -> h.pause()
            Chaos.RESUME -> h.resume()
            Chaos.SKIP -> if (h.currentPhase.isActive) h.skip()
            Chaos.HINT -> if (h.currentPhase.isActive) h.hint()
            Chaos.REPEAT -> if (h.currentPhase.isActive) h.repeatQuestion()
            Chaos.STOP_SPEAKING -> if (h.currentPhase.isActive) h.stopSpeaking()

            Chaos.PTT_START -> if (h.currentPhase.isActive) h.pressToTalk()
            Chaos.PTT_STOP -> h.releasePushToTalk()

            Chaos.TTS_COMPLETES -> repeat(1 + random.nextInt(2)) { h.speech.completeNext() }
            Chaos.TTS_FAILS -> h.speech.failNext()

            Chaos.STT_RESULT -> if (h.recognition.hasActiveTurn) {
                h.recognition.deliverAnswer(
                    text = if (random.nextBoolean()) TestTranscripts.MEDICAL_ANSWER else TestTranscripts.CLINICAL_ANSWER,
                    cardId = h.currentCardId
                )
            }

            Chaos.STT_NO_SPEECH -> h.recognition.deliverNoSpeech()
            Chaos.STT_FAILURE -> h.recognition.deliverFailure()

            // A callback from a turn that is already dead: the platform does this routinely.
            Chaos.STT_LATE_CALLBACK ->
                h.recognition.deliverStale("ghost-${random.nextInt(10_000)}", TestTranscripts.MEDICAL_ANSWER)

            // The headset leaves mid-turn: speech must stop before anything else happens, and the
            // pending answer must not be submitted from a route that no longer exists.
            Chaos.ROUTE_LOST -> {
                val answersBefore = h.sentAnswers().size
                h.routeManager.setRoute(TestRoutes.phone)
                h.routeSnapshots.value = TestRoutes.phone
                h.advance(700)
                assertEquals(
                    "a lost route must never submit the answer that was in progress",
                    answersBefore,
                    h.sentAnswers().size
                )
            }

            Chaos.ROUTE_RESTORED -> {
                val target = if (random.nextBoolean()) TestRoutes.bluetooth else TestRoutes.wired
                h.routeManager.setRoute(target)
                h.routeSnapshots.value = target
            }

            Chaos.CONNECTION_LOST -> h.connection.loseConnection()
            Chaos.CONNECTION_RESTORED -> {
                h.connection.restoreConnection()
                h.advance(500)
            }

            Chaos.DUPLICATE_QUESTION -> h.server.resendCurrentQuestion()
            Chaos.DUPLICATE_EVALUATION -> h.server.lastEvaluation?.let { h.connection.deliver(it) }
            Chaos.RATING_ACK -> h.server.resendLastRatingSaved()

            // Push past an in-flight watchdog so timeout recovery is exercised, not skipped.
            Chaos.TIMEOUT -> h.advance(20_000L + random.nextInt(20_000))
        }
    }

    /**
     * Seeds 100–199 by default: enough variety to cover the state space hundreds of times over
     * (each run applies 30–50 random actions), while still fast in a PR gate.
     *
     * `-Dstudyagent.chaos.full=true` widens the sweep to 100–1000 for a nightly run. The seeds
     * themselves never change, so a failure found nightly is reproducible in a PR.
     */
    private fun chaosSeeds(): List<Long> =
        if (System.getProperty("studyagent.chaos.full") == "true") {
            (100L..1_000L).toList()
        } else {
            (100L..199L).toList()
        }

    @Test
    fun `a chaos failure report contains the seed and the event history`() = runTest {
        // The report is the debugging interface of this suite: if it is not good enough to
        // reproduce a failure from the artifact alone, the suite is not worth running (§172).
        val h = newHarness(serverDeckSize = 5)
        h.startSession()
        h.advance(600)
        val report = h.report(listOf("nothing actually failed"), seed = 4242L)
        assertTrue(report.contains("seed: 4242"))
        assertTrue(report.contains("epoch:"))
        assertTrue(report.contains("phase:"))
        assertTrue(report.contains("cardTurn:"))
        assertTrue(report.contains("resources:"))
        assertTrue(report.contains("transitions:"))
        assertTrue(report.contains("QUESTION_RECEIVED"))
        assertTrue(report.contains("nothing actually failed"))
    }

    @Test
    fun `a dropped rating acknowledgement times out and leaves a retryable state`() = runTest {
        val h = newHarness(serverDeckSize = 5)
        h.server.dropEveryNthReply = 0 // explicit: the acknowledgement is sent
        h.startSession()
        h.awaitWaitingForAnswer()
        h.answer()
        h.awaitWaitingForRating()

        // Now drop the acknowledgement of the rating itself and let the watchdog fire.
        h.server.dropEveryNthReply = 1
        h.rate()
        h.advance(20_000)

        assertEquals(
            "a missing rating ack must fall back to a state the user can retry from",
            SessionPhase.WaitingForRating,
            h.currentPhase
        )
        assertEquals(
            "the client must not resubmit a rating on its own; a retry is the user's decision",
            1,
            h.sentRatings().size
        )
        assertEquals(
            "the timeout must be recorded as a recoverable problem, not swallowed",
            "RATING_TIMEOUT",
            h.machineState.value.error?.problem?.name
        )

        // The user retries now that the server is answering again: exactly one more rating, and
        // the session moves on.
        h.server.dropEveryNthReply = 0
        h.rate()
        h.advance(5_000)
        assertEquals("the retry is a second, user-initiated submission", 2, h.sentRatings().size)
        assertTrue(
            "after a successful retry the session must leave the rating window, was ${h.currentPhase}",
            h.currentPhase !is SessionPhase.WaitingForRating && h.currentPhase !is SessionPhase.SubmittingRating
        )
        h.assertInvariants("dropped ack")
    }

    @Test
    fun `losing the connection mid-turn freezes the voice pipeline`() = runTest {
        val h = newHarness(serverDeckSize = 5)
        h.startSession()
        h.awaitWaitingForAnswer()
        assertEquals(1, h.recognition.startedPurposes().count { it.name == "ANSWER" })

        h.connection.loseConnection()
        h.advance(1_000)

        assertTrue("a lost connection must be visible to the user", h.currentPhase is SessionPhase.Recovering)
        assertFalse("no microphone may stay open while the session is frozen", h.recognition.hasActiveTurn)
        assertFalse("nothing may keep talking into a dead session", h.speech.isActivelySpeaking)

        h.connection.restoreConnection(host = "localhost", port = 8000)
        h.advance(1_000)
        assertTrue(
            "the client must ask the server for authoritative state after reconnecting",
            h.connection.sentOfType("request_session_status").isNotEmpty()
        )
        assertTrue(
            "the client must report itself as connected again",
            h.connection.currentStateLabel().startsWith("Connected")
        )
        h.assertInvariants("reconnect")
    }
}
