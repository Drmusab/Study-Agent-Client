package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.BackendCommitResult
import com.studyagent.client.core.anki.MutationBoundary

/**
 * The single AnkiDroid adapter failure-classification boundary.
 *
 * A provider-facing method must not decide retry policy from an exception class. It tells this
 * object whether the answer update was still on the PREPARED side of the boundary or whether the
 * provider update had been entered. This object then produces the only three backend facts the
 * coordinator accepts:
 *
 * * [BackendCommitResult.ConfirmedNotCommitted] means this attempt was refused before the answer
 *   update was dispatched;
 * * [BackendCommitResult.ConfirmedCommitted] means the synchronous return and the immediate,
 *   documented scheduler-state observation are consistent with one normal answer;
 * * [BackendCommitResult.OutcomeUnknown] is the fail-closed result after an entered update when
 *   the provider response or observation cannot prove the effect.
 *
 * Keep this mapping here. Do not add a second exception-to-result table in the Android provider
 * client, rating gateway, backend or coordinator.
 */
internal object AnkiDroidCommitResultClassifier {

    /** A refusal before the scheduler answer update: no rating mutation was dispatched. */
    fun beforeMutation(error: AnkiError): BackendCommitResult =
        BackendCommitResult.ConfirmedNotCommitted(error)

    /** An entered answer update with no transaction-correlated proof. */
    fun afterMutation(detail: String, error: AnkiError? = null): BackendCommitResult =
        BackendCommitResult.OutcomeUnknown(error ?: AnkiError.Unknown(detail))

    /**
     * Classifies an unexpected adapter exception at the known boundary. Exception text and the
     * native exception object never cross the gateway; only a stable, content-free token does.
     */
    fun unexpected(boundary: MutationBoundary, detail: String = "adapter_exception"): BackendCommitResult =
        when (boundary) {
            MutationBoundary.BEFORE_CALL -> beforeMutation(AnkiError.Unknown("pre_mutation_$detail"))
            MutationBoundary.AFTER_CALL_ENTERED -> afterMutation("post_mutation_$detail")
        }

    /** Maps a provider dispatch that did not return a normal result. */
    fun dispatch(dispatch: AnkiDroidAnswerDispatch): BackendCommitResult? = when (dispatch) {
        is AnkiDroidAnswerDispatch.NotDispatched -> beforeMutation(dispatch.error)
        is AnkiDroidAnswerDispatch.Unknown -> afterMutation(dispatch.detail)
        is AnkiDroidAnswerDispatch.Returned -> null
    }

    /**
     * A row count is not enough at the pinned API: AnkiDroid can swallow a scheduler exception and
     * still return one row. Only the normal row count plus the immediate, single-card state
     * observation may take the synchronous success path. A filtered-deck preview is conservative
     * because its public state transition does not expose the normal `reps` evidence.
     */
    fun synchronousAnswer(
        dispatch: AnkiDroidAnswerDispatch.Returned,
        verdict: AnkiDroidCommitVerifier.Verdict,
        inFilteredDeck: Boolean
    ): BackendCommitResult {
        if (dispatch.rowCount != AnkiDroidApiContract.REVIEW_ANSWER_REACHED_ROWS) {
            return afterMutation("answer_outcome_unavailable")
        }
        return when (verdict) {
            is AnkiDroidCommitVerifier.Verdict.ConsistentWithAnswer ->
                if (inFilteredDeck) afterMutation("filtered_deck_result_unverified")
                else BackendCommitResult.ConfirmedCommitted()

            is AnkiDroidCommitVerifier.Verdict.Unchanged,
            is AnkiDroidCommitVerifier.Verdict.Unattributable ->
                afterMutation("answer_not_confirmed")
        }
    }
}
