package com.studyagent.client.core.anki.edit

import kotlinx.serialization.Serializable

/**
 * GATE 17 — identity of one note-edit transaction. Deliberately separate from `ReviewCommitId`
 * and `ReviewerActionId`: a note edit never shares a ledger, a status type or an id space with the
 * rating or reviewer-action protocols.
 *
 * The value is generated locally (see [NoteMutationIdSource]); it is never derived from a note
 * revision, timestamp or hash.
 */
@Serializable
@JvmInline
value class NoteMutationId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Invalid note mutation id" }
        require(value.none { it.isISOControl() }) { "Note mutation id must not contain control characters" }
    }

    companion object {
        const val MAX_LENGTH: Int = 128
    }
}

/** Produces fresh [NoteMutationId] values. Injected so tests can be deterministic. */
fun interface NoteMutationIdSource {
    fun next(): NoteMutationId
}

/** Production source: random UUIDs. Uniqueness is what matters; the value carries no meaning. */
object UuidNoteMutationIdSource : NoteMutationIdSource {
    override fun next(): NoteMutationId = NoteMutationId(java.util.UUID.randomUUID().toString())
}
