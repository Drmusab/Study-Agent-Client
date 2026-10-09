package com.studyagent.client.ui

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCardDetails
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiNoteField
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.NoteConflictGuarantee
import com.studyagent.client.core.anki.edit.ChangedFieldMark
import com.studyagent.client.core.anki.edit.DeckChange
import com.studyagent.client.core.anki.edit.NoteDeckChangeScope
import com.studyagent.client.core.anki.edit.NoteEditBase
import com.studyagent.client.core.anki.edit.NoteMutationAttestation
import com.studyagent.client.core.anki.edit.NoteMutationId
import com.studyagent.client.core.anki.edit.NoteMutationOperation
import com.studyagent.client.core.anki.edit.NoteMutationPlan
import com.studyagent.client.core.anki.edit.NoteMutationReason
import com.studyagent.client.core.anki.edit.NoteMutationRecord
import com.studyagent.client.core.anki.edit.NoteMutationSemantics
import com.studyagent.client.core.anki.edit.NoteMutationStatus
import com.studyagent.client.core.anki.edit.NoteMutationVerification
import com.studyagent.client.ui.screens.editnote.EditNoticeTone
import com.studyagent.client.ui.screens.editnote.EditNoteBlockedMutation
import com.studyagent.client.ui.screens.editnote.EditNoteMapper
import com.studyagent.client.ui.screens.editnote.EditNoteSaveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 17 — the edit screen's projection rules, checked where they live: in the mapper, not in
 * Compose. The screen renders what this produces, so every contract-mandated wording rule and every
 * "may this control be used" answer is asserted here (INV-17-17: the UI decides nothing).
 */
class EditNoteMapperTest {

    private val backend = AnkiBackendId.AnkiDroidLocal
    private val cardRef = AnkiCardRef(backend, cardId = "card-1", noteId = "note-1", cardOrd = 0)
    private val noteRef = AnkiNoteRef(backend, "note-1")

    private val fullCapabilities = AnkiCapabilities(
        cardDetails = true,
        editNoteFields = true,
        editNoteTags = true,
        changeCardDeck = true,
        noteEditConflictGuarantee = NoteConflictGuarantee.BEST_EFFORT_PRE_SAVE_REREAD
    )

    private val semantics = NoteMutationSemantics.ANKIDROID_V2_24_1

    private fun details(
        fields: List<AnkiNoteField>? = listOf(
            AnkiNoteField("Front", "front text", 0),
            AnkiNoteField("Back", "back text", 1)
        ),
        tags: List<String>? = listOf("alpha"),
        deckRef: AnkiDeckRef? = AnkiDeckRef(backend, "deck-a"),
        deckName: String? = "A",
        noteRef: AnkiNoteRef? = this.noteRef,
        noteTypeId: String? = "model-1",
        noteTypeName: String? = "Basic",
        templateName: String? = "Card 1",
        cardOrd: Int? = 0
    ): AnkiCardDetails = AnkiCardDetails(
        cardRef = cardRef,
        noteRef = noteRef,
        cardOrd = cardOrd,
        deckRef = deckRef,
        deckName = deckName,
        noteTypeId = noteTypeId,
        noteTypeName = noteTypeName,
        templateName = templateName,
        questionHtml = null,
        answerHtml = null,
        questionText = null,
        answerText = null,
        pureAnswerText = null,
        fields = fields,
        tags = tags,
        flag = null,
        cardType = null,
        queueState = null,
        scheduling = null,
        originalDeckRef = null,
        noteCreatedEpochSeconds = null,
        noteModifiedEpochSeconds = null
    )

    private fun base(details: AnkiCardDetails = details()): NoteEditBase =
        NoteEditBase.from(details) ?: error("the fixture must be editable")

    private fun decks(): List<AnkiDeck> = listOf(
        AnkiDeck(ref = AnkiDeckRef(backend, "deck-a"), name = "A", isFiltered = false),
        AnkiDeck(ref = AnkiDeckRef(backend, "deck-b"), name = "B", isFiltered = false),
        AnkiDeck(ref = AnkiDeckRef(backend, "deck-filtered"), name = "Filtered", isFiltered = true),
        AnkiDeck(ref = AnkiDeckRef(backend, "deck-unknown"), name = "Unknown", isFiltered = null),
        AnkiDeck(ref = AnkiDeckRef(AnkiBackendId.PcAgent("p"), "deck-foreign"), name = "Foreign", isFiltered = false)
    )

    private fun input(
        details: AnkiCardDetails = details(),
        base: NoteEditBase = base(details),
        capabilities: AnkiCapabilities = fullCapabilities,
        semantics: NoteMutationSemantics = this.semantics,
        draftFieldValues: Map<Int, String> = emptyMap(),
        draftTags: List<String>? = null,
        selectedDeckId: String? = null,
        decks: List<AnkiDeck> = decks(),
        decksLoading: Boolean = false,
        decksIssue: AnkiError? = null,
        saveState: EditNoteSaveState = EditNoteSaveState.Idle,
        blockedByMutation: EditNoteBlockedMutation? = null
    ) = EditNoteMapper.Input(
        base = base,
        details = details,
        capabilities = capabilities,
        semantics = semantics,
        draftFieldValues = draftFieldValues,
        draftTags = draftTags,
        selectedDeckId = selectedDeckId,
        decks = decks,
        decksLoading = decksLoading,
        decksIssue = decksIssue,
        tagIssue = null,
        saveState = saveState,
        blockedByMutation = blockedByMutation
    )

    private fun record(
        status: NoteMutationStatus,
        reason: NoteMutationReason = NoteMutationReason.NONE,
        changedFields: List<ChangedFieldMark> = listOf(ChangedFieldMark(0, "Front")),
        deckChange: DeckChange? = null,
        lastEntered: Int = 0
    ) = NoteMutationRecord(
        mutationId = NoteMutationId("m-1"),
        backendId = backend,
        cardRef = cardRef,
        noteRef = noteRef,
        noteTypeId = "model-1",
        changedFields = changedFields,
        tagsChanged = false,
        deckChange = deckChange,
        plan = NoteMutationPlan(
            buildList {
                if (changedFields.isNotEmpty()) {
                    add(NoteMutationOperation.UpdateNoteContent(updatesFields = true, updatesTags = false))
                }
                if (deckChange != null) add(NoteMutationOperation.ChangeDeck(deckChange.toDeck))
                if (isEmpty()) add(NoteMutationOperation.ChangeDeck(AnkiDeckRef(backend, "deck-b")))
            }
        ),
        status = status,
        lastEnteredOperation = lastEntered,
        attemptCount = 1,
        reason = reason,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L
    )

    private fun noticesText(state: com.studyagent.client.ui.screens.editnote.EditNoteEditorState): String =
        state.notices.joinToString("\n") { it.message }

    // ---- whether the editor is offered at all -----------------------------------------------------

    @Test
    fun editingIsRefusedWhenTheBackendCannotReadTheNote() {
        val decision = EditNoteMapper.baseOrRefusal(details(), fullCapabilities.copy(cardDetails = false))
        assertTrue(decision is EditNoteMapper.BaseDecision.Refused)
        assertTrue((decision as EditNoteMapper.BaseDecision.Refused).message.contains("cannot read the note"))
    }

    @Test
    fun editingIsRefusedWhenNoEditingDimensionIsOffered() {
        val decision = EditNoteMapper.baseOrRefusal(
            details(),
            fullCapabilities.copy(editNoteFields = false, editNoteTags = false, changeCardDeck = false)
        )
        assertTrue(decision is EditNoteMapper.BaseDecision.Refused)
        assertTrue((decision as EditNoteMapper.BaseDecision.Refused).message.contains("does not offer note editing"))
    }

    @Test
    fun editingIsRefusedWhenTheNoteIdentityOrContentIsNotExposed() {
        // No note identity.
        assertTrue(
            EditNoteMapper.baseOrRefusal(details(noteRef = null), fullCapabilities)
                is EditNoteMapper.BaseDecision.Refused
        )
        // No field list.
        assertTrue(
            EditNoteMapper.baseOrRefusal(details(fields = null), fullCapabilities)
                is EditNoteMapper.BaseDecision.Refused
        )
        // No tag list.
        assertTrue(
            EditNoteMapper.baseOrRefusal(details(tags = null), fullCapabilities)
                is EditNoteMapper.BaseDecision.Refused
        )
        // Ordinals that do not match template order: identity is not safe, so nothing is guessed.
        val shuffled = details(
            fields = listOf(AnkiNoteField("Front", "front text", 1), AnkiNoteField("Back", "back text", 0))
        )
        assertTrue(
            EditNoteMapper.baseOrRefusal(shuffled, fullCapabilities) is EditNoteMapper.BaseDecision.Refused
        )
    }

    @Test
    fun editingIsOfferedWhenAtLeastOneDimensionIsAvailable() {
        val tagsOnly = fullCapabilities.copy(editNoteFields = false, changeCardDeck = false)
        val decision = EditNoteMapper.baseOrRefusal(details(), tagsOnly)
        assertTrue(decision is EditNoteMapper.BaseDecision.Ready)
        val projected = EditNoteMapper.project(input(capabilities = tagsOnly))
        assertFalse(projected.capabilities.fields)
        assertTrue(projected.capabilities.tags)
        assertFalse(projected.capabilities.deck)
        assertTrue(projected.fields.none { it.editable })
        assertTrue(projected.tags.editable)
    }

    // ---- capability truth comes from the backend, never from its name ------------------------------

    @Test
    fun aDeckMoveIsOfferedOnlyForCardScopedBackends() {
        assertEquals(
            true,
            EditNoteMapper.capabilitiesOf(fullCapabilities, semantics).deck
        )
        for (scope in listOf(NoteDeckChangeScope.NOTE_WIDE, NoteDeckChangeScope.UNKNOWN)) {
            val projected = EditNoteMapper.capabilitiesOf(
                fullCapabilities,
                semantics.copy(deckChangeScope = scope)
            )
            assertFalse("$scope is not the scope this screen can describe", projected.deck)
        }
        // Even a card-scoped backend that does not offer the write gets no deck control.
        assertFalse(EditNoteMapper.capabilitiesOf(fullCapabilities.copy(changeCardDeck = false), semantics).deck)
    }

    @Test
    fun unverifiedSemanticsOfferNothingBeyondWhatTheBackendProves() {
        val projected = EditNoteMapper.capabilitiesOf(fullCapabilities, NoteMutationSemantics.UNVERIFIED)
        assertFalse(projected.deck)
        assertFalse(projected.contentWriteAtomic)
        assertFalse(projected.authoritativeReconciliation)
        assertFalse(projected.trailingEmptyFieldRepresentable)
        assertEquals(NoteConflictGuarantee.NONE, projected.conflictGuarantee)
    }

    @Test
    fun theLockedBackendNeverClaimsAuthoritativeReconciliation() {
        assertFalse(EditNoteMapper.capabilitiesOf(fullCapabilities, semantics).authoritativeReconciliation)
        assertFalse(fullCapabilities.authoritativeMutationReconciliation)
    }

    // ---- field, tag and deck projection -----------------------------------------------------------

    @Test
    fun fieldsProjectPositionallyWithTheDraftOverlaid() {
        val state = EditNoteMapper.project(input(draftFieldValues = mapOf(1 to "edited back")))
        assertEquals(listOf(0, 1), state.fields.map { it.ordinal })
        assertEquals(listOf("Front", "Back"), state.fields.map { it.label })
        assertEquals("front text", state.fields[0].draftValue)
        assertEquals("front text", state.fields[0].originalValue)
        assertEquals("edited back", state.fields[1].draftValue)
        assertEquals("back text", state.fields[1].originalValue)
        assertFalse(state.fields[0].changed)
        assertTrue(state.fields[1].changed)
        assertTrue(state.dirty)
    }

    @Test
    fun anUnchangedDraftIsNotDirtyAndOffersNoSave() {
        val state = EditNoteMapper.project(input(draftFieldValues = mapOf(0 to "front text")))
        assertFalse(state.dirty)
        assertFalse(state.canSave)
    }

    @Test
    fun aChangedFieldMakesSaveAvailable() {
        val state = EditNoteMapper.project(input(draftFieldValues = mapOf(0 to "new front")))
        assertTrue(state.dirty)
        assertTrue(state.canSave)
    }

    @Test
    fun theFieldSeparatorIsRefusedByTheControlBeforeAnySave() {
        val state = EditNoteMapper.project(input(draftFieldValues = mapOf(0 to "a\u001Fb")))
        val issue = state.fields[0].issue
        assertNotNull(issue)
        assertTrue(issue!!.blocking)
        assertTrue(issue.message.contains("field separator"))
        assertFalse("a blocking field issue must stop save", state.canSave)
    }

    @Test
    fun anEmptyLastFieldIsRefusedWhenTheBackendCannotRepresentIt() {
        val state = EditNoteMapper.project(input(draftFieldValues = mapOf(1 to "")))
        val issue = state.fields[1].issue
        assertNotNull(issue)
        assertTrue(issue!!.blocking)
        assertTrue(issue.message.contains("empty last field"))
        assertFalse(state.canSave)
        // The first field is not the last one, so an empty value there is not this problem.
        val first = EditNoteMapper.project(input(draftFieldValues = mapOf(0 to "")))
        assertNull(first.fields[0].issue)
        assertTrue(first.canSave)
    }

    @Test
    fun anEmptyLastFieldIsAllowedWhenTheBackendProvesItCanRepresentIt() {
        val state = EditNoteMapper.project(
            input(
                draftFieldValues = mapOf(1 to ""),
                semantics = semantics.copy(trailingEmptyFieldRepresentable = true)
            )
        )
        assertNull(state.fields[1].issue)
        assertTrue(state.canSave)
    }

    @Test
    fun tagsProjectAsOneReplaceableSet() {
        val unchanged = EditNoteMapper.project(input())
        assertEquals(listOf("alpha"), unchanged.tags.originalTags)
        assertEquals(listOf("alpha"), unchanged.tags.draftTags)
        assertFalse(unchanged.tags.changed)

        val replaced = EditNoteMapper.project(input(draftTags = listOf("alpha", "beta")))
        assertEquals(listOf("alpha", "beta"), replaced.tags.draftTags)
        assertTrue(replaced.tags.changed)
        assertTrue(replaced.dirty)
        assertTrue(replaced.canSave)
    }

    @Test
    fun deckOptionsMarkFilteredAndUnverifiableDecksAsUnselectable() {
        val state = EditNoteMapper.project(input())
        val byId = state.deck.options.associateBy { it.deckId }
        assertTrue(byId.getValue("deck-b").selectable)
        assertFalse("a filtered deck is refused by the backend", byId.getValue("deck-filtered").selectable)
        assertTrue(byId.getValue("deck-filtered").filtered)
        assertFalse("an unverifiable deck cannot be checked", byId.getValue("deck-unknown").selectable)
        assertTrue(byId.getValue("deck-unknown").unverifiable)
        assertFalse("another backend's deck is never offered", byId.containsKey("deck-foreign"))
    }

    @Test
    fun deckSelectionDefaultsToTheCurrentDeckAndIsNotADraftChange() {
        val state = EditNoteMapper.project(input())
        assertEquals("deck-a", state.deck.currentDeckId)
        assertEquals("deck-a", state.deck.selectedDeckId)
        assertFalse(state.deck.changed)
        assertFalse(state.dirty)
        // The label is display only; identity stays the stable deck id.
        assertEquals("A", state.deck.currentDeckLabel)
    }

    @Test
    fun selectingAnotherDeckIsADraftChangeWithTheIdAsIdentity() {
        val state = EditNoteMapper.project(input(selectedDeckId = "deck-b"))
        assertEquals("deck-b", state.deck.selectedDeckId)
        assertTrue(state.deck.changed)
        assertTrue(state.canSave)
    }

    @Test
    fun anUnknownCurrentDeckRefusesTheMoveInsteadOfGuessing() {
        val noDeck = details(deckRef = null, deckName = null)
        val state = EditNoteMapper.project(input(details = noDeck, base = base(noDeck)))
        assertFalse(state.deck.editable)
        val issue = state.deck.issue
        assertNotNull(issue)
        assertTrue(issue!!.blocking)
        assertTrue(issue.message.contains("deck is unknown"))
    }

    @Test
    fun anUnreadableDeckListRefusesTheMove() {
        val state = EditNoteMapper.project(input(decksIssue = AnkiError.BackendUnavailable()))
        val issue = state.deck.issue
        assertNotNull(issue)
        assertTrue(issue!!.blocking)
        assertTrue(issue.message.contains("could not be read"))
        assertFalse(state.canSave)
    }

    @Test
    fun aLoadingDeckListIsShownAsLoadingWithoutAnIssue() {
        val state = EditNoteMapper.project(input(decksLoading = true, decks = emptyList()))
        assertTrue(state.deck.loading)
        assertNull(state.deck.issue)
        assertTrue(state.deck.options.isEmpty())
    }

    @Test
    fun theCardLabelDescribesTheTemplateAndTheCardOrdinal() {
        assertEquals("Card 1 (card 1)", EditNoteMapper.project(input()).cardLabel)
        val noTemplate = details(templateName = null)
        assertEquals("Card 1", EditNoteMapper.project(input(details = noTemplate)).cardLabel)
        val noOrdinal = details(cardOrd = null)
        assertEquals("Card 1", EditNoteMapper.project(input(details = noOrdinal)).cardLabel)
        val neither = details(templateName = null, cardOrd = null)
        assertNull(EditNoteMapper.project(input(details = neither)).cardLabel)
    }

    // ---- contract wording -------------------------------------------------------------------------

    @Test
    fun theDeckNoticeSaysCardScopeAndNeverSaysMoveNote() {
        val text = noticesText(EditNoteMapper.project(input()))
        assertTrue(text.contains("THIS CARD ONLY"))
        assertFalse("the screen must not claim a note-wide move", text.contains("moves the note"))
        assertFalse(text.lowercase().contains("move note"))
    }

    @Test
    fun theConflictNoticeSaysBestEffortAndNeverSaysProtected() {
        val text = noticesText(EditNoteMapper.project(input()))
        assertTrue(text.contains("best-effort detection, not protection"))
        assertFalse(text.contains("protected against concurrent"))
    }

    @Test
    fun aBackendWithNoConflictDetectionSaysSo() {
        val text = noticesText(
            EditNoteMapper.project(
                input(semantics = semantics.copy(conflictGuarantee = NoteConflictGuarantee.NONE))
            )
        )
        assertTrue(text.contains("Nothing detects a concurrent change"))
    }

    @Test
    fun theTwoWriteNoticeAppearsOnlyWhenBothDimensionsAreOffered() {
        val both = noticesText(EditNoteMapper.project(input()))
        assertTrue(both.contains("second, separate write"))

        val contentOnly = noticesText(
            EditNoteMapper.project(input(capabilities = fullCapabilities.copy(changeCardDeck = false)))
        )
        assertFalse(contentOnly.contains("second, separate write"))
    }

    @Test
    fun theSiblingCardWarningAppearsOnlyWhenAFieldActuallyChanged() {
        val untouched = noticesText(EditNoteMapper.project(input()))
        assertFalse(untouched.contains("generate additional cards"))

        val edited = noticesText(EditNoteMapper.project(input(draftFieldValues = mapOf(0 to "new front"))))
        assertTrue(edited.contains("generate additional cards"))
        assertTrue(edited.contains("placed by Anki, not by this screen"))
    }

    @Test
    fun noReconciliationMeansTheScreenSaysTheUserWillBeAsked() {
        val text = noticesText(EditNoteMapper.project(input()))
        assertTrue(text.contains("cannot prove afterwards whether it applied"))
        assertTrue(text.contains("asked to check Anki and confirm"))
    }

    @Test
    fun atomicityWordingFollowsTheDeclaredSemantics() {
        assertTrue(noticesText(EditNoteMapper.project(input())).contains("one all-or-nothing change"))
        val notAtomic = noticesText(
            EditNoteMapper.project(input(semantics = semantics.copy(contentWriteAtomic = false)))
        )
        assertTrue(notAtomic.contains("does not guarantee that fields and tags are written together"))
    }

    @Test
    fun aMissingNoteTypeIdentityIsAnnouncedWhenFieldsAreEditable() {
        val noIdentity = details(noteTypeId = null)
        val state = EditNoteMapper.project(input(details = noIdentity, base = base(noIdentity)))
        assertTrue(noticesText(state).contains("type identity is unavailable"))
        assertEquals(EditNoticeTone.WARNING, state.notices.last { it.message.contains("type identity") }.tone)
    }

    @Test
    fun savingIsAnnouncedAsADoNotCloseNotice() {
        val state = EditNoteMapper.project(input(saveState = EditNoteSaveState.Saving))
        assertTrue(noticesText(state).contains("Do not close this screen"))
        assertFalse("save cannot be offered while saving", state.canSave)
    }

    // ---- outcome → presentation -------------------------------------------------------------------

    @Test
    fun everyOutcomeProjectsToItsOwnPresentationState() {
        assertTrue(
            EditNoteMapper.saveStateOf(EditNoteMapper.EditNoteOutcomeProjection.NoChanges) is EditNoteSaveState.Blocked
        )
        assertTrue(
            EditNoteMapper.saveStateOf(EditNoteMapper.EditNoteOutcomeProjection.Validation(listOf("a reason"))) is EditNoteSaveState.Blocked
        )
        assertTrue(
            EditNoteMapper.saveStateOf(EditNoteMapper.EditNoteOutcomeProjection.Refusal("refused")) is EditNoteSaveState.Blocked
        )
        assertTrue(
            EditNoteMapper.saveStateOf(EditNoteMapper.EditNoteOutcomeProjection.Ledger("store_failed")) is EditNoteSaveState.Blocked
        )
        assertTrue(
            EditNoteMapper.saveStateOf(EditNoteMapper.EditNoteOutcomeProjection.ReadFailed("read failed")) is EditNoteSaveState.Blocked
        )
        assertTrue(
            EditNoteMapper.saveStateOf(EditNoteMapper.EditNoteOutcomeProjection.Conflict("conflict", "m-1")) is EditNoteSaveState.Conflicted
        )
        assertTrue(
            EditNoteMapper.saveStateOf(EditNoteMapper.EditNoteOutcomeProjection.RetryAvailable("not applied", "m-1"))
                is EditNoteSaveState.RetryAvailable
        )
        assertTrue(
            EditNoteMapper.saveStateOf(EditNoteMapper.EditNoteOutcomeProjection.Ambiguous("unknown", "m-1", "evidence"))
                is EditNoteSaveState.Ambiguous
        )
        assertTrue(EditNoteMapper.saveStateOf(EditNoteMapper.EditNoteOutcomeProjection.Saved) is EditNoteSaveState.Saved)
    }

    @Test
    fun aSavedStateSaysTheNoteWasReRead() {
        val saved = EditNoteMapper.saveStateOf(EditNoteMapper.EditNoteOutcomeProjection.Saved)
            as EditNoteSaveState.Saved
        assertTrue(saved.message.contains("re-read"))
        assertTrue(saved.message.contains("holds the values you entered"))
    }

    @Test
    fun aBlockedOutcomeCarriesTheRefusalReasons() {
        val blocked = EditNoteMapper.saveStateOf(
            EditNoteMapper.EditNoteOutcomeProjection.Validation(listOf("first reason", "second reason"))
        ) as EditNoteSaveState.Blocked
        assertEquals(listOf("first reason", "second reason"), blocked.issues)
        assertTrue(blocked.message.contains("before anything was written"))
    }

    @Test
    fun nothingThatWasNotWrittenMayBeWordedAsAFailure() {
        // Refusals, validation, ledger and read failures are all "nothing was written".
        for (outcome in listOf(
            EditNoteMapper.EditNoteOutcomeProjection.NoChanges,
            EditNoteMapper.EditNoteOutcomeProjection.Validation(listOf("x")),
            EditNoteMapper.EditNoteOutcomeProjection.Refusal("no"),
            EditNoteMapper.EditNoteOutcomeProjection.Ledger("store"),
            EditNoteMapper.EditNoteOutcomeProjection.ReadFailed("io")
        )) {
            val state = EditNoteMapper.saveStateOf(outcome)
            assertTrue(state is EditNoteSaveState.Blocked)
        }
    }

    @Test
    fun anUnknownOutcomeIsWordedAsUnprovableNeverAsFailed() {
        val text = EditNoteMapper.reasonMessage(NoteMutationReason.OUTCOME_UNKNOWN)
        assertTrue(text.contains("cannot prove whether Anki applied it"))
        assertFalse(text.lowercase().contains("failed"))

        val partial = EditNoteMapper.reasonMessage(NoteMutationReason.PARTIAL_OPERATION_UNKNOWN)
        assertTrue(partial.contains("Part of this edit was written"))
        assertTrue(partial.contains("cannot be retried safely"))
    }

    @Test
    fun aVerificationFailureIsWordedAsUnprovableNotAsNonApplication() {
        val mismatch = EditNoteMapper.reasonMessage(NoteMutationReason.POST_WRITE_VERIFICATION_MISMATCH)
        assertTrue(mismatch.contains("does not hold the values you entered"))
        assertTrue(mismatch.contains("cannot prove"))
        assertFalse(mismatch.contains("Nothing was written"))

        val unread = EditNoteMapper.reasonMessage(NoteMutationReason.POST_WRITE_UNVERIFIED)
        assertTrue(unread.contains("could not be re-read"))
    }

    @Test
    fun anAttestationIsWordedAsTheUsersConfirmationNotAsBackendEvidence() {
        val applied = EditNoteMapper.reasonMessage(NoteMutationReason.USER_ATTESTED_APPLIED)
        assertTrue(applied.contains("you confirmed"))
        assertTrue(applied.contains("not evidence from Anki"))

        val absent = EditNoteMapper.reasonMessage(NoteMutationReason.USER_ATTESTED_NOT_APPLIED)
        assertTrue(absent.contains("you confirmed"))
        assertTrue(absent.contains("Re-open the note"))
    }

    @Test
    fun evidenceWordingNeverClaimsProof() {
        val matches = EditNoteMapper.evidenceMessage(NoteMutationVerification.MatchesIntent)
        assertNotNull(matches)
        assertTrue(matches!!.contains("does not prove this save wrote them"))
        assertTrue(matches.contains("another editor could have produced the same result"))

        val differs = EditNoteMapper.evidenceMessage(
            NoteMutationVerification.DiffersFromIntent("field_values")
        )
        assertTrue(differs!!.contains("does not hold the values you entered"))
        assertTrue(differs.contains("field_values"))

        val unverifiable = EditNoteMapper.evidenceMessage(
            NoteMutationVerification.Unverifiable("post_write_read_unavailable")
        )
        assertTrue(unverifiable!!.contains("cannot compare"))

        assertNull(EditNoteMapper.evidenceMessage(null))
    }

    // ---- a mutation that already owns the note ----------------------------------------------------

    @Test
    fun anAmbiguousRecordOffersAttestationAndRecoveryButNeverAnAutomaticRetry() {
        val blocked = EditNoteMapper.blockedMutation(record(NoteMutationStatus.AMBIGUOUS, NoteMutationReason.OUTCOME_UNKNOWN))
        assertTrue(blocked.canAttest)
        assertTrue(blocked.canRecover)
        assertFalse(blocked.canRetry)
        assertFalse(blocked.canResume)
        assertFalse(blocked.canRestartAfterConflict)
        assertEquals("m-1", blocked.mutationId)
        assertTrue(blocked.message.contains("cannot prove whether Anki applied"))
    }

    @Test
    fun aRetryAllowedRecordOffersRetryAndNothingElse() {
        val blocked = EditNoteMapper.blockedMutation(
            record(NoteMutationStatus.RETRY_ALLOWED, NoteMutationReason.NOT_APPLIED_BY_BACKEND)
        )
        assertTrue(blocked.canRetry)
        assertFalse(blocked.canAttest)
        assertFalse(blocked.canResume)
        assertFalse(blocked.canRestartAfterConflict)
    }

    @Test
    fun eachOtherStatusOffersExactlyItsOwnEscape() {
        val prepared = EditNoteMapper.blockedMutation(record(NoteMutationStatus.PREPARED))
        assertTrue(prepared.canResume)
        assertFalse(prepared.canRetry || prepared.canAttest || prepared.canRestartAfterConflict)

        val submitting = EditNoteMapper.blockedMutation(record(NoteMutationStatus.SUBMITTING))
        assertTrue(submitting.canRecover)
        assertFalse(submitting.canRetry || submitting.canAttest || submitting.canResume)

        val conflict = EditNoteMapper.blockedMutation(
            record(NoteMutationStatus.CONFLICT, NoteMutationReason.CONFLICT_BEFORE_WRITE)
        )
        assertTrue(conflict.canRestartAfterConflict)
        assertFalse(conflict.canRetry || conflict.canAttest || conflict.canResume || conflict.canRecover)

        val applied = EditNoteMapper.blockedMutation(record(NoteMutationStatus.APPLIED))
        assertFalse(applied.canRetry || applied.canAttest || applied.canResume || applied.canRecover ||
            applied.canRestartAfterConflict)
    }

    @Test
    fun aBlockingMutationStopsSaveEvenWhenTheDraftIsDirty() {
        val blocked = EditNoteMapper.blockedMutation(record(NoteMutationStatus.AMBIGUOUS))
        val state = EditNoteMapper.project(
            input(draftFieldValues = mapOf(0 to "new front"), blockedByMutation = blocked)
        )
        assertTrue(state.dirty)
        assertFalse(state.canSave)
        assertEquals(blocked, state.blockedByMutation)
    }

    @Test
    fun evidenceIsCarriedIntoTheBlockedMutationForTheHumanDeciding() {
        val blocked = EditNoteMapper.blockedMutation(
            record(NoteMutationStatus.AMBIGUOUS),
            evidence = NoteMutationVerification.MatchesIntent
        )
        assertNotNull(blocked.evidenceMessage)
        assertTrue(blocked.evidenceMessage!!.contains("does not prove"))
    }

    @Test
    fun statusLabelsNeverReuseADurableStatusName() {
        // The presentation layer must not rename durable truth (the GATE 11B rule, applied here).
        for (status in NoteMutationStatus.entries) {
            val label = EditNoteMapper.statusLabel(status)
            assertTrue("$status label is blank", label.isNotBlank())
            assertFalse("$status label reuses the durable name", label.contains(status.name))
            assertFalse("$status label reuses the durable name in lower case",
                label.lowercase().contains(status.name.lowercase()))
        }
        assertEquals("Outcome unknown", EditNoteMapper.statusLabel(NoteMutationStatus.AMBIGUOUS))
        assertEquals("Saved", EditNoteMapper.statusLabel(NoteMutationStatus.APPLIED))
    }

    @Test
    fun attestationLabelsAreTheUsersWordsNotTheBackends() {
        assertEquals(
            "It is saved in Anki",
            EditNoteMapper.attestationLabel(NoteMutationAttestation.APPLIED_IN_COLLECTION)
        )
        assertEquals(
            "It is not in Anki",
            EditNoteMapper.attestationLabel(NoteMutationAttestation.ABSENT_FROM_COLLECTION)
        )
    }

    // ---- tag input --------------------------------------------------------------------------------

    @Test
    fun tagInputIsSplitOnWhitespaceAndNormalized() {
        val accepted = EditNoteMapper.tagInputResult("  alpha\tbeta\u3000gamma\n")
        assertTrue(accepted is EditNoteMapper.TagInputResult.Accepted)
        assertEquals(listOf("alpha", "beta", "gamma"), (accepted as EditNoteMapper.TagInputResult.Accepted).tags)
    }

    @Test
    fun tagInputDeduplicatesCaseInsensitivelyKeepingTheFirstSpelling() {
        val accepted = EditNoteMapper.tagInputResult("Foo foo FOO bar")
        assertEquals(listOf("Foo", "bar"), (accepted as EditNoteMapper.TagInputResult.Accepted).tags)
    }

    @Test
    fun tagInputRejectsWhatTheBackendWouldRewrite() {
        for (raw in listOf("a::::b", "::lead", "trail::", "con\u0007trol")) {
            val result = EditNoteMapper.tagInputResult(raw)
            assertTrue("$raw must be refused", result is EditNoteMapper.TagInputResult.Rejected)
            assertTrue((result as EditNoteMapper.TagInputResult.Rejected).message.contains("cannot be stored"))
        }
    }

    @Test
    fun emptyTagInputIsAnEmptySetNotAnError() {
        val accepted = EditNoteMapper.tagInputResult("   ")
        assertTrue(accepted is EditNoteMapper.TagInputResult.Accepted)
        assertTrue((accepted as EditNoteMapper.TagInputResult.Accepted).tags.isEmpty())
    }

    // ---- error wording ----------------------------------------------------------------------------

    @Test
    fun errorMessagesAreUserFacingAndCarryNoProviderDetail() {
        assertEquals("Anki permission is not granted", EditNoteMapper.message(AnkiError.PermissionRequired()))
        assertEquals("Anki is not available", EditNoteMapper.message(AnkiError.BackendUnavailable()))
        assertEquals("the note no longer exists", EditNoteMapper.message(AnkiError.NoteNotFound("note-1")))
        for (error in listOf(
            AnkiError.PermissionRequired(),
            AnkiError.BackendUnavailable(),
            AnkiError.CollectionUnavailable("closed"),
            AnkiError.Unknown("binder died"),
            AnkiError.InvalidRequest("bad uri")
        )) {
            val text = EditNoteMapper.message(error)
            assertFalse("$text leaks provider detail", text.contains("binder"))
            assertFalse("$text leaks provider detail", text.contains("uri"))
            assertFalse("$text leaks provider detail", text.contains("closed"))
        }
    }
}
