package com.studyagent.client.audio

import com.studyagent.client.core.audio.AudioRouteSnapshot
import com.studyagent.client.core.audio.DefaultStudyAudioModeResolver
import com.studyagent.client.core.audio.StudyAudioMode
import com.studyagent.client.core.audio.StudyAudioPreferences
import com.studyagent.client.core.audio.StudyAudioRouteCoordinator
import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.StudyCard
import com.studyagent.client.core.study.SessionMachineState
import com.studyagent.client.core.study.SessionPhase
import com.studyagent.client.core.study.StudyEffect
import com.studyagent.client.core.study.StudyEvent
import com.studyagent.client.core.study.StudyReducer
import com.studyagent.client.core.audio.EffectiveStudyAudioMode
import com.studyagent.client.core.voice.StudyVoiceTurnGate
import com.studyagent.client.core.voice.VoiceTurnDecision
import com.studyagent.client.core.voice.tts.AcousticGapPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The acceptance tests for Headset-Free Operation (§90-§105), assembled at the layers that can
 * prove them without a device:
 *
 *  - the reducer owns turn legality (a spoken "good" may never rate from feedback audio),
 *  - the turn gate owns half-duplex (the microphone may never overlap the speaker),
 *  - the coordinator owns route history (Phone Mode never fabricates a headset loss).
 */
class PhoneModeLoopTest {

    private val resolver = DefaultStudyAudioModeResolver()

    private fun phoneRoute() = resolver.resolve(StudyAudioMode.AUTO, AudioRouteSnapshot.PHONE_ONLY)

    private fun card(id: String = "c1") = StudyCard(id, "Q for $id", 1, 5)

    private fun evaluation() = Evaluation(shortFeedback = "Good", suggestedRating = Rating.GOOD, confidence = 90.0)

    private fun started(): SessionMachineState {
        var s = StudyReducer.reduce(
            SessionMachineState(epoch = 1L, phase = SessionPhase.Idle),
            StudyEvent.UserStartRequested("Toronto Notes", "m-start"),
            0L
        ).newState
        s = StudyReducer.reduce(s, StudyEvent.ServerSessionStarted("s1", "Toronto Notes", 10, "srv1"), 10L).newState
        return s
    }

    private fun withQuestion(state: SessionMachineState, cardId: String, turnId: String): SessionMachineState =
        StudyReducer.reduce(
            state,
            StudyEvent.ServerQuestionReceived(null, cardId, "Q for $cardId", 1, 9, true, UUID.randomUUID().toString(), turnId, 1L),
            100L
        ).newState

    /** The invariant that keeps Phone Mode from hearing itself: never speak and listen together. */
    private fun assertHalfDuplex(effects: List<StudyEffect>, where: String) {
        val speaks = effects.any { it is StudyEffect.Voice.Speak }
        val listens = effects.any { it is StudyEffect.Voice.StartRecognition }
        assertFalse("TTS and STT emitted in the same transition ($where)", speaks && listens)
    }

    @Test
    fun `a full phone turn never overlaps speaker and microphone`() {
        // §90/§101: no headphones anywhere in this test.
        var s = started()
        s = withQuestion(s, "c1", "turn-1")
        assertTrue("question must be spoken", s.phase is SessionPhase.SpeakingQuestion)

        var t = StudyReducer.reduce(s, StudyEvent.QuestionSpeechCompleted("c1", s.activeSpeechEffectId ?: "e-q", true), 200L)
        assertHalfDuplex(t.effects, "question speech completed")
        assertTrue(t.effects.any { it is StudyEffect.Voice.StartRecognition })
        assertFalse("the microphone waits for the question to finish", t.effects.any { it is StudyEffect.Voice.Speak })
        s = t.newState
        assertEquals(SessionPhase.WaitingForAnswer, s.phase)

        t = StudyReducer.reduce(s, StudyEvent.UserSubmitAnswer("c1", "subdural hematoma"), 300L)
        assertHalfDuplex(t.effects, "answer submitted")
        s = t.newState
        assertEquals(SessionPhase.SubmittingAnswer, s.phase)

        t = StudyReducer.reduce(s, StudyEvent.ServerEvaluationReceived("s1", "c1", evaluation(), true, "e1"), 400L)
        assertHalfDuplex(t.effects, "feedback received")
        s = t.newState
        assertTrue(s.phase is SessionPhase.SpeakingFeedback)

        t = StudyReducer.reduce(s, StudyEvent.FeedbackSpeechCompleted("c1", s.activeSpeechEffectId ?: "e-f", true), 500L)
        assertHalfDuplex(t.effects, "feedback speech completed")
        s = t.newState
        assertEquals(SessionPhase.WaitingForRating, s.phase)

        t = StudyReducer.reduce(s, StudyEvent.UserRateCard(Rating.GOOD, "c1"), 600L)
        assertHalfDuplex(t.effects, "rating submitted")
        assertTrue(t.newState.phase is SessionPhase.SubmittingRating)
    }

    @Test
    fun `the word good inside spoken feedback cannot rate the card`() {
        // §52/§92: the feedback text itself contains "Good". A recognition result that leaks
        // back during that window must be rejected — only an explicit rating may reschedule.
        var s = started()
        s = withQuestion(s, "c1", "turn-1")
        s = StudyReducer.reduce(s, StudyEvent.QuestionSpeechCompleted("c1", s.activeSpeechEffectId ?: "e", true), 200L).newState
        s = StudyReducer.reduce(s, StudyEvent.UserSubmitAnswer("c1", "answer"), 300L).newState
        s = StudyReducer.reduce(s, StudyEvent.ServerEvaluationReceived("s1", "c1", evaluation(), true, "e1"), 400L).newState
        assertTrue(s.phase is SessionPhase.SpeakingFeedback)

        val leaked = StudyReducer.reduce(s, StudyEvent.RecognitionCompleted("c1", s.cardTurn?.turnId, "good", false), 410L)

        assertFalse("feedback audio must never be interpreted as a rating", leaked.accepted)
        assertFalse(
            "no rating may leave the device from a leaked transcript",
            leaked.effects.any { it is StudyEffect.Network.Send }
        )
        assertEquals(SessionPhase.SpeakingFeedback, leaked.newState.phase)
    }

    @Test
    fun `a spoken rating in the rating window still works`() {
        // The other half of the previous test: hands-free rating must remain possible.
        var s = started()
        s = withQuestion(s, "c1", "turn-1")
        s = StudyReducer.reduce(s, StudyEvent.QuestionSpeechCompleted("c1", s.activeSpeechEffectId ?: "e", true), 200L).newState
        s = StudyReducer.reduce(s, StudyEvent.UserSubmitAnswer("c1", "answer"), 300L).newState
        s = StudyReducer.reduce(s, StudyEvent.ServerEvaluationReceived("s1", "c1", evaluation(), true, "e1"), 400L).newState
        s = StudyReducer.reduce(s, StudyEvent.FeedbackSpeechCompleted("c1", s.activeSpeechEffectId ?: "f", true), 500L).newState
        assertEquals(SessionPhase.WaitingForRating, s.phase)

        val rated = StudyReducer.reduce(s, StudyEvent.UserRateCard(Rating.GOOD, "c1"), 700L)

        assertTrue(rated.accepted)
        assertTrue(rated.newState.phase is SessionPhase.SubmittingRating)
    }

    @Test
    fun `one hundred phone cards produce no headset loss events and no recovery loops`() = runTest {
        // §93/§95: a phone-only session must never look like a headset session that lost its
        // headset, no matter how many cards are reviewed. Device churn is emitted too — real
        // phones re-enumerate audio devices constantly.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val snapshots = MutableStateFlow(AudioRouteSnapshot.PHONE_ONLY)
        val coordinator = StudyAudioRouteCoordinator(
            snapshots = snapshots,
            preferences = MutableStateFlow(StudyAudioPreferences(mode = StudyAudioMode.AUTO)),
            scope = scope
        )
        val events = mutableListOf<Any>()
        scope.launch { coordinator.events.collect { events += it } }

        var state = started()
        repeat(100) { index ->
            snapshots.value = AudioRouteSnapshot.PHONE_ONLY.copy(revision = index.toLong())

            state = withQuestion(state, "c$index", "turn-$index")
            state = StudyReducer.reduce(state, StudyEvent.QuestionSpeechCompleted("c$index", state.activeSpeechEffectId ?: "e", true), 1_000L + index).newState
            state = StudyReducer.reduce(state, StudyEvent.UserSubmitAnswer("c$index", "answer"), 2_000L + index).newState
            state = StudyReducer.reduce(state, StudyEvent.ServerEvaluationReceived("s1", "c$index", evaluation(), true, "e$index"), 3_000L + index).newState
            state = StudyReducer.reduce(state, StudyEvent.FeedbackSpeechCompleted("c$index", state.activeSpeechEffectId ?: "f", true), 4_000L + index).newState
            state = StudyReducer.reduce(state, StudyEvent.UserRateCard(Rating.GOOD, "c$index"), 5_000L + index).newState
            state = StudyReducer.reduce(state, StudyEvent.ServerRatingSaved("s1", "c$index", Rating.GOOD, "2d", "r$index"), 6_000L + index).newState

            // Turn boundaries are where a deferred switch would be applied.
            coordinator.onSafeTurnBoundary()
        }

        assertTrue("no route events at all for a phone-only session: $events", events.isEmpty())
        assertEquals(0, coordinator.metrics.metrics.value.headsetLossEvents)
        assertEquals(0, coordinator.metrics.metrics.value.blockedStarts)
        assertNull(coordinator.pendingRoute.value)
        assertEquals(EffectiveStudyAudioMode.PHONE, coordinator.effectiveRoute.value.effective)
    }

    @Test
    fun `one thousand phone-mode listen turns stay bounded and correctly gapped`() = runTest {
        // §105: no accumulation of jobs, callbacks or STT requests across a long session. The
        // gate is stateless, so 1000 turns must produce 1000 identical, bounded decisions.
        val gaps = mutableListOf<Long>()
        val gate = StudyVoiceTurnGate(
            routeProvider = { phoneRoute() },
            configuredGapMs = { 350 },
            speechActive = { false },
            queueDepth = { 0 },
            delayFn = { gaps += it }
        )

        var ready = 0
        repeat(1000) {
            val decision = gate.awaitListenWindow { true }
            if (decision is VoiceTurnDecision.Ready) {
                assertEquals(AcousticGapPolicy.PHONE_SPEAKER_FLOOR_MS, decision.gapMs)
                ready++
            }
        }

        assertEquals(1000, ready)
        assertEquals(1000, gaps.size)
        assertTrue(gaps.all { it == AcousticGapPolicy.PHONE_SPEAKER_FLOOR_MS.toLong() })
    }

    @Test
    fun `a headset connecting during phone speech only switches at the boundary`() = runTest {
        // §94/§95: plugging in headphones mid-question must not change the output device under
        // a live utterance.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val snapshots = MutableStateFlow(AudioRouteSnapshot.PHONE_ONLY)
        val coordinator = StudyAudioRouteCoordinator(
            snapshots = snapshots,
            preferences = MutableStateFlow(StudyAudioPreferences()),
            scope = scope
        )

        snapshots.value = AudioRouteSnapshot.WIRED_HEADSET
        assertEquals("a new headset must not cut a live turn", EffectiveStudyAudioMode.PHONE, coordinator.effectiveRoute.value.effective)

        coordinator.onSafeTurnBoundary()
        assertEquals(EffectiveStudyAudioMode.HEADSET, coordinator.effectiveRoute.value.effective)
    }
}
