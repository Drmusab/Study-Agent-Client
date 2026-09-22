package com.studyagent.client.core.voice.tts

import com.studyagent.client.core.common.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Production provider router: local Android backend + lazy cloud backends,
 * all observed through ONE [RemoteTtsTransport] (the authenticated PC Study
 * Agent). Owns the [TtsProviderHealth] the Settings UI and diagnostics read.
 *
 * Fallback policy (master prompt §13/§38/§39):
 *  - select(): preferred cloud provider NOT available (agent down, provider
 *    unconfigured/unhealthy) → speak with the local backend right away and
 *    record a transient fallback event. The PREFERRED setting is never
 *    rewritten.
 *  - Runtime failures BEFORE audible playback are handled inside
 *    [RemoteSpeechBackend] (local retry, no duplication); AFTER meaningful
 *    playback the request simply fails with a typed error.
 */
class ProviderBackendRouter(
    private val engine: TtsEngineAdapter,
    private val transport: RemoteTtsTransport,
    private val settingsProvider: () -> TtsSettings,
    private val playerFactory: (TtsProvider) -> StreamingSpeechPlayer,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Long = { System.currentTimeMillis() }
) : SpeechBackendRouter {

    private val tag = "TtsRouter"

    override val androidBackend: SpeechBackend = AndroidSpeechBackend(engine)

    private val remoteBackends = HashMap<TtsProvider, RemoteSpeechBackend>()

    override val backends: List<SpeechBackend>
        get() = synchronized(remoteBackends) {
            listOf(androidBackend) + remoteBackends.values.toList()
        }

    private val _providerHealth = MutableStateFlow(initialHealth())
    override val providerHealth: StateFlow<TtsProviderHealth> = _providerHealth.asStateFlow()

    private val _lastFallback = MutableStateFlow<TtsFallbackEvent?>(null)
    override val lastFallback: StateFlow<TtsFallbackEvent?> = _lastFallback.asStateFlow()

    @Volatile
    private var released = false

    init {
        // Track agent availability; refresh capabilities when the agent (re)connects
        // and starts advertising TTS.
        scope.launch {
            combineConnectionSignals()
                .collectLatest { connectedAndSupports ->
                    if (!connectedAndSupports) {
                        _providerHealth.value = _providerHealth.value.copy(
                            agentConnected = transport.available.value,
                            agentSupportsTts = transport.supportsTts.value,
                            providers = markAllUnavailable(_providerHealth.value.providers)
                        )
                        return@collectLatest
                    }
                    refreshCapabilities(reason = "agent-connected")
                }
        }
    }

    private suspend fun combineConnectionSignals() = kotlinx.coroutines.flow.combine(
        transport.available,
        transport.supportsTts
    ) { available, supports -> available && supports }.distinctUntilChanged()

    // ------------------------------------------------------------------ selection

    override fun select(request: SpeechRequest): SpeechBackend {
        val cfg = settingsProvider()
        val provider = cfg.ttsProvider
        if (provider == TtsProvider.ANDROID) return androidBackend

        val health = _providerHealth.value
        val info = health.info(provider)
        if (info != null && info.available) {
            return remoteBackendFor(provider, cfg)
        }

        // Preferred provider not available right now → local fallback for this
        // request only (preferred setting untouched). Transient notice via
        // lastFallback so the study UI can show "…using device voice".
        val reason = when {
            !health.agentConnected -> "PC Study Agent not connected"
            !health.agentSupportsTts -> "PC Study Agent does not offer remote TTS"
            info == null -> "provider not reported by the agent"
            !info.configured -> "${provider.label} is not configured on the PC Study Agent"
            else -> "${provider.label} is currently unavailable"
        }
        _lastFallback.value = TtsFallbackEvent(provider, reason, clock(), request.id)
        AppLogger.w(tag, "Fallback ${provider.label} → Android for ${request.purpose}: $reason")
        return androidBackend
    }

    private fun remoteBackendFor(provider: TtsProvider, cfg: TtsSettings): RemoteSpeechBackend =
        synchronized(remoteBackends) {
            remoteBackends.getOrPut(provider) {
                RemoteSpeechBackend(
                    provider = provider,
                    transport = transport,
                    androidDelegate = androidBackend,
                    playerFactory = { playerFactory(provider) },
                    cacheProvider = {
                        if (cfg.cloudTtsCache == CloudTtsCachePolicy.SESSION) sessionCache else null
                    },
                    settingsProvider = { settingsProvider() },
                    clock = clock
                )
            }
        }

    /** Session-scoped cloud cache, shared by all remote backends (bounded). */
    private val sessionCache = CloudTtsCache()

    // ------------------------------------------------------------------ health

    private fun initialHealth(): TtsProviderHealth = TtsProviderHealth(
        agentConnected = transport.available.value,
        agentSupportsTts = transport.supportsTts.value
    )

    private fun markAllUnavailable(
        providers: Map<TtsProvider, RemoteProviderInfo>
    ): Map<TtsProvider, RemoteProviderInfo> = providers.mapValues { (_, info) ->
        info.copy(connected = false, available = false)
    }

    /** Refresh provider capability/health from the agent (idempotent, bounded). */
    fun refreshCapabilities(reason: String = "manual") {
        if (released) return
        scope.launch {
            val health = _providerHealth.value
            if (!health.agentConnected || !health.agentSupportsTts) return@launch
            val started = clock()
            try {
                val infos = transport.fetchCapabilities(CAPABILITY_TIMEOUT_MS)
                val android = RemoteProviderInfo(
                    provider = TtsProvider.ANDROID,
                    connected = true,
                    configured = true,
                    healthy = true,
                    supportsRate = true,
                    supportsPitch = true
                )
                val providers = HashMap<TtsProvider, RemoteProviderInfo>()
                providers[TtsProvider.ANDROID] = android
                for (info in infos) {
                    providers[info.provider] = info.copy(
                        connected = true,
                        lastCheckedAtMs = clock()
                    )
                }
                _providerHealth.value = TtsProviderHealth(
                    agentConnected = true,
                    agentSupportsTts = true,
                    providers = providers
                )
                // Propagate capability changes to live remote backends.
                for (backend in backends) {
                    (backend as? RemoteSpeechBackend)?.updateProviderInfo(providers[backend.provider])
                }
                AppLogger.i(tag, "TTS capabilities refreshed ($reason) in ${clock() - started}ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(tag, "TTS capability refresh failed ($reason): ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ router API

    override fun stopActive() {
        // Stop every backend: only one can be producing audio, but stopping the
        // rest is idempotent and covers mid-request provider switches.
        for (backend in backends) {
            try {
                backend.stop()
            } catch (e: Exception) {
                AppLogger.w(tag, "backend ${backend.id} stop failed: ${e.message}")
            }
        }
    }

    override fun updateSettings(settings: TtsSettings) {
        for (backend in backends) {
            (backend as? RemoteSpeechBackend)?.onSettingsChanged(settings)
        }
    }

    fun releaseBackend(provider: TtsProvider) {
        synchronized(remoteBackends) {
            remoteBackends.remove(provider)?.release()
        }
    }

    override fun release() {
        released = true
        for (backend in backends) {
            try {
                backend.release()
            } catch (e: Exception) {
                AppLogger.w(tag, "backend ${backend.id} release failed: ${e.message}")
            }
        }
    }

    companion object {
        const val CAPABILITY_TIMEOUT_MS = 10_000L
    }
}
