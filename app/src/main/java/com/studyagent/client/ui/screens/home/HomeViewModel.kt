package com.studyagent.client.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.audio.EffectiveStudyAudioRoute
import com.studyagent.client.core.audio.StudyAudioRouteCoordinator
import com.studyagent.client.core.models.AiUsageRange
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.core.models.StatsRange
import com.studyagent.client.core.models.StudyMode
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.data.repository.CapabilityStore
import com.studyagent.client.data.repository.ConnectionRepository
import com.studyagent.client.data.repository.DashboardRepository
import com.studyagent.client.data.repository.StudyControlRepository
import com.studyagent.client.data.repository.StudySessionRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Dashboard ViewModel. All data arrives from the repository layer
 * (server -> protocol -> repository -> state -> Compose, §6); the ViewModel
 * only combines, maps and forwards user intents. No requests are triggered by
 * Compose recomposition — lifecycle events and explicit user actions only (§110).
 */
class HomeViewModel(
    private val connectionRepository: ConnectionRepository,
    private val capabilityStore: CapabilityStore,
    private val dashboardRepository: DashboardRepository,
    private val studyControlRepository: StudyControlRepository,
    private val studySessionRepository: StudySessionRepository,
    studyAudioRouteCoordinator: StudyAudioRouteCoordinator? = null
) : ViewModel() {

    val connectionState: StateFlow<com.studyagent.client.core.models.ConnectionState> =
        connectionRepository.connectionState

    val activeProfile: StateFlow<ServerProfile?> = connectionRepository.activeProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val studyState: StateFlow<StudyState> = studySessionRepository.studyState

    private val audioRouteFlow: Flow<EffectiveStudyAudioRoute?> =
        studyAudioRouteCoordinator?.effectiveRoute ?: flowOf(null)

    /** Slow wall-clock tick so "updated 12 min ago" labels age without polling the server. */
    private val nowTicker: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(REFRESH_LABEL_TICK_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())

    /** Server control config drives Smart Start; the draft wins while edits are unsaved. */
    private val effectiveConfigFlow = combine(
        studyControlRepository.draft,
        studyControlRepository.serverConfig,
        studyControlRepository.localConfig
    ) { draft, server, local -> draft ?: server ?: local }

    private val dashboardSnapshotFlow = combine(
        connectionRepository.connectionState,
        capabilityStore.capabilities,
        dashboardRepository.data,
        studySessionRepository.studyState
    ) { connection, capabilities, dashboard, study ->
        DashboardBundle(connection, capabilities, dashboard, study)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        dashboardSnapshotFlow,
        effectiveConfigFlow,
        audioRouteFlow,
        nowTicker
    ) { bundle, config, audioRoute, now ->
        DashboardUiMapper.build(
            connectionState = bundle.connection,
            capabilities = bundle.capabilities,
            dashboard = bundle.dashboard,
            studyState = bundle.study,
            effectiveConfig = config,
            startRequest = studyControlRepository.currentStartRequest(),
            audioRoute = audioRoute,
            nowMs = now
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(nowMs = System.currentTimeMillis())
    )

    private data class DashboardBundle(
        val connection: com.studyagent.client.core.models.ConnectionState,
        val capabilities: com.studyagent.client.core.models.AgentCapabilities,
        val dashboard: com.studyagent.client.data.repository.DashboardData,
        val study: StudyState
    )

    // ------------------------------------------------------------------ lifecycle

    /**
     * Called when the dashboard becomes visible. Refreshes only when data is
     * missing or stale — recomposition and quick tab switches never spam the
     * server (§17/§110/§111).
     */
    fun onScreenActive() {
        dashboardRepository.refreshIfStale(STALE_REFRESH_AFTER_MS, reason = "screen-active")
    }

    fun refresh() {
        dashboardRepository.refresh(reason = "manual")
        dashboardRepository.refreshDecks()
    }

    fun clearError() = dashboardRepository.clearError()

    // ------------------------------------------------------------------ connection

    fun connect() {
        viewModelScope.launch { connectionRepository.connect() }
    }

    // ------------------------------------------------------------------ smart start (§23/§24)

    /**
     * Starts a session using the Control Center's current configuration.
     * The session state machine rejects duplicate starts, and this guard keeps
     * rapid double taps from even sending a second request (§24).
     */
    fun startStudy(): Boolean {
        val state = studySessionRepository.studyState.value
        if (state is StudyState.Loading || isSessionActive(state)) return false
        if (!connectionRepository.connectionState.value.isConnected) return false
        val request = studyControlRepository.currentStartRequest()
        viewModelScope.launch {
            studySessionRepository.startStudy(
                deckName = request.deck,
                mode = request.mode,
                config = request.config
            )
        }
        return true
    }

    private fun isSessionActive(state: StudyState): Boolean = when (state) {
        is StudyState.SpeakingQuestion,
        is StudyState.Listening,
        is StudyState.Evaluating,
        is StudyState.ShowingFeedback,
        is StudyState.WaitingForRating,
        is StudyState.HintShowing,
        is StudyState.ExplanationShowing,
        is StudyState.Paused -> true

        else -> false
    }

    // ------------------------------------------------------------------ mini session controller (§26)

    fun pauseSession() {
        viewModelScope.launch { studySessionRepository.pauseStudy() }
    }

    fun resumeSession() {
        val state = studySessionRepository.studyState.value
        if (state is StudyState.Paused) {
            viewModelScope.launch { studySessionRepository.resumeStudy() }
        }
        // Otherwise "resume" is pure navigation to the existing session (§145):
        // the caller navigates to Study; no StartSession is ever re-sent.
    }

    fun endSession() {
        viewModelScope.launch { studySessionRepository.endStudy() }
    }

    // ------------------------------------------------------------------ finished session (§83-§86)

    private val _finishedSummaryDismissed = MutableStateFlow(false)

    /** Whether the post-session summary card should render. */
    fun shouldShowFinishedSummary(state: StudyState): Boolean =
        state is StudyState.SessionFinished && !_finishedSummaryDismissed.value

    fun dismissFinishedSummary() {
        _finishedSummaryDismissed.value = true
    }

    /** Configure "review mistakes" follow-up (server-side mode, §85). */
    fun prepareReviewMistakes() {
        studyControlRepository.updateDraft { it.copy(studyMode = StudyMode.INCORRECT_CARDS) }
    }

    /** Configure "practice weak cards" follow-up (server-side mode, §86). */
    fun preparePracticeWeakCards() {
        studyControlRepository.updateDraft { it.copy(studyMode = StudyMode.WEAK_CARDS) }
    }

    // ------------------------------------------------------------------ decks & recommendation

    fun selectDeck(deckName: String) {
        studyControlRepository.setActiveDeck(deckName)
    }

    /** Applies the server's advisory recommendation to the Control Center draft (§42). */
    fun useRecommendation() {
        val recommendation = dashboardRepository.data.value.snapshot?.recommendation ?: return
        studyControlRepository.updateDraft { config ->
            config.copy(
                activeDeck = recommendation.recommendedDeck ?: config.activeDeck,
                studyMode = StudyMode.fromWire(recommendation.recommendedMode)
            )
        }
        // Best-effort sync; failures remain visible in the Control Center save state.
        val candidate = studyControlRepository.draft.value ?: return
        viewModelScope.launch { studyControlRepository.saveConfig(candidate) }
    }

    // ------------------------------------------------------------------ panel refreshes

    fun refreshHistory(range: StatsRange) = dashboardRepository.refreshHistory(range)

    fun refreshAiUsage(range: AiUsageRange) = dashboardRepository.refreshAiUsage(range)

    /** Explicit user action only — Home never triggers insight generation on open (§40). */
    fun refreshInsight() = dashboardRepository.refreshInsight()

    fun refreshComponentHealth() = dashboardRepository.refreshComponentHealth()

    private companion object {
        const val REFRESH_LABEL_TICK_MS = 30_000L
        const val STALE_REFRESH_AFTER_MS = 2 * 60_000L
    }
}
