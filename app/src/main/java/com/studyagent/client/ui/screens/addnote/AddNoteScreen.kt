package com.studyagent.client.ui.screens.addnote

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyagent.client.ui.components.AppCard
import com.studyagent.client.ui.components.AppDivider
import com.studyagent.client.ui.components.BannerTone
import com.studyagent.client.ui.components.EmptyState
import com.studyagent.client.ui.components.InfoBanner
import com.studyagent.client.ui.components.PrimaryButton
import com.studyagent.client.ui.components.SecondaryButton
import com.studyagent.client.ui.components.SectionHeader
import com.studyagent.client.ui.components.StudyAgentTopBar
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppSpacing

const val ADD_NOTE_TEST_TAG = "add_note"
const val ADD_NOTE_SAVE_TEST_TAG = "add_note_save"
const val ADD_NOTE_MODEL_PICKER_TEST_TAG = "add_note_model_picker"
const val ADD_NOTE_FIELD_TEST_TAG = "add_note_field"
const val ADD_NOTE_ATTACH_TEST_TAG = "add_note_attach"

/**
 * GATE 18 — the Add Note screen. Pure presentation: it renders [AddNoteUiState] and reports user
 * intent to the ViewModel; it decides nothing (AUDIT-18-09/11). Notably it shows:
 * - the deck as a *notice*, never a selector (the pinned backend accepts no caller deck —
 *   CONTRACT-18-05);
 * - no duplicate-protection claim (the backend enforces none — CONTRACT-18-07);
 * - ambiguous outcomes as a human decision with attestation, never an automatic retry
 *   (INV-18-08).
 */
@Composable
fun AddNoteScreen(
    viewModel: AddNoteViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            StudyAgentTopBar(
                title = "Add Note",
                subtitle = viewModel.backendLabel,
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.MD)
                .testTag(ADD_NOTE_TEST_TAG),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.MD)
        ) {
            when (val current = state) {
                is AddNoteUiState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(AppSpacing.XL),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is AddNoteUiState.Unavailable -> EmptyState(
                    title = "Anki not reachable",
                    message = "The Anki backend is not available right now. Open AnkiDroid once and try again."
                )
                is AddNoteUiState.Error -> EmptyState(
                    title = "Note types unavailable",
                    message = "The list of note types could not be loaded. Try again.",
                    icon = Icons.Default.Close
                )
                is AddNoteUiState.Refused -> InfoBanner(
                    message = current.message,
                    tone = BannerTone.WARNING
                )
                is AddNoteUiState.Ready -> ReadyEditor(
                    editor = current.editor,
                    onSelectModel = viewModel::selectModel,
                    onFieldChanged = viewModel::onFieldChanged,
                    onTagsChanged = viewModel::onTagsChanged,
                    onAttachMedia = viewModel::attachMedia,
                    onRemoveMedia = viewModel::removeMedia,
                    onSave = viewModel::save,
                    onRetry = viewModel::retry,
                    onAttest = viewModel::attest,
                    onHydrate = viewModel::hydrate,
                    onNewNote = viewModel::startNewNote
                )
            }
        }
    }
}

@Composable
private fun ReadyEditor(
    editor: AddNoteEditorState,
    onSelectModel: (String) -> Unit,
    onFieldChanged: (Int, String) -> Unit,
    onTagsChanged: (List<String>) -> Unit,
    onAttachMedia: (String, Int) -> Unit,
    onRemoveMedia: (Int) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onAttest: (com.studyagent.client.core.anki.create.NoteCreationId, Boolean) -> Unit,
    onHydrate: (com.studyagent.client.core.anki.create.NoteCreationId) -> Unit,
    onNewNote: () -> Unit
) {
    // One picker at a time; the chosen field ordinal decides where a stored reference will land.
    var attachTargetOrdinal by remember { mutableStateOf<Int?>(null) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = attachTargetOrdinal
        if (uri != null && target != null) onAttachMedia(uri.toString(), target)
        attachTargetOrdinal = null
    }

    // Recovery surface first: an ambiguous attempt must be resolved in the user's mind before they
    // create anything else.
    editor.recovery.forEach { record ->
        InfoBanner(
            title = if (record.orphanMediaNote) "Unclear media outcome" else "Unclear creation outcome",
            message = buildString {
                append("Attempt \"")
                append(record.modelName ?: record.creationId.value)
                append("\" ended without a clear result. It will never be retried automatically.")
            },
            tone = BannerTone.DANGER,
            actionLabel = if (record.canAttest) "I checked Anki" else null,
            onAction = if (record.canAttest) {
                { onAttest(record.creationId, true) }
            } else null
        )
    }

    if (editor.models.isEmpty()) {
        EmptyState(
            title = "No note types",
            message = "This collection has no note types to create notes with."
        )
        return
    }

    SectionHeader(title = "Note type")
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val selectedLabel = editor.models.firstOrNull { it.modelId == editor.selectedModelId }?.name
        ?: "Choose a note type"
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ADD_NOTE_MODEL_PICKER_TEST_TAG),
            trailingIcon = {
                IconButton(onClick = { modelMenuExpanded = !modelMenuExpanded }) {
                    Icon(imageVector = Icons.Default.ExpandMore, contentDescription = "Choose note type")
                }
            },
            singleLine = true
        )
        DropdownMenu(
            expanded = modelMenuExpanded,
            onDismissRequest = { modelMenuExpanded = false }
        ) {
            editor.models.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.name + if (option.isCloze) "  (cloze)" else "",
                            maxLines = 1
                        )
                    },
                    onClick = {
                        onSelectModel(option.modelId)
                        modelMenuExpanded = false
                    }
                )
            }
        }
    }

    editor.deckNotice?.let { notice ->
        val deckText = notice.defaultDeckLabel ?: "the note type's default deck"
        val tone = if (!notice.deckConfirmed) BannerTone.WARNING else BannerTone.INFO
        InfoBanner(
            message = "Anki adds this note's cards to \"$deckText\". The deck is chosen by the " +
                "note type; Study-Agent cannot change it here.",
            tone = tone
        )
    }
    if (editor.modelIsCloze) {
        InfoBanner(
            message = "This is a cloze note type: mark deletions in the field text as {{c1::text}}. " +
                "Anki generates one card per cloze number.",
            tone = BannerTone.INFO
        )
    }
    if (editor.duplicateProtection) {
        InfoBanner(
            message = "This backend checks duplicates before creating.",
            tone = BannerTone.INFO
        )
    } else {
        InfoBanner(
            message = "Heads-up: Anki does not check for duplicates at creation. Saving twice can " +
                "create two notes.",
            tone = BannerTone.NEUTRAL
        )
    }

    if (editor.fields.isNotEmpty()) {
        SectionHeader(title = "Fields")
        editor.fields.forEach { field ->
            AddNoteFieldRow(
                field = field,
                onValueChange = { value -> onFieldChanged(field.ordinal, value) },
                onAttach = if (editor.mediaAttachmentOffered) {
                    {
                        attachTargetOrdinal = field.ordinal
                        mediaPicker.launch("*/*")
                    }
                } else null
            )
        }
    }

    if (editor.media.isNotEmpty()) {
        SectionHeader(title = "Media")
        AppCard {
            Column(
                modifier = Modifier.padding(AppSpacing.cardPadding),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)
            ) {
                editor.media.forEach { media ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = null,
                            tint = AppColors.contentSecondary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = media.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(
                                text = "${media.sizeLabel} · into \"${media.targetFieldLabel}\"" +
                                    if (media.pending) " · pending until saved" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.contentMuted
                            )
                        }
                        IconButton(onClick = { onRemoveMedia(media.attachmentId) }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove ${media.label}"
                            )
                        }
                    }
                }
            }
        }
    }

    SectionHeader(title = "Tags")
    AddNoteTagsRow(tags = editor.tags, onTagsChanged = onTagsChanged)

    AppDivider()
    SaveSection(editor = editor, onSave = onSave, onRetry = onRetry, onNewNote = onNewNote, onHydrate = onHydrate)
}

@Composable
private fun AddNoteFieldRow(
    field: AddNoteFieldState,
    onValueChange: (String) -> Unit,
    onAttach: (() -> Unit)?
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = field.value,
            onValueChange = onValueChange,
            label = {
                Text(
                    text = field.label + (field.issueToken?.let { " — ${AddNoteMapper.validationMessage(it)}" } ?: ""),
                    maxLines = 2
                )
            },
            isError = field.issueToken != null,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("${ADD_NOTE_FIELD_TEST_TAG}_${field.ordinal}"),
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            minLines = 2
        )
        if (onAttach != null) {
            SecondaryButton(
                text = "Attach media to \"${field.label}\"",
                onClick = onAttach,
                icon = Icons.Default.AttachFile,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppSpacing.XXS)
                    .testTag("${ADD_NOTE_ATTACH_TEST_TAG}_${field.ordinal}")
            )
        }
    }
}

@Composable
private fun AddNoteTagsRow(tags: List<String>, onTagsChanged: (List<String>) -> Unit) {
    var input by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.XS)
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Add tag") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(
                    onClick = {
                        val tag = input.trim()
                        if (tag.isNotEmpty()) {
                            onTagsChanged(tags + tag)
                            input = ""
                        }
                    },
                    enabled = input.isNotBlank()
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add tag")
                }
            }
        )
        if (tags.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.XS)
            ) {
                tags.forEach { tag ->
                    SecondaryButton(
                        text = "$tag  ✕",
                        onClick = { onTagsChanged(tags - tag) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveSection(
    editor: AddNoteEditorState,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onNewNote: () -> Unit,
    onHydrate: (com.studyagent.client.core.anki.create.NoteCreationId) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.SM)
    ) {
        when (val save = editor.saveState) {
            is AddNoteSaveState.SavingMedia -> InfoBanner(
                message = "Storing media ${save.step} of ${save.total}…",
                tone = BannerTone.INFO
            )
            is AddNoteSaveState.SavingNote -> InfoBanner(
                message = "Creating the note…",
                tone = BannerTone.INFO
            )
            is AddNoteSaveState.ValidationMessage -> save.tokens.forEach { token ->
                InfoBanner(message = AddNoteMapper.validationMessage(token), tone = BannerTone.WARNING)
            }
            is AddNoteSaveState.RetryAvailable -> InfoBanner(
                message = save.message,
                tone = BannerTone.WARNING,
                actionLabel = "Retry",
                onAction = onRetry
            )
            is AddNoteSaveState.MediaUnclear -> InfoBanner(message = save.message, tone = BannerTone.WARNING)
            is AddNoteSaveState.Ambiguous -> InfoBanner(message = save.message, tone = BannerTone.DANGER)
            is AddNoteSaveState.RefusalMessage -> InfoBanner(message = save.message, tone = BannerTone.WARNING)
            is AddNoteSaveState.LedgerMessage -> InfoBanner(
                message = "The creation ledger is unavailable (${save.detail}). Nothing was sent.",
                tone = BannerTone.DANGER
            )
            is AddNoteSaveState.Created -> {
                val summary = save.summary
                InfoBanner(
                    title = "Note created",
                    message = buildString {
                        append("Note ")
                        append(summary.noteId)
                        summary.modelName?.let { append(" · ").append(it) }
                        append(" · ")
                        append(summary.cards.size)
                        append(if (summary.cards.size == 1) " card" else " cards")
                        append(" generated by Anki")
                    },
                    tone = BannerTone.INFO
                )
                summary.cards.forEach { card ->
                    Text(
                        text = "• ${card.cardLabel}" + (card.deckLabel?.let { " — $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                summary.hydrationIssueToken?.let {
                    InfoBanner(
                        message = "The note is created, but its details could not be read back. " +
                            "Reading it again will not create anything.",
                        tone = BannerTone.WARNING,
                        actionLabel = if (summary.creationId != null) "Read again" else null,
                        onAction = summary.creationId?.let { id -> { onHydrate(id) } }
                    )
                }
            }
            is AddNoteSaveState.Idle -> Unit
        }

        when (editor.saveState) {
            is AddNoteSaveState.Created -> PrimaryButton(
                text = "Add another note",
                onClick = onNewNote,
                modifier = Modifier.fillMaxWidth()
            )
            is AddNoteSaveState.RetryAvailable,
            is AddNoteSaveState.SavingMedia,
            is AddNoteSaveState.SavingNote -> PrimaryButton(
                text = if (editor.saveState is AddNoteSaveState.RetryAvailable) "Retrying…" else "Saving…",
                onClick = {},
                enabled = false,
                loading = editor.saveState !is AddNoteSaveState.RetryAvailable,
                modifier = Modifier.fillMaxWidth().testTag(ADD_NOTE_SAVE_TEST_TAG)
            )
            else -> PrimaryButton(
                text = "Create note",
                onClick = onSave,
                enabled = editor.canSave,
                modifier = Modifier.fillMaxWidth().testTag(ADD_NOTE_SAVE_TEST_TAG)
            )
        }
    }
}
