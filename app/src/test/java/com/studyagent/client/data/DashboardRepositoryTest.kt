package com.studyagent.client.data

import com.studyagent.client.core.models.AgentCapability
import com.studyagent.client.core.models.DashboardSnapshotPayload
import com.studyagent.client.core.models.DayStats
import com.studyagent.client.core.models.DeckSummary
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.SessionSummaryPayload
import com.studyagent.client.core.models.StudyHistoryPayload
import com.studyagent.client.core.models.TodayStats
import com.studyagent.client.data.repository.CapabilityStore
import com.studyagent.client.data.repository.DashboardError
import com.studyagent.client.data.repository.DefaultDashboardRepository
import com.studyagent.client.data.repository.FreshnessPolicy
import com.studyagent.client.data.repository.InMemoryManagementCacheStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * §8/§15-§18/§110-§113: the dashboard data layer — capability awareness,
 * coalesced requests, bounded timeouts, out-of-order protection, caching and
 * live session pushes.
 */
class DashboardRepositoryTest {

    private class Harness {
        val connection = FakeConnectionRepository()
        val storage = InMemoryManagementCacheStorage()
        lateinit var scope: CoroutineScope
        lateinit var capabilities: CapabilityStore
        lateinit var dashboard: DefaultDashboardRepository

        fun start(scheduler: kotlinx.coroutines.test.TestScheduler) {
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
            capabilities = CapabilityStore(connection, scope, negotiationTimeoutMs = 4_000L)
            dashboard = DefaultDashboardRepository(
                connectionRepository = connection,
                capabilityStore = capabilities,
                cacheStorage = storage,
                dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher(scheduler)),
                scope = scope,
                requestTimeoutMs = 8_000L
            )
        }

        suspend fun connectV2(vararg caps: String) {
            connection.setConnected(true)
            connection.emit(
                ServerMessage.Capabilities(
                    capabilities = caps.toList().ifEmpty {
                        listOf(
                            AgentCapability.DASHBOARD,
                            AgentCapability.DECK_LIST,
                            AgentCapability.HISTORY,
                            AgentCapability.COMPONENT_HEALTH,
                            AgentCapability.LEARNING_INSIGHTS,
                            AgentCapability.AI_USAGE
                        )
                    }
                )
            )
        }

        fun lastDashboardRequestId(): String? =
            connection.sentOfType("request_dashboard").lastOrNull()?.messageId
    }

    private fun snapshotPayload(reviewed: Int = 100, generatedAt: String = "2026-09-13T08:00:00.000Z") =
        DashboardSnapshotPayload(
            generatedAt = generatedAt,
            today = TodayStats(cardsReviewed = reviewed),
            activeDeck = DeckSummary(name = "MCCQE::Cardiology", dueCount = 42)
        )

    @Test
    fun `negotiated v2 triggers exactly one automatic dashboard request`() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.start(testScheduler)
        h.connectV2()
        advanceUntilIdle()

        assertEquals(1, h.connection.sentOfType("request_dashboard").size)
        assertTrue(h.dashboard.data.value.isRefreshing)
    }

    @Test
    fun `rapid refresh calls coalesce into one in-flight request`() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.start(testScheduler)
        h.connectV2()
        advanceUntilIdle()
        assertEquals(1, h.connection.sentOfType("request_dashboard").size)

        // Hammer refresh while the request is in flight (§110/§111).
        h.dashboard.refresh("manual-1")
        h.dashboard.refresh("manual-2")
        h.dashboard.refresh("manual-3")
        advanceUntilIdle()
        assertEquals(1, h.connection.sentOfType("request_dashboard").size)

        // Answer the request: one coalesced follow-up may run, never a burst.
        h.connection.emit(
            ServerMessage.DashboardSnapshotResponse(
                messageId = h.lastDashboardRequestId(),
                snapshot = snapshotPayload()
            )
        )
        advanceUntilIdle()
        assertTrue(h.connection.sentOfType("request_dashboard").size <= 2)
        assertNotNull(h.dashboard.data.value.snapshot)
    }

    @Test
    fun `older snapshot cannot overwrite newer data`() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.start(testScheduler)
        h.connectV2()
        advanceUntilIdle()

        h.connection.emit(
            ServerMessage.DashboardSnapshotResponse(
                messageId = h.lastDashboardRequestId(),
                snapshot = snapshotPayload(reviewed = 200, generatedAt = "2026-09-13T09:00:00.000Z")
            )
        )
        advanceUntilIdle()
        assertEquals(200, h.dashboard.data.value.snapshot?.today?.cardsReviewed)

        // §113: an unsolicited stale snapshot must lose to newer data.
        h.connection.emit(
            ServerMessage.DashboardSnapshotResponse(
                messageId = "unrelated-push",
                snapshot = snapshotPayload(reviewed = 5, generatedAt = "2026-09-12T22:00:00.000Z")
            )
        )
        advanceUntilIdle()
        assertEquals(200, h.dashboard.data.value.snapshot?.today?.cardsReviewed)
    }

    @Test
    fun `request timeout ends loading and reports TimedOut`() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.start(testScheduler)
        h.connectV2()
        advanceUntilIdle()
        assertTrue(h.dashboard.data.value.isRefreshing)

        advanceTimeBy(9_000)

        val data = h.dashboard.data.value
        assertEquals(DashboardError.TimedOut, data.error)
        assertTrue(!data.isRefreshing)
    }

    @Test
    fun `applied snapshot is cached and restored by a new repository`() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.start(testScheduler)
        h.connectV2()
        advanceUntilIdle()
        h.connection.emit(
            ServerMessage.DashboardSnapshotResponse(
                messageId = h.lastDashboardRequestId(),
                snapshot = snapshotPayload(reviewed = 123)
            )
        )
        advanceUntilIdle()
        assertNotNull(h.storage.readDashboardCache())

        // A fresh process-start: disconnected, but the cache is readable (§15).
        val connection2 = FakeConnectionRepository()
        val scope2 = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val capabilities2 = CapabilityStore(connection2, scope2, negotiationTimeoutMs = 4_000L)
        val repo2 = DefaultDashboardRepository(
            connectionRepository = connection2,
            capabilityStore = capabilities2,
            cacheStorage = h.storage,
            dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler)),
            scope = scope2,
            requestTimeoutMs = 8_000L
        )
        advanceUntilIdle()

        val restored = repo2.data.value
        assertEquals(123, restored.snapshot?.today?.cardsReviewed)
        assertTrue(restored.snapshotFromCache)
        // Cached data is never Live while disconnected (§14).
        val freshness = FreshnessPolicy.evaluate(
            hasData = restored.snapshot != null,
            isConnected = false,
            updatedAtEpochMs = restored.snapshotUpdatedAtMs,
            nowMs = System.currentTimeMillis()
        )
        assertTrue(freshness is com.studyagent.client.core.models.DataFreshness.Cached)
    }

    @Test
    fun `corrupt dashboard cache is isolated from a valid decks cache`() = runTest(timeout = 30.seconds) {
        val storage = InMemoryManagementCacheStorage()
        storage.saveDashboardCache(
            com.studyagent.client.data.repository.CachedPayload("{not-dashboard", 1L)
        )
        storage.saveDecksCache(
            com.studyagent.client.data.repository.CachedPayload(
                "[{\"name\":\"Recovered deck\"}]",
                2L
            )
        )
        val connection = FakeConnectionRepository()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val caps = CapabilityStore(connection, scope, negotiationTimeoutMs = 4_000L)
        val repo = DefaultDashboardRepository(
            connectionRepository = connection,
            capabilityStore = caps,
            cacheStorage = storage,
            dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler)),
            scope = scope
        )
        advanceUntilIdle()

        assertNull(repo.data.value.snapshot)
        assertEquals("Recovered deck", repo.data.value.decks.single().name)
        assertNull(storage.readDashboardCache())
        assertNotNull(storage.readDecksCache())
    }

    @Test
    fun `session pushes update the active session panel without a full refresh`() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.start(testScheduler)
        h.connectV2()
        advanceUntilIdle()
        val requestsBefore = h.connection.sentOfType("request_dashboard").size

        h.connection.emit(ServerMessage.SessionStarted(sessionId = "s1", deck = "MCCQE::Cardiology", totalCards = 183))
        h.connection.emit(ServerMessage.SessionProgress(sessionId = "s1", currentCardIndex = 37, totalCards = 183))
        advanceUntilIdle()

        val session = h.dashboard.data.value.activeSession
        assertNotNull(session)
        assertEquals(37, session?.cardsReviewed)
        assertEquals(183, session?.totalCards)
        assertEquals("MCCQE::Cardiology", session?.deck)

        // §18: pushes never trigger another full dashboard request.
        assertEquals(requestsBefore, h.connection.sentOfType("request_dashboard").size)

        h.connection.emit(ServerMessage.SessionPaused(sessionId = "s1"))
        advanceUntilIdle()
        assertTrue(h.dashboard.data.value.activeSession?.isPaused == true)

        h.connection.emit(ServerMessage.SessionFinished(sessionId = "s1", totalReviewed = 37))
        advanceUntilIdle()
        assertNull(h.dashboard.data.value.activeSession)
    }

    @Test
    fun `deck list and history responses merge panel-scoped`() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.start(testScheduler)
        h.connectV2()
        advanceUntilIdle()
        h.connection.emit(
            ServerMessage.DashboardSnapshotResponse(
                messageId = h.lastDashboardRequestId(),
                snapshot = snapshotPayload(reviewed = 55)
            )
        )
        advanceUntilIdle()

        h.connection.emit(
            ServerMessage.DeckListResponse(
                decks = listOf(DeckSummary("A", dueCount = 1), DeckSummary("B::C", dueCount = 2))
            )
        )
        h.connection.emit(
            ServerMessage.StudyHistoryResponse(
                history = StudyHistoryPayload(range = "7d", days = listOf(DayStats(date = "2026-09-12", cardsReviewed = 96)))
            )
        )
        advanceUntilIdle()

        val data = h.dashboard.data.value
        assertEquals(2, data.decks.size)
        assertEquals(96, data.history?.days?.first()?.cardsReviewed)
        // §92: unrelated snapshot sections survive the panel updates.
        assertEquals(55, data.snapshot?.today?.cardsReviewed)
        // Decks cache persisted for offline use.
        assertNotNull(h.storage.readDecksCache())
    }

    @Test
    fun `legacy v1 server marks dashboard unsupported`() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.start(testScheduler)
        h.connection.setConnected(true)
        // No capabilities frame: negotiation times out to LEGACY_V1 (§133).
        advanceTimeBy(5_000)
        advanceUntilIdle()

        assertTrue(h.capabilities.capabilities.value.isLegacyV1)
        assertEquals(0, h.connection.sentOfType("request_dashboard").size)

        h.dashboard.refresh("manual")
        advanceUntilIdle()
        assertEquals(DashboardError.NotSupported, h.dashboard.data.value.error)
    }
}
