package com.studyagent.client.core.voice.tts

import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * One platform utterance: already preprocessed, single-locale text plus the
 * exact runtime configuration to apply. The engine adapter applies this
 * verbatim — all content intelligence lives upstream in the orchestrator.
 */
data class EngineUtterance(
    val utteranceId: String,
    val text: String,
    val locale: Locale,
    /** Engine voice name for this locale, or null to use [locale] via setLanguage(). */
    val voiceName: String? = null,
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f
)

enum class EngineStatus {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    FAILED,
    RELEASED
}

/**
 * Low-level speech engine abstraction (one engine instance per app lifetime).
 *
 * The Android implementation hides `TextToSpeech`'s callback API completely:
 * [speak] suspends and returns exactly one [SpeechResult]; cancellation of the
 * suspending call stops the engine utterance. Callback cleanup on every
 * terminal path is the implementation's contract, verified by tests against
 * the fake.
 *
 * Keeping this interface coroutine-first keeps the orchestrator (and future
 * alternative backends — see docs/TTS_ARCHITECTURE.md §Future providers) free
 * of Android framework types.
 */
interface TtsEngineAdapter {

    val status: StateFlow<EngineStatus>

    /**
     * Optional observability hook, invoked with the utteranceId when the engine
     * actually starts synthesizing it (metrics: request→start latency).
     */
    var utteranceStartedListener: ((String) -> Unit)?
        get() = null
        set(_) {}

    /**
     * Speak one utterance end-to-end.
     *
     * Initialization policy: if the engine is still initializing, wait for it
     * (bounded); never silently drop the utterance. Returns
     * [SpeechResult.Failed] with a typed [SpeechErrorCode] on any terminal
     * failure. Never throws for engine-level problems.
     */
    suspend fun speak(utterance: EngineUtterance): SpeechResult

    /** Stop the current utterance; every pending [speak] completes [SpeechResult.Cancelled]. */
    fun stop()

    /** Platform max chars for a single speak call (TextToSpeech.getMaxSpeechInputLength). */
    fun maxSpeechInputLength(): Int

    /** Voices installed for the *current* engine (normalized, framework-free). */
    suspend fun queryVoices(): List<TtsVoiceInfo>

    /** All installed TTS engines on the device. */
    suspend fun queryEngines(): List<TtsEngineInfo>

    /** Switch engine (null → system default); reinitializes asynchronously. */
    fun setEngine(enginePackage: String?)

    /** Stop + shutdown; all pending calls complete [SpeechResult.Cancelled]. Terminal. */
    fun release()
}
