package com.studyagent.client.core.anki.create

import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCreatedNote
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiNoteModel
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.AnkiResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Why a creation request was refused before any durable record or boundary. Nothing was sent. */
sealed interface NoteCreationRefusal {
    data class UnsupportedCapability(val dimension: String) : NoteCreationRefusal
    data object BackendMismatch : NoteCreationRefusal
    data object BackendUnavailable : NoteCreationRefusal
    data object ModelMissing : NoteCreationRefusal

    /** The model's schema changed since the draft was taken. No silent remapping (PART I §19). */
    data object SchemaDrifted : NoteCreationRefusal
    data class RetryNotAllowed(val status: NoteCreationStatus) : NoteCreationRefusal
    data object UnknownCreation : NoteCreationRefusal

    /** The in-process payload for a retry is gone. Possible only after restart, which abandons it. */
    data object PayloadUnavailable : NoteCreationRefusal
}

/**
 * GATE 18 — every result of one create/retry attempt. Each variant states what is known about the
 * backend, so a caller cannot mistake "not sent" for "not created" — and never the other way.
 */
sealed interface NoteCreationOutcome {

    /** Pre-transaction validation failed. Nothing was recorded or sent. */
    data class ValidationFailed(val issues: List<String>) : NoteCreationOutcome

    data class Refused(val reason: NoteCreationRefusal) : NoteCreationOutcome

    /** Another creation call is already running on this coordinator (duplicate Save input). */
    data class CreationInProgress(val detail: String) : NoteCreationOutcome

    /** Durable state could not be used. Nothing was sent to the backend. */
    data class LedgerUnavailable(val detail: String) : NoteCreationOutcome

    /**
     * The backend confirmed the note AND the durable finalization succeeded. [created] is the
     * authoritative hydration read — it, not the draft, is the presentation truth (INV-18-16).
     */
    data class Created(
        val record: NoteCreationRecord,
        val created: AnkiCreatedNote
    ) : NoteCreationOutcome

    /**
     * Creation is confirmed, but the post-create read failed. The creation REMAINS created
     * (INV-18-09): this is a read failure, reported separately, never a reason to re-create.
     */
    data class CreatedHydrationFailed(
        val record: NoteCreationRecord,
        val error: AnkiError
    ) : NoteCreationOutcome

    /** Proven: no note exists. The record is RETRY_ALLOWED; a retry is an explicit user action. */
    data class RetryAvailable(
        val record: NoteCreationRecord,
        val error: AnkiError
    ) : NoteCreationOutcome

    /**
     * A media step's outcome is unknown and the plan stopped before the note boundary: no note
     * effect is possible, but the stored state of that media is unclear. The record is ABANDONED
     * with the orphan-media risk disclosed — never cleaned up automatically (§20).
     */
    data class MediaOutcomeUnclear(
        val record: NoteCreationRecord,
        val error: AnkiError
    ) : NoteCreationOutcome

    /**
     * The note may or may not exist (unknown id). Never retried (INV-18-08); the only escape is a
     * human attestation after checking the collection.
     */
    data class AmbiguousCreation(
        val record: NoteCreationRecord,
        val error: AnkiError?
    ) : NoteCreationOutcome
}

/** Result of the read-only recovery/attestation surface. */
sealed interface NoteCreationRecoveryOutcome {
    data class Resolved(val record: NoteCreationRecord) : NoteCreationRecoveryOutcome
    data class StillAmbiguous(val record: NoteCreationRecord) : NoteCreationRecoveryOutcome

    /** The created note, re-read from the backend (hydration resume for a CREATED record). */
    data class Hydrated(val record: NoteCreationRecord, val created: AnkiCreatedNote) : NoteCreationRecoveryOutcome
    data class HydrationFailed(val record: NoteCreationRecord, val error: AnkiError) : NoteCreationRecoveryOutcome

    data object NotFound : NoteCreationRecoveryOutcome
    data class LedgerUnavailable(val detail: String) : NoteCreationRecoveryOutcome
    data object Busy : NoteCreationRecoveryOutcome
}

interface NoteCreationCoordinator {

    /**
     * Records and runs one new creation. Media boundaries (if any) are crossed first, each durably
     * marked; the note boundary is crossed last, exactly once, after its durable mark.
     */
    suspend fun create(draft: AddNoteDraft): NoteCreationOutcome

    /**
     * Retries a RETRY_ALLOWED creation with the same id. Requires the in-process payload; after a
     * restart that payload is gone and the refusal says so. AMBIGUOUS is never retried.
     */
    suspend fun retry(creationId: NoteCreationId): NoteCreationOutcome

    /**
     * Closes an AMBIGUOUS creation on an explicit human attestation. Exists because the pinned
     * backends offer no reconciliation (CONTRACT-18-20): without it an ambiguous record could never
     * be closed. Recorded as an attestation, never presented as backend proof.
     */
    suspend fun resolveAmbiguous(
        creationId: NoteCreationId,
        attestation: NoteCreationAttestation
    ): NoteCreationRecoveryOutcome

    /** Re-reads a CREATED creation's note and generated cards from the backend (hydration resume). */
    suspend fun hydrate(creationId: NoteCreationId): NoteCreationRecoveryOutcome

    /** Read-only: every non-terminal creation record for one backend (recovery surface). */
    suspend fun activeCreationsFor(backendId: AnkiBackendId): List<NoteCreationRecord>

    /** Read-only: every unresolved (CREATING_NOTE / AMBIGUOUS) creation record. */
    suspend fun unresolvedCreations(): List<NoteCreationRecord>
}

/**
 * GATE 18 — the ONLY component that creates notes or stores creation media. Ordering, in every
 * path (docs/GATE_18 §15):
 *
 * 1. validate the draft (pure; no record);
 * 2. refuse unsupported capabilities, foreign backends, missing or drifted models;
 * 3. persist PREPARED;
 * 4. for each media step: persist the boundary mark, dispatch one store, persist the outcome —
 *    a stored name is durable before the next boundary is entered;
 * 5. re-read the schema (drift -> ABANDONED, no note boundary);
 * 6. persist the note-boundary mark (CREATING_NOTE) — no note write is issued before this is
 *    durable;
 * 7. dispatch ONE create-note call with fields assembled ONLY from backend-returned names;
 * 8. persist the terminal status from the backend's answer: CREATED (then hydrate), RETRY_ALLOWED
 *    (proven non-creation) or AMBIGUOUS (unknown — never retried);
 * 9. hydrate through an authoritative read; its failure keeps the creation CREATED.
 *
 * One coordinator call runs at a time: a second create/retry while one is in flight is refused
 * ([NoteCreationOutcome.CreationInProgress]) — duplicate Save inputs never produce duplicate
 * dispatches (VER-18-13). The coordinator touches no review session, rating or reviewer-action
 * surface (INV-18-19).
 */
class DefaultNoteCreationCoordinator(
    private val backend: AnkiBackend,
    private val ledger: NoteCreationLedger,
    private val idSource: NoteCreationIdSource = UuidNoteCreationIdSource,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : NoteCreationCoordinator {

    private val execution = Mutex()

    /** In-process payloads for same-id retries; discarded with the process (restart abandons). */
    private val payloadMutex = Mutex()
    private val payloads = HashMap<NoteCreationId, AddNoteDraft>()

    override suspend fun create(draft: AddNoteDraft): NoteCreationOutcome = runExclusive {
        runCreation(draft, retryOf = null)
    }

    override suspend fun retry(creationId: NoteCreationId): NoteCreationOutcome = runExclusive {
        val record = when (val read = ledger.get(creationId)) {
            is CreationLedgerResult.Ok -> read.value
                ?: return@runExclusive NoteCreationOutcome.Refused(NoteCreationRefusal.UnknownCreation)
            is CreationLedgerResult.Rejected ->
                return@runExclusive NoteCreationOutcome.Refused(NoteCreationRefusal.UnknownCreation)
            is CreationLedgerResult.Unavailable ->
                return@runExclusive NoteCreationOutcome.LedgerUnavailable(read.detail)
        }
        if (!record.status.userRetryAllowed) {
            return@runExclusive NoteCreationOutcome.Refused(
                NoteCreationRefusal.RetryNotAllowed(record.status)
            )
        }
        val draft = payloadFor(creationId)
            ?: return@runExclusive NoteCreationOutcome.Refused(NoteCreationRefusal.PayloadUnavailable)
        // Reset to PREPARED durably first; runCreation then repeats the full fresh preflight
        // (capabilities, backend context, validation, live schema) exactly like a first attempt.
        val reset = ledgerApply(creationId, NoteCreationStatus.RETRY_ALLOWED, NoteCreationEvent.RetryRequested)
            ?: return@runExclusive NoteCreationOutcome.LedgerUnavailable("retry_reset_not_persisted")
        runCreation(draft, retryOf = reset)
    }

    override suspend fun resolveAmbiguous(
        creationId: NoteCreationId,
        attestation: NoteCreationAttestation
    ): NoteCreationRecoveryOutcome {
        if (execution.isLocked) return NoteCreationRecoveryOutcome.Busy
        val record = when (val read = ledger.get(creationId)) {
            is CreationLedgerResult.Ok -> read.value ?: return NoteCreationRecoveryOutcome.NotFound
            is CreationLedgerResult.Rejected -> return NoteCreationRecoveryOutcome.NotFound
            is CreationLedgerResult.Unavailable ->
                return NoteCreationRecoveryOutcome.LedgerUnavailable(read.detail)
        }
        if (record.status != NoteCreationStatus.AMBIGUOUS) {
            return NoteCreationRecoveryOutcome.Resolved(record)
        }
        val event = when (attestation) {
            NoteCreationAttestation.CREATED_IN_COLLECTION -> NoteCreationEvent.UserAttestedCreated
            NoteCreationAttestation.ABSENT_FROM_COLLECTION -> NoteCreationEvent.UserAttestedAbsent
        }
        val next = ledgerApply(creationId, NoteCreationStatus.AMBIGUOUS, event)
            ?: return NoteCreationRecoveryOutcome.LedgerUnavailable("attestation_not_persisted")
        return NoteCreationRecoveryOutcome.Resolved(next)
    }

    override suspend fun hydrate(creationId: NoteCreationId): NoteCreationRecoveryOutcome {
        val record = when (val read = ledger.get(creationId)) {
            is CreationLedgerResult.Ok -> read.value ?: return NoteCreationRecoveryOutcome.NotFound
            is CreationLedgerResult.Rejected -> return NoteCreationRecoveryOutcome.NotFound
            is CreationLedgerResult.Unavailable ->
                return NoteCreationRecoveryOutcome.LedgerUnavailable(read.detail)
        }
        if (record.status != NoteCreationStatus.CREATED) {
            return NoteCreationRecoveryOutcome.Resolved(record)
        }
        val noteId = record.createdNoteId
            ?: return NoteCreationRecoveryOutcome.Resolved(record)
        return readCreatedNote(record, noteId)
    }

    override suspend fun activeCreationsFor(backendId: AnkiBackendId): List<NoteCreationRecord> =
        when (val read = ledger.activeFor(backendId)) {
            is CreationLedgerResult.Ok -> read.value
            else -> emptyList()
        }

    override suspend fun unresolvedCreations(): List<NoteCreationRecord> =
        when (val read = ledger.unresolved()) {
            is CreationLedgerResult.Ok -> read.value
            else -> emptyList()
        }

    // ---------------------------------------------------------------- engine

    /**
     * One create/retry at a time. A second request while one runs is refused instead of queueing,
     * so duplicate Save inputs can never produce duplicate dispatches (VER-18-13).
     */
    private suspend fun runExclusive(block: suspend () -> NoteCreationOutcome): NoteCreationOutcome =
        if (execution.isLocked) {
            NoteCreationOutcome.CreationInProgress("creation_already_running")
        } else {
            execution.lock()
            try {
                block()
            } finally {
                execution.unlock()
            }
        }

    /** First-attempt and retry preflight: capability, context, validation, live model. */
    private sealed interface Preflight {
        data class Refused(val reason: NoteCreationRefusal) : Preflight
        data class Invalid(val issues: List<String>) : Preflight
        data object Ready : Preflight
    }

    private suspend fun preflight(draft: AddNoteDraft): Preflight {
        if (draft.backendId != backend.id) return Preflight.Refused(NoteCreationRefusal.BackendMismatch)
        val caps = backend.capabilities.value
        if (!caps.createNotes) {
            return Preflight.Refused(NoteCreationRefusal.UnsupportedCapability("create_notes"))
        }
        if (!caps.noteModelListing) {
            return Preflight.Refused(NoteCreationRefusal.UnsupportedCapability("note_model_listing"))
        }
        if (draft.media.isNotEmpty() && !caps.storeMedia) {
            return Preflight.Refused(NoteCreationRefusal.UnsupportedCapability("store_media"))
        }
        val issues = NoteCreationValidator.validate(draft, caps)
        if (issues.isNotEmpty()) return Preflight.Invalid(issues)
        val fresh = when (val listing = backend.getNoteModels()) {
            is AnkiResult.Failure -> return Preflight.Refused(NoteCreationRefusal.BackendUnavailable)
            is AnkiResult.Success ->
                listing.value.firstOrNull { it.ref.modelId == draft.model.ref.modelId }
        } ?: return Preflight.Refused(NoteCreationRefusal.ModelMissing)
        if (NoteCreationValidator.schemaDrift(draft.model, fresh)) {
            return Preflight.Refused(NoteCreationRefusal.SchemaDrifted)
        }
        return Preflight.Ready
    }

    private suspend fun runCreation(
        draft: AddNoteDraft,
        retryOf: NoteCreationRecord?
    ): NoteCreationOutcome {
        when (val pre = preflight(draft)) {
            is Preflight.Refused -> return NoteCreationOutcome.Refused(pre.reason)
            is Preflight.Invalid -> return NoteCreationOutcome.ValidationFailed(pre.issues)
            is Preflight.Ready -> Unit
        }

        val creationId = retryOf?.creationId ?: idSource.next()
        val record = retryOf ?: newPreparedNoteCreationRecord(
            creationId = creationId,
            backendId = draft.backendId,
            collectionKey = draft.collectionKey,
            modelId = draft.model.ref.modelId,
            modelName = draft.model.name,
            fieldCount = draft.model.fieldCount,
            tagCount = draft.tags.size,
            mediaSteps = draft.media.mapIndexed { index, pending ->
                CreationMediaStep(index, pending.requestedName, pending.kind)
            },
            nowEpochMs = nowEpochMs()
        )
        if (retryOf == null) {
            when (val created = ledger.create(record)) {
                is CreationLedgerResult.Ok -> Unit
                is CreationLedgerResult.Rejected ->
                    return NoteCreationOutcome.Refused(NoteCreationRefusal.UnknownCreation)
                is CreationLedgerResult.Unavailable ->
                    return NoteCreationOutcome.LedgerUnavailable(created.detail)
            }
            rememberPayload(creationId, draft)
        }

        // ---- media boundaries, each durably marked before dispatch (docs/GATE_18 §15) ----
        var current = record
        draft.media.forEachIndexed { index, pending ->
            val enterFrom = current.status
            val entered = ledgerApply(
                creationId, enterFrom, NoteCreationEvent.EnterMediaBoundary(index)
            ) ?: return NoteCreationOutcome.LedgerUnavailable("media_boundary_mark_not_persisted")
            current = entered

            val result = backend.storeAnkiMedia(
                StoreMediaBackendRequest(
                    backendId = draft.backendId,
                    contentUri = pending.contentUri,
                    preferredName = pending.requestedName,
                    mimeType = pending.mimeType,
                    sizeBytes = pending.sizeBytes
                )
            )
            current = when (result) {
                is MediaStoreBackendResult.Stored -> {
                    ledgerApply(creationId, NoteCreationStatus.STORING_MEDIA, NoteCreationEvent.MediaStored(index, result.mediaName))
                        ?: return failClosedAfterMediaStore(creationId, "media_store_name_not_persisted")
                }
                is MediaStoreBackendResult.ConfirmedNotStored -> {
                    val updated = ledgerApply(
                        creationId, NoteCreationStatus.STORING_MEDIA, NoteCreationEvent.MediaConfirmedNotStored
                    ) ?: return NoteCreationOutcome.LedgerUnavailable("media_refusal_not_persisted")
                    forgetPayloadIfTerminal(updated)
                    return NoteCreationOutcome.RetryAvailable(updated, result.error)
                }
                is MediaStoreBackendResult.OutcomeUnknown -> {
                    val updated = ledgerApply(
                        creationId, NoteCreationStatus.STORING_MEDIA, NoteCreationEvent.MediaOutcomeUnknown
                    ) ?: return NoteCreationOutcome.LedgerUnavailable("media_unknown_not_persisted")
                    forgetPayloadIfTerminal(updated)
                    return NoteCreationOutcome.MediaOutcomeUnclear(updated, result.error)
                }
            }
        }

        // ---- schema re-read immediately before the note boundary (drift -> refuse, no effect) ----
        val freshModel = when (val listing = backend.getNoteModels()) {
            is AnkiResult.Failure -> {
                val abandoned = ledgerApply(
                    creationId, current.status, NoteCreationEvent.PreBoundaryRefused
                ) ?: return NoteCreationOutcome.LedgerUnavailable("drift_close_not_persisted")
                return NoteCreationOutcome.Refused(NoteCreationRefusal.BackendUnavailable).also {
                    forgetPayloadIfTerminal(abandoned)
                }
            }
            is AnkiResult.Success ->
                listing.value.firstOrNull { it.ref.modelId == draft.model.ref.modelId }
        }
        if (freshModel == null || NoteCreationValidator.schemaDrift(draft.model, freshModel)) {
            val abandoned = ledgerApply(
                creationId, current.status, NoteCreationEvent.PreBoundaryRefused
            ) ?: return NoteCreationOutcome.LedgerUnavailable("drift_close_not_persisted")
            forgetPayloadIfTerminal(abandoned)
            return NoteCreationOutcome.Refused(
                if (freshModel == null) NoteCreationRefusal.ModelMissing else NoteCreationRefusal.SchemaDrifted
            )
        }

        // ---- note boundary: durable mark first, then exactly one dispatch ----
        val boundary = ledgerApply(creationId, current.status, NoteCreationEvent.EnterNoteBoundary)
            ?: return NoteCreationOutcome.LedgerUnavailable("note_boundary_mark_not_persisted")
        current = boundary

        val fields = if (draft.media.isEmpty()) {
            NoteCreationFieldAssembly.assembleWithoutMedia(draft)
        } else {
            NoteCreationFieldAssembly.assemble(draft, current.storedMediaNames)
                ?: return noteBoundaryUnknown(creationId, AnkiError.InvalidRequest("media_names_incomplete"))
        }
        val result = backend.createAnkiNote(
            CreateNoteBackendRequest(
                backendId = draft.backendId,
                model = draft.model.ref,
                orderedFields = fields,
                tags = draft.tags
            )
        )
        return when (result) {
            is CreateNoteBackendResult.ConfirmedCreated -> {
                val created = ledgerApply(
                    creationId, NoteCreationStatus.CREATING_NOTE,
                    NoteCreationEvent.BackendConfirmedCreated(result.noteId)
                ) ?: return failClosedAfterConfirmedCreation(creationId)
                finishCreated(created, result.noteId)
            }
            is CreateNoteBackendResult.ConfirmedNotCreated -> {
                val updated = ledgerApply(
                    creationId, NoteCreationStatus.CREATING_NOTE, NoteCreationEvent.BackendConfirmedNotCreated
                ) ?: return NoteCreationOutcome.LedgerUnavailable("not_created_not_persisted")
                NoteCreationOutcome.RetryAvailable(updated, result.error)
            }
            is CreateNoteBackendResult.OutcomeUnknown -> {
                val updated = ledgerApply(
                    creationId, NoteCreationStatus.CREATING_NOTE, NoteCreationEvent.BackendOutcomeUnknown
                ) ?: return NoteCreationOutcome.LedgerUnavailable("unknown_not_persisted")
                forgetPayloadIfTerminal(updated)
                NoteCreationOutcome.AmbiguousCreation(updated, result.error)
            }
        }
    }

    /** CREATED is durable: hydrate through the backend's own read; a read failure never re-creates. */
    private suspend fun finishCreated(
        record: NoteCreationRecord,
        noteId: String
    ): NoteCreationOutcome {
        forgetPayloadIfTerminal(record)
        return when (val hydration = readCreatedNote(record, noteId)) {
            is NoteCreationRecoveryOutcome.Hydrated ->
                NoteCreationOutcome.Created(hydration.record, hydration.created)
            is NoteCreationRecoveryOutcome.HydrationFailed ->
                NoteCreationOutcome.CreatedHydrationFailed(hydration.record, hydration.error)
            else -> NoteCreationOutcome.CreatedHydrationFailed(
                record, AnkiError.Unknown(cause = "hydration_unavailable")
            )
        }
    }

    private suspend fun readCreatedNote(
        record: NoteCreationRecord,
        noteId: String
    ): NoteCreationRecoveryOutcome {
        val noteRef = AnkiNoteRef(record.backendId, noteId, record.collectionKey)
        return when (val read = backend.resolveCreatedNote(noteRef)) {
            is AnkiResult.Success -> NoteCreationRecoveryOutcome.Hydrated(record, read.value)
            is AnkiResult.Failure -> {
                // Record the separate read failure; the creation itself stays CREATED (INV-18-09).
                ledgerApply(record.creationId, NoteCreationStatus.CREATED, NoteCreationEvent.NoteHydrationFailed)
                NoteCreationRecoveryOutcome.HydrationFailed(record, read.error)
            }
        }
    }

    /**
     * The backend confirmed creation but the durable CREATED write failed: fail closed. The note is
     * treated as potentially created; it is NEVER re-created (INV-18-09), and the record reports an
     * ambiguity even though the backend answer itself was positive.
     */
    private suspend fun failClosedAfterConfirmedCreation(creationId: NoteCreationId): NoteCreationOutcome {
        val updated = ledgerApply(
            creationId, NoteCreationStatus.CREATING_NOTE, NoteCreationEvent.FinalizationFailedAfterConfirm
        )
        return if (updated != null) {
            NoteCreationOutcome.AmbiguousCreation(updated, AnkiError.Unknown(cause = "finalization_failed_after_confirm"))
        } else {
            NoteCreationOutcome.LedgerUnavailable("confirmed_creation_not_persisted")
        }
    }

    /** Same fail-closed rule for a media store whose returned name could not be persisted. */
    private suspend fun failClosedAfterMediaStore(
        creationId: NoteCreationId,
        detail: String
    ): NoteCreationOutcome {
        val updated = ledgerApply(
            creationId, NoteCreationStatus.STORING_MEDIA, NoteCreationEvent.MediaOutcomeUnknown
        )
        return if (updated != null) {
            NoteCreationOutcome.MediaOutcomeUnclear(updated, AnkiError.Unknown(cause = detail))
        } else {
            NoteCreationOutcome.LedgerUnavailable(detail)
        }
    }

    private suspend fun noteBoundaryUnknown(creationId: NoteCreationId, error: AnkiError): NoteCreationOutcome {
        // The note boundary mark is durable but no dispatch happened (assembly failed): the honest
        // state is ambiguous, because the durable record says the boundary was entered. No retry.
        val updated = ledgerApply(
            creationId, NoteCreationStatus.CREATING_NOTE, NoteCreationEvent.BackendOutcomeUnknown
        )
        return if (updated != null) {
            NoteCreationOutcome.AmbiguousCreation(updated, error)
        } else {
            NoteCreationOutcome.LedgerUnavailable("unknown_not_persisted")
        }
    }

    private suspend fun ledgerApply(
        creationId: NoteCreationId,
        expected: NoteCreationStatus,
        event: NoteCreationEvent
    ): NoteCreationRecord? = when (val applied = ledger.apply(creationId, expected, event)) {
        is CreationLedgerResult.Ok -> applied.value
        is CreationLedgerResult.Rejected -> null
        is CreationLedgerResult.Unavailable -> null
    }

    private suspend fun rememberPayload(creationId: NoteCreationId, draft: AddNoteDraft) {
        withContext(NonCancellable) {
            payloadMutex.lock()
            try {
                payloads[creationId] = draft
            } finally {
                payloadMutex.unlock()
            }
        }
    }

    private suspend fun payloadFor(creationId: NoteCreationId): AddNoteDraft? {
        payloadMutex.lock()
        return try {
            payloads[creationId]
        } finally {
            payloadMutex.unlock()
        }
    }

    private suspend fun forgetPayloadIfTerminal(record: NoteCreationRecord) {
        if (!record.status.isTerminal) return
        payloadMutex.lock()
        try {
            payloads.remove(record.creationId)
        } finally {
            payloadMutex.unlock()
        }
    }
}
