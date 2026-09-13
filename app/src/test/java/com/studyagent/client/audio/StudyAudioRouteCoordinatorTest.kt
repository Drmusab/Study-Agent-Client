package com.studyagent.client.audio

import com.studyagent.client.core.audio.AudioRouteSnapshot
import com.studyagent.client.core.audio.EffectiveStudyAudioMode
import com.studyagent.client.core.audio.StudyAudioDisconnectPolicy
import com.studyagent.client.core.audio.StudyAudioMode
import com.studyagent.client.core.audio.StudyAudioPreferences
import com.studyagent.client.core.audio.StudyAudioRouteCoordinator
import com.studyagent.client.core.audio.StudyAudioRouteEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.launch
import org.junit.Test

/**
 * Route coordination over time: loss semantics, deferred switches, overrides (§36–§44,
 * §93–§98, §117/§118).
 *
 * The single most important behaviour here is the one that used to be wrong: an app that
 * *starts* without a headset must never produce a "headset lost" event, and must never run
 * headset-recovery logic (§43/§44/§93/§119).
 */
class StudyAudioRouteCoordinatorTest {

    private class Harness(
        val snapshots: MutableStateFlow<AudioRouteSnapshot>,
        val preferences: MutableStateFlow<StudyAudioPreferences>,
        val coordinator: StudyAudioRouteCoordinator,
        val events: MutableList<StudyAudioRouteEvent>
    )

    /** Builds a harness whose event collector keeps running for the whole test. */
    private fun CoroutineScope.harnessWithLiveEvents(
        snapshot: AudioRouteSnapshot = AudioRouteSnapshot.PHONE_ONLY,
        preferences: StudyAudioPreferences = StudyAudioPreferences()
    ): Harness {
        val snapshots = MutableStateFlow(snapshot)
        val prefs = MutableStateFlow(preferences)
        val coordinator = StudyAudioRouteCoordinator(
            snapshots = snapshots,
            preferences = prefs,
            scope = this
        )
        val events = mutableListOf<StudyAudioRouteEvent>()
        launch { coordinator.events.collect { events += it } }
        return Harness(snapshots, prefs, coordinator, events)
    }

    @Test
    fun `starting without a headset is phone mode and produces no loss event`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val h = scope.harnessWithLiveEvents()

        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)
        assertTrue(h.events.isEmpty())
        assertEquals(0, h.coordinator.metrics.metrics.value.headsetLossEvents)
        assertEquals(0, h.coordinator.metrics.metrics.value.blockedStarts)
        assertNull(h.coordinator.attention.value)
    }

    @Test
    fun `device churn without a headset never looks like a route loss`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.harnessWithLiveEvents()

        repeat(100) { revision ->
            h.snapshots.value = AudioRouteSnapshot.PHONE_ONLY.copy(revision = revision.toLong())
        }

        assertTrue(h.events.isEmpty())
        assertEquals(0, h.coordinator.metrics.metrics.value.headsetLossEvents)
        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)
    }

    @Test
    fun `headset appearing mid-turn is deferred to a safe boundary`() = runTest {
        // §39/§94: never switch the output device in the middle of a question.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.harnessWithLiveEvents()

        h.snapshots.value = AudioRouteSnapshot.BLUETOOTH_HEADSET

        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)
        assertNotNull(h.coordinator.pendingRoute.value)
        assertTrue(h.events.any { it is StudyAudioRouteEvent.ExternalHeadsetConnected && !it.applied })

        h.coordinator.onSafeTurnBoundary()

        assertEquals(EffectiveStudyAudioMode.HEADSET, h.coordinator.effectiveRoute.value.effective)
        assertNull(h.coordinator.pendingRoute.value)
        assertTrue(h.events.any { it is StudyAudioRouteEvent.RouteChanged && it.atSafeBoundary })
    }

    @Test
    fun `headset loss switches immediately and raises attention under the pause policy`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.harnessWithLiveEvents(snapshot = AudioRouteSnapshot.BLUETOOTH_HEADSET)

        assertEquals(EffectiveStudyAudioMode.HEADSET, h.coordinator.effectiveRoute.value.effective)

        h.snapshots.value = AudioRouteSnapshot.PHONE_ONLY

        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)
        assertEquals(1, h.coordinator.metrics.metrics.value.headsetLossEvents)
        val loss = h.events.filterIsInstance<StudyAudioRouteEvent.ExternalHeadsetLost>().single()
        assertEquals(StudyAudioDisconnectPolicy.PAUSE_VOICE, loss.policy)
        assertNotNull(h.coordinator.attention.value)
        assertFalse(h.coordinator.headsetRouteActive.value)
    }

    @Test
    fun `continue on phone clears the prompt and pins the phone route`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.harnessWithLiveEvents(snapshot = AudioRouteSnapshot.BLUETOOTH_HEADSET)
        h.snapshots.value = AudioRouteSnapshot.PHONE_ONLY
        assertNotNull(h.coordinator.attention.value)

        h.coordinator.continueOnPhone()

        assertNull(h.coordinator.attention.value)
        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)

        // Headphones reappearing releases the "continue on phone" hold, but the upgrade itself
        // is still deferred to a safe boundary so nothing switches mid-turn (§39/§94).
        h.snapshots.value = AudioRouteSnapshot.BLUETOOTH_HEADSET
        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)
        assertNotNull(h.coordinator.pendingRoute.value)
        h.coordinator.onSafeTurnBoundary()
        assertEquals(EffectiveStudyAudioMode.HEADSET, h.coordinator.effectiveRoute.value.effective)
    }

    @Test
    fun `continue on phone without headphones keeps the phone route`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.harnessWithLiveEvents()

        h.coordinator.continueOnPhone()

        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)
        assertEquals(0, h.coordinator.metrics.metrics.value.headsetLossEvents)
        assertNull(h.coordinator.attention.value)
    }

    @Test
    fun `auto fallback policy produces a loss event without a prompt`() = runTest {
        // §97: "Continue on phone" as the configured policy — no modal, the session continues.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.harnessWithLiveEvents(
            snapshot = AudioRouteSnapshot.BLUETOOTH_HEADSET,
            preferences = StudyAudioPreferences(disconnectPolicy = StudyAudioDisconnectPolicy.CONTINUE_ON_PHONE)
        )

        h.snapshots.value = AudioRouteSnapshot.PHONE_ONLY

        val loss = h.events.filterIsInstance<StudyAudioRouteEvent.ExternalHeadsetLost>().single()
        assertEquals(StudyAudioDisconnectPolicy.CONTINUE_ON_PHONE, loss.policy)
        assertNull(h.coordinator.attention.value)
        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)
    }

    @Test
    fun `user switching to phone mode applies immediately and clears the override`() = runTest {
        // §84/§85: a manual mode change is explicit intent and may cut a turn short.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.harnessWithLiveEvents(snapshot = AudioRouteSnapshot.BLUETOOTH_HEADSET)

        h.preferences.value = StudyAudioPreferences(mode = StudyAudioMode.PHONE)

        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)
        assertNull(h.coordinator.pendingRoute.value)
        assertTrue(h.events.any { it is StudyAudioRouteEvent.RouteChanged && !it.atSafeBoundary })
    }

    @Test
    fun `switching the preference to phone with headphones attached is not a loss`() = runTest {
        // §44: loss means the *device* went away. A preference change is a user decision, so it
        // applies immediately and must not raise the disconnect prompt.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.harnessWithLiveEvents(snapshot = AudioRouteSnapshot.BLUETOOTH_HEADSET)

        h.preferences.value = StudyAudioPreferences(mode = StudyAudioMode.PHONE)

        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)
        assertEquals(0, h.coordinator.metrics.metrics.value.headsetLossEvents)
        assertNull(h.coordinator.attention.value)
        assertTrue(h.events.none { it is StudyAudioRouteEvent.ExternalHeadsetLost })
        assertTrue(h.events.any { it is StudyAudioRouteEvent.RouteChanged && !it.atSafeBoundary })
    }

    @Test
    fun `use headphones now applies immediately when headphones exist`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.harnessWithLiveEvents()

        h.snapshots.value = AudioRouteSnapshot.BLUETOOTH_HEADSET
        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)

        assertTrue(h.coordinator.useHeadsetNow())
        // Applied immediately (manual action), not left pending.
        assertEquals(EffectiveStudyAudioMode.HEADSET, h.coordinator.effectiveRoute.value.effective)
    }

    @Test
    fun `use headphones now refuses without headphones`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.harnessWithLiveEvents()

        assertFalse(h.coordinator.useHeadsetNow())
        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)
    }

    @Test
    fun `headset required reports blocked and never falls back`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.harnessWithLiveEvents(
            preferences = StudyAudioPreferences(mode = StudyAudioMode.HEADSET_REQUIRED)
        )

        assertFalse(h.coordinator.effectiveRoute.value.canStartVoiceStudy)
        assertEquals(EffectiveStudyAudioMode.BLOCKED, h.coordinator.effectiveRoute.value.effective)
        // Counted as a blocked start so Diagnostics can show why study did not begin.
        assertTrue(h.coordinator.metrics.metrics.value.blockedStarts >= 1)
    }

    @Test
    fun `route generation changes only when the effective route changes`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.harnessWithLiveEvents()
        val initialGeneration = h.coordinator.effectiveRoute.value.generation

        repeat(10) { h.snapshots.value = AudioRouteSnapshot.PHONE_ONLY.copy(revision = it.toLong()) }
        assertEquals(initialGeneration, h.coordinator.effectiveRoute.value.generation)

        h.snapshots.value = AudioRouteSnapshot.WIRED_HEADSET
        h.coordinator.onSafeTurnBoundary()
        assertTrue(h.coordinator.effectiveRoute.value.generation > initialGeneration)
    }

    @Test
    fun `dismissing the prompt keeps the phone route`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.harnessWithLiveEvents(snapshot = AudioRouteSnapshot.BLUETOOTH_HEADSET)
        h.snapshots.value = AudioRouteSnapshot.PHONE_ONLY
        assertNotNull(h.coordinator.attention.value)

        h.coordinator.dismissAttention()

        assertNull(h.coordinator.attention.value)
        assertEquals(EffectiveStudyAudioMode.PHONE, h.coordinator.effectiveRoute.value.effective)
    }

    @Test
    fun `diagnostics rows distinguish preference from effective route`() = runTest {
        // §68: "Preference: Auto / Currently using: Phone".
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val h = scope.harnessWithLiveEvents()
        val rows = h.coordinator.diagnosticsRows().toMap()

        assertEquals("Automatic", rows["Study audio mode"])
        assertEquals("Phone", rows["Effective mode"])
        assertEquals("Not connected", rows["External headset"])
        assertEquals("Phone Speaker", rows["Acoustic profile"])
    }
}
