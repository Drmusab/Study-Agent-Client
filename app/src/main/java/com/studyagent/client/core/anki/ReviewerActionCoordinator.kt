package com.studyagent.client.core.anki

import com.studyagent.client.core.common.orOnStoreFailure
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * GATE 13 §16 — what the coordinator **returns**. It is a conclusion of local transaction policy,
 * never a restatement of a backend fact: the backend reports [ReviewerActionBackendResult], the
 * coordinator turns it into one of these.
 *
 * Every variant except [Conflict] carries the durable [ReviewerActionRecord], so a caller can never
 * act on an outcome that was not durably recorded (§17).
 */
sealed interface ReviewerActionOutcome {

    /** The backend confirmed the action and the confirmation is durable (§9/§11). */
    data class Applied(val record: ReviewerActionRecord) : ReviewerActionOutcome

    /** Proven not applied; the same logical action may be retried (same id, §13). */
    data class RetryAllowed(val record: ReviewerActionRecord) : ReviewerActionOutcome

    /** The action may or may not have been applied. No retry and no next card (§5/§13). */
    data class Ambiguous(val record: ReviewerActionRecord) : ReviewerActionOutcome

    /**
     * Refused before any mutation, or the durable record could not be written/read.
     *
     * [record] is the durable action this refusal revolved around, when one exists. A `null`
     * [record] means *nothing durable exists* — the caller must then treat the action as if it had
     * never been requested (the coordinator guarantees no mutation was dispatched, INV-13-08),
     * while a non-null [record] means the ledger holds an attempt of this turn that the caller must
     * not overwrite with a local guess.
     */
    data class Conflict(
        val error: AnkiError,
        val record: ReviewerActionRecord? = null
    ) : ReviewerActionOutcome

    val actionId: ReviewerActionId?
        get() = when (this) {
            is Applied -> record.actionId
            is RetryAllowed -> record.actionId
            is Ambiguous -> record.actionId
            is Conflict -> null
        }

    val statusOrNull: ReviewerActionStatus?
        get() = when (this) {
            is Applied -> record.status
            is RetryAllowed -> record.status
            is Ambiguous -> record.status
            is Conflict -> null
        }
}

/**
 * GATE 13 §16 — the **only** transaction orchestrator for reviewer actions.
 *
 * The coordinator owns the transaction, not the study interaction: the study layer decides *that* a
 * flag/bury/suspend was requested, the coordinator decides *whether and how* it may reach the
 * backend at most once. Nothing outside this interface may re-send an action (§17, AUDIT 3).
 */
interface ReviewerActionCoordinator {

    /**
     * Perform one reviewer action on a turn that has no logical action yet.
     *
     * Ordered exactly as §17: validate → create [ReviewerActionId] → persist `PREPARED` → persist
     * `SUBMITTING` → enter the backend mutation → classify → persist the final status. The backend
     * mutation is never entered before the durable `SUBMITTING` write (INV-13-08).
     *
     * Existing-record behaviour, always with the **same** id:
     *
     * | Durable status | `perform()` |
     * |---|---|
     * | `PREPARED` | resumes the existing action (provably un-entered); no new record |
     * | `APPLIED` | returns the prior [ReviewerActionOutcome.Applied]; the backend is never called again (§12) |
     * | `SUBMITTING` | [ReviewerActionOutcome.Conflict] — already in progress |
     * | `RETRY_ALLOWED` | [ReviewerActionOutcome.RetryAllowed] — use [retry] to submit again |
     * | `AMBIGUOUS` | [ReviewerActionOutcome.Ambiguous] — use [recover]; never a replay |
     */
    suspend fun perform(request: ReviewerActionRequest): ReviewerActionOutcome

    /**
     * Submit an existing action again after it was proven not applied (§13).
     *
     * Legal from [ReviewerActionStatus.RETRY_ALLOWED] (through the locked sequence
     * `RETRY_ALLOWED → RetryRequested → PREPARED → SUBMITTING`, INV-13-11) and from
     * [ReviewerActionStatus.PREPARED], which §5 defines as provably un-entered — including a
     * `PREPARED` record restored after process death (§26).
     */
    suspend fun retry(actionId: ReviewerActionId): ReviewerActionOutcome

    /**
     * Classify (and, where read-only evidence can decide it, resolve) an action after an
     * interruption (§26/§27). Used for [ReviewerActionStatus.AMBIGUOUS] and for a
     * [ReviewerActionStatus.SUBMITTING] record found after process recovery.
     *
     * It never performs a blind mutation replay: the only backend call it may make is the read-only
     * [AnkiBackend.reconcileReviewerAction] probe.
     */
    suspend fun recover(actionId: ReviewerActionId): ReviewerActionRecoveryOutcome
}

/**
 * The default [ReviewerActionCoordinator]: the durable ledger plus the backend contract, ordered
 * exactly as §17 demands.
 *
 * ## Mutual exclusion (§15/§25)
 *
 * Two different owners must not mutate one card at once, so this coordinator refuses to prepare an
 * action while a rating commit exists for the same turn (any status — §23 makes even `COMMITTED`
 * block, because the turn is already resolved) and while another action is active for the turn
 * (§15). The reverse direction — a rating commit refused while an action is unresolved — is
 * enforced at the rating pipeline's own preparation step (§25); both live below the UI, never in a
 * disabled button.
 *
 * ## No ledger, no mutation
 *
 * Without a durable [ReviewerActionLedger] there is no transaction truth across process death, so
 * every action is refused **before** dispatch ([AnkiError.ActionLedgerUnavailable]) — the same
 * fail-closed rule GATE 11B applied to ratings.
 */
class DefaultReviewerActionCoordinator(
    private val ledger: ReviewerActionLedger,
    /** The *rating* ledger, read-only here: it answers "does this turn already own the slot?". */
    private val commitLedger: ReviewCommitLedger? = null,
    private val registry: AnkiBackendRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
    private val recoveryPolicy: ReviewerActionRecoveryPolicy = ReviewerActionRecoveryPolicy(),
    private val reconciler: ReviewerActionReconciler = AnkiReviewerActionReconciler(registry, clock)
) : ReviewerActionCoordinator {

    /**
     * In-process attempt exclusion, keyed by [ReviewerActionId]. Process-local and deliberately
     * *not* truth: it exists so that concurrent callers produce one logical mutation
     * (VERIFICATION 13), while the durable `SUBMITTING` status remains the cross-process guarantee.
     */
    private val attemptsInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun perform(request: ReviewerActionRequest): ReviewerActionOutcome {
        // §17 step 1 — validate before anything durable exists.
        validate(request)?.let { return ReviewerActionOutcome.Conflict(it) }
        // §25 — a rating transaction owns this turn's mutation slot.
        commitBlocker(request.turnId, request.sessionId)?.let { return ReviewerActionOutcome.Conflict(it) }
        if (!attemptsInFlight.add(request.actionId.value)) {
            return ReviewerActionOutcome.Conflict(
                AnkiError.ActionConflict("action_in_flight"), ledger.get(request.actionId))
        }
        try {
            // §17 steps 2/3 — the id is derived (never random) and the intent becomes durable.
            val prepared = when (val created = ledger.create(request.toRecord(clock(), request.semantics))) {
                is ReviewerActionLedgerWrite.Created -> created.record
                is ReviewerActionLedgerWrite.Existing -> created.record
                is ReviewerActionLedgerWrite.Conflict ->
                    return ReviewerActionOutcome.Conflict(
                        AnkiError.ActionConflict("action_slot_taken"))
                ReviewerActionLedgerWrite.Full ->
                    return ReviewerActionOutcome.Conflict(
                        AnkiError.ActionLedgerUnavailable("ledger_full"))
                is ReviewerActionLedgerWrite.StoreFailed ->
                    return ReviewerActionOutcome.Conflict(
                        AnkiError.ActionLedgerUnavailable(created.reason))
                is ReviewerActionLedgerWrite.Unavailable ->
                    return ReviewerActionOutcome.Conflict(
                        AnkiError.ActionLedgerUnavailable(created.reason))
                is ReviewerActionLedgerWrite.Rejected ->
                    return ReviewerActionOutcome.Conflict(
                        AnkiError.ActionLedgerUnavailable("invalid_action_record"))
                ReviewerActionLedgerWrite.Missing ->
                    return ReviewerActionOutcome.Conflict(
                        AnkiError.ActionLedgerUnavailable("ledger_record_missing"))
                is ReviewerActionLedgerWrite.StatusMismatch ->
                    return ReviewerActionOutcome.Conflict(
                        AnkiError.ActionLedgerUnavailable("unexpected_status"))
                is ReviewerActionLedgerWrite.Transitioned ->
                    return ReviewerActionOutcome.Conflict(
                        AnkiError.ActionLedgerUnavailable("unexpected_transition"))
            }
            // Answer from the ledger whenever the record may not be submitted by this call.
            when (prepared.status) {
                ReviewerActionStatus.APPLIED -> return ReviewerActionOutcome.Applied(prepared)
                ReviewerActionStatus.AMBIGUOUS -> return ReviewerActionOutcome.Ambiguous(prepared)
                ReviewerActionStatus.RETRY_ALLOWED -> return ReviewerActionOutcome.RetryAllowed(prepared)
                ReviewerActionStatus.SUBMITTING -> return ReviewerActionOutcome.Conflict(
                    AnkiError.ActionConflict("action_in_flight"), prepared)
                ReviewerActionStatus.PREPARED -> Unit
            }
            return submit(prepared)
        } finally {
            attemptsInFlight.remove(request.actionId.value)
        }
    }

    override suspend fun retry(actionId: ReviewerActionId): ReviewerActionOutcome {
        val record = ledger.get(actionId)
            ?: return ReviewerActionOutcome.Conflict(AnkiError.ActionLedgerUnavailable("record_missing"))
        when (record.status) {
            ReviewerActionStatus.APPLIED -> return ReviewerActionOutcome.Applied(record)
            ReviewerActionStatus.AMBIGUOUS -> return ReviewerActionOutcome.Ambiguous(record)
            ReviewerActionStatus.SUBMITTING -> return ReviewerActionOutcome.Conflict(
                AnkiError.ActionConflict("action_in_flight"), record)
            ReviewerActionStatus.RETRY_ALLOWED, ReviewerActionStatus.PREPARED -> Unit
        }
        commitBlocker(record.turnId, record.sessionId)?.let { return ReviewerActionOutcome.Conflict(it) }
        if (!attemptsInFlight.add(actionId.value)) {
            return ReviewerActionOutcome.Conflict(AnkiError.ActionConflict("action_in_flight"))
        }
        try {
            val prepared = if (record.status == ReviewerActionStatus.RETRY_ALLOWED) {
                // INV-13-11: RETRY_ALLOWED → PREPARED *durably*, then the normal sequence. The
                // identity, the card and the action never change.
                when (val saved = ledger.transition(
                    actionId, ReviewerActionStatus.RETRY_ALLOWED, ReviewerActionTransition.RetryRequested)) {
                    is ReviewerActionLedgerWrite.Transitioned -> saved.record
                    is ReviewerActionLedgerWrite.StatusMismatch -> return ReviewerActionOutcome.Conflict(
                        AnkiError.ActionConflict("action_status_changed"))
                    is ReviewerActionLedgerWrite.StoreFailed -> return ReviewerActionOutcome.Conflict(
                        AnkiError.ActionLedgerUnavailable(saved.reason))
                    is ReviewerActionLedgerWrite.Unavailable -> return ReviewerActionOutcome.Conflict(
                        AnkiError.ActionLedgerUnavailable(saved.reason))
                    is ReviewerActionLedgerWrite.Rejected -> return ReviewerActionOutcome.Conflict(
                        AnkiError.ActionConflict("retry_not_allowed"))
                    else -> return ReviewerActionOutcome.Conflict(
                        AnkiError.ActionLedgerUnavailable("retry_persistence_failure"))
                }
            } else record
            return submit(prepared)
        } finally {
            attemptsInFlight.remove(actionId.value)
        }
    }

    override suspend fun recover(actionId: ReviewerActionId): ReviewerActionRecoveryOutcome {
        val record = ledger.get(actionId)
        if (record == null) {
            // Unknown is not absent: a ledger that cannot be read must never license a fresh
            // mutation for a turn whose durable truth is simply unknown.
            return when (val health = ledger.health()) {
                ReviewerActionLedgerHealth.Ready ->
                    ReviewerActionRecoveryOutcome.NoTransaction(actionId)
                is ReviewerActionLedgerHealth.Unavailable ->
                    if (health.reason == ReviewerActionLedgerCodec.MULTIPLE_ACTIVE_PER_TURN ||
                        health.reason == ReviewerActionLedgerCodec.MULTIPLE_UNRESOLVED_PER_SESSION
                    ) {
                        ReviewerActionRecoveryOutcome.IntegrityFailure(health.reason)
                    } else {
                        ReviewerActionRecoveryOutcome.Indeterminate(
                            actionId, "action_ledger_unavailable")
                    }
            }
        }
        // §26 — the durable status alone decides what recovery may do.
        return when (val decision = recoveryPolicy.classify(record)) {
            ReviewerActionRecoveryAction.ResumeApplied -> ReviewerActionRecoveryOutcome.Recovered(
                decision, record, ReviewerActionOutcome.Applied(record))
            ReviewerActionRecoveryAction.OfferRetry -> ReviewerActionRecoveryOutcome.Recovered(
                decision, record, ReviewerActionOutcome.RetryAllowed(record))
            is ReviewerActionRecoveryAction.IntegrityFailure -> ReviewerActionRecoveryOutcome.Recovered(
                decision, record, null)
            ReviewerActionRecoveryAction.RemainBlocked -> ReviewerActionRecoveryOutcome.Recovered(
                decision, record, ReviewerActionOutcome.Ambiguous(record))
            ReviewerActionRecoveryAction.Reconcile -> reconcileRecord(record)
        }
    }

    // ------------------------------------------------------------------ §17 submission sequence

    /**
     * §17 steps 4-7 for one `PREPARED` record: durable `SUBMITTING` at the mutation boundary, one
     * backend call, one classified answer, one durable final status.
     */
    private suspend fun submit(record: ReviewerActionRecord): ReviewerActionOutcome {
        val backend = registry.find(record.backendId)
            ?: return markConfirmedNotApplied(record, AnkiError.BackendUnavailable(), "backend_missing")
        var boundaryEntered = false
        var boundaryWriteFailed = false
        // The AnkiBackend contract says a call returns typed evidence and propagates only
        // cancellation; a backend that throws has violated that contract, and anything unclassified
        // after dispatch may already have mutated — so the fallback is explicit uncertainty, never
        // a retry grant (no provider text is read or logged, INV-13-12).
        val result = orOnStoreFailure<ReviewerActionBackendResult>(
            ReviewerActionBackendResult.OutcomeUnknown(
                AnkiError.Unknown(ReviewActionCategories.EXECUTOR_EXCEPTION),
                ReviewActionCategories.EXECUTOR_EXCEPTION)
        ) {
            backend.performReviewerAction(record.cardRef, record.action) {
                if (boundaryEntered) {
                    // A second callback is not a second authorized dispatch.
                    return@performReviewerAction false
                }
                val saved = withContext(NonCancellable) {
                    ledger.transition(
                        record.actionId, ReviewerActionStatus.PREPARED,
                        ReviewerActionTransition.EnterMutationBoundary)
                }
                boundaryEntered = saved is ReviewerActionLedgerWrite.Transitioned
                if (!boundaryEntered) {
                    boundaryWriteFailed = saved is ReviewerActionLedgerWrite.StoreFailed ||
                        saved is ReviewerActionLedgerWrite.Unavailable
                }
                boundaryEntered
            }
        }
        return persistResult(record, result, boundaryEntered, boundaryWriteFailed)
    }

    /**
     * §9 mapping, applied durably. The mapping itself is never re-derived here: the command comes
     * from [ReviewerActionBackendResult.toTransition], whose status target is fixed by the
     * transition engine.
     */
    private suspend fun persistResult(
        record: ReviewerActionRecord,
        result: ReviewerActionBackendResult,
        boundaryEntered: Boolean,
        boundaryWriteFailed: Boolean
    ): ReviewerActionOutcome {
        if (boundaryWriteFailed) {
            // Our `SUBMITTING` write failed, so the backend was told not to mutate (INV-13-08). If
            // it obeyed, nothing happened — and that is still *our* storage failure, not a backend
            // retry grant. If it mutated anyway, only uncertainty may be recorded.
            return if (result is ReviewerActionBackendResult.ConfirmedNotApplied) {
                // The durable record is still PREPARED (memory rolled back) and the backend was
                // told not to mutate, so non-application is proven: this is exactly what
                // RETRY_ALLOWED means. The *storage* failure is reported as the failure category,
                // never as a retry grant invented by the caller.
                ReviewerActionOutcome.RetryAllowed(record)
            } else {
                persistBoundaryViolation(record, result)
            }
        }
        if (!boundaryEntered) {
            // The backend answered without ever asking to cross the boundary.
            return when (result) {
                is ReviewerActionBackendResult.ConfirmedNotApplied ->
                    // It certifies that no mutation was dispatched by this attempt, so the durable
                    // record (PREPARED) remains true and a retry is safe (§5).
                    markConfirmedNotApplied(record, result.reason, null)
                // Applied/Unknown without a durable boundary: the backend violated §17's ordering.
                // Force the boundary first so the mutation window is on record, then classify; if
                // even that write fails, record unbounded uncertainty rather than a safe PREPARED.
                is ReviewerActionBackendResult.ConfirmedApplied,
                is ReviewerActionBackendResult.OutcomeUnknown -> {
                    val forced = withContext(NonCancellable) {
                        ledger.transition(
                            record.actionId, ReviewerActionStatus.PREPARED,
                            ReviewerActionTransition.EnterMutationBoundary)
                    }
                    when (forced) {
                        is ReviewerActionLedgerWrite.Transitioned ->
                            persistResult(forced.record, result, boundaryEntered = true, boundaryWriteFailed = false)
                        else -> persistBoundaryViolation(record, result)
                    }
                }
            }
        }
        val current = ledger.get(record.actionId)
            ?: return ReviewerActionOutcome.Conflict(
                AnkiError.ActionLedgerUnavailable("record_missing"))
        if (current.status != ReviewerActionStatus.SUBMITTING) {
            // Another path already resolved this record (or the ledger moved on): the durable truth
            // wins and no second write happens.
            return current.toOutcome()
        }
        val saved = withContext(NonCancellable) {
            ledger.transition(record.actionId, ReviewerActionStatus.SUBMITTING, result.toTransition())
        }
        return when (saved) {
            is ReviewerActionLedgerWrite.Transitioned -> saved.record.toOutcome()
            is ReviewerActionLedgerWrite.StatusMismatch -> saved.actual.toOutcome()
            is ReviewerActionLedgerWrite.Rejected -> current.toOutcome()
            is ReviewerActionLedgerWrite.StoreFailed -> ReviewerActionOutcome.Conflict(
                AnkiError.ActionLedgerUnavailable(saved.reason))
            is ReviewerActionLedgerWrite.Unavailable -> ReviewerActionOutcome.Conflict(
                AnkiError.ActionLedgerUnavailable(saved.reason))
            else -> ReviewerActionOutcome.Conflict(AnkiError.ActionLedgerUnavailable("persist_failed"))
        }
    }

    /** A pre-dispatch refusal (or a boundary callback that never ran): proven not applied. */
    private suspend fun markConfirmedNotApplied(
        record: ReviewerActionRecord,
        reason: AnkiError?,
        category: String?
    ): ReviewerActionOutcome {
        val saved = withContext(NonCancellable) {
            ledger.transition(
                record.actionId, record.status,
                ReviewerActionTransition.BackendConfirmedNotApplied(
                    reason, category,
                    resolution = ReviewerActionResolution.REFUSED_BEFORE_DISPATCH))
        }
        return when (saved) {
            is ReviewerActionLedgerWrite.Transitioned -> saved.record.toOutcome()
            is ReviewerActionLedgerWrite.StoreFailed -> ReviewerActionOutcome.Conflict(
                AnkiError.ActionLedgerUnavailable(saved.reason))
            is ReviewerActionLedgerWrite.Unavailable -> ReviewerActionOutcome.Conflict(
                AnkiError.ActionLedgerUnavailable(saved.reason))
            else -> ReviewerActionOutcome.Conflict(AnkiError.ActionLedgerUnavailable("refusal_not_durable"))
        }
    }

    /**
     * The boundary could not be made durable although the backend answered definitively. The only
     * honest record is explicit uncertainty: `PREPARED` would claim the window never opened.
     */
    private suspend fun persistBoundaryViolation(
        record: ReviewerActionRecord,
        result: ReviewerActionBackendResult
    ): ReviewerActionOutcome {
        val saved = withContext(NonCancellable) {
            ledger.transition(
                record.actionId, ReviewerActionStatus.PREPARED,
                ReviewerActionTransition.BackendOutcomeUnknown(
                    AnkiError.Unknown(ReviewActionCategories.PERSISTENCE_FAILURE),
                    "${ReviewActionCategories.PERSISTENCE_FAILURE}:${result.token()}",
                    resolution = ReviewerActionResolution.RECOVERED_UNRESOLVED))
        }
        return when (saved) {
            is ReviewerActionLedgerWrite.Transitioned -> ReviewerActionOutcome.Ambiguous(saved.record)
            else -> ReviewerActionOutcome.Conflict(
                AnkiError.ActionLedgerUnavailable("boundary_violation_not_durable"))
        }
    }

    // ------------------------------------------------------------------ §26/§27 recovery

    private suspend fun reconcileRecord(record: ReviewerActionRecord): ReviewerActionRecoveryOutcome {
        // A backend that throws from a read-only probe violated the contract; nothing to undo, and
        // an unresolved reconciliation is a first-class answer (§27).
        val reconciliation = orOnStoreFailure<ReviewerActionReconciliationResult>(
            ReviewerActionReconciliationResult.Unresolved(AnkiError.Unknown("reconcile_threw"))
        ) { reconciler.reconcile(record) }
        val event: ReviewerActionTransition = when (reconciliation) {
            is ReviewerActionReconciliationResult.ConfirmedApplied ->
                ReviewerActionTransition.ReconciliationConfirmedApplied(reconciliation.receipt)
            ReviewerActionReconciliationResult.ConfirmedNotApplied ->
                ReviewerActionTransition.ReconciliationConfirmedNotApplied
            is ReviewerActionReconciliationResult.Unresolved ->
                ReviewerActionTransition.ReconciliationUnresolved(
                    reconciliation.reason?.commitCategory() ?: ReviewActionCategories.RECONCILIATION_INCONCLUSIVE)
        }
        val saved = withContext(NonCancellable) { ledger.transition(record.actionId, record.status, event) }
        return when (saved) {
            is ReviewerActionLedgerWrite.Transitioned -> when (saved.record.status) {
                ReviewerActionStatus.APPLIED -> ReviewerActionRecoveryOutcome.Recovered(
                    ReviewerActionRecoveryAction.ResumeApplied, saved.record,
                    ReviewerActionOutcome.Applied(saved.record))
                ReviewerActionStatus.RETRY_ALLOWED -> ReviewerActionRecoveryOutcome.Recovered(
                    ReviewerActionRecoveryAction.OfferRetry, saved.record,
                    ReviewerActionOutcome.RetryAllowed(saved.record))
                // Unresolved is a first-class answer, not an error (§11/§27).
                else -> ReviewerActionRecoveryOutcome.Recovered(
                    ReviewerActionRecoveryAction.RemainBlocked, saved.record,
                    ReviewerActionOutcome.Ambiguous(saved.record))
            }
            is ReviewerActionLedgerWrite.Rejected -> ReviewerActionRecoveryOutcome.Recovered(
                ReviewerActionRecoveryAction.IntegrityFailure("reconciliation_rejected"),
                record, null)
            is ReviewerActionLedgerWrite.StoreFailed -> ReviewerActionRecoveryOutcome.Indeterminate(
                record.actionId, saved.reason)
            is ReviewerActionLedgerWrite.Unavailable -> ReviewerActionRecoveryOutcome.Indeterminate(
                record.actionId, saved.reason)
            else -> ReviewerActionRecoveryOutcome.Indeterminate(
                record.actionId, "reconciliation_not_durable")
        }
    }

    // ------------------------------------------------------------------ validation & exclusion

    /**
     * §17 step 1. Request integrity is checked against the backend the action was locked into —
     * never the current global preference — and an action the backend's *live* contract does not
     * support is refused before anything durable exists.
     */
    private fun validate(request: ReviewerActionRequest): AnkiError? {
        if (request.cardRef.backendId != request.backendId ||
            request.collectionRef?.backendId?.let { it != request.backendId } == true
        ) {
            return AnkiError.InvalidRequest("action_backend_mismatch")
        }
        val backend = registry.find(request.backendId)
            ?: return AnkiError.BackendUnavailable()
        val capabilities = backend.capabilities.value.reviewerActions()
        if (!capabilities.supports(request.action)) {
            return AnkiError.UnsupportedAction(request.action.key)
        }
        return null
    }

    /**
     * §23/§25 — the rating transaction's claim on this turn's mutation slot. Every existing commit
     * record blocks a reviewer action: an unfinished one may still mutate, and a `COMMITTED` one
     * means the turn is already resolved (its reviewer actions belong to a future turn).
     */
    private suspend fun commitBlocker(turnId: ReviewTurnId, sessionId: String): AnkiError? {
        val commit = commitLedger ?: return null
        val record = commit.getByTurn(turnId) ?: return null
        if (record.sessionId != sessionId) {
            return AnkiError.ActionConflict("rating_commit_other_session")
        }
        return AnkiError.ActionConflict("rating_commit_${record.status.name.lowercase()}")
    }

    private fun ReviewerActionRecord.toOutcome(): ReviewerActionOutcome = when (status) {
        ReviewerActionStatus.APPLIED -> ReviewerActionOutcome.Applied(this)
        ReviewerActionStatus.RETRY_ALLOWED -> ReviewerActionOutcome.RetryAllowed(this)
        ReviewerActionStatus.AMBIGUOUS -> ReviewerActionOutcome.Ambiguous(this)
        // PREPARED/SUBMITTING are not outcomes: they are unfinished work, and the record travels
        // with the refusal so the caller never overwrites an attempt with a local guess.
        ReviewerActionStatus.PREPARED, ReviewerActionStatus.SUBMITTING ->
            ReviewerActionOutcome.Conflict(AnkiError.ActionConflict("action_unfinished"), this)
    }
}
