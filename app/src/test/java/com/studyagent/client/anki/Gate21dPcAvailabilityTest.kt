package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendContext
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiBackendResolver
import com.studyagent.client.core.anki.AnkiBackendStatusCenter
import com.studyagent.client.core.anki.AnkiBackendUnavailableReason
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCapabilityEvidence
import com.studyagent.client.core.anki.AnkiCapabilityReadiness
import com.studyagent.client.core.anki.AnkiCapabilityStatus
import com.studyagent.client.core.anki.AnkiIdentityAssurance
import com.studyagent.client.core.anki.AnkiIdentityCheck
import com.studyagent.client.core.anki.AnkiImplementedSupport
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.AnkiUnverifiabilityQualifier
import com.studyagent.client.core.anki.isReadyForReview
import com.studyagent.client.core.anki.statusCode
import com.studyagent.client.core.anki.unavailableReason
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.data.anki.remote.PcAnkiAvailabilityMapper
import com.studyagent.client.data.anki.remote.PcAnkiAvailabilitySource
import com.studyagent.client.data.anki.remote.PcAnkiReadinessReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21D — PC Anki readiness **structure** (VER-21D-10, 11, 12, 16, 18, 20; CHECKPOINTS 21D-11,
 * 21D-13, 21D-16; AUDIT-21D-06/11/12).
 *
 * ## What this does and does not verify
 *
 * No PC Anki backend exists in this repository and the agent protocol carries no Anki traffic, so
 * nothing here exercises a real PC backend: the evidence is the **readiness ladder** the locked
 * contract requires (transport → authentication → protocol → Anki beneath), checked against the
 * **real** `ConnectionState` type with explicit Anki reports as the only way to reach `Ready`. A
 * green result means "the structure cannot produce a PC `Ready` it has not been given evidence for",
 * never "a PC backend works". The report states that distinction; this class must not blur it.
 */
class Gate21dPcAvailabilityTest {

    private val caps = Gate21dCaps.REVIEW

    private fun readyConnection(
        agentId: String? = "agent-1",
        protocolVersion: String = "2",
        serverVersion: String? = "1.4.0",
        latencyMs: Long? = 12,
        authenticated: Boolean = true,
        agentFeatures: Set<String> = setOf("tts", "stt", "ai_evaluation")
    ) = ConnectionState.Ready(
        host = "pc.example", port = 8765, serverName = "Study PC", serverVersion = serverVersion,
        protocolVersion = protocolVersion, latencyMs = latencyMs, capabilities = agentFeatures,
        agentId = agentId, authenticated = authenticated
    )

    /** One instance of **every** `ConnectionState` variant, with the availability each must map to. */
    private fun everyConnectionState(): List<Pair<ConnectionState, AnkiAvailability>> = listOf(
        ConnectionState.Disconnected to AnkiAvailability.AgentDisconnected,
        ConnectionState.Resolving("h", 1) to AnkiAvailability.Checking,
        ConnectionState.ConnectingTransport("h", 1) to AnkiAvailability.Checking,
        ConnectionState.TransportConnected("h", 1, "s") to AnkiAvailability.Checking,
        ConnectionState.Handshaking("h", 1) to AnkiAvailability.Checking,
        ConnectionState.Authenticating("h", 1) to AnkiAvailability.Checking,
        ConnectionState.NegotiatingCapabilities("h", 1, "s", "2") to AnkiAvailability.Checking,
        readyConnection() to AnkiAvailability.Ready(caps),
        readyConnection(authenticated = false) to AnkiAvailability.AgentAuthenticationRequired,
        ConnectionState.ReadyLegacy("h", 1, "s", 3) to AnkiAvailability.Unsupported(PcAnkiAvailabilityMapper.PROTOCOL_LEGACY),
        ConnectionState.Reconnecting(1, 5, 1000, "r", "p") to AnkiAvailability.AgentDisconnected,
        ConnectionState.NetworkUnavailable to AnkiAvailability.AgentDisconnected,
        ConnectionState.AuthenticationFailed("bad token", isExpired = false) to AnkiAvailability.AgentAuthenticationRequired,
        ConnectionState.TlsFailure("cert") to AnkiAvailability.AgentDisconnected,
        ConnectionState.ProtocolMismatch("x", "9") to AnkiAvailability.Unsupported(PcAnkiAvailabilityMapper.PROTOCOL_INCOMPATIBLE),
        ConnectionState.AgentUnavailable("busy") to AnkiAvailability.AgentDisconnected,
        ConnectionState.ServerUnavailable("down") to AnkiAvailability.AgentDisconnected,
        ConnectionState.HandshakeTimeout("h", 1) to AnkiAvailability.AgentDisconnected,
        ConnectionState.Error("boom", IllegalStateException("secret-cause")) to AnkiAvailability.AgentDisconnected,
        ConnectionState.Connecting("h", 1) to AnkiAvailability.Checking,
        ConnectionState.Connected("h", 1, "s", 3) to AnkiAvailability.Unsupported(PcAnkiAvailabilityMapper.PROTOCOL_LEGACY)
    )

    private val allReports = listOf(
        PcAnkiReadinessReport.NotReported,
        PcAnkiReadinessReport.AnkiUnavailable,
        PcAnkiReadinessReport.CollectionUnavailable,
        PcAnkiReadinessReport.Ready(caps)
    )

    // ------------------------------------------------------------------------- the ladder

    @Test
    fun `every connection state maps to its own availability when Anki reports ready`() {
        val states = everyConnectionState()
        assertEquals("every ConnectionState variant is covered", 21, states.size)
        states.forEach { (connection, expected) ->
            assertEquals(
                connection::class.simpleName,
                expected,
                PcAnkiAvailabilityMapper.availability(connection, PcAnkiReadinessReport.Ready(caps))
            )
        }
    }

    @Test
    fun `ready is produced in exactly one situation and in no other`() {
        everyConnectionState().forEach { (connection, _) ->
            allReports.forEach { report ->
                val availability = PcAnkiAvailabilityMapper.availability(connection, report)
                val authenticatedReadyAgent = connection is ConnectionState.Ready && connection.authenticated
                val ankiReady = report is PcAnkiReadinessReport.Ready
                assertEquals(
                    "${connection::class.simpleName} × ${report::class.simpleName}",
                    authenticatedReadyAgent && ankiReady,
                    availability is AnkiAvailability.Ready
                )
            }
        }
    }

    // ------------------------------------------------------------------------- VER-21D-10

    @Test
    fun `a connected agent without an Anki report is never a ready PC Anki backend`() {
        val connection = readyConnection()

        assertEquals(AnkiAvailability.Checking, PcAnkiAvailabilityMapper.availability(connection, PcAnkiReadinessReport.NotReported))
        assertEquals(
            AnkiAvailability.AgentAnkiUnavailable,
            PcAnkiAvailabilityMapper.availability(connection, PcAnkiReadinessReport.AnkiUnavailable)
        )
        assertEquals(
            AnkiAvailability.CollectionNotInitialized,
            PcAnkiAvailabilityMapper.availability(connection, PcAnkiReadinessReport.CollectionUnavailable)
        )
        listOf(
            PcAnkiReadinessReport.NotReported,
            PcAnkiReadinessReport.AnkiUnavailable,
            PcAnkiReadinessReport.CollectionUnavailable
        ).forEach {
            val availability = PcAnkiAvailabilityMapper.availability(connection, it)
            assertFalse(availability.isReadyForReview)
            assertFalse(availability is AnkiAvailability.Ready)
        }
    }

    @Test
    fun `AI and speech readiness of the agent never stands in for Anki readiness`() {
        // The agent advertises every AI feature — and even a feature literally named "anki".
        val connection = readyConnection(agentFeatures = setOf("tts", "stt", "ai_evaluation", "anki", "anki_review"))

        val availability = PcAnkiAvailabilityMapper.availability(connection, PcAnkiReadinessReport.NotReported)

        assertEquals(AnkiAvailability.Checking, availability)
        // And once Anki *does* report, only the negotiated Anki capabilities count — not the agent's.
        val negotiated = PcAnkiAvailabilityMapper.availability(
            connection,
            PcAnkiReadinessReport.Ready(AnkiCapabilities(deckListing = true))
        ) as AnkiAvailability.Ready
        assertEquals(AnkiCapabilities(deckListing = true), negotiated.capabilities)
        assertFalse(negotiated.capabilities.review)
    }

    // ------------------------------------------------------------------------- VER-21D-11

    @Test
    fun `an authentication failure is distinguishable, non-ready and leaks nothing`() {
        listOf(
            ConnectionState.AuthenticationFailed("bad token abc123 for secret-host", isExpired = false),
            ConnectionState.AuthenticationFailed("expired credential abc123", isExpired = true),
            readyConnection(authenticated = false)
        ).forEach { connection ->
            val availability = PcAnkiAvailabilityMapper.availability(connection, PcAnkiReadinessReport.Ready(caps))
            assertEquals(AnkiAvailability.AgentAuthenticationRequired, availability)
            assertEquals(AnkiBackendUnavailableReason.AUTHENTICATION_REQUIRED, availability.unavailableReason())
            assertFalse(availability is AnkiAvailability.Ready)
            assertEquals("AGENT_AUTHENTICATION_REQUIRED", availability.statusCode)
        }
        // A distinct condition from every other non-ready state.
        val others = listOf(
            PcAnkiAvailabilityMapper.availability(ConnectionState.NetworkUnavailable, PcAnkiReadinessReport.NotReported),
            PcAnkiAvailabilityMapper.availability(readyConnection(), PcAnkiReadinessReport.AnkiUnavailable),
            PcAnkiAvailabilityMapper.availability(ConnectionState.ProtocolMismatch("x", null), PcAnkiReadinessReport.NotReported)
        )
        others.forEach { assertTrue(it.unavailableReason() != AnkiBackendUnavailableReason.AUTHENTICATION_REQUIRED) }
    }

    @Test
    fun `no endpoint, credential or rejection text reaches availability`() {
        val secretive = listOf(
            ConnectionState.Resolving("secret-host", 4242),
            ConnectionState.TransportConnected("secret-host", 4242, "secret-server"),
            ConnectionState.AuthenticationFailed("token=abc123", false),
            ConnectionState.TlsFailure("pinned cert of secret-host"),
            ConnectionState.ProtocolMismatch("server said secret", "secret-version"),
            ConnectionState.AgentUnavailable("secret agent text"),
            ConnectionState.Error("secret message", IllegalStateException("secret cause"))
        )
        secretive.forEach { connection ->
            allReports.forEach { report ->
                val availability = PcAnkiAvailabilityMapper.availability(connection, report)
                val text = availability.toString() + availability.statusCode + availability.unavailableReason()
                listOf("secret", "abc123", "4242").forEach {
                    assertFalse("${connection::class.simpleName} leaked `$it` into availability: $text", text.contains(it))
                }
            }
        }
    }

    // ------------------------------------------------------------------------- VER-21D-12

    @Test
    fun `an incompatible protocol is a version problem, and unnegotiated capabilities are not supported`() {
        val mismatch = PcAnkiAvailabilityMapper.availability(
            ConnectionState.ProtocolMismatch("client 1,2 server 9", "9"),
            PcAnkiReadinessReport.Ready(caps)
        )
        assertEquals(AnkiAvailability.Unsupported(PcAnkiAvailabilityMapper.PROTOCOL_INCOMPATIBLE), mismatch)
        assertEquals(AnkiBackendUnavailableReason.VERSION_INCOMPATIBLE, mismatch.unavailableReason())

        // A legacy (v1) agent cannot have negotiated anything for Anki, however ready it looks.
        val legacy = PcAnkiAvailabilityMapper.availability(
            ConnectionState.ReadyLegacy("h", 1, "s", 5),
            PcAnkiReadinessReport.Ready(caps)
        )
        assertEquals(AnkiBackendUnavailableReason.VERSION_INCOMPATIBLE, legacy.unavailableReason())

        // Negotiation finished but produced nothing: SUPPORTED is never assumed for a negotiated backend.
        val state = com.studyagent.client.core.anki.AnkiBackendState(
            com.studyagent.client.core.anki.AnkiBackendDescriptor(
                Gate21dIds.pcA,
                AnkiAvailability.Ready(AnkiCapabilities.NONE),
                AnkiCapabilities.NONE
            ),
            implementedClaim = null
        )
        AnkiRequiredCapability.entries.forEach {
            assertEquals(
                "$it",
                AnkiCapabilityStatus(AnkiImplementedSupport.UNSUPPORTED, AnkiCapabilityReadiness.NOT_READY),
                state.capability(it)
            )
        }
    }

    // ------------------------------------------------------------------------- evidence belongs to a session

    @Test
    fun `before anything is reported a connected agent is checking, not ready`() = runTest {
        val connection = MutableStateFlow<ConnectionState>(readyConnection())
        val source = PcAnkiAvailabilitySource(connection, backgroundScope)
        runCurrent()

        assertEquals(AnkiAvailability.Checking, source.availability.value)
    }

    @Test
    fun `a report for the live session is accepted and becomes availability`() = runTest {
        val connection = MutableStateFlow<ConnectionState>(readyConnection())
        val source = PcAnkiAvailabilitySource(connection, backgroundScope)
        runCurrent()

        assertTrue(source.report(source.currentSession(), PcAnkiReadinessReport.AnkiUnavailable))
        runCurrent()
        assertEquals(AnkiAvailability.AgentAnkiUnavailable, source.availability.value)

        assertTrue(source.report(source.currentSession(), PcAnkiReadinessReport.Ready(caps)))
        runCurrent()
        assertEquals(AnkiAvailability.Ready(caps), source.availability.value)
    }

    @Test
    fun `a disconnect clears what the agent said and a reconnect starts from nothing`() = runTest {
        val connection = MutableStateFlow<ConnectionState>(readyConnection())
        val source = PcAnkiAvailabilitySource(connection, backgroundScope)
        runCurrent()
        source.report(source.currentSession(), PcAnkiReadinessReport.Ready(caps))
        runCurrent()
        assertTrue(source.availability.value is AnkiAvailability.Ready)

        connection.value = ConnectionState.NetworkUnavailable
        runCurrent()
        assertEquals(AnkiAvailability.AgentDisconnected, source.availability.value)

        connection.value = readyConnection()
        runCurrent()
        assertEquals(
            "the transport is back, and that is all it proves: Anki must say so again",
            AnkiAvailability.Checking,
            source.availability.value
        )
    }

    @Test
    fun `a late answer from before the reconnect can neither revive nor overwrite the new session`() = runTest {
        val connection = MutableStateFlow<ConnectionState>(readyConnection())
        val source = PcAnkiAvailabilitySource(connection, backgroundScope)
        runCurrent()
        val oldSession = source.currentSession()

        connection.value = ConnectionState.Reconnecting(1, 3, 500, null, null)
        runCurrent()
        connection.value = readyConnection()
        runCurrent()
        val newSession = source.currentSession()

        // Probe B (new session) says READY…
        assertTrue(source.report(newSession, PcAnkiReadinessReport.Ready(caps)))
        runCurrent()
        assertEquals(AnkiAvailability.Ready(caps), source.availability.value)
        // …and Probe A's delayed answer from the previous session arrives afterwards: rejected.
        assertFalse(source.report(oldSession, PcAnkiReadinessReport.AnkiUnavailable))
        runCurrent()
        assertEquals("the older observation did not overwrite the newer one", AnkiAvailability.Ready(caps), source.availability.value)
    }

    @Test
    fun `an old session's ready answer cannot revive a new session either`() = runTest {
        val connection = MutableStateFlow<ConnectionState>(readyConnection())
        val source = PcAnkiAvailabilitySource(connection, backgroundScope)
        runCurrent()
        val oldSession = source.currentSession()
        connection.value = ConnectionState.NetworkUnavailable
        runCurrent()
        connection.value = readyConnection()
        runCurrent()

        assertFalse(source.report(oldSession, PcAnkiReadinessReport.Ready(caps)))
        runCurrent()

        assertEquals(AnkiAvailability.Checking, source.availability.value)
    }

    @Test
    fun `a different agent or protocol version starts a new session, a latency change does not`() = runTest {
        val connection = MutableStateFlow<ConnectionState>(readyConnection(agentId = "agent-1", latencyMs = 10))
        val source = PcAnkiAvailabilitySource(connection, backgroundScope)
        runCurrent()
        source.report(source.currentSession(), PcAnkiReadinessReport.Ready(caps))
        runCurrent()

        connection.value = readyConnection(agentId = "agent-1", latencyMs = 250)
        runCurrent()
        assertEquals("latency is not identity", AnkiAvailability.Ready(caps), source.availability.value)

        connection.value = readyConnection(agentId = "agent-2", latencyMs = 250)
        runCurrent()
        assertEquals("another agent: what the first one said is void", AnkiAvailability.Checking, source.availability.value)

        source.report(source.currentSession(), PcAnkiReadinessReport.Ready(caps))
        runCurrent()
        connection.value = readyConnection(agentId = "agent-2", protocolVersion = "3")
        runCurrent()
        assertEquals("a protocol change voids the negotiation", AnkiAvailability.Checking, source.availability.value)

        source.report(source.currentSession(), PcAnkiReadinessReport.Ready(caps))
        runCurrent()
        connection.value = readyConnection(agentId = "agent-2", protocolVersion = "3", serverVersion = "2.0.0")
        runCurrent()
        assertEquals("a server version change voids it too", AnkiAvailability.Checking, source.availability.value)
    }

    @Test
    fun `nothing can be reported for a connection that is not authenticated and ready`() = runTest {
        val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val source = PcAnkiAvailabilitySource(connection, backgroundScope)
        runCurrent()

        assertFalse(source.report(source.currentSession(), PcAnkiReadinessReport.Ready(caps)))
        connection.value = readyConnection(authenticated = false)
        runCurrent()
        assertFalse(source.report(source.currentSession(), PcAnkiReadinessReport.Ready(caps)))
        assertEquals(AnkiAvailability.AgentAuthenticationRequired, source.availability.value)
    }

    // ------------------------------------------------------------------------- VER-21D-16 / 20

    @Test
    fun `a PC backend driven by the readiness source is observable through the registry`() = runTest {
        val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val source = PcAnkiAvailabilitySource(connection, backgroundScope)
        val liveCaps = source.availability
            .map { (it as? AnkiAvailability.Ready)?.capabilities ?: AnkiCapabilities.NONE }
            .stateIn(backgroundScope, SharingStarted.Eagerly, AnkiCapabilities.NONE)
        val pc = ProbeOnlyBackend(
            Gate21dIds.pcA,
            availabilityOverride = source.availability,
            capabilitiesOverride = liveCaps
        )
        val registry = AnkiBackendRegistry(listOf(pc))
        val center = AnkiBackendStatusCenter(registry, FixedPreferenceStore(), AnkiBackendResolver(registry), backgroundScope)
        runCurrent()
        val req = AnkiRequiredCapability.REVIEW
        fun state() = center.states.value.single()

        // 1. No transport.
        assertEquals(AnkiBackendUnavailableReason.TRANSPORT_UNAVAILABLE, state().unavailableReason)
        assertEquals(AnkiCapabilityStatus(AnkiImplementedSupport.UNKNOWN, AnkiCapabilityReadiness.NOT_READY), state().capability(req))

        // 2. Transport + auth + protocol are fine: still not Anki.
        connection.value = readyConnection()
        runCurrent()
        assertNull(state().unavailableReason)
        assertEquals(AnkiCapabilityStatus(AnkiImplementedSupport.UNKNOWN, AnkiCapabilityReadiness.UNKNOWN), state().capability(req))
        assertFalse(state().descriptor.isReachable)

        // 3. The agent says desktop Anki is down: connected, authenticated, and *still not ready*.
        source.report(source.currentSession(), PcAnkiReadinessReport.AnkiUnavailable)
        runCurrent()
        assertEquals(AnkiBackendUnavailableReason.ANKI_UNAVAILABLE, state().unavailableReason)
        assertFalse(state().descriptor.isReachable)

        // 4. Anki reports ready with negotiated capabilities.
        source.report(source.currentSession(), PcAnkiReadinessReport.Ready(caps))
        runCurrent()
        assertEquals(AnkiCapabilityEvidence.RUNTIME_NEGOTIATED, state().capabilityEvidence)
        assertEquals(AnkiCapabilityStatus(AnkiImplementedSupport.SUPPORTED, AnkiCapabilityReadiness.READY), state().capability(req))
        val oldSession = source.currentSession()

        // 5. The connection drops: negotiated evidence is not retained, identity does not change.
        connection.value = ConnectionState.NetworkUnavailable
        runCurrent()
        assertEquals(AnkiBackendUnavailableReason.TRANSPORT_UNAVAILABLE, state().unavailableReason)
        assertEquals(AnkiCapabilityStatus(AnkiImplementedSupport.UNKNOWN, AnkiCapabilityReadiness.NOT_READY), state().capability(req))
        assertEquals(Gate21dIds.pcA, state().backendId)
        assertSame(pc, registry.find(Gate21dIds.pcA))

        // 6. It reconnects: reachable again, and the registry still does not call it ready.
        connection.value = readyConnection()
        runCurrent()
        assertEquals(AnkiCapabilityStatus(AnkiImplementedSupport.UNKNOWN, AnkiCapabilityReadiness.UNKNOWN), state().capability(req))
        assertFalse("reconnected is not ready", state().descriptor.isReachable)

        // 7. A stale answer from the old session is refused; a fresh one is accepted.
        assertFalse(source.report(oldSession, PcAnkiReadinessReport.Ready(caps)))
        runCurrent()
        assertFalse(state().descriptor.isReachable)
        assertTrue(source.report(source.currentSession(), PcAnkiReadinessReport.Ready(caps)))
        runCurrent()
        assertEquals(AnkiCapabilityStatus(AnkiImplementedSupport.SUPPORTED, AnkiCapabilityReadiness.READY), state().capability(req))

        // Identity is untouched by all of it, and so is every assurance a bound context could claim.
        assertEquals(Gate21dIds.pcA, state().backendId)
        assertEquals(
            AnkiIdentityCheck.SameBackend(AnkiIdentityAssurance.IdentityUnverifiable(AnkiUnverifiabilityQualifier.NOMINAL)),
            AnkiBackendContext(Gate21dIds.pcA).resourceRelation(Gate21dIds.pcA, null)
        )
        // The whole story was told without a single call into the backend beyond its flows.
        assertEquals(emptyList<String>(), pc.calls)
    }
}
