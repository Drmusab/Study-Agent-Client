package com.studyagent.client.ui.components.anki

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.mergeDescendants
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import com.studyagent.client.ui.screens.carddetails.MetadataRow
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

/** Reusable read-only label/value section (GATE 16 CHECKPOINT 16). */
@Composable
fun CardMetadataSection(
    title: String,
    rows: List<MetadataRow>,
    modifier: Modifier = Modifier,
    emptyMessage: String? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppShape.cardShape,
        color = AppColors.surfacePrimary
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.contentPrimary)
            if (rows.isEmpty()) {
                if (emptyMessage != null) {
                    Text(
                        text = emptyMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.contentSecondary
                    )
                }
            } else {
                rows.forEach { row ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                contentDescription = row.contentDescription
                            },
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.XXS)
                    ) {
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
                            color = AppColors.contentSecondary
                        )
                        Text(
                            text = row.value ?: "Not available",
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                            color = if (row.value == null) AppColors.contentMuted else AppColors.contentPrimary
                        )
                    }
                }
            }
        }
    }
}
