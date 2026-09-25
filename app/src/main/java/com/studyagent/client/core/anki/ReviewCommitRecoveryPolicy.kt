package com.studyagent.client.core.anki

/** No UI strings or scheduler reads: the single decision table for restart and reconciliation. */
sealed interface CommitRecoveryAction {
    data object RetryAllowed : CommitRecoveryAction
    data object ResumeCommitted : CommitRecoveryAction
    data object ReconciliationRequired : CommitRecoveryAction
    data object BlockedUnresolved : CommitRecoveryAction
    data object IntegrityError : CommitRecoveryAction
}

class ReviewCommitRecoveryPolicy {
    fun classify(record: ReviewCommitRecord, proof: ReconcileCommitResult? = null): CommitRecoveryAction {
        if (proof != null && record.state != ReviewCommitState.AMBIGUOUS) return CommitRecoveryAction.IntegrityError
        if (record.state == ReviewCommitState.AMBIGUOUS && proof != null) return when (proof) {
            is ReconcileCommitResult.Applied -> CommitRecoveryAction.ResumeCommitted
            is ReconcileCommitResult.NotApplied -> if (proof.safeToRetry) CommitRecoveryAction.RetryAllowed
                else CommitRecoveryAction.BlockedUnresolved
            is ReconcileCommitResult.StillAmbiguous, is ReconcileCommitResult.Unsupported,
            is ReconcileCommitResult.Unavailable -> CommitRecoveryAction.BlockedUnresolved
        }
        return when (record.state) {
            ReviewCommitState.NOT_STARTED -> if (record.phase == null && record.attemptCount == 0)
                CommitRecoveryAction.RetryAllowed else CommitRecoveryAction.IntegrityError
            ReviewCommitState.SUBMITTING -> when (record.phase) {
                CommitAttemptPhase.PREPARED -> CommitRecoveryAction.RetryAllowed
                CommitAttemptPhase.MUTATION_CALL_ENTERED -> CommitRecoveryAction.ReconciliationRequired
                CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED -> when (record.response?.kind) {
                    CommitResponseKind.CONFIRMED_COMMITTED -> CommitRecoveryAction.ResumeCommitted
                    CommitResponseKind.CONFIRMED_NOT_APPLIED -> if (record.response.failure?.safeToRetry == true)
                        CommitRecoveryAction.RetryAllowed else CommitRecoveryAction.BlockedUnresolved
                    CommitResponseKind.OUTCOME_UNKNOWN -> CommitRecoveryAction.ReconciliationRequired
                    null -> CommitRecoveryAction.IntegrityError
                }
                CommitAttemptPhase.LOCAL_RESULT_PERSISTED -> CommitRecoveryAction.IntegrityError
                // A v1 SUBMITTING row must be migrated to CALL_ENTERED, never guessed PREPARED.
                null -> CommitRecoveryAction.ReconciliationRequired
            }
            ReviewCommitState.COMMITTED -> if (record.phase == CommitAttemptPhase.LOCAL_RESULT_PERSISTED ||
                record.phase == null) CommitRecoveryAction.ResumeCommitted else CommitRecoveryAction.IntegrityError
            ReviewCommitState.FAILED -> if (record.safeToRetry) CommitRecoveryAction.RetryAllowed
                else CommitRecoveryAction.BlockedUnresolved
            ReviewCommitState.AMBIGUOUS -> CommitRecoveryAction.BlockedUnresolved
        }
    }
}
