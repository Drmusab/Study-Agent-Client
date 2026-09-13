package com.studyagent.client.ui.screens.home

import com.studyagent.client.core.audio.EffectiveStudyAudioRoute
import com.studyagent.client.core.models.AgentCapabilities
import com.studyagent.client.core.models.ComponentHealth
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.DashboardSnapshotPayload
import com.studyagent.client.core.models.DataFreshness
import com.studyagent.client.core.models.DeckSummary
import com.studyagent.client.core.models.SessionSummaryPayload
import com.studyagent.client.core.models.StudyControlConfig
import com.studyagent.client.core.models.StudyMode
import com.studyagent.client.core.models.StudyPreset
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.core.common.TimeFormatting
import com.studyagent.client.data.repository.DashboardData
import com.studyagent.client.data.repository.DashboardError
import com.studyagent.client.data.repository.StartStudyRequest
import com.studyagent.client.data.repository.isProtocolV2

/** The single dominant dashboard button (§23). */
enum class PrimaryAction {
    CONNECT,
    CONNECTING,
    START,
    STARTING,
    RESUME_STUDY,
    RESUME_SESSION
}

/** Coarse study lifecycle derived from the authoritative session state machine. */
enum class SessionPhaseSummary { IDLE, STARTING, ACTIVE, PAUSED, FINISHED }

/**
 * One coherent dashboard state (§13). Everything is server-sourced or
 * explicitly labelled cached — the UI never invents metrics (§6).
 */
data class DashboardUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val capabilities: AgentCapabilities = AgentCapabilities(),
    val freshness: DataFreshness = DataFreshness.Unavailable,
    val decksFreshness: DataFreshness = DataFreshness.Unavailable,
    val snapshot: DashboardSnapshotPayload? = null,
    val snapshotFromCache: Boolean = false,
    val decks: List<DeckSummary> = emptyList(),
    val componentHealth: ComponentHealth? = null,
    val activeSession: SessionSummaryPayload? = null,
    val sessionPhase: SessionPhaseSummary = SessionPhaseSummary.IDLE,
    val finishedSession: StudyState.SessionFinished? = null,
    val refreshing: Boolean = false,
    val error: DashboardError? = null,
    val primaryAction: PrimaryAction = PrimaryAction.CONNECT,
    /** What Smart Start will send (§75). */
    val startRequest: StartStudyRequest = StartStudyRequest(null, StudyMode.DUE_REVIEWS.wireValue, null),
    /** Display label for the current Control Center configuration (§77/§121). */
    val startSummary: StartSummary = StartSummary(),
    val studyConfig: StudyControlConfig = StudyControlConfig(),
    val audioRoute: EffectiveStudyAudioRoute? = null,
    val nowMs: Long = 0L,
    /** Dedicated history payload; falls back to snapshot.recentPerformance in the UI. */
    val history: com.studyagent.client.core.models.StudyHistoryPayload? = null,
    val historyRange: com.studyagent.client.core.models.StatsRange = com.studyagent.client.core.models.StatsRange.SEVEN_DAYS,
    val aiUsage: com.studyagent.client.core.models.AiUsageSummary? = null
) {
    val isConnected: Boolean get() = connectionState.isConnected
    val isLegacyV1: Boolean get() = capabilities.isLegacyV1
    val isV2: Boolean get() = capabilities.isProtocolV2

    /** Deck currently selected in the Control Center (may be missing server-side, §118). */
    val selectedDeckName: String? get() = studyConfig.activeDeck

    val selectedDeck: DeckSummary?
        get() = selectedDeckName?.let { name -> decks.firstOrNull { it.name == name } }

    val selectedDeckUnavailable: Boolean
        get() = decks.isNotEmpty() && selectedDeckName != null && selectedDeck == null
}

/** Human-readable summary of the current start configuration (§77/§121). */
data class StartSummary(
    val presetName: String = StudyPreset.CUSTOM.displayName,
    val modeLabel: String = StudyMode.DUE_REVIEWS.displayName,
    val targetLabel: String? = null,
    val deckLabel: String? = null
) {
    val subtitle: String
        get() = listOfNotNull(deckLabel, targetLabel, modeLabel).joinToString(" • ")
}

/**
 * Pure mapping from repository/session data to UI state. Lives outside the
 * ViewModel so every dashboard state (loading/live/cached/stale/disconnected/
 * empty/error/active/finished) is unit-testable without Android (§143).
 */
object DashboardUiMapper {

    fun sessionPhase(studyState: StudyState): SessionPhaseSummary = when (studyState) {
        is StudyState.Loading -> SessionPhaseSummary.STARTING
        is StudyState.Paused -> SessionPhaseSummary.PAUSED
        is StudyState.SessionFinished -> SessionPhaseSummary.FINISHED
        is StudyState.Idle, is StudyState.Error -> SessionPhaseSummary.IDLE
        else -> SessionPhaseSummary.ACTIVE
    }

    fun primaryAction(
        connectionState: ConnectionState,
        sessionPhase: SessionPhaseSummary,
        hasServerActiveSession: Boolean,
        serverSessionPaused: Boolean
    ): PrimaryAction = when {
        connectionState is ConnectionState.Connecting || connectionState is ConnectionState.Reconnecting ->
            PrimaryAction.CONNECTING

        !connectionState.isConnected -> PrimaryAction.CONNECT

        sessionPhase == SessionPhaseSummary.STARTING -> PrimaryAction.STARTING
        sessionPhase == SessionPhaseSummary.PAUSED -> PrimaryAction.RESUME_SESSION
        sessionPhase == SessionPhaseSummary.ACTIVE -> PrimaryAction.RESUME_STUDY
        sessionPhase == SessionPhaseSummary.FINISHED -> PrimaryAction.START
        hasServerActiveSession && serverSessionPaused -> PrimaryAction.RESUME_SESSION
        hasServerActiveSession -> PrimaryAction.RESUME_STUDY
        else -> PrimaryAction.START
    }

    fun startSummary(config: StudyControlConfig): StartSummary {
        val preset = StudyPreset.matching(config)
        val targetLabel = when (config.sessionTargetType) {
            com.studyagent.client.core.models.SessionTargetType.CARDS -> "${config.sessionTargetValue} cards"
            com.studyagent.client.core.models.SessionTargetType.MINUTES -> "${config.sessionTargetValue} min"
            com.studyagent.client.core.models.SessionTargetType.FINISH_DUE -> "Finish due"
        }
        return StartSummary(
            presetName = preset.displayName,
            modeLabel = config.studyMode.displayName,
            targetLabel = targetLabel,
            deckLabel = config.activeDeck?.let { deckDisplayName(it) }
        )
    }

    /** Nested-deck-friendly display: "MCCQE::Cardiology" -> "Cardiology" (hierarchy kept in subtitles). */
    fun deckDisplayName(rawName: String): String {
        val segments = rawName.split("::")
        return segments.lastOrNull()?.trim()?.ifEmpty { rawName } ?: rawName
    }

    fun deckHierarchy(rawName: String): String? {
        val segments = rawName.split("::").map { it.trim() }.filter { it.isNotEmpty() }
        return if (segments.size > 1) segments.dropLast(1).joinToString(" :: ") else null
    }

    fun build(
        connectionState: ConnectionState,
        capabilities: AgentCapabilities,
        dashboard: DashboardData,
        studyState: StudyState,
        effectiveConfig: StudyControlConfig,
        startRequest: StartStudyRequest,
        audioRoute: EffectiveStudyAudioRoute?,
        nowMs: Long
    ): DashboardUiState {
        val phase = sessionPhase(studyState)
        val activeSession = when {
            dashboard.activeSession != null -> dashboard.activeSession
            else -> null
        }
        return DashboardUiState(
            connectionState = connectionState,
            capabilities = capabilities,
            freshness = com.studyagent.client.data.repository.FreshnessPolicy.evaluate(
                hasData = dashboard.snapshot != null,
                isConnected = connectionState.isConnected,
                updatedAtEpochMs = dashboard.snapshotUpdatedAtMs,
                nowMs = nowMs,
                isRefreshing = dashboard.isRefreshing
            ),
            decksFreshness = com.studyagent.client.data.repository.FreshnessPolicy.evaluate(
                hasData = dashboard.decks.isNotEmpty(),
                isConnected = connectionState.isConnected,
                updatedAtEpochMs = dashboard.decksUpdatedAtMs,
                nowMs = nowMs
            ),
            snapshot = dashboard.snapshot,
            snapshotFromCache = dashboard.snapshotFromCache,
            decks = dashboard.decks,
            componentHealth = dashboard.componentHealth ?: dashboard.snapshot?.componentHealth,
            activeSession = activeSession,
            sessionPhase = phase,
            finishedSession = studyState as? StudyState.SessionFinished,
            refreshing = dashboard.isRefreshing,
            error = dashboard.error,
            primaryAction = primaryAction(
                connectionState = connectionState,
                sessionPhase = phase,
                hasServerActiveSession = activeSession != null,
                serverSessionPaused = activeSession?.isPaused == true
            ),
            startRequest = startRequest,
            startSummary = startSummary(effectiveConfig),
            studyConfig = effectiveConfig,
            audioRoute = audioRoute,
            nowMs = nowMs,
            history = dashboard.history,
            historyRange = dashboard.historyRange,
            aiUsage = dashboard.aiUsage ?: dashboard.snapshot?.aiUsage
        )
    }
}

/** Freshness -> user-visible label (§14). Cached data is never presented as live. */
fun freshnessLabel(freshness: DataFreshness, nowMs: Long): String = when (freshness) {
    is DataFreshness.Loading -> "Loading…"
    is DataFreshness.Unavailable -> "No data"
    is DataFreshness.Live -> "Live • ${TimeFormatting.formatRelativeTime(freshness.updatedAtEpochMs, nowMs)}"
    is DataFreshness.Cached -> "Cached • ${TimeFormatting.formatRelativeTime(freshness.updatedAtEpochMs, nowMs)}"
    is DataFreshness.Stale -> "Stale • updated ${TimeFormatting.formatRelativeTime(freshness.updatedAtEpochMs, nowMs)}"
}
