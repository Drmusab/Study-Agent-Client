package com.studyagent.client.ui

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.ui.screens.addnote.AddNoteMapper
import com.studyagent.client.ui.screens.addnote.AddNoteSaveState
import com.studyagent.client.ui.screens.addnote.AddNoteUiState
import com.studyagent.client.anki.create.FakeNoteCreationBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 18 — the Add Note projection. Every gating rule here is capability-derived (AUDIT-18-14):
 * the backend's name never decides anything, claims are never exaggerated, and the screen's one
 * save flag is computed here, not in Compose.
 */
class AddNoteMapperTest {

    private fun source(
        availability: AnkiAvailability = AnkiAvailability.Ready(FakeNoteCreationBackend.defaultCapabilities()),
        capabilities: AnkiCapabilities? = FakeNoteCreationBackend.defaultCapabilities(),
        modelsLoading: Boolean = false,
        modelsError: AnkiError? = null,
        models: List<com.studyagent.client.core.anki.AnkiNoteModel> =
            listOf(FakeNoteCreationBackend.basicModel()),
        selected: com.studyagent.client.core.anki.AnkiNoteModel? = null,
        fieldValues: Map<Int, String> = emptyMap(),
        tags: List<String> = emptyList(),
        validationTokens: List<String> = emptyList(),
        saveState: AddNoteSaveState = AddNoteSaveState.Idle,
        saving: Boolean = false
    ) = AddNoteMapper.Source(
        availability = availability,
        capabilities = capabilities,
        modelsLoading = modelsLoading,
        modelsError = modelsError,
        models = models,
        selected = selected,
        fieldValues = fieldValues,
        tags = tags,
        pendingMedia = emptyList(),
        deckNames = mapOf("7" to "Spanish", "1" to "Default"),
        deckListingFailed = false,
        validationTokens = validationTokens,
        saveState = saveState,
        saving = saving,
        recoveryRecords = emptyList()
    )

    @Test
    fun `an unavailable backend is reported as unavailable, never degraded`() {
        val state = AddNoteMapper.project(
            source(availability = AnkiAvailability.NotInstalled)
        )
        assertTrue(state is AddNoteUiState.Unavailable)
    }

    @Test
    fun `missing creation capability refuses with a reason`() {
        val caps = AnkiCapabilities(deckListing = true, createNotes = false, noteModelListing = false)
        val state = AddNoteMapper.project(source(capabilities = caps))
        val refused = state as AddNoteUiState.Refused
        assertTrue(refused.message.contains("cannot create notes"))
    }

    @Test
    fun `a models listing failure is an error, not an empty editor`() {
        val state = AddNoteMapper.project(
            source(modelsError = AnkiError.QueryFailure("x"))
        )
        assertTrue(state is AddNoteUiState.Error)
    }

    @Test
    fun `loading with nothing yet is the loading state`() {
        val state = AddNoteMapper.project(source(modelsLoading = true, models = emptyList()))
        assertTrue(state is AddNoteUiState.Loading)
    }

    @Test
    fun `the ready editor never claims duplicate protection or a deck selector`() {
        val model = FakeNoteCreationBackend.basicModel()
        val editor = (AddNoteMapper.project(
            source(
                selected = model,
                fieldValues = mapOf(0 to "a", 1 to "b")
            )
        ) as AddNoteUiState.Ready).editor
        // The pinned backend enforces no duplicate check; the UI must not claim it.
        assertFalse(editor.duplicateProtection)
        // Deck is a notice, not a selector: the stored default deck, resolved for display.
        val notice = editor.deckNotice!!
        assertEquals("7", notice.defaultDeckId)
        assertEquals("Spanish", notice.defaultDeckLabel)
        assertTrue(notice.deckConfirmed)
    }

    @Test
    fun `media attachment is offered only when the backend claims storeMedia`() {
        val model = FakeNoteCreationBackend.basicModel()
        val withMedia = (AddNoteMapper.project(
            source(selected = model, fieldValues = mapOf(0 to "a", 1 to "b"))
        ) as AddNoteUiState.Ready).editor
        assertTrue(withMedia.mediaAttachmentOffered)

        val noMediaCaps = FakeNoteCreationBackend.defaultCapabilities().copy(storeMedia = false)
        val withoutMedia = (AddNoteMapper.project(
            source(
                capabilities = noMediaCaps,
                availability = AnkiAvailability.Ready(noMediaCaps),
                selected = model,
                fieldValues = mapOf(0 to "a", 1 to "b")
            )
        ) as AddNoteUiState.Ready).editor
        assertFalse(withoutMedia.mediaAttachmentOffered)
    }

    @Test
    fun `canSave requires a selected model and no open issues`() {
        val model = FakeNoteCreationBackend.basicModel()
        // No model selected.
        val noModel = (AddNoteMapper.project(source()) as AddNoteUiState.Ready).editor
        assertFalse(noModel.canSave)
        // Complete and valid.
        val valid = (AddNoteMapper.project(
            source(selected = model, fieldValues = mapOf(0 to "a", 1 to "b"))
        ) as AddNoteUiState.Ready).editor
        assertTrue(valid.canSave)
        // One validation token blocks.
        val blocked = (AddNoteMapper.project(
            source(
                selected = model,
                fieldValues = mapOf(0 to "a", 1 to "b"),
                validationTokens = listOf("first_field_empty")
            )
        ) as AddNoteUiState.Ready).editor
        assertFalse(blocked.canSave)
        // Saving blocks.
        val saving = (AddNoteMapper.project(
            source(selected = model, fieldValues = mapOf(0 to "a", 1 to "b"), saving = true)
        ) as AddNoteUiState.Ready).editor
        assertFalse(saving.canSave)
    }

    @Test
    fun `cloze models are labelled and keep normal field submission`() {
        val cloze = FakeNoteCreationBackend.clozeModel()
        val editor = (AddNoteMapper.project(
            source(models = listOf(cloze), selected = cloze, fieldValues = mapOf(0 to "", 1 to "", 2 to "x"))
        ) as AddNoteUiState.Ready).editor
        assertTrue(editor.modelIsCloze)
        assertTrue(editor.models.single().isCloze)
        assertEquals(3, editor.fields.size)
    }

    @Test
    fun `refusal and validation wording is stable and content free`() {
        assertEquals(
            "The selected backend changed. Close Add Note and open it again.",
            AddNoteMapper.refusalMessage("BackendMismatch")
        )
        assertEquals(
            "The first field cannot be empty.",
            AddNoteMapper.validationMessage("first_field_empty")
        )
        assertEquals(
            "This file type is not supported for notes. Attach a common image or audio file.",
            AddNoteMapper.validationMessage("mime_unsupported")
        )
        // Unknown tokens degrade to honest generic wording, never provider text.
        assertEquals("Check the note before saving.", AddNoteMapper.validationMessage("never_invented"))
    }
}
