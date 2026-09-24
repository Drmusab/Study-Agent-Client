package com.studyagent.client.core.voice.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.studyagent.client.core.common.AppLogger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Production streaming player on [AudioTrack] (master prompt §46).
 *
 * Design:
 *  - MODE_STREAM with a bounded in-app queue; the producer (the transport
 *    read loop) blocks when the queue is full → natural backpressure, no
 *    unbounded memory.
 *  - Small start buffer (START_BUFFER_MS) → fast start without long preroll (§54).
 *  - Underrun tracking: continuous starvation beyond MAX_UNDERRUN_MS fails the
 *    stream instead of silent gaps forever (§55).
 *  - Exactly-once completion: [awaitPlaybackFinished] completes only when the
 *    hardware has drained the last frame (MODE_STREAM reaches STATE_STOPPED
 *    after EOF) — the network's last byte is explicitly NOT completion (§53/§93).
 *  - Sample-rate fallback: 24 kHz is canonical, but a few OEM HALs reject
 *    non-44.1/48 kHz output; construction then retries at 48 kHz with a
 *    trivial 2× upsampler (no decoding — the format stays s16le mono).
 */
class AudioTrackStreamingPlayer(
    private val tag: String = "StreamingSpeechPlayer",
    @Suppress("unused") private val clock: () -> Long = { System.currentTimeMillis() }
) : StreamingSpeechPlayer {

    @Volatile
    private var activeRate = 0

    @Volatile
    private var upsample2x = false

    @Volatile
    private var track: AudioTrack? = null

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val sentinel = ByteArray(0)

    @Volatile
    private var eof = false

    private val stopped = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    private val bytesWrittenToTrack = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile
    private var firstFrameAtMs: Long = -1
    @Volatile
    private var finishedAtMs: Long = -1
    @Volatile
    private var underruns = 0
    @Volatile
    private var underrunFailed = false

    @Volatile
    private var finalStats = PlaybackStats(0L)

    /** Counted down exactly once, from [finishStream], before [finalStats] is observed. */
    private val completion = CountDownLatch(1)

    @Volatile
    private var consumerThread: Thread? = null

    override fun prepare(sampleRate: Int, channels: Int) {
        require(channels == 1) { "canonical stream is mono; got $channels channels" }
        require(sampleRate > 0) { "invalid sample rate $sampleRate" }
        if (released.get()) throw IllegalStateException("player already released")

        upsample2x = false
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(1280)
        val bufferSizeInBytes = (minBuf * 2).coerceAtLeast(START_BUFFER_BYTES)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val built = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSizeInBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            // Some OEM HALs reject 24 kHz output — retry at 48 kHz and upsample.
            AppLogger.w(tag, "AudioTrack at ${sampleRate} Hz failed (${e.message}); retrying at 48000 Hz")
            val retryFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            upsample2x = true
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(retryFormat)
                .setBufferSizeInBytes(bufferSizeInBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }
        activeRate = if (upsample2x) 48_000 else sampleRate
        track = built

        val thread = Thread({ consumeLoop() }, "tts-stream-player")
        thread.isDaemon = true
        consumerThread = thread
        thread.start()
    }

    override fun write(chunk: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || stopped.get() || released.get()) return
        if (firstFrameAtMs < 0) firstFrameAtMs = System.currentTimeMillis()
        var view: ByteArray
        if (upsample2x) {
            // Duplicate every 2-byte little-endian sample (2× → 48 kHz).
            val even = length - (length % 2)
            view = ByteArray(even * 2)
            var i = offset
            var end = offset + even
            var j = 0
            while (i + 1 < end) {
                view[j++] = chunk[i]
                view[j++] = chunk[i + 1]
                view[j++] = chunk[i]
                view[j++] = chunk[i + 1]
                i += 2
            }
            if (view.size != j) view = view.copyOf(j)
        } else {
            view = if (offset == 0 && length == chunk.size) chunk
            else chunk.copyOfRange(offset, offset + length)
        }
        queue.put(view) // bounded: blocks the read loop = backpressure
    }

    override fun endOfStream() {
        if (eof || stopped.get()) return
        eof = true
        queue.put(sentinel)
    }

    override suspend fun awaitPlaybackFinished(): PlaybackStats {
        return suspendCancellableCoroutine { cont: CancellableContinuation<PlaybackStats> ->
            cont.invokeOnCancellation {
                stop()
                release()
            }
            if (completion.await(0, TimeUnit.MILLISECONDS)) {
                resumeIfNeeded(cont, finalStats)
                return@suspendCancellableCoroutine
            }
            val waiter = Thread({
                try {
                    completion.await()
                } finally {
                    resumeIfNeeded(cont, finalStats)
                }
            }, "tts-player-wait")
            waiter.isDaemon = true
            waiter.start()
        }
    }

    private fun resumeIfNeeded(cont: CancellableContinuation<PlaybackStats>, stats: PlaybackStats) {
        if (!cont.isCancelled) cont.resume(stats)
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        finishStream(failed = false)
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        stop()
        consumerThread?.let {
            try {
                it.join(RELEASE_JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        consumerThread = null
        val t = track
        track = null
        if (t != null) {
            try {
                t.release()
            } catch (e: Exception) {
                AppLogger.w(tag, "AudioTrack.release failed: ${e.message}")
            }
        }
    }

    /** Idempotent: settles stats and signals [completion] exactly once. */
    private fun finishStream(failed: Boolean) {
        if (!finished.compareAndSet(false, true)) return
        val t = track
        if (t != null && !failed) {
            try {
                t.stop()
            } catch (e: Exception) {
                AppLogger.w(tag, "AudioTrack.stop failed: ${e.message}")
            }
        }
        if (firstFrameAtMs >= 0 && finishedAtMs < 0) finishedAtMs = System.currentTimeMillis()
        finalStats = PlaybackStats(
            bytesPlayed = bytesWrittenToTrack.get(),
            firstFrameAtMs = if (failed) -1L else firstFrameAtMs,
            finishedAtMs = finishedAtMs,
            underruns = underruns
        )
        completion.countDown()
    }

    // ------------------------------------------------------------------ consumer

    private fun consumeLoop() {
        val t = track ?: return

        // Start gate (§54): wait for a small buffer (or EOF) before playing.
        val startBytes = if (upsample2x) START_BUFFER_BYTES * 2 else START_BUFFER_BYTES
        var buffered = 0L
        try {
            while (!stopped.get()) {
                if (!queue.isEmpty()) {
                    buffered += (queue.peek()?.size ?: 0).toLong()
                    if (buffered >= startBytes || eof) break
                    continue
                }
                if (eof) break
                Thread.sleep(POLL_MS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
        if (stopped.get()) return
        if (queue.isEmpty() && buffered == 0L) {
            // Stream ended (or never produced audio) — complete with zero bytes.
            finishStream(failed = false)
            return
        }

        try {
            t.play()
        } catch (e: Exception) {
            AppLogger.e(tag, "Playback start failed: ${e.message}", e)
            finishStream(failed = true)
            return
        }

        var starvationStartMs = -1L
        while (!stopped.get()) {
            val data: ByteArray? = try {
                queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (data == null) {
                if (eof) break
                // No app-level data; the hardware buffer may still be draining.
                if (starvationStartMs < 0) {
                    starvationStartMs = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - starvationStartMs > MAX_UNDERRUN_MS) {
                    // Continuous starvation → typed failure path (graceful stop).
                    underruns++
                    underrunFailed = true
                    AppLogger.w(tag, "Stream starved for ${MAX_UNDERRUN_MS}ms — failing playback")
                    finishStream(failed = true)
                    return
                }
                continue
            }
            if (data === sentinel) break
            starvationStartMs = -1L
            try {
                t.write(data, 0, data.size)
                bytesWrittenToTrack.addAndGet(data.size.toLong())
            } catch (e: Exception) {
                AppLogger.e(tag, "AudioTrack.write failed: ${e.message}", e)
                finishStream(failed = true)
                return
            }
        }

        // Drain detection (§53/§93): after EOF and an empty queue, MODE_STREAM
        // reaches STATE_STOPPED once the last frame has actually played. A
        // bounded grace covers slow hardware buffers.
        if (!underrunFailed) {
            val graceStart = System.currentTimeMillis()
            var state = readPlayState(t)
            while (state == AudioTrack.PLAYSTATE_PLAYING &&
                System.currentTimeMillis() - graceStart < DRAIN_GRACE_MS
            ) {
                try {
                    Thread.sleep(POLL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                state = readPlayState(t)
            }
        }
        finishStream(failed = underrunFailed)
    }

    private fun readPlayState(t: AudioTrack): Int = try {
        t.playState
    } catch (_: Exception) {
        AudioTrack.PLAYSTATE_STOPPED
    }

    companion object {
        private const val START_BUFFER_MS = 150
        private const val START_BUFFER_BYTES = 24_000 * 2 * START_BUFFER_MS / 1000 // 24 kHz mono s16
        private const val MAX_UNDERRUN_MS = 800L
        private const val POLL_MS = 10L
        private const val DRAIN_GRACE_MS = 1_500L
        private const val RELEASE_JOIN_MS = 250L
        private const val QUEUE_CAPACITY = 128
    }
}
