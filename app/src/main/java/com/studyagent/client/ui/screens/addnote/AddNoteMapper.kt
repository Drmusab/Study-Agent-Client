package com.studyagent.client.ui.screens.addnote

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiNoteModel
import com.studyagent.client.core.anki.AnkiNoteModelKind
import com.studyagent.client.core.anki.create.AddNoteDraft
import com.studyagent.client.core.anki.create.MAX_MEDIA_PER_CREATION
import com.studyagent.client.core.anki.create.NoteCreationId
import com.studyagent.client.core.anki.create.NoteCreationRecord
import com.studyagent.client.core.anki.create.NoteCreationStatus
import com.studyagent.client.core.anki.create.PendingMedia

/**
 * GATE 18 — the Add Note projection. Pure Kotlin (no Compose, no Android, no data layer): every
 * wording and gating rule here is unit-testable on the JVM, and the Compose layer renders the
 * result without deciding anything (AUDIT-18-09 mirror of AUDIT-17-09).
 *
 * Capability honesty rules (AUDIT-18-14):
 * - creation is offered only when the backend claims `createNotes` AND `noteModelListing`;
 * - media attachment is offered only when it additionally claims `storeMedia`;
 * - the deck notice never pretends the caller can choose a deck (it cannot at this pin —
 *   CONTRACT-18-05); duplicate protection is never claimed (the backend does not enforce it —
 *   CONTRACT-18-07).
 */
object AddNoteMapper {

    /**
     * Display-only wording for the backend a creation is aimed at, keyed by the stable identity
     * string. This is a *label*, never a gate: nothing here decides whether creation is offered —
     * that is capability-derived in [project] (AUDIT-18-14).
     */
    fun backendLabel(stableId: String): String = when {
        stableId == "ankidroid_local" -> "AnkiDroid on this device"
        stableId.startsWith("pc_agent:") -> "PC Study Agent"
        stableId.startsWith("fake:") -> "Test backend"
        else -> stableId
    }

    /** Everything the projection needs; the ViewModel owns the lifecycle of each part. */
    data class Source(
        val availability: AnkiAvailability,
        val capabilities: AnkiCapabilities?,
        val modelsLoading: Boolean,
        val modelsError: AnkiError?,
        val models: List<AnkiNoteModel>,
        val selected: AnkiNoteModel?,
        val fieldValues: Map<Int, String>,
        val tags: List<String>,
        val pendingMedia: List<PendingMedia>,
        val deckNames: Map<String, String>,
        val deckListingFailed: Boolean,
        /** Validation tokens for the CURRENT draft (empty = valid). */
        val validationTokens: List<String>,
        val saveState: AddNoteSaveState,
        val saving: Boolean,
        val recoveryRecords: List<NoteCreationRecord>
    )

    fun project(source: Source): AddNoteUiState {
        val availability = source.availability
        if (availability !is AnkiAvailability.Ready) {
            return AddNoteUiState.Unavailable(availability)
        }
        val capabilities = source.capabilities
            ?: return AddNoteUiState.Refused("Creation is not available on this backend.")
        if (!capabilities.createNotes || !capabilities.noteModelListing) {
            return AddNoteUiState.Refused(
                "This backend cannot create notes. Note creation is offered only on backends that " +
                    "advertise the creation capability."
            )
        }
        if (source.modelsError != null) {
            return AddNoteUiState.Error(source.modelsError)
        }
        if (source.modelsLoading && source.models.isEmpty() && source.selected == null) {
            return AddNoteUiState.Loading
        }

        val selected = source.selected
        val fields = selected?.fields?.map { field ->
            AddNoteFieldState(
                ordinal = field.ordinal,
                label = field.name,
                value = source.fieldValues[field.ordinal] ?: "",
                issueToken = fieldIssueToken(source.validationTokens, field.ordinal, selected.fieldCount)
            )
        } ?: emptyList()

        val mediaOffered = capabilities.storeMedia
        val media = source.pendingMedia.mapIndexed { index, item ->
            AddNoteMediaState(
                attachmentId = index,
                label = item.sourceName,
                sizeLabel = sizeLabel(item.sizeBytes),
                isImage = item.kind == com.studyagent.client.core.anki.create.CreationMediaKind.IMAGE,
                targetFieldLabel = selected?.fields
                    ?.firstOrNull { it.ordinal == item.targetFieldOrdinal }?.name
                    ?: "field ${item.targetFieldOrdinal + 1}",
                pending = true,
                issueToken = mediaIssueToken(source.validationTokens, index)
            )
        }

        val deckNotice = selected?.let { model ->
            val deckId = model.defaultDeckId
            AddNoteDeckNotice(
                defaultDeckId = deckId,
                defaultDeckLabel = deckId?.let { source.deckNames[it] },
                deckConfirmed = deckId == null || source.deckListingFailed || source.deckNames.containsKey(deckId)
            )
        }

        val canSave = selected != null &&
            !source.saving &&
            source.saveState !is AddNoteSaveState.SavingMedia &&
            source.saveState !is AddNoteSaveState.SavingNote &&
            source.validationTokens.isEmpty() &&
            fields.none { it.issueToken != null } &&
            media.none { it.issueToken != null }

        return AddNoteUiState.Ready(
            AddNoteEditorState(
                models = source.models.map { model ->
                    AddNoteModelOption(
                        modelId = model.ref.modelId,
                        name = model.name,
                        isCloze = model.kind == AnkiNoteModelKind.CLOZE,
                        fieldCount = model.fieldCount
                    )
                },
                selectedModelId = selected?.ref?.modelId,
                modelName = selected?.name,
                modelIsCloze = selected?.kind == AnkiNoteModelKind.CLOZE,
                fields = fields,
                tags = source.tags,
                media = media,
                deckNotice = deckNotice,
                mediaAttachmentOffered = mediaOffered,
                canSave = canSave,
                saveState = source.saveState,
                noteAndTagsAtomic = true,
                // The pinned backend never rejects duplicates at creation (CONTRACT-18-07): the UI
                // must not claim protection it does not have.
                duplicateProtection = false,
                recovery = source.recoveryRecords.map { record -> projectRecovery(record) }
            )
        )
    }

    fun projectCreatedSummary(
        noteId: String,
        creationId: NoteCreationId?,
        modelName: String?,
        fields: List<String>,
        tags: List<String>,
        cards: List<Pair<String, String?>>,
        hydrationIssueToken: String?
    ): AddNoteCreatedSummary = AddNoteCreatedSummary(
        noteId = noteId,
        creationId = creationId,
        modelName = modelName,
        fieldCount = fields.size,
        tags = tags,
        cards = cards.mapIndexed { index, (label, deckLabel) ->
            AddNoteCreatedCardLine(
                cardLabel = label.ifBlank { "Card ${index + 1}" },
                deckLabel = deckLabel
            )
        },
        hydrationIssueToken = hydrationIssueToken
    )

    // ---------------------------------------------------------------- recovery projection

    private fun projectRecovery(record: NoteCreationRecord): AddNoteRecoveryState =
        AddNoteRecoveryState(
            creationId = record.creationId,
            modelName = record.modelName,
            ambiguous = record.status == NoteCreationStatus.AMBIGUOUS,
            canAttest = record.status == NoteCreationStatus.AMBIGUOUS,
            orphanMediaNote = record.mediaBoundaryEntered && !record.noteBoundaryEntered
        )

    // ---------------------------------------------------------------- helpers

    private fun fieldIssueToken(tokens: List<String>, ordinal: Int, fieldCount: Int): String? = when {
            "first_field_empty" in tokens && ordinal == 0 -> "first_field_empty"
            "last_field_not_representable" in tokens && ordinal == fieldCount - 1 ->
                "last_field_not_representable"
            else -> null
        }

    private fun mediaIssueToken(tokens: List<String>, index: Int): String? = when {
        "media_size_exceeded" in tokens -> "media_size_exceeded"
        "media_target_field_missing" in tokens -> "media_target_field_missing"
        "media_count_exceeded" in tokens && index >= MAX_MEDIA_PER_CREATION -> "media_count_exceeded"
        else -> null
    }

    private fun sizeLabel(bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb < 1024.0) "%.0f KB".format(kb) else "%.1f MB".format(kb / 1024.0)
    }

    /** Wording for a creation refusal reason — stable, user-facing, no provider text. */
    fun refusalMessage(reason: String): String = when (reason) {
        "BackendMismatch" -> "The selected backend changed. Close Add Note and open it again."
        "BackendUnavailable" -> "The Anki backend is not reachable right now."
        "ModelMissing" -> "The selected note type no longer exists. Choose another note type."
        "SchemaDrifted" -> "The note type changed while you were writing. Review the fields and save again."
        "PayloadUnavailable" -> "The saved attempt expired after the app restarted. Start a new note."
        "UnknownCreation" -> "This attempt is no longer known. Start a new note."
        "CreationInProgress" -> "A creation is already running. Wait for it to finish."
        else -> "The note could not be created."
    }

    /** Wording for validation tokens — one honest line each, no backend exaggeration. */
    fun validationMessage(token: String): String = when (token) {
        "no_model_selected" -> "Choose a note type first."
        "first_field_empty" -> "The first field cannot be empty."
        "last_field_not_representable" ->
            "The last field cannot be empty with this Anki integration."
        "field_contains_separator" -> "A field contains a character Anki reserves. Remove it."
        "field_ordinals_incomplete" -> "The note type changed. Reopen Add Note."
        "tag_blank", "tag_invalid_character" -> "Tags cannot be empty or contain spaces."
        "tag_hierarchy_component_blank" -> "A tag has an empty hierarchy part (check '::')."
        "media_size_exceeded" -> "A media file is larger than 25 MB."
        "media_count_exceeded" -> "At most 8 media files can be attached to one note."
        "media_target_field_missing" -> "A media attachment targets a field that no longer exists."
        "capability_store_media_missing" -> "This backend cannot store media."
        // Local media-probe refusals (still reversible; nothing reached the backend).
        "uri_unparseable" -> "The picked file could not be read."
        "mime_unknown" -> "The picked file has no type, so it cannot be attached."
        "mime_unsupported" ->
            "This file type is not supported for notes. Attach a common image or audio file."
        "size_unknown" -> "The file size could not be determined, so it cannot be attached."
        "probe_failed" -> "The picked file could not be inspected."
        else -> "Check the note before saving."
    }
}
