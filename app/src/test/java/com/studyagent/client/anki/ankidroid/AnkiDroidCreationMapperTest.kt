package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiNoteModelKind
import com.studyagent.client.core.anki.AnkiNoteModelRef
import com.studyagent.client.core.anki.create.CreateNoteBackendRequest
import com.studyagent.client.core.anki.create.CreateNoteBackendResult
import com.studyagent.client.core.anki.create.MediaStoreBackendResult
import com.studyagent.client.core.anki.create.StoreMediaBackendRequest
import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiContract
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCreationDispatch
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCreationMapper
import com.studyagent.client.data.anki.ankidroid.InMemoryProviderRow
import com.studyagent.client.data.anki.ankidroid.ProviderValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 18 — the creation mapper. Pure translation + boundary classification against the pinned
 * v2.24.1 contract (docs/GATE_18_BACKEND_CREATION_CONTRACT.md §2/§4/§9/§12). The classification
 * matrix is the safety core: only a parseable note id proves creation, only proven pre-write
 * refusals prove non-creation, everything else stays UNKNOWN and is never retried blindly.
 */
class AnkiDroidCreationMapperTest {

    private val backendId = AnkiBackendId.AnkiDroidLocal

    private fun noteRequest(
        modelId: String = "1607392319",
        fields: List<String> = listOf("front", "back"),
        tags: List<String> = emptyList()
    ) = CreateNoteBackendRequest(
        backendId = backendId,
        model = AnkiNoteModelRef(backendId, modelId),
        orderedFields = fields,
        tags = tags
    )

    private fun mediaRequest(
        preferredName: String = "diagram.png",
        uri: String = "content://media/external/images/17"
    ) = StoreMediaBackendRequest(
        backendId = backendId,
        contentUri = uri,
        preferredName = preferredName,
        mimeType = "image/png",
        sizeBytes = 100
    )

    // ---------------------------------------------------------------- note write mapping

    @Test
    fun `note create maps to the pinned insert columns`() {
        val mapping = AnkiDroidCreationMapper.mapNoteCreate(
            noteRequest(fields = listOf("a", "b", "c"), tags = listOf("one", "two"))
        ) as AnkiDroidCreationMapper.NoteCreateMapping.Ready
        assertEquals(AnkiDroidApiContract.NOTES_INSERT_PATH, mapping.path)
        val byName = mapping.values.associate { it.column to it }
        assertEquals(
            1607392319L,
            (byName.getValue(AnkiDroidApiContract.NOTE_INSERT_MID_COLUMN) as ProviderValue.LongValue).value
        )
        assertEquals(
            "a${AnkiDroidApiContract.FIELD_SEPARATOR}b${AnkiDroidApiContract.FIELD_SEPARATOR}c",
            (byName.getValue(AnkiDroidApiContract.NOTE_FIELDS_COLUMN) as ProviderValue.StringValue).value
        )
        assertEquals(
            "one${AnkiDroidApiContract.TAGS_SEPARATOR}two",
            (byName.getValue(AnkiDroidApiContract.NOTE_TAGS_COLUMN) as ProviderValue.StringValue).value
        )
        // No deck column exists in the pinned insert: the mapper must not invent one.
        assertTrue(byName.keys.none { it.contains("deck", ignoreCase = true) })
    }

    @Test
    fun `note create omits the tags column when there are no tags`() {
        val mapping = AnkiDroidCreationMapper.mapNoteCreate(noteRequest()) as
            AnkiDroidCreationMapper.NoteCreateMapping.Ready
        assertTrue(mapping.values.none { it.column == AnkiDroidApiContract.NOTE_TAGS_COLUMN })
    }

    @Test
    fun `note create refuses non-numeric model ids before dispatch`() {
        assertTrue(
            AnkiDroidCreationMapper.mapNoteCreate(noteRequest(modelId = "not-a-number"))
                is AnkiDroidCreationMapper.NoteCreateMapping.Refused
        )
    }

    @Test
    fun `the request itself rejects separator content, defense in depth for the mapper rule`() {
        // The mapper keeps a separator refusal for any request that could exist; the request type
        // already rejects it at construction, so both layers agree.
        try {
            noteRequest(fields = listOf("a\u001Fb", "c"))
            throw AssertionError("CreateNoteBackendRequest must reject the field separator")
        } catch (expected: IllegalArgumentException) {
            // The locked structural rule.
        }
    }

    // ---------------------------------------------------------------- media write mapping

    @Test
    fun `media store maps to the pinned insert columns`() {
        val mapping = AnkiDroidCreationMapper.mapMediaStore(mediaRequest()) as
            AnkiDroidCreationMapper.MediaStoreMapping.Ready
        assertEquals(AnkiDroidApiContract.MEDIA_PATH, mapping.path)
        val byName = mapping.values.associate { it.column to it }
        assertEquals(
            "content://media/external/images/17",
            (byName.getValue(AnkiDroidApiContract.MEDIA_FILE_URI_COLUMN) as ProviderValue.StringValue).value
        )
        assertEquals(
            "diagram.png",
            (byName.getValue(AnkiDroidApiContract.MEDIA_PREFERRED_NAME_COLUMN) as ProviderValue.StringValue).value
        )
    }

    @Test
    fun `media store refuses path separators in the preferred name`() {
        assertTrue(
            AnkiDroidCreationMapper.mapMediaStore(mediaRequest(preferredName = "../evil.png"))
                is AnkiDroidCreationMapper.MediaStoreMapping.Refused
        )
    }

    // ---------------------------------------------------------------- note boundary classification

    @Test
    fun `note classification proves creation only from a parseable returned id`() {
        val created = AnkiDroidCreationMapper.classifyNoteInsert(
            AnkiDroidCreationDispatch.Returned("content://com.ichi2.anki.provider/notes/16873")
        )
        assertEquals("16873", (created as CreateNoteBackendResult.ConfirmedCreated).noteId)
    }

    @Test
    fun `note classification treats null and unparseable uris as unknown, never not-created`() {
        assertTrue(
            AnkiDroidCreationMapper.classifyNoteInsert(AnkiDroidCreationDispatch.Returned(null))
                is CreateNoteBackendResult.OutcomeUnknown
        )
        assertTrue(
            AnkiDroidCreationMapper.classifyNoteInsert(AnkiDroidCreationDispatch.Returned("content://notes/notanumber"))
                is CreateNoteBackendResult.OutcomeUnknown
        )
    }

    @Test
    fun `note classification proves non-creation only for pre-write refusals`() {
        assertTrue(
            AnkiDroidCreationMapper.classifyNoteInsert(
                AnkiDroidCreationDispatch.Threw("SecurityException")
            ) is CreateNoteBackendResult.ConfirmedNotCreated
        )
        for (cls in listOf("IllegalArgumentException", "NumberFormatException", "NullPointerException")) {
            assertTrue(
                "$cls precedes the irreversible write at the pin",
                AnkiDroidCreationMapper.classifyNoteInsert(AnkiDroidCreationDispatch.Threw(cls))
                    is CreateNoteBackendResult.ConfirmedNotCreated
            )
        }
        // Backend Rust errors arrive as RuntimeException subclasses: origin unprovable -> UNKNOWN.
        assertTrue(
            AnkiDroidCreationMapper.classifyNoteInsert(AnkiDroidCreationDispatch.Threw("BackendNotFoundException"))
                is CreateNoteBackendResult.OutcomeUnknown
        )
        assertTrue(
            AnkiDroidCreationMapper.classifyNoteInsert(AnkiDroidCreationDispatch.Threw("RuntimeException"))
                is CreateNoteBackendResult.OutcomeUnknown
        )
        assertTrue(
            AnkiDroidCreationMapper.classifyNoteInsert(AnkiDroidCreationDispatch.Unknown("insert_timeout"))
                is CreateNoteBackendResult.OutcomeUnknown
        )
        assertTrue(
            AnkiDroidCreationMapper.classifyNoteInsert(
                AnkiDroidCreationDispatch.NotDispatched(AnkiError.QueryFailure("provider_write_busy"))
            ) is CreateNoteBackendResult.ConfirmedNotCreated
        )
    }

    // ---------------------------------------------------------------- media boundary classification

    @Test
    fun `media classification reads the backend chosen name from the returned uri`() {
        val stored = AnkiDroidCreationMapper.classifyMediaInsert(
            AnkiDroidCreationDispatch.Returned("file:///storage/emulated/0/Android/media/diagram_9f3.png")
        )
        assertEquals("diagram_9f3.png", (stored as MediaStoreBackendResult.Stored).mediaName)
    }

    @Test
    fun `media classification treats null and malformed uris as unknown`() {
        assertTrue(
            AnkiDroidCreationMapper.classifyMediaInsert(AnkiDroidCreationDispatch.Returned(null))
                is MediaStoreBackendResult.OutcomeUnknown
        )
        assertTrue(
            AnkiDroidCreationMapper.classifyMediaInsert(AnkiDroidCreationDispatch.Returned("file:///"))
                is MediaStoreBackendResult.OutcomeUnknown
        )
    }

    @Test
    fun `media classification proves non-storage only for pre-write refusals`() {
        assertTrue(
            AnkiDroidCreationMapper.classifyMediaInsert(AnkiDroidCreationDispatch.Threw("SecurityException"))
                is MediaStoreBackendResult.ConfirmedNotStored
        )
        assertTrue(
            AnkiDroidCreationMapper.classifyMediaInsert(AnkiDroidCreationDispatch.Threw("IllegalArgumentException"))
                is MediaStoreBackendResult.ConfirmedNotStored
        )
        assertTrue(
            AnkiDroidCreationMapper.classifyMediaInsert(AnkiDroidCreationDispatch.Threw("EmptyMediaException"))
                is MediaStoreBackendResult.OutcomeUnknown
        )
        assertTrue(
            AnkiDroidCreationMapper.classifyMediaInsert(AnkiDroidCreationDispatch.Unknown("insert_timeout"))
                is MediaStoreBackendResult.OutcomeUnknown
        )
    }

    // ---------------------------------------------------------------- model row mapping

    private fun modelRow(
        id: Any? = 1607392319L,
        name: Any? = "Basic",
        fields: Any? = "Front${AnkiDroidApiContract.FIELD_SEPARATOR}Back",
        type: Any? = AnkiDroidApiContract.MODEL_TYPE_NORMAL,
        numCards: Any? = 1,
        deckId: Any? = 1L
    ) = InMemoryProviderRow(
        linkedMapOf(
            AnkiDroidApiContract.MODEL_ID_COLUMN to id,
            AnkiDroidApiContract.MODEL_NAME_COLUMN to name,
            AnkiDroidApiContract.MODEL_FIELD_NAMES_COLUMN to fields,
            AnkiDroidApiContract.MODEL_TYPE_COLUMN to type,
            AnkiDroidApiContract.MODEL_NUM_CARDS_COLUMN to numCards,
            AnkiDroidApiContract.MODEL_DECK_ID_COLUMN to deckId
        )
    )

    @Test
    fun `a full model row maps to a creation schema with ordinal identity`() {
        val outcome = AnkiDroidCreationMapper.mapModelRow(backendId, modelRow())
            as AnkiDroidCreationMapper.ModelRowOutcome.Model
        assertEquals("1607392319", outcome.model.ref.modelId)
        assertEquals("Basic", outcome.model.name)
        assertEquals(listOf("Front", "Back"), outcome.model.fields.map { it.name })
        assertEquals(listOf(0, 1), outcome.model.fields.map { it.ordinal })
        assertEquals(AnkiNoteModelKind.NORMAL, outcome.model.kind)
        assertEquals(1, outcome.model.templateCount)
        assertEquals("1", outcome.model.defaultDeckId)
    }

    @Test
    fun `cloze models are recognised and optional columns degrade`() {
        val outcome = AnkiDroidCreationMapper.mapModelRow(
            backendId, modelRow(type = AnkiDroidApiContract.MODEL_TYPE_CLOZE, numCards = null, deckId = null)
        ) as AnkiDroidCreationMapper.ModelRowOutcome.Model
        assertEquals(AnkiNoteModelKind.CLOZE, outcome.model.kind)
        assertEquals(null, outcome.model.templateCount)
        assertEquals(null, outcome.model.defaultDeckId)
    }

    @Test
    fun `malformed rows report stable tokens instead of partial models`() {
        fun token(row: InMemoryProviderRow): String =
            (AnkiDroidCreationMapper.mapModelRow(backendId, row)
                as AnkiDroidCreationMapper.ModelRowOutcome.Malformed).token

        assertEquals("model_id_unreadable", token(modelRow(id = null)))
        assertEquals("model_id_unreadable", token(modelRow(id = 0L)))
        assertEquals("model_name_unreadable", token(modelRow(name = " ")))
        assertEquals("model_fields_unreadable", token(modelRow(fields = null)))
        assertEquals(
            "model_field_names_malformed",
            token(modelRow(fields = "Front${AnkiDroidApiContract.FIELD_SEPARATOR}"))
        )
        assertEquals("model_id_column_missing", token(
            InMemoryProviderRow(linkedMapOf(AnkiDroidApiContract.MODEL_NAME_COLUMN to "Basic"))
        ))
    }

    @Test
    fun `an unknown type code maps to unknown kind, never a guess`() {
        val outcome = AnkiDroidCreationMapper.mapModelRow(backendId, modelRow(type = 99))
            as AnkiDroidCreationMapper.ModelRowOutcome.Model
        assertEquals(AnkiNoteModelKind.UNKNOWN, outcome.model.kind)
    }
}
