package com.studyagent.client.core.anki.create

import com.studyagent.client.core.anki.AnkiBackendId
import kotlinx.serialization.Serializable

/** What kind of media a creation step stores. Determines the field-reference syntax only. */
@Serializable
enum class CreationMediaKind {
    /** Referenced as `<img src="NAME">` (Anki image semantics). */
    IMAGE,

    /** Referenced as `[sound:NAME]` (Anki audio semantics). */
    AUDIO
}

/**
 * One media step of a creation plan — metadata only. [requestedName] is the sanitized preferred
 * name Study-Agent asked for; [storedName] is the authoritative name the backend returned, persisted
 * as soon as it is known and before the next boundary (docs/GATE_18 §15). No URI and no content is
 * persisted: after a restart the payload is gone and the record is abandoned (§18).
 */
@Serializable
data class CreationMediaStep(
    val index: Int,
    val requestedName: String,
    val kind: CreationMediaKind,
    val storedName: String? = null
) {
    init {
        require(index >= 0) { "Media step indices start at zero" }
        require(requestedName.isNotBlank()) { "A media step needs the requested name" }
        require(storedName == null || storedName.isNotBlank()) { "A stored name must be usable" }
    }
}

/**
 * GATE 18 — the durable record of one note-creation transaction (CONTRACT-18-15/41).
 *
 * **Metadata only.** It stores identities, counts, the ordered media plan with returned names, the
 * status and attempt bookkeeping. It never stores field values, tag text, media URIs or rendered
 * content: an in-process retry keeps the payload in memory (like GATE 17), and a restart abandons
 * the record instead of persisting content.
 */
@Serializable
data class NoteCreationRecord(
    val creationId: NoteCreationId,
    val backendId: AnkiBackendId,
    val collectionKey: String?,
    val modelId: String,
    val modelName: String?,
    val fieldCount: Int,
    val tagCount: Int,
    val mediaSteps: List<CreationMediaStep>,
    val status: NoteCreationStatus,
    /**
     * Last boundary entered: -1 before any boundary, `0..mediaSteps.lastIndex` for media steps,
     * `mediaSteps.size` for the note boundary. Durable BEFORE the boundary is entered (§15).
     */
    val lastEnteredStep: Int,
    /** Note-boundary entries. A user retry increments it; it never resets. */
    val attemptCount: Int,
    val reason: NoteCreationReason,
    /** The backend-returned note id once creation is confirmed; null before (and for attestations). */
    val createdNoteId: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
) {
    init {
        require(fieldCount > 0) { "A creation record needs its field count" }
        require(tagCount >= 0) { "Tag count cannot be negative" }
        require(attemptCount >= 0) { "Attempt count cannot be negative" }
        require(lastEnteredStep >= -1 && lastEnteredStep <= mediaSteps.size) {
            "Step index is outside the plan"
        }
        require(mediaSteps.map { it.index } == mediaSteps.indices.toList()) {
            "Media step indices must be dense and start at zero"
        }
        require(createdNoteId == null || createdNoteId.isNotBlank()) { "A note id must be usable" }
        require(createdNoteId == null || status == NoteCreationStatus.CREATED ||
            reason == NoteCreationReason.USER_ATTESTED_CREATED) {
            "A note id only exists once creation is confirmed"
        }
    }

    /** True when at least one media boundary may have been entered (orphan-media note applies). */
    val mediaBoundaryEntered: Boolean get() = lastEnteredStep >= 0 && mediaSteps.isNotEmpty()

    /** True when the note boundary may have been entered. */
    val noteBoundaryEntered: Boolean get() = lastEnteredStep >= mediaSteps.size && status != NoteCreationStatus.PREPARED

    val storedMediaNames: List<String> get() = mediaSteps.mapNotNull { it.storedName }
}

/** Builds the first record for a new creation: always PREPARED, never with a backend effect. */
fun newPreparedNoteCreationRecord(
    creationId: NoteCreationId,
    backendId: AnkiBackendId,
    collectionKey: String?,
    modelId: String,
    modelName: String?,
    fieldCount: Int,
    tagCount: Int,
    mediaSteps: List<CreationMediaStep>,
    nowEpochMs: Long
): NoteCreationRecord = NoteCreationRecord(
    creationId = creationId,
    backendId = backendId,
    collectionKey = collectionKey,
    modelId = modelId,
    modelName = modelName,
    fieldCount = fieldCount,
    tagCount = tagCount,
    mediaSteps = mediaSteps,
    status = NoteCreationStatus.PREPARED,
    lastEnteredStep = -1,
    attemptCount = 0,
    reason = NoteCreationReason.NONE,
    createdNoteId = null,
    createdAtEpochMs = nowEpochMs,
    updatedAtEpochMs = nowEpochMs
)
