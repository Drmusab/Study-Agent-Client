package com.studyagent.client.tts

import com.studyagent.client.core.voice.tts.DefaultSpeechOrchestrator
import com.studyagent.client.core.voice.tts.QueuePolicy
import com.studyagent.client.core.voice.tts.SegmentLanguage
import com.studyagent.client.core.voice.tts.SpeechErrorCode
import com.studyagent.client.core.voice.tts.SpeechPurpose
import com.studyagent.client.core.voice.tts.SpeechRequest
import com.studyagent.client.core.voice.tts.SpeechResult
import com.studyagent.client.core.voice.tts.StopReason
import com.studyagent.client.core.voice.tts.TtsSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §69/§70/§71 (queue, cancellation, callback-cleanup) exercised against the fake engine.
 */
class SpeechOrchestratorTest {

    private fun newOrchestrator(
        engine: FakeTtsEngineAdapter,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ) = DefaultSpeechOrchestrator(engine = engine, workDispatcher = dispatcher)

    private fun question(
        id: String,
        text: String = "What are the indications for surgery?",
        policy: QueuePolicy = QueuePolicy.REPLACE
    ) = SpeechRequest(
        id = id,
        text = text,
        purpose = SpeechPurpose.QUESTION,
        queuePolicy = policy
    )

    // ---------------------------------------------------------------- lifecycle

    @Test
    fun `speak during initialization is queued until ready`() = runTest {
        val engine = FakeTtsEngineAdapter()
        engine.markInitializing()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val orchestrator = newOrchestrator(engine, dispatcher)
        advanceUntilIdle() // let engine.status collector observe INITIALIZING

        val result = async { orchestrator.speak(question("q1")) }
        runCurrent()
        engine.markReady()
        advanceUntilIdle()

        assertEquals(SpeechResult.Completed, result.await())
        assertEquals(1, engine.spoken.size)
        orchestrator.release()
    }

    @Test
    fun `speak fails fast with typed error when engine is down`() = runTest {
        val engine = FakeTtsEngineAdapter()
        engine.markFailed()
        val orchestrator = newOrchestrator(engine, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        val result = orchestrator.speak(question("q1"))
        assertTrue(result is SpeechResult.Failed)
        assertEquals(0, engine.spoken.size)
        orchestrator.release()
    }

    // ---------------------------------------------------------------- chunking & aggregation

    @Test
    fun `long explanation aggregates chunks into single completion`() = runTest {
        val engine = FakeTtsEngineAdapter(maxInputLength = 60)
        engine.markReady()
        val orchestrator = newOrchestrator(engine, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        val text = List(8) { "Sentence number $it explains the condition in detail." }.joinToString(" ")
        val result = orchestrator.speak(
            SpeechRequest(
                id = "explain_1",
                text = text,
                purpose = SpeechPurpose.EXPLANATION,
                queuePolicy = QueuePolicy.APPEND
            )
        )
        assertEquals(SpeechResult.Completed, result)
        assertTrue("expected multiple engine utterances, got ${engine.spoken.size}", engine.spoken.size > 1)
        assertEquals(0, engine.pendingCount) // §70: no callback leaks
        orchestrator.release()
    }

    // ---------------------------------------------------------------- queue policies

    @Test
    fun `append requests run sequentially in order`() = runTest {
        val engine = FakeTtsEngineAdapter()
        engine.markReady()
        val orchestrator = newOrchestrator(engine, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        val first = async {
            orchestrator.speak(question("q1", text = "First sentence.", policy = QueuePolicy.APPEND))
        }
        val second = async {
            orchestrator.speak(question("q2", text = "Second sentence.", policy = QueuePolicy.APPEND))
        }
        advanceUntilIdle()

        assertEquals(SpeechResult.Completed, first.await())
        assertEquals(SpeechResult.Completed, second.await())
        assertTrue(engine.spoken[0].text.contains("First"))
        assertTrue(engine.spoken.any { it.text.contains("Second") })
        orchestrator.release()
    }

    @Test
    fun `replace cancels in-flight speech and replays`() = runTest {
        val engine = FakeTtsEngineAdapter()
        engine.markReady()
        engine.mode = FakeTtsEngineAdapter.Mode.MANUAL
        val orchestrator = newOrchestrator(engine, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        val first = async { orchestrator.speak(question("q1", text = "Old question.")) }
        runCurrent() // pump starts, utterance parks in manual mode

        val second = async { orchestrator.speak(question("q2", text = "New question.")) }
        advanceUntilIdle()

        assertEquals(SpeechResult.Cancelled, first.await())
        engine.completeNext()
        advanceUntilIdle()
        assertEquals(SpeechResult.Completed, second.await())
        assertEquals(0, engine.pendingCount)
        assertEquals("New question.", engine.spoken.last().text)
        orchestrator.release()
    }

    @Test
    fun `duplicate append is suppressed without double speak`() = runTest {
        val engine = FakeTtsEngineAdapter()
        engine.markReady()
        engine.mode = FakeTtsEngineAdapter.Mode.MANUAL
        val orchestrator = newOrchestrator(engine, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        val dupRequest = question("dup1", text = "Volume greater than 30 mL.", policy = QueuePolicy.APPEND)
        val first = async { orchestrator.speak(dupRequest) }
        runCurrent()
        val second = async { orchestrator.speak(dupRequest) }
        advanceUntilIdle()

        assertEquals(SpeechResult.Cancelled, second.await())
        engine.completeNext()
        advanceUntilIdle()
        assertEquals(SpeechResult.Completed, first.await())
        assertEquals(1, engine.spoken.size)
        orchestrator.release()
    }

    @Test
    fun `stop speech cancels everything and callers are informed`() = runTest {
        val engine = FakeTtsEngineAdapter()
        engine.markReady()
        engine.mode = FakeTtsEngineAdapter.Mode.MANUAL
        val orchestrator = newOrchestrator(engine, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        val first = async { orchestrator.speak(question("q1")) }
        runCurrent()
        orchestrator.stopSpeech(StopReason.USER)
        advanceUntilIdle()

        assertEquals(SpeechResult.Cancelled, first.await())
        assertEquals(1, engine.spoken.size) // utterance started, then cancelled mid-flight
        assertEquals(0, engine.pendingCount)
        orchestrator.release()
    }

    @Test
    fun `route loss maps to typed failure not silent cancel`() = runTest {
        val engine = FakeTtsEngineAdapter()
        engine.markReady()
        engine.mode = FakeTtsEngineAdapter.Mode.MANUAL
        val orchestrator = newOrchestrator(engine, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        val first = async { orchestrator.speak(question("q1")) }
        runCurrent()
        orchestrator.stopSpeech(StopReason.ROUTE_LOST)
        advanceUntilIdle()

        val result = first.await()
        assertTrue(result is SpeechResult.Failed)
        assertEquals(SpeechErrorCode.ROUTE_LOST, (result as SpeechResult.Failed).error.code)
        orchestrator.release()
    }

    // ---------------------------------------------------------------- failure & cleanup

    @Test
    fun `engine playback failure propagates typed error and counts metrics`() = runTest {
        val engine = FakeTtsEngineAdapter()
        engine.markReady()
        engine.mode = FakeTtsEngineAdapter.Mode.AUTO_FAIL
        engine.failWith = com.studyagent.client.core.voice.tts.SpeechError(
            SpeechErrorCode.PLAYBACK_ERROR, "synthesis exploded"
        )
        val orchestrator = newOrchestrator(engine, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        val result = orchestrator.speak(question("q1"))
        assertTrue(result is SpeechResult.Failed)
        assertEquals(SpeechErrorCode.PLAYBACK_ERROR, (result as SpeechResult.Failed).error.code)
        assertEquals(1, orchestrator.health.value.metrics.failedRequests)
        assertEquals(SpeechErrorCode.PLAYBACK_ERROR, orchestrator.health.value.lastError?.code)
        orchestrator.release()
    }

    @Test
    fun `release completes manual callers and leaves no pending callbacks`() = runTest {
        val engine = FakeTtsEngineAdapter()
        engine.markReady()
        engine.mode = FakeTtsEngineAdapter.Mode.MANUAL
        val orchestrator = newOrchestrator(engine, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        val first = async { orchestrator.speak(question("q1")) }
        runCurrent()
        orchestrator.release()
        advanceUntilIdle()

        assertEquals(SpeechResult.Cancelled, first.await())
        assertEquals(0, engine.pendingCount)
        assertTrue(engine.releaseCount >= 1)
    }

    // ---------------------------------------------------------------- content pipeline

    @Test
    fun `mixed arabic english text produces per-language utterances`() = runTest {
        val engine = FakeTtsEngineAdapter()
        engine.markReady()
        val orchestrator = newOrchestrator(engine, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        val result = orchestrator.speak(question("q_mixed", text = "مع epidural hematoma مع"))
        assertEquals(SpeechResult.Completed, result)
        assertTrue(engine.spoken.size >= 2)
        val locales = engine.spoken.map { it.locale.language }
        assertEquals("ar", locales.first())
        assertTrue("en" in locales)
        orchestrator.release()
    }

    @Test
    fun `medical pronunciation applies to english segments only`() = runTest {
        val engine = FakeTtsEngineAdapter()
        engine.markReady()
        val orchestrator = newOrchestrator(engine, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        orchestrator.speak(question("q_med", text = "GCS 15/15"))
        assertTrue(engine.spoken.first().text.contains("G C S"))

        orchestrator.speak(question("q_ar", text = "درجة 15"))
        assertTrue(engine.spoken.last().text.contains("درجة 15"))
        orchestrator.release()
    }

    @Test
    fun `rate is clamped and NaN never reaches the engine`() = runTest {
        val engine = FakeTtsEngineAdapter()
        engine.markReady()
        val orchestrator = newOrchestrator(engine, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        orchestrator.updateSettings(TtsSettings(questionRate = Float.NaN, feedbackRate = 99f))
        advanceUntilIdle()
        orchestrator.speak(question("q_rate"))

        val utterance = engine.spoken.first()
        assertTrue(utterance.rate in 0.5f..2.0f)
        assertEquals(1.0f, utterance.rate) // NaN → default
        orchestrator.release()
    }

    @Test
    fun `engine switch is forwarded to the engine adapter`() = runTest {
        val engine = FakeTtsEngineAdapter()
        engine.markReady()
        val orchestrator = newOrchestrator(engine, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        orchestrator.updateSettings(TtsSettings(engineId = "com.fake.engine"))
        advanceUntilIdle()
        assertTrue(engine.setEngineCalls.contains("com.fake.engine"))
        orchestrator.release()
    }

    @Test
    fun `arabic preview forces arabic locale`() = runTest {
        val engine = FakeTtsEngineAdapter()
        engine.markReady()
        val orchestrator = newOrchestrator(engine, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        val result = orchestrator.speakPreview(SegmentLanguage.ARABIC)
        assertEquals(SpeechResult.Completed, result)
        assertEquals("ar", engine.spoken.first().locale.language)
        orchestrator.release()
    }
}
