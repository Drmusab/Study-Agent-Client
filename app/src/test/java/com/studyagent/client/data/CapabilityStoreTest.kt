package com.studyagent.client.data

import com.studyagent.client.core.models.AgentCapabilities
import com.studyagent.client.core.models.AgentCapability
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.data.repository.CapabilityStore
import com.studyagent.client.data.repository.isProtocolV2
import com.studyagent.client.data.repository.supportsV2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §10/§109/§132/§133: one authoritative capability state. A silent server is
 * a Protocol v1 agent; capability gating decides what the UI may show.
 */
class CapabilityStoreTest {

    private fun store(repo: FakeConnectionRepository, scope: CoroutineScope) = CapabilityStore(
        connectionRepository = repo,
        scope = scope,
        negotiationTimeoutMs = 4_000L
    )

    @Test
    fun `silent server resolves to legacy v1 after timeout`() = runTest {
        val repo = FakeConnectionRepository()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = store(repo, scope)

        repo.setConnected(true)
        assertEquals(AgentCapabilities.NegotiationStatus.NEGOTIATING, store.capabilities.value.status)

        advanceTimeBy(4_500)

        assertEquals(AgentCapabilities.NegotiationStatus.LEGACY_V1, store.capabilities.value.status)
        assertTrue(store.capabilities.value.isLegacyV1)
        assertFalse(store.capabilities.value.isProtocolV2)
        // Basic study stays possible; management features are gated off (§12).
        assertFalse(store.capabilities.value.supportsV2(AgentCapability.DASHBOARD))
    }

    @Test
    fun `capabilities frame resolves v2 with advertised set`() = runTest {
        val repo = FakeConnectionRepository()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = store(repo, scope)

        repo.setConnected(true)
        repo.emit(
            ServerMessage.Capabilities(
                capabilities = listOf(AgentCapability.DASHBOARD, AgentCapability.DECK_LIST),
                serverName = "Test Agent",
                serverVersion = "2.1"
            )
        )

        val caps = store.capabilities.value
        assertEquals(AgentCapabilities.NegotiationStatus.NEGOTIATED_V2, caps.status)
        assertTrue(caps.supportsV2(AgentCapability.DASHBOARD))
        assertTrue(caps.supportsV2(AgentCapability.DECK_LIST))
        assertFalse(caps.supportsV2(AgentCapability.AI_USAGE))
        assertEquals("Test Agent", caps.serverName)
        assertEquals("2.1", caps.serverVersion)

        // Even after the timeout window, the resolution sticks.
        advanceTimeBy(10_000)
        assertEquals(AgentCapabilities.NegotiationStatus.NEGOTIATED_V2, store.capabilities.value.status)
    }

    @Test
    fun `empty capability list is still v2 with nothing supported`() = runTest {
        val repo = FakeConnectionRepository()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = store(repo, scope)

        repo.setConnected(true)
        repo.emit(ServerMessage.Capabilities(capabilities = emptyList()))

        val caps = store.capabilities.value
        assertTrue(caps.isProtocolV2)
        assertFalse(caps.supportsV2(AgentCapability.DASHBOARD))
    }

    @Test
    fun `disconnect resets negotiation`() = runTest {
        val repo = FakeConnectionRepository()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = store(repo, scope)

        repo.setConnected(true)
        repo.emit(ServerMessage.Capabilities(capabilities = listOf(AgentCapability.DASHBOARD)))
        assertTrue(store.capabilities.value.isProtocolV2)

        repo.setConnected(false)
        assertEquals(AgentCapabilities.NegotiationStatus.UNKNOWN, store.capabilities.value.status)
        assertFalse(store.capabilities.value.supportsV2(AgentCapability.DASHBOARD))
    }
}
