package com.studyagent.client.core.anki

import kotlinx.serialization.Serializable

/**
 * GATE 13 §5 — the **only** durable reviewer-action transaction status (INV-13-01).
 *
 * ```text
 *                 no action record
 *                        │  PrepareAction
 *                        ▼
 *                    PREPARED ──────────────┐ EnterMutationBoundary
 *                        ▲                  ▼
 *          RetryRequested│             SUBMITTING
 *                        │            /     |      \
 *                        │           /      |       \
 *                        │          ▼       ▼        ▼
 *                RETRY_ALLOWED   APPLIED  RETRY_   AMBIGUOUS
 *                        ▲      (terminal) ALLOWED     │
 *                        │                    ▲        │ reconcile
 *                        └────────────────────┴────────┤
 *                                          APPLIED ◀───┘
 * ```
 *
 * There is deliberately **no `NOT_STARTED`**: an action that does not exist is already expressed by
 * the absence of a [ReviewerActionRecord]. The first durable status of a record that exists is
 * [PREPARED] — the same discipline [ReviewCommitStatus] follows, in the other ledger.
 *
 * The status itself encodes the mutation boundary, so recovery never has to infer safety from
 * status plus anything else (§26):
 *
 * ```text
 * PREPARED   = durable intent, backend mutation boundary NOT entered → backend effect known absent
 * SUBMITTING = the boundary HAS been entered → the action may already have been applied
 * ```
 *
 * Nothing else in this codebase is reviewer-action transaction truth. `ReviewerActionUiState`
 * (`Idle` / `Saving` / `RetryAvailable` / `VerificationRequired`) is a **projection** of this enum
 * (§6/§7), never a competing vocabulary.
 */
@Serializable
enum class ReviewerActionStatus {

    /**
     * Action intent is durably recorded, but the irreversible backend mutation boundary has not yet
     * been crossed. Backend effect = **known absent**, so a retry is safe (§5).
     */
    PREPARED,

    /**
     * The backend mutation boundary has been crossed and the action may already have been applied.
     * **Never retried automatically** (§5, INV-13-12); after process death it is reconciled or
     * normalized to [AMBIGUOUS] (§26).
     */
    SUBMITTING,

    /**
     * Backend action success was confirmed **and durably recorded**. Terminal for this logical
     * reviewer action (§12, INV-13-09): every mutation event is rejected, and a new user action
     * requires a new [ReviewerActionId].
     */
    APPLIED,

    /**
     * There is authoritative evidence that the action was **not** applied, so the same logical
     * action — same [ReviewerActionId] (INV-13-11) — may be submitted again. The only status from
     * which a retry may begin (§10/§13).
     */
    RETRY_ALLOWED,

    /**
     * The action may or may not have been applied and Study-Agent cannot prove which. No blind
     * retry (§13, INV-13-10): only authoritative reconciliation may move this record, to [APPLIED]
     * or to [RETRY_ALLOWED] — or leave it [AMBIGUOUS], which is not an error.
     */
    AMBIGUOUS
}

/**
 * GATE 13 §11 — the only legal ways a [ReviewerActionRecord] may change.
 *
 * Callers never assign `record.status = …`. The ledger applies one of these commands, rejects
 * anything else *before* a storage write, and bumps [ReviewerActionRecord.version] only when the
 * write succeeds ([ReviewerActionTransitions.transition] is that gate). Names describe **events**,
 * never target statuses, so a command name can never be confused with a [ReviewerActionStatus].
 */
sealed interface ReviewerActionTransition {

    /**
     * `PREPARED → SUBMITTING` (§10/§11). Written durably **immediately before** the real backend
     * mutation and never before the backend is merely invoked (§17, INV-13-08): a crash during
     * read-only preflight still leaves a provably un-entered record behind.
     */
    data object EnterMutationBoundary : ReviewerActionTransition

    /** `SUBMITTING → APPLIED` on the backend's own confirmed answer (§9: the only mapping). */
    data class BackendConfirmedApplied(
        val receipt: ReviewerActionReceipt? = null,
        val resolution: String = ReviewerActionResolution.BACKEND_CONFIRMED
    ) : ReviewerActionTransition

    /** `SUBMITTING → RETRY_ALLOWED`: non-application is proven (§9). */
    data class BackendConfirmedNotApplied(
        val reason: AnkiError? = null,
        val category: String? = null,
        val resolution: String = ReviewerActionResolution.BACKEND_NOT_APPLIED
    ) : ReviewerActionTransition

    /** `SUBMITTING → AMBIGUOUS`: the outcome cannot be proven (§9). */
    data class BackendOutcomeUnknown(
        val reason: AnkiError? = null,
        val category: String? = null,
        val resolution: String = ReviewerActionResolution.BACKEND_AMBIGUOUS
    ) : ReviewerActionTransition

    /**
     * `RETRY_ALLOWED → PREPARED` for the same immutable action and the same [ReviewerActionId]
     * (§10/§11, INV-13-11). Never straight to SUBMITTING: every attempt repeats the same safety
     * sequence, and §13 forbids the same shortcut from [ReviewerActionStatus.AMBIGUOUS].
     */
    data object RetryRequested : ReviewerActionTransition

    /** `AMBIGUOUS → APPLIED` on authoritative read-only evidence (§11/§27). */
    data class ReconciliationConfirmedApplied(
        val receipt: ReviewerActionReceipt? = null
    ) : ReviewerActionTransition

    /** `AMBIGUOUS → RETRY_ALLOWED` on authoritative read-only evidence (§11/§27). */
    data object ReconciliationConfirmedNotApplied : ReviewerActionTransition

    /**
     * Reconciliation could not decide: `AMBIGUOUS → AMBIGUOUS` (only the reason is refreshed, which
     * §11 lists as legal and is *not* an error), and — for a [ReviewerActionStatus.SUBMITTING]
     * record found after process death — the §26 normalization `SUBMITTING → AMBIGUOUS`.
     */
    data class ReconciliationUnresolved(
        val category: String,
        val resolution: String = ReviewerActionResolution.RECONCILIATION_UNRESOLVED
    ) : ReviewerActionTransition
}

/** Typed rejection reasons. No rejected command may silently become a no-op. */
enum class ReviewerActionTransitionRejection {
    /** §12 — `APPLIED` is terminal; every mutation event on it is rejected. */
    APPLIED_TERMINAL,
    ILLEGAL_STATUS_TRANSITION,
    IDENTITY_MUTATION,
    INVALID_RESULT_RECORD,
    /** A receipt naming another backend or another action is not evidence for this record. */
    INVALID_RECEIPT
}

sealed interface ReviewerActionTransitionResult {
    data class Applied(val record: ReviewerActionRecord) : ReviewerActionTransitionResult
    data class Rejected(val reason: ReviewerActionTransitionRejection) : ReviewerActionTransitionResult
}

/** Stable, content-free tokens saying *how* a status was reached (diagnostics and the gate report). */
object ReviewerActionResolution {
    const val BACKEND_CONFIRMED = "backend_confirmed"
    const val BACKEND_NOT_APPLIED = "backend_not_applied"
    const val BACKEND_AMBIGUOUS = "backend_ambiguous"
    const val RECONCILED_APPLIED = "reconciled_applied"
    const val RECONCILED_NOT_APPLIED = "reconciled_not_applied"
    const val RECONCILIATION_UNRESOLVED = "reconciliation_unresolved"
    const val RECOVERED_UNRESOLVED = "recovered_unresolved"
    const val REFUSED_BEFORE_DISPATCH = "refused_before_dispatch"
}

/**
 * GATE 13 §11 — the closed transition engine.
 *
 * One guard over `(record, command)` pairs; anything the canonical table does not specify is
 * rejected **before** a durable write. The whole gate is:
 *
 * 1. `APPLIED` is terminal (§12) — every mutation command is rejected;
 * 2. recovery-originated commands must produce exactly the status the closed recovery table
 *    ([reviewerActionRecoveryTransition]) allows (§26);
 * 3. identity and the frozen payload are immutable;
 * 4. the `before.status → after.status` pair is one of the table's rows;
 * 5. the resulting record satisfies the per-status invariants ([valid]).
 */
object ReviewerActionTransitions {

    const val NOT_APPLIED_CATEGORY = "not_applied"
    const val UNKNOWN_OUTCOME_CATEGORY = "unknown_outcome"

    /**
     * Validates the initial durable intent (§11 first row: `no record → PrepareAction → PREPARED`).
     * Creation is not an unrestricted status transition.
     */
    fun validateInitial(record: ReviewerActionRecord): ReviewerActionTransitionResult {
        val valid = record.status == ReviewerActionStatus.PREPARED && record.attemptCount == 0 &&
            record.backendReceipt == null && record.failure == null && record.resolution == null &&
            record.submittedAtEpochMs == null && record.resolvedAtEpochMs == null &&
            record.version == 0L && valid(record)
        return if (valid) ReviewerActionTransitionResult.Applied(record)
        else ReviewerActionTransitionResult.Rejected(ReviewerActionTransitionRejection.INVALID_RESULT_RECORD)
    }

    /**
     * Pure command application. A [ReviewerActionTransitionResult.Rejected] means the command is
     * illegal for this record and must not be written. Does not stamp
     * [ReviewerActionRecord.version]; the ledger does that when the write is durable.
     */
    fun transition(
        record: ReviewerActionRecord,
        transition: ReviewerActionTransition,
        now: Long
    ): ReviewerActionTransitionResult {
        if (record.status == ReviewerActionStatus.APPLIED) {
            // §12: APPLIED is terminal. There is no metadata-only command in this family.
            return ReviewerActionTransitionResult.Rejected(ReviewerActionTransitionRejection.APPLIED_TERMINAL)
        }
        if (transition is ReviewerActionTransition.BackendConfirmedApplied &&
            !receiptBelongsTo(record, transition.receipt)
        ) {
            return ReviewerActionTransitionResult.Rejected(ReviewerActionTransitionRejection.INVALID_RECEIPT)
        }
        if (transition is ReviewerActionTransition.ReconciliationConfirmedApplied &&
            !receiptBelongsTo(record, transition.receipt)
        ) {
            return ReviewerActionTransitionResult.Rejected(ReviewerActionTransitionRejection.INVALID_RECEIPT)
        }
        if (transition is ReviewerActionTransition.BackendConfirmedApplied &&
            record.backendReceipt != null && record.backendReceipt != transition.receipt
        ) {
            // A receipt that is already durable must match exactly: a lost answer is never
            // overwritten by a different one.
            return ReviewerActionTransitionResult.Rejected(ReviewerActionTransitionRejection.INVALID_RECEIPT)
        }
        if (blankResolution(transition)) {
            return ReviewerActionTransitionResult.Rejected(ReviewerActionTransitionRejection.INVALID_RESULT_RECORD)
        }
        // §26 — a recovery-originated command must land on exactly the status the one closed
        // recovery table allows, or it is rejected before any durable write.
        val recoveryEvent = transition.recoveryEventOrNull()
        val recoveryTarget = recoveryEvent?.let { reviewerActionRecoveryTransition(record.status, it) }
        if (recoveryEvent != null && recoveryTarget == null) {
            return ReviewerActionTransitionResult.Rejected(
                ReviewerActionTransitionRejection.ILLEGAL_STATUS_TRANSITION)
        }
        val next = try {
            applyUnchecked(record, transition, now)
        } catch (_: IllegalArgumentException) {
            return ReviewerActionTransitionResult.Rejected(
                ReviewerActionTransitionRejection.INVALID_RESULT_RECORD)
        } ?: return ReviewerActionTransitionResult.Rejected(
            ReviewerActionTransitionRejection.ILLEGAL_STATUS_TRANSITION)
        if (recoveryEvent != null && next.status != recoveryTarget) {
            return ReviewerActionTransitionResult.Rejected(
                ReviewerActionTransitionRejection.ILLEGAL_STATUS_TRANSITION)
        }
        if (!sameIdentity(record, next)) {
            return ReviewerActionTransitionResult.Rejected(ReviewerActionTransitionRejection.IDENTITY_MUTATION)
        }
        if (!allowed(record, next) || !valid(next)) {
            return ReviewerActionTransitionResult.Rejected(
                ReviewerActionTransitionRejection.ILLEGAL_STATUS_TRANSITION)
        }
        return ReviewerActionTransitionResult.Applied(next)
    }

    /**
     * The whole durable write gate: identity is immutable, the frozen semantics are immutable, and
     * the status machine admits the `before → after` pair. The ledger applies nothing else.
     */
    fun validWrite(before: ReviewerActionRecord, after: ReviewerActionRecord): Boolean =
        sameIdentity(before, after) &&
            before.frozenIdempotentReplay == after.frozenIdempotentReplay &&
            before.frozenAuthoritativeReconciliation == after.frozenAuthoritativeReconciliation &&
            allowed(before, after) && valid(after)

    private fun blankResolution(transition: ReviewerActionTransition): Boolean = when (transition) {
        is ReviewerActionTransition.BackendConfirmedApplied -> transition.resolution.isBlank()
        is ReviewerActionTransition.BackendConfirmedNotApplied -> transition.resolution.isBlank()
        is ReviewerActionTransition.BackendOutcomeUnknown -> transition.resolution.isBlank()
        is ReviewerActionTransition.ReconciliationUnresolved ->
            transition.category.isBlank() || transition.resolution.isBlank()
        else -> false
    }

    /** A receipt is evidence only for the backend and the action it names (§4: content-free). */
    private fun receiptBelongsTo(record: ReviewerActionRecord, receipt: ReviewerActionReceipt?): Boolean =
        receipt == null || (receipt.backendId == record.backendId && receipt.actionKey == record.action.key)

    private fun sameIdentity(before: ReviewerActionRecord, after: ReviewerActionRecord): Boolean =
        before.actionId == after.actionId && before.backendId == after.backendId &&
            before.sessionId == after.sessionId && before.turnId == after.turnId &&
            before.cardRef == after.cardRef && before.collectionRef == after.collectionRef &&
            before.action == after.action && before.createdAtEpochMs == after.createdAtEpochMs

    /**
     * The durable status machine of §10/§11, as one guard over `before → after` pairs. Anything not
     * listed here cannot be written — including every §12/§13 forbidden edge
     * (`APPLIED → *`, `AMBIGUOUS → PREPARED`, `AMBIGUOUS → SUBMITTING`,
     * `RETRY_ALLOWED → SUBMITTING` directly).
     */
    fun allowed(before: ReviewerActionRecord, after: ReviewerActionRecord): Boolean =
        when (before.status) {
            ReviewerActionStatus.APPLIED -> false
            // PREPARED → PREPARED (a retried preflight) and PREPARED → SUBMITTING are the §10/§11
            // rows. PREPARED → RETRY_ALLOWED / AMBIGUOUS are the *pre-entry resolution* rows: a
            // backend that certifies a pre-dispatch refusal proves non-application, and a backend
            // that answers Applied/Unknown without ever crossing the durable boundary has violated
            // §17's ordering — which must be recorded as either proven-not-applied or explicit
            // uncertainty, never left looking like a safely un-entered intent (the same reasoning
            // GATE 11B applied to its boundary-violation rows).
            ReviewerActionStatus.PREPARED -> after.status in setOf(
                ReviewerActionStatus.PREPARED, ReviewerActionStatus.SUBMITTING,
                ReviewerActionStatus.RETRY_ALLOWED, ReviewerActionStatus.AMBIGUOUS
            ) && after.attemptCount >= before.attemptCount
            ReviewerActionStatus.SUBMITTING -> after.status in setOf(
                ReviewerActionStatus.SUBMITTING, ReviewerActionStatus.APPLIED,
                ReviewerActionStatus.RETRY_ALLOWED, ReviewerActionStatus.AMBIGUOUS
            ) && after.attemptCount == before.attemptCount
            ReviewerActionStatus.RETRY_ALLOWED -> after.status in setOf(
                ReviewerActionStatus.RETRY_ALLOWED, ReviewerActionStatus.PREPARED
            ) && after.attemptCount == before.attemptCount
            ReviewerActionStatus.AMBIGUOUS -> after.status in setOf(
                ReviewerActionStatus.AMBIGUOUS, ReviewerActionStatus.APPLIED,
                ReviewerActionStatus.RETRY_ALLOWED
            ) && after.attemptCount == before.attemptCount
        }

    /**
     * The per-status record invariants, re-checked for every record read from storage and after
     * every transition. They are what make `status` self-sufficient: no other field has to be
     * consulted to know whether the mutation boundary was crossed.
     */
    fun valid(record: ReviewerActionRecord): Boolean {
        if (record.cardRef.backendId != record.backendId) return false
        if (record.collectionRef != null && record.collectionRef.backendId != record.backendId) return false
        if (record.collectionRef?.collectionKey != null && record.cardRef.collectionKey != null &&
            record.collectionRef.collectionKey != record.cardRef.collectionKey
        ) return false
        if (record.actionId != ReviewerActionId.of(
                record.backendId, record.sessionId, record.turnId, record.action)
        ) return false
        if (record.backendReceipt != null && !receiptBelongsTo(record, record.backendReceipt)) return false
        return when (record.status) {
            ReviewerActionStatus.PREPARED -> record.attemptCount >= 0 && record.backendReceipt == null &&
                record.failure == null && record.submittedAtEpochMs == null &&
                record.resolvedAtEpochMs == null
            ReviewerActionStatus.SUBMITTING -> record.attemptCount > 0 && record.failure == null &&
                record.submittedAtEpochMs != null && record.resolvedAtEpochMs == null
            ReviewerActionStatus.APPLIED -> record.attemptCount > 0 && record.failure == null &&
                record.submittedAtEpochMs != null && record.resolvedAtEpochMs != null
            ReviewerActionStatus.RETRY_ALLOWED -> record.failure != null &&
                record.backendReceipt == null && record.resolvedAtEpochMs != null
            ReviewerActionStatus.AMBIGUOUS -> record.attemptCount > 0 && record.failure != null &&
                record.backendReceipt == null && record.submittedAtEpochMs != null &&
                record.resolvedAtEpochMs != null
        }
    }

    private fun applyUnchecked(
        record: ReviewerActionRecord,
        transition: ReviewerActionTransition,
        now: Long
    ): ReviewerActionRecord? = when (transition) {
        ReviewerActionTransition.EnterMutationBoundary -> enterMutationBoundary(record, now)
        is ReviewerActionTransition.BackendConfirmedApplied ->
            applied(record, transition.receipt, transition.resolution, now)
        is ReviewerActionTransition.BackendConfirmedNotApplied ->
            notApplied(record, transition.category ?: transition.reason?.commitCategory()
                ?: NOT_APPLIED_CATEGORY, transition.resolution, now)
        is ReviewerActionTransition.BackendOutcomeUnknown ->
            unknown(record, transition.category ?: transition.reason?.commitCategory()
                ?: UNKNOWN_OUTCOME_CATEGORY, transition.resolution, now)
        ReviewerActionTransition.RetryRequested -> retryRequested(record)
        is ReviewerActionTransition.ReconciliationConfirmedApplied ->
            reconciledApplied(record, transition.receipt, now)
        ReviewerActionTransition.ReconciliationConfirmedNotApplied -> reconciledNotApplied(record, now)
        is ReviewerActionTransition.ReconciliationUnresolved ->
            reconciliationUnresolved(record, transition.category, transition.resolution, now)
    }

    /** `PREPARED → SUBMITTING`, and the attempt counter of the attempt that crosses the boundary. */
    private fun enterMutationBoundary(record: ReviewerActionRecord, now: Long): ReviewerActionRecord? =
        if (record.status != ReviewerActionStatus.PREPARED) null
        else record.copy(
            status = ReviewerActionStatus.SUBMITTING,
            attemptCount = record.attemptCount + 1,
            submittedAtEpochMs = now,
            updatedAtEpochMs = now
        )

    private fun applied(
        record: ReviewerActionRecord,
        receipt: ReviewerActionReceipt?,
        resolution: String,
        now: Long
    ): ReviewerActionRecord? =
        if (record.status != ReviewerActionStatus.SUBMITTING) null
        else record.copy(
            status = ReviewerActionStatus.APPLIED,
            backendReceipt = receipt ?: record.backendReceipt,
            failure = null,
            resolution = resolution,
            resolvedAtEpochMs = now,
            updatedAtEpochMs = now
        )

    /** `SUBMITTING → RETRY_ALLOWED`, and the pre-entry resolution `PREPARED → RETRY_ALLOWED`. */
    private fun notApplied(
        record: ReviewerActionRecord,
        category: String,
        resolution: String,
        now: Long
    ): ReviewerActionRecord? =
        if (record.status != ReviewerActionStatus.SUBMITTING &&
            record.status != ReviewerActionStatus.PREPARED
        ) null
        else record.copy(
            status = ReviewerActionStatus.RETRY_ALLOWED,
            backendReceipt = null,
            failure = ReviewerActionFailure(category.take(96)),
            resolution = resolution,
            resolvedAtEpochMs = now,
            updatedAtEpochMs = now
        )

    /**
     * `SUBMITTING → AMBIGUOUS`, and the pre-entry resolution `PREPARED → AMBIGUOUS` for a backend
     * that mutated without crossing the boundary. That row also stamps the mutation window
     * ([ReviewerActionRecord.submittedAtEpochMs]) because the window really did open — recording
     * `PREPARED`'s "window never opened" would be the one lie that makes a later reconciliation
     * read unsound.
     */
    private fun unknown(
        record: ReviewerActionRecord,
        category: String,
        resolution: String,
        now: Long
    ): ReviewerActionRecord? = when (record.status) {
        ReviewerActionStatus.SUBMITTING -> record.copy(
            status = ReviewerActionStatus.AMBIGUOUS,
            backendReceipt = null,
            failure = ReviewerActionFailure(category.take(96)),
            resolution = resolution,
            resolvedAtEpochMs = now,
            updatedAtEpochMs = now
        )
        ReviewerActionStatus.PREPARED -> record.copy(
            status = ReviewerActionStatus.AMBIGUOUS,
            attemptCount = record.attemptCount + 1,
            backendReceipt = null,
            failure = ReviewerActionFailure(category.take(96)),
            resolution = resolution,
            submittedAtEpochMs = now,
            resolvedAtEpochMs = now,
            updatedAtEpochMs = now
        )
        else -> null
    }

    /**
     * `RETRY_ALLOWED → PREPARED` only (§10/§13, INV-13-10/INV-13-11). The action, the identity and
     * the frozen semantics never change; the previous attempt's failure is cleared because the
     * record is once again an un-entered intent.
     */
    private fun retryRequested(record: ReviewerActionRecord): ReviewerActionRecord? =
        if (record.status != ReviewerActionStatus.RETRY_ALLOWED) null
        else record.copy(
            status = ReviewerActionStatus.PREPARED,
            backendReceipt = null,
            failure = null,
            resolution = null,
            submittedAtEpochMs = null,
            resolvedAtEpochMs = null
        )

    /** `SUBMITTING`/`AMBIGUOUS → APPLIED` on authoritative read-only evidence (§11/§26/§27). */
    private fun reconciledApplied(
        record: ReviewerActionRecord,
        receipt: ReviewerActionReceipt?,
        now: Long
    ): ReviewerActionRecord? =
        if (reviewerActionRecoveryTransition(
                record.status,
                ReviewerActionRecoveryEvent.RECONCILIATION_CONFIRMED_APPLIED
            ) != ReviewerActionStatus.APPLIED
        ) null
        else record.copy(
            status = ReviewerActionStatus.APPLIED,
            backendReceipt = receipt ?: record.backendReceipt,
            failure = null,
            resolution = ReviewerActionResolution.RECONCILED_APPLIED,
            resolvedAtEpochMs = now,
            updatedAtEpochMs = now
        )

    /** `SUBMITTING`/`AMBIGUOUS → RETRY_ALLOWED` on authoritative read-only evidence (§11/§26/§27). */
    private fun reconciledNotApplied(record: ReviewerActionRecord, now: Long): ReviewerActionRecord? =
        if (reviewerActionRecoveryTransition(
                record.status,
                ReviewerActionRecoveryEvent.RECONCILIATION_CONFIRMED_NOT_APPLIED
            ) != ReviewerActionStatus.RETRY_ALLOWED
        ) null
        else record.copy(
            status = ReviewerActionStatus.RETRY_ALLOWED,
            backendReceipt = null,
            failure = ReviewerActionFailure(ReviewActionCategories.RECONCILED_NOT_APPLIED),
            resolution = ReviewerActionResolution.RECONCILED_NOT_APPLIED,
            resolvedAtEpochMs = now,
            updatedAtEpochMs = now
        )

    /**
     * §11 (`AMBIGUOUS → AMBIGUOUS`) and §26 (a recovered `SUBMITTING` that cannot be classified is
     * normalized into explicit uncertainty). Both targets come from the closed recovery table.
     */
    private fun reconciliationUnresolved(
        record: ReviewerActionRecord,
        category: String,
        resolution: String,
        now: Long
    ): ReviewerActionRecord? {
        val target = reviewerActionRecoveryTransition(
            record.status, ReviewerActionRecoveryEvent.RECONCILIATION_UNRESOLVED) ?: return null
        if (target == record.status) {
            // AMBIGUOUS → AMBIGUOUS: refresh only the reason; the record stays unresolved.
            return record.copy(
                failure = ReviewerActionFailure(category.take(96)),
                resolution = resolution,
                updatedAtEpochMs = now
            )
        }
        return record.copy(
            status = ReviewerActionStatus.AMBIGUOUS,
            backendReceipt = null,
            failure = ReviewerActionFailure(category.take(96)),
            resolution = resolution,
            resolvedAtEpochMs = now,
            updatedAtEpochMs = now
        )
    }
}

/** Stable, content-free failure-category tokens for reviewer actions. Never provider text. */
object ReviewActionCategories {
    const val RECONCILED_NOT_APPLIED = "reconciled_not_applied"
    const val RECONCILIATION_INCONCLUSIVE = "reconciliation_inconclusive"
    const val RECOVERED_SUBMITTING = "recovered_submitting"
    const val BACKEND_MISSING = "backend_missing"
    const val LEDGER_UNAVAILABLE = "action_ledger_unavailable"
    const val PERSISTENCE_FAILURE = "action_persistence_failure"
    const val EXECUTOR_EXCEPTION = "executor_exception"
}

/**
 * GATE 13 §22 — the exact fresh-next-card rule for reviewer actions:
 *
 * ```text
 * nextCardAllowed IFF action.invalidatesCurrentTurn AND status == APPLIED
 * ```
 *
 * | Action | Status | Next card |
 * |---|---|---:|
 * | Flag | `APPLIED` | no |
 * | Bury / Suspend | `APPLIED` | yes |
 * | any | `PREPARED` / `SUBMITTING` / `RETRY_ALLOWED` / `AMBIGUOUS` | no |
 *
 * This is the reviewer-action sibling of GATE 11E's `nextCardAllowed(ReviewCommitStatus)` — one
 * production barrier per mutation family, never a local `status == APPLIED` comparison at a call
 * site (INV-13-15).
 */
fun nextCardAllowed(action: ReviewerAction, status: ReviewerActionStatus): Boolean =
    action.invalidatesCurrentTurn && status == ReviewerActionStatus.APPLIED
