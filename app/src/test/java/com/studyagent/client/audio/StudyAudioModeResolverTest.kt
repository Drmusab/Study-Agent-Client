package com.studyagent.client.audio

import com.studyagent.client.core.audio.AudioRouteSnapshot
import com.studyagent.client.core.audio.AcousticProfile
import com.studyagent.client.core.audio.DefaultStudyAudioModeResolver
import com.studyagent.client.core.audio.EffectiveStudyAudioMode
import com.studyagent.client.core.audio.HeadsetMicKind
import com.studyagent.client.core.audio.HeadsetOutputKind
import com.studyagent.client.core.audio.RouteCertainty
import com.studyagent.client.core.audio.StudyAudioMode
import com.studyagent.client.core.audio.StudyAudioReadiness
import com.studyagent.client.core.audio.StudyInputRoute
import com.studyagent.client.core.audio.StudyOutputRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The policy matrix from §29 — no headset, headset, hybrid, forced phone, headset-required —
 * exercised as pure logic. This is the test that proves "headphones are optional" is a
 * property of the architecture rather than a claim in a README.
 */
class StudyAudioModeResolverTest {

    private val resolver = DefaultStudyAudioModeResolver()

    // ---------------------------------------------------------------- AUTO

    @Test
    fun `auto without headset resolves to phone and is ready`() {
        // §71/§90: app start with no headphones, built-in speaker + built-in microphone.
        val route = resolver.resolve(StudyAudioMode.AUTO, AudioRouteSnapshot.PHONE_ONLY)

        assertEquals(EffectiveStudyAudioMode.PHONE, route.effective)
        assertEquals(StudyOutputRoute.PHONE_SPEAKER, route.output)
        assertEquals(StudyInputRoute.BUILTIN_MIC, route.input)
        assertEquals(AcousticProfile.PHONE_SPEAKER, route.acousticProfile)
        assertEquals(StudyAudioReadiness.READY, route.readiness)
        assertTrue(route.canStartVoiceStudy)
        assertTrue(route.hasUsableMicrophone)
        assertFalse(route.usesExternalHeadsetOutput)
    }

    @Test
    fun `auto with bluetooth headset and microphone resolves to headset`() {
        // §72
        val route = resolver.resolve(StudyAudioMode.AUTO, AudioRouteSnapshot.BLUETOOTH_HEADSET)

        assertEquals(EffectiveStudyAudioMode.HEADSET, route.effective)
        assertEquals(StudyOutputRoute.BLUETOOTH, route.output)
        assertEquals(StudyInputRoute.BLUETOOTH_MIC, route.input)
        assertEquals(AcousticProfile.HEADSET, route.acousticProfile)
        assertEquals(StudyAudioReadiness.READY, route.readiness)
    }

    @Test
    fun `auto with wired headset resolves to headset`() {
        val route = resolver.resolve(StudyAudioMode.AUTO, AudioRouteSnapshot.WIRED_HEADSET)

        assertEquals(EffectiveStudyAudioMode.HEADSET, route.effective)
        assertEquals(StudyOutputRoute.WIRED, route.output)
        assertEquals(StudyInputRoute.WIRED_MIC, route.input)
        // The wired *input* is the honest half: this snapshot fixture does not claim the
        // recognizer's actual device, so the combined route stays "likely".
        assertEquals(RouteCertainty.LIKELY, route.certainty)
    }

    @Test
    fun `output-only headphones become a hybrid route, not a failure`() {
        // §73/§74/§99: A2DP output, no Bluetooth microphone, built-in microphone available.
        val route = resolver.resolve(StudyAudioMode.AUTO, AudioRouteSnapshot.OUTPUT_ONLY_HEADPHONES)

        assertEquals(EffectiveStudyAudioMode.HYBRID, route.effective)
        assertEquals(StudyOutputRoute.BLUETOOTH, route.output)
        assertEquals(StudyInputRoute.BUILTIN_MIC, route.input)
        assertTrue(route.isHybrid)
        assertEquals(StudyAudioReadiness.READY, route.readiness)
        assertTrue(route.canStartVoiceStudy)
        // Headphones isolate the app's own speech even though the mic belongs to the phone.
        assertEquals(AcousticProfile.HEADSET, route.acousticProfile)
    }

    @Test
    fun `headset output with no microphone at all is output-only, not blocked`() {
        val route = resolver.resolve(
            StudyAudioMode.AUTO,
            AudioRouteSnapshot(
                headsetOutput = HeadsetOutputKind.WIRED,
                builtInMicAvailable = false
            )
        )

        assertEquals(StudyAudioReadiness.OUTPUT_ONLY, route.readiness)
        // §87/§88: the question can still be spoken and answered with on-screen controls.
        assertTrue(route.canStartVoiceStudy)
        assertFalse(route.hasUsableMicrophone)
    }

    // ---------------------------------------------------------------- HEADSET_PREFERRED

    @Test
    fun `headset preferred falls back to phone when no headset exists`() {
        // §5: fall back, never block.
        val route = resolver.resolve(StudyAudioMode.HEADSET_PREFERRED, AudioRouteSnapshot.PHONE_ONLY)

        assertEquals(EffectiveStudyAudioMode.PHONE, route.effective)
        assertEquals(StudyAudioReadiness.READY, route.readiness)
        assertTrue(route.canStartVoiceStudy)
        assertEquals("Phone speaker", route.outputLabel)
    }

    @Test
    fun `headset preferred uses the headset when one is present`() {
        val route = resolver.resolve(StudyAudioMode.HEADSET_PREFERRED, AudioRouteSnapshot.BLUETOOTH_HEADSET)
        assertEquals(EffectiveStudyAudioMode.HEADSET, route.effective)
    }

    // ---------------------------------------------------------------- PHONE

    @Test
    fun `phone mode ignores a connected headset without failing`() {
        // §6/§98: explicit Phone preference must not degrade into Headset Required.
        val route = resolver.resolve(StudyAudioMode.PHONE, AudioRouteSnapshot.BLUETOOTH_HEADSET)

        assertEquals(EffectiveStudyAudioMode.PHONE, route.effective)
        assertEquals(StudyOutputRoute.PHONE_SPEAKER, route.output)
        assertTrue(route.canStartVoiceStudy)
        // The app does not force routing, so with a headset attached the route is *likely*.
        assertEquals(RouteCertainty.LIKELY, route.certainty)
        assertTrue(route.phonePreferredWithHeadset)
    }

    @Test
    fun `phone mode on a bare phone uses built-in speaker and microphone`() {
        val route = resolver.resolve(StudyAudioMode.PHONE, AudioRouteSnapshot.PHONE_ONLY)

        assertEquals(StudyOutputRoute.PHONE_SPEAKER, route.output)
        assertEquals(StudyInputRoute.BUILTIN_MIC, route.input)
        assertEquals(StudyAudioReadiness.READY, route.readiness)
    }

    @Test
    fun `phone mode falls back to the earpiece when no speaker is enumerated`() {
        val route = resolver.resolve(
            StudyAudioMode.PHONE,
            AudioRouteSnapshot(builtInSpeakerAvailable = false, builtInEarpieceAvailable = true)
        )

        assertEquals(StudyOutputRoute.PHONE_EARPIECE, route.output)
        assertEquals(EffectiveStudyAudioMode.PHONE, route.effective)
    }

    @Test
    fun `phone mode with no usable microphone still speaks and stays usable visually`() {
        val route = resolver.resolve(
            StudyAudioMode.PHONE,
            AudioRouteSnapshot(builtInMicAvailable = false)
        )

        assertEquals(StudyAudioReadiness.OUTPUT_ONLY, route.readiness)
        assertTrue(route.canStartVoiceStudy)
        assertFalse(route.hasUsableMicrophone)
    }

    // ---------------------------------------------------------------- HEADSET_REQUIRED

    @Test
    fun `headset required blocks voice study without headphones`() {
        // §7: the only mode that is allowed to block, and never the default.
        val route = resolver.resolve(StudyAudioMode.HEADSET_REQUIRED, AudioRouteSnapshot.PHONE_ONLY)

        assertEquals(EffectiveStudyAudioMode.BLOCKED, route.effective)
        assertEquals(StudyAudioReadiness.BLOCKED, route.readiness)
        assertFalse(route.canStartVoiceStudy)
        assertFalse(route.hasUsableMicrophone)
        assertTrue(route.reason.orEmpty().contains("Headphones required"))
    }

    @Test
    fun `headset required works when headphones are present`() {
        val route = resolver.resolve(StudyAudioMode.HEADSET_REQUIRED, AudioRouteSnapshot.WIRED_HEADSET)

        assertEquals(EffectiveStudyAudioMode.HEADSET, route.effective)
        assertEquals(StudyAudioReadiness.READY, route.readiness)
        assertTrue(route.canStartVoiceStudy)
    }

    @Test
    fun `headset required is not satisfied by a microphone alone`() {
        // Privacy intent: a Bluetooth microphone without a headset output would still play
        // study content out loud.
        val route = resolver.resolve(
            StudyAudioMode.HEADSET_REQUIRED,
            AudioRouteSnapshot(headsetMic = HeadsetMicKind.BLUETOOTH_MIC)
        )

        assertEquals(EffectiveStudyAudioMode.BLOCKED, route.effective)
    }

    // ---------------------------------------------------------------- storage migration

    @Test
    fun `unknown persisted mode migrates to auto instead of throwing`() {
        // §78: a settings file written by another build must never crash study.
        assertEquals(StudyAudioMode.AUTO, StudyAudioMode.fromStorage("SOMETHING_NEW"))
        assertEquals(StudyAudioMode.AUTO, StudyAudioMode.fromStorage(null))
        assertEquals(StudyAudioMode.AUTO, StudyAudioMode.fromStorage(""))
        assertEquals(StudyAudioMode.PHONE, StudyAudioMode.fromStorage("phone"))
        assertEquals(StudyAudioMode.HEADSET_REQUIRED, StudyAudioMode.fromStorage("HEADSET_REQUIRED"))
    }

    // ---------------------------------------------------------------- labels

    @Test
    fun `status labels never describe phone mode as an error`() {
        val phone = resolver.resolve(StudyAudioMode.AUTO, AudioRouteSnapshot.PHONE_ONLY)
        assertEquals("Phone", phone.statusLabel)
        assertEquals("📱 Phone", phone.statusLabelWithIcon)
        assertEquals("Phone speaker • Built-in microphone", phone.compactSummary)

        val hybrid = resolver.resolve(StudyAudioMode.AUTO, AudioRouteSnapshot.OUTPUT_ONLY_HEADPHONES)
        assertEquals("🎧 Headphones + phone mic", hybrid.statusLabelWithIcon)
    }
}
