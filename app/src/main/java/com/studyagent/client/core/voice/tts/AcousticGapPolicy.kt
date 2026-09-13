package com.studyagent.client.core.voice.tts

import com.studyagent.client.core.audio.AcousticProfile
import com.studyagent.client.core.models.AppSettingsPolicy

/**
 * Route-aware acoustic gap policy (§15/§16/§17).
 *
 * The gap exists for exactly one reason: speaker cones, codec buffers and A2DP/SCO drain
 * pipelines outlive `onDone` by a few hundred milliseconds. Opening the microphone inside
 * that window feeds the app its own voice back (self-echo), which is how a rating window
 * used to auto-rate a card from the word "Good" in its own feedback (§52/§53/§92).
 *
 * Two rules keep this honest:
 *  1. The gap is **not** logical state control. Correctness comes from the completion gate,
 *     the drained queue, a stable route and request-generation validation (§17). The gap only
 *     covers the physical tail.
 *  2. Phones speaker output leaks into the phone's own microphones; headphones do not. So the
 *     phone-speaker profile gets a more conservative floor **without** exposing millisecond
 *     tuning to ordinary users — the value is derived from the single existing user setting
 *     (`TtsSettings.acousticGapMs`) plus the route the app is actually on (§16).
 */
object AcousticGapPolicy {

    const val MIN_GAP_MS = AppSettingsPolicy.MIN_ACOUSTIC_GAP_MS
    const val MAX_GAP_MS = AppSettingsPolicy.MAX_ACOUSTIC_GAP_MS

    /** Floor for the phone-speaker path: measurably longer than a typical headset tail. */
    const val PHONE_SPEAKER_FLOOR_MS = 450

    /** Multiplier applied to the user's configured gap when audio comes out of the speaker. */
    const val PHONE_SPEAKER_SCALE = 1.25

    /** Degraded (TTS failed) gaps still leave a floor so the tail cannot be captured. */
    const val DEGRADED_FLOOR_MS = 200

    /** Base clamp applied to whatever the user configured. */
    fun clampBase(configuredGapMs: Int): Int = configuredGapMs.coerceIn(MIN_GAP_MS, MAX_GAP_MS)

    /**
     * @param profile the *effective output* profile. Headphones isolate the app's own speech
     *                even when the microphone is the phone's built-in one.
     * @param failedSpeech true when the utterance failed/aborted, so the audio path is already
     *                     stopped and a shorter wait is safe.
     */
    fun gapMs(profile: AcousticProfile, configuredGapMs: Int, failedSpeech: Boolean = false): Int {
        val base = clampBase(configuredGapMs)
        return when (profile) {
            AcousticProfile.HEADSET ->
                if (failedSpeech) (base / 2).coerceAtLeast(MIN_GAP_MS) else base

            AcousticProfile.PHONE_SPEAKER -> {
                val required = maxOf(
                    PHONE_SPEAKER_FLOOR_MS,
                    (base * PHONE_SPEAKER_SCALE).toInt()
                ).coerceAtMost(MAX_GAP_MS)
                if (failedSpeech) {
                    (required / 2).coerceAtLeast(DEGRADED_FLOOR_MS)
                } else {
                    required
                }
            }
        }
    }
}
