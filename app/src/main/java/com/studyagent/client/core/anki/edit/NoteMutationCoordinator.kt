package com.studyagent.client.core.anki.edit

import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiCardDetails
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Why a request was refused before any mutation was recorded. Nothing was sent to the backend. */
sealed interface NoteMutationRefusal {
    data class UnsupportedDimension(val dimension: String) : NoteMutationRefusal

    /** The resulting last field is empty and this backend cannot represent that write. */
    data object TrailingEmptyFieldNotRepresentable : NoteMutationRefusal
    data object BackendMismatch : NoteMutationRefusal
    data class DeckListingUnavailable(val error: AnkiError) : NoteMutationRefusal
    data object SaveInProgress : NoteMutationRefusal
    data class StudyActivityOverlaps(val block: NoteEditBlock) : NoteMutationRefusal
    data class RetryNotAllowed(val status: NoteMutationStatus) : NoteMutationRefusal
    data object UnknownMutation : NoteMutationRefusal

    /** The in-process payload for a retry is gone. Possible only after restart, which abandons the record. */
    data object PayloadUnavailable : NoteMutationRefusal
    data class ConflictNotResolvable(val status: NoteMutationStatus) : NoteMutationRefusal
}

/**
 * GATE 17 — every terminal or interim result of one save/retry attempt. Each variant states what is
 * known about the backend, so a caller cannot mistake "not sent" for "not applied".
 */
sealed interface NoteMutationOutcome {
    /** The draft produced an empty patch. Nothing was recorded or sent. */
    data object NoChanges : NoteMutationOutcome

    /** Pre-transaction validation failed. Nothing was recorded or sent. */
    data class ValidationFailed(val errors: List<NoteEditValidationError>) : NoteMutationOutcome

    data class Refused(val reason: NoteMutationRefusal) : NoteMutationOutcome

    /** Another non-terminal mutation already covers this note. */
    data class ActiveMutationExists(val record: NoteMutationRecord) : NoteMutationOutcome

    /** Durable state could not be used. Nothing was sent to the backend. */
    data class LedgerUnavailable(val detail: String) : NoteMutationOutcome

    /** The pre-write re-read failed. The record stays PREPARED and nothing was sent. */
    data class ReadFailed(val record: NoteMutationRecord, val error: AnkiError) : NoteMutationOutcome

    /** The note changed since it was read. Nothing was written. Resolve by starting a new mutation. */
    data class Conflict(val record: NoteMutationRecord, val latest: NoteEditBase?) : NoteMutationOutcome

    /** The backend confirmed the full plan. [refreshed] is the authoritative post-write read. */
    data class Applied(
        val record: NoteMutationRecord,
        val refreshed: AnkiCardDetails?,
        val refreshError: AnkiError?
    ) : NoteMutationOutcome

    /** Proven not applied. The record is RETRY_ALLOWED and can be retried with the same id. */
    data class RetryAvailable(val record: NoteMutationRecord, val error: AnkiError) : NoteMutationOutcome

    /** Unknown or partial outcome, or progress that could not be recorded. Do not retry. */
    data class VerificationRequired(val record: NoteMutationRecord, val reason: String) : NoteMutationOutcome
}

sealed interface NoteMutationRecoveryOutcome {
    data class Resolved(val record: NoteMutationRecord) : NoteMutationRecoveryOutcome
    data class StillAmbiguous(val record: NoteMutationRecord, val error: AnkiError?) : NoteMutationRecoveryOutcome
    data class Unchanged(val record: NoteMutationRecord) : NoteMutationRecoveryOutcome
    data object NotFound : NoteMutationRecoveryOutcome
    data class LedgerUnavailable(val detail: String) : NoteMutationRecoveryOutcome
    data object Busy : NoteMutationRecoveryOutcome
}

interface NoteMutationCoordinator {
    /** Records and runs a new mutation for [base] (validated against the same [draft]). */
    suspend fun save(base: NoteEditBase, draft: NoteEditDraft): NoteMutationOutcome

    /** Retries a RETRY_ALLOWED mutation with the same id. AMBIGUOUS is refused. */
    suspend fun retry(mutationId: NoteMutationId): NoteMutationOutcome

    /** Continues a PREPARED mutation whose pre-write re-read failed. No backend effect until the boundary. */
    suspend fun resumePrepared(mutationId: NoteMutationId): NoteMutationOutcome

    /** Starts a new mutation that supersedes a CONFLICT mutation. Never reuses the old id. */
    suspend fun startAfterConflict(
        conflictedId: NoteMutationId,
        base: NoteEditBase,
        draft: NoteEditDraft
    ): NoteMutationOutcome

    /** Read-only evidence check for an AMBIGUOUS or interrupted mutation. */
    suspend fun recover(mutationId: NoteMutationId): NoteMutationRecoveryOutcome
}

/**
 * GATE 17 — the only component that writes note content. Ordering, in every path:
 *
 * 1. validate and build the patch (pure; no record);
 * 2. refuse unsupported dimensions and deck targets that cannot be verified;
 * 3. refuse when another active mutation or unresolved Study work covers the note;
 * 4. persist PREPARED;
 * 5. re-read the note (`getCardDetails`); drift -> PREPARED -> CONFLICT, no write;
 * 6. persist SUBMITTING (the boundary) — no backend write is issued before this is durable;
 * 7. issue one backend write per plan operation; each later operation persists its index first;
 * 8. classify the result and persist the terminal status;
 * 9. refresh the card authoritatively (APPLIED only).
 *
 * One coordinator call runs at a time. A second call while one is in flight is refused ([SaveInProgress]).
 */
class DefaultNoteMutationCoordinator(
    private val backend: AnkiBackend,
    private val ledger: NoteMutationLedger,
    private val safety: NoteEditSafetyPolicy,
    private val ids: NoteMutationIdSource = UuidNoteMutationIdSource,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : NoteMutationCoordinator {

    private val gate = Mutex()

    /**
     * In-process payload for every non-terminal mutation started by this process. It holds note
     * content, so it is never persisted. Guarded by [gate].
     */
    private val pending = HashMap<NoteMutationId, PendingMutation>()

    private class PendingMutation(
        val base: NoteEditBase,
        val fieldValues: Map<Int, String>,
        val tags: List<String>?,
        val deckTo: AnkiDeckRef?
    )

    override suspend fun save(base: NoteEditBase, draft: NoteEditDraft): NoteMutationOutcome =
        withGate(NoteMutationOutcome.Refused(NoteMutationRefusal.SaveInProgress)) {
            saveLocked(base, draft, supersedes = null)
        }

    override suspend fun startAfterConflict(
        conflictedId: NoteMutationId,
        base: NoteEditBase,
        draft: NoteEditDraft
    ): NoteMutationOutcome = withGate(NoteMutationOutcome.Refused(NoteMutationRefusal.SaveInProgress)) {
        when (val prior = ledger.get(conflictedId)) {
            is NoteLedgerResult.Unavailable -> NoteMutationOutcome.LedgerUnavailable(prior.detail)
            is NoteLedgerResult.Rejected -> NoteMutationOutcome.LedgerUnavailable(prior.reason)
            is NoteLedgerResult.Ok -> {
                val record = prior.value
                    ?: return@withGate NoteMutationOutcome.Refused(NoteMutationRefusal.UnknownMutation)
                if (record.status != NoteMutationStatus.CONFLICT) {
                    return@withGate NoteMutationOutcome.Refused(NoteMutationRefusal.ConflictNotResolvable(record.status))
                }
                saveLocked(base, draft, supersedes = conflictedId)
            }
        }
    }

    override suspend fun retry(mutationId: NoteMutationId): NoteMutationOutcome =
        withGate(NoteMutationOutcome.Refused(NoteMutationRefusal.SaveInProgress)) {
            val record = when (val read = ledger.get(mutationId)) {
                is NoteLedgerResult.Unavailable -> return@withGate NoteMutationOutcome.LedgerUnavailable(read.detail)
                is NoteLedgerResult.Rejected -> return@withGate NoteMutationOutcome.LedgerUnavailable(read.reason)
                is NoteLedgerResult.Ok -> read.value
                    ?: return@withGate NoteMutationOutcome.Refused(NoteMutationRefusal.UnknownMutation)
            }
            // Only RETRY_ALLOWED may be retried. AMBIGUOUS and terminal statuses are refused here,
            // before any transition, so a refusal changes nothing.
            if (record.status != NoteMutationStatus.RETRY_ALLOWED) {
                return@withGate NoteMutationOutcome.Refused(NoteMutationRefusal.RetryNotAllowed(record.status))
            }
            if (!pending.containsKey(mutationId)) {
                return@withGate NoteMutationOutcome.Refused(NoteMutationRefusal.PayloadUnavailable)
            }
            unsupportedDimensionFor(record.plan)?.let {
                return@withGate NoteMutationOutcome.Refused(NoteMutationRefusal.UnsupportedDimension(it))
            }
            val requeued = when (val step = ledger.apply(mutationId, NoteMutationStatus.RETRY_ALLOWED, NoteMutationEvent.RetryRequested)) {
                is NoteLedgerResult.Unavailable -> return@withGate NoteMutationOutcome.LedgerUnavailable(step.detail)
                is NoteLedgerResult.Rejected -> return@withGate NoteMutationOutcome.LedgerUnavailable(step.reason)
                is NoteLedgerResult.Ok -> step.value
            }
            runFromPrepared(requeued)
        }

    override suspend fun resumePrepared(mutationId: NoteMutationId): NoteMutationOutcome =
        withGate(NoteMutationOutcome.Refused(NoteMutationRefusal.SaveInProgress)) {
            val record = when (val read = ledger.get(mutationId)) {
                is NoteLedgerResult.Unavailable -> return@withGate NoteMutationOutcome.LedgerUnavailable(read.detail)
                is NoteLedgerResult.Rejected -> return@withGate NoteMutationOutcome.LedgerUnavailable(read.reason)
                is NoteLedgerResult.Ok -> read.value
                    ?: return@withGate NoteMutationOutcome.Refused(NoteMutationRefusal.UnknownMutation)
            }
            if (record.status != NoteMutationStatus.PREPARED) {
                return@withGate NoteMutationOutcome.Refused(NoteMutationRefusal.RetryNotAllowed(record.status))
            }
            if (!pending.containsKey(mutationId)) {
                return@withGate NoteMutationOutcome.Refused(NoteMutationRefusal.PayloadUnavailable)
            }
            runFromPrepared(record)
        }

    override suspend fun recover(mutationId: NoteMutationId): NoteMutationRecoveryOutcome {
        if (!gate.tryLock()) return NoteMutationRecoveryOutcome.Busy
        try {
            val record = when (val read = ledger.get(mutationId)) {
                is NoteLedgerResult.Unavailable -> return NoteMutationRecoveryOutcome.LedgerUnavailable(read.detail)
                is NoteLedgerResult.Rejected -> return NoteMutationRecoveryOutcome.LedgerUnavailable(read.reason)
                is NoteLedgerResult.Ok -> read.value ?: return NoteMutationRecoveryOutcome.NotFound
            }
            return when (record.status) {
                NoteMutationStatus.SUBMITTING -> {
                    // A submitted write whose outcome was never recorded (for example a cancelled call).
                    when (val step = ledger.apply(mutationId, NoteMutationStatus.SUBMITTING, NoteMutationEvent.RecoveryNormalizedUnknown)) {
                        is NoteLedgerResult.Ok -> NoteMutationRecoveryOutcome.StillAmbiguous(step.value, null)
                        is NoteLedgerResult.Unavailable -> NoteMutationRecoveryOutcome.LedgerUnavailable(step.detail)
                        is NoteLedgerResult.Rejected -> NoteMutationRecoveryOutcome.LedgerUnavailable(step.reason)
                    }
                }
                NoteMutationStatus.AMBIGUOUS -> reconcileAmbiguous(record)
                else -> NoteMutationRecoveryOutcome.Unchanged(record)
            }
        } finally {
            gate.unlock()
        }
    }

    private suspend fun reconcileAmbiguous(record: NoteMutationRecord): NoteMutationRecoveryOutcome {
        val evidence = backend.reconcileNoteMutation(
            NoteMutationReconciliationRequest(
                mutationId = record.mutationId,
                cardRef = record.cardRef,
                noteRef = record.noteRef,
                plan = record.plan,
                lastEnteredOperation = record.lastEnteredOperation
            )
        )
        return when (evidence) {
            is NoteMutationReconciliationResult.ConfirmedApplied -> {
                when (val step = ledger.apply(record.mutationId, NoteMutationStatus.AMBIGUOUS, NoteMutationEvent.ReconciliationConfirmedApplied)) {
                    is NoteLedgerResult.Ok -> {
                        pending.remove(record.mutationId)
                        NoteMutationRecoveryOutcome.Resolved(step.value)
                    }
                    is NoteLedgerResult.Unavailable -> NoteMutationRecoveryOutcome.LedgerUnavailable(step.detail)
                    is NoteLedgerResult.Rejected -> NoteMutationRecoveryOutcome.LedgerUnavailable(step.reason)
                }
            }
            is NoteMutationReconciliationResult.ConfirmedNotApplied -> {
                // Retry needs the in-process payload. Without it the edit cannot be re-sent under this id.
                if (record.lastEnteredOperation != 0 || !pending.containsKey(record.mutationId)) {
                    NoteMutationRecoveryOutcome.StillAmbiguous(record, null)
                } else {
                    when (val step = ledger.apply(record.mutationId, NoteMutationStatus.AMBIGUOUS, NoteMutationEvent.ReconciliationConfirmedNotApplied)) {
                        is NoteLedgerResult.Ok -> NoteMutationRecoveryOutcome.Resolved(step.value)
                        is NoteLedgerResult.Unavailable -> NoteMutationRecoveryOutcome.LedgerUnavailable(step.detail)
                        is NoteLedgerResult.Rejected -> NoteMutationRecoveryOutcome.LedgerUnavailable(step.reason)
                    }
                }
            }
            is NoteMutationReconciliationResult.Unresolved ->
                NoteMutationRecoveryOutcome.StillAmbiguous(record, evidence.error)
        }
    }

    private suspend fun saveLocked(
        base: NoteEditBase,
        draft: NoteEditDraft,
        supersedes: NoteMutationId?
    ): NoteMutationOutcome {
        if (base.backendId != backend.id) return NoteMutationOutcome.Refused(NoteMutationRefusal.BackendMismatch)

        val validation = NoteEditPlanner.validateDraft(base, draft)
        if (validation.isNotEmpty()) return NoteMutationOutcome.ValidationFailed(validation)

        val patch = NoteEditPlanner.buildPatch(base, draft)
        if (patch.isEmpty) return NoteMutationOutcome.NoChanges

        unsupportedDimensionFor(patch)?.let {
            return NoteMutationOutcome.Refused(NoteMutationRefusal.UnsupportedDimension(it))
        }
        if (patch.fieldChanges.isNotEmpty() &&
            !backend.noteMutationSemantics.trailingEmptyFieldRepresentable &&
            lastFieldAfter(base, patch).isEmpty()
        ) {
            return NoteMutationOutcome.Refused(NoteMutationRefusal.TrailingEmptyFieldNotRepresentable)
        }

        if (patch.deckChange != null) {
            when (val deckCheck = checkDeckTarget(base, patch)) {
                is DeckCheck.ListingFailed ->
                    return NoteMutationOutcome.Refused(NoteMutationRefusal.DeckListingUnavailable(deckCheck.error))
                is DeckCheck.Invalid -> return NoteMutationOutcome.ValidationFailed(deckCheck.errors)
                DeckCheck.Verified -> Unit
            }
        }

        val plan = NoteEditPlanner.buildPlan(patch)

        when (val active = ledger.findActiveForNote(base.backendId, base.noteRef.noteId)) {
            is NoteLedgerResult.Unavailable -> return NoteMutationOutcome.LedgerUnavailable(active.detail)
            is NoteLedgerResult.Rejected -> return NoteMutationOutcome.LedgerUnavailable(active.reason)
            is NoteLedgerResult.Ok -> active.value?.let { return NoteMutationOutcome.ActiveMutationExists(it) }
        }

        safety.blockingStudyActivity(base.noteRef)?.let {
            return NoteMutationOutcome.Refused(NoteMutationRefusal.StudyActivityOverlaps(it))
        }

        val id = ids.next()
        val record = newPreparedNoteMutationRecord(id, base, patch, plan, nowEpochMs(), supersedes)
        when (val created = ledger.create(record)) {
            is NoteLedgerResult.Unavailable -> return NoteMutationOutcome.LedgerUnavailable(created.detail)
            is NoteLedgerResult.Rejected -> return NoteMutationOutcome.LedgerUnavailable(created.reason)
            is NoteLedgerResult.Ok -> Unit
        }
        pending[id] = PendingMutation(
            base = base,
            fieldValues = patch.fieldChanges.associate { it.ordinal to it.newValue },
            tags = patch.tagChange?.newTags,
            deckTo = patch.deckChange?.toDeck
        )
        return runFromPrepared(record)
    }

    /**
     * Everything from the pre-write re-read to the terminal status. Precondition: [record] is
     * PREPARED and its payload is in [pending]. Caller holds [gate].
     */
    private suspend fun runFromPrepared(record: NoteMutationRecord): NoteMutationOutcome {
        val id = record.mutationId
        val payload = pending[id] ?: return NoteMutationOutcome.Refused(NoteMutationRefusal.PayloadUnavailable)

        // Study work can start between recording and writing; check again before any write.
        safety.blockingStudyActivity(record.noteRef)?.let {
            return NoteMutationOutcome.Refused(NoteMutationRefusal.StudyActivityOverlaps(it))
        }

        // Pre-write re-read. A failure here crosses no boundary, so the record stays PREPARED.
        val details = when (val read = backend.getCardDetails(record.cardRef)) {
            is AnkiResult.Failure -> return NoteMutationOutcome.ReadFailed(record, read.error)
            is AnkiResult.Success -> read.value
        }
        val latest = NoteEditBase.from(details)
            ?: return NoteMutationOutcome.ReadFailed(record, AnkiError.DataIntegrityFailure("card_details_not_editable"))

        if (latest != payload.base) {
            return when (val moved = ledger.apply(id, record.status, NoteMutationEvent.PreBoundaryConflict(NoteMutationReason.CONFLICT_BEFORE_WRITE))) {
                is NoteLedgerResult.Ok -> {
                    pending.remove(id)
                    NoteMutationOutcome.Conflict(moved.value, latest)
                }
                is NoteLedgerResult.Unavailable -> NoteMutationOutcome.LedgerUnavailable(moved.detail)
                is NoteLedgerResult.Rejected -> NoteMutationOutcome.LedgerUnavailable(moved.reason)
            }
        }

        val steps = materialize(record, payload, latest)

        // The durable boundary. Failing here means no backend write was issued.
        var current = when (val entered = ledger.apply(id, record.status, NoteMutationEvent.EnterMutationBoundary)) {
            is NoteLedgerResult.Ok -> entered.value
            is NoteLedgerResult.Unavailable -> return NoteMutationOutcome.LedgerUnavailable(entered.detail)
            is NoteLedgerResult.Rejected -> return NoteMutationOutcome.LedgerUnavailable(entered.reason)
        }

        for ((index, step) in steps.withIndex()) {
            if (index > 0) {
                current = when (val advanced = ledger.apply(id, NoteMutationStatus.SUBMITTING, NoteMutationEvent.EnterNextOperation(index))) {
                    is NoteLedgerResult.Ok -> advanced.value
                    // The earlier operation may have applied and this boundary is not recorded. Stop.
                    is NoteLedgerResult.Unavailable -> return NoteMutationOutcome.VerificationRequired(current, "progress_not_recorded")
                    is NoteLedgerResult.Rejected -> return NoteMutationOutcome.VerificationRequired(current, "progress_not_recorded")
                }
            }

            val request = BackendNoteMutationRequest(
                mutationId = id,
                cardRef = current.cardRef,
                noteRef = current.noteRef,
                stepIndex = index,
                step = step
            )
            val result = try {
                callBackend(request)
            } catch (cancelled: CancellationException) {
                // The write may have been issued. Record that before the cancellation propagates.
                withContext(NonCancellable) {
                    ledger.apply(id, NoteMutationStatus.SUBMITTING, NoteMutationEvent.BackendOutcomeUnknown)
                }
                throw cancelled
            }

            when (result) {
                is NoteMutationBackendResult.ConfirmedApplied -> {
                    if (index == steps.lastIndex) return finishApplied(current, record)
                }
                is NoteMutationBackendResult.ConfirmedNotApplied -> {
                    if (index == 0) {
                        return when (val recorded = ledger.apply(id, NoteMutationStatus.SUBMITTING, NoteMutationEvent.BackendConfirmedNotApplied)) {
                            is NoteLedgerResult.Ok -> NoteMutationOutcome.RetryAvailable(recorded.value, result.error)
                            is NoteLedgerResult.Unavailable, is NoteLedgerResult.Rejected ->
                                NoteMutationOutcome.VerificationRequired(current, "not_applied_not_recorded")
                        }
                    }
                    // An earlier operation may have applied. Proven-not-applied for this step is not enough.
                    return markAmbiguous(current)
                }
                is NoteMutationBackendResult.Conflict -> {
                    if (index == 0) {
                        return when (val recorded = ledger.apply(id, NoteMutationStatus.SUBMITTING, NoteMutationEvent.BackendConflict)) {
                            is NoteLedgerResult.Ok -> {
                                pending.remove(id)
                                NoteMutationOutcome.Conflict(recorded.value, result.latest)
                            }
                            is NoteLedgerResult.Unavailable, is NoteLedgerResult.Rejected ->
                                NoteMutationOutcome.VerificationRequired(current, "conflict_not_recorded")
                        }
                    }
                    return markAmbiguous(current)
                }
                is NoteMutationBackendResult.OutcomeUnknown -> return markAmbiguous(current)
            }
        }
        // Unreachable: the plan is non-empty and the last step returns above.
        return markAmbiguous(current)
    }

    /** Persists AMBIGUOUS. If even that fails, the durable record is still SUBMITTING and recovery normalizes it. */
    private suspend fun markAmbiguous(current: NoteMutationRecord): NoteMutationOutcome {
        // The ledger picks PARTIAL_OPERATION_UNKNOWN itself when an earlier operation had applied.
        return when (val recorded = ledger.apply(current.mutationId, NoteMutationStatus.SUBMITTING, NoteMutationEvent.BackendOutcomeUnknown)) {
            is NoteLedgerResult.Ok -> NoteMutationOutcome.VerificationRequired(recorded.value, recorded.value.reason.name)
            is NoteLedgerResult.Unavailable, is NoteLedgerResult.Rejected ->
                NoteMutationOutcome.VerificationRequired(current, "outcome_not_recorded")
        }
    }

    private suspend fun finishApplied(current: NoteMutationRecord, record: NoteMutationRecord): NoteMutationOutcome {
        val applied = when (val recorded = ledger.apply(record.mutationId, NoteMutationStatus.SUBMITTING, NoteMutationEvent.BackendConfirmedApplied)) {
            is NoteLedgerResult.Ok -> recorded.value
            is NoteLedgerResult.Unavailable, is NoteLedgerResult.Rejected ->
                return NoteMutationOutcome.VerificationRequired(current, "applied_not_recorded")
        }
        pending.remove(record.mutationId)
        // The authoritative refresh is a read. Its failure never turns APPLIED into anything else.
        return when (val refresh = backend.getCardDetails(record.cardRef)) {
            is AnkiResult.Success -> NoteMutationOutcome.Applied(applied, refresh.value, null)
            is AnkiResult.Failure -> NoteMutationOutcome.Applied(applied, null, refresh.error)
        }
    }

    /**
     * One backend call. Any failure except cancellation is an unknown outcome: the call may have
     * reached the backend. Only the backend itself can classify a failure as proven non-application.
     */
    private suspend fun callBackend(request: BackendNoteMutationRequest): NoteMutationBackendResult {
        val attempt = runCatching { backend.applyNoteMutation(request) }
        val failure = attempt.exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (failure != null) return NoteMutationBackendResult.OutcomeUnknown(AnkiError.Unknown("backend_threw"))
        return attempt.getOrNull() ?: NoteMutationBackendResult.OutcomeUnknown(AnkiError.Unknown("backend_empty"))
    }

    /**
     * Builds the concrete payloads for each plan operation from the verified current state. Field
     * values are the complete positional list, so a field the user did not touch keeps its value.
     */
    private fun materialize(
        record: NoteMutationRecord,
        payload: PendingMutation,
        latest: NoteEditBase
    ): List<NoteMutationStep> = record.plan.operations.map { operation ->
        when (operation) {
            is NoteMutationOperation.UpdateNoteContent -> NoteMutationStep.UpdateNoteContent(
                fieldValues = if (operation.updatesFields) {
                    latest.fields.map { field -> payload.fieldValues[field.ordinal] ?: field.value }
                } else {
                    null
                },
                tags = if (operation.updatesTags) payload.tags else null
            )
            is NoteMutationOperation.ChangeDeck -> NoteMutationStep.ChangeDeck(
                fromDeck = latest.deckRef,
                toDeck = operation.toDeck
            )
        }
    }

    private fun unsupportedDimensionFor(patch: NoteMutationPatch): String? {
        val caps = backend.capabilities.value
        return when {
            !caps.cardDetails -> "card_details"
            patch.fieldChanges.isNotEmpty() && !caps.editNoteFields -> "fields"
            patch.tagChange != null && !caps.editNoteTags -> "tags"
            patch.deckChange != null && !caps.changeCardDeck -> "deck"
            patch.deckChange != null &&
                backend.noteMutationSemantics.deckChangeScope != NoteDeckChangeScope.CARD_ONLY -> "deck_scope"
            else -> null
        }
    }

    /**
     * The value the last field will hold after the patch. A field write always sends the full
     * positional array, so this is the value the provider's trailing-empty handling sees.
     */
    private fun lastFieldAfter(base: NoteEditBase, patch: NoteMutationPatch): String {
        // Field ordinals are positional indices into base.fields (see NoteEditPlanner.validateDraft).
        val lastIndex = base.fields.lastIndex
        if (lastIndex < 0) return ""
        return patch.fieldChanges.firstOrNull { it.ordinal == lastIndex }?.newValue ?: base.fields[lastIndex].value
    }

    private fun unsupportedDimensionFor(plan: NoteMutationPlan): String? {
        val caps = backend.capabilities.value
        return when {
            !caps.cardDetails -> "card_details"
            plan.operations.any { it is NoteMutationOperation.ChangeDeck } && !caps.changeCardDeck -> "deck"
            plan.operations.any { it is NoteMutationOperation.UpdateNoteContent && it.updatesFields } && !caps.editNoteFields -> "fields"
            plan.operations.any { it is NoteMutationOperation.UpdateNoteContent && it.updatesTags } && !caps.editNoteTags -> "tags"
            else -> null
        }
    }

    private sealed interface DeckCheck {
        data object Verified : DeckCheck
        data class Invalid(val errors: List<NoteEditValidationError>) : DeckCheck
        data class ListingFailed(val error: AnkiError) : DeckCheck
    }

    /** Deck targets are verified against the live listing: existence and not-filtered, both ways. */
    private suspend fun checkDeckTarget(base: NoteEditBase, patch: NoteMutationPatch): DeckCheck {
        val change = patch.deckChange ?: return DeckCheck.Verified
        val decks = when (val listing = backend.getDecks()) {
            is AnkiResult.Failure -> return DeckCheck.ListingFailed(listing.error)
            is AnkiResult.Success -> listing.value
        }
        val errors = mutableListOf<NoteEditValidationError>()
        val target = decks.firstOrNull { it.ref.backendId == backend.id && it.ref.deckId == change.toDeck.deckId }
        when {
            target == null -> errors += NoteEditValidationError.DeckTargetNotFound
            target.isFiltered == null -> errors += NoteEditValidationError.DeckTargetUnverifiable
            target.isFiltered == true -> errors += NoteEditValidationError.DeckTargetFiltered
        }
        val source = base.deckRef
        if (source != null) {
            val sourceDeck = decks.firstOrNull { it.ref.deckId == source.deckId }
            if (sourceDeck?.isFiltered == true) errors += NoteEditValidationError.SourceDeckFiltered
        }
        return if (errors.isEmpty()) DeckCheck.Verified else DeckCheck.Invalid(errors)
    }

    private suspend fun withGate(
        busy: NoteMutationOutcome,
        block: suspend () -> NoteMutationOutcome
    ): NoteMutationOutcome {
        if (!gate.tryLock()) return busy
        return try {
            block()
        } finally {
            gate.unlock()
        }
    }
}
