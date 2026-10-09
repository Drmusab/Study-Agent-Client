package com.studyagent.client.core.anki.edit

/**
 * GATE 17 §33 — the closed transition table for note edits.
 *
 * Every legal change is one [NoteMutationEvent] applied to one source status. Anything not in this
 * table is rejected and the record is left untouched. There is no generic "set status" path.
 *
 * Table (source -> target, event):
 *
 * | From        | Event                             | To          | Guard                              |
 * |-------------|-----------------------------------|-------------|------------------------------------|
 * | PREPARED    | EnterMutationBoundary             | SUBMITTING  | attemptCount+1, lastEntered = 0    |
 * | PREPARED    | PreBoundaryConflict(reason)       | CONFLICT    | no backend effect                  |
 * | RETRY_ALLOWED | RetryRequested                  | PREPARED    | same mutation id                   |
 * | RETRY_ALLOWED | PreBoundaryConflict(reason)     | CONFLICT    | re-read found drift, or abandoned  |
 * | SUBMITTING  | EnterNextOperation(i)             | SUBMITTING  | i == lastEntered + 1, i < size     |
 * | SUBMITTING  | BackendConfirmedApplied           | APPLIED     | last operation entered             |
 * | SUBMITTING  | BackendConfirmedNotApplied        | RETRY_ALLOWED | lastEntered == 0               |
 * | SUBMITTING  | BackendConflict                   | CONFLICT    | lastEntered == 0                   |
 * | SUBMITTING  | BackendOutcomeUnknown             | AMBIGUOUS   | any                                |
 * | SUBMITTING  | PostWriteVerificationFailed(r)     | AMBIGUOUS   | confirmed write, read disagrees    |
 * | SUBMITTING  | RecoveryNormalizedUnknown         | AMBIGUOUS   | restart or cancelled attempt       |
 * | AMBIGUOUS   | ReconciliationConfirmedApplied    | APPLIED     | authoritative evidence             |
 * | AMBIGUOUS   | ReconciliationConfirmedNotApplied | RETRY_ALLOWED | lastEntered == 0, evidence      |
 * | AMBIGUOUS   | UserAttestedApplied               | APPLIED     | a human checked the collection     |
 * | AMBIGUOUS   | UserAttestedNotApplied            | CONFLICT    | a human checked; closed, re-edit   |
 *
 * Extensions beyond the bare spec table (each is needed for a safe end state, and each is listed in
 * the GATE 17 report): `EnterNextOperation` (multi-step plans), `RecoveryNormalizedUnknown`
 * (a SUBMITTING record found at startup), `PreBoundaryConflict` (stale base found before a write, or
 * a RETRY_ALLOWED edit abandoned at restart), `RetryRequested` (the retry entry point),
 * `PostWriteVerificationFailed` (CONTRACT-06/15: the provider's row count is weak evidence, so
 * APPLIED requires an authoritative read that holds the intended state) and the two
 * `UserAttested*` edges (CONTRACT-26: an ambiguous record blocks the note until a human resolves it,
 * and a human resolution has to be representable or the note is blocked forever).
 *
 * APPLIED and CONFLICT have no outgoing edges. AMBIGUOUS has no automatic replay edge: it can only
 * leave via authoritative reconciliation or an explicit human attestation, and the attestation is
 * recorded in [NoteMutationReason] so a human-confirmed record is never mistaken for backend proof.
 */
sealed interface NoteMutationEvent {
    data object EnterMutationBoundary : NoteMutationEvent
    data class EnterNextOperation(val index: Int) : NoteMutationEvent
    data object BackendConfirmedApplied : NoteMutationEvent
    data object BackendConfirmedNotApplied : NoteMutationEvent
    data object BackendConflict : NoteMutationEvent
    data object BackendOutcomeUnknown : NoteMutationEvent
    data class PostWriteVerificationFailed(val reason: NoteMutationReason) : NoteMutationEvent
    data class PreBoundaryConflict(val reason: NoteMutationReason) : NoteMutationEvent
    data object RetryRequested : NoteMutationEvent
    data object RecoveryNormalizedUnknown : NoteMutationEvent
    data object ReconciliationConfirmedApplied : NoteMutationEvent
    data object ReconciliationConfirmedNotApplied : NoteMutationEvent
    data object UserAttestedApplied : NoteMutationEvent
    data object UserAttestedNotApplied : NoteMutationEvent
}

/**
 * What a human concluded after inspecting the collection for an AMBIGUOUS note mutation. This is the
 * only way an ambiguous record can be closed when the backend offers no reconciliation, and it is
 * always recorded as an attestation ([NoteMutationReason.USER_ATTESTED_APPLIED] /
 * [NoteMutationReason.USER_ATTESTED_NOT_APPLIED]) rather than as backend evidence.
 */
enum class NoteMutationAttestation {
    /** "I checked Anki: the edit is there." */
    APPLIED_IN_COLLECTION,

    /** "I checked Anki: the edit is not there." The mutation is closed; a new edit starts fresh. */
    ABSENT_FROM_COLLECTION
}

/** Result of applying one event. A rejected event never produces a changed record. */
sealed interface NoteMutationTransitionResult {
    data class Accepted(val record: NoteMutationRecord) : NoteMutationTransitionResult
    data class Rejected(val from: NoteMutationStatus, val event: NoteMutationEvent) : NoteMutationTransitionResult
}

object NoteMutationTransitions {

    /** Applies [event] to [record]. The only way a status changes in GATE 17. */
    fun apply(record: NoteMutationRecord, event: NoteMutationEvent, nowEpochMs: Long): NoteMutationTransitionResult {
        val last = record.plan.operations.lastIndex
        val from = record.status
        fun accept(
            to: NoteMutationStatus,
            lastEntered: Int = record.lastEnteredOperation,
            attempts: Int = record.attemptCount,
            reason: NoteMutationReason = record.reason
        ): NoteMutationTransitionResult = NoteMutationTransitionResult.Accepted(
            record.copy(
                status = to,
                lastEnteredOperation = lastEntered,
                attemptCount = attempts,
                reason = reason,
                updatedAtEpochMs = nowEpochMs
            )
        )
        val rejected = NoteMutationTransitionResult.Rejected(from, event)

        return when (from) {
            NoteMutationStatus.PREPARED -> when (event) {
                is NoteMutationEvent.EnterMutationBoundary ->
                    accept(NoteMutationStatus.SUBMITTING, lastEntered = 0, attempts = record.attemptCount + 1)
                is NoteMutationEvent.PreBoundaryConflict ->
                    accept(NoteMutationStatus.CONFLICT, reason = event.reason)
                else -> rejected
            }

            NoteMutationStatus.RETRY_ALLOWED -> when (event) {
                is NoteMutationEvent.RetryRequested ->
                    accept(NoteMutationStatus.PREPARED, reason = NoteMutationReason.NONE)
                is NoteMutationEvent.PreBoundaryConflict ->
                    accept(NoteMutationStatus.CONFLICT, reason = event.reason)
                else -> rejected
            }

            NoteMutationStatus.SUBMITTING -> when (event) {
                is NoteMutationEvent.EnterNextOperation ->
                    if (event.index == record.lastEnteredOperation + 1 && event.index <= last) {
                        accept(NoteMutationStatus.SUBMITTING, lastEntered = event.index)
                    } else {
                        rejected
                    }
                is NoteMutationEvent.BackendConfirmedApplied ->
                    if (record.lastEnteredOperation == last) {
                        accept(NoteMutationStatus.APPLIED, reason = NoteMutationReason.NONE)
                    } else {
                        rejected
                    }
                is NoteMutationEvent.BackendConfirmedNotApplied ->
                    if (record.lastEnteredOperation == 0) {
                        accept(NoteMutationStatus.RETRY_ALLOWED, reason = NoteMutationReason.NOT_APPLIED_BY_BACKEND)
                    } else {
                        rejected
                    }
                is NoteMutationEvent.BackendConflict ->
                    if (record.lastEnteredOperation == 0) {
                        accept(NoteMutationStatus.CONFLICT, reason = NoteMutationReason.CONFLICT_REPORTED_BY_BACKEND)
                    } else {
                        rejected
                    }
                is NoteMutationEvent.BackendOutcomeUnknown -> {
                    val reason = if (record.lastEnteredOperation > 0) {
                        NoteMutationReason.PARTIAL_OPERATION_UNKNOWN
                    } else {
                        NoteMutationReason.OUTCOME_UNKNOWN
                    }
                    accept(NoteMutationStatus.AMBIGUOUS, reason = reason)
                }
                is NoteMutationEvent.PostWriteVerificationFailed ->
                    accept(NoteMutationStatus.AMBIGUOUS, reason = event.reason)
                is NoteMutationEvent.RecoveryNormalizedUnknown ->
                    accept(NoteMutationStatus.AMBIGUOUS, reason = NoteMutationReason.RECOVERED_AFTER_RESTART)
                else -> rejected
            }

            NoteMutationStatus.AMBIGUOUS -> when (event) {
                is NoteMutationEvent.ReconciliationConfirmedApplied ->
                    accept(NoteMutationStatus.APPLIED, reason = NoteMutationReason.RECONCILED_APPLIED)
                is NoteMutationEvent.ReconciliationConfirmedNotApplied ->
                    if (record.lastEnteredOperation == 0) {
                        accept(NoteMutationStatus.RETRY_ALLOWED, reason = NoteMutationReason.RECONCILED_NOT_APPLIED)
                    } else {
                        rejected
                    }
                // A human closed the ambiguity. Recorded as an attestation, never as backend proof.
                is NoteMutationEvent.UserAttestedApplied ->
                    accept(NoteMutationStatus.APPLIED, reason = NoteMutationReason.USER_ATTESTED_APPLIED)
                is NoteMutationEvent.UserAttestedNotApplied ->
                    // Terminal, so the note is free again and the user re-enters the edit from a
                    // fresh authoritative read (a new mutation id, never a replay of this one).
                    accept(NoteMutationStatus.CONFLICT, reason = NoteMutationReason.USER_ATTESTED_NOT_APPLIED)
                else -> rejected
            }

            NoteMutationStatus.APPLIED, NoteMutationStatus.CONFLICT -> rejected
        }
    }
}
