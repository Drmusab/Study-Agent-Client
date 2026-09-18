package com.studyagent.client.network

import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.network.AgentConnectionSnapshot
import com.studyagent.client.core.network.ConnectionProblem
import com.studyagent.client.core.network.TransportStatus
import org.junit.Assert.*
import org.junit.Test

class ConnectionStateTest {

    @Test
    fun transportOpenDoesNotMeanAgentReady() {
        val transportOpen = ConnectionState.TransportConnected("192.168.1.100", 8765, "PC")
        assertTrue(transportOpen.isTransportOpen)
        assertFalse(transportOpen.isAgentReady)
        assertFalse(transportOpen.isConnected) // isConnected should be agent ready only in new model, but legacy Connected still counts
        assertEquals("TRANSPORT_OPEN", transportOpen.phaseName)
    }

    @Test
    fun readyMeansAgentReady() {
        val ready = ConnectionState.Ready("192.168.1.100", 8765, "Study PC", "2.3.0", "2", 14L, setOf("dashboard"), "agent-123", true)
        assertTrue(ready.isAgentReady)
        assertTrue(ready.isConnected)
        assertTrue(ready.isTransportOpen)
        assertEquals("READY", ready.phaseName)
    }

    @Test
    fun readyLegacyMeansAgentReady() {
        val legacy = ConnectionState.ReadyLegacy("192.168.1.100", 8765, "Old Agent", 10L)
        assertTrue(legacy.isAgentReady)
        assertTrue(legacy.isReady)
        assertEquals("READY_LEGACY", legacy.phaseName)
    }

    @Test
    fun failureBranchesDistinct() {
        val authFailed = ConnectionState.AuthenticationFailed("Invalid token")
        val tlsFailed = ConnectionState.TlsFailure("Cert invalid")
        val protocolMismatch = ConnectionState.ProtocolMismatch("Server only v3", "3", listOf("1","2"))
        val handshakeTimeout = ConnectionState.HandshakeTimeout("192.168.1.100", 8765)
        val networkUnavailable = ConnectionState.NetworkUnavailable
        val agentUnavailable = ConnectionState.AgentUnavailable("Agent busy")

        assertEquals("AUTHENTICATION_FAILED", authFailed.phaseName)
        assertEquals("TLS_FAILED", tlsFailed.phaseName)
        assertEquals("INCOMPATIBLE_PROTOCOL", protocolMismatch.phaseName)
        assertEquals("HANDSHAKE_TIMEOUT", handshakeTimeout.phaseName)
        assertEquals("NETWORK_UNAVAILABLE", networkUnavailable.phaseName)
        assertEquals("AGENT_UNAVAILABLE", agentUnavailable.phaseName)
    }

    @Test
    fun connectionProblemMapsToUserAction() {
        val problems = listOf(
            ConnectionProblem.NetworkMissing to "Check Wi-Fi",
            ConnectionProblem.DnsFailure("192.168.1.999") to "Verify host",
            ConnectionProblem.ConnectionRefused("192.168.1.100", 8765) to "Start the PC",
            ConnectionProblem.TlsFailure("Cert") to "Check secure",
            ConnectionProblem.AuthenticationRejected("Invalid") to "Edit token",
            ConnectionProblem.ProtocolMismatch(listOf("1","2"), "3") to "Update app",
            ConnectionProblem.HandshakeTimeout(10000L) to "Verify this is a Study Agent",
            ConnectionProblem.AgentNotStudyAgent("Not Study Agent") to "Verify host/port"
        )

        problems.forEach { (problem, expectedActionSnippet) ->
            assertTrue(problem.userAction.contains(expectedActionSnippet.split(" ")[0], ignoreCase = true))
        }
    }

    @Test
    fun snapshotIsCoherentTruth() {
        val snapshot = AgentConnectionSnapshot(
            phase = ConnectionState.Ready("192.168.1.100", 8765, "Study PC", "2.3.0", "2", 14L, setOf("dashboard"), "agent-1", true),
            transport = TransportStatus.OPEN,
            profile = null,
            protocolVersion = "2",
            serverName = "Study PC",
            serverVersion = "2.3.0",
            agentId = "agent-1",
            capabilities = setOf("dashboard"),
            authenticated = true,
            latencyMs = 14L,
            lastMessageAgeMs = 100L,
            retry = null,
            problem = null
        )

        assertTrue(snapshot.isReady)
        assertTrue(snapshot.isTransportOpen)
        assertEquals("2", snapshot.protocolVersion)
    }

    @Test
    fun legacyConnectedStillCountsAsReadyForCompat() {
        val legacyConnected = ConnectionState.Connected("192.168.1.100", 8765, "PC", 10L)
        // For backward compat, legacy Connected should still be considered ready
        assertTrue(legacyConnected.isConnected)
        assertTrue(legacyConnected.isAgentReady)
    }
}
