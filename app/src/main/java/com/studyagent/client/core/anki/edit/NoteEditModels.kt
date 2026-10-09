package com.studyagent.client.core.anki.edit

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardDetails
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiNoteField
import com.studyagent.client.core.anki.AnkiNoteRef
import kotlinx.serialization.Serializable

/**
 * GATE 17 — the exact state a user started editing from, taken from one authoritative read of the
 * card's note (Card Details). Field identity is the authoritative ordinal (template order); every
 * entry's ordinal must equal its position, otherwise the base is refused by [from].
 *
 * The base is in-memory only. It holds note content, and it is never written to the ledger
 * (GATE 17: persist transaction metadata, not card content).
 */
data class NoteEditBase(
    val cardRef: AnkiCardRef,
    val noteRef: AnkiNoteRef,
    val noteTypeId: String?,
    val fields: List<AnkiNoteField>,
    val tags: List<String>,
    val deckRef: AnkiDeckRef?
) {
    init {
        require(cardRef.backendId == noteRef.backendId) { "Card and note must share a backend" }
        require(cardRef.noteId == null || cardRef.noteId == noteRef.noteId) {
            "Card reference must belong to the same note"
        }
        require(fields.withIndex().all { (index, field) -> field.ordinal == index }) {
            "Field ordinals must equal their template positions"
        }
        require(deckRef == null || deckRef.backendId == noteRef.backendId) { "Deck must share the backend" }
    }

    val backendId: AnkiBackendId get() = noteRef.backendId

    companion object {
        /**
         * Builds a base from one card-details read, or returns null when the read cannot support a
         * safe edit (no note identity, no field list, no tags, or field ordinals that do not match
         * template order). Null means "editing refused", never a guessed base.
         */
        fun from(details: AnkiCardDetails): NoteEditBase? {
            val noteRef = details.noteRef ?: return null
            val fields = details.fields ?: return null
            val tags = details.tags ?: return null
            val cardRef = details.cardRef
            if (cardRef.backendId != noteRef.backendId) return null
            if (cardRef.noteId != null && cardRef.noteId != noteRef.noteId) return null
            if (fields.withIndex().any { (index, field) -> field.ordinal != index }) return null
            val deck = details.deckRef
            if (deck != null && deck.backendId != noteRef.backendId) return null
            return NoteEditBase(
                cardRef = cardRef,
                noteRef = noteRef,
                noteTypeId = details.noteTypeId,
                fields = fields,
                tags = tags,
                deckRef = deck
            )
        }
    }
}

/**
 * What the user wants. Absent entries mean "unchanged":
 * - [fieldValues]: ordinal -> new value (only changed fields need to be present);
 * - [tags]: `null` = tags unchanged, otherwise the complete desired tag set;
 * - [targetDeck]: `null` = deck unchanged, otherwise the desired deck by stable identity.
 */
data class NoteEditDraft(
    val fieldValues: Map<Int, String> = emptyMap(),
    val tags: List<String>? = null,
    val targetDeck: AnkiDeckRef? = null
)

/** A changed field, in memory only: old and new values are note content. */
data class NoteFieldChange(
    val ordinal: Int,
    val name: String,
    val oldValue: String,
    val newValue: String
)

/** A changed tag set. [newTags] replaces the whole set (the backend's tag write is a full replace). */
data class TagChange(val oldTags: List<String>, val newTags: List<String>)

/** A card-scoped deck move. [fromDeck] is the base deck (null when the base never named one). */
@Serializable
data class DeckChange(val fromDeck: AnkiDeckRef?, val toDeck: AnkiDeckRef)

/**
 * The minimal, validated difference between a base and a draft. Nothing here is written when the
 * patch is empty: an empty patch is [NoteMutationOutcome.NoChanges], never a record.
 */
data class NoteMutationPatch(
    val noteTypeId: String?,
    val fieldChanges: List<NoteFieldChange>,
    val tagChange: TagChange?,
    val deckChange: DeckChange?
) {
    val isEmpty: Boolean get() = fieldChanges.isEmpty() && tagChange == null && deckChange == null
    val touchesContent: Boolean get() = fieldChanges.isNotEmpty() || tagChange != null
}

/** One ordered backend operation. Content (fields and tags) is one operation; deck is another. */
@Serializable
sealed interface NoteMutationOperation {
    /**
     * Fields and/or tags, sent as one backend call. Whether that call is atomic is a per-backend
     * claim ([NoteMutationSemantics.contentWriteAtomic]), not something this type asserts.
     */
    @Serializable
    data class UpdateNoteContent(val updatesFields: Boolean, val updatesTags: Boolean) : NoteMutationOperation {
        init {
            require(updatesFields || updatesTags) { "A content operation must change something" }
        }
    }

    /** Move the source card to [toDeck]. Card-scoped only; never a note-wide move. */
    @Serializable
    data class ChangeDeck(val toDeck: AnkiDeckRef) : NoteMutationOperation
}

/** The ordered operations a mutation will attempt. Persisted as metadata; contains no note content. */
@Serializable
data class NoteMutationPlan(val operations: List<NoteMutationOperation>) {
    init {
        require(operations.isNotEmpty()) { "A plan needs at least one operation" }
    }
}

/**
 * Concrete, materialized payload for ONE backend step. Field values and tags are note content, so
 * this type lives only in memory for the duration of a mutation attempt.
 */
sealed interface NoteMutationStep {
    /** [fieldValues] is the complete positional value list (template order) when fields change. */
    data class UpdateNoteContent(
        val fieldValues: List<String>?,
        val tags: List<String>?
    ) : NoteMutationStep

    data class ChangeDeck(val fromDeck: AnkiDeckRef?, val toDeck: AnkiDeckRef) : NoteMutationStep
}

/** Persisted pointer to one changed field: identity and name only, never the values. */
@Serializable
data class ChangedFieldMark(val ordinal: Int, val name: String)

/** Why a non-normal status was chosen. Metadata only; lets the UI explain without content. */
@Serializable
enum class NoteMutationReason {
    NONE,
    CONFLICT_BEFORE_WRITE,
    CONFLICT_REPORTED_BY_BACKEND,
    NOT_APPLIED_BY_BACKEND,
    OUTCOME_UNKNOWN,
    PARTIAL_OPERATION_UNKNOWN,
    ABANDONED_ON_RESTART,
    RECOVERED_AFTER_RESTART,
    RECONCILED_APPLIED,
    RECONCILED_NOT_APPLIED,

    /**
     * The backend confirmed the write, but the authoritative post-write read does not hold the
     * intended state (CONTRACT-06/15: a row count proves the provider path completed, not the stored
     * value). The record becomes AMBIGUOUS; it is never reported as APPLIED.
     */
    POST_WRITE_VERIFICATION_MISMATCH,

    /** The post-write read needed to confirm the write could not be obtained. */
    POST_WRITE_UNVERIFIED,

    /**
     * A human inspected the collection and attested that the edit IS present. This is a *human*
     * resolution of an ambiguous outcome, not backend proof: no receipt or transaction correlation
     * exists at the pin, so the record says who confirmed it.
     */
    USER_ATTESTED_APPLIED,

    /** A human inspected the collection and attested that the edit is NOT present. */
    USER_ATTESTED_NOT_APPLIED
}

/** Why a draft cannot become a patch. Raised before any transaction; never RETRY_ALLOWED. */
sealed interface NoteEditValidationError {
    data class UnknownField(val ordinal: Int) : NoteEditValidationError
    data class FieldContainsSeparator(val ordinal: Int) : NoteEditValidationError
    /** Field edits need a note type identity to prove the positional mapping. */
    data object FieldIdentityUnavailable : NoteEditValidationError
    data class InvalidTag(val tag: String) : NoteEditValidationError
    data object DeckTargetWrongBackend : NoteEditValidationError
    data object DeckTargetNotFound : NoteEditValidationError
    data object DeckTargetFiltered : NoteEditValidationError
    data object DeckTargetUnverifiable : NoteEditValidationError
    data object SourceDeckUnknown : NoteEditValidationError
    data object SourceDeckFiltered : NoteEditValidationError
}

/** Input to the backend for one step of one mutation. */
data class BackendNoteMutationRequest(
    val mutationId: NoteMutationId,
    val cardRef: AnkiCardRef,
    val noteRef: AnkiNoteRef,
    val stepIndex: Int,
    val step: NoteMutationStep
)

/** Input to read-only reconciliation of an ambiguous mutation. */
data class NoteMutationReconciliationRequest(
    val mutationId: NoteMutationId,
    val cardRef: AnkiCardRef,
    val noteRef: AnkiNoteRef,
    val plan: NoteMutationPlan,
    /** Index of the last operation whose boundary was entered, -1 when none was. */
    val lastEnteredOperation: Int
)
