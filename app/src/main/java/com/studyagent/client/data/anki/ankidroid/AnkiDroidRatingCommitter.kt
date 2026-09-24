package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.CommitPreparation
import com.studyagent.client.core.anki.CommitRatingRequest
import com.studyagent.client.core.anki.CommitRatingResult
import com.studyagent.client.core.anki.ReconcileCommitRequest
import com.studyagent.client.core.anki.ReconcileCommitResult
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.AppLogger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * GATE 11 — the AnkiDroid rating protocol (STEP 18-§28, §32-§37, §55-§59).
 *
 * ```text
 *  pre-mutation   1. fresh card state == ledger baseline?        (no → Rejected: card changed)
 *                 2. selected deck == session deck? else select   (v2.24.1 precondition, config only)
 *                 3. queue front == this card?                    (no → Rejected, nothing sent)
 *  boundary       4. ONE provider update (answer_ease, time_taken)
 *  post-mutation  5. restore the user's selected deck (always, NonCancellable)
 *                 6. read card state again → verifier → Committed | not applied | Ambiguous
 * ```
 *
 * Why step 6 exists: at the pinned v2.24.1 the provider swallows scheduler exceptions and still
 * answers `1` (see `AnkiDroidApiContract`), so "no exception, one row" is never treated as success.
 * Every outcome after dispatch is decided by evidence; when evidence cannot decide, it is AMBIGUOUS.
 * Callers serialize commits (the backend holds one lock per commit), and the gateway serializes
 * physical writes, so this class never issues two answers at once.
 */
internal class AnkiDroidRatingCommitter(
    private val gateway: AnkiDroidRatingGateway,
    private val clock: AppClock
) {
    /** Read-only baseline for the ledger (STEP 58: pre-commit reps and scheduling facts). */
    suspend fun prepare(authority: String, card: AnkiCardRef): CommitPreparation =
        when (val state = gateway.readCardState(authority, card)) {
            is AnkiResult.Success -> CommitPreparation.Ready(state.value.toEvidence(clock.nowMillis()))
            is AnkiResult.Failure -> CommitPreparation.Refused(state.error, retryable = state.error.isTransient())
        }

    suspend fun commit(authority: String, sessionDeckId: Long, request: CommitRatingRequest): CommitRatingResult {
        val card = request.card
        val noteId = card.noteId?.toLongOrNull()
        val cardOrd = card.cardOrd
        if (noteId == null || cardOrd == null) {
            return CommitRatingResult.Rejected(AnkiError.InvalidRequest("answer_requires_note_and_ord"))
        }
        // A write from an earlier attempt that never returned may still land: do not add another.
        if (gateway.writeInFlight) {
            return CommitRatingResult.RetryableFailure(AnkiError.QueryFailure("provider_write_busy"))
        }

        // 1. Fresh baseline, compared with the durable one captured before SUBMITTING.
        val baseline = when (val read = gateway.readCardState(authority, card)) {
            is AnkiResult.Success -> read.value
            is AnkiResult.Failure -> return preDispatch(read.error)
        }
        request.evidence?.let { evidence ->
            val expected = AnkiDroidCardState.fromEvidence(evidence)
                ?: return CommitRatingResult.Rejected(AnkiError.InvalidRequest("commit_evidence_unreadable"))
            if (!expected.unchangedFrom(baseline)) {
                // Someone (AnkiDroid itself, sync, another client) changed the card since it was
                // rated here. Answering now would stack a second review on top: refuse, pre-mutation.
                log("ANKI_COMMIT_PRECONDITION_FAILED reason=card_state_changed")
                return CommitRatingResult.Rejected(AnkiError.CommitConflict(card))
            }
        }

        // 2. v2.24.1 answers the front card of AnkiDroid's *selected* deck; make that our deck.
        val previousDeck = when (val read = gateway.readSelectedDeck(authority)) {
            is AnkiResult.Success -> read.value
            is AnkiResult.Failure -> return preDispatch(read.error)
        }
        var selectionChanged = false
        if (previousDeck != sessionDeckId) {
            when (val selected = gateway.selectDeck(authority, sessionDeckId)) {
                AnkiDroidSelectOutcome.Selected -> selectionChanged = true
                AnkiDroidSelectOutcome.DeckMissing -> return CommitRatingResult.Rejected(AnkiError.DeckNotFound())
                is AnkiDroidSelectOutcome.NotDispatched -> return CommitRatingResult.RetryableFailure(selected.error)
                is AnkiDroidSelectOutcome.Unknown -> {
                    restoreSelection(authority, previousDeck)
                    return CommitRatingResult.RetryableFailure(AnkiError.QueryFailure("deck_selection_unconfirmed"))
                }
            }
        }

        // 3 + 4 inside try: whatever happens, step 5 restores the user's selection.
        val attempt: Pair<AnkiDroidAnswerDispatch, Long> = try {
            val front = when (val read = gateway.readQueueFront(authority)) {
                is AnkiResult.Success -> read.value
                is AnkiResult.Failure -> return preDispatch(read.error)
            }
            if (front == null || front.noteId != noteId || front.cardOrd != cardOrd) {
                // The scheduler now wants another card first (e.g. a learning card became due). On
                // v2.24.1 the provider would silently fail to answer; nothing is sent.
                log("ANKI_COMMIT_PRECONDITION_FAILED reason=card_not_next_in_queue")
                return CommitRatingResult.Rejected(AnkiError.CommitConflict(card))
            }
            val windowStart = clock.nowMillis()
            gateway.submitAnswer(authority, AnkiDroidAnswer(noteId, cardOrd, request.rating, request.answerDurationMs)) to windowStart
        } finally {
            if (selectionChanged) withContext(NonCancellable) { restoreSelection(authority, previousDeck) }
        }
        val (dispatch, windowStart) = attempt
        return classify(authority, card, baseline, dispatch, windowStart, clock.nowMillis())
    }

    /** Read-only reconciliation from the durable baseline (works after process death). */
    suspend fun reconcile(authority: String, request: ReconcileCommitRequest): ReconcileCommitResult {
        val baseline = AnkiDroidCardState.fromEvidence(request.evidence)
            ?: return ReconcileCommitResult.StillAmbiguous("baseline_evidence_unavailable")
        if (gateway.writeInFlight) return ReconcileCommitResult.StillAmbiguous("provider_call_in_flight")
        val after = when (val read = gateway.readCardState(authority, request.card)) {
            is AnkiResult.Success -> read.value
            is AnkiResult.Failure -> return if (read.error is AnkiError.CardNotFound) {
                ReconcileCommitResult.StillAmbiguous("card_not_found")
            } else ReconcileCommitResult.Unavailable(read.error)
        }
        return when (val verdict = AnkiDroidCommitVerifier.classify(
            baseline, after, request.submittedAtEpochMs, request.windowEndEpochMs, tightWindow = false
        )) {
            is AnkiDroidCommitVerifier.Verdict.Applied -> ReconcileCommitResult.Applied(verdict.detail)
            is AnkiDroidCommitVerifier.Verdict.NotApplied -> ReconcileCommitResult.NotApplied(verdict.detail, verdict.safeToRetry)
            is AnkiDroidCommitVerifier.Verdict.Unattributable -> ReconcileCommitResult.StillAmbiguous(verdict.detail)
        }
    }

    private suspend fun classify(
        authority: String,
        card: AnkiCardRef,
        baseline: AnkiDroidCardState,
        dispatch: AnkiDroidAnswerDispatch,
        windowStart: Long,
        windowEnd: Long
    ): CommitRatingResult {
        if (dispatch is AnkiDroidAnswerDispatch.NotDispatched) {
            return CommitRatingResult.RetryableFailure(dispatch.error) // no IPC happened at all
        }
        val after = when (val read = gateway.readCardState(authority, card)) {
            is AnkiResult.Success -> read.value
            // After dispatch, missing evidence never becomes a failure — it is AMBIGUOUS.
            is AnkiResult.Failure -> {
                log("ANKI_COMMIT_VERIFICATION_UNAVAILABLE error=${read.error::class.simpleName}")
                return if (dispatch is AnkiDroidAnswerDispatch.RejectedBeforeMutation) {
                    rejectedBeforeMutation(dispatch)
                } else CommitRatingResult.Ambiguous(AnkiError.Unknown("verification_read_failed"))
            }
        }
        val verdict = AnkiDroidCommitVerifier.classify(baseline, after, windowStart, windowEnd, tightWindow = true)
        log("ANKI_COMMIT_VERIFIED dispatch=${dispatch.token()} verdict=${verdict::class.simpleName}:${verdict.detail}")
        return when (verdict) {
            is AnkiDroidCommitVerifier.Verdict.Applied -> when (dispatch) {
                // A pre-mutation exception with an applied answer is a contradiction: never guess.
                is AnkiDroidAnswerDispatch.RejectedBeforeMutation ->
                    CommitRatingResult.Ambiguous(AnkiError.Unknown("rejected_but_applied"))
                else -> CommitRatingResult.Committed()
            }
            is AnkiDroidCommitVerifier.Verdict.Unattributable ->
                CommitRatingResult.Ambiguous(AnkiError.Unknown("unattributable:${verdict.detail}"))
            is AnkiDroidCommitVerifier.Verdict.NotApplied -> when (dispatch) {
                is AnkiDroidAnswerDispatch.RejectedBeforeMutation -> rejectedBeforeMutation(dispatch)
                // A timed-out call may still apply after this read: unknown, not "failed".
                is AnkiDroidAnswerDispatch.Unknown -> if (dispatch.callMayStillBeRunning || !verdict.safeToRetry) {
                    CommitRatingResult.Ambiguous(AnkiError.Unknown(dispatch.detail))
                } else CommitRatingResult.RetryableFailure(AnkiError.QueryFailure("answer_not_applied"))
                is AnkiDroidAnswerDispatch.Returned -> when {
                    !verdict.safeToRetry -> CommitRatingResult.Rejected(AnkiError.CommitConflict(card))
                    // v2.24.1: the provider swallowed a scheduler exception and still answered 1.
                    dispatch.rowCount == AnkiDroidApiContract.REVIEW_ANSWER_REACHED_ROWS ->
                        CommitRatingResult.RetryableFailure(AnkiError.QueryFailure("answer_not_applied"))
                    // The provider process died; its transaction never committed.
                    dispatch.rowCount == AnkiDroidApiContract.UPDATE_REMOTE_FAILURE_ROWS ->
                        CommitRatingResult.RetryableFailure(AnkiError.QueryFailure("provider_died_before_applying"))
                    // 0 rows: the provider did not recognise the answer request at all.
                    dispatch.rowCount == 0 ->
                        CommitRatingResult.Rejected(AnkiError.MalformedResponse("answer_update_no_rows"))
                    else -> CommitRatingResult.Ambiguous(AnkiError.Unknown("answer_rows_${dispatch.rowCount}"))
                }
                is AnkiDroidAnswerDispatch.NotDispatched -> CommitRatingResult.RetryableFailure(dispatch.error)
            }
        }
    }

    private fun rejectedBeforeMutation(dispatch: AnkiDroidAnswerDispatch.RejectedBeforeMutation): CommitRatingResult =
        if (dispatch.error.isTransient()) CommitRatingResult.RetryableFailure(dispatch.error)
        else CommitRatingResult.Rejected(dispatch.error)

    private fun preDispatch(error: AnkiError): CommitRatingResult =
        if (error.isTransient()) CommitRatingResult.RetryableFailure(error) else CommitRatingResult.Rejected(error)

    private suspend fun restoreSelection(authority: String, previousDeck: Long?) {
        if (previousDeck == null) return
        when (val restored = gateway.selectDeck(authority, previousDeck)) {
            AnkiDroidSelectOutcome.Selected -> Unit
            else -> log("ANKI_DECK_SELECTION_RESTORE_FAILED outcome=${restored::class.simpleName}")
        }
    }

    private fun AnkiDroidAnswerDispatch.token(): String = when (this) {
        is AnkiDroidAnswerDispatch.NotDispatched -> "not_dispatched"
        is AnkiDroidAnswerDispatch.RejectedBeforeMutation -> "rejected_before_mutation:$exceptionClass"
        is AnkiDroidAnswerDispatch.Returned -> "returned:$rowCount"
        is AnkiDroidAnswerDispatch.Unknown -> "unknown:$detail"
    }

    private fun log(message: String) = AppLogger.i(TAG, message)

    private companion object {
        const val TAG = "AnkiDroidRatingCommitter"
    }
}

/** Errors that say "try later", as opposed to "this request cannot succeed". Pre-dispatch only. */
internal fun AnkiError.isTransient(): Boolean = when (this) {
    is AnkiError.BackendUnavailable, is AnkiError.ProviderUnavailable, is AnkiError.QueryFailure,
    is AnkiError.PermissionRequired, is AnkiError.CollectionUnavailable, is AnkiError.Unknown -> true
    else -> false
}
