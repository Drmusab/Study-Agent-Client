package com.studyagent.client.core.anki

/**
 * GATE 11B §25 — recovery **decisions**. No UI strings, no scheduler reads.
 *
 * These names are deliberately not [ReviewCommitStatus] names, so a log line never has to guess
 * which layer it belongs to (INV-11B-14):
 *
 * ```text
 * status = RETRY_ALLOWED   → durable transaction truth
 * action = OfferRetry      → what recovery decided to do about it
 * ```
 */
sealed interface ReviewCommitRecoveryAction {
    /** Stable, content-free name for diagnostics (`GATE 11B PART V`). */
    val label: String get() = this::class.simpleName ?: "unknown"

    /** Durable success. Resume the study flow; never replay the mutation. */
    data object ResumeCommitted : ReviewCommitRecoveryAction

    /** The backend mutation is proven not to have been entered or not to have happened. */
    data object OfferRetry : ReviewCommitRecoveryAction

    /** The outcome is unknown: gather read-only backend evidence before anything else. */
    data object Reconcile : ReviewCommitRecoveryAction

    /** Nothing may be done yet: stay blocked and keep the user informed. */
    data object RemainBlocked : ReviewCommitRecoveryAction

    /** The durable record contradicts itself. Never guess, never replay. */
    data class IntegrityFailure(val reason: String) : ReviewCommitRecoveryAction
}

/**
 * Result of [ReviewCommitCoordinator.recover]. `NoTransaction` is explicit rather than a fifth
 * action name: "there is no transaction" is not a decision about a transaction (GATE 11B §45,
 * first row).
 */
sealed interface ReviewCommitRecoveryResult {
    /** No durable transaction exists for this commit id: the normal rating flow applies. */
    data class NoTransaction(val commitId: ReviewCommitId) : ReviewCommitRecoveryResult

    /**
     * A durable transaction was found. [action] is what recovery decided; [outcome] is non-null
     * when recovery also resolved the transaction without any mutation (durable evidence or
     * read-only reconciliation).
     */
    data class Recovered(
        val action: ReviewCommitRecoveryAction,
        val record: ReviewCommitRecord,
        val outcome: ReviewCommitOutcome? = null
    ) : ReviewCommitRecoveryResult {
        val commitId: ReviewCommitId get() = record.commitId
    }

    /**
     * The durable ledger could not be read or written, so recovery refuses to classify anything.
     * Distinct from [NoTransaction]: unknown is not the same as absent.
     */
    data class Indeterminate(val commitId: ReviewCommitId, val reason: String) : ReviewCommitRecoveryResult

    /**
     * GATE 11D §30 — the durable ledger contains a structural anomaly a valid ledger never
     * writes (for example two unresolved commits for one active study session). Recovery must
     * not choose one heuristically: no commit, no retry, no reconciliation is offered for any
     * record, and the affected backend is refused until the anomaly is resolved.
     */
    data class IntegrityFailure(val reason: String) : ReviewCommitRecoveryResult
}

/**
 * The normative recovery decision table (GATE 11B §45). Safety classification reads the durable
 * [ReviewCommitStatus] only — never `status + phase` (GATE 11B §27).
 *
 * | Durable status | Meaning | Action |
 * |---|---|---|
 * | no record | no transaction | normal rating flow (`NoTransaction`) |
 * | `PREPARED` | backend not entered | `OfferRetry` |
 * | `SUBMITTING` | backend may have mutated | `Reconcile` |
 * | `RETRY_ALLOWED` | proven no mutation | `OfferRetry` |
 * | `AMBIGUOUS` | outcome unknown | `Reconcile` |
 * | `COMMITTED` | durable success | `ResumeCommitted` |
 */
class ReviewCommitRecoveryPolicy {

    /**
     * [proof] is authoritative read-only reconciliation evidence. It is only ever meaningful for an
     * [ReviewCommitStatus.AMBIGUOUS] record; supplying it for any other status is an integrity
     * failure, never a shortcut to a decision.
     */
    fun classify(record: ReviewCommitRecord, proof: ReconcileCommitResult? = null): ReviewCommitRecoveryAction {
        if (record.status == ReviewCommitStatus.AMBIGUOUS && proof != null) return when (proof) {
            is ReconcileCommitResult.Applied -> ReviewCommitRecoveryAction.ResumeCommitted
            is ReconcileCommitResult.NotApplied -> ReviewCommitRecoveryAction.OfferRetry
            is ReconcileCommitResult.StillAmbiguous,
            is ReconcileCommitResult.Unsupported,
            is ReconcileCommitResult.Unavailable -> ReviewCommitRecoveryAction.RemainBlocked
        }
        if (proof != null) return ReviewCommitRecoveryAction.IntegrityFailure("proof_for_${record.status.name.lowercase()}")
        return classifyStatus(record.status)
    }

    /**
     * The normative recovery table as a total function of the durable status alone
     * (GATE 11B §45 / PART IV).
     *
     * Safety never reads [ReviewCommitPhase]: `SUBMITTING` means the mutation boundary was entered,
     * `PREPARED` means it provably was not. Callers that hold only a status — diagnostics, the
     * study projection, the recovery-matrix tests — use this instead of re-deriving the table, so
     * there is exactly one recovery policy in the codebase (brief PART I PHASE 12: pure and deterministic). It is pure: same status,
     * same decision, no clock, no I/O.
     *
     * | Durable status | Action | Why |
     * |---|---|---|
     * | `PREPARED` | `OfferRetry` | provably un-entered |
     * | `SUBMITTING` | `Reconcile` | boundary entered; the outcome is unknown until proven |
     * | `RETRY_ALLOWED` | `OfferRetry` | proven not applied |
     * | `AMBIGUOUS` | `Reconcile` | unknown; read-only evidence may resolve it |
     * | `COMMITTED` | `ResumeCommitted` | terminal; never replayed |
     */
    fun classifyStatus(status: ReviewCommitStatus): ReviewCommitRecoveryAction = when (status) {
        ReviewCommitStatus.PREPARED -> ReviewCommitRecoveryAction.OfferRetry
        ReviewCommitStatus.SUBMITTING -> ReviewCommitRecoveryAction.Reconcile
        ReviewCommitStatus.RETRY_ALLOWED -> ReviewCommitRecoveryAction.OfferRetry
        ReviewCommitStatus.AMBIGUOUS -> ReviewCommitRecoveryAction.Reconcile
        ReviewCommitStatus.COMMITTED -> ReviewCommitRecoveryAction.ResumeCommitted
    }
}
