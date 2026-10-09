package com.studyagent.client.core.anki.create

import kotlinx.serialization.Serializable

/**
 * GATE 18 — identity of one note-creation transaction. Deliberately separate from
 * `NoteMutationId`, `ReviewCommitId` and `ReviewerActionId`: creation never shares a ledger, a
 * status type or an id space with the edit, rating or reviewer-action protocols (INV-18-17).
 *
 * This is Study-Agent's LOCAL transaction identity. It is NOT backend idempotency: the pinned
 * provider accepts no caller key, so a replayed request is a brand-new note (CONTRACT-18-19,
 * INV-18-07). The value is generated locally; it is never derived from content or timestamps.
 */
@Serializable
@JvmInline
value class NoteCreationId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Invalid note creation id" }
        require(value.none { it.isISOControl() }) { "Note creation id must not contain control characters" }
    }

    companion object {
        const val MAX_LENGTH: Int = 128
    }
}

/** Produces fresh [NoteCreationId] values. Injected so tests can be deterministic. */
fun interface NoteCreationIdSource {
    fun next(): NoteCreationId
}

/** Production source: random UUIDs. Uniqueness is what matters; the value carries no meaning. */
object UuidNoteCreationIdSource : NoteCreationIdSource {
    override fun next(): NoteCreationId = NoteCreationId(java.util.UUID.randomUUID().toString())
}
