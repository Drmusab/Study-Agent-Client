package com.studyagent.client.ui.screens.home.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.studyagent.client.core.common.TimeFormatting
import com.studyagent.client.core.models.AiUsageSummary
import com.studyagent.client.core.models.ComponentHealth
import com.studyagent.client.core.models.ComponentHealthEntry
import com.studyagent.client.core.models.ComponentStatus
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.DayStats
import com.studyagent.client.core.models.LearningInsight
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.RatingDistribution
import com.studyagent.client.core.models.SessionSummaryPayload
import com.studyagent.client.core.models.StudyGoalProgress
import com.studyagent.client.core.models.StudyMode
import com.studyagent.client.core.models.StudyRecommendation
import com.studyagent.client.core.models.TodayStats
import com.studyagent.client.ui.components.AppCard
import com.studyagent.client.ui.components.AppHeroCard
import com.studyagent.client.ui.components.MetricTile
import com.studyagent.client.ui.components.PrimaryButton
import com.studyagent.client.ui.components.SecondaryButton
import com.studyagent.client.ui.components.SectionHeader
import com.studyagent.client.ui.screens.home.DashboardUiState
import com.studyagent.client.ui.screens.home.PrimaryAction
import com.studyagent.client.ui.screens.home.StartSummary
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppSpacing

// ---------------------------------------------------------------------------
// Shared primitives
// ---------------------------------------------------------------------------

/**
 * Titled card: uppercase section header + optional trailing action + content.
 * [accent] colours the header only — never the card itself (§16).
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = AppColors.contentSecondary,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    AppCard(modifier = modifier) {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
            SectionHeader(title = title, color = accent, action = action)
            Spacer(modifier = Modifier.height(AppSpacing.SM))
            content()
        }
    }
}

/** Compact label/value pair used across stats cards. Null values are hidden (§30). */
@Composable
fun StatItem(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    valueColor: Color = AppColors.contentPrimary
) {
    MetricTile(value = value, label = label, modifier = modifier, valueColor = valueColor)
}

/** Thin 6dp progress track shared by goal / session cards. */
@Composable
private fun ThinProgress(progress: Float, color: Color = AppColors.actionPrimary) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
        color = color,
        trackColor = AppColors.surfaceElevated
    )
}

// ---------------------------------------------------------------------------
// System readiness (§20-§22). Anki/LLM status is NEVER inferred from the
// WebSocket connection — only server component_health counts.
// ---------------------------------------------------------------------------

private data class StatusVisual(val icon: ImageVector, val color: Color, val text: String)

private fun statusVisual(status: ComponentStatus, customText: String? = null): StatusVisual = when (status) {
    ComponentStatus.READY -> StatusVisual(Icons.Default.CheckCircle, AppColors.statusSuccess, customText ?: "Ready")
    ComponentStatus.CONNECTING -> StatusVisual(Icons.Default.HourglassEmpty, AppColors.statusWarning, customText ?: "Connecting")
    ComponentStatus.WARNING -> StatusVisual(Icons.Default.Warning, AppColors.statusWarning, customText ?: "Warning")
    ComponentStatus.UNAVAILABLE -> StatusVisual(Icons.Default.Cancel, AppColors.statusDanger, customText ?: "Unavailable")
    ComponentStatus.ERROR -> StatusVisual(Icons.Default.Error, AppColors.statusDanger, customText ?: "Error")
    ComponentStatus.UNKNOWN -> StatusVisual(Icons.Default.HelpOutline, AppColors.statusNeutral, customText ?: "Unknown")
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
        Spacer(modifier = Modifier.width(AppSpacing.SM))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.contentPrimary,
            modifier = Modifier.width(110.dp)
        )
        Text(text = visual.text, style = MaterialTheme.typography.bodyMedium, color = visual.color)
        if (detail != null) {
            Spacer(modifier = Modifier.width(AppSpacing.XS))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.contentMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false)
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
    // PC Agent readiness IS the transport connection — the one row the client may derive.
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
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh system health",
                    tint = AppColors.contentSecondary,
                    modifier = Modifier.size(18.dp)
                )
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
            Spacer(modifier = Modifier.height(AppSpacing.XXS))
            Text(
                text = "Component health not reported by this Study Agent.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.contentMuted
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Smart start / active session (§23-§26)
// ---------------------------------------------------------------------------

/** The single dominant action on the Dashboard: one hero card, one tall button. */
@Composable
fun SmartStartCard(
    state: DashboardUiState,
    onStart: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val action = state.primaryAction
    val enabled = action == PrimaryAction.START || action == PrimaryAction.CONNECT
    val loading = action == PrimaryAction.STARTING || action == PrimaryAction.CONNECTING
    val label = when (action) {
        PrimaryAction.CONNECT -> "Connect"
        PrimaryAction.CONNECTING -> "Connecting…"
        PrimaryAction.START -> startButtonLabel(state.startSummary)
        PrimaryAction.STARTING -> "Starting…"
        PrimaryAction.RESUME_STUDY -> "Resume Study"
        PrimaryAction.RESUME_SESSION -> "Resume Session"
    }
    val subtitle = when (action) {
        PrimaryAction.START, PrimaryAction.STARTING -> state.startSummary.subtitle.ifBlank { null }
        PrimaryAction.CONNECT, PrimaryAction.CONNECTING -> "Connect to your PC Study Agent to begin."
        else -> null
    }

    AppHeroCard(modifier = modifier) {
        Column(modifier = Modifier.padding(AppSpacing.heroCardPadding)) {
            Text(
                text = if (action == PrimaryAction.CONNECT || action == PrimaryAction.CONNECTING) "Get connected" else "Ready to study",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.contentPrimary
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(AppSpacing.XXS))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.contentSecondary
                )
            }
            Spacer(modifier = Modifier.height(AppSpacing.MD))
            PrimaryButton(
                text = label,
                onClick = {
                    when (action) {
                        PrimaryAction.CONNECT -> onConnect()
                        PrimaryAction.START -> onStart()
                        else -> Unit
                    }
                },
                enabled = enabled,
                loading = loading,
                icon = Icons.Default.PlayArrow,
                tall = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "$label. Primary action" }
            )
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
    val progress = session.totalCards?.takeIf { it > 0 }?.let { session.cardsReviewed.toFloat() / it }

    AppHeroCard(modifier = modifier) {
        Column(modifier = Modifier.padding(AppSpacing.heroCardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isPaused) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = if (isPaused) AppColors.statusWarning else AppColors.statusSuccess,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isPaused) "Session paused" else "Session in progress",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isPaused) AppColors.statusWarning else AppColors.statusSuccess
                )
            }
            Spacer(modifier = Modifier.height(AppSpacing.XS))
            Text(
                text = session.deck ?: "Study Session",
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.contentPrimary
            )
            Spacer(modifier = Modifier.height(AppSpacing.SM))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                StatItem(
                    label = "Reviewed",
                    value = session.totalCards?.let { "${session.cardsReviewed} / $it" } ?: "${session.cardsReviewed}"
                )
                StatItem(label = "Recall", value = recallLabel, valueColor = AppColors.actionAccent)
                StatItem(label = "Time", value = TimeFormatting.formatDurationSeconds(session.elapsedSeconds))
            }
            if (progress != null) {
                Spacer(modifier = Modifier.height(AppSpacing.SM))
                ThinProgress(progress)
            }
            Spacer(modifier = Modifier.height(AppSpacing.MD))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
                PrimaryButton(
                    text = "Resume",
                    onClick = onResume,
                    icon = Icons.Default.PlayArrow,
                    modifier = Modifier.weight(1f)
                )
                SecondaryButton(
                    text = "Pause",
                    onClick = onPause,
                    enabled = !isPaused,
                    icon = Icons.Default.Pause,
                    modifier = Modifier.weight(1f)
                )
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
            StatItem("Recall", today.recallRate?.let { TimeFormatting.formatPercent(it) }, valueColor = AppColors.actionAccent)
        }
        Spacer(modifier = Modifier.height(AppSpacing.SM))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            StatItem("Time", TimeFormatting.formatDurationSeconds(today.studyTimeSeconds))
            StatItem("Avg/card", today.avgSecondsPerCard?.let { "${it.toInt()}s" })
        }
        // Daily goal rows only when the server configured one (§31) — never invented.
        val goalCards = today.dailyGoalCards
        val goalMinutes = today.dailyGoalMinutes
        if (goalCards != null && goalCards > 0) {
            Spacer(modifier = Modifier.height(AppSpacing.MD))
            GoalBar(label = "Daily goal", current = today.cardsReviewed, target = goalCards, unit = "cards")
        }
        if (goalMinutes != null && goalMinutes > 0) {
            Spacer(modifier = Modifier.height(AppSpacing.SM))
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = AppColors.contentSecondary)
            Text(
                text = "$current / $target $unit • ${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.contentPrimary
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        ThinProgress(progress, color = AppColors.actionAccent)
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
        "ahead" -> AppColors.statusSuccess
        "on_track" -> AppColors.actionAccent
        "behind" -> AppColors.statusWarning
        else -> AppColors.contentMuted
    }
    val percent = goal.percentComplete

    SectionCard(title = "Goal Progress", modifier = modifier) {
        if (goal.deck != null) {
            Text(text = goal.deck, style = MaterialTheme.typography.titleSmall, color = AppColors.contentPrimary)
            Spacer(modifier = Modifier.height(AppSpacing.XS))
        }
        if (percent != null) {
            ThinProgress((percent / 100.0).toFloat())
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${percent.toInt()}% complete",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.contentSecondary
            )
            Spacer(modifier = Modifier.height(AppSpacing.SM))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            goal.targetCards?.let { StatItem("Target", "$it") }
            StatItem("Learned", "${goal.learnedCards}")
            goal.remainingCards?.let { StatItem("Remaining", "$it") }
        }
        Spacer(modifier = Modifier.height(AppSpacing.SM))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            goal.requiredPerDay?.let { StatItem("Needed/day", "${it.toInt()}") }
            goal.currentPerDay?.let { StatItem("Current/day", "${it.toInt()}") }
            if (paceLabel != null) StatItem("Pace", paceLabel, valueColor = paceColor)
        }
        val targetDate = goal.targetDate?.let { TimeFormatting.formatShortDate(it) }
        val estimated = goal.estimatedCompletionDate?.let { TimeFormatting.formatShortDate(it) }
        if (targetDate != null || estimated != null) {
            Spacer(modifier = Modifier.height(AppSpacing.SM))
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
    barColor: Color = AppColors.actionPrimary,
    modifier: Modifier = Modifier,
    labelFor: (DayStats) -> String
) {
    val maxValue = days.maxOfOrNull { valueSelector(it) }?.coerceAtLeast(1f) ?: 1f
    val summary = days.joinToString(", ") { day -> labelFor(day) }
    Canvas(
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
                topLeft = Offset(left, size.height - barHeight),
                size = Size(barWidth, barHeight.coerceAtLeast(2f)),
                cornerRadius = CornerRadius(4f, 4f)
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
        RatingDistributionRows(distribution)
    }
}

/** Distribution rows without a card, for embedding in another card. */
@Composable
fun RatingDistributionRows(distribution: RatingDistribution?) {
    if (distribution == null || distribution.total == 0) {
        Text("No ratings recorded for this range.", style = MaterialTheme.typography.bodySmall, color = AppColors.contentMuted)
        return
    }
    RatingRow("Again", distribution.again, distribution.percentOf(Rating.AGAIN), AppColors.ratingAgainText)
    RatingRow("Hard", distribution.hard, distribution.percentOf(Rating.HARD), AppColors.ratingHardText)
    RatingRow("Good", distribution.good, distribution.percentOf(Rating.GOOD), AppColors.ratingGoodText)
    RatingRow("Easy", distribution.easy, distribution.percentOf(Rating.EASY), AppColors.ratingEasyText)
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
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.contentPrimary,
            modifier = Modifier.width(56.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(AppColors.surfaceElevated)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((percent / 100f).coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.SM))
        Text("$count", style = MaterialTheme.typography.bodySmall, color = AppColors.contentSecondary)
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
        accent = AppColors.statusWarning,
        action = {
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh insight",
                    tint = AppColors.contentSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    ) {
        val topic = listOfNotNull(insight.weakTopic, insight.weakSubtopic).joinToString(" → ")
        if (topic.isNotEmpty()) {
            Text(topic, style = MaterialTheme.typography.titleSmall, color = AppColors.contentPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
        }
        insight.recallRate?.let {
            Text("Recall ${TimeFormatting.formatPercent(it)}", style = MaterialTheme.typography.bodyMedium, color = AppColors.statusWarning)
            Spacer(modifier = Modifier.height(6.dp))
        }
        if (insight.missedPoints.isNotEmpty()) {
            Text("Repeatedly missed:", style = MaterialTheme.typography.bodySmall, color = AppColors.contentSecondary)
            insight.missedPoints.forEach { point ->
                Text("• $point", style = MaterialTheme.typography.bodySmall, color = AppColors.contentSecondary)
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
        insight.advice?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.contentPrimary)
        }
        if (generatedLabel != null) {
            Spacer(modifier = Modifier.height(AppSpacing.XS))
            Text(generatedLabel, style = MaterialTheme.typography.labelSmall, color = AppColors.contentMuted)
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
    SectionCard(title = "Recommended", modifier = modifier, accent = AppColors.actionPrimary) {
        recommendation.recommendedMode?.let { mode ->
            Text(
                text = StudyMode.fromWire(mode).displayName,
                style = MaterialTheme.typography.titleSmall,
                color = AppColors.contentPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }
        recommendation.recommendedDeck?.let { deck ->
            Spacer(modifier = Modifier.height(AppSpacing.XXS))
            Text("Deck: $deck", style = MaterialTheme.typography.bodyMedium, color = AppColors.contentSecondary)
        }
        val sizeBits = listOfNotNull(
            recommendation.estimatedCards?.let { "~$it cards" },
            recommendation.estimatedMinutes?.let { "~$it min" }
        )
        if (sizeBits.isNotEmpty()) {
            Spacer(modifier = Modifier.height(AppSpacing.XXS))
            Text(sizeBits.joinToString(" • "), style = MaterialTheme.typography.bodyMedium, color = AppColors.contentSecondary)
        }
        recommendation.reason?.let { reason ->
            Spacer(modifier = Modifier.height(AppSpacing.XS))
            Text(reason, style = MaterialTheme.typography.bodySmall, color = AppColors.contentMuted)
        }
        Spacer(modifier = Modifier.height(AppSpacing.SM))
        SecondaryButton(
            text = "Use Recommendation",
            onClick = onUse,
            contentColor = AppColors.actionPrimary,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ---------------------------------------------------------------------------
// AI usage (§43/§44). Only rendered when the server advertises the capability;
// cost is always server-authoritative — "Cost unavailable" instead of $0.00.
// ---------------------------------------------------------------------------

@Composable
fun AiUsageCard(usage: AiUsageSummary?, modifier: Modifier = Modifier) {
    SectionCard(title = "AI Usage", modifier = modifier, accent = AppColors.statusAccentAi) {
        if (usage == null) {
            Text("No usage data reported.", style = MaterialTheme.typography.bodySmall, color = AppColors.contentMuted)
            return@SectionCard
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            StatItem("Evaluations", TimeFormatting.formatCompactNumber(usage.evaluations.toLong()))
            StatItem("Input tok", TimeFormatting.formatCompactNumber(usage.inputTokens))
            StatItem("Output tok", TimeFormatting.formatCompactNumber(usage.outputTokens))
        }
        Spacer(modifier = Modifier.height(AppSpacing.SM))
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
// Capability gate / info panels (§11/§87/§88)
// ---------------------------------------------------------------------------

@Composable
fun UnsupportedPanel(feature: String, modifier: Modifier = Modifier) {
    InfoPanel(text = "$feature: not supported by this Study Agent", modifier = modifier)
}

@Composable
fun InfoPanel(text: String, modifier: Modifier = Modifier, color: Color = AppColors.contentMuted) {
    AppCard(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.padding(AppSpacing.cardPadding)
        )
    }
}

// ---------------------------------------------------------------------------
// Previews (static data only)
// ---------------------------------------------------------------------------

@Preview(name = "Smart start — ready", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun SmartStartPreview() {
    SmartStartCard(
        state = DashboardUiState(
            connectionState = ConnectionState.Connected("pc", 8765, "Study PC"),
            primaryAction = PrimaryAction.START,
            startSummary = StartSummary(presetName = "Quick", modeLabel = "Due reviews", targetLabel = "20 cards", deckLabel = "Cardiology")
        ),
        onStart = {},
        onConnect = {}
    )
}

@Preview(name = "Active session", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun ActiveSessionPreview() {
    ActiveSessionCard(
        session = SessionSummaryPayload(deck = "Cardiology", cardsReviewed = 12, totalCards = 20, recallRate = 0.83, elapsedSeconds = 540),
        isPaused = false,
        onResume = {},
        onPause = {}
    )
}

@Preview(name = "Today", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun TodayPreview() {
    TodayStatsCard(TodayStats(cardsReviewed = 34, newStudied = 6, dueRemaining = 18, recallRate = 0.79, studyTimeSeconds = 1500))
}
