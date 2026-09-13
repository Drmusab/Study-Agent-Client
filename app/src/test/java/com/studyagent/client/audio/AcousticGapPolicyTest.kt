package com.studyagent.client.audio

import com.studyagent.client.core.audio.AcousticProfile
import com.studyagent.client.core.voice.tts.AcousticGapPolicy
import com.studyagent.client.core.voice.tts.SpeechError
import com.studyagent.client.core.voice.tts.SpeechErrorCode
import com.studyagent.client.core.voice.tts.SpeechResult
import com.studyagent.client.core.voice.tts.VoiceHandoffController
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route-aware acoustic gap (§15/§16/§17).
 *
 * Headphones keep the user's tuned gap; the phone speaker gets a more conservative floor
 * because the phone's own loudspeaker couples into its own microphones. The gap is a physical
 * guard only — never the mechanism that decides correctness.
 */
class AcousticGapPolicyTest {

    @Test
    fun `headset keeps the configured gap`() {
        assertEquals(350, AcousticGapPolicy.gapMs(AcousticProfile.HEADSET, 350))
    }

    @Test
    fun `headset gap is clamped to the documented range`() {
        assertEquals(AcousticGapPolicy.MIN_GAP_MS, AcousticGapPolicy.gapMs(AcousticProfile.HEADSET, 5))
        assertEquals(AcousticGapPolicy.MAX_GAP_MS, AcousticGapPolicy.gapMs(AcousticProfile.HEADSET, 99_999))
    }

    @Test
    fun `failed speech keeps the shorter degraded gap on headphones`() {
        assertEquals(175, AcousticGapPolicy.gapMs(AcousticProfile.HEADSET, 350, failedSpeech = true))
    }

    @Test
    fun `phone speaker gets a more conservative gap than the user setting`() {
        val headset = AcousticGapPolicy.gapMs(AcousticProfile.HEADSET, 350)
        val phone = AcousticGapPolicy.gapMs(AcousticProfile.PHONE_SPEAKER, 350)

        assertTrue("phone gap must exceed the headset gap", phone > headset)
        assertEquals(AcousticGapPolicy.PHONE_SPEAKER_FLOOR_MS, phone)
    }

    @Test
    fun `phone speaker gap grows with the user setting`() {
        assertTrue(
            AcousticGapPolicy.gapMs(AcousticProfile.PHONE_SPEAKER, 600) >
                AcousticGapPolicy.gapMs(AcousticProfile.PHONE_SPEAKER, 350)
        )
    }

    @Test
    fun `phone speaker scales a larger configured gap and stays within bounds`() {
        assertEquals(625, AcousticGapPolicy.gapMs(AcousticProfile.PHONE_SPEAKER, 500))
        assertEquals(
            AcousticGapPolicy.MAX_GAP_MS,
            AcousticGapPolicy.gapMs(AcousticProfile.PHONE_SPEAKER, 99_999)
        )
    }

    @Test
    fun `degraded phone-speaker gap still leaves a floor`() {
        val degraded = AcousticGapPolicy.gapMs(AcousticProfile.PHONE_SPEAKER, 350, failedSpeech = true)
        assertTrue(degraded >= AcousticGapPolicy.DEGRADED_FLOOR_MS)
        assertTrue(degraded < AcousticGapPolicy.gapMs(AcousticProfile.PHONE_SPEAKER, 350))
    }

    // ---------------------------------------------------------------- handoff integration

    /** Records the gaps a controller asks for, without any virtual-time bookkeeping. */
    private fun recordingScheduler(delays: MutableList<Long>): suspend (Long) -> Unit = { ms ->
        delays += ms
    }

    @Test
    fun `handoff controller honours the route profile`() = runTest {
        val delays = mutableListOf<Long>()
        val headset = VoiceHandoffController(
            gapProvider = { 350 },
            scheduler = recordingScheduler(delays),
            profileProvider = { AcousticProfile.HEADSET }
        )
        headset.afterSpeech(SpeechResult.Completed) {}

        val phone = VoiceHandoffController(
            gapProvider = { 350 },
            scheduler = recordingScheduler(delays),
            profileProvider = { AcousticProfile.PHONE_SPEAKER }
        )
        phone.afterSpeech(SpeechResult.Completed) {}

        assertEquals(2, delays.size)
        assertEquals(350L, delays[0])
        assertTrue("phone speaker must wait at least as long as headphones", delays[1] >= delays[0])
    }

    @Test
    fun `cancelled speech never waits and never listens under any profile`() = runTest {
        val delays = mutableListOf<Long>()
        var ran = false
        val phone = VoiceHandoffController(
            gapProvider = { 350 },
            scheduler = recordingScheduler(delays),
            profileProvider = { AcousticProfile.PHONE_SPEAKER }
        )

        val didRun = phone.afterSpeech(SpeechResult.Cancelled) { ran = true }

        assertEquals(false, didRun)
        assertEquals(false, ran)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `failed speech on the phone still gives the caller a chance to listen`() = runTest {
        val delays = mutableListOf<Long>()
        val phone = VoiceHandoffController(
            gapProvider = { 350 },
            scheduler = recordingScheduler(delays),
            profileProvider = { AcousticProfile.PHONE_SPEAKER }
        )

        val ran = phone.afterSpeech(SpeechResult.Failed(SpeechError(SpeechErrorCode.PLAYBACK_ERROR, "boom"))) {}

        assertTrue(ran)
        assertEquals(1, delays.size)
        assertTrue("a failed utterance still leaves an acoustic floor", delays[0] >= 200L)
    }
}
