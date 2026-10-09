package com.studyagent.client.ui.screens.editnote

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.NoteConflictGuarantee

/**
 * GATE 17 CHECKPOINT 13 — the edit screen's presentation model.
 *
 * Pure Kotlin: no Compose, no Android, no provider type, and no mutation type. It describes what the
 * editor may show and which controls may be enabled; it can never write anything by itself
 * (INV-17-17). Every "can I edit this" answer here is derived from a backend capability or from the
 * backend's declared semantics, never from the backend's name.
 */
sealed interface EditNoteUiState {
    data object Loading : EditNoteUiState

    /** The backend cannot serve the authoritative read the editor is based on. */
    data class Unavailable(val reason: AnkiAvailability) : EditNoteUiState

    data class Error(val error: AnkiError) : EditNoteUiState

    /**
     * Editing is not offered for this card at all: a missing capability, a note identity the backend
     * does not expose, or field ordinals that do not match template order. The affordance is absent
     * with a reason, never silently degraded (AUDIT-17-12).
     */
    data class Refused(val message: String) : EditNoteUiState

    data class Ready(val editor: EditNoteEditorState) : EditNoteUiState
}

/** What the connected backend actually offers for this note, as capability truth. */
data class EditNoteCapabilities(
    val fields: Boolean,
    val tags: Boolean,
    val deck: Boolean,
    val conflictGuarantee: NoteConflictGuarantee,
    /** A field edit can make the backend generate further cards for the same note. */
    val fieldEditCanGenerateSiblingCards: Boolean,
    /** Fields and tags are one all-or-nothing backend write; a deck move is a second one. */
    val contentWriteAtomic: Boolean,
    /** A write whose last field is empty cannot be represented at this pin. */
    val trailingEmptyFieldRepresentable: Boolean,
    /** The backend can prove whether an ambiguous write applied. Never true at this pin. */
    val authoritativeReconciliation: Boolean
) {
    val anythingEditable: Boolean get() = fields || tags || deck
}

/** One editable field. Identity is the ordinal; the name is a label only (CONTRACT-14). */
data class EditNoteFieldState(
    val ordinal: Int,
    val label: String,
    val originalValue: String,
    val draftValue: String,
    val editable: Boolean,
    val issue: EditNoteIssue? = null
) {
    val changed: Boolean get() = draftValue != originalValue
}

/** Tag editing is a full-set replace: the draft list is the whole desired set (CONTRACT-02). */
data class EditNoteTagsState(
    val originalTags: List<String>,
    val draftTags: List<String>,
    val editable: Boolean,
    val issue: EditNoteIssue? = null
) {
    val changed: Boolean get() = draftTags != originalTags
}

/**
 * Deck editing moves ONE card. Identity is the stable deck id; labels are display only
 * (CONTRACT-03, INV-17-09).
 */
data class EditNoteDeckState(
    val currentDeckId: String?,
    val currentDeckLabel: String?,
    val selectedDeckId: String?,
    val editable: Boolean,
    val options: List<EditNoteDeckOption>,
    val loading: Boolean,
    val issue: EditNoteIssue? = null
) {
    val changed: Boolean get() = selectedDeckId != null && selectedDeckId != currentDeckId
}

data class EditNoteDeckOption(
    val deckId: String,
    val label: String,
    /** A filtered deck is refused by the backend before any write. */
    val filtered: Boolean,
    /** The backend did not say whether the deck is filtered, so it cannot be verified. */
    val unverifiable: Boolean
) {
    val selectable: Boolean get() = !filtered && !unverifiable
}

/** A user-facing problem with one control. Never carries a stack trace or a provider message. */
data class EditNoteIssue(val message: String, val blocking: Boolean)

/** A standing contract notice (why something behaves the way it does), not an error. */
data class EditNoteNotice(val message: String, val tone: EditNoticeTone)

enum class EditNoticeTone { INFO, WARNING }

/** The save/recovery situation the screen has to render. */
sealed interface EditNoteSaveState {
    data object Idle : EditNoteSaveState
    data object Saving : EditNoteSaveState

    /** Durable APPLIED, verified by an authoritative post-write read. */
    data class Saved(val message: String) : EditNoteSaveState

    /** Nothing was written: validation, refusal, or an empty patch. */
    data class Blocked(val message: String, val issues: List<String> = emptyList()) : EditNoteSaveState

    /** Proven not applied; the same transaction may be retried under the same id. */
    data class RetryAvailable(val message: String, val mutationId: String) : EditNoteSaveState

    /** The base drifted before the write. Nothing was written; a fresh edit supersedes this one. */
    data class Conflicted(val message: String, val mutationId: String) : EditNoteSaveState

    /**
     * The outcome cannot be proven either way. The note stays blocked until a human checks the
     * collection and attests. [evidence] is a read-only state comparison, never proof.
     */
    data class Ambiguous(
        val message: String,
        val mutationId: String,
        val evidenceMessage: String?
    ) : EditNoteSaveState
}

/** Everything the ready screen renders. */
data class EditNoteEditorState(
    val cardRef: AnkiCardRef,
    val noteIdLabel: String?,
    val noteTypeLabel: String?,
    val cardLabel: String?,
    val capabilities: EditNoteCapabilities,
    val fields: List<EditNoteFieldState>,
    val tags: EditNoteTagsState,
    val deck: EditNoteDeckState,
    val notices: List<EditNoteNotice>,
    val saveState: EditNoteSaveState,
    /** A blocking mutation already covers this note (its own editor is not offered). */
    val blockedByMutation: EditNoteBlockedMutation?
) {
    val dirty: Boolean
        get() = fields.any { it.changed } || tags.changed || deck.changed

    /** Save is offered only when something changed and nothing is blocking. */
    val canSave: Boolean
        get() = dirty && saveState !is EditNoteSaveState.Saving && blockedByMutation == null &&
            fields.none { it.issue?.blocking == true } && tags.issue?.blocking != true
}

/** An unresolved note mutation that owns this note, described without any note content. */
data class EditNoteBlockedMutation(
    val mutationId: String,
    val statusLabel: String,
    val message: String,
    val canRecover: Boolean,
    val canAttest: Boolean,
    val canRetry: Boolean,
    val canResume: Boolean,
    val canRestartAfterConflict: Boolean,
    val evidenceMessage: String? = null
)
