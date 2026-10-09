package com.studyagent.client.core.anki.create

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiNoteModelRef

/**
 * GATE 18 — the backend-facing creation contract (CONTRACT-18-38/39/40, locked).
 *
 * Media storage and note creation are **separate irreversible operations** on every pinned backend,
 * so they are separate methods with separate results: no single `createNote()` may hide the media
 * boundary (AUDIT-18-10). Tags travel inside the note creation call because the pinned provider
 * applies them in the same transaction (ATOMIC_SINGLE_OPERATION, docs/GATE_18 §8).
 */

/**
 * One note-creation request at the backend boundary. Fields are ordered values — one per schema
 * ordinal (CONTRACT-18-03); tags are the full desired set (canonified by the backend inside the
 * same transaction). No deck: the pinned provider accepts none (CONTRACT-18-05).
 */
data class CreateNoteBackendRequest(
    val backendId: AnkiBackendId,
    val model: AnkiNoteModelRef,
    val orderedFields: List<String>,
    val tags: List<String>
) {
    init {
        require(model.backendId == backendId) { "Creation request carries one backend identity" }
        require(orderedFields.isNotEmpty()) { "A note needs at least one field" }
        require(orderedFields.none { it.indexOf('\u001F') >= 0 }) {
            "Field values must not contain the field separator"
        }
        require(tags.none { it.isBlank() || it.any { c -> c.isWhitespace() || c.isISOControl() } }) {
            "Tags must be non-blank and free of whitespace/control characters"
        }
    }
}

/**
 * CONTRACT-18-39 — what one create-note dispatch reported at the boundary. Exactly three branches
 * exist because the pinned backends have no duplicate rejection and no conflict semantic
 * (docs/GATE_18 §7/§16): anything not proven created or proven not-created is unknown.
 */
sealed interface CreateNoteBackendResult {

    /**
     * The backend returned the new note's authoritative id (rslib `AddNoteResponse.note_id` through
     * the provider URI). Proof the note exists; generated cards are still discovered by the
     * hydration read, never assumed here (CONTRACT-18-31).
     */
    data class ConfirmedCreated(val noteId: String) : CreateNoteBackendResult {
        init { require(noteId.isNotBlank()) { "A created note must carry its id" } }
    }

    /** Proven: nothing was created (refusal before the irreversible write). */
    data class ConfirmedNotCreated(val error: AnkiError) : CreateNoteBackendResult

    /** The note may or may not exist. Never retried automatically (INV-18-08). */
    data class OutcomeUnknown(val error: AnkiError) : CreateNoteBackendResult
}

/**
 * One media-storage request at the backend boundary. [contentUri] is a string the *backend process*
 * can read (a content URI chosen by the user); [preferredName] is a request, never an instruction —
 * the backend owns the final filename (CONTRACT-18-27). [sizeBytes] is the locally observed size,
 * carried so the gateway can enforce the product limit without re-probing.
 */
data class StoreMediaBackendRequest(
    val backendId: AnkiBackendId,
    val contentUri: String,
    val preferredName: String,
    val mimeType: String,
    val sizeBytes: Long
) {
    init {
        require(contentUri.isNotBlank()) { "Media needs a readable source URI" }
        require(preferredName.isNotBlank()) { "Media needs a preferred name" }
        require(preferredName.indexOf('\u001F') < 0 && preferredName.none { it.isISOControl() }) {
            "Preferred names must be plain text"
        }
        require(mimeType.isNotBlank()) { "Media needs a MIME type" }
        require(sizeBytes > 0) { "Media size must be known and positive" }
    }
}

/**
 * CONTRACT-18-40 — what one media-store dispatch reported. [Stored.mediaName] is the authoritative
 * name the backend chose (collision-renamed, NFC-normalized, deduplicated by content hash); no
 * richer reference is invented because the backend returns a name.
 */
sealed interface MediaStoreBackendResult {

    data class Stored(val mediaName: String) : MediaStoreBackendResult {
        init { require(mediaName.isNotBlank()) { "Stored media must carry the backend's name" } }
    }

    data class ConfirmedNotStored(val error: AnkiError) : MediaStoreBackendResult

    data class OutcomeUnknown(val error: AnkiError) : MediaStoreBackendResult
}

/**
 * Evidence-based claims a backend makes about creation (the GATE 17 `NoteMutationSemantics`
 * pattern). Every field is a claim that must be backed by a pinned source reference in
 * `docs/GATE_18_BACKEND_CREATION_CONTRACT.md`; defaults are the safe answer.
 */
data class NoteCreationSemantics(
    /** Note + tags land in one backend call that is all-or-nothing. */
    val noteAndTagsAtomic: Boolean,
    /** The caller can choose the deck at creation time. False at the pinned provider. */
    val callerDeckSelection: Boolean,
    /** The backend rejects duplicates at creation. False: Anki's check is advisory and unexposed. */
    val duplicateRejection: Boolean,
    /** The backend accepts a caller-supplied idempotency key. False: no such input exists. */
    val backendHonoursCreationId: Boolean,
    /** An ambiguous creation can be resolved authoritatively by a read. False at this pin. */
    val authoritativeCreationReconciliation: Boolean,
    /** Stored media is content-deduplicated by the backend (re-store of equal bytes is safe). */
    val mediaStoreContentDeduplicated: Boolean
) {
    companion object {
        /** Nothing is claimed. Any backend without evidence uses this. */
        val UNVERIFIED = NoteCreationSemantics(
            noteAndTagsAtomic = false,
            callerDeckSelection = false,
            duplicateRejection = false,
            backendHonoursCreationId = false,
            authoritativeCreationReconciliation = false,
            mediaStoreContentDeduplicated = false
        )

        /**
         * AnkiDroid v2.24.1 public provider, resolved in `docs/GATE_18_BACKEND_CREATION_CONTRACT.md`
         * (§8 atomicity, §6 deck, §7 duplicates, §14 idempotency/reconciliation, §9 media).
         */
        val ANKIDROID_V2_24_1 = NoteCreationSemantics(
            noteAndTagsAtomic = true,
            callerDeckSelection = false,
            duplicateRejection = false,
            backendHonoursCreationId = false,
            authoritativeCreationReconciliation = false,
            mediaStoreContentDeduplicated = true
        )
    }
}
