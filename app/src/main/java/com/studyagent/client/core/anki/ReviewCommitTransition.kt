package com.studyagent.client.core.anki

/**
 * The only legal ways a [ReviewCommitRecord] may change.
 *
 * Callers do not assign `record.state = …`. The ledger applies one of these commands, rejects
 * anything else *before* a storage write, and bumps [ReviewCommitRecord.version] only when the
 * write succeeds. [ReviewCommitTransitions.allowed] is that gate.
 *
 * Concurrency: in-process writers are serialized by the ledger mutex, and the store replaces the
 * whole snapshot atomically. [ReviewCommitLedger.transition] additionally refuses a write unless
 * `expectedVersion` still matches. There is no distributed lock — this process is the only writer
 * of the ledger file. That is the concurrency model; do not pretend otherwise.
 */
sealed interface ReviewCommitTransition {
    data object MarkMutationCallEntered : ReviewCommitTransition
    data class MarkResponseReceived(val result: CommitRatingResult) : ReviewCommitTransition
    data class MarkCommitted(val result: CommitRatingResult, val resolution: String) : ReviewCommitTransition
    data class MarkSafeToRetry(val category: String, val safeToRetry: Boolean = true) : ReviewCommitTransition
    data class MarkNotApplied(val category: String, val safeToRetry: Boolean) : ReviewCommitTransition
    data class MarkAmbiguous(val category: String, val resolution: String) : ReviewCommitTransition
    data class MarkReconciled(val result: ReconcileCommitResult, val action: CommitRecoveryAction) : ReviewCommitTransition
    data object Acknowledge : ReviewCommitTransition
    /** Metadata only. Never changes [ReviewCommitState] and never means "not committed". */
    data class NoteAbandoned(val abandonedAtEpochMs: Long) : ReviewCommitTransition
}

object ReviewCommitTransitions {
    fun responseEvidence(record: ReviewCommitRecord, result: CommitRatingResult): CommitResponseEvidence? = when (result) {
        is CommitRatingResult.Committed -> {
            val receipt = result.receipt
            if (receipt != null && (receipt.backendId != record.backendId || receipt.committedRating != record.rating)) null
            else CommitResponseEvidence(CommitResponseKind.CONFIRMED_COMMITTED, backendReceiptId = receipt?.backendReceiptId)
        }
        is CommitRatingResult.RetryableFailure -> CommitResponseEvidence(
            CommitResponseKind.CONFIRMED_NOT_APPLIED, ReviewCommitFailure(result.error.commitCategory(), true)
        )
        is CommitRatingResult.Rejected -> CommitResponseEvidence(
            CommitResponseKind.CONFIRMED_NOT_APPLIED, ReviewCommitFailure(result.error.commitCategory(), false)
        )
        is CommitRatingResult.Ambiguous -> CommitResponseEvidence(
            CommitResponseKind.OUTCOME_UNKNOWN,
            ReviewCommitFailure(result.error?.commitCategory() ?: "unknown_outcome", false)
        )
    }

    fun terminalFromResponse(record: ReviewCommitRecord, resolution: String, now: Long): ReviewCommitRecord {
        val response = checkNotNull(record.response)
        val state = when (response.kind) {
            CommitResponseKind.CONFIRMED_COMMITTED -> ReviewCommitState.COMMITTED
            CommitResponseKind.CONFIRMED_NOT_APPLIED -> ReviewCommitState.FAILED
            CommitResponseKind.OUTCOME_UNKNOWN -> ReviewCommitState.AMBIGUOUS
        }
        return record.copy(
            state = state,
            phase = CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
            failure = response.failure,
            resolution = resolutionFor(state, resolution, response),
            updatedAtEpochMs = now,
            resolvedAtEpochMs = now
        )
    }

    fun resolutionFor(state: ReviewCommitState, source: String, response: CommitResponseEvidence): String =
        if (source != ReviewCommitResolution.BACKEND_CONFIRMED) source else when (state) {
            ReviewCommitState.COMMITTED -> ReviewCommitResolution.BACKEND_CONFIRMED
            ReviewCommitState.FAILED -> if (response.failure?.safeToRetry == true)
                ReviewCommitResolution.BACKEND_NOT_APPLIED else ReviewCommitResolution.BACKEND_REJECTED
            ReviewCommitState.AMBIGUOUS -> ReviewCommitResolution.BACKEND_AMBIGUOUS
            else -> source
        }

    /**
     * Pure command application. `null` means the command is illegal for [record] and must not be
     * written. Does not assign [ReviewCommitRecord.version]; the ledger stamps that at the write.
     */
    fun apply(record: ReviewCommitRecord, transition: ReviewCommitTransition, now: Long): ReviewCommitRecord? {
        if (record.state == ReviewCommitState.COMMITTED && transition !is ReviewCommitTransition.NoteAbandoned &&
            transition !is ReviewCommitTransition.Acknowledge) {
            return null
        }
        return when (transition) {
            ReviewCommitTransition.MarkMutationCallEntered ->
                if (record.state == ReviewCommitState.SUBMITTING && record.phase == CommitAttemptPhase.PREPARED)
                    record.copy(phase = CommitAttemptPhase.MUTATION_CALL_ENTERED, updatedAtEpochMs = now) else null
            is ReviewCommitTransition.MarkResponseReceived -> {
                if (record.state != ReviewCommitState.SUBMITTING ||
                    record.phase != CommitAttemptPhase.MUTATION_CALL_ENTERED) return null
                val proof = responseEvidence(record, transition.result) ?: return null
                record.copy(phase = CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED, response = proof, updatedAtEpochMs = now)
            }
            is ReviewCommitTransition.MarkCommitted -> {
                if (record.state != ReviewCommitState.SUBMITTING ||
                    record.phase != CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED ||
                    record.response != responseEvidence(record, transition.result)) return null
                terminalFromResponse(record, transition.resolution, now)
            }
            is ReviewCommitTransition.MarkSafeToRetry ->
                if (record.state == ReviewCommitState.SUBMITTING && record.phase == CommitAttemptPhase.PREPARED)
                    failed(record, transition.category, transition.safeToRetry, ReviewCommitResolution.RECOVERED_PREPARED, now)
                else null
            is ReviewCommitTransition.MarkNotApplied ->
                if (record.state == ReviewCommitState.NOT_STARTED || record.safeToRetry)
                    record.copy(
                        state = ReviewCommitState.FAILED,
                        phase = if (record.attemptCount == 0) null else CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
                        response = null,
                        failure = ReviewCommitFailure(transition.category.take(96), transition.safeToRetry),
                        resolution = ReviewCommitResolution.REFUSED_BEFORE_DISPATCH,
                        updatedAtEpochMs = now,
                        resolvedAtEpochMs = now
                    ) else null
            is ReviewCommitTransition.MarkAmbiguous -> when {
                record.state == ReviewCommitState.SUBMITTING && record.phase == CommitAttemptPhase.PREPARED ->
                    ambiguous(record, transition.category, transition.resolution, now)
                record.state == ReviewCommitState.SUBMITTING &&
                    record.phase == CommitAttemptPhase.MUTATION_CALL_ENTERED ->
                    ambiguous(record, transition.category, transition.resolution, now)
                else -> null
            }
            is ReviewCommitTransition.MarkReconciled -> reconcile(record, transition, now)
            ReviewCommitTransition.Acknowledge ->
                if (record.state == ReviewCommitState.AMBIGUOUS || record.state == ReviewCommitState.FAILED)
                    record.copy(acknowledged = true, updatedAtEpochMs = now) else null
            is ReviewCommitTransition.NoteAbandoned ->
                if (transition.abandonedAtEpochMs >= 0)
                    record.copy(abandonedAtEpochMs = transition.abandonedAtEpochMs, updatedAtEpochMs = now) else null
        }
    }

    /**
     * Rejects illegal state changes before any store write. [after] must already carry
     * `version == before.version + 1`. Metadata-only updates (acknowledge, abandon) may keep state.
     */
    fun allowed(before: ReviewCommitRecord, after: ReviewCommitRecord): Boolean {
        if (before.commitId != after.commitId || before.card != after.card || before.rating != after.rating ||
            before.turnId != after.turnId || before.sessionId != after.sessionId ||
            before.createdAtEpochMs != after.createdAtEpochMs) return false
        if (before.deckRef != null && after.deckRef != before.deckRef) return false
        if (after.version != before.version + 1 || after.attemptCount < before.attemptCount) return false
        if (before.state == ReviewCommitState.COMMITTED && after.state != ReviewCommitState.COMMITTED) return false
        if (before.state == ReviewCommitState.AMBIGUOUS && after.state == ReviewCommitState.SUBMITTING) return false
        if (before.state == ReviewCommitState.FAILED && !before.safeToRetry &&
            after.state == ReviewCommitState.SUBMITTING) return false
        if (after.attemptCount > before.attemptCount &&
            (after.attemptCount != before.attemptCount + 1 || after.state != ReviewCommitState.SUBMITTING ||
                after.phase != CommitAttemptPhase.PREPARED ||
                (before.state != ReviewCommitState.NOT_STARTED &&
                    !(before.state == ReviewCommitState.FAILED && before.safeToRetry)))) return false
        if (before.state == after.state && before.phase == after.phase && before.attemptCount == after.attemptCount &&
            before.response == after.response && before.failure == after.failure) return true
        return when (before.state) {
            ReviewCommitState.NOT_STARTED ->
                (after.state == ReviewCommitState.SUBMITTING && after.phase == CommitAttemptPhase.PREPARED &&
                    after.attemptCount == before.attemptCount + 1) ||
                    (after.state == ReviewCommitState.FAILED && after.attemptCount == before.attemptCount)
            ReviewCommitState.SUBMITTING -> when (before.phase) {
                CommitAttemptPhase.PREPARED ->
                    (after.state == ReviewCommitState.SUBMITTING &&
                        after.phase == CommitAttemptPhase.MUTATION_CALL_ENTERED &&
                        after.attemptCount == before.attemptCount) ||
                        (after.state == ReviewCommitState.FAILED &&
                            after.phase == CommitAttemptPhase.LOCAL_RESULT_PERSISTED) ||
                        (after.state == ReviewCommitState.AMBIGUOUS &&
                            after.phase == CommitAttemptPhase.LOCAL_RESULT_PERSISTED)
                CommitAttemptPhase.MUTATION_CALL_ENTERED ->
                    (after.state == ReviewCommitState.SUBMITTING &&
                        after.phase == CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED &&
                        after.attemptCount == before.attemptCount) ||
                        (after.state == ReviewCommitState.AMBIGUOUS &&
                            after.phase == CommitAttemptPhase.LOCAL_RESULT_PERSISTED)
                CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED ->
                    after.phase == CommitAttemptPhase.LOCAL_RESULT_PERSISTED &&
                        after.attemptCount == before.attemptCount && after.response == before.response &&
                        after.state in setOf(
                            ReviewCommitState.COMMITTED, ReviewCommitState.FAILED, ReviewCommitState.AMBIGUOUS
                        )
                CommitAttemptPhase.LOCAL_RESULT_PERSISTED, null -> false
            }
            ReviewCommitState.FAILED ->
                (after.state == ReviewCommitState.FAILED && after.attemptCount == before.attemptCount) ||
                    (before.safeToRetry && after.state == ReviewCommitState.SUBMITTING &&
                        after.phase == CommitAttemptPhase.PREPARED &&
                        after.attemptCount == before.attemptCount + 1)
            ReviewCommitState.AMBIGUOUS ->
                after.attemptCount == before.attemptCount && after.state in setOf(
                    ReviewCommitState.AMBIGUOUS, ReviewCommitState.COMMITTED, ReviewCommitState.FAILED
                )
            ReviewCommitState.COMMITTED ->
                after.state == ReviewCommitState.COMMITTED && after.phase == before.phase &&
                    after.attemptCount == before.attemptCount && after.failure == null &&
                    after.response == before.response
        }
    }

    private fun failed(
        record: ReviewCommitRecord, category: String, safeToRetry: Boolean, resolution: String, now: Long
    ) = record.copy(
        state = ReviewCommitState.FAILED,
        phase = CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
        failure = ReviewCommitFailure(category.take(96), safeToRetry),
        resolution = resolution,
        updatedAtEpochMs = now,
        resolvedAtEpochMs = now
    )

    private fun ambiguous(record: ReviewCommitRecord, category: String, resolution: String, now: Long) = record.copy(
        state = ReviewCommitState.AMBIGUOUS,
        phase = CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
        failure = ReviewCommitFailure(category.take(96), false),
        resolution = resolution,
        updatedAtEpochMs = now,
        resolvedAtEpochMs = now
    )

    private fun reconcile(
        record: ReviewCommitRecord, transition: ReviewCommitTransition.MarkReconciled, now: Long
    ): ReviewCommitRecord? {
        if (record.state != ReviewCommitState.AMBIGUOUS) return null
        val next = when (transition.action) {
            CommitRecoveryAction.ResumeCommitted -> record.copy(
                state = ReviewCommitState.COMMITTED, response = null, failure = null,
                resolution = ReviewCommitResolution.RECONCILED_APPLIED
            )
            CommitRecoveryAction.RetryAllowed -> record.copy(
                state = ReviewCommitState.FAILED, response = null,
                failure = ReviewCommitFailure("reconciled_not_applied", true),
                resolution = ReviewCommitResolution.RECONCILED_NOT_APPLIED
            )
            CommitRecoveryAction.BlockedUnresolved -> if (transition.result is ReconcileCommitResult.NotApplied)
                record.copy(
                    state = ReviewCommitState.FAILED, response = null,
                    failure = ReviewCommitFailure("reconciled_not_applied", false),
                    resolution = ReviewCommitResolution.RECONCILED_NOT_APPLIED
                )
            else record.copy(
                failure = ReviewCommitFailure("reconciliation_inconclusive", false),
                resolution = ReviewCommitResolution.RECONCILIATION_INCONCLUSIVE
            )
            CommitRecoveryAction.ReconciliationRequired, CommitRecoveryAction.IntegrityError -> return null
        }
        return next.copy(
            phase = CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
            updatedAtEpochMs = now,
            resolvedAtEpochMs = now
        )
    }
}
