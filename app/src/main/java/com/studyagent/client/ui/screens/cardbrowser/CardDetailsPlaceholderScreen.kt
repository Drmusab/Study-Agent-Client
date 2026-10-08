package com.studyagent.client.ui.screens.cardbrowser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.ui.components.StudyAgentTopBar
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppSpacing

/** GATE 15 navigation target only; GATE 16 owns one-card hydration and full details presentation. */
@Composable
fun CardDetailsPlaceholderScreen(
    cardRef: AnkiCardRef?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        containerColor = AppColors.appBackground,
        topBar = { StudyAgentTopBar(title = "Card details", onBack = onNavigateBack) },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AppSpacing.contentGutter)
                .semantics(mergeDescendants = true) {
                    contentDescription = if (cardRef == null) {
                        "Invalid card reference. Card details will be implemented in Gate 16."
                    } else {
                        "Card reference received. Full read-only card details will be implemented in Gate 16."
                    }
                },
            verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
        ) {
            Text(
                text = if (cardRef == null) "The card reference could not be opened." else "Card Details are planned for GATE 16.",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.contentPrimary
            )
            Text(
                text = if (cardRef == null) {
                    "Return to the browser and open a card again."
                } else {
                    "This route received a stable card reference. No card content was hydrated in GATE 15."
                },
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.contentSecondary
            )
        }
    }
}
