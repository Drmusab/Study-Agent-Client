package com.studyagent.client.ui.screens.editnote

import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCardDetails
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.NoteConflictGuarantee
import com.studyagent.client.core.anki.edit.NoteEditBase
import com.studyagent.client.core.anki.edit.NoteEditPlanner
import com.studyagent.client.core.anki.edit.NoteEditValidationError
import com.studyagent.client.core.anki.edit.NoteMutationAttestation
import com.studyagent.client.core.anki.edit.NoteMutationReason
import com.studyagent.client.core.anki.edit.NoteMutationRecord
import com.studyagent.client.core.anki.edit.NoteMutationRefusal
import com.studyagent.client.core.anki.edit.NoteMutationSemantics
import com.studyagent.client.core.anki.edit.NoteMutationStatus
import com.studyagent.client.core.anki.edit.NoteMutationVerification
import com.studyagent.client.core.anki.edit.NoteDeckChangeScope
import com.studyagent.client.core.anki.edit.TagNormalization

/**
 * GATE 17 CHECKPOINT 13 — pure projection between the editing domain and the screen.
 *
 * Every rule the UI depends on lives here so it is testable on the JVM and so the Compose layer holds
 * no decision at all (INV-17-17: the UI cannot bypass backend-contract enforcement, because it never
 * decides anything — it renders what this mapper derived from capabilities and declared semantics).
 *
 * Wording rules enforced here, from the locked contract:
 * - the deck control says **card**, never "move note" (CONTRACT-03 / INV-17-09);
 * - the conflict notice says **best effort**, never "protected" (CONTRACT-11 / INV-17-06);
 * - an ambiguous outcome says **cannot be proven**, never "failed" (CONTRACT-08);
 * - a human resolution says **you confirmed**, never "the backend confirmed" (CONTRACT-12).
 */
object EditNoteMapper {

    /** Everything the projection needs. The ViewModel owns this state; nothing here is mutable. */
    data class Input(
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
        val blockedByMutation: EditNoteBlockedMutation?
    )

    /**
     * The base the editor may start from, or the reason it may not. A capability that is absent, a
     * note identity the backend does not expose, or field ordinals that do not match template order
     * all refuse the editor instead of degrading it silently (AUDIT-17-12).
     */
    fun baseOrRefusal(details: AnkiCardDetails, capabilities: AnkiCapabilities): BaseDecision = when {
        !capabilities.cardDetails -> BaseDecision.Refused("This backend cannot read the note behind this card.")
        !capabilities.editNoteFields && !capabilities.editNoteTags && !capabilities.changeCardDeck ->
            BaseDecision.Refused("This backend does not offer note editing.")
        else -> when (val base = NoteEditBase.from(details)) {
            null -> BaseDecision.Refused(
                "This card's note cannot be edited safely: the backend did not expose an editable note " +
                    "identity, its fields, or its tags."
            )
            else -> BaseDecision.Ready(base)
        }
    }

    sealed interface BaseDecision {
        data class Ready(val base: NoteEditBase) : BaseDecision
        data class Refused(val message: String) : BaseDecision
    }

    fun capabilitiesOf(source: AnkiCapabilities, semantics: NoteMutationSemantics): EditNoteCapabilities =
        EditNoteCapabilities(
            fields = source.editNoteFields,
            tags = source.editNoteTags,
            // A deck move is only offered when the backend's scope is the one the UI can describe.
            deck = source.changeCardDeck && semantics.deckChangeScope == NoteDeckChangeScope.CARD_ONLY,
            conflictGuarantee = semantics.conflictGuarantee,
            fieldEditCanGenerateSiblingCards = semantics.fieldEditCanGenerateSiblingCards,
            contentWriteAtomic = semantics.contentWriteAtomic,
            trailingEmptyFieldRepresentable = semantics.trailingEmptyFieldRepresentable,
            authoritativeReconciliation = semantics.authoritativeReconciliation
        )

    fun project(input: Input): EditNoteEditorState {
        val capabilities = capabilitiesOf(input.capabilities, input.semantics)
        val base = input.base
        // `NoteEditBase` proves every ordinal equals its template position, so the position IS the
        // field identity here (CONTRACT-14). The list index is used because the domain model keeps the
        // backend's nullable ordinal honest.
        val fields = base.fields.mapIndexed { index, field ->
            val draft = input.draftFieldValues[index] ?: field.value
            EditNoteFieldState(
                ordinal = index,
                label = field.name.ifBlank { "Field ${index + 1}" },
                originalValue = field.value,
                draftValue = draft,
                editable = capabilities.fields,
                issue = fieldIssue(index, draft, base, capabilities)
            )
        }
        val tags = EditNoteTagsState(
            originalTags = base.tags,
            draftTags = input.draftTags ?: base.tags,
            editable = capabilities.tags,
            issue = input.tagIssue
        )
        val deck = projectDeck(base, input, capabilities)
        return EditNoteEditorState(
            cardRef = base.cardRef,
            noteIdLabel = base.noteRef.noteId,
            noteTypeLabel = input.details.noteTypeName,
            cardLabel = cardLabel(input.details),
            capabilities = capabilities,
            fields = fields,
            tags = tags,
            deck = deck,
            notices = notices(base, capabilities, fields, input.saveState),
            saveState = input.saveState,
            blockedByMutation = input.blockedByMutation
        )
    }

    private fun cardLabel(details: AnkiCardDetails): String? {
        val template = details.templateName
        val ord = details.cardOrd
        return when {
            template != null && ord != null -> "$template (card ${ord + 1})"
            template != null -> template
            ord != null -> "Card ${ord + 1}"
            else -> null
        }
    }

    private fun projectDeck(base: NoteEditBase, input: Input, capabilities: EditNoteCapabilities): EditNoteDeckState {
        val currentId = base.deckRef?.deckId
        // Display label only. Identity for the write is always the stable deck id.
        val currentLabel = input.decks.firstOrNull { it.ref.deckId == currentId }?.name
            ?: input.details.deckRef?.takeIf { it.deckId == currentId }?.let { input.details.deckName }
        val options = input.decks
            .filter { it.ref.backendId == base.backendId }
            .map { deck ->
                EditNoteDeckOption(
                    deckId = deck.ref.deckId,
                    label = deck.name,
                    filtered = deck.isFiltered == true,
                    unverifiable = deck.isFiltered == null
                )
            }
        return EditNoteDeckState(
            currentDeckId = currentId,
            currentDeckLabel = currentLabel,
            selectedDeckId = input.selectedDeckId ?: currentId,
            editable = capabilities.deck && currentId != null,
            options = options,
            loading = input.decksLoading,
            issue = when {
                !capabilities.deck -> null
                currentId == null -> EditNoteIssue(
                    "This card's deck is unknown, so a move cannot be verified before it is written.",
                    blocking = true
                )
                input.decksIssue != null -> EditNoteIssue(
                    "The deck list could not be read (${message(input.decksIssue)}). A deck move is refused until it can.",
                    blocking = true
                )
                else -> null
            }
        )
    }

    /**
     * Field-level problems, decided before any save so the control can refuse them (CONTRACT-11 style
     * pre-transaction validation, never a backend round trip).
     */
    private fun fieldIssue(
        ordinal: Int,
        draft: String,
        base: NoteEditBase,
        capabilities: EditNoteCapabilities
    ): EditNoteIssue? {
        if (!capabilities.fields) return null
        if (draft.indexOf(NoteEditPlanner.FIELD_SEPARATOR) >= 0) {
            return EditNoteIssue("A field cannot contain the Anki field separator character.", blocking = true)
        }
        val isLast = ordinal == base.fields.lastIndex
        if (isLast && draft.isEmpty() && !capabilities.trailingEmptyFieldRepresentable) {
            return EditNoteIssue(
                "Anki's public interface cannot store an empty last field. Give this field a value " +
                    "(for example a single space) before saving.",
                blocking = true
            )
        }
        return null
    }

    /**
     * Standing contract notices. These are the honest wording the gate requires: what is atomic, what
     * is best effort, and what a field edit can cause besides the edit itself.
     */
    fun notices(
        base: NoteEditBase,
        capabilities: EditNoteCapabilities,
        fields: List<EditNoteFieldState>,
        saveState: EditNoteSaveState
    ): List<EditNoteNotice> = buildList {
        if (capabilities.fields || capabilities.tags) {
            add(
                EditNoteNotice(
                    if (capabilities.contentWriteAtomic) {
                        "Fields and tags are written together as one all-or-nothing change."
                    } else {
                        "This backend does not guarantee that fields and tags are written together."
                    },
                    EditNoticeTone.INFO
                )
            )
        }
        if (capabilities.deck) {
            add(
                EditNoteNotice(
                    "Moving a deck moves THIS CARD ONLY. Other cards generated from the same note stay " +
                        "where they are.",
                    EditNoticeTone.INFO
                )
            )
        }
        if (capabilities.fields && capabilities.deck) {
            add(
                EditNoteNotice(
                    "A deck move is a second, separate write after the content write. If the second " +
                        "write cannot be confirmed, the content change may still have been saved.",
                    EditNoticeTone.WARNING
                )
            )
        }
        if (capabilities.fieldEditCanGenerateSiblingCards && fields.any { it.changed }) {
            add(
                EditNoteNotice(
                    "Editing fields can make Anki generate additional cards for this note (for example " +
                        "when a card template's front side stops being empty). Those cards are placed by " +
                        "Anki, not by this screen.",
                    EditNoticeTone.WARNING
                )
            )
        }
        when (capabilities.conflictGuarantee) {
            NoteConflictGuarantee.BEST_EFFORT_PRE_SAVE_REREAD -> add(
                EditNoteNotice(
                    "Anki offers no locking for this write. Study Agent re-reads the note immediately " +
                        "before saving and stops if it changed, but a change in between cannot be " +
                        "excluded. This is best-effort detection, not protection.",
                    EditNoticeTone.WARNING
                )
            )
            NoteConflictGuarantee.NONE -> add(
                EditNoteNotice(
                    "Nothing detects a concurrent change to this note. Saving overwrites what Anki holds.",
                    EditNoticeTone.WARNING
                )
            )
            NoteConflictGuarantee.ATOMIC_COMPARE_AND_SET -> Unit
        }
        if (!capabilities.authoritativeReconciliation) {
            add(
                EditNoteNotice(
                    "If a save cannot be confirmed, Study Agent cannot prove afterwards whether it " +
                        "applied. You will be asked to check Anki and confirm.",
                    EditNoticeTone.WARNING
                )
            )
        }
        if (base.noteTypeId.isNullOrBlank() && capabilities.fields) {
            add(
                EditNoteNotice(
                    "This note's type identity is unavailable, so field edits are refused.",
                    EditNoticeTone.WARNING
                )
            )
        }
        if (saveState is EditNoteSaveState.Saving) {
            add(EditNoteNotice("Saving. Do not close this screen.", EditNoticeTone.WARNING))
        }
    }

    // ---- outcome → screen -----------------------------------------------------------------------

    /** The screen state for one coordinator outcome. Pure, so every branch is unit-testable. */
    fun saveStateOf(outcome: EditNoteOutcomeProjection): EditNoteSaveState = when (outcome) {
        is EditNoteOutcomeProjection.NoChanges -> EditNoteSaveState.Blocked("Nothing to save: the note already holds these values.")
        is EditNoteOutcomeProjection.Validation -> EditNoteSaveState.Blocked(
            "The edit was refused before anything was written.",
            outcome.messages
        )
        is EditNoteOutcomeProjection.Refusal -> EditNoteSaveState.Blocked(outcome.message)
        is EditNoteOutcomeProjection.Ledger -> EditNoteSaveState.Blocked(
            "Study Agent could not record this edit durably, so it was not sent to Anki."
        )
        is EditNoteOutcomeProjection.ReadFailed -> EditNoteSaveState.Blocked(
            "The pre-save read of this note failed (${outcome.message}). Nothing was written; you can try again."
        )
        is EditNoteOutcomeProjection.Conflict -> EditNoteSaveState.Conflicted(outcome.message, outcome.mutationId)
        is EditNoteOutcomeProjection.RetryAvailable -> EditNoteSaveState.RetryAvailable(outcome.message, outcome.mutationId)
        is EditNoteOutcomeProjection.Ambiguous -> EditNoteSaveState.Ambiguous(
            outcome.message,
            outcome.mutationId,
            outcome.evidenceMessage
        )
        is EditNoteOutcomeProjection.Saved -> EditNoteSaveState.Saved(
            "Saved. Anki was re-read and holds the values you entered."
        )
    }

    /**
     * The ViewModel reduces a `NoteMutationOutcome` to this small projection so the mapper does not
     * need the outcome type's every variant in its signature, and so the mapping stays testable.
     */
    sealed interface EditNoteOutcomeProjection {
        data object NoChanges : EditNoteOutcomeProjection
        data class Validation(val messages: List<String>) : EditNoteOutcomeProjection
        data class Refusal(val message: String) : EditNoteOutcomeProjection
        data class Ledger(val detail: String) : EditNoteOutcomeProjection
        data class ReadFailed(val message: String) : EditNoteOutcomeProjection
        data class Conflict(val message: String, val mutationId: String) : EditNoteOutcomeProjection
        data class RetryAvailable(val message: String, val mutationId: String) : EditNoteOutcomeProjection
        data class Ambiguous(
            val message: String,
            val mutationId: String,
            val evidenceMessage: String?
        ) : EditNoteOutcomeProjection

        data object Saved : EditNoteOutcomeProjection
    }

    fun validationMessages(errors: List<NoteEditValidationError>): List<String> = errors.map { error ->
        when (error) {
            is NoteEditValidationError.UnknownField -> "Field ${error.ordinal + 1} does not exist on this note any more."
            is NoteEditValidationError.FieldContainsSeparator ->
                "A field contains the Anki field separator character and cannot be written."
            NoteEditValidationError.FieldIdentityUnavailable ->
                "This note's type identity is unavailable, so its fields cannot be mapped safely."
            is NoteEditValidationError.InvalidTag ->
                "\"${error.tag}\" cannot be stored as an Anki tag. Tags cannot contain spaces or control " +
                    "characters, and every part between \"::\" must have text."
            NoteEditValidationError.DeckTargetWrongBackend -> "That deck belongs to a different Anki backend."
            NoteEditValidationError.DeckTargetNotFound -> "That deck no longer exists in this collection."
            NoteEditValidationError.DeckTargetFiltered -> "Cards cannot be moved into a filtered deck."
            NoteEditValidationError.DeckTargetUnverifiable ->
                "Anki did not say whether that deck is filtered, so the move is refused."
            NoteEditValidationError.SourceDeckUnknown ->
                "This card's current deck is unknown, so the move cannot be described or verified."
            NoteEditValidationError.SourceDeckFiltered ->
                "This card currently sits in a filtered deck. Restore it in Anki before moving it here."
        }
    }

    fun refusalMessage(reason: NoteMutationRefusal): String = when (reason) {
        is NoteMutationRefusal.UnsupportedDimension -> when (reason.dimension) {
            "card_details" -> "This backend cannot read note details, so it cannot edit them."
            "fields" -> "This backend does not offer field editing."
            "tags" -> "This backend does not offer tag editing."
            "deck" -> "This backend does not offer moving a card to another deck."
            "deck_scope" -> "This backend's deck move does not have the card-only scope this screen describes."
            else -> "That part of the edit is not supported by this backend."
        }
        NoteMutationRefusal.TrailingEmptyFieldNotRepresentable ->
            "Anki's public interface cannot store an empty last field, so this edit cannot be written."
        NoteMutationRefusal.BackendMismatch ->
            "The note belongs to a different backend than the one currently connected. The edit was not sent."
        is NoteMutationRefusal.DeckListingUnavailable ->
            "The deck list could not be read (${message(reason.error)}), so the move cannot be verified."
        NoteMutationRefusal.SaveInProgress -> "A save is already running. Wait for it to finish."
        is NoteMutationRefusal.StudyActivityOverlaps ->
            "This note is part of unfinished study work (${reason.block.source}). Finish or resolve it before editing."
        is NoteMutationRefusal.RetryNotAllowed ->
            "This edit cannot be retried in its current state (${reason.status.name})."
        NoteMutationRefusal.UnknownMutation -> "That edit is no longer known to Study Agent."
        NoteMutationRefusal.PayloadUnavailable ->
            "Study Agent restarted, so the values of that edit are gone. Open the note again and re-enter the edit."
        is NoteMutationRefusal.ConflictNotResolvable ->
            "That edit is not in a conflicted state (${reason.status.name}), so it cannot be superseded."
    }

    fun reasonMessage(reason: NoteMutationReason): String = when (reason) {
        NoteMutationReason.NONE -> ""
        NoteMutationReason.CONFLICT_BEFORE_WRITE ->
            "The note changed between the moment it was read and the moment Study Agent was about to " +
                "write. Nothing was written."
        NoteMutationReason.CONFLICT_REPORTED_BY_BACKEND -> "The backend refused the write as a conflict."
        NoteMutationReason.NOT_APPLIED_BY_BACKEND ->
            "The backend refused the write before applying it. Nothing changed, so it can be retried."
        NoteMutationReason.OUTCOME_UNKNOWN ->
            "The write was sent but its answer was lost. Study Agent cannot prove whether Anki applied it."
        NoteMutationReason.PARTIAL_OPERATION_UNKNOWN ->
            "Part of this edit was written and the outcome of a later part is unknown. The whole edit " +
                "cannot be retried safely."
        NoteMutationReason.ABANDONED_ON_RESTART ->
            "Study Agent restarted before this edit was sent. Nothing was written."
        NoteMutationReason.RECOVERED_AFTER_RESTART ->
            "Study Agent restarted while this edit was in flight. Whether Anki applied it is unknown."
        NoteMutationReason.RECONCILED_APPLIED -> "Read-only evidence showed the edit is present."
        NoteMutationReason.RECONCILED_NOT_APPLIED -> "Read-only evidence showed the edit is absent."
        NoteMutationReason.POST_WRITE_VERIFICATION_MISMATCH ->
            "Anki accepted the write, but a fresh read of the note does not hold the values you entered. " +
                "Study Agent cannot prove what Anki stored."
        NoteMutationReason.POST_WRITE_UNVERIFIED ->
            "Anki accepted the write, but the note could not be re-read to confirm it."
        NoteMutationReason.USER_ATTESTED_APPLIED ->
            "Closed because you confirmed the edit is present in Anki. This is your confirmation, not " +
                "evidence from Anki."
        NoteMutationReason.USER_ATTESTED_NOT_APPLIED ->
            "Closed because you confirmed the edit is not in Anki. Re-open the note to edit it again."
    }

    /** Read-only state comparison wording. It is evidence for a human, never proof. */
    fun evidenceMessage(verification: NoteMutationVerification?): String? = when (verification) {
        null -> null
        NoteMutationVerification.MatchesIntent ->
            "Right now the note in Anki holds the values you entered. That does not prove this save " +
                "wrote them — another editor could have produced the same result."
        is NoteMutationVerification.DiffersFromIntent ->
            "Right now the note in Anki does not hold the values you entered (${verification.detail})."
        is NoteMutationVerification.Unverifiable ->
            "Study Agent cannot compare the note with your edit (${verification.detail})."
    }

    /** What the screen offers for a mutation that already owns this note. */
    fun blockedMutation(
        record: NoteMutationRecord,
        evidence: NoteMutationVerification? = null
    ): EditNoteBlockedMutation {
        val ambiguous = record.status == NoteMutationStatus.AMBIGUOUS
        return EditNoteBlockedMutation(
            mutationId = record.mutationId.value,
            statusLabel = statusLabel(record.status),
            message = reasonMessage(record.reason).ifBlank { statusMessage(record.status) },
            canRecover = ambiguous || record.status == NoteMutationStatus.SUBMITTING,
            canAttest = ambiguous,
            canRetry = record.status == NoteMutationStatus.RETRY_ALLOWED,
            canResume = record.status == NoteMutationStatus.PREPARED,
            canRestartAfterConflict = record.status == NoteMutationStatus.CONFLICT,
            evidenceMessage = evidenceMessage(evidence)
        )
    }

    fun statusLabel(status: NoteMutationStatus): String = when (status) {
        NoteMutationStatus.PREPARED -> "Recorded, not sent"
        NoteMutationStatus.SUBMITTING -> "In flight"
        NoteMutationStatus.APPLIED -> "Saved"
        NoteMutationStatus.RETRY_ALLOWED -> "Not applied — can retry"
        NoteMutationStatus.AMBIGUOUS -> "Outcome unknown"
        NoteMutationStatus.CONFLICT -> "Closed — note changed"
    }

    private fun statusMessage(status: NoteMutationStatus): String = when (status) {
        NoteMutationStatus.PREPARED -> "This edit was recorded but not sent to Anki."
        NoteMutationStatus.SUBMITTING -> "This edit was sent to Anki and no answer was recorded."
        NoteMutationStatus.APPLIED -> "This edit was saved and confirmed by a fresh read of the note."
        NoteMutationStatus.RETRY_ALLOWED -> "Anki refused this edit before applying it."
        NoteMutationStatus.AMBIGUOUS ->
            "Study Agent cannot prove whether Anki applied this edit. Check the note in Anki, then confirm."
        NoteMutationStatus.CONFLICT -> "This edit was closed without being written."
    }

    fun attestationLabel(attestation: NoteMutationAttestation): String = when (attestation) {
        NoteMutationAttestation.APPLIED_IN_COLLECTION -> "It is saved in Anki"
        NoteMutationAttestation.ABSENT_FROM_COLLECTION -> "It is not in Anki"
    }

    /** Tag input is one line; Anki tags are space separated, so the line is split on whitespace. */
    fun tagInputResult(raw: String): TagInputResult {
        val parts = raw.split(' ', '\t', '\n', '\u3000').filter { it.isNotBlank() }
        return when (val normalized = NoteEditPlanner.normalizeTags(parts)) {
            is TagNormalization.Valid -> TagInputResult.Accepted(normalized.tags)
            is TagNormalization.Invalid -> TagInputResult.Rejected(
                "\"${normalized.tag}\" cannot be stored as an Anki tag. Tags cannot contain spaces or " +
                    "control characters, and every part between \"::\" must have text."
            )
        }
    }

    sealed interface TagInputResult {
        data class Accepted(val tags: List<String>) : TagInputResult
        data class Rejected(val message: String) : TagInputResult
    }

    fun message(error: AnkiError): String = when (error) {
        is AnkiError.PermissionRequired -> "Anki permission is not granted"
        is AnkiError.InvalidRequest -> "the request was refused"
        is AnkiError.NoteNotFound -> "the note no longer exists"
        is AnkiError.CardNotFound -> "the card no longer exists"
        is AnkiError.DeckNotFound -> "the deck no longer exists"
        is AnkiError.StaleCardReference -> "the card reference is stale"
        is AnkiError.BackendUnavailable, is AnkiError.ProviderUnavailable -> "Anki is not available"
        is AnkiError.CollectionUnavailable -> "the collection is not open"
        is AnkiError.QueryFailure, is AnkiError.InvalidCursor -> "the read failed"
        is AnkiError.MalformedResponse, is AnkiError.DataIntegrityFailure -> "the data could not be trusted"
        is AnkiError.UnsupportedAction, is AnkiError.UnsupportedApi, is AnkiError.ActionNotApplicable ->
            "this backend does not support that"
        is AnkiError.TransientFailure -> "a temporary failure"
        else -> "an error"
    }
}
