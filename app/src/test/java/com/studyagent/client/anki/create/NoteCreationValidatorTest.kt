package com.studyagent.client.anki.create

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiNoteModel
import com.studyagent.client.core.anki.AnkiNoteModelField
import com.studyagent.client.core.anki.AnkiNoteModelKind
import com.studyagent.client.core.anki.AnkiNoteModelRef
import com.studyagent.client.core.anki.create.AddNoteDraft
import com.studyagent.client.core.anki.create.CreationMediaKind
import com.studyagent.client.core.anki.create.MAX_MEDIA_PER_CREATION
import com.studyagent.client.core.anki.create.MAX_MEDIA_SIZE_BYTES
import com.studyagent.client.core.anki.create.NoteCreationValidator
import com.studyagent.client.core.anki.create.PendingMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 18 — pre-boundary validation (CONTRACT-18-13/14). Pure and total: every rule asserts on the
 * stable token the coordinator and UI share, and nothing here may throw for any input shape.
 */
class NoteCreationValidatorTest {

    private val backend = AnkiBackendId.AnkiDroidLocal

    private val caps = AnkiCapabilities(
        noteModelListing = true,
        createNotes = true,
        storeMedia = true
    )

    private fun model(fields: List<String> = listOf("Front", "Back")): AnkiNoteModel = AnkiNoteModel(
        ref = AnkiNoteModelRef(backend, "model-basic"),
        name = "Basic",
        kind = AnkiNoteModelKind.NORMAL,
        fields = fields.mapIndexed { index, name -> AnkiNoteModelField(ordinal = index, name = name) },
        templateCount = 1,
        defaultDeckId = "1"
    )

    private fun media(
        sizeBytes: Long = 1024,
        extension: String = "png",
        mime: String = "image/png",
        kind: CreationMediaKind = CreationMediaKind.IMAGE,
        target: Int = 0
    ) = PendingMedia(
        contentUri = "content://picker/1",
        sourceName = "photo.$extension",
        mimeType = mime,
        extension = extension,
        sizeBytes = sizeBytes,
        kind = kind,
        targetFieldOrdinal = target
    )

    private fun draft(
        values: Map<Int, String> = mapOf(0 to "front", 1 to "back"),
        tags: List<String> = emptyList(),
        media: List<PendingMedia> = emptyList(),
        model: AnkiNoteModel = model()
    ) = AddNoteDraft(
        backendId = backend,
        collectionKey = null,
        model = model,
        fieldValues = values,
        tags = tags,
        media = media
    )

    @Test
    fun `a complete valid draft reports nothing`() {
        assertEquals(emptyList<String>(), NoteCreationValidator.validate(draft(), caps))
    }

    @Test
    fun `capabilities gate creation and media`() {
        val noCreate = caps.copy(createNotes = false)
        assertTrue(NoteCreationValidator.validate(draft(), noCreate).contains("capability_create_notes_missing"))
        val noListing = caps.copy(noteModelListing = false)
        assertTrue(NoteCreationValidator.validate(draft(), noListing).contains("capability_model_listing_missing"))
        val noMedia = caps.copy(storeMedia = false)
        assertTrue(
            NoteCreationValidator.validate(draft(media = listOf(media())), noMedia)
                .contains("capability_store_media_missing")
        )
        // A draft without media does not need the media capability.
        assertEquals(emptyList<String>(), NoteCreationValidator.validate(draft(), noMedia))
    }

    @Test
    fun `field rules mirror the pinned provider's representability`() {
        // Empty first field is Anki's blocking "Empty" state.
        assertTrue(
            NoteCreationValidator.validate(draft(values = mapOf(0 to "", 1 to "back")), caps)
                .contains("first_field_empty")
        )
        // Blank-only first field is equally empty to Anki.
        assertTrue(
            NoteCreationValidator.validate(draft(values = mapOf(0 to "   ", 1 to "back")), caps)
                .contains("first_field_empty")
        )
        // Trailing empty fields are dropped by splitFields at the pin and then refused.
        assertTrue(
            NoteCreationValidator.validate(draft(values = mapOf(0 to "front", 1 to "")), caps)
                .contains("last_field_not_representable")
        )
        // The field separator would silently merge two fields.
        assertTrue(
            NoteCreationValidator.validate(draft(values = mapOf(0 to "a\u001Fb", 1 to "back")), caps)
                .contains("field_contains_separator")
        )
        // Missing ordinals refuse instead of guessing.
        assertTrue(
            NoteCreationValidator.validate(draft(values = mapOf(0 to "front")), caps)
                .contains("field_ordinals_incomplete")
        )
    }

    @Test
    fun `tag rules match the pinned backend rewrite behaviour`() {
        assertTrue(NoteCreationValidator.validate(draft(tags = listOf("")), caps).contains("tag_blank"))
        assertTrue(
            NoteCreationValidator.validate(draft(tags = listOf("two words")), caps)
                .contains("tag_invalid_character")
        )
        assertTrue(
            NoteCreationValidator.validate(draft(tags = listOf("a::")), caps)
                .contains("tag_hierarchy_component_blank")
        )
        assertEquals(emptyList<String>(), NoteCreationValidator.validate(draft(tags = listOf("a::b", "c")), caps))
    }

    @Test
    fun `media limits protect the provider process`() {
        assertTrue(
            NoteCreationValidator.validate(draft(media = listOf(media(sizeBytes = MAX_MEDIA_SIZE_BYTES + 1))), caps)
                .contains("media_size_exceeded")
        )
        assertEquals(
            emptyList<String>(),
            NoteCreationValidator.validate(draft(media = listOf(media(sizeBytes = MAX_MEDIA_SIZE_BYTES))), caps)
        )
        // The draft itself refuses more than the product limit at construction (defense in depth);
        // the validator keeps the rule for any draft that could exist.
        try {
            draft(media = List(MAX_MEDIA_PER_CREATION + 1) { media() })
            throw AssertionError("AddNoteDraft must refuse more than $MAX_MEDIA_PER_CREATION media")
        } catch (expected: IllegalArgumentException) {
            // The locked product limit.
        }
        // A blank extension or unknown MIME can never become a PendingMedia (the probe refuses
        // pre-boundary, R4 of docs/GATE_18). A media attachment targeting a missing field is
        // refused already at draft construction (the validator keeps the rule as defense in depth):
        try {
            draft(media = listOf(media(target = 5)))
            throw AssertionError("AddNoteDraft must refuse a media target outside the schema")
        } catch (expected: IllegalArgumentException) {
            // The locked structural rule.
        }
    }

    @Test
    fun `media kind must agree with its mime family`() {
        assertTrue(
            NoteCreationValidator.validate(
                draft(media = listOf(media(mime = "audio/mpeg", kind = CreationMediaKind.IMAGE))), caps
            ).contains("media_kind_mime_mismatch")
        )
    }

    @Test
    fun `schema drift detection compares ids and fields exactly`() {
        val original = model()
        assertFalse(NoteCreationValidator.schemaDrift(original, original))
        assertFalse(NoteCreationValidator.schemaDrift(original, model()))
        // Renamed field = drift.
        val renamed = model(fields = listOf("Front", "Rear"))
        assertTrue(NoteCreationValidator.schemaDrift(original, renamed))
        // Added field = drift.
        val added = model(fields = listOf("Front", "Back", "Extra"))
        assertTrue(NoteCreationValidator.schemaDrift(original, added))
        // A different model id is drift even with identical fields.
        val otherId = original.copy(ref = AnkiNoteModelRef(backend, "model-other"))
        assertTrue(NoteCreationValidator.schemaDrift(original, otherId))
    }
}
