package com.studyagent.client.ui.screens.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyagent.client.core.models.AutoRatingMode
import com.studyagent.client.core.models.EvaluationStrictness
import com.studyagent.client.core.models.FeedbackDepth
import com.studyagent.client.core.models.HintPolicy
import com.studyagent.client.core.models.LearningHandling
import com.studyagent.client.core.models.SessionTargetType
import com.studyagent.client.core.models.StudyControlConfig
import com.studyagent.client.core.models.StudyMode
import com.studyagent.client.core.models.StudyPreset
import com.studyagent.client.core.models.TranscriptRetention
import com.studyagent.client.ui.components.BannerTone
import com.studyagent.client.ui.components.DestructiveButton
import com.studyagent.client.ui.components.InfoBanner
import com.studyagent.client.ui.components.InlineTextButton
import com.studyagent.client.ui.components.PrimaryButton
import com.studyagent.client.ui.components.SecondaryButton
import com.studyagent.client.ui.components.StudyAgentTopBar
import com.studyagent.client.ui.screens.control.components.ChoiceChips
import com.studyagent.client.ui.screens.control.components.ControlSection
import com.studyagent.client.ui.screens.control.components.ExpandableAdvanced
import com.studyagent.client.ui.screens.control.components.FieldLabel
import com.studyagent.client.ui.screens.control.components.SliderRow
import com.studyagent.client.ui.screens.control.components.StepperRow
import com.studyagent.client.ui.screens.control.components.SwitchRow
import com.studyagent.client.ui.screens.home.components.DeckPickerDialog
import com.studyagent.client.ui.screens.home.components.SectionCard
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

/**
 * Study Control (§45): how the PC Study Agent studies — deck, mode, targets,
 * evaluation, teaching style, rating automation and privacy. Device speech settings
 * live in Settings, never here.
 *
 * Layout: summary → presets → sections → persistent Apply bar (§46).
 */
@Composable
fun ControlCenterScreen(
    viewModel: ControlCenterViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToStudy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showDeckPicker by rememberSaveable { mutableStateOf(false) }
    var manualDeckText by rememberSaveable { mutableStateOf("") }
    var presetPreview by remember { mutableStateOf<StudyPreset?>(null) }
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }
    var advancedEvaluationExpanded by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = AppColors.appBackground,
        topBar = { StudyAgentTopBar(title = "Study Control", onBack = onNavigateBack) },
        bottomBar = {
            ApplyBar(
                state = state,
                onApply = { viewModel.applyDraft() },
                onDiscard = { viewModel.discardDraft() },
                onReset = { showResetConfirm = true }
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
                    .align(Alignment.TopCenter),
                contentPadding = PaddingValues(
                    start = AppSpacing.contentGutter,
                    end = AppSpacing.contentGutter,
                    top = AppSpacing.XS,
                    bottom = AppSpacing.LG
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.screenSectionGap)
            ) {
                // Server-pushed change while the draft is dirty.
                if (state.serverChangedWhileDirty) {
                    item(key = "pushNotice") {
                        InfoBanner(
                            title = "Configuration changed on the Study Agent",
                            message = "You have unsaved edits. Reload the server configuration or keep your draft.",
                            tone = BannerTone.WARNING,
                            actionLabel = "Reload",
                            onAction = { viewModel.reloadServerConfig() },
                            dismissible = true,
                            onDismiss = { viewModel.keepMyDraft() }
                        )
                    }
                }

                // Active session: primary controls first; edits apply next session.
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
                        InfoBanner(
                            message = "A session is in progress. Changes below apply to your next session.",
                            tone = BannerTone.INFO
                        )
                    }
                }

                if (state.isLegacyV1) {
                    item(key = "v1notice") {
                        InfoBanner(
                            title = "Protocol v1 agent",
                            message = "Remote configuration is not available. Your choices are stored on this device " +
                                "and applied when a session starts.",
                            tone = BannerTone.INFO
                        )
                    }
                }

                // Summary — configuration at a glance.
                item(key = "summary") { ControlSummary(state = state) }

                // Presets.
                item(key = "presets") {
                    ControlSection(title = "Preset") {
                        ChoiceChips(
                            options = StudyPreset.entries.filter { it != StudyPreset.CUSTOM },
                            selected = state.selectedPreset,
                            onSelect = { preset -> presetPreview = preset },
                            label = { it.displayName }
                        )
                        if (state.selectedPreset == StudyPreset.CUSTOM) {
                            Spacer(modifier = Modifier.height(AppSpacing.XS))
                            Text(
                                "Custom configuration",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.contentSecondary
                            )
                        }
                    }
                }

                val nextSessionBadge = if (state.sessionActive) "Applies next session" else null

                // Deck & mode.
                item(key = "deckMode") {
                    ControlSection(title = "Deck & Mode", badge = nextSessionBadge) {
                        FieldDeckSelector(
                            state = state,
                            onOpenPicker = { showDeckPicker = true },
                            manualDeckText = manualDeckText,
                            onManualDeckChange = { manualDeckText = it },
                            onManualDeckSubmit = { name -> if (name.isNotBlank()) viewModel.selectDeck(name.trim()) }
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.SM))
                        FieldLabel("Study mode")
                        Spacer(modifier = Modifier.height(6.dp))
                        ChoiceChips(
                            options = StudyMode.entries,
                            selected = state.draftConfig.studyMode,
                            onSelect = { mode -> viewModel.updateDraft { it.copy(studyMode = mode) } },
                            label = { it.displayName }
                        )
                    }
                }

                // Session target.
                item(key = "session") {
                    val draft = state.draftConfig
                    ControlSection(title = "Session", badge = nextSessionBadge) {
                        FieldLabel("Target")
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
                        Spacer(modifier = Modifier.height(AppSpacing.XS))
                        FieldLabel("Learning cards")
                        Spacer(modifier = Modifier.height(6.dp))
                        ChoiceChips(
                            options = LearningHandling.entries,
                            selected = draft.learningHandling,
                            onSelect = { handling -> viewModel.updateDraft { it.copy(learningHandling = handling) } },
                            label = { it.displayName }
                        )
                    }
                }

                // Evaluation.
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
                            color = AppColors.contentSecondary
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.SM))
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
                        }
                    }
                }

                // Teaching style.
                item(key = "teaching") {
                    val draft = state.draftConfig
                    ControlSection(title = "Teaching") {
                        FieldLabel("Feedback")
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
                            color = AppColors.contentSecondary
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.XS))
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
                        Spacer(modifier = Modifier.height(AppSpacing.XS))
                        FieldLabel("Hints")
                        Spacer(modifier = Modifier.height(6.dp))
                        ChoiceChips(
                            options = HintPolicy.entries,
                            selected = draft.hintPolicy,
                            onSelect = { policy -> viewModel.updateDraft { it.copy(hintPolicy = policy) } },
                            label = { it.displayName }
                        )
                    }
                }

                // Rating.
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
                            color = AppColors.contentSecondary
                        )
                        if (draft.ratingMode == AutoRatingMode.AUTO_CONFIDENT) {
                            Spacer(modifier = Modifier.height(AppSpacing.XS))
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
                            Spacer(modifier = Modifier.height(AppSpacing.XS))
                            InfoBanner(
                                message = "Ratings will be applied without confirmation and will affect Anki scheduling.",
                                tone = BannerTone.WARNING
                            )
                        }
                    }
                }

                // Privacy.
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
                            color = AppColors.contentSecondary
                        )
                    }
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
            containerColor = AppColors.surfacePrimary,
            shape = AppShape.dialogShape,
            title = { Text("Reset to defaults?", color = AppColors.contentPrimary) },
            text = {
                Text(
                    "Study configuration returns to balanced defaults. Your active deck is kept.",
                    color = AppColors.contentSecondary
                )
            },
            confirmButton = {
                PrimaryButton(
                    text = "Reset",
                    onClick = {
                        viewModel.resetToDefaults()
                        showResetConfirm = false
                    }
                )
            },
            dismissButton = {
                InlineTextButton(text = "Cancel", onClick = { showResetConfirm = false }, color = AppColors.contentSecondary)
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
    FieldLabel("Active deck")
    Spacer(modifier = Modifier.height(6.dp))
    if (state.supportsDeckList && state.decks.isNotEmpty()) {
        SecondaryButton(
            text = state.draftConfig.activeDeck ?: "Choose deck…",
            onClick = onOpenPicker,
            contentColor = if (state.draftConfig.activeDeck != null) AppColors.contentPrimary else AppColors.contentSecondary,
            modifier = Modifier.fillMaxWidth()
        )
        if (state.selectedDeckUnavailable) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Selected deck unavailable — the Study Agent no longer reports it. Choose another deck.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.statusWarning
            )
        }
    } else {
        OutlinedTextField(
            value = manualDeckText.ifEmpty { state.draftConfig.activeDeck ?: "" },
            onValueChange = onManualDeckChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Deck name", color = AppColors.contentMuted) },
            singleLine = true,
            shape = AppShape.fieldShape,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = AppColors.contentPrimary)
        )
        InlineTextButton(text = "Use this deck", onClick = { onManualDeckSubmit(manualDeckText) })
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
    val lines = listOf(
        draft.activeDeck ?: "No deck selected",
        draft.studyMode.displayName,
        targetLabel,
        "${draft.evaluation.strictness.displayName} evaluation",
        "${draft.feedbackDepth.displayName} feedback",
        draft.ratingMode.displayName
    )
    SectionCard(title = "Current configuration") {
        Text(
            text = lines.joinToString("  •  "),
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.contentPrimary
        )
        if (state.configIsLocalOnly && !state.isLegacyV1) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Waiting for the Study Agent's configuration…",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.contentMuted
            )
        }
        if (state.hasUnsavedChanges) {
            Spacer(modifier = Modifier.height(6.dp))
            Text("Unsaved changes", style = MaterialTheme.typography.bodySmall, color = AppColors.statusWarning)
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
    SectionCard(
        title = if (isPaused) "Session paused" else "Session in progress",
        accent = if (isPaused) AppColors.statusWarning else AppColors.statusSuccess
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
            PrimaryButton(text = "Resume", onClick = onResume, icon = Icons.Default.PlayArrow, modifier = Modifier.weight(1f))
            SecondaryButton(text = "Pause", onClick = onPause, enabled = !isPaused, icon = Icons.Default.Pause, modifier = Modifier.weight(1f))
            DestructiveButton(text = "End", onClick = onEnd, icon = Icons.Default.Stop, modifier = Modifier.weight(1f))
        }
    }
}

/** Persistent bottom bar: status line + Apply / Discard / Reset (§46). */
@Composable
private fun ApplyBar(
    state: ControlCenterUiState,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onReset: () -> Unit
) {
    Surface(color = AppColors.surfacePrimary, shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.contentGutter, vertical = AppSpacing.SM)
        ) {
            val statusText: String?
            val statusColor: androidx.compose.ui.graphics.Color
            when {
                state.validationErrors.isNotEmpty() -> {
                    statusText = state.validationErrors.joinToString("  •  ")
                    statusColor = AppColors.statusDanger
                }
                state.saveError != null -> {
                    statusText = if (state.saveTimedOut) "${state.saveError} Draft kept — retry or reset." else state.saveError
                    statusColor = AppColors.statusDanger
                }
                state.hasUnsavedChanges -> {
                    statusText = "Unsaved changes"
                    statusColor = AppColors.statusWarning
                }
                state.lastSavedAt != null -> {
                    statusText = if (state.savedLocallyOnly) "Saved on this device" else "Saved on Study Agent"
                    statusColor = AppColors.statusSuccess
                }
                else -> {
                    statusText = null
                    statusColor = AppColors.contentMuted
                }
            }
            if (statusText != null) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    modifier = Modifier.semantics { contentDescription = "Save status: $statusText" }
                )
                Spacer(modifier = Modifier.height(AppSpacing.XS))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
                PrimaryButton(
                    text = "Apply Changes",
                    onClick = onApply,
                    enabled = state.hasUnsavedChanges && !state.saving && state.validationErrors.isEmpty(),
                    loading = state.saving,
                    modifier = Modifier.weight(1f)
                )
                SecondaryButton(
                    text = "Discard",
                    onClick = onDiscard,
                    enabled = state.hasUnsavedChanges && !state.saving,
                    contentColor = AppColors.contentSecondary
                )
                SecondaryButton(
                    text = "Reset",
                    onClick = onReset,
                    enabled = !state.saving,
                    contentColor = AppColors.contentSecondary
                )
            }
        }
    }
}

/** Preset preview: concise consequences, then explicit Apply. */
@Composable
private fun PresetPreviewDialog(
    preset: StudyPreset,
    previewConfig: StudyControlConfig,
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
        containerColor = AppColors.surfacePrimary,
        shape = AppShape.dialogShape,
        title = { Text(preset.displayName, style = MaterialTheme.typography.titleLarge, color = AppColors.contentPrimary) },
        text = {
            Column {
                Text(preset.description, style = MaterialTheme.typography.bodySmall, color = AppColors.contentSecondary)
                Spacer(modifier = Modifier.height(AppSpacing.SM))
                bullets.forEach { bullet ->
                    Text("• $bullet", style = MaterialTheme.typography.bodyMedium, color = AppColors.contentPrimary)
                }
            }
        },
        confirmButton = { PrimaryButton(text = "Apply", onClick = onApply) },
        dismissButton = { InlineTextButton(text = "Cancel", onClick = onDismiss, color = AppColors.contentSecondary) }
    )
}

// ---------------------------------------------------------------------------
// Descriptions — short, factual, no duplicated business rules.
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

// ---------------------------------------------------------------------------
// Previews (fake static data only — no repositories)
// ---------------------------------------------------------------------------

@Preview(name = "Apply bar — unsaved", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun ApplyBarPreview() {
    ApplyBar(state = ControlCenterUiState(hasUnsavedChanges = true), onApply = {}, onDiscard = {}, onReset = {})
}

@Preview(name = "Apply bar — saved", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun ApplyBarSavedPreview() {
    ApplyBar(state = ControlCenterUiState(lastSavedAt = 1L, savedLocallyOnly = false), onApply = {}, onDiscard = {}, onReset = {})
}
