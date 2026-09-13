package com.studyagent.client.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.studyagent.client.core.common.TimeFormatting
import com.studyagent.client.core.models.AiUsageSummary
import com.studyagent.client.core.models.ComponentHealth
import com.studyagent.client.core.models.ComponentHealthEntry
import com.studyagent.client.core.models.ComponentStatus
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.DayStats
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.RatingDistribution
import com.studyagent.client.core.models.RecentPerformance
import com.studyagent.client.core.models.SessionSummaryPayload
import com.studyagent.client.core.models.StudyGoalProgress
import com.studyagent.client.core.models.StudyRecommendation
import com.studyagent.client.core.models.LearningInsight
import com.studyagent.client.core.models.TodayStats
import com.studyagent.client.ui.screens.home.DashboardUiState
import com.studyagent.client.ui.screens.home.PrimaryAction
import com.studyagent.client.ui.screens.home.StartSummary
import com.studyagent.client.ui.theme.AccentTeal
import com.studyagent.client.ui.theme.DarkSurface
import com.studyagent.client.ui.theme.DarkSurfaceElevated
import com.studyagent.client.ui.theme.PrimaryBlue
import com.studyagent.client.ui.theme.RatingAgain
import com.studyagent.client.ui.theme.RatingEasy
import com.studyagent.client.ui.theme.RatingGood
import com.studyagent.client.ui.theme.RatingHard
import com.studyagent.client.ui.theme.StatusAmber
import com.studyagent.client.ui.theme.StatusGreen
import com.studyagent.client.ui.theme.StatusRed
import com.studyagent.client.ui.theme.TextMuted
import com.studyagent.client.ui.theme.TextPrimary
import com.studyagent.client.ui.theme.TextSecondary

// ---------------------------------------------------------------------------
// Shared primitives
// ---------------------------------------------------------------------------

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = AccentTeal,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = accent
                )
                action?.invoke()
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

/** Compact label/value pair used across stats cards. Null values are hidden (§30). */
@Composable
fun StatItem(label: String, value: String?, modifier: Modifier = Modifier, valueColor: Color = TextPrimary) {
    if (value == null) return
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.headlineSmall, color = valueColor)
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

// ---------------------------------------------------------------------------
// System readiness (§20-§22). Anki/LLM status is NEVER inferred from the
// WebSocket connection — only server component_health counts (§21).
// ---------------------------------------------------------------------------

private data class StatusVisual(val icon: ImageVector, val color: Color, val text: String)

private fun statusVisual(status: ComponentStatus, customText: String? = null): StatusVisual = when (status) {
    ComponentStatus.READY -> StatusVisual(Icons.Default.CheckCircle, StatusGreen, customText ?: "Ready")
    ComponentStatus.CONNECTING -> StatusVisual(Icons.Default.HourglassEmpty, StatusAmber, customText ?: "Connecting")
    ComponentStatus.WARNING -> StatusVisual(Icons.Default.Warning, StatusAmber, customText ?: "Warning")
    ComponentStatus.UNAVAILABLE -> StatusVisual(Icons.Default.Cancel, StatusRed, customText ?: "Unavailable")
    ComponentStatus.ERROR -> StatusVisual(Icons.Default.Error, StatusRed, customText ?: "Error")
    ComponentStatus.UNKNOWN -> StatusVisual(Icons.Default.HelpOutline, TextMuted, customText ?: "Unknown")
}

@Composable
private fun HealthRow(label: String, visual: StatusVisual, detail: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics { contentDescription = "$label: ${visual.text}${detail?.let { ". $it" } ?: ""}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = visual.icon,
            contentDescription = null,
            tint = visual.color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.width(110.dp))
        Text(text = visual.text, style = MaterialTheme.typography.bodyMedium, color = visual.color)
        if (detail != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 1
            )
        }
    }
}

@Composable
fun SystemReadinessCard(
    connectionState: ConnectionState,
    health: ComponentHealth?,
    audioRouteLabel: String?,
    serverName: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    // PC Agent readiness IS the transport connection — this is the one row the
    // client may legitimately derive (§20). Everything else comes from the server.
    val pcAgentVisual = when (connectionState) {
        is ConnectionState.Connected -> statusVisual(ComponentStatus.READY)
        is ConnectionState.Connecting, is ConnectionState.Reconnecting -> statusVisual(ComponentStatus.CONNECTING)
        else -> statusVisual(ComponentStatus.UNAVAILABLE, "Not connected")
    }

    fun entryVisual(entry: ComponentHealthEntry?): StatusVisual =
        if (entry == null) statusVisual(ComponentStatus.UNKNOWN)
        else statusVisual(entry.status, entry.status.displayName)

    SectionCard(
        title = "System",
        modifier = modifier,
        action = {
            IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh system health", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    ) {
        HealthRow("PC Agent", pcAgentVisual, detail = serverName)
        HealthRow("Anki", entryVisual(health?.anki), detail = health?.anki?.message)
        HealthRow("AI Evaluator", entryVisual(health?.llm), detail = health?.llm?.message)
        if (audioRouteLabel != null) {
            HealthRow("Audio", statusVisual(ComponentStatus.READY, audioRouteLabel))
        }
        if (health == null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Component health not reported by this Study Agent.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Smart start / active session (§23-§26, §77)
// ---------------------------------------------------------------------------

@Composable
fun SmartStartCard(
    state: DashboardUiState,
    onStart: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val action = state.primaryAction
    val enabled = action == PrimaryAction.START || action == PrimaryAction.CONNECT
    val label = when (action) {
        PrimaryAction.CONNECT -> "Connect"
        PrimaryAction.CONNECTING -> "Connecting…"
        PrimaryAction.START -> startButtonLabel(state.startSummary)
        PrimaryAction.STARTING -> "Starting…"
        PrimaryAction.RESUME_STUDY -> "Resume Study"
        PrimaryAction.RESUME_SESSION -> "Resume Session"
    }
    val subtitle = when {
        action == PrimaryAction.START || action == PrimaryAction.STARTING -> state.startSummary.subtitle
        action == PrimaryAction.CONNECT || action == PrimaryAction.CONNECTING ->
            "Connect to your PC Study Agent"

        else -> null
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Button(
                onClick = {
                    when (action) {
                        PrimaryAction.CONNECT -> onConnect()
                        PrimaryAction.START -> onStart()
                        else -> Unit
                    }
                },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                if (action == PrimaryAction.STARTING || action == PrimaryAction.CONNECTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = TextPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(label.uppercase(), style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            }
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (!state.isConnected && state.snapshot != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Connect to Study Agent first to start studying.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusAmber
                )
            }
        }
    }
}

private fun startButtonLabel(summary: StartSummary): String =
    if (summary.presetName.equals("Custom", ignoreCase = true)) "Start Study"
    else "Start ${summary.presetName}"

@Composable
fun ActiveSessionCard(
    session: SessionSummaryPayload,
    isPaused: Boolean,
    onResume: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    val recallLabel = session.recallRate?.let { TimeFormatting.formatPercent(it) }
    val progress = if (session.totalCards != null && session.totalCards > 0) {
        session.cardsReviewed.toFloat() / session.totalCards
    } else null

    SectionCard(
        title = if (isPaused) "Session Paused" else "Session Active",
        modifier = modifier,
        accent = if (isPaused) StatusAmber else StatusGreen
    ) {
        Text(
            text = session.deck ?: "Study Session",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            StatItem(
                label = "Reviewed",
                value = session.totalCards?.let { "${session.cardsReviewed} / $it" } ?: "${session.cardsReviewed}"
            )
            StatItem(label = "Recall", value = recallLabel, valueColor = AccentTeal)
            StatItem(label = "Time", value = TimeFormatting.formatDurationSeconds(session.elapsedSeconds))
        }
        if (progress != null) {
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = PrimaryBlue,
                trackColor = DarkSurfaceElevated
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onResume,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Resume", color = TextPrimary)
            }
            OutlinedButton(
                onClick = onPause,
                enabled = !isPaused,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Pause, contentDescription = null, tint = TextSecondary)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Pause", color = TextSecondary)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Today (§30/§31)
// ---------------------------------------------------------------------------

@Composable
fun TodayStatsCard(today: TodayStats, modifier: Modifier = Modifier) {
    SectionCard(title = "Today", modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            StatItem("Reviewed", "${today.cardsReviewed}")
            StatItem("New", "${today.newStudied}")
            StatItem("Due left", "${today.dueRemaining}")
            StatItem("Recall", today.recallRate?.let { TimeFormatting.formatPercent(it) }, valueColor = AccentTeal)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            StatItem("Time", TimeFormatting.formatDurationSeconds(today.studyTimeSeconds))
            StatItem("Avg/card", today.avgSecondsPerCard?.let { "${it.toInt()}s" })
        }
        // Daily goal rows only when the server configured one (§31) — never invented.
        val goalCards = today.dailyGoalCards
        val goalMinutes = today.dailyGoalMinutes
        if (goalCards != null && goalCards > 0) {
            Spacer(modifier = Modifier.height(14.dp))
            GoalBar(
                label = "Daily goal",
                current = today.cardsReviewed,
                target = goalCards,
                unit = "cards"
            )
        }
        if (goalMinutes != null && goalMinutes > 0) {
            Spacer(modifier = Modifier.height(10.dp))
            GoalBar(
                label = "Daily time",
                current = (today.studyTimeSeconds / 60).toInt(),
                target = goalMinutes,
                unit = "min"
            )
        }
    }
}

@Composable
private fun GoalBar(label: String, current: Int, target: Int, unit: String) {
    val progress = (current.toFloat() / target).coerceIn(0f, 1f)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(
                text = "$current / $target $unit • ${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = AccentTeal,
            trackColor = DarkSurfaceElevated
        )
    }
}

// ---------------------------------------------------------------------------
// Goal progress (§32/§33). Pace is server-authoritative — Android renders only.
// ---------------------------------------------------------------------------

@Composable
fun GoalProgressCard(goal: StudyGoalProgress, modifier: Modifier = Modifier) {
    val paceLabel = when (goal.paceStatus?.lowercase()) {
        "ahead" -> "Ahead"
        "on_track" -> "On track"
        "behind" -> "Behind"
        else -> null
    }
    val paceColor = when (goal.paceStatus?.lowercase()) {
        "ahead" -> StatusGreen
        "on_track" -> AccentTeal
        "behind" -> StatusAmber
        else -> TextMuted
    }
    val percent = goal.percentComplete

    SectionCard(title = "Goal Progress", modifier = modifier) {
        if (goal.deck != null) {
            Text(text = goal.deck, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (percent != null) {
            LinearProgressIndicator(
                progress = { (percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = PrimaryBlue,
                trackColor = DarkSurfaceElevated
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${percent.toInt()}% complete",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            goal.targetCards?.let { StatItem("Target", "$it") }
            StatItem("Learned", "${goal.learnedCards}")
            goal.remainingCards?.let { StatItem("Remaining", "$it") }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            goal.requiredPerDay?.let { StatItem("Needed/day", "${it.toInt()}") }
            goal.currentPerDay?.let { StatItem("Current/day", "${it.toInt()}") }
            if (paceLabel != null) StatItem("Pace", paceLabel, valueColor = paceColor)
        }
        val targetDate = goal.targetDate?.let { TimeFormatting.formatShortDate(it) }
        val estimated = goal.estimatedCompletionDate?.let { TimeFormatting.formatShortDate(it) }
        if (targetDate != null || estimated != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                if (targetDate != null) StatItem("Target date", targetDate)
                if (estimated != null) StatItem("Est. finish", estimated)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Weekly performance chart (§35-§37). Canvas bars + accessible text summary.
// ---------------------------------------------------------------------------

@Composable
fun WeeklyBars(
    days: List<DayStats>,
    valueSelector: (DayStats) -> Float,
    barColor: Color = PrimaryBlue,
    modifier: Modifier = Modifier,
    labelFor: (DayStats) -> String
) {
    val maxValue = days.maxOfOrNull { valueSelector(it) }?.coerceAtLeast(1f) ?: 1f
    val summary = days.joinToString(", ") { day -> "${labelFor(day)}" }
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .semantics { contentDescription = "Bar chart. $summary" }
    ) {
        val barCount = days.size
        if (barCount == 0) return@Canvas
        val gap = 8.dp.toPx()
        val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(4f)
        days.forEachIndexed { index, day ->
            val value = valueSelector(day).coerceIn(0f, maxValue)
            val barHeight = (value / maxValue) * (size.height - 18f)
            val left = index * (barWidth + gap)
            drawRoundRect(
                color = barColor,
                topLeft = androidx.compose.ui.geometry.Offset(left, size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight.coerceAtLeast(2f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
            )
        }
    }
}

/** Day labels Mon..Sun derived from server dates, falling back to position. */
fun dayLabel(dateIso: String?, index: Int): String {
    if (dateIso != null) {
        val epoch = TimeFormatting.parseIsoToEpochMs(dateIso)
        if (epoch != null) {
            val instant = java.time.Instant.ofEpochMilli(epoch)
            val dow = instant.atZone(java.time.ZoneOffset.UTC).dayOfWeek
            return dow.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        }
    }
    return "Day ${index + 1}"
}

// ---------------------------------------------------------------------------
// Rating distribution (§38)
// ---------------------------------------------------------------------------

@Composable
fun RatingDistributionCard(
    distribution: RatingDistribution?,
    rangeLabel: String,
    modifier: Modifier = Modifier
) {
    SectionCard(title = "Ratings • $rangeLabel", modifier = modifier) {
        if (distribution == null || distribution.total == 0) {
            Text("No ratings recorded for this range.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            return@SectionCard
        }
        RatingRow("Again", distribution.again, distribution.percentOf(Rating.AGAIN), RatingAgain)
        RatingRow("Hard", distribution.hard, distribution.percentOf(Rating.HARD), RatingHard)
        RatingRow("Good", distribution.good, distribution.percentOf(Rating.GOOD), RatingGood)
        RatingRow("Easy", distribution.easy, distribution.percentOf(Rating.EASY), RatingEasy)
    }
}

@Composable
private fun RatingRow(label: String, count: Int, percent: Int, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics { contentDescription = "$label: $count ($percent%)" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.width(56.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(DarkSurfaceElevated)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent / 100f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text("$count", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

// ---------------------------------------------------------------------------
// Learning insight (§39). Rendered with generation time — never generated on open.
// ---------------------------------------------------------------------------

@Composable
fun LearningInsightCard(
    insight: LearningInsight,
    nowMs: Long,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val generatedLabel = insight.generatedAt
        ?.let { TimeFormatting.parseIsoToEpochMs(it) }
        ?.let { "Updated ${TimeFormatting.formatRelativeTime(it, nowMs)}" }

    SectionCard(
        title = "Weak Area",
        modifier = modifier,
        accent = StatusAmber,
        action = {
            IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh insight", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    ) {
        val topic = listOfNotNull(insight.weakTopic, insight.weakSubtopic).joinToString(" → ")
        if (topic.isNotEmpty()) {
            Text(topic, style = MaterialTheme.typography.titleSmall, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
        }
        insight.recallRate?.let {
            Text("Recall ${TimeFormatting.formatPercent(it)}", style = MaterialTheme.typography.bodyMedium, color = StatusAmber)
            Spacer(modifier = Modifier.height(6.dp))
        }
        if (insight.missedPoints.isNotEmpty()) {
            Text("Repeatedly missed:", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            insight.missedPoints.forEach { point ->
                Text("• $point", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
        insight.advice?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        }
        if (generatedLabel != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(generatedLabel, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
    }
}

// ---------------------------------------------------------------------------
// Recommendation (§41/§42). Advisory only — applying it updates the draft.
// ---------------------------------------------------------------------------

@Composable
fun RecommendationCard(
    recommendation: StudyRecommendation,
    onUse: () -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(title = "Recommended", modifier = modifier, accent = PrimaryBlue) {
        recommendation.recommendedMode?.let { mode ->
            Text(
                text = com.studyagent.client.core.models.StudyMode.fromWire(mode).displayName,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }
        recommendation.recommendedDeck?.let { deck ->
            Spacer(modifier = Modifier.height(4.dp))
            Text("Deck: $deck", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        val sizeBits = listOfNotNull(
            recommendation.estimatedCards?.let { "~$it cards" },
            recommendation.estimatedMinutes?.let { "~$it min" }
        )
        if (sizeBits.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(sizeBits.joinToString(" • "), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        recommendation.reason?.let { reason ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(reason, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onUse,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("USE RECOMMENDATION", color = PrimaryBlue)
        }
    }
}

// ---------------------------------------------------------------------------
// AI usage (§43/§44). Only rendered when the server advertises the capability;
// cost is always server-authoritative — "Cost unavailable" instead of $0.00.
// ---------------------------------------------------------------------------

@Composable
fun AiUsageCard(usage: AiUsageSummary?, modifier: Modifier = Modifier) {
    SectionCard(title = "AI Usage", modifier = modifier, accent = com.studyagent.client.ui.theme.StatusPurple) {
        if (usage == null) {
            Text("No usage data reported.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            return@SectionCard
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            StatItem("Evaluations", TimeFormatting.formatCompactNumber(usage.evaluations.toLong()))
            StatItem("Input tok", TimeFormatting.formatCompactNumber(usage.inputTokens))
            StatItem("Output tok", TimeFormatting.formatCompactNumber(usage.outputTokens))
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            val costLabel = usage.estimatedCost?.let { cost ->
                val currency = usage.currency ?: "$"
                String.format(java.util.Locale.US, "%s%.2f", currency, cost)
            } ?: "Cost unavailable"
            StatItem("Est. cost", costLabel)
            usage.range?.let { StatItem("Range", it.replace("_", " ")) }
        }
    }
}

// ---------------------------------------------------------------------------
// Capability gate / empty & error panels (§11/§87/§88)
// ---------------------------------------------------------------------------

@Composable
fun UnsupportedPanel(feature: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Text(
            text = "$feature: not supported by this Study Agent",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
fun InfoPanel(text: String, modifier: Modifier = Modifier, color: Color = TextMuted) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.padding(16.dp)
        )
    }
}
