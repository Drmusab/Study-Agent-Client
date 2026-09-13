package com.studyagent.client.core.diagnostics

import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.SystemAppClock

/**
 * Latency distribution for one measured family (§53-§54).
 *
 * `count`/`min`/`average`/`p50`/`p95`/`max`, plus how many samples the *window* still holds.
 * An average alone hides exactly the spikes users notice, so the percentiles are computed from a
 * bounded recent window rather than from every sample ever taken — the storage cost is fixed at
 * [LatencyWindow.windowSize] longs no matter how long the session runs.
 *
 * A family that was never measured reports `-` ([DiagnosticsFormatting.NOT_MEASURED]), never
 * `0ms`. Reporting zero for "unknown" is the difference between a useful diagnostic and a lie
 * (§66).
 */
data class LatencyStats(
    val name: String = "",
    /** How many samples have been recorded since start (may exceed the window). */
    val lifetimeSamples: Long = 0L,
    /** Samples currently in the bounded window. */
    val windowSamples: Int = 0,
    val minMs: Long = -1L,
    val averageMs: Long = -1L,
    val p50Ms: Long = -1L,
    val p95Ms: Long = -1L,
    val maxMs: Long = -1L
) {
    val measured: Boolean get() = lifetimeSamples > 0L && windowSamples > 0

    /** Compact, honest rendering: `p50=82ms p95=210ms n=427`. */
    fun format(): String {
        if (!measured) return DiagnosticsFormatting.NOT_MEASURED
        return "p50=${p50Ms}ms p95=${p95Ms}ms (n=$lifetimeSamples)"
    }

    /** `82ms` style single-value label for the summary block. */
    fun p50Label(): String = if (!measured) DiagnosticsFormatting.NOT_MEASURED else "${p50Ms}ms"
}

/**
 * Fixed-size rolling window with lifetime counters (§54).
 *
 * Thread-safe via a short monitor: recording is a few array writes, and reading sorts at most
 * [windowSize] values. Both are cheap enough to run on the voice path — the metric must never
 * become the bottleneck it is measuring (§55).
 */
class LatencyWindow(
    val name: String,
    val windowSize: Int = DEFAULT_WINDOW_SIZE
) {
    private val lock = Any()
    private val samples = LongArray(windowSize.coerceAtLeast(1))
    private var nextIndex = 0
    private var filled = 0
    private var lifetimeCount = 0L
    private var lifetimeSum = 0L

    init {
        require(windowSize > 0) { "windowSize must be positive" }
    }

    /** Records one sample. Negative values are treated as 0 (a clock step must not poison p95). */
    fun record(millis: Long) {
        val value = if (millis < 0L) 0L else millis
        synchronized(lock) {
            samples[nextIndex] = value
            nextIndex = (nextIndex + 1) % windowSize
            if (filled < windowSize) filled++
            lifetimeCount++
            lifetimeSum += value
        }
    }

    fun stats(): LatencyStats {
        synchronized(lock) {
            if (filled == 0 || lifetimeCount == 0L) return LatencyStats(name = name)
            val copy = LongArray(filled)
            System.arraycopy(samples, 0, copy, 0, filled)
            copy.sort()
            return LatencyStats(
                name = name,
                lifetimeSamples = lifetimeCount,
                windowSamples = filled,
                minMs = copy.first(),
                averageMs = lifetimeSum / lifetimeCount,
                p50Ms = percentile(copy, 0.50),
                p95Ms = percentile(copy, 0.95),
                maxMs = copy.last()
            )
        }
    }

    val samplesRecorded: Long get() = synchronized(lock) { lifetimeCount }

    fun reset() {
        synchronized(lock) {
            samples.fill(0L)
            nextIndex = 0
            filled = 0
            lifetimeCount = 0L
            lifetimeSum = 0L
        }
    }

    companion object {
        /** Big enough for a stable p95, small enough to be irrelevant to memory. */
        const val DEFAULT_WINDOW_SIZE = 128

        /** Nearest-rank percentile on a sorted array. */
        internal fun percentile(sorted: LongArray, fraction: Double): Long {
            if (sorted.isEmpty()) return -1L
            val rank = Math.ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}

/** Heap/process memory sample. Fields are null when the platform cannot report them safely. */
data class MemorySample(
    val usedHeapBytes: Long? = null,
    val maxHeapBytes: Long? = null,
    /** Total PSS, only on Android and only when asked for (§65). */
    val pssBytes: Long? = null,
    val atMs: Long = 0L
)

/** Injectable so the diagnostics layer can be unit-tested without an Android runtime. */
fun interface MemoryProbe {
    fun sample(atMs: Long): MemorySample
}

/** JVM-safe default: heap numbers from `Runtime`, no PSS. */
object RuntimeMemoryProbe : MemoryProbe {
    override fun sample(atMs: Long): MemorySample {
        val runtime = Runtime.getRuntime()
        return MemorySample(
            usedHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
            maxHeapBytes = runtime.maxMemory(),
            pssBytes = null,
            atMs = atMs
        )
    }
}

// ---------------------------------------------------------------------------- snapshots

data class SessionPerformance(
    /** Card turns started (question received). */
    val turns: Long = 0L,
    val answersSubmitted: Long = 0L,
    val ratingsSubmitted: Long = 0L,
    /** Start tapped → `start_session` written to the transport. */
    val startToRequest: LatencyStats = LatencyStats("start→request"),
    /** Question received → TTS actually began. */
    val questionToSpeechStart: LatencyStats = LatencyStats("question→speech"),
    /** Utterance terminal → microphone open (the handoff, including the acoustic gap). */
    val speechDoneToListen: LatencyStats = LatencyStats("speech→listen"),
    /** `onEndOfSpeech` → terminal transcript. */
    val sttFinalize: LatencyStats = LatencyStats("stt finalize"),
    /** Answer written to the transport → evaluation received. */
    val evaluationRoundTrip: LatencyStats = LatencyStats("evaluation RTT"),
    /** Rating written to the transport → next question received. */
    val ratingToNextQuestion: LatencyStats = LatencyStats("rating→next"),
    val sessionDurationMs: Long = -1L
)

data class TtsPerformance(
    val requestsCompleted: Long = 0L,
    val requestsFailed: Long = 0L,
    val requestsCancelled: Long = 0L,
    val queueDepth: Int = 0,
    val lastRequestToStartMs: Long = -1L
) {
    val totalAttempted: Long get() = requestsCompleted + requestsFailed + requestsCancelled
}

data class SttPerformance(
    val turnsCompleted: Long = 0L,
    val turnsFailed: Long = 0L,
    val noSpeech: Long = 0L,
    val noMatch: Long = 0L,
    val busy: Long = 0L,
    val rateLimited: Long = 0L,
    val staleCallbacksDropped: Long = 0L,
    val activeRequests: Int = 0,
    val lastReadyLatencyMs: Long = -1L,
    val lastFinalizeLatencyMs: Long = -1L
)

data class NetworkPerformance(
    val messagesSent: Long = 0L,
    val messagesReceived: Long = 0L,
    val sendFailures: Long = 0L,
    val reconnects: Long = 0L,
    val lastPingRttMs: Long = -1L,
    val lastMessageAgeMs: Long = -1L,
    val messagesPerMinute: Double = -1.0
)

data class AudioPerformance(
    val handoff: LatencyStats = LatencyStats("handoff"),
    val routeInterruptions: Long = 0L,
    val suspectedSelfEcho: Long = 0L,
    val phoneTurns: Long = 0L,
    val headsetTurns: Long = 0L
)

data class RuntimePerformance(
    val usedHeapBytes: Long? = null,
    val maxHeapBytes: Long? = null,
    /** Highest heap value observed across diagnostics samples — "peak during session" (§65). */
    val peakUsedHeapBytes: Long? = null,
    val pssBytes: Long? = null,
    val sampledAtMs: Long = -1L
)

/** The structured performance view Diagnostics renders (§57). One object, no scattered counters. */
data class PerformanceSnapshot(
    val session: SessionPerformance = SessionPerformance(),
    val tts: TtsPerformance = TtsPerformance(),
    val stt: SttPerformance = SttPerformance(),
    val network: NetworkPerformance = NetworkPerformance(),
    val audio: AudioPerformance = AudioPerformance(),
    val memory: RuntimePerformance = RuntimePerformance()
)

/**
 * Bounded, local, cheap technical metrics for study sessions (§55-§57/§108-§110/§162-§165).
 *
 * Scope discipline: these are *technical* numbers — how long the app took, how often a pipe
 * failed. Learning metrics (recall rate, scheduling) belong to the PC agent and the Dashboard;
 * this object never claims to measure how well somebody is studying (§163).
 *
 * Everything here is stored in fixed-size windows and plain counters, so the object's footprint
 * is constant regardless of session length, and nothing is persisted or transmitted (§89/§161).
 */
class PerformanceMetrics(
    private val clock: AppClock = SystemAppClock,
    memoryProbe: MemoryProbe = RuntimeMemoryProbe
) {
    private val lock = Any()

    /**
     * Where memory numbers come from.
     *
     * Settable because the *app* can report PSS (`Debug.MemoryInfo`) while the JVM unit tests must
     * not touch `android.os` at all. The default stays the runtime-only probe, so a test that
     * forgets to replace it still gets honest (if less detailed) numbers instead of an exception.
     */
    @Volatile
    var memoryProbe: MemoryProbe = memoryProbe

    private val questionToSpeechStart = LatencyWindow("question→speech")
    private val speechDoneToListen = LatencyWindow("speech→listen")
    private val sttFinalizeWindow = LatencyWindow("stt-finalize")
    private val evaluationRoundTrip = LatencyWindow("evaluation-rtt")
    private val ratingToNextQuestion = LatencyWindow("rating→next")
    private val startToRequest = LatencyWindow("start→request")
    private val handoff = LatencyWindow("handoff")

    private var turns = 0L
    private var answersSubmitted = 0L
    private var ratingsSubmitted = 0L
    private var sessionStartedAtMs = -1L
    private var sessionFinishedAtMs = -1L

    private var ttsCompleted = 0L
    private var ttsFailed = 0L
    private var ttsCancelled = 0L
    private var ttsQueueDepth = 0
    private var ttsLastRequestToStartMs = -1L

    private var sttCompleted = 0L
    private var sttFailed = 0L
    private var sttNoSpeech = 0L
    private var sttNoMatch = 0L
    private var sttBusy = 0L
    private var sttRateLimited = 0L
    private var sttStaleDropped = 0L
    private var sttActive = 0
    private var sttLastReadyMs = -1L
    private var sttLastFinalizeMs = -1L

    private var peakUsedHeapBytes = -1L
    private var routeInterruptions = 0L
    private var suspectedSelfEcho = 0L
    private var phoneTurns = 0L
    private var headsetTurns = 0L

    /**
     * Highest value of the STT orchestrator's own "stale callbacks dropped" counter that has
     * already been mirrored here.
     *
     * The orchestrator owns the counter; the machine only samples it when a turn terminates, so
     * this watermark prevents double counting. Volatile because the sampler runs on the machine
     * coroutine while `reset()` may be called from a test thread.
     */
    @Volatile
    var staleCallbackWatermark: Long = 0L

    // ------------------------------------------------------------------ session

    fun onSessionStarted() {
        synchronized(lock) {
            sessionStartedAtMs = clock.nowMillis()
            sessionFinishedAtMs = -1L
        }
    }

    fun onSessionFinished() {
        synchronized(lock) {
            if (sessionStartedAtMs > 0L) sessionFinishedAtMs = clock.nowMillis()
        }
    }

    fun onTurnStarted() {
        synchronized(lock) { turns++ }
    }

    fun onAnswerSubmitted() {
        synchronized(lock) { answersSubmitted++ }
    }

    fun onRatingSubmitted() {
        synchronized(lock) { ratingsSubmitted++ }
    }

    fun recordStartToRequest(millis: Long) = startToRequest.record(millis)

    fun recordQuestionToSpeechStart(millis: Long) = questionToSpeechStart.record(millis)

    fun recordSpeechDoneToListen(millis: Long) {
        speechDoneToListen.record(millis)
        handoff.record(millis)
    }

    fun recordSttFinalize(millis: Long) {
        if (millis < 0L) return
        sttFinalizeWindow.record(millis)
        synchronized(lock) { sttLastFinalizeMs = millis }
    }

    fun recordEvaluationRoundTrip(millis: Long) = evaluationRoundTrip.record(millis)

    fun recordRatingToNextQuestion(millis: Long) = ratingToNextQuestion.record(millis)

    // ------------------------------------------------------------------ voice

    fun onTtsCompleted() {
        synchronized(lock) { ttsCompleted++ }
    }

    fun onTtsFailed() {
        synchronized(lock) { ttsFailed++ }
    }

    fun onTtsCancelled() {
        synchronized(lock) { ttsCancelled++ }
    }

    fun onTtsQueueDepth(depth: Int) {
        synchronized(lock) { ttsQueueDepth = depth }
    }

    fun onTtsRequestToStart(millis: Long) {
        synchronized(lock) { ttsLastRequestToStartMs = millis }
    }

    fun onSttCompleted() {
        synchronized(lock) { sttCompleted++ }
    }

    fun onSttFailed() {
        synchronized(lock) { sttFailed++ }
    }

    fun onSttNoSpeech() {
        synchronized(lock) { sttNoSpeech++ }
    }

    fun onSttNoMatch() {
        synchronized(lock) { sttNoMatch++ }
    }

    fun onSttBusy() {
        synchronized(lock) { sttBusy++ }
    }

    fun onSttRateLimited() {
        synchronized(lock) { sttRateLimited++ }
    }

    fun onSttStaleCallbackDropped() {
        synchronized(lock) { sttStaleDropped++ }
    }

    fun onSttActiveRequests(count: Int) {
        synchronized(lock) { sttActive = count }
    }

    fun onSttReady(latencyMs: Long) {
        if (latencyMs >= 0L) synchronized(lock) { sttLastReadyMs = latencyMs }
    }

    // ------------------------------------------------------------------ audio

    fun onRouteInterruption() {
        synchronized(lock) { routeInterruptions++ }
    }

    fun onSuspectedSelfEcho() {
        synchronized(lock) { suspectedSelfEcho++ }
    }

    fun onListenTurnOnPhone() {
        synchronized(lock) { phoneTurns++ }
    }

    fun onListenTurnOnHeadset() {
        synchronized(lock) { headsetTurns++ }
    }

    // ------------------------------------------------------------------ snapshot

    fun snapshot(): PerformanceSnapshot {
        val memory = try {
            memoryProbe.sample(clock.nowMillis())
        } catch (_: Throwable) {
            MemorySample(atMs = -1L)
        }
        synchronized(lock) {
            val usedHeap = memory.usedHeapBytes
            if (usedHeap != null && usedHeap > peakUsedHeapBytes) peakUsedHeapBytes = usedHeap
            val duration = when {
                sessionStartedAtMs <= 0L -> -1L
                sessionFinishedAtMs > 0L -> sessionFinishedAtMs - sessionStartedAtMs
                else -> clock.nowMillis() - sessionStartedAtMs
            }
            return PerformanceSnapshot(
                session = SessionPerformance(
                    turns = turns,
                    answersSubmitted = answersSubmitted,
                    ratingsSubmitted = ratingsSubmitted,
                    startToRequest = startToRequest.stats(),
                    questionToSpeechStart = questionToSpeechStart.stats(),
                    speechDoneToListen = speechDoneToListen.stats(),
                    sttFinalize = sttFinalizeWindow.stats(),
                    evaluationRoundTrip = evaluationRoundTrip.stats(),
                    ratingToNextQuestion = ratingToNextQuestion.stats(),
                    sessionDurationMs = duration
                ),
                tts = TtsPerformance(
                    requestsCompleted = ttsCompleted,
                    requestsFailed = ttsFailed,
                    requestsCancelled = ttsCancelled,
                    queueDepth = ttsQueueDepth,
                    lastRequestToStartMs = ttsLastRequestToStartMs
                ),
                stt = SttPerformance(
                    turnsCompleted = sttCompleted,
                    turnsFailed = sttFailed,
                    noSpeech = sttNoSpeech,
                    noMatch = sttNoMatch,
                    busy = sttBusy,
                    rateLimited = sttRateLimited,
                    staleCallbacksDropped = sttStaleDropped,
                    activeRequests = sttActive,
                    lastReadyLatencyMs = sttLastReadyMs,
                    lastFinalizeLatencyMs = sttLastFinalizeMs
                ),
                audio = AudioPerformance(
                    handoff = handoff.stats(),
                    routeInterruptions = routeInterruptions,
                    suspectedSelfEcho = suspectedSelfEcho,
                    phoneTurns = phoneTurns,
                    headsetTurns = headsetTurns
                ),
                memory = RuntimePerformance(
                    usedHeapBytes = memory.usedHeapBytes,
                    maxHeapBytes = memory.maxHeapBytes,
                    peakUsedHeapBytes = if (peakUsedHeapBytes >= 0L) peakUsedHeapBytes else null,
                    pssBytes = memory.pssBytes,
                    sampledAtMs = memory.atMs
                )
            )
        }
    }

    /** Clears every counter and window. Lifetime-vs-session semantics are explicit here (§161). */
    fun reset() {
        questionToSpeechStart.reset()
        speechDoneToListen.reset()
        sttFinalizeWindow.reset()
        evaluationRoundTrip.reset()
        ratingToNextQuestion.reset()
        startToRequest.reset()
        handoff.reset()
        synchronized(lock) {
            turns = 0L
            answersSubmitted = 0L
            ratingsSubmitted = 0L
            sessionStartedAtMs = -1L
            sessionFinishedAtMs = -1L
            ttsCompleted = 0L
            ttsFailed = 0L
            ttsCancelled = 0L
            ttsQueueDepth = 0
            ttsLastRequestToStartMs = -1L
            sttCompleted = 0L
            sttFailed = 0L
            sttNoSpeech = 0L
            sttNoMatch = 0L
            sttBusy = 0L
            sttRateLimited = 0L
            sttStaleDropped = 0L
            sttActive = 0
            sttLastReadyMs = -1L
            sttLastFinalizeMs = -1L
            staleCallbackWatermark = 0L
            routeInterruptions = 0L
            peakUsedHeapBytes = -1L
            suspectedSelfEcho = 0L
            phoneTurns = 0L
            headsetTurns = 0L
        }
    }
}

/** Process-lifetime holder, mirroring [AppDiagnostics]. Tests inject their own instance. */
object AppPerformanceMetrics {
    val metrics = PerformanceMetrics()

    fun resetForTests() {
        metrics.reset()
    }
}
