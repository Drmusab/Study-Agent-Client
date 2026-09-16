package com.studyagent.client.ui.screens.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyagent.client.core.common.LogLevel
import com.studyagent.client.ui.theme.AccentTeal
import com.studyagent.client.ui.theme.DarkBackground
import com.studyagent.client.ui.theme.DarkSurface
import com.studyagent.client.ui.theme.DarkSurfaceElevated
import com.studyagent.client.ui.theme.PrimaryBlue
import com.studyagent.client.ui.theme.StatusAmber
import com.studyagent.client.ui.theme.StatusGreen
import com.studyagent.client.ui.theme.StatusRed
import com.studyagent.client.ui.theme.TextMuted
import com.studyagent.client.ui.theme.TextPrimary
import com.studyagent.client.ui.theme.TextSecondary
import com.studyagent.client.ui.components.AppCard
import com.studyagent.client.ui.components.StudyAgentTopBar
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing
import com.studyagent.client.ui.theme.AppTextStyle

/** Semantics tag for the diagnostics list (tests scroll to rows that are not composed yet). */
const val DIAGNOSTICS_LIST_TEST_TAG = "diagnostics_list"

@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // §91: long-lived flows are collected only while the screen is resumed, so a backgrounded
    // Diagnostics screen stops recomposing behind a running study session.
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val audioDevice by viewModel.activeOutputDevice.collectAsStateWithLifecycle()
    val studyAudioRoute by viewModel.studyAudioRoute.collectAsStateWithLifecycle()
    val ttsHealth by viewModel.ttsHealth.collectAsStateWithLifecycle()
    val recognitionHealth by viewModel.recognitionHealth.collectAsStateWithLifecycle()
    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }

        // Memoize the per-composition work (§86): the bounded buffer must not be
    // filtered/reversed into new lists on every recomposition.
    val timeline = remember(logs, connectionState) { viewModel.timeline() }
    val filteredLogs = remember(logs, levelFilter) {
        if (levelFilter == null) logs else logs.filter { it.level == levelFilter }
    }

    Scaffold(
        containerColor = DarkBackground,
        containerColor = AppColors.appBackground,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                    Text(
                        text = "Diagnostics & Logs",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                }

                Row {
                    // §88: two explicit actions instead of one giant dump — a short summary that
                    // fits in a chat message, and a detailed export for a real investigation.
                    IconButton(onClick = {
                        copyToClipboard(context, "StudyAgent Summary", viewModel.getSummaryText())
                    }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy summary",
                            tint = AccentTeal
                        )
                    }
                    IconButton(onClick = {
                        copyToClipboard(context, "StudyAgent Diagnostics", viewModel.getExportText())
                    }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Export detailed diagnostics",
                            tint = AccentTeal
                        )
                    }
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear logs", tint = TextMuted)
            StudyAgentTopBar(
                title = "Diagnostics & Logs",
                onBack = onNavigateBack,
                trailing = {
                    Row {
                        // §88: two explicit actions instead of one giant dump — a short summary
                        // that fits in a chat message, and a detailed export for investigation.
                        IconButton(onClick = {
                            copyToClipboard(context, "StudyAgent Summary", viewModel.getSummaryText())
                        }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy summary",
                                tint = AppColors.voiceSpeaking
                            )
                        }
                        IconButton(onClick = {
                            copyToClipboard(context, "StudyAgent Diagnostics", viewModel.getExportText())
                        }) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "Export detailed diagnostics",
                                tint = AppColors.voiceSpeaking
                            )
                        }
                        IconButton(onClick = { viewModel.clearLogs() }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Clear logs",
                                tint = AppColors.contentMuted
                            )
                        }
                    }
                }
            }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .testTag(DIAGNOSTICS_LIST_TEST_TAG),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Summary banner
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "SYSTEM STATUS", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = "Connection: ${connectionState.label}", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text(text = "Audio Preference: ${studyAudioRoute.preference.displayName}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Text(text = "Currently using: ${studyAudioRoute.statusLabel}", style = MaterialTheme.typography.bodyMedium, color = AccentTeal)
                        Text(text = "Audio Output: ${audioDevice.name} (${audioDevice.typeName})", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Text(text = "Audio Input: ${recognitionHealth.inputRouteLabel}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
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
                // Summary banner
                item {
                    DiagnosticsRowsCard("System status", buildList {
                        add("Connection" to connectionState.label)
                        add("Audio preference" to studyAudioRoute.preference.displayName)
                        add("Currently using" to studyAudioRoute.statusLabel)
                        add("Audio output" to "${audioDevice.name} (${audioDevice.typeName})")
                        add("Audio input" to recognitionHealth.inputRouteLabel)
                    })
                }
            }

            // TTS health card (§50): live engine/voice/queue/metrics state.
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "TTS HEALTH", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Engine: ${ttsHealth.enginePackage ?: "unknown"} — ${ttsHealth.engineStatus}",
                            style = MaterialTheme.typography.bodyMedium, color = TextPrimary
                        )
                        Text(
                            text = "English Voice: ${ttsHealth.englishVoiceDisplay ?: "auto (unresolved)"}",
                            style = MaterialTheme.typography.bodyMedium, color = TextSecondary
                        )
                        Text(
                            text = "Arabic Voice: ${ttsHealth.arabicVoiceDisplay ?: "auto (unresolved)"}",
                            style = MaterialTheme.typography.bodyMedium, color = TextSecondary
                        )
                        Text(
                            text = "Offline Ready: en=${ttsHealth.englishVoiceOffline?.let { if (it) "Yes" else "No" } ?: "?"} / ar=${ttsHealth.arabicVoiceOffline?.let { if (it) "Yes" else "No" } ?: "?"}",
                            style = MaterialTheme.typography.bodyMedium, color = TextSecondary
                // TTS health card (§50): live engine/voice/queue/metrics state.
                item {
                    DiagnosticsRowsCard("TTS health", buildList {
                        add("Engine" to "${ttsHealth.enginePackage ?: "unknown"} — ${ttsHealth.engineStatus}")
                        add("English voice" to (ttsHealth.englishVoiceDisplay ?: "auto (unresolved)"))
                        add("Arabic voice" to (ttsHealth.arabicVoiceDisplay ?: "auto (unresolved)"))
                        add(
                            "Offline ready" to
                                "en=${ttsHealth.englishVoiceOffline?.let { if (it) "Yes" else "No" } ?: "?"} / " +
                                    "ar=${ttsHealth.arabicVoiceOffline?.let { if (it) "Yes" else "No" } ?: "?"}"
                        )
                        Text(
                            text = "Audio Focus: ${if (ttsHealth.audioFocusHeld) "Held" else "Released"}   Queue: ${ttsHealth.queueDepth}",
                            style = MaterialTheme.typography.bodyMedium, color = TextSecondary
                        add("Audio focus" to (if (ttsHealth.audioFocusHeld) "Held" else "Released"))
                        add("Queue depth" to ttsHealth.queueDepth.toString())
                        add(
                            "Metrics" to
                                "ready=${ttsHealth.metrics.timeToReadyMs}ms, start=${ttsHealth.metrics.lastRequestToStartMs}ms, " +
                                    "ok=${ttsHealth.metrics.completedRequests}, fail=${ttsHealth.metrics.failedRequests}, " +
                                    "cancel=${ttsHealth.metrics.cancelledRequests}"
                        )
                        Text(
                            text = "Metrics: ready=${ttsHealth.metrics.timeToReadyMs}ms, start=${ttsHealth.metrics.lastRequestToStartMs}ms, " +
                                "ok=${ttsHealth.metrics.completedRequests}, fail=${ttsHealth.metrics.failedRequests}, cancel=${ttsHealth.metrics.cancelledRequests}",
                            style = MaterialTheme.typography.bodySmall, color = TextMuted
                        )
                        Text(
                            text = "Last Error: ${ttsHealth.lastError?.let { "${it.code}: ${it.message}" } ?: "none"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ttsHealth.lastError != null) StatusRed else StatusGreen
                        )
                    }
                }
            }

            // Study audio routing card (§67): preference, effective mode, output, input,
            // external headset, acoustic profile, plus local Phone Mode counters.
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "STUDY AUDIO", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    })
                    val lastError = ttsHealth.lastError
                    if (lastError != null) {
                        AppCard {
                            Text(
                                text = studyAudioRoute.statusLabelWithIcon,
                                style = MaterialTheme.typography.labelMedium,
                                color = AccentTeal
                                text = "Last error: ${lastError.code}: ${lastError.message}",
                                style = AppTextStyle.monoCaption,
                                color = AppColors.statusDanger,
                                modifier = Modifier.padding(horizontal = AppSpacing.cardPadding, vertical = AppSpacing.XS)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        viewModel.studyAudioRows().forEach { (label, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = label, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                Text(text = value, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                            }
                        }
                        val metrics = viewModel.phoneModeMetrics()
                        Text(
                            text = "Phone turns=${metrics.phoneTurns} headset turns=${metrics.headsetTurns} " +
                                "handoff=${if (metrics.avgHandoffLatencyMs < 0) "-" else "${metrics.avgHandoffLatencyMs}ms"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            }

            // Recognition health card: real recognizer status, including what could NOT be
            // determined. "Unknown" is reported as Unknown rather than coerced to "No".
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "SPEECH RECOGNITION", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                // Study audio routing card (§67): preference, effective mode, output, input,
                // external headset, acoustic profile, plus local Phone Mode counters.
                item {
                    DiagnosticsRowsCard(
                        "Study audio",
                        viewModel.studyAudioRows() + listOf(
                            "Route" to studyAudioRoute.statusLabelWithIcon,
                            "Phone turns / headset turns / handoff" to
                                run {
                                    val metrics = viewModel.phoneModeMetrics()
                                    "${metrics.phoneTurns} / ${metrics.headsetTurns} / " +
                                        (if (metrics.avgHandoffLatencyMs < 0) "-" else "${metrics.avgHandoffLatencyMs}ms")
                                }
                        )
                    )
                }

                // Recognition health card: real recognizer status, including what could NOT be
                // determined. "Unknown" is reported as Unknown rather than coerced to "No".
                item {
                    DiagnosticsRowsCard("Speech recognition", viewModel.recognitionRows())
                    val recError = recognitionHealth.lastError
                    if (recError != null) {
                        AppCard {
                            Text(
                                text = recognitionHealth.state.label.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (recognitionHealth.state.isActive) StatusAmber else StatusGreen
                                text = "Last error: ${recError.code.name}: ${recError.message}",
                                style = AppTextStyle.monoCaption,
                                color = AppColors.statusDanger,
                                modifier = Modifier.padding(horizontal = AppSpacing.cardPadding, vertical = AppSpacing.XS)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        viewModel.recognitionRows().forEach { (label, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                            }
                        }
                        Text(
                            text = "Last Error: ${recognitionHealth.lastError?.let { "${it.code.name}: ${it.message}" } ?: "none"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (recognitionHealth.lastError != null) StatusRed else StatusGreen
                        )
                    }
                }
            }

            // Session identity and turn (§59) — the first thing to look at when a turn stalls.
            item { DiagnosticsRowsCard("SESSION", viewModel.sessionRows()) }
                // Session identity and turn (§59) — the first thing to look at when a turn stalls.
                item { DiagnosticsRowsCard("Session", viewModel.sessionRows()) }

            // Network + protocol (§60/§61). No token, no raw frame.
            item { DiagnosticsRowsCard("NETWORK", viewModel.networkRows()) }
            item { DiagnosticsRowsCard("PROTOCOL", viewModel.protocolRows()) }
                // Network + protocol (§60/§61). No token, no raw frame.
                item { DiagnosticsRowsCard("Network", viewModel.networkRows()) }
                item { DiagnosticsRowsCard("Protocol", viewModel.protocolRows()) }

            // Performance (§65): p50/p95 per measured family, failure rates with denominators,
            // queue depths and memory where the platform reports it.
            item { DiagnosticsRowsCard("PERFORMANCE", viewModel.performanceRows()) }
                // Performance (§65): p50/p95 per measured family, failure rates with denominators,
                // queue depths and memory where the platform reports it.
                item { DiagnosticsRowsCard("Performance", viewModel.performanceRows()) }

            item { DiagnosticsRowsCard("DASHBOARD", viewModel.dashboardRows()) }
            item { DiagnosticsRowsCard("CONTROL CENTER", viewModel.controlRows()) }
            item { DiagnosticsRowsCard("PERSISTENCE", viewModel.persistenceRows()) }
                item { DiagnosticsRowsCard("Dashboard", viewModel.dashboardRows()) }
                item { DiagnosticsRowsCard("Control Center", viewModel.controlRows()) }
                item { DiagnosticsRowsCard("Persistence", viewModel.persistenceRows()) }

            // Structured timeline (§67): the ordering evidence for a race.
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "EVENT TIMELINE (${viewModel.timeline().size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentTeal
                    )
                    IconButton(onClick = { viewModel.clearTimeline() }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Clear event timeline",
                            tint = TextMuted
                // Structured timeline (§67): the ordering evidence for a race.
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "EVENT TIMELINE (${timeline.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = AppColors.voiceSpeaking
                        )
                        IconButton(onClick = { viewModel.clearTimeline() }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Clear event timeline",
                                tint = AppColors.contentMuted
                            )
                        }
                    }
                }
            }

            items(viewModel.timeline().asReversed(), key = { event -> "evt-${event.sequence}" }) { event ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            text = "${event.formattedTime}  [${event.category.label.uppercase()}]  ${event.event}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = TextPrimary
                        )
                        if (event.metadata.isNotEmpty()) {
                items(timeline.asReversed(), key = { event -> "evt-${event.sequence}" }) { event ->
                    AppCard {
                        Column(
                            modifier = Modifier.padding(horizontal = AppSpacing.SM, vertical = AppSpacing.XS)
                        ) {
                            Text(
                                text = event.metadata.entries.joinToString("  ") { "${it.key}=${it.value}" },
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = TextMuted
                                text = "${event.formattedTime}  [${event.category.label.uppercase()}]  ${event.event}",
                                style = AppTextStyle.monoCaption,
                                color = AppColors.contentPrimary
                            )
                            if (event.metadata.isNotEmpty()) {
                                Text(
                                    text = event.metadata.entries.joinToString("  ") { "${it.key}=${it.value}" },
                                    style = AppTextStyle.monoCaption,
                                    color = AppColors.contentMuted
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "LOGS (${logs.size})", style = MaterialTheme.typography.labelMedium, color = AccentTeal)
                    Row {
                        FilterChip(
                            selected = levelFilter == null,
                            onClick = { levelFilter = null },
                            label = { Text("All", fontSize = 11.sp) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        FilterChip(
                            selected = levelFilter == LogLevel.ERROR,
                            onClick = { levelFilter = if (levelFilter == LogLevel.ERROR) null else LogLevel.ERROR },
                            label = { Text("Errors", fontSize = 11.sp) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        FilterChip(
                            selected = levelFilter == LogLevel.WARN,
                            onClick = { levelFilter = if (levelFilter == LogLevel.WARN) null else LogLevel.WARN },
                            label = { Text("Warn", fontSize = 11.sp) }
                // Logs header + severity filter.
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "LOGS (${logs.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = AppColors.voiceSpeaking
                        )
                        Row {
                            FilterChip(
                                selected = levelFilter == null,
                                onClick = { levelFilter = null },
                                label = { Text("All", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.heightIn(min = 40.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            FilterChip(
                                selected = levelFilter == LogLevel.ERROR,
                                onClick = { levelFilter = if (levelFilter == LogLevel.ERROR) null else LogLevel.ERROR },
                                label = { Text("Errors", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.heightIn(min = 40.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            FilterChip(
                                selected = levelFilter == LogLevel.WARN,
                                onClick = { levelFilter = if (levelFilter == LogLevel.WARN) null else LogLevel.WARN },
                                label = { Text("Warn", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.heightIn(min = 40.dp)
                            )
                        }
                    }
                }
            }

            val filteredLogs = if (levelFilter == null) logs else logs.filter { it.level == levelFilter }

            // §94: `sequence` is the stable key — a rotating bounded buffer must never rebind a
            // row to a different message.
            items(filteredLogs.asReversed(), key = { log -> log.sequence }) { log ->
                val (badgeColor, badgeText) = when (log.level) {
                    LogLevel.DEBUG -> Pair(TextMuted, "DEBUG")
                    LogLevel.INFO -> Pair(PrimaryBlue, "INFO")
                    LogLevel.WARN -> Pair(StatusAmber, "WARN")
                    LogLevel.ERROR -> Pair(StatusRed, "ERROR")
                }
                // §94: `sequence` is the stable key — a rotating bounded buffer must never
                // rebind a row to a different message.
                items(filteredLogs.asReversed(), key = { log -> log.sequence }) { log ->
                    val (badgeColor, badgeText) = when (log.level) {
                        LogLevel.DEBUG -> Pair(AppColors.statusNeutral, "DEBUG")
                        LogLevel.INFO -> Pair(AppColors.statusInfo, "INFO")
                        LogLevel.WARN -> Pair(AppColors.statusWarning, "WARN")
                        LogLevel.ERROR -> Pair(AppColors.statusDanger, "ERROR")
                    }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(badgeColor.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    AppCard(color = AppColors.surfacePrimary) {
                        Column(modifier = Modifier.padding(AppSpacing.SM)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = badgeText, style = MaterialTheme.typography.labelSmall, color = badgeColor)
                                Box(
                                    modifier = Modifier
                                        .clip(AppShape.chipShape)
                                        .background(badgeColor.copy(alpha = 0.18f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = badgeText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = badgeColor
                                    )
                                }
                                Text(
                                    text = log.formattedTime,
                                    style = AppTextStyle.monoCaption,
                                    color = AppColors.contentMuted
                                )
                            }
                            Text(text = log.formattedTime, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "[${log.tag}] ${log.message}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = TextPrimary
                        )

                        log.throwable?.let { t ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = t.message ?: "Exception",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = StatusRed
                                text = "[${log.tag}] ${log.message}",
                                style = AppTextStyle.monoValue,
                                color = AppColors.contentPrimary
                            )

                            log.throwable?.let { t ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = t.message ?: "Exception",
                                    style = AppTextStyle.monoCaption,
                                    color = AppColors.statusDanger
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
@Composable
private fun DiagnosticsRowsCard(title: String, rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Spacer(modifier = Modifier.height(6.dp))
            rows.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
            }
        }
    }
}

/** Copy helper shared by the summary and detailed export actions (§88). */
private fun copyToClipboard(context: Context, label: String, text: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    } catch (t: Throwable) {
        Toast.makeText(context, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
    }
}
