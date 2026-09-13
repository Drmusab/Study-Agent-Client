package com.studyagent.client.ui.screens.control

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyagent.client.core.models.AutoRatingMode
import com.studyagent.client.core.models.EvaluationStrictness
import com.studyagent.client.core.models.FeedbackDepth
import com.studyagent.client.core.models.HintPolicy
import com.studyagent.client.core.models.LearningHandling
import com.studyagent.client.core.models.SessionTargetType
import com.studyagent.client.core.models.StudyMode
import com.studyagent.client.core.models.StudyPreset
import com.studyagent.client.core.models.TranscriptRetention
import com.studyagent.client.ui.screens.control.components.ChoiceChips
import com.studyagent.client.ui.screens.control.components.ControlSection
import com.studyagent.client.ui.screens.control.components.ExpandableAdvanced
import com.studyagent.client.ui.screens.control.components.SliderRow
import com.studyagent.client.ui.screens.control.components.StepperRow
import com.studyagent.client.ui.screens.control.components.SwitchRow
import com.studyagent.client.ui.screens.home.components.DeckPickerDialog
import com.studyagent.client.ui.screens.home.components.InfoPanel
import com.studyagent.client.ui.screens.home.components.SectionCard
import com.studyagent.client.ui.screens.home.components.StatItem
import com.studyagent.client.ui.theme.AccentTeal
import com.studyagent.client.ui.theme.DarkBackground
import com.studyagent.client.ui.theme.DarkSurface
import com.studyagent.client.ui.theme.PrimaryBlue
import com.studyagent.client.ui.theme.StatusAmber
import com.studyagent.client.ui.theme.StatusGreen
import com.studyagent.client.ui.theme.StatusRed
import com.studyagent.client.ui.theme.TextMuted
import com.studyagent.client.ui.theme.TextPrimary
import com.studyagent.client.ui.theme.TextSecondary

/**
 * Study Control Center (§45): how the PC Study Agent studies — deck, mode,
 * targets, evaluation, teaching style, rating automation and privacy (§48).
 * Device speech settings deliberately live in Settings, never here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlCenterScreen(
    viewModel: ControlCenterViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToStudy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeckPicker by remember { mutableStateOf(false) }
    var manualDeckText by remember { mutableStateOf("") }
    var presetPreview by remember { mutableStateOf<StudyPreset?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var advancedEvaluationExpanded by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text("Study Control", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        bottomBar = {
            ApplyBar(
                state = state,
                onApply = { viewModel.applyDraft() },
                onDiscard = { viewModel.discardDraft() },
                onReset = { showResetConfirm = true }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Server-pushed change while the draft is dirty (§115).
            if (state.serverChangedWhileDirty) {
                item(key = "pushNotice") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Configuration changed on the Study Agent",
                                style = MaterialTheme.typography.titleSmall,
                                color = StatusAmber
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "You have unsaved edits. Reload the server configuration or keep your draft.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { viewModel.reloadServerConfig() }, shape = RoundedCornerShape(10.dp)) {
                                    Text("Reload", color = PrimaryBlue)
                                }
                                TextButton(onClick = { viewModel.keepMyDraft() }) {
                                    Text("Keep my draft", color = TextSecondary)
                                }
                            }
                        }
                    }
                }
            }

            // Active session: primary controls first (§82), edits apply next session (§80).
            if (state.sessionActive) {
                item(key = "activeSession") {
                    ActiveSessionControl(
                        isPaused = state.sessionPaused,
                        onPause = { viewModel.pauseSession() },
                        onResume = {
                            viewModel.resumeSession()
                            onNavigateToStudy()
                        },
                        onEnd = { viewModel.endSession() }
                    )
                }
                item(key = "nextSessionNotice") {
                    InfoPanel(
                        text = "A session is in progress. Changes below apply to your next session.",
                        color = StatusAmber
                    )
                }
            }

            if (state.isLegacyV1) {
                item(key = "v1notice") {
                    InfoPanel(
                        text = "This Study Agent speaks Protocol v1: remote configuration is not available. " +
                            "Your choices are stored on this device and applied when a session starts.",
                        color = StatusAmber
                    )
                }
            }

            // Control summary (§121) — configuration at a glance.
            item(key = "summary") { ControlSummary(state = state) }

            // Presets (§70).
            item(key = "presets") {
                ControlSection(title = "Preset") {
                    ChoiceChips(
                        options = StudyPreset.entries.filter { it != StudyPreset.CUSTOM },
                        selected = state.selectedPreset,
                        onSelect = { preset -> presetPreview = preset },
                        label = { it.displayName }
                    )
                    if (state.selectedPreset == StudyPreset.CUSTOM) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Custom configuration",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            }

            // Deck & mode (§55/§56).
            item(key = "deckMode") {
                val nextSessionBadge = if (state.sessionActive) "Applies next session" else null
                ControlSection(title = "Deck & Mode", badge = nextSessionBadge) {
                    FieldDeckSelector(
                        state = state,
                        onOpenPicker = { showDeckPicker = true },
                        manualDeckText = manualDeckText,
                        onManualDeckChange = { manualDeckText = it },
                        onManualDeckSubmit = { name -> if (name.isNotBlank()) viewModel.selectDeck(name.trim()) }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Study Mode", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    ChoiceChips(
                        options = StudyMode.entries,
                        selected = state.draftConfig.studyMode,
                        onSelect = { mode -> viewModel.updateDraft { it.copy(studyMode = mode) } },
                        label = { it.displayName }
                    )
                }
            }

            // Session target (§57-§60).
            item(key = "session") {
                val draft = state.draftConfig
                ControlSection(title = "Session", badge = if (state.sessionActive) "Applies next session" else null) {
                    Text("Target", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    ChoiceChips(
                        options = SessionTargetType.entries,
                        selected = draft.sessionTargetType,
                        onSelect = { type -> viewModel.updateDraft { it.copy(sessionTargetType = type) } },
                        label = { it.displayName }
                    )
                    when (draft.sessionTargetType) {
                        SessionTargetType.CARDS -> StepperRow(
                            label = "Target cards",
                            value = draft.sessionTargetValue,
                            onValueChange = { v -> viewModel.updateDraft { it.copy(sessionTargetValue = v) } },
                            range = 1..999,
                            step = 5
                        )

                        SessionTargetType.MINUTES -> StepperRow(
                            label = "Target minutes",
                            value = draft.sessionTargetValue,
                            onValueChange = { v -> viewModel.updateDraft { it.copy(sessionTargetValue = v) } },
                            range = 1..240,
                            step = 5
                        )

                        SessionTargetType.FINISH_DUE -> Unit
                    }
                    StepperRow(
                        label = "New cards",
                        description = "Session-level Study Agent limit — does not change your Anki deck options.",
                        value = draft.newPerDay,
                        onValueChange = { v -> viewModel.updateDraft { it.copy(newPerDay = v) } },
                        range = 0..999,
                        step = 5
                    )
                    SwitchRow(
                        label = "Use Anki default review limit",
                        checked = draft.reviewLimitPerDay == null,
                        onCheckedChange = { useDefault ->
                            viewModel.updateDraft { it.copy(reviewLimitPerDay = if (useDefault) null else 200) }
                        }
                    )
                    draft.reviewLimitPerDay?.let { limit ->
                        StepperRow(
                            label = "Review limit",
                            value = limit,
                            onValueChange = { v -> viewModel.updateDraft { it.copy(reviewLimitPerDay = v) } },
                            range = 0..9999,
                            step = 10
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Learning cards", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    ChoiceChips(
                        options = LearningHandling.entries,
                        selected = draft.learningHandling,
                        onSelect = { handling -> viewModel.updateDraft { it.copy(learningHandling = handling) } },
                        label = { it.displayName }
                    )
                }
            }

            // Evaluation (§61/§62).
            item(key = "evaluation") {
                val draft = state.draftConfig
                ControlSection(title = "Evaluation") {
                    ChoiceChips(
                        options = EvaluationStrictness.entries,
                        selected = draft.evaluation.strictness,
                        onSelect = { strictness ->
                            viewModel.updateDraft { it.copy(evaluation = it.evaluation.copy(strictness = strictness)) }
                        },
                        label = { it.displayName }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        strictnessDescription(draft.evaluation.strictness),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ExpandableAdvanced(
                        title = "Advanced evaluation",
                        expanded = advancedEvaluationExpanded,
                        onToggle = { advancedEvaluationExpanded = !advancedEvaluationExpanded }
                    ) {
                        SwitchRow(
                            label = "Semantic matching",
                            checked = draft.evaluation.semanticMatching,
                            onCheckedChange = { v ->
                                viewModel.updateDraft { it.copy(evaluation = it.evaluation.copy(semanticMatching = v)) }
                            }
                        )
                        SwitchRow(
                            label = "Require key points",
                            checked = draft.evaluation.requireKeyPoints,
                            onCheckedChange = { v ->
                                viewModel.updateDraft { it.copy(evaluation = it.evaluation.copy(requireKeyPoints = v)) }
                            }
                        )
                        SwitchRow(
                            label = "Penalize incorrect statements",
                            checked = draft.evaluation.penalizeIncorrectStatements,
                            onCheckedChange = { v ->
                                viewModel.updateDraft {
                                    it.copy(evaluation = it.evaluation.copy(penalizeIncorrectStatements = v))
                                }
                            }
                        )
                        SwitchRow(
                            label = "Penalize dangerous misconceptions",
                            checked = draft.evaluation.penalizeDangerousMisconceptions,
                            onCheckedChange = { v ->
                                viewModel.updateDraft {
                                    it.copy(evaluation = it.evaluation.copy(penalizeDangerousMisconceptions = v))
                                }
                            }
                        )
                        SwitchRow(
                            label = "Partial credit",
                            checked = draft.evaluation.partialCredit,
                            onCheckedChange = { v ->
                                viewModel.updateDraft { it.copy(evaluation = it.evaluation.copy(partialCredit = v)) }
                            }
                        )
                    }
                }
            }

            // Teaching style (§63-§65).
            item(key = "teaching") {
                val draft = state.draftConfig
                ControlSection(title = "Teaching") {
                    Text("Feedback", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    ChoiceChips(
                        options = FeedbackDepth.entries,
                        selected = draft.feedbackDepth,
                        onSelect = { depth -> viewModel.updateDraft { it.copy(feedbackDepth = depth) } },
                        label = { it.displayName }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        feedbackDescription(draft.feedbackDepth),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SwitchRow(
                        label = "Socratic mode",
                        description = "The agent asks follow-up questions before revealing answers.",
                        checked = draft.socratic.enabled,
                        onCheckedChange = { enabled ->
                            viewModel.updateDraft { it.copy(socratic = it.socratic.copy(enabled = enabled)) }
                        }
                    )
                    if (draft.socratic.enabled) {
                        StepperRow(
                            label = "Max follow-ups",
                            value = draft.socratic.maxFollowUps,
                            onValueChange = { v ->
                                viewModel.updateDraft { it.copy(socratic = it.socratic.copy(maxFollowUps = v)) }
                            },
                            range = 1..5
                        )
                        StepperRow(
                            label = "Reveal answer after attempts",
                            value = draft.socratic.revealAfterAttempts,
                            onValueChange = { v ->
                                viewModel.updateDraft { it.copy(socratic = it.socratic.copy(revealAfterAttempts = v)) }
                            },
                            range = 1..10
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Hints", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    ChoiceChips(
                        options = HintPolicy.entries,
                        selected = draft.hintPolicy,
                        onSelect = { policy -> viewModel.updateDraft { it.copy(hintPolicy = policy) } },
                        label = { it.displayName }
                    )
                }
            }

            // Rating (§66-§68).
            item(key = "rating") {
                val draft = state.draftConfig
                ControlSection(title = "Rating") {
                    ChoiceChips(
                        options = AutoRatingMode.entries,
                        selected = draft.ratingMode,
                        onSelect = { mode -> viewModel.updateDraft { it.copy(ratingMode = mode) } },
                        label = { it.displayName }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        ratingDescription(draft.ratingMode),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    if (draft.ratingMode == AutoRatingMode.AUTO_CONFIDENT) {
                        Spacer(modifier = Modifier.height(8.dp))
                        SliderRow(
                            label = "Confidence threshold",
                            description = "Only high-confidence evaluations are rated automatically.",
                            value = draft.autoRateConfidence,
                            range = 50..100,
                            onValueChange = { v -> viewModel.updateDraft { it.copy(autoRateConfidence = v) } },
                            valueLabel = "${draft.autoRateConfidence}%"
                        )
                    }
                    if (draft.ratingMode == AutoRatingMode.AUTOMATIC) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Ratings will be applied without confirmation and will affect Anki scheduling.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusAmber
                        )
                    }
                }
            }

            // Privacy (§69).
            item(key = "privacy") {
                val draft = state.draftConfig
                ControlSection(title = "Privacy") {
                    ChoiceChips(
                        options = TranscriptRetention.entries,
                        selected = draft.transcriptRetention,
                        onSelect = { retention -> viewModel.updateDraft { it.copy(transcriptRetention = retention) } },
                        label = { it.displayName }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        retentionDescription(draft.transcriptRetention),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ dialogs

    if (showDeckPicker) {
        DeckPickerDialog(
            decks = state.decks,
            currentDeckName = state.draftConfig.activeDeck,
            onSelect = { name ->
                viewModel.selectDeck(name)
                showDeckPicker = false
            },
            onDismiss = { showDeckPicker = false }
        )
    }

    presetPreview?.let { preset ->
        PresetPreviewDialog(
            preset = preset,
            previewConfig = preset.applyTo(state.draftConfig),
            onApply = {
                viewModel.applyPreset(preset)
                presetPreview = null
            },
            onDismiss = { presetPreview = null }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = DarkSurface,
            title = { Text("Reset to defaults?", color = TextPrimary) },
            text = {
                Text(
                    "Study configuration returns to balanced defaults. Your active deck is kept.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetToDefaults()
                        showResetConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) { Text("Reset", color = TextPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel", color = TextSecondary) }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Sub-composables
// ---------------------------------------------------------------------------

@Composable
private fun FieldDeckSelector(
    state: ControlCenterUiState,
    onOpenPicker: () -> Unit,
    manualDeckText: String,
    onManualDeckChange: (String) -> Unit,
    onManualDeckSubmit: (String) -> Unit
) {
    Text("Active Deck", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    Spacer(modifier = Modifier.height(6.dp))
    if (state.supportsDeckList && state.decks.isNotEmpty()) {
        OutlinedButton(
            onClick = onOpenPicker,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = state.draftConfig.activeDeck ?: "Choose deck…",
                color = if (state.draftConfig.activeDeck != null) TextPrimary else TextMuted
            )
        }
        if (state.selectedDeckUnavailable) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Selected deck unavailable — the Study Agent no longer reports it. Choose another deck.",
                style = MaterialTheme.typography.bodySmall,
                color = StatusAmber
            )
        }
    } else {
        OutlinedTextField(
            value = manualDeckText.ifEmpty { state.draftConfig.activeDeck ?: "" },
            onValueChange = onManualDeckChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Deck name", color = TextMuted) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
        )
        TextButton(onClick = { onManualDeckSubmit(manualDeckText) }) {
            Text("Use this deck", color = PrimaryBlue)
        }
    }
}

@Composable
private fun ControlSummary(state: ControlCenterUiState) {
    val draft = state.draftConfig
    val targetLabel = when (draft.sessionTargetType) {
        SessionTargetType.CARDS -> "${draft.sessionTargetValue} cards"
        SessionTargetType.MINUTES -> "${draft.sessionTargetValue} minutes"
        SessionTargetType.FINISH_DUE -> "Finish due cards"
    }
    val lines = listOfNotNull(
        draft.activeDeck ?: "No deck selected",
        draft.studyMode.displayName,
        targetLabel,
        "${draft.evaluation.strictness.displayName} evaluation",
        "${draft.feedbackDepth.displayName} feedback",
        draft.ratingMode.displayName
    )
    SectionCard(title = "Current Configuration") {
        Text(
            text = lines.joinToString("  •  "),
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary
        )
        if (state.configIsLocalOnly && !state.isLegacyV1) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Waiting for the Study Agent's configuration…",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
        if (state.hasUnsavedChanges) {
            Spacer(modifier = Modifier.height(6.dp))
            Text("Unsaved changes", style = MaterialTheme.typography.bodySmall, color = StatusAmber)
        }
    }
}

@Composable
private fun ActiveSessionControl(
    isPaused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEnd: () -> Unit
) {
    SectionCard(title = if (isPaused) "Session Paused" else "Active Session", accent = StatusGreen) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onResume,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Resume", color = TextPrimary)
            }
            OutlinedButton(
                onClick = onPause,
                enabled = !isPaused,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Pause, contentDescription = null, tint = TextSecondary)
                Spacer(Modifier.width(4.dp))
                Text("Pause", color = TextSecondary)
            }
            OutlinedButton(
                onClick = onEnd,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, tint = StatusRed)
                Spacer(Modifier.width(4.dp))
                Text("End", color = StatusRed)
            }
        }
    }
}

@Composable
private fun ApplyBar(
    state: ControlCenterUiState,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onReset: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (state.validationErrors.isNotEmpty()) {
                state.validationErrors.forEach { error ->
                    Text("• $error", style = MaterialTheme.typography.bodySmall, color = StatusRed)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            state.saveError?.let { error ->
                Text(
                    text = if (state.saveTimedOut) "$error Draft kept — Retry or Reset." else error,
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusRed
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (state.lastSavedAt != null && state.saveError == null && !state.hasUnsavedChanges) {
                Text(
                    text = if (state.savedLocallyOnly) "Saved on this device" else "Saved on Study Agent",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusGreen
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApply,
                    enabled = state.hasUnsavedChanges && !state.saving && state.validationErrors.isEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp, color = TextPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("Saving…", color = TextPrimary)
                    } else {
                        Text("APPLY CHANGES", color = TextPrimary)
                    }
                }
                OutlinedButton(
                    onClick = onDiscard,
                    enabled = state.hasUnsavedChanges && !state.saving,
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Discard", color = TextSecondary) }
                OutlinedButton(
                    onClick = onReset,
                    enabled = !state.saving,
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Reset", color = TextSecondary) }
            }
        }
    }
}

/** Preset preview (§72): concise consequences, then explicit Apply. */
@Composable
private fun PresetPreviewDialog(
    preset: StudyPreset,
    previewConfig: com.studyagent.client.core.models.StudyControlConfig,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    val targetLabel = when (previewConfig.sessionTargetType) {
        SessionTargetType.CARDS -> "${previewConfig.sessionTargetValue}-card target"
        SessionTargetType.MINUTES -> "${previewConfig.sessionTargetValue}-minute target"
        SessionTargetType.FINISH_DUE -> "Finish all due cards"
    }
    val bullets = listOf(
        previewConfig.studyMode.displayName,
        targetLabel,
        "${previewConfig.feedbackDepth.displayName} feedback",
        if (previewConfig.socratic.enabled) "Socratic follow-ups (max ${previewConfig.socratic.maxFollowUps})" else "No Socratic follow-ups",
        previewConfig.hintPolicy.displayName,
        previewConfig.ratingMode.displayName
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = { Text(preset.displayName.uppercase(), color = TextPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(preset.description, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                Spacer(modifier = Modifier.height(10.dp))
                bullets.forEach { bullet ->
                    Text("• $bullet", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
            }
        },
        confirmButton = {
            Button(onClick = onApply, colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)) {
                Text("Apply", color = TextPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

// ---------------------------------------------------------------------------
// Descriptions — short, factual, no duplicated business rules (§54).
// ---------------------------------------------------------------------------

private fun strictnessDescription(strictness: EvaluationStrictness): String = when (strictness) {
    EvaluationStrictness.LENIENT -> "Accepts paraphrased answers; fewer points required."
    EvaluationStrictness.BALANCED -> "Standard grading — recommended for daily study."
    EvaluationStrictness.STRICT -> "Requires precise coverage of key points."
    EvaluationStrictness.EXAM -> "Exam conditions: exact answers, no hints, no partial credit."
}

private fun feedbackDescription(depth: FeedbackDepth): String = when (depth) {
    FeedbackDepth.MINIMAL -> "Fastest feedback — right/wrong and a short note."
    FeedbackDepth.NORMAL -> "Short explanation of what was missing."
    FeedbackDepth.DETAILED -> "Fuller explanations after every card."
    FeedbackDepth.TUTOR -> "More explanation and teaching around each answer."
}

private fun ratingDescription(mode: AutoRatingMode): String = when (mode) {
    AutoRatingMode.MANUAL -> "You rate every card yourself."
    AutoRatingMode.SUGGEST -> "The agent suggests a rating; you confirm it. Recommended."
    AutoRatingMode.AUTO_CONFIDENT -> "High-confidence evaluations are rated automatically; the rest need confirmation."
    AutoRatingMode.AUTOMATIC -> "Every card is rated automatically."
}

private fun retentionDescription(retention: TranscriptRetention): String = when (retention) {
    TranscriptRetention.NONE -> "Nothing you say is stored on the Study Agent."
    TranscriptRetention.SCORE_ONLY -> "Only the evaluation score is kept. Recommended for privacy."
    TranscriptRetention.FULL -> "Full transcripts are stored for analytics — review your privacy preferences."
}
