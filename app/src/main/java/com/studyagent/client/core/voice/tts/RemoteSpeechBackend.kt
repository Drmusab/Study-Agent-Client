package com.studyagent.client.core.voice.tts

import com.studyagent.client.core.common.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * One cloud provider behind the [SpeechBackend] seam (master prompt §46-§53).
 *
 * One logical utterance = ONE remote synthesis stream (§57): the transport
 * requests a stream over the control plane, then the PCM media plane is
 * streamed into the dedicated player. Provider-side chunking stays on the PC
 * Agent — Android never makes one paid API call per local chunk.
 *
 * Completion semantics (§53/§93):
 *  - Completed ONLY after playback drain (last frame out of the hardware),
 *    never at network EOF.
 *  - Cancellation: close the media stream + stop the player immediately and
 *    rethrow [CancellationException] (the orchestrator maps the stop reason).
 *
 * Fallback semantics (§13/§110):
 *  - failure BEFORE meaningful audible playback (and policy = fallback) →
 *    the same utterance is spoken on the local Android backend. Nothing was
 *    heard, so there is no duplicated speech.
 *  - failure AFTER meaningful playback started → typed Failed; the local
 *    engine is NOT re-spoken (no duplicate), and the study flow's normal
 *    recovery (visual continue / explicit repeat) applies.
 *
 * Stale-stream protection (§51): every active stream carries a generation +
 * speech-request identity; late callbacks from an older request can never
 * complete or disturb the newer one.
 */
class RemoteSpeechBackend(
    override val provider: TtsProvider,
    private val transport: RemoteTtsTransport,
    private val androidDelegate: SpeechBackend,
    private val playerFactory: () -> StreamingSpeechPlayer,
    private val cacheProvider: () -> CloudTtsCache?,
    private val settingsProvider: () -> TtsSettings,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : SpeechBackend {

    private val tag = "TtsRemote:${provider.storageId}"

    override val id: String = provider.storageId
    override val kind: SpeechBackendKind = SpeechBackendKind.REMOTE

    private val _status = MutableStateFlow(EngineStatus.UNINITIALIZED)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    @Volatile
    override var utteranceStartedListener: ((String) -> Unit)? = null

    @Volatile
    private var providerInfo: RemoteProviderInfo? = null

    /** Catalog cache (bounded: one list per provider, refreshed on demand). */
    private val _voices = MutableStateFlow<List<SpeechVoice>?>(null)
    private val _voicesFetchedAtMs = MutableStateFlow(0L)

    @Volatile
    private var released = false

    @Volatile
    private var currentStreamId: String? = null

    // Stream identity for stale-callback protection (§51): generation monotonically
    // increases per speak(); the media source checks it before any completion.
    @Volatile
    private var streamGeneration = 0L
    @Volatile
    private var currentSpeechRequestId: String? = null

    override val capabilities: SpeechBackendCapabilities
        get() {
            val info = providerInfo
            return SpeechBackendCapabilities(
                streaming = true,
                multilingual = true,
                supportsRate = info?.supportsRate ?: true,
                supportsPitch = false, // cloud voices have no pitch control
                supportsStyleInstructions = info?.supportsStyleInstructions ?: false,
                supportsVoiceSettings = info?.supportsVoiceSettings ?: false,
                supportsCustomVoices = info?.supportsCustomVoices ?: false,
                maxInputChars = info?.maxInputChars ?: DEFAULT_MAX_INPUT_CHARS
            )
        }

    /** Called by the router when fresh capability/health arrives. */
    fun updateProviderInfo(info: RemoteProviderInfo?) {
        providerInfo = info
        if (released) return
        _status.value = when {
            info == null -> EngineStatus.UNINITIALIZED
            !info.available -> EngineStatus.FAILED
            else -> EngineStatus.READY
        }
    }

    fun onSettingsChanged(@Suppress("UNUSED_PARAMETER") settings: TtsSettings) {
        // Voice/quality changes invalidate nothing structural; the next utterance
        // simply uses the new values. Catalog refresh is user-triggered.
    }

    // ------------------------------------------------------------------ speak

    override suspend fun speak(utterance: BackendUtterance): SpeechResult {
        if (released) {
            return SpeechResult.Failed(SpeechError.notInitialized())
        }
        val info = providerInfo
        if (info == null || !info.available) {
            return SpeechResult.Failed(
                SpeechError(
                    SpeechErrorCode.PROVIDER_UNAVAILABLE,
                    "${provider.label} is not available right now. " +
                        "The PC Study Agent is offline or the provider is not configured.",
                    recoverable = true
                )
            )
        }
        if (utterance.text.isBlank()) return SpeechResult.Completed

        val generation = ++streamGeneration
        val speechRequestId = utterance.utteranceId
        currentSpeechRequestId = speechRequestId
        val cfg = settingsProvider()

        val startedAtMs = clock()
        var player: StreamingSpeechPlayer? = null
        var source: PcmByteSource? = null
        var firstAudioAtMs = -1L
        var bytesReceived = 0L
        var ttfbMs = -1L
        var cacheHit = false

        try {
            // 1. Resolve the stream: client cache first (repeat/preview replay),
            //    else one synthesis request over the control plane.
            val cache = if (cfg.cloudTtsCache == CloudTtsCachePolicy.SESSION) cacheProvider() else null
            val cacheKey = cache?.let {
                CloudTtsCacheKeys.key(
                    provider = provider,
                    voiceId = utterance.voiceName,
                    model = cfg.cloudModelIdFor(provider),
                    rate = utterance.rate,
                    options = utterance.providerOptions,
                    text = utterance.text
                )
            }
            val cached = cacheKey?.let { cache?.get(it) }
            val descriptor = if (cached != null) {
                cacheHit = true
                RemoteStreamDescriptor(
                    streamId = "cache_${generation}",
                    streamPath = "",
                    speechRequestId = speechRequestId,
                    format = "pcm_s16le",
                    sampleRate = DEFAULT_SAMPLE_RATE,
                    channels = 1,
                    expiresInSeconds = 0,
                    cacheHit = true,
                    estimatedDurationMs = 0,
                    fromClientCache = true,
                    cachedBytes = cached
                )
            } else {
                transport.synthesize(buildRequest(utterance, cfg, speechRequestId), SYNTHESIS_TIMEOUT_MS)
            }

            currentStreamId = descriptor.streamId
            if (generation != streamGeneration) throw CancellationException("superseded")

            // 2. Open the media plane (authenticated HTTP; token from the profile).
            val ttfbStart = clock()
            source = if (descriptor.fromClientCache && descriptor.cachedBytes != null) {
                InMemoryPcmSource(descriptor.cachedBytes)
            } else {
                transport.openStream(descriptor)
            }
            ttfbMs = clock() - ttfbStart

            // 3. Stream into the dedicated player; first audio frame = "started".
            player = playerFactory()
            val requestedRate = descriptor.sampleRate.coerceIn(8_000, 48_000)
            player.prepare(requestedRate, descriptor.channels.coerceIn(1, 2))

            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                if (n > 0) {
                    if (firstAudioAtMs < 0) {
                        firstAudioAtMs = clock()
                        utteranceStartedListener?.invoke(utterance.utteranceId)
                    }
                    bytesReceived += n
                    player.write(buffer, 0, n)
                }
            }
            source.close()
            source = null
            player.endOfStream()

            // 4. Wait for REAL playback completion (hardware drain) — §53/§93.
            val stats = player.awaitPlaybackFinished()
            player = null
            source = null

            if (stats.underruns > MAX_TOLERATED_UNDERRUNS && stats.bytesPlayed == 0L) {
                return failWithFallback(
                    utterance, cfg,
                    SpeechError(
                        SpeechErrorCode.CLOUD_STREAM_FAILED,
                        "The cloud audio stream stalled and could not be played.",
                        recoverable = true
                    ),
                    playedMs = 0L,
                    generation = generation,
                    speechRequestId = speechRequestId,
                    androidDelegate = androidDelegate,
                    policy = cfg.cloudTtsFallback
                )
            }

            if (stats.bytesPlayed == 0L && bytesReceived > 0L) {
                // Data arrived but nothing audible played — treat as a failure.
                return failWithFallback(
                    utterance, cfg,
                    SpeechError(
                        SpeechErrorCode.CLOUD_STREAM_FAILED,
                        "Cloud audio could not be played on this device.",
                        recoverable = true
                    ),
                    playedMs = 0L,
                    generation = generation,
                    speechRequestId = speechRequestId,
                    androidDelegate = androidDelegate,
                    policy = cfg.cloudTtsFallback
                )
            }

            AppLogger.d(
                tag,
                "speech ${speechRequestId.take(16)} complete: ttfb=${ttfbMs}ms " +
                    "firstAudio=${if (firstAudioAtMs < 0) -1 else firstAudioAtMs - startedAtMs}ms " +
                    "bytes=$bytesReceived underruns=${stats.underruns} cacheHit=$cacheHit"
            )
            return SpeechResult.Completed
        } catch (ce: CancellationException) {
            // Orchestrator stopped this request (REPLACE / stop / route loss /
            // focus pause): already silenced above; propagate for reason mapping.
            cancelUpstreamQuietly()
            throw ce
        } catch (e: RemoteTtsException) {
            return failWithFallback(
                utterance, cfg, mapError(e),
                playedMs = if (firstAudioAtMs < 0) 0L else clock() - firstAudioAtMs,
                generation = generation,
                speechRequestId = speechRequestId,
                androidDelegate = androidDelegate,
                policy = cfg.cloudTtsFallback
            )
        } catch (e: PcmStreamException) {
            return failWithFallback(
                utterance, cfg,
                SpeechError(
                    SpeechErrorCode.CLOUD_STREAM_FAILED,
                    "The audio connection to the PC Study Agent was interrupted.",
                    recoverable = true
                ),
                playedMs = if (firstAudioAtMs < 0) 0L else clock() - firstAudioAtMs,
                generation = generation,
                speechRequestId = speechRequestId,
                androidDelegate = androidDelegate,
                policy = cfg.cloudTtsFallback
            )
        } catch (e: Exception) {
            return failWithFallback(
                utterance, cfg,
                SpeechError(
                    SpeechErrorCode.CLOUD_STREAM_FAILED,
                    "Cloud speech failed unexpectedly.",
                    recoverable = true
                ),
                playedMs = if (firstAudioAtMs < 0) 0L else clock() - firstAudioAtMs,
                generation = generation,
                speechRequestId = speechRequestId,
                androidDelegate = androidDelegate,
                policy = cfg.cloudTtsFallback
            )
        } finally {
            try {
                source?.close()
            } catch (_: Exception) {
            }
            try {
                player?.stop()
                player?.release()
            } catch (_: Exception) {
            }
            if (generation == streamGeneration) {
                currentStreamId = null
                currentSpeechRequestId = null
            }
        }
    }

    /**
     * Failure handling with the no-duplication rule (§13/§110):
     *  - [playedMs] < MIN_MEANINGFUL_PLAYBACK_MS and policy allows fallback →
     *    speak the SAME utterance on the local backend (nothing was heard yet).
     *  - otherwise → typed Failed (the study flow continues visually).
     */
    private suspend fun failWithFallback(
        utterance: BackendUtterance,
        cfg: TtsSettings,
        error: SpeechError,
        playedMs: Long,
        generation: Long,
        speechRequestId: String,
        androidDelegate: SpeechBackend,
        policy: CloudTtsFallbackPolicy
    ): SpeechResult {
        AppLogger.w(tag, "cloud failure code=${error.code} playedMs=$playedMs req=${speechRequestId.take(16)}")
        val meaningfulPlayback = playedMs >= MIN_MEANINGFUL_PLAYBACK_MS
        if (!meaningfulPlayback && policy == CloudTtsFallbackPolicy.FALLBACK_TO_ANDROID) {
            // Nothing audible was produced → local retry is NOT a duplicate.
            return try {
                androidDelegate.speak(utterance)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(tag, "local fallback failed: ${e.message}", e)
                SpeechResult.Failed(error)
            }
        }
        return SpeechResult.Failed(error)
    }

    private fun cancelUpstreamQuietly() {
        val streamId = currentStreamId
        if (streamId == null || streamId.startsWith("cache_")) return
        // Fire-and-forget on a process-wide scope: cancelling an upstream synthesis must survive
        // caller cancellation (an in-flight provider call is never erased by UI/lifecycle death).
        GlobalScope.launch {
            try {
                transport.cancel(streamId)
            } catch (_: Exception) {
            }
        }
    }

    private fun buildRequest(
        utterance: BackendUtterance,
        cfg: TtsSettings,
        speechRequestId: String
    ): RemoteTtsRequest {
        val info = providerInfo
        val maxChars = info?.maxInputChars ?: DEFAULT_MAX_INPUT_CHARS
        // Defense in depth: the orchestrator already sizes text to this limit,
        // but never trust an unknown value — fail typed instead of truncated.
        require(utterance.text.length <= maxChars) {
            "text exceeds provider limit (${utterance.text.length} > $maxChars)"
        }
        return RemoteTtsRequest(
            speechRequestId = speechRequestId,
            provider = provider,
            text = utterance.text,
            voiceId = utterance.voiceName,
            model = cfg.cloudModelIdFor(provider),
            language = CloudTtsStyle.languageCode(utterance.language),
            rate = utterance.rate,
            purpose = null, // purpose text is already in the payload
            options = utterance.providerOptions
        )
    }

    private fun mapError(e: RemoteTtsException): SpeechError = when (e.code) {
        RemoteTtsException.PROVIDER_UNAVAILABLE -> SpeechError(
            SpeechErrorCode.PROVIDER_UNAVAILABLE,
            "${provider.label} is unreachable right now.",
            recoverable = true
        )
        RemoteTtsException.PROVIDER_NOT_CONFIGURED -> SpeechError(
            SpeechErrorCode.PROVIDER_NOT_CONFIGURED,
            "${provider.label} is not configured on the PC Study Agent. " +
                "Set the provider key on the PC running Study Agent.",
            recoverable = true,
            requiresUserAction = true
        )
        RemoteTtsException.PROVIDER_AUTH_FAILED -> SpeechError(
            SpeechErrorCode.PROVIDER_AUTH_FAILED,
            "${provider.label} rejected its credential on the PC Study Agent.",
            recoverable = true,
            requiresUserAction = true
        )
        RemoteTtsException.PROVIDER_RATE_LIMITED -> SpeechError(
            SpeechErrorCode.PROVIDER_RATE_LIMITED,
            "${provider.label} is temporarily rate limited. Try again in a moment.",
            recoverable = true
        )
        RemoteTtsException.PROVIDER_QUOTA_EXCEEDED -> SpeechError(
            SpeechErrorCode.PROVIDER_QUOTA_EXCEEDED,
            "${provider.label} quota or billing is unavailable on the PC Study Agent.",
            recoverable = true,
            requiresUserAction = true
        )
        RemoteTtsException.VOICE_NOT_FOUND -> SpeechError(
            SpeechErrorCode.VOICE_UNAVAILABLE,
            "The selected ${provider.label} voice is no longer available.",
            recoverable = true,
            requiresUserAction = true
        )
        RemoteTtsException.MODEL_UNAVAILABLE -> SpeechError(
            SpeechErrorCode.MODEL_UNAVAILABLE,
            "The selected ${provider.label} model is not available.",
            recoverable = true
        )
        RemoteTtsException.STREAM_TIMEOUT -> SpeechError(
            SpeechErrorCode.TIMEOUT,
            "The ${provider.label} stream timed out.",
            recoverable = true
        )
        RemoteTtsException.TEXT_TOO_LONG -> SpeechError(
            SpeechErrorCode.SPEAK_FAILED,
            "The text is too long for this provider.",
            recoverable = true
        )
        else -> SpeechError(
            SpeechErrorCode.CLOUD_STREAM_FAILED,
            "${provider.label} speech failed. The device voice can continue the session.",
            recoverable = true
        )
    }

    // ------------------------------------------------------------------ catalog

    override suspend fun queryVoices(languageCode: String?): List<SpeechVoice> {
        if (released) return emptyList()
        val cached = _voices.value
        val stale = _voicesFetchedAtMs.value < 0 ||
            clock() - _voicesFetchedAtMs.value > CATALOG_CACHE_MS
        val voices = if (cached != null && !stale) cached
        else try {
            transport.fetchVoices(provider, refresh = true, VOICE_QUERY_TIMEOUT_MS)
        } catch (e: RemoteTtsException) {
            return cached ?: emptyList()
        }
        _voices.value = voices
        _voicesFetchedAtMs.value = clock()
        if (languageCode.isNullOrBlank()) return voices
        return voices.filter { v -> v.languageCodes.contains(languageCode.lowercase()) }
    }

    override fun maxSpeechInputLength(): Int =
        (providerInfo?.maxInputChars ?: DEFAULT_MAX_INPUT_CHARS).coerceAtLeast(MIN_CHUNK_LENGTH)

    // ------------------------------------------------------------------ lifecycle

    override fun stop() {
        // Immediate cancellation: close the media stream (the finally block in
        // speak does the same via the CancellationException path).
        val streamId = currentStreamId
        if (streamId != null && !streamId.startsWith("cache_")) {
            cancelUpstreamQuietly()
        }
        _status.value = _status.value // no-op: status tracks capability, not playback
    }

    override fun release() {
        released = true
        cancelUpstreamQuietly()
        _status.value = EngineStatus.RELEASED
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 24_000
        const val DEFAULT_MAX_INPUT_CHARS = 4_000
        const val MIN_CHUNK_LENGTH = 128
        private const val READ_BUFFER_BYTES = 16 * 1024
        private const val SYNTHESIS_TIMEOUT_MS = 20_000L
        private const val VOICE_QUERY_TIMEOUT_MS = 10_000L
        private const val CATALOG_CACHE_MS = 10 * 60 * 1000L
        private const val MAX_TOLERATED_UNDERRUNS = 4
        /** Below this, "playback started" is not meaningful (jitter/startup noise). */
        const val MIN_MEANINGFUL_PLAYBACK_MS = 300L
    }
}

/** Convenience used by [TtsSettings] mapping: model override per provider. */
fun TtsSettings.cloudModelIdFor(provider: TtsProvider): String? = when (provider) {
    TtsProvider.OPENAI -> openaiModelId
    TtsProvider.ELEVENLABS -> elevenlabsModelId
    TtsProvider.ANDROID -> null
}
