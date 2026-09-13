package com.studyagent.client.study

import com.studyagent.client.core.audio.AudioRouteSnapshot
import com.studyagent.client.core.audio.EffectiveStudyAudioMode
import com.studyagent.client.core.audio.StudyAudioDisconnectPolicy
import com.studyagent.client.core.audio.StudyAudioPreferences
import com.studyagent.client.core.audio.StudyAudioRouteCoordinator
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.core.study.SessionPhase
import com.studyagent.client.core.study.StudyEvent
import com.studyagent.client.core.study.StudySessionMachine
import com.studyagent.client.core.voice.stt.RecognitionBackendEvent
import com.studyagent.client.core.voice.stt.RecognitionBackendKind
import com.studyagent.client.core.voice.stt.RecognitionCapabilities
import com.studyagent.client.core.voice.stt.RecognitionHealthSnapshot
import com.studyagent.client.core.voice.stt.RecognitionHypothesis
import com.studyagent.client.core.voice.stt.RecognitionOutcome
import com.studyagent.client.core.voice.stt.RecognitionPurpose
import com.studyagent.client.core.voice.stt.RecognitionRequest
import com.studyagent.client.core.voice.stt.RecognitionStartResult
import com.studyagent.client.core.voice.stt.RecognitionState
import com.studyagent.client.core.voice.stt.RecognitionTurnResult
import com.studyagent.client.core.voice.stt.SpeechRecognitionOrchestrator
import com.studyagent.client.core.voice.stt.SttSettings
import com.studyagent.client.core.voice.tts.EngineStatus
import com.studyagent.client.core.voice.tts.SegmentLanguage
import com.studyagent.client.core.voice.tts.SpeechOrchestrator
import com.studyagent.client.core.voice.tts.SpeechRequest
import com.studyagent.client.core.voice.tts.SpeechResult
import com.studyagent.client.core.voice.tts.StopReason
import com.studyagent.client.core.voice.tts.TtsEngineInfo
import com.studyagent.client.core.voice.tts.TtsHealthSnapshot
import com.studyagent.client.core.voice.tts.TtsSettings
import com.studyagent.client.core.voice.tts.TtsState
import com.studyagent.client.core.voice.tts.TtsVoiceInfo
import com.studyagent.client.data.repository.ConnectionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone loop at the level the user experiences it (§90-§105).
 *
 * The reducer, gate and coordinator tests prove their own rules; this suite wires them into the
 * real [StudySessionMachine] and asserts the **sequence the phone actually produces**:
 *
 * ```
 *   speak → silence → listen → silence → speak (feedback) → silence → listen (rating)
 * ```
 *
 * with no overlap, a hands-free rating that reaches the same network message as the button, and
 * the two disconnect policies behaving end to end. No Android APIs, no headset anywhere in the
 * first test.
 */
class PhoneModeMachineIntegrationTest {

    // ------------------------------------------------------------------ timeline

    /**
     * Shared record of when the voice pipeline was talking and when it was listening, so the
     * half-duplex invariant can be asserted on the real effect execution order rather than on
     * a single transition.
     */
    private class VoiceTimeline {
        val entries = mutableListOf<String>()
        private var speaking = false
        private var listening = false
        var overlapDetected = false
            private set

        fun speakStarted() {
            if (listening) overlapDetected = true
            speaking = true
            entries += "speak"
        }

        fun speakEnded() {
            speaking = false
            entries += "silence"
        }

        fun listenStarted() {
            if (speaking) overlapDetected = true
            listening = true
            entries += "listen"
        }

        fun listenEnded() {
            listening = false
        }
    }

    // ------------------------------------------------------------------ fakes

    private class FakeConnectionRepository : ConnectionRepository {
        private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connected("localhost", 8000))
        override val connectionState: StateFlow<ConnectionState> = _connectionState

        private val _incoming = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 64)
        override val incomingMessages: Flow<ServerMessage> = _incoming

        override val activeProfile: Flow<ServerProfile?> = MutableStateFlow(null)

        val sent = mutableListOf<ClientMessage>()

        override suspend fun connect(profile: ServerProfile?) {}
        override suspend fun disconnect(reason: String) {}
        override suspend fun send(message: ClientMessage): Boolean {
            sent += message
            return true
        }

        override fun toggleFakeAgent(useFake: Boolean) {}
    }

    private class FakeSpeechOrchestrator(private val timeline: VoiceTimeline) : SpeechOrchestrator {
        private val _health = MutableStateFlow(TtsHealthSnapshot(engineStatus = EngineStatus.READY))
        override val health: StateFlow<TtsHealthSnapshot> = _health

        private val _isSpeaking = MutableStateFlow(false)
        override val isSpeaking: StateFlow<Boolean> = _isSpeaking

        private val _ttsState = MutableStateFlow<TtsState>(TtsState.Ready())
        override val ttsState: StateFlow<TtsState> = _ttsState

        private val _isReady = MutableStateFlow(true)
        override val isReady: StateFlow<Boolean> = _isReady

        val spoken = mutableListOf<SpeechRequest>()
        val stopReasons = mutableListOf<StopReason>()

        override suspend fun speak(request: SpeechRequest): SpeechResult {
            spoken += request
            timeline.speakStarted()
            _isSpeaking.value = true
            // The engine finishes inside the call: by the time the turn gate looks, the room is
            // silent, which is the state Phone Mode must always reach before listening.
            _isSpeaking.value = false
            _health.value = _health.value.copy(speakingPurpose = null, queueDepth = 0)
            timeline.speakEnded()
            return SpeechResult.Completed
        }

        override suspend fun speakPreview(language: SegmentLanguage): SpeechResult = SpeechResult.Completed

        override suspend fun getVoices(languageCode: String): List<TtsVoiceInfo> = emptyList()

        override suspend fun getEngines(): List<TtsEngineInfo> = emptyList()

        override fun stopSpeech(reason: StopReason) {
            stopReasons += reason
            _isSpeaking.value = false
        }

        override fun updateSettings(settings: TtsSettings) {}
        override fun release() {}
    }

    private class FakeRecognitionOrchestrator(private val timeline: VoiceTimeline) : SpeechRecognitionOrchestrator {
        private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
        override val state: StateFlow<RecognitionState> = _state

        private val _isListening = MutableStateFlow(false)
        override val isListening: StateFlow<Boolean> = _isListening

        private val _partialTranscript = MutableStateFlow("")
        override val partialTranscript: StateFlow<String> = _partialTranscript

        private val _audioLevel = MutableStateFlow(0f)
        override val audioLevel: StateFlow<Float> = _audioLevel

        private val _capabilities = MutableStateFlow(RecognitionCapabilities(recognitionAvailable = true))
        override val capabilities: StateFlow<RecognitionCapabilities> = _capabilities

        private val _health = MutableStateFlow(RecognitionHealthSnapshot())
        override val health: StateFlow<RecognitionHealthSnapshot> = _health

        private val _turnResults = MutableSharedFlow<RecognitionTurnResult>(extraBufferCapacity = 16)
        override val turnResults: SharedFlow<RecognitionTurnResult> = _turnResults

        private val _languageEvents = MutableSharedFlow<RecognitionBackendEvent.LanguageDetected>(extraBufferCapacity = 8)
        override val languageEvents: SharedFlow<RecognitionBackendEvent.LanguageDetected> = _languageEvents

        val started = mutableListOf<RecognitionRequest>()
        val cancelled = mutableListOf<String>()
        var finishedTurns = 0

        override fun startRecognition(request: RecognitionRequest): RecognitionStartResult {
            started += request
            timeline.listenStarted()
            _isListening.value = true
            return RecognitionStartResult.Started(request.id, RecognitionBackendKind.SYSTEM)
        }

        override fun finishCurrentTurn() {
            finishedTurns += 1
        }

        override fun cancelCurrentTurn(reason: String) {
            cancelled += reason
            _isListening.value = false
            timeline.listenEnded()
        }

        override fun updateSettings(settings: SttSettings) {}

        override fun refreshCapabilities(): RecognitionCapabilities = _capabilities.value

        override fun requestModelDownload(languageTag: String): Boolean = false

        override fun release() {}

        /** A terminal turn exactly as the backend would deliver it. */
        suspend fun complete(cardId: String, purpose: RecognitionPurpose, text: String, confidence: Float? = null) {
            val hypothesis = RecognitionHypothesis(text = text, confidence = confidence, rank = 0)
            _isListening.value = false
            timeline.listenEnded()
            _turnResults.emit(
                RecognitionTurnResult.Completed(
                    RecognitionOutcome(
                        requestId = started.lastOrNull()?.id ?: "stt",
                        purpose = purpose,
                        cardId = cardId,
                        hypotheses = listOf(hypothesis),
                        selectedText = text,
                        selectedHypothesis = hypothesis
                    )
                )
            )
        }
    }

    private class Fixture(
        val machine: StudySessionMachine,
        val connection: FakeConnectionRepository,
        val speech: FakeSpeechOrchestrator,
        val recognition: FakeRecognitionOrchestrator,
        val coordinator: StudyAudioRouteCoordinator,
        val snapshots: MutableStateFlow<AudioRouteSnapshot>,
        val timeline: VoiceTimeline
    )

    private fun CoroutineScope.fixture(
        snapshot: AudioRouteSnapshot = AudioRouteSnapshot.PHONE_ONLY,
        disconnectPolicy: StudyAudioDisconnectPolicy = StudyAudioDisconnectPolicy.PAUSE_VOICE
    ): Fixture {
        val connection = FakeConnectionRepository()
        val timeline = VoiceTimeline()
        val speech = FakeSpeechOrchestrator(timeline)
        val recognition = FakeRecognitionOrchestrator(timeline)
        val snapshots = MutableStateFlow(snapshot)
        val coordinator = StudyAudioRouteCoordinator(
            snapshots = snapshots,
            preferences = MutableStateFlow(StudyAudioPreferences(disconnectPolicy = disconnectPolicy)),
            scope = this
        )
        val machine = StudySessionMachine(
            connectionRepository = connection,
            speechOrchestrator = speech,
            recognitionOrchestrator = recognition,
            settingsFlow = MutableStateFlow(AppSettings()),
            scope = this,
            clock = { 0L },
            audioRouteCoordinator = coordinator
        )
        return Fixture(machine, connection, speech, recognition, coordinator, snapshots, timeline)
    }

    /**
     * Advances just past the acoustic gap (450 ms on the phone speaker) without reaching any
     * pending-action watchdog (the shortest is 8 s), so a test never trips a timeout it did not
     * intend to exercise.
     */
    private fun TestScope.settle() {
        advanceTimeBy(600)
        runCurrent()
    }

    private fun Fixture.startSession(cardId: String = "c1", turnId: String = "turn-1") {
        machine.dispatch(StudyEvent.ServerSessionStarted("s1", "Toronto Notes", 10, "srv-start"))
        machine.dispatch(
            StudyEvent.ServerQuestionReceived(
                sessionId = "s1",
                cardId = cardId,
                question = "What are the indications for evacuation of an epidural hematoma?",
                cardNumber = 1,
                remaining = 9,
                speak = true,
                messageId = "q-$turnId",
                serverTurnId = turnId,
                serverRevision = 1L
            )
        )
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `a full phone turn speaks, waits, listens and rates with no overlap`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.fixture()
        h.startSession()
        settle()

        // 1. The question was spoken on the phone speaker and the turn moved on to listening —
        //    never the other way round.
        assertEquals(1, h.speech.spoken.size)
        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)

        // 2. Speaking finished, the turn gate waited the phone-speaker gap, and only then did the
        //    microphone open for the answer window.
        assertEquals(1, h.recognition.started.size)
        assertEquals(RecognitionPurpose.ANSWER, h.recognition.started.first().purpose)
        assertEquals("c1", h.recognition.started.first().cardId)
        assertEquals(SessionPhase.WaitingForAnswer, h.machine.machineState.value.phase)

        // 3. The answer is recognized and submitted exactly like a typed one.
        h.recognition.complete("c1", RecognitionPurpose.ANSWER, "Volume over thirty millilitres", confidence = 0.92f)
        settle()
        assertTrue(h.connection.sent.any { it is ClientMessage.SubmitAnswer })
        assertEquals(SessionPhase.SubmittingAnswer, h.machine.machineState.value.phase)

        // 4. Feedback is spoken; the rating window opens only after it finishes.
        h.machine.dispatch(
            StudyEvent.ServerEvaluationReceived(
                sessionId = "s1",
                cardId = "c1",
                evaluation = Evaluation(shortFeedback = "Good — you missed midline shift.", suggestedRating = Rating.GOOD),
                speak = true,
                messageId = "e1"
            )
        )
        settle()

        assertEquals(2, h.speech.spoken.size)
        assertEquals(SessionPhase.WaitingForRating, h.machine.machineState.value.phase)
        assertEquals(2, h.recognition.started.size)
        assertEquals(RecognitionPurpose.RATING, h.recognition.started.last().purpose)

        // 5. A spoken rating reaches the same network message as the on-screen button — the
        //    command grammar, the window mapping and the reducer, end to end.
        h.recognition.complete("c1", RecognitionPurpose.RATING, "good", confidence = 0.95f)
        settle()

        assertTrue("a spoken rating must reach the server", h.connection.sent.any { it is ClientMessage.RateCard })
        assertEquals(SessionPhase.SubmittingRating, h.machine.machineState.value.phase)

        // 6. The invariant, measured on the executed effects rather than on one transition.
        assertFalse("TTS and STT overlapped on the phone speaker", h.timeline.overlapDetected)
        assertEquals(listOf("speak", "silence", "listen"), h.timeline.entries.take(3))
        assertEquals(2, h.timeline.entries.count { it == "speak" })
        assertEquals(2, h.timeline.entries.count { it == "listen" })
    }

    @Test
    fun `the word good inside feedback cannot rate before the app stops talking`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.fixture()
        h.startSession()
        settle()
        h.recognition.complete("c1", RecognitionPurpose.ANSWER, "subdural hematoma", confidence = 0.8f)
        settle()
        h.machine.dispatch(
            StudyEvent.ServerEvaluationReceived(
                sessionId = "s1",
                cardId = "c1",
                evaluation = Evaluation(shortFeedback = "Good.", suggestedRating = Rating.GOOD),
                speak = true,
                messageId = "e1"
            )
        )
        settle()
        // Feedback has been spoken and the rating window is open; a rating only now is legitimate.
        val ratingsBefore = h.connection.sent.count { it is ClientMessage.RateCard }
        assertEquals(0, ratingsBefore)
        assertEquals(SessionPhase.WaitingForRating, h.machine.machineState.value.phase)
        assertFalse(h.connection.sent.any { it is ClientMessage.RateCard })
    }

    @Test
    fun `losing headphones mid-question pauses and offers the phone`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.fixture(snapshot = AudioRouteSnapshot.BLUETOOTH_HEADSET)
        assertEquals(EffectiveStudyAudioMode.HEADSET, h.coordinator.effectiveRoute.value.effective)

        h.startSession()
        settle()
        assertEquals(1, h.speech.spoken.size)

        // The headset disappears while the question is still being spoken.
        h.snapshots.value = AudioRouteSnapshot.PHONE_ONLY
        settle()

        // Speech stops (never continues over the loudspeaker by surprise) and the turn is cancelled.
        assertTrue(h.speech.stopReasons.contains(StopReason.ROUTE_LOST))
        assertTrue(h.recognition.cancelled.contains("input-route-lost"))
        assertNotNull("the user must be offered a choice", h.coordinator.attention.value)
        // Pause was requested from the server rather than silently continuing.
        assertTrue(h.connection.sent.any { it is ClientMessage.PauseSession })

        // The server confirms the pause.
        h.machine.dispatch(StudyEvent.ServerSessionPaused("s1", "p1"))
        settle()
        assertEquals(SessionPhase.Paused, h.machine.machineState.value.phase)

        // "Continue on phone": the route resolves to the phone and the question is repeated once.
        h.coordinator.continueOnPhone()
        h.machine.dispatch(StudyEvent.UserResumeRequested("m-resume"))
        h.machine.dispatch(StudyEvent.ServerSessionResumed("s1", "m-resume-ack"))
        settle()

        assertNull(h.coordinator.attention.value)
        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)
        assertEquals("the question is repeated on the new route", 2, h.speech.spoken.size)
        assertEquals(h.speech.spoken[0].text, h.speech.spoken[1].text)
        assertEquals(SessionPhase.WaitingForAnswer, h.machine.machineState.value.phase)
        assertFalse(h.timeline.overlapDetected)
    }

    @Test
    fun `continue-on-phone policy continues by itself, exactly once`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.fixture(
            snapshot = AudioRouteSnapshot.BLUETOOTH_HEADSET,
            disconnectPolicy = StudyAudioDisconnectPolicy.CONTINUE_ON_PHONE
        )

        h.startSession()
        settle()
        assertEquals(1, h.speech.spoken.size)

        h.snapshots.value = AudioRouteSnapshot.PHONE_ONLY
        settle()

        // No prompt under this policy, no pause message, and the question is re-issued on the
        // phone route — once, not in a loop.
        assertNull(h.coordinator.attention.value)
        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)
        assertEquals(2, h.speech.spoken.size)
        assertFalse(h.connection.sent.any { it is ClientMessage.PauseSession })
        assertEquals(1, h.coordinator.metrics.metrics.value.headsetLossEvents)

        settle()
        assertEquals("no recovery loop may re-issue the turn again", 2, h.speech.spoken.size)
        assertFalse(h.timeline.overlapDetected)
    }

    @Test
    fun `a headset appearing mid-turn is only applied at a boundary`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.fixture()
        h.startSession()
        settle()
        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)

        // Headphones are plugged in while the app waits for an answer: no mid-turn device swap.
        h.snapshots.value = AudioRouteSnapshot.WIRED_HEADSET
        settle()
        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)
        assertNotNull(h.coordinator.pendingRoute.value)
        assertEquals(1, h.speech.spoken.size)

        // The turn ends; the boundary promotes the headset route and the next question uses it.
        h.recognition.complete("c1", RecognitionPurpose.ANSWER, "answer", confidence = 0.9f)
        settle()
        assertEquals(EffectiveStudyAudioMode.HEADSET, h.coordinator.effectiveRoute.value.effective)
        assertNull(h.coordinator.pendingRoute.value)
    }

    @Test
    fun `push to talk cancels speech and ends the turn on release`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.fixture()
        h.startSession()
        settle()

        h.machine.dispatch(StudyEvent.PttStarted("c1", "turn-1"))
        settle()

        // The user asked to talk: the utterance is interrupted and the microphone opens for the
        // push-to-talk answer window.
        assertTrue(h.speech.stopReasons.isNotEmpty())
        assertTrue(
            h.recognition.started.any {
                it.purpose == RecognitionPurpose.PUSH_TO_TALK_ANSWER || it.purpose == RecognitionPurpose.ANSWER
            }
        )

        h.machine.dispatch(StudyEvent.PttStopped("c1", "turn-1"))
        settle()
        assertEquals("release must finish the turn, not drop the effect", 1, h.recognition.finishedTurns)
        // Nothing is submitted at release time; the terminal result decides.
        assertFalse(h.connection.sent.any { it is ClientMessage.SubmitAnswer })
    }
}
