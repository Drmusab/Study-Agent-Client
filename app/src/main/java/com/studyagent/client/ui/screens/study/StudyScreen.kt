package com.studyagent.client.ui.screens.study

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.ui.components.AudioRouteIndicator
import com.studyagent.client.ui.components.ConnectionBadge
import com.studyagent.client.ui.components.PushToTalkButton
import com.studyagent.client.ui.components.RatingButtonGroup
import com.studyagent.client.ui.components.StudyStateHeader
import com.studyagent.client.ui.components.VoiceWaveVisualizer
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

@Composable
fun StudyScreen(
    viewModel: StudyViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studyState by viewModel.studyState.collectAsState()
    val session by viewModel.currentSession.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val audioDevice by viewModel.activeAudioDevice.collectAsState()
    val isHeadset by viewModel.isHeadsetConnected.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()

    val currentCard = studyState.currentCardOrNull ?: session?.currentCard
    val isPaused = studyState is StudyState.Paused

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                ConnectionBadge(
                    connectionState = connectionState,
                    onClick = onNavigateToConnection
                )
                AudioRouteIndicator(
                    audioDevice = audioDevice,
                    isHeadset = isHeadset
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                StudyStateHeader(
                    deckName = session?.deckName ?: "Study Session",
                    cardNumber = session?.cardNumber ?: 0,
                    remainingCards = session?.remainingCards ?: 0,
                    state = studyState
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Question Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "QUESTION",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentTeal
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = currentCard?.question ?: "Press 'Start' to begin loading cards from PC agent...",
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
                            color = TextPrimary
                        )

                        // Voice Waveform
                        Spacer(modifier = Modifier.height(16.dp))
                        VoiceWaveVisualizer(
                            isActive = isListening || isSpeaking,
                            color = if (isSpeaking) AccentTeal else PrimaryBlue
                        )
                    }
                }

                // Real-time Transcript display
                val transcriptText = when (val s = studyState) {
                    is StudyState.Listening -> s.partialTranscript
                    is StudyState.Evaluating -> s.userTranscript
                    else -> ""
                }

                AnimatedVisibility(visible = transcriptText.isNotBlank()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "YOUR TRANSCRIPT",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "\"$transcriptText\"",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary
                            )
                        }
                    }
                }

                // AI Evaluation / Feedback Box
                val evaluation = when (val s = studyState) {
                    is StudyState.ShowingFeedback -> s.evaluation
                    is StudyState.WaitingForRating -> s.evaluation
                    else -> null
                }

                AnimatedVisibility(visible = evaluation != null) {
                    evaluation?.let { eval ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "AI FEEDBACK",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = PrimaryBlue
                                    )
                                    eval.score?.let { score ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (score >= 80) StatusGreen.copy(alpha = 0.2f) else StatusAmber.copy(alpha = 0.2f))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = "Score: $score%",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (score >= 80) StatusGreen else StatusAmber
                                            )
                                        }
                                    }
                                }

                                if (eval.shortFeedback.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = eval.shortFeedback,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TextPrimary
                                    )
                                }

                                // Correct points
                                if (eval.correctPoints.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    for (pt in eval.correctPoints) {
                                        Row(modifier = Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(text = pt, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                        }
                                    }
                                }

                                // Missing points
                                if (eval.missingPoints.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    for (pt in eval.missingPoints) {
                                        Row(modifier = Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Warning, contentDescription = null, tint = StatusAmber, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(text = "Missed: $pt", style = MaterialTheme.typography.bodyMedium, color = StatusAmber)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Hint box
                if (studyState is StudyState.HintShowing) {
                    val hint = (studyState as StudyState.HintShowing).hintText
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lightbulb, contentDescription = null, tint = StatusAmber)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(text = hint, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        }
                    }
                }

                // Explanation box
                if (studyState is StudyState.ExplanationShowing) {
                    val exp = (studyState as StudyState.ExplanationShowing).explanationText
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = AccentTeal)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(text = exp, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Bottom controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                // Push-to-Talk button
                PushToTalkButton(
                    isListening = isListening,
                    onPressStart = { viewModel.onPushToTalkDown() },
                    onPressEnd = { viewModel.onPushToTalkUp() },
                    onTapToggle = { viewModel.onPushToTalkToggle() }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Quick study tools
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.onRepeatQuestion() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Repeat", fontSize = 12.sp)
                    }

                    FilledTonalButton(
                        onClick = { viewModel.onRequestHint() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Lightbulb, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Hint", fontSize = 12.sp)
                    }

                    FilledTonalButton(
                        onClick = { viewModel.onRequestExplanation() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Explain", fontSize = 12.sp)
                    }

                    FilledTonalButton(
                        onClick = { viewModel.onSkipCard() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Skip", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Rating buttons
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

                Spacer(modifier = Modifier.height(10.dp))

                // Session controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            if (isPaused) viewModel.onResumeSession() else viewModel.onPauseSession()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isPaused) "Resume" else "Pause")
                    }

                    OutlinedButton(
                        onClick = { viewModel.onEndSession() },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, tint = StatusRed)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("End Session", color = StatusRed)
                    }
                }
            }
        }
    }
}
