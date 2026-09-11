package com.studyagent.client.stt

import com.studyagent.client.core.voice.stt.DefaultSpeechRecognitionOrchestrator
import com.studyagent.client.core.voice.stt.RecognitionBackendKind
import com.studyagent.client.core.voice.stt.RecognitionCapabilities
import com.studyagent.client.core.voice.stt.RecognitionError
import com.studyagent.client.core.voice.stt.RecognitionErrorCode
import com.studyagent.client.core.voice.stt.RecognitionHypothesis
import com.studyagent.client.core.voice.stt.RecognitionLanguageMode
import com.studyagent.client.core.voice.stt.RecognitionPolicyFactory
import com.studyagent.client.core.voice.stt.RecognitionPurpose
import com.studyagent.client.core.voice.stt.RecognitionRequest
import com.studyagent.client.core.voice.stt.RecognitionStartResult
import com.studyagent.client.core.voice.stt.RecognitionState
import com.studyagent.client.core.voice.stt.RecognitionTimeouts
import com.studyagent.client.core.voice.stt.RecognitionTurnResult
import com.studyagent.client.core.voice.stt.SttSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
 * Lifecycle, race and recovery behaviour of the recognition orchestrator (§103–§110/§115).
 *
 * Everything runs against [FakeSpeechRecognitionBackend], so none of it needs a microphone,
 * an emulator or a recognition provider.
 *
 * `minTurnIntervalMs` is 0 and `maxRetriesPerTurn` is 0 by default here: these tests assert
 * single-turn behaviour, and the rate limiter / retry policy are exercised explicitly in
 * their own tests below.
 */
class SpeechRecognitionOrchestratorTest {

    private val baseSettings = SttSettings(minTurnIntervalMs = 0L, maxRetriesPerTurn = 0)

    private class Fixture(
        val backend: FakeSpeechRecognitionBackend,
        val orchestrator: DefaultSpeechRecognitionOrchestrator,
        val results: MutableList<RecognitionTurnResult>,
        val collector: Job
    )

    private fun kotlinx.coroutines.test.TestScope.fixture(
        capabilities: RecognitionCapabilities = RecognitionCapabilities(
            recognitionAvailable = true,
            onDeviceAvailable = true,
            supportedLanguages = setOf("en-US", "ar-IQ"),
            installedLanguages = setOf("en-US", "ar-IQ")
        ),
        settings: SttSettings = baseSettings,
        canOpenMicrophone: () -> Boolean = { true }
    ): Fixture {
        val backend = FakeSpeechRecognitionBackend(initialCapabilities = capabilities)
        val orchestrator = DefaultSpeechRecognitionOrchestrator(
            backend = backend,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            canOpenMicrophone = canOpenMicrophone,
            clock = { testScheduler.currentTime }
        )
        orchestrator.updateSettings(settings)

        val results = mutableListOf<RecognitionTurnResult>()
        val collector = CoroutineScope(UnconfinedTestDispatcher(testScheduler)).launch {
            orchestrator.turnResults.collect { results += it }
        }
        runCurrent()
        return Fixture(backend, orchestrator, results, collector)
    }

    private fun request(
        purpose: RecognitionPurpose = RecognitionPurpose.ANSWER,
        settings: SttSettings = baseSettings,
        languageMode: RecognitionLanguageMode = RecognitionLanguageMode.AUTO_EN_AR
    ): RecognitionRequest = RecognitionPolicyFactory(
        idFactory = RecognitionPolicyFactory.counterIdFactory()
    ).createRequest(
        purpose = purpose,
        settings = settings.copy(languageMode = languageMode)
    )

    private fun RecognitionTurnResult.asCompleted() = this as RecognitionTurnResult.Completed
    private fun RecognitionTurnResult.asFailed() = this as RecognitionTurnResult.Failed
    private fun RecognitionStartResult.requestId(): String =
        (this as RecognitionStartResult.Started).requestId

    // ------------------------------------------------------------------ §103 normal answer

    @Test
    fun `normal answer reaches exactly one terminal result`() = runTest {
        val f = fixture()

        val start = f.orchestrator.startRecognition(request())
        assertTrue(start is RecognitionStartResult.Started)
        assertTrue(f.backend.isBusy)

        f.backend.emitReadyAndSpeech()
        f.backend.emitPartial("Volume more than")
        f.backend.emitPartial("Volume more than thirty milliliters")
        f.backend.emitSpeechEnded()

        // End-of-speech is NOT completion. This is the state the old implementation got wrong,
        // which is what allowed a second start to hit ERROR_RECOGNIZER_BUSY.
        assertTrue("turn must stay busy until the terminal callback", f.backend.isBusy)
        assertTrue(f.orchestrator.state.value is RecognitionState.Processing)

        f.backend.emitFinal("Volume more than thirty milliliters and midline shift", 0.87f)
        runCurrent()

        assertEquals("exactly one terminal result", 1, f.results.size)
        val outcome = f.results.single().asCompleted().outcome
        assertEquals("Volume more than thirty milliliters and midline shift", outcome.selectedText)
        assertEquals(0.87f, outcome.topConfidence!!, 0.001f)
        assertFalse(f.backend.isBusy)
        assertFalse(f.orchestrator.state.value.isActive)
        f.collector.cancel()
    }

    // ------------------------------------------------------------------ §105 push to talk

    @Test
    fun `push to talk does not submit on release, only on the final result`() = runTest {
        val f = fixture()

        f.orchestrator.startRecognition(request(RecognitionPurpose.PUSH_TO_TALK_ANSWER))
        f.backend.emitReadyAndSpeech()
        f.backend.emitPartial("The patient has")

        f.orchestrator.finishCurrentTurn()
        runCurrent()

        assertEquals("release calls stopListening, not cancel", 1, f.backend.stopListeningCount)
        assertEquals("release must not cancel the turn", 0, f.backend.cancelCount)
        assertTrue("nothing may be submitted at the moment of release", f.results.isEmpty())
        assertTrue(f.orchestrator.state.value is RecognitionState.Processing)
        assertTrue(f.backend.isBusy)

        f.backend.emitFinal("The patient has an epidural hematoma")
        runCurrent()

        assertEquals(1, f.results.size)
        assertEquals(
            "The patient has an epidural hematoma",
            f.results.single().asCompleted().outcome.selectedText
        )
        f.collector.cancel()
    }

    // ------------------------------------------------------------------ §8 busy race

    @Test
    fun `a second start while a turn is in flight is refused as busy`() = runTest {
        val f = fixture()

        assertTrue(f.orchestrator.startRecognition(request()) is RecognitionStartResult.Started)

        f.backend.emitReadyAndSpeech()
        f.backend.emitSpeechEnded()

        val second = f.orchestrator.startRecognition(request())
        assertTrue("second start must be refused", second is RecognitionStartResult.Rejected)
        assertEquals(RecognitionErrorCode.BUSY, second.asRejected().error.code)
        assertEquals("only one request may reach the recognizer", 1, f.backend.startedRequests.size)

        f.backend.emitFinal("answer")
        runCurrent()

        assertTrue(f.orchestrator.startRecognition(request()) is RecognitionStartResult.Started)
        assertEquals(2, f.backend.startedRequests.size)
        f.collector.cancel()
    }

    // ------------------------------------------------------------------ §10/§108 stale callback

    @Test
    fun `a late result from a cancelled turn cannot affect the next card`() = runTest {
        val f = fixture()

        val firstId = f.orchestrator.startRecognition(request()).requestId()
        f.backend.emitReadyAndSpeech()

        // The card advances: the old turn is cancelled, a new one starts.
        f.orchestrator.cancelCurrentTurn("new-question")
        runCurrent()
        val secondId = f.orchestrator.startRecognition(request()).requestId()
        assertTrue(firstId != secondId)
        f.backend.emitReadyAndSpeech()

        // The cancelled turn's result finally arrives.
        f.backend.emitStaleFinal(firstId, "stale answer from card one")
        runCurrent()

        assertEquals("the stale transcript must not be delivered", 0, f.results.size)
        assertTrue("the new turn must still be live", f.backend.isBusy)
        assertEquals(secondId, f.backend.activeRequestId)
        assertTrue(f.orchestrator.health.value.metrics.staleCallbacksDropped >= 1)

        f.backend.emitFinal("answer for card two")
        runCurrent()

        assertEquals(1, f.results.size)
        assertEquals("answer for card two", f.results.single().asCompleted().outcome.selectedText)
        f.collector.cancel()
    }

    // ------------------------------------------------------------------ §109 bounded busy recovery

    @Test
    fun `busy errors are retried with backoff and stop after the configured bound`() = runTest {
        val settings = SttSettings(minTurnIntervalMs = 0L, maxRetriesPerTurn = 2, retryBackoffMs = 100L)
        val f = fixture(settings = settings)

        f.orchestrator.startRecognition(request(settings = settings))
        assertEquals(1, f.backend.startedRequests.size)

        // First BUSY: a retry is scheduled 200ms out (backoff * (attempt + 2)), not immediate.
        f.backend.emitError(RecognitionErrorCode.BUSY)
        runCurrent()
        assertEquals("retry must be deferred, not instant", 1, f.backend.startedRequests.size)

        advanceTimeBy(250)
        runCurrent()
        assertEquals(2, f.backend.startedRequests.size)

        f.backend.emitError(RecognitionErrorCode.BUSY)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(3, f.backend.startedRequests.size)

        // Third failure exhausts the budget (initial attempt + 2 retries).
        f.backend.emitError(RecognitionErrorCode.BUSY)
        runCurrent()

        assertEquals("no unbounded retry loop", 3, f.backend.startedRequests.size)
        assertEquals(1, f.results.size)
        assertEquals(RecognitionErrorCode.BUSY, f.results.single().asFailed().error.code)
        assertFalse(f.backend.isBusy)
        f.collector.cancel()
    }

    // ------------------------------------------------------------------ §57/§110 TTS gate

    @Test
    fun `recognition never starts while speech is still active`() = runTest {
        var speechActive = true
        val f = fixture(canOpenMicrophone = { !speechActive })

        val result = f.orchestrator.startRecognition(request())
        assertTrue(result is RecognitionStartResult.Rejected)
        assertEquals("the recognizer must never have been touched", 0, f.backend.startedRequests.size)

        speechActive = false
        assertTrue(f.orchestrator.startRecognition(request()) is RecognitionStartResult.Started)
        assertEquals(1, f.backend.startedRequests.size)
        f.collector.cancel()
    }

    // ------------------------------------------------------------------ §92 watchdog

    @Test
    fun `a recognizer that never answers is bounded by the watchdog`() = runTest {
        val f = fixture()

        f.orchestrator.startRecognition(request())
        // onReadyForSpeech never arrives.
        advanceTimeBy(RecognitionTimeouts.DEFAULT.readyMs + 1)
        runCurrent()

        assertEquals(1, f.results.size)
        assertEquals(RecognitionErrorCode.WATCHDOG_TIMEOUT, f.results.single().asFailed().error.code)
        assertFalse("the watchdog must have released the recognizer", f.backend.isBusy)
        assertEquals(1, f.orchestrator.health.value.metrics.watchdogTimeouts)
        f.collector.cancel()
    }

    @Test
    fun `a long answer is given a longer budget than a rating`() {
        val policy = RecognitionPolicyFactory()
        val long = policy.timeoutsFor(
            RecognitionPurpose.ANSWER,
            SttSettings(answerEndpointProfile = com.studyagent.client.core.voice.stt.AnswerEndpointProfile.LONG)
        )
        val normal = policy.timeoutsFor(RecognitionPurpose.ANSWER, SttSettings())
        val rating = policy.timeoutsFor(RecognitionPurpose.RATING, SttSettings())

        assertTrue("long answers need more room to think", long.totalMs > normal.totalMs)
        assertTrue("ratings must time out fast", rating.totalMs < normal.totalMs)
        assertTrue(long.finalResultMs > normal.finalResultMs)
    }

    // ------------------------------------------------------------------ §52 rate limiting

    @Test
    fun `rapid repeated starts are rate limited`() = runTest {
        val f = fixture(settings = SttSettings(minTurnIntervalMs = 500L, maxRetriesPerTurn = 0))

        assertTrue(f.orchestrator.startRecognition(request()) is RecognitionStartResult.Started)
        f.backend.emitFinal("answer")
        runCurrent()

        val second = f.orchestrator.startRecognition(request())
        assertTrue(second is RecognitionStartResult.Rejected)
        assertEquals(RecognitionErrorCode.TOO_MANY_REQUESTS, second.asRejected().error.code)
        assertEquals(1, f.backend.startedRequests.size)
        f.collector.cancel()
    }

    // ------------------------------------------------------------------ §44/§115 event volume

    @Test
    fun `a flood of partial results stays bounded and still yields exactly one result`() = runTest {
        val f = fixture()

        f.orchestrator.startRecognition(request())
        f.backend.emitReadyAndSpeech()
        repeat(500) { i ->
            f.backend.emitPartial("partial number $i")
            f.backend.emitAudioLevel(i.toFloat() / 10f)
        }
        runCurrent()

        // partialTranscript is a conflated StateFlow: 500 emissions collapse to one value,
        // so the waveform cannot crowd out the final result.
        assertEquals("partial number 499", f.orchestrator.partialTranscript.value)
        assertTrue(f.results.isEmpty())

        f.backend.emitFinal("the real answer")
        runCurrent()
        assertEquals(1, f.results.size)
        f.collector.cancel()
    }

    // ------------------------------------------------------------------ §22 backend selection

    @Test
    fun `the on-device backend is chosen only when it is actually available`() = runTest {
        val onDevice = fixture()
        onDevice.orchestrator.startRecognition(request())
        assertEquals(RecognitionBackendKind.ON_DEVICE, onDevice.orchestrator.health.value.backendInUse)
        onDevice.orchestrator.cancelCurrentTurn("test")
        onDevice.collector.cancel()

        val systemOnly = fixture(
            capabilities = RecognitionCapabilities(recognitionAvailable = true, onDeviceAvailable = false)
        )
        systemOnly.orchestrator.startRecognition(request())
        assertEquals(RecognitionBackendKind.SYSTEM, systemOnly.orchestrator.health.value.backendInUse)
        systemOnly.collector.cancel()
    }

    @Test
    fun `on-device is not claimed for a language whose model is known to be missing`() = runTest {
        // §23: never show "Offline" unless we actually know on-device recognition is in use.
        val f = fixture(
            capabilities = RecognitionCapabilities(
                recognitionAvailable = true,
                onDeviceAvailable = true,
                supportedLanguages = setOf("en-US", "ar-IQ"),
                installedLanguages = setOf("en-US") // Arabic model missing
            )
        )
        f.orchestrator.startRecognition(
            request(languageMode = RecognitionLanguageMode.ARABIC)
        )
        assertEquals(RecognitionBackendKind.SYSTEM, f.orchestrator.health.value.backendInUse)
        f.collector.cancel()
    }

    // ------------------------------------------------------------------ §82 permission

    @Test
    fun `missing microphone permission is reported without touching the recognizer`() = runTest {
        val backend = FakeSpeechRecognitionBackend().also { it.permissionGranted = false }
        val orchestrator = DefaultSpeechRecognitionOrchestrator(
            backend = backend,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            clock = { testScheduler.currentTime }
        ).also { it.updateSettings(baseSettings) }

        val result = orchestrator.startRecognition(request())
        assertTrue(result is RecognitionStartResult.Rejected)
        val error: RecognitionError = result.asRejected().error
        assertEquals(RecognitionErrorCode.PERMISSION_DENIED, error.code)
        assertEquals(0, backend.startedRequests.size)
        assertFalse("permission errors must not be auto-retried", error.recommendedRetry)
        assertTrue("permission errors need the user", error.requiresUserAction)
    }

    // ------------------------------------------------------------------ §29/§30 bilingual

    @Test
    fun `auto language mode carries both locales on a single request`() = runTest {
        val settings = baseSettings.copy(
            languageMode = RecognitionLanguageMode.AUTO_EN_AR,
            englishLocale = "en-US",
            arabicLocale = "ar-IQ"
        )
        val req = RecognitionPolicyFactory().createRequest(RecognitionPurpose.ANSWER, settings)

        assertEquals(RecognitionLanguageMode.AUTO_EN_AR, req.languageMode)
        assertEquals("en-US", req.englishLocale)
        assertEquals("ar-IQ", req.arabicLocale)
        assertEquals("the fallback must be a real locale", "en-US", req.autoFallbackLocale)

        val f = fixture(settings = settings)
        f.orchestrator.startRecognition(req)
        assertEquals("exactly one recognizer serves both languages", 1, f.backend.startedRequests.size)
        f.collector.cancel()
    }

    // ------------------------------------------------------------------ §96 cancellation

    @Test
    fun `cancelling a turn leaves nothing active and emits no answer`() = runTest {
        val f = fixture()

        f.orchestrator.startRecognition(request())
        f.backend.emitReadyAndSpeech()
        f.backend.emitPartial("half an answer")

        f.orchestrator.cancelCurrentTurn("session-ended")
        runCurrent()

        assertFalse(f.backend.isBusy)
        assertEquals("", f.orchestrator.partialTranscript.value)
        assertNull(f.backend.activeRequestId)
        assertTrue(
            "a cancel must never produce a submit-able answer",
            f.results.none { it is RecognitionTurnResult.Completed }
        )
        f.collector.cancel()
    }

    // ------------------------------------------------------------------ §12 candidates preserved

    @Test
    fun `all recognizer alternatives and confidences are carried on the outcome`() = runTest {
        val f = fixture()

        f.orchestrator.startRecognition(request())
        f.backend.emitReadyAndSpeech()
        f.backend.emitFinalHypotheses(
            listOf(
                RecognitionHypothesis("midline shift more than 5 millimeters", 0.88f, 0),
                RecognitionHypothesis("midline shift more than five millimeters", 0.71f, 1),
                RecognitionHypothesis("midline shift more than five millimeter", 0.44f, 2)
            )
        )
        runCurrent()

        val outcome = f.results.single().asCompleted().outcome
        assertEquals(3, outcome.hypotheses.size)
        assertEquals("midline shift more than 5 millimeters", outcome.selectedText)
        assertEquals(0.88f, outcome.hypotheses[0].confidence!!, 0.001f)
        assertEquals(0.44f, outcome.hypotheses[2].confidence!!, 0.001f)
        f.collector.cancel()
    }

    // ------------------------------------------------------------------ §48 no speech vs no match

    @Test
    fun `no speech and no match stay distinct`() = runTest {
        val f = fixture()

        f.orchestrator.startRecognition(request())
        f.backend.emitError(RecognitionErrorCode.NO_SPEECH)
        runCurrent()
        f.orchestrator.startRecognition(request())
        f.backend.emitError(RecognitionErrorCode.NO_MATCH)
        runCurrent()

        val codes = f.results.map { it.asFailed().error.code }
        assertEquals(listOf(RecognitionErrorCode.NO_SPEECH, RecognitionErrorCode.NO_MATCH), codes)
        assertEquals(1, f.orchestrator.health.value.metrics.noSpeechCount)
        assertEquals(1, f.orchestrator.health.value.metrics.noMatchCount)
        f.collector.cancel()
    }

    // ------------------------------------------------------------------ §114 error classification

    @Test
    fun `every error code carries an explicit recovery classification`() {
        for (code in RecognitionErrorCode.entries) {
            assertNotNull("label required for ${code.name}", code.label)
            assertTrue("label must not be blank for ${code.name}", code.label.isNotBlank())
        }
        // The cases that must never be silently retried.
        assertFalse(RecognitionErrorCode.PERMISSION_DENIED.recommendedRetry)
        assertFalse(RecognitionErrorCode.UNAVAILABLE.recommendedRetry)
        assertFalse(RecognitionErrorCode.LANGUAGE_UNSUPPORTED.recommendedRetry)
        assertFalse(RecognitionErrorCode.LANGUAGE_MODEL_UNAVAILABLE.recommendedRetry)
        // Unknown future codes degrade safely rather than being treated as fatal.
        assertTrue(RecognitionErrorCode.INTERNAL.recoverable)
        // Unknown codes must remain distinguishable from known ones.
        assertTrue(RecognitionErrorCode.NO_SPEECH != RecognitionErrorCode.NO_MATCH)
    }

    // ------------------------------------------------------------------ §32 language events

    @Test
    fun `language detection events are surfaced for diagnostics only`() = runTest {
        val f = fixture()
        val tags = mutableListOf<String>()
        val listener = CoroutineScope(UnconfinedTestDispatcher(testScheduler)).launch {
            f.orchestrator.languageEvents.collect { event -> event.languageTag?.let { tags += it } }
        }
        runCurrent()

        f.orchestrator.startRecognition(request())
        f.backend.emitReadyAndSpeech()
        f.backend.emitLanguageDetected("ar-IQ", confidenceLevel = 2)
        runCurrent()
        f.backend.emitFinal("المريض عنده epidural hematoma")
        runCurrent()

        assertEquals(listOf("ar-IQ"), tags)
        listener.cancel()
        f.collector.cancel()
    }

    // ------------------------------------------------------------------ §120 release

    @Test
    fun `release destroys the backend and blocks further turns`() = runTest {
        val f = fixture()
        f.orchestrator.startRecognition(request())
        f.orchestrator.release()

        assertEquals(1, f.backend.releaseCount)
        assertTrue(f.orchestrator.state.value is RecognitionState.Released)
        assertTrue(f.orchestrator.startRecognition(request()) is RecognitionStartResult.Rejected)
        f.collector.cancel()
    }

    @Test
    fun `health snapshot always exposes a usable state`() = runTest {
        val f = fixture()
        assertNotNull(f.orchestrator.health.value.state)
        assertNotNull(f.orchestrator.capabilities.value)
        assertEquals(-1L, f.orchestrator.health.value.activeRequestAgeMs)
        f.collector.cancel()
    }

    private fun RecognitionStartResult.asRejected() = this as RecognitionStartResult.Rejected
}
