package com.studyagent.client.network

import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.network.RequestCoordinator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RequestCoordinatorTest {

    @Test
    fun correlationViaInReplyTo() = runTest {
        val coordinator = RequestCoordinator()

        val messageId = "msg-123"
        val deferred = coordinator.register(messageId, "request_dashboard")

        // Simulate server reply with in_reply_to
        val response = ServerMessage.DashboardSnapshotResponse(
            messageId = "server-msg-1",
            inReplyTo = messageId,
            snapshot = null
        )
        val correlated = coordinator.onServerMessage(response)

        assertTrue(correlated)
        assertTrue(deferred.isCompleted)
        val result = deferred.await()
        assertTrue(result is ServerMessage.DashboardSnapshotResponse)
    }

    @Test
    fun pendingCountTracking() = runTest {
        val coordinator = RequestCoordinator()

        val d1 = coordinator.register("msg-1", "request_dashboard")
        assertEquals(1, coordinator.pendingCount())

        coordinator.cancel("msg-1")
        assertEquals(0, coordinator.pendingCount())
    }

    @Test
    fun purposeSpecificTimeouts() {
        val coordinator = RequestCoordinator()
        val evalTimeout = coordinator.timeoutFor("submit_answer")
        val deckTimeout = coordinator.timeoutFor("request_decks")
        val dashboardTimeout = coordinator.timeoutFor("request_dashboard")

        assertTrue(evalTimeout > deckTimeout)
        assertTrue(evalTimeout > dashboardTimeout)
        assertEquals(30_000L, evalTimeout)
        assertEquals(5_000L, deckTimeout)
    }

    @Test
    fun disconnectCompletesAllPending() = runTest {
        val coordinator = RequestCoordinator()

        val d1 = coordinator.register("msg-1", "request_dashboard")
        val d2 = coordinator.register("msg-2", "submit_answer")

        coordinator.cancelAll()

        assertEquals(0, coordinator.pendingCount())
        assertTrue(d1.isCancelled)
        assertTrue(d2.isCancelled)
    }

    @Test
    fun boundedMapPreventsLeak() = runTest {
        val coordinator = RequestCoordinator()

        // Register many requests
        repeat(210) { i ->
            coordinator.register("msg-$i", "request_dashboard", timeoutMs = 60_000L)
        }

        // Should be bounded to <= 200
        assertTrue(coordinator.pendingCount() <= 200)

        coordinator.cancelAll()
    }

    @Test
    fun statsTracking() = runTest {
        val coordinator = RequestCoordinator()

        coordinator.register("msg-1", "request_dashboard")
        coordinator.register("msg-2", "request_dashboard")

        val stats = coordinator.stats()
        assertEquals(2, stats.pending)
    }
}
