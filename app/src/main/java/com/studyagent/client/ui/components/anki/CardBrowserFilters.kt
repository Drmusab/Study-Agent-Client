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
import androidx.compose.ui.unit.dp
import com.studyagent.client.core.anki.AnkiCardBrowserCapabilities
import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardType
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppSpacing

/** Reusable, read-only filters. A filter is rendered only when the active backend advertises it. */
@Composable
fun CardBrowserFilters(
    capabilities: AnkiCardBrowserCapabilities,
    filters: AnkiCardFilters,
    onFiltersChanged: (AnkiCardFilters) -> Unit,
    modifier: Modifier = Modifier
) {
    val hasAnyFilter = capabilities.flagFilter || capabilities.tagFilter ||
        capabilities.cardTypeFilter || capabilities.suspendedFilter || capabilities.buriedFilter
    if (!hasAnyFilter) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)
    ) {
        Text("Filters", style = MaterialTheme.typography.labelLarge, color = AppColors.contentSecondary)

        if (capabilities.flagFilter) {
            FilterGroupLabel("Flag")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
            ) {
                AnkiFlag.entries.forEach { flag ->
                    val selected = flag in filters.flags
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val next = filters.flags.toMutableSet().apply {
                                if (selected) remove(flag) else add(flag)
                            }
                            onFiltersChanged(filters.copy(flags = next))
                        },
                        label = { Text(flag.browserLabel()) },
                        modifier = Modifier.semantics {
                            contentDescription = "${flag.browserLabel()} flag filter ${if (selected) "selected" else "not selected"}"
                        }
                    )
                }
            }
        }

        if (capabilities.cardTypeFilter) {
            FilterGroupLabel("Card type")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
            ) {
                AnkiCardType.entries.forEach { type ->
                    val selected = type in filters.cardTypes
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val next = filters.cardTypes.toMutableSet().apply {
                                if (selected) remove(type) else add(type)
                            }
                            onFiltersChanged(filters.copy(cardTypes = next))
                        },
                        label = { Text(type.browserLabel()) },
                        modifier = Modifier.semantics {
                            contentDescription = "${type.browserLabel()} card type filter ${if (selected) "selected" else "not selected"}"
                        }
                    )
                }
            }
        }

        if (capabilities.tagFilter) {
            var tagDraft by remember(filters.tags) { mutableStateOf("") }
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
                        val tag = tagDraft.trim()
                        if (tag.isNotEmpty()) onFiltersChanged(filters.copy(tags = filters.tags + tag))
                        tagDraft = ""
                    },
                    enabled = tagDraft.isNotBlank()
                ) { Text("Add") }
            }
            if (filters.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
                ) {
                    filters.tags.sorted().forEach { tag ->
                        FilterChip(
                            selected = true,
                            onClick = { onFiltersChanged(filters.copy(tags = filters.tags - tag)) },
                            label = { Text(tag, maxLines = 1) },
                            modifier = Modifier.semantics { contentDescription = "Remove tag filter $tag" }
                        )
                    }
                }
            }
        }

        if (capabilities.suspendedFilter) {
            TriStateFilterChip(
                label = "Suspended",
                value = filters.suspended,
                onClick = { onFiltersChanged(filters.copy(suspended = nextTriState(filters.suspended))) }
            )
        }
        if (capabilities.buriedFilter) {
            TriStateFilterChip(
                label = "Buried",
                value = filters.buried,
                onClick = { onFiltersChanged(filters.copy(buried = nextTriState(filters.buried))) }
            )
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
private fun FilterGroupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = AppColors.contentMuted)
}

@Composable
private fun TriStateFilterChip(label: String, value: Boolean?, onClick: () -> Unit) {
    val valueLabel = when (value) {
        null -> "Any"
        true -> "Only"
        false -> "Exclude"
    }
    FilterChip(
        selected = value != null,
        onClick = onClick,
        label = { Text("$label: $valueLabel") },
        modifier = Modifier.semantics { contentDescription = "$label filter, $valueLabel. Activate to cycle." }
    )
}

private fun nextTriState(value: Boolean?): Boolean? = when (value) {
    null -> true
    true -> false
    false -> null
}

private fun AnkiFlag.browserLabel(): String = when (this) {
    AnkiFlag.NONE -> "No flag"
    AnkiFlag.RED -> "Red"
    AnkiFlag.ORANGE -> "Orange"
    AnkiFlag.GREEN -> "Green"
    AnkiFlag.BLUE -> "Blue"
    AnkiFlag.PINK -> "Pink"
    AnkiFlag.TURQUOISE -> "Turquoise"
    AnkiFlag.PURPLE -> "Purple"
    AnkiFlag.UNKNOWN -> "Unknown flag"
}

private fun AnkiCardType.browserLabel(): String = when (this) {
    AnkiCardType.NEW -> "New"
    AnkiCardType.LEARNING -> "Learning"
    AnkiCardType.REVIEW -> "Review"
    AnkiCardType.RELEARNING -> "Relearning"
    AnkiCardType.UNKNOWN -> "Unknown type"
}
