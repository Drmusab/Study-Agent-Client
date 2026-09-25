package com.studyagent.client.core.study

import com.studyagent.client.core.anki.ReviewCommitState
import com.studyagent.client.core.models.Rating

/** Pure presentation model. Buttons are never the transaction safety mechanism. */
data class RatingCommitRecoveryUi(
    val status: Status,
    val rating: Rating?,
    val title: String,
    val message: String,
    /** Only a durable, proven-not-applied attempt with its original live turn may be retried. */
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
    enum class Status { SAVING, NOT_SAVED, UNCONFIRMED, CHECKING, PERSISTENCE_FAULT, SAVED, EARLIER_UNCONFIRMED }

    companion object {
        fun from(machine: SessionMachineState): RatingCommitRecoveryUi? {
            val local = machine.anki ?: return null
            val commit = local.commit
            val rating = commit?.rating
            val label = rating?.displayName ?: ""
            return when {
                commit != null && machine.phase is SessionPhase.CommitPersistenceFailure ->
                    RatingCommitRecoveryUi(Status.PERSISTENCE_FAULT, rating, "Review record could not be saved",
                        "Study-Agent could not safely record the review result. It will not submit the rating " +
                            "again or load another card. Check the record again or end the session.",
                        canRetry = false, canCheckAgain = !commit.reconciling, canEndSession = true,
                        canOpenAnkiDroid = true, canViewDiagnostics = true)

                commit != null && local.restoredCommit && machine.phase is SessionPhase.RatingCommitFailed ->
                    RatingCommitRecoveryUi(Status.NOT_SAVED, rating, "Review interrupted",
                        "The original review turn cannot be resumed after restart. Study-Agent will not " +
                            "send this rating again. End this session, then start a new review if needed.",
                        canRetry = false, canCheckAgain = false, canEndSession = true,
                        canOpenAnkiDroid = true, canViewDiagnostics = true)

                commit != null && commit.isPending && machine.phase is SessionPhase.SubmittingRating ->
                    RatingCommitRecoveryUi(Status.SAVING, rating, "Saving rating",
                        "Saving \u201c$label\u201d to Anki\u2026",
                        canRetry = false, canCheckAgain = false, canEndSession = true)

                commit != null && commit.state == ReviewCommitState.FAILED &&
                    machine.phase is SessionPhase.RatingCommitFailed -> {
                    val retryable = commit.safeToRetry && local.turn != null && !local.restoredCommit
                    RatingCommitRecoveryUi(Status.NOT_SAVED, rating, "Rating not saved",
                        if (retryable) "Could not save the rating. Nothing was changed in Anki. " +
                            "Retry is safe for \u201c$label\u201d."
                        else "Anki did not accept \u201c$label\u201d. Nothing was changed. End the session.",
                        canRetry = retryable, canCheckAgain = false, canEndSession = true,
                        canViewDiagnostics = !retryable)
                }

                commit != null && commit.state == ReviewCommitState.AMBIGUOUS &&
                    machine.phase is SessionPhase.ReconciliationRequired ->
                    if (commit.reconciling) RatingCommitRecoveryUi(
                        Status.CHECKING, rating, "Checking Anki",
                        "Checking whether Anki saved \u201c$label\u201d\u2026",
                        canRetry = false, canCheckAgain = false, canEndSession = true
                    ) else RatingCommitRecoveryUi(
                        Status.UNCONFIRMED, rating, "Review status uncertain",
                        "The rating may already have been saved. Study-Agent will not " +
                            "submit it again until the review state is verified.",
                        canRetry = false, canCheckAgain = true, canEndSession = true,
                        canOpenAnkiDroid = true, canViewDiagnostics = true
                    )

                commit != null && commit.state == ReviewCommitState.COMMITTED &&
                    machine.phase is SessionPhase.WaitingForFirstCard ->
                    if (commit.verifiedByReconciliation) RatingCommitRecoveryUi(
                        Status.SAVED, rating, "Rating verified as saved",
                        "Rating verified as saved. Loading the next scheduled card\u2026",
                        canRetry = false, canCheckAgain = false, canEndSession = true
                    ) else RatingCommitRecoveryUi(Status.SAVED, rating, "Rating saved",
                        "Loading the next scheduled card\u2026",
                        canRetry = false, canCheckAgain = false, canEndSession = true)

                commit == null && local.priorUnresolvedCommits > 0 && machine.phase.isActive ->
                    RatingCommitRecoveryUi(Status.EARLIER_UNCONFIRMED, null, "Earlier rating not confirmed",
                        "${local.priorUnresolvedCommits} earlier rating(s) remain unresolved in another " +
                            "session or collection. They will not be re-sent.",
                        canRetry = false, canCheckAgain = false, canEndSession = false,
                        ratingControlsEnabled = true, canOpenAnkiDroid = true, canViewDiagnostics = true)
                else -> null
            }
        }
    }
}
