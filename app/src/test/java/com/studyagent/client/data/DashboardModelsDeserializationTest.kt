package com.studyagent.client.data

import com.studyagent.client.core.models.ComponentStatus
import com.studyagent.client.core.models.DashboardSnapshotPayload
import com.studyagent.client.core.network.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §128: dashboard models must survive full, partial, missing and unknown
 * payloads — Android renders whatever the server sent, never more.
 */
class DashboardModelsDeserializationTest {

    @Test
    fun `full snapshot deserializes every panel`() {
        val json = """
        {
          "generated_at": "2026-09-13T07:42:00.000Z",
          "active_deck": {"name": "MCCQE::Cardiology", "due_count": 42, "new_count": 8, "learning_count": 5, "total_count": 620, "is_favorite": true},
          "today": {"cards_reviewed": 427, "new_studied": 32, "due_remaining": 47, "recall_rate": 84.0, "study_time_seconds": 6120, "avg_seconds_per_card": 14.3, "daily_goal_cards": 500, "daily_goal_minutes": 120},
          "current_session": {"session_id": "s1", "deck": "MCCQE::Cardiology", "cards_reviewed": 37, "total_cards": 183, "recall_rate": 86.0, "elapsed_seconds": 1260, "is_paused": false},
          "goal": {"deck": "MCCQE::Cardiology", "target_cards": 2000, "learned_cards": 1240, "percent_complete": 62.0, "pace_status": "ahead"},
          "recent_performance": {"days": [{"date": "2026-09-12", "cards_reviewed": 154, "study_time_seconds": 2100}], "rating_distribution": {"again": 3, "hard": 9, "good": 31, "easy": 11}, "range": "7d"},
          "recommendation": {"recommended_deck": "MCCQE::Cardiology", "recommended_mode": "weak_cards", "estimated_cards": 30, "estimated_minutes": 20, "reason": "Recall fell."},
          "insight": {"weak_topic": "Cardiology", "weak_subtopic": "Arrhythmias", "recall_rate": 62.0, "missed_points": ["cardioversion"], "advice": "Review 15 minutes."},
          "ai_usage": {"range": "today", "evaluations": 427, "input_tokens": 380000, "output_tokens": 96000, "estimated_cost": 1.84, "currency": "${'$'}"},
          "component_health": {"anki": {"name": "anki", "status": "ready", "latency_ms": 12}, "llm": {"name": "llm", "status": "ready"}}
        }
        """.trimIndent()

        val snapshot = ProtocolJson.json.decodeFromString(DashboardSnapshotPayload.serializer(), json)

        assertEquals("MCCQE::Cardiology", snapshot.activeDeck?.name)
        assertEquals(42, snapshot.activeDeck?.dueCount)
        assertTrue(snapshot.activeDeck?.isFavorite == true)
        assertEquals(427, snapshot.today.cardsReviewed)
        assertEquals(500, snapshot.today.dailyGoalCards)
        assertEquals(37, snapshot.currentSession?.cardsReviewed)
        assertEquals("ahead", snapshot.goal?.paceStatus)
        assertEquals(154, snapshot.recentPerformance?.days?.first()?.cardsReviewed)
        assertEquals(54, snapshot.recentPerformance?.ratingDistribution?.total)
        assertEquals("weak_cards", snapshot.recommendation?.recommendedMode)
        assertEquals("Arrhythmias", snapshot.insight?.weakSubtopic)
        assertEquals(1.84, snapshot.aiUsage?.estimatedCost ?: 0.0, 0.001)
        assertEquals(ComponentStatus.READY, snapshot.componentHealth?.anki?.status)
    }

    @Test
    fun `partial snapshot keeps missing panels null`() {
        val json = """
        {
          "today": {"cards_reviewed": 12},
          "active_deck": {"name": "Pharmacology"}
        }
        """.trimIndent()

        val snapshot = ProtocolJson.json.decodeFromString(DashboardSnapshotPayload.serializer(), json)

        assertEquals(12, snapshot.today.cardsReviewed)
        assertEquals("Pharmacology", snapshot.activeDeck?.name)
        assertNull(snapshot.currentSession)
        assertNull(snapshot.goal)
        assertNull(snapshot.recentPerformance)
        assertNull(snapshot.recommendation)
        assertNull(snapshot.insight)
        assertNull(snapshot.aiUsage)
        assertNull(snapshot.componentHealth)
        // Defaults are zero, never invented values:
        assertEquals(0, snapshot.today.dueRemaining)
        assertNull(snapshot.today.recallRate)
        assertNull(snapshot.today.dailyGoalCards)
    }

    @Test
    fun `empty object deserializes to safe defaults`() {
        val snapshot = ProtocolJson.json.decodeFromString(DashboardSnapshotPayload.serializer(), "{}")
        assertNotNull(snapshot.today)
        assertNull(snapshot.generatedAt)
        assertNull(snapshot.activeDeck)
        assertEquals(0, snapshot.today.cardsReviewed)
    }

    @Test
    fun `unknown fields are ignored`() {
        val json = """
        {
          "today": {"cards_reviewed": 3, "some_future_field": {"x": 1}},
          "future_panel": [1, 2, 3]
        }
        """.trimIndent()
        val snapshot = ProtocolJson.json.decodeFromString(DashboardSnapshotPayload.serializer(), json)
        assertEquals(3, snapshot.today.cardsReviewed)
    }

    @Test
    fun `component health entries map by name and tolerate missing names`() {
        val json = """
        {"components": [
            {"name": "anki", "status": "ready"},
            {"name": "llm", "status": "warning", "message": "quota low"},
            {"status": "ready"}
        ]}
        """.trimIndent()
        val response = ProtocolJson.json.decodeFromString(
            com.studyagent.client.core.models.ServerMessage.ComponentHealthResponse.serializer(),
            json
        )
        val health = com.studyagent.client.core.models.ComponentHealth.fromEntries(response.components)
        assertEquals(ComponentStatus.READY, health.anki?.status)
        assertEquals(ComponentStatus.WARNING, health.llm?.status)
        assertEquals("quota low", health.llm?.message)
    }

    @Test
    fun `panel merge never wipes unrelated sections`() {
        val base = DashboardSnapshotPayload(
            activeDeck = com.studyagent.client.core.models.DeckSummary(name = "A", dueCount = 5),
            goal = com.studyagent.client.core.models.StudyGoalProgress(targetCards = 100)
        )
        val newer = DashboardSnapshotPayload(
            recentPerformance = com.studyagent.client.core.models.RecentPerformance(
                days = listOf(com.studyagent.client.core.models.DayStats(cardsReviewed = 10))
            )
        )
        val merged = base.mergedWith(newer)
        assertEquals("A", merged.activeDeck?.name)
        assertEquals(100, merged.goal?.targetCards)
        assertEquals(10, merged.recentPerformance?.days?.first()?.cardsReviewed)
    }
}
