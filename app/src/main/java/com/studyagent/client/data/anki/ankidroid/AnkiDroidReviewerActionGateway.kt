package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.ReviewerActionKind
import com.studyagent.client.core.common.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * GATE 13 STEP 8/§22 — the only AnkiDroid component that performs reviewer mutations.
 *
 * Scope, deliberately narrow:
 *
 * - **one** provider `update` per call, on the pinned public `schedule` endpoint, carrying exactly
 *   the columns the pinned contract defines (`note_id`, `ord`, and `buried` **or** `suspended`);
 * - no private database access, no scheduler internals, no `/data/data` (INV-13-06/INV-13-18);
 * - the physical call is serialized by the **shared** [AnkiDroidWritePermit], so it can never
 *   overlap a rating answer (or another reviewer action);
 * - it never decides success: [submitAction] reports what happened at the mutation boundary, and
 *   [AnkiDroidReviewerActionCommitter] combines that with before/after card state, because the
 *   pinned provider swallows scheduler exceptions and still answers one updated row.
 *
 * The pinned contract facts behind this file are documented in `AnkiDroidApiContract`
 * (GATE 13 table) and were re-verified against the pinned AnkiDroid source; the important ones:
 *
 * - `ReviewInfo.BURY = "buried"` / `ReviewInfo.SUSPEND = "suspended"`, write-only, "set to 1 to
 *   bury/suspend the card", mutually exclusive with answering (one `update` performs exactly one
 *   of the three);
 * - the provider resolves the card by `(note_id, ord)` and calls `sched.buryCards([cardId])` /
 *   `sched.suspendCards([cardId])` — **card**-scoped, never note-scoped (STEP 11 keeps the two
 *   distinct and this gate implements only the smaller, card-scoped operation);
 * - those scheduler calls are wrapped in `catch (RuntimeException)` that only logs, so `1` is not
 *   proof of mutation — the same rule as the GATE 11 answer path;
 * - there is **no** unbury/unsuspend column and no flag column at all: those operations have no
 *   public write path at the pin and are therefore not modelled (STEP 11/14/20).
 */
interface AnkiDroidReviewerActionGateway {

    /** Issues exactly one provider action update, or refuses before any IPC. */
    suspend fun submitAction(authority: String, mutation: AnkiDroidActionMutation): AnkiDroidReviewerActionDispatch

    /** Physical action `update` calls issued since construction (diagnostics/test evidence). */
    val physicalActionCalls: Long

    /** True while a provider write issued through the shared permit has not returned. */
    val writeInFlight: Boolean
}

/**
 * One card-scoped reviewer mutation, typed. The provider column names never travel above the
 * gateway (INV-ANKI-06: nothing above the gateway parses or speaks provider-native values).
 */
data class AnkiDroidActionMutation(
    val noteId: Long,
    val cardOrd: Int,
    val kind: ReviewerActionKind
) {
    init {
        require(noteId > 0L && cardOrd >= 0)
        require(kind != ReviewerActionKind.FLAG) {
            "The pinned AnkiDroid public contract has no flag write; a flag must never reach the gateway"
        }
    }
}

/** What one action `update` produced, before any evidence is consulted. */
sealed interface AnkiDroidReviewerActionDispatch {
    /** Refused before any IPC: provably not dispatched, provably not applied. */
    data class NotDispatched(val error: AnkiError) : AnkiDroidReviewerActionDispatch

    /** The provider returned. `1` is NOT proof of mutation at v2.24.1 (swallowed exceptions). */
    data class Returned(val rowCount: Int) : AnkiDroidReviewerActionDispatch

    /**
     * Issued; no classifiable answer. [callMayStillBeRunning] = the wait timed out, so the provider
     * may still apply the action after any read made now.
     */
    data class Unknown(val detail: String, val callMayStillBeRunning: Boolean) : AnkiDroidReviewerActionDispatch
}

class DefaultAnkiDroidReviewerActionGateway(
    private val providerClient: AnkiDroidProviderClient,
    /** Owns issued provider writes so a caller that stops waiting never cancels a live call. */
    private val scope: CoroutineScope,
    /**
     * The permit shared with the rating gateway. Required (no default): a reviewer-action gateway
     * with its own permit would be a second writer, and this gate exists precisely to keep one.
     */
    private val writePermit: AnkiDroidWritePermit,
    private val backendId: AnkiBackendId = AnkiBackendId.AnkiDroidLocal,
    private val actionTimeoutMs: Long = ACTION_TIMEOUT_MS,
    private val permitTimeoutMs: Long = PERMIT_TIMEOUT_MS
) : AnkiDroidReviewerActionGateway {

    private val actionCalls = AtomicLong(0L)

    override val physicalActionCalls: Long get() = actionCalls.get()

    override val writeInFlight: Boolean get() = writePermit.inFlight

    override suspend fun submitAction(
        authority: String,
        mutation: AnkiDroidActionMutation
    ): AnkiDroidReviewerActionDispatch {
        // Defense in depth: the committer refuses flags before reaching here, and the pinned
        // contract cannot express a flag write at all. Refusing before the permit keeps a
        // protocol lie from ever occupying the single write slot.
        if (mutation.kind == ReviewerActionKind.FLAG) {
            return AnkiDroidReviewerActionDispatch.NotDispatched(
                AnkiError.UnsupportedAction("flag_unsupported_by_pinned_contract")
            )
        }
        val values = buildList {
            add(ProviderValue.LongValue(AnkiDroidApiContract.REVIEW_NOTE_ID_COLUMN, mutation.noteId))
            add(ProviderValue.IntValue(AnkiDroidApiContract.REVIEW_CARD_ORD_COLUMN, mutation.cardOrd))
            when (mutation.kind) {
                ReviewerActionKind.BURY -> add(
                    ProviderValue.IntValue(AnkiDroidApiContract.REVIEW_BURY_COLUMN, 1)
                )
                ReviewerActionKind.SUSPEND -> add(
                    ProviderValue.IntValue(AnkiDroidApiContract.REVIEW_SUSPEND_COLUMN, 1)
                )
                ReviewerActionKind.FLAG -> return AnkiDroidReviewerActionDispatch.NotDispatched(
                    AnkiError.UnsupportedAction("flag_unsupported_by_pinned_contract")
                )
            }
        }
        if (!writePermit.acquire(permitTimeoutMs)) {
            // The wait timed out before anything was issued: provably pre-dispatch.
            return AnkiDroidReviewerActionDispatch.NotDispatched(AnkiError.QueryFailure("provider_write_busy"))
        }
        // This is the one action entry point. `issue` reaches the provider client's single
        // ContentResolver.update call exactly once; it is not retried here or below.
        actionCalls.incrementAndGet()
        AppLogger.i(TAG, "ANKI_REVIEWER_ACTION_PROVIDER_CALL kind=${mutation.kind.name.lowercase()}")
        val result = issue(authority, values)
            ?: return AnkiDroidReviewerActionDispatch.Unknown("action_timeout", callMayStillBeRunning = true)
        return when (result) {
            is ProviderUpdateResult.Returned -> AnkiDroidReviewerActionDispatch.Returned(result.rowCount)
            is ProviderUpdateResult.NotDispatched ->
                AnkiDroidReviewerActionDispatch.NotDispatched(AnkiError.QueryFailure(result.reason))
            is ProviderUpdateResult.Threw -> {
                // The exception class is not a mutation receipt: a permission error or an
                // IllegalArgumentException can arrive after or before provider-side work, and the
                // class alone cannot prove which. Only NotDispatched can claim safe retry.
                AnkiDroidReviewerActionDispatch.Unknown(
                    "action_threw_${result.exceptionClass}", callMayStillBeRunning = false
                )
            }
        }
    }

    /**
     * Issues one provider update in [scope] and waits at most [actionTimeoutMs]. Returns `null` on
     * timeout — the call is still running and will release the permit when it returns.
     */
    private suspend fun issue(authority: String, values: List<ProviderValue>): ProviderUpdateResult? {
        val call = scope.async(start = CoroutineStart.ATOMIC) {
            try {
                providerClient.safeUpdate(authority, AnkiDroidApiContract.SCHEDULE_PATH, values)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                ProviderUpdateResult.Threw(
                    throwable::class.java.simpleName,
                    AnkiDroidFailureClassifier.classify(throwable, AnkiDroidOperationStage.PROVIDER_UPDATE)
                )
            } finally {
                writePermit.release()
            }
        }
        return withTimeoutOrNull(actionTimeoutMs) { call.await() }
    }

    private companion object {
        const val TAG = "AnkiDroidActionGateway"

        /** A provider action normally takes milliseconds; this covers collection-lock contention. */
        const val ACTION_TIMEOUT_MS = 15_000L

        /** How long an action waits for a previous (possibly stuck) write before refusing. */
        const val PERMIT_TIMEOUT_MS = 5_000L
    }
}
