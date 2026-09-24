package com.studyagent.client.core.study

import com.studyagent.client.core.anki.ReviewCommitState
import com.studyagent.client.core.models.Rating

/**
 * GATE 11 — backend-neutral presentation model for the current rating transaction (STEP 60-§62,
 * §91-§94). Derived purely from [SessionMachineState]; the UI renders it and dispatches intents. It
 * never inspects which backend is active and never offers a blind re-rate: while a rating is being
 * saved, not saved, or unconfirmed, the rating controls stay disabled.
 */
data class RatingCommitRecoveryUi(
    val status: Status,
    /** The rating being saved / not saved / unconfirmed; `null` only for [Status.EARLIER_UNCONFIRMED]. */
    val rating: Rating?,
    val title: String,
    val message: String,
    /** Retry the *same* commit (same id, same rating). Only when the failure is proven safe. */
    val canRetry: Boolean,
    /** Ask the backend for reconciliation evidence. Only for an unconfirmed (AMBIGUOUS) rating. */
    val canCheckAgain: Boolean,
    /** Ending the session is always available while a rating transaction is unresolved. */
    val canEndSession: Boolean,
    /** False while this session's transaction is pending or unresolved (duplicate-input safety). */
    val ratingControlsEnabled: Boolean = false
) {
    enum class Status { SAVING, NOT_SAVED, UNCONFIRMED, CHECKING, EARLIER_UNCONFIRMED }

    companion object {
        fun from(machine: SessionMachineState): RatingCommitRecoveryUi? {
            val local = machine.anki ?: return null
            val commit = local.commit
            val rating = commit?.rating
            val label = rating?.displayName ?: ""
            return when {
                commit != null && commit.isPending && machine.phase is SessionPhase.SubmittingRating ->
                    RatingCommitRecoveryUi(
                        Status.SAVING, rating, "Saving rating",
                        "Saving \u201c$label\u201d to Anki\u2026",
                        canRetry = false, canCheckAgain = false, canEndSession = true
                    )

                commit != null && commit.state == ReviewCommitState.FAILED &&
                    machine.phase is SessionPhase.RatingCommitFailed ->
                    RatingCommitRecoveryUi(
                        Status.NOT_SAVED, rating, "Rating not saved",
                        if (commit.safeToRetry) "Anki did not save \u201c$label\u201d. Nothing was changed. " +
                            "You can retry the same rating or end the session."
                        else "Anki did not accept \u201c$label\u201d. Nothing was changed. End the session; " +
                            "Anki will show the card again when it is due.",
                        canRetry = commit.safeToRetry, canCheckAgain = false, canEndSession = true
                    )

                commit != null && commit.state == ReviewCommitState.AMBIGUOUS &&
                    machine.phase is SessionPhase.ReconciliationRequired ->
                    if (commit.reconciling) RatingCommitRecoveryUi(
                        Status.CHECKING, rating, "Checking Anki",
                        "Checking whether Anki saved \u201c$label\u201d\u2026",
                        canRetry = false, canCheckAgain = false, canEndSession = true
                    ) else RatingCommitRecoveryUi(
                        Status.UNCONFIRMED, rating, "Rating not confirmed",
                        "Study-Agent cannot confirm whether Anki saved \u201c$label\u201d, so it will not send it " +
                            "again. Check again, or end the session \u2014 a new session asks Anki's scheduler " +
                            "which card is next.",
                        canRetry = false, canCheckAgain = true, canEndSession = true
                    )

                commit == null && local.priorUnresolvedCommits > 0 && machine.phase.isActive ->
                    RatingCommitRecoveryUi(
                        Status.EARLIER_UNCONFIRMED, null, "Earlier rating not confirmed",
                        "${local.priorUnresolvedCommits} earlier rating(s) could not be confirmed. They will " +
                            "not be re-sent; Anki's scheduler decides which card comes next.",
                        canRetry = false, canCheckAgain = false, canEndSession = false,
                        // Informational only: the current turn is rated normally.
                        ratingControlsEnabled = true
                    )

                else -> null
            }
        }
    }
}
