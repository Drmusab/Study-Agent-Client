package com.studyagent.client.ui.components.anki

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import com.studyagent.client.ui.screens.carddetails.FieldPresentation
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

/** Ordered, read-only, safe text projection of note fields (GATE 16 CHECKPOINT 17). */
@Composable
fun NoteFieldsSection(
    fields: List<FieldPresentation>?,
    modifier: Modifier = Modifier
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
            Text("Note fields", style = MaterialTheme.typography.titleMedium, color = AppColors.contentPrimary)
            when {
                fields == null -> Text(
                    "Field values are not available from this backend.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.contentSecondary
                )
                fields.isEmpty() -> Text(
                    "This note has no fields.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.contentSecondary
                )
                else -> fields.forEach { field ->
                    SafeExpandableText(
                        label = field.name,
                        value = field.value,
                        stableKey = "${field.ordinal ?: -1}:${field.name}"
                    )
                }
            }
        }
    }
}

/** Read-only expansion uses selectable plain text; no field value is passed to an HTML renderer. */
@Composable
fun SafeExpandableText(
    label: String,
    value: String,
    stableKey: String,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable(stableKey) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$label: ${if (value.isEmpty()) "empty" else value}"
            },
        verticalArrangement = Arrangement.spacedBy(AppSpacing.XXS)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
            color = AppColors.contentSecondary
        )
        SelectionContainer {
            Text(
                text = if (value.isEmpty()) "Empty" else value,
                modifier = if (expanded) {
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier.fillMaxWidth()
                },
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                color = AppColors.contentPrimary,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis
            )
        }
        if (value.length > COLLAPSED_CHAR_HINT || value.count { it == '\n' } >= COLLAPSED_LINES) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    text = if (expanded) "Show less" else "Show full field",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

private const val COLLAPSED_LINES = 4
private const val COLLAPSED_CHAR_HINT = 180
