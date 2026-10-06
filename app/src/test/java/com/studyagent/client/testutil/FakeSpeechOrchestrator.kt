package com.studyagent.client.testutil

import com.studyagent.client.core.voice.tts.EngineStatus
import com.studyagent.client.core.voice.tts.SegmentLanguage
import com.studyagent.client.core.voice.tts.SpeechError
import com.studyagent.client.core.voice.tts.SpeechErrorCode
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Programmable speech double (§12/§13).
 *
 * Covers the states the real pipeline can be in — completing, failing, cancelled, parked
 * (long-running), queued behind another utterance — with virtual-time durations, and records what
 * was actually spoken so the half-duplex invariant can be asserted from the *executed* order.
 *
 * It deliberately does not implement queueing policy, chunking or voice selection: those are
 * production behaviours and are already covered against the real orchestrator. This fake exists
 * to drive the *study machine*.
 */
class FakeSpeechOrchestrator(
    private val clock: () -> Long = System::currentTimeMillis,
    /** Virtual-time length of an utterance. 0 = returns immediately. */
    var speakDurationMs: Long = 0L,
    var mode: Mode = Mode.COMPLETE,
    var failWith: SpeechError = SpeechError(SpeechErrorCode.PLAYBACK_ERROR, "fake playback failure")
) : SpeechOrchestrator {

    enum class Mode {
        /** Returns [SpeechResult.Completed] after [speakDurationMs]. */
        COMPLETE,

        /** Returns [SpeechResult.Failed] after [speakDurationMs]. */
        FAIL,

        /** Parks until [completeNext]/[failNext]/[stopSpeech]/[release]. */
        PARK
    }

    data class Spoken(
        val request: SpeechRequest,
        val startedAtMs: Long,
        val finishedAtMs: Long,
        val result: SpeechResult
    )

    private val _health = MutableStateFlow(TtsHealthSnapshot(engineStatus = EngineStatus.READY))
    override val health: StateFlow<TtsHealthSnapshot> = _health

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _ttsState = MutableStateFlow<TtsState>(TtsState.Ready())
    override val ttsState: StateFlow<TtsState> = _ttsState

    private val _isReady = MutableStateFlow(true)
    override val isReady: StateFlow<Boolean> = _isReady

    /** Completed utterances, in completion order. */
    val spoken: MutableList<Spoken> = mutableListOf()

    /** Requests for which [speak] was *entered* — includes utterances still in flight. */
    val started: MutableList<SpeechRequest> = mutableListOf()

    val stopReasons: MutableList<StopReason> = mutableListOf()
    var stopCount: Int = 0
        private set
    var releaseCount: Int = 0
        private set
    var settingsUpdates: Int = 0
        private set

    /** Concurrently-speaking count; > 1 while parked utterances pile up (queue simulation). */
    private var inFlight = 0

    private val parked = LinkedHashMap<String, CompletableDeferred<SpeechResult>>()

    /** Parked (long-running) requests — a leak detector for the cancel path. */
    val pendingCount: Int get() = parked.size

    /** Invoked as speech starts/finishes; the harness uses this to detect TTS/STT overlap. */
    var onSpeakStart: ((SpeechRequest) -> Unit)? = null
    var onSpeakEnd: ((SpeechRequest, SpeechResult) -> Unit)? = null

    override suspend fun speak(request: SpeechRequest): SpeechResult {
        // PARK models an engine that accepted the request but has not started voicing it: the
        // utterance sits in the engine's queue until the test releases it with completeNext().
        // A parked request therefore does NOT claim the voice channel — no started/spoken
        // bookkeeping, no speaking flag, no overlap window — and on release it runs to its
        // result immediately (off-screen), which is what the manual drivers have always
        // expected. This keeps half-duplex observations honest: a parked next-question and an
        // open microphone are not an overlap, and a caller observing the harness right after a
        // rating commit sees a clean voice timeline.
        if (mode == Mode.PARK) {
            val gate = CompletableDeferred<SpeechResult>()
            parked[request.id] = gate
            val parkedResult = try {
                gate.await()
            } finally {
                parked.remove(request.id)
            }
            val releasedAt = clock()
            started += request
            spoken += Spoken(request, releasedAt, clock(), parkedResult)
            onSpeakStart?.invoke(request)
            onSpeakEnd?.invoke(request, parkedResult)
            return parkedResult
        }
        started += request
        inFlight++
        _isSpeaking.value = true
        _health.value = _health.value.copy(
            speakingPurpose = request.purpose,
            queueDepth = (inFlight - 1).coerceAtLeast(0)
        )
        onSpeakStart?.invoke(request)
        val startedAt = clock()

        val result = try {
            when (mode) {
                Mode.COMPLETE -> {
                    if (speakDurationMs > 0L) delay(speakDurationMs)
                    SpeechResult.Completed
                }

                Mode.FAIL -> {
                    if (speakDurationMs > 0L) delay(speakDurationMs)
                    SpeechResult.Failed(failWith)
                }

                Mode.PARK -> SpeechResult.Completed // unreachable: parked requests return above
            }
        } finally {
            inFlight = (inFlight - 1).coerceAtLeast(0)
            if (inFlight == 0) {
                _isSpeaking.value = false
                _health.value = _health.value.copy(speakingPurpose = null, queueDepth = 0)
            }
        }

        spoken += Spoken(request, startedAt, clock(), result)
        onSpeakEnd?.invoke(request, result)
        return result
    }

    override suspend fun speakPreview(language: SegmentLanguage): SpeechResult = SpeechResult.Completed

    override fun stopSpeech(reason: StopReason) {
        stopCount++
        stopReasons += reason
        completeAllParked(SpeechResult.Cancelled)
        _isSpeaking.value = false
        _health.value = _health.value.copy(speakingPurpose = null, queueDepth = 0)
    }

    override suspend fun getVoices(languageCode: String): List<TtsVoiceInfo> = emptyList()

    override suspend fun getEngines(): List<TtsEngineInfo> = emptyList()

    override fun updateSettings(settings: TtsSettings) {
        settingsUpdates++
    }

    override fun release() {
        releaseCount++
        completeAllParked(SpeechResult.Cancelled)
        _isSpeaking.value = false
        _ttsState.value = TtsState.Released
    }

    // ------------------------------------------------------------------ manual drivers

    /** Completes the oldest parked utterance with [result]. */
    fun completeNext(result: SpeechResult = SpeechResult.Completed) {
        val first = parked.entries.firstOrNull() ?: return
        first.value.complete(result)
    }

    fun failNext(error: SpeechError = failWith) {
        completeNext(SpeechResult.Failed(error))
    }

    private fun completeAllParked(result: SpeechResult) {
        val all = parked.values.toList()
        parked.clear()
        all.forEach { it.complete(result) }
    }

    // ------------------------------------------------------------------ assertions helpers

    fun spokenTexts(): List<String> = spoken.map { it.request.text }

    fun spokenPurposes(): List<com.studyagent.client.core.voice.tts.SpeechPurpose> =
        spoken.map { it.request.purpose }

    /** True while an utterance has started but not yet produced a terminal result. */
    val isActivelySpeaking: Boolean get() = _isSpeaking.value
}
