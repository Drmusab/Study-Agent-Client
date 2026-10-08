package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.ReviewerAction
import com.studyagent.client.core.anki.ReviewerActionKind
import com.studyagent.client.core.anki.ReviewerActionResult
import com.studyagent.client.core.anki.ReviewerCardAvailability
import com.studyagent.client.core.anki.ReviewerCardState
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.AppLogger

/**
 * GATE 13 STEP 9/11/14/33/34/39 — the AnkiDroid reviewer-action protocol.
 *
 * ```text
 *  pre-mutation  1. is the action expressible in the pinned public contract?   (flag: no → Rejected)
 *                2. no provider write in flight?                                (no → Rejected, nothing sent)
 *                3. read the card: does the backend's own semantics apply?     (no → Rejected)
 *                   (already in the desired state → Applied, no write at all)
 *  boundary      4. ONE provider update (buried=1 | suspended=1)
 *  post-mutation 5. immediate card-state read → Applied | Rejected | OutcomeUnknown
 * ```
 *
 * Why step 5 exists: the pinned v2.24.1 provider resolves the card, calls the scheduler and
 * swallows `RuntimeException` while still answering one updated row (`AnkiDroidApiContract`
 * GATE 13 table). A returned row count is therefore never treated as success — the *card's own
 * queue* is the only positive evidence, exactly as the GATE 11 committer uses counters instead of
 * trusting the answer call's return value.
 *
 * Failure semantics per action (STEP 30-33) are answered from the pinned backend rather than
 * assumed, and each action is audited separately:
 *
 * | Action | Retry after Rejected | Retry after OutcomeUnknown | Idempotency |
 * |---|---|---|---|
 * | Flag | n/a — the pinned contract cannot express it (`UnsupportedAction`) | n/a | n/a |
 * | Bury | safe: a bury that provably did not apply leaves the card untouched | **never** (INV-13-13) | second bury is a scheduler-level no-op (queue already `UserBuried`) |
 * | Suspend | safe, same reasoning | **never** | second suspend is a scheduler-level no-op |
 *
 * GATE 13 deliberately does **not** add a `ReviewerActionLedger` (STEP 30): the audit showed that
 * bury/suspend have no transaction identity, no dedup table and no receipt in the public contract,
 * so a ledger would store nothing a later read could correlate. What makes "not applied" provable
 * here is the *synchronous* return plus an immediate read; what makes double-application harmless
 * is the scheduler's own desired-state check.
 */
internal class AnkiDroidReviewerActionCommitter(
    private val gateway: AnkiDroidReviewerActionGateway,
    /** Read-only card-state reads. One state reader in the codebase; it never mutates. */
    private val cardStates: AnkiDroidRatingGateway,
    private val clock: AppClock
) {

    suspend fun perform(
        authority: String,
        card: AnkiCardRef,
        action: ReviewerAction
    ): ReviewerActionResult {
        // 1. Contract check, *before* any provider traffic. The pinned public contract exposes no
        //    flag write (there is not even a flag column to read), so a flag is refused as an
        //    unsupported action instead of pretending to apply it (STEP 9, INV-13-17).
        if (action.kind == ReviewerActionKind.FLAG) {
            return ReviewerActionResult.Rejected(
                AnkiError.UnsupportedAction("flag_unsupported_by_pinned_contract")
            )
        }
        val noteId = card.noteId?.toLongOrNull()
        val cardOrd = card.cardOrd
        if (noteId == null || noteId <= 0L || cardOrd == null) {
            return ReviewerActionResult.Rejected(
                AnkiError.InvalidRequest("action_requires_positive_note_and_ord")
            )
        }
        // 2. A write from an earlier attempt that never returned may still land. This attempt has
        //    not been dispatched, but non-application of the in-flight write is not proven, so the
        //    honest answer is a refusal, never a second write.
        if (gateway.writeInFlight) {
            return ReviewerActionResult.Rejected(AnkiError.QueryFailure("provider_write_busy"))
        }
        val before = when (val read = cardStates.readCardState(authority, card)) {
            is AnkiResult.Success -> read.value
            is AnkiResult.Failure -> return ReviewerActionResult.Rejected(read.error)
        }

        // 3. The backend's own semantics, applied *before* the mutation. The pinned scheduler
        //    refuses to bury a suspended card (it would unsuspend it) and treats a second
        //    bury/suspend as a no-op; both are decided here so the outcome is honest without
        //    spending the single write slot.
        val desiredQueue = when (action.kind) {
            ReviewerActionKind.BURY -> ReviewerCardState.QUEUE_MANUALLY_BURIED
            ReviewerActionKind.SUSPEND -> ReviewerCardState.QUEUE_SUSPENDED
            ReviewerActionKind.FLAG -> return ReviewerActionResult.Rejected(
                AnkiError.UnsupportedAction("flag_unsupported_by_pinned_contract")
            )
        }
        if (before.queue == ReviewerCardState.QUEUE_SUSPENDED && action.kind == ReviewerActionKind.BURY) {
            // rslib: "do not bury suspended cards as that would unsuspend them" — the action is a
            // no-op. Reporting Applied would claim a state change that provably cannot happen;
            // reporting a typed refusal keeps the UI honest and leaves the card untouched.
            return ReviewerActionResult.Rejected(
                AnkiError.ActionNotApplicable("suspended_card_cannot_be_buried")
            )
        }
        if (before.queue == desiredQueue) {
            // Already in the desired state: the scheduler would write nothing (its own
            // `card.queue != desired_queue` check). This is the audited idempotency path — the
            // second identical action cannot create a duplicate mutation because none is sent.
            return ReviewerActionResult.Applied(
                updatedCard = card,
                cardState = ReviewerCardState.fromQueue(before.queue),
                detail = "already_in_desired_state"
            )
        }

        // 4. ONE provider update. No retry, here or below.
        val dispatch = gateway.submitAction(
            authority,
            AnkiDroidActionMutation(noteId, cardOrd, action.kind)
        )

        // 5. Evidence, not trust.
        return classify(authority, card, before.queue, desiredQueue, dispatch)
    }

    private suspend fun classify(
        authority: String,
        card: AnkiCardRef,
        beforeQueue: Int,
        desiredQueue: Int,
        dispatch: AnkiDroidReviewerActionDispatch
    ): ReviewerActionResult = when (dispatch) {
        is AnkiDroidReviewerActionDispatch.NotDispatched ->
            ReviewerActionResult.Rejected(dispatch.error)

        is AnkiDroidReviewerActionDispatch.Unknown -> {
            // Timed out or threw after the call was issued. The mutation may have been applied and
            // may still be applied; only a fresh read could say anything, and even then a
            // still-running call could land afterwards, so nothing is resolved here.
            ReviewerActionResult.OutcomeUnknown(
                reason = AnkiError.Unknown(dispatch.detail),
                detail = dispatch.detail
            )
        }

        is AnkiDroidReviewerActionDispatch.Returned -> when (dispatch.rowCount) {
            // `1` means the update branch reached the action call; it is NOT success.
            1 -> verifyAfterMutation(authority, card, beforeQueue, desiredQueue)
            // The provider matched the note/ord keys but found no card (it logs and skips), or the
            // keys were absent: nothing was mutated.
            0 -> ReviewerActionResult.Rejected(AnkiError.CardNotFound(card))
            // The provider process died mid-call: the write may or may not have landed.
            UPDATE_REMOTE_FAILURE_ROWS -> ReviewerActionResult.OutcomeUnknown(
                reason = AnkiError.ProviderUnavailable(),
                detail = "provider_process_died"
            )
            else -> ReviewerActionResult.OutcomeUnknown(
                reason = AnkiError.MalformedResponse("action_rows_${dispatch.rowCount}"),
                detail = "action_rows_${dispatch.rowCount}"
            )
        }
    }

    /**
     * The immediate post-mutation read. Three verdicts, and only three:
     *
     * - the card is in the desired state → **Applied** (the backend's own state is the evidence);
     * - the card is exactly where it was → **Rejected** (a synchronous return plus an unchanged
     *   card proves the scheduler did not apply it — the swallowed-exception path); the caller may
     *   retry the same action safely;
     * - anything else (identity changed, some *other* state) → **OutcomeUnknown**: the mutation
     *   cannot be attributed to this call, and nothing may be replayed.
     */
    private suspend fun verifyAfterMutation(
        authority: String,
        card: AnkiCardRef,
        beforeQueue: Int,
        desiredQueue: Int
    ): ReviewerActionResult {
        val after = when (val read = cardStates.readCardState(authority, card)) {
            is AnkiResult.Success -> read.value
            is AnkiResult.Failure -> {
                log("ANKI_REVIEWER_ACTION_VERIFICATION_UNAVAILABLE error=${read.error::class.simpleName}")
                return ReviewerActionResult.OutcomeUnknown(
                    reason = AnkiError.Unknown("verification_read_failed"),
                    detail = "verification_read_failed"
                )
            }
        }
        return when (after.queue) {
            desiredQueue -> {
                log("ANKI_REVIEWER_ACTION_VERIFIED queue=$desiredQueue")
                ReviewerActionResult.Applied(
                    updatedCard = card,
                    cardState = ReviewerCardState.fromQueue(after.queue),
                    detail = "confirmed_by_card_state"
                )
            }
            beforeQueue -> {
                log("ANKI_REVIEWER_ACTION_NOT_APPLIED queue=$beforeQueue")
                ReviewerActionResult.Rejected(
                    AnkiError.QueryFailure("action_not_applied_scheduler_refused")
                )
            }
            else -> {
                val availability = ReviewerCardState.fromQueue(after.queue).availability
                log("ANKI_REVIEWER_ACTION_UNATTRIBUTABLE availability=$availability")
                ReviewerActionResult.OutcomeUnknown(
                    reason = AnkiError.Unknown("card_state_changed_after_action"),
                    detail = "card_state_changed_after_action"
                )
            }
        }
    }

    private fun log(message: String) = AppLogger.i(TAG, message)

    private companion object {
        const val TAG = "AnkiDroidActionCommitter"

        /** `ContentResolver.update` returns this when the provider process died mid-call. */
        const val UPDATE_REMOTE_FAILURE_ROWS: Int = -1
    }
}
