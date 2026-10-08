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
 * | SUBMITTING  | RecoveryNormalizedUnknown         | AMBIGUOUS   | restart or cancelled attempt       |
 * | AMBIGUOUS   | ReconciliationConfirmedApplied    | APPLIED     | authoritative evidence             |
 * | AMBIGUOUS   | ReconciliationConfirmedNotApplied | RETRY_ALLOWED | lastEntered == 0, evidence      |
 *
 * Extensions beyond the bare spec table (each is needed for a safe end state, and each is listed in
 * the GATE 17 report): `EnterNextOperation` (multi-step plans), `RecoveryNormalizedUnknown`
 * (a SUBMITTING record found at startup), `PreBoundaryConflict` (stale base found before a write, or
 * a RETRY_ALLOWED edit abandoned at restart), and `RetryRequested` (the retry entry point).
 *
 * APPLIED and CONFLICT have no outgoing edges. AMBIGUOUS has no retry edge: it can only leave via
 * authoritative reconciliation.
 */
sealed interface NoteMutationEvent {
    data object EnterMutationBoundary : NoteMutationEvent
    data class EnterNextOperation(val index: Int) : NoteMutationEvent
    data object BackendConfirmedApplied : NoteMutationEvent
    data object BackendConfirmedNotApplied : NoteMutationEvent
    data object BackendConflict : NoteMutationEvent
    data object BackendOutcomeUnknown : NoteMutationEvent
    data class PreBoundaryConflict(val reason: NoteMutationReason) : NoteMutationEvent
    data object RetryRequested : NoteMutationEvent
    data object RecoveryNormalizedUnknown : NoteMutationEvent
    data object ReconciliationConfirmedApplied : NoteMutationEvent
    data object ReconciliationConfirmedNotApplied : NoteMutationEvent
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
                else -> rejected
            }

            NoteMutationStatus.APPLIED, NoteMutationStatus.CONFLICT -> rejected
        }
    }
}
