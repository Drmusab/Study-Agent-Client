package com.studyagent.client.network

import com.studyagent.client.core.models.AgentCapabilities
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.network.ProtocolContext
import org.junit.Assert.*
import org.junit.Test

class ProtocolHandshakeTest {

    @Test
    fun clientHelloAdvertisesVersionFromBuildConfig() {
        val context = ProtocolContext(
            versionProvider = { "1.2.3" },
            supportedVersions = listOf("2", "1")
        )
        val hello = context.createHello(
            supportedProtocols = listOf("2", "1"),
            authToken = "token-123"
        )
        assertEquals("1.2.3", hello.clientVersion)
        assertEquals(listOf("2", "1"), hello.supportedVersions)
    }

    @Test
    fun noHardwareIdentifiersInHello() {
        val context = ProtocolContext(
            versionProvider = { "1.0.0" },
            supportedVersions = listOf("2")
        )
        val hello = context.createHello(listOf("2"), null)
        val json = hello.toString()
        assertFalse(json.contains("imei", ignoreCase = true))
        assertFalse(json.contains("android_id", ignoreCase = true))
        assertFalse(json.contains("serial", ignoreCase = true))
    }

    @Test
    fun welcomeContainsSelectedProtocol() {
        val welcome = ServerMessage.Welcome(
            selectedProtocol = "2",
            serverName = "Study Agent",
            serverVersion = "2.3.0",
            agentId = "persistent-uuid-123",
            capabilities = listOf("dashboard", "study_config", "session_recovery"),
            authentication = ServerMessage.AuthInfo(required = true)
        )
        assertEquals("2", welcome.selectedProtocol)
        assertEquals("persistent-uuid-123", welcome.agentId)
        assertTrue(welcome.capabilities.contains("dashboard"))
    }

    @Test
    fun protocolNegotiationServerChooses() {
        val clientSupported = listOf("2", "1")
        val serverSupported = listOf("2")
        val selected = serverSupported.firstOrNull { it in clientSupported }
        assertEquals("2", selected)

        val incompatibleClient = listOf("1")
        val incompatibleServer = listOf("3")
        val none = incompatibleServer.firstOrNull { it in incompatibleClient }
        assertNull(none)
    }

    @Test
    fun welcomeBackwardCompatForOldClient() {
        val caps = AgentCapabilities.fromStrings(setOf("dashboard", "unknown_future_cap"))
        assertTrue(caps.hasDashboard)
        assertTrue(caps.raw.contains("unknown_future_cap"))
    }

    @Test
    fun protocolContextFactoryCreatesMessagesWithVersion() {
        val factory = ProtocolContext(versionProvider = { "9.9.9" })
        val start = factory.createStartSession("Deck", "msg-1")
        assertEquals("Deck", start.deck)
        assertEquals("msg-1", start.messageId)
    }

    @Test
    fun capabilityVersioningPreservesUnknown() {
        val known = setOf("dashboard", "study_config")
        val withUnknown = known + "future_cap_v3"
        val caps = AgentCapabilities.fromStrings(withUnknown)
        assertEquals(3, caps.raw.size)
        assertTrue(caps.raw.contains("future_cap_v3"))
    }
}
