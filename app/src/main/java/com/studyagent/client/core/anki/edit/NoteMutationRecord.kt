package com.studyagent.client.core.anki.edit

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiNoteRef
import kotlinx.serialization.Serializable

/**
 * GATE 17 — the durable record of one note-edit transaction.
 *
 * **Metadata only.** It stores identities, the changed field ordinals and names, the deck move's
 * stable refs, the ordered plan, status and attempt bookkeeping. It never stores field values, tag
 * text, rendered card HTML or a content digest (GATE 17: persist transaction metadata only).
 * Values needed for an in-process retry are kept in memory by the coordinator and are discarded on
 * restart; see [NoteMutationLedger] for the restart rule.
 */
@Serializable
data class NoteMutationRecord(
    val mutationId: NoteMutationId,
    val backendId: AnkiBackendId,
    val cardRef: AnkiCardRef,
    val noteRef: AnkiNoteRef,
    val noteTypeId: String?,
    val changedFields: List<ChangedFieldMark>,
    val tagsChanged: Boolean,
    val deckChange: DeckChange?,
    val plan: NoteMutationPlan,
    val status: NoteMutationStatus,
    /** Index of the last operation whose boundary was entered; -1 before the boundary. */
    val lastEnteredOperation: Int,
    /** Number of boundary entries. Retry increments it; it never resets. */
    val attemptCount: Int,
    val reason: NoteMutationReason,
    /** Set on a record created to resolve a conflict; links back to the mutation it supersedes. */
    val supersedesMutationId: NoteMutationId? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /**
     * GATE 21J — the collection this mutation was admitted against, as its OWNING backend reported
     * it at admission. `null` means the backend had no authoritative discriminator at that moment
     * (the normal AnkiDroid case): unknown, never a match. Optional with a `null` default so a record
     * written before GATE 21J decodes unchanged. It belongs to [backendId] by construction.
     */
    val collectionRef: AnkiCollectionIdentity? = null
) {
    init {
        require(lastEnteredOperation >= -1 && lastEnteredOperation < plan.operations.size) {
            "Operation index is outside the plan"
        }
        require(attemptCount >= 0) { "Attempt count cannot be negative" }
        require(collectionRef == null || collectionRef.backendId == backendId) {
            "A mutation's collection identity must belong to the mutation's own backend"
        }
    }
}

/** Builds the first record for a new mutation: always PREPARED, never with a backend effect. */
fun newPreparedNoteMutationRecord(
    mutationId: NoteMutationId,
    base: NoteEditBase,
    patch: NoteMutationPatch,
    plan: NoteMutationPlan,
    nowEpochMs: Long,
    supersedes: NoteMutationId? = null,
    collectionRef: AnkiCollectionIdentity? = null
): NoteMutationRecord = NoteMutationRecord(
    mutationId = mutationId,
    backendId = base.backendId,
    cardRef = base.cardRef,
    noteRef = base.noteRef,
    noteTypeId = patch.noteTypeId,
    changedFields = patch.fieldChanges.map { ChangedFieldMark(it.ordinal, it.name) },
    tagsChanged = patch.tagChange != null,
    deckChange = patch.deckChange,
    plan = plan,
    status = NoteMutationStatus.PREPARED,
    lastEnteredOperation = -1,
    attemptCount = 0,
    reason = NoteMutationReason.NONE,
    supersedesMutationId = supersedes,
    createdAtEpochMs = nowEpochMs,
    updatedAtEpochMs = nowEpochMs,
    collectionRef = collectionRef
)
