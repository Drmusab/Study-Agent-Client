package com.studyagent.client.voice

import com.studyagent.client.core.audio.AudioRouteSnapshot
import com.studyagent.client.core.audio.DefaultStudyAudioModeResolver
import com.studyagent.client.core.audio.EffectiveStudyAudioRoute
import com.studyagent.client.core.audio.StudyAudioMode
import com.studyagent.client.core.voice.StudyVoiceTurnGate
import com.studyagent.client.core.voice.VoiceTurnBlock
import com.studyagent.client.core.voice.VoiceTurnDecision
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half-duplex contract of Phone Mode (§13/§14/§15/§59/§101–§104).
 *
 * Every test here is a rule that, if it regressed, would let the app listen to itself: a
 * rating captured from the app's own "Good", a stale gap reopening the microphone after the
 * user skipped a card, a microphone opened underneath live speech.
 */
class StudyVoiceTurnGateTest {

    private val resolver = DefaultStudyAudioModeResolver()

    private fun phoneRoute(): EffectiveStudyAudioRoute =
        resolver.resolve(StudyAudioMode.AUTO, AudioRouteSnapshot.PHONE_ONLY)

    private fun headsetRoute(): EffectiveStudyAudioRoute =
        resolver.resolve(StudyAudioMode.AUTO, AudioRouteSnapshot.BLUETOOTH_HEADSET)

    @Test
    fun `phone gap is applied before the microphone opens`() = runTest {
        val delays = mutableListOf<Long>()
        val gate = StudyVoiceTurnGate(
            routeProvider = { phoneRoute() },
            configuredGapMs = { 350 },
            speechActive = { false },
            queueDepth = { 0 },
            nowMs = { 0L },
            delayFn = { delays += it }
        )

        val decision = gate.awaitListenWindow { true }

        assertEquals(listOf(450L), delays) // §16: phone speaker floor, not the raw 350 ms
        assertTrue(decision is VoiceTurnDecision.Ready)
    }

    @Test
    fun `headset gap is unchanged`() = runTest {
        val delays = mutableListOf<Long>()
        val gate = StudyVoiceTurnGate(
            routeProvider = { headsetRoute() },
            configuredGapMs = { 350 },
            speechActive = { false },
            queueDepth = { 0 },
            delayFn = { delays += it }
        )

        gate.awaitListenWindow { true }

        assertEquals(listOf(350L), delays)
    }

    @Test
    fun `speech still playing means the microphone never opens`() = runTest {
        var speech = true
        val gate = StudyVoiceTurnGate(
            routeProvider = { phoneRoute() },
            configuredGapMs = { 350 },
            speechActive = { speech },
            queueDepth = { 0 },
            delayFn = { }
        )

        val decision = gate.awaitListenWindow { true }

        assertEquals(VoiceTurnDecision.Blocked(VoiceTurnBlock.SPEECH_ACTIVE), decision)
        speech = false
    }

    @Test
    fun `queued speech counts as speaking`() = runTest {
        val gate = StudyVoiceTurnGate(
            routeProvider = { phoneRoute() },
            configuredGapMs = { 350 },
            speechActive = { false },
            queueDepth = { 2 },
            delayFn = { }
        )

        assertEquals(
            VoiceTurnDecision.Blocked(VoiceTurnBlock.SPEECH_ACTIVE),
            gate.awaitListenWindow { true }
        )
    }

    @Test
    fun `stale acoustic gap never opens the microphone`() = runTest {
        // §102: TTS finishes, the gap is running, the user skips the card. The old gap must
        // not start a listen for a question that no longer exists.
        var turnValid = true
        val gate = StudyVoiceTurnGate(
            routeProvider = { phoneRoute() },
            configuredGapMs = { 350 },
            speechActive = { false },
            queueDepth = { 0 },
            delayFn = { turnValid = false }
        )

        val decision = gate.awaitListenWindow { turnValid }

        assertEquals(VoiceTurnDecision.Blocked(VoiceTurnBlock.STALE_TURN), decision)
    }

    @Test
    fun `pause during the phone gap keeps the microphone closed`() = runTest {
        // §103
        var paused = false
        val gate = StudyVoiceTurnGate(
            routeProvider = { phoneRoute() },
            configuredGapMs = { 350 },
            speechActive = { false },
            queueDepth = { 0 },
            voicePaused = { paused },
            delayFn = { paused = true }
        )

        assertEquals(
            VoiceTurnDecision.Blocked(VoiceTurnBlock.VOICE_PAUSED),
            gate.awaitListenWindow { true }
        )
    }

    @Test
    fun `end during the phone gap keeps the microphone closed`() = runTest {
        // §104
        var turnValid = true
        val gate = StudyVoiceTurnGate(
            routeProvider = { phoneRoute() },
            configuredGapMs = { 350 },
            speechActive = { false },
            queueDepth = { 0 },
            delayFn = { turnValid = false }
        )

        assertEquals(
            VoiceTurnDecision.Blocked(VoiceTurnBlock.STALE_TURN),
            gate.awaitListenWindow { turnValid }
        )
    }

    @Test
    fun `speech restarting during the gap cancels the listen`() = runTest {
        var speech = false
        var firstWait = true
        val gate = StudyVoiceTurnGate(
            routeProvider = { phoneRoute() },
            configuredGapMs = { 350 },
            speechActive = { speech },
            queueDepth = { 0 },
            delayFn = { if (firstWait) { speech = true; firstWait = false } }
        )

        // A queued APPEND explanation must never overlap the rating listener (§15).
        assertEquals(
            VoiceTurnDecision.Blocked(VoiceTurnBlock.SPEECH_ACTIVE),
            gate.awaitListenWindow { true }
        )
    }

    @Test
    fun `route change during the gap cancels the listen`() = runTest {
        // Switching output device mid-gap would open the microphone on a half-switched audio
        // pipeline, so the turn is dropped and re-issued against the new route (§40/§41).
        var route = phoneRoute()
        val gate = StudyVoiceTurnGate(
            routeProvider = { route },
            configuredGapMs = { 350 },
            speechActive = { false },
            queueDepth = { 0 },
            delayFn = { route = headsetRoute().copy(generation = 1L) }
        )

        assertEquals(
            VoiceTurnDecision.Blocked(VoiceTurnBlock.STALE_TURN),
            gate.awaitListenWindow { true }
        )
    }

    @Test
    fun `same-generation route noise does not cancel the listen`() = runTest {
        // Labels and certainty can change without the device pair moving; that must not throw
        // away a perfectly good turn.
        var route = phoneRoute()
        val gate = StudyVoiceTurnGate(
            routeProvider = { route },
            configuredGapMs = { 350 },
            speechActive = { false },
            queueDepth = { 0 },
            delayFn = { route = phoneRoute() }
        )

        assertTrue(gate.awaitListenWindow { true } is VoiceTurnDecision.Ready)
    }

    @Test
    fun `built-in microphone is usable without any external microphone`() = runTest {
        // §11: STT must not require hasExternalMicrophone.
        val gate = StudyVoiceTurnGate(
            routeProvider = { phoneRoute() },
            configuredGapMs = { 350 },
            speechActive = { false },
            queueDepth = { 0 },
            delayFn = { }
        )

        assertTrue(gate.canOpenMicrophoneNow())
        assertTrue(gate.awaitListenWindow { true } is VoiceTurnDecision.Ready)
    }

    @Test
    fun `headphones-required route blocks listening without headphones`() = runTest {
        val blocked = resolver.resolve(StudyAudioMode.HEADSET_REQUIRED, AudioRouteSnapshot.PHONE_ONLY)
        val gate = StudyVoiceTurnGate(
            routeProvider = { blocked },
            configuredGapMs = { 350 },
            speechActive = { false },
            queueDepth = { 0 },
            delayFn = { }
        )

        assertEquals(
            VoiceTurnDecision.Blocked(VoiceTurnBlock.ROUTE_BLOCKED),
            gate.awaitListenWindow { true }
        )
    }

    @Test
    fun `no usable microphone blocks with a distinct reason`() = runTest {
        val route = resolver.resolve(
            StudyAudioMode.PHONE,
            AudioRouteSnapshot(builtInMicAvailable = false, builtInSpeakerAvailable = true)
        )
        val gate = StudyVoiceTurnGate(
            routeProvider = { route },
            configuredGapMs = { 350 },
            speechActive = { false },
            queueDepth = { 0 },
            delayFn = { }
        )

        assertEquals(
            VoiceTurnDecision.Blocked(VoiceTurnBlock.NO_MICROPHONE),
            gate.awaitListenWindow { true }
        )
    }

    @Test
    fun `push-to-talk waits for speech to settle instead of refusing`() = runTest {
        // §27: the *user* asked to talk; the engine just needs a moment to stop.
        var speech = true
        var waited = 0L
        val gate = StudyVoiceTurnGate(
            routeProvider = { phoneRoute() },
            configuredGapMs = { 350 },
            speechActive = { speech },
            queueDepth = { 0 },
            delayFn = { step ->
                waited += step
                if (waited >= 150L) speech = false
            }
        )

        val decision = gate.awaitListenWindow(stillValid = { true }, waitForSpeechToSettleMs = 600L)

        assertTrue(decision is VoiceTurnDecision.Ready)
        assertTrue(waited >= 150L)
    }
}
