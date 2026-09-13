package com.studyagent.client.core.voice.stt

import com.studyagent.client.core.common.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Terminal outcome of one recognition turn, delivered exactly once. */
sealed interface RecognitionTurnResult {
    data class Completed(val outcome: RecognitionOutcome) : RecognitionTurnResult
    data class Failed(val error: RecognitionError) : RecognitionTurnResult

    val requestId: String
        get() = when (this) {
            is Completed -> outcome.requestId
            is Failed -> error.requestId.orEmpty()
        }
}

/**
 * The single authority over the microphone's recognition session (§53/§54).
 *
 * Study code asks for a *purpose*; this class owns everything else — backend selection,
 * readiness, the request lifecycle, watchdogs, bounded retry, rate limiting, stale-callback
 * rejection, metrics. Nothing else in the app calls [SpeechRecognitionBackend] directly.
 *
 * ## The two invariants that matter most
 *
 * **1. One turn at a time, and a turn ends only on a terminal callback.**
 * The previous implementation flipped a boolean in `onEndOfSpeech()` and in
 * `stopListening()`, so a second `startListening()` could reach a recognizer that was still
 * finalising — `ERROR_RECOGNIZER_BUSY`. Here a turn is only over when the backend reports
 * [RecognitionBackendEvent.Results] or [RecognitionBackendEvent.Failed], and [state] never
 * reports `isReadyForNewRequest` before then.
 *
 * **2. Every callback is validated against the active request id.**
 * A result that arrives after its turn was cancelled is dropped and counted
 * ([RecognitionMetrics.staleCallbacksDropped]). A transcript from card N can therefore never
 * be applied to card N+1 (§10/§108).
 *
 * ## Turn-based, not continuous (§66)
 * Recognition only runs inside an explicit answer/rating window. There is no open-microphone
 * loop, which keeps battery, data, privacy exposure and recognizer throttling bounded across
 * multi-hour sessions.
 */
class DefaultSpeechRecognitionOrchestrator(
    private val backend: SpeechRecognitionBackend,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val idFactory: (String) -> String = { label -> RecognitionPolicyFactory.defaultRequestId(label) },
    /**
     * TTS→STT gate (§57/§58). The repository supplies "nothing is playing and nothing is
     * queued", so the microphone never opens while the app's own voice could be captured.
     */
    private val canOpenMicrophone: () -> Boolean = { true },
    /** Diagnostics only — reports what is *known* about the mic route (§89). */
    private val inputRouteLabel: () -> String = { "Unknown" },
    private val policyFactory: RecognitionPolicyFactory = RecognitionPolicyFactory(),
    private val clock: () -> Long = System::currentTimeMillis
) : SpeechRecognitionOrchestrator {

    private val tag = "SttOrchestrator"

    /**
     * Single-owner guard (§62/§77). Every state transition — start, finish, cancel,
     * release, backend event, watchdog fire and the post-delay retry — runs inside this
     * monitor, so check-then-act sequences (busy check → `backend.start`) are atomic with
     * respect to each other even when callers arrive from different threads (UI thread,
     * repository coroutines, the event collector, watchdog and retry jobs).
     *
     * Every critical section below is non-suspending (backend calls post or return
     * synchronously; emissions use `tryEmit`; `scope.launch` does not suspend the caller),
     * so the lock is held only for microseconds and cannot deadlock. `synchronized` is
     * reentrant, which is what makes nested transitions (start → backend event → complete)
     * safe on dispatchers that run inline, such as test schedulers.
     */
    private val transitionLock = Any()

    private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    override val state: StateFlow<RecognitionState> = _state.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    override val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    override val audioLevel: StateFlow<Float> = backend.audioLevel

    override val capabilities: StateFlow<RecognitionCapabilities> = backend.capabilities

    private val _turnResults = MutableSharedFlow<RecognitionTurnResult>(extraBufferCapacity = 16)
    override val turnResults: SharedFlow<RecognitionTurnResult> = _turnResults.asSharedFlow()

    private val _languageEvents =
        MutableSharedFlow<RecognitionBackendEvent.LanguageDetected>(extraBufferCapacity = 16)
    override val languageEvents: SharedFlow<RecognitionBackendEvent.LanguageDetected> = _languageEvents.asSharedFlow()

    private val _health = MutableStateFlow(RecognitionHealthSnapshot())
    override val health: StateFlow<RecognitionHealthSnapshot> = _health.asStateFlow()

    private var settings: SttSettings = SttSettings()
    private var activeRequest: RecognitionRequest? = null
    private var activeBackendKind: RecognitionBackendKind = RecognitionBackendKind.UNKNOWN
    private var turnStartedAtMs = 0L
    private var retryAttempt = 0

    /**
     * Starts at "the distant past", never at 0: the first turn after launch (and the first
     * turn of a virtual-time test, where the clock starts at 0) must never be rate limited.
     */
    private var lastStartAttemptMs = Long.MIN_VALUE / 2
    private var watchdogJob: Job? = null
    private var metrics = RecognitionMetrics()
    private var released = false

    init {
        scope.launch {
            backend.events.collect { event -> handleBackendEvent(event) }
        }
        publishHealth()
    }

    override fun updateSettings(settings: SttSettings) {
        synchronized(transitionLock) { this.settings = settings }
    }

    // ------------------------------------------------------------------ starting

    override fun startRecognition(request: RecognitionRequest): RecognitionStartResult {
        synchronized(transitionLock) {
            if (released) {
                return RecognitionStartResult.Rejected(
                    RecognitionError(RecognitionErrorCode.UNAVAILABLE, request.id)
                )
            }
            if (_state.value.isActive) {
                AppLogger.w(tag, "start rejected: a turn is already active (${_state.value.requestId})")
                return RecognitionStartResult.Rejected(
                    RecognitionError(RecognitionErrorCode.BUSY, request.id)
                )
            }
            // Rate limit (§52): TTS completion, a UI tap and the hands-free loop can all try to
            // open the microphone within the same few milliseconds. Only one wins.
            val now = clock()
            if (now - lastStartAttemptMs < settings.minTurnIntervalMs) {
                metrics = metrics.copy(rateLimitRejections = metrics.rateLimitRejections + 1)
                publishHealth()
                AppLogger.d(tag, "start rejected: rate limited (${now - lastStartAttemptMs}ms since last)")
                return RecognitionStartResult.Rejected(
                    RecognitionError(RecognitionErrorCode.TOO_MANY_REQUESTS, request.id)
                )
            }
            // TTS gate: never listen while the app is still speaking (§57).
            if (!canOpenMicrophone()) {
                AppLogger.d(tag, "start deferred: speech pipeline has not settled")
                return RecognitionStartResult.Rejected(
                    RecognitionError(RecognitionErrorCode.BUSY, request.id, detail = "speech-active")
                )
            }
            return beginTurn(request, attempt = 0, rateLimited = true)
        }
    }

    /** Internal start: retries bypass the rate limiter but never the busy or TTS checks. */
    private fun beginTurn(
        request: RecognitionRequest,
        attempt: Int,
        rateLimited: Boolean
    ): RecognitionStartResult {
        if (rateLimited) lastStartAttemptMs = clock()
        retryAttempt = attempt

        _state.value = RecognitionState.Preparing(request.id, request.purpose, activeBackendKind)
        _partialTranscript.value = ""
        activeRequest = request
        turnStartedAtMs = clock()

        val result = backend.start(request)
        return when (result) {
            is RecognitionStartResult.Started -> {
                activeBackendKind = result.backend
                _state.value = RecognitionState.Preparing(request.id, request.purpose, result.backend)
                scheduleWatchdog(request.timeouts.readyMs, "ready")
                publishHealth()
                AppLogger.i(
                    tag,
                    "Turn started id=${request.id} purpose=${request.purpose.name} backend=${result.backend.name}"
                )
                result
            }

            is RecognitionStartResult.Rejected -> {
                // The backend refused synchronously; nothing is in flight, so clear state now.
                activeRequest = null
                _state.value = RecognitionState.Idle
                handleFailure(request, result.error, attempt)
                result
            }
        }
    }

    override fun finishCurrentTurn() {
        synchronized(transitionLock) {
            val request = activeRequest ?: return
            // stopListening, not cancel: we want the speech already captured to be finalised.
            // The turn stays active until the backend's terminal callback (§18/§94).
            AppLogger.d(tag, "Finishing turn ${request.id}; waiting for final result")
            _state.value = RecognitionState.Processing(request.id, request.purpose)
            scheduleWatchdog(request.timeouts.finalResultMs, "final")
            backend.stopListening()
            publishHealth()
        }
    }

    override fun cancelCurrentTurn(reason: String) {
        synchronized(transitionLock) {
            val request = activeRequest ?: return
            AppLogger.i(tag, "Cancelling turn ${request.id} ($reason)")
            // Invalidating the request id *before* cancelling means any in-flight callback is
            // already stale by the time it arrives — the core of §95/§96/§97.
            activeRequest = null
            watchdogJob?.cancel()
            watchdogJob = null
            metrics = metrics.copy(cancelledTurns = metrics.cancelledTurns + 1)
            _partialTranscript.value = ""
            _state.value = RecognitionState.Idle
            backend.cancel()
            publishHealth()
        }
    }

    override fun refreshCapabilities(): RecognitionCapabilities = backend.refreshCapabilities()

    override fun requestModelDownload(languageTag: String): Boolean =
        backend.requestModelDownload(languageTag)

    override fun release() {
        synchronized(transitionLock) {
            if (released) return
            released = true
            watchdogJob?.cancel()
            watchdogJob = null
            activeRequest = null
            backend.release()
            _state.value = RecognitionState.Released
            _isListening.value = false
            publishHealth()
        }
    }

    // ------------------------------------------------------------------ backend events

    private fun handleBackendEvent(event: RecognitionBackendEvent) {
        synchronized(transitionLock) { handleBackendEventLocked(event) }
    }

    /** Precondition: [transitionLock] is held by the caller. */
    private fun handleBackendEventLocked(event: RecognitionBackendEvent) {
        val current = activeRequest
        // Stale-callback protection (§10). A callback for a turn we no longer own is dropped.
        if (current == null || current.id != event.requestId) {
            // Our own cancel() produces a CANCELLED failure for a turn we already
            // invalidated; that is expected bookkeeping, not a stale callback.
            val isExpectedCancellation = event is RecognitionBackendEvent.Failed &&
                event.error.code == RecognitionErrorCode.CANCELLED
            if (!isExpectedCancellation) {
                metrics = metrics.copy(staleCallbacksDropped = metrics.staleCallbacksDropped + 1)
                publishHealth()
            }
            AppLogger.d(
                tag,
                "Dropped stale ${event::class.simpleName} for ${event.requestId} " +
                    "(active=${current?.id ?: "none"})"
            )
            return
        }

        when (event) {
            is RecognitionBackendEvent.Started -> {
                activeBackendKind = event.backend
                publishHealth()
            }

            is RecognitionBackendEvent.ReadyForSpeech -> {
                val readyMs = clock() - turnStartedAtMs
                metrics = metrics.copy(
                    lastReadyLatencyMs = readyMs,
                    readySamples = metrics.readySamples + 1,
                    avgReadyLatencyMs = runningAverage(
                        metrics.avgReadyLatencyMs, readyMs, metrics.readySamples + 1
                    )
                )
                _state.value = RecognitionState.ReadyForSpeech(current.id, current.purpose)
                scheduleWatchdog(current.timeouts.firstSpeechMs, "first-speech")
                publishHealth()
            }

            is RecognitionBackendEvent.SpeechBegan -> {
                _state.value = RecognitionState.SpeechDetected(current.id, current.purpose)
                scheduleWatchdog(remainingTotalBudget(current), "total")
                publishHealth()
            }

            is RecognitionBackendEvent.Partial -> {
                val text = event.hypotheses.firstOrNull()?.text.orEmpty()
                _partialTranscript.value = text
                val st = _state.value
                _state.value = when (st) {
                    is RecognitionState.SpeechDetected -> st.copy(partialTranscript = text)
                    is RecognitionState.ReadyForSpeech ->
                        RecognitionState.Listening(current.id, current.purpose, text)

                    is RecognitionState.Listening -> st.copy(partialTranscript = text)
                    else -> st
                }
                publishHealth()
            }

            is RecognitionBackendEvent.SpeechEnded -> {
                // Processing, NOT idle: the recognizer still owes a terminal callback (§7).
                _state.value = RecognitionState.Processing(current.id, current.purpose)
                scheduleWatchdog(current.timeouts.finalResultMs, "final")
                publishHealth()
            }

            is RecognitionBackendEvent.Results -> completeTurn(current, event)

            is RecognitionBackendEvent.Failed -> {
                activeRequest = null
                watchdogJob?.cancel()
                watchdogJob = null
                handleFailure(current, event.error, retryAttempt)
            }

            is RecognitionBackendEvent.LanguageDetected -> {
                _languageEvents.tryEmit(event)
                AppLogger.d(
                    tag,
                    "Language event id=${event.requestId} tag=${event.languageTag} " +
                        "level=${event.confidenceLevel} switch=${event.switchResult}"
                )
            }
        }
    }

    private fun completeTurn(request: RecognitionRequest, event: RecognitionBackendEvent.Results) {
        activeRequest = null
        watchdogJob?.cancel()
        watchdogJob = null

        val outcome = RecognitionOutcome(
            requestId = request.id,
            purpose = request.purpose,
            cardId = request.cardId,
            hypotheses = event.hypotheses,
            selectedText = event.hypotheses.firstOrNull()?.text.orEmpty(),
            selectedHypothesis = event.hypotheses.firstOrNull(),
            detectedLanguage = event.detectedLanguage,
            source = event.source,
            durationMs = event.speechDurationMs,
            finalizationLatencyMs = event.finalizationLatencyMs
        )

        metrics = metrics.copy(
            completedTurns = metrics.completedTurns + 1,
            onDeviceTurns = metrics.onDeviceTurns + if (event.source == RecognitionSource.ON_DEVICE) 1 else 0,
            networkTurns = metrics.networkTurns + if (event.source == RecognitionSource.NETWORK) 1 else 0,
            lastFinalizationMs = event.finalizationLatencyMs ?: metrics.lastFinalizationMs,
            finalizationSamples = event.finalizationLatencyMs?.let {
                metrics.finalizationSamples + 1
            } ?: metrics.finalizationSamples,
            avgFinalizationMs = event.finalizationLatencyMs?.let {
                runningAverage(metrics.avgFinalizationMs, it, metrics.finalizationSamples + 1)
            } ?: metrics.avgFinalizationMs,
            lastConfidence = outcome.topConfidence ?: metrics.lastConfidence
        )
        retryAttempt = 0
        _partialTranscript.value = ""
        _state.value = RecognitionState.Completed(request.id, outcome)
        publishHealth()
        // Privacy-safe: never logs transcript text (§46).
        AppLogger.i(tag, "Recognition complete id=${request.id} ${outcome.logSummary()}")
        _turnResults.tryEmit(RecognitionTurnResult.Completed(outcome))
        // Left at Completed rather than snapped back to Idle: the UI can keep showing the
        // final transcript, and the next beginTurn()/cancelCurrentTurn() resets the state.
    }

    // ------------------------------------------------------------------ failure & retry

    private fun handleFailure(request: RecognitionRequest, error: RecognitionError, attempt: Int) {
        activeRequest = null
        watchdogJob?.cancel()
        watchdogJob = null

        metrics = when (error.code) {
            RecognitionErrorCode.NO_SPEECH -> metrics.copy(noSpeechCount = metrics.noSpeechCount + 1)
            RecognitionErrorCode.NO_MATCH -> metrics.copy(noMatchCount = metrics.noMatchCount + 1)
            RecognitionErrorCode.BUSY -> metrics.copy(busyErrors = metrics.busyErrors + 1)
            RecognitionErrorCode.NETWORK_UNAVAILABLE,
            RecognitionErrorCode.NETWORK_TIMEOUT,
            RecognitionErrorCode.SERVER_DISCONNECTED ->
                metrics.copy(networkErrors = metrics.networkErrors + 1)

            RecognitionErrorCode.WATCHDOG_TIMEOUT ->
                metrics.copy(watchdogTimeouts = metrics.watchdogTimeouts + 1)

            else -> metrics
        }
        metrics = metrics.copy(failedTurns = metrics.failedTurns + 1)

        _partialTranscript.value = ""
        _state.value = RecognitionState.Failed(request.id, error)
        publishHealth()
        AppLogger.w(
            tag,
            "Recognition failed id=${request.id} code=${error.code.name} raw=${error.rawCode} " +
                "attempt=$attempt"
        )

        // Bounded retry (§50/§51). A delay of `null` means "do not retry".
        val delayMs = policyFactory.retryDelayMs(error, attempt, settings)
        if (delayMs == null) {
            retryAttempt = 0
            _turnResults.tryEmit(RecognitionTurnResult.Failed(error))
            _state.value = RecognitionState.Idle
            publishHealth()
            return
        }

        retryAttempt = attempt + 1
        publishHealth()
        val retryRequest = request.copy(
            id = idFactory(request.purpose.label),
            backendPreference = fallbackPreference(request, error)
        )
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            synchronized(transitionLock) {
                // Re-check: the study state may have moved on while we waited, and starting a
                // turn nobody wants is how hands-free mode ends up spinning.
                if (activeRequest != null || released || !canOpenMicrophone()) {
                    AppLogger.d(tag, "Retry abandoned for ${request.id}; state moved on")
                    _turnResults.tryEmit(RecognitionTurnResult.Failed(error))
                    return@launch
                }
                val outcome = beginTurn(retryRequest, attempt + 1, rateLimited = false)
                if (outcome is RecognitionStartResult.Rejected) {
                    _turnResults.tryEmit(RecognitionTurnResult.Failed(outcome.error))
                }
            }
        }
    }

    /**
     * On a network failure, prefer on-device for the retry if it exists — the one fallback
     * that can actually succeed without connectivity (§50).
     */
    private fun fallbackPreference(
        request: RecognitionRequest,
        error: RecognitionError
    ): RecognitionBackendPreference {
        val networkFailure = error.code == RecognitionErrorCode.NETWORK_UNAVAILABLE ||
            error.code == RecognitionErrorCode.NETWORK_TIMEOUT ||
            error.code == RecognitionErrorCode.SERVER_DISCONNECTED ||
            error.code == RecognitionErrorCode.SERVER_ERROR
        if (!networkFailure) return request.backendPreference
        if (backend.capabilities.value.onDeviceAvailable != true) return request.backendPreference
        return RecognitionBackendPreference.PREFER_ON_DEVICE
    }

    // ------------------------------------------------------------------ watchdog

    /**
     * Bounded, phase-specific timeouts (§92/§93).
     *
     * Without this, a recognizer that never returns a terminal callback leaves the subsystem
     * permanently "busy" and the study loop stalls forever — there is no other recovery path.
     */
    private fun scheduleWatchdog(durationMs: Long, phase: String) {
        if (durationMs <= 0) return
        watchdogJob?.cancel()
        val request = activeRequest ?: return
        watchdogJob = scope.launch {
            delay(durationMs)
            synchronized(transitionLock) {
                if (activeRequest?.id != request.id) return@launch
                AppLogger.w(tag, "Watchdog fired ($phase) for ${request.id} after ${durationMs}ms")
                // Drop ownership BEFORE cancelling the backend: with Unconfined dispatch
                // the backend's CANCELLED failure is delivered synchronously, and if the
                // request were still active it would be treated as a terminal (no-retry)
                // failure that resets retryAttempt — un-bounding the watchdog retry loop.
                // As a stale (expected-bookkeeping) event it is dropped instead.
                activeRequest = null
                backend.cancel()
                // handleFailure() below cancels and clears watchdogJob.
                handleFailure(
                    request,
                    RecognitionError(
                        RecognitionErrorCode.WATCHDOG_TIMEOUT,
                        request.id,
                        detail = "phase=$phase"
                    ),
                    retryAttempt
                )
            }
        }
    }

    /** Push-to-talk turns are bounded by the user, not by a total budget. */
    private fun remainingTotalBudget(request: RecognitionRequest): Long {
        if (request.purpose.isPushToTalk) return 0L
        val elapsed = clock() - turnStartedAtMs
        return (request.timeouts.totalMs - elapsed).coerceAtLeast(0L)
    }

    // ------------------------------------------------------------------ health

    private fun publishHealth() {
        val now = clock()
        _isListening.value = _state.value.isActive
        _health.value = RecognitionHealthSnapshot(
            state = _state.value,
            activePurpose = activeRequest?.purpose,
            activeRequestId = activeRequest?.id,
            activeRequestAgeMs = if (activeRequest != null) now - turnStartedAtMs else -1L,
            backendInUse = activeBackendKind,
            capabilities = backend.capabilities.value,
            inputRouteLabel = inputRouteLabel(),
            lastError = (_state.value as? RecognitionState.Failed)?.error,
            lastConfidence = metrics.lastConfidence,
            retryAttempt = retryAttempt,
            metrics = metrics
        )
    }

    private fun runningAverage(previous: Long, sample: Long, count: Int): Long {
        if (count <= 1) return sample
        if (previous < 0) return sample
        return previous + (sample - previous) / count
    }
}

/**
 * The recognition API for the rest of the app (§54).
 *
 * Deliberately narrow: callers supply a purpose-built [RecognitionRequest] and observe
 * state; they never touch a recognizer, a retry counter or a timeout.
 */
interface SpeechRecognitionOrchestrator {

    /** Full lifecycle state (§6). */
    val state: StateFlow<RecognitionState>

    /** Derived convenience for existing UI. Never used as a readiness signal internally. */
    val isListening: StateFlow<Boolean>

    /** Live transcript for the UI. Conflated, throttled upstream. */
    val partialTranscript: StateFlow<String>

    /** Sampled microphone level for the waveform. Separate channel by design (§122/§123). */
    val audioLevel: StateFlow<Float>

    val capabilities: StateFlow<RecognitionCapabilities>

    /** Diagnostics: active request, age, backend, metrics, last error (§89). */
    val health: StateFlow<RecognitionHealthSnapshot>

    /** Terminal outcomes. Reliable: partials and levels never share this channel. */
    val turnResults: SharedFlow<RecognitionTurnResult>

    /** Android 14+ language detection/switching events. Diagnostics and policy only. */
    val languageEvents: SharedFlow<RecognitionBackendEvent.LanguageDetected>

    fun startRecognition(request: RecognitionRequest): RecognitionStartResult

    /** Finish from captured speech; the turn stays active until the terminal callback. */
    fun finishCurrentTurn()

    /** Discard the turn and invalidate its request id, so late callbacks are dropped. */
    fun cancelCurrentTurn(reason: String)

    fun updateSettings(settings: SttSettings)

    fun refreshCapabilities(): RecognitionCapabilities

    fun requestModelDownload(languageTag: String): Boolean

    fun release()
}
