package com.studyagent.client.core.voice.stt

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.studyagent.client.core.common.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The only place in the app that touches [SpeechRecognizer] (§54/§55).
 *
 * What this class is responsible for, and nothing more:
 *  - owning exactly one recognizer instance, on the main thread, for the process lifetime;
 *  - translating a [RecognitionRequest] into a [RecognizerIntent];
 *  - turning [RecognitionListener] callbacks into request-stamped [RecognitionBackendEvent]s;
 *  - reporting what the device can actually do.
 *
 * Retry, policy, candidate selection and study semantics all live above this in
 * [DefaultSpeechRecognitionOrchestrator].
 *
 * ## Bugs this replaces (all verified against the previous `AndroidSpeechRecognitionManager`)
 *  - `isListening` was set false in `onEndOfSpeech` and in `stopListening()`, so a new turn
 *    could start while the recognizer was still working → `ERROR_RECOGNIZER_BUSY`. Now
 *    [isBusy] stays true until a terminal callback.
 *  - Only `matches.firstOrNull()` was read and confidence scores were discarded. All
 *    alternatives plus `EXTRA_CONFIDENCE_SCORES` are now captured.
 *  - `ERROR_NO_MATCH` and `ERROR_SPEECH_TIMEOUT` were collapsed into one `NoSpeech` event.
 *  - Final and partial transcripts were logged verbatim. Only privacy-safe summaries are
 *    logged now; full text requires an explicit opt-in (§46).
 *  - `onRmsChanged` flooded a single `SharedFlow` shared with final results. Levels now go to
 *    a separate conflated `StateFlow`, sampled.
 */
class AndroidSpeechRecognitionBackend(
    context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    /** Injectable for tests; production uses wall-clock time. */
    private val clock: () -> Long = System::currentTimeMillis
) : SpeechRecognitionBackend {

    private val appContext: Context = context.applicationContext
    private val tag = "SttBackend"

    private val _events = MutableSharedFlow<RecognitionBackendEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<RecognitionBackendEvent> = _events.asSharedFlow()

    private val _audioLevel = MutableStateFlow(IDLE_AUDIO_LEVEL)
    override val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _capabilities = MutableStateFlow(RecognitionCapabilities.UNKNOWN)
    override val capabilities: StateFlow<RecognitionCapabilities> = _capabilities.asStateFlow()

    /** Guarded by the main thread: every recognizer call is posted to [mainHandler]. */
    private var recognizer: SpeechRecognizer? = null
    private var recognizerKind: RecognitionBackendKind? = null

    /** Non-null while a turn owns the recognizer. This is the busy signal. */
    private var activeTurn: ActiveTurn? = null
    private var released = false

    private var lastLevelEmitMs = 0L
    private var lastLevelValue = IDLE_AUDIO_LEVEL
    private var lastPartialEmitMs = 0L

    override val isBusy: Boolean
        get() = activeTurn != null

    override val activeRequestId: String?
        get() = activeTurn?.request?.id

    // ------------------------------------------------------------------ lifecycle

    override fun start(request: RecognitionRequest): RecognitionStartResult {
        if (released) {
            return RecognitionStartResult.Rejected(error(RecognitionErrorCode.UNAVAILABLE, request.id))
        }
        // The single most important line in this class: never hand the recognizer a second
        // request before the previous one reached onResults/onError.
        if (activeTurn != null) {
            AppLogger.w(tag, "start() rejected: recognizer busy with ${activeTurn?.request?.id}")
            return RecognitionStartResult.Rejected(error(RecognitionErrorCode.BUSY, request.id))
        }
        if (!hasRecordAudioPermission()) {
            return RecognitionStartResult.Rejected(error(RecognitionErrorCode.PERMISSION_DENIED, request.id))
        }
        val caps = _capabilities.value
        if (!caps.recognitionAvailable) {
            return RecognitionStartResult.Rejected(error(RecognitionErrorCode.UNAVAILABLE, request.id))
        }

        val kind = selectBackendKind(request, caps)
        activeTurn = ActiveTurn(request = request, backend = kind, startedAtMs = clock())
        lastLevelEmitMs = 0L
        lastPartialEmitMs = 0L
        _audioLevel.value = IDLE_AUDIO_LEVEL

        postToMain { beginListening(request, kind) }
        return RecognitionStartResult.Started(request.id, kind)
    }

    override fun stopListening() {
        postToMain {
            val turn = activeTurn ?: return@postToMain
            try {
                recognizer?.stopListening()
            } catch (t: Throwable) {
                AppLogger.w(tag, "stopListening failed: ${t.message}")
            }
            // Deliberately NOT clearing activeTurn: the recognizer still owes us a terminal
            // callback, and treating it as free here is exactly what caused busy races.
            AppLogger.d(tag, "stopListening() id=${turn.request.id}; awaiting terminal callback")
        }
    }

    override fun cancel() {
        postToMain {
            val turn = activeTurn ?: return@postToMain
            try {
                recognizer?.cancel()
            } catch (t: Throwable) {
                AppLogger.w(tag, "cancel failed: ${t.message}")
            }
            terminateWith(
                RecognitionBackendEvent.Failed(
                    requestId = turn.request.id,
                    error = error(RecognitionErrorCode.CANCELLED, turn.request.id)
                )
            )
        }
    }

    override fun release() {
        released = true
        postToMain {
            activeTurn?.let { turn ->
                terminateWith(
                    RecognitionBackendEvent.Failed(
                        requestId = turn.request.id,
                        error = error(RecognitionErrorCode.CANCELLED, turn.request.id)
                    )
                )
            }
            try {
                recognizer?.destroy()
            } catch (t: Throwable) {
                AppLogger.w(tag, "destroy failed: ${t.message}")
            }
            recognizer = null
            recognizerKind = null
            _audioLevel.value = IDLE_AUDIO_LEVEL
        }
    }

    // ------------------------------------------------------------------ capabilities

    override fun refreshCapabilities(): RecognitionCapabilities {
        val previous = _capabilities.value
        val available = runCatching { SpeechRecognizer.isRecognitionAvailable(appContext) }
            .getOrElse {
                AppLogger.w(tag, "isRecognitionAvailable threw: ${it.message}")
                false
            }

        val onDeviceAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }.getOrNull()
        } else {
            null
        }

        val updated = previous.copy(
            recognitionAvailable = available,
            onDeviceAvailable = onDeviceAvailable,
            // Language detection/switching are API 34 platform features; whether the
            // installed recognizer actually honours them is unknowable from here, so they
            // stay null ("unknown") rather than being reported as supported (§25).
            languageDetectionSupported = if (Build.VERSION.SDK_INT >= 34) null else false,
            languageSwitchSupported = if (Build.VERSION.SDK_INT >= 34) null else false,
            vocabularyBiasingSupported = if (Build.VERSION.SDK_INT >= 33) null else false,
            capturedAtMs = clock()
        )
        _capabilities.value = updated
        if (available) queryRecognitionSupport()
        return updated
    }

    /**
     * API 33+ capability query (§24). Async by platform design, so the answer lands in
     * [capabilities] when it arrives; the UI reads the flow rather than blocking on it.
     */
    private fun queryRecognitionSupport() {
        if (Build.VERSION.SDK_INT < 33) return
        postToMain {
            val rec = recognizer ?: ensureRecognizer(RecognitionBackendKind.SYSTEM) ?: return@postToMain
            val probe = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            }
            runCatching {
                rec.checkRecognitionSupport(probe, object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        val supported = support.supportedOnDeviceLanguages.toSet()
                        val installed = support.installedOnDeviceLanguages.toSet()
                        _capabilities.value = _capabilities.value.copy(
                            supportedLanguages = supported,
                            installedLanguages = installed,
                            capturedAtMs = clock()
                        )
                        AppLogger.i(
                            tag,
                            "Recognition support: onDeviceSupported=${supported.size} installed=${installed.size} " +
                                "online=${support.onlineLanguages.size}"
                        )
                    }

                    override fun onError(errorCode: Int) {
                        AppLogger.w(tag, "checkRecognitionSupport failed (code=$errorCode)")
                    }
                })
            }.onFailure {
                AppLogger.w(tag, "checkRecognitionSupport threw: ${it.message}")
            }
        }
    }

    override fun requestModelDownload(languageTag: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        var requested = false
        // Posted rather than run inline: SpeechRecognizer calls must happen on the main thread.
        postToMain {
            val rec = recognizer ?: ensureRecognizer(RecognitionBackendKind.SYSTEM)
            if (rec == null) return@postToMain
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            }
            runCatching { rec.triggerModelDownload(intent) }
                .onSuccess { AppLogger.i(tag, "Model download requested for $languageTag") }
                .onFailure { AppLogger.w(tag, "triggerModelDownload failed: ${it.message}") }
        }
        requested = true
        return requested
    }

    // ------------------------------------------------------------------ recognizer

    private fun beginListening(request: RecognitionRequest, kind: RecognitionBackendKind) {
        // The turn may have been cancelled while the main-thread hop was in flight.
        val turn = activeTurn
        if (turn == null || turn.request.id != request.id) {
            AppLogger.d(tag, "beginListening skipped; turn no longer active")
            return
        }
        val rec = ensureRecognizer(kind)
        if (rec == null) {
            terminateWith(
                RecognitionBackendEvent.Failed(
                    requestId = request.id,
                    error = error(RecognitionErrorCode.UNAVAILABLE, request.id)
                )
            )
            return
        }
        try {
            rec.startListening(buildIntent(request))
            emit(RecognitionBackendEvent.Started(request.id, turn.backend))
        } catch (t: Throwable) {
            AppLogger.e(tag, "startListening threw: ${t.message}", t)
            terminateWith(
                RecognitionBackendEvent.Failed(
                    requestId = request.id,
                    error = error(RecognitionErrorCode.CLIENT_ERROR, request.id, detail = t.message)
                )
            )
        }
    }

    /**
     * Returns the recognizer for [kind], creating or swapping only when the kind changes.
     *
     * Recreating a recognizer per card was explicitly ruled out (§119/§120): binding to a
     * recognition service costs real time and battery, so the instance is reused across
     * hundreds of turns and only replaced when on-device/system actually switches.
     */
    private fun ensureRecognizer(kind: RecognitionBackendKind): SpeechRecognizer? {
        recognizer?.let { existing ->
            if (recognizerKind == kind) return existing
            AppLogger.i(tag, "Recognition backend changed ${recognizerKind?.name} -> ${kind.name}; recreating")
            runCatching { existing.destroy() }
            recognizer = null
            recognizerKind = null
        }
        return try {
            val created = if (kind == RecognitionBackendKind.ON_DEVICE &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            } else {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }
            created.setRecognitionListener(listener)
            recognizer = created
            recognizerKind = kind
            AppLogger.i(tag, "Recognizer created (backend=${kind.name})")
            created
        } catch (t: Throwable) {
            AppLogger.e(tag, "Failed to create recognizer: ${t.message}", t)
            recognizer = null
            recognizerKind = null
            null
        }
    }

    /** AUTO / PREFER_ON_DEVICE resolve to on-device only when it is genuinely available. */
    private fun selectBackendKind(
        request: RecognitionRequest,
        caps: RecognitionCapabilities
    ): RecognitionBackendKind = when (request.backendPreference) {
        RecognitionBackendPreference.SYSTEM_DEFAULT -> RecognitionBackendKind.SYSTEM

        RecognitionBackendPreference.AUTO,
        RecognitionBackendPreference.PREFER_ON_DEVICE -> {
            val onDeviceUsable = caps.onDeviceAvailable == true &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                // Never claim offline recognition for a language whose model is known to be
                // missing — that would show "Offline" while silently using the network (§23).
                caps.isLanguageInstalled(request.primaryLocaleTag()) != false
            if (onDeviceUsable && request.preferOnDevice) {
                RecognitionBackendKind.ON_DEVICE
            } else {
                RecognitionBackendKind.SYSTEM
            }
        }
    }

    private fun buildIntent(request: RecognitionRequest): Intent {
        val primaryLocale = request.primaryLocaleTag()
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, primaryLocale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primaryLocale)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, primaryLocale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, request.partialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, request.maxCandidates)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)

            // A hint, not a guarantee: Android documents EXTRA_PREFER_OFFLINE as something
            // implementations may ignore. Backend selection above is the real mechanism.
            if (request.preferOnDevice) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

            applyVocabularyBiasing(request)
            applyLanguagePolicy(request)
            applyEndpointHints(request)
        }
    }

    /** API 33+ contextual biasing (§33/§35). Silently skipped where unsupported. */
    private fun Intent.applyVocabularyBiasing(request: RecognitionRequest) {
        if (Build.VERSION.SDK_INT < 33) return
        val hints = request.vocabularyHints
        if (hints.isEmpty()) return
        runCatching {
            putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints))
        }.onFailure { AppLogger.w(tag, "Biasing strings rejected: ${it.message}") }
    }

    /**
     * English + Arabic in one turn (§28–§31).
     *
     * On API 34+ with `AUTO_EN_AR` we ask for language detection and conservative switching
     * restricted to a two-language allowlist. Below 34 — or if the recognizer ignores the
     * extras, which it is explicitly allowed to do — the request still works: it simply runs
     * in the single fallback locale, which is the documented degradation path.
     *
     * Two recognizers are never run against the microphone at once (§31/§137).
     */
    private fun Intent.applyLanguagePolicy(request: RecognitionRequest) {
        if (request.languageMode != RecognitionLanguageMode.AUTO_EN_AR) return
        if (Build.VERSION.SDK_INT < 34) return
        val allowlist = ArrayList(request.languageAllowlist())
        runCatching {
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, allowlist)
            putExtra(
                RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                // BALANCED rather than QUICK_RESPONSE: mid-answer switching is disruptive,
                // and a medical answer is better transcribed in one language than flickering.
                RecognizerIntent.LANGUAGE_SWITCH_BALANCED
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, allowlist)
        }.onFailure { AppLogger.w(tag, "Language detection/switch extras rejected: ${it.message}") }
    }

    /**
     * Silence-length hints (§17).
     *
     * Attached only when [EndpointPolicy.applySilenceHints] is true, which no default
     * profile currently sets: Android documents these extras as producing "unexpected
     * behavior" and they are ignored by several recognizers. Endpoint behaviour is bounded
     * by the watchdog instead.
     */
    private fun Intent.applyEndpointHints(request: RecognitionRequest) {
        val policy = request.endpointPolicy
        if (!policy.applySilenceHints) return
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            policy.completeSilenceHintMs
        )
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            policy.possiblyCompleteSilenceHintMs
        )
    }

    // ------------------------------------------------------------------ listener

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            val turn = activeTurn ?: return
            emit(RecognitionBackendEvent.ReadyForSpeech(turn.request.id))
        }

        override fun onBeginningOfSpeech() {
            val turn = activeTurn ?: return
            turn.speechBeganAtMs = clock()
            emit(RecognitionBackendEvent.SpeechBegan(turn.request.id))
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Liveness guard only: levels for an already-terminated turn are discarded.
            if (activeTurn == null) return
            val now = clock()
            // Sampled: the waveform needs ~16 updates a second, the callback can arrive far
            // more often than that, and each emission costs a Compose recomposition (§44).
            if (now - lastLevelEmitMs < LEVEL_SAMPLE_INTERVAL_MS) return
            if (kotlin.math.abs(rmsdB - lastLevelValue) < LEVEL_MIN_DELTA_DB &&
                now - lastLevelEmitMs < LEVEL_FORCE_INTERVAL_MS
            ) {
                return
            }
            lastLevelEmitMs = now
            lastLevelValue = rmsdB
            // Conflated StateFlow, not an event: a slow collector just sees the newest level.
            _audioLevel.value = rmsdB
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Raw audio is intentionally not retained (§45).
        }

        override fun onEndOfSpeech() {
            val turn = activeTurn ?: return
            turn.endOfSpeechAtMs = clock()
            // NOTE: no state reset here. The turn is still in flight until onResults/onError.
            emit(RecognitionBackendEvent.SpeechEnded(turn.request.id))
        }

        override fun onError(error: Int) {
            val turn = activeTurn ?: return
            val mapped = mapError(error, turn.request.id)
            AppLogger.w(tag, "Recognition error code=$error -> ${mapped.code.name} (id=${turn.request.id})")
            terminateWith(RecognitionBackendEvent.Failed(turn.request.id, mapped))
        }

        override fun onResults(results: Bundle?) {
            val turn = activeTurn ?: return
            val hypotheses = extractHypotheses(results)
            val finalizeMs = turn.endOfSpeechAtMs?.let { clock() - it }
            val speechMs = turn.speechBeganAtMs?.let { (turn.endOfSpeechAtMs ?: clock()) - it }
            val source = if (turn.backend == RecognitionBackendKind.ON_DEVICE) {
                RecognitionSource.ON_DEVICE
            } else {
                RecognitionSource.UNKNOWN
            }
            if (turn.settingsDebugLogging) {
                AppLogger.d(tag, "Final transcript id=${turn.request.id}: '${hypotheses.firstOrNull()?.text}'")
            }
            AppLogger.i(
                tag,
                "Recognition complete id=${turn.request.id} purpose=${turn.request.purpose.name} " +
                    "chars=${hypotheses.firstOrNull()?.text?.length ?: 0} candidates=${hypotheses.size} " +
                    "conf=${hypotheses.firstOrNull()?.confidence} finalizeMs=$finalizeMs"
            )
            terminateWith(
                RecognitionBackendEvent.Results(
                    requestId = turn.request.id,
                    hypotheses = hypotheses,
                    detectedLanguage = turn.detectedLanguage,
                    source = source,
                    speechDurationMs = speechMs,
                    finalizationLatencyMs = finalizeMs
                )
            )
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val turn = activeTurn ?: return
            val now = clock()
            // Throttled so a chatty recognizer cannot drive hundreds of recompositions per
            // second (§43). Final results are never delayed — they take a different path.
            if (now - lastPartialEmitMs < PARTIAL_SAMPLE_INTERVAL_MS) return
            val hypotheses = extractHypotheses(partialResults)
            if (hypotheses.isEmpty()) return
            lastPartialEmitMs = now
            if (turn.settingsDebugLogging) {
                AppLogger.d(tag, "Partial id=${turn.request.id}: '${hypotheses.firstOrNull()?.text}'")
            }
            emit(RecognitionBackendEvent.Partial(turn.request.id, hypotheses))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        /**
         * API 34+ language detection/switching (§29/§30/§32).
         *
         * Diagnostics and policy only — the user is never interrupted to be told the
         * language changed mid-answer.
         */
        override fun onLanguageDetection(results: Bundle) {
            if (Build.VERSION.SDK_INT < 34) return
            val turn = activeTurn ?: return
            val detectedTag = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
            val confidence = if (results.containsKey(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL)) {
                results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL)
            } else {
                null
            }
            val switchResult = if (results.containsKey(SpeechRecognizer.LANGUAGE_SWITCH_RESULT)) {
                results.getInt(SpeechRecognizer.LANGUAGE_SWITCH_RESULT)
            } else {
                null
            }
            if (detectedTag != null) turn.detectedLanguage = detectedTag
            AppLogger.d(
                tag,
                "Language detected=$detectedTag confidenceLevel=$confidence switchResult=$switchResult"
            )
            emit(
                RecognitionBackendEvent.LanguageDetected(
                    requestId = turn.request.id,
                    languageTag = detectedTag,
                    confidenceLevel = confidence,
                    switchResult = switchResult
                )
            )
        }
    }

    /**
     * Capability discovery is deferred until after [listener] exists.
     *
     * `refreshCapabilities()` reaches `queryRecognitionSupport()`, which posts to the main
     * handler — and `postToMain` runs the block *inline* when the caller is already on the
     * main thread. Doing this in a constructor-init block above the `listener` declaration
     * would therefore hand `setRecognitionListener` a not-yet-initialized property.
     */
    init {
        _capabilities.value = refreshCapabilities()
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Alternatives plus per-alternative confidence (§11/§12).
     *
     * Confidence is nullable end-to-end: many recognizers never populate
     * `EXTRA_CONFIDENCE_SCORES`, and inventing a score would make downstream safety gates
     * meaningless.
     */
    private fun extractHypotheses(bundle: Bundle?): List<RecognitionHypothesis> {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return emptyList()
        val scores = runCatching { bundle.getFloatArray(RecognizerIntent.EXTRA_CONFIDENCE_SCORES) }.getOrNull()
        return matches
            .mapIndexed { index, raw ->
                RecognitionHypothesis(
                    text = raw?.trim().orEmpty(),
                    confidence = scores?.getOrNull(index)?.takeIf { it in 0f..1f },
                    rank = index
                )
            }
            .filterNot { it.isBlank }
    }

    private fun mapError(code: Int, requestId: String): RecognitionError {
        val mapped = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> RecognitionErrorCode.AUDIO_FAILURE
            SpeechRecognizer.ERROR_CLIENT -> RecognitionErrorCode.CLIENT_ERROR
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionErrorCode.PERMISSION_DENIED
            SpeechRecognizer.ERROR_NETWORK -> RecognitionErrorCode.NETWORK_UNAVAILABLE
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> RecognitionErrorCode.NETWORK_TIMEOUT
            // Kept distinct: "nothing was said" and "something was said but not understood"
            // need different prompts and different retry budgets (§48).
            SpeechRecognizer.ERROR_NO_MATCH -> RecognitionErrorCode.NO_MATCH
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognitionErrorCode.NO_SPEECH
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> RecognitionErrorCode.BUSY
            SpeechRecognizer.ERROR_SERVER -> RecognitionErrorCode.SERVER_ERROR
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> RecognitionErrorCode.SERVER_DISCONNECTED
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> RecognitionErrorCode.TOO_MANY_REQUESTS
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> RecognitionErrorCode.LANGUAGE_UNSUPPORTED
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> RecognitionErrorCode.LANGUAGE_MODEL_UNAVAILABLE
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> RecognitionErrorCode.SUPPORT_CHECK_FAILED
            else -> RecognitionErrorCode.INTERNAL
        }
        return error(mapped, requestId, rawCode = code)
    }

    private fun error(
        code: RecognitionErrorCode,
        requestId: String?,
        rawCode: Int? = null,
        detail: String? = null
    ) = RecognitionError(code = code, requestId = requestId, rawCode = rawCode, detail = detail)

    private fun emit(event: RecognitionBackendEvent) {
        if (!_events.tryEmit(event)) {
            AppLogger.w(tag, "Dropped recognition event (buffer full): ${event::class.simpleName}")
        }
    }

    /** Terminal path: clears the turn first so no later callback can be mistaken for live. */
    private fun terminateWith(event: RecognitionBackendEvent) {
        activeTurn = null
        _audioLevel.value = IDLE_AUDIO_LEVEL
        emit(event)
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun hasRecordAudioPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private class ActiveTurn(
        val request: RecognitionRequest,
        val backend: RecognitionBackendKind,
        val startedAtMs: Long
    ) {
        var speechBeganAtMs: Long? = null
        var endOfSpeechAtMs: Long? = null
        var detectedLanguage: String? = null

        /** Opt-in developer logging only; transcripts are never logged by default (§46). */
        val settingsDebugLogging: Boolean
            get() = request.debugTranscriptLogging
    }

    companion object {
        /** RMS sampling: ~16 fps is plenty for a waveform. */
        const val LEVEL_SAMPLE_INTERVAL_MS = 60L

        /** Force an emission this often even if the level barely moved, so the UI never freezes. */
        const val LEVEL_FORCE_INTERVAL_MS = 250L

        /** Ignore sub-perceptual level jitter. */
        const val LEVEL_MIN_DELTA_DB = 0.75f

        /** Partial-result cadence: 100 ms is responsive and keeps recomposition bounded (§43). */
        const val PARTIAL_SAMPLE_INTERVAL_MS = 100L

        const val IDLE_AUDIO_LEVEL = -2.0f
    }
}

/** The locale a single-language request should use. */
internal fun RecognitionRequest.primaryLocaleTag(): String = when (languageMode) {
    RecognitionLanguageMode.ENGLISH -> englishLocale
    RecognitionLanguageMode.ARABIC -> arabicLocale
    // AUTO's primary locale is what a recognizer without language switching will run in.
    RecognitionLanguageMode.AUTO_EN_AR -> autoFallbackLocale
}

/** Two-language allowlist for API 34+ detection/switching. Deliberately tiny (§29). */
internal fun RecognitionRequest.languageAllowlist(): List<String> =
    listOf(englishLocale, arabicLocale).distinct()
