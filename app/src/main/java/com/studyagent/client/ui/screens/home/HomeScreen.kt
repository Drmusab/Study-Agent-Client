package com.studyagent.client.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyagent.client.core.common.TimeFormatting
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.DataFreshness
import com.studyagent.client.core.models.DayStats
import com.studyagent.client.core.models.RatingDistribution
import com.studyagent.client.core.models.StatsRange
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.data.repository.DashboardError
import com.studyagent.client.ui.components.AppHeroCard
import com.studyagent.client.ui.components.AudioRouteIndicator
import com.studyagent.client.ui.components.BannerTone
import com.studyagent.client.ui.components.ConnectionBadge
import com.studyagent.client.ui.components.InfoBanner
import com.studyagent.client.ui.components.InlineTextButton
import com.studyagent.client.ui.components.PrimaryButton
import com.studyagent.client.ui.components.SecondaryButton
import com.studyagent.client.ui.components.SkeletonCard
import com.studyagent.client.ui.screens.home.components.ActiveDeckCard
import com.studyagent.client.ui.screens.home.components.ActiveSessionCard
import com.studyagent.client.ui.screens.home.components.AiUsageCard
import com.studyagent.client.ui.screens.home.components.DeckPickerDialog
import com.studyagent.client.ui.screens.home.components.GoalProgressCard
import com.studyagent.client.ui.screens.home.components.InfoPanel
import com.studyagent.client.ui.screens.home.components.LearningInsightCard
import com.studyagent.client.ui.screens.home.components.RatingDistributionRows
import com.studyagent.client.ui.screens.home.components.RecommendationCard
import com.studyagent.client.ui.screens.home.components.SectionCard
import com.studyagent.client.ui.screens.home.components.SmartStartCard
import com.studyagent.client.ui.screens.home.components.StatItem
import com.studyagent.client.ui.screens.home.components.SystemReadinessCard
import com.studyagent.client.ui.screens.home.components.TodayStatsCard
import com.studyagent.client.ui.screens.home.components.UnsupportedPanel
import com.studyagent.client.ui.screens.home.components.WeeklyBars
import com.studyagent.client.ui.screens.home.components.dayLabel
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppSpacing

/** Test tag on the Active Deck card (read by the instrumented suite). */
const val ACTIVE_DECK_CARD_TEST_TAG = "active_deck_card"

/**
 * Primary Dashboard: status first, then the one dominant Start/Resume action, then
 * progressively lower-priority information (§13-§15).
 *
 * Order is fixed:
 *   header (title, freshness, actions) → connection/audio line → banners
 *   → System readiness → Smart Start / Active session / Finished summary
 *   → Active Deck → Today → Goal → History → Insight → Recommendation → AI usage.
 *
 * Every metric shown here originates from the PC Study Agent — nothing is invented
 * client-side.
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
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val studyState by viewModel.studyState.collectAsStateWithLifecycle()

    // Refresh when the dashboard becomes visible — the repository coalesces and
    // de-dupes from here; recomposition never spams the server.
    LaunchedEffect(Unit) { viewModel.onScreenActive() }

    var showDeckPicker by rememberSaveable { mutableStateOf(false) }
    var selectedMetric by rememberSaveable { mutableStateOf(HistoryMetric.REVIEWED) }

    // DashboardUiState already exposes the derived primary action. startStudy() returns
    // false when a session is active or starting, which cancels the navigation.
    val onSmartStart: () -> Unit = { if (viewModel.startStudy()) onNavigateToStudy() }
    val onConnect: () -> Unit = {
        viewModel.connect()
        onNavigateToConnection()
    }

    val sessionActive = state.sessionPhase == SessionPhaseSummary.ACTIVE ||
        state.sessionPhase == SessionPhaseSummary.PAUSED
    val showFinished = state.finishedSession != null && viewModel.shouldShowFinishedSummary(studyState)
    val isInitialLoading = state.freshness is DataFreshness.Loading &&
        state.snapshot == null &&
        state.error == null &&
        state.decks.isEmpty()

    Scaffold(containerColor = AppColors.appBackground) { innerPadding ->
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Content width cap for tablet/foldable (§58-§60).
            val contentWidth = maxWidth.coerceAtMost(AppSpacing.dashboardMaxWidth)

            if (isInitialLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .width(contentWidth)
                        .align(Alignment.TopCenter)
                        .padding(horizontal = AppSpacing.contentGutter, vertical = AppSpacing.XS)
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
                        bottom = AppSpacing.XXL
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

                    // Banners (§87/§88): at most one of error / offline / v1 notice at a time.
                    val error = state.error
                    when {
                        error != null -> item(key = "error") {
                            DashboardErrorPanel(
                                error = error,
                                connected = state.isConnected,
                                onRetry = { viewModel.refresh() },
                                onConnect = onConnect,
                                onDismiss = { viewModel.clearError() }
                            )
                        }
                        !state.isConnected && state.snapshot != null -> item(key = "offline") {
                            OfflineBanner(
                                updatedAtLabel = state.freshness.updatedAtEpochMs?.let {
                                    TimeFormatting.formatRelativeTime(it, state.nowMs)
                                },
                                onConnect = onConnect
                            )
                        }
                        state.isLegacyV1 && state.isConnected -> item(key = "v1notice") {
                            InfoBanner(
                                title = "Protocol v1 agent",
                                message = "Start and voice study work as usual. The live dashboard and " +
                                    "Study Control need a Protocol v2 Study Agent.",
                                tone = BannerTone.INFO
                            )
                        }
                    }

                    // System readiness strip (§18-§22).
                    item(key = "system") {
                        SystemReadinessCard(
                            connectionState = state.connectionState,
                            health = state.componentHealth,
                            audioRouteLabel = state.audioRoute?.statusLabel,
                            serverName = state.capabilities.serverName
                                ?: (state.connectionState as? ConnectionState.Connected)?.serverName,
                            onRefresh = { viewModel.refreshComponentHealth() },
                            modifier = Modifier.semantics { contentDescription = "System status" }
                        )
                    }

                    // Hero zone (§16/§23): exactly one of active session / finished summary / Smart Start.
                    when {
                        sessionActive -> item(key = "active-session") {
                            val session = state.activeSession
                                ?: com.studyagent.client.core.models.SessionSummaryPayload()
                            ActiveSessionCard(
                                session = session,
                                isPaused = state.sessionPhase == SessionPhaseSummary.PAUSED || session.isPaused,
                                onResume = {
                                    viewModel.resumeSession()
                                    onNavigateToStudy()
                                },
                                onPause = { viewModel.pauseSession() }
                            )
                        }
                        showFinished -> item(key = "finished-session") {
                            SessionSummaryCard(
                                finished = state.finishedSession!!,
                                supportsWeakModes = state.isV2,
                                onStartAgain = {
                                    viewModel.dismissFinishedSummary()
                                    onSmartStart()
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
                        else -> item(key = "smart-start") {
                            SmartStartCard(state = state, onStart = onSmartStart, onConnect = onConnect)
                        }
                    }

                    // Active deck (§27): capability-gated.
                    val decksSupported = state.decks.isNotEmpty()
                    if (decksSupported || state.selectedDeckName != null) {
                        item(key = "active-deck") {
                            ActiveDeckCard(
                                selectedDeckName = state.selectedDeckName,
                                selectedDeck = state.selectedDeck,
                                deckUnavailable = state.selectedDeckUnavailable,
                                decksSupported = decksSupported,
                                onChangeDeck = { showDeckPicker = true },
                                modifier = Modifier.testTag(ACTIVE_DECK_CARD_TEST_TAG)
                            )
                        }
                    }

                    val snapshot = state.snapshot
                    if (snapshot != null && !state.isLegacyV1) {
                        item(key = "today") { TodayStatsCard(today = snapshot.today) }
                        snapshot.goal?.let { goal ->
                            item(key = "goal") { GoalProgressCard(goal = goal) }
                        }
                    }

                    // History (§34-§38): dedicated payload first, snapshot fallback.
                    if (!state.isLegacyV1) {
                        val days = state.history?.days ?: snapshot?.recentPerformance?.days ?: emptyList()
                        val ratings = state.history?.ratingDistribution ?: snapshot?.recentPerformance?.ratingDistribution
                        val range = state.history?.range?.let { StatsRange.fromWire(it) } ?: state.historyRange
                        if (days.isNotEmpty() || (ratings != null && ratings.total > 0) || state.isConnected) {
                            item(key = "history") {
                                HistoryPanel(
                                    days = days,
                                    range = range,
                                    onRangeChange = { viewModel.refreshHistory(it) },
                                    selectedMetric = selectedMetric,
                                    onMetricChange = { selectedMetric = it },
                                    ratings = ratings
                                )
                            }
                        } else if (snapshot != null) {
                            item(key = "history-unsupported") { UnsupportedPanel("Study history") }
                        }
                    }

                    snapshot?.insight?.let { insight ->
                        item(key = "insight") {
                            LearningInsightCard(
                                insight = insight,
                                nowMs = state.nowMs,
                                onRefresh = { viewModel.refreshInsight() }
                            )
                        }
                    }

                    snapshot?.recommendation?.let { recommendation ->
                        item(key = "recommendation") {
                            RecommendationCard(
                                recommendation = recommendation,
                                onUse = {
                                    viewModel.useRecommendation()
                                    onNavigateToControl()
                                }
                            )
                        }
                    }

                    if (!state.isLegacyV1 && state.aiUsage != null) {
                        item(key = "ai-usage") { AiUsageCard(usage = state.aiUsage) }
                    }

                    if (!state.isConnected && snapshot == null) {
                        item(key = "connect-hint") {
                            InfoPanel(
                                "Connect to your PC Study Agent to see readiness, due cards, goals and recommendations."
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeckPicker) {
        DeckPickerDialog(
            decks = state.decks,
            currentDeckName = state.selectedDeckName,
            onSelect = { name ->
                viewModel.selectDeck(name)
                showDeckPicker = false
            },
            onDismiss = { showDeckPicker = false }
        )
    }
}

// ---------------------------------------------------------------------------
// Header (§13/§14)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardHeader(
    state: DashboardUiState,
    onRefresh: () -> Unit,
    onDiagnostics: () -> Unit,
    onSettings: () -> Unit,
    onConnectionClick: () -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Study Agent",
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppColors.contentPrimary
                )
                // Subtle freshness line (§14): cached data is never presented as live.
                val label = freshnessLabel(state.freshness, state.nowMs)
                val dotColor = when (state.freshness) {
                    is DataFreshness.Live -> AppColors.statusSuccess
                    is DataFreshness.Stale -> AppColors.statusWarning
                    is DataFreshness.Cached, is DataFreshness.Loading, is DataFreshness.Unavailable -> AppColors.statusNeutral
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .semantics { contentDescription = "Data freshness: $label" }
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = label, style = MaterialTheme.typography.labelSmall, color = AppColors.contentMuted)
                }
            }
            IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                if (state.refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = "Refresh dashboard" },
                        strokeWidth = 2.dp,
                        color = AppColors.contentSecondary
                    )
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh dashboard",
                        tint = AppColors.contentSecondary
                    )
                }
            }
            IconButton(onClick = onDiagnostics) {
                Icon(Icons.Default.Analytics, contentDescription = "Diagnostics", tint = AppColors.contentSecondary)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = AppColors.contentSecondary)
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.XS))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)
        ) {
            ConnectionBadge(connectionState = state.connectionState, onClick = onConnectionClick)
            AudioRouteIndicator(route = state.audioRoute)
        }
    }
}

// ---------------------------------------------------------------------------
// Banners / error panel (§87/§88) — friendly wording only; detail lives in Diagnostics.
// ---------------------------------------------------------------------------

@Composable
private fun OfflineBanner(updatedAtLabel: String?, onConnect: () -> Unit) {
    InfoBanner(
        title = "Offline",
        message = "Showing dashboard${updatedAtLabel?.let { " from $it" } ?: ""}. Live actions are unavailable.",
        tone = BannerTone.WARNING,
        actionLabel = "Connect",
        onAction = onConnect
    )
}

@Composable
private fun DashboardErrorPanel(
    error: DashboardError,
    connected: Boolean,
    onRetry: () -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val (title, message) = when (error) {
        DashboardError.NotConnected -> "Not connected" to "Connect to the PC Study Agent to load the dashboard."
        DashboardError.NotSupported -> "Dashboard not supported" to "This Study Agent does not provide dashboard data."
        DashboardError.TimedOut -> "Request timed out" to "The Study Agent took too long to answer. Cached data may be shown."
        is DashboardError.RequestFailed -> "Couldn't load dashboard" to "Details are available in Diagnostics."
    }
    val (actionLabel, action) = when (error) {
        DashboardError.NotConnected -> "Connect" to onConnect
        DashboardError.TimedOut, is DashboardError.RequestFailed ->
            if (connected) "Retry" to onRetry else null to null
        DashboardError.NotSupported -> null to null
    }
    InfoBanner(
        title = title,
        message = message,
        tone = BannerTone.DANGER,
        actionLabel = actionLabel,
        onAction = action,
        dismissible = true,
        onDismiss = onDismiss
    )
}

// ---------------------------------------------------------------------------
// History panel (§34-§38): range + metric filters, bar chart, accessible values.
// ---------------------------------------------------------------------------

enum class HistoryMetric(val label: String) { REVIEWED("Reviewed"), NEW("New"), RECALL("Recall"), TIME("Time") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryPanel(
    days: List<DayStats>,
    range: StatsRange,
    onRangeChange: (StatsRange) -> Unit,
    selectedMetric: HistoryMetric,
    onMetricChange: (HistoryMetric) -> Unit,
    ratings: RatingDistribution?
) {
    var showValues by rememberSaveable { mutableStateOf(false) }

    SectionCard(title = "Performance") {
        // Range filter: drives a server refresh (§34).
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)
        ) {
            StatsRange.entries.forEach { r ->
                FilterChip(
                    selected = range == r,
                    onClick = { onRangeChange(r) },
                    label = { Text(r.displayName) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppColors.surfaceInteractive,
                        selectedLabelColor = AppColors.actionPrimary,
                        labelColor = AppColors.contentSecondary
                    ),
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
        if (days.isEmpty()) {
            Spacer(modifier = Modifier.height(AppSpacing.SM))
            Text(
                "No performance data for this range yet.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.contentMuted
            )
        } else {
            // Metric filter: UI-only, re-projects the same data (§35).
            Spacer(modifier = Modifier.height(AppSpacing.XS))
            FlowRow(
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
                            selectedLabelColor = AppColors.actionAccent,
                            labelColor = AppColors.contentSecondary
                        ),
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.SM))
            if (showValues) {
                days.forEachIndexed { index, day ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(dayLabel(day.date, index), style = MaterialTheme.typography.bodySmall, color = AppColors.contentSecondary)
                        Text(metricValue(day, selectedMetric), style = MaterialTheme.typography.bodySmall, color = AppColors.contentPrimary)
                    }
                }
            } else {
                WeeklyBars(
                    days = days,
                    valueSelector = { day ->
                        when (selectedMetric) {
                            HistoryMetric.REVIEWED -> day.cardsReviewed.toFloat()
                            HistoryMetric.NEW -> day.newCards.toFloat()
                            HistoryMetric.RECALL -> (day.recallRate ?: 0.0).toFloat()
                            HistoryMetric.TIME -> day.studyTimeSeconds / 60f
                        }
                    },
                    labelFor = { day -> "${dayLabel(day.date, days.indexOf(day))} ${metricValue(day, selectedMetric)}" }
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(dayLabel(days.first().date, 0), style = MaterialTheme.typography.labelSmall, color = AppColors.contentMuted)
                    Text(dayLabel(days.last().date, days.size - 1), style = MaterialTheme.typography.labelSmall, color = AppColors.contentMuted)
                }
            }
            InlineTextButton(
                text = if (showValues) "Show chart" else "Show values",
                onClick = { showValues = !showValues },
                color = AppColors.contentSecondary
            )
        }
        // Rating distribution (§38) belongs to the same range.
        if (ratings != null && ratings.total > 0) {
            Spacer(modifier = Modifier.height(AppSpacing.XS))
            Text(
                text = "Ratings • ${range.displayName}",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.contentSecondary
            )
            Spacer(modifier = Modifier.height(AppSpacing.XXS))
            RatingDistributionRows(ratings)
        }
    }
}

private fun metricValue(day: DayStats, metric: HistoryMetric): String = when (metric) {
    HistoryMetric.REVIEWED -> "${day.cardsReviewed} cards"
    HistoryMetric.NEW -> "${day.newCards} new"
    HistoryMetric.RECALL -> TimeFormatting.formatPercent(day.recallRate)
    HistoryMetric.TIME -> TimeFormatting.formatDurationSeconds(day.studyTimeSeconds)
}

// ---------------------------------------------------------------------------
// Finished session summary (§83-§86)
// ---------------------------------------------------------------------------

@Composable
private fun SessionSummaryCard(
    finished: StudyState.SessionFinished,
    supportsWeakModes: Boolean,
    onStartAgain: () -> Unit,
    onReviewMistakes: () -> Unit,
    onPracticeWeak: () -> Unit,
    onDismiss: () -> Unit
) {
    val details = finished.details
    val weakTopics = details?.weakTopics ?: emptyList()

    AppHeroCard {
        Column(modifier = Modifier.padding(AppSpacing.heroCardPadding)) {
            Text(
                text = "Session complete",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.statusSuccess
            )
            Spacer(modifier = Modifier.height(AppSpacing.XXS))
            Text(
                text = finished.summary?.takeIf { it.isNotBlank() } ?: "Reviewed ${finished.cardsReviewed} cards",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.contentPrimary
            )
            Spacer(modifier = Modifier.height(AppSpacing.SM))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                StatItem("Reviewed", "${details?.cardsReviewed ?: finished.cardsReviewed}")
                StatItem("Recall", details?.recallRate?.let { TimeFormatting.formatPercent(it) }, valueColor = AppColors.actionAccent)
                StatItem("Time", details?.let { TimeFormatting.formatDurationSeconds(it.elapsedSeconds) })
                StatItem("Avg/card", details?.avgSecondsPerCard?.let { "${it.toInt()}s" })
            }
            val distribution = details?.ratingDistribution
            if (distribution != null && distribution.total > 0) {
                Spacer(modifier = Modifier.height(AppSpacing.SM))
                RatingDistributionRows(distribution)
            }
            if (weakTopics.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.SM))
                Text(
                    text = "Focus next: ${weakTopics.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.statusWarning
                )
            }
            details?.aiNote?.let { note ->
                Spacer(modifier = Modifier.height(AppSpacing.XS))
                Text(note, style = MaterialTheme.typography.bodyMedium, color = AppColors.contentPrimary)
            }
            Spacer(modifier = Modifier.height(AppSpacing.MD))
            PrimaryButton(
                text = "Start Again",
                onClick = onStartAgain,
                modifier = Modifier.fillMaxWidth()
            )
            if (supportsWeakModes) {
                Spacer(modifier = Modifier.height(AppSpacing.XS))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
                    SecondaryButton(text = "Review Mistakes", onClick = onReviewMistakes, modifier = Modifier.weight(1f))
                    SecondaryButton(
                        text = "Practice Weak",
                        onClick = onPracticeWeak,
                        enabled = weakTopics.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            InlineTextButton(
                text = "Dismiss",
                onClick = onDismiss,
                color = AppColors.contentSecondary,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}
