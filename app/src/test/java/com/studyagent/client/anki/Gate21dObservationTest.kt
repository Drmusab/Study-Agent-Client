package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendContext
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.AnkiBackendRefresh
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiBackendResolver
import com.studyagent.client.core.anki.AnkiBackendState
import com.studyagent.client.core.anki.AnkiBackendStateLookup
import com.studyagent.client.core.anki.AnkiBackendStatusCenter
import com.studyagent.client.core.anki.AnkiBackendUnavailableReason
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCapabilityReadiness
import com.studyagent.client.core.anki.AnkiIdentityAssurance
import com.studyagent.client.core.anki.AnkiIdentityCheck
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.AnkiUnverifiabilityQualifier
import com.studyagent.client.core.anki.BackendResolutionRequest
import com.studyagent.client.core.anki.BackendResolutionResult
import com.studyagent.client.core.anki.STUDY_SESSION_REQUIREMENTS
import com.studyagent.client.core.anki.statusCode
import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiCapabilityReport
import com.studyagent.client.data.anki.ankidroid.AnkiDroidBackend
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCapabilityProbeResult
import com.studyagent.client.data.anki.ankidroid.AnkiDroidGateway
import com.studyagent.client.data.anki.ankidroid.AnkiDroidGatewayHealthSnapshot
import com.studyagent.client.data.anki.ankidroid.AnkiDroidIntegrationState
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidCardGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidDeckGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidReviewGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * GATE 21D — the observable registry state (VER-21D-17 … 21, 25, 26; CHECKPOINTS 21D-14/15/16;
 * AUDIT-21D-09/10/11/13).
 *
 * `AnkiBackendStatusCenter.states` is the observation point: it *watches* the registry's membership
 * and each backend's own flows, and never probes, refreshes or writes. These tests drive it with
 * strict recording backends, and — for the stale-probe rule, which belongs to the real adapter — with
 * the real [AnkiDroidBackend] and its publication guard.
 */
class Gate21dObservationTest {

    private fun TestScope.center(
        registry: AnkiBackendRegistry,
        mode: AnkiBackendMode = AnkiBackendMode.AUTO
    ) = AnkiBackendStatusCenter(registry, FixedPreferenceStore(mode), AnkiBackendResolver(registry), backgroundScope)

    private fun <T> TestScope.record(flow: StateFlow<T>): MutableList<T> {
        val seen = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { seen += it } }
        return seen
    }

    private fun List<AnkiBackendState>.of(id: AnkiBackendId): AnkiBackendState = single { it.backendId == id }

    // ------------------------------------------------------------------------- VER-21D-17

    @Test
    fun `states mirror the backends' own projections`() = runTest {
        val local = ProbeOnlyBackend(Gate21dIds.local, claim = Gate21dCaps.REVIEW)
        val pc = ProbeOnlyBackend(Gate21dIds.pcA, AnkiAvailability.AgentDisconnected)
        val registry = AnkiBackendRegistry(listOf(pc, local))
        val center = center(registry)
        runCurrent()

        assertEquals("the first value is the registry's own snapshot", registry.states(), center.states.value)
        assertEquals(listOf(Gate21dIds.local, Gate21dIds.pcA), center.states.value.map { it.backendId })

        local.publishReady(Gate21dCaps.REVIEW)
        runCurrent()

        val now = center.states.value
        assertEquals(AnkiCapabilityReadiness.READY, now.of(Gate21dIds.local).capability(AnkiRequiredCapability.REVIEW).operational)
        assertEquals(AnkiBackendUnavailableReason.TRANSPORT_UNAVAILABLE, now.of(Gate21dIds.pcA).unavailableReason)
    }

    @Test
    fun `a change to one backend leaves every other backend's state untouched`() = runTest {
        val local = ProbeOnlyBackend(Gate21dIds.local, AnkiAvailability.Ready(Gate21dCaps.REVIEW), Gate21dCaps.REVIEW, claim = Gate21dCaps.REVIEW)
        val pc = ProbeOnlyBackend(Gate21dIds.pcA, AnkiAvailability.AgentDisconnected)
        val registry = AnkiBackendRegistry(listOf(local, pc))
        val center = center(registry)
        runCurrent()
        val seen = record(center.states)
        val localBefore = center.states.value.of(Gate21dIds.local)
        val emissionsBefore = seen.size

        pc.publish(AnkiAvailability.AgentAuthenticationRequired)
        runCurrent()
        pc.publish(AnkiAvailability.AgentAnkiUnavailable)
        runCurrent()

        assertTrue("each change was observed", seen.size > emissionsBefore)
        seen.drop(emissionsBefore).forEach { snapshot ->
            assertEquals("the unrelated backend never changed", localBefore, snapshot.of(Gate21dIds.local))
        }
        assertEquals(
            AnkiBackendUnavailableReason.ANKI_UNAVAILABLE,
            center.states.value.of(Gate21dIds.pcA).unavailableReason
        )
    }

    // ------------------------------------------------------------------------- VER-21D-07 / 26

    @Test
    fun `observation follows registration and a removed backend is no longer collected`() = runTest {
        val a = ProbeOnlyBackend(Gate21dIds.pcA, AnkiAvailability.AgentDisconnected)
        val b = ProbeOnlyBackend(Gate21dIds.pcB, AnkiAvailability.AgentDisconnected)
        val registry = AnkiBackendRegistry(listOf(a))
        val center = center(registry)
        runCurrent()
        assertEquals(1, a.availabilityObservers)

        registry.register(b)
        runCurrent()
        assertEquals(listOf(Gate21dIds.pcA, Gate21dIds.pcB), center.states.value.map { it.backendId })
        assertEquals("the rebuilt observation holds one collector per backend, not two", 1, a.availabilityObservers)
        assertEquals(1, b.availabilityObservers)

        registry.remove(Gate21dIds.pcA)
        runCurrent()
        assertEquals(listOf(Gate21dIds.pcB), center.states.value.map { it.backendId })
        assertEquals("nothing keeps collecting a backend nobody holds", 0, a.availabilityObservers)

        val before = center.states.value
        a.publish(AnkiAvailability.Ready(Gate21dCaps.REVIEW), Gate21dCaps.REVIEW)
        runCurrent()
        assertEquals("a removed backend's later changes are invisible", before, center.states.value)
    }

    @Test
    fun `the status snapshot is derived from the same observation and reports the stored preference`() = runTest {
        val a = ProbeOnlyBackend(Gate21dIds.pcA, AnkiAvailability.Ready(Gate21dCaps.REVIEW), Gate21dCaps.REVIEW)
        val registry = AnkiBackendRegistry(listOf(a))
        val center = center(registry, AnkiBackendMode.PC_AGENT)
        runCurrent()

        assertEquals(AnkiBackendMode.PC_AGENT, center.status.value.preferred)
        assertEquals(center.states.value.map { it.descriptor }, center.status.value.entries.map { it.descriptor })

        a.publish(AnkiAvailability.AgentDisconnected)
        runCurrent()
        assertEquals("AGENT_DISCONNECTED", center.status.value.entry(Gate21dIds.pcA)!!.statusToken)
        assertEquals(center.states.value.map { it.descriptor }, center.status.value.entries.map { it.descriptor })

        // An empty registry still reports what the user stored (the old fixed EMPTY snapshot did not).
        val empty = AnkiBackendRegistry(emptyList())
        val emptyCenter = center(empty, AnkiBackendMode.ANKIDROID_LOCAL)
        runCurrent()
        assertEquals(AnkiBackendMode.ANKIDROID_LOCAL, emptyCenter.status.value.preferred)
        assertEquals(emptyList<AnkiBackendState>(), emptyCenter.states.value)
    }

    @Test
    fun `stopping the observation scope freezes the last state without corrupting it`() = runTest {
        val a = ProbeOnlyBackend(Gate21dIds.pcA, AnkiAvailability.AgentDisconnected)
        val registry = AnkiBackendRegistry(listOf(a))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val center = AnkiBackendStatusCenter(registry, FixedPreferenceStore(), AnkiBackendResolver(registry), scope)
        runCurrent()
        val frozen = center.states.value

        scope.cancel()
        a.publish(AnkiAvailability.AgentAuthenticationRequired)
        runCurrent()

        assertEquals(frozen, center.states.value)
        assertEquals(0, a.availabilityObservers)
    }

    // ------------------------------------------------------------------------- VER-21D-21 / 03 / 25

    @Test
    fun `a refresh requests only the read-only probe of exactly the named instance`() = runTest {
        val local = ProbeOnlyBackend(Gate21dIds.local, AnkiAvailability.Ready(Gate21dCaps.REVIEW), Gate21dCaps.REVIEW)
        val a = ProbeOnlyBackend(Gate21dIds.pcA, AnkiAvailability.Ready(Gate21dCaps.REVIEW), Gate21dCaps.REVIEW)
        val b = ProbeOnlyBackend(Gate21dIds.pcB, AnkiAvailability.AgentDisconnected)
        val registry = AnkiBackendRegistry(listOf(local, a, b))
        val center = center(registry)
        runCurrent()

        val outcome = registry.refresh(Gate21dIds.pcA)
        runCurrent()

        assertEquals(AnkiBackendRefresh.Requested(Gate21dIds.pcA), outcome)
        assertEquals(
            "nothing but the health probe reached the backend: no rating, reviewer action, edit, creation, media or session call",
            listOf("refreshAvailability"),
            a.calls
        )
        assertEquals("siblings were not probed", emptyList<String>(), local.calls + b.calls)
        // Merely observing (the status center is running) added no call anywhere.
        assertEquals(3, center.states.value.size)
    }

    @Test
    fun `refreshing a backend that is not there probes no one, not even a ready sibling`() = runTest {
        val a = ProbeOnlyBackend(Gate21dIds.pcA, AnkiAvailability.Ready(Gate21dCaps.REVIEW), Gate21dCaps.REVIEW)
        val registry = AnkiBackendRegistry(listOf(a))

        val outcome = registry.refresh(Gate21dIds.pcB)

        assertEquals(AnkiBackendRefresh.NotRegistered(Gate21dIds.pcB), outcome)
        assertEquals(emptyList<String>(), a.calls)
        assertEquals(AnkiBackendStateLookup.NotRegistered(Gate21dIds.pcB), registry.stateOf(Gate21dIds.pcB))
        assertNull(registry.find(Gate21dIds.pcB))
    }

    @Test
    fun `a backend removed while its probe runs is reported absent`() = runTest {
        lateinit var registry: AnkiBackendRegistry
        val a = ProbeOnlyBackend(Gate21dIds.pcA, onRefresh = { registry.remove(Gate21dIds.pcA) })
        registry = AnkiBackendRegistry(listOf(a))

        val outcome = registry.refresh(Gate21dIds.pcA)

        assertEquals(AnkiBackendRefresh.NotRegistered(Gate21dIds.pcA), outcome)
    }

    @Test
    fun `cancellation reaches the probe and is never turned into a state`() = runTest {
        val hanging = ProbeOnlyBackend(
            Gate21dIds.pcA,
            AnkiAvailability.Ready(Gate21dCaps.REVIEW),
            Gate21dCaps.REVIEW,
            onRefresh = { awaitCancellation() }
        )
        val registry = AnkiBackendRegistry(listOf(hanging))
        val center = center(registry)
        runCurrent()
        val before = center.states.value

        val job = launch { registry.refresh(Gate21dIds.pcA) }
        runCurrent()
        assertTrue(job.isActive)
        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals("a cancelled probe changed nothing", before, center.states.value)
        assertEquals(listOf("refreshAvailability"), hanging.calls)
    }

    // ------------------------------------------------------------------------- VER-21D-18

    /** A gateway whose every refresh blocks until the test completes it — a controllable slow probe. */
    private class GatedGateway : AnkiDroidGateway {
        val gates = mutableListOf<CompletableDeferred<AnkiDroidIntegrationState>>()
        override suspend fun refreshIntegrationState(): AnkiDroidIntegrationState {
            val gate = CompletableDeferred<AnkiDroidIntegrationState>()
            synchronized(gates) { gates += gate }
            return gate.await()
        }
        override suspend fun probeCapabilities(): AnkiDroidCapabilityProbeResult = error("not part of a refresh")
        override suspend fun probeHealth(): AnkiDroidGatewayHealthSnapshot = error("not part of a refresh")
        override fun currentState(): AnkiDroidIntegrationState = AnkiDroidIntegrationState.initial(0L)
    }

    private fun integrationState(availability: AnkiAvailability, capabilities: AnkiCapabilities) =
        AnkiDroidIntegrationState(
            availability = availability,
            capabilities = capabilities,
            apiCapabilities = AnkiDroidApiCapabilityReport.UNKNOWN,
            metadata = null,
            lastCheckAtMs = 0L,
            latencyMs = null,
            lastError = null,
            healthSnapshot = null
        )

    @Test
    fun `an older delayed probe cannot overwrite a newer one, as observed through the registry`() = runTest {
        val gateway = GatedGateway()
        val backend = AnkiDroidBackend(
            gateway = gateway,
            scope = backgroundScope,
            deckGateway = FakeAnkiDroidDeckGateway(),
            reviewGateway = FakeAnkiDroidReviewGateway(),
            cardGateway = FakeAnkiDroidCardGateway()
        )
        val registry = AnkiBackendRegistry(listOf(backend))
        val center = center(registry)
        runCurrent()
        val seen = record(center.states)

        // Probe A starts and is slow…
        val probeA = async { registry.refresh(Gate21dIds.local) }
        runCurrent()
        assertEquals(1, gateway.gates.size)
        // …the situation changes and probe B is requested while A is still in flight.
        val probeB = async { registry.refresh(Gate21dIds.local) }
        runCurrent()

        // A finally answers with an *unavailable* verdict that is now older than B's request.
        gateway.gates[0].complete(integrationState(AnkiAvailability.PermissionRequired(), AnkiCapabilities.NONE))
        runCurrent()
        assertEquals("B is now being probed", 2, gateway.gates.size)
        assertFalse(
            "A's stale result never became visible",
            seen.any { snapshot -> snapshot.any { it.availability is AnkiAvailability.PermissionRequired } }
        )

        // B answers READY and wins.
        gateway.gates[1].complete(integrationState(AnkiAvailability.Ready(AnkiCapabilities(deckListing = true)), AnkiCapabilities(deckListing = true)))
        probeA.await()
        probeB.await()
        runCurrent()

        val finalState = center.states.value.of(Gate21dIds.local)
        assertTrue(finalState.availability is AnkiAvailability.Ready)
        assertFalse(
            "the stale verdict is not visible at any point of the history",
            seen.any { snapshot -> snapshot.any { it.availability is AnkiAvailability.PermissionRequired } }
        )
    }

    // ------------------------------------------------------------------------- VER-21D-19

    @Test
    fun `simultaneous updates to two backends only ever publish coherent snapshots`() = runBlocking(Dispatchers.Default) {
        // Each backend *starts* inside its own vocabulary, so even the very first snapshot is checkable.
        val local = ProbeOnlyBackend(Gate21dIds.local, AnkiAvailability.Checking, claim = Gate21dCaps.REVIEW)
        val pc = ProbeOnlyBackend(Gate21dIds.pcA, AnkiAvailability.AgentDisconnected)
        val registry = AnkiBackendRegistry(listOf(local, pc))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val center = AnkiBackendStatusCenter(registry, FixedPreferenceStore(), AnkiBackendResolver(registry), scope)
        val observed = ConcurrentLinkedQueue<List<AnkiBackendState>>()
        val collector = scope.launch { center.states.collect { observed.add(it) } }

        // Disjoint vocabularies: any cross-contamination between the two backends is detectable.
        val localValues = listOf<AnkiAvailability>(
            AnkiAvailability.Checking,
            AnkiAvailability.PermissionRequired(),
            AnkiAvailability.NotInstalled,
            AnkiAvailability.Ready(Gate21dCaps.REVIEW)
        )
        val pcValues = listOf<AnkiAvailability>(
            AnkiAvailability.AgentDisconnected,
            AnkiAvailability.AgentAuthenticationRequired,
            AnkiAvailability.AgentAnkiUnavailable,
            AnkiAvailability.Ready(Gate21dCaps.DECKS_ONLY)
        )
        val rounds = 400
        val producers = listOf(
            async(Dispatchers.Default) {
                repeat(rounds) { i -> local.publish(localValues[i % localValues.size]); if (i % 7 == 0) delay(1) }
            },
            async(Dispatchers.Default) {
                repeat(rounds) { i -> pc.publish(pcValues[i % pcValues.size]); if (i % 5 == 0) delay(1) }
            }
        )
        producers.forEach { it.await() }
        val expectedLocal = localValues[(rounds - 1) % localValues.size]
        val expectedPc = pcValues[(rounds - 1) % pcValues.size]
        withTimeout(10_000) {
            while (center.states.value.of(Gate21dIds.local).availability != expectedLocal ||
                center.states.value.of(Gate21dIds.pcA).availability != expectedPc
            ) delay(5)
        }
        collector.cancel()
        scope.cancel()

        assertTrue("snapshots were observed", observed.isNotEmpty())
        observed.forEach { snapshot ->
            assertEquals(listOf(Gate21dIds.local, Gate21dIds.pcA), snapshot.map { it.backendId })
            assertTrue("local entry holds only local values", snapshot.of(Gate21dIds.local).availability in localValues)
            assertTrue("pc entry holds only pc values", snapshot.of(Gate21dIds.pcA).availability in pcValues)
        }
        assertEquals(expectedLocal, center.states.value.of(Gate21dIds.local).availability)
        assertEquals(expectedPc, center.states.value.of(Gate21dIds.pcA).availability)
    }

    // ------------------------------------------------------------------------- VER-21D-20 / 16

    @Test
    fun `loss and reconnect are reported per backend and never swapped between backends`() = runTest {
        val a = ProbeOnlyBackend(Gate21dIds.pcA, AnkiAvailability.Ready(Gate21dCaps.REVIEW), Gate21dCaps.REVIEW)
        val b = ProbeOnlyBackend(Gate21dIds.pcB, AnkiAvailability.Ready(Gate21dCaps.REVIEW), Gate21dCaps.REVIEW)
        val registry = AnkiBackendRegistry(listOf(a, b))
        val center = center(registry)
        runCurrent()

        a.publish(AnkiAvailability.AgentDisconnected, AnkiCapabilities.NONE)
        runCurrent()

        val lost = center.states.value
        assertEquals(AnkiBackendUnavailableReason.TRANSPORT_UNAVAILABLE, lost.of(Gate21dIds.pcA).unavailableReason)
        assertNull("B is simply ready", lost.of(Gate21dIds.pcB).unavailableReason)
        assertSame("A's identity still resolves to A, not to the ready B", a, registry.find(Gate21dIds.pcA))
        assertEquals(Gate21dIds.pcA, (registry.stateOf(Gate21dIds.pcA) as AnkiBackendStateLookup.Registered).state.backendId)

        a.publishReady(Gate21dCaps.REVIEW)
        runCurrent()

        val back = center.states.value.of(Gate21dIds.pcA)
        assertEquals(Gate21dIds.pcA, back.backendId)
        assertSame(a, registry.find(Gate21dIds.pcA))
        assertNull(back.unavailableReason)
    }

    @Test
    fun `a reconnect proves reachability and asserts nothing about the collection`() = runTest {
        val a = ProbeOnlyBackend(Gate21dIds.pcA, AnkiAvailability.Ready(Gate21dCaps.REVIEW), Gate21dCaps.REVIEW)
        val registry = AnkiBackendRegistry(listOf(a))
        // What a session bound to A recorded: instance, and no collection evidence (none exists).
        val bound = AnkiBackendContext(Gate21dIds.pcA)
        fun relation() = bound.resourceRelation(Gate21dIds.pcA, collectionKey = null)

        val before = relation()
        a.publish(AnkiAvailability.AgentDisconnected, AnkiCapabilities.NONE)
        a.publishReady(Gate21dCaps.REVIEW)
        val after = relation()

        assertEquals("the registry event changed no identity assurance", before, after)
        assertEquals(
            AnkiIdentityCheck.SameBackend(AnkiIdentityAssurance.IdentityUnverifiable(AnkiUnverifiabilityQualifier.NOMINAL)),
            after
        )
        // The state itself has no vocabulary in which continuity could even be asserted.
        val vocabulary = AnkiBackendState::class.java.declaredFields.map { it.name.lowercase() } +
            AnkiBackendState::class.java.declaredMethods.map { it.name.lowercase() }
        listOf("collection", "assurance", "continuity", "verified", "same").forEach { word ->
            assertTrue("AnkiBackendState must not speak of `$word`", vocabulary.none { it.contains(word) })
        }
        assertEquals(1, registry.states().size)
    }

    // ------------------------------------------------------------------------- VER-21D-22

    @Test
    fun `a test double is visible as such and no production mode will ever select it`() = runTest {
        val fake = ProbeOnlyBackend(
            AnkiBackendId.Fake("dev"),
            AnkiAvailability.Ready(Gate21dCaps.REVIEW),
            Gate21dCaps.REVIEW,
            claim = Gate21dCaps.REVIEW
        )
        val registry = AnkiBackendRegistry(listOf(fake))
        val center = center(registry)
        runCurrent()

        // Registered explicitly, so it is observable — and flagged as a test double, not hidden.
        assertEquals(1, center.states.value.size)
        assertEquals(com.studyagent.client.core.anki.AnkiBackendLocality.TEST_DOUBLE, center.states.value.single().locality)

        // …but no real mode accepts it, so it can never become a production candidate.
        AnkiBackendMode.entries.forEach { mode ->
            assertFalse("$mode must not accept a fake identity", mode.accepts(fake.id))
            val result = AnkiBackendResolver(registry).resolve(BackendResolutionRequest(mode, STUDY_SESSION_REQUIREMENTS))
            assertFalse("$mode must not resolve to a fake", result is BackendResolutionResult.Resolved)
        }
        assertEquals("resolution never probed it", emptyList<String>(), fake.calls)
    }

    // ------------------------------------------------------------------------- observation is passive

    @Test
    fun `observing a backend never calls it`() = runTest {
        val a = ProbeOnlyBackend(Gate21dIds.pcA, AnkiAvailability.Ready(Gate21dCaps.REVIEW), Gate21dCaps.REVIEW)
        val registry = AnkiBackendRegistry(listOf(a))
        val center = center(registry)
        runCurrent()
        a.publish(AnkiAvailability.AgentDisconnected)
        runCurrent()
        a.publish(AnkiAvailability.Ready(Gate21dCaps.REVIEW), Gate21dCaps.REVIEW)
        runCurrent()
        center.snapshotNow()
        center.status.value
        center.states.value

        assertEquals(emptyList<String>(), a.calls)
        assertNotEquals(AnkiAvailability.Checking.statusCode, center.status.value.entry(Gate21dIds.pcA)!!.statusToken)
    }
}
