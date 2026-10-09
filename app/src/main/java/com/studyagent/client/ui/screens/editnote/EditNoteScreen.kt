package com.studyagent.client.ui.screens.editnote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.edit.NoteMutationAttestation
import com.studyagent.client.ui.components.AppCard
import com.studyagent.client.ui.components.AppDivider
import com.studyagent.client.ui.components.BannerTone
import com.studyagent.client.ui.components.EmptyState
import com.studyagent.client.ui.components.InfoBanner
import com.studyagent.client.ui.components.PrimaryButton
import com.studyagent.client.ui.components.SecondaryButton
import com.studyagent.client.ui.components.SectionHeader
import com.studyagent.client.ui.components.StudyAgentTopBar
import com.studyagent.client.ui.components.anki.DeckSelector
import com.studyagent.client.ui.components.anki.NoteFieldEditor
import com.studyagent.client.ui.components.anki.TagsEditor
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppSpacing

const val EDIT_NOTE_TEST_TAG = "edit_note"

/**
 * GATE 17 CHECKPOINT 15 — the edit screen.
 *
 * It renders decisions; it never makes them. What may be edited comes from backend capabilities, what
 * the wording says comes from the backend's declared semantics, and every write goes through the
 * coordinator the ViewModel holds (INV-17-17: the UI cannot bypass contract enforcement, because it
 * has no write path of its own).
 */
@Composable
fun EditNoteScreen(
    viewModel: EditNoteViewModel,
    onNavigateBack: () -> Unit,
    onSaved: (AnkiCardRef) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val savedRef by viewModel.savedCardRef.collectAsStateWithLifecycle()

    LaunchedEffect(savedRef) {
        val ref = savedRef ?: return@LaunchedEffect
        viewModel.consumeSaved()
        onSaved(ref)
    }

    val editor = (state as? EditNoteUiState.Ready)?.editor
    Scaffold(
        containerColor = AppColors.appBackground,
        modifier = modifier.testTag(EDIT_NOTE_TEST_TAG),
        topBar = {
            StudyAgentTopBar(
                title = "Edit note",
                onBack = onNavigateBack,
                subtitle = editor?.cardLabel ?: editor?.noteTypeLabel
            )
        },
        bottomBar = {
            if (editor != null) {
                SaveBar(
                    editor = editor,
                    onSave = viewModel::save,
                    onDiscard = onNavigateBack
                )
            }
        }
    ) { padding ->
        when (val current = state) {
            EditNoteUiState.Loading -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = AppColors.actionAccent)
                Text(
                    text = "Reading the note…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.contentSecondary,
                    modifier = Modifier.padding(top = AppSpacing.SM)
                )
            }

            is EditNoteUiState.Unavailable -> EmptyState(
                title = "Anki is not ready",
                message = "Editing needs a live read of this note. Connect Anki, then open this card again.",
                modifier = Modifier.padding(padding)
            )

            is EditNoteUiState.Error -> EmptyState(
                title = "This note could not be read",
                message = EditNoteMapper.message(current.error) + ". Nothing was written.",
                modifier = Modifier.padding(padding)
            )

            is EditNoteUiState.Refused -> EmptyState(
                title = "Editing is not offered here",
                message = current.message,
                modifier = Modifier.padding(padding)
            )

            is EditNoteUiState.Ready -> EditorContent(
                editor = current.editor,
                viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun EditorContent(
    editor: EditNoteEditorState,
    viewModel: EditNoteViewModel,
    modifier: Modifier = Modifier
) {
    val inputsEnabled = editor.saveState !is EditNoteSaveState.Saving && editor.blockedByMutation == null
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.contentGutter, vertical = AppSpacing.SM),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
    ) {
        if (editor.noteIdLabel != null) {
            Text(
                text = "Note ${editor.noteIdLabel}" + (editor.noteTypeLabel?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.contentMuted
            )
        }

        editor.blockedByMutation?.let { blocked ->
            BlockedMutationPanel(blocked = blocked, viewModel = viewModel)
        }

        SaveStateBanner(editor.saveState, onDismiss = viewModel::dismissMessage)

        editor.notices.forEach { notice ->
            InfoBanner(
                message = notice.message,
                tone = if (notice.tone == EditNoticeTone.WARNING) BannerTone.WARNING else BannerTone.INFO
            )
        }

        if (editor.capabilities.fields && editor.fields.isNotEmpty()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
                    SectionHeader(title = "Fields")
                    Text(
                        text = "Every field is written together, in order. Fields you do not touch keep " +
                            "the value Anki holds for them.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.contentMuted
                    )
                    editor.fields.forEach { field ->
                        NoteFieldEditor(
                            field = field,
                            onValueChange = { value -> viewModel.onFieldChange(field.ordinal, value) },
                            onReset = { viewModel.resetField(field.ordinal) },
                            enabled = inputsEnabled
                        )
                    }
                }
            }
        }

        if (editor.capabilities.tags) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SectionHeader(title = "Tags")
                    TagsEditor(
                        state = editor.tags,
                        onAddTags = viewModel::addTags,
                        onRemoveTag = viewModel::removeTag,
                        onReset = viewModel::resetTags,
                        enabled = inputsEnabled
                    )
                }
            }
        }

        if (editor.capabilities.deck) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SectionHeader(title = "Deck")
                    DeckSelector(
                        state = editor.deck,
                        onSelect = viewModel::selectDeck,
                        enabled = inputsEnabled
                    )
                }
            }
        }

        if (!editor.capabilities.anythingEditable) {
            EmptyState(
                title = "Nothing here can be edited",
                message = "The connected backend offers no edit operation for this note."
            )
        }
    }
}

@Composable
private fun SaveStateBanner(state: EditNoteSaveState, onDismiss: () -> Unit) {
    when (state) {
        EditNoteSaveState.Idle, EditNoteSaveState.Saving -> Unit

        is EditNoteSaveState.Saved -> InfoBanner(
            message = state.message,
            tone = BannerTone.INFO,
            title = "Saved",
            actionLabel = "Close",
            onAction = onDismiss
        )

        is EditNoteSaveState.Blocked -> InfoBanner(
            message = if (state.issues.isEmpty()) {
                state.message
            } else {
                state.message + "\n" + state.issues.joinToString("\n") { "• $it" }
            },
            tone = BannerTone.WARNING,
            title = "Not written",
            actionLabel = "Dismiss",
            onAction = onDismiss
        )

        is EditNoteSaveState.RetryAvailable -> InfoBanner(
            message = state.message,
            tone = BannerTone.WARNING,
            title = "Anki refused the write"
        )

        is EditNoteSaveState.Conflicted -> InfoBanner(
            message = state.message,
            tone = BannerTone.WARNING,
            title = "The note changed"
        )

        is EditNoteSaveState.Ambiguous -> InfoBanner(
            message = state.message + (state.evidenceMessage?.let { "\n\n$it" } ?: ""),
            tone = BannerTone.DANGER,
            title = "Outcome unknown"
        )
    }
}

/**
 * An unresolved mutation that owns this note. The actions offered are exactly the ones the locked
 * contract allows for that status: a proven non-application may be retried, an unknown outcome may
 * only be re-checked read-only or closed by a human attestation, and a closed conflict is superseded
 * by a fresh edit from a new authoritative read.
 */
@Composable
private fun BlockedMutationPanel(blocked: EditNoteBlockedMutation, viewModel: EditNoteViewModel) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("$EDIT_NOTE_TEST_TAG blocked")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)) {
            SectionHeader(title = "This note has an unfinished edit")
            Text(
                text = blocked.statusLabel,
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.statusWarning
            )
            Text(
                text = blocked.message,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrLtr),
                color = AppColors.contentPrimary
            )
            blocked.evidenceMessage?.let { evidence ->
                Text(
                    text = evidence,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.ContentOrLtr),
                    color = AppColors.contentSecondary
                )
            }
            Text(
                text = "Reference ${blocked.mutationId}",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.contentMuted
            )
            AppDivider()
            if (blocked.canRetry) {
                PrimaryButton(
                    text = "Send this edit again",
                    onClick = viewModel::retry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("$EDIT_NOTE_TEST_TAG retry")
                )
            }
            if (blocked.canResume) {
                PrimaryButton(
                    text = "Try sending it now",
                    onClick = viewModel::resumePrepared,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (blocked.canRecover) {
                SecondaryButton(
                    text = "Check the outcome again",
                    onClick = viewModel::checkAgain,
                    icon = Icons.Default.Refresh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("$EDIT_NOTE_TEST_TAG recover")
                )
            }
            if (blocked.canAttest) {
                Text(
                    text = "Open this note in Anki and check it, then tell Study Agent what you found. " +
                        "Your answer is recorded as your confirmation, not as evidence from Anki.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.contentSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
                ) {
                    SecondaryButton(
                        text = EditNoteMapper.attestationLabel(NoteMutationAttestation.APPLIED_IN_COLLECTION),
                        onClick = { viewModel.attest(NoteMutationAttestation.APPLIED_IN_COLLECTION) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("$EDIT_NOTE_TEST_TAG attest_applied")
                    )
                    SecondaryButton(
                        text = EditNoteMapper.attestationLabel(NoteMutationAttestation.ABSENT_FROM_COLLECTION),
                        onClick = { viewModel.attest(NoteMutationAttestation.ABSENT_FROM_COLLECTION) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("$EDIT_NOTE_TEST_TAG attest_absent")
                    )
                }
            }
            if (blocked.canRestartAfterConflict) {
                PrimaryButton(
                    text = "Re-open the note and keep my values",
                    onClick = viewModel::reloadAfterConflict,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("$EDIT_NOTE_TEST_TAG restart")
                )
            }
        }
    }
}

@Composable
private fun SaveBar(
    editor: EditNoteEditorState,
    onSave: () -> Unit,
    onDiscard: () -> Unit
) {
    val saving = editor.saveState is EditNoteSaveState.Saving
    Surface(color = AppColors.surfacePrimary) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.SM),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SecondaryButton(
                text = "Cancel",
                onClick = onDiscard,
                enabled = !saving,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = if (saving) "Saving…" else "Save",
                onClick = onSave,
                enabled = editor.canSave,
                loading = saving,
                modifier = Modifier
                    .weight(1f)
                    .testTag("$EDIT_NOTE_TEST_TAG save")
            )
        }
    }
}
