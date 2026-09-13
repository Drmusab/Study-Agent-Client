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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.studyagent.client.core.models.DeckSummary
import com.studyagent.client.ui.screens.home.DashboardUiMapper
import com.studyagent.client.ui.theme.AccentTeal
import com.studyagent.client.ui.theme.DarkBackground
import com.studyagent.client.ui.theme.DarkSurface
import com.studyagent.client.ui.theme.DarkSurfaceElevated
import com.studyagent.client.ui.theme.PrimaryBlue
import com.studyagent.client.ui.theme.StatusAmber
import com.studyagent.client.ui.theme.TextMuted
import com.studyagent.client.ui.theme.TextPrimary
import com.studyagent.client.ui.theme.TextSecondary

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
                TextButton(onClick = onChangeDeck) {
                    Text("Change", color = PrimaryBlue)
                }
            }
        }
    ) {
        when {
            deckUnavailable -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = StatusAmber, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Selected deck unavailable",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusAmber
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "\"$selectedDeckName\" is no longer reported by the Study Agent. Choose another deck.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }

            selectedDeck != null -> DeckDetails(selectedDeck)

            selectedDeckName != null -> {
                Text(
                    text = DashboardUiMapper.deckDisplayName(selectedDeckName),
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary
                )
                DashboardUiMapper.deckHierarchy(selectedDeckName)?.let { hierarchy ->
                    Text(hierarchy, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }

            else -> Text(
                text = if (decksSupported) "No deck selected" else "Deck selection is managed by the Study Agent",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted
            )
        }
    }
}

@Composable
private fun DeckDetails(deck: DeckSummary) {
    Text(
        text = DashboardUiMapper.deckDisplayName(deck.name),
        style = MaterialTheme.typography.headlineSmall,
        color = TextPrimary
    )
    DashboardUiMapper.deckHierarchy(deck.name)?.let { hierarchy ->
        Text(hierarchy, style = MaterialTheme.typography.bodySmall, color = TextMuted)
    }
    Spacer(modifier = Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        StatItem("Due", "${deck.dueCount}", valueColor = PrimaryBlue)
        StatItem("New", "${deck.newCount}", valueColor = AccentTeal)
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

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Choose Deck", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurfaceElevated)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                        cursorBrush = SolidColor(PrimaryBlue),
                        decorationBox = { inner ->
                            if (query.text.isEmpty()) {
                                Text("Search decks…", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                            }
                            inner()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    Text(
                        text = if (decks.isEmpty()) "No decks reported by the Study Agent." else "No decks match your search.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(vertical = 24.dp)
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
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel", color = TextSecondary)
                }
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
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) DarkSurfaceElevated else DarkSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .semantics {
                contentDescription = buildString {
                    append(deck.name)
                    append(", ${deck.dueCount} due, ${deck.newCount} new")
                    if (deck.isFavorite) append(", favorite")
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (deck.isFavorite) {
                    Icon(Icons.Default.Star, contentDescription = "Favorite", tint = StatusAmber, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = DashboardUiMapper.deckDisplayName(deck.name),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) PrimaryBlue else TextPrimary
                )
            }
            if (hierarchy != null) {
                Text(hierarchy, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
        }
        Text("${deck.dueCount} due", style = MaterialTheme.typography.bodySmall, color = PrimaryBlue)
        Spacer(modifier = Modifier.width(12.dp))
        Text("${deck.newCount} new", style = MaterialTheme.typography.bodySmall, color = AccentTeal)
    }
}
