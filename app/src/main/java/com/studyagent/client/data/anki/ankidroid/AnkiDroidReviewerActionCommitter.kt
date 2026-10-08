package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.ReviewerAction
import com.studyagent.client.core.anki.ReviewerActionBackendResult
import com.studyagent.client.core.anki.ReviewerActionKind
import com.studyagent.client.core.anki.ReviewerActionReceipt
import com.studyagent.client.core.anki.ReviewerActionReconciliationResult
import com.studyagent.client.core.anki.ReviewerCardState
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.AppLogger

/**
 * GATE 13 §9/§28/§30 — the AnkiDroid reviewer-action protocol.
 *
 * ```text
 *  pre-mutation  1. is the action expressible in the pinned public contract?   (flag: no → not applied)
 *                2. no provider write in flight?                                (no → not applied, nothing sent)
 *                3. read the card: does the backend's own semantics apply?     (no → not applied)
 *  boundary      4. the durable SUBMITTING marker (the executor's callback; never reached early)
 *                5. ONE provider update (buried=1 | suspended=1)
 *  post-mutation 6. immediate card-state read → Applied | NotApplied | OutcomeUnknown
 * ```
 *
 * Why step 6 exists: the pinned v2.24.1 provider resolves the card, calls the scheduler and
 * swallows `RuntimeException` while still answering one updated row (`AnkiDroidApiContract` GATE 13
 * table). A returned row count is therefore never treated as success — the *card's own queue* is the
 * only positive evidence, exactly as the GATE 11 committer uses counters instead of trusting the
 * answer call's return value.
 *
 * Failure semantics per action are answered from the pinned backend rather than assumed, and each
 * action is audited separately (§28):
 *
 * | Action | Retry after `ConfirmedNotApplied` | Retry after `OutcomeUnknown` | Verified semantics |
 * |---|---|---|---|
 * | Flag | n/a — the pinned contract cannot express it (`UnsupportedAction`) | n/a | unverified |
 * | Bury | safe: a bury that provably did not apply leaves the card untouched | **never** (INV-13-12) | idempotent replay (desired-state check), authoritative reconciliation (card-state read) |
 * | Suspend | safe, same reasoning | **never** | idempotent replay (desired-state check), authoritative reconciliation (card-state read) |
 *
 * A *durable* ledger sits above this class (GATE 13 §14): this committer never writes transaction
 * truth, it only reports what the backend did. The ledger is what makes the same decision survive a
 * process death (§30) — nothing here is remembered between calls.
 */
internal class AnkiDroidReviewerActionCommitter(
    private val gateway: AnkiDroidReviewerActionGateway,
    /** Read-only card-state reads. One state reader in the codebase; it never mutates. */
    private val cardStates: AnkiDroidRatingGateway,
    private val clock: AppClock,
    private val backendId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal
) {

    /**
     * [mutationEntry] is the durable boundary callback (GATE 13 §17). It is called exactly once,
     * immediately before the single provider write that mutates the card — and, for the verified
     * no-op path, immediately before reporting the already-desired state — so the coordinator's
     * `SUBMITTING` write is always durable first (INV-13-08).
     */
    suspend fun perform(
        authority: String,
        card: AnkiCardRef,
        action: ReviewerAction,
        mutationEntry: suspend () -> Boolean
    ): ReviewerActionBackendResult {
        // 1. Contract check, *before* any provider traffic. The pinned public contract exposes no
        //    flag write (there is not even a flag column to read), so a flag is refused as an
        //    unsupported action instead of pretending to apply it (§28, INV-13-16).
        if (action.kind == ReviewerActionKind.FLAG) {
            return ReviewerActionBackendResult.ConfirmedNotApplied(
                AnkiError.UnsupportedAction("flag_unsupported_by_pinned_contract")
            )
        }
        val noteId = card.noteId?.toLongOrNull()
        val cardOrd = card.cardOrd
        if (noteId == null || noteId <= 0L || cardOrd == null) {
            return ReviewerActionBackendResult.ConfirmedNotApplied(
                AnkiError.InvalidRequest("action_requires_positive_note_and_ord")
            )
        }
        // 2. A write from an earlier attempt that never returned may still land. This attempt has
        //    not been dispatched, but non-application of the in-flight write is not proven, so the
        //    honest answer is a refusal, never a second write.
        if (gateway.writeInFlight) {
            return ReviewerActionBackendResult.ConfirmedNotApplied(
                AnkiError.QueryFailure("provider_write_busy"))
        }
        // 3. Read-only preflight: still on the PREPARED side of the boundary.
        val before = when (val read = cardStates.readCardState(authority, card)) {
            is AnkiResult.Success -> read.value
            is AnkiResult.Failure -> return ReviewerActionBackendResult.ConfirmedNotApplied(read.error)
        }
        val desiredQueue = desiredQueueFor(action)
            ?: return ReviewerActionBackendResult.ConfirmedNotApplied(
                AnkiError.UnsupportedAction(action.key))
        if (before.queue == ReviewerCardState.QUEUE_SUSPENDED && action.kind == ReviewerActionKind.BURY) {
            // rslib: "do not bury suspended cards as that would unsuspend them" — the action is a
            // no-op. Reporting Applied would claim a state change that provably cannot happen;
            // reporting a typed refusal keeps the UI honest and leaves the card untouched.
            return ReviewerActionBackendResult.ConfirmedNotApplied(
                AnkiError.ActionNotApplicable("suspended_card_cannot_be_buried"))
        }
        if (before.queue == desiredQueue) {
            // Already in the desired state: the scheduler would write nothing (its own
            // `card.queue != desired_queue` check). The committed audit calls this the idempotent
            // replay path (§28) — the second identical action cannot create a duplicate mutation
            // because none is sent. The boundary callback still runs first so the durable record
            // never claims the action was applied without crossing its own boundary.
            if (!mutationEntry()) {
                return ReviewerActionBackendResult.ConfirmedNotApplied(AnkiError.ActionLedgerUnavailable())
            }
            return ReviewerActionBackendResult.ConfirmedApplied(
                receipt(action, before.queue, "already_in_desired_state")
            )
        }

        // 4. The durable boundary, then exactly ONE provider update. No retry, here or below.
        if (!mutationEntry()) {
            return ReviewerActionBackendResult.ConfirmedNotApplied(AnkiError.ActionLedgerUnavailable())
        }
        val dispatch = gateway.submitAction(
            authority,
            AnkiDroidActionMutation(noteId, cardOrd, action.kind)
        )

        // 5. Evidence, not trust.
        return classify(authority, card, action, before.queue, desiredQueue, dispatch)
    }

    /**
     * §27 — read-only reconciliation of an unresolved action. It performs **no** mutation: the only
     * provider traffic is the card-state read this class already uses for verification.
     *
     * Only a positive observation is trusted: the card is in the state the action wanted, so the
     * action is in effect. Anything else is [ReviewerActionReconciliationResult.Unresolved] rather
     * than "not applied" — the pinned semantics are day-scoped (a bury expires at the next day
     * rollover, a suspend can be undone by the user in AnkiDroid), so "not currently buried" cannot
     * prove "was never buried".
     */
    suspend fun reconcile(
        authority: String,
        card: AnkiCardRef,
        action: ReviewerAction
    ): ReviewerActionReconciliationResult {
        val desiredQueue = desiredQueueFor(action)
            ?: return ReviewerActionReconciliationResult.Unresolved(
                AnkiError.UnsupportedAction(action.key))
        val observed = when (val read = cardStates.readCardState(authority, card)) {
            is AnkiResult.Success -> read.value
            is AnkiResult.Failure -> return ReviewerActionReconciliationResult.Unresolved(read.error)
        }
        return if (observed.queue == desiredQueue) {
            ReviewerActionReconciliationResult.ConfirmedApplied(
                receipt(action, observed.queue, "reconciled_by_card_state"))
        } else {
            ReviewerActionReconciliationResult.Unresolved(
                AnkiError.Unknown("card_state_not_desired"))
        }
    }

    /** The queue the pinned scheduler leaves behind for one action. Flag has none (§28). */
    private fun desiredQueueFor(action: ReviewerAction): Int? = when (action.kind) {
        ReviewerActionKind.BURY -> ReviewerCardState.QUEUE_MANUALLY_BURIED
        ReviewerActionKind.SUSPEND -> ReviewerCardState.QUEUE_SUSPENDED
        ReviewerActionKind.FLAG -> null
    }

    private fun receipt(
        action: ReviewerAction,
        queue: Int,
        detail: String
    ): ReviewerActionReceipt = ReviewerActionReceipt(
        backendId = backendId,
        actionKey = action.key,
        cardState = ReviewerCardState.fromQueue(queue),
        detail = detail,
        observedAtEpochMs = clock.nowMillis()
    )

    private suspend fun classify(
        authority: String,
        card: AnkiCardRef,
        action: ReviewerAction,
        beforeQueue: Int,
        desiredQueue: Int,
        dispatch: AnkiDroidReviewerActionDispatch
    ): ReviewerActionBackendResult = when (dispatch) {
        is AnkiDroidReviewerActionDispatch.NotDispatched ->
            // Refused before any IPC: provably not applied (safe retry).
            ReviewerActionBackendResult.ConfirmedNotApplied(dispatch.error)

        is AnkiDroidReviewerActionDispatch.Unknown -> {
            // Timed out or threw after the call was issued. The mutation may have been applied and
            // may still be applied; only a fresh read could say anything, and even then a
            // still-running call could land afterwards, so nothing is resolved here.
            ReviewerActionBackendResult.OutcomeUnknown(
                reason = AnkiError.Unknown(dispatch.detail),
                detail = dispatch.detail
            )
        }

        is AnkiDroidReviewerActionDispatch.Returned -> when (dispatch.rowCount) {
            // `1` means the update branch reached the action call; it is NOT success.
            1 -> verifyAfterMutation(authority, card, action, beforeQueue, desiredQueue)
            // The provider matched the note/ord keys but found no card (it logs and skips), or the
            // keys were absent: nothing was mutated.
            0 -> ReviewerActionBackendResult.ConfirmedNotApplied(AnkiError.CardNotFound(card))
            // The provider process died mid-call: the write may or may not have landed.
            UPDATE_REMOTE_FAILURE_ROWS -> ReviewerActionBackendResult.OutcomeUnknown(
                reason = AnkiError.ProviderUnavailable(),
                detail = "provider_process_died"
            )
            else -> ReviewerActionBackendResult.OutcomeUnknown(
                reason = AnkiError.MalformedResponse("action_rows_${dispatch.rowCount}"),
                detail = "action_rows_${dispatch.rowCount}"
            )
        }
    }

    /**
     * The immediate post-mutation read. Three verdicts, and only three:
     *
     * - the card is in the desired state → **ConfirmedApplied** (the backend's own state is the
     *   evidence);
     * - the card is exactly where it was → **ConfirmedNotApplied** (a synchronous return plus an
     *   unchanged card proves the scheduler did not apply it — the swallowed-exception path); the
     *   caller may retry the same action safely;
     * - anything else (identity changed, some *other* state) → **OutcomeUnknown**: the mutation
     *   cannot be attributed to this call, and nothing may be replayed.
     */
    private suspend fun verifyAfterMutation(
        authority: String,
        card: AnkiCardRef,
        action: ReviewerAction,
        beforeQueue: Int,
        desiredQueue: Int
    ): ReviewerActionBackendResult {
        val after = when (val read = cardStates.readCardState(authority, card)) {
            is AnkiResult.Success -> read.value
            is AnkiResult.Failure -> {
                log("ANKI_REVIEWER_ACTION_VERIFICATION_UNAVAILABLE error=${read.error::class.simpleName}")
                return ReviewerActionBackendResult.OutcomeUnknown(
                    reason = AnkiError.Unknown("verification_read_failed"),
                    detail = "verification_read_failed"
                )
            }
        }
        return when (after.queue) {
            desiredQueue -> {
                log("ANKI_REVIEWER_ACTION_VERIFIED queue=$desiredQueue")
                ReviewerActionBackendResult.ConfirmedApplied(
                    receipt(action, after.queue, "confirmed_by_card_state"))
            }
            beforeQueue -> {
                log("ANKI_REVIEWER_ACTION_NOT_APPLIED queue=$beforeQueue")
                ReviewerActionBackendResult.ConfirmedNotApplied(
                    AnkiError.QueryFailure("action_not_applied_scheduler_refused"))
            }
            else -> {
                log("ANKI_REVIEWER_ACTION_UNATTRIBUTABLE queue=${after.queue}")
                ReviewerActionBackendResult.OutcomeUnknown(
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
