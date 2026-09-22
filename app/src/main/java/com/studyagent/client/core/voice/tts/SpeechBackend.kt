package com.studyagent.client.core.voice.tts

import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * The generalized speech backend seam (master prompt §7).
 *
 * [SpeechOrchestrator] remains the ONLY speech API — it now drives a
 * [SpeechBackendRouter] that picks ONE of these backends per request:
 *
 *   - [AndroidSpeechBackend]  — the existing, fully-tested local TextToSpeech path
 *                                (default provider; offline; always available).
 *   - [RemoteSpeechBackend]   — cloud providers (OpenAI / ElevenLabs) reached
 *                                through the authenticated PC Study Agent
 *                                (WebSocket control plane + authenticated HTTP
 *                                PCM media plane + dedicated streaming player).
 *
 * What stays provider-agnostic (inside the orchestrator): queue policy,
 * cancellation, audio focus, route-loss, TTS→STT handoff, duplicate
 * suppression, exactly-once completion. What is provider-specific (catalog
 * shape, synthesis call, media streaming, model mapping) stays inside each
 * backend. No per-provider orchestrators, no per-provider study flows (§90).
 */
enum class SpeechBackendKind {
    /** Local TextToSpeech engine (the proven, default path). */
    ANDROID_LOCAL,

    /** Cloud provider streamed from the PC Study Agent. */
    REMOTE
}

/**
 * Provider capability block (from agent capabilities / local knowledge).
 * The Settings UI gates provider-specific controls on these (§15), and the
 * orchestrator only sends knobs a backend actually understands.
 */
data class SpeechBackendCapabilities(
    val streaming: Boolean = false,
    /** One voice can speak both supported languages (cloud multilingual voices). */
    val multilingual: Boolean = false,
    val supportsRate: Boolean = true,
    val supportsPitch: Boolean = true,
    val supportsStyleInstructions: Boolean = false,
    val supportsVoiceSettings: Boolean = false,
    val supportsCustomVoices: Boolean = false,
    /** Provider max input chars per synthesis call; null = unknown (use a safe default). */
    val maxInputChars: Int? = null
)

/**
 * One provider utterance: already preprocessed, single-language text plus the
 * resolved runtime configuration. The backend applies it verbatim — all
 * content intelligence (preprocessing, medical policy, segmentation, voice
 * choice) lives upstream in the orchestrator, exactly as with [EngineUtterance].
 */
data class BackendUtterance(
    val utteranceId: String,
    val text: String,
    val language: SegmentLanguage,
    /** Preferred locale (used by the Android backend; remote backends may ignore). */
    val locale: Locale? = null,
    /**
     * Resolved voice: engine voice NAME for [SpeechBackendKind.ANDROID_LOCAL],
     * namespaced id ("openai:coral") for [SpeechBackendKind.REMOTE]. Null =
     * backend default.
     */
    val voiceName: String? = null,
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
    /** OpenAI-style natural-language delivery instructions (when supported). */
    val styleInstructions: String? = null,
    /**
     * Provider-specific style knobs (ElevenLabs voice settings, quality
     * profile...). Passed through opaquely; the PC Agent owns interpretation.
     */
    val providerOptions: Map<String, Any> = emptyMap()
)

/**
 * Normalized, provider-agnostic voice description (§69/§70).
 *
 * [id] is ALWAYS namespaced ("openai:coral", "elevenlabs:<voiceId>") — one
 * opaque, provider-qualified identifier Android can persist without knowing
 * provider internals, and which cannot collide across providers.
 */
data class SpeechVoice(
    val provider: TtsProvider,
    val id: String,
    val displayName: String,
    /** ISO 639-1 codes this voice handles ("en", "ar"). */
    val languages: List<String> = emptyList(),
    /** BCP-47 tag when known (for display), e.g. "en-US". */
    val localeTag: String? = null,
    val networkRequired: Boolean = false,
    val custom: Boolean = false,
    val previewAvailable: Boolean = true,
    val category: String? = null,
    val description: String? = null
) {
    val languageCodes: Set<String>
        get() = languages.map { it.lowercase() }.toSet()

    fun speaks(language: SegmentLanguage): Boolean = when (language) {
        SegmentLanguage.ARABIC -> "ar" in languageCodes
        else -> "en" in languageCodes
    }

    companion object {
        /** Namespaced voice id: "provider:localId". */
        fun namespacedId(provider: TtsProvider, localId: String): String =
            "${provider.storageId}:$localId"

        /** Split a namespaced id; null when it does not belong to [provider]. */
        fun localIdOf(id: String?, provider: TtsProvider): String? {
            if (id.isNullOrBlank()) return null
            val prefix = "${provider.storageId}:"
            return if (id.startsWith(prefix)) id.removePrefix(prefix).ifBlank { null }
            else null
        }
    }
}

/**
 * One speech backend. [speak] has the exact same contract as
 * [TtsEngineAdapter.speak]: it suspends and returns exactly ONE [SpeechResult];
 * cancelling the suspending call must stop audio immediately and propagate
 * [kotlinx.coroutines.CancellationException] so the orchestrator can map the
 * stop reason.
 */
interface SpeechBackend {

    /** Stable backend id: "android" | "openai" | "elevenlabs". */
    val id: String

    val kind: SpeechBackendKind

    /** Provider for REMOTE backends (the local backend reports [TtsProvider.ANDROID]). */
    val provider: TtsProvider

    /** Lifecycle — same states as the local engine (READY = usable now). */
    val status: StateFlow<EngineStatus>

    val capabilities: SpeechBackendCapabilities

    /**
     * Observability hook, invoked when audio for the utterance actually starts
     * (local: engine onStart; remote: first audio frame written to the player).
     */
    var utteranceStartedListener: ((String) -> Unit)?
        get() = null
        set(_) {}

    /**
     * Speak one utterance end-to-end. Terminal failures return a typed
     * [SpeechResult.Failed]; cancellation throws [kotlinx.coroutines.CancellationException]
     * after silencing.
     */
    suspend fun speak(utterance: BackendUtterance): SpeechResult

    /** Voice catalog for [languageCode] ("en"/"ar"/null = all) — for Settings. */
    suspend fun queryVoices(languageCode: String? = null): List<SpeechVoice>

    /**
     * Max chars for one speak call. Local: the platform's real limit. Remote:
     * the provider's input limit (the PC Agent chunks internally beyond it).
     */
    fun maxSpeechInputLength(): Int

    /** Stop current audio; every pending [speak] completes Cancelled (or throws). */
    fun stop()

    /** Terminal shutdown; pending calls complete Cancelled. */
    fun release()
}
