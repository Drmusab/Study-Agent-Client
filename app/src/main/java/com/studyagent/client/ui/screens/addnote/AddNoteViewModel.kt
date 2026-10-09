package com.studyagent.client.ui.screens.addnote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiNoteModel
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.create.AddNoteDraft
import com.studyagent.client.core.anki.create.MediaSourceProbe
import com.studyagent.client.core.anki.create.MediaSourceProbeResult
import com.studyagent.client.core.anki.create.NoteCreationAttestation
import com.studyagent.client.core.anki.create.NoteCreationCoordinator
import com.studyagent.client.core.anki.create.NoteCreationId
import com.studyagent.client.core.anki.create.NoteCreationOutcome
import com.studyagent.client.core.anki.create.NoteCreationRecoveryOutcome
import com.studyagent.client.core.anki.create.NoteCreationRecord
import com.studyagent.client.core.anki.create.NoteCreationValidator
import com.studyagent.client.core.anki.create.PendingMedia
import com.studyagent.client.core.anki.edit.NoteEditPlanner
import com.studyagent.client.core.anki.edit.TagNormalization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * GATE 18 — the Add Note screen's state owner.
 *
 * It holds the draft (selected note type, field values, tags, pending media) and the outcome of the
 * last coordinator call. It contains **no** creation logic: every write goes through
 * [NoteCreationCoordinator], which owns validation, the durable ordering, the creation boundary and
 * the post-create hydration (INV-18-15 mirrors INV-17-11). Media preparation stays local and
 * reversible here; only the coordinator stores media in the backend (PART I §17).
 *
 * Model-change invalidation (PART I §19): switching the note type discards field values and media
 * attachments — a silent remap into a different schema would corrupt meaning. Tags survive because
 * they are not schema-bound.
 */
class AddNoteViewModel(
    val backend: AnkiBackend,
    private val coordinator: NoteCreationCoordinator,
    private val mediaProbe: MediaSourceProbe
) : ViewModel() {

    val backendLabel: String
        get() = AddNoteMapper.backendLabel(backend.id.stableId)

    private data class Editor(
        val models: List<AnkiNoteModel>,
        val selectedModelId: String?,
        val fieldValues: Map<Int, String>,
        val tags: List<String>,
        val pendingMedia: List<PendingMedia>,
        val deckNames: Map<String, String>,
        val saveState: AddNoteSaveState,
        val saving: Boolean,
        val mediaIssueToken: String?
    )

    private var editor: Editor = Editor(
        models = emptyList(),
        selectedModelId = null,
        fieldValues = emptyMap(),
        tags = emptyList(),
        pendingMedia = emptyList(),
        deckNames = emptyMap(),
        saveState = AddNoteSaveState.Idle,
        saving = false,
        mediaIssueToken = null
    )

    private var modelsLoading = false
    private var modelsError: AnkiError? = null
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var lastOutcomeCreationId: NoteCreationId? = null

    // Declared before [init]: the availability collector publishes synchronously on some
    // schedulers, so every state read by publish() must already be initialized.
    private var recoveryCache: List<NoteCreationRecord> = emptyList()

    private val _uiState = MutableStateFlow<AddNoteUiState>(AddNoteUiState.Loading)
    val uiState: StateFlow<AddNoteUiState> = _uiState.asStateFlow()

    init {
        // Availability changes re-project: a backend that becomes Ready mid-session enables the
        // affordance truthfully; one that drops drops it without silently degrading.
        viewModelScope.launch {
            backend.availability.collect { publish() }
        }
        reloadModels()
        loadDeckNames()
        refreshRecovery()
    }

    // ---------------------------------------------------------------- loading

    fun reloadModels() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            modelsLoading = true
            modelsError = null
            val result = try {
                backend.getNoteModels()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                AnkiResult.Failure(AnkiError.QueryFailure(causeCategory = "model_listing_threw"))
            }
            modelsLoading = false
            when (result) {
                is AnkiResult.Success -> {
                    editor = editor.copy(models = result.value, selectedModelId = null)
                }
                is AnkiResult.Failure -> modelsError = result.error
            }
            publish()
        }
    }

    private fun loadDeckNames() {
        viewModelScope.launch {
            val decks = try {
                backend.getDecks()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                AnkiResult.Failure(AnkiError.QueryFailure(causeCategory = "deck_listing_threw"))
            }
            if (decks is AnkiResult.Success) {
                editor = editor.copy(deckNames = decks.value.associate { deck -> deck.ref.deckId to deck.name })
                publish()
            }
            // A failed deck listing is non-fatal: the notice degrades to "not resolvable".
        }
    }

    // ---------------------------------------------------------------- draft editing

    fun selectModel(modelId: String) {
        val current = editor
        if (current.selectedModelId == modelId) return
        val model = current.models.firstOrNull { it.ref.modelId == modelId } ?: return
        // PART I §19: a model change discards every field value and attachment from the old schema.
        editor = current.copy(
            selectedModelId = modelId,
            fieldValues = model.fields.associate { field -> field.ordinal to "" },
            pendingMedia = emptyList(),
            saveState = AddNoteSaveState.Idle,
            mediaIssueToken = null
        )
        publish()
    }

    fun onFieldChanged(ordinal: Int, value: String) {
        editor = editor.copy(fieldValues = editor.fieldValues + (ordinal to value))
        publish()
    }

    fun onTagsChanged(tags: List<String>) {
        // Raw input is kept as typed: canonicalization and rejection happen in
        // [currentDraft]/[NoteCreationValidator], so an invalid tag is surfaced, not hidden.
        editor = editor.copy(tags = tags)
        publish()
    }

    fun attachMedia(uri: String, targetFieldOrdinal: Int) {
        val model = selectedModel() ?: return
        val ordinal = if (targetFieldOrdinal in model.fields.map { it.ordinal }) {
            targetFieldOrdinal
        } else {
            model.fields.firstOrNull()?.ordinal ?: return
        }
        viewModelScope.launch {
            val probe = try {
                mediaProbe.probe(uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                MediaSourceProbeResult.Refused("probe_failed")
            }
            when (probe) {
                is MediaSourceProbeResult.Refused -> {
                    editor = editor.copy(mediaIssueToken = probe.token)
                    publish()
                }
                is MediaSourceProbeResult.Ready -> {
                    val kind = com.studyagent.client.core.anki.create.MediaMimeTypes.kindFor(probe.mimeType)
                    if (kind == null) {
                        editor = editor.copy(mediaIssueToken = "mime_unsupported")
                        publish()
                        return@launch
                    }
                    editor = editor.copy(
                        pendingMedia = editor.pendingMedia + PendingMedia(
                            contentUri = uri,
                            sourceName = probe.displayName,
                            mimeType = probe.mimeType,
                            extension = probe.extension,
                            sizeBytes = probe.sizeBytes,
                            kind = kind,
                            targetFieldOrdinal = ordinal
                        ),
                        mediaIssueToken = null
                    )
                    publish()
                }
            }
        }
    }

    fun removeMedia(index: Int) {
        editor = editor.copy(pendingMedia = editor.pendingMedia.filterIndexed { i, _ -> i != index })
        publish()
    }

    // ---------------------------------------------------------------- creation

    fun save() {
        if (editor.saving) return
        val draft = currentDraft() ?: return
        saveJob?.cancel()
        editor = editor.copy(saving = true, saveState = AddNoteSaveState.Idle)
        publish()
        saveJob = viewModelScope.launch {
            editor = editor.copy(
                saveState = if (draft.media.isEmpty()) AddNoteSaveState.SavingNote
                else AddNoteSaveState.SavingMedia(step = 1, total = draft.media.size)
            )
            publish()
            applyOutcome(coordinator.create(draft))
        }
    }

    fun retry() {
        if (editor.saving) return
        val creationId = lastOutcomeCreationId ?: return
        saveJob?.cancel()
        editor = editor.copy(saving = true, saveState = AddNoteSaveState.Idle)
        publish()
        saveJob = viewModelScope.launch {
            applyOutcome(coordinator.retry(creationId))
        }
    }

    /** Human attestation for an AMBIGUOUS creation: the user checked Anki and concluded. */
    fun attest(creationId: NoteCreationId, foundInCollection: Boolean) {
        viewModelScope.launch {
            val attestation = if (foundInCollection) {
                NoteCreationAttestation.CREATED_IN_COLLECTION
            } else {
                NoteCreationAttestation.ABSENT_FROM_COLLECTION
            }
            when (coordinator.resolveAmbiguous(creationId, attestation)) {
                is NoteCreationRecoveryOutcome.Resolved,
                is NoteCreationRecoveryOutcome.StillAmbiguous -> {
                    editor = editor.copy(
                        saveState = AddNoteSaveState.RefusalMessage(
                            "The outcome was recorded exactly as you attested it. No automatic action " +
                                "was taken; any new note must be created as a fresh attempt."
                        )
                    )
                }
                else -> {
                    editor = editor.copy(
                        saveState = AddNoteSaveState.RefusalMessage(
                            "This attempt can no longer be attested. Start a new note."
                        )
                    )
                }
            }
            editor = editor.copy(saving = false)
            publish()
        }
    }

    /** Hydration resume for a CREATED record whose post-create read previously failed. */
    fun hydrate(creationId: NoteCreationId) {
        if (editor.saving) return
        editor = editor.copy(saving = true)
        publish()
        viewModelScope.launch {
            editor = editor.copy(saving = false)
            when (val outcome = coordinator.hydrate(creationId)) {
                is NoteCreationRecoveryOutcome.Hydrated -> {
                    lastOutcomeCreationId = creationId
                    editor = editor.copy(saveState = createdSaveState(outcome.record.createdNoteId, outcome.created))
                }
                is NoteCreationRecoveryOutcome.HydrationFailed -> {
                    lastOutcomeCreationId = creationId
                    editor = editor.copy(saveState = hydrationFailedSaveState(outcome.record))
                }
                else -> {
                    editor = editor.copy(
                        saveState = AddNoteSaveState.RefusalMessage("The created note could not be read back.")
                    )
                }
            }
            publish()
        }
    }

    /** After a durable success, reset the draft to empty (same model selected) for the next note. */
    fun startNewNote() {
        lastOutcomeCreationId = null
        val model = selectedModel()
        editor = editor.copy(
            selectedModelId = null,
            fieldValues = emptyMap(),
            pendingMedia = emptyList(),
            saveState = AddNoteSaveState.Idle,
            mediaIssueToken = null
        )
        if (model != null) selectModel(model.ref.modelId)
        publish()
    }

    // ---------------------------------------------------------------- internals

    private fun selectedModel(): AnkiNoteModel? =
        editor.models.firstOrNull { it.ref.modelId == editor.selectedModelId }

    private fun currentDraft(): AddNoteDraft? {
        val model = selectedModel() ?: return null
        // GATE 17 tag rules: canonicalize when valid; when a tag is unrepresentable, keep the raw
        // list so the validator reports it honestly instead of silently dropping it.
        val tags = when (val normalized = NoteEditPlanner.normalizeTags(editor.tags)) {
            is TagNormalization.Valid -> normalized.tags
            is TagNormalization.Invalid -> editor.tags
        }
        return AddNoteDraft(
            backendId = backend.id,
            collectionKey = null,
            model = model,
            fieldValues = editor.fieldValues,
            tags = tags,
            media = editor.pendingMedia
        )
    }

    private fun applyOutcome(outcome: NoteCreationOutcome) {
        lastOutcomeCreationId = when (outcome) {
            is NoteCreationOutcome.Created -> outcome.record.creationId
            is NoteCreationOutcome.CreatedHydrationFailed -> outcome.record.creationId
            is NoteCreationOutcome.RetryAvailable -> outcome.record.creationId
            is NoteCreationOutcome.MediaOutcomeUnclear -> outcome.record.creationId
            is NoteCreationOutcome.AmbiguousCreation -> outcome.record.creationId
            is NoteCreationOutcome.ValidationFailed -> lastOutcomeCreationId
            is NoteCreationOutcome.Refused -> lastOutcomeCreationId
            is NoteCreationOutcome.CreationInProgress -> lastOutcomeCreationId
            is NoteCreationOutcome.LedgerUnavailable -> lastOutcomeCreationId
        }
        editor = editor.copy(saving = false)
        when (outcome) {
            is NoteCreationOutcome.Created -> {
                editor = editor.copy(saveState = createdSaveState(outcome.record.createdNoteId, outcome.created))
                clearDraftForNextNote()
            }
            is NoteCreationOutcome.CreatedHydrationFailed -> {
                editor = editor.copy(saveState = hydrationFailedSaveState(outcome.record))
                clearDraftForNextNote()
            }
            is NoteCreationOutcome.RetryAvailable -> {
                editor = editor.copy(
                    saveState = AddNoteSaveState.RetryAvailable(
                        AddNoteMapper.refusalMessage(refusalTokenFor(outcome.error)) +
                            " Nothing was created. You can retry this attempt."
                    )
                )
            }
            is NoteCreationOutcome.MediaOutcomeUnclear -> {
                editor = editor.copy(
                    saveState = AddNoteSaveState.MediaUnclear(
                        "The media step ended without a clear result, and no note was created. This " +
                            "attempt is marked and will never be retried automatically."
                    ),
                    pendingMedia = emptyList()
                )
            }
            is NoteCreationOutcome.AmbiguousCreation -> {
                editor = editor.copy(
                    saveState = AddNoteSaveState.Ambiguous(
                        "The creation ended without a clear result. It may or may not have been " +
                            "created. Do NOT create this note again until you checked Anki directly."
                    ),
                    pendingMedia = emptyList()
                )
            }
            is NoteCreationOutcome.ValidationFailed -> {
                editor = editor.copy(saveState = AddNoteSaveState.ValidationMessage(outcome.issues))
            }
            is NoteCreationOutcome.Refused -> {
                editor = editor.copy(saveState = AddNoteSaveState.RefusalMessage(refusalMessageFor(outcome)))
            }
            is NoteCreationOutcome.CreationInProgress -> {
                editor = editor.copy(
                    saveState = AddNoteSaveState.RefusalMessage(AddNoteMapper.refusalMessage("CreationInProgress"))
                )
            }
            is NoteCreationOutcome.LedgerUnavailable -> {
                editor = editor.copy(saveState = AddNoteSaveState.LedgerMessage(outcome.detail))
            }
        }
        publish()
    }

    private fun clearDraftForNextNote() {
        val model = selectedModel()
        editor = editor.copy(
            fieldValues = model?.fields?.associate { field -> field.ordinal to "" } ?: emptyMap(),
            pendingMedia = emptyList()
        )
    }

    private fun refusalMessageFor(outcome: NoteCreationOutcome.Refused): String = when (outcome.reason) {
        is com.studyagent.client.core.anki.create.NoteCreationRefusal.UnsupportedCapability ->
            "This backend does not offer that creation capability."
        is com.studyagent.client.core.anki.create.NoteCreationRefusal.BackendMismatch ->
            AddNoteMapper.refusalMessage("BackendMismatch")
        is com.studyagent.client.core.anki.create.NoteCreationRefusal.BackendUnavailable ->
            AddNoteMapper.refusalMessage("BackendUnavailable")
        is com.studyagent.client.core.anki.create.NoteCreationRefusal.ModelMissing ->
            AddNoteMapper.refusalMessage("ModelMissing")
        is com.studyagent.client.core.anki.create.NoteCreationRefusal.SchemaDrifted ->
            AddNoteMapper.refusalMessage("SchemaDrifted")
        is com.studyagent.client.core.anki.create.NoteCreationRefusal.PayloadUnavailable ->
            AddNoteMapper.refusalMessage("PayloadUnavailable")
        is com.studyagent.client.core.anki.create.NoteCreationRefusal.UnknownCreation ->
            AddNoteMapper.refusalMessage("UnknownCreation")
        is com.studyagent.client.core.anki.create.NoteCreationRefusal.RetryNotAllowed ->
            "This attempt cannot be retried."
    }

    private fun refusalTokenFor(error: AnkiError): String = when (error) {
        is AnkiError.NoteModelNotFound -> "ModelMissing"
        is AnkiError.MediaRejected -> "MediaRejected"
        else -> "UnknownCreation"
    }

    private fun createdSaveState(createdNoteId: String?, created: com.studyagent.client.core.anki.AnkiCreatedNote): AddNoteSaveState =
        AddNoteSaveState.Created(
            AddNoteMapper.projectCreatedSummary(
                noteId = createdNoteId ?: created.noteRef.noteId,
                creationId = lastOutcomeCreationId,
                modelName = created.modelName,
                fields = created.fields,
                tags = created.tags,
                cards = created.cards.map { card ->
                    val label = card.cardName ?: card.ref.cardOrd?.let { ord -> "Card ${ord + 1}" } ?: "Card"
                    label to card.deckName
                },
                hydrationIssueToken = null
            )
        )

    private fun hydrationFailedSaveState(record: NoteCreationRecord): AddNoteSaveState =
        AddNoteSaveState.Created(
            AddNoteCreatedSummary(
                noteId = record.createdNoteId ?: record.creationId.value,
                creationId = record.creationId,
                modelName = record.modelName,
                fieldCount = record.fieldCount,
                tags = emptyList(),
                cards = emptyList(),
                hydrationIssueToken = "hydration_read_failed"
            )
        )

    // ---------------------------------------------------------------- projection

    private fun publish() {
        val availability = backend.availability.value
        val capabilities = backend.capabilities.value
        val selected = selectedModel()

        // The enablement check runs the SAME validator the coordinator runs, against a draft built
        // exactly the way the coordinator will receive it: what enables Save is what Save will send.
        val tokens = ArrayList<String>()
        val draft = currentDraft()
        if (draft == null) {
            tokens += "no_model_selected"
        } else {
            tokens += NoteCreationValidator.validate(draft, capabilities)
        }
        editor.mediaIssueToken?.let { tokens += it }

        _uiState.value = AddNoteMapper.project(
            AddNoteMapper.Source(
                availability = availability,
                capabilities = capabilities,
                modelsLoading = modelsLoading,
                modelsError = modelsError,
                models = editor.models,
                selected = selected,
                fieldValues = editor.fieldValues,
                tags = editor.tags,
                pendingMedia = editor.pendingMedia,
                deckNames = editor.deckNames,
                deckListingFailed = editor.deckNames.isEmpty(),
                validationTokens = tokens.distinct(),
                saveState = editor.saveState,
                saving = editor.saving,
                recoveryRecords = recoveryCache.filter { record -> record.backendId == backend.id }
            )
        )
    }


    /** Refreshes the recovery surface (ambiguous/in-flight records for this backend). */
    fun refreshRecovery() {
        viewModelScope.launch {
            val active = try {
                coordinator.activeCreationsFor(backend.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
            val unresolved = try {
                coordinator.unresolvedCreations().filter { record -> record.backendId == backend.id }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
            recoveryCache = (active + unresolved).distinctBy { record -> record.creationId.value }
            publish()
        }
    }
}
