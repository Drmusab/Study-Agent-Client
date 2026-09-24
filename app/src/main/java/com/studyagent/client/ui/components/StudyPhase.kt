package com.studyagent.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

/**
 * The presentation phase of a study turn — *what the user should be doing right now*.
 *
 * This is the single place that maps the protocol state machine to UI language.
 * The Study screen renders [StudyPhaseChip] + [StudySessionHeader] from it and never
 * re-derives phase copy inline (§23).
 */
enum class StudyPhase {
    IDLE,
    LOADING,
    SPEAKING,
    LISTENING,
    /** Microphone closed; a completed answer awaits Submit / Try again. */
    REVIEW,
    EVALUATING,
    FEEDBACK,
    RATING,
    HINT,
    EXPLANATION,
    PAUSED,
    FINISHED,
    ERROR
}

/**
 * GATE 11 — rating buttons are enabled only where the session machine can accept a rating
 * (waiting for a rating, or feedback that permits an early rating). While a rating is being
 * saved (`Loading` with a pending rating), or a rating transaction failed/is unconfirmed, they are
 * disabled so a double tap cannot even produce a second intent. The reducer remains the authority;
 * this only stops the UI from offering an action that would be rejected.
 */
fun ratingControlsEnabled(state: StudyState): Boolean =
    state is StudyState.WaitingForRating || state is StudyState.ShowingFeedback

/** Pure mapping: protocol state → presentation phase (unit-testable, §23). */
fun studyPhaseOf(state: StudyState): StudyPhase = when (state) {
    is StudyState.Idle -> StudyPhase.IDLE
    is StudyState.Loading -> StudyPhase.LOADING
    is StudyState.SpeakingQuestion -> StudyPhase.SPEAKING
    is StudyState.Listening ->
        if (state.hasPendingTranscript) StudyPhase.REVIEW else StudyPhase.LISTENING
    is StudyState.Evaluating -> StudyPhase.EVALUATING
    is StudyState.ShowingFeedback -> StudyPhase.FEEDBACK
    is StudyState.WaitingForRating -> StudyPhase.RATING
    is StudyState.HintShowing -> StudyPhase.HINT
    is StudyState.ExplanationShowing -> StudyPhase.EXPLANATION
    is StudyState.Paused -> StudyPhase.PAUSED
    is StudyState.SessionFinished -> StudyPhase.FINISHED
    is StudyState.Error -> StudyPhase.ERROR
}

/** Visual identity of a phase: icon + text + color (never color alone, §23/§65). */
data class PhaseVisual(
    val label: String,
    val icon: ImageVector,
    val color: Color
) {
    companion object {
        fun of(phase: StudyPhase): PhaseVisual = when (phase) {
            StudyPhase.IDLE ->
                PhaseVisual("Idle", Icons.Default.PlayCircle, AppColors.statusNeutral)
            StudyPhase.LOADING ->
                PhaseVisual("Loading…", Icons.Default.HourglassTop, AppColors.contentSecondary)
            StudyPhase.SPEAKING ->
                PhaseVisual("Speaking", Icons.Default.VolumeUp, AppColors.voiceSpeaking)
            StudyPhase.LISTENING ->
                PhaseVisual("Listening", Icons.Default.Mic, AppColors.voiceListening)
            StudyPhase.REVIEW ->
                PhaseVisual("Review answer", Icons.Default.Edit, AppColors.contentSecondary)
            StudyPhase.EVALUATING ->
                PhaseVisual("Evaluating", Icons.Default.Autorenew, AppColors.voiceProcessing)
            StudyPhase.FEEDBACK ->
                PhaseVisual("Feedback", Icons.Default.FormatQuote, AppColors.voiceSpeaking)
            StudyPhase.RATING ->
                PhaseVisual("Rate this card", Icons.Default.Star, AppColors.statusWarning)
            StudyPhase.HINT ->
                PhaseVisual("Hint", Icons.Default.Lightbulb, AppColors.statusWarning)
            StudyPhase.EXPLANATION ->
                PhaseVisual("Explanation", Icons.Default.Info, AppColors.voiceSpeaking)
            StudyPhase.PAUSED ->
                PhaseVisual("Paused", Icons.Default.Pause, AppColors.statusWarning)
            StudyPhase.FINISHED ->
                PhaseVisual("Completed", Icons.Default.CheckCircle, AppColors.statusSuccess)
            StudyPhase.ERROR ->
                PhaseVisual("Needs attention", Icons.Default.Warning, AppColors.statusDanger)
        }
    }
}

/**
 * Explicit phase chip: icon + text + color, one line, always visible (§23).
 *
 * The phase transition is animated with a short fade so the user instantly sees
 * when the turn flips from Speaking → Listening (§72). Colour is never the only
 * cue — icon and text animate together.
 *
 * Carries the `study_phase_chip` semantics tag read by the instrumented suite.
 */
@Composable
fun StudyPhaseChip(
    phase: StudyPhase,
    modifier: Modifier = Modifier
) {
    val visual = PhaseVisual.of(phase)
    AnimatedContent(
        targetState = phase,
        transitionSpec = {
            fadeIn(animationSpec = androidx.compose.animation.core.tween(180)) togetherWith
                fadeOut(animationSpec = androidx.compose.animation.core.tween(150))
        },
        label = "phaseChip",
        modifier = modifier
            .clip(AppShape.chipShape)
            .background(visual.color.copy(alpha = 0.14f))
            .heightIn(min = 32.dp)
            .padding(horizontal = AppSpacing.SM, vertical = AppSpacing.XS)
            .semantics { contentDescription = "Study phase: ${visual.label}" }
            .testTag(STUDY_PHASE_CHIP_TEST_TAG)
    ) { targetPhase ->
        val targetVisual = PhaseVisual.of(targetPhase)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = targetVisual.icon,
                contentDescription = null,
                tint = targetVisual.color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = targetVisual.label,
                style = MaterialTheme.typography.labelLarge,
                color = targetVisual.color
            )
        }
    }
}

/**
 * Session header: deck + card progress + current phase.
 *
 * Replaces the old emoji header — the phase is communicated exactly once, by
 * [StudyPhaseChip], with icon + text + color (§23).
 */
@Composable
fun StudySessionHeader(
    deckName: String,
    cardNumber: Int,
    remainingCards: Int,
    phase: StudyPhase,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppShape.cardShape,
        color = AppColors.surfacePrimary
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (deckName.isBlank()) "Study Session" else deckName,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.contentPrimary,
                    maxLines = 2
                )
                if (cardNumber > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append("Card ").append(cardNumber)
                            if (remainingCards > 0) append(" • ").append(remainingCards).append(" left")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.contentSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.width(AppSpacing.XS))
            StudyPhaseChip(phase = phase)
        }
    }
}

/**
 * Semantics tag for the study phase chip, consumed by the instrumented suite.
 * Kept in the components layer (old home was StudyScreen).
 */
const val STUDY_PHASE_CHIP_TEST_TAG = "study_phase_chip"

// ---------------------------------------------------------------------------
// Previews (fake static data only — no repositories, §90)
// ---------------------------------------------------------------------------

@Preview(name = "Phase chip — listening", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun StudyPhaseChipListeningPreview() {
    StudyPhaseChip(phase = StudyPhase.LISTENING)
}

@Preview(name = "Phase chip — paused", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun StudyPhaseChipPausedPreview() {
    StudyPhaseChip(phase = StudyPhase.PAUSED)
}

@Preview(name = "Session header", showBackground = true, backgroundColor = 0xFF0F172A)
@Composable
private fun StudySessionHeaderPreview() {
    StudySessionHeader(
        deckName = "MCCQE::Cardiology",
        cardNumber = 37,
        remainingCards = 146,
        phase = StudyPhase.LISTENING
    )
}
