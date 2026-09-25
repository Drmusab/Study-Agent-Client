package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.SystemAppClock
import com.studyagent.client.core.models.Rating
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * GATE 11 — the only AnkiDroid component that writes (STEP 22-§25).
 *
 * Responsibilities are split so each one is checkable:
 * - **reads** used as commit evidence and preconditions ([readCardState], [readSelectedDeck],
 *   [readQueueFront]) — identity and counters only, never card content;
 * - **one config write**, [selectDeck], used to satisfy the v2.24.1 queue-front precondition and to
 *   restore the user's selection right after;
 * - **one scheduler mutation**, [submitAnswer]: exactly one provider `update` per call.
 *
 * It never decides success. [submitAnswer] reports what happened at the mutation boundary; the
 * committer combines that with before/after evidence. Upper layers never see a `ContentResolver`,
 * `ContentValues`, URI or numeric ease value.
 */
interface AnkiDroidRatingGateway {
    suspend fun readCardState(authority: String, card: AnkiCardRef): AnkiResult<AnkiDroidCardState>
    suspend fun readSelectedDeck(authority: String): AnkiResult<Long?>
    suspend fun selectDeck(authority: String, deckId: Long): AnkiDroidSelectOutcome
    suspend fun readQueueFront(authority: String): AnkiResult<AnkiDroidQueueFront?>
    suspend fun submitAnswer(authority: String, answer: AnkiDroidAnswer): AnkiDroidAnswerDispatch

    /** Physical `update` calls issued for answers since construction (the mutation counter). */
    val physicalAnswerCalls: Long

    /**
     * A provider write issued by this gateway has not returned yet (for example after a timeout).
     * While true, "no change observed" is not evidence of "not applied" — the call may still land.
     */
    val writeInFlight: Boolean
}

/** The card AnkiDroid's selected deck would answer next. */
data class AnkiDroidQueueFront(val noteId: Long, val cardOrd: Int)

/** One answer, typed. The numeric ease is derived inside the gateway, never supplied from above. */
data class AnkiDroidAnswer(val noteId: Long, val cardOrd: Int, val rating: Rating, val timeTakenMs: Long?) {
    init { require(cardOrd >= 0 && (timeTakenMs == null || timeTakenMs >= 0)) }
}

sealed interface AnkiDroidSelectOutcome {
    data object Selected : AnkiDroidSelectOutcome
    /** The provider answered 0 rows: the deck does not exist in the open collection. */
    data object DeckMissing : AnkiDroidSelectOutcome
    /** Refused before any IPC (busy, invalid): the selection provably did not change. */
    data class NotDispatched(val error: AnkiError) : AnkiDroidSelectOutcome
    /** Issued, outcome unknown: the selection may or may not have changed. */
    data class Unknown(val detail: String) : AnkiDroidSelectOutcome
}

/** What the single answer `update` produced, before any evidence is consulted. */
sealed interface AnkiDroidAnswerDispatch {
    /** Refused before any IPC: provably not dispatched, provably not applied. */
    data class NotDispatched(val error: AnkiError) : AnkiDroidAnswerDispatch

    /** The provider returned. `1` is NOT proof of mutation at v2.24.1 (swallowed exceptions). */
    data class Returned(val rowCount: Int) : AnkiDroidAnswerDispatch

    /**
     * Issued; no classifiable answer. [callMayStillBeRunning] = the wait timed out, so the provider
     * may still apply the answer after any read made now.
     */
    data class Unknown(val detail: String, val callMayStillBeRunning: Boolean) : AnkiDroidAnswerDispatch
}

class DefaultAnkiDroidRatingGateway(
    private val providerClient: AnkiDroidProviderClient,
    /**
     * Owns issued provider writes. A caller that stops waiting (timeout, cancellation) never
     * cancels a call that was already issued, and the write permit is released only when the
     * physical call really returns — so no second write can overlap a stuck one.
     */
    private val scope: CoroutineScope,
    private val clock: AppClock = SystemAppClock,
    private val backendId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal,
    private val answerTimeoutMs: Long = ANSWER_TIMEOUT_MS,
    private val permitTimeoutMs: Long = PERMIT_TIMEOUT_MS
) : AnkiDroidRatingGateway {

    private val writePermit = Mutex()
    private val answerCalls = AtomicLong(0L)

    override val physicalAnswerCalls: Long get() = answerCalls.get()

    override val writeInFlight: Boolean get() = writePermit.isLocked

    override suspend fun readCardState(authority: String, card: AnkiCardRef): AnkiResult<AnkiDroidCardState> {
        if (card.backendId != backendId) return AnkiResult.Failure(AnkiError.InvalidRequest("card_ref_foreign_backend"))
        val path = when {
            card.noteId != null && card.cardOrd != null ->
                "${AnkiDroidApiContract.NOTES_PATH}/${card.noteId}/${AnkiDroidApiContract.NOTE_CARDS_PATH}/${card.cardOrd}"
            card.cardId != null -> "${AnkiDroidApiContract.CARDS_PATH}/${card.cardId}"
            else -> return AnkiResult.Failure(AnkiError.InvalidRequest("card_ref_unaddressable"))
        }
        val query = try {
            providerClient.safeQuery(authority, path, AnkiDroidApiContract.CARD_STATE_PROJECTION, null, null, null) { row ->
                AnkiDroidCardState.fromRow(row)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            return AnkiResult.Failure(AnkiDroidErrorMapper.mapThrowable(throwable, "card_state", AnkiDroidOperationStage.PROVIDER_QUERY))
        }
        return when (query) {
            is ProviderQueryResult.Failure -> AnkiResult.Failure(AnkiDroidErrorMapper.mapQueryResultFailure(query))
            is ProviderQueryResult.Empty -> AnkiResult.Failure(AnkiError.CardNotFound(card))
            is ProviderQueryResult.Success -> {
                val state = query.data.firstOrNull()
                    ?: return AnkiResult.Failure(AnkiError.MalformedResponse("card_state_unmappable"))
                // The row must be the card that was asked for (no silent substitution).
                val matches = (card.cardId == null || card.cardId == state.cardId.toString()) &&
                    (card.noteId == null || card.noteId == state.noteId.toString()) &&
                    (card.cardOrd == null || card.cardOrd == state.cardOrd)
                if (matches) AnkiResult.Success(state)
                else AnkiResult.Failure(AnkiError.StaleCardReference(card, "card_state_identity_mismatch"))
            }
        }
    }

    override suspend fun readSelectedDeck(authority: String): AnkiResult<Long?> {
        val query = try {
            providerClient.safeQuery(
                authority, AnkiDroidApiContract.SELECTED_DECK_PATH,
                arrayOf(AnkiDroidApiContract.DECK_ID_COLUMN), null, null, null
            ) { row ->
                row.columnIndex(AnkiDroidApiContract.DECK_ID_COLUMN).takeIf { it >= 0 && !row.isNull(it) }
                    ?.let { row.getLong(it) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            return AnkiResult.Failure(AnkiDroidErrorMapper.mapThrowable(throwable, "selected_deck", AnkiDroidOperationStage.PROVIDER_QUERY))
        }
        return when (query) {
            is ProviderQueryResult.Failure -> AnkiResult.Failure(AnkiDroidErrorMapper.mapQueryResultFailure(query))
            is ProviderQueryResult.Empty -> AnkiResult.Success(null)
            is ProviderQueryResult.Success -> AnkiResult.Success(query.data.firstOrNull())
        }
    }

    override suspend fun readQueueFront(authority: String): AnkiResult<AnkiDroidQueueFront?> {
        val query = try {
            providerClient.safeQuery(
                authority, AnkiDroidApiContract.SCHEDULE_PATH, AnkiDroidApiContract.QUEUE_FRONT_PROJECTION,
                // No deckID on purpose: that key would make the provider re-select a deck.
                AnkiDroidApiContract.REVIEW_LIMIT_SELECTION_KEY + AnkiDroidApiContract.REVIEW_SELECTION_KEY_VALUE_SEPARATOR + 1,
                null, null
            ) { row ->
                val note = row.columnIndex(AnkiDroidApiContract.REVIEW_NOTE_ID_COLUMN)
                val ord = row.columnIndex(AnkiDroidApiContract.REVIEW_CARD_ORD_COLUMN)
                if (note < 0 || ord < 0 || row.isNull(note) || row.isNull(ord)) null
                else AnkiDroidQueueFront(row.getLong(note), row.getInt(ord))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            return AnkiResult.Failure(AnkiDroidErrorMapper.mapThrowable(throwable, "queue_front", AnkiDroidOperationStage.PROVIDER_QUERY))
        }
        return when (query) {
            is ProviderQueryResult.Failure -> AnkiResult.Failure(AnkiDroidErrorMapper.mapQueryResultFailure(query))
            is ProviderQueryResult.Empty -> AnkiResult.Success(null)
            is ProviderQueryResult.Success -> {
                val front = query.data.firstOrNull()
                    ?: return AnkiResult.Failure(AnkiError.MalformedResponse("queue_front_unmappable"))
                AnkiResult.Success(front)
            }
        }
    }

    override suspend fun selectDeck(authority: String, deckId: Long): AnkiDroidSelectOutcome {
        if (!acquirePermit()) return AnkiDroidSelectOutcome.NotDispatched(AnkiError.QueryFailure("provider_write_busy"))
        val result = issue(authority, AnkiDroidApiContract.SELECTED_DECK_PATH,
            listOf(ProviderValue.LongValue(AnkiDroidApiContract.DECK_ID_COLUMN, deckId)))
            ?: return AnkiDroidSelectOutcome.Unknown("select_timeout")
        return when (result) {
            is ProviderUpdateResult.Returned -> when (result.rowCount) {
                1 -> AnkiDroidSelectOutcome.Selected
                0 -> AnkiDroidSelectOutcome.DeckMissing
                else -> AnkiDroidSelectOutcome.Unknown("select_rows_${result.rowCount}")
            }
            is ProviderUpdateResult.NotDispatched -> AnkiDroidSelectOutcome.NotDispatched(AnkiError.QueryFailure(result.reason))
            is ProviderUpdateResult.Threw -> AnkiDroidSelectOutcome.Unknown("select_threw_${result.exceptionClass}")
        }
    }

    override suspend fun submitAnswer(authority: String, answer: AnkiDroidAnswer): AnkiDroidAnswerDispatch {
        val values = buildList {
            add(ProviderValue.LongValue(AnkiDroidApiContract.REVIEW_NOTE_ID_COLUMN, answer.noteId))
            add(ProviderValue.IntValue(AnkiDroidApiContract.REVIEW_CARD_ORD_COLUMN, answer.cardOrd))
            add(ProviderValue.IntValue(AnkiDroidApiContract.REVIEW_ANSWER_EASE_COLUMN, AnkiDroidRatingContract.easeFor(answer.rating)))
            answer.timeTakenMs?.let { add(ProviderValue.LongValue(AnkiDroidApiContract.REVIEW_TIME_TAKEN_COLUMN, it)) }
        }
        if (!acquirePermit()) {
            return AnkiDroidAnswerDispatch.NotDispatched(AnkiError.QueryFailure("provider_write_busy"))
        }
        answerCalls.incrementAndGet()
        AppLogger.i(TAG, "ANKI_COMMIT_PROVIDER_CALL rating=${answer.rating.name.lowercase()}")
        val result = issue(authority, AnkiDroidApiContract.SCHEDULE_PATH, values)
            ?: return AnkiDroidAnswerDispatch.Unknown("answer_timeout", callMayStillBeRunning = true)
        return when (result) {
            is ProviderUpdateResult.Returned -> AnkiDroidAnswerDispatch.Returned(result.rowCount)
            is ProviderUpdateResult.NotDispatched ->
                AnkiDroidAnswerDispatch.NotDispatched(AnkiError.QueryFailure(result.reason))
            is ProviderUpdateResult.Threw -> {
                // The exception *class* is not a transaction receipt. Even a permission error may
                // be delivered after a partial provider operation on an unverified version. Only
                // NotDispatched (before IPC) can claim safe retry here.
                AnkiDroidAnswerDispatch.Unknown("answer_threw_${result.exceptionClass}", callMayStillBeRunning = false)
            }
        }
    }

    /**
     * Bounded wait for the write permit. Timing out while waiting is pre-dispatch (nothing issued).
     * The flag is set on the same stack frame the lock is taken, so a timeout that races the
     * acquisition can never leave the permit held without us knowing.
     */
    private suspend fun acquirePermit(): Boolean {
        var acquired = false
        withTimeoutOrNull(permitTimeoutMs) {
            writePermit.lock()
            acquired = true
        }
        return acquired
    }

    /**
     * Issues one provider update in [scope] and waits at most [answerTimeoutMs]. Returns `null` on
     * timeout — the call is still running and will release the permit when it returns. Caller
     * cancellation propagates; the issued call is not cancelled.
     */
    private suspend fun issue(authority: String, path: String, values: List<ProviderValue>): ProviderUpdateResult? {
        val call = scope.async(start = CoroutineStart.ATOMIC) {
            try {
                providerClient.safeUpdate(authority, path, values)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                ProviderUpdateResult.Threw(
                    throwable::class.java.simpleName,
                    AnkiDroidFailureClassifier.classify(throwable, AnkiDroidOperationStage.PROVIDER_UPDATE)
                )
            } finally {
                writePermit.unlock()
            }
        }
        return withTimeoutOrNull(answerTimeoutMs) { call.await() }
    }

    private companion object {
        const val TAG = "AnkiDroidRatingGateway"

        /** A provider answer normally takes milliseconds; this covers collection-lock contention. */
        const val ANSWER_TIMEOUT_MS = 15_000L

        /** How long a commit waits for a previous (possibly stuck) write before refusing. */
        const val PERMIT_TIMEOUT_MS = 5_000L
    }
}
