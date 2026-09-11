package com.studyagent.client.core.voice.stt

/**
 * Framework-free domain model for the speech-to-text subsystem.
 *
 * Nothing in this file imports `android.*`, so every policy decision (purpose →
 * configuration, candidate selection, error classification, retry) is unit-testable
 * without a microphone, an emulator, or a recognition provider.
 *
 * The one hard rule this model encodes: **recognition is not one task.** An answer and a
 * rating are acoustically identical to `SpeechRecognizer` but have completely opposite
 * risk profiles, so every request carries an explicit [RecognitionPurpose] that drives
 * endpointing, candidate selection, confidence thresholds and retry behaviour.
 */

// ------------------------------------------------------------------ purposes

/**
 * What the app expects the user to say. Drives endpoint profile, vocabulary biasing,
 * candidate selection and acceptance thresholds.
 */
enum class RecognitionPurpose {
    /** A free-form medical answer (one word up to ~60 s). */
    ANSWER,

    /** Again / Hard / Good / Easy. Modifies Anki scheduling → highest safety bar. */
    RATING,

    /** Repeat / Hint / Explain / Skip / Pause / Resume / End. Some are destructive. */
    COMMAND,

    /** [ANSWER] captured while the user holds push-to-talk. */
    PUSH_TO_TALK_ANSWER,

    /** [COMMAND] captured while the user holds push-to-talk. */
    PUSH_TO_TALK_COMMAND,

    /** "Start study on <deck>" — needs the deck name as free text. */
    DECK_SELECTION,

    /** Yes/no confirmations, e.g. rating confirmation. */
    SHORT_CONFIRMATION;

    /** True for purposes whose transcript is submitted to the PC evaluator verbatim. */
    val isAnswerLike: Boolean
        get() = this == ANSWER || this == PUSH_TO_TALK_ANSWER

    /** True for purposes interpreted against the controlled command grammar. */
    val isCommandLike: Boolean
        get() = this == COMMAND || this == PUSH_TO_TALK_COMMAND || this == RATING

    /** True when the user, not the endpointer, decides when speech ends. */
    val isPushToTalk: Boolean
        get() = this == PUSH_TO_TALK_ANSWER || this == PUSH_TO_TALK_COMMAND

    /** Short lowercase label used in request ids and logs — never transcript content. */
    val label: String
        get() = name.lowercase()
}

// ------------------------------------------------------------------ language

/**
 * Which language(s) the recognizer should attempt.
 *
 * `AUTO_EN_AR` is the default study configuration: the user should not have to switch
 * locale between an English card and an Arabic answer. It maps to Android 14+ language
 * detection/switching where the provider supports it, and to a deterministic fallback
 * ([SttSettings.autoModeFallbackLocale]) where it does not.
 */
enum class RecognitionLanguageMode {
    AUTO_EN_AR,
    ENGLISH,
    ARABIC;

    companion object {
        fun fromTag(tag: String?): RecognitionLanguageMode = when {
            tag.isNullOrBlank() -> AUTO_EN_AR
            tag.equals(AUTO_TAG, ignoreCase = true) -> AUTO_EN_AR
            tag.lowercase().startsWith("ar") -> ARABIC
            else -> ENGLISH
        }

        const val AUTO_TAG = "auto"
    }
}

/**
 * Backend preference exposed in Settings. Deliberately vendor-neutral: the app never
 * claims a specific provider, because it does not control which one is installed.
 */
enum class RecognitionBackendPreference {
    /** On-device when available *and* the language is supported, else system default. */
    AUTO,

    /** On-device if at all possible; fall back to system default rather than fail. */
    PREFER_ON_DEVICE,

    /** Whatever the platform picks by default (usually the network recognizer). */
    SYSTEM_DEFAULT
}

/** Which recognizer actually served a request — reported only when we truly know. */
enum class RecognitionBackendKind {
    ON_DEVICE,
    SYSTEM,
    UNKNOWN
}

// ------------------------------------------------------------------ endpointing

/**
 * Named endpoint profiles. These are the *only* user-visible answer-length knob
 * ([AnswerEndpointProfile]); the millisecond hints below stay internal policy (§16/§128).
 */
enum class EndpointProfile {
    /** Fast endpoint: "Good", "Repeat", "Pause". */
    SHORT_COMMAND,

    /** Natural thinking pauses allowed. Default for answers. */
    NORMAL_ANSWER,

    /** Tolerant of multiple pauses; for long structured medical answers. */
    LONG_ANSWER,

    /** The user controls start/stop; the endpointer should be maximally patient. */
    PUSH_TO_TALK
}

/**
 * Silence-length hints for `RecognizerIntent`.
 *
 * IMPORTANT: `EXTRA_SPEECH_INPUT_*_SILENCE_LENGTH_MILLIS` are documented by Android as
 * producing "unexpected behavior" and may be ignored entirely depending on the
 * recognition implementation. They are *hints only* — no correctness in this subsystem
 * depends on them being honoured, and the watchdog (§92) is what actually bounds a turn.
 */
data class EndpointPolicy(
    val profile: EndpointProfile,
    /** Hint for "definitely done speaking". */
    val completeSilenceHintMs: Long,
    /** Hint for "probably done speaking". */
    val possiblyCompleteSilenceHintMs: Long,
    /**
     * When true the hints are attached to the intent. Disabled by default for the
     * system recognizer because unvalidated values have been observed to shorten or
     * lengthen endpoints unpredictably across providers (§17).
     */
    val applySilenceHints: Boolean = false
) {
    companion object {
        /** Platform defaults — used unless a profile has a validated reason to override. */
        fun platformDefault(profile: EndpointProfile): EndpointPolicy = EndpointPolicy(
            profile = profile,
            completeSilenceHintMs = 0L,
            possiblyCompleteSilenceHintMs = 0L,
            applySilenceHints = false
        )
    }
}

// ------------------------------------------------------------------ request

/**
 * One explicit recognition turn.
 *
 * [id] is the backbone of stale-callback protection: every event carries it, and the
 * orchestrator drops anything that does not match the active request. A delayed result
 * from card N therefore can never be applied to card N+1.
 */
data class RecognitionRequest(
    val id: String,
    val purpose: RecognitionPurpose,
    val languageMode: RecognitionLanguageMode = RecognitionLanguageMode.AUTO_EN_AR,
    val backendPreference: RecognitionBackendPreference = RecognitionBackendPreference.AUTO,
    val preferOnDevice: Boolean = true,
    /** When false, a network failure is terminal instead of retried on another backend. */
    val allowNetworkFallback: Boolean = true,
    val partialResults: Boolean = true,
    val maxCandidates: Int = 5,
    val endpointPolicy: EndpointPolicy = EndpointPolicy.platformDefault(EndpointProfile.NORMAL_ANSWER),
    /** Bounded, de-duplicated contextual bias list. Never contains an expected answer. */
    val vocabularyHints: List<String> = emptyList(),
    /** BCP-47 tag used when the recognizer runs a single language. */
    val englishLocale: String = "en-US",
    val arabicLocale: String = "ar-IQ",
    /** Locale AUTO falls back to when detection/switching is unavailable (§31). */
    val autoFallbackLocale: String = "en-US",
    /** Card this turn belongs to, for stale-callback validation in study logic. */
    val cardId: String? = null,
    /** Opt-in developer logging of full transcript text. Off by default (§46). */
    val debugTranscriptLogging: Boolean = false,
    /** Purpose-specific watchdog budget, resolved by [RecognitionPolicyFactory]. */
    val timeouts: RecognitionTimeouts = RecognitionTimeouts.DEFAULT,
    /** Request creation time, for latency metrics. */
    val createdAtMs: Long = 0L
) {
    /** Short label used in logs/diagnostics — never includes transcript content. */
    val label: String
        get() = purpose.label
}

/**
 * Watchdog budgets (§92/§93). Purpose-specific on purpose: a rating must time out fast so
 * a noisy room cannot stall the loop, while a long answer needs room to think.
 */
data class RecognitionTimeouts(
    /** Start accepted → `onReadyForSpeech`. */
    val readyMs: Long,
    /** `onReadyForSpeech` → `onBeginningOfSpeech`. */
    val firstSpeechMs: Long,
    /** `onEndOfSpeech` / `stopListening()` → terminal `onResults`/`onError`. */
    val finalResultMs: Long,
    /** Hard ceiling for the whole turn (ignored for push-to-talk, which is user-bounded). */
    val totalMs: Long
) {
    companion object {
        val DEFAULT = RecognitionTimeouts(
            readyMs = 4_000L,
            firstSpeechMs = 12_000L,
            finalResultMs = 8_000L,
            totalMs = 45_000L
        )
    }
}

// ------------------------------------------------------------------ state

/**
 * Deterministic recognition lifecycle (§6/§7):
 *
 * ```
 * IDLE → PREPARING → READY_FOR_SPEECH → LISTENING → SPEECH_DETECTED
 *      → PROCESSING → COMPLETED → IDLE
 * ```
 *
 * The critical invariant: `onEndOfSpeech()` moves to [Processing], **not** idle. A turn is
 * only finished by [Completed] or [Failed], so the next turn can never be started while
 * the recognizer is still working — which is what produces `ERROR_RECOGNIZER_BUSY`.
 */
sealed interface RecognitionState {
    val requestId: String?

    /** No recognition service on this device. Nothing will ever listen. */
    data object Unavailable : RecognitionState {
        override val requestId: String? = null
    }

    /** Service available, no turn in flight. */
    data object Idle : RecognitionState {
        override val requestId: String? = null
    }

    data class Preparing(
        override val requestId: String,
        val purpose: RecognitionPurpose,
        val backend: RecognitionBackendKind
    ) : RecognitionState

    data class ReadyForSpeech(
        override val requestId: String,
        val purpose: RecognitionPurpose
    ) : RecognitionState

    data class Listening(
        override val requestId: String,
        val purpose: RecognitionPurpose,
        val partialTranscript: String = ""
    ) : RecognitionState

    /** `onBeginningOfSpeech` fired — the user is talking. */
    data class SpeechDetected(
        override val requestId: String,
        val purpose: RecognitionPurpose,
        val partialTranscript: String = ""
    ) : RecognitionState

    /**
     * Speech ended; waiting for the terminal callback. **Still busy** — starting a new
     * turn here is what caused the busy race.
     */
    data class Processing(
        override val requestId: String,
        val purpose: RecognitionPurpose
    ) : RecognitionState

    data class Completed(
        override val requestId: String,
        val outcome: RecognitionOutcome
    ) : RecognitionState

    data class Failed(
        override val requestId: String?,
        val error: RecognitionError
    ) : RecognitionState

    /** Terminal shutdown; the recognizer has been destroyed. */
    data object Released : RecognitionState {
        override val requestId: String? = null
    }

    /** True while a turn owns the microphone. */
    val isActive: Boolean
        get() = this is Preparing ||
            this is ReadyForSpeech ||
            this is Listening ||
            this is SpeechDetected ||
            this is Processing

    /** True when a new turn may be started (the busy-race readiness signal). */
    val isReadyForNewRequest: Boolean
        get() = !isActive && this !is Unavailable && this !is Released

    /** Short user-facing label, for the Diagnostics screen. */
    val label: String
        get() = when (this) {
            Unavailable -> "Unavailable"
            Idle -> "Idle"
            is Preparing -> "Preparing"
            is ReadyForSpeech -> "Ready for speech"
            is Listening -> "Listening"
            is SpeechDetected -> "Speech detected"
            is Processing -> "Processing"
            is Completed -> "Completed"
            is Failed -> "Failed"
            Released -> "Released"
        }
}

// ------------------------------------------------------------------ results

/** Where a transcript came from. Reported only when actually known. */
enum class RecognitionSource {
    ON_DEVICE,
    NETWORK,
    UNKNOWN
}

/** One recognizer alternative with its rank and (optional) confidence. */
data class RecognitionHypothesis(
    val text: String,
    /** `null` when the provider did not supply `CONFIDENCE_SCORES`. Never faked. */
    val confidence: Float?,
    val rank: Int
) {
    val isBlank: Boolean get() = text.isBlank()
}

/**
 * A finished recognition turn.
 *
 * [hypotheses] keeps every alternative the provider returned; [selectedText] is the one
 * the [RecognitionCandidateSelector] chose for this purpose. For answers that is normally
 * rank 0; for ratings it may legitimately be a lower-ranked candidate that matches the
 * controlled grammar (§12).
 */
data class RecognitionOutcome(
    val requestId: String,
    val purpose: RecognitionPurpose,
    val cardId: String? = null,
    val hypotheses: List<RecognitionHypothesis>,
    val selectedText: String,
    val selectedHypothesis: RecognitionHypothesis?,
    val detectedLanguage: String? = null,
    val source: RecognitionSource = RecognitionSource.UNKNOWN,
    val durationMs: Long? = null,
    /** Time from `onEndOfSpeech` to the terminal callback — the user-perceived latency. */
    val finalizationLatencyMs: Long? = null
) {
    val topConfidence: Float?
        get() = selectedHypothesis?.confidence ?: hypotheses.firstNotNullOfOrNull { it.confidence }

    val isEmpty: Boolean get() = selectedText.isBlank()

    /**
     * Privacy-safe one-line summary for logs (§46). Never contains transcript content.
     */
    fun logSummary(): String = buildString {
        append("purpose=").append(purpose.name)
        append(" chars=").append(selectedText.length)
        append(" candidates=").append(hypotheses.size)
        topConfidence?.let { append(" conf=").append(String.format("%.2f", it)) }
        detectedLanguage?.let { append(" lang=").append(it) }
        append(" source=").append(source.name)
        finalizationLatencyMs?.let { append(" finalizeMs=").append(it) }
    }
}

// ------------------------------------------------------------------ errors

/**
 * Typed recognition errors (§47/§48/§49).
 *
 * [NO_SPEECH] (nothing audible) and [NO_MATCH] (audio present, nothing recognisable) are
 * deliberately distinct — they need different user-facing prompts and different retry
 * policies. The old code collapsed both into one `NoSpeech` event.
 */
enum class RecognitionErrorCode(
    /** Safe to retry automatically, subject to the bounded-retry budget. */
    val recoverable: Boolean,
    /** The user must do something (grant permission, install a model, ...). */
    val requiresUserAction: Boolean,
    /** Default recommendation surfaced to the orchestrator's retry policy. */
    val recommendedRetry: Boolean,
    /** Human-readable, transcript-free description. */
    val label: String
) {
    PERMISSION_DENIED(false, true, false, "Microphone permission not granted"),
    UNAVAILABLE(false, true, false, "No speech recognition service on this device"),
    NO_SPEECH(true, false, true, "No speech was detected"),
    NO_MATCH(true, false, true, "Speech was detected but could not be recognised"),
    BUSY(true, false, true, "Recognizer is busy with a previous request"),
    AUDIO_FAILURE(true, false, true, "Microphone recording failed"),
    NETWORK_UNAVAILABLE(true, false, true, "No network connection for recognition"),
    NETWORK_TIMEOUT(true, false, true, "Recognition service timed out"),
    SERVER_ERROR(true, false, true, "Recognition service error"),
    SERVER_DISCONNECTED(true, false, true, "Disconnected from the recognition service"),
    TOO_MANY_REQUESTS(true, false, true, "Recognition service is rate limiting"),
    LANGUAGE_UNSUPPORTED(false, true, false, "This language is not supported"),
    LANGUAGE_MODEL_UNAVAILABLE(false, true, false, "Offline model for this language is not installed"),
    SUPPORT_CHECK_FAILED(true, false, false, "Could not verify recognition support"),
    CLIENT_ERROR(true, false, true, "Recognition client error"),
    WATCHDOG_TIMEOUT(true, false, true, "Recognizer did not respond in time"),
    CANCELLED(false, false, false, "Recognition was cancelled"),
    /** Any error code this SDK does not know about — degrades safely, keeps the raw code. */
    INTERNAL(true, false, false, "Recognition failed")
}

/** An error plus the diagnostics needed to act on it. */
data class RecognitionError(
    val code: RecognitionErrorCode,
    val requestId: String? = null,
    /** Raw `SpeechRecognizer.ERROR_*` constant, preserved for Diagnostics (§27). */
    val rawCode: Int? = null,
    val detail: String? = null
) {
    val recoverable: Boolean get() = code.recoverable
    val requiresUserAction: Boolean get() = code.requiresUserAction
    val recommendedRetry: Boolean get() = code.recommendedRetry

    /** User-facing message. Never contains transcript content. */
    val userMessage: String
        get() = detail?.takeIf { it.isNotBlank() } ?: code.label
}

// ------------------------------------------------------------------ capabilities

/**
 * What this device can actually do (§24/§25).
 *
 * `null` means "could not determine" and is rendered as *Unknown* in Diagnostics — never
 * silently coerced to false, because that would lie to the user about offline support.
 */
data class RecognitionCapabilities(
    val recognitionAvailable: Boolean = false,
    val onDeviceAvailable: Boolean? = null,
    val supportedLanguages: Set<String> = emptySet(),
    val installedLanguages: Set<String> = emptySet(),
    val languageDetectionSupported: Boolean? = null,
    val languageSwitchSupported: Boolean? = null,
    val vocabularyBiasingSupported: Boolean? = null,
    val segmentedSessionSupported: Boolean? = null,
    /** Recognizer service package, for the advanced diagnostics section only (§84). */
    val defaultRecognizerPackage: String? = null,
    val onDeviceRecognizerPackage: String? = null,
    /** When this snapshot was taken — capability data is cached (§119). */
    val capturedAtMs: Long = 0L
) {
    fun isLanguageSupported(tag: String?): Boolean? {
        val t = tag ?: return null
        if (supportedLanguages.isEmpty()) return null
        val lower = t.lowercase()
        val primary = lower.substringBefore('-')
        return supportedLanguages.any {
            val s = it.lowercase()
            s == lower || s == primary || s.substringBefore('-') == primary
        }
    }

    fun isLanguageInstalled(tag: String?): Boolean? {
        val t = tag ?: return null
        if (installedLanguages.isEmpty()) return null
        val primary = t.lowercase().substringBefore('-')
        return installedLanguages.any {
            val s = it.lowercase()
            s == t.lowercase() || s.substringBefore('-') == primary
        }
    }

    companion object {
        val UNKNOWN = RecognitionCapabilities()
    }
}

/** Lightweight, privacy-safe local metrics (§90/§91). No transcript text is ever stored. */
data class RecognitionMetrics(
    val completedTurns: Int = 0,
    val failedTurns: Int = 0,
    val cancelledTurns: Int = 0,
    val noSpeechCount: Int = 0,
    val noMatchCount: Int = 0,
    val busyErrors: Int = 0,
    val networkErrors: Int = 0,
    val watchdogTimeouts: Int = 0,
    val rateLimitRejections: Int = 0,
    val staleCallbacksDropped: Int = 0,
    val onDeviceTurns: Int = 0,
    val networkTurns: Int = 0,
    /** Start accepted → `onReadyForSpeech`. -1 until observed. */
    val lastReadyLatencyMs: Long = -1L,
    val avgReadyLatencyMs: Long = -1L,
    /** `onEndOfSpeech` → terminal callback. -1 until observed. */
    val lastFinalizationMs: Long = -1L,
    val avgFinalizationMs: Long = -1L,
    val lastConfidence: Float? = null,
    /** Sample counts backing the two running averages above. */
    val readySamples: Int = 0,
    val finalizationSamples: Int = 0
)

/** Diagnostics-facing snapshot (§89). */
data class RecognitionHealthSnapshot(
    val state: RecognitionState = RecognitionState.Idle,
    val activePurpose: RecognitionPurpose? = null,
    val activeRequestId: String? = null,
    /** Age of the in-flight turn, for "Request age 4.2s". -1 when idle. */
    val activeRequestAgeMs: Long = -1L,
    val backendInUse: RecognitionBackendKind = RecognitionBackendKind.UNKNOWN,
    val capabilities: RecognitionCapabilities = RecognitionCapabilities.UNKNOWN,
    val inputRouteLabel: String = "Unknown",
    val lastError: RecognitionError? = null,
    val lastConfidence: Float? = null,
    val retryAttempt: Int = 0,
    val metrics: RecognitionMetrics = RecognitionMetrics()
)
