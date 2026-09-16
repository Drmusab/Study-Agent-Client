package com.studyagent.client.ui.screens.study

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyagent.client.core.audio.EffectiveStudyAudioMode
import com.studyagent.client.core.audio.StudyAudioMode
import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.ui.components.AppCard
import com.studyagent.client.ui.components.AppHeroCard
import com.studyagent.client.ui.components.AudioRouteIndicator
import com.studyagent.client.ui.components.BannerTone
import com.studyagent.client.ui.components.ConnectionBadge
import com.studyagent.client.ui.components.DestructiveButton
import com.studyagent.client.ui.components.ExpandableSection
import com.studyagent.client.ui.components.InfoBanner
import com.studyagent.client.ui.components.PrimaryButton
import com.studyagent.client.ui.components.PushToTalkButton
import com.studyagent.client.ui.components.RatingButtonGroup
import com.studyagent.client.ui.components.STUDY_PHASE_CHIP_TEST_TAG
import com.studyagent.client.ui.components.SecondaryButton
import com.studyagent.client.ui.components.SectionHeader
import com.studyagent.client.ui.components.StudySessionHeader
import com.studyagent.client.ui.components.VoiceWaveVisualizer
import com.studyagent.client.ui.components.studyPhaseOf
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

/**
 * Study Session (§22–§30). Layout, top to bottom:
 *
 * 1. Top bar — back, connection badge, effective audio route (neutral wording).
 * 2. Session header — deck, progress and the single explicit phase chip.
 * 3. Route notices — pending headset switch / unexpected headset loss (cards, not dialogs).
 * 4. Paused transform — a visible "Paused" card with Resume; the question dims behind it.
 * 5. Question hero — the largest text on screen, with a small voice-activity indicator.
 * 6. Progressive disclosure — transcript, review, evaluation (summary + expandable detail),
 *    hint, explanation, error and completion cards appear only when they have content.
 * 7. Pinned controls — Push to Talk, quick tools, large rating buttons, Pause / End.
 *
 * All voice state comes from the ViewModel; nothing here touches STT/TTS/routing directly.
 * The RMS level flow is deliberately *not* collected here — the visualizer only needs
 * `isActive`, so a hot microphone never recomposes the screen.
 */
@Composable
fun StudyScreen(
    viewModel: StudyViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studyState by viewModel.studyState.collectAsStateWithLifecycle()
    val session by viewModel.currentSession.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    // Route flows are optional (no coordinator in some test/fake wirings): collect safely.
    val studyAudioRoute = viewModel.studyAudioRoute?.collectAsStateWithLifecycle()?.value
    val audioRouteAttention = viewModel.audioRouteAttention?.collectAsStateWithLifecycle()?.value
    val pendingAudioRoute = viewModel.pendingAudioRoute?.collectAsStateWithLifecycle()?.value
    var phoneNoticeDismissed by rememberSaveable { mutableStateOf(false) }

    val phase = remember(studyState) { studyPhaseOf(studyState) }
    val currentCard = studyState.currentCardOrNull ?: session?.currentCard
    val isPaused = studyState is StudyState.Paused

    // One-time education (§31): only when the phone route is a *fallback*, only once, and
    // only after the user has started studying. Never a blocking dialog in Auto mode.
    val showPhoneNotice = !phoneNoticeDismissed &&
        studyAudioRoute?.effective == EffectiveStudyAudioMode.PHONE &&
        studyAudioRoute.preference != StudyAudioMode.PHONE &&
        appSettings?.phoneAudioNoticeAcknowledged == false &&
        studyState !is StudyState.Idle

    if (showPhoneNotice) {
        AlertDialog(
            onDismissRequest = { phoneNoticeDismissed = true },
            containerColor = AppColors.surfaceElevated,
            titleContentColor = AppColors.contentPrimary,
            textContentColor = AppColors.contentSecondary,
            title = { Text("Using phone audio") },
            text = {
                Text(
                    "No headphones connected. Study Agent will use your phone speaker and " +
                        "microphone, so you can study with only your phone.\n\n" +
                        "Phone speaker mode may be audible to people nearby. Headphones improve " +
                        "privacy and may improve recognition, and you can connect them at any time."
                )
            },
            confirmButton = {
                TextButton(onClick = { phoneNoticeDismissed = true }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = {
                    phoneNoticeDismissed = true
                    viewModel.acknowledgePhoneAudioNotice(suppressForever = true)
                }) { Text("Don't show again") }
            }
        )
    }

    Scaffold(
        containerColor = AppColors.appBackground,
        topBar = {
            StudyTopBar(
                onBack = onNavigateBack,
                connectionBadge = {
                    ConnectionBadge(connectionState = connectionState, onClick = onNavigateToConnection)
                },
                routeIndicator = { AudioRouteIndicator(route = studyAudioRoute) }
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val contentWidth = maxWidth.coerceAtMost(AppSpacing.studyMaxWidth)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .width(contentWidth)
                    .align(Alignment.TopCenter)
                    .padding(horizontal = AppSpacing.contentGutter)
            ) {
                // ---------------- Scrollable content ----------------
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
                ) {
                    Spacer(modifier = Modifier.height(0.dp))

                    StudySessionHeader(
                        deckName = session?.deckName ?: "Study Session",
                        cardNumber = session?.cardNumber ?: 0,
                        remainingCards = session?.remainingCards ?: 0,
                        phase = phase
                    )

                    // A better route is waiting for the turn boundary (§41/§42).
                    if (pendingAudioRoute != null) {
                        InfoBanner(
                            title = "Headphones connected",
                            message = "Study Agent will switch to headphones after this turn.",
                            tone = BannerTone.INFO,
                            actionLabel = "Use headphones now",
                            onAction = { viewModel.onUseHeadsetNow() }
                        )
                    }

                    // Unexpected headset loss under the "pause voice study" policy (§96/§118).
                    if (audioRouteAttention != null) {
                        HeadsetLostCard(
                            message = audioRouteAttention.message,
                            canContinueOnPhone = audioRouteAttention.canContinueOnPhone,
                            canWaitForHeadset = audioRouteAttention.canWaitForHeadset,
                            onContinueOnPhone = { viewModel.onContinueOnPhone() },
                            onWaitForHeadset = { viewModel.onWaitForHeadset() }
                        )
                    }

                    // Paused transform (§27): impossible to miss, one obvious way back.
                    if (isPaused) {
                        PausedCard(onResume = { viewModel.onResumeSession() })
                    }

                    // Error surface (§30): plain language here; details live in Diagnostics.
                    (studyState as? StudyState.Error)?.let { error ->
                        InfoBanner(
                            title = if (error.recoverable) "Something went wrong" else "Session stopped",
                            message = error.message,
                            tone = BannerTone.DANGER,
                            actionLabel = if (!connectionState.isConnected) "Connection" else null,
                            onAction = if (!connectionState.isConnected) onNavigateToConnection else null
                        )
                    }

                    // Question hero.
                    QuestionCard(
                        question = currentCard?.question,
                        loadingMessage = (studyState as? StudyState.Loading)?.message,
                        isListening = isListening || studyState is StudyState.Listening,
                        isSpeaking = isSpeaking,
                        dimmed = isPaused
                    )

                    // Live / final transcript.
                    val transcriptText = when (val s = studyState) {
                        is StudyState.Listening -> s.partialTranscript
                        is StudyState.Evaluating -> s.userTranscript
                        else -> ""
                    }
                    AnimatedVisibility(visible = transcriptText.isNotBlank()) {
                        QuoteCard(
                            heading = if (studyState is StudyState.Evaluating) "Your answer" else "Hearing…",
                            text = transcriptText
                        )
                    }

                    // Transcript review (§19/§73): submit or listen again — not a text editor.
                    val pendingTranscript = (studyState as? StudyState.Listening)?.pendingTranscript.orEmpty()
                    AnimatedVisibility(visible = pendingTranscript.isNotBlank()) {
                        AppCard(color = AppColors.surfaceElevated) {
                            Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
                                SectionHeader(title = "Review your answer")
                                Spacer(modifier = Modifier.height(AppSpacing.XS))
                                Text(
                                    text = "\u201C$pendingTranscript\u201D",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = AppColors.contentPrimary
                                )
                                Spacer(modifier = Modifier.height(AppSpacing.SM))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
                                ) {
                                    SecondaryButton(
                                        text = "Listen again",
                                        onClick = { viewModel.onDiscardPendingTranscript() },
                                        modifier = Modifier.weight(1f)
                                    )
                                    PrimaryButton(
                                        text = "Submit",
                                        onClick = { viewModel.onSubmitPendingTranscript() },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    // Evaluation: short verdict first, key points behind one tap (§25).
                    val evaluation = when (val s = studyState) {
                        is StudyState.ShowingFeedback -> s.evaluation
                        is StudyState.WaitingForRating -> s.evaluation
                        else -> null
                    }
                    AnimatedVisibility(visible = evaluation != null) {
                        evaluation?.let { EvaluationCard(evaluation = it) }
                    }

                    (studyState as? StudyState.HintShowing)?.let { s ->
                        IconNoteCard(
                            heading = "Hint",
                            text = s.hintText,
                            icon = Icons.Default.Lightbulb,
                            tint = AppColors.statusWarning
                        )
                    }
                    (studyState as? StudyState.ExplanationShowing)?.let { s ->
                        IconNoteCard(
                            heading = "Explanation",
                            text = s.explanationText,
                            icon = Icons.Default.Info,
                            tint = AppColors.voiceSpeaking
                        )
                    }
                    (studyState as? StudyState.SessionFinished)?.let { s ->
                        AppCard {
                            Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = AppColors.statusSuccess,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(AppSpacing.XS))
                                    Text(
                                        text = "Session complete",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = AppColors.contentPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.height(AppSpacing.XS))
                                Text(
                                    text = s.summary
                                        ?: "You reviewed ${s.cardsReviewed} card${if (s.cardsReviewed == 1) "" else "s"}.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.contentSecondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(AppSpacing.XS))
                }

                // ---------------- Pinned controls ----------------
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AppSpacing.XS, bottom = AppSpacing.MD),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
                ) {
                    PushToTalkButton(
                        isListening = isListening,
                        isProcessing = studyState is StudyState.Evaluating,
                        onPressStart = { viewModel.onPushToTalkDown() },
                        onPressEnd = { viewModel.onPushToTalkUp() },
                        onTapToggle = { viewModel.onPushToTalkToggle() }
                    )

                    QuickToolsRow(
                        onRepeat = { viewModel.onRepeatQuestion() },
                        onHint = { viewModel.onRequestHint() },
                        onExplain = { viewModel.onRequestExplanation() },
                        onSkip = { viewModel.onSkipCard() }
                    )

                    val suggestedRating = when (val s = studyState) {
                        is StudyState.WaitingForRating -> s.suggestedRating
                        is StudyState.ShowingFeedback -> s.evaluation.suggestedRating
                        else -> null
                    }
                    RatingButtonGroup(
                        onRate = { rating -> viewModel.onRateCard(rating) },
                        suggestedRating = suggestedRating,
                        enabled = studyState !is StudyState.Idle && studyState !is StudyState.SessionFinished
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
                    ) {
                        SecondaryButton(
                            text = if (isPaused) "Resume" else "Pause",
                            icon = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            onClick = {
                                if (isPaused) viewModel.onResumeSession() else viewModel.onPauseSession()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag(StudyScreenTags.PAUSE_TOGGLE)
                        )
                        DestructiveButton(
                            text = "End Session",
                            icon = Icons.Default.Stop,
                            onClick = { viewModel.onEndSession() },
                            modifier = Modifier
                                .weight(1f)
                                .testTag(StudyScreenTags.END_SESSION)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Semantics tags for the study controls (§141). Only the instrumented tests read them; the
 * visible labels stay the source of truth for users and for accessibility.
 */
object StudyScreenTags {
    const val PHASE_CHIP = STUDY_PHASE_CHIP_TEST_TAG
    const val PAUSE_TOGGLE = "study_pause_toggle"
    const val END_SESSION = "study_end_session"
}

// ---------------------------------------------------------------------------
// Private pieces
// ---------------------------------------------------------------------------

@Composable
private fun StudyTopBar(
    onBack: () -> Unit,
    connectionBadge: @Composable () -> Unit,
    routeIndicator: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.XS, vertical = AppSpacing.XS),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = AppColors.contentPrimary
            )
        }
        connectionBadge()
        routeIndicator()
    }
}

@Composable
private fun PausedCard(onResume: () -> Unit) {
    AppCard(color = AppColors.statusWarningFill) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Pause,
                contentDescription = null,
                tint = AppColors.statusWarning,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(AppSpacing.SM))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Paused",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.contentPrimary
                )
                Text(
                    text = "Microphone and speech are stopped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.contentSecondary
                )
            }
            Spacer(modifier = Modifier.width(AppSpacing.SM))
            PrimaryButton(text = "Resume", icon = Icons.Default.PlayArrow, onClick = onResume)
        }
    }
}

@Composable
private fun HeadsetLostCard(
    message: String,
    canContinueOnPhone: Boolean,
    canWaitForHeadset: Boolean,
    onContinueOnPhone: () -> Unit,
    onWaitForHeadset: () -> Unit
) {
    AppCard(color = AppColors.surfaceElevated) {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
            SectionHeader(title = "Headphones disconnected", color = AppColors.statusWarning)
            Spacer(modifier = Modifier.height(AppSpacing.XS))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.contentPrimary
            )
            Spacer(modifier = Modifier.height(AppSpacing.SM))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
            ) {
                if (canContinueOnPhone) {
                    PrimaryButton(
                        text = "Continue on phone",
                        onClick = onContinueOnPhone,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (canWaitForHeadset) {
                    SecondaryButton(
                        text = "Wait for headphones",
                        onClick = onWaitForHeadset,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: String?,
    loadingMessage: String?,
    isListening: Boolean,
    isSpeaking: Boolean,
    dimmed: Boolean
) {
    AppHeroCard(modifier = Modifier.alpha(if (dimmed) 0.6f else 1f)) {
        Column(modifier = Modifier.padding(AppSpacing.heroCardPadding)) {
            SectionHeader(title = "Question", color = AppColors.voiceSpeaking)
            Spacer(modifier = Modifier.height(AppSpacing.XS))
            Text(
                text = question
                    ?: loadingMessage
                    ?: "Press 'Start' to begin loading cards from your Study Agent.",
                style = MaterialTheme.typography.headlineSmall,
                color = if (question != null) AppColors.contentPrimary else AppColors.contentSecondary
            )
            Spacer(modifier = Modifier.height(AppSpacing.MD))
            // Activity only — the RMS level is intentionally not plumbed into this screen.
            VoiceWaveVisualizer(
                isActive = isListening || isSpeaking,
                color = if (isSpeaking) AppColors.voiceSpeaking else AppColors.voiceListening,
                description = when {
                    isSpeaking -> "Speaking"
                    isListening -> "Listening"
                    else -> "Voice idle"
                }
            )
        }
    }
}

@Composable
private fun QuoteCard(heading: String, text: String) {
    AppCard(color = AppColors.surfaceElevated) {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
            SectionHeader(title = heading)
            Spacer(modifier = Modifier.height(AppSpacing.XS))
            Text(
                text = "\u201C$text\u201D",
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.contentPrimary
            )
        }
    }
}

@Composable
private fun IconNoteCard(heading: String, text: String, icon: ImageVector, tint: Color) {
    AppCard(color = AppColors.surfaceElevated) {
        Row(
            modifier = Modifier.padding(AppSpacing.cardPadding),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(AppSpacing.SM))
            Column {
                Text(text = heading, style = MaterialTheme.typography.labelLarge, color = tint)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = text, style = MaterialTheme.typography.bodyMedium, color = AppColors.contentPrimary)
            }
        }
    }
}

@Composable
private fun EvaluationCard(evaluation: Evaluation) {
    var detailsExpanded by rememberSaveable(evaluation) { mutableStateOf(false) }
    val hasDetails = evaluation.correctPoints.isNotEmpty() ||
        evaluation.missingPoints.isNotEmpty() ||
        evaluation.incorrectPoints.isNotEmpty()
    val score = evaluation.score
    val scoreColor = when {
        score == null -> AppColors.contentSecondary
        score >= 80 -> AppColors.statusSuccess
        score >= 50 -> AppColors.statusWarning
        else -> AppColors.statusDanger
    }

    AppCard {
        Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader(title = "Feedback", color = AppColors.actionPrimary, modifier = Modifier.weight(1f))
                if (score != null) {
                    Text(
                        text = "$score%",
                        style = MaterialTheme.typography.titleMedium,
                        color = scoreColor
                    )
                }
            }
            if (evaluation.shortFeedback.isNotBlank()) {
                Spacer(modifier = Modifier.height(AppSpacing.XS))
                Text(
                    text = evaluation.shortFeedback,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.contentPrimary
                )
            }
            if (hasDetails) {
                Spacer(modifier = Modifier.height(AppSpacing.XS))
                ExpandableSection(
                    title = "Key points",
                    summary = buildString {
                        if (evaluation.correctPoints.isNotEmpty()) append("${evaluation.correctPoints.size} correct")
                        if (evaluation.missingPoints.isNotEmpty()) {
                            if (isNotEmpty()) append(" • ")
                            append("${evaluation.missingPoints.size} missed")
                        }
                        if (evaluation.incorrectPoints.isNotEmpty()) {
                            if (isNotEmpty()) append(" • ")
                            append("${evaluation.incorrectPoints.size} incorrect")
                        }
                    },
                    expanded = detailsExpanded,
                    onToggle = { detailsExpanded = !detailsExpanded }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.XXS)) {
                        evaluation.correctPoints.forEach { pt ->
                            PointRow(pt, Icons.Default.CheckCircle, AppColors.statusSuccess, AppColors.contentPrimary)
                        }
                        evaluation.missingPoints.forEach { pt ->
                            PointRow("Missed: $pt", Icons.Default.Warning, AppColors.statusWarning, AppColors.statusWarning)
                        }
                        evaluation.incorrectPoints.forEach { pt ->
                            PointRow("Incorrect: $pt", Icons.Default.Cancel, AppColors.statusDanger, AppColors.statusDanger)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PointRow(text: String, icon: ImageVector, iconTint: Color, textColor: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = textColor)
    }
}

/** Four secondary tools; labels stay short and single-line so the row survives font scaling. */
@Composable
private fun QuickToolsRow(
    onRepeat: () -> Unit,
    onHint: () -> Unit,
    onExplain: () -> Unit,
    onSkip: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
    ) {
        QuickTool("Repeat", Icons.Default.Replay, onRepeat, Modifier.weight(1f))
        QuickTool("Hint", Icons.Default.Lightbulb, onHint, Modifier.weight(1f))
        QuickTool("Explain", Icons.Default.Info, onExplain, Modifier.weight(1f))
        QuickTool("Skip", Icons.Default.SkipNext, onSkip, Modifier.weight(1f))
    }
}

@Composable
private fun QuickTool(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = AppShape.buttonShape,
        contentPadding = PaddingValues(horizontal = AppSpacing.XS, vertical = AppSpacing.XS),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = AppColors.surfaceInteractive,
            contentColor = AppColors.contentPrimary
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
