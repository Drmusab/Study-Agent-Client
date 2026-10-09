package com.studyagent.client.ui.components.anki

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.studyagent.client.ui.components.SecondaryButton
import com.studyagent.client.ui.screens.editnote.EditNoteDeckState
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppSpacing

const val DECK_SELECTOR_TEST_TAG = "deck_selector"

/**
 * GATE 17 CHECKPOINT 15 — the deck control.
 *
 * Wording follows the discovered scope, not the convenient one (CONTRACT-03 / INV-17-09): the pinned
 * AnkiDroid operation moves **one card** (`notes/<id>/cards/<ord>` with `deck_id`), so nothing here
 * says "move note". Sibling cards generated from the same note stay where they are, and the screen
 * says so.
 *
 * Identity is the stable deck id. Labels are display only, and a deck that the backend cannot confirm
 * is not filtered is offered as unselectable with a reason instead of being written optimistically.
 */
@Composable
fun DeckSelector(
    state: EditNoteDeckState,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val editable = enabled && state.editable
    val selectedLabel = state.options.firstOrNull { it.deckId == state.selectedDeckId }?.label
        ?: state.currentDeckLabel
        ?: "Unknown deck"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(DECK_SELECTOR_TEST_TAG)
            .semantics { contentDescription = "Deck for this card" },
        verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (state.changed) "Deck (edited)" else "Deck",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.contentSecondary,
                modifier = Modifier.weight(1f)
            )
            if (state.changed) {
                Text(
                    text = "Was: ${state.currentDeckLabel ?: "unknown"}",
                    style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content),
                    color = AppColors.contentMuted
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(
                text = selectedLabel,
                onClick = { expanded = true },
                enabled = editable && !state.loading && state.options.isNotEmpty(),
                icon = Icons.Default.ExpandMore,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("$DECK_SELECTOR_TEST_TAG button")
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.testTag("$DECK_SELECTOR_TEST_TAG menu")
            ) {
                DropdownMenuItem(
                    text = { Text("Keep the current deck") },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    }
                )
                state.options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        textDirection = TextDirection.ContentOrLtr
                                    )
                                )
                                val reason = when {
                                    option.filtered -> "Filtered deck — Anki refuses this move"
                                    option.unverifiable -> "Anki did not say whether it is filtered — refused"
                                    else -> null
                                }
                                if (reason != null) {
                                    Text(
                                        text = reason,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AppColors.statusWarning
                                    )
                                }
                            }
                        },
                        onClick = {
                            onSelect(option.deckId)
                            expanded = false
                        },
                        enabled = option.selectable
                    )
                }
            }
        }

        when {
            state.loading -> Text(
                text = "Reading the deck list…",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.contentMuted
            )

            state.issue != null -> Text(
                text = state.issue.message,
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.ContentOrLtr),
                color = if (state.issue.blocking) AppColors.statusDanger else AppColors.contentMuted
            )
        }

        Text(
            text = "This moves the card you opened, not the note. Other cards from the same note stay " +
                "in their own decks, and this is written as a second step after the content change.",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.contentMuted,
            modifier = Modifier.padding(bottom = AppSpacing.XXS)
        )
    }
}
