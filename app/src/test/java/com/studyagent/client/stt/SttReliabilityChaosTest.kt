package com.studyagent.client.stt

import com.studyagent.client.core.voice.stt.DefaultSpeechRecognitionOrchestrator
import com.studyagent.client.core.voice.stt.RecognitionErrorCode
import com.studyagent.client.core.voice.stt.RecognitionHypothesis
import com.studyagent.client.core.voice.stt.RecognitionPolicyFactory
import com.studyagent.client.core.voice.stt.RecognitionPurpose
import com.studyagent.client.core.voice.stt.RecognitionRequest
import com.studyagent.client.core.voice.stt.RecognitionStartResult
import com.studyagent.client.core.voice.stt.RecognitionState
import com.studyagent.client.core.voice.stt.RecognitionTurnResult
import com.studyagent.client.core.voice.stt.SttSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

/**
 * Chaos-suite for the recognition orchestrator (§72/§78–§80/§87/§91).
 *
 * These tests deliberately feed the orchestrator the callback sequences *buggy* recognizer
 * services produce — duplicate terminals, errors after results, results after cancellation,
 * late partials, endless no-speech — and assert that the state machine's authority wins
 * over callback ordering, every time, for a thousand consecutive turns.
 */
class SttReliabilityChaosTest {

    private val baseSettings = SttSettings(minTurnIntervalMs = 0L, maxRetriesPerTurn = 0)

    private class Fixture(
        val backend: FakeSpeechRecognitionBackend,
        val orchestrator: DefaultSpeechRecognitionOrchestrator,
        val results: MutableList<RecognitionTurnResult>,
        val collector: Job
    )

    private fun TestScope.fixture(
        settings: SttSettings = baseSettings
    ): Fixture {
        val backend = FakeSpeechRecognitionBackend()
        val orchestrator = DefaultSpeechRecognitionOrchestrator(
            backend = backend,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            clock = { testScheduler.currentTime }
        )
        orchestrator.updateSettings(settings)

        val results = Collections.synchronizedList(mutableListOf<RecognitionTurnResult>())
        val collector = CoroutineScope(UnconfinedTestDispatcher(testScheduler)).launch {
            orchestrator.turnResults.collect { results += it }
        }
        runCurrent()
        return Fixture(backend, orchestrator, results, collector)
    }

    private fun request(purpose: RecognitionPurpose = RecognitionPurpose.ANSWER): RecognitionRequest =
        RecognitionPolicyFactory(idFactory = RecognitionPolicyFactory.counterIdFactory())
            .createRequest(purpose, baseSettings)

    private fun RecognitionStartResult.asRejected() = this as RecognitionStartResult.Rejected
    private fun RecognitionTurnResult.asCompleted() = this as RecognitionTurnResult.Completed
    private fun RecognitionTurnResult.asFailed() = this as RecognitionTurnResult.Failed

    // ---------------------------------------------------------- §78 duplicate final

    @Test
    fun `a duplicate final callback produces exactly one submission`() = runTest {
        val f = fixture()
        val id = startAndHear(f)

        // A buggy service emits the terminal twice.
        f.backend.emitRawResults(id, listOf(RecognitionHypothesis("the answer", 0.9f, 0)))
        runCurrent()
        f.backend.emitRawResults(id, listOf(RecognitionHypothesis("the answer", 0.9f, 0)))
        runCurrent()

        assertEquals("second terminal for the same request must be dropped", 1, f.results.size)
        assertTrue(f.results.single() is RecognitionTurnResult.Completed)
        assertTrue(f.orchestrator.health.value.metrics.staleCallbacksDropped >= 1)
        f.collector.cancel()
    }

    // ---------------------------------------------------------- §79 final then error

    @Test
    fun `an error after a final result cannot override it`() = runTest {
        val f = fixture()
        val id = startAndHear(f)

        f.backend.emitFinal("the authoritative answer")
        runCurrent()
        f.backend.emitRawError(id, RecognitionErrorCode.BUSY)
        f.backend.emitRawError(id, RecognitionErrorCode.AUDIO_FAILURE)
        runCurrent()

        assertEquals(1, f.results.size)
        val terminal = f.results.single()
        assertTrue("final stays authoritative", terminal is RecognitionTurnResult.Completed)
        assertEquals("the authoritative answer", terminal.asCompleted().outcome.selectedText)
        assertTrue(f.orchestrator.state.value is RecognitionState.Completed)
        assertFalse(f.backend.isBusy)
        f.collector.cancel()
    }

    // ---------------------------------------------------------- §80 cancel then final

    @Test
    fun `after cancellation neither a final result nor an error may resurrect the turn`() = runTest {
        val f = fixture()
        val id = f.orchestrator.startRecognition(request()).let { (it as RecognitionStartResult.Started).requestId }
        f.backend.emitReadyAndSpeech()

        f.orchestrator.cancelCurrentTurn("new-question")
        runCurrent()

        // Everything the recognizer still owes for the dead request arrives late.
        f.backend.emitRawResults(id, listOf(RecognitionHypothesis("late answer", 0.95f, 0)))
        f.backend.emitRawError(id, RecognitionErrorCode.BUSY)
        f.backend.emitRawPartial(id, "late partial")
        runCurrent()

        assertTrue(
            "no terminal may be delivered for a cancelled request",
            f.results.isEmpty()
        )
        assertEquals("transcript must stay clear", "", f.orchestrator.partialTranscript.value)
        assertEquals(RecognitionState.Idle, f.orchestrator.state.value)
        assertTrue(f.orchestrator.state.value.isReadyForNewRequest)
        f.collector.cancel()
    }

    // ---------------------------------------------------------- §57 late partials

    @Test
    fun `a late partial after the final result cannot make the transcript regress`() = runTest {
        val f = fixture()
        val id = startAndHear(f)
        f.backend.emitFinal("full and final answer")
        runCurrent()

        assertEquals(1, f.results.size)
        assertEquals("transcript cleared on completion", "", f.orchestrator.partialTranscript.value)

        f.backend.emitRawPartial(id, "stale earlier partial")
        f.backend.emitRawPartial(id, "even earlier partial")
        runCurrent()

        assertEquals("UI transcript must not regress", "", f.orchestrator.partialTranscript.value)
        f.collector.cancel()
    }

    // ---------------------------------------------------------- §87 no-speech retry bound

    @Test
    fun `no speech retries exactly once and then reports a recoverable failure`() = runTest {
        val settings = SttSettings(minTurnIntervalMs = 0L, maxRetriesPerTurn = 5)
        val f = fixture(settings = settings)

        f.orchestrator.startRecognition(request())
        f.backend.emitError(RecognitionErrorCode.NO_SPEECH)
        runCurrent()
        // The policy allows exactly one silent-room retry, regardless of the larger budget.
        assertEquals(2, f.backend.startedRequests.size)
        assertTrue(f.backend.isBusy)

        f.backend.emitError(RecognitionErrorCode.NO_SPEECH)
        runCurrent()

        assertEquals("retry budget for NO_SPEECH is one", 2, f.backend.startedRequests.size)
        assertEquals(1, f.results.size)
        assertEquals(RecognitionErrorCode.NO_SPEECH, f.results.single().asFailed().error.code)
        assertFalse(f.backend.isBusy)
        assertTrue(f.orchestrator.state.value.isReadyForNewRequest)
        f.collector.cancel()
    }

    // ---------------------------------------------------------- §87/§50 no match retry bound

    @Test
    fun `no match retries exactly once and then surfaces the failure`() = runTest {
        val settings = SttSettings(minTurnIntervalMs = 0L, maxRetriesPerTurn = 5)
        val f = fixture(settings = settings)

        f.orchestrator.startRecognition(request())
        f.backend.emitReadyAndSpeech()
        f.backend.emitError(RecognitionErrorCode.NO_MATCH)
        runCurrent()
        assertEquals(2, f.backend.startedRequests.size)

        f.backend.emitReadyAndSpeech()
        f.backend.emitError(RecognitionErrorCode.NO_MATCH)
        runCurrent()

        assertEquals(2, f.backend.startedRequests.size)
        assertEquals(1, f.results.size)
        assertEquals(RecognitionErrorCode.NO_MATCH, f.results.single().asFailed().error.code)
        f.collector.cancel()
    }

    // ---------------------------------------------------------- §62/§77 concurrent access

    @Test
    fun `a hundred concurrent starts cannot open a second turn while one is active`() {
        val backend = FakeSpeechRecognitionBackend()
        val orchestrator = DefaultSpeechRecognitionOrchestrator(
            backend = backend,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )
        orchestrator.updateSettings(baseSettings)

        runBlocking {
            assertTrue(orchestrator.startRecognition(request()) is RecognitionStartResult.Started)

            val threads = 8
            val perThread = 100
            val barrier = CyclicBarrier(threads)
            val rejected = AtomicInteger(0)
            val accepted = AtomicInteger(0)
            val errors = AtomicInteger(0)
            val latch = CountDownLatch(threads)

            repeat(threads) {
                Thread {
                    barrier.await()
                    repeat(perThread) {
                        try {
                            when (orchestrator.startRecognition(request())) {
                                is RecognitionStartResult.Started -> accepted.incrementAndGet()
                                is RecognitionStartResult.Rejected -> rejected.incrementAndGet()
                            }
                        } catch (t: Throwable) {
                            errors.incrementAndGet()
                        }
                    }
                    latch.countDown()
                }.start()
            }
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("all hammer threads finished", 0, latch.count)

            assertEquals("no start may slip past the busy gate", 0, accepted.get())
            assertEquals(threads * perThread, rejected.get())
            assertEquals("no thread may see an exception", 0, errors.get())
            assertEquals("exactly one request ever reached the recognizer", 1, backend.startedRequests.size)
        }

        orchestrator.cancelCurrentTurn("test-done")
        orchestrator.release()
    }

    // ---------------------------------------------------------- §91 long session

    @Test
    fun `one thousand turns with chaos injected stay exactly-once and leave no residue`() = runTest {
        val f = fixture()

        val totalTurns = 1000
        var expectedCompleted = 0
        var expectedFailed = 0
        var expectedCancelled = 0
        var injectedStale = 0
        val seenTerminalIds = Collections.synchronizedSet(LinkedHashSet<String>())

        repeat(totalTurns) { turn ->
            val start = f.orchestrator.startRecognition(request())
            if (start !is RecognitionStartResult.Started) {
                // Nothing else may be in flight — the loop always drains before continuing.
                throw AssertionError("turn $turn could not start: ${start.asRejected().error.code}")
            }
            val id = start.requestId
            f.backend.emitReady()
            f.backend.emitSpeechBegan()
            f.backend.emitPartial("partial $turn")

            when {
                turn % 5 == 0 -> {
                    // Cancel mid-turn, then deliver everything late: all of it must be void.
                    f.orchestrator.cancelCurrentTurn("chaos")
                    f.backend.emitRawResults(id, listOf(RecognitionHypothesis("late $turn", 0.9f, 0)))
                    f.backend.emitRawError(id, RecognitionErrorCode.BUSY)
                    expectedCancelled++
                    injectedStale += 2
                }
                turn % 7 == 0 -> {
                    f.backend.emitError(RecognitionErrorCode.NO_SPEECH)
                    expectedFailed++
                }
                else -> {
                    f.backend.emitFinal("answer $turn", 0.8f)
                    expectedCompleted++
                }
            }
            runCurrent()
        }

        val completed = f.results.filterIsInstance<RecognitionTurnResult.Completed>()
        val failed = f.results.filterIsInstance<RecognitionTurnResult.Failed>()

        assertEquals("every successful turn delivers exactly one result", expectedCompleted, completed.size)
        assertEquals("every exhausted turn delivers exactly one failure", expectedFailed, failed.size)
        assertEquals(expectedCompleted + expectedFailed, f.results.size)

        // No duplicate terminal request ids anywhere in the session.
        completed.forEach { assertTrue("no duplicate terminal", seenTerminalIds.add(it.outcome.requestId)) }
        failed.forEach { failure ->
            failure.error.requestId?.let { id ->
                assertTrue("no duplicate terminal", seenTerminalIds.add(id))
            }
        }

        val metrics = f.orchestrator.health.value.metrics
        assertEquals(expectedCompleted, metrics.completedTurns)
        assertEquals(expectedFailed, metrics.failedTurns)
        assertEquals(expectedCancelled, metrics.cancelledTurns)
        assertEquals("every injected late callback was counted", injectedStale, metrics.staleCallbacksDropped)

        // No residue after a thousand turns.
        assertEquals(totalTurns, f.backend.startedRequests.size)
        assertFalse(f.backend.isBusy)
        assertTrue(f.orchestrator.state.value.isReadyForNewRequest)
        assertEquals("", f.orchestrator.partialTranscript.value)
        assertEquals(-1L, f.orchestrator.health.value.activeRequestAgeMs)
        f.collector.cancel()
    }

    /** Start a turn, run it to speech, leaving the terminal callback to the caller. */
    private fun startAndHear(f: Fixture): String {
        val start = f.orchestrator.startRecognition(request())
        assertTrue(start is RecognitionStartResult.Started)
        val id = (start as RecognitionStartResult.Started).requestId
        f.backend.emitReadyAndSpeech()
        f.backend.emitPartial("half of it")
        return id
    }
}
