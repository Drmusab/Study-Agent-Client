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
        return when (record.status) {
            ReviewCommitStatus.PREPARED -> ReviewCommitRecoveryAction.OfferRetry
            ReviewCommitStatus.SUBMITTING -> ReviewCommitRecoveryAction.Reconcile
            ReviewCommitStatus.RETRY_ALLOWED -> ReviewCommitRecoveryAction.OfferRetry
            ReviewCommitStatus.AMBIGUOUS -> ReviewCommitRecoveryAction.Reconcile
            ReviewCommitStatus.COMMITTED -> ReviewCommitRecoveryAction.ResumeCommitted
        }
    }
}
