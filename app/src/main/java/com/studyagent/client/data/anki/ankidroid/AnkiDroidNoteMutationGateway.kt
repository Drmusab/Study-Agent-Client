package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.common.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * GATE 17 — the only AnkiDroid component that writes note content or a card's deck.
 *
 * Scope, deliberately narrow:
 *
 * - **one** provider `update` per call, on the pinned `notes/<id>` (fields and/or tags) or
 *   `notes/<id>/cards/<ord>` (deck) endpoint, carrying exactly the columns the pinned contract
 *   defines — see [AnkiDroidNoteMutationMapper];
 * - the physical call is serialized by the **shared** [AnkiDroidWritePermit], so it can never
 *   overlap a rating answer or a reviewer action;
 * - it is never retried here or below. It reports what happened at the mutation boundary and never
 *   decides success; the classification lives in [AnkiDroidNoteMutationMapper.classify].
 */
interface AnkiDroidNoteMutationGateway {

    /** Issues exactly one provider write, or refuses before any IPC. */
    suspend fun submit(authority: String, write: AnkiDroidNoteWrite): AnkiDroidNoteWriteDispatch

    /** Physical note-write `update` calls issued since construction (diagnostics/test evidence). */
    val physicalWriteCalls: Long

    /** True while a provider write issued through the shared permit has not returned. */
    val writeInFlight: Boolean
}

/** What one note-write `update` produced, before any classification. */
sealed interface AnkiDroidNoteWriteDispatch {
    /** Refused before any IPC: provably not dispatched, provably not applied. */
    data class NotDispatched(val error: AnkiError) : AnkiDroidNoteWriteDispatch

    /** The provider returned a row count. Meaning is decided by [AnkiDroidNoteMutationMapper.classify]. */
    data class Returned(val rowCount: Int) : AnkiDroidNoteWriteDispatch

    /** The provider threw. Only the exception class is kept, never a message (it can carry content). */
    data class Threw(val exceptionClass: String) : AnkiDroidNoteWriteDispatch

    /** Issued; no classifiable answer (for example a wait timeout while the call may still run). */
    data class Unknown(val detail: String) : AnkiDroidNoteWriteDispatch
}

class DefaultAnkiDroidNoteMutationGateway(
    private val providerClient: AnkiDroidProviderClient,
    /** Owns an issued provider write so a caller that stops waiting never cancels a live call. */
    private val scope: CoroutineScope,
    /** Required (no default): a second write permit would be a second writer. */
    private val writePermit: AnkiDroidWritePermit,
    private val writeTimeoutMs: Long = WRITE_TIMEOUT_MS,
    private val permitTimeoutMs: Long = PERMIT_TIMEOUT_MS
) : AnkiDroidNoteMutationGateway {

    private val writeCalls = AtomicLong(0L)

    override val physicalWriteCalls: Long get() = writeCalls.get()

    override val writeInFlight: Boolean get() = writePermit.inFlight

    override suspend fun submit(authority: String, write: AnkiDroidNoteWrite): AnkiDroidNoteWriteDispatch {
        if (write.values.isEmpty()) {
            return AnkiDroidNoteWriteDispatch.NotDispatched(AnkiError.InvalidRequest("no_values"))
        }
        if (!writePermit.acquire(permitTimeoutMs)) {
            // The wait timed out before anything was issued: provably pre-dispatch.
            return AnkiDroidNoteWriteDispatch.NotDispatched(AnkiError.QueryFailure("provider_write_busy"))
        }
        // The one note-write entry point. `issue` reaches the provider client's single
        // ContentResolver.update exactly once; it is not retried here or below.
        writeCalls.incrementAndGet()
        AppLogger.i(TAG, "ANKI_NOTE_MUTATION_PROVIDER_CALL columns=${write.values.size}")
        val result = issue(authority, write)
            ?: return AnkiDroidNoteWriteDispatch.Unknown("write_timeout")
        return when (result) {
            is ProviderUpdateResult.Returned -> AnkiDroidNoteWriteDispatch.Returned(result.rowCount)
            is ProviderUpdateResult.NotDispatched ->
                AnkiDroidNoteWriteDispatch.NotDispatched(AnkiError.QueryFailure(result.reason))
            is ProviderUpdateResult.Threw -> AnkiDroidNoteWriteDispatch.Threw(result.exceptionClass)
        }
    }

    /**
     * Issues one provider update in [scope] and waits at most [writeTimeoutMs]. Returns `null` on
     * timeout — the call is still running and releases the permit when it returns.
     */
    private suspend fun issue(authority: String, write: AnkiDroidNoteWrite): ProviderUpdateResult? {
        val call = scope.async(start = CoroutineStart.ATOMIC) {
            try {
                providerClient.safeUpdate(authority, write.path, write.values)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                ProviderUpdateResult.Threw(
                    exceptionClass = throwable::class.java.simpleName,
                    failure = AnkiDroidFailureClassifier.classify(throwable, AnkiDroidOperationStage.PROVIDER_UPDATE)
                )
            } finally {
                writePermit.release()
            }
        }
        return withTimeoutOrNull(writeTimeoutMs) { call.await() }
    }

    private companion object {
        const val TAG = "AnkiDroidNoteMutation"

        /** A provider content write normally takes milliseconds; this covers collection-lock contention. */
        const val WRITE_TIMEOUT_MS = 15_000L

        /** How long a write waits for a previous (possibly stuck) write before refusing. */
        const val PERMIT_TIMEOUT_MS = 5_000L
    }
}
