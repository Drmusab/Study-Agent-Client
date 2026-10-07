package com.studyagent.client.core.anki

/**
 * Answers "may we retry?" from "can we prove the scheduler mutation did not happen?", never from
 * "did an exception occur?".
 *
 * | Failure | Before mutation boundary | After mutation boundary |
 * |---|---|---|
 * | Validation | proven no effect (rejected) | not applicable |
 * | Backend unavailable | safe if the call was not entered | ambiguous if entered |
 * | Permission denied | safe if pre-call | ambiguous after entry (provider-specific) |
 * | Timeout | not applicable | ambiguous |
 * | Process cancellation | safe before the call | ambiguous after entry |
 * | Binder / IPC | safe before the call | ambiguous after entry |
 * | Provider explicit rejection | safe only if the provider documents no mutation | ambiguous after entry |
 * | Storage failure | block before the call | fail closed; do not advance |
 *
 * AnkiDroid's own committer may be stricter than this table. It must not be looser.
 */
enum class MutationBoundary { BEFORE_CALL, AFTER_CALL_ENTERED }

enum class CommitFailureKind {
    VALIDATION,
    BACKEND_UNAVAILABLE,
    PERMISSION_DENIED,
    TIMEOUT,
    CANCELLATION,
    BINDER_IPC,
    PROVIDER_REJECTION,
    STORAGE,
    UNCLASSIFIED
}

fun classifyCommitFailure(kind: CommitFailureKind, boundary: MutationBoundary): BackendCommitResult {
    if (boundary == MutationBoundary.AFTER_CALL_ENTERED) {
        return when (kind) {
            CommitFailureKind.STORAGE -> BackendCommitResult.OutcomeUnknown(AnkiError.CommitLedgerUnavailable())
            CommitFailureKind.VALIDATION -> BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("validation_after_entry"))
            else -> BackendCommitResult.OutcomeUnknown(AnkiError.Unknown(kind.name.lowercase()))
        }
    }
    return when (kind) {
        // Proven not applied, whether the refusal is transient or permanent: the canonical status
        // set has one "proven not applied" status and re-submitting a refused rating is safe.
        CommitFailureKind.VALIDATION, CommitFailureKind.PROVIDER_REJECTION ->
            BackendCommitResult.ConfirmedNotCommitted(AnkiError.InvalidRequest(kind.name.lowercase()))
        CommitFailureKind.STORAGE ->
            BackendCommitResult.ConfirmedNotCommitted(AnkiError.CommitLedgerUnavailable())
        CommitFailureKind.TIMEOUT ->
            BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("timeout_without_boundary"))
        else -> BackendCommitResult.ConfirmedNotCommitted(AnkiError.Unknown(kind.name.lowercase()))
    }
}
