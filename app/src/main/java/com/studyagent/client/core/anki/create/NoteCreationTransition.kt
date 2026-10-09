package com.studyagent.client.core.anki.create

/**
 * GATE 18 — the closed transition table for note creation (CONTRACT-18-41, locked).
 *
 * Every legal change is one [NoteCreationEvent] applied to one source status. Anything not in this
 * table is rejected and the record is left untouched. There is no generic "set status" path.
 *
 * Table (source -> target, event):
 *
 * | From          | Event                          | To            | Guard / effect                            |
 * |---------------|--------------------------------|---------------|-------------------------------------------|
 * | PREPARED      | EnterMediaBoundary(0)          | STORING_MEDIA | first media step; lastEntered = 0          |
 * | PREPARED      | EnterNoteBoundary              | CREATING_NOTE | attempt+1; lastEntered = mediaSteps.size   |
 * | PREPARED      | RestartAbandoned               | ABANDONED     | payload gone; no boundary was entered      |
 * | STORING_MEDIA | EnterMediaBoundary(i)          | STORING_MEDIA | i == lastEntered + 1 (next step)           |
 * | STORING_MEDIA | MediaStored(i, name)           | STORING_MEDIA | i == lastEntered; stores the returned name |
 * | STORING_MEDIA | MediaConfirmedNotStored        | RETRY_ALLOWED | no note effect; media replay is dedupe-safe|
 * | STORING_MEDIA | MediaOutcomeUnknown            | ABANDONED     | no note effect; orphan risk disclosed      |
 * | STORING_MEDIA | EnterNoteBoundary              | CREATING_NOTE | every media step must have a stored name;  |
 * |               |                                |               | attempt+1; lastEntered = mediaSteps.size   |
 * | PREPARED      | PreBoundaryRefused             | ABANDONED     | drift/model-gone before any boundary       |
 * | STORING_MEDIA | PreBoundaryRefused             | ABANDONED     | drift/model-gone before the note boundary; |
 * |               |                                |               | stored media stays as disclosed orphan     |
 * | STORING_MEDIA | RestartAbandoned               | ABANDONED     | payload gone; orphan note if media ran     |
 * | CREATING_NOTE | BackendConfirmedCreated(id)    | CREATED       | terminal positive                          |
 * | CREATING_NOTE | BackendConfirmedNotCreated     | RETRY_ALLOWED | proven non-creation                        |
 * | CREATING_NOTE | BackendOutcomeUnknown          | AMBIGUOUS     | never retried (INV-18-08)                  |
 * | CREATING_NOTE | FinalizationFailedAfterConfirm | AMBIGUOUS     | backend said yes, durable write failed     |
 * | CREATING_NOTE | RecoveryNormalizedUnknown      | AMBIGUOUS     | restart found an unclosed boundary         |
 * | RETRY_ALLOWED | RetryRequested                 | PREPARED      | same id; media steps reset (dedupe-safe)   |
 * | RETRY_ALLOWED | RestartAbandoned               | ABANDONED     | payload gone after restart                 |
 * | AMBIGUOUS     | UserAttestedCreated            | CREATED       | human attestation, recorded as such        |
 * | AMBIGUOUS     | UserAttestedAbsent             | ABANDONED     | human attestation; closed                  |
 * | CREATED       | NoteHydrationFailed            | CREATED       | reason recorded; creation stays confirmed  |
 *
 * CREATED and ABANDONED have no other outgoing edges. AMBIGUOUS has no automatic replay edge: only
 * an explicit human attestation can close it, and the attestation is recorded in [NoteCreationReason]
 * so it is never mistaken for backend proof (INV-18-09: a confirmed creation is never re-created
 * because hydration fails — that is exactly the `NoteHydrationFailed` row).
 */
sealed interface NoteCreationEvent {
    data class EnterMediaBoundary(val index: Int) : NoteCreationEvent
    data class MediaStored(val index: Int, val storedName: String) : NoteCreationEvent
    data object MediaConfirmedNotStored : NoteCreationEvent
    data object MediaOutcomeUnknown : NoteCreationEvent
    data object EnterNoteBoundary : NoteCreationEvent
    data class BackendConfirmedCreated(val noteId: String) : NoteCreationEvent
    data object BackendConfirmedNotCreated : NoteCreationEvent
    data object BackendOutcomeUnknown : NoteCreationEvent
    data object FinalizationFailedAfterConfirm : NoteCreationEvent
    data object RecoveryNormalizedUnknown : NoteCreationEvent
    data object PreBoundaryRefused : NoteCreationEvent
    data object RetryRequested : NoteCreationEvent
    data object RestartAbandoned : NoteCreationEvent
    data object UserAttestedCreated : NoteCreationEvent
    data object UserAttestedAbsent : NoteCreationEvent
    data object NoteHydrationFailed : NoteCreationEvent
}

/** What a human concluded about an AMBIGUOUS creation after checking the collection. */
enum class NoteCreationAttestation {
    /** "I checked Anki: a note from this attempt exists." */
    CREATED_IN_COLLECTION,

    /** "I checked Anki: there is no such note." The record is closed; a fresh creation may start. */
    ABSENT_FROM_COLLECTION
}

/** Result of applying one event. A rejected event never produces a changed record. */
sealed interface NoteCreationTransitionResult {
    data class Accepted(val record: NoteCreationRecord) : NoteCreationTransitionResult
    data class Rejected(val from: NoteCreationStatus, val event: NoteCreationEvent) : NoteCreationTransitionResult
}

object NoteCreationTransitions {

    /** Applies [event] to [record]. The only way a creation status changes in GATE 18. */
    fun apply(
        record: NoteCreationRecord,
        event: NoteCreationEvent,
        nowEpochMs: Long
    ): NoteCreationTransitionResult {
        val from = record.status
        fun accept(
            to: NoteCreationStatus,
            lastEntered: Int = record.lastEnteredStep,
            attempts: Int = record.attemptCount,
            reason: NoteCreationReason = record.reason,
            noteId: String? = record.createdNoteId,
            mediaSteps: List<CreationMediaStep> = record.mediaSteps
        ): NoteCreationTransitionResult = NoteCreationTransitionResult.Accepted(
            record.copy(
                status = to,
                lastEnteredStep = lastEntered,
                attemptCount = attempts,
                reason = reason,
                createdNoteId = noteId,
                mediaSteps = mediaSteps,
                updatedAtEpochMs = nowEpochMs
            )
        )
        val rejected = NoteCreationTransitionResult.Rejected(from, event)

        return when (from) {
            NoteCreationStatus.PREPARED -> when (event) {
                is NoteCreationEvent.EnterMediaBoundary ->
                    if (event.index == 0 && record.mediaSteps.isNotEmpty()) {
                        accept(NoteCreationStatus.STORING_MEDIA, lastEntered = 0)
                    } else rejected
                is NoteCreationEvent.EnterNoteBoundary ->
                    if (record.mediaSteps.isEmpty()) {
                        accept(
                            NoteCreationStatus.CREATING_NOTE,
                            lastEntered = record.mediaSteps.size,
                            attempts = record.attemptCount + 1
                        )
                    } else rejected
                is NoteCreationEvent.PreBoundaryRefused ->
                    accept(NoteCreationStatus.ABANDONED, reason = NoteCreationReason.PRE_BOUNDARY_REFUSED)
                is NoteCreationEvent.RestartAbandoned ->
                    accept(NoteCreationStatus.ABANDONED, reason = NoteCreationReason.ABANDONED_ON_RESTART)
                else -> rejected
            }

            NoteCreationStatus.STORING_MEDIA -> when (event) {
                is NoteCreationEvent.EnterMediaBoundary ->
                    if (event.index == record.lastEnteredStep + 1 && event.index < record.mediaSteps.size) {
                        accept(NoteCreationStatus.STORING_MEDIA, lastEntered = event.index)
                    } else rejected
                is NoteCreationEvent.MediaStored ->
                    if (event.index == record.lastEnteredStep &&
                        event.index < record.mediaSteps.size &&
                        record.mediaSteps[event.index].storedName == null &&
                        event.storedName.isNotBlank()
                    ) {
                        val steps = record.mediaSteps.toMutableList()
                        steps[event.index] = steps[event.index].copy(storedName = event.storedName)
                        accept(NoteCreationStatus.STORING_MEDIA, mediaSteps = steps)
                    } else rejected
                is NoteCreationEvent.MediaConfirmedNotStored ->
                    accept(NoteCreationStatus.RETRY_ALLOWED, reason = NoteCreationReason.NOT_CREATED_BY_BACKEND)
                is NoteCreationEvent.MediaOutcomeUnknown ->
                    // No note effect is possible yet; the record closes with the orphan risk
                    // disclosed instead of pretending to know what the media store did.
                    accept(NoteCreationStatus.ABANDONED, reason = NoteCreationReason.OUTCOME_UNKNOWN)
                is NoteCreationEvent.EnterNoteBoundary ->
                    if (record.mediaSteps.all { it.storedName != null }) {
                        accept(
                            NoteCreationStatus.CREATING_NOTE,
                            lastEntered = record.mediaSteps.size,
                            attempts = record.attemptCount + 1
                        )
                    } else rejected
                is NoteCreationEvent.PreBoundaryRefused ->
                    accept(NoteCreationStatus.ABANDONED, reason = NoteCreationReason.PRE_BOUNDARY_REFUSED)
                is NoteCreationEvent.RestartAbandoned ->
                    accept(NoteCreationStatus.ABANDONED, reason = NoteCreationReason.ABANDONED_ON_RESTART)
                else -> rejected
            }

            NoteCreationStatus.CREATING_NOTE -> when (event) {
                is NoteCreationEvent.BackendConfirmedCreated ->
                    accept(
                        NoteCreationStatus.CREATED,
                        reason = NoteCreationReason.NONE,
                        noteId = event.noteId
                    )
                is NoteCreationEvent.BackendConfirmedNotCreated ->
                    accept(NoteCreationStatus.RETRY_ALLOWED, reason = NoteCreationReason.NOT_CREATED_BY_BACKEND)
                is NoteCreationEvent.BackendOutcomeUnknown ->
                    accept(NoteCreationStatus.AMBIGUOUS, reason = NoteCreationReason.OUTCOME_UNKNOWN)
                is NoteCreationEvent.FinalizationFailedAfterConfirm ->
                    // The backend confirmed, but the durable CREATED write failed: fail closed and
                    // treat the creation as potentially applied. Never re-create (INV-18-09).
                    accept(NoteCreationStatus.AMBIGUOUS, reason = NoteCreationReason.OUTCOME_UNKNOWN)
                is NoteCreationEvent.RecoveryNormalizedUnknown ->
                    accept(NoteCreationStatus.AMBIGUOUS, reason = NoteCreationReason.RECOVERED_AFTER_RESTART)
                else -> rejected
            }

            NoteCreationStatus.RETRY_ALLOWED -> when (event) {
                is NoteCreationEvent.RetryRequested ->
                    // Media re-storage is content-deduplicated by the backend, so the plan restarts
                    // from zero without risk of duplicated bytes.
                    accept(
                        NoteCreationStatus.PREPARED,
                        lastEntered = -1,
                        reason = NoteCreationReason.NONE,
                        mediaSteps = record.mediaSteps.map { it.copy(storedName = null) }
                    )
                is NoteCreationEvent.RestartAbandoned ->
                    accept(NoteCreationStatus.ABANDONED, reason = NoteCreationReason.ABANDONED_ON_RESTART)
                else -> rejected
            }

            NoteCreationStatus.AMBIGUOUS -> when (event) {
                is NoteCreationEvent.UserAttestedCreated ->
                    accept(NoteCreationStatus.CREATED, reason = NoteCreationReason.USER_ATTESTED_CREATED)
                is NoteCreationEvent.UserAttestedAbsent ->
                    accept(NoteCreationStatus.ABANDONED, reason = NoteCreationReason.USER_ATTESTED_ABSENT)
                else -> rejected
            }

            NoteCreationStatus.CREATED -> when (event) {
                // Creation stays confirmed; only the reason records the separate read failure.
                is NoteCreationEvent.NoteHydrationFailed ->
                    accept(NoteCreationStatus.CREATED, reason = NoteCreationReason.HYDRATION_FAILED)
                else -> rejected
            }

            NoteCreationStatus.ABANDONED -> rejected
        }
    }
}
