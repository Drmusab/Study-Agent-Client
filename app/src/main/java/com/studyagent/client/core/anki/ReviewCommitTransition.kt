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
    /** NOT_STARTED → SUBMITTING/PREPARED, incrementing one real attempt. */
    data class Prepare(val evidence: ReviewCommitEvidence?) : ReviewCommitTransition
    /** FAILED_SAFE_TO_RETRY → SUBMITTING/PREPARED for the same immutable payload. */
    data class RetryPrepared(val evidence: ReviewCommitEvidence? = null) : ReviewCommitTransition
    data object MarkMutationCallEntered : ReviewCommitTransition
    data class MarkResponseReceived(val result: CommitRatingResult) : ReviewCommitTransition
    data class MarkCommitted(val result: CommitRatingResult, val resolution: String) : ReviewCommitTransition
    /** Finalize an already durable response after process recreation; never redispatches. */
    data object FinalizeRecordedResponse : ReviewCommitTransition
    data class MarkSafeToRetry(
        val category: String,
        val safeToRetry: Boolean = true,
        val resolution: String = ReviewCommitResolution.REFUSED_BEFORE_DISPATCH
    ) : ReviewCommitTransition
    data class MarkNotApplied(val category: String, val safeToRetry: Boolean) : ReviewCommitTransition
    data class MarkAmbiguous(val category: String, val resolution: String) : ReviewCommitTransition
    data class MarkReconciled(val result: ReconcileCommitResult, val action: CommitRecoveryAction) : ReviewCommitTransition
    data object Acknowledge : ReviewCommitTransition
    /** Metadata only. Never changes [ReviewCommitState] and never means "not committed". */
    data class NoteAbandoned(val abandonedAtEpochMs: Long) : ReviewCommitTransition
}

/** Typed failure reasons for a rejected pure transition. No failed command silently becomes a no-op. */
enum class ReviewCommitTransitionRejection {
    COMMITTED_TERMINAL,
    INVALID_BACKEND_RESULT,
    INVALID_RECONCILIATION_ACTION,
    ILLEGAL_STATE_TRANSITION,
    IDENTITY_MUTATION,
    INVALID_RESULT_RECORD
}

sealed interface ReviewCommitTransitionResult {
    data class Applied(val record: ReviewCommitRecord) : ReviewCommitTransitionResult
    data class Rejected(val reason: ReviewCommitTransitionRejection) : ReviewCommitTransitionResult
}

object ReviewCommitTransitions {
    /** Validates an initial durable intent; creation is not an unrestricted state transition. */
    fun validateInitial(record: ReviewCommitRecord): ReviewCommitTransitionResult {
        val valid = record.state == ReviewCommitState.NOT_STARTED && record.attemptCount == 0 &&
            record.phase == null && record.response == null && record.failure == null &&
            record.committedRating == null && record.submittedAtEpochMs == null &&
            record.resolvedAtEpochMs == null && record.acknowledged.not() &&
            record.version == 0L && record.abandonedAtEpochMs == null
        return if (valid) ReviewCommitTransitionResult.Applied(record)
        else ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.INVALID_RESULT_RECORD)
    }

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

    private fun terminalFromResponse(record: ReviewCommitRecord, resolution: String, now: Long): ReviewCommitRecord {
        val response = checkNotNull(record.response)
        val state = when (response.kind) {
            CommitResponseKind.CONFIRMED_COMMITTED -> ReviewCommitState.COMMITTED
            CommitResponseKind.CONFIRMED_NOT_APPLIED -> if (response.failure?.safeToRetry == true)
                ReviewCommitState.FAILED_SAFE_TO_RETRY else ReviewCommitState.FAILED_NOT_RETRYABLE
            CommitResponseKind.OUTCOME_UNKNOWN -> ReviewCommitState.AMBIGUOUS
        }
        return record.copy(
            state = state,
            phase = CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
            failure = response.failure,
            committedRating = if (state == ReviewCommitState.COMMITTED) record.selectedRating else null,
            resolution = resolutionFor(state, resolution, response),
            updatedAtEpochMs = now,
            resolvedAtEpochMs = now
        )
    }

    fun resolutionFor(state: ReviewCommitState, source: String, response: CommitResponseEvidence): String =
        if (source != ReviewCommitResolution.BACKEND_CONFIRMED) source else when (state) {
            ReviewCommitState.COMMITTED -> ReviewCommitResolution.BACKEND_CONFIRMED
            ReviewCommitState.FAILED_SAFE_TO_RETRY -> ReviewCommitResolution.BACKEND_NOT_APPLIED
            ReviewCommitState.FAILED_NOT_RETRYABLE -> ReviewCommitResolution.BACKEND_REJECTED
            ReviewCommitState.AMBIGUOUS -> ReviewCommitResolution.BACKEND_AMBIGUOUS
            else -> source
        }

    /**
     * Pure command application. `null` means the command is illegal for [record] and must not be
     * written. Does not assign [ReviewCommitRecord.version]; the ledger stamps that at the write.
     */
    fun transition(
        record: ReviewCommitRecord,
        transition: ReviewCommitTransition,
        now: Long
    ): ReviewCommitTransitionResult {
        if (record.state == ReviewCommitState.COMMITTED && transition !is ReviewCommitTransition.NoteAbandoned) {
            return ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.COMMITTED_TERMINAL)
        }
        if (transition is ReviewCommitTransition.MarkResponseReceived &&
            responseEvidence(record, transition.result) == null) {
            return ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.INVALID_BACKEND_RESULT)
        }
        if (transition is ReviewCommitTransition.MarkCommitted &&
            record.response != responseEvidence(record, transition.result)) {
            return ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.INVALID_BACKEND_RESULT)
        }
        if ((transition is ReviewCommitTransition.MarkCommitted && transition.resolution.isBlank()) ||
            (transition is ReviewCommitTransition.MarkAmbiguous && transition.resolution.isBlank())) {
            return ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.INVALID_RESULT_RECORD)
        }
        if (transition is ReviewCommitTransition.MarkReconciled &&
            ReviewCommitRecoveryPolicy().classify(record, transition.result) != transition.action) {
            return ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.INVALID_RECONCILIATION_ACTION)
        }
        val next = try {
            applyUnchecked(record, transition, now)
        } catch (_: IllegalArgumentException) {
            return ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.INVALID_RESULT_RECORD)
        } ?: return ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.ILLEGAL_STATE_TRANSITION)
        if (!sameIdentity(record, next) ||
            (record.evidence != null && next.evidence != record.evidence)) {
            return ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.IDENTITY_MUTATION)
        }
        return ReviewCommitTransitionResult.Applied(next)
    }

    private fun sameIdentity(before: ReviewCommitRecord, after: ReviewCommitRecord): Boolean =
        before.commitId == after.commitId && before.backendId == after.backendId &&
            before.sessionId == after.sessionId && before.turnId == after.turnId &&
            before.collectionRef == after.collectionRef && before.deckRef == after.deckRef &&
            before.cardRef == after.cardRef && before.selectedRating == after.selectedRating &&
            before.createdAtEpochMs == after.createdAtEpochMs && before.ratedAtEpochMs == after.ratedAtEpochMs &&
            before.answerDurationMs == after.answerDurationMs && before.frozenGuarantee == after.frozenGuarantee &&
            before.frozenIdempotentReplay == after.frozenIdempotentReplay &&
            before.frozenAuthoritativeReconciliation == after.frozenAuthoritativeReconciliation

    private fun applyUnchecked(
        record: ReviewCommitRecord,
        transition: ReviewCommitTransition,
        now: Long
    ): ReviewCommitRecord? {
        return when (transition) {
            is ReviewCommitTransition.Prepare -> prepare(record, transition.evidence, now)
            is ReviewCommitTransition.RetryPrepared -> retryPrepared(record, transition.evidence, now)
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
            ReviewCommitTransition.FinalizeRecordedResponse ->
                if (record.state == ReviewCommitState.SUBMITTING &&
                    record.phase == CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED && record.response != null)
                    terminalFromResponse(record, ReviewCommitResolution.RECOVERED_RESPONSE, now)
                else null
            is ReviewCommitTransition.MarkSafeToRetry ->
                if (record.state == ReviewCommitState.SUBMITTING && record.phase == CommitAttemptPhase.PREPARED)
                    failed(record, transition.category, transition.safeToRetry, transition.resolution, now)
                else null
            is ReviewCommitTransition.MarkNotApplied ->
                if (record.state == ReviewCommitState.NOT_STARTED || record.safeToRetry)
                    record.copy(
                        state = failureState(transition.safeToRetry),
                        phase = if (record.attemptCount == 0) null else CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
                        response = null,
                        committedRating = null,
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
                if (record.state == ReviewCommitState.AMBIGUOUS || record.state in FAILED_REVIEW_COMMIT_STATES)
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
        if (!sameIdentity(before, after) ||
            (before.evidence != null && after.evidence != before.evidence)) return false
        if (after.version != before.version + 1 || after.attemptCount < before.attemptCount) return false
        if (before.state == ReviewCommitState.COMMITTED && after.state != ReviewCommitState.COMMITTED) return false
        if (before.state == ReviewCommitState.AMBIGUOUS && after.state == ReviewCommitState.SUBMITTING) return false
        if (before.state != ReviewCommitState.FAILED_SAFE_TO_RETRY &&
            after.state == ReviewCommitState.SUBMITTING && before.state in FAILED_REVIEW_COMMIT_STATES) return false
        if (after.attemptCount > before.attemptCount &&
            (after.attemptCount != before.attemptCount + 1 || after.state != ReviewCommitState.SUBMITTING ||
                after.phase != CommitAttemptPhase.PREPARED ||
                (before.state != ReviewCommitState.NOT_STARTED &&
                    before.state != ReviewCommitState.FAILED_SAFE_TO_RETRY))) return false
        if (before.state == after.state && before.phase == after.phase && before.attemptCount == after.attemptCount &&
            before.response == after.response && before.failure == after.failure) return true
        return when (before.state) {
            ReviewCommitState.NOT_STARTED ->
                (after.state == ReviewCommitState.SUBMITTING && after.phase == CommitAttemptPhase.PREPARED &&
                    after.attemptCount == before.attemptCount + 1) ||
                    (after.state in FAILED_REVIEW_COMMIT_STATES && after.attemptCount == before.attemptCount)
            ReviewCommitState.SUBMITTING -> when (before.phase) {
                CommitAttemptPhase.PREPARED ->
                    (after.state == ReviewCommitState.SUBMITTING &&
                        after.phase == CommitAttemptPhase.MUTATION_CALL_ENTERED &&
                        after.attemptCount == before.attemptCount) ||
                        (after.state in FAILED_REVIEW_COMMIT_STATES &&
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
                            ReviewCommitState.COMMITTED, ReviewCommitState.FAILED_SAFE_TO_RETRY,
                            ReviewCommitState.FAILED_NOT_RETRYABLE, ReviewCommitState.AMBIGUOUS
                        )
                CommitAttemptPhase.LOCAL_RESULT_PERSISTED, null -> false
            }
            ReviewCommitState.FAILED_SAFE_TO_RETRY ->
                (after.state in setOf(ReviewCommitState.FAILED_SAFE_TO_RETRY,
                    ReviewCommitState.FAILED_NOT_RETRYABLE) && after.attemptCount == before.attemptCount) ||
                    (after.state == ReviewCommitState.SUBMITTING &&
                        after.phase == CommitAttemptPhase.PREPARED &&
                        after.attemptCount == before.attemptCount + 1)
            ReviewCommitState.FAILED_NOT_RETRYABLE ->
                after.state == ReviewCommitState.FAILED_NOT_RETRYABLE && after.attemptCount == before.attemptCount
            ReviewCommitState.AMBIGUOUS ->
                after.attemptCount == before.attemptCount && after.state in setOf(
                    ReviewCommitState.AMBIGUOUS, ReviewCommitState.COMMITTED,
                    ReviewCommitState.FAILED_SAFE_TO_RETRY, ReviewCommitState.FAILED_NOT_RETRYABLE
                )
            ReviewCommitState.COMMITTED ->
                after.state == ReviewCommitState.COMMITTED && after.phase == before.phase &&
                    after.attemptCount == before.attemptCount && after.failure == null &&
                    after.response == before.response
        }
    }

    private fun prepare(record: ReviewCommitRecord, evidence: ReviewCommitEvidence?, now: Long): ReviewCommitRecord? =
        if (record.state != ReviewCommitState.NOT_STARTED || record.attemptCount != 0) null
        else submitting(record, evidence, now)

    private fun retryPrepared(record: ReviewCommitRecord, evidence: ReviewCommitEvidence?, now: Long): ReviewCommitRecord? =
        if (record.state != ReviewCommitState.FAILED_SAFE_TO_RETRY) null
        else submitting(record, evidence, now)

    private fun submitting(record: ReviewCommitRecord, evidence: ReviewCommitEvidence?, now: Long) = record.copy(
        state = ReviewCommitState.SUBMITTING,
        attemptCount = record.attemptCount + 1,
        phase = CommitAttemptPhase.PREPARED,
        response = null,
        committedRating = null,
        evidence = record.evidence ?: evidence,
        submittedAtEpochMs = now,
        updatedAtEpochMs = now,
        resolvedAtEpochMs = null,
        failure = null,
        resolution = null
    )

    private fun failureState(safeToRetry: Boolean) = if (safeToRetry)
        ReviewCommitState.FAILED_SAFE_TO_RETRY else ReviewCommitState.FAILED_NOT_RETRYABLE

    private fun failed(
        record: ReviewCommitRecord, category: String, safeToRetry: Boolean, resolution: String, now: Long
    ) = record.copy(
        state = failureState(safeToRetry),
        phase = CommitAttemptPhase.LOCAL_RESULT_PERSISTED,
        committedRating = null,
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
                state = ReviewCommitState.COMMITTED, committedRating = record.selectedRating,
                response = null, failure = null,
                resolution = ReviewCommitResolution.RECONCILED_APPLIED
            )
            CommitRecoveryAction.RetryAllowed -> record.copy(
                state = ReviewCommitState.FAILED_SAFE_TO_RETRY, committedRating = null, response = null,
                failure = ReviewCommitFailure("reconciled_not_applied", true),
                resolution = ReviewCommitResolution.RECONCILED_NOT_APPLIED
            )
            CommitRecoveryAction.BlockedUnresolved -> if (transition.result is ReconcileCommitResult.NotApplied)
                record.copy(
                    state = ReviewCommitState.FAILED_NOT_RETRYABLE, committedRating = null, response = null,
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
