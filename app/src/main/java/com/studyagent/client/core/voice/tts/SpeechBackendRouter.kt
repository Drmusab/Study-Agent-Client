package com.studyagent.client.core.voice.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-provider availability as observed from the PC Study Agent
 * (master prompt §29/§33/§72). This is what the router and the Settings UI
 * read; it is derived from `tts_capabilities` + connection state and is
 * refreshed on connect and on demand — never "Ready" merely because a key
 * exists.
 */
data class TtsProviderHealth(
    /** The agent WebSocket is connected. */
    val agentConnected: Boolean = false,
    /** The connected agent advertised the "tts" capability. */
    val agentSupportsTts: Boolean = false,
    val providers: Map<TtsProvider, RemoteProviderInfo> = emptyMap()
) {
    fun info(provider: TtsProvider): RemoteProviderInfo? = providers[provider]

    fun isAvailable(provider: TtsProvider): Boolean =
        provider != TtsProvider.ANDROID && info(provider)?.available == true
}

/**
 * One fallback occurrence (for the transient "OpenAI voice unavailable. Using
 * device voice." notice, §137). The PREFERRED provider setting is NEVER
 * rewritten by this — fallback is always per-request and temporary (§14/§39).
 */
data class TtsFallbackEvent(
    val provider: TtsProvider,
    val reason: String,
    val atMs: Long,
    val speechRequestId: String
)

/**
 * Chooses which [SpeechBackend] speaks a request (master prompt §90: the
 * router sits UNDER the one orchestrator — no per-provider orchestrators).
 *
 * Contract:
 *  - [select] is cheap and side-effect-light; it must never block.
 *  - Fallback is decided here (known-unavailable → local backend immediately)
 *    and inside [RemoteSpeechBackend] (runtime failure BEFORE audible
 *    playback → local retry; after meaningful playback → no duplicate).
 *  - [stopActive] stops whichever backend is currently producing audio.
 */
interface SpeechBackendRouter {

    /** The local Android backend (always present; the fallback target). */
    val androidBackend: SpeechBackend

    /** All backends (local + cloud) — used for listener wiring and release. */
    val backends: List<SpeechBackend>

    /** Live provider health (agent + per-provider availability). */
    val providerHealth: StateFlow<TtsProviderHealth>

    /** Most recent fallback occurrence (transient UI notice source). */
    val lastFallback: StateFlow<TtsFallbackEvent?>

    /** Pick the backend for this request given current settings. */
    fun select(request: SpeechRequest): SpeechBackend

    /** Stop whatever is currently speaking (focus pause, stop, release). */
    fun stopActive()

    /** Apply new settings (provider/voice/quality changes, catalog invalidation). */
    fun updateSettings(settings: TtsSettings)

    /** Terminal: releases every backend. */
    fun release()
}

/**
 * Default router: the existing single-engine behavior, unchanged.
 * Used by every JVM test that builds the orchestrator with a fake engine —
 * local-only apps and the entire pre-existing test suite keep byte-identical
 * behavior.
 */
class AndroidOnlyBackendRouter(
    private val engine: TtsEngineAdapter
) : SpeechBackendRouter {

    override val androidBackend: SpeechBackend = AndroidSpeechBackend(engine)
    override val backends: List<SpeechBackend> = listOf(androidBackend)

    private val _providerHealth = MutableStateFlow(
        TtsProviderHealth(
            agentConnected = false,
            agentSupportsTts = false,
            providers = mapOf(TtsProvider.ANDROID to RemoteProviderInfo(
                provider = TtsProvider.ANDROID,
                connected = true,
                configured = true,
                healthy = true,
                supportsRate = true,
                supportsPitch = true
            ))
        )
    )
    override val providerHealth: StateFlow<TtsProviderHealth> = _providerHealth.asStateFlow()

    private val _lastFallback = MutableStateFlow<TtsFallbackEvent?>(null)
    override val lastFallback: StateFlow<TtsFallbackEvent?> = _lastFallback.asStateFlow()

    override fun select(request: SpeechRequest): SpeechBackend = androidBackend

    override fun stopActive() {
        androidBackend.stop()
    }

    override fun updateSettings(settings: TtsSettings) {
        // Local-only: nothing provider-specific to apply (engine switching is
        // handled by the orchestrator directly on the engine).
    }

    override fun release() {
        androidBackend.release()
    }
}
