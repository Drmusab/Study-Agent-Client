package com.studyagent.client.ui.screens.customstudy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

/**
 * GATE 20 — the custom-study panel on a deck (IMPLEMENTATION-20-15/16/17, AUDIT-20-14).
 *
 * It renders **capability truth only**:
 *
 * - the scope the backend will really use (this deck *and its descendants*, because that is how
 *   Anki builds a queue — never a Study-Agent deck walk);
 * - what answers do to scheduling, before the first rating;
 * - each locked custom-study option the backend cannot honour, with the reason;
 * - a blocking notice when the deck cannot be studied at all, instead of a Start button that would
 *   fail later.
 *
 * There is no filter control that the locked contract does not support, and nothing here selects,
 * orders or counts a card (INV-20-03/INV-20-04/INV-20-06).
 */
@Composable
fun CustomStudyPanel(
    state: CustomStudyPanelState,
    modifier: Modifier = Modifier
) {
    Surface(
        color = AppColors.surfacePrimary,
        shape = AppShape.heroCardShape,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.heroCardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
        ) {
            Text("Custom study", style = MaterialTheme.typography.titleMedium, color = AppColors.contentPrimary)

            Text(
                text = "Source deck: ${state.deckName}",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.contentSecondary
            )
            Text(
                text = if (state.includesChildDecks) {
                    "Includes subdecks — Anki builds the review queue from this deck and its descendants."
                } else {
                    "This deck only."
                },
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.contentMuted
            )
            if (state.deckIsFiltered == null) {
                Text(
                    text = "This backend did not say whether the deck is filtered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.contentMuted
                )
            }

            Text(
                text = state.schedulingSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.contentPrimary
            )

            state.blockingNotice?.let { notice ->
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
                    Text(
                        text = notice.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = AppColors.statusWarning
                    )
                    Text(
                        text = notice.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.contentSecondary
                    )
                }
            }

            if (!state.backendSupportsAnyCustomStudy) {
                Text(
                    text = "This backend offers no custom-study filters. Ordinary deck review below " +
                        "remains fully available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.contentMuted
                )
            }
            state.unsupportedOptions.forEach { option ->
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.XXS)) {
                    Text(
                        text = "${option.title} — not available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.contentSecondary
                    )
                    Text(
                        text = option.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.contentMuted
                    )
                }
            }
        }
    }
}
