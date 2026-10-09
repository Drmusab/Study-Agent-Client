package com.studyagent.client.ui.components.anki

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.studyagent.client.ui.components.InlineTextButton
import com.studyagent.client.ui.screens.editnote.EditNoteTagsState
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppShape
import com.studyagent.client.ui.theme.AppSpacing

const val TAGS_EDITOR_TEST_TAG = "tags_editor"

/**
 * GATE 17 CHECKPOINT 15 — the tag editor.
 *
 * The backend offers **replace-only** tag writes: the draft list is the whole desired set and one
 * write replaces it (CONTRACT-02). There is no "add tag" or "remove tag" backend operation behind
 * these controls — they edit the set that will be written as a whole. Anki owns the stored order and
 * the stored capitalisation, so neither is presented as meaningful (CONTRACT-02, INV-17-03).
 *
 * Direction (VER-17-20): tag text follows its own content, so Arabic tags read right-to-left.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsEditor(
    state: EditNoteTagsState,
    onAddTags: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var input by rememberSaveable { mutableStateOf("") }
    val editable = enabled && state.editable
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAGS_EDITOR_TEST_TAG)
            .semantics { contentDescription = "Tags" },
        verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (state.changed) "Tags (edited)" else "Tags",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.contentSecondary,
                modifier = Modifier.weight(1f)
            )
            if (state.changed && editable) {
                InlineTextButton(text = "Reset", onClick = onReset, enabled = editable)
            }
        }

        if (state.draftTags.isEmpty()) {
            Text(
                text = "This note has no tags.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.contentMuted
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
            ) {
                state.draftTags.forEach { tag ->
                    AssistChip(
                        onClick = { if (editable) onRemoveTag(tag) },
                        modifier = Modifier.testTag("$TAGS_EDITOR_TEST_TAG chip"),
                        label = {
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    textDirection = TextDirection.ContentOrLtr
                                )
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove tag $tag",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag("$TAGS_EDITOR_TEST_TAG input"),
                enabled = editable,
                singleLine = true,
                isError = state.issue?.blocking == true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrLtr),
                shape = AppShape.fieldShape,
                label = { Text("Add a tag") }
            )
            IconButton(
                onClick = {
                    val entered = input.trim()
                    if (entered.isNotEmpty()) {
                        onAddTags(entered)
                        input = ""
                    }
                },
                enabled = editable && input.isNotBlank(),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("$TAGS_EDITOR_TEST_TAG add")
                    .semantics { contentDescription = "Add tag" }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = if (editable && input.isNotBlank()) AppColors.actionAccent else AppColors.contentMuted
                )
            }
        }

        val issue = state.issue
        if (issue != null) {
            Text(
                text = issue.message,
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.ContentOrLtr),
                color = if (issue.blocking) AppColors.statusDanger else AppColors.contentMuted
            )
        }
        Text(
            text = "One write replaces the whole tag set. Anki stores tags in its own order and may " +
                "adjust their capitalisation. A space separates two tags.",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.contentMuted,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(bottom = AppSpacing.XXS)
        )
    }
}
