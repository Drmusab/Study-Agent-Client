package com.studyagent.client.testutil

import com.studyagent.client.core.voice.stt.RecognitionBackendEvent
import com.studyagent.client.core.voice.stt.RecognitionBackendKind
import com.studyagent.client.core.voice.stt.RecognitionCapabilities
import com.studyagent.client.core.voice.stt.RecognitionError
import com.studyagent.client.core.voice.stt.RecognitionErrorCode
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Programmable recognition double (§12/§13).
 *
 * Honours the two contracts the real orchestrator guarantees, because tests depend on them:
 *
 * 1. One turn at a time — a second [startRecognition] while a turn is live is rejected.
 * 2. A cancelled turn is dead — an automatic reply that fires after [cancelCurrentTurn] delivers
 *    nothing, exactly like a stale callback from the platform recognizer.
 *
 * Everything else is scripted: [autoRespondWith] decides what the "user" said per request, so the
 * answer text, the purpose, a no-speech failure and a late response are all one lambda away.
 */
class FakeRecognitionOrchestrator(
    private val scope: CoroutineScope? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Virtual-time delay before an automatic reply; models "the user takes a second". */
    var autoRespondDelayMs: Long = 0L,
    /**
     * Produces the automatic reply for a request, or null to stay silent so the test drives it
     * manually with [deliver]/[deliverNoSpeech]/[deliverFailure].
     */
    var autoRespondWith: (RecognitionRequest) -> RecognitionTurnResult? = { null }
) : SpeechRecognitionOrchestrator {

    private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    override val state: StateFlow<RecognitionState> = _state

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening

    private val _partialTranscript = MutableStateFlow("")
    override val partialTranscript: StateFlow<String> = _partialTranscript

    private val _audioLevel = MutableStateFlow(0f)
    override val audioLevel: StateFlow<Float> = _audioLevel

    private val _capabilities = MutableStateFlow(TestCapabilities.available())
    override val capabilities: StateFlow<RecognitionCapabilities> = _capabilities

    private val _health = MutableStateFlow(RecognitionHealthSnapshot())
    override val health: StateFlow<RecognitionHealthSnapshot> = _health

    private val _turnResults = MutableSharedFlow<RecognitionTurnResult>(extraBufferCapacity = 64)
    override val turnResults: SharedFlow<RecognitionTurnResult> = _turnResults

    private val _languageEvents =
        MutableSharedFlow<RecognitionBackendEvent.LanguageDetected>(extraBufferCapacity = 16)
    override val languageEvents: SharedFlow<RecognitionBackendEvent.LanguageDetected> = _languageEvents

    /** Every request that actually opened the microphone. */
    val started: MutableList<RecognitionRequest> = mutableListOf()

    /** Start attempts refused because a turn was already live. */
    var rejectedBusyCount: Int = 0
        private set

    val cancelledReasons: MutableList<String> = mutableListOf()
    var finishedTurns: Int = 0
        private set
    var releaseCount: Int = 0
        private set

    /**
     * Automatic replies that arrived for a turn that had already ended.
     *
     * This is the client's most dangerous callback class: the platform recognizer can deliver
     * `onResults` after the app cancelled a turn (pause, route change, card change). The double
     * drops them the way the real orchestrator must, and counts them so a test can assert
     * "0 stale results were honoured" instead of hoping (§24/§52).
     */
    var staleAutoRepliesDropped: Int = 0
        private set

    /** Results handed to [deliverStale]: deliberately injected late callbacks. */
    var injectedStaleCallbacks: Int = 0
        private set

    private var activeRequestId: String? = null
    private val pendingJobs = mutableListOf<Job>()

    /** Invoked as a turn starts; the harness uses this to detect TTS/STT overlap (§25). */
    var onStarted: ((RecognitionRequest) -> Unit)? = null

    /** Metrics reported through [health]; tests can set them to prove mirroring works. */
    var reportedFinalizeMs: Long = 120L
    var reportedReadyMs: Long = 40L
    var reportedStaleDrops: Int = 0

    override fun startRecognition(request: RecognitionRequest): RecognitionStartResult {
        if (activeRequestId != null) {
            rejectedBusyCount++
            return RecognitionStartResult.Rejected(
                RecognitionError(RecognitionErrorCode.BUSY, request.id)
            )
        }
        activeRequestId = request.id
        started += request
        onStarted?.invoke(request)
        _isListening.value = true
        _partialTranscript.value = ""
        _state.value = RecognitionState.Listening(request.id, request.purpose)
        publishHealth(request.purpose, request.id)

        val responder = autoRespondWith
        val collectorScope = scope
        if (collectorScope != null) {
            val delayMs = autoRespondDelayMs
            pendingJobs += collectorScope.launch {
                if (delayMs > 0L) delay(delayMs)
                // Late reply for a dead turn: dropped, exactly like the platform's stale callback.
                if (activeRequestId != request.id) {
                    staleAutoRepliesDropped++
                    return@launch
                }
                val reply = responder(request) ?: return@launch
                emitReply(request.id, reply)
            }
        }
        return RecognitionStartResult.Started(request.id, RecognitionBackendKind.SYSTEM)
    }

    override fun finishCurrentTurn() {
        finishedTurns++
        _isListening.value = false
    }

    override fun cancelCurrentTurn(reason: String) {
        cancelledReasons += reason
        activeRequestId = null
        _isListening.value = false
        _partialTranscript.value = ""
        _state.value = RecognitionState.Idle
    }

    override fun updateSettings(settings: SttSettings) = Unit

    override fun refreshCapabilities(): RecognitionCapabilities = _capabilities.value

    override fun requestModelDownload(languageTag: String): Boolean = false

    override fun release() {
        releaseCount++
        cancelCurrentTurn("release")
        pendingJobs.forEach { it.cancel() }
        pendingJobs.clear()
    }

    /**
     * Delivers a terminal result through the public flow, honouring the cancellation contract.
     * Returns false when the turn was already dead (the "late callback" case).
     */
    fun deliver(requestId: String, result: RecognitionTurnResult): Boolean {
        if (requestId != activeRequestId) return false
        return emitReply(requestId, result)
    }

    /** Convenience: a successful transcript for the active turn. */
    fun deliverAnswer(
        text: String,
        purpose: RecognitionPurpose = RecognitionPurpose.ANSWER,
        cardId: String? = null,
        confidence: Float? = 0.9f
    ): Boolean {
        val id = activeRequestId ?: return false
        val request = started.lastOrNull { it.id == id }
        return deliver(
            id,
            RecognitionTurnResult.Completed(
                TestTranscripts.outcome(
                    requestId = id,
                    purpose = request?.purpose ?: purpose,
                    cardId = cardId ?: request?.cardId,
                    text = text,
                    confidence = confidence
                )
            )
        )
    }

    fun deliverNoSpeech(): Boolean {
        val id = activeRequestId ?: return false
        return deliver(id, RecognitionTurnResult.Failed(TestTranscripts.error(RecognitionErrorCode.NO_SPEECH, id)))
    }

    fun deliverNoMatch(): Boolean {
        val id = activeRequestId ?: return false
        return deliver(id, RecognitionTurnResult.Failed(TestTranscripts.error(RecognitionErrorCode.NO_MATCH, id)))
    }

    fun deliverFailure(code: RecognitionErrorCode = RecognitionErrorCode.AUDIO_FAILURE): Boolean {
        val id = activeRequestId ?: return false
        return deliver(id, RecognitionTurnResult.Failed(RecognitionError(code, id)))
    }

    /** A stale result for a request that is no longer active — must be dropped. */
    fun deliverStale(requestId: String, text: String): Boolean {
        injectedStaleCallbacks++
        return tryEmit(
            RecognitionTurnResult.Completed(
                TestTranscripts.outcome(requestId = requestId, text = text)
            )
        )
    }

    val activeRequest: RecognitionRequest? get() = started.lastOrNull { it.id == activeRequestId }

    val hasActiveTurn: Boolean get() = activeRequestId != null

    /** Purposes for which the microphone has been opened, in order. */
    fun startedPurposes(): List<RecognitionPurpose> = started.map { it.purpose }

    fun setCapabilities(capabilities: RecognitionCapabilities) {
        _capabilities.value = capabilities
    }

    // ------------------------------------------------------------------ internals

    private fun emitReply(requestId: String, result: RecognitionTurnResult): Boolean {
        activeRequestId = null
        _isListening.value = false
        _partialTranscript.value = ""
        _state.value = RecognitionState.Idle
        reportedStaleDrops = _health.value.metrics.staleCallbacksDropped
        publishHealth(null, null)
        return tryEmit(result)
    }

    private fun tryEmit(result: RecognitionTurnResult): Boolean = _turnResults.tryEmit(result)

    private fun publishHealth(purpose: RecognitionPurpose?, requestId: String?) {
        val previous = _health.value
        val completed = if (purpose == null) previous.metrics.completedTurns + 1 else previous.metrics.completedTurns
        val metrics = previous.metrics.copy(
            completedTurns = completed,
            lastFinalizationMs = reportedFinalizeMs,
            avgFinalizationMs = reportedFinalizeMs,
            lastReadyLatencyMs = reportedReadyMs,
            avgReadyLatencyMs = reportedReadyMs,
            staleCallbacksDropped = reportedStaleDrops
        )
        _health.value = previous.copy(
            activePurpose = purpose,
            activeRequestId = requestId,
            state = _state.value,
            metrics = metrics,
            backendInUse = RecognitionBackendKind.SYSTEM,
            capabilities = _capabilities.value
        )
    }

    /** Hypothesis helper for tests that need several alternatives. */
    fun hypotheses(vararg texts: String): List<RecognitionHypothesis> =
        texts.mapIndexed { index, text -> RecognitionHypothesis(text, 0.9f - index * 0.1f, index) }

    fun now(): Long = clock()
}
