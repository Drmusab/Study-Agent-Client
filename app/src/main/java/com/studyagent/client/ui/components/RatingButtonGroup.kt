package com.studyagent.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studyagent.client.core.models.Rating
import com.studyagent.client.ui.theme.RatingAgain
import com.studyagent.client.ui.theme.RatingEasy
import com.studyagent.client.ui.theme.RatingGood
import com.studyagent.client.ui.theme.RatingHard
import com.studyagent.client.ui.theme.TextPrimary

@Composable
fun RatingButtonGroup(
    onRate: (Rating) -> Unit,
    suggestedRating: Rating? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val ratings = listOf(
            Triple(Rating.AGAIN, "Again", RatingAgain),
            Triple(Rating.HARD, "Hard", RatingHard),
            Triple(Rating.GOOD, "Good", RatingGood),
            Triple(Rating.EASY, "Easy", RatingEasy)
        )

        for ((rating, label, color) in ratings) {
            val isSuggested = rating == suggestedRating
            Button(
                onClick = { onRate(rating) },
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = color,
                    contentColor = TextPrimary,
                    disabledContainerColor = color.copy(alpha = 0.4f)
                ),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text(
                    text = if (isSuggested) "★ $label" else label,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                    maxLines = 1
                )
            }
        }
    }
}
