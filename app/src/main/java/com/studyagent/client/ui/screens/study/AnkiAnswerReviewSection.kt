package com.studyagent.client.ui.screens.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.studyagent.client.core.anki.ReviewerAction
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.render.AnkiCardRenderConfig
import com.studyagent.client.core.render.AnkiCardRenderController
import com.studyagent.client.core.render.AnkiCardSide
import com.studyagent.client.core.render.AnkiRenderPresentation
import com.studyagent.client.core.render.AnkiRenderState
import com.studyagent.client.core.render.AnkiRenderSurfaceKind
import com.studyagent.client.core.render.CardTextDirection
import com.studyagent.client.core.study.AnswerCompareMode
import com.studyagent.client.core.study.AnswerRevealState
import com.studyagent.client.core.study.AnswerReviewModel
import com.studyagent.client.core.study.RatingCommitRecoveryUi
import com.studyagent.client.ui.components.RatingButtonGroup
import com.studyagent.client.ui.components.anki.AnkiCardRenderer
import com.studyagent.client.ui.components.anki.CleanAnkiCardView
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

/**
 * Complete GATE 12 Answer Reveal, Reference Answer & Compare UI for an Anki review turn.
 *
 * Implements the compact phone layout (STEP 36):
 * 1. Question summary (collapsed or compact once revealed)
 * 2. Mode selector: `Original | Clean | Compare`
 * 3. Scrollable Reference / Compare area (with GATE 08/09 renderer for `ORIGINAL`, clean text for `CLEAN`,
 *    and side-by-side/stacked comparison for `COMPARE`)
 * 4. Optional AI feedback card (with expandable key points & advisory badge)
 * 5. Pinned Rating bar (honouring scheduler `AnkiRatingOptions`, interval labels, and GATE 11 lock state)
 */
@Composable
fun AnkiAnswerReviewSection(
    model: AnswerReviewModel,
    recovery: RatingCommitRecoveryUi?,
    onRevealAnswer: () -> Unit,
    onSelectCompareMode: (AnswerCompareMode) -> Unit,
    onToggleRawReference: (Boolean) -> Unit,
    onRepeatAnswer: () -> Unit,
    onRepeatFeedback: () -> Unit,
    onRenderFallback: (String) -> Unit,
    onRateCard: (Rating) -> Unit,
    /** GATE 13 — user picked a card action (flag/bury/suspend). Dispatch only; nothing here decides. */
    onReviewerAction: (ReviewerAction) -> Unit = {},
    /** GATE 13 §13 — retry the *same* action identity after proven non-application. */
    onRetryReviewerAction: () -> Unit = {},
    /** GATE 13 §27 — ask for read-only reconciliation of an unproven action. Never a replay. */
    onRecoverReviewerAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState = remember(model, recovery) { AnswerReviewUiState.from(model, recovery) }
    val renderController = remember {
        AnkiCardRenderController(surfaceKind = AnkiRenderSurfaceKind.BROWSING)
    }
    val renderState by renderController.state.collectAsState()

    // STEP 24: If answerHtml fails to render during ANSWER presentation, notify the study machine
    // so it falls back to CLEAN mode on the same turn without losing the turn or forcing a rating.
    LaunchedEffect(renderState, model.turnId, model.revealState) {
        if (model.revealState == AnswerRevealState.REVEALED) {
            when (val current = renderState) {
                is AnkiRenderState.Failed -> {
                    if (current.request.turnId == model.turnId &&
                        current.request.side == AnkiCardSide.ANSWER &&
                        model.renderFallbackReason == null
                    ) {
                        onRenderFallback(current.failure.token)
                    }
                }
                is AnkiRenderState.Ready -> {
                    if (current.request.turnId == model.turnId &&
                        current.request.side == AnkiCardSide.ANSWER &&
                        current.presentation == AnkiRenderPresentation.CLEAN_FALLBACK &&
                        model.renderFallbackReason == null
                    ) {
                        onRenderFallback("clean_fallback")
                    }
                }
                else -> Unit
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
    ) {
        // 1. Question Summary (compact when answer is revealed — STEP 36)
        QuestionSummaryHeader(
            questionText = uiState.questionSummaryText,
            direction = uiState.questionDirection,
            compact = uiState.isQuestionCompact
        )

        // 1b. GATE 13 — card actions (flag / bury / suspend).
        //
        // Mounted with the question, not with the rating bar: a reviewer may flag or bury a card
        // before revealing the answer, and the durable action model grants the turn exactly one
        // mutation slot regardless of reveal state (§15). The menu renders itself away when the
        // frozen capability set offers nothing (INV-13-16) and shows the durable projection —
        // retry or reconcile — instead of ever replaying an unproven action (§31).
        ReviewerActionMenu(
            model = model,
            onAction = onReviewerAction,
            onRetry = onRetryReviewerAction,
            onRecover = onRecoverReviewerAction
        )

        // 2. Mode Selector: Original | Clean | Compare (visible once revealed — STEP 6, 36, 45)
        if (uiState.showModeSelector) {
            CompareModeSelectorRow(
                selectedMode = uiState.selectedCompareMode,
                onSelectMode = onSelectCompareMode
            )
        }

        // Non-blocking renderer fallback notice (STEP 24)
        if (uiState.renderFallbackBanner != null) {
            Surface(
                shape = AppShape.cardShape,
                color = AppColors.statusWarning.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, AppColors.statusWarning.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = uiState.renderFallbackBanner,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.contentPrimary,
                    modifier = Modifier.padding(AppSpacing.SM)
                )
            }
        }

        // 3 & 4. Scrollable Answer / Compare / AI Feedback area (STEP 35: long content scrolls cleanly
        // while keeping the header and bottom rating controls reachable).
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(scrollState)
                .testTag(AnswerReviewSemantics.TAG_SCROLL_CONTAINER),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
        ) {
            if (uiState.revealState == AnswerRevealState.HIDDEN) {
                // Hidden state: show only the QUESTION side through GATE 08 renderer, never answer fields (STEP 20).
                val questionCard = model.renderedCard
                if (questionCard != null) {
                    AnkiCardRenderer(
                        card = questionCard,
                        turnId = model.turnId,
                        side = AnkiCardSide.QUESTION,
                        controller = renderController,
                        config = AnkiCardRenderConfig.DARK_APP.copy(mode = uiState.rendererMode),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 280.dp)
                    )
                }
            } else {
                // Revealed state: render according to effectiveCompareMode (ORIGINAL, CLEAN, COMPARE)
                when (uiState.effectiveCompareMode) {
                    AnswerCompareMode.ORIGINAL -> {
                        val revealedCard = model.renderedCard
                        if (revealedCard != null) {
                            Surface(
                                shape = AppShape.cardShape,
                                color = AppColors.surfacePrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = AnswerReviewSemantics.ORIGINAL_ANSWER }
                            ) {
                                AnkiCardRenderer(
                                    card = revealedCard,
                                    turnId = model.turnId,
                                    side = AnkiCardSide.ANSWER,
                                    controller = renderController,
                                    config = AnkiCardRenderConfig.DARK_APP.copy(mode = uiState.rendererMode),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 160.dp, max = 320.dp)
                                )
                            }
                        }
                    }

                    AnswerCompareMode.CLEAN -> {
                        Surface(
                            shape = AppShape.cardShape,
                            color = AppColors.surfacePrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = AnswerReviewSemantics.CLEAN_ANSWER }
                        ) {
                            CleanAnkiCardView(
                                text = uiState.visibleAnswerText
                                    ?: uiState.referenceAnswerComparison?.displayText.orEmpty(),
                                side = AnkiCardSide.ANSWER,
                                nightMode = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    AnswerCompareMode.COMPARE -> {
                        CompareAnswersView(
                            userAnswer = uiState.userAnswerComparison,
                            referenceAnswer = uiState.referenceAnswerComparison,
                            onSwitchToOriginal = { onSelectCompareMode(AnswerCompareMode.ORIGINAL) },
                            onToggleRawReference = onToggleRawReference
                        )
                    }
                }

                // 4. Optional AI Evaluation Card (STEP 15, 23, 39, 40, 45)
                EvaluationFeedbackSection(
                    evaluationCard = uiState.evaluationCard,
                    onRepeatFeedback = onRepeatFeedback
                )
            }
        }

        // Explicit Reveal Answer button when answer is still hidden (STEP 7, 19, 21, 22, 45)
        if (uiState.showRevealButton) {
            Button(
                onClick = onRevealAnswer,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag(AnswerReviewSemantics.TAG_REVEAL_BUTTON)
                    .semantics { contentDescription = AnswerReviewSemantics.REVEAL_ANSWER },
                shape = AppShape.buttonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.brandPrimary,
                    contentColor = AppColors.onRatingFill
                )
            ) {
                Text(
                    text = AnswerReviewSemantics.REVEAL_ANSWER,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        // Repeat Answer / Repeat Feedback quick actions when revealed (STEP 28)
        if (uiState.revealState == AnswerRevealState.REVEALED &&
            (uiState.canRepeatAnswer || uiState.canRepeatFeedback)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
            ) {
                if (uiState.canRepeatAnswer) {
                    OutlinedButton(
                        onClick = onRepeatAnswer,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = AnswerReviewSemantics.REPEAT_ANSWER },
                        shape = AppShape.buttonShape,
                        contentPadding = PaddingValues(horizontal = AppSpacing.SM, vertical = 6.dp)
                    ) {
                        Text(
                            text = AnswerReviewSemantics.REPEAT_ANSWER,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                if (uiState.canRepeatFeedback) {
                    OutlinedButton(
                        onClick = onRepeatFeedback,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = AnswerReviewSemantics.REPEAT_FEEDBACK },
                        shape = AppShape.buttonShape,
                        contentPadding = PaddingValues(horizontal = AppSpacing.SM, vertical = 6.dp)
                    ) {
                        Text(
                            text = AnswerReviewSemantics.REPEAT_FEEDBACK,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        // 5. Pinned Rating Bar (STEP 16, 17, 18, 41, 42, 43, 45)
        if (uiState.revealState == AnswerRevealState.REVEALED && uiState.ratingOptions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AnswerReviewSemantics.TAG_RATING_BAR),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (uiState.suggestedRating != null) {
                    Text(
                        text = "${AnswerReviewSemantics.SUGGESTED_RATING}: ${uiState.suggestedRating.displayName} (Advisory — tap a rating to confirm)",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.contentSecondary,
                        modifier = Modifier
                            .testTag(AnswerReviewSemantics.TAG_SUGGESTED_BADGE)
                            .semantics {
                                contentDescription =
                                    "${AnswerReviewSemantics.SUGGESTED_RATING}: ${uiState.suggestedRating.displayName}"
                            }
                    )
                }
                RatingButtonGroup(
                    onRate = onRateCard,
                    suggestedRating = uiState.suggestedRating,
                    enabled = uiState.ratingControlsEnabled,
                    availableRatings = model.availableRatings,
                    intervalLabels = model.nextReviewTimes
                )
            }
        }
    }
}

@Composable
private fun QuestionSummaryHeader(
    questionText: String,
    direction: CardTextDirection,
    compact: Boolean
) {
    val layoutDir = if (direction == CardTextDirection.RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
        Surface(
            shape = AppShape.cardShape,
            color = AppColors.surfaceElevated,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.SM)) {
                Text(
                    text = if (compact) "Question (Summary)" else "Question",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.contentSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = questionText,
                    style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                    color = AppColors.contentPrimary,
                    maxLines = if (compact) 2 else 6,
                    textAlign = if (direction == CardTextDirection.RTL) TextAlign.Right else TextAlign.Left,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun CompareModeSelectorRow(
    selectedMode: AnswerCompareMode,
    onSelectMode: (AnswerCompareMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AnswerReviewSemantics.TAG_MODE_SELECTOR),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
    ) {
        val modes = listOf(
            Triple(AnswerCompareMode.ORIGINAL, "Original", AnswerReviewSemantics.TAG_MODE_ORIGINAL),
            Triple(AnswerCompareMode.CLEAN, "Clean", AnswerReviewSemantics.TAG_MODE_CLEAN),
            Triple(AnswerCompareMode.COMPARE, "Compare", AnswerReviewSemantics.TAG_MODE_COMPARE)
        )
        modes.forEach { (mode, label, tag) ->
            val selected = mode == selectedMode
            val semanticsLabel = AnswerReviewSemantics.modeTabSemantics(mode)
            OutlinedButton(
                onClick = { onSelectMode(mode) },
                modifier = Modifier
                    .weight(1f)
                    .testTag(tag)
                    .semantics { contentDescription = semanticsLabel },
                shape = AppShape.buttonShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (selected) AppColors.brandPrimary.copy(alpha = 0.2f) else AppColors.surfacePrimary,
                    contentColor = if (selected) AppColors.brandPrimary else AppColors.contentSecondary
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (selected) AppColors.brandPrimary else AppColors.borderSubtle
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun CompareAnswersView(
    userAnswer: UserAnswerComparisonUi?,
    referenceAnswer: ReferenceAnswerComparisonUi?,
    onSwitchToOriginal: () -> Unit,
    onToggleRawReference: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = AnswerReviewSemantics.COMPARE_ANSWERS },
        verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
    ) {
        // Your Answer Card (STEP 37)
        if (userAnswer != null) {
            val userLayoutDir = if (userAnswer.textDirection == CardTextDirection.RTL) {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            }
            CompositionLocalProvider(LocalLayoutDirection provides userLayoutDir) {
                Card(
                    shape = AppShape.cardShape,
                    colors = CardDefaults.cardColors(containerColor = AppColors.surfacePrimary),
                    border = BorderStroke(1.dp, AppColors.borderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AnswerReviewSemantics.TAG_YOUR_ANSWER)
                        .semantics { contentDescription = AnswerReviewSemantics.YOUR_ANSWER }
                ) {
                    Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
                        Text(
                            text = userAnswer.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = AppColors.contentSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = userAnswer.displayText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (userAnswer.hasRecordedAnswer) {
                                AppColors.contentPrimary
                            } else {
                                AppColors.contentMuted
                            },
                            textAlign = if (userAnswer.textDirection == CardTextDirection.RTL) {
                                TextAlign.Right
                            } else {
                                TextAlign.Left
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Reference Answer Card (STEP 38)
        if (referenceAnswer != null) {
            val refLayoutDir = if (referenceAnswer.textDirection == CardTextDirection.RTL) {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            }
            CompositionLocalProvider(LocalLayoutDirection provides refLayoutDir) {
                Card(
                    shape = AppShape.cardShape,
                    colors = CardDefaults.cardColors(containerColor = AppColors.surfacePrimary),
                    border = BorderStroke(1.dp, AppColors.brandPrimary.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AnswerReviewSemantics.TAG_REFERENCE_ANSWER)
                        .semantics { contentDescription = AnswerReviewSemantics.REFERENCE_ANSWER }
                ) {
                    Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = referenceAnswer.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = AppColors.brandPrimary
                            )
                            if (referenceAnswer.canSwitchToOriginal) {
                                TextButton(
                                    onClick = onSwitchToOriginal,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        text = "View Original",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = referenceAnswer.displayText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.contentPrimary,
                            textAlign = if (referenceAnswer.textDirection == CardTextDirection.RTL) {
                                TextAlign.Right
                            } else {
                                TextAlign.Left
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (referenceAnswer.rawEvaluatorText != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(
                                onClick = { onToggleRawReference(!referenceAnswer.showRawEvaluatorText) },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    text = if (referenceAnswer.showRawEvaluatorText) {
                                        "Hide pure reference text"
                                    } else {
                                        "Show pure reference text"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.contentSecondary
                                )
                            }
                            if (referenceAnswer.showRawEvaluatorText) {
                                Text(
                                    text = referenceAnswer.rawEvaluatorText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.contentSecondary,
                                    modifier = Modifier.fillMaxWidth()
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
private fun EvaluationFeedbackSection(
    evaluationCard: AnswerEvaluationCardUi,
    onRepeatFeedback: () -> Unit
) {
    when (evaluationCard) {
        AnswerEvaluationCardUi.Hidden -> Unit

        AnswerEvaluationCardUi.Evaluating -> {
            Surface(
                shape = AppShape.cardShape,
                color = AppColors.surfaceElevated,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AnswerReviewSemantics.TAG_AI_FEEDBACK)
                    .semantics { contentDescription = AnswerReviewSemantics.AI_FEEDBACK }
            ) {
                Text(
                    text = "Evaluating your answer…",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.contentSecondary,
                    modifier = Modifier.padding(AppSpacing.cardPadding)
                )
            }
        }

        is AnswerEvaluationCardUi.Unavailable -> {
            Surface(
                shape = AppShape.cardShape,
                color = AppColors.surfaceElevated,
                border = BorderStroke(1.dp, AppColors.borderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AnswerReviewSemantics.TAG_AI_FEEDBACK)
                    .semantics { contentDescription = AnswerReviewSemantics.AI_FEEDBACK }
            ) {
                Text(
                    text = evaluationCard.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.contentSecondary,
                    modifier = Modifier.padding(AppSpacing.cardPadding)
                )
            }
        }

        is AnswerEvaluationCardUi.Available -> {
            var detailsExpanded by rememberSaveable { mutableStateOf(false) }
            val fbLayoutDir = if (evaluationCard.feedbackDirection == CardTextDirection.RTL) {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            }
            CompositionLocalProvider(LocalLayoutDirection provides fbLayoutDir) {
                Card(
                    shape = AppShape.cardShape,
                    colors = CardDefaults.cardColors(containerColor = AppColors.surfaceElevated),
                    border = BorderStroke(1.dp, AppColors.borderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AnswerReviewSemantics.TAG_AI_FEEDBACK)
                        .semantics { contentDescription = AnswerReviewSemantics.AI_FEEDBACK }
                ) {
                    Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = evaluationCard.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = AppColors.contentSecondary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (evaluationCard.score != null) {
                                    Text(
                                        text = "Score: ${evaluationCard.score}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AppColors.contentSecondary
                                    )
                                }
                                if (evaluationCard.suggestedAdvisoryLabel != null) {
                                    Spacer(modifier = Modifier.width(AppSpacing.XS))
                                    Surface(
                                        shape = AppShape.chipShape,
                                        color = AppColors.brandPrimary.copy(alpha = 0.16f),
                                        modifier = Modifier.semantics {
                                            contentDescription =
                                                "${AnswerReviewSemantics.SUGGESTED_RATING}: ${evaluationCard.suggestedAdvisoryLabel}"
                                        }
                                    ) {
                                        Text(
                                            text = evaluationCard.suggestedAdvisoryLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AppColors.brandPrimary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (evaluationCard.feedbackText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = evaluationCard.feedbackText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.contentPrimary,
                                textAlign = if (evaluationCard.feedbackDirection == CardTextDirection.RTL) {
                                    TextAlign.Right
                                } else {
                                    TextAlign.Left
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        val summary = evaluationCard.summary
                        if (summary != null && summary.hasKeyPoints) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { detailsExpanded = !detailsExpanded },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        text = if (detailsExpanded) "Hide key points" else "Show key points",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (evaluationCard.canRepeatFeedback) {
                                    TextButton(
                                        onClick = onRepeatFeedback,
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(
                                            text = AnswerReviewSemantics.REPEAT_FEEDBACK,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                            if (detailsExpanded) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    summary.correctPoints.forEach { pt ->
                                        Text(
                                            text = "✓ $pt",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AppColors.statusSuccess
                                        )
                                    }
                                    summary.missingPoints.forEach { pt ->
                                        Text(
                                            text = "• Missing: $pt",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AppColors.statusWarning
                                        )
                                    }
                                    summary.incorrectPoints.forEach { pt ->
                                        Text(
                                            text = "✗ Incorrect: $pt",
                                            style = MaterialTheme.typography.bodySmall,
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
    }
}
