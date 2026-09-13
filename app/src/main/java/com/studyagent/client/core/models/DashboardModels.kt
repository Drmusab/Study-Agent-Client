package com.studyagent.client.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire + domain models for the Protocol v2 dashboard surface.
 *
 * All values in these models are produced by the PC Study Agent (Anki/FSRS/LLM
 * side). The Android client never invents or estimates study metrics; fields
 * are nullable/zero-defaulted so older or partial server payloads degrade
 * gracefully instead of crashing deserialization.
 */

/** Explicit health state of a single PC-agent-managed component. */
@Serializable
enum class ComponentStatus(val wireValue: String, val displayName: String) {
    @SerialName("ready")
    READY("ready", "Ready"),

    @SerialName("connecting")
    CONNECTING("connecting", "Connecting"),

    @SerialName("warning")
    WARNING("warning", "Warning"),

    @SerialName("unavailable")
    UNAVAILABLE("unavailable", "Unavailable"),

    @SerialName("error")
    ERROR("error", "Error"),

    @SerialName("unknown")
    UNKNOWN("unknown", "Unknown")
}

/**
 * Health of one server-side component (Anki, LLM, ...).
 *
 * [name] identifies the component on the wire ("anki", "llm", "audio", ...).
 * Older servers may omit it; such entries cannot be attributed and are kept
 * in [ComponentHealth.extra] instead of being silently mapped.
 */
@Serializable
data class ComponentHealthEntry(
    val name: String? = null,
    val status: ComponentStatus = ComponentStatus.UNKNOWN,
    val message: String? = null,
    @SerialName("latency_ms")
    val latencyMs: Long? = null
)

/**
 * Component health as explicitly reported by the PC agent.
 * The client must never infer Anki/LLM status from the WebSocket state alone.
 */
@Serializable
data class ComponentHealth(
    val anki: ComponentHealthEntry? = null,
    val llm: ComponentHealthEntry? = null,
    val audio: ComponentHealthEntry? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
) {
    /** All attributed components, for rendering arbitrary server-reported parts. */
    val all: List<ComponentHealthEntry>
        get() = listOfNotNull(anki, llm, audio)

    fun mergedWith(newer: ComponentHealth?): ComponentHealth {
        if (newer == null) return this
        return ComponentHealth(
            anki = newer.anki ?: anki,
            llm = newer.llm ?: llm,
            audio = newer.audio ?: audio,
            updatedAt = newer.updatedAt ?: updatedAt
        )
    }

    companion object {
        private fun ComponentHealthEntry.namedAs(candidate: String): Boolean =
            name?.trim()?.equals(candidate, ignoreCase = true) == true

        /**
         * Maps a nameless-legacy-safe list of entries into attributed health.
         * Entries whose names are not recognized are preserved without attribution.
         */
        fun fromEntries(entries: List<ComponentHealthEntry>, updatedAt: String? = null): ComponentHealth {
            val known = entries.filter { it.name != null }
            return ComponentHealth(
                anki = known.firstOrNull { it.namedAs("anki") },
                llm = known.firstOrNull { it.namedAs("llm") || it.namedAs("ai") || it.namedAs("evaluator") },
                audio = known.firstOrNull { it.namedAs("audio") },
                updatedAt = updatedAt
            )
        }
    }
}

/** A deck as reported by the PC agent (Anki is the source of truth). */
@Serializable
data class DeckSummary(
    val name: String,
    @SerialName("due_count")
    val dueCount: Int = 0,
    @SerialName("new_count")
    val newCount: Int = 0,
    @SerialName("learning_count")
    val learningCount: Int = 0,
    @SerialName("total_count")
    val totalCount: Int = 0,
    @SerialName("is_favorite")
    val isFavorite: Boolean = false
)

/** Compact per-day statistics used by weekly history and "yesterday" rows. */
@Serializable
data class DayStats(
    /** ISO-8601 date (yyyy-MM-dd) as reported by the server. */
    val date: String? = null,
    @SerialName("cards_reviewed")
    val cardsReviewed: Int = 0,
    @SerialName("new_cards")
    val newCards: Int = 0,
    @SerialName("recall_rate")
    val recallRate: Double? = null,
    @SerialName("study_time_seconds")
    val studyTimeSeconds: Long = 0
)

/** Aggregate "today" numbers as computed by the PC agent. */
@Serializable
data class TodayStats(
    @SerialName("cards_reviewed")
    val cardsReviewed: Int = 0,
    @SerialName("new_studied")
    val newStudied: Int = 0,
    @SerialName("due_remaining")
    val dueRemaining: Int = 0,
    @SerialName("recall_rate")
    val recallRate: Double? = null,
    @SerialName("study_time_seconds")
    val studyTimeSeconds: Long = 0,
    @SerialName("avg_seconds_per_card")
    val avgSecondsPerCard: Double? = null,
    /** Null when the user/server has not configured a daily goal. Never invent one. */
    @SerialName("daily_goal_cards")
    val dailyGoalCards: Int? = null,
    @SerialName("daily_goal_minutes")
    val dailyGoalMinutes: Int? = null,
    val yesterday: DayStats? = null
)

/** Rating distribution counts produced from real review events. */
@Serializable
data class RatingDistribution(
    val again: Int = 0,
    val hard: Int = 0,
    val good: Int = 0,
    val easy: Int = 0
) {
    val total: Int get() = again + hard + good + easy

    fun percentOf(rating: Rating): Int {
        if (total <= 0) return 0
        val count = when (rating) {
            Rating.AGAIN -> again
            Rating.HARD -> hard
            Rating.GOOD -> good
            Rating.EASY -> easy
        }
        return ((count * 100.0) / total).toInt()
    }
}

/** Live or finished session summary, server-provided. */
@Serializable
data class SessionSummaryPayload(
    @SerialName("session_id")
    val sessionId: String? = null,
    val deck: String? = null,
    val mode: String? = null,
    @SerialName("cards_reviewed")
    val cardsReviewed: Int = 0,
    @SerialName("total_cards")
    val totalCards: Int? = null,
    @SerialName("remaining_cards")
    val remainingCards: Int? = null,
    @SerialName("recall_rate")
    val recallRate: Double? = null,
    @SerialName("elapsed_seconds")
    val elapsedSeconds: Long = 0,
    @SerialName("avg_seconds_per_card")
    val avgSecondsPerCard: Double? = null,
    @SerialName("is_paused")
    val isPaused: Boolean = false,
    @SerialName("rating_distribution")
    val ratingDistribution: RatingDistribution? = null,
    @SerialName("correct_count")
    val correctCount: Int? = null,
    @SerialName("weak_topics")
    val weakTopics: List<String> = emptyList(),
    @SerialName("ai_note")
    val aiNote: String? = null
)

/** Long-term study goal progress. Target values are user/server configured. */
@Serializable
data class StudyGoalProgress(
    val deck: String? = null,
    @SerialName("target_cards")
    val targetCards: Int? = null,
    /** ISO-8601 date (yyyy-MM-dd) for a target/finish date, if configured. */
    @SerialName("target_date")
    val targetDate: String? = null,
    @SerialName("learned_cards")
    val learnedCards: Int = 0,
    @SerialName("remaining_cards")
    val remainingCards: Int? = null,
    @SerialName("percent_complete")
    val percentComplete: Double? = null,
    @SerialName("required_per_day")
    val requiredPerDay: Double? = null,
    @SerialName("current_per_day")
    val currentPerDay: Double? = null,
    @SerialName("estimated_completion_date")
    val estimatedCompletionDate: String? = null,
    /** One of "ahead", "on_track", "behind" (server-authoritative). */
    @SerialName("pace_status")
    val paceStatus: String? = null,
    @SerialName("daily_new_target")
    val dailyNewTarget: Int? = null,
    @SerialName("daily_review_target")
    val dailyReviewTarget: Int? = null,
    @SerialName("daily_minutes_target")
    val dailyMinutesTarget: Int? = null
)

/** Weekly/recent performance bundle delivered inside a dashboard snapshot. */
@Serializable
data class RecentPerformance(
    val days: List<DayStats> = emptyList(),
    @SerialName("rating_distribution")
    val ratingDistribution: RatingDistribution? = null,
    /** Range the distribution refers to: "today", "7d", "30d". */
    val range: String? = null
)

/** Advisory "what should I study next" recommendation. Never mandatory. */
@Serializable
data class StudyRecommendation(
    @SerialName("recommended_deck")
    val recommendedDeck: String? = null,
    @SerialName("recommended_mode")
    val recommendedMode: String? = null,
    val reason: String? = null,
    @SerialName("estimated_cards")
    val estimatedCards: Int? = null,
    @SerialName("estimated_minutes")
    val estimatedMinutes: Int? = null
)

/** Server-generated learning insight. Cached and rendered with freshness. */
@Serializable
data class LearningInsight(
    val deck: String? = null,
    @SerialName("weak_topic")
    val weakTopic: String? = null,
    @SerialName("weak_subtopic")
    val weakSubtopic: String? = null,
    @SerialName("recall_rate")
    val recallRate: Double? = null,
    @SerialName("missed_points")
    val missedPoints: List<String> = emptyList(),
    val advice: String? = null,
    @SerialName("generated_at")
    val generatedAt: String? = null
)

/** Optional AI usage/cost info. Only displayed when the server provides it. */
@Serializable
data class AiUsageSummary(
    /** Range the numbers refer to: "today", "month", "all_time". */
    val range: String? = null,
    val evaluations: Int = 0,
    @SerialName("input_tokens")
    val inputTokens: Long = 0,
    @SerialName("output_tokens")
    val outputTokens: Long = 0,
    /** Server-authoritative cost estimate. The client never computes billing. */
    @SerialName("estimated_cost")
    val estimatedCost: Double? = null,
    val currency: String? = null
)

/**
 * Consolidated dashboard payload. Prefer one snapshot over many chatty
 * messages; individual panels may also be refreshed by dedicated responses.
 */
@Serializable
data class DashboardSnapshotPayload(
    @SerialName("generated_at")
    val generatedAt: String? = null,
    @SerialName("active_deck")
    val activeDeck: DeckSummary? = null,
    val today: TodayStats = TodayStats(),
    @SerialName("current_session")
    val currentSession: SessionSummaryPayload? = null,
    val goal: StudyGoalProgress? = null,
    @SerialName("recent_performance")
    val recentPerformance: RecentPerformance? = null,
    val recommendation: StudyRecommendation? = null,
    val insight: LearningInsight? = null,
    @SerialName("ai_usage")
    val aiUsage: AiUsageSummary? = null,
    @SerialName("component_health")
    val componentHealth: ComponentHealth? = null
) {
    /**
     * Panel-scoped merge (§92): a dedicated response (e.g. history) replaces only
     * its own section and never wipes unrelated snapshot data. Null incoming
     * sections keep the current value unless [replaceNulls] is set.
     */
    fun mergedWith(newer: DashboardSnapshotPayload, replaceNulls: Boolean = false): DashboardSnapshotPayload =
        DashboardSnapshotPayload(
            generatedAt = newer.generatedAt ?: if (replaceNulls) null else generatedAt,
            activeDeck = newer.activeDeck ?: if (replaceNulls) null else activeDeck,
            today = newer.today,
            currentSession = newer.currentSession ?: if (replaceNulls) null else currentSession,
            goal = newer.goal ?: if (replaceNulls) null else goal,
            recentPerformance = newer.recentPerformance ?: if (replaceNulls) null else recentPerformance,
            recommendation = newer.recommendation ?: if (replaceNulls) null else recommendation,
            insight = newer.insight ?: if (replaceNulls) null else insight,
            aiUsage = newer.aiUsage ?: if (replaceNulls) null else aiUsage,
            componentHealth = componentHealth.mergedWith(newer.componentHealth)
        )
}

/** Range selector shared by history / rating distribution requests. */
enum class StatsRange(val wireValue: String, val displayName: String) {
    TODAY("today", "Today"),
    SEVEN_DAYS("7d", "7 days"),
    THIRTY_DAYS("30d", "30 days");

    companion object {
        fun fromWire(value: String?): StatsRange =
            entries.firstOrNull { it.wireValue == value } ?: TODAY
    }
}

/** Range selector for AI usage requests. */
enum class AiUsageRange(val wireValue: String, val displayName: String) {
    TODAY("today", "Today"),
    MONTH("month", "This month"),
    ALL_TIME("all_time", "All time");

    companion object {
        fun fromWire(value: String?): AiUsageRange =
            entries.firstOrNull { it.wireValue == value } ?: MONTH
    }
}

/** History response payload (weekly chart + rating distribution per range). */
@Serializable
data class StudyHistoryPayload(
    val range: String? = null,
    val days: List<DayStats> = emptyList(),
    @SerialName("rating_distribution")
    val ratingDistribution: RatingDistribution? = null
)

/**
 * Data freshness semantics for dashboard panels. The UI must never present
 * stale statistics as live, and must label cached data when disconnected.
 */
sealed interface DataFreshness {
    data object Loading : DataFreshness
    data object Unavailable : DataFreshness
    data class Live(override val updatedAtEpochMs: Long) : DataFreshness
    data class Cached(override val updatedAtEpochMs: Long) : DataFreshness
    data class Stale(override val updatedAtEpochMs: Long) : DataFreshness

    val updatedAtEpochMs: Long?
        get() = when (this) {
            is Live -> updatedAtEpochMs
            is Cached -> updatedAtEpochMs
            is Stale -> updatedAtEpochMs
            else -> null
        }
}