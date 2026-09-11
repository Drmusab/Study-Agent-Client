package com.studyagent.client.core.voice.stt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Raw recognizer events, each stamped with the request that produced it (§9/§10).
 *
 * Stamping is not cosmetic: the orchestrator drops any event whose `requestId` is not the
 * active one, which is what makes a late `onResults` from a cancelled turn harmless instead
 * of an answer submitted against the wrong card.
 */
sealed interface RecognitionBackendEvent {
    val requestId: String

    /** `startListening` was accepted by the recognizer. */
    data class Started(
        override val requestId: String,
        val backend: RecognitionBackendKind
    ) : RecognitionBackendEvent

    data class ReadyForSpeech(override val requestId: String) : RecognitionBackendEvent

    data class SpeechBegan(override val requestId: String) : RecognitionBackendEvent

    data class Partial(
        override val requestId: String,
        val hypotheses: List<RecognitionHypothesis>
    ) : RecognitionBackendEvent

    /** `onEndOfSpeech` — the user stopped talking. The turn is NOT finished. */
    data class SpeechEnded(override val requestId: String) : RecognitionBackendEvent

    /** Terminal. */
    data class Results(
        override val requestId: String,
        val hypotheses: List<RecognitionHypothesis>,
        val detectedLanguage: String?,
        val source: RecognitionSource,
        val speechDurationMs: Long?,
        val finalizationLatencyMs: Long?
    ) : RecognitionBackendEvent

    /** Terminal. */
    data class Failed(
        override val requestId: String,
        val error: RecognitionError
    ) : RecognitionBackendEvent

    /**
     * Android 14+ language detection/switching (§32). Diagnostics and policy only — never
     * announced to the user mid-answer.
     */
    data class LanguageDetected(
        override val requestId: String,
        val languageTag: String?,
        val confidenceLevel: Int?,
        /**
         * Raw `SpeechRecognizer.LANGUAGE_SWITCH_RESULT_*` value, kept raw because the
         * platform's success constant is not stable enough to hard-code against.
         */
        val switchResult: Int?
    ) : RecognitionBackendEvent
}

/** Result of asking a backend to begin a turn. Synchronous, so callers can branch immediately. */
sealed interface RecognitionStartResult {
    data class Started(
        val requestId: String,
        val backend: RecognitionBackendKind
    ) : RecognitionStartResult

    /** Refused before the recognizer was touched (busy, unavailable, permission, ...). */
    data class Rejected(val error: RecognitionError) : RecognitionStartResult
}

/**
 * The lowest layer of the STT stack (§99).
 *
 * A backend wraps exactly one recognition engine and nothing else: no retry, no policy, no
 * study semantics. That separation is what lets the whole recognition lifecycle be tested
 * against [FakeSpeechRecognitionBackend] without a microphone.
 *
 * Contract every implementation must honour:
 *  1. [isBusy] becomes true the moment [start] returns [RecognitionStartResult.Started] and
 *     stays true until a terminal event ([RecognitionBackendEvent.Results] or
 *     [RecognitionBackendEvent.Failed]) or [cancel]. `stopListening()` does **not** clear it.
 *  2. Every event carries the `requestId` of the turn that produced it.
 *  3. Exactly one terminal event per accepted turn.
 *  4. All engine calls happen on the thread the engine requires (main, for Android).
 */
interface SpeechRecognitionBackend {

    /** Terminal + lifecycle events. Critical, low-volume, never crowded out. */
    val events: Flow<RecognitionBackendEvent>

    /**
     * Microphone level, as a conflated `StateFlow` rather than an event (§44/§122/§123).
     *
     * `onRmsChanged` can fire hundreds of times a second. Feeding that into the same buffer
     * as final results means a waveform animation can evict the transcript — so the level
     * lives on its own conflated channel, sampled to a UI-friendly cadence, and a slow
     * collector simply sees the newest value.
     */
    val audioLevel: StateFlow<Float>

    val capabilities: StateFlow<RecognitionCapabilities>

    /** True while a turn owns the recognizer — the real busy signal, not `isListening`. */
    val isBusy: Boolean

    val activeRequestId: String?

    fun start(request: RecognitionRequest): RecognitionStartResult

    /** Finish the current turn using the speech already captured; still yields a terminal event. */
    fun stopListening()

    /** Discard the current turn. Yields a terminal [RecognitionBackendEvent.Failed] (CANCELLED). */
    fun cancel()

    /** Re-query the platform. Cheap enough for Settings/Diagnostics; cached by the orchestrator. */
    fun refreshCapabilities(): RecognitionCapabilities

    /** Ask the platform to download the on-device model for [languageTag] (§26). */
    fun requestModelDownload(languageTag: String): Boolean

    /** Destroy the engine. Safe to call more than once. */
    fun release()
}
