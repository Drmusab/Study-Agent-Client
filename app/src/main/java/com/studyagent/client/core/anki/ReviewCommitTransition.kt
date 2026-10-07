package com.studyagent.client.core.anki

/**
 * The only legal ways a [ReviewCommitRecord] may change.
 *
 * Callers do not assign `record.status = …`. The ledger applies one of these commands, rejects
 * anything else *before* a storage write, and bumps [ReviewCommitRecord.version] only when the
 * write succeeds. [ReviewCommitTransitions.allowed] is that gate.
 *
 * Concurrency: in-process writers are serialized by the ledger mutex, and the store replaces the
 * whole snapshot atomically. [ReviewCommitLedger.transition] additionally refuses a write unless
 * `expectedVersion` still matches. There is no distributed lock — this process is the only writer
 * of the ledger file. That is the concurrency model; do not pretend otherwise.
 *
 * Transition names describe **events**, never target statuses, so a transition name can never be
 * confused with a [ReviewCommitStatus].
 */
sealed interface ReviewCommitTransition {

    /**
     * The durable attempt claim. It is the "durable preparation" of GATE 11B §22 and deliberately
     * does **not** change [ReviewCommitStatus]: the record stays [ReviewCommitStatus.PREPARED]
     * because the mutation boundary is still un-entered. It exists so that (a) exactly one
     * attempt per transaction can win a durable claim and (b) two durable writes precede the
     * backend call even for the first attempt.
     */
    data class BeginAttempt(val evidence: ReviewCommitEvidence? = null) : ReviewCommitTransition

    /**
     * [ReviewCommitStatus.PREPARED] → [ReviewCommitStatus.SUBMITTING]. Written durably immediately
     * before the real scheduler mutation (GATE 11B §28), never before the backend is merely
     * *invoked*, so a crash during backend preflight still leaves a provably un-entered record.
     */
    data object EnterMutationBoundary : ReviewCommitTransition

    /** Durably record the classified backend answer **before** any terminal status (§29). */
    data class BackendResponseReceived(val result: BackendCommitResult) : ReviewCommitTransition

    /** [ReviewCommitStatus.SUBMITTING] → [ReviewCommitStatus.COMMITTED]. */
    data class BackendCommitted(
        val result: BackendCommitResult,
        val resolution: String = ReviewCommitResolution.BACKEND_CONFIRMED
    ) : ReviewCommitTransition

    /** [ReviewCommitStatus.SUBMITTING] → [ReviewCommitStatus.RETRY_ALLOWED] (§21, INV-11B-10). */
    data class BackendConfirmedNoMutation(
        val reason: AnkiError? = null,
        val resolution: String = ReviewCommitResolution.BACKEND_NOT_APPLIED,
        /** Explicit failure token when the caller already classified it; else derived from [reason]. */
        val category: String? = null
    ) : ReviewCommitTransition

    /** [ReviewCommitStatus.SUBMITTING] → [ReviewCommitStatus.AMBIGUOUS] (§21, INV-11B-11). */
    data class BackendOutcomeUnknown(
        val reason: AnkiError? = null,
        val resolution: String = ReviewCommitResolution.BACKEND_AMBIGUOUS,
        /** Explicit failure token when the caller already classified it; else derived from [reason]. */
        val category: String? = null
    ) : ReviewCommitTransition

    /**
     * [ReviewCommitStatus.RETRY_ALLOWED] → [ReviewCommitStatus.PREPARED] for the same immutable
     * payload. Never goes straight to SUBMITTING: every attempt repeats the same safety sequence
     * (INV-11B-09).
     */
    data object BeginRetry : ReviewCommitTransition

    /**
     * An attempt that was abandoned *before* the mutation boundary releases its claim without
     * claiming an outcome. The status stays [ReviewCommitStatus.PREPARED] because the boundary was
     * never entered — nothing was dispatched, so nothing needs proof (GATE 11B §45).
     */
    data object ReleaseClaim : ReviewCommitTransition

    /** [ReviewCommitStatus.AMBIGUOUS] → [ReviewCommitStatus.COMMITTED] on reconciliation evidence. */
    data class ReconciliationConfirmedCommitted(val receipt: CommitReceipt? = null) : ReviewCommitTransition

    /** [ReviewCommitStatus.AMBIGUOUS] → [ReviewCommitStatus.RETRY_ALLOWED] on evidence of no mutation. */
    data object ReconciliationConfirmedNotCommitted : ReviewCommitTransition

    /** Reconciliation could not decide: the record stays AMBIGUOUS, only the reason is refreshed. */
    data class ReconciliationInconclusive(val category: String) : ReviewCommitTransition

    /** Finalize an already durable backend response after process recreation; never redispatches. */
    data object FinalizeRecordedResponse : ReviewCommitTransition

    data object Acknowledge : ReviewCommitTransition

    /** Metadata only. Never changes [ReviewCommitStatus] and never means "not committed". */
    data class NoteAbandoned(val abandonedAtEpochMs: Long) : ReviewCommitTransition
}

/** Typed failure reasons for a rejected pure transition. No failed command silently becomes a no-op. */
enum class ReviewCommitTransitionRejection {
    COMMITTED_TERMINAL,
    INVALID_BACKEND_RESULT,
    INVALID_RECONCILIATION_ACTION,
    ILLEGAL_STATUS_TRANSITION,
    IDENTITY_MUTATION,
    INVALID_RESULT_RECORD
}

sealed interface ReviewCommitTransitionResult {
    data class Applied(val record: ReviewCommitRecord) : ReviewCommitTransitionResult
    data class Rejected(val reason: ReviewCommitTransitionRejection) : ReviewCommitTransitionResult
}

object ReviewCommitTransitions {

    /** Validates an initial durable intent; creation is not an unrestricted status transition. */
    fun validateInitial(record: ReviewCommitRecord): ReviewCommitTransitionResult {
        val valid = record.status == ReviewCommitStatus.PREPARED && record.attemptCount == 0 &&
            record.phase == ReviewCommitPhase.INTENT_PERSISTED && record.response == null &&
            record.failure == null && record.committedRating == null && record.submittedAtEpochMs == null &&
            record.resolvedAtEpochMs == null && record.acknowledged.not() &&
            record.version == 0L && record.abandonedAtEpochMs == null
        return if (valid) ReviewCommitTransitionResult.Applied(record)
        else ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.INVALID_RESULT_RECORD)
    }

    /** Translates a backend fact into durable evidence, once, in one place. */
    fun responseEvidence(record: ReviewCommitRecord, result: BackendCommitResult): CommitResponseEvidence? = when (result) {
        is BackendCommitResult.ConfirmedCommitted -> {
            val receipt = result.receipt
            if (receipt != null && (receipt.backendId != record.backendId || receipt.committedRating != record.rating)) null
            else CommitResponseEvidence(CommitResponseKind.CONFIRMED_COMMITTED, backendReceiptId = receipt?.backendReceiptId)
        }
        is BackendCommitResult.ConfirmedNotCommitted -> CommitResponseEvidence(
            CommitResponseKind.CONFIRMED_NOT_APPLIED,
            ReviewCommitFailure(result.reason?.commitCategory() ?: NOT_APPLIED_CATEGORY)
        )
        is BackendCommitResult.OutcomeUnknown -> CommitResponseEvidence(
            CommitResponseKind.OUTCOME_UNKNOWN,
            ReviewCommitFailure(result.reason?.commitCategory() ?: UNKNOWN_OUTCOME_CATEGORY)
        )
    }

    const val NOT_APPLIED_CATEGORY = "not_applied"
    const val UNKNOWN_OUTCOME_CATEGORY = "unknown_outcome"
    const val RECONCILED_NOT_APPLIED_CATEGORY = "reconciled_not_applied"

    private fun terminalFromResponse(record: ReviewCommitRecord, resolution: String, now: Long): ReviewCommitRecord {
        val response = checkNotNull(record.response)
        val status = response.kind.toReviewCommitStatus()
        return record.copy(
            status = status,
            phase = ReviewCommitPhase.FINAL_STATUS_PERSISTED,
            failure = response.failure,
            committedRating = if (status == ReviewCommitStatus.COMMITTED) record.selectedRating else null,
            resolution = resolutionFor(status, resolution, response),
            updatedAtEpochMs = now,
            resolvedAtEpochMs = now
        )
    }

    private fun CommitResponseKind.toReviewCommitStatus(): ReviewCommitStatus = when (this) {
        CommitResponseKind.CONFIRMED_COMMITTED -> ReviewCommitStatus.COMMITTED
        CommitResponseKind.CONFIRMED_NOT_APPLIED -> ReviewCommitStatus.RETRY_ALLOWED
        CommitResponseKind.OUTCOME_UNKNOWN -> ReviewCommitStatus.AMBIGUOUS
    }

    fun resolutionFor(
        status: ReviewCommitStatus,
        source: String,
        response: CommitResponseEvidence
    ): String = if (source != ReviewCommitResolution.BACKEND_CONFIRMED) source else when (status) {
        ReviewCommitStatus.COMMITTED -> ReviewCommitResolution.BACKEND_CONFIRMED
        ReviewCommitStatus.RETRY_ALLOWED -> ReviewCommitResolution.BACKEND_NOT_APPLIED
        ReviewCommitStatus.AMBIGUOUS -> ReviewCommitResolution.BACKEND_AMBIGUOUS
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
        if (record.status == ReviewCommitStatus.COMMITTED &&
            transition !is ReviewCommitTransition.NoteAbandoned &&
            transition !is ReviewCommitTransition.Acknowledge
        ) {
            return ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.COMMITTED_TERMINAL)
        }
        if (transition is ReviewCommitTransition.BackendResponseReceived &&
            responseEvidence(record, transition.result) == null) {
            return ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.INVALID_BACKEND_RESULT)
        }
        // A response that is not durable yet is derived from the result; one that *is* durable must
        // match it exactly, so a lost answer can never be overwritten by a different one.
        if (transition is ReviewCommitTransition.BackendCommitted && record.response != null &&
            record.response != responseEvidence(record, transition.result)) {
            return ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.INVALID_BACKEND_RESULT)
        }
        if ((transition is ReviewCommitTransition.BackendCommitted && transition.resolution.isBlank()) ||
            (transition is ReviewCommitTransition.BackendOutcomeUnknown && transition.resolution.isBlank()) ||
            (transition is ReviewCommitTransition.BackendConfirmedNoMutation && transition.resolution.isBlank())) {
            return ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.INVALID_RESULT_RECORD)
        }
        val next = try {
            applyUnchecked(record, transition, now)
        } catch (_: IllegalArgumentException) {
            return ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.INVALID_RESULT_RECORD)
        } ?: return ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.ILLEGAL_STATUS_TRANSITION)
        if (!sameIdentity(record, next) || (record.evidence != null && next.evidence != record.evidence)) {
            return ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.IDENTITY_MUTATION)
        }
        if (!allowed(record, next)) {
            return ReviewCommitTransitionResult.Rejected(ReviewCommitTransitionRejection.ILLEGAL_STATUS_TRANSITION)
        }
        return ReviewCommitTransitionResult.Applied(next)
    }

    /**
     * The whole durable write gate: identity is immutable, the frozen evidence is immutable, and
     * the status machine admits the `before → after` pair. The ledger applies nothing else.
     */
    fun validWrite(before: ReviewCommitRecord, after: ReviewCommitRecord): Boolean =
        sameIdentity(before, after) && (before.evidence == null || after.evidence == before.evidence) &&
            allowed(before, after)

    private fun sameIdentity(before: ReviewCommitRecord, after: ReviewCommitRecord): Boolean =
        before.commitId == after.commitId && before.backendId == after.backendId &&
            before.sessionId == after.sessionId && before.turnId == after.turnId &&
            before.collectionRef == after.collectionRef && before.deckRef == after.deckRef &&
            before.cardRef == after.cardRef && before.selectedRating == after.selectedRating &&
            before.createdAtEpochMs == after.createdAtEpochMs && before.ratedAtEpochMs == after.ratedAtEpochMs &&
            before.answerDurationMs == after.answerDurationMs && before.frozenGuarantee == after.frozenGuarantee &&
            before.frozenIdempotentReplay == after.frozenIdempotentReplay &&
            before.frozenAuthoritativeReconciliation == after.frozenAuthoritativeReconciliation

    /**
     * The durable status machine of GATE 11B §21/§23/§24, expressed as one guard over
     * `before → after` pairs. Anything not listed here cannot be written.
     */
    fun allowed(before: ReviewCommitRecord, after: ReviewCommitRecord): Boolean = when (before.status) {
        ReviewCommitStatus.COMMITTED ->
            after.status == ReviewCommitStatus.COMMITTED && after.phase == before.phase &&
                after.attemptCount == before.attemptCount && after.failure == null &&
                after.response == before.response
        ReviewCommitStatus.AMBIGUOUS ->
            after.attemptCount == before.attemptCount && after.status in setOf(
                ReviewCommitStatus.AMBIGUOUS, ReviewCommitStatus.COMMITTED, ReviewCommitStatus.RETRY_ALLOWED
            )
        ReviewCommitStatus.RETRY_ALLOWED ->
            after.attemptCount == before.attemptCount &&
                after.status in setOf(ReviewCommitStatus.RETRY_ALLOWED, ReviewCommitStatus.PREPARED)
        ReviewCommitStatus.PREPARED ->
            after.attemptCount >= before.attemptCount && after.status in setOf(
                ReviewCommitStatus.PREPARED, ReviewCommitStatus.SUBMITTING,
                ReviewCommitStatus.RETRY_ALLOWED, ReviewCommitStatus.AMBIGUOUS
            )
        ReviewCommitStatus.SUBMITTING ->
            after.attemptCount == before.attemptCount && after.status in setOf(
                ReviewCommitStatus.SUBMITTING, ReviewCommitStatus.COMMITTED,
                ReviewCommitStatus.RETRY_ALLOWED, ReviewCommitStatus.AMBIGUOUS
            )
    }

    private fun applyUnchecked(
        record: ReviewCommitRecord,
        transition: ReviewCommitTransition,
        now: Long
    ): ReviewCommitRecord? {
        return when (transition) {
            is ReviewCommitTransition.BeginAttempt -> beginAttempt(record, transition.evidence, now)
            is ReviewCommitTransition.EnterMutationBoundary -> enterMutationBoundary(record, now)
            is ReviewCommitTransition.BackendResponseReceived -> responseReceived(record, transition.result)
            is ReviewCommitTransition.BackendCommitted -> committed(record, transition.result, transition.resolution, now)
            is ReviewCommitTransition.BackendConfirmedNoMutation -> notCommitted(record, transition, now)
            is ReviewCommitTransition.BackendOutcomeUnknown -> unknown(record, transition, now)
            is ReviewCommitTransition.BeginRetry -> beginRetry(record)
            is ReviewCommitTransition.ReleaseClaim -> releaseClaim(record, now)
            is ReviewCommitTransition.ReconciliationConfirmedCommitted ->
                reconciledCommitted(record, transition.receipt, now)
            is ReviewCommitTransition.ReconciliationConfirmedNotCommitted -> reconciledNotCommitted(record, now)
            is ReviewCommitTransition.ReconciliationInconclusive -> reconciliationInconclusive(record, transition.category, now)
            is ReviewCommitTransition.FinalizeRecordedResponse -> finalizeRecordedResponse(record, now)
            is ReviewCommitTransition.Acknowledge -> record.copy(acknowledged = true, updatedAtEpochMs = now)
            is ReviewCommitTransition.NoteAbandoned ->
                record.copy(abandonedAtEpochMs = transition.abandonedAtEpochMs, updatedAtEpochMs = now)
        }
    }

    // ------------------------------------------------------------------ attempt lifecycle

    /** The durable claim. Status stays PREPARED: the boundary is still un-entered. */
    private fun beginAttempt(record: ReviewCommitRecord, evidence: ReviewCommitEvidence?, now: Long): ReviewCommitRecord? =
        if (record.status != ReviewCommitStatus.PREPARED || record.claimedAtEpochMs != null) null
        else record.copy(
            attemptCount = record.attemptCount + 1,
            phase = ReviewCommitPhase.INTENT_PERSISTED,
            claimedAtEpochMs = now,
            response = null,
            committedRating = null,
            evidence = record.evidence ?: evidence,
            submittedAtEpochMs = null,
            updatedAtEpochMs = now,
            resolvedAtEpochMs = null,
            failure = null,
            resolution = null
        )

    private fun enterMutationBoundary(record: ReviewCommitRecord, now: Long): ReviewCommitRecord? =
        if (record.status != ReviewCommitStatus.PREPARED || record.attemptCount < 1) null
        else record.copy(
            status = ReviewCommitStatus.SUBMITTING,
            phase = ReviewCommitPhase.MUTATION_BOUNDARY_ENTERED,
            claimedAtEpochMs = null,
            submittedAtEpochMs = now,
            updatedAtEpochMs = now
        )

    private fun responseReceived(record: ReviewCommitRecord, result: BackendCommitResult): ReviewCommitRecord? {
        if (record.status != ReviewCommitStatus.SUBMITTING ||
            record.phase != ReviewCommitPhase.MUTATION_BOUNDARY_ENTERED) return null
        val evidence = responseEvidence(record, result) ?: return null
        return record.copy(
            phase = ReviewCommitPhase.BACKEND_RESPONSE_RECEIVED,
            response = evidence,
            updatedAtEpochMs = record.updatedAtEpochMs
        )
    }

    private fun committed(
        record: ReviewCommitRecord,
        result: BackendCommitResult,
        resolution: String,
        now: Long
    ): ReviewCommitRecord? {
        if (record.status != ReviewCommitStatus.SUBMITTING) return null
        val withResponse = if (record.response != null) record
        else responseReceived(record, result) ?: return null
        return terminalFromResponse(withResponse, resolution, now)
    }

    private fun notCommitted(
        record: ReviewCommitRecord,
        transition: ReviewCommitTransition.BackendConfirmedNoMutation,
        now: Long
    ): ReviewCommitRecord? {
        if (record.status !in setOf(ReviewCommitStatus.SUBMITTING, ReviewCommitStatus.PREPARED)) return null
        return record.copy(
            status = ReviewCommitStatus.RETRY_ALLOWED,
            phase = ReviewCommitPhase.FINAL_STATUS_PERSISTED,
            committedRating = null,
            failure = ReviewCommitFailure(
                (transition.category ?: transition.reason?.commitCategory() ?: NOT_APPLIED_CATEGORY).take(96)
            ),
            resolution = transition.resolution,
            updatedAtEpochMs = now,
            resolvedAtEpochMs = now
        )
    }

    private fun unknown(
        record: ReviewCommitRecord,
        transition: ReviewCommitTransition.BackendOutcomeUnknown,
        now: Long
    ): ReviewCommitRecord? {
        if (record.status !in setOf(ReviewCommitStatus.SUBMITTING, ReviewCommitStatus.PREPARED)) return null
        return record.copy(
            status = ReviewCommitStatus.AMBIGUOUS,
            phase = ReviewCommitPhase.FINAL_STATUS_PERSISTED,
            committedRating = null,
            failure = ReviewCommitFailure(
                (transition.category ?: transition.reason?.commitCategory() ?: UNKNOWN_OUTCOME_CATEGORY).take(96)
            ),
            resolution = transition.resolution,
            updatedAtEpochMs = now,
            resolvedAtEpochMs = now
        )
    }

    /** RETRY_ALLOWED → PREPARED only (INV-11B-09). The payload never changes. */
    private fun beginRetry(record: ReviewCommitRecord): ReviewCommitRecord? =
        if (record.status != ReviewCommitStatus.RETRY_ALLOWED) null
        else record.copy(
            status = ReviewCommitStatus.PREPARED,
            phase = ReviewCommitPhase.INTENT_PERSISTED,
            claimedAtEpochMs = null,
            response = null,
            committedRating = null,
            submittedAtEpochMs = null,
            resolvedAtEpochMs = null,
            failure = null,
            resolution = null
        )

    /** Releases the exclusive claim of an attempt that never entered the boundary. */
    private fun releaseClaim(record: ReviewCommitRecord, now: Long): ReviewCommitRecord? =
        if (record.status != ReviewCommitStatus.PREPARED || record.claimedAtEpochMs == null) null
        else record.copy(claimedAtEpochMs = null, updatedAtEpochMs = now)

    // ------------------------------------------------------------------ reconciliation

    /**
     * Reconciliation evidence *replaces* the unknown outcome with a definitive one: the durable
     * response is what the backend's authoritative query proved, not what the lost answer claimed.
     */
    private fun reconciledCommitted(
        record: ReviewCommitRecord,
        receipt: CommitReceipt?,
        now: Long
    ): ReviewCommitRecord? =
        if (record.status != ReviewCommitStatus.AMBIGUOUS) null
        else record.copy(
            status = ReviewCommitStatus.COMMITTED,
            phase = ReviewCommitPhase.FINAL_STATUS_PERSISTED,
            committedRating = record.selectedRating,
            response = CommitResponseEvidence(
                CommitResponseKind.CONFIRMED_COMMITTED,
                backendReceiptId = receipt?.backendReceiptId
            ),
            failure = null,
            resolution = ReviewCommitResolution.RECONCILED_APPLIED,
            updatedAtEpochMs = now,
            resolvedAtEpochMs = now
        )

    private fun reconciledNotCommitted(record: ReviewCommitRecord, now: Long): ReviewCommitRecord? =
        if (record.status != ReviewCommitStatus.AMBIGUOUS) null
        else record.copy(
            status = ReviewCommitStatus.RETRY_ALLOWED,
            phase = ReviewCommitPhase.FINAL_STATUS_PERSISTED,
            committedRating = null,
            response = CommitResponseEvidence(
                CommitResponseKind.CONFIRMED_NOT_APPLIED,
                ReviewCommitFailure(RECONCILED_NOT_APPLIED_CATEGORY)
            ),
            failure = ReviewCommitFailure(RECONCILED_NOT_APPLIED_CATEGORY),
            resolution = ReviewCommitResolution.RECONCILED_NOT_APPLIED,
            updatedAtEpochMs = now,
            resolvedAtEpochMs = now
        )

    private fun reconciliationInconclusive(record: ReviewCommitRecord, category: String, now: Long): ReviewCommitRecord? =
        if (record.status != ReviewCommitStatus.AMBIGUOUS) null
        else record.copy(
            failure = ReviewCommitFailure(category.take(96)),
            resolution = ReviewCommitResolution.RECONCILIATION_INCONCLUSIVE,
            updatedAtEpochMs = now
        )

    /** Recover a recorded backend response after process death. Never redispatches. */
    private fun finalizeRecordedResponse(record: ReviewCommitRecord, now: Long): ReviewCommitRecord? =
        if (record.status != ReviewCommitStatus.SUBMITTING ||
            record.phase != ReviewCommitPhase.BACKEND_RESPONSE_RECEIVED) null
        else terminalFromResponse(record, ReviewCommitResolution.RECOVERED_RESPONSE, now)
}
