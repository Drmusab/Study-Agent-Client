package com.studyagent.client.ui

import androidx.lifecycle.ViewModelStore
import com.studyagent.client.anki.create.FakeNoteCreationBackend
import com.studyagent.client.anki.create.InMemoryNoteCreationStore
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.create.DefaultNoteCreationCoordinator
import com.studyagent.client.core.anki.create.DefaultNoteCreationLedger
import com.studyagent.client.core.anki.create.MediaSourceProbe
import com.studyagent.client.core.anki.create.MediaSourceProbeResult
import com.studyagent.client.core.anki.create.NoteCreationId
import com.studyagent.client.core.anki.create.NoteCreationIdSource
import com.studyagent.client.core.anki.create.NoteCreationStatus
import com.studyagent.client.ui.screens.addnote.AddNoteSaveState
import com.studyagent.client.ui.screens.addnote.AddNoteUiState
import com.studyagent.client.ui.screens.addnote.AddNoteViewModel
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 18 — the Add Note screen's state owner, driven against the real coordinator, the real ledger
 * and a scriptable backend + probe.
 *
 * Pinned behaviours: the screen never writes on its own; a model change invalidates the draft;
 * media stays local until the coordinator stores it; the created summary comes from the backend's
 * hydration read; ambiguous outcomes surface attestation, never a retry; and capability honesty
 * gates every affordance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddNoteViewModelTest {

    private val viewModelStores = mutableListOf<ViewModelStore>()

    @After
    fun tearDown() {
        viewModelStores.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    private class ScriptedProbe : MediaSourceProbe {
        var next: MediaSourceProbeResult = MediaSourceProbeResult.Ready(
            mimeType = "image/png", extension = "png", sizeBytes = 10, displayName = "photo.png"
        )
        val calls = mutableListOf<String>()
        override suspend fun probe(contentUri: String): MediaSourceProbeResult {
            calls += contentUri
            return next
        }
    }

    private class Fixture(capabilities: AnkiCapabilities = FakeNoteCreationBackend.defaultCapabilities()) {
        val events = mutableListOf<String>()
        val backend = FakeNoteCreationBackend(capabilities = capabilities, events = events)
        val store = InMemoryNoteCreationStore(events)
        private var counter = 0
        val coordinator = DefaultNoteCreationCoordinator(
            backend,
            DefaultNoteCreationLedger(store, nowEpochMs = { 1_000L }),
            NoteCreationIdSource { NoteCreationId("c-${++counter}") },
            nowEpochMs = { 1_000L }
        )
        val probe = ScriptedProbe()
        val modelId get() = backend.models.first().ref.modelId

        fun ready(model: AddNoteViewModel) = (model.uiState.value as AddNoteUiState.Ready).editor
    }

    private fun TestScope.vm(fixture: Fixture): AddNoteViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        return AddNoteViewModel(
            backend = fixture.backend,
            coordinator = fixture.coordinator,
            mediaProbe = fixture.probe
        ).also { model ->
            ViewModelStore().also { store ->
                store.put("add-note", model)
                viewModelStores += store
            }
            advanceUntilIdle()
        }
    }

    @Test
    fun `a ready backend shows the editor with a note type to choose`() = runTest {
        val fixture = Fixture()
        val model = vm(fixture)
        val ready = model.uiState.value as AddNoteUiState.Ready
        assertEquals(1, ready.editor.models.size)
        assertFalse(ready.editor.canSave) // nothing selected yet
    }

    @Test
    fun `missing creation capability refuses the whole screen`() = runTest {
        val caps = AnkiCapabilities(deckListing = true, createNotes = false, noteModelListing = false)
        val fixture = Fixture(capabilities = caps)
        val model = vm(fixture)
        assertTrue(model.uiState.value is AddNoteUiState.Refused)
    }

    @Test
    fun `selecting a model enables save once fields are valid`() = runTest {
        val fixture = Fixture()
        val model = vm(fixture)
        model.selectModel(fixture.modelId)
        advanceUntilIdle()
        assertFalse(fixture.ready(model).canSave) // first field empty
        model.onFieldChanged(0, "front text")
        model.onFieldChanged(1, "back text")
        advanceUntilIdle()
        assertTrue(fixture.ready(model).canSave)
    }

    @Test
    fun `an empty last field keeps save disabled per the pinned provider rule`() = runTest {
        val fixture = Fixture()
        val model = vm(fixture)
        model.selectModel(fixture.modelId)
        model.onFieldChanged(0, "front text")
        model.onFieldChanged(1, "")
        advanceUntilIdle()
        assertFalse(fixture.ready(model).canSave)
    }

    @Test
    fun `saving a plain note publishes the backend created summary`() = runTest {
        val fixture = Fixture()
        val model = vm(fixture)
        model.selectModel(fixture.modelId)
        model.onFieldChanged(0, "front text")
        model.onFieldChanged(1, "back text")
        model.onTagsChanged(listOf("alpha"))
        advanceUntilIdle()

        model.save()
        advanceUntilIdle()

        val created = fixture.ready(model).saveState as AddNoteSaveState.Created
        assertEquals(listOf("front text", "back text"), createdSummaryFields(fixture, model))
        assertEquals(listOf("alpha"), created.summary.tags)
        assertEquals(1, created.summary.cards.size)
        // The draft was cleared for the next note.
        assertTrue(fixture.ready(model).fields.all { it.value.isEmpty() })
    }

    private fun createdSummaryFields(fixture: Fixture, model: AddNoteViewModel): List<String> {
        val created = fixture.ready(model).saveState as AddNoteSaveState.Created
        // The backend stores the fields; recover them from the fake's stored note.
        return fixture.backend.storedNotes.values.single().fields
    }

    @Test
    fun `attaching media keeps it local until save and stores it once`() = runTest {
        val fixture = Fixture()
        val model = vm(fixture)
        model.selectModel(fixture.modelId)
        model.onFieldChanged(0, "front text")
        model.onFieldChanged(1, "back text")

        model.attachMedia("content://picked/1", targetFieldOrdinal = 1)
        advanceUntilIdle()
        assertEquals(1, fixture.probe.calls.size)
        assertEquals(1, fixture.ready(model).media.size)
        assertTrue(fixture.ready(model).media.single().pending)
        // Nothing reached the backend yet.
        assertEquals(0, fixture.backend.mediaStoreCalls.size)

        model.save()
        advanceUntilIdle()
        assertEquals(1, fixture.backend.mediaStoreCalls.size)
        // The created field references the stored name via img syntax.
        val stored = fixture.backend.storedNotes.values.single()
        assertTrue(stored.fields[1].contains("<img src=\"photo.png\">"))
    }

    @Test
    fun `an unsupported media type surfaces a refusal and is not attached`() = runTest {
        val fixture = Fixture()
        val model = vm(fixture)
        model.selectModel(fixture.modelId)
        model.onFieldChanged(0, "a")
        model.onFieldChanged(1, "b")
        fixture.probe.next = MediaSourceProbeResult.Refused("mime_unsupported")

        model.attachMedia("content://picked/1", targetFieldOrdinal = 0)
        advanceUntilIdle()
        assertTrue(fixture.ready(model).media.isEmpty())
        assertFalse(fixture.ready(model).canSave) // the issue token blocks save
    }

    @Test
    fun `switching the note type discards field values and media`() = runTest {
        val fixture = Fixture()
        // The second model must exist before the screen loads its listing.
        fixture.backend.models += FakeNoteCreationBackend.clozeModel(fixture.backend.id)
        val model = vm(fixture)
        model.selectModel(fixture.modelId)
        model.onFieldChanged(0, "typed value")
        model.attachMedia("content://picked/1", targetFieldOrdinal = 0)
        advanceUntilIdle()
        assertEquals(1, fixture.ready(model).media.size)

        model.selectModel("model-cloze")
        advanceUntilIdle()
        val editor = fixture.ready(model)
        assertEquals("model-cloze", editor.selectedModelId)
        assertTrue(editor.fields.all { it.value.isEmpty() })
        assertTrue(editor.media.isEmpty())
    }

    @Test
    fun `a duplicate save while one runs is refused, never double dispatched`() = runTest {
        val fixture = Fixture()
        val model = vm(fixture)
        model.selectModel(fixture.modelId)
        model.onFieldChanged(0, "a")
        model.onFieldChanged(1, "b")
        advanceUntilIdle()

        model.save()
        model.save()
        advanceUntilIdle()
        assertEquals(1, fixture.backend.createNoteCalls.size)
    }

    @Test
    fun `an ambiguous outcome offers attestation and refuses retry`() = runTest {
        val fixture = Fixture()
        fixture.backend.failCreateWithUnknown = true
        val model = vm(fixture)
        model.selectModel(fixture.modelId)
        model.onFieldChanged(0, "a")
        model.onFieldChanged(1, "b")
        advanceUntilIdle()

        model.save()
        advanceUntilIdle()
        assertTrue(fixture.ready(model).saveState is AddNoteSaveState.Ambiguous)

        // A retry on an ambiguous attempt is refused by the coordinator.
        model.retry()
        advanceUntilIdle()
        assertEquals(1, fixture.backend.createNoteCalls.size)

        // Attesting absent closes it; a fresh note may then be created.
        val ambiguousId = fixture.store.encoded!!.let {
            com.studyagent.client.core.anki.create.NoteCreationCodec.decode(it)!!
                .single { record -> record.status == NoteCreationStatus.AMBIGUOUS }.creationId
        }
        model.attest(ambiguousId, foundInCollection = false)
        advanceUntilIdle()
        val decoded = com.studyagent.client.core.anki.create.NoteCreationCodec.decode(fixture.store.encoded!!)!!
        assertEquals(NoteCreationStatus.ABANDONED, decoded.single { it.creationId == ambiguousId }.status)
    }

    @Test
    fun `a retryable failure re-runs under the same creation id`() = runTest {
        val fixture = Fixture()
        fixture.backend.createNoteResults += com.studyagent.client.core.anki.create.CreateNoteBackendResult
            .ConfirmedNotCreated(com.studyagent.client.core.anki.AnkiError.InvalidRequest("x"))
        val model = vm(fixture)
        model.selectModel(fixture.modelId)
        model.onFieldChanged(0, "a")
        model.onFieldChanged(1, "b")
        advanceUntilIdle()

        model.save()
        advanceUntilIdle()
        assertTrue(fixture.ready(model).saveState is AddNoteSaveState.RetryAvailable)

        model.retry()
        advanceUntilIdle()
        assertTrue(fixture.ready(model).saveState is AddNoteSaveState.Created)
        assertEquals(2, fixture.backend.createNoteCalls.size)
        assertEquals(1, fixture.backend.storedNotes.size)
    }

    @Test
    fun `removing media before save keeps it out of the backend`() = runTest {
        val fixture = Fixture()
        val model = vm(fixture)
        model.selectModel(fixture.modelId)
        model.onFieldChanged(0, "a")
        model.onFieldChanged(1, "b")
        model.attachMedia("content://picked/1", targetFieldOrdinal = 0)
        advanceUntilIdle()
        model.removeMedia(0)
        advanceUntilIdle()
        model.save()
        advanceUntilIdle()
        assertEquals(0, fixture.backend.mediaStoreCalls.size)
    }
}
