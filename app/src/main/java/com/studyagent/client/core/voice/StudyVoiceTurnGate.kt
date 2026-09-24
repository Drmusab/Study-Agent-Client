package com.studyagent.client.core.voice

import com.studyagent.client.core.audio.AcousticProfile
import com.studyagent.client.core.audio.EffectiveStudyAudioRoute
import com.studyagent.client.core.voice.tts.AcousticGapPolicy
import kotlinx.coroutines.delay

/** Why the microphone was not opened. Every value is a *diagnostic*, not a user error. */
enum class VoiceTurnBlock {
    /** The turn this start belonged to is gone (skipped card, pause, end, session change). */
    STALE_TURN,

    /** TTS is still speaking or still queued — opening the mic here would overlap (§59/§101). */
    SPEECH_ACTIVE,

    /** Voice interaction is paused (session paused, or headphones lost under the pause policy). */
    VOICE_PAUSED,

    /** No microphone this app can use (recognizer unavailable / no input devices). */
    NO_MICROPHONE,

    /** `HEADSET_REQUIRED` is not satisfied; the user's privacy preference wins (§7). */
    ROUTE_BLOCKED
}

sealed interface VoiceTurnDecision {
    data class Ready(
        /** The acoustic gap that was actually observed. */
        val gapMs: Int,
        /** TTS terminal → microphone open, in wall-clock milliseconds. */
        val handoffLatencyMs: Long
    ) : VoiceTurnDecision

    data class Blocked(val reason: VoiceTurnBlock) : VoiceTurnDecision
}

/**
 * The single place that decides **when the microphone may open** (§15/§59/§101/§112).
 *
 * This is the Phone Mode self-echo defence, layered in the order that actually matters:
 *
 * ```
 *  1. voice paused?                  → no mic
 *  2. route blocked / no mic?        → no mic
 *  3. TTS speaking or queued?        → no mic
 *  4. acoustic gap (route-aware)     → wait the physical tail out
 *  5. turn still valid after the gap?→ else no mic   (§102/§103/§104)
 *  6. speech/queue re-check          → else no mic
 *  7. ready
 * ```
 *
 * Deliberately *not* a `delay(350)` and hope: step 5 is what makes a stale gap harmless, and
 * steps 3/6 are what make the TTS/STT overlap invariant hold.
 *
 * Pure apart from its injected lambdas, so every rule above is unit-testable with virtual time.
 */
class StudyVoiceTurnGate(
    private val routeProvider: () -> EffectiveStudyAudioRoute,
    private val configuredGapMs: () -> Int,
    private val speechActive: () -> Boolean,
    private val queueDepth: () -> Int,
    private val voicePaused: () -> Boolean = { false },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val delayFn: suspend (Long) -> Unit = { delay(it) }
) {
    /** The gap this route would use, for diagnostics and tests. */
    fun policyGapMs(failedSpeech: Boolean = false): Int =
        AcousticGapPolicy.gapMs(profile(), configuredGapMs(), failedSpeech)

    private fun profile(): AcousticProfile = routeProvider().acousticProfile

    /**
     * Suspends until the microphone may open for a turn that must still be valid — [stillValid]
     * is re-evaluated *after* the gap, which is how a gap that outlives its turn is dropped.
     *
     * @param waitForSpeechToSettleMs how long to wait for an in-flight utterance to stop before
     *        giving up. Automatic turn-taking passes `0`: a request that arrives while TTS is
     *        still running is a bug upstream, and refusing it is the safe answer. Push-to-talk
     *        passes a small budget, because there the *user* asked to talk and the engine simply
     *        needs a moment to actually stop (§27).
     */
    suspend fun awaitListenWindow(
        waitForSpeechToSettleMs: Long = 0L,
        stillValid: () -> Boolean
    ): VoiceTurnDecision {
        val route = routeProvider()

        if (voicePaused()) return VoiceTurnDecision.Blocked(VoiceTurnBlock.VOICE_PAUSED)
        if (!route.canStartVoiceStudy) return VoiceTurnDecision.Blocked(VoiceTurnBlock.ROUTE_BLOCKED)
        if (!route.hasUsableMicrophone) return VoiceTurnDecision.Blocked(VoiceTurnBlock.NO_MICROPHONE)
        if (speechActive() || queueDepth() > 0) {
            if (waitForSpeechToSettleMs <= 0L) return VoiceTurnDecision.Blocked(VoiceTurnBlock.SPEECH_ACTIVE)
            val settled = awaitSpeechSettled(stillValid, waitForSpeechToSettleMs)
            if (!settled) return VoiceTurnDecision.Blocked(VoiceTurnBlock.SPEECH_ACTIVE)
        }
        if (!stillValid()) return VoiceTurnDecision.Blocked(VoiceTurnBlock.STALE_TURN)

        val gap = policyGapMs()
        val startedAt = nowMs()
        if (gap > 0) delayFn(gap.toLong())

        // --- after the gap: everything is re-validated, because the world moved on -----------
        if (voicePaused()) return VoiceTurnDecision.Blocked(VoiceTurnBlock.VOICE_PAUSED)
        if (!stillValid()) return VoiceTurnDecision.Blocked(VoiceTurnBlock.STALE_TURN)
        if (speechActive() || queueDepth() > 0) return VoiceTurnDecision.Blocked(VoiceTurnBlock.SPEECH_ACTIVE)
        val currentRoute = routeProvider()
        if (!currentRoute.canStartVoiceStudy) return VoiceTurnDecision.Blocked(VoiceTurnBlock.ROUTE_BLOCKED)
        if (!currentRoute.hasUsableMicrophone) return VoiceTurnDecision.Blocked(VoiceTurnBlock.NO_MICROPHONE)
        if (currentRoute.generation != route.generation) {
            // The route changed while we were waiting. Let the caller re-issue the turn against
            // the new route rather than opening the microphone on a half-switched pipeline.
            return VoiceTurnDecision.Blocked(VoiceTurnBlock.STALE_TURN)
        }

        val latency = (nowMs() - startedAt).coerceAtLeast(0L)
        return VoiceTurnDecision.Ready(gapMs = gap, handoffLatencyMs = latency)
    }

    /**
     * Bounded wait for the speech pipeline to drain. Every step re-validates the turn, so a
     * push-to-talk press that is released (or a card that is skipped) during the wait stops
     * waiting immediately instead of opening the microphone late.
     */
    private suspend fun awaitSpeechSettled(stillValid: () -> Boolean, budgetMs: Long): Boolean {
        var remaining = budgetMs
        while (remaining > 0L) {
            if (!stillValid()) return false
            if (!speechActive() && queueDepth() == 0) return true
            val step = minOf(SETTLE_STEP_MS, remaining)
            delayFn(step)
            remaining -= step
        }
        return !speechActive() && queueDepth() == 0
    }

    /** Non-suspending readiness check for callers that cannot wait (e.g. diagnostics). */
    fun canOpenMicrophoneNow(): Boolean {
        val route = routeProvider()
        return !voicePaused() &&
            route.canStartVoiceStudy &&
            route.hasUsableMicrophone &&
            !speechActive() &&
            queueDepth() == 0
    }

    companion object {
        /** Granularity of the bounded "has the utterance stopped?" wait. */
        const val SETTLE_STEP_MS = 75L
    }
}
