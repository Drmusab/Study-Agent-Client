package com.studyagent.client.core.anki

/**
 * GATE 18 — note types ("models") as creation input, read from the backend's authoritative surface.
 *
 * CONTRACT-18-02: the creation UI derives every field editor and every create payload from these
 * objects — never from rendered card HTML, never from a template body, never from memory. Identity
 * is the backend's numeric note-type id carried as the domain's string identity; names are display
 * only and can change without identity changing.
 */

/** The backend kind of a note type. Only documented codes exist; anything else is [UNKNOWN]. */
enum class AnkiNoteModelKind {
    /** Plain note type: one card per template whose front renders non-empty (Anki owns generation). */
    NORMAL,

    /** Cloze note type: cards are generated per cloze number in the fields (Anki owns generation). */
    CLOZE,

    /** A code this build has not verified. Creation must refuse it rather than guess. */
    UNKNOWN
}

/** Stable backend-qualified identity of one note type. Never derived from the name. */
data class AnkiNoteModelRef(
    val backendId: AnkiBackendId,
    val modelId: String,
    val collectionKey: String? = null
) {
    init {
        require(modelId.isNotBlank()) { "A note type reference needs an id" }
        require(modelId.toLongOrNull() != null || modelId.any { !it.isDigit() }) {
            "Note type id must be a plain identifier"
        }
    }

    val stableKey: String get() = "anki:${backendId.stableId}:${collectionKey ?: "?"}:model:$modelId"
}

/**
 * One field of a note type. [ordinal] is the field's identity for creation (the pinned provider
 * splits the `flds` payload positionally — CONTRACT-18-03); [name] is the display label and the
 * drift-detection key, never a write key.
 */
data class AnkiNoteModelField(
    val ordinal: Int,
    val name: String
) {
    init {
        require(ordinal >= 0) { "Field ordinals start at zero" }
        require(name.isNotBlank()) { "A field needs a name" }
        require(name.indexOf('\u001F') < 0) { "Field names cannot contain the field separator" }
    }
}

/**
 * The authoritative creation schema of one note type.
 *
 * - [fields] in backend order; the list is the whole schema (no field may be omitted at creation).
 * - [kind] distinguishes Cloze content handling (CONTRACT-18-25: cloze text is submitted as normal
 *   field content; card generation stays Anki's).
 * - [templateCount] is informational only ("how many cards *might* this produce"); it is never used
 *   to generate or enumerate cards (INV-18-02/03).
 * - [defaultDeckId] is the note type's stored default/last deck, surfaced **for display only**: at
 *   the pinned AnkiDroid contract the caller cannot pass a deck at creation, and Anki may fall back
 *   to its Default deck when this deck is missing or filtered (CONTRACT-18-05). `null` = the backend
 *   did not report one.
 */
data class AnkiNoteModel(
    val ref: AnkiNoteModelRef,
    val name: String,
    val kind: AnkiNoteModelKind,
    val fields: List<AnkiNoteModelField>,
    val templateCount: Int?,
    val defaultDeckId: String? = null
) {
    init {
        require(name.isNotBlank()) { "A note type needs a name" }
        require(fields.isNotEmpty()) { "A note type needs at least one field" }
        require(fields.map { it.ordinal } == fields.indices.toList()) {
            "Field ordinals must be dense and start at zero"
        }
        require(fields.map { it.name }.distinct().size == fields.size) { "Field names must be unique" }
        require(templateCount == null || templateCount >= 0) { "Template count cannot be negative" }
    }

    val fieldCount: Int get() = fields.size

    /** True when [other] could not have been produced by the same schema read (drift detection). */
    fun schemaMatches(other: AnkiNoteModel): Boolean =
        ref.modelId == other.ref.modelId && fields == other.fields
}

/**
 * GATE 18 — one card Anki generated for a freshly created note, as reported by the post-create
 * read (`notes/<id>/cards`). Identity and deck come from the backend; nothing here is derived from
 * the creation request (INV-18-16).
 */
data class AnkiCreatedNoteCard(
    val ref: AnkiCardRef,
    val cardName: String?,
    val deckRef: AnkiDeckRef?,
    val deckName: String?
) {
    init {
        require(deckRef == null || deckRef.backendId == ref.backendId)
    }
}

/**
 * GATE 18 — the authoritative post-creation snapshot of one created note (CONTRACT-18-30/31).
 *
 * [cards] is the backend's own enumeration of generated cards: it may hold one card or many, and a
 * note is never assumed to mean one card (INV-18-03). All fields/tags values are the backend's
 * stored values after canonification/normalization — not the submitted draft.
 */
data class AnkiCreatedNote(
    val noteRef: AnkiNoteRef,
    val model: AnkiNoteModelRef?,
    val modelName: String?,
    val fields: List<String>,
    val tags: List<String>,
    val cards: List<AnkiCreatedNoteCard>
) {
    init {
        require(cards.all { it.ref.noteId == noteRef.noteId }) {
            "Every generated card must belong to the created note"
        }
    }
}
