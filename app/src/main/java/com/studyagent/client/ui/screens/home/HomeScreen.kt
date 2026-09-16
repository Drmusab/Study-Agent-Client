package com.studyagent.client.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyagent.client.core.common.TimeFormatting
import com.studyagent.client.core.models.AgentCapability
import com.studyagent.client.core.models.AiUsageRange
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.DayStats
import com.studyagent.client.core.models.RatingDistribution
import com.studyagent.client.core.models.SessionSummaryPayload
import com.studyagent.client.core.models.DataFreshness
import com.studyagent.client.core.models.StatsRange
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.data.repository.DashboardError
import com.studyagent.client.data.repository.supportsV2
import com.studyagent.client.ui.components.AudioRouteIndicator
import com.studyagent.client.ui.components.ConnectionBadge
import com.studyagent.client.ui.components.SkeletonCard
import com.studyagent.client.ui.screens.home.components.ActiveDeckCard
import com.studyagent.client.ui.screens.home.components.AiUsageCard
import com.studyagent.client.ui.screens.home.components.ActiveSessionCard
import com.studyagent.client.ui.screens.home.components.DeckPickerDialog
import com.studyagent.client.ui.screens.home.components.GoalProgressCard
import com.studyagent.client.ui.screens.home.components.InfoPanel
import com.studyagent.client.ui.screens.home.components.LearningInsightCard
import com.studyagent.client.ui.screens.home.components.RatingDistributionCard
import com.studyagent.client.ui.screens.home.components.RecommendationCard
import com.studyagent.client.ui.screens.home.components.SectionCard
import com.studyagent.client.ui.screens.home.components.SmartStartCard
import com.studyagent.client.ui.screens.home.components.ActiveSessionCard
import com.studyagent.client.ui.screens.home.components.StatItem
import com.studyagent.client.ui.screens.home.components.SystemReadinessCard
import com.studyagent.client.ui.screens.home.components.TodayStatsCard
import com.studyagent.client.ui.screens.home.components.UnsupportedPanel
import com.studyagent.client.ui.screens.home.components.WeeklyBars
import com.studyagent.client.ui.screens.home.components.StatItem
import com.studyagent.client.ui.screens.home.components.dayLabel
import com.studyagent.client.ui.theme.AccentTeal
import com.studyagent.client.ui.theme.DarkBackground
import com.studyagent.client.ui.theme.DarkSurface
import com.studyagent.client.ui.theme.DarkSurfaceElevated
import com.studyagent.client.ui.theme.PrimaryBlue
import com.studyagent.client.ui.theme.StatusAmber
import com.studyagent.client.ui.theme.StatusRed
import com.studyagent.client.ui.theme.TextMuted
import com.studyagent.client.ui.theme.TextPrimary
import com.studyagent.client.ui.theme.TextSecondary
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

/**
 * The Study Agent command center: a live operational dashboard (§19).
 * Every metric shown here originates from the PC Study Agent via Protocol v2 —
 * nothing is invented client-side (§6/§151).
 * Primary Dashboard: status first, then the dominant Smart Start action,
 * then progressively lower-priority information (§13-§15).
 *
 * Order is fixed (§15):
 *   header (title, freshness, actions) → connection/status line → banners
 *   → Smart Start hero → Active Deck → Today → Goal → History →
 *   → Insight → Recommendation → AI usage.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToStudy: () -> Unit,
    onNavigateToConnection: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToControl: () -> Unit,
    modifier: Modifier = Modifier
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val studyState by viewModel.studyState.collectAsState()
    var showDeckPicker by remember { mutableStateOf(false) }
    val studyState by viewModel.studyState.collectAsStateWithLifecycle()

    // Lifecycle-driven request: runs when the screen enters composition, never on
    // recomposition. The repository coalesces and de-dupes from here (§110/§111).
    androidx.compose.runtime.LaunchedEffect(Unit) {
    // Refresh when the dashboard becomes visible — but only if data is missing
    // or stale (§17); recomposition and quick tab switches never spam the server.
    LaunchedEffect(Unit) {
        viewModel.onScreenActive()
    }

    Scaffold(containerColor = DarkBackground) { innerPadding ->
        LazyColumn(
            modifier = modifier
    var showDeckPicker by remember { mutableStateOf(false) }

    // History filter (§43): range is persisted by the repository, metric is UI state.
    val historyRange = state.historyRange
    var selectedMetric by remember { mutableStateOf(HistoryMetric.REVIEWED) }

    // DashboardUiState already exposes the derived primary action. The dashboard
    // never starts a session with the wrong lifecycle (§24): startStudy() returns
    // false when a session is active or starting, which cancels the navigation.
    val onSmartStart: () -> Unit = {
        if (viewModel.startStudy()) {
            onNavigateToStudy()
        }
    }
    val onConnect: () -> Unit = {
        viewModel.connect()
        onNavigateToConnection()
    }
    val showSessionActions = state.sessionPhase == SessionPhaseSummary.ACTIVE ||
        state.sessionPhase == SessionPhaseSummary.PAUSED

    val isInitialLoading = state.freshness is DataFreshness.Loading &&
        state.snapshot == null &&
        state.error == null &&
        state.decks.isEmpty()

    Scaffold(containerColor = AppColors.appBackground) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
                .padding(innerPadding)
        ) {
            item(key = "header") {
                DashboardHeader(
                    connectionState = state.connectionState,
                    freshnessLabel = freshnessLabel(state.freshness, state.nowMs),
                    refreshing = state.refreshing,
                    audioRoute = state.audioRoute,
                    onRefresh = { viewModel.refresh() },
                    onNavigateToConnection = onNavigateToConnection,
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToDiagnostics = onNavigateToDiagnostics
                )
            }

            // Offline banner (§103): cached data stays inspectable, live actions disabled.
            if (!state.isConnected && state.snapshot != null) {
                item(key = "offline") {
                    OfflineBanner(
                        updatedAtLabel = state.snapshot?.generatedAt?.let {
                            TimeFormatting.formatRelativeTime(TimeFormatting.parseIsoToEpochMs(it), state.nowMs)
                        } ?: state.freshness.updatedAtEpochMs?.let {
                            TimeFormatting.formatRelativeTime(it, state.nowMs)
                        },
                        onReconnect = { viewModel.connect() }
            // Content width cap for tablet/foldable (§58-§60).
            val contentWidth = maxWidth.coerceAtMost(AppSpacing.dashboardMaxWidth)

            if (isInitialLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .width(contentWidth)
                        .align(Alignment.TopCenter)
                        .padding(horizontal = AppSpacing.contentGutter)
                        .verticalScroll(rememberScrollState())
                ) {
                    DashboardHeader(
                        state = state,
                        onRefresh = { viewModel.refresh() },
                        onDiagnostics = onNavigateToDiagnostics,
                        onSettings = onNavigateToSettings,
                        onConnectionClick = onNavigateToConnection
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.screenSectionGap))
                    SkeletonCard(lines = 3)
                    Spacer(modifier = Modifier.height(AppSpacing.screenSectionGap))
                    SkeletonCard(lines = 2)
                    Spacer(modifier = Modifier.height(AppSpacing.screenSectionGap))
                    SkeletonCard(lines = 2)
                }
            }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .width(contentWidth)
                        .align(Alignment.TopCenter),
                    contentPadding = PaddingValues(
                        start = AppSpacing.contentGutter,
                        end = AppSpacing.contentGutter,
                        top = AppSpacing.XS,
                        bottom = 96.dp // clear the bottom navigation
                    ),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.screenSectionGap)
                ) {
                    item(key = "header") {
                        DashboardHeader(
                            state = state,
                            onRefresh = { viewModel.refresh() },
                            onDiagnostics = onNavigateToDiagnostics,
                            onSettings = onNavigateToSettings,
                            onConnectionClick = onNavigateToConnection
                        )
                    }

            state.error?.let { error ->
                item(key = "error") { DashboardErrorPanel(error = error, connected = state.isConnected, onRetry = { viewModel.refresh() }, onDismiss = { viewModel.clearError() }, onConnect = { viewModel.connect() }) }
            }
                    // v1 notice: configuration changes apply at session start.
                    if (state.isLegacyV1) {
                        item(key = "v1info") {
                            InfoPanel(
                                text = "This Study Agent is on Protocol v1. Start, connection, audio and " +
                                    "dashboard work normally; session targets, presets, evaluation and " +
                                    "teaching options apply locally when a session starts.",
                                color = AppColors.contentMuted
                            )
                        }
                    }

            // Protocol v1 agents: basic study works, advanced panels are gated off (§12).
            if (state.isLegacyV1 && state.isConnected) {
                item(key = "v1notice") {
                    InfoPanel(
                        text = "This Study Agent uses Protocol v1. Start and voice study work as usual; the live dashboard and Study Control Center require a Protocol v2 agent.",
                        color = StatusAmber
                    )
                }
            }
                    // Offline banner (§87/§88): only when truly offline, never on cache hits.
                    if (!state.isConnected && state.error == null && !state.isLegacyV1) {
                        item(key = "offline") {
                            OfflineBanner(onConnect = onConnect)
                        }
                    }

            item(key = "system") {
                SystemReadinessCard(
                    connectionState = state.connectionState,
                    health = state.componentHealth,
                    audioRouteLabel = state.audioRoute?.outputLabel,
                    serverName = state.capabilities.serverName
                        ?: (state.connectionState as? ConnectionState.Connected)?.serverName,
                    onRefresh = { viewModel.refreshComponentHealth() }
                )
            }
                    // Dashboard error panel (§88): actionable, never a raw stack trace.
                    if (state.error != null) {
                        item(key = "error") {
                            DashboardErrorPanel(error = state.error!!, onRetry = { viewModel.refresh() }, onDismiss = { viewModel.clearError() })
                        }
                    }

            // Start/Resume zone (§19: always above the fold).
            val session = state.activeSession
            when {
                state.sessionPhase == SessionPhaseSummary.ACTIVE ||
                    state.sessionPhase == SessionPhaseSummary.PAUSED -> {
                    item(key = "activeSession") {
                        ActiveSessionCard(
                            session = session ?: SessionSummaryPayload(),
                            isPaused = state.sessionPhase == SessionPhaseSummary.PAUSED || session?.isPaused == true,
                            onResume = {
                                viewModel.resumeSession()
                                onNavigateToStudy()
                            },
                            onPause = { viewModel.pauseSession() }
                        )
                    // System readiness strip (§18-§22).
                    if (state.isV2 && !state.isLegacyV1) {
                        item(key = "system") {
                            SystemReadinessCard(
                                connectionState = state.connectionState,
                                health = state.componentHealth,
                                audioRouteLabel = state.audioRoute?.statusLabel,
                                serverName = (state.connectionState as? com.studyagent.client.core.models.ConnectionState.Connected)?.serverName,
                                onRefresh = { viewModel.refreshComponentHealth() },
                                modifier = Modifier.semantics { contentDescription = "System status" }
                            )
                        }
                    }

                    // Active session card — dashboard is the hub (§23).
                    if (showSessionActions && state.activeSession != null) {
                        val session = state.activeSession!!
                        item(key = "active-session") {
                            ActiveSessionCard(
                                session = session,
                                isPaused = state.sessionPhase == SessionPhaseSummary.PAUSED,
                                onResume = {
                                    viewModel.resumeSession()
                                    onNavigateToStudy()
                                },
                                onPause = { viewModel.pauseSession() }
                            )
                        }
                    }
                }

                else -> {
                    // Post-session summary (§83-§86): shown once per finish, then the
                    // dashboard returns to the Smart Start view.
                    if (viewModel.shouldShowFinishedSummary(studyState)) {
                        item(key = "finishedSession") {
                        val finished = state.finishedSession!!
                        item(key = "finished-session") {
                            SessionSummaryCard(
                                finished = studyState as StudyState.SessionFinished,
                                supportsWeakModes = state.isV2,
                                onReviewMistakes = { viewModel.prepareReviewMistakes() },
                                onPracticeWeak = { viewModel.preparePracticeWeakCards() },
                                onDone = { viewModel.dismissFinishedSummary() }
                                finished = finished,
                                onStartAgain = {
                                    viewModel.dismissFinishedSummary()
                                    if (viewModel.startStudy()) onNavigateToStudy()
                                },
                                onReviewMistakes = {
                                    viewModel.prepareReviewMistakes()
                                    viewModel.dismissFinishedSummary()
                                    onNavigateToControl()
                                },
                                onPracticeWeak = {
                                    viewModel.preparePracticeWeakCards()
                                    viewModel.dismissFinishedSummary()
                                    onNavigateToControl()
                                },
                                onDismiss = { viewModel.dismissFinishedSummary() }
                            )
                        }
                    }
                    item(key = "smartStart") {
                        SmartStartCard(
                            state = state,
                            onStart = {
                                if (viewModel.startStudy()) onNavigateToStudy()
                            },
                            onConnect = { viewModel.connect() }
                        )
                    }
                }
            }

            // Active deck (§27) — dynamic, capability-gated.
            val decksSupported = state.capabilities.supportsV2(AgentCapability.DECK_LIST)
            if (decksSupported || state.decks.isNotEmpty() || state.selectedDeckName != null) {
                item(key = "deck") {
                    ActiveDeckCard(
                        selectedDeckName = state.selectedDeckName,
                        selectedDeck = state.selectedDeck,
                        deckUnavailable = state.selectedDeckUnavailable,
                        decksSupported = decksSupported,
                        onChangeDeck = { showDeckPicker = true }
                    )
                }
            }
                    // Smart Start: the one dominant action (§16-§17).
                    if (!showSessionActions && !(state.finishedSession != null && viewModel.shouldShowFinishedSummary(studyState))) {
                        item(key = "smart-start") {
                            SmartStartCard(
                                state = state,
                                onStart = onSmartStart,
                                onConnect = onConnect
                            )
                        }
                    }

            // Today (§30).
            state.snapshot?.let { snapshot ->
                item(key = "today") { TodayStatsCard(today = snapshot.today) }
                    // Active deck (§27).
                    if (state.isV2) {
                        item(key = "active-deck") {
                            ActiveDeckCard(
                                selectedDeckName = state.selectedDeckName,
                                selectedDeck = state.selectedDeck,
                                deckUnavailable = state.selectedDeckUnavailable,
                                decksSupported = state.decks.isNotEmpty(),
                                onChangeDeck = { showDeckPicker = true },
                                modifier = Modifier.testTag("active_deck_card")
                            )
                        }
                    } else {
                        item(key = "deck-unsupported") {
                            UnsupportedPanel("Deck picker")
                        }
                    }

                snapshot.goal?.let { goal ->
                    item(key = "goal") { GoalProgressCard(goal = goal) }
                }
            }
                    // Today (§30/§31) — only when the server provides today's stats.
                    if (!state.isLegacyV1 && state.snapshot?.today != null) {
                        item(key = "today") {
                            TodayStatsCard(today = state.snapshot!!.today!!)
                        }
                    }

            // Weekly performance + rating distribution (§35-§38), capability-gated.
            val historySupported = state.capabilities.supportsV2(AgentCapability.HISTORY)
            if (historySupported || state.snapshot?.recentPerformance != null) {
                item(key = "history") {
                    HistoryPanel(
                        state = state,
                        onRangeSelected = { viewModel.refreshHistory(it) }
                    )
                }
            } else if (state.isV2 && state.isConnected) {
                item(key = "historyUnsupported") { UnsupportedPanel("Study history") }
            }
                    // Goal progress (§32/§33).
                    if (!state.isLegacyV1 && state.snapshot?.goal != null) {
                        item(key = "goal") {
                            GoalProgressCard(goal = state.snapshot!!.goal!!)
                        }
                    }

            // Learning insight (§39): only server-generated content, never generated on open.
            val insightSupported = state.capabilities.supportsV2(AgentCapability.LEARNING_INSIGHTS)
            val insight = state.snapshot?.insight
            if (insight != null) {
                item(key = "insight") {
                    LearningInsightCard(
                        insight = insight,
                        nowMs = state.nowMs,
                        onRefresh = { viewModel.refreshInsight() }
                    )
                }
            } else if (insightSupported && state.isConnected) {
                item(key = "insightEmpty") {
                    InfoPanel("No learning insight available yet. Insights are generated by the Study Agent after reviews.")
                }
            }
                    // History (§34/§35/§36).
                    val days = state.history?.days ?: state.snapshot?.recentPerformance?.days
                    val ratings = state.history?.ratingDistribution ?: state.snapshot?.recentPerformance?.ratingDistribution
                    val hasHistory = (days?.isNotEmpty() == true) || (ratings != null && ratings.total > 0)
                    if (!state.isLegacyV1 && (hasHistory || state.error != null)) {
                        item(key = "history") {
                            HistoryPanel(
                                days = days ?: emptyList(),
                                range = historyRange,
                                onRangeChange = { viewModel.refreshHistory(it) },
                                selectedMetric = selectedMetric,
                                onMetricChange = { selectedMetric = it },
                                ratings = ratings
                            )
                        }
                    } else if (!state.isLegacyV1) {
                        item(key = "history-empty") {
                            UnsupportedPanel("History")
                        }
                    }

            // Recommendation (§41/§42).
            state.snapshot?.recommendation?.let { recommendation ->
                item(key = "recommendation") {
                    RecommendationCard(
                        recommendation = recommendation,
                        onUse = {
                            viewModel.useRecommendation()
                            onNavigateToControl()
                    // Learning insight (§39/§40): the server's weak-topic analysis.
                    if (state.snapshot?.insight != null) {
                        item(key = "insight") {
                            LearningInsightCard(
                                insight = state.snapshot!!.insight!!,
                                nowMs = state.nowMs,
                                onRefresh = { viewModel.refreshInsight() }
                            )
                        }
                    )
                }
            }
                    }

            // AI usage (§43): only when the capability is advertised (§11/§44).
            if (state.capabilities.supportsV2(AgentCapability.AI_USAGE)) {
                item(key = "aiUsage") { AiUsageCard(usage = state.aiUsage) }
            }
                    // Recommendation (§41/§42).
                    if (state.snapshot?.recommendation != null) {
                        item(key = "recommendation") {
                            RecommendationCard(
                                recommendation = state.snapshot!!.recommendation!!,
                                onUse = {
                                    viewModel.useRecommendation()
                                    onNavigateToControl()
                                }
                            )
                        }
                    }

            if (!state.isV2 && !state.isConnected) {
                item(key = "connectHint") {
                    InfoPanel("Connect to your PC Study Agent to see live readiness, due cards, goals and recommendations.")
                    // AI usage (§43/§44).
                    if (!state.isLegacyV1 && state.aiUsage != null) {
                        item(key = "ai-usage") {
                            AiUsageCard(usage = state.aiUsage)
                        }
                    }
                }
            }
        }
    }

    // Deck picker dialog (§28).
    if (showDeckPicker) {
        DeckPickerDialog(
            decks = state.decks,
}

// ---------------------------------------------------------------------------
// Header / banners
// Header (§13/§14)
// ---------------------------------------------------------------------------

@Composable
private fun DashboardHeader(
    connectionState: ConnectionState,
    freshnessLabel: String,
    refreshing: Boolean,
    audioRoute: com.studyagent.client.core.audio.EffectiveStudyAudioRoute?,
    state: DashboardUiState,
    onRefresh: () -> Unit,
    onNavigateToConnection: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDiagnostics: () -> Unit
    onDiagnostics: () -> Unit,
    onSettings: () -> Unit,
    onConnectionClick: () -> Unit
) {
    Column {
        Row(
                Text(
                    text = "Study Agent",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Text(
                    text = freshnessLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                    color = AppColors.contentPrimary
                )
            }
            IconButton(onClick = onRefresh) {
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = TextSecondary)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh dashboard", tint = TextSecondary)
                // Subtle freshness line (§14): cached data is never presented as live.
                val freshness = state.freshness
                val label = freshnessLabel(freshness, state.nowMs)
                val dotColor = when (freshness) {
                    is DataFreshness.Live -> AppColors.statusSuccess
                    is DataFreshness.Cached -> AppColors.contentMuted
                    is DataFreshness.Stale -> AppColors.statusWarning
                    is DataFreshness.Loading -> AppColors.contentMuted
                    is DataFreshness.Unavailable -> AppColors.statusNeutral
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp).semantics { contentDescription = "Data freshness: $label" }
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.contentMuted
                    )
                }
            }
            IconButton(onClick = onNavigateToDiagnostics) {
                Icon(Icons.Default.QueryStats, contentDescription = "Diagnostics", tint = TextSecondary)
            IconButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp).width(48.dp)) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh dashboard",
                    tint = AppColors.contentSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDiagnostics, modifier = Modifier.heightIn(min = 48.dp).width(48.dp)) {
                Icon(
                    Icons.Default.Analytics,
                    contentDescription = "Diagnostics",
                    tint = AppColors.contentSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onNavigateToSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextSecondary)
            IconButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp).width(48.dp)) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = AppColors.contentSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConnectionBadge(connectionState = connectionState, onClick = onNavigateToConnection)
            AudioRouteIndicator(route = audioRoute)
            ConnectionBadge(
                connectionState = state.connectionState,
                onClick = onConnectionClick
            )
            AudioRouteIndicator(route = state.audioRoute)
        }
    }
}

// ---------------------------------------------------------------------------
// Banners / error panel (§87/§88)
// ---------------------------------------------------------------------------

@Composable
private fun OfflineBanner(updatedAtLabel: String?, onReconnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.WifiOff, contentDescription = null, tint = StatusAmber, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Offline", style = MaterialTheme.typography.titleSmall, color = StatusAmber)
                Text(
                    text = "Showing dashboard${updatedAtLabel?.let { " from $it" } ?: ""}. Live actions are disabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            OutlinedButton(onClick = onReconnect, shape = RoundedCornerShape(12.dp)) {
                Text("Reconnect", color = PrimaryBlue)
            }
        }
    }
private fun OfflineBanner(onConnect: () -> Unit) {
    com.studyagent.client.ui.components.InfoBanner(
        message = "Showing cached data. Connect to your PC Study Agent for live study.",
        tone = com.studyagent.client.ui.components.BannerTone.WARNING,
        title = "Offline",
        actionLabel = "Connect",
        onAction = onConnect
    )
}

@Composable
private fun DashboardErrorPanel(
    error: DashboardError,
    connected: Boolean,
    error: com.studyagent.client.data.repository.DashboardError,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onConnect: () -> Unit
    onDismiss: () -> Unit
) {
    val (title, detail) = when (error) {
        DashboardError.NotConnected -> "No connection" to "Connect to the PC Study Agent to load the dashboard."
        DashboardError.NotSupported -> "Dashboard not supported" to "This Study Agent does not provide dashboard data."
        DashboardError.TimedOut -> "Request timed out" to "The Study Agent did not answer in time. Cached data may be shown."
        is DashboardError.RequestFailed -> "Dashboard request failed" to (error.message)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = StatusRed)
            Spacer(modifier = Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (error) {
                    DashboardError.NotConnected -> Button(onClick = onConnect, shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)) {
                        Text("Connect", color = TextPrimary)
                    }

                    DashboardError.TimedOut, is DashboardError.RequestFailed ->
                        Button(onClick = onRetry, enabled = connected, shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)) {
                            Text("Retry", color = TextPrimary)
                        }

                    DashboardError.NotSupported -> Unit
                }
                TextButton(onClick = onDismiss) { Text("Dismiss", color = TextSecondary) }
            }
        }
    // Friendly wording only (§78): technical detail stays in Diagnostics.
    val message = when (error) {
        com.studyagent.client.data.repository.DashboardError.NotConnected ->
            "You're not connected to the Study Agent."
        com.studyagent.client.data.repository.DashboardError.NotSupported ->
            "This Study Agent version does not report this data."
        com.studyagent.client.data.repository.DashboardError.TimedOut ->
            "The Study Agent took too long to answer."
        is com.studyagent.client.data.repository.DashboardError.RequestFailed ->
            "Couldn't load dashboard data."
    }
    com.studyagent.client.ui.components.InfoBanner(
        message = message,
        tone = com.studyagent.client.ui.components.BannerTone.DANGER,
        title = "Dashboard unavailable",
        actionLabel = "Retry",
        onAction = onRetry,
        dismissible = true,
        onDismiss = onDismiss
    )
}

// ---------------------------------------------------------------------------
// History panel (§35/§38): chart with range + metric selectors and an
// accessible text fallback.
// Finished session summary (§83-§86)
// ---------------------------------------------------------------------------

private enum class HistoryMetric(val label: String) { CARDS("Cards"), RECALL("Recall"), TIME("Time") }

@Composable
private fun HistoryPanel(
    state: DashboardUiState,
    onRangeSelected: (StatsRange) -> Unit
private fun SessionSummaryCard(
    finished: com.studyagent.client.core.models.StudyState.SessionFinished,
    onStartAgain: () -> Unit,
    onReviewMistakes: () -> Unit,
    onPracticeWeak: () -> Unit,
    onDismiss: () -> Unit
) {
    // Prefer the dedicated history response; fall back to the snapshot's bundled
    // recent performance (§91: one snapshot normally, dedicated requests for ranges).
    val days: List<DayStats> = state.history?.days
        ?: state.snapshot?.recentPerformance?.days
        ?: emptyList()
    val distribution: RatingDistribution? = state.history?.ratingDistribution
        ?: state.snapshot?.recentPerformance?.ratingDistribution
    val range = state.history?.range?.let { StatsRange.fromWire(it) }
        ?: state.snapshot?.recentPerformance?.range?.let { StatsRange.fromWire(it) }
        ?: state.historyRange
    var metric by remember { mutableStateOf(HistoryMetric.CARDS) }
    var showTextList by remember { mutableStateOf(false) }

    SectionCard(title = "Last ${if (range == StatsRange.THIRTY_DAYS) "30 days" else if (range == StatsRange.TODAY) "day" else "7 days"}") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsRange.entries.forEach { r ->
                FilterChip(
                    selected = r == range,
                    onClick = { onRangeSelected(r) },
                    label = { Text(r.displayName, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DarkSurfaceElevated,
                        selectedLabelColor = PrimaryBlue
                    )
    val details = finished.details
    val weakTopics = details?.weakTopics ?: emptyList()

    com.studyagent.client.ui.components.AppCard {
        Column(modifier = Modifier.padding(AppSpacing.heroCardPadding)) {
            Text(
                text = "SESSION COMPLETE",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.statusSuccess
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (!finished.summary.isNullOrBlank()) {
                Text(
                    text = finished.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.contentSecondary
                )
                Spacer(modifier = Modifier.height(AppSpacing.XS))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (days.isEmpty()) {
            Text("No study history for this range yet.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HistoryMetric.entries.forEach { m ->
                    FilterChip(
                        selected = m == metric,
                        onClick = { metric = m },
                        label = { Text(m.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = DarkSurfaceElevated,
                            selectedLabelColor = AccentTeal
                        )
            if (details != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatItem(
                        label = "Reviewed",
                        value = details.totalCards?.let { "${details.cardsReviewed} / $it" } ?: "${details.cardsReviewed}"
                    )
                    StatItem(
                        label = "Recall",
                        value = details.recallRate?.let { TimeFormatting.formatPercent(it) },
                        valueColor = AppColors.voiceSpeaking
                    )
                    StatItem(label = "Time", value = TimeFormatting.formatDurationSeconds(details.elapsedSeconds))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (showTextList) {
                days.forEachIndexed { index, day ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(dayLabel(day.date, index), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text(
                            text = metricValueLabel(day, metric),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.XS))
            } else {
                WeeklyBars(
                    days = days,
                    valueSelector = { day ->
                        when (metric) {
                            HistoryMetric.CARDS -> day.cardsReviewed.toFloat()
                            HistoryMetric.RECALL -> day.recallRate?.toFloat() ?: 0f
                            HistoryMetric.TIME -> day.studyTimeSeconds.toFloat() / 60f
                        }
                    },
                    labelFor = { day -> "${dayLabel(day.date, days.indexOf(day))} ${metricValueLabel(day, metric)}" }
                Text(
                    text = "Reviewed ${finished.cardsReviewed} cards",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.contentSecondary
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(dayLabel(days.firstOrNull()?.date, 0), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text(dayLabel(days.lastOrNull()?.date, days.size - 1), style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
            if (weakTopics.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.XS))
                Text(
                    text = "Focus next: ${weakTopics.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.statusWarning
                )
            }
            Spacer(modifier = Modifier.height(AppSpacing.SM))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
            ) {
                Button(
                    onClick = onStartAgain,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = AppShape.buttonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.actionPrimaryStrong)
                ) {
                    Text("Start Again", color = Color.White)
                }
                OutlinedButton(
                    onClick = onReviewMistakes,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = AppShape.buttonShape
                ) {
                    Text("Review Mistakes", color = AppColors.contentSecondary)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(onClick = { showTextList = !showTextList }) {
                Text(if (showTextList) "Show chart" else "Show values", color = TextSecondary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
            ) {
                OutlinedButton(
                    onClick = onPracticeWeak,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                        enabled = weakTopics.isNotEmpty(),
                    shape = AppShape.buttonShape
                ) {
                    Text("Practice Weak", color = AppColors.contentSecondary)
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = AppShape.buttonShape
                ) {
                    Text("Dismiss", color = AppColors.contentMuted)
                }
            }
        }

        if (distribution != null && distribution.total > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            RatingDistributionCard(distribution = distribution, rangeLabel = range.displayName)
        }
    }
}

private fun metricValueLabel(day: DayStats, metric: HistoryMetric): String = when (metric) {
    HistoryMetric.CARDS -> "${day.cardsReviewed}"
    HistoryMetric.RECALL -> TimeFormatting.formatPercent(day.recallRate)
    HistoryMetric.TIME -> TimeFormatting.formatDurationSeconds(day.studyTimeSeconds)
}

// ---------------------------------------------------------------------------
// Finished session summary (§83-§86)
// History panel (§34-§37)
// ---------------------------------------------------------------------------

private enum class HistoryMetric(val label: String) {
    REVIEWED("Reviewed"),
    NEW("New"),
    RECALL("Recall"),
    TIME("Time")
}

@Composable
private fun SessionSummaryCard(
    finished: StudyState.SessionFinished,
    supportsWeakModes: Boolean,
    onReviewMistakes: () -> Unit,
    onPracticeWeak: () -> Unit,
    onDone: () -> Unit
private fun HistoryPanel(
    days: List<com.studyagent.client.core.models.DayStats>,
    range: com.studyagent.client.core.models.StatsRange,
    onRangeChange: (com.studyagent.client.core.models.StatsRange) -> Unit,
    selectedMetric: HistoryMetric,
    onMetricChange: (HistoryMetric) -> Unit,
    ratings: com.studyagent.client.core.models.RatingDistribution?
) {
    val details = finished.details
    SectionCard(title = "Session Complete", accent = AccentTeal) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            StatItem("Reviewed", "${details?.cardsReviewed ?: finished.cardsReviewed}")
            StatItem(
                "Recall",
                details?.recallRate?.let { TimeFormatting.formatPercent(it) },
                valueColor = AccentTeal
            )
            StatItem("Time", details?.let { TimeFormatting.formatDurationSeconds(it.elapsedSeconds) })
            StatItem("Avg/card", details?.avgSecondsPerCard?.let { "${it.toInt()}s" })
        }
        val distribution = details?.ratingDistribution
        if (distribution != null && distribution.total > 0) {
            Spacer(modifier = Modifier.height(12.dp))
            RatingDistributionCard(distribution = distribution, rangeLabel = "This session")
        }
        if (details != null && details.weakTopics.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("Weak topics:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            details.weakTopics.forEach { topic ->
                Text("• $topic", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    SectionCard(title = "Performance") {
        // Range filter: drives server refresh (§34).
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)
        ) {
            com.studyagent.client.core.models.StatsRange.entries.forEach { r ->
                FilterChip(
                    selected = range == r,
                    onClick = { onRangeChange(r) },
                    label = { Text(r.displayName) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppColors.surfaceInteractive,
                        selectedLabelColor = AppColors.statusInfo
                    ),
                    modifier = Modifier.heightIn(min = 40.dp)
                )
            }
        }
        details?.aiNote?.let { note ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(note, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        // Metric filter: UI-only, re-sorts the same data (§35).
        Spacer(modifier = Modifier.height(AppSpacing.XS))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)
        ) {
            HistoryMetric.entries.forEach { metric ->
                FilterChip(
                    selected = selectedMetric == metric,
                    onClick = { onMetricChange(metric) },
                    label = { Text(metric.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppColors.surfaceInteractive,
                        selectedLabelColor = AppColors.voiceSpeaking
                    ),
                    modifier = Modifier.heightIn(min = 40.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (supportsWeakModes) {
                OutlinedButton(onClick = onReviewMistakes, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Text("Review mistakes", color = PrimaryBlue, style = MaterialTheme.typography.labelMedium)

        if (days.isNotEmpty()) {
            Spacer(modifier = Modifier.height(AppSpacing.SM))
            val valueSelector = { day: com.studyagent.client.core.models.DayStats ->
                when (selectedMetric) {
                    HistoryMetric.REVIEWED -> day.cardsReviewed.toFloat()
                    HistoryMetric.NEW -> day.newCards.toFloat()
                    HistoryMetric.RECALL -> (day.recallRate ?: 0.0).toFloat()
                    HistoryMetric.TIME -> (day.studyTimeSeconds / 60f)
                }
                OutlinedButton(onClick = onPracticeWeak, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Text("Weak cards", color = AccentTeal, style = MaterialTheme.typography.labelMedium)
            }
            WeeklyBars(
                days = days,
                valueSelector = valueSelector,
                labelFor = { dayLabel(it.date, days.indexOf(it)) }
            )
            // Accessible per-day values: the chart is decorative, the list is truth (§37).
            Spacer(modifier = Modifier.height(AppSpacing.XS))
            days.forEach { day ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .semantics {
                            contentDescription = "${dayLabel(day.date, days.indexOf(day))}: " +
                                metricValue(day, selectedMetric)
                        },
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = dayLabel(day.date, days.indexOf(day)),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.contentMuted
                    )
                    Text(
                        text = metricValue(day, selectedMetric),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.contentPrimary
                    )
                }
            }
            TextButton(onClick = onDone) { Text("Done", color = TextSecondary) }
        } else {
            Spacer(modifier = Modifier.height(AppSpacing.SM))
            Text(
                "No performance data for this range yet.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.contentMuted
            )
        }

        // Rating distribution (§38) belongs to the same range.
        if (ratings != null && ratings.total > 0) {
            Spacer(modifier = Modifier.height(AppSpacing.SM))
            RatingDistributionCard(
                distribution = ratings,
                rangeLabel = range.displayName
            )
        }
    }
}

private fun metricValue(day: com.studyagent.client.core.models.DayStats, metric: HistoryMetric): String =
    when (metric) {
        HistoryMetric.REVIEWED -> "${day.cardsReviewed} cards"
        HistoryMetric.NEW -> "${day.newCards} new"
        HistoryMetric.RECALL -> day.recallRate?.let { TimeFormatting.formatPercent(it) } ?: "—"
        HistoryMetric.TIME -> TimeFormatting.formatDurationSeconds(day.studyTimeSeconds)
    }
// ---------------------------------------------------------------------------
// Finished session summary (§83-§86)
// ---------------------------------------------------------------------------

@Composable
private fun SessionSummaryCard(
    finished: StudyState.SessionFinished,
    supportsWeakModes: Boolean,
    onReviewMistakes: () -> Unit,
    onPracticeWeak: () -> Unit,
    onDone: () -> Unit
) {
    val details = finished.details
    SectionCard(title = "Session Complete", accent = AccentTeal) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            StatItem("Reviewed", "${details?.cardsReviewed ?: finished.cardsReviewed}")
            StatItem(
                "Recall",
                details?.recallRate?.let { TimeFormatting.formatPercent(it) },
                valueColor = AccentTeal
            )
            StatItem("Time", details?.let { TimeFormatting.formatDurationSeconds(it.elapsedSeconds) })
            StatItem("Avg/card", details?.avgSecondsPerCard?.let { "${it.toInt()}s" })
        }
        val distribution = details?.ratingDistribution
        if (distribution != null && distribution.total > 0) {
            Spacer(modifier = Modifier.height(12.dp))
            RatingDistributionCard(distribution = distribution, rangeLabel = "This session")
        }
        if (details != null && details.weakTopics.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("Weak topics:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            details.weakTopics.forEach { topic ->
                Text("• $topic", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        details?.aiNote?.let { note ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(note, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (supportsWeakModes) {
                OutlinedButton(onClick = onReviewMistakes, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Text("Review mistakes", color = PrimaryBlue, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(onClick = onPracticeWeak, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Text("Weak cards", color = AccentTeal, style = MaterialTheme.typography.labelMedium)
                }
            }
            TextButton(onClick = onDone) { Text("Done", color = TextSecondary) }
        }
    }
}
