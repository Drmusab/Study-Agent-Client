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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyagent.client.core.common.TimeFormatting
import com.studyagent.client.core.models.AgentCapability
import com.studyagent.client.core.models.AiUsageRange
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.DayStats
import com.studyagent.client.core.models.RatingDistribution
import com.studyagent.client.core.models.SessionSummaryPayload
import com.studyagent.client.core.models.StatsRange
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.data.repository.DashboardError
import com.studyagent.client.data.repository.supportsV2
import com.studyagent.client.ui.components.AudioRouteIndicator
import com.studyagent.client.ui.components.ConnectionBadge
import com.studyagent.client.ui.screens.home.components.ActiveDeckCard
import com.studyagent.client.ui.screens.home.components.AiUsageCard
import com.studyagent.client.ui.screens.home.components.DeckPickerDialog
import com.studyagent.client.ui.screens.home.components.GoalProgressCard
import com.studyagent.client.ui.screens.home.components.InfoPanel
import com.studyagent.client.ui.screens.home.components.LearningInsightCard
import com.studyagent.client.ui.screens.home.components.RatingDistributionCard
import com.studyagent.client.ui.screens.home.components.RecommendationCard
import com.studyagent.client.ui.screens.home.components.SectionCard
import com.studyagent.client.ui.screens.home.components.SmartStartCard
import com.studyagent.client.ui.screens.home.components.ActiveSessionCard
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

/**
 * The Study Agent command center: a live operational dashboard (§19).
 * Every metric shown here originates from the PC Study Agent via Protocol v2 —
 * nothing is invented client-side (§6/§151).
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
    val studyState by viewModel.studyState.collectAsState()
    var showDeckPicker by remember { mutableStateOf(false) }

    // Lifecycle-driven request: runs when the screen enters composition, never on
    // recomposition. The repository coalesces and de-dupes from here (§110/§111).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.onScreenActive()
    }

    Scaffold(containerColor = DarkBackground) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                    )
                }
            }

            state.error?.let { error ->
                item(key = "error") { DashboardErrorPanel(error = error, connected = state.isConnected, onRetry = { viewModel.refresh() }, onDismiss = { viewModel.clearError() }, onConnect = { viewModel.connect() }) }
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
                    }
                }

                else -> {
                    if (viewModel.shouldShowFinishedSummary(studyState)) {
                        item(key = "finishedSession") {
                            SessionSummaryCard(
                                finished = studyState as StudyState.SessionFinished,
                                supportsWeakModes = state.isV2,
                                onReviewMistakes = { viewModel.prepareReviewMistakes() },
                                onPracticeWeak = { viewModel.preparePracticeWeakCards() },
                                onDone = { viewModel.dismissFinishedSummary() }
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

            // Today (§30).
            state.snapshot?.let { snapshot ->
                item(key = "today") { TodayStatsCard(today = snapshot.today) }

                snapshot.goal?.let { goal ->
                    item(key = "goal") { GoalProgressCard(goal = goal) }
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

            // Recommendation (§41/§42).
            state.snapshot?.recommendation?.let { recommendation ->
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

            // AI usage (§43): only when the capability is advertised (§11/§44).
            if (state.capabilities.supportsV2(AgentCapability.AI_USAGE)) {
                item(key = "aiUsage") { AiUsageCard(usage = state.aiUsage) }
            }

            if (!state.isV2 && !state.isConnected) {
                item(key = "connectHint") {
                    InfoPanel("Connect to your PC Study Agent to see live readiness, due cards, goals and recommendations.")
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
// Header / banners
// ---------------------------------------------------------------------------

@Composable
private fun DashboardHeader(
    connectionState: ConnectionState,
    freshnessLabel: String,
    refreshing: Boolean,
    audioRoute: com.studyagent.client.core.audio.EffectiveStudyAudioRoute?,
    onRefresh: () -> Unit,
    onNavigateToConnection: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDiagnostics: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Study Agent",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Text(
                    text = freshnessLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            IconButton(onClick = onRefresh) {
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = TextSecondary)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh dashboard", tint = TextSecondary)
                }
            }
            IconButton(onClick = onNavigateToDiagnostics) {
                Icon(Icons.Default.QueryStats, contentDescription = "Diagnostics", tint = TextSecondary)
            }
            IconButton(onClick = onNavigateToSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextSecondary)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConnectionBadge(connectionState = connectionState, onClick = onNavigateToConnection)
            AudioRouteIndicator(route = audioRoute)
        }
    }
}

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
}

@Composable
private fun DashboardErrorPanel(
    error: DashboardError,
    connected: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onConnect: () -> Unit
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
    }
}

// ---------------------------------------------------------------------------
// History panel (§35/§38): chart with range + metric selectors and an
// accessible text fallback.
// ---------------------------------------------------------------------------

private enum class HistoryMetric(val label: String) { CARDS("Cards"), RECALL("Recall"), TIME("Time") }

@Composable
private fun HistoryPanel(
    state: DashboardUiState,
    onRangeSelected: (StatsRange) -> Unit
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
                )
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
                    )
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
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(dayLabel(days.firstOrNull()?.date, 0), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text(dayLabel(days.lastOrNull()?.date, days.size - 1), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(onClick = { showTextList = !showTextList }) {
                Text(if (showTextList) "Show chart" else "Show values", color = TextSecondary)
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
