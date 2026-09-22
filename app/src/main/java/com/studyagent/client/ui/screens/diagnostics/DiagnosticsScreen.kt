package com.studyagent.client.ui.screens.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyagent.client.core.common.LogLevel
import com.studyagent.client.ui.components.AppCard
import com.studyagent.client.ui.components.KeyValueRow
import com.studyagent.client.ui.components.SectionHeader
import com.studyagent.client.ui.components.StudyAgentTopBar
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing
import com.studyagent.client.ui.theme.AppTextStyle

/** Semantics tag for the diagnostics list (tests scroll to rows that are not composed yet). */
const val DIAGNOSTICS_LIST_TEST_TAG = "diagnostics_list"

/**
 * Diagnostics (§43): dense, monospace, engineer-facing. This is the only screen where raw
 * technical detail is shown. Section order: system → TTS → study audio → recognition →
 * session → network/protocol → performance → dashboard/control/persistence → timeline → logs.
 */
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Long-lived flows are collected only while the screen is resumed, so a backgrounded
    // Diagnostics screen stops recomposing behind a running study session.
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val audioDevice by viewModel.activeOutputDevice.collectAsStateWithLifecycle()
    val studyAudioRoute by viewModel.studyAudioRoute.collectAsStateWithLifecycle()
    val ttsHealth by viewModel.ttsHealth.collectAsStateWithLifecycle()
    val recognitionHealth by viewModel.recognitionHealth.collectAsStateWithLifecycle()
    var levelFilter by rememberSaveable { mutableStateOf<LogLevel?>(null) }

    // Memoize per-composition work: the bounded buffer must not be filtered/reversed into
    // new lists on every recomposition.
    val timeline = remember(logs, connectionState) { viewModel.timeline() }
    val filteredLogs = remember(logs, levelFilter) {
        val base = if (levelFilter == null) logs else logs.filter { it.level == levelFilter }
        base.asReversed()
    }
    val reversedTimeline = remember(timeline) { timeline.asReversed() }

    Scaffold(
        containerColor = AppColors.appBackground,
        topBar = {
            StudyAgentTopBar(
                title = "Diagnostics",
                subtitle = "${logs.size} log entries • ${timeline.size} events",
                onBack = onNavigateBack,
                trailing = {
                    Row {
                        // Two explicit actions instead of one giant dump: a short summary that
                        // fits in a chat message, and a detailed export for an investigation.
                        IconButton(onClick = {
                            copyToClipboard(context, "StudyAgent Summary", viewModel.getSummaryText())
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy summary", tint = AppColors.contentPrimary)
                        }
                        IconButton(onClick = {
                            copyToClipboard(context, "StudyAgent Diagnostics", viewModel.getExportText())
                        }) {
                            Icon(Icons.Default.IosShare, contentDescription = "Export detailed diagnostics", tint = AppColors.contentPrimary)
                        }
                        IconButton(onClick = { viewModel.clearLogs() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear logs", tint = AppColors.contentSecondary)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val contentWidth = maxWidth.coerceAtMost(AppSpacing.dashboardMaxWidth)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .width(contentWidth)
                    .align(Alignment.TopCenter)
                    .testTag(DIAGNOSTICS_LIST_TEST_TAG),
                contentPadding = PaddingValues(
                    start = AppSpacing.contentGutter,
                    end = AppSpacing.contentGutter,
                    top = AppSpacing.XS,
                    bottom = AppSpacing.XL
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
            ) {
                item(key = "system") {
                    DiagnosticsRowsCard(
                        "System status",
                        listOf(
                            "Connection" to connectionState.label,
                            "Audio preference" to studyAudioRoute.preference.displayName,
                            "Currently using" to studyAudioRoute.statusLabel,
                            "Audio output" to "${audioDevice.name} (${audioDevice.typeName})",
                            "Audio input" to recognitionHealth.inputRouteLabel
                        )
                    )
                }

                // TTS health: live engine/voice/queue/metrics state.
                item(key = "tts") {
                    val yesNo: (Boolean?) -> String = { v -> v?.let { if (it) "Yes" else "No" } ?: "?" }
                    DiagnosticsRowsCard(
                        "TTS health",
                        buildList {
                            add("Engine" to "${ttsHealth.enginePackage ?: "unknown"} — ${ttsHealth.engineStatus}")
                            add("English voice" to (ttsHealth.englishVoiceDisplay ?: "auto (unresolved)"))
                            add("Arabic voice" to (ttsHealth.arabicVoiceDisplay ?: "auto (unresolved)"))
                            add("Offline ready" to "en=${yesNo(ttsHealth.englishVoiceOffline)} / ar=${yesNo(ttsHealth.arabicVoiceOffline)}")
                            add("Audio focus" to (if (ttsHealth.audioFocusHeld) "Held" else "Released"))
                            add("Queue depth" to ttsHealth.queueDepth.toString())
                            ttsHealth.speakingPurpose?.let { add("Speaking" to it.toString()) }
                            add(
                                "Metrics" to
                                    "ready=${ttsHealth.metrics.timeToReadyMs}ms start=${ttsHealth.metrics.lastRequestToStartMs}ms " +
                                    "ok=${ttsHealth.metrics.completedRequests} fail=${ttsHealth.metrics.failedRequests} " +
                                    "cancel=${ttsHealth.metrics.cancelledRequests}"
                            )
                        },
                        error = ttsHealth.lastError?.let { "${it.code}: ${it.message}" }
                    )
                }

                // Study audio routing: preference, effective mode, output, input, phone-mode counters.
                item(key = "study-audio") {
                    val metrics = viewModel.phoneModeMetrics()
                    DiagnosticsRowsCard(
                        "Study audio",
                        viewModel.studyAudioRows() + listOf(
                            "Route" to studyAudioRoute.statusLabel,
                            "Phone / headset turns" to "${metrics.phoneTurns} / ${metrics.headsetTurns}",
                            "Avg handoff" to (if (metrics.avgHandoffLatencyMs < 0) "—" else "${metrics.avgHandoffLatencyMs}ms")
                        )
                    )
                }

                // Recognition health: real recognizer status, including what could NOT be
                // determined — "Unknown" stays Unknown rather than being coerced to "No".
                item(key = "recognition") {
                    DiagnosticsRowsCard(
                        "Speech recognition",
                        listOf("State" to recognitionHealth.state.label) + viewModel.recognitionRows(),
                        error = recognitionHealth.lastError?.let { "${it.code.name}: ${it.message}" }
                    )
                }

                item(key = "session") { DiagnosticsRowsCard("Session", viewModel.sessionRows()) }
                item(key = "network") { DiagnosticsRowsCard("Network", viewModel.networkRows()) }
                item(key = "protocol") { DiagnosticsRowsCard("Protocol", viewModel.protocolRows()) }
                item(key = "performance") { DiagnosticsRowsCard("Performance", viewModel.performanceRows()) }
                item(key = "dashboard") { DiagnosticsRowsCard("Dashboard", viewModel.dashboardRows()) }
                item(key = "control") { DiagnosticsRowsCard("Study Control", viewModel.controlRows()) }
                item(key = "persistence") { DiagnosticsRowsCard("Persistence", viewModel.persistenceRows()) }

                // AnkiDroid integration (GATE 02): technical facts only — no card content exists
                // in this section, and none is needed (§68).
                item(key = "ankidroid") { DiagnosticsRowsCard("AnkiDroid", viewModel.ankiDroidRows()) }

                // Structured timeline: the ordering evidence for a race.
                item(key = "timeline-header") {
                    SectionHeader(
                        title = "Event timeline (${timeline.size})",
                        modifier = Modifier.padding(top = AppSpacing.XS),
                        action = {
                            IconButton(onClick = { viewModel.clearTimeline() }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Clear event timeline",
                                    tint = AppColors.contentSecondary
                                )
                            }
                        }
                    )
                }
                items(reversedTimeline, key = { event -> "evt-${event.sequence}" }) { event ->
                    AppCard {
                        Column(modifier = Modifier.padding(horizontal = AppSpacing.SM, vertical = AppSpacing.XS)) {
                            Text(
                                text = "${event.formattedTime}  [${event.category.label.uppercase()}]  ${event.event}",
                                style = AppTextStyle.monoValue,
                                color = AppColors.contentPrimary
                            )
                            if (event.metadata.isNotEmpty()) {
                                Text(
                                    text = event.metadata.entries.joinToString("  ") { "${it.key}=${it.value}" },
                                    style = AppTextStyle.monoCaption,
                                    color = AppColors.contentSecondary
                                )
                            }
                        }
                    }
                }

                // Logs header + severity filter.
                item(key = "logs-header") {
                    Column(modifier = Modifier.padding(top = AppSpacing.XS)) {
                        SectionHeader(title = "Logs (${logs.size})")
                        Spacer(modifier = Modifier.height(AppSpacing.XS))
                        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
                            LevelChip("All", levelFilter == null) { levelFilter = null }
                            LevelChip("Errors", levelFilter == LogLevel.ERROR) {
                                levelFilter = if (levelFilter == LogLevel.ERROR) null else LogLevel.ERROR
                            }
                            LevelChip("Warnings", levelFilter == LogLevel.WARN) {
                                levelFilter = if (levelFilter == LogLevel.WARN) null else LogLevel.WARN
                            }
                        }
                    }
                }

                if (filteredLogs.isEmpty()) {
                    item(key = "logs-empty") {
                        Text(
                            text = if (logs.isEmpty()) "No log entries yet." else "No entries at this level.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.contentMuted,
                            modifier = Modifier.padding(vertical = AppSpacing.SM)
                        )
                    }
                }

                // `sequence` is the stable key — a rotating bounded buffer must never rebind a
                // row to a different message.
                items(filteredLogs, key = { log -> log.sequence }) { log ->
                    val (badgeColor, badgeText) = when (log.level) {
                        LogLevel.DEBUG -> AppColors.statusNeutral to "DEBUG"
                        LogLevel.INFO -> AppColors.statusInfo to "INFO"
                        LogLevel.WARN -> AppColors.statusWarning to "WARN"
                        LogLevel.ERROR -> AppColors.statusDanger to "ERROR"
                    }
                    AppCard(color = AppColors.surfacePrimary) {
                        Column(modifier = Modifier.padding(AppSpacing.SM)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(AppShape.chipShape)
                                        .background(badgeColor.copy(alpha = 0.18f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(text = badgeText, style = MaterialTheme.typography.labelSmall, color = badgeColor)
                                }
                                Text(text = log.formattedTime, style = AppTextStyle.monoCaption, color = AppColors.contentMuted)
                            }
                            Spacer(modifier = Modifier.height(AppSpacing.XXS))
                            Text(
                                text = "[${log.tag}] ${log.message}",
                                style = AppTextStyle.monoValue,
                                color = AppColors.contentPrimary
                            )
                            log.throwable?.let { t ->
                                Spacer(modifier = Modifier.height(AppSpacing.XXS))
                                Text(
                                    text = t.message ?: t.javaClass.simpleName,
                                    style = AppTextStyle.monoCaption,
                                    color = AppColors.statusDanger
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AppColors.surfaceInteractive,
            selectedLabelColor = AppColors.actionPrimary,
            labelColor = AppColors.contentSecondary
        ),
        modifier = Modifier.heightIn(min = 48.dp)
    )
}

/** Dense label/value card. Renders nothing for an empty row list. */
@Composable
private fun DiagnosticsRowsCard(
    title: String,
    rows: List<Pair<String, String>>,
    error: String? = null
) {
    if (rows.isEmpty() && error == null) return
    AppCard {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
            SectionHeader(title = title)
            Spacer(modifier = Modifier.height(6.dp))
            rows.forEach { (label, value) ->
                KeyValueRow(label = label, value = value, valueStyle = AppTextStyle.monoValue)
            }
            if (error != null) {
                Spacer(modifier = Modifier.height(AppSpacing.XXS))
                Text(
                    text = "Last error: $error",
                    style = AppTextStyle.monoCaption,
                    color = AppColors.statusDanger
                )
            }
        }
    }
}

/** Copy helper shared by the summary and detailed export actions. */
private fun copyToClipboard(context: Context, label: String, text: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    } catch (t: Throwable) {
        Toast.makeText(context, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
    }
}
