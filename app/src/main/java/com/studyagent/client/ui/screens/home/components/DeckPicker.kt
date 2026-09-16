package com.studyagent.client.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.studyagent.client.core.models.DeckSummary
import com.studyagent.client.ui.components.InlineTextButton
import com.studyagent.client.ui.screens.home.DashboardUiMapper
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

// ---------------------------------------------------------------------------
// Active deck card (§27). Deck data is server-sourced; the raw deck identifier
// is preserved verbatim for the server (§29).
// ---------------------------------------------------------------------------

@Composable
fun ActiveDeckCard(
    selectedDeckName: String?,
    selectedDeck: DeckSummary?,
    deckUnavailable: Boolean,
    decksSupported: Boolean,
    onChangeDeck: () -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(
        title = "Active Deck",
        modifier = modifier,
        action = {
            if (decksSupported) {
                InlineTextButton(text = "Change", onClick = onChangeDeck)
            }
        }
    ) {
        when {
            deckUnavailable -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = AppColors.statusWarning,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.XS))
                    Text(
                        text = "Selected deck unavailable",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.statusWarning
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.XXS))
                Text(
                    text = "\"$selectedDeckName\" is no longer reported by the Study Agent. Choose another deck.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.contentSecondary
                )
            }
            selectedDeck != null -> DeckDetails(selectedDeck)
            selectedDeckName != null -> {
                Text(
                    text = DashboardUiMapper.deckDisplayName(selectedDeckName),
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppColors.contentPrimary
                )
                DashboardUiMapper.deckHierarchy(selectedDeckName)?.let { hierarchy ->
                    Text(hierarchy, style = MaterialTheme.typography.bodySmall, color = AppColors.contentMuted)
                }
            }
            else -> Text(
                text = if (decksSupported) "No deck selected" else "Deck selection is managed by the Study Agent",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.contentSecondary
            )
        }
    }
}

@Composable
private fun DeckDetails(deck: DeckSummary) {
    Text(
        text = DashboardUiMapper.deckDisplayName(deck.name),
        style = MaterialTheme.typography.headlineSmall,
        color = AppColors.contentPrimary
    )
    DashboardUiMapper.deckHierarchy(deck.name)?.let { hierarchy ->
        Text(hierarchy, style = MaterialTheme.typography.bodySmall, color = AppColors.contentMuted)
    }
    Spacer(modifier = Modifier.height(AppSpacing.SM))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        StatItem("Due", "${deck.dueCount}", valueColor = AppColors.actionPrimary)
        StatItem("New", "${deck.newCount}", valueColor = AppColors.actionAccent)
        StatItem("Learning", "${deck.learningCount}")
        StatItem("Total", "${deck.totalCount}")
    }
}

// ---------------------------------------------------------------------------
// Deck picker dialog (§28/§29): search, favorites, nested-deck display and a
// LazyColumn so hundreds of decks stay cheap.
// ---------------------------------------------------------------------------

@Composable
fun DeckPickerDialog(
    decks: List<DeckSummary>,
    currentDeckName: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val filtered = remember(query.text, decks) {
        val q = query.text.trim().lowercase()
        val matching = if (q.isEmpty()) decks else decks.filter { it.name.lowercase().contains(q) }
        matching.sortedWith(
            compareByDescending<DeckSummary> { it.isFavorite }
                .thenByDescending { it.dueCount }
                .thenBy { it.name.lowercase() }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp),
            shape = AppShape.dialogShape,
            color = AppColors.surfacePrimary
        ) {
            Column(modifier = Modifier.padding(AppSpacing.MD)) {
                Text("Choose Deck", style = MaterialTheme.typography.titleLarge, color = AppColors.contentPrimary)
                Spacer(modifier = Modifier.height(AppSpacing.SM))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AppShape.fieldShape)
                        .background(AppColors.surfaceElevated)
                        .padding(horizontal = AppSpacing.SM),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = AppColors.contentMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.XS))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = AppColors.contentPrimary),
                        cursorBrush = SolidColor(AppColors.actionPrimary),
                        decorationBox = { inner ->
                            if (query.text.isEmpty()) {
                                Text(
                                    "Search decks…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.contentMuted
                                )
                            }
                            inner()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = AppSpacing.SM)
                            .semantics { contentDescription = "Search decks" }
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.XS))
                if (filtered.isEmpty()) {
                    Text(
                        text = if (decks.isEmpty()) "No decks reported by the Study Agent." else "No decks match your search.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.contentMuted,
                        modifier = Modifier.padding(vertical = AppSpacing.XL)
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(filtered, key = { it.name }) { deck ->
                            DeckPickerRow(
                                deck = deck,
                                isSelected = deck.name == currentDeckName,
                                onClick = { onSelect(deck.name) }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.XS))
                InlineTextButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    color = AppColors.contentSecondary,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

@Composable
private fun DeckPickerRow(deck: DeckSummary, isSelected: Boolean, onClick: () -> Unit) {
    val hierarchy = DashboardUiMapper.deckHierarchy(deck.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShape.fieldShape)
            .background(if (isSelected) AppColors.surfaceElevated else AppColors.surfacePrimary)
            .clickable(onClick = onClick, role = Role.Button)
            .heightIn(min = 52.dp)
            .padding(horizontal = AppSpacing.SM, vertical = AppSpacing.SM)
            .semantics {
                contentDescription = buildString {
                    append(deck.name)
                    append(", ${deck.dueCount} due, ${deck.newCount} new")
                    if (deck.isFavorite) append(", favorite")
                    if (isSelected) append(", selected")
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (deck.isFavorite) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = AppColors.statusWarning,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.XXS))
                }
                Text(
                    text = DashboardUiMapper.deckDisplayName(deck.name),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) AppColors.actionPrimary else AppColors.contentPrimary
                )
            }
            if (hierarchy != null) {
                Text(hierarchy, style = MaterialTheme.typography.labelSmall, color = AppColors.contentMuted)
            }
        }
        Text("${deck.dueCount} due", style = MaterialTheme.typography.bodySmall, color = AppColors.actionPrimary)
        Spacer(modifier = Modifier.width(AppSpacing.SM))
        Text("${deck.newCount} new", style = MaterialTheme.typography.bodySmall, color = AppColors.actionAccent)
    }
}
