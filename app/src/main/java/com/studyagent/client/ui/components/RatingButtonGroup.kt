package com.studyagent.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.studyagent.client.core.models.Rating
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

/**
 * Semantics tag for one rating button, e.g. `rating_good`.
 *
 * Exposed so the instrumented tests can assert per-rating identity and touch target
 * size without depending on the visible label.
 */
fun ratingTestTag(rating: Rating): String = "rating_${rating.name.lowercase()}"

private data class RatingVisual(val label: String, val text: Color, val fill: Color)

/**
 * AA-checked visual identity per rating. Non-suggested buttons are tinted (16 % fill,
 * bright text); the suggested rating is filled solid with white text and a ★ prefix so
 * the suggestion is never encoded in colour alone (§30/§65).
 */
private fun ratingVisual(rating: Rating): RatingVisual = when (rating) {
    Rating.AGAIN -> RatingVisual("Again", AppColors.ratingAgainText, AppColors.ratingAgainFill)
    Rating.HARD -> RatingVisual("Hard", AppColors.ratingHardText, AppColors.ratingHardFill)
    Rating.GOOD -> RatingVisual("Good", AppColors.ratingGoodText, AppColors.ratingGoodFill)
    Rating.EASY -> RatingVisual("Easy", AppColors.ratingEasyText, AppColors.ratingEasyFill)
}

/**
 * Again / Hard / Good / Easy — the most critical actions after evaluation (§31).
 *
 * Layout is responsive (§32): a single row on wide screens, a 2×2 grid below
 * [AppSpacing.ratingGridBreakpoint] so four buttons are never compressed into
 * sub-target widths. Each button is ≥52dp tall.
 */
@Composable
fun RatingButtonGroup(
    onRate: (Rating) -> Unit,
    suggestedRating: Rating? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val ratings = remember { listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY) }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val useGrid = maxWidth < AppSpacing.ratingGridBreakpoint
        if (useGrid) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
                RatingRow(ratings.subList(0, 2), suggestedRating, enabled, onRate)
                RatingRow(ratings.subList(2, 4), suggestedRating, enabled, onRate)
            }
        } else {
            RatingRow(ratings, suggestedRating, enabled, onRate)
        }
    }
}

@Composable
private fun RatingRow(
    ratings: List<Rating>,
    suggestedRating: Rating?,
    enabled: Boolean,
    onRate: (Rating) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
    ) {
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

@Composable
private fun RatingButton(
    rating: Rating,
    suggested: Boolean,
    enabled: Boolean,
    onRate: (Rating) -> Unit,
    modifier: Modifier = Modifier
) {
    val visual = ratingVisual(rating)
    val description = buildString {
        append(visual.label).append(" rating")
        if (suggested) append(", suggested")
        if (!enabled) append(", unavailable")
    }
    Button(
        onClick = { onRate(rating) },
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 52.dp)
            .testTag(ratingTestTag(rating))
            .semantics { contentDescription = description },
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
// Previews (fake static data only — no repositories)
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
