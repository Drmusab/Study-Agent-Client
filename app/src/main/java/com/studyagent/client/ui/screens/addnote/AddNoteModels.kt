package com.studyagent.client.ui.screens.addnote

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.create.NoteCreationId

/**
 * GATE 18 — the Add Note screen's presentation model.
 *
 * Pure Kotlin: no Compose, no Android, no provider type, no ledger type. It describes what the
 * screen may show and which controls may be enabled; it can never write anything by itself. Every
 * "can I create" answer here is derived from a backend capability or from the locked creation
 * contract, never from the backend's name (AUDIT-18-14).
 */
sealed interface AddNoteUiState {
    data object Loading : AddNoteUiState

    /** The backend cannot serve creation at all (availability or missing capability). */
    data class Unavailable(val reason: AnkiAvailability) : AddNoteUiState

    data class Error(val error: AnkiError) : AddNoteUiState

    /**
     * Creation is not offered on this backend: capability absent, no creation path wired, or the
     * backend is not the one this screen was opened for. The affordance is absent with a reason,
     * never silently degraded.
     */
    data class Refused(val message: String) : AddNoteUiState

    data class Ready(val editor: AddNoteEditorState) : AddNoteUiState
}

/** One selectable note type. Identity is the stable model id; labels are display only. */
data class AddNoteModelOption(
    val modelId: String,
    val name: String,
    /** "Cloze" marker is informational: cloze content is still submitted as normal fields. */
    val isCloze: Boolean,
    val fieldCount: Int
)

/** One field editor. Identity is the ordinal; the name is a label only (CONTRACT-18-03). */
data class AddNoteFieldState(
    val ordinal: Int,
    val label: String,
    val value: String,
    /** Stable issue token for this field, if any (never provider text). */
    val issueToken: String? = null
)

/** What the creation surface says about this backend's deck behaviour (display only). */
data class AddNoteDeckNotice(
    /** The note type's stored default deck id, when the backend reported one. */
    val defaultDeckId: String?,
    /** Display name resolved from the deck listing; null = not resolvable right now. */
    val defaultDeckLabel: String?,
    /** True when the model's stored deck exists in the listing (or could not be checked). */
    val deckConfirmed: Boolean
)

/** One media attachment in the draft, before and after backend storage. */
data class AddNoteMediaState(
    val attachmentId: Int,
    val label: String,
    val sizeLabel: String,
    val isImage: Boolean,
    val targetFieldLabel: String,
    /** Local pending until the backend confirms; the screen never shows a stored name early. */
    val pending: Boolean,
    val issueToken: String? = null
)

/** The created-note summary shown only after durable creation confirmation. */
data class AddNoteCreatedSummary(
    val noteId: String,
    /** The creation record behind this summary; present so a failed hydration can be re-read. */
    val creationId: NoteCreationId?,
    val modelName: String?,
    val fieldCount: Int,
    val tags: List<String>,
    /** Backend truth: one note can mean many cards (INV-18-03). Never a single assumed card. */
    val cards: List<AddNoteCreatedCardLine>,
    /** A hydration read failure is reported separately; the creation stays created (INV-18-09). */
    val hydrationIssueToken: String?
)

data class AddNoteCreatedCardLine(
    val cardLabel: String,
    val deckLabel: String?
)

/** An unresolved creation record surfaced for recovery (ambiguous outcome). */
data class AddNoteRecoveryState(
    val creationId: NoteCreationId,
    val modelName: String?,
    val ambiguous: Boolean,
    val canAttest: Boolean,
    /** Orphan-media note: media boundaries were entered, the note boundary outcome is unclear. */
    val orphanMediaNote: Boolean
)

/** Save-button + result area. The screen renders exactly this, recomputing nothing (AUDIT-18-11). */
sealed interface AddNoteSaveState {
    data object Idle : AddNoteSaveState
    data class SavingMedia(val step: Int, val total: Int) : AddNoteSaveState
    data object SavingNote : AddNoteSaveState
    data class Created(val summary: AddNoteCreatedSummary) : AddNoteSaveState
    data class RetryAvailable(val message: String) : AddNoteSaveState
    data class Ambiguous(val message: String) : AddNoteSaveState
    data class MediaUnclear(val message: String) : AddNoteSaveState
    data class ValidationMessage(val tokens: List<String>) : AddNoteSaveState
    data class RefusalMessage(val message: String) : AddNoteSaveState
    data class LedgerMessage(val detail: String) : AddNoteSaveState
}

/** Everything the Compose layer renders for a ready editor. */
data class AddNoteEditorState(
    val models: List<AddNoteModelOption>,
    val selectedModelId: String?,
    val modelName: String?,
    val modelIsCloze: Boolean,
    val fields: List<AddNoteFieldState>,
    val tags: List<String>,
    val media: List<AddNoteMediaState>,
    val deckNotice: AddNoteDeckNotice?,
    /** Capability truth: media attachment exists only when the backend claims `storeMedia`. */
    val mediaAttachmentOffered: Boolean,
    /** The single derived flag that enables the save control (AUDIT-18-11). */
    val canSave: Boolean,
    val saveState: AddNoteSaveState,
    /** The backend's declared creation claims, as wording-relevant facts. */
    val noteAndTagsAtomic: Boolean,
    val duplicateProtection: Boolean,
    val recovery: List<AddNoteRecoveryState>
)
