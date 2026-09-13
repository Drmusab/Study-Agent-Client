package com.studyagent.client.study

import com.studyagent.client.core.audio.AudioDeviceInfoModel
import com.studyagent.client.core.audio.AudioRouteManager
import com.studyagent.client.core.audio.InputRouteInfo
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.stt.FakeSpeechRecognitionBackend
import com.studyagent.client.core.voice.stt.DefaultSpeechRecognitionOrchestrator
import com.studyagent.client.core.voice.stt.RecognitionErrorCode
import com.studyagent.client.core.voice.stt.RecognitionHypothesis
import com.studyagent.client.core.voice.stt.RecognitionPurpose
import com.studyagent.client.core.voice.stt.SpeechRecognitionOrchestrator
import com.studyagent.client.data.repository.DefaultStudySessionRepository
import com.studyagent.client.data.repository.ConnectionRepository
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Study-loop reliability tests (§73–§91/§95).
 *
 * The full stack runs here — the real [DefaultSpeechRecognitionOrchestrator] over
 * [FakeSpeechRecognitionBackend] (same fixture as the STT unit tests) wired to the real
 * [DefaultStudySessionRepository] — with only the Android boundaries faked: the TTS engine,
 * the server connection, the audio route and persisted settings.
 *
 * What these tests buy: the exactly-once guarantees, stale-card rejection, the TTS/STT
 * interlock, push-to-talk lifecycle, interruption recovery and connection-loss behaviour are
 * proven end-to-end against the same state machines production uses.
 */
class DefaultStudySessionRepositoryTest {

    // ------------------------------------------------------------------ fakes

    private class FakeSpeechOrchestrator : SpeechOrchestrator {
        val isSpeakingFlow = MutableStateFlow(false)
        override val isSpeaking: StateFlow<Boolean> = isSpeakingFlow
        override val ttsState: StateFlow<TtsState> = MutableStateFlow(TtsState.Ready())
        override val health: StateFlow<TtsHealthSnapshot> = MutableStateFlow(TtsHealthSnapshot())
        override val isReady: StateFlow<Boolean> = MutableStateFlow(true)

        val speakRequests = mutableListOf<SpeechRequest>()
        val stopReasons = mutableListOf<StopReason>()

        /** When non-null, [speak] stays "in flight" until the test completes it. */
        var gate: CompletableDeferred<SpeechResult>? = null

        override suspend fun speak(request: SpeechRequest): SpeechResult {
            speakRequests += request
            isSpeakingFlow.value = true
            val held = gate
            if (held != null) {
                try {
                    return held.await()
                } finally {
                    isSpeakingFlow.value = false
                }
            }
            // Real utterances take time; consuming virtual time keeps the same timing
            // relationships (speech > acoustic gap > min turn interval) as production.
            kotlinx.coroutines.delay(SPEECH_DURATION_MS)
            isSpeakingFlow.value = false
            return SpeechResult.Completed
        }

        override suspend fun speakPreview(language: SegmentLanguage): SpeechResult = SpeechResult.Completed
        override fun stopSpeech(reason: StopReason) {
            stopReasons += reason
            isSpeakingFlow.value = false
        }
        override suspend fun getVoices(languageCode: String): List<TtsVoiceInfo> = emptyList()
        override suspend fun getEngines(): List<TtsEngineInfo> = emptyList()
        override fun updateSettings(settings: TtsSettings) {}
        override fun release() {}
    }

    private class FakeConnectionRepository : ConnectionRepository {
        val sent = mutableListOf<ClientMessage>()
        private val incomingFlow = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 64)
        override val incomingMessages: Flow<ServerMessage> = incomingFlow
        val connectionStateFlow =
            MutableStateFlow<ConnectionState>(ConnectionState.Connected("10.0.0.2", 8765))
        override val connectionState: StateFlow<ConnectionState> = connectionStateFlow.asStateFlow()
        override val activeProfile: Flow<ServerProfile?> = MutableStateFlow(null)

        override suspend fun connect(profile: ServerProfile?) {}
        override suspend fun disconnect(reason: String) {}
        override suspend fun send(message: ClientMessage): Boolean {
            sent += message
            return true
        }
        override fun toggleFakeAgent(useFake: Boolean) {}

        suspend fun receive(message: ServerMessage) {
            incomingFlow.emit(message)
        }

        fun submitAnswers() = sent.filterIsInstance<ClientMessage.SubmitAnswer>()
        fun rateCards() = sent.filterIsInstance<ClientMessage.RateCard>()
    }

    private class FakeAudioRouteManager(initialHeadset: Boolean) : AudioRouteManager {
        val headsetFlow = MutableStateFlow(initialHeadset)
        override val isHeadsetConnected: StateFlow<Boolean> = headsetFlow.asStateFlow()
        override val activeOutputDevice: StateFlow<AudioDeviceInfoModel> =
            MutableStateFlow(AudioDeviceInfoModel.DEFAULT_SPEAKER)
        override val availableInputDevices: StateFlow<List<AudioDeviceInfoModel>> =
            MutableStateFlow(emptyList())
        override val likelyInputRoute: StateFlow<InputRouteInfo> =
            MutableStateFlow(InputRouteInfo.UNKNOWN)
        override val hasExternalMicrophone: StateFlow<Boolean> = MutableStateFlow(false)
        override fun refreshAudioDevices() {}
        override fun release() {}
    }

    private class TestDispatcherProvider(dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val unconfined: CoroutineDispatcher = dispatcher
    }

    private class Fixture(
        val backend: FakeSpeechRecognitionBackend,
        val speech: FakeSpeechOrchestrator,
        val connection: FakeConnectionRepository,
        val audio: FakeAudioRouteManager,
        val settings: MutableStateFlow<AppSettings>,
        val recognition: SpeechRecognitionOrchestrator,
        val repository: DefaultStudySessionRepository
    )

    private fun TestScope.fixture(
        settings: AppSettings = AppSettings(),
        headsetConnected: Boolean = true
    ): Fixture {
        val speech = FakeSpeechOrchestrator()
        val backend = FakeSpeechRecognitionBackend()
        val connection = FakeConnectionRepository()
        val audio = FakeAudioRouteManager(initialHeadset = headsetConnected)
        val settingsFlow = MutableStateFlow(settings)
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)

        val recognition = DefaultSpeechRecognitionOrchestrator(
            backend = backend,
            scope = CoroutineScope(SupervisorJob() + testDispatcher),
            // Same predicate production wires in AppContainer (§57/§58).
            canOpenMicrophone = { !speech.isSpeaking.value && speech.health.value.queueDepth == 0 },
            inputRouteLabel = { audio.likelyInputRoute.value.displayLabel },
            clock = { testScheduler.currentTime }
        )

        val repository = DefaultStudySessionRepository(
            connectionRepository = connection,
            speechOrchestrator = speech,
            recognitionOrchestrator = recognition,
            settingsFlow = settingsFlow,
            audioRouteManager = audio,
            dispatchers = TestDispatcherProvider(testDispatcher),
            scope = CoroutineScope(SupervisorJob() + testDispatcher),
            clock = { testScheduler.currentTime }
        )
        runCurrent()
        return Fixture(backend, speech, connection, audio, settingsFlow, recognition, repository)
    }

    // ------------------------------------------------------------------ helpers

    private fun question(cardId: String, text: String = "Question for $cardId") =
        ServerMessage.Question(sessionId = "s1", cardId = cardId, question = text, cardNumber = 1, remaining = 5)

    private fun evaluation(cardId: String, feedback: String = "Well structured.") =
        ServerMessage.EvaluationResponse(sessionId = "s1", cardId = cardId, score = 75, shortFeedback = feedback)

    /** Drives the question speech + acoustic-gap handoff so the answer window actually opens. */
    private suspend fun TestScope.openAnswerWindow(f: Fixture, cardId: String) {
        f.connection.receive(ServerMessage.SessionStarted(sessionId = "s1", totalCards = 10))
        f.connection.receive(question(cardId))
        advanceTimeBy(HANDOFF_GAP_MS)
        runCurrent()
    }

    /** Drives feedback speech + handoff so the rating window actually opens. */
    private suspend fun TestScope.openRatingWindow(f: Fixture, cardId: String) {
        f.connection.receive(evaluation(cardId))
        advanceTimeBy(HANDOFF_GAP_MS)
        runCurrent()
    }

    private fun StudyState.cardIdOrNull(): String? = this.currentCardOrNull?.id

    private companion object {
        const val HANDOFF_GAP_MS = 1_000L // > acoustic gap (350ms) and its failed variant

        /** Virtual duration of one fake utterance (question, feedback, prompt...). */
        const val SPEECH_DURATION_MS = 300L
    }

    // ---------------------------------------------------- §73/§95 normal flow, exactly once

    @Test
    fun `a spoken answer is submitted exactly once and late duplicates are dropped`() = runTest {
        val f = fixture()
        openAnswerWindow(f, "c1")

        assertEquals("the answer window opened", StudyState.Listening::class, f.repository.studyState.value::class)
        assertEquals(1, f.backend.startedRequests.size)
        assertEquals(
            RecognitionPurpose.ANSWER,
            f.recognition.health.value.activePurpose
        )

        f.backend.emitReadyAndSpeech()
        f.backend.emitFinal("The lesion is in the left parietal lobe", 0.9f)
        runCurrent()

        assertEquals("exactly one SubmitAnswer", 1, f.connection.submitAnswers().size)
        assertEquals("c1", f.connection.submitAnswers().single().cardId)
        assertEquals(
            "The lesion is in the left parietal lobe",
            f.connection.submitAnswers().single().text
        )
        assertTrue(f.repository.studyState.value is StudyState.Evaluating)

        // A buggy recognizer re-delivers the same terminal: it must change nothing.
        val finishedId = f.recognition.health.value.metrics.completedTurns.let {
            f.backend.startedRequests.last().id
        }
        f.backend.emitRawResults(
            finishedId,
            listOf(RecognitionHypothesis("The lesion is in the left parietal lobe", 0.9f, 0))
        )
        runCurrent()
        assertEquals("duplicate final must not resubmit", 1, f.connection.submitAnswers().size)
    }

    // ---------------------------------------------------- §81/§82 answer containing rating word

    @Test
    fun `an answer containing good is submitted as an answer and never as a rating`() = runTest {
        val f = fixture()
        openAnswerWindow(f, "c1")

        f.backend.emitReadyAndSpeech()
        f.backend.emitFinal("Good blood pressure control reduces risk", 0.8f)
        runCurrent()

        assertEquals(1, f.connection.submitAnswers().size)
        assertEquals(
            "Good blood pressure control reduces risk",
            f.connection.submitAnswers().single().text
        )
        assertEquals("no rating may fire during an answer", 0, f.connection.rateCards().size)
    }

    // ---------------------------------------------------- §83 medical repeat word

    @Test
    fun `repeat inside a medical answer does not trigger repeat question`() = runTest {
        val f = fixture()
        openAnswerWindow(f, "c1")

        f.backend.emitReadyAndSpeech()
        f.backend.emitFinal("repeat CT brain after six hours", 0.85f)
        runCurrent()

        assertEquals("medical answer is submitted verbatim", 1, f.connection.submitAnswers().size)
        assertTrue(
            "no RepeatQuestion may be sent",
            f.connection.sent.none { it is ClientMessage.RepeatQuestion }
        )
    }

    // ---------------------------------------------------- §84 explicit command in answer mode

    @Test
    fun `an explicit repeat question phrase still works during an answer`() = runTest {
        val f = fixture()
        openAnswerWindow(f, "c1")

        f.backend.emitReadyAndSpeech()
        f.backend.emitFinal("repeat question", 0.92f)
        runCurrent()

        assertEquals("no answer may be submitted for a command", 0, f.connection.submitAnswers().size)
        assertEquals(1, f.connection.sent.filterIsInstance<ClientMessage.RepeatQuestion>().size)
    }

    // ---------------------------------------------------- §76 stale card

    @Test
    fun `a late result for card A cannot submit after card B has arrived`() = runTest {
        val f = fixture()
        openAnswerWindow(f, "c1")
        val staleId = f.backend.startedRequests.single().id

        // Server moves on to the next card before the user finished.
        f.connection.receive(question("c2"))
        runCurrent()
        assertEquals("card A recognition was cancelled", 0, f.connection.submitAnswers().size)

        // The dead request's result finally arrives.
        f.backend.emitRawResults(
            staleId,
            listOf(RecognitionHypothesis("late answer for card A", 0.95f, 0))
        )
        runCurrent()

        assertTrue(
            "the stale transcript must never be submitted",
            f.connection.submitAnswers().isEmpty()
        )
        assertEquals("c2", f.repository.studyState.value.cardIdOrNull())
        assertTrue(f.recognition.health.value.metrics.staleCallbacksDropped >= 1)
    }

    // ---------------------------------------------------- §74/§9 push to talk

    @Test
    fun `push to talk submits only from the final result, never at release`() = runTest {
        val f = fixture(settings = AppSettings(handsFreeMode = false))
        openAnswerWindow(f, "c1")
        // Manual mode: after the question speech the user presses PTT.
        f.repository.startManualPushToTalk()
        advanceTimeBy(HANDOFF_GAP_MS)
        runCurrent()

        assertEquals(1, f.backend.startedRequests.size)
        assertEquals(
            RecognitionPurpose.PUSH_TO_TALK_ANSWER,
            f.recognition.health.value.activePurpose
        )

        f.backend.emitReadyAndSpeech()
        f.backend.emitPartial("The diagnosis is")
        f.repository.stopManualPushToTalk()
        runCurrent()

        assertEquals("release means stopListening", 1, f.backend.stopListeningCount)
        assertTrue("nothing may submit at release", f.connection.submitAnswers().isEmpty())

        f.backend.emitFinal("The diagnosis is temporal arteritis", 0.9f)
        runCurrent()
        assertEquals(1, f.connection.submitAnswers().size)
        assertEquals("The diagnosis is temporal arteritis", f.connection.submitAnswers().single().text)
    }

    // ---------------------------------------------------- §75 PTT late result after pause

    @Test
    fun `a push to talk result arriving after pause is never submitted`() = runTest {
        val f = fixture(settings = AppSettings(handsFreeMode = false))
        openAnswerWindow(f, "c1")
        f.repository.startManualPushToTalk()
        advanceTimeBy(HANDOFF_GAP_MS)
        runCurrent()

        val activeId = f.backend.startedRequests.single().id
        f.backend.emitReadyAndSpeech()

        f.repository.pauseStudy()
        runCurrent()
        assertTrue(f.repository.studyState.value is StudyState.Paused)

        // The recognizer finalises anyway, late, into a paused session.
        f.backend.emitRawResults(
            activeId,
            listOf(RecognitionHypothesis("half a thought", 0.9f, 0))
        )
        runCurrent()

        assertTrue("no submission after pause", f.connection.submitAnswers().isEmpty())
        assertTrue(f.repository.studyState.value is StudyState.Paused)
    }

    @Test
    fun `push to talk is refused outside an active study window`() = runTest {
        val f = fixture(settings = AppSettings(handsFreeMode = false))

        f.repository.startManualPushToTalk()
        advanceTimeBy(HANDOFF_GAP_MS)
        runCurrent()

        assertEquals("no mic outside study windows", 0, f.backend.startedRequests.size)
    }

    // ---------------------------------------------------- §11/§70/§71 button vs voice rating

    @Test
    fun `a button rating wins over a later voice rating and only one is sent`() = runTest {
        val f = fixture()
        openAnswerWindow(f, "c1")
        openRatingWindow(f, "c1")

        f.backend.emitReadyAndSpeech() // rating STT is live

        // The user taps Good while the microphone is open.
        f.repository.rateCurrentCard(Rating.GOOD)
        runCurrent()
        assertEquals(1, f.connection.rateCards().size)

        // The in-flight rating turn was invalidated; its late result is dropped.
        val ratingTurnId = f.backend.startedRequests.last().id
        f.backend.emitRawResults(
            ratingTurnId,
            listOf(RecognitionHypothesis("hard", 0.95f, 0))
        )
        runCurrent()

        assertEquals(1, f.connection.rateCards().size)
        assertEquals(Rating.GOOD, f.connection.rateCards().single().rating)
    }

    @Test
    fun `a voice rating followed by a button tap still rates exactly once`() = runTest {
        val f = fixture()
        openAnswerWindow(f, "c1")
        openRatingWindow(f, "c1")

        f.backend.emitReadyAndSpeech()
        f.backend.emitFinal("easy", 0.95f)
        runCurrent()
        assertEquals(1, f.connection.rateCards().size)
        assertEquals(Rating.EASY, f.connection.rateCards().single().rating)

        // The user also taps (double-tap, jitter, recomposition): the ledger refuses it.
        f.repository.rateCurrentCard(Rating.GOOD)
        runCurrent()
        assertEquals(1, f.connection.rateCards().size)
    }

    // ---------------------------------------------------- §85/§86 rating candidates & confidence

    @Test
    fun `a low confidence rating is never applied silently`() = runTest {
        val f = fixture()
        openAnswerWindow(f, "c1")
        openRatingWindow(f, "c1")

        f.backend.emitReadyAndSpeech()
        f.backend.emitFinal("good", 0.25f)
        runCurrent()

        assertEquals("0.25 confidence must not reschedule the card", 0, f.connection.rateCards().size)
        assertEquals("no phantom answer either", 0, f.connection.submitAnswers().size)
    }

    @Test
    fun `rating alternatives could good hood resolve to Good exactly once`() = runTest {
        val f = fixture()
        openAnswerWindow(f, "c1")
        openRatingWindow(f, "c1")

        f.backend.emitReadyAndSpeech()
        f.backend.emitFinalHypotheses(
            listOf(
                RecognitionHypothesis("could", 0.5f, 0),
                RecognitionHypothesis("good", 0.8f, 1),
                RecognitionHypothesis("hood", 0.4f, 2)
            )
        )
        runCurrent()

        assertEquals(1, f.connection.rateCards().size)
        assertEquals(Rating.GOOD, f.connection.rateCards().single().rating)
    }

    @Test
    fun `an answer-like utterance in the rating window does not re-submit the card`() = runTest {
        val f = fixture()
        openAnswerWindow(f, "c1")
        openRatingWindow(f, "c1")

        // The user rambles a whole answer again instead of rating.
        f.backend.emitReadyAndSpeech()
        f.backend.emitFinal("The temporal artery inflammation steroid treatment", 0.7f)
        runCurrent()

        assertEquals(
            "an already-evaluated card must not be answered twice",
            0,
            f.connection.submitAnswers().size
        )
        assertEquals("and it must not be rated either", 0, f.connection.rateCards().size)
        assertTrue("the rating window stays open", f.repository.studyState.value is StudyState.WaitingForRating)
    }

    // ---------------------------------------------------- §14/§140 rating confirmation loop

    @Test
    fun `an uncertain rating asks for confirmation and yes applies it exactly once`() = runTest {
        val f = fixture(settings = AppSettings(confirmRating = true))
        openAnswerWindow(f, "c1")
        openRatingWindow(f, "c1")

        // 0.6 is above the rating floor (0.45) but below the auto-apply bar (0.75).
        f.backend.emitReadyAndSpeech()
        f.backend.emitFinal("good", 0.6f)
        runCurrent()
        assertEquals("not applied before confirmation", 0, f.connection.rateCards().size)

        // The confirmation prompt is spoken; when it drains the SHORT_CONFIRMATION listener opens.
        advanceTimeBy(HANDOFF_GAP_MS)
        runCurrent()
        assertEquals(
            RecognitionPurpose.SHORT_CONFIRMATION,
            f.recognition.health.value.activePurpose
        )

        f.backend.emitReadyAndSpeech()
        f.backend.emitFinal("yes", 0.8f)
        runCurrent()

        assertEquals(1, f.connection.rateCards().size)
        assertEquals(Rating.GOOD, f.connection.rateCards().single().rating)

        // An echoed second "yes" (or a repeat) must not rate again.
        f.backend.emitReadyAndSpeech()
        f.backend.emitFinal("yes", 0.8f)
        runCurrent()
        assertEquals("rating is exactly-once", 1, f.connection.rateCards().size)
    }

    // ---------------------------------------------------- §90/§68 TTS/STT interlock

    @Test
    fun `the microphone never opens while speech is active and resumes only after it settles`() = runTest {
        val f = fixture()
        f.speech.gate = CompletableDeferred()

        // Question speech is now in flight (gated).
        f.connection.receive(question("c1"))
        runCurrent()
        assertTrue("TTS active", f.speech.isSpeaking.value)
        assertEquals(0, f.backend.startedRequests.size)

        // The server moves on while the previous question is still being spoken.
        f.connection.receive(ServerMessage.Question(sessionId = "s1", cardId = "c2", question = "Next", speak = false))
        runCurrent()
        assertEquals("no STT while TTS is active", 0, f.backend.startedRequests.size)

        // Speech finishes; the stale card transition is refused and the drain backstop
        // opens the answer window for the CURRENT card only.
        f.speech.gate!!.complete(SpeechResult.Completed)
        advanceTimeBy(HANDOFF_GAP_MS)
        runCurrent()

        assertEquals(1, f.backend.startedRequests.size)
        assertEquals("the answer window belongs to the CURRENT card", "c2", f.backend.startedRequests.single().cardId)
        assertEquals("c2", f.repository.studyState.value.cardIdOrNull())
    }

    // ---------------------------------------------------- §19/§43 auto-submit setting

    @Test
    fun `auto submit off parks the transcript until the user confirms, exactly once`() = runTest {
        val f = fixture(settings = AppSettings(autoSubmitTranscript = false))
        openAnswerWindow(f, "c1")

        f.backend.emitReadyAndSpeech()
        f.backend.emitFinal("The mortality rate is high", 0.9f)
        runCurrent()
        assertEquals("nothing is sent until the user confirms", 0, f.connection.submitAnswers().size)
        assertTrue(f.repository.studyState.value is StudyState.Listening)

        f.repository.submitPendingTranscript()
        runCurrent()
        assertEquals(1, f.connection.submitAnswers().size)

        f.repository.submitPendingTranscript()
        runCurrent()
        assertEquals("confirming twice must not send twice", 1, f.connection.submitAnswers().size)
    }

    // ---------------------------------------------------- §20/§42 spoken rating setting

    @Test
    fun `spoken ratings off keeps the microphone closed in the rating window`() = runTest {
        val f = fixture(settings = AppSettings(listenForSpokenRating = false))
        openAnswerWindow(f, "c1")
        f.backend.emitReadyAndSpeech()
        f.backend.emitFinal("An answer", 0.9f)
        runCurrent()
        assertEquals(1, f.backend.startedRequests.size) // the answer turn

        openRatingWindow(f, "c1")

        assertTrue(f.repository.studyState.value is StudyState.WaitingForRating)
        assertEquals(
            "no rating listener may start",
            1,
            f.backend.startedRequests.size
        )

        // Buttons still work.
        f.repository.rateCurrentCard(Rating.HARD)
        runCurrent()
        assertEquals(1, f.connection.rateCards().size)
    }

    // ---------------------------------------------------- §28 pause

    @Test
    fun `pausing cancels recognition and a late final cannot submit`() = runTest {
        val f = fixture()
        openAnswerWindow(f, "c1")
        val activeId = f.backend.startedRequests.single().id
        f.backend.emitReadyAndSpeech()

        f.repository.pauseStudy()
        runCurrent()

        f.backend.emitRawResults(
            activeId,
            listOf(RecognitionHypothesis("late answer", 0.9f, 0))
        )
        runCurrent()

        assertTrue(f.repository.studyState.value is StudyState.Paused)
        assertTrue(f.connection.submitAnswers().isEmpty())
    }

    // ---------------------------------------------------- §29 end session

    @Test
    fun `ending the session invalidates recognition and nothing submits afterwards`() = runTest {
        val f = fixture()
        openAnswerWindow(f, "c1")
        val activeId = f.backend.startedRequests.single().id
        f.backend.emitReadyAndSpeech()

        f.repository.endStudy()
        runCurrent()

        f.backend.emitRawResults(
            activeId,
            listOf(RecognitionHypothesis("late answer", 0.9f, 0))
        )
        runCurrent()

        assertTrue(f.repository.studyState.value is StudyState.SessionFinished)
        assertTrue(f.connection.submitAnswers().isEmpty())
    }

    // ---------------------------------------------------- §31 connection loss

    @Test
    fun `connection loss cancels recognition and does not submit partials`() = runTest {
        val f = fixture()
        openAnswerWindow(f, "c1")
        val activeId = f.backend.startedRequests.single().id
        f.backend.emitReadyAndSpeech()
        f.backend.emitPartial("The answer starts with")

        f.connection.connectionStateFlow.value = ConnectionState.Disconnected
        runCurrent()

        assertTrue(f.repository.studyState.value is StudyState.Error)
        assertEquals("recognition was cancelled", null, f.recognition.health.value.activeRequestId)

        f.backend.emitRawResults(
            activeId,
            listOf(RecognitionHypothesis("The answer starts with", 0.8f, 0))
        )
        runCurrent()
        assertTrue("no partial answer may be submitted", f.connection.submitAnswers().isEmpty())

        // Reconnect reconciles the session instead of resuming recognition blindly.
        f.connection.connectionStateFlow.value = ConnectionState.Connected("10.0.0.2", 8765)
        runCurrent()
        assertEquals(1, f.connection.sent.filterIsInstance<ClientMessage.RequestSessionStatus>().size)
    }

    // ---------------------------------------------------- §32 headset disconnect

    @Test
    fun `headset loss during recognition cancels the turn and late results are dropped`() = runTest {
        val f = fixture(headsetConnected = true)
        openAnswerWindow(f, "c1")
        val activeId = f.backend.startedRequests.single().id
        f.backend.emitReadyAndSpeech()

        f.audio.headsetFlow.value = false
        runCurrent()
        assertEquals("turn was cancelled on route loss", null, f.recognition.health.value.activeRequestId)

        f.backend.emitRawResults(
            activeId,
            listOf(RecognitionHypothesis("answer through a dead headset", 0.9f, 0))
        )
        runCurrent()
        assertTrue(f.connection.submitAnswers().isEmpty())
        assertEquals("the card stays active", "c1", f.repository.studyState.value.cardIdOrNull())
    }

    // ---------------------------------------------------- §50 too many requests

    @Test
    fun `a rate limited recognizer is not immediately restarted`() = runTest {
        val f = fixture()
        openAnswerWindow(f, "c1")
        assertEquals(1, f.backend.startedRequests.size)

        f.backend.emitError(RecognitionErrorCode.TOO_MANY_REQUESTS)
        runCurrent()

        assertEquals(
            "no rapid re-start after throttling",
            1,
            f.backend.startedRequests.size
        )
        assertTrue(f.repository.studyState.value is StudyState.Error)
    }

    // ---------------------------------------------------- §30 duplicate question re-delivery

    @Test
    fun `a duplicate question re-delivery does not cancel the answer in progress`() = runTest {
        val f = fixture()
        openAnswerWindow(f, "c1")
        assertEquals(1, f.backend.startedRequests.size)

        // Server re-sends the same question (e.g. after reconnect).
        f.connection.receive(question("c1"))
        runCurrent()

        assertEquals(
            "the user's in-flight recognition must survive",
            true,
            f.recognition.health.value.activeRequestId != null
        )

        // And its answer still lands exactly once.
        f.backend.emitReadyAndSpeech()
        f.backend.emitFinal("still the answer", 0.9f)
        runCurrent()
        assertEquals(1, f.connection.submitAnswers().size)
    }

    // ---------------------------------------------------- §10 next-card ledger reset

    @Test
    fun `the next card can be answered and rated after the previous one`() = runTest {
        val f = fixture()
        openAnswerWindow(f, "c1")
        f.backend.emitReadyAndSpeech()
        f.backend.emitFinal("Answer one", 0.9f)
        runCurrent()
        openRatingWindow(f, "c1")
        f.backend.emitReadyAndSpeech()
        f.backend.emitFinal("good", 0.9f)
        runCurrent()

        assertEquals(1, f.connection.submitAnswers().size)
        assertEquals(1, f.connection.rateCards().size)

        f.connection.receive(ServerMessage.RatingSaved(sessionId = "s1", cardId = "c1", rating = Rating.GOOD))
        runCurrent()
        openAnswerWindow(f, "c2")
        f.backend.emitReadyAndSpeech()
        f.backend.emitFinal("Answer two", 0.9f)
        runCurrent()
        openRatingWindow(f, "c2")
        f.backend.emitReadyAndSpeech()
        f.backend.emitFinal("easy", 0.9f)
        runCurrent()

        assertEquals(2, f.connection.submitAnswers().size)
        assertEquals(2, f.connection.rateCards().size)
        assertEquals("c2", f.connection.submitAnswers().last().cardId)
    }

    // ---------------------------------------------------- §91 mini marathon at study level

    @Test
    fun `ten consecutive full study turns each submit exactly one answer and one rating`() = runTest {
        val f = fixture()

        repeat(10) { i ->
            val cardId = "card-$i"
            openAnswerWindow(f, cardId)
            f.backend.emitReadyAndSpeech()
            f.backend.emitFinal("Answer for card $i", 0.9f)
            runCurrent()
            openRatingWindow(f, cardId)
            f.backend.emitReadyAndSpeech()
            f.backend.emitFinal("good", 0.9f)
            runCurrent()
            f.connection.receive(ServerMessage.RatingSaved(sessionId = "s1", cardId = cardId, rating = Rating.GOOD))
            runCurrent()
        }

        assertEquals(10, f.connection.submitAnswers().size)
        assertEquals(10, f.connection.rateCards().size)
        assertEquals(
            "every card answered once",
            (0 until 10).map { "card-$it" },
            f.connection.submitAnswers().map { it.cardId }
        )
        assertEquals(
            "every card rated once",
            (0 until 10).map { "card-$it" },
            f.connection.rateCards().map { it.cardId }
        )
    }
}
