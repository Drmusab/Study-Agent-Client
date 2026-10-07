package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.CommitPreparation
import com.studyagent.client.core.anki.CommitRatingRequest
import com.studyagent.client.core.anki.BackendCommitResult
import com.studyagent.client.core.anki.MutationBoundary
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
 *                 6. synchronous response + immediate read → Committed | Ambiguous
 * ```
 *
 * Why step 6 exists: at the pinned v2.24.1 the provider swallows scheduler exceptions and still
 * answers `1` (see `AnkiDroidApiContract`), so "no exception, one row" is never treated as success.
 * A lost response is always AMBIGUOUS; later card-state reads cannot attribute a ReviewCommitId.
 * A synchronous `1` plus a single normal review transition is the limited immediate success path.
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

    suspend fun commit(
        authority: String, sessionDeckId: Long, request: CommitRatingRequest,
        mutationEntry: suspend () -> Boolean = { true }
    ): BackendCommitResult {
        val card = request.card
        val noteId = card.noteId?.toLongOrNull()
        val cardOrd = card.cardOrd
        if (noteId == null || noteId <= 0L || cardOrd == null) {
            return AnkiDroidCommitResultClassifier.beforeMutation(
                AnkiError.InvalidRequest("answer_requires_positive_note_and_ord")
            )
        }
        if (sessionDeckId <= 0L) {
            return AnkiDroidCommitResultClassifier.beforeMutation(
                AnkiError.InvalidRequest("session_deck_id_invalid")
            )
        }

        // A write from an earlier attempt that never returned may still land. This attempt has not
        // entered, but non-application of the in-flight write is not proven, so retry is not safe.
        if (gateway.writeInFlight) {
            return AnkiDroidCommitResultClassifier.afterMutation("provider_write_busy")
        }

        var mutationBoundaryEntered = false
        var selectionChanged = false
        return try {
            // 1. Fresh baseline, compared with the durable one captured before SUBMITTING.
            val baseline = when (val read = gateway.readCardState(authority, card)) {
                is AnkiResult.Success -> read.value
                is AnkiResult.Failure -> return AnkiDroidCommitResultClassifier.beforeMutation(read.error)
            }
            request.evidence?.let { evidence ->
                val expected = AnkiDroidCardState.fromEvidence(evidence)
                    ?: return AnkiDroidCommitResultClassifier.beforeMutation(
                        AnkiError.InvalidRequest("commit_evidence_unreadable")
                    )
                if (!expected.unchangedFrom(baseline)) {
                    // Someone (AnkiDroid itself, sync, another client) changed the card since it was
                    // rated here. Answering now would stack a second review on top: refuse, pre-mutation.
                    log("ANKI_COMMIT_PRECONDITION_FAILED reason=card_state_changed")
                    return AnkiDroidCommitResultClassifier.beforeMutation(AnkiError.CommitConflict(card))
                }
            }

            // 2. v2.24.1 answers the front card of AnkiDroid's *selected* deck; make that our deck.
            val previousDeck = when (val read = gateway.readSelectedDeck(authority)) {
                is AnkiResult.Success -> read.value
                is AnkiResult.Failure -> return AnkiDroidCommitResultClassifier.beforeMutation(read.error)
            }
            if (previousDeck != sessionDeckId) {
                when (val selected = gateway.selectDeck(authority, sessionDeckId)) {
                    AnkiDroidSelectOutcome.Selected -> selectionChanged = true
                    AnkiDroidSelectOutcome.DeckMissing -> return AnkiDroidCommitResultClassifier.beforeMutation(
                        AnkiError.DeckNotFound()
                    )
                    is AnkiDroidSelectOutcome.NotDispatched ->
                        return AnkiDroidCommitResultClassifier.beforeMutation(selected.error)
                    is AnkiDroidSelectOutcome.Unknown -> {
                        // This is a configuration-selection uncertainty, not an answer dispatch. The
                        // answer update is still never issued; restore is best effort and bounded by
                        // the gateway's single-write permit.
                        restoreSelection(authority, previousDeck)
                        return AnkiDroidCommitResultClassifier.beforeMutation(
                            AnkiError.QueryFailure("deck_selection_unconfirmed")
                        )
                    }
                }
            }

            // 3 + 4 inside try: whatever happens, step 5 restores the user's selected deck.
            val attempt: Pair<AnkiDroidAnswerDispatch, Long> = try {
                val front = when (val read = gateway.readQueueFront(authority)) {
                    is AnkiResult.Success -> read.value
                    is AnkiResult.Failure -> return AnkiDroidCommitResultClassifier.beforeMutation(read.error)
                }
                if (front == null || front.noteId != noteId || front.cardOrd != cardOrd) {
                    // The scheduler now wants another card first (e.g. a learning card became due).
                    // On v2.24.1 the provider would otherwise answer the selected queue front.
                    log("ANKI_COMMIT_PRECONDITION_FAILED reason=card_not_next_in_queue")
                    return AnkiDroidCommitResultClassifier.beforeMutation(AnkiError.CommitConflict(card))
                }

                // ==================== IRREVERSIBLE MUTATION BOUNDARY ====================
                // The coordinator has durably persisted SUBMITTING before this callback returns
                // true. No provider answer may be dispatched unless it returns true.
                if (!mutationEntry()) {
                    return AnkiDroidCommitResultClassifier.beforeMutation(AnkiError.CommitLedgerUnavailable())
                }
                mutationBoundaryEntered = true
                val windowStart = clock.nowMillis()
                gateway.submitAnswer(
                    authority,
                    AnkiDroidAnswer(noteId, cardOrd, request.rating, request.answerDurationMs)
                ) to windowStart
            } finally {
                if (selectionChanged) withContext(NonCancellable) { restoreSelection(authority, previousDeck) }
            }
            val (dispatch, windowStart) = attempt
            classify(authority, card, baseline, dispatch, windowStart, clock.nowMillis())
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // The coordinator owns cancellation recovery. It records AMBIGUOUS when cancellation
            // happened after this marker and releases only an unentered claim before it.
            throw cancellation
        } catch (_: Throwable) {
            AnkiDroidCommitResultClassifier.unexpected(
                if (mutationBoundaryEntered) MutationBoundary.AFTER_CALL_ENTERED
                else MutationBoundary.BEFORE_CALL
            )
        }
    }

    /**
     * Evidence gathering is read-only, but AnkiDroid exposes no ReviewCommitId / durable receipt.
     * A changed card, unchanged card, time, revlog or next card cannot prove which caller caused
     * the effect, nor that a timed-out write will never land. Never resolve either way here.
     */
    suspend fun reconcile(authority: String, request: ReconcileCommitRequest): ReconcileCommitResult {
        val baseline = AnkiDroidCardState.fromEvidence(request.evidence)
            ?: return ReconcileCommitResult.StillAmbiguous("baseline_evidence_unavailable")
        if (gateway.writeInFlight) return ReconcileCommitResult.StillAmbiguous("provider_call_in_flight")
        return when (val read = gateway.readCardState(authority, request.card)) {
            is AnkiResult.Failure -> if (read.error is AnkiError.CardNotFound)
                ReconcileCommitResult.StillAmbiguous("card_not_found")
                else ReconcileCommitResult.Unavailable(read.error)
            is AnkiResult.Success -> if (!read.value.sameIdentity(baseline))
                ReconcileCommitResult.StillAmbiguous("card_identity_changed")
                else ReconcileCommitResult.StillAmbiguous("no_transaction_correlated_receipt")
        }
    }

    private suspend fun classify(
        authority: String,
        card: AnkiCardRef,
        baseline: AnkiDroidCardState,
        dispatch: AnkiDroidAnswerDispatch,
        windowStart: Long,
        windowEnd: Long
    ): BackendCommitResult {
        // A timeout, exception, or -1 is a lost response, NOT a transaction result. Even if a
        // later card read shows reps+1 (or no change), that observation cannot attribute the
        // mutation to this ReviewCommitId. The only immediate happy path is a synchronous return
        // from this answer call *and* a consistent single review transition.
        AnkiDroidCommitResultClassifier.dispatch(dispatch)?.let { return it }
        val returned = dispatch as? AnkiDroidAnswerDispatch.Returned
            ?: return AnkiDroidCommitResultClassifier.afterMutation("answer_outcome_unavailable")
        val after = when (val read = gateway.readCardState(authority, card)) {
            is AnkiResult.Success -> read.value
            is AnkiResult.Failure -> {
                log("ANKI_COMMIT_VERIFICATION_UNAVAILABLE error=${read.error::class.simpleName}")
                return BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("verification_read_failed"))
            }
        }
        val verdict = AnkiDroidCommitVerifier.classify(baseline, after, windowStart, windowEnd, tightWindow = true)
        log("ANKI_COMMIT_VERIFIED dispatch=${returned.token()} verdict=${verdict::class.simpleName}:${verdict.detail}")
        return AnkiDroidCommitResultClassifier.synchronousAnswer(
            dispatch = returned,
            verdict = verdict,
            inFilteredDeck = baseline.inFilteredDeck
        )
    }

    private suspend fun restoreSelection(authority: String, previousDeck: Long?) {
        if (previousDeck == null) return
        when (val restored = gateway.selectDeck(authority, previousDeck)) {
            AnkiDroidSelectOutcome.Selected -> Unit
            else -> log("ANKI_DECK_SELECTION_RESTORE_FAILED outcome=${restored::class.simpleName}")
        }
    }

    private fun AnkiDroidAnswerDispatch.token(): String = when (this) {
        is AnkiDroidAnswerDispatch.NotDispatched -> "not_dispatched"
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
