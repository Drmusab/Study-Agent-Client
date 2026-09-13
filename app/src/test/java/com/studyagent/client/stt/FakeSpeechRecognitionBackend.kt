package com.studyagent.client.stt

import com.studyagent.client.core.voice.stt.RecognitionBackendEvent
import com.studyagent.client.core.voice.stt.RecognitionCapabilities
import com.studyagent.client.core.voice.stt.RecognitionError
import com.studyagent.client.core.voice.stt.RecognitionErrorCode
import com.studyagent.client.core.voice.stt.RecognitionHypothesis
import com.studyagent.client.core.voice.stt.RecognitionRequest
import com.studyagent.client.core.voice.stt.RecognitionSource
import com.studyagent.client.core.voice.stt.RecognitionStartResult
import com.studyagent.client.core.voice.stt.SpeechRecognitionBackend
import com.studyagent.client.core.voice.stt.primaryLocaleTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Scriptable stand-in for `AndroidSpeechRecognitionBackend` (§102).
 *
 * The whole point is that recognition behaviour can be exercised without a microphone, an
 * emulator or a recognition provider: every Android callback has an `emit*` method, and the
 * awkward cases the real recognizer produces — busy refusals, late callbacks for cancelled
 * turns, missing confidence scores, watchdog silence — are trivially reproducible here.
 *
 * It honours the same contract as the real backend: `isBusy` stays true from [start] until a
 * terminal event or [cancel], and every event carries its request id.
 */
class FakeSpeechRecognitionBackend(
    initialCapabilities: RecognitionCapabilities = RecognitionCapabilities(
        recognitionAvailable = true,
        onDeviceAvailable = true,
        supportedLanguages = setOf("en-US", "ar-IQ"),
        installedLanguages = setOf("en-US", "ar-IQ")
    ),
    private val clock: () -> Long = System::currentTimeMillis
) : SpeechRecognitionBackend {

    private val _events = MutableSharedFlow<RecognitionBackendEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<RecognitionBackendEvent> = _events.asSharedFlow()

    private val _audioLevel = MutableStateFlow(-2.0f)
    override val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _capabilities = MutableStateFlow(initialCapabilities)
    override val capabilities: StateFlow<RecognitionCapabilities> = _capabilities.asStateFlow()

    /** Every request handed to [start], in order — the basis of most assertions. */
    val startedRequests = mutableListOf<RecognitionRequest>()

    var stopListeningCount = 0
        private set
    var cancelCount = 0
        private set
    var releaseCount = 0
        private set
    var modelDownloadRequests = mutableListOf<String>()
        private set
    var capabilityRefreshCount = 0
        private set

    /** Set false to simulate a device with no recognition service at all. */
    var permissionGranted = true

    private var activeId: String? = null

    override val isBusy: Boolean get() = activeId != null

    override val activeRequestId: String? get() = activeId

    override fun start(request: RecognitionRequest): RecognitionStartResult {
        if (!permissionGranted) {
            return RecognitionStartResult.Rejected(
                RecognitionError(RecognitionErrorCode.PERMISSION_DENIED, request.id)
            )
        }
        if (!_capabilities.value.recognitionAvailable) {
            return RecognitionStartResult.Rejected(
                RecognitionError(RecognitionErrorCode.UNAVAILABLE, request.id)
            )
        }
        // Mirrors the real backend: a second start before the terminal callback is refused,
        // which is what makes the busy-race regression test meaningful.
        if (activeId != null) {
            return RecognitionStartResult.Rejected(
                RecognitionError(RecognitionErrorCode.BUSY, request.id)
            )
        }
        startedRequests += request
        activeId = request.id
        emit(RecognitionBackendEvent.Started(request.id, backendKindFor(request)))
        return RecognitionStartResult.Started(request.id, backendKindFor(request))
    }

    override fun stopListening() {
        stopListeningCount++
    }

    override fun cancel() {
        cancelCount++
        val id = activeId ?: return
        activeId = null
        emit(
            RecognitionBackendEvent.Failed(
                id,
                RecognitionError(RecognitionErrorCode.CANCELLED, id)
            )
        )
    }

    override fun refreshCapabilities(): RecognitionCapabilities {
        capabilityRefreshCount++
        return _capabilities.value
    }

    override fun requestModelDownload(languageTag: String): Boolean {
        modelDownloadRequests += languageTag
        return true
    }

    override fun release() {
        releaseCount++
        activeId = null
    }

    fun setCapabilities(capabilities: RecognitionCapabilities) {
        _capabilities.value = capabilities
    }

    // ------------------------------------------------------------------ scripted callbacks

    fun emitReady() {
        val id = activeId ?: return
        emit(RecognitionBackendEvent.ReadyForSpeech(id))
    }

    fun emitSpeechBegan() {
        val id = activeId ?: return
        emit(RecognitionBackendEvent.SpeechBegan(id))
    }

    fun emitPartial(text: String) {
        val id = activeId ?: return
        emit(RecognitionBackendEvent.Partial(id, listOf(RecognitionHypothesis(text, null, 0))))
    }

    fun emitAudioLevel(levelDb: Float) {
        _audioLevel.value = levelDb
    }

    fun emitSpeechEnded() {
        val id = activeId ?: return
        emit(RecognitionBackendEvent.SpeechEnded(id))
    }

    /** Final result from a single hypothesis. */
    fun emitFinal(text: String, confidence: Float? = null) {
        emitFinalHypotheses(listOf(RecognitionHypothesis(text, confidence, 0)))
    }

    /**
     * Final result from several alternatives — the shape a real recognizer returns, and the
     * input the contextual candidate selector is designed for.
     */
    fun emitFinalHypotheses(hypotheses: List<RecognitionHypothesis>) {
        val id = activeId ?: return
        activeId = null
        emit(
            RecognitionBackendEvent.Results(
                requestId = id,
                hypotheses = hypotheses,
                detectedLanguage = null,
                source = RecognitionSource.UNKNOWN,
                speechDurationMs = null,
                finalizationLatencyMs = null
            )
        )
    }

    fun emitError(code: RecognitionErrorCode, rawCode: Int? = null) {
        val id = activeId ?: return
        activeId = null
        emit(
            RecognitionBackendEvent.Failed(
                id,
                RecognitionError(code, id, rawCode = rawCode)
            )
        )
    }

    fun emitLanguageDetected(languageTag: String, confidenceLevel: Int? = null) {
        val id = activeId ?: return
        emit(
            RecognitionBackendEvent.LanguageDetected(
                requestId = id,
                languageTag = languageTag,
                confidenceLevel = confidenceLevel,
                switchResult = null
            )
        )
    }

    /**
     * Emit a terminal result for a request that is no longer active — the stale callback
     * the orchestrator must drop (§10/§108).
     */
    fun emitStaleFinal(requestId: String, text: String) {
        emit(
            RecognitionBackendEvent.Results(
                requestId = requestId,
                hypotheses = listOf(RecognitionHypothesis(text, 0.9f, 0)),
                detectedLanguage = null,
                source = RecognitionSource.UNKNOWN,
                speechDurationMs = null,
                finalizationLatencyMs = null
            )
        )
    }

    fun emitStaleError(requestId: String, code: RecognitionErrorCode) {
        emit(
            RecognitionBackendEvent.Failed(
                requestId,
                RecognitionError(code, requestId)
            )
        )
    }

    // ------------------------------------------------------------------ chaos injection
    //
    // The emit* helpers above model a well-behaved recognizer: one terminal per turn. The
    // raw* helpers below deliberately bypass that contract — they emit a terminal event for
    // an arbitrary request id without touching [activeId] — which is how the orchestrator's
    // duplicate-final, final-then-error and cancel-then-final guards are exercised against
    // the buggy services real devices sometimes ship.

    /** Forge a terminal Results event for [requestId], regardless of the active turn. */
    fun emitRawResults(
        requestId: String,
        hypotheses: List<RecognitionHypothesis>,
        confidence: Float? = null
    ) {
        emit(
            RecognitionBackendEvent.Results(
                requestId = requestId,
                hypotheses = hypotheses.ifEmpty { listOf(RecognitionHypothesis("", confidence, 0)) },
                detectedLanguage = null,
                source = RecognitionSource.UNKNOWN,
                speechDurationMs = null,
                finalizationLatencyMs = null
            )
        )
    }

    /** Forge a terminal Failed event for [requestId], regardless of the active turn. */
    fun emitRawError(requestId: String, code: RecognitionErrorCode) {
        emit(RecognitionBackendEvent.Failed(requestId, RecognitionError(code, requestId)))
    }

    /** Forge a partial for [requestId] — e.g. a late partial after the turn finished. */
    fun emitRawPartial(requestId: String, text: String) {
        emit(
            RecognitionBackendEvent.Partial(
                requestId,
                listOf(RecognitionHypothesis(text, null, 0))
            )
        )
    }

    /** The request ids handed to [start], for forging callbacks against them. */
    val lastStartedRequestId: String? get() = startedRequests.lastOrNull()?.id

    /** Convenience: the full happy path up to (but not including) the final result. */
    fun emitReadyAndSpeech() {
        emitReady()
        emitSpeechBegan()
    }

    /** Mirrors AndroidSpeechRecognitionBackend.selectBackendKind, including the
     *  per-language model check (§23). */
    private fun backendKindFor(request: RecognitionRequest):
        com.studyagent.client.core.voice.stt.RecognitionBackendKind {
        val caps = _capabilities.value
        return when (request.backendPreference) {
            com.studyagent.client.core.voice.stt.RecognitionBackendPreference.SYSTEM_DEFAULT ->
                com.studyagent.client.core.voice.stt.RecognitionBackendKind.SYSTEM

            com.studyagent.client.core.voice.stt.RecognitionBackendPreference.AUTO,
            com.studyagent.client.core.voice.stt.RecognitionBackendPreference.PREFER_ON_DEVICE -> {
                val onDeviceUsable = caps.onDeviceAvailable == true &&
                    caps.isLanguageInstalled(request.primaryLocaleTag()) != false
                if (onDeviceUsable && request.preferOnDevice) {
                    com.studyagent.client.core.voice.stt.RecognitionBackendKind.ON_DEVICE
                } else {
                    com.studyagent.client.core.voice.stt.RecognitionBackendKind.SYSTEM
                }
            }
        }
    }

    private fun emit(event: RecognitionBackendEvent) {
        _events.tryEmit(event)
    }

    /** Wall-clock hook, exposed so tests can reason about watchdog timing. */
    val nowMs: Long get() = clock()

    /** All terminal events observed by tests that prefer to assert on the raw stream. */
    val eventsAsFlow: Flow<RecognitionBackendEvent> get() = events
}
