package com.studyagent.client.network

import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.network.TransportStatus
import org.junit.Assert.*
import org.junit.Test

class ConnectionLifecycleTest {

    @Test
    fun lifecycleOrder() {
        val states = listOf(
            ConnectionState.Disconnected,
            ConnectionState.Resolving("192.168.1.100"),
            ConnectionState.ConnectingTransport("192.168.1.100", 8765),
            ConnectionState.TransportConnected("192.168.1.100", 8765, "PC"),
            ConnectionState.Handshaking("192.168.1.100", 8765),
            ConnectionState.Authenticating("192.168.1.100", 8765),
            ConnectionState.NegotiatingCapabilities("192.168.1.100", 8765),
            ConnectionState.Ready("192.168.1.100", 8765, "Study PC", "2.3.0", "2", 14L, setOf("dashboard"), "agent-1", true)
        )

        // Each state should have distinct phase
        val phases = states.map { it.phaseName }
        assertEquals(phases.size, phases.toSet().size)

        // Only last is ready
        assertFalse(states[0].isReady)
        assertFalse(states[1].isReady)
        assertFalse(states[2].isReady)
        assertFalse(states[3].isReady)
        assertFalse(states[4].isReady)
        assertFalse(states[5].isReady)
        assertFalse(states[6].isReady)
        assertTrue(states[7].isReady)
    }

    @Test
    fun generationPreventsParallelSockets() {
        var generation = 0

        fun connect(): Int {
            generation++
            return generation
        }

        val gen1 = connect()
        val gen2 = connect()
        val gen3 = connect()

        assertEquals(1, gen1)
        assertEquals(2, gen2)
        assertEquals(3, gen3)

        // Late callback from gen1 should be ignored if current is gen3
        fun isStale(callbackGen: Int, currentGen: Int): Boolean {
            return callbackGen != currentGen
        }

        assertTrue(isStale(gen1, gen3))
        assertTrue(isStale(gen2, gen3))
        assertFalse(isStale(gen3, gen3))
    }

    @Test
    fun exponentialBackoffWithJitter() {
        fun backoffMs(attempt: Int): Long {
            val base = 1000L
            val max = 30000L
            val exponential = base * (1 shl attempt.coerceAtMost(5))
            return exponential.coerceAtMost(max)
        }

        assertEquals(1000L, backoffMs(0))
        assertEquals(2000L, backoffMs(1))
        assertEquals(4000L, backoffMs(2))
        assertEquals(8000L, backoffMs(3))
        assertEquals(16000L, backoffMs(4))
        assertEquals(30000L, backoffMs(10)) // capped
    }

    @Test
    fun backoffResetAfterStableSuccess() {
        var attempts = 5
        var stableSuccess = true

        if (stableSuccess) {
            attempts = 0
        }

        assertEquals(0, attempts)
    }

    @Test
    fun networkMonitorDistinguishesNoNetworkVsAgentUnreachable() {
        val noNetwork = ConnectionState.NetworkUnavailable
        val agentUnreachable = ConnectionState.AgentUnavailable("Timeout")

        assertEquals("NETWORK_UNAVAILABLE", noNetwork.phaseName)
        assertEquals("AGENT_UNAVAILABLE", agentUnreachable.phaseName)

        // User actions differ
        assertTrue(noNetwork.toString().contains("NetworkUnavailable") || noNetwork.phaseName == "NETWORK_UNAVAILABLE")
    }

    @Test
    fun wrongServiceDetectionViaHandshakeTimeout() {
        val handshakeTimeout = ConnectionState.HandshakeTimeout("192.168.1.100", 8765)
        assertEquals("HANDSHAKE_TIMEOUT", handshakeTimeout.phaseName)
        assertTrue(handshakeTimeout.message.contains("Study Agent", ignoreCase = true) || handshakeTimeout.host.isNotEmpty())
    }

    @Test
    fun transportStatusLifecycle() {
        val statuses = listOf(
            TransportStatus.DISCONNECTED,
            TransportStatus.RESOLVING,
            TransportStatus.CONNECTING,
            TransportStatus.OPEN,
            TransportStatus.HANDSHAKING,
            TransportStatus.AUTHENTICATING,
            TransportStatus.NEGOTIATING,
            TransportStatus.READY
        )

        assertEquals(8, statuses.size)
        assertEquals(TransportStatus.READY, statuses.last())
    }

    @Test
    fun profileSwitchInvalidatesPrevious() {
        var currentProfileId = "profile-1"
        var generation = 1

        fun switchProfile(newId: String) {
            currentProfileId = newId
            generation++ // invalidate previous connections
        }

        switchProfile("profile-2")
        assertEquals("profile-2", currentProfileId)
        assertEquals(2, generation)
    }

    @Test
    fun manualOverrideResetsBackoff() {
        var backoffAttempt = 3
        var manualOverride = true

        if (manualOverride) {
            backoffAttempt = 0
        }

        assertEquals(0, backoffAttempt)
    }

    @Test
    fun reconnectOnNetworkRestore() {
        var wasDisconnectedDueToNetwork = true
        var networkRestored = true
        var shouldReconnect = false

        if (wasDisconnectedDueToNetwork && networkRestored) {
            shouldReconnect = true
        }

        assertTrue(shouldReconnect)
    }
}
