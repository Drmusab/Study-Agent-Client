package com.studyagent.client.core.voice.tts

import kotlinx.coroutines.flow.StateFlow

/**
 * Remote TTS transport seam (master prompt §31/§46/§48): the ONLY place the
 * Android app talks to the PC Study Agent for cloud speech.
 *
 *  - CONTROL plane: WebSocket JSON (tts_capabilities / tts_voices /
 *    tts_synthesize / tts_cancel / tts_test_provider), correlated with the
 *    existing protocol's in_reply_to — never base64 audio in JSON.
 *  - MEDIA plane: authenticated short-lived HTTP stream of raw PCM
 *    (16-bit mono, known sample rate; the agent normalizes provider formats —
 *    Android does no decoding and no ffmpeg).
 *
 * The production implementation ([WebSocketRemoteTtsTransport]) reuses the
 * existing AgentConnection/ConnectionRepository and the profile's Study Agent
 * credential (Bearer header — never in the URL). The JVM test suite uses a
 * deterministic fake.
 */

/** Normalized synthesis request (text is already preprocessed/normalized upstream). */
data class RemoteTtsRequest(
    val speechRequestId: String,
    val provider: TtsProvider,
    val text: String,
    /** Namespaced voice id ("openai:coral") or null = agent default voice. */
    val voiceId: String?,
    /** Explicit model id or null = agent default for the quality profile. */
    val model: String?,
    /** "auto" | "en" | "ar" — language hint from the segmenter. */
    val language: String,
    val rate: Float,
    val purpose: String?,
    /** Provider-specific options passed through (instructions, quality_profile, style). */
    val options: Map<String, Any> = emptyMap()
)

/** Stream descriptor returned by the agent (control-plane reply to tts_synthesize). */
data class RemoteStreamDescriptor(
    val streamId: String,
    val streamPath: String,
    val speechRequestId: String,
    val format: String,
    val sampleRate: Int,
    val channels: Int,
    val expiresInSeconds: Int,
    val cacheHit: Boolean,
    val estimatedDurationMs: Int,
    /** True when served from the client-side session cache (no agent round trip). */
    val fromClientCache: Boolean = false,
    /** The cached payload itself when [fromClientCache]; otherwise null. */
    val cachedBytes: ByteArray? = null
)

/** One provider's capability/health block (from tts_capabilities; never keys). */
data class RemoteProviderInfo(
    val provider: TtsProvider,
    val connected: Boolean = false,
    val configured: Boolean = false,
    val healthy: Boolean = false,
    val models: List<String> = emptyList(),
    val defaultModel: String? = null,
    val supportsRate: Boolean = true,
    val supportsPitch: Boolean = true,
    val supportsStyleInstructions: Boolean = false,
    val supportsVoiceSettings: Boolean = false,
    val supportsCustomVoices: Boolean = false,
    val maxInputChars: Int? = null,
    val lastCheckedAtMs: Long = 0,
    val error: String? = null
) {
    val available: Boolean get() = connected && configured && healthy
}

/** Result of the "Test provider" action (§36). No paid synthesis for live providers. */
data class RemoteProviderTestResult(
    val provider: TtsProvider,
    val configured: Boolean,
    val authentication: String?,
    val reachable: Boolean,
    val voiceList: Boolean,
    val synthesis: Boolean?,
    val message: String?,
    val latencyMs: Long?
)

/** Typed remote TTS error (maps to [SpeechErrorCode] inside [RemoteSpeechBackend]). */
class RemoteTtsException(
    val code: String,
    message: String,
    val retryable: Boolean = false
) : Exception(message) {

    companion object {
        // Stable provider error codes (PC Agent contract — mirror of
        // server/tts/models.py TtsErrorCode).
        const val PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE"
        const val PROVIDER_NOT_CONFIGURED = "PROVIDER_NOT_CONFIGURED"
        const val PROVIDER_AUTH_FAILED = "PROVIDER_AUTH_FAILED"
        const val PROVIDER_RATE_LIMITED = "PROVIDER_RATE_LIMITED"
        const val PROVIDER_QUOTA_EXCEEDED = "PROVIDER_QUOTA_EXCEEDED"
        const val VOICE_NOT_FOUND = "VOICE_NOT_FOUND"
        const val MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE"
        const val SYNTHESIS_FAILED = "SYNTHESIS_FAILED"
        const val STREAM_FAILED = "STREAM_FAILED"
        const val STREAM_TIMEOUT = "STREAM_TIMEOUT"
        const val STREAM_EXPIRED = "STREAM_EXPIRED"
        const val STREAM_NOT_FOUND = "STREAM_NOT_FOUND"
        const val STREAM_NOT_AUTHENTICATED = "STREAM_NOT_AUTHENTICATED"
        const val INVALID_REQUEST = "INVALID_REQUEST"
        const val TEXT_TOO_LONG = "TEXT_TOO_LONG"
        const val NOT_CONNECTED = "NOT_CONNECTED"
        const val CAPABILITY_TIMEOUT = "CAPABILITY_TIMEOUT"

        fun unavailable() = RemoteTtsException(
            PROVIDER_UNAVAILABLE, "The PC Study Agent is not reachable right now.")

        fun notConnected() = RemoteTtsException(
            NOT_CONNECTED, "Not connected to the PC Study Agent.", retryable = true)

        fun capabilityTimeout(provider: TtsProvider) = RemoteTtsException(
            CAPABILITY_TIMEOUT,
            "Timed out waiting for ${provider.label} capabilities from the PC Study Agent.")
    }
}

/** A failure while reading the PCM media stream (HTTP/IO level). */
class PcmStreamException(
    message: String,
    val httpStatus: Int? = null
) : Exception(message)

/**
 * Raw PCM byte source for one stream. Read loop contract: [read] returns the
 * number of bytes written to [buffer] or -1 at clean EOF; it throws
 * [PcmStreamException] on transport failure. [close] is idempotent and must be
 * called on every path (especially cancel).
 */
interface PcmByteSource {
    fun read(buffer: ByteArray): Int
    fun close()
}

/** In-memory PCM source (client cache / tests). */
class InMemoryPcmSource(private val data: ByteArray) : PcmByteSource {
    private var offset = 0
    @Volatile
    private var closed = false

    override fun read(buffer: ByteArray): Int {
        if (closed) throw PcmStreamException("Stream closed")
        if (offset >= data.size) return -1
        val n = minOf(buffer.size, data.size - offset)
        System.arraycopy(data, offset, buffer, 0, n)
        offset += n
        return n
    }

    override fun close() {
        closed = true
    }
}

/**
 * The transport interface. All control calls suspend and throw
 * [RemoteTtsException] with a typed [RemoteTtsException.code] on failure —
 * never raw HTTP text.
 */
interface RemoteTtsTransport {

    /** Agent WebSocket is connected (transport open). */
    val available: StateFlow<Boolean>

    /** Connected agent advertised the "tts" capability. */
    val supportsTts: StateFlow<Boolean>

    /**
     * Fetch provider capability/health blocks. Cached by the caller
     * ([RemoteSpeechBackend]/router); this is a round trip.
     */
    suspend fun fetchCapabilities(timeoutMs: Long): List<RemoteProviderInfo>

    /** Voice catalog for [provider] (agent-side cached; refresh = bypass agent cache). */
    suspend fun fetchVoices(
        provider: TtsProvider,
        refresh: Boolean = false,
        timeoutMs: Long
    ): List<SpeechVoice>

    /**
     * Request a synthesis stream. Returns the stream descriptor (media plane)
     * — on failure throws [RemoteTtsException] with the typed provider code.
     * Bounded: never retries paid synthesis.
     */
    suspend fun synthesize(request: RemoteTtsRequest, timeoutMs: Long): RemoteStreamDescriptor

    /** Best-effort cancel of an active stream (control plane; fire and forget). */
    suspend fun cancel(streamId: String?)

    /** Open the authenticated PCM media stream for [descriptor]. */
    fun openStream(descriptor: RemoteStreamDescriptor): PcmByteSource

    /** "Test provider" action (§36). Null when not connected. */
    suspend fun testProvider(provider: TtsProvider, timeoutMs: Long): RemoteProviderTestResult?
}
