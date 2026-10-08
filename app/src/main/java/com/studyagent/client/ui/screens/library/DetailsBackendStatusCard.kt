package com.studyagent.client.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.ui.components.SecondaryButton
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

@Composable
internal fun DetailsBackendStatusCard(
    availability: AnkiAvailability,
    capabilities: AnkiCapabilities,
    onRetry: () -> Unit
) {
    val ready = availability is AnkiAvailability.Ready
    val tint = if (ready) AppColors.statusSuccess else AppColors.statusWarning
    Surface(color = AppColors.surfacePrimary, shape = AppShape.cardShape, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = if (ready) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = tint
                )
                Column(
                    modifier = Modifier.weight(1f).padding(start = AppSpacing.SM),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.XXS)
                ) {
                    Text(
                        text = "Backend · ${availability.displayLabel()}",
                        style = MaterialTheme.typography.titleSmall,
                        color = tint
                    )
                    Text(
                        text = if (ready) {
                            when {
                                capabilities.review && capabilities.scheduledReview -> "Deck browsing and scheduled study are supported."
                                capabilities.deckListing -> "Deck browsing is available; review is not supported."
                                else -> "Deck browsing is not supported by this backend."
                            }
                        } else availability.displayMessage(),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.contentSecondary
                    )
                }
            }
            if (!ready) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SecondaryButton("Retry", onRetry, icon = Icons.Default.Refresh)
                }
            }
        }
    }
}
