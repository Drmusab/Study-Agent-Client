package com.studyagent.client.core.anki

import kotlinx.coroutines.withTimeoutOrNull

/**
 * GATE 13 §26 — the canonical **recovery events** for reviewer actions.
 *
 * Recovery may produce only these transaction-level events; UI/navigation events are separate and
 * never named here. Every recovery status decision in the codebase is one `(current, event)` pair
 * evaluated by [reviewerActionRecoveryTransition] — startup code, the coordinator, the reconciler,
 * the ledger and the UI must not spread the table (the same discipline GATE 11D imposed on rating
 * recovery, in the other ledger).
 */
enum class ReviewerActionRecoveryEvent {

    /** An unfinished action was found (for example at process restore). Detection never classifies. */
    RECOVERY_DETECTED,

    /**
     * §10/§13 — an explicit retry of the same logical action. Legal from
     * [ReviewerActionStatus.PREPARED] (stays PREPARED, same identity: the boundary was never
     * entered) and from [ReviewerActionStatus.RETRY_ALLOWED] (returns to PREPARED). Forbidden from
     * SUBMITTING, AMBIGUOUS and APPLIED: pressing a button never creates evidence (INV-13-10/12).
     */
    RETRY_REQUESTED,

    /**
     * A read-only reconciliation began (§27). Legal only from
     * [ReviewerActionStatus.SUBMITTING] and [ReviewerActionStatus.AMBIGUOUS]; the durable status
     * remains the current one while reconciliation runs — reconciliation never introduces a durable
     * status of its own.
     */
    RECONCILIATION_STARTED,

    /** Authoritative reconciliation proved the action **was** applied (§11/§26/§27). */
    RECONCILIATION_CONFIRMED_APPLIED,

    /** Authoritative reconciliation proved the action was **not** applied (§11/§26/§27). */
    RECONCILIATION_CONFIRMED_NOT_APPLIED,

    /**
     * Reconciliation could not determine the outcome (unsupported, unavailable, timeout,
     * insufficient evidence). A recovered SUBMITTING becomes explicitly uncertain
     * ([ReviewerActionStatus.AMBIGUOUS], §26); AMBIGUOUS remains AMBIGUOUS, which is not an error
     * (§11 last row).
     */
    RECONCILIATION_UNRESOLVED,

    /**
     * The durable record contradicts itself or its backend. Truth is never rewritten to escape a
     * contradiction: the status stays exactly what it was and recovery surfaces
     * [ReviewerActionRecoveryAction.IntegrityFailure] instead.
     */
    INTEGRITY_VIOLATION_DETECTED
}

/**
 * GATE 13 §11 + §26 — the **one pure recovery transition function**.
 *
 * ```kotlin
 * reviewerActionRecoveryTransition(current, event) -> next status, or null when the pair is invalid
 * ```
 *
 * Closed table (`-` = invalid, reject before any durable write):
 *
 * | Current | RecoveryDetected | RetryRequested | ReconciliationStarted | ConfirmedApplied | ConfirmedNotApplied | Unresolved | IntegrityViolation |
 * |---|---|---|---|---|---|---|---|
 * | `PREPARED` | `PREPARED` | `PREPARED` | - | - | - | - | `PREPARED` |
 * | `SUBMITTING` | `SUBMITTING` | - | `SUBMITTING` | `APPLIED` | `RETRY_ALLOWED` | `AMBIGUOUS` | `SUBMITTING` |
 * | `RETRY_ALLOWED` | `RETRY_ALLOWED` | `PREPARED` | - | - | - | - | `RETRY_ALLOWED` |
 * | `AMBIGUOUS` | `AMBIGUOUS` | - | `AMBIGUOUS` | `APPLIED` | `RETRY_ALLOWED` | `AMBIGUOUS` | `AMBIGUOUS` |
 * | `APPLIED` | `APPLIED` | - | `APPLIED` | `APPLIED` | - | `APPLIED` | `APPLIED` |
 *
 * `APPLIED` is terminal (§12, INV-13-09): no event changes the status. The two events that would
 * rewrite truth (`RetryRequested`, `ReconciliationConfirmedNotApplied`) are rejected outright; the
 * remaining events re-state the durable truth without mutating it.
 *
 * The `SUBMITTING` reconciliation rows are §26, not an extension of §11: a record found in
 * SUBMITTING after process death has crossed the mutation boundary, so recovery must reconcile it
 * — and an unresolved reconciliation normalizes it into explicit uncertainty.
 *
 * Restart is deliberately **not** an event of this table: restart alone never rewrites transaction
 * truth (§30). What runs after restart is recovery classification, which produces exactly these
 * events.
 */
fun reviewerActionRecoveryTransition(
    current: ReviewerActionStatus,
    event: ReviewerActionRecoveryEvent
): ReviewerActionStatus? = when (event) {
    ReviewerActionRecoveryEvent.RECOVERY_DETECTED -> current

    ReviewerActionRecoveryEvent.RETRY_REQUESTED -> when (current) {
        // §10/§13: PREPARED stays the same action (same action id), RETRY_ALLOWED re-enters
        // PREPARED through the locked retry sequence. Everything else is forbidden.
        ReviewerActionStatus.PREPARED, ReviewerActionStatus.RETRY_ALLOWED -> ReviewerActionStatus.PREPARED
        else -> null
    }

    ReviewerActionRecoveryEvent.RECONCILIATION_STARTED -> when (current) {
        // §27: the durable status remains while reconciliation executes. Reconciliation is
        // meaningless for a provably un-entered PREPARED or a proven RETRY_ALLOWED.
        ReviewerActionStatus.SUBMITTING, ReviewerActionStatus.AMBIGUOUS, ReviewerActionStatus.APPLIED -> current
        else -> null
    }

    ReviewerActionRecoveryEvent.RECONCILIATION_CONFIRMED_APPLIED -> when (current) {
        ReviewerActionStatus.SUBMITTING, ReviewerActionStatus.AMBIGUOUS -> ReviewerActionStatus.APPLIED
        // Terminal: re-stated, never replayed (§12, VERIFICATION 10).
        ReviewerActionStatus.APPLIED -> ReviewerActionStatus.APPLIED
        else -> null
    }

    ReviewerActionRecoveryEvent.RECONCILIATION_CONFIRMED_NOT_APPLIED -> when (current) {
        ReviewerActionStatus.SUBMITTING, ReviewerActionStatus.AMBIGUOUS -> ReviewerActionStatus.RETRY_ALLOWED
        // An APPLIED record may never be downgraded (§12).
        else -> null
    }

    ReviewerActionRecoveryEvent.RECONCILIATION_UNRESOLVED -> when (current) {
        // §26: a recovered SUBMITTING is normalized into explicit uncertainty; AMBIGUOUS remains
        // AMBIGUOUS, which is not an error (§11).
        ReviewerActionStatus.SUBMITTING, ReviewerActionStatus.AMBIGUOUS -> ReviewerActionStatus.AMBIGUOUS
        ReviewerActionStatus.APPLIED -> ReviewerActionStatus.APPLIED
        else -> null
    }

    ReviewerActionRecoveryEvent.INTEGRITY_VIOLATION_DETECTED -> current
}

/**
 * The recovery event a durable [ReviewerActionTransition] expresses, or `null` for a command that
 * belongs to the normal action pipeline (§17). The transition engine routes every
 * recovery-originated command through [reviewerActionRecoveryTransition], so the closed table above
 * is the single authority for recovery status changes.
 */
fun ReviewerActionTransition.recoveryEventOrNull(): ReviewerActionRecoveryEvent? = when (this) {
    ReviewerActionTransition.RetryRequested -> ReviewerActionRecoveryEvent.RETRY_REQUESTED
    is ReviewerActionTransition.ReconciliationConfirmedApplied ->
        ReviewerActionRecoveryEvent.RECONCILIATION_CONFIRMED_APPLIED
    ReviewerActionTransition.ReconciliationConfirmedNotApplied ->
        ReviewerActionRecoveryEvent.RECONCILIATION_CONFIRMED_NOT_APPLIED
    is ReviewerActionTransition.ReconciliationUnresolved ->
        ReviewerActionRecoveryEvent.RECONCILIATION_UNRESOLVED
    else -> null
}

/**
 * GATE 13 §26 — recovery **decisions**. No UI strings, no scheduler reads.
 *
 * These names are deliberately not [ReviewerActionStatus] names, so a log line never has to guess
 * which layer it belongs to:
 *
 * ```text
 * status = RETRY_ALLOWED   → durable action truth
 * action = OfferRetry      → what recovery decided to do about it
 * ```
 */
sealed interface ReviewerActionRecoveryAction {
    /** Stable, content-free name for diagnostics. */
    val label: String get() = this::class.simpleName ?: "unknown"

    /** Durable success. Resume the post-action flow; never replay the mutation (§12/§30). */
    data object ResumeApplied : ReviewerActionRecoveryAction

    /** The backend mutation is proven not to have been entered or not to have happened. */
    data object OfferRetry : ReviewerActionRecoveryAction

    /** The outcome is unknown: gather read-only backend evidence before anything else. */
    data object Reconcile : ReviewerActionRecoveryAction

    /** Nothing may be done yet: stay blocked and keep the user informed. */
    data object RemainBlocked : ReviewerActionRecoveryAction

    /** The durable record contradicts itself. Never guess, never replay. */
    data class IntegrityFailure(val reason: String) : ReviewerActionRecoveryAction
}

/**
 * The normative recovery decision table (§26), as a total function of the durable status alone:
 *
 * | Durable status | Action | Why |
 * |---|---|---|
 * | `PREPARED` | `OfferRetry` | provably un-entered; a retry may be offered |
 * | `SUBMITTING` | `Reconcile` | boundary entered; normalize to AMBIGUOUS if unresolved |
 * | `RETRY_ALLOWED` | `OfferRetry` | proven not applied |
 * | `AMBIGUOUS` | `Reconcile` | reconcile or remain blocked |
 * | `APPLIED` | `ResumeApplied` | never replayed |
 *
 * Safety never reads anything but the status: `SUBMITTING` means the boundary was entered,
 * `PREPARED` means it provably was not. Pure — same status, same decision, no clock, no I/O.
 */
class ReviewerActionRecoveryPolicy {

    /**
     * [proof] is authoritative read-only reconciliation evidence (§27). It is meaningful exactly
     * for the two statuses whose outcome reconciliation may resolve —
     * [ReviewerActionStatus.SUBMITTING] and [ReviewerActionStatus.AMBIGUOUS]. Supplying it for a
     * status that needs no proof (`PREPARED`, `RETRY_ALLOWED`) or that already carries proven truth
     * (`APPLIED`) is an integrity failure, never a shortcut to a decision.
     */
    fun classify(
        record: ReviewerActionRecord,
        proof: ReviewerActionReconciliationResult? = null
    ): ReviewerActionRecoveryAction {
        if (proof != null && record.status in setOf(
                ReviewerActionStatus.SUBMITTING, ReviewerActionStatus.AMBIGUOUS)
        ) return when (proof) {
            is ReviewerActionReconciliationResult.ConfirmedApplied ->
                ReviewerActionRecoveryAction.ResumeApplied
            ReviewerActionReconciliationResult.ConfirmedNotApplied ->
                ReviewerActionRecoveryAction.OfferRetry
            is ReviewerActionReconciliationResult.Unresolved ->
                ReviewerActionRecoveryAction.RemainBlocked
        }
        if (proof != null) {
            return ReviewerActionRecoveryAction.IntegrityFailure(
                "proof_for_${record.status.name.lowercase()}")
        }
        return classifyStatus(record.status)
    }

    fun classifyStatus(status: ReviewerActionStatus): ReviewerActionRecoveryAction = when (status) {
        ReviewerActionStatus.PREPARED -> ReviewerActionRecoveryAction.OfferRetry
        ReviewerActionStatus.SUBMITTING -> ReviewerActionRecoveryAction.Reconcile
        ReviewerActionStatus.RETRY_ALLOWED -> ReviewerActionRecoveryAction.OfferRetry
        ReviewerActionStatus.AMBIGUOUS -> ReviewerActionRecoveryAction.Reconcile
        ReviewerActionStatus.APPLIED -> ReviewerActionRecoveryAction.ResumeApplied
    }
}

/**
 * GATE 13 §27 — the canonical result of one **read-only** reconciliation of a durable
 * [ReviewerActionRecord].
 *
 * There are deliberately no probabilistic states: the backend proved applied, proved not applied,
 * or could not prove either. "Could not prove" is a first-class answer, not an error to be retried
 * into a guess — and reconciliation never mutates (§27), which is what makes re-running it safe.
 */
sealed interface ReviewerActionReconciliationResult {

    /**
     * Authoritative evidence that the action was applied. [receipt] is a backend-issued receipt when
     * the contract has one; for AnkiDroid it is the observed post-action card state. Null is honest,
     * never fabricated.
     */
    data class ConfirmedApplied(
        val receipt: ReviewerActionReceipt? = null
    ) : ReviewerActionReconciliationResult

    /** Authoritative evidence that the action was not applied: the card is still where it was. */
    data object ConfirmedNotApplied : ReviewerActionReconciliationResult

    /**
     * The backend could not prove either way: unreachable, unsupported, card missing, timeout, or a
     * state that cannot be attributed to this action. The record stays blocked; nothing is inferred.
     */
    data class Unresolved(val reason: AnkiError? = null) : ReviewerActionReconciliationResult
}

/**
 * GATE 13 §27 — the contract for deciding an unresolved action after the original response was
 * lost.
 *
 * **Read-only**: an implementation must not flag, bury, suspend, rate, load-and-advance the
 * scheduler or mutate a note/card. It gathers evidence only.
 *
 * Identity rules: the backend is resolved from [ReviewerActionRecord.backendId] — the backend the
 * action was locked into when it was created — never from the current global preference; only the
 * record's own card is consulted; a collection mismatch is
 * [ReviewerActionReconciliationResult.Unresolved], never a redirect.
 */
interface ReviewerActionReconciler {
    suspend fun reconcile(record: ReviewerActionRecord): ReviewerActionReconciliationResult
}

/**
 * Default [ReviewerActionReconciler]: dispatches to the backend the action belongs to and maps that
 * backend's read-only evidence into the canonical result.
 *
 * The capability gate uses the semantics **frozen** with the record (§28) capped by what the backend
 * still claims live — a capability can only shrink, never grow. When the effective capability says
 * no authoritative reconciliation exists, no backend call is issued at all: a heuristic (for
 * example "the card is no longer due, so it was probably buried") is never evidence.
 */
class AnkiReviewerActionReconciler(
    private val registry: AnkiBackendRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) : ReviewerActionReconciler {
    init { require(timeoutMs > 0) }

    /** Frozen promise ∩ live backend capability. */
    fun effectiveSupport(record: ReviewerActionRecord, backend: AnkiBackend): Boolean {
        val live = backend.reviewerActionSemantics(record.action).supportsAuthoritativeReconciliation
        return if (record.frozenAuthoritativeReconciliation) live else false
    }

    override suspend fun reconcile(record: ReviewerActionRecord): ReviewerActionReconciliationResult {
        // The backend locked into the original action, not the current preference.
        val backend = registry.find(record.backendId)
            ?: return ReviewerActionReconciliationResult.Unresolved(AnkiError.BackendUnavailable())
        if (record.cardRef.backendId != backend.id ||
            record.collectionRef?.let { it.backendId != backend.id } == true
        ) {
            return ReviewerActionReconciliationResult.Unresolved(AnkiError.SessionInvalid())
        }
        if (!effectiveSupport(record, backend)) {
            // No provider call, no heuristic, no guess: the record stays blocked.
            return ReviewerActionReconciliationResult.Unresolved(
                AnkiError.UnsupportedApi(null, 0, "action_reconciliation_unsupported"))
        }
        val submittedAt = record.submittedAtEpochMs ?: record.createdAtEpochMs
        val windowEnd = maxOf(submittedAt, record.resolvedAtEpochMs ?: clock())
        // Read-only and bounded: a hung provider query expires as "still unknown", never as
        // "not applied" (INV-13-12).
        val evidence: ReviewerActionReconciliationResult? = withTimeoutOrNull(timeoutMs) {
            backend.reconcileReviewerAction(record.toReconcileRequest(windowEnd))
        }
        return evidence ?: ReviewerActionReconciliationResult.Unresolved(
            AnkiError.QueryFailure("action_reconcile_timeout"))
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 10_000L
    }
}

/**
 * Result of `ReviewerActionCoordinator.recover` (§16). `NoTransaction` is explicit rather than a
 * fifth action name: "there is no action" is not a decision about an action.
 */
sealed interface ReviewerActionRecoveryOutcome {

    /** No durable action exists for this id: the normal action flow applies. */
    data class NoTransaction(val actionId: ReviewerActionId) : ReviewerActionRecoveryOutcome

    /**
     * A durable action was found. [decision] is what recovery decided; [outcome] is non-null when
     * recovery also resolved the action without any mutation (durable evidence or read-only
     * reconciliation).
     */
    data class Recovered(
        val decision: ReviewerActionRecoveryAction,
        val record: ReviewerActionRecord,
        val outcome: ReviewerActionOutcome? = null
    ) : ReviewerActionRecoveryOutcome {
        val actionId: ReviewerActionId get() = record.actionId
    }

    /**
     * The durable ledger could not be read or written, so recovery refuses to classify anything.
     * Distinct from [NoTransaction]: unknown is not the same as absent.
     */
    data class Indeterminate(val actionId: ReviewerActionId, val reason: String) :
        ReviewerActionRecoveryOutcome

    /**
     * The ledger contains a structural anomaly a valid ledger never writes (for example two active
     * actions for one turn, §15). Recovery must not choose one heuristically: no retry and no
     * reconciliation is offered for any record.
     */
    data class IntegrityFailure(val reason: String) : ReviewerActionRecoveryOutcome
}
