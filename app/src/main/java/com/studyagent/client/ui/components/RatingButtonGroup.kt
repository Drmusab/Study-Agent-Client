package com.studyagent.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.spacedBy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studyagent.client.core.models.Rating
import com.studyagent.client.ui.theme.RatingAgain
import com.studyagent.client.ui.theme.RatingEasy
import com.studyagent.client.ui.theme.RatingGood
import com.studyagent.client.ui.theme.RatingHard
import com.studyagent.client.ui.theme.TextPrimary
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

/**
 * Semantics tag for one rating button, e.g. `rating_good`.
 *
 * Exposed so the instrumented tests can assert per-rating identity and touch target size without
 * depending on the visible label (§141).
 */
fun ratingTestTag(rating: Rating): String = "rating_${rating.name.lowercase()}"
private data class RatingVisual(val label: String, val text: Color, val fill: Color)

/**
 * AA-checked visual identity per rating. Text tones are bright enough for dark surfaces;
 * the suggested rating uses the saturated fill with dark text (≥4.5:1) plus a star so the
 * suggestion is not encoded in color alone (§30/§31/§65).
 */
private fun ratingVisual(rating: Rating): RatingVisual = when (rating) {
    Rating.AGAIN -> RatingVisual("Again", AppColors.ratingAgainText, AppColors.statusDangerFill)
    Rating.HARD -> RatingVisual("Hard", AppColors.ratingHardText, Color(0xFFEA580C))
    Rating.GOOD -> RatingVisual("Good", AppColors.ratingGoodText, AppColors.statusSuccessFill)
    Rating.EASY -> RatingVisual("Easy", AppColors.ratingEasyText, AppColors.statusInfoFill)
}

/**
 * Again / Hard / Good / Easy — the most critical actions after evaluation (§31).
 *
 * Layout is responsive (§32): a single row on wide screens, a 2×2 grid below the
 * [AppSpacing.ratingGridBreakpoint] so four buttons are never compressed into
 * sub-target widths. Each button is ≥52dp tall with clear text and semantic color.
 */
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
    val ratings = remember { listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY) }

        for ((rating, label, color) in ratings) {
            val isSuggested = rating == suggestedRating
            Button(
                onClick = { onRate(rating) },
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag(ratingTestTag(rating)),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = color,
                    contentColor = TextPrimary,
                    disabledContainerColor = color.copy(alpha = 0.4f)
                ),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val useGrid = maxWidth < AppSpacing.ratingGridBreakpoint
        if (useGrid) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
                ) {
                    ratings.subList(0, 2).forEach { rating ->
                        RatingButton(
                            rating = rating,
                            suggested = rating == suggestedRating,
                            enabled = enabled,
                            onRate = onRate,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
                ) {
                    ratings.subList(2, 4).forEach { rating ->
                        RatingButton(
                            rating = rating,
                            suggested = rating == suggestedRating,
                            enabled = enabled,
                            onRate = onRate,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
            ) {
                Text(
                    text = if (isSuggested) "★ $label" else label,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                    maxLines = 1
                )
                ratings.forEach { rating ->
                    RatingButton(
                        rating = rating,
                        suggested = rating == suggestedRating,
                        enabled = enabled,
                        onRate = onRate,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RatingButton(
    rating: Rating,
    suggested: Boolean,
    enabled: Boolean,
    onRate: (Rating) -> Unit,
    modifier: Modifier = Modifier
) {
    val visual = ratingVisual(rating)
    Button(
        onClick = { onRate(rating) },
        enabled = enabled,
        modifier = modifier
            .height(52.dp)
            .testTag(ratingTestTag(rating))
            .semantics {
                contentDescription = "${visual.label} rating" +
                    if (suggested) ", suggested" else "" +
                    if (!enabled) ", disabled" else ""
            },
        shape = AppShape.buttonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (suggested) visual.fill else visual.fill.copy(alpha = 0.16f),
            contentColor = if (suggested) AppColors.onRatingFill else visual.text,
            disabledContainerColor = AppColors.surfaceInteractive.copy(alpha = 0.5f),
            disabledContentColor = AppColors.contentMuted
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
    ) {
        Text(
            text = if (suggested) "★ ${visual.label}" else visual.label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------
// Previews (fake static data only — no repositories, §90)
// ---------------------------------------------------------------------------

@Preview(name = "Ratings — row, none suggested", showBackground = true, backgroundColor = 0xFF0F172A, widthDp = 480)
@Composable
private fun RatingGroupRowPreview() {
    RatingButtonGroup(onRate = {})
}

@Preview(name = "Ratings — row, Good suggested", showBackground = true, backgroundColor = 0xFF0F172A, widthDp = 480)
@Composable
private fun RatingGroupSuggestedPreview() {
    RatingButtonGroup(onRate = {}, suggestedRating = Rating.GOOD)
}

@Preview(name = "Ratings — grid on narrow phone", showBackground = true, backgroundColor = 0xFF0F172A, widthDp = 360)
@Composable
private fun RatingGroupGridPreview() {
    RatingButtonGroup(onRate = {}, suggestedRating = Rating.GOOD)
}

@Preview(name = "Ratings — disabled", showBackground = true, backgroundColor = 0xFF0F172A, widthDp = 360)
@Composable
private fun RatingGroupDisabledPreview() {
    RatingButtonGroup(onRate = {}, enabled = false)
}
