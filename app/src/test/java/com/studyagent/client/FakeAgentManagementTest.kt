package com.studyagent.client

import com.studyagent.client.core.models.AgentCapability
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.core.models.StudyControlConfig
import com.studyagent.client.core.network.FakeAgentConnection
import com.studyagent.client.core.network.FakeCapabilityMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * §124-§127: the Fake Agent demonstrates the entire Dashboard/Control surface —
 * capabilities, dashboard snapshot, decks, health, history, insights, AI usage,
 * and config ACK/rejection — so capability gating is testable end to end.
 */
class FakeAgentManagementTest {

    private class Collector {
        val messages = mutableListOf<ServerMessage>()

        /** Subscribes eagerly (unconfined) so frames emitted during connect are captured. */
        fun attach(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler, connection: FakeAgentConnection) {
            val scope = CoroutineScope(kotlinx.coroutines.test.UnconfinedTestDispatcher(scheduler))
            scope.launch { connection.incomingMessages.collect { messages.add(it) } }
        }

        fun last(predicate: (ServerMessage) -> Boolean): ServerMessage? =
            messages.lastOrNull(predicate)

        fun count(predicate: (ServerMessage) -> Boolean): Int = messages.count(predicate)
    }

    @Test
    fun `full v2 fake agent advertises the complete management surface`() = runTest(timeout = 20.seconds) {
        val fakeAgent = FakeAgentConnection(TestScope(testScheduler), simulateNetworkDelayMs = 0L)
        val collector = Collector()
        collector.attach(testScheduler, fakeAgent)

        fakeAgent.connect(ServerProfile.defaultLocalProfile())
        advanceUntilIdle()

        val caps = collector.last { it is ServerMessage.Capabilities } as? ServerMessage.Capabilities
        assertNotNull("Expected a capabilities frame", caps)
        assertTrue(caps!!.capabilities.contains(AgentCapability.DASHBOARD))
        assertTrue(caps.capabilities.contains(AgentCapability.STUDY_CONFIG))
        assertTrue(caps.capabilities.contains(AgentCapability.AI_USAGE))
        assertTrue(caps.capabilities.contains(AgentCapability.COMPONENT_HEALTH))
        assertNotNull(caps.serverName)
        assertNotNull(caps.serverVersion)
    }

    @Test
    fun `partial v2 mode advertises a reduced capability set`() = runTest(timeout = 20.seconds) {
        val fakeAgent = FakeAgentConnection(TestScope(testScheduler), simulateNetworkDelayMs = 0L)
        fakeAgent.capabilityMode = FakeCapabilityMode.PARTIAL_V2
        val collector = Collector()
        collector.attach(testScheduler, fakeAgent)

        fakeAgent.connect(ServerProfile.defaultLocalProfile())
        advanceUntilIdle()

        val caps = collector.last { it is ServerMessage.Capabilities } as ServerMessage.Capabilities
        assertTrue(caps.capabilities.contains(AgentCapability.DASHBOARD))
        assertTrue(!caps.capabilities.contains(AgentCapability.AI_USAGE))
        assertTrue(!caps.capabilities.contains(AgentCapability.LEARNING_INSIGHTS))
    }

    @Test
    fun `v1-only mode stays silent about capabilities`() = runTest(timeout = 20.seconds) {
        val fakeAgent = FakeAgentConnection(TestScope(testScheduler), simulateNetworkDelayMs = 0L)
        fakeAgent.capabilityMode = FakeCapabilityMode.V1_ONLY
        val collector = Collector()
        collector.attach(testScheduler, fakeAgent)

        fakeAgent.connect(ServerProfile.defaultLocalProfile())
        advanceUntilIdle()

        assertEquals(0, collector.count { it is ServerMessage.Capabilities })
    }

    @Test
    fun `dashboard snapshot carries every demo panel`() = runTest(timeout = 20.seconds) {
        val fakeAgent = FakeAgentConnection(TestScope(testScheduler), simulateNetworkDelayMs = 0L)
        val collector = Collector()
        collector.attach(testScheduler, fakeAgent)
        fakeAgent.connect(ServerProfile.defaultLocalProfile())
        advanceUntilIdle()

        fakeAgent.send(ClientMessage.RequestDashboard())
        advanceUntilIdle()

        val response = collector.last { it is ServerMessage.DashboardSnapshotResponse }
            as? ServerMessage.DashboardSnapshotResponse
        assertNotNull(response)
        val snapshot = response!!.snapshot!!
        assertTrue(snapshot.today.cardsReviewed > 0)
        assertNotNull(snapshot.activeDeck)
        assertNotNull(snapshot.goal)
        assertTrue((snapshot.recentPerformance?.days?.size ?: 0) >= 7)
        assertNotNull(snapshot.recommendation)
        assertNotNull(snapshot.insight)
        assertNotNull(snapshot.aiUsage)
        assertNotNull(snapshot.componentHealth?.anki)
        // Fake data is server-side demo data — Android just renders it (§125).
    }

    @Test
    fun `deck list includes nested decks with counts`() = runTest(timeout = 20.seconds) {
        val fakeAgent = FakeAgentConnection(TestScope(testScheduler), simulateNetworkDelayMs = 0L)
        val collector = Collector()
        collector.attach(testScheduler, fakeAgent)
        fakeAgent.connect(ServerProfile.defaultLocalProfile())
        advanceUntilIdle()

        fakeAgent.send(ClientMessage.RequestDecks())
        advanceUntilIdle()

        val decks = (collector.last { it is ServerMessage.DeckListResponse }
            as? ServerMessage.DeckListResponse)?.decks
        assertNotNull(decks)
        assertTrue(decks!!.size >= 5)
        assertTrue(decks.any { it.name.contains("::") })
        assertTrue(decks.any { it.isFavorite })
        assertTrue(decks.all { it.totalCount >= 0 })
    }

    @Test
    fun `config update is acknowledged with the request message id`() = runTest(timeout = 20.seconds) {
        val fakeAgent = FakeAgentConnection(TestScope(testScheduler), simulateNetworkDelayMs = 0L)
        val collector = Collector()
        collector.attach(testScheduler, fakeAgent)
        fakeAgent.connect(ServerProfile.defaultLocalProfile())
        advanceUntilIdle()

        val request = ClientMessage.UpdateStudyConfig(config = StudyControlConfig(activeDeck = "Pharmacology", newPerDay = 42))
        fakeAgent.send(request)
        advanceUntilIdle()

        val ack = collector.last { it is ServerMessage.StudyConfigUpdated } as? ServerMessage.StudyConfigUpdated
        assertNotNull("Expected a study_config_updated ACK", ack)
        assertEquals(request.messageId, ack!!.messageId)
        assertEquals(42, ack.config?.newPerDay)

        // The ACKed config is now authoritative on the fake server (§116).
        fakeAgent.send(ClientMessage.RequestStudyConfig())
        advanceUntilIdle()
        val current = collector.messages.filterIsInstance<ServerMessage.StudyConfigResponse>().last()
        assertEquals(42, current.config?.newPerDay)
    }

    @Test
    fun `config rejection returns an error frame and keeps old config`() = runTest(timeout = 20.seconds) {
        val fakeAgent = FakeAgentConnection(TestScope(testScheduler), simulateNetworkDelayMs = 0L)
        fakeAgent.simulateConfigRejection = true
        val collector = Collector()
        collector.attach(testScheduler, fakeAgent)
        fakeAgent.connect(ServerProfile.defaultLocalProfile())
        advanceUntilIdle()

        fakeAgent.send(ClientMessage.RequestStudyConfig())
        advanceUntilIdle()
        val before = collector.messages.filterIsInstance<ServerMessage.StudyConfigResponse>().last().config

        val request = ClientMessage.UpdateStudyConfig(config = StudyControlConfig(newPerDay = 99))
        fakeAgent.send(request)
        advanceUntilIdle()

        val error = collector.last { it is ServerMessage.ErrorMessage } as? ServerMessage.ErrorMessage
        assertNotNull("Expected rejection error", error)
        assertEquals(request.messageId, error!!.messageId)
        assertEquals("config_rejected", error.code)
        assertEquals(0, collector.count { it is ServerMessage.StudyConfigUpdated })

        fakeAgent.send(ClientMessage.RequestStudyConfig())
        advanceUntilIdle()
        val after = collector.messages.filterIsInstance<ServerMessage.StudyConfigResponse>().last().config
        assertEquals(before, after)
    }

    @Test
    fun `history insights and usage requests answer with matching ranges`() = runTest(timeout = 20.seconds) {
        val fakeAgent = FakeAgentConnection(TestScope(testScheduler), simulateNetworkDelayMs = 0L)
        val collector = Collector()
        collector.attach(testScheduler, fakeAgent)
        fakeAgent.connect(ServerProfile.defaultLocalProfile())
        advanceUntilIdle()

        fakeAgent.send(ClientMessage.RequestHistory(range = "30d"))
        fakeAgent.send(ClientMessage.RequestLearningInsights())
        fakeAgent.send(ClientMessage.RequestAiUsage(range = "month"))
        advanceUntilIdle()

        val history = collector.last { it is ServerMessage.StudyHistoryResponse } as ServerMessage.StudyHistoryResponse
        assertEquals("30d", history.history?.range)
        assertTrue((history.history?.days?.size ?: 0) > 0)

        val insight = collector.last { it is ServerMessage.LearningInsightResponse } as ServerMessage.LearningInsightResponse
        assertTrue(insight.insights.isNotEmpty())
        assertNotNull(insight.insights.first().weakTopic)

        val usage = collector.last { it is ServerMessage.AiUsageResponse } as ServerMessage.AiUsageResponse
        assertEquals("month", usage.usage?.range)
        assertNotNull(usage.usage?.estimatedCost)
    }
}
