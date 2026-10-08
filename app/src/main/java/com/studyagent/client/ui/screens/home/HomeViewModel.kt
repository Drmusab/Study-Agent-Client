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
import com.studyagent.client.data.repository.AnkiLocalStudyStarter
import com.studyagent.client.data.repository.ConnectionRepository
import com.studyagent.client.data.repository.DashboardRepository
import com.studyagent.client.data.repository.StudyControlRepository
import com.studyagent.client.data.repository.StudySessionRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
    studyAudioRouteCoordinator: StudyAudioRouteCoordinator? = null,
    /**
     * GATE 13 STEP 32.2 — the app's local-Anki session-start site. Optional so a headless ViewModel
     * (tests, or a build without the AnkiDroid backend) simply has no local start to offer; the
     * dashboard then keeps the existing PC-agent behaviour.
     */
    private val ankiLocalStudyStarter: AnkiLocalStudyStarter? = null
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

    /**
     * GATE 13 — whether this phone can start a review on its own Anki collection right now.
     *
     * The starter owns the resolution (one ready on-device backend), so the dashboard never inspects
     * AnkiDroid health, provider specs or permissions itself.
     */
    private val localAnkiReadyFlow: Flow<Boolean> =
        ankiLocalStudyStarter?.localStudyReady ?: flowOf(false)

    /** The local start's outcome, for the one-shot navigation decision in the screen. */
    private val _localStart = MutableSharedFlow<AnkiLocalStudyStarter.Result>(extraBufferCapacity = 1)

    /**
     * A local start no longer navigates from [startStudy]'s return value: the deck is validated
     * against the live collection first, so the screen navigates only once a session really exists.
     */
    val localStart: SharedFlow<AnkiLocalStudyStarter.Result> = _localStart.asSharedFlow()

    /**
     * A refusal is not an error state of the dashboard: it is one honest sentence about why nothing
     * started, shown until dismissed. Tokens are mapped to copy here so the screen renders a string.
     */
    private val _localStartNotice = MutableStateFlow<String?>(null)
    val localStartNotice: StateFlow<String?> = _localStartNotice.asStateFlow()

    fun dismissLocalStartNotice() {
        _localStartNotice.value = null
    }

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
        nowTicker,
        localAnkiReadyFlow
    ) { bundle, config, audioRoute, now, localAnkiReady ->
        DashboardUiMapper.build(
            connectionState = bundle.connection,
            capabilities = bundle.capabilities,
            dashboard = bundle.dashboard,
            studyState = bundle.study,
            effectiveConfig = config,
            startRequest = studyControlRepository.currentStartRequest(),
            audioRoute = audioRoute,
            nowMs = now,
            localAnkiReady = localAnkiReady
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
        if (!connectionRepository.connectionState.value.isConnected) {
            // GATE 13 STEP 32.2 — no PC agent: the phone's own Anki collection is a first-class session
            // source. `false` here means "nothing to navigate to yet"; the screen navigates when the
            // start really happened ([localStart]).
            startLocalAnkiStudy()
            return false
        }
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

    /**
     * GATE 13 STEP 32.2 — start a review against this phone's AnkiDroid collection.
     *
     * The starter resolves the backend, validates the deck against the live collection, freezes the
     * session's reviewer-action capabilities and only then dispatches. A refusal dispatches nothing;
     * the flow is emitted either way so the screen can say what happened instead of navigating into
     * an empty session.
     */
    fun startLocalAnkiStudy() {
        val starter = ankiLocalStudyStarter ?: return
        val deckName = studyControlRepository.currentStartRequest().deck
        viewModelScope.launch {
            val result = starter.start(AnkiLocalStudyStarter.Options(deckName = deckName))
            _localStartNotice.value = (result as? AnkiLocalStudyStarter.Result.Refused)?.let {
                localStartRefusalMessage(it.reason)
            }
            _localStart.emit(result)
        }
    }

    /** Plain language for a content-free refusal token (kept out of the starter, which has no UI). */
    private fun localStartRefusalMessage(reason: String): String = when (reason) {
        AnkiLocalStudyStarter.REASON_DECK_NOT_FOUND ->
            "That deck is not in this phone's Anki collection. Pick a deck AnkiDroid has."
        AnkiLocalStudyStarter.REASON_NO_READY_BACKEND, AnkiLocalStudyStarter.REASON_BACKEND_NOT_READY ->
            "AnkiDroid is not ready to review on this phone yet. Open AnkiDroid once and check its permissions."
        AnkiLocalStudyStarter.REASON_AMBIGUOUS_BACKEND ->
            "More than one Anki backend could serve this session, so nothing was started."
        else -> "The session could not be started on this phone."
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
