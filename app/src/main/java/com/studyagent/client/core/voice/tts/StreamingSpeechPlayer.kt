package com.studyagent.client.core.voice.tts

/**
 * Dedicated streaming player contract (master prompt §46/§53/§54/§93).
 *
 * The remote backend feeds PCM bytes as they arrive; the player owns:
 *  - a small start/jitter buffer (fast start, no long preroll),
 *  - underrun tracking (continuous starvation → typed failure, §55),
 *  - EXACTLY-ONCE completion: [awaitPlaybackFinished] completes only when the
 *    last audio frame has actually finished playing (playback drain) — network
 *    EOF / last byte received is explicitly NOT playback completion (§53).
 *
 * The core interface is Android-free so the remote backend's full state
 * machine (cancel, stale-stream, no-duplicate fallback) is unit-testable on
 * the JVM with a fake player. The production implementation is
 * [AudioTrackStreamingPlayer].
 */

data class PlaybackStats(
    /** Bytes consumed by the hardware (all of them on clean drain). */
    val bytesPlayed: Long,
    /** Clock of the first frame written to the player. -1 = nothing played. */
    val firstFrameAtMs: Long = -1,
    /** Clock of the drain completion. */
    val finishedAtMs: Long = -1,
    /** Count of playback underruns (buffer emptied while the player kept running). */
    val underruns: Int = 0
) {
    val playedMs: Long
        get() = if (firstFrameAtMs < 0 || finishedAtMs < 0) 0L else finishedAtMs - firstFrameAtMs
}

/** Raised when the stream starves continuously (network cannot keep up). */
class PlaybackUnderrunException(message: String) : Exception(message)

interface StreamingSpeechPlayer {

    /** Configure before any [write]. [sampleRate] Hz, mono, 16-bit little-endian. */
    fun prepare(sampleRate: Int, channels: Int = 1)

    /** Feed PCM (may be called from the read loop; thread-safe). */
    fun write(chunk: ByteArray, offset: Int, length: Int)

    /** No more bytes will arrive. Playback continues until the buffer drains. */
    fun endOfStream()

    /**
     * Suspend until playback has fully drained (last frame out of the
     * hardware) or the stream is stopped/failed. Returns [PlaybackStats].
     * Cancellation must stop audio and release resources.
     */
    suspend fun awaitPlaybackFinished(): PlaybackStats

    /** Immediate stop (idempotent): silences audio, finishes the stream. */
    fun stop()

    /** Release native resources. Idempotent. */
    fun release()
}
