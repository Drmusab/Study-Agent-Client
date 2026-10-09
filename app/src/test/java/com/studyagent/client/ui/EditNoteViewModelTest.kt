package com.studyagent.client.ui

import androidx.lifecycle.ViewModelStore
import com.studyagent.client.anki.edit.FakeNoteMutationBackend
import com.studyagent.client.anki.edit.InMemoryNoteMutationStore
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.edit.DefaultNoteMutationCoordinator
import com.studyagent.client.core.anki.edit.DefaultNoteMutationLedger
import com.studyagent.client.core.anki.edit.NoteEditBlock
import com.studyagent.client.core.anki.edit.NoteEditSafetyPolicy
import com.studyagent.client.core.anki.edit.NoteMutationAttestation
import com.studyagent.client.core.anki.edit.NoteMutationBackendResult
import com.studyagent.client.core.anki.edit.NoteMutationId
import com.studyagent.client.core.anki.edit.NoteMutationIdSource
import com.studyagent.client.core.anki.edit.NoteMutationStatus
import com.studyagent.client.ui.screens.editnote.EditNoteSaveState
import com.studyagent.client.ui.screens.editnote.EditNoteUiState
import com.studyagent.client.ui.screens.editnote.EditNoteViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 17 — the edit screen's state owner, driven end to end against the real coordinator, the real
 * ledger and a scriptable backend.
 *
 * What this suite pins is the part the domain tests cannot see: the screen never writes on its own,
 * never offers a write the contract refuses, adopts the backend's post-write read as its truth, and
 * keeps an unresolved mutation blocking the note until a human closes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditNoteViewModelTest {

    private val viewModelStores = mutableListOf<ViewModelStore>()

    @After
    fun tearDown() {
        viewModelStores.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    private class Fixture(
        capabilities: AnkiCapabilities = FakeNoteMutationBackend.defaultCapabilities()
    ) {
        val events = mutableListOf<String>()
        val backend = FakeNoteMutationBackend(capabilities = capabilities, events = events)
        val store = InMemoryNoteMutationStore(events)
        var block: NoteEditBlock? = null
        private var counter = 0
        val coordinator = DefaultNoteMutationCoordinator(
            backend,
            DefaultNoteMutationLedger(store, nowEpochMs = { 1_000L }),
            NoteEditSafetyPolicy { block },
            NoteMutationIdSource { NoteMutationId("m-${++counter}") },
            nowEpochMs = { 1_000L }
        )

        fun ready(model: EditNoteViewModel) = (model.uiState.value as EditNoteUiState.Ready).editor
    }

    /** The ViewModel's coroutines must share the test scheduler, or `advanceUntilIdle` cannot see them. */
    private fun TestScope.vm(
        fixture: Fixture,
        cardRef: AnkiCardRef = fixture.backend.cardRef()
    ): EditNoteViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        return EditNoteViewModel(
            initialBackend = fixture.backend,
            initialCardRef = cardRef,
            coordinator = fixture.coordinator
        ).also { model ->
            ViewModelStore().also { store ->
                store.put("edit-note", model)
                viewModelStores += store
            }
        }
    }

    // ---- loading ---------------------------------------------------------------------------------

    @Test
    fun theEditorStartsFromOneAuthoritativeReadAndIsNotDirty() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        val editor = f.ready(model)
        assertEquals(listOf("front text", "back text"), editor.fields.map { it.draftValue })
        assertEquals(listOf("front text", "back text"), editor.fields.map { it.originalValue })
        assertEquals(listOf("alpha"), editor.tags.draftTags)
        assertFalse(editor.dirty)
        assertFalse(editor.canSave)
        assertNull(editor.blockedByMutation)
        assertEquals(1, f.backend.readCount)
        assertTrue("nothing may be written while loading", f.backend.applyCalls.isEmpty())
    }

    @Test
    fun aBackendWithoutAnyEditingDimensionIsRefusedNotDegraded() = runTest {
        val f = Fixture(
            FakeNoteMutationBackend.defaultCapabilities().copy(
                editNoteFields = false,
                editNoteTags = false,
                changeCardDeck = false
            )
        )
        val model = vm(f)
        advanceUntilIdle()

        val state = model.uiState.value
        assertTrue("expected Refused, got $state", state is EditNoteUiState.Refused)
        assertTrue((state as EditNoteUiState.Refused).message.contains("does not offer note editing"))
        assertTrue(f.backend.applyCalls.isEmpty())
    }

    @Test
    fun aBackendThatCannotReadTheNoteIsRefused() = runTest {
        val f = Fixture(FakeNoteMutationBackend.defaultCapabilities().copy(cardDetails = false))
        val model = vm(f)
        advanceUntilIdle()

        assertTrue(model.uiState.value is EditNoteUiState.Refused)
    }

    @Test
    fun aFailedReadIsAnErrorAndNeverAnEmptyEditor() = runTest {
        val f = Fixture()
        f.backend.readFailure = AnkiError.BackendUnavailable()
        val model = vm(f)
        advanceUntilIdle()

        val state = model.uiState.value
        assertTrue("expected Error, got $state", state is EditNoteUiState.Error)
    }

    @Test
    fun aCardReferenceFromAnotherBackendIsAnErrorNotAGuess() = runTest {
        val f = Fixture()
        val foreign = AnkiCardRef(
            backendId = AnkiBackendId.PcAgent("elsewhere"),
            cardId = "card-1",
            noteId = "note-1",
            cardOrd = 0
        )
        val model = vm(f, foreign)
        advanceUntilIdle()

        val state = model.uiState.value
        assertTrue("expected Error, got $state", state is EditNoteUiState.Error)
        assertTrue(f.backend.applyCalls.isEmpty())
        assertEquals(0, f.backend.readCount)
    }

    // ---- drafting --------------------------------------------------------------------------------

    @Test
    fun editingAFieldOffersSaveAndResettingWithdrawsIt() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        model.onFieldChange(0, "new front")
        var editor = f.ready(model)
        assertTrue(editor.dirty)
        assertTrue(editor.canSave)

        model.resetField(0)
        editor = f.ready(model)
        assertFalse(editor.dirty)
        assertFalse(editor.canSave)
        assertTrue(f.backend.applyCalls.isEmpty())
    }

    @Test
    fun aFieldOrdinalTheNoteDoesNotHaveIsIgnored() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        model.onFieldChange(9, "no such field")
        val editor = f.ready(model)
        assertFalse(editor.dirty)
        assertFalse(editor.canSave)
    }

    @Test
    fun validTagInputReplacesTheWholeSetAndInvalidInputBlocksTheControl() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        model.addTags("beta  gamma")
        var editor = f.ready(model)
        assertEquals(listOf("alpha", "beta", "gamma"), editor.tags.draftTags)
        assertTrue(editor.tags.changed)
        assertNull(editor.tags.issue)

        model.addTags("bad::::tag")
        editor = f.ready(model)
        assertNotNull(editor.tags.issue)
        assertTrue(editor.tags.issue!!.blocking)
        assertFalse("a blocking tag issue must stop save", editor.canSave)

        model.resetTags()
        editor = f.ready(model)
        assertNull(editor.tags.issue)
        assertFalse(editor.dirty)
    }

    @Test
    fun removingATagIsPartOfTheSameReplaceOnlyDraft() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        model.removeTag("alpha")
        val editor = f.ready(model)
        assertEquals(emptyList<String>(), editor.tags.draftTags)
        assertTrue(editor.tags.changed)
        assertTrue(editor.canSave)
    }

    @Test
    fun aDeckSelectionIsADraftChangeAndAnUnknownDeckIsIgnored() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        model.selectDeck("deck-b")
        var editor = f.ready(model)
        assertEquals("deck-b", editor.deck.selectedDeckId)
        assertTrue(editor.deck.changed)
        assertTrue(editor.canSave)

        model.selectDeck("deck-that-does-not-exist")
        editor = f.ready(model)
        assertEquals("a deck the listing does not contain is never selected", "deck-b", editor.deck.selectedDeckId)

        // "Keep the current deck" withdraws the move.
        model.selectDeck(null)
        editor = f.ready(model)
        assertEquals("deck-a", editor.deck.selectedDeckId)
        assertFalse(editor.deck.changed)
    }

    // ---- saving ----------------------------------------------------------------------------------

    @Test
    fun savingWithoutAChangeWritesNothingAndRecordsNothing() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        model.save()
        advanceUntilIdle()

        val editor = f.ready(model)
        assertTrue(editor.saveState is EditNoteSaveState.Blocked)
        assertTrue((editor.saveState as EditNoteSaveState.Blocked).message.contains("Nothing to save"))
        assertTrue(f.backend.applyCalls.isEmpty())
        assertNull("an empty patch is never recorded", f.store.encoded)
        assertNull(model.savedCardRef.value)
    }

    @Test
    fun aSavedEditWritesOnceAndAdoptsTheBackendReadAsItsTruth() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        model.onFieldChange(0, "new front")
        model.save()
        advanceUntilIdle()

        val editor = f.ready(model)
        assertTrue("expected Saved, got ${editor.saveState}", editor.saveState is EditNoteSaveState.Saved)
        assertEquals(1, f.backend.applyCalls.size)
        assertEquals("new front", f.backend.fields[0].value)
        // INV-17-14: the screen's truth is the post-write read, not the draft.
        assertEquals("new front", editor.fields[0].originalValue)
        assertEquals("new front", editor.fields[0].draftValue)
        assertFalse(editor.dirty)
        assertFalse(editor.canSave)
        assertEquals(f.backend.cardRef(), model.savedCardRef.value)
    }

    @Test
    fun consumeSavedClearsTheNavigationSignalSoItCannotFireTwice() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        model.onFieldChange(0, "new front")
        model.save()
        advanceUntilIdle()
        assertNotNull(model.savedCardRef.value)

        model.consumeSaved()
        assertNull(model.savedCardRef.value)
    }

    @Test
    fun fieldsAndTagsAreWrittenTogetherAndADeckMoveIsASecondWrite() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        model.onFieldChange(0, "new front")
        model.addTags("beta")
        model.selectDeck("deck-b")
        model.save()
        advanceUntilIdle()

        // CONTRACT-04: one content write (fields + tags), then one card-scoped deck write.
        assertEquals(2, f.backend.applyCalls.size)
        assertEquals(0, f.backend.applyCalls[0].stepIndex)
        assertEquals(1, f.backend.applyCalls[1].stepIndex)
        assertEquals("new front", f.backend.fields[0].value)
        assertEquals(listOf("alpha", "beta"), f.backend.tags)
        assertEquals("deck-b", f.backend.deckId)
        assertTrue(f.ready(model).saveState is EditNoteSaveState.Saved)
    }

    @Test
    fun unfinishedStudyWorkRefusesTheSaveWithoutAnyWrite() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        f.block = NoteEditBlock("review_commit")
        model.onFieldChange(0, "new front")
        model.save()
        advanceUntilIdle()

        val editor = f.ready(model)
        assertTrue(editor.saveState is EditNoteSaveState.Blocked)
        assertTrue((editor.saveState as EditNoteSaveState.Blocked).message.contains("unfinished study work"))
        assertTrue(f.backend.applyCalls.isEmpty())
        assertNull(f.store.encoded)
    }

    @Test
    fun aFilteredDeckTargetIsRefusedBeforeAnyWrite() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        model.selectDeck("deck-filtered")
        assertTrue(f.ready(model).canSave)
        model.save()
        advanceUntilIdle()

        val editor = f.ready(model)
        assertTrue(editor.saveState is EditNoteSaveState.Blocked)
        val blocked = editor.saveState as EditNoteSaveState.Blocked
        assertTrue(blocked.message.contains("refused before anything was written"))
        assertTrue("the refusal must name the filtered deck: ${blocked.issues}",
            blocked.issues.any { it.contains("filtered deck") })
        assertTrue(f.backend.applyCalls.isEmpty())
    }

    @Test
    fun aDimensionTheBackendDoesNotOfferIsRefusedEvenIfTheUiAsksForIt() = runTest {
        // INV-17-17: the screen cannot bypass the contract. The deck control is not offered, and a
        // programmatic selection is still refused by the domain before any write.
        val f = Fixture(FakeNoteMutationBackend.defaultCapabilities().copy(changeCardDeck = false))
        val model = vm(f)
        advanceUntilIdle()

        val editor = f.ready(model)
        assertFalse(editor.capabilities.deck)
        assertFalse(editor.deck.editable)
        // A dimension that is not offered is not even listed, so there is nothing to select.
        assertTrue(editor.deck.options.isEmpty())

        model.selectDeck("deck-b")
        assertEquals("an unoffered deck is never selected", "deck-a", f.ready(model).deck.selectedDeckId)
        model.save()
        advanceUntilIdle()

        val after = f.ready(model)
        assertTrue(after.saveState is EditNoteSaveState.Blocked)
        assertTrue((after.saveState as EditNoteSaveState.Blocked).message.contains("Nothing to save"))
        assertTrue("no write for a dimension the backend does not offer", f.backend.applyCalls.isEmpty())
    }

    @Test
    fun theFieldSeparatorIsRefusedByTheControlAndByTheSave() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        model.onFieldChange(0, "a\u001Fb")
        val editor = f.ready(model)
        assertNotNull(editor.fields[0].issue)
        assertFalse(editor.canSave)

        // The screen would not offer the save, and the domain refuses it anyway.
        model.save()
        advanceUntilIdle()
        val after = f.ready(model)
        assertTrue(after.saveState is EditNoteSaveState.Blocked)
        assertTrue(
            "the refusal must name the separator: ${(after.saveState as EditNoteSaveState.Blocked).issues}",
            after.saveState.issues.any { it.contains("separator") }
        )
        assertTrue("a blocking field issue must stop the write", f.backend.applyCalls.isEmpty())
    }

    // ---- an unresolved mutation owns the note -----------------------------------------------------

    /**
     * Makes the next write ambiguous the honest way: the provider claims the write applied, but the
     * collection cannot be read afterwards, so nothing ties the intent to the stored state.
     */
    private fun makeWritesUnverifiable(f: Fixture) {
        f.backend.applyHook = { request, backend ->
            backend.applyFaithfully(request.step)
            backend.readFailure = AnkiError.BackendUnavailable()
            NoteMutationBackendResult.ConfirmedApplied
        }
    }

    @Test
    fun anUnresolvedMutationBlocksTheEditorAndIsNeverWrittenOver() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        makeWritesUnverifiable(f)
        model.onFieldChange(0, "first edit")
        model.save()
        advanceUntilIdle()

        val ambiguous = f.ready(model)
        assertTrue("expected Ambiguous, got ${ambiguous.saveState}", ambiguous.saveState is EditNoteSaveState.Ambiguous)
        assertNotNull(ambiguous.blockedByMutation)
        assertTrue(ambiguous.blockedByMutation!!.canAttest)
        assertFalse(ambiguous.blockedByMutation!!.canRetry)
        assertFalse(ambiguous.canSave)
        assertEquals(1, f.backend.applyCalls.size)

        // Re-opening the note once the collection is readable again shows the same block: the record
        // owns the note until a human closes it, even though the state now matches the intent.
        f.backend.applyHook = null
        f.backend.readFailure = null
        val reopened = vm(f)
        advanceUntilIdle()

        val blocked = f.ready(reopened)
        assertNotNull(blocked.blockedByMutation)
        assertEquals("m-1", blocked.blockedByMutation!!.mutationId)
        assertTrue(blocked.blockedByMutation!!.canAttest)
        assertFalse(blocked.canSave)
        // A matching state is offered as evidence for the human, never as proof of the transaction.
        val evidence = blocked.blockedByMutation!!.evidenceMessage
        assertNotNull("the read-only comparison is shown", evidence)
        assertTrue(evidence!!.contains("does not prove"))
        assertEquals(NoteMutationStatus.AMBIGUOUS.name, "AMBIGUOUS")

        reopened.onFieldChange(0, "second edit")
        reopened.save()
        advanceUntilIdle()
        assertEquals("a blocked note is never written over", 1, f.backend.applyCalls.size)
        // The read-only re-check cannot close it either: this backend answers "unresolved", so the
        // record stays AMBIGUOUS and still owns the note.
        val stillActive = f.coordinator.activeMutationFor(
            f.backend.noteRef().backendId,
            f.backend.noteRef().noteId
        )
        assertNotNull(stillActive)
        assertEquals(NoteMutationStatus.AMBIGUOUS, stillActive!!.status)
    }

    @Test
    fun anAttestationClosesTheAmbiguityReloadsTheNoteAndIsNotAWrite() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        makeWritesUnverifiable(f)
        model.onFieldChange(0, "first edit")
        model.save()
        advanceUntilIdle()
        assertEquals(1, f.backend.applyCalls.size)

        model.attest(NoteMutationAttestation.APPLIED_IN_COLLECTION)
        advanceUntilIdle()

        // The record is closed, so the screen re-reads the note. The collection is still unreadable in
        // this fixture, and the honest answer is an error state — never the retained draft.
        assertTrue("expected Error, got ${model.uiState.value}", model.uiState.value is EditNoteUiState.Error)
        assertEquals("an attestation is not a write", 1, f.backend.applyCalls.size)
        assertTrue("the pinned backend offers no reconciliation", f.backend.reconcileCalls.isEmpty())
        assertNull(f.coordinator.activeMutationFor(f.backend.noteRef().backendId, f.backend.noteRef().noteId))
    }

    @Test
    fun attestingAbsenceClosesTheRecordSoAFreshEditCanStart() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        makeWritesUnverifiable(f)
        model.onFieldChange(0, "first edit")
        model.save()
        advanceUntilIdle()

        // The collection becomes readable again, exactly as it is once the user has checked Anki.
        f.backend.readFailure = null
        f.backend.applyHook = null
        model.attest(NoteMutationAttestation.ABSENT_FROM_COLLECTION)
        advanceUntilIdle()

        val editor = f.ready(model)
        assertNull(editor.blockedByMutation)
        assertEquals(1, f.backend.applyCalls.size)

        model.onFieldChange(0, "second edit")
        model.save()
        advanceUntilIdle()
        assertEquals("the closed record no longer owns the note", 2, f.backend.applyCalls.size)
        assertTrue(f.ready(model).saveState is EditNoteSaveState.Saved)
    }

    @Test
    fun aRetryIsOfferedOnlyForAProvenNonApplication() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        // The provider refuses before writing: proven non-application, so the same id may be replayed.
        f.backend.applyHook = { _, _ ->
            NoteMutationBackendResult.ConfirmedNotApplied(
                AnkiError.InvalidRequest("provider_refused_before_write")
            )
        }
        model.onFieldChange(0, "new front")
        model.save()
        advanceUntilIdle()

        val refused = f.ready(model)
        assertTrue("expected RetryAvailable, got ${refused.saveState}", refused.saveState is EditNoteSaveState.RetryAvailable)
        assertNotNull(refused.blockedByMutation)
        assertTrue(refused.blockedByMutation!!.canRetry)
        assertFalse(refused.blockedByMutation!!.canAttest)
        assertEquals(1, f.backend.applyCalls.size)

        // The write now succeeds; the retry reuses the same mutation id.
        f.backend.applyHook = null
        model.retry()
        advanceUntilIdle()
        assertEquals(2, f.backend.applyCalls.size)
        assertEquals(
            "a retry replays the same transaction id",
            f.backend.applyCalls[0].mutationId,
            f.backend.applyCalls[1].mutationId
        )
        assertTrue(f.ready(model).saveState is EditNoteSaveState.Saved)
    }

    @Test
    fun aPreWriteConflictIsWordedAsNothingWrittenAndOffersAFreshStart() = runTest {
        val f = Fixture()
        val model = vm(f)
        advanceUntilIdle()

        // The note changes in Anki between the base read and the save.
        f.backend.beforeRead = { backend ->
            if (backend.readCount >= 1) backend.fields[0] = backend.fields[0].copy(value = "someone else edited")
        }
        model.onFieldChange(0, "new front")
        model.save()
        advanceUntilIdle()

        val editor = f.ready(model)
        assertTrue("expected Conflicted, got ${editor.saveState}", editor.saveState is EditNoteSaveState.Conflicted)
        assertTrue(editor.blockedByMutation!!.canRestartAfterConflict)
        assertTrue(f.backend.applyCalls.isEmpty())
        val message = (editor.saveState as EditNoteSaveState.Conflicted).message
        assertTrue(message.contains("Nothing was written"))
    }
}
