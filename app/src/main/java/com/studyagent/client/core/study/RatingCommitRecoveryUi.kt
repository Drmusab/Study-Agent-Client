package com.studyagent.client.core.study

import com.studyagent.client.core.anki.ReviewCommitStatus
import com.studyagent.client.core.models.Rating

/**
 * Pure presentation model for the rating-commit recovery surface.
 *
 * [commitUiState] is the canonical GATE 11B §13 projection; [title]/[message] are the copy shown
 * for it and [canRetry]/[canCheckAgain]/… are the affordances. Buttons are never the transaction
 * safety mechanism: the ledger refuses an illegal retry even if the UI is wrong.
 */
data class RatingCommitRecoveryUi(
    val commitUiState: RatingCommitUiState,
    val rating: Rating?,
    val title: String,
    val message: String,
    /** Only a durable, proven-not-applied transaction with its original live turn may be retried. */
    val canRetry: Boolean,
    /** Evidence gathering / retrying a *ledger write*, never re-rating the card. */
    val canCheckAgain: Boolean,
    val canEndSession: Boolean,
    val ratingControlsEnabled: Boolean = false,
    /** Navigational only. Opening AnkiDroid does not resolve the transaction. */
    val canOpenAnkiDroid: Boolean = false,
    /** Navigational only. Diagnostics do not resubmit the rating. */
    val canViewDiagnostics: Boolean = false
) {

    companion object {
        /**
         * Derives the surface from the durable status the machine knows about.
         *
         * `CommitPersistenceFailure` shows [RatingCommitUiState.VerificationRequired] with its own
         * copy: the durability of the outcome is exactly what could not be established, so from
         * the user's point of view it is the same required action as AMBIGUOUS — verify, do not
         * retry — and it is never presented as "saved" or "not saved".
         */
        fun from(machine: SessionMachineState): RatingCommitRecoveryUi? {
            val local = machine.anki ?: return null
            val commit = local.commit
            val rating = commit?.rating
            val label = rating?.displayName ?: ""
            return when {
                commit != null && machine.phase is SessionPhase.CommitPersistenceFailure ->
                    RatingCommitRecoveryUi(
                        RatingCommitUiState.VerificationRequired(commit.commitId), rating,
                        "Review record could not be saved",
                        "Study-Agent could not safely record the review result. It will not submit the rating " +
                            "again or load another card. Check the record again or end the session.",
                        canRetry = false, canCheckAgain = !commit.reconciling, canEndSession = true,
                        canOpenAnkiDroid = true, canViewDiagnostics = true)

                commit != null && local.restoredCommit && machine.phase is SessionPhase.RatingCommitFailed ->
                    RatingCommitRecoveryUi(
                        RatingCommitUiState.VerificationRequired(commit.commitId), rating,
                        "Review interrupted",
                        "The original review turn cannot be resumed after restart. Study-Agent will not " +
                            "send this rating again. End this session, then start a new review if needed.",
                        canRetry = false, canCheckAgain = false, canEndSession = true,
                        canOpenAnkiDroid = true, canViewDiagnostics = true)

                commit != null && commit.isPending && machine.phase is SessionPhase.SubmittingRating ->
                    RatingCommitRecoveryUi(
                        RatingCommitUiState.Saving(commit.commitId), rating, "Saving rating",
                        "Saving \u201c$label\u201d to Anki\u2026",
                        canRetry = false, canCheckAgain = false, canEndSession = true)

                commit != null && commit.status == ReviewCommitStatus.RETRY_ALLOWED &&
                    machine.phase is SessionPhase.RatingCommitFailed -> {
                    val retryable = local.turn != null && !local.restoredCommit
                    RatingCommitRecoveryUi(
                        RatingCommitUiState.RetryAvailable(commit.commitId), rating, "Rating not saved",
                        if (retryable) "Could not save the rating. Nothing was changed in Anki. " +
                            "Retry is safe for \u201c$label\u201d."
                        else "Anki did not save \u201c$label\u201d. Nothing was changed. End this session, " +
                            "then start a new review if needed.",
                        canRetry = retryable, canCheckAgain = false, canEndSession = true,
                        canViewDiagnostics = !retryable)
                }

                commit != null && commit.status == ReviewCommitStatus.AMBIGUOUS &&
                    machine.phase is SessionPhase.ReconciliationRequired ->
                    if (commit.reconciling) RatingCommitRecoveryUi(
                        RatingCommitUiState.VerificationRequired(commit.commitId), rating, "Checking Anki",
                        "Checking whether Anki saved \u201c$label\u201d\u2026",
                        canRetry = false, canCheckAgain = false, canEndSession = true
                    ) else RatingCommitRecoveryUi(
                        RatingCommitUiState.VerificationRequired(commit.commitId), rating,
                        "Review status uncertain",
                        "The rating may already have been saved. Study-Agent will not " +
                            "submit it again until the review state is verified.",
                        canRetry = false, canCheckAgain = true, canEndSession = true,
                        canOpenAnkiDroid = true, canViewDiagnostics = true
                    )

                commit != null && commit.status == ReviewCommitStatus.COMMITTED &&
                    machine.phase is SessionPhase.WaitingForFirstCard ->
                    if (commit.verifiedByReconciliation) RatingCommitRecoveryUi(
                        RatingCommitUiState.Saved(commit.commitId, commit.rating), rating,
                        "Rating verified as saved",
                        "Rating verified as saved. Loading the next scheduled card\u2026",
                        canRetry = false, canCheckAgain = false, canEndSession = true
                    ) else RatingCommitRecoveryUi(
                        RatingCommitUiState.Saved(commit.commitId, commit.rating), rating, "Rating saved",
                        "Loading the next scheduled card\u2026",
                        canRetry = false, canCheckAgain = false, canEndSession = true)

                commit == null && local.priorUnresolvedCommits > 0 && machine.phase.isActive ->
                    RatingCommitRecoveryUi(
                        RatingCommitUiState.AwaitingRating, null, "Earlier rating not confirmed",
                        "${local.priorUnresolvedCommits} earlier rating(s) remain unresolved in another " +
                            "session or collection. They will not be re-sent.",
                        canRetry = false, canCheckAgain = false, canEndSession = false,
                        ratingControlsEnabled = true, canOpenAnkiDroid = true, canViewDiagnostics = true)
                else -> null
            }
        }
    }
}
