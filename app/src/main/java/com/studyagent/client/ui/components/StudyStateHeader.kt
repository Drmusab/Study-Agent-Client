package com.studyagent.client.ui.components

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.ui.theme.AccentTeal
import com.studyagent.client.ui.theme.DarkSurfaceElevated
import com.studyagent.client.ui.theme.PrimaryBlue
import com.studyagent.client.ui.theme.StatusAmber
import com.studyagent.client.ui.theme.StatusGreen
import com.studyagent.client.ui.theme.StatusPurple
import com.studyagent.client.ui.theme.StatusRed
import com.studyagent.client.ui.theme.TextMuted
import com.studyagent.client.ui.theme.TextPrimary
import com.studyagent.client.ui.theme.TextSecondary

@Composable
fun StudyStateHeader(
    deckName: String,
    cardNumber: Int,
    remainingCards: Int,
    state: StudyState,
    modifier: Modifier = Modifier
) {
    val (statusLabel, statusColor) = when (state) {
        is StudyState.SpeakingQuestion -> Pair("🔊 Speaking Question", PrimaryBlue)
        is StudyState.Listening -> Pair("🎧 Listening to Answer", StatusGreen)
        is StudyState.Evaluating -> Pair("🧠 PC Evaluating...", StatusPurple)
        is StudyState.ShowingFeedback -> Pair("🔊 Feedback", AccentTeal)
        is StudyState.WaitingForRating -> Pair("⭐ Rate Card (Again / Hard / Good / Easy)", StatusAmber)
        is StudyState.HintShowing -> Pair("💡 Hint", StatusAmber)
        is StudyState.ExplanationShowing -> Pair("📖 Explanation", AccentTeal)
        is StudyState.Paused -> Pair("⏸️ Paused", StatusAmber)
        is StudyState.Loading -> Pair("⏳ ${state.message}", TextSecondary)
        is StudyState.SessionFinished -> Pair("🎉 Completed", StatusGreen)
        is StudyState.Error -> Pair("⚠️ ${state.message}", StatusRed)
        is StudyState.Idle -> Pair("Ready", TextMuted)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurfaceElevated)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = deckName,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            if (cardNumber > 0) {
                Text(
                    text = "Card $cardNumber" + (if (remainingCards > 0) " / $remainingCards left" else ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentTeal
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}
