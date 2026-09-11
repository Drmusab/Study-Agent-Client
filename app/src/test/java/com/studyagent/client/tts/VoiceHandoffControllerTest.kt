package com.studyagent.client.tts

import com.studyagent.client.core.voice.tts.SpeechError
import com.studyagent.client.core.voice.tts.SpeechErrorCode
import com.studyagent.client.core.voice.tts.SpeechResult
import com.studyagent.client.core.voice.tts.VoiceHandoffController
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §31–§33: the mic only opens after a completed speech + acoustic gap. */
class VoiceHandoffControllerTest {

    private fun controller(gap: Int, clock: MutableList<Long>) = VoiceHandoffController(
        gapProvider = { gap },
        scheduler = { ms -> clock += ms }
    )

    @Test
    fun `completed speech runs action after full gap`() = runTest {
        val delays = mutableListOf<Long>()
        val handoff = controller(350, delays)
        var ran = false
        val didRun = handoff.afterSpeech(SpeechResult.Completed) { ran = true }
        assertTrue(didRun)
        assertTrue(ran)
        assertEquals(listOf(350L), delays)
    }

    @Test
    fun `cancelled speech never opens the microphone`() = runTest {
        val delays = mutableListOf<Long>()
        val handoff = controller(350, delays)
        var ran = false
        val didRun = handoff.afterSpeech(SpeechResult.Cancelled) { ran = true }
        assertFalse(didRun)
        assertFalse(ran)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `failed speech uses shorter degraded gap but still lets caller decide`() = runTest {
        val delays = mutableListOf<Long>()
        val handoff = controller(350, delays)
        var ran = false
        val didRun = handoff.afterSpeech(
            SpeechResult.Failed(SpeechError(SpeechErrorCode.PLAYBACK_ERROR, "x"))
        ) { ran = true }
        assertTrue(didRun)
        assertTrue(ran)
        assertEquals(listOf(175L), delays) // gap / 2
    }

    @Test
    fun `gap is clamped to safe bounds`() = runTest {
        val delaysLow = mutableListOf<Long>()
        controller(10, delaysLow).afterSpeech(SpeechResult.Completed) {}
        assertEquals(listOf(150L), delaysLow)

        val delaysHigh = mutableListOf<Long>()
        controller(99_999, delaysHigh).afterSpeech(SpeechResult.Completed) {}
        assertEquals(listOf(1200L), delaysHigh)
    }
}
