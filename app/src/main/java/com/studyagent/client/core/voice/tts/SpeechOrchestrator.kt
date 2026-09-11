package com.studyagent.client.core.voice.tts

import kotlinx.coroutines.flow.StateFlow

/** Lightweight, bounds-checked local metrics for Diagnostics (never leaves the device). */
data class TtsMetrics(
    /** Engine creation → first READY. -1 while pending. */
    val timeToReadyMs: Long = -1,
    /** Request accepted → engine onStart for its first chunk. -1 until first utterance. */
    val lastRequestToStartMs: Long = -1,
    /** Playback wall time of the last completed request. */
    val lastRequestDurationMs: Long = -1,
    val completedRequests: Int = 0,
    val failedRequests: Int = 0,
    val cancelledRequests: Int = 0,
    val audioFocusDenials: Int = 0,
    val routeInterruptions: Int = 0
)

/** Diagnostics-facing, framework-free view of speech pipeline health. */
data class TtsHealthSnapshot(
    val engineStatus: EngineStatus = EngineStatus.UNINITIALIZED,
    val enginePackage: String? = null,
    val englishVoiceDisplay: String? = null,
    val arabicVoiceDisplay: String? = null,
    val englishVoiceOffline: Boolean? = null,
    val arabicVoiceOffline: Boolean? = null,
    val audioFocusHeld: Boolean = false,
    val queueDepth: Int = 0,
    val speakingPurpose: SpeechPurpose? = null,
    val lastError: SpeechError? = null,
    val metrics: TtsMetrics = TtsMetrics()
)

/**
 * The single speech API for the whole app.
 *
 * Study logic says *what* to say ([SpeechRequest]); the orchestrator owns everything
 * else: preprocessing, medical pronunciation, Arabic/English segmentation, voice
 * selection, chunking, audio focus, engine invocation, queue policy, cancellation,
 * timeouts, metrics and recovery state.
 *
 * Every accepted request completes exactly once with a [SpeechResult] — callers can
 * safely build "speak, then listen" flows on suspension instead of fragile callbacks.
 */
interface SpeechOrchestrator {

    /** Rich lifecycle (Uninitialized/Initializing/Ready/Speaking/Error/Released). */
    val ttsState: StateFlow<TtsState>

    /** Diagnostics view — queue depth, voices, errors, metrics. */
    val health: StateFlow<TtsHealthSnapshot>

    /** Legacy-friendly convenience (true iff a request is actively speaking). */
    val isSpeaking: StateFlow<Boolean>

    /** Engine reached READY at least once and is currently usable. */
    val isReady: StateFlow<Boolean>

    /**
     * Speak one logical request end-to-end. Suspends until the request completes,
     * is cancelled, or fails — never returns early, never completes twice.
     */
    suspend fun speak(request: SpeechRequest): SpeechResult

    /** Speak the built-in preview sentence for [language] with current voice/rate/pitch. */
    suspend fun speakPreview(language: SegmentLanguage): SpeechResult

    /**
     * Cancel current + queued speech. Pending callers receive the mapped terminal
     * result (ROUTE_LOST/FOCUS_LOST → Failed, everything else → Cancelled).
     * Safe to call from any thread; never blocks.
     */
    fun stopSpeech(reason: StopReason = StopReason.USER)

    /** Normalized voices installed for [languageCode] ("en", "ar") — for Settings. */
    suspend fun getVoices(languageCode: String): List<TtsVoiceInfo>

    /** Installed TTS engines — for Settings advanced section. */
    suspend fun getEngines(): List<TtsEngineInfo>

    /** Push new settings; voice caches invalidate only when relevant fields change. */
    fun updateSettings(settings: TtsSettings)

    /** Terminal shutdown: cancels everything and releases the engine. */
    fun release()
}
