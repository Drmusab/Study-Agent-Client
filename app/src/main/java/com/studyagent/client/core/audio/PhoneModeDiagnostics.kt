package com.studyagent.client.core.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Local, privacy-safe Phone Mode metrics (§110/§111).
 *
 * What is recorded: counts, timings, route changes, error categories.
 * What is never recorded: transcripts. Spoken medical answers are personal data and the
 * metrics exist to tune Phone Mode, not to keep a record of what the user said.
 *
 * Nothing leaves the device: this is the same DataStore-free in-memory surface the rest of
 * Diagnostics uses.
 */
data class PhoneModeMetrics(
    val phoneTurns: Int = 0,
    val headsetTurns: Int = 0,
    val handoffCount: Int = 0,
    val routeChanges: Int = 0,
    val headsetLossEvents: Int = 0,
    val blockedStarts: Int = 0,
    val suggestedSelfEcho: Int = 0,
    val sttNoSpeech: Int = 0,
    val sttNoMatch: Int = 0,
    val sttTimeouts: Int = 0,
    val micUnavailableSkips: Int = 0,
    val lastHandoffLatencyMs: Long = -1L,
    val avgHandoffLatencyMs: Long = -1L
)

/**
 * Thread-safe counter store behind [PhoneModeMetrics]. Called from coroutines on several
 * dispatchers, so every mutation is atomic and the snapshot is published as one StateFlow.
 */
class PhoneModeDiagnostics {

    private val phoneTurns = AtomicInteger(0)
    private val headsetTurns = AtomicInteger(0)
    private val handoffCount = AtomicInteger(0)
    private val routeChanges = AtomicInteger(0)
    private val headsetLossEvents = AtomicInteger(0)
    private val blockedStarts = AtomicInteger(0)
    private val suggestedSelfEcho = AtomicInteger(0)
    private val sttNoSpeech = AtomicInteger(0)
    private val sttNoMatch = AtomicInteger(0)
    private val sttTimeouts = AtomicInteger(0)
    private val micUnavailableSkips = AtomicInteger(0)
    private val lastHandoffLatencyMs = AtomicLong(-1L)
    private val avgHandoffLatencyMs = AtomicLong(-1L)

    private val _metrics = MutableStateFlow(PhoneModeMetrics())
    val metrics: StateFlow<PhoneModeMetrics> = _metrics.asStateFlow()

    fun recordListenTurn(profile: AcousticProfile) {
        if (profile == AcousticProfile.PHONE_SPEAKER) phoneTurns.incrementAndGet() else headsetTurns.incrementAndGet()
        publish()
    }

    fun recordRouteChange() {
        routeChanges.incrementAndGet()
        publish()
    }

    fun recordHeadsetLoss() {
        headsetLossEvents.incrementAndGet()
        publish()
    }

    fun recordBlockedStart() {
        blockedStarts.incrementAndGet()
        publish()
    }

    /**
     * Diagnostic only: the transcript looked like the app's own speech. Never used to
     * discard a user's answer (§18).
     */
    fun recordSuspectedSelfEcho() {
        suggestedSelfEcho.incrementAndGet()
        publish()
    }

    fun recordNoSpeech() {
        sttNoSpeech.incrementAndGet()
        publish()
    }

    fun recordNoMatch() {
        sttNoMatch.incrementAndGet()
        publish()
    }

    fun recordTimeout() {
        sttTimeouts.incrementAndGet()
        publish()
    }

    fun recordMicUnavailableSkip() {
        micUnavailableSkips.incrementAndGet()
        publish()
    }

    /** TTS terminal → microphone open, as actually observed by the turn gate. */
    fun recordHandoffLatency(latencyMs: Long) {
        if (latencyMs < 0) return
        val count = handoffCount.incrementAndGet()
        lastHandoffLatencyMs.set(latencyMs)
        val previous = avgHandoffLatencyMs.get()
        avgHandoffLatencyMs.set(if (count <= 1 || previous < 0) latencyMs else previous + (latencyMs - previous) / count)
        publish()
    }

    private fun publish() {
        _metrics.value = PhoneModeMetrics(
            phoneTurns = phoneTurns.get(),
            headsetTurns = headsetTurns.get(),
            handoffCount = handoffCount.get(),
            routeChanges = routeChanges.get(),
            headsetLossEvents = headsetLossEvents.get(),
            blockedStarts = blockedStarts.get(),
            suggestedSelfEcho = suggestedSelfEcho.get(),
            sttNoSpeech = sttNoSpeech.get(),
            sttNoMatch = sttNoMatch.get(),
            sttTimeouts = sttTimeouts.get(),
            micUnavailableSkips = micUnavailableSkips.get(),
            lastHandoffLatencyMs = lastHandoffLatencyMs.get(),
            avgHandoffLatencyMs = avgHandoffLatencyMs.get()
        )
    }
}

/**
 * Cheap token-overlap similarity. Deliberately simple: it decides *whether to raise a
 * diagnostic flag*, never whether to drop a transcript (§18/§119).
 */
object TextEchoSimilarity {

    private val ignoredTokens = setOf(
        "a", "an", "the", "of", "and", "or", "to", "in", "is", "are", "was", "were",
        "for", "with", "on", "at", "by", "it", "this", "that"
    )

    fun tokens(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 && it !in ignoredTokens }
            .toSet()

    /** Jaccard overlap in `0.0..1.0`, `0.0` when either side has no comparable tokens. */
    fun similarity(a: String, b: String): Double {
        val left = tokens(a)
        val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.intersect(right).size
        val union = left.union(right).size
        return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
    }
}

/**
 * Detects the *symptom* of self-echo: the recognizer returning something very close to what
 * the app just said, immediately after it said it (§18/§52/§53/§54).
 *
 * Important: this is observation, not a filter. A user may legitimately repeat words from
 * the question, and phone-mode study content is full of repeated medical terms — so a match
 * raises a diagnostic counter and a log line, and the transcript is still processed normally.
 */
class SelfEchoDetector(
    private val similarityThreshold: Double = 0.75,
    private val windowMs: Long = 2_500L,
    private val minimumSpokenTokens: Int = 4
) {
    private var lastSpokenText: String? = null
    private var lastSpokenEndedAtMs: Long = -1L

    /** Called when TTS for a question/feedback/hint/explanation completes (§18). */
    fun noteSpoken(text: String, endedAtMs: Long) {
        if (TextEchoSimilarity.tokens(text).size < minimumSpokenTokens) {
            // Short utterances ("Good.", "Next question.") are exactly the ones a spoken
            // rating legitimately repeats — flagging them would produce false positives.
            lastSpokenText = null
            lastSpokenEndedAtMs = -1L
            return
        }
        lastSpokenText = text
        lastSpokenEndedAtMs = endedAtMs
    }

    /** Called when a recognition turn completes. */
    fun isSuspectedSelfEcho(transcript: String, nowMs: Long): Boolean {
        val spoken = lastSpokenText ?: return false
        if (lastSpokenEndedAtMs < 0) return false
        val age = nowMs - lastSpokenEndedAtMs
        if (age < 0 || age > windowMs) return false
        if (transcript.isBlank()) return false
        return TextEchoSimilarity.similarity(spoken, transcript) >= similarityThreshold
    }

    fun clear() {
        lastSpokenText = null
        lastSpokenEndedAtMs = -1L
    }
}
