package com.studyagent.client.ui.components.anki

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import com.studyagent.client.core.anki.AnkiCardBrowserCapabilities
import com.studyagent.client.core.anki.AnkiCardFilterCapability
import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.ui.screens.cardbrowser.FilterChipModel
import com.studyagent.client.ui.screens.cardbrowser.filterChipGroups
import com.studyagent.client.ui.screens.cardbrowser.toggleChip
import com.studyagent.client.ui.screens.cardbrowser.withTag
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppSpacing

/**
 * Reusable, read-only filters. Only families the active backend advertises are rendered, and every
 * activation path goes through the pure `toggleChip`/`withTag` helpers, so the UI can never drop a
 * filter silently or invent one the backend does not support.
 */
@Composable
fun CardBrowserFilters(
    capabilities: AnkiCardBrowserCapabilities,
    filters: AnkiCardFilters,
    onFiltersChanged: (AnkiCardFilters) -> Unit,
    modifier: Modifier = Modifier
) {
    val groups = filterChipGroups(capabilities, filters)
    val showsExactTagEntry = AnkiCardFilterCapability.TAGS in capabilities.supportedFilters
    if (groups.isEmpty() && !showsExactTagEntry) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)
    ) {
        Text("Filters", style = MaterialTheme.typography.labelLarge, color = AppColors.contentSecondary)

        if (showsExactTagEntry) {
            ExactTagEntry(
                onAddTag = { tag -> onFiltersChanged(filters.withTag(tag)) }
            )
        }

        groups.forEach { group ->
            if (group.chips.isEmpty()) return@forEach
            Text(group.label, style = MaterialTheme.typography.labelMedium, color = AppColors.contentMuted)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
            ) {
                group.chips.forEach { chip ->
                    FilterChipView(
                        chip = chip,
                        onClick = { onFiltersChanged(filters.toggleChip(chip.key)) }
                    )
                }
            }
        }

        if (!filters.isEmpty) {
            TextButton(
                onClick = { onFiltersChanged(AnkiCardFilters()) },
                modifier = Modifier.semantics { contentDescription = "Clear all card filters" }
            ) { Text("Clear filters", color = AppColors.actionAccent) }
        }
    }
}

@Composable
private fun FilterChipView(chip: FilterChipModel, onClick: () -> Unit) {
    FilterChip(
        selected = chip.selected,
        onClick = onClick,
        label = { Text(chip.label, maxLines = 1) },
        modifier = Modifier.semantics {
            contentDescription =
                "${chip.contentDescription} ${if (chip.selected) "selected" else "not selected"}"
        }
    )
}

@Composable
private fun ExactTagEntry(onAddTag: (String) -> Unit) {
    var tagDraft by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
    ) {
        OutlinedTextField(
            value = tagDraft,
            onValueChange = { tagDraft = it },
            label = { Text("Exact tag") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrLtr)
        )
        Button(
            onClick = {
                onAddTag(tagDraft)
                tagDraft = ""
            },
            enabled = tagDraft.isNotBlank()
        ) { Text("Add") }
    }
}
