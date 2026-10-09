package com.studyagent.client.ui.components.anki

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.studyagent.client.ui.components.InlineTextButton
import com.studyagent.client.ui.screens.editnote.EditNoteFieldState
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

/**
 * GATE 17 CHECKPOINT 15 — one editable note field.
 *
 * Field identity is the ordinal; the name is a label only (CONTRACT-14). The value is shown as plain
 * text in a text field: a note field may contain HTML, and this screen never renders it as markup
 * (the read-only renderer stays the only place that does).
 *
 * Direction (VER-17-20): the value follows its own content (`ContentOrLtr`), so an Arabic field is
 * edited right-to-left while the surrounding layout stays as the app locale dictates. The label uses
 * `Content` for the same reason.
 */
@Composable
fun NoteFieldEditor(
    field: EditNoteFieldState,
    onValueChange: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val editable = enabled && field.editable
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("${NOTE_FIELD_EDITOR_TEST_TAG}_${field.ordinal}")
            .semantics { contentDescription = "Field ${field.ordinal + 1}: ${field.label}" },
        verticalArrangement = Arrangement.spacedBy(AppSpacing.XXS)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = field.label,
                style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
                color = AppColors.contentSecondary,
                modifier = Modifier.weight(1f)
            )
            if (field.changed) {
                Text(
                    text = "Edited",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.actionAccent,
                    modifier = Modifier.testTag("${NOTE_FIELD_EDITOR_TEST_TAG}_${field.ordinal}_changed")
                )
                if (editable) {
                    InlineTextButton(text = "Reset", onClick = onReset, enabled = editable)
                }
            }
        }
        OutlinedTextField(
            value = field.draftValue,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("${NOTE_FIELD_EDITOR_TEST_TAG}_${field.ordinal}_input"),
            enabled = editable,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrLtr),
            shape = AppShape.fieldShape,
            minLines = 2,
            maxLines = 10,
            isError = field.issue?.blocking == true,
            singleLine = false
        )
        val issue = field.issue
        if (issue != null) {
            Text(
                text = issue.message,
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.ContentOrLtr),
                color = if (issue.blocking) AppColors.statusDanger else AppColors.contentMuted,
                modifier = Modifier.padding(bottom = AppSpacing.XXS)
            )
        }
        if (field.changed && field.issue == null) {
            Text(
                text = "Was: ${field.originalValue.ifBlank { "(empty)" }}",
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.ContentOrLtr),
                color = AppColors.contentMuted,
                maxLines = 2,
                modifier = Modifier.padding(bottom = AppSpacing.XXS)
            )
        }
    }
}

const val NOTE_FIELD_EDITOR_TEST_TAG = "note_field_editor"
