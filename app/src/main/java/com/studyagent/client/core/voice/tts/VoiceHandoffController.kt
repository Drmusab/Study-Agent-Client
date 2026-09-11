package com.studyagent.client.core.voice.tts

import kotlinx.coroutines.delay

/**
 * Policy owner for the TTS → STT transition.
 *
 * Why this exists: opening the microphone the instant the engine reports onDone
 * lets the recognizer hear the tail of the app's *own* speech (codec/A2DP drain
 * buffers can outlive the callback by a few hundred milliseconds) — the question
 * then comes back as the user's "answer" (self-echo). A fixed `delay(200)` used to
 * paper over this without any policy.
 *
 * Defenses, layered (in order of importance):
 *  1. **Completion gate** — [afterSpeech] only proceeds on [SpeechResult.Completed]
 *     (or after a Failed, treated carefully by the caller); Cancelled means another
 *     state transition already happened and nothing starts listening.
 *  2. **Acoustic gap** — a single configurable delay ([TtsSettings.acousticGapMs],
 *     default 350 ms, clamped 150–1200 ms) lets the output path drain on A2DP/SCO.
 *  3. **Degraded-mode rule** — on [SpeechResult.Failed] the handoff waits a shorter
 *     gap (audio is already broken/stopped) and still lets the caller decide to
 *     listen so a TTS failure never bricks the study loop (graceful degradation).
 *
 * Device-specific tuning belongs here (one place), not scattered through the
 * repository.
 */
class VoiceHandoffController(
    private val gapProvider: () -> Int,
    private val scheduler: suspend (Long) -> Unit = { delay(it) }
) {
    data class HandoffPolicy(
        val gapMs: Int,
        val failedGapMs: Int
    )

    fun policy(): HandoffPolicy {
        val gap = gapProvider().coerceIn(
            TtsSettings.MIN_ACOUSTIC_GAP_MS,
            TtsSettings.MAX_ACOUSTIC_GAP_MS
        )
        return HandoffPolicy(gapMs = gap, failedGapMs = gap / 2)
    }

    /**
     * Run [action] only if the speech result permits a listen transition, after the
     * appropriate acoustic gap. Returns true when [action] ran.
     */
    suspend fun afterSpeech(result: SpeechResult, action: suspend () -> Unit): Boolean {
        val gap = when (result) {
            SpeechResult.Completed -> policy().gapMs
            is SpeechResult.Failed -> policy().failedGapMs
            SpeechResult.Cancelled -> return false
        }
        scheduler(gap.toLong())
        action()
        return true
    }

    companion object {
        fun of(settings: TtsSettings): VoiceHandoffController =
            VoiceHandoffController(gapProvider = { settings.acousticGapMs })
    }
}
