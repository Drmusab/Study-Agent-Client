package com.studyagent.client.ui.screens.editnote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCardDetails
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.edit.NoteEditBase
import com.studyagent.client.core.anki.edit.NoteEditDraft
import com.studyagent.client.core.anki.edit.NoteEditPlanner
import com.studyagent.client.core.anki.edit.NoteMutationAttestation
import com.studyagent.client.core.anki.edit.NoteMutationCoordinator
import com.studyagent.client.core.anki.edit.NoteMutationId
import com.studyagent.client.core.anki.edit.NoteMutationOutcome
import com.studyagent.client.core.anki.edit.NoteMutationRecoveryOutcome
import com.studyagent.client.core.anki.edit.NoteMutationRecord
import com.studyagent.client.core.anki.edit.NoteMutationSemantics
import com.studyagent.client.core.anki.edit.NoteMutationStatus
import com.studyagent.client.core.anki.edit.TagNormalization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * GATE 17 CHECKPOINT 14 — the edit screen's state owner.
 *
 * It holds one authoritative base (a single `getCardDetails` read), the user's draft, the deck
 * listing, and the outcome of the last coordinator call. It contains **no** mutation logic: every
 * write goes through [NoteMutationCoordinator], which owns validation, the durable ordering, the
 * mutation boundary and the post-write verification (INV-17-11, INV-17-17).
 *
 * Backend-context discipline (VER-17-15): a request is correlated with the backend instance and the
 * card reference it was started for, and a response for an obsolete context is dropped instead of
 * being published. A backend switch never reinterprets the old reference.
 */
class EditNoteViewModel(
    initialBackend: AnkiBackend,
    initialCardRef: AnkiCardRef,
    private val coordinator: NoteMutationCoordinator
) : ViewModel() {

    /** Everything the projection needs. Immutable; every change replaces it and re-publishes. */
    private data class Editor(
        val base: NoteEditBase,
        val details: AnkiCardDetails,
        val capabilities: AnkiCapabilities,
        val semantics: NoteMutationSemantics,
        val draftFieldValues: Map<Int, String>,
        val draftTags: List<String>?,
        val selectedDeckId: String?,
        val decks: List<AnkiDeck>,
        val decksLoading: Boolean,
        val decksIssue: AnkiError?,
        val tagIssue: EditNoteIssue?,
        val saveState: EditNoteSaveState,
        val blocked: EditNoteBlockedMutation?,
        /** The mutation this draft would supersede (a CONFLICT record), if any. */
        val supersedes: NoteMutationId?,
        /** The mutation whose recovery actions are on screen, if any. */
        val activeMutationId: NoteMutationId?,
        val saving: Boolean
    )

    private var backend: AnkiBackend = initialBackend
    private var cardRef: AnkiCardRef = initialCardRef
    private var generation: Long = 0L
    private var editor: Editor? = null
    private var loadJob: Job? = null
    private var saveJob: Job? = null

    private val _uiState = MutableStateFlow<EditNoteUiState>(EditNoteUiState.Loading)
    val uiState: StateFlow<EditNoteUiState> = _uiState.asStateFlow()

    /**
     * Set once a save is durable APPLIED and verified by a post-write read. The screen navigates back
     * and the details screen re-reads; [consumeSaved] clears it so it cannot fire twice.
     */
    private val _savedCardRef = MutableStateFlow<AnkiCardRef?>(null)
    val savedCardRef: StateFlow<AnkiCardRef?> = _savedCardRef.asStateFlow()

    init {
        load()
    }

    fun consumeSaved() {
        _savedCardRef.value = null
    }

    /** (Re)reads the authoritative base. A fresh read never carries a stale draft forward. */
    fun load() {
        generation += 1L
        val token = generation
        loadJob?.cancel()

        if (cardRef.backendId != backend.id) {
            editor = null
            _uiState.value = EditNoteUiState.Error(AnkiError.InvalidRequest(detail = "card_ref_foreign_backend"))
            return
        }
        when (val availability = backend.availability.value) {
            AnkiAvailability.Checking -> {
                _uiState.value = EditNoteUiState.Loading
                return
            }
            is AnkiAvailability.Ready -> Unit
            else -> {
                editor = null
                _uiState.value = EditNoteUiState.Unavailable(availability)
                return
            }
        }

        _uiState.value = EditNoteUiState.Loading
        loadJob = viewModelScope.launch {
            val read = try {
                backend.getCardDetails(cardRef)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                AnkiResult.Failure(AnkiError.Unknown(cause = failure::class.java.simpleName))
            }
            if (token != generation) return@launch
            if (read is AnkiResult.Failure) {
                _uiState.value = EditNoteUiState.Error(read.error)
                return@launch
            }
            val details = (read as AnkiResult.Success).value
            val capabilities = backend.capabilities.value
            when (val decision = EditNoteMapper.baseOrRefusal(details, capabilities)) {
                is EditNoteMapper.BaseDecision.Refused -> {
                    _uiState.value = EditNoteUiState.Refused(decision.message)
                    return@launch
                }
                is EditNoteMapper.BaseDecision.Ready -> {
                    editor = Editor(
                        base = decision.base,
                        details = details,
                        capabilities = capabilities,
                        semantics = backend.noteMutationSemantics,
                        draftFieldValues = emptyMap(),
                        draftTags = null,
                        selectedDeckId = null,
                        decks = emptyList(),
                        decksLoading = capabilities.changeCardDeck,
                        decksIssue = null,
                        tagIssue = null,
                        saveState = EditNoteSaveState.Idle,
                        blocked = null,
                        supersedes = null,
                        activeMutationId = null,
                        saving = false
                    )
                    publish()
                    val blocking = coordinator.activeMutationFor(decision.base.backendId, decision.base.noteRef.noteId)
                    if (token != generation) return@launch
                    if (blocking != null) {
                        showBlocking(blocking, token)
                    } else {
                        loadDecks(token)
                    }
                }
            }
        }
    }

    /**
     * A backend switch never reinterprets the reference the editor was opened with: the draft is
     * dropped and the screen fails closed unless navigation supplies a reference qualified for the
     * new backend and collection.
     */
    fun switchBackend(next: AnkiBackend) {
        if (next === backend) return
        loadJob?.cancel()
        saveJob?.cancel()
        backend = next
        editor = null
        generation += 1L
        when {
            next.id != cardRef.backendId -> _uiState.value = EditNoteUiState.Error(
                AnkiError.InvalidRequest(detail = "card_ref_foreign_backend")
            )
            cardRef.collectionKey == null -> _uiState.value = EditNoteUiState.Error(
                AnkiError.InvalidRequest(detail = "edit_note_collection_identity_unavailable")
            )
            else -> load()
        }
    }

    // ---- draft edits ---------------------------------------------------------------------------

    fun onFieldChange(ordinal: Int, value: String) {
        val current = editor ?: return
        if (current.saving) return
        if (current.base.fields.none { it.ordinal == ordinal }) return
        editor = current.copy(draftFieldValues = current.draftFieldValues + (ordinal to value))
        publish()
    }

    fun resetField(ordinal: Int) {
        val current = editor ?: return
        if (current.saving) return
        editor = current.copy(draftFieldValues = current.draftFieldValues - ordinal)
        publish()
    }

    /** One line of tag input; Anki tags are space separated, so a pasted line may add several. */
    fun addTags(raw: String) {
        val current = editor ?: return
        if (current.saving) return
        if (raw.isBlank()) return
        when (val result = EditNoteMapper.tagInputResult(raw)) {
            is EditNoteMapper.TagInputResult.Rejected -> {
                editor = current.copy(tagIssue = EditNoteIssue(result.message, blocking = true))
            }
            is EditNoteMapper.TagInputResult.Accepted -> {
                val existing = current.draftTags ?: current.base.tags
                when (val merged = NoteEditPlanner.normalizeTags(existing + result.tags)) {
                    is TagNormalization.Invalid -> editor = current.copy(
                        tagIssue = EditNoteIssue(
                            "\"${merged.tag}\" cannot be stored as an Anki tag.",
                            blocking = true
                        )
                    )
                    is TagNormalization.Valid -> editor = current.copy(draftTags = merged.tags, tagIssue = null)
                }
            }
        }
        publish()
    }

    fun removeTag(tag: String) {
        val current = editor ?: return
        if (current.saving) return
        val existing = current.draftTags ?: current.base.tags
        editor = current.copy(
            draftTags = existing.filterNot { it.equals(tag, ignoreCase = true) },
            tagIssue = null
        )
        publish()
    }

    fun resetTags() {
        val current = editor ?: return
        if (current.saving) return
        editor = current.copy(draftTags = null, tagIssue = null)
        publish()
    }

    fun selectDeck(deckId: String?) {
        val current = editor ?: return
        if (current.saving) return
        if (deckId != null && current.decks.none { it.ref.deckId == deckId }) return
        editor = current.copy(selectedDeckId = deckId)
        publish()
    }

    fun dismissMessage() {
        val current = editor ?: return
        if (current.saveState is EditNoteSaveState.Saving) return
        editor = current.copy(saveState = EditNoteSaveState.Idle)
        publish()
    }

    // ---- transactions --------------------------------------------------------------------------

    fun save() {
        val current = editor ?: return
        if (current.saving || current.blocked != null) return
        val draft = draftOf(current)
        val supersedes = current.supersedes
        editor = current.copy(saving = true, saveState = EditNoteSaveState.Saving)
        publish()
        val token = generation
        saveJob = viewModelScope.launch {
            val outcome = try {
                if (supersedes != null) {
                    coordinator.startAfterConflict(supersedes, current.base, draft)
                } else {
                    coordinator.save(current.base, draft)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                null
            }
            if (token != generation) return@launch
            handleOutcome(outcome)
        }
    }

    fun retry() = runRecovery { id -> coordinator.retry(id) }

    fun resumePrepared() = runRecovery { id -> coordinator.resumePrepared(id) }

    /** Read-only evidence re-check of an unresolved mutation. Never writes. */
    fun checkAgain() {
        val current = editor ?: return
        val id = current.activeMutationId ?: return
        val token = generation
        viewModelScope.launch {
            val outcome = try {
                coordinator.recover(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                null
            }
            if (token != generation) return@launch
            applyRecovery(outcome, keepSaveState = true)
        }
    }

    /**
     * CONTRACT-26 — the human closes an ambiguity the backend cannot resolve. The attestation is
     * recorded as an attestation; it is never presented as backend evidence.
     */
    fun attest(attestation: NoteMutationAttestation) {
        val current = editor ?: return
        val id = current.activeMutationId ?: return
        val token = generation
        viewModelScope.launch {
            val outcome = try {
                coordinator.resolveAmbiguous(id, attestation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                null
            }
            if (token != generation) return@launch
            applyRecovery(outcome, keepSaveState = false)
        }
    }

    /**
     * After a pre-write conflict: re-read the note, keep the user's values, and let the next save
     * supersede the closed mutation. The base is always a fresh authoritative read.
     */
    fun reloadAfterConflict() {
        val current = editor ?: return
        val keepFields = current.draftFieldValues
        val keepTags = current.draftTags
        val keepDeck = current.selectedDeckId
        generation += 1L
        val token = generation
        _uiState.value = EditNoteUiState.Loading
        viewModelScope.launch {
            val read = backend.getCardDetails(cardRef)
            if (token != generation) return@launch
            if (read is AnkiResult.Failure) {
                _uiState.value = EditNoteUiState.Error(read.error)
                return@launch
            }
            val details = (read as AnkiResult.Success).value
            val base = NoteEditBase.from(details)
            if (base == null) {
                _uiState.value = EditNoteUiState.Refused(
                    "The note changed shape and can no longer be edited safely. Open it again."
                )
                return@launch
            }
            editor = current.copy(
                base = base,
                details = details,
                draftFieldValues = keepFields.filterKeys { ordinal -> base.fields.any { it.ordinal == ordinal } },
                draftTags = keepTags,
                selectedDeckId = keepDeck,
                saveState = EditNoteSaveState.Idle,
                blocked = null,
                supersedes = current.activeMutationId ?: current.supersedes,
                activeMutationId = null,
                saving = false
            )
            publish()
            loadDecks(token)
        }
    }

    private fun runRecovery(action: suspend (NoteMutationId) -> NoteMutationOutcome) {
        val current = editor ?: return
        val id = current.activeMutationId ?: return
        if (current.saving) return
        editor = current.copy(saving = true, saveState = EditNoteSaveState.Saving)
        publish()
        val token = generation
        saveJob = viewModelScope.launch {
            val outcome = try {
                action(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                null
            }
            if (token != generation) return@launch
            handleOutcome(outcome)
        }
    }

    private fun handleOutcome(outcome: NoteMutationOutcome?) {
        val current = editor ?: return
        when (outcome) {
            null -> editor = current.copy(
                saving = false,
                saveState = EditNoteSaveState.Blocked("The save did not complete. Nothing was written by this attempt.")
            )

            NoteMutationOutcome.NoChanges -> editor = current.copy(
                saving = false,
                saveState = EditNoteSaveState.Blocked("Nothing to save: Anki already holds these values.")
            )

            is NoteMutationOutcome.ValidationFailed -> editor = current.copy(
                saving = false,
                saveState = EditNoteSaveState.Blocked(
                    "The edit was refused before anything was written.",
                    EditNoteMapper.validationMessages(outcome.errors)
                )
            )

            is NoteMutationOutcome.Refused -> editor = current.copy(
                saving = false,
                saveState = EditNoteSaveState.Blocked(EditNoteMapper.refusalMessage(outcome.reason))
            )

            is NoteMutationOutcome.LedgerUnavailable -> editor = current.copy(
                saving = false,
                saveState = EditNoteSaveState.Blocked(
                    "Study Agent could not record this edit durably, so it was not sent to Anki."
                )
            )

            is NoteMutationOutcome.ActiveMutationExists -> blockOn(current, outcome.record, outcome.record.status)

            is NoteMutationOutcome.ReadFailed -> blockOn(
                current.copy(
                    saving = false,
                    saveState = EditNoteSaveState.Blocked(
                        "The pre-save read of this note failed (${EditNoteMapper.message(outcome.error)}). " +
                            "Nothing was written."
                    )
                ),
                outcome.record,
                outcome.record.status
            )

            is NoteMutationOutcome.Conflict -> blockOn(
                current.copy(saving = false),
                outcome.record,
                outcome.record.status,
                saveState = EditNoteSaveState.Conflicted(
                    EditNoteMapper.reasonMessage(outcome.record.reason).ifBlank {
                        "The note changed before Study Agent wrote to it. Nothing was written."
                    },
                    outcome.record.mutationId.value
                )
            )

            is NoteMutationOutcome.RetryAvailable -> blockOn(
                current.copy(saving = false),
                outcome.record,
                outcome.record.status,
                saveState = EditNoteSaveState.RetryAvailable(
                    EditNoteMapper.reasonMessage(outcome.record.reason).ifBlank {
                        "Anki refused the write before applying it (${EditNoteMapper.message(outcome.error)})."
                    },
                    outcome.record.mutationId.value
                )
            )

            is NoteMutationOutcome.VerificationRequired -> blockOn(
                current.copy(saving = false),
                outcome.record,
                outcome.record.status,
                saveState = EditNoteSaveState.Ambiguous(
                    EditNoteMapper.reasonMessage(outcome.record.reason).ifBlank { outcome.reason },
                    outcome.record.mutationId.value,
                    null
                )
            )

            is NoteMutationOutcome.Applied -> {
                // INV-17-14: the backend read, not the draft, becomes the truth the screen shows.
                val refreshedBase = NoteEditBase.from(outcome.refreshed)
                editor = current.copy(
                    saving = false,
                    saveState = EditNoteSaveState.Saved("Saved. Anki was re-read and holds the values you entered."),
                    base = refreshedBase ?: current.base,
                    details = outcome.refreshed,
                    draftFieldValues = emptyMap(),
                    draftTags = null,
                    selectedDeckId = null,
                    blocked = null,
                    supersedes = null,
                    activeMutationId = null
                )
                publish()
                _savedCardRef.value = outcome.refreshed.cardRef
                return
            }
        }
        publish()
    }

    /**
     * Shows a mutation that owns this note. It assigns [editor] itself and returns nothing: a caller
     * that merely *called* it would drop the new state and leave the screen stuck on "Saving" while a
     * record is unresolved — the one presentation failure this gate treats as unsafe.
     */
    private fun blockOn(
        current: Editor,
        record: NoteMutationRecord,
        status: NoteMutationStatus,
        saveState: EditNoteSaveState? = null
    ) {
        val message = EditNoteMapper.reasonMessage(record.reason).ifBlank { statusFallback(status) }
        editor = current.copy(
            saving = false,
            blocked = EditNoteMapper.blockedMutation(record),
            activeMutationId = record.mutationId,
            supersedes = if (status == NoteMutationStatus.CONFLICT) record.mutationId else current.supersedes,
            saveState = saveState ?: EditNoteSaveState.Blocked(message)
        )
    }

    private fun statusFallback(status: NoteMutationStatus): String = when (status) {
        NoteMutationStatus.PREPARED -> "This edit was recorded but not sent to Anki."
        NoteMutationStatus.SUBMITTING -> "This edit is in flight and has no recorded answer yet."
        NoteMutationStatus.APPLIED -> "This edit was saved."
        NoteMutationStatus.RETRY_ALLOWED -> "Anki refused this edit before applying it."
        NoteMutationStatus.AMBIGUOUS ->
            "Study Agent cannot prove whether Anki applied this edit. Check the note in Anki, then confirm."
        NoteMutationStatus.CONFLICT -> "This edit was closed without being written."
    }

    private fun showBlocking(record: NoteMutationRecord, token: Long) {
        viewModelScope.launch {
            val recovery = if (record.status == NoteMutationStatus.SUBMITTING ||
                record.status == NoteMutationStatus.AMBIGUOUS
            ) {
                try {
                    coordinator.recover(record.mutationId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    null
                }
            } else {
                null
            }
            if (token != generation) return@launch
            applyRecovery(recovery, keepSaveState = true, fallback = record)
            val stillBlocking = editor?.blocked != null
            if (!stillBlocking) loadDecks(token)
        }
    }

    private fun applyRecovery(
        outcome: NoteMutationRecoveryOutcome?,
        keepSaveState: Boolean,
        fallback: NoteMutationRecord? = null
    ) {
        val current = editor ?: return
        when (outcome) {
            is NoteMutationRecoveryOutcome.Resolved -> {
                val record = outcome.record
                if (record.status.isTerminal) {
                    editor = current.copy(
                        blocked = null,
                        activeMutationId = null,
                        saving = false,
                        saveState = if (keepSaveState) current.saveState else EditNoteSaveState.Idle
                    )
                    publish()
                    // The note may have been written; the screen truth has to come from a fresh read.
                    load()
                } else {
                    editor = current.copy(
                        blocked = EditNoteMapper.blockedMutation(record),
                        activeMutationId = record.mutationId,
                        saving = false
                    )
                    publish()
                }
            }

            is NoteMutationRecoveryOutcome.StillAmbiguous -> {
                editor = current.copy(
                    blocked = EditNoteMapper.blockedMutation(outcome.record, outcome.evidence),
                    activeMutationId = outcome.record.mutationId,
                    saving = false,
                    saveState = if (keepSaveState) {
                        current.saveState
                    } else {
                        EditNoteSaveState.Ambiguous(
                            EditNoteMapper.reasonMessage(outcome.record.reason).ifBlank { statusFallback(outcome.record.status) },
                            outcome.record.mutationId.value,
                            EditNoteMapper.evidenceMessage(outcome.evidence)
                        )
                    }
                )
                publish()
            }

            is NoteMutationRecoveryOutcome.Unchanged -> {
                val record = outcome.record
                if (record.status.isActive) {
                    editor = current.copy(
                        blocked = EditNoteMapper.blockedMutation(record),
                        activeMutationId = record.mutationId,
                        saving = false
                    )
                } else {
                    editor = current.copy(blocked = null, activeMutationId = null, saving = false)
                }
                publish()
            }

            NoteMutationRecoveryOutcome.NotFound, NoteMutationRecoveryOutcome.Busy -> {
                val record = fallback
                editor = if (record != null && record.status.isActive) {
                    current.copy(
                        blocked = EditNoteMapper.blockedMutation(record),
                        activeMutationId = record.mutationId,
                        saving = false
                    )
                } else {
                    current.copy(blocked = null, activeMutationId = null, saving = false)
                }
                publish()
            }

            is NoteMutationRecoveryOutcome.LedgerUnavailable, null -> {
                val record = fallback
                editor = if (record != null) {
                    current.copy(
                        blocked = EditNoteMapper.blockedMutation(record),
                        activeMutationId = record.mutationId,
                        saving = false
                    )
                } else {
                    current.copy(saving = false)
                }
                publish()
            }
        }
    }

    private suspend fun loadDecks(token: Long) {
        val current = editor ?: return
        if (!current.capabilities.changeCardDeck) {
            editor = current.copy(decksLoading = false)
            publish()
            return
        }
        editor = current.copy(decksLoading = true)
        publish()
        val result = try {
            backend.getDecks()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            AnkiResult.Failure(AnkiError.Unknown(cause = failure::class.java.simpleName))
        }
        if (token != generation) return
        val now = editor ?: return
        editor = when (result) {
            is AnkiResult.Failure -> now.copy(decksLoading = false, decksIssue = result.error)
            is AnkiResult.Success -> now.copy(decksLoading = false, decks = result.value, decksIssue = null)
        }
        publish()
    }

    /** The draft the coordinator will validate. Absent entries mean "unchanged". */
    private fun draftOf(current: Editor): NoteEditDraft {
        val baseDeckId = current.base.deckRef?.deckId
        return NoteEditDraft(
            fieldValues = current.draftFieldValues.filter { (ordinal, value) ->
                current.base.fields.any { it.ordinal == ordinal && it.value != value }
            },
            tags = current.draftTags,
            targetDeck = current.selectedDeckId
                ?.takeIf { it != baseDeckId }
                ?.let { AnkiDeckRef(current.base.backendId, it) }
        )
    }

    private fun publish() {
        val current = editor ?: return
        _uiState.value = EditNoteUiState.Ready(
            EditNoteMapper.project(
                EditNoteMapper.Input(
                    base = current.base,
                    details = current.details,
                    capabilities = current.capabilities,
                    semantics = current.semantics,
                    draftFieldValues = current.draftFieldValues,
                    draftTags = current.draftTags,
                    selectedDeckId = current.selectedDeckId,
                    decks = current.decks,
                    decksLoading = current.decksLoading,
                    decksIssue = current.decksIssue,
                    tagIssue = current.tagIssue,
                    saveState = current.saveState,
                    blockedByMutation = current.blocked
                )
            )
        )
    }
}
