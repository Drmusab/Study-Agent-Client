package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiContract
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailure
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureCategory
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureEvidence
import com.studyagent.client.data.anki.ankidroid.AnkiDroidNoteGateway
import com.studyagent.client.data.anki.ankidroid.AnkiDroidNoteMapper
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidNoteGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidProviderClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiDroidNoteGatewayTest {
    private fun noteRow(
        id: String = "7",
        mid: String = "2",
        fields: String = "Q${AnkiDroidApiContract.FIELD_SEPARATOR}A",
        tags: String? = "zebra alpha",
        mod: String = "1700000000"
    ) = mapOf(
        "_id" to id,
        "mid" to mid,
        "flds" to fields,
        "tags" to tags,
        "mod" to mod
    )

    private fun modelRow(
        id: String = "2",
        names: String = "Front${AnkiDroidApiContract.FIELD_SEPARATOR}Back",
        name: String = "Basic",
        type: Int = AnkiDroidApiContract.MODEL_TYPE_NORMAL,
        count: Int = 2
    ) = mapOf(
        "_id" to id,
        "field_names" to names,
        "name" to name,
        "type" to type,
        "num_cards" to count
    )

    @Test
    fun `loads one exact note and exact note type with backend field order`() = runTest {
        val provider = FakeAnkiDroidProviderClient().apply {
            scriptRows("notes/7", listOf(noteRow(fields = "A${AnkiDroidApiContract.FIELD_SEPARATOR}B")))
            scriptRows("models/2", listOf(modelRow(names = "Zulu${AnkiDroidApiContract.FIELD_SEPARATOR}Alpha")))
        }
        val gateway: AnkiDroidNoteGateway = DefaultAnkiDroidNoteGateway(provider)

        val result = gateway.queryNoteDetails("authority", "7", collectionKey = "collection-A")
        assertTrue(result is AnkiResult.Success)
        val note = (result as AnkiResult.Success).value
        assertEquals("7", note.noteRef.noteId)
        assertEquals("collection-A", note.noteRef.collectionKey)
        assertEquals(listOf("Zulu", "Alpha"), note.fields?.map { it.name })
        assertEquals(listOf("A", "B"), note.fields?.map { it.value })
        assertEquals(listOf(0, 1), note.fields?.map { it.ordinal })
        assertEquals(listOf("zebra", "alpha"), note.tags)
        assertEquals("Basic", note.noteTypeName)
        assertEquals(1_700_000_000L, note.noteModifiedEpochSeconds)
        assertEquals(listOf("authority/notes/7", "authority/models/2"), provider.queryLog)
        assertEquals(AnkiDroidApiContract.NOTE_PROJECTION.toList(), provider.projectionLog[0])
        assertEquals(AnkiDroidApiContract.MODEL_PROJECTION.toList(), provider.projectionLog[1])
        assertEquals(2L, (gateway as DefaultAnkiDroidNoteGateway).lastQueryDiagnostics().providerQueryCount)
        assertFalse(provider.queryLog.any { it == "authority/notes" || it == "authority/models" })
    }

    @Test
    fun `empty source fields and tag lists stay distinct from unavailable metadata`() = runTest {
        val sep = AnkiDroidApiContract.FIELD_SEPARATOR
        val provider = FakeAnkiDroidProviderClient().apply {
            scriptRows("notes/7", listOf(noteRow(fields = "Q${sep}${sep}", tags = "")))
            scriptRows("models/2", listOf(modelRow(names = "Front${sep}Back${sep}Extra")))
        }
        val note = (DefaultAnkiDroidNoteGateway(provider).queryNoteDetails("a", "7") as AnkiResult.Success).value
        assertEquals(listOf("Q", "", ""), note.fields?.map { it.value })
        assertEquals(emptyList<String>(), note.tags)

        val unavailableProvider = FakeAnkiDroidProviderClient().apply {
            scriptRows("notes/7", listOf(noteRow(fields = "Q${sep}A", tags = null)))
            scriptRows("models/2", listOf(modelRow()))
        }
        val unavailable = (DefaultAnkiDroidNoteGateway(unavailableProvider)
            .queryNoteDetails("a", "7") as AnkiResult.Success).value
        assertNull(unavailable.tags)
        assertTrue(AnkiDroidNoteMapper.DEG_NOTE_TAGS_UNAVAILABLE in unavailable.degradations)
    }

    @Test
    fun `inconsistent note and model field counts are integrity failures not guessed names`() = runTest {
        val sep = AnkiDroidApiContract.FIELD_SEPARATOR
        val provider = FakeAnkiDroidProviderClient().apply {
            scriptRows("notes/7", listOf(noteRow(fields = "Q${sep}A${sep}C")))
            scriptRows("models/2", listOf(modelRow(names = "Front${sep}Back")))
        }
        val result = DefaultAnkiDroidNoteGateway(provider).queryNoteDetails("a", "7")
        assertTrue(result is AnkiResult.Failure)
        assertEquals("note_field_pairing_inconsistent", (result as AnkiResult.Failure).error.let {
            (it as AnkiError.DataIntegrityFailure).detail
        })
    }

    @Test
    fun `missing note is typed not found and missing note type is integrity failure`() = runTest {
        val missing = DefaultAnkiDroidNoteGateway(FakeAnkiDroidProviderClient())
            .queryNoteDetails("a", "7")
        assertTrue(missing is AnkiResult.Failure)
        assertTrue((missing as AnkiResult.Failure).error is AnkiError.NoteNotFound)

        val modelMissing = FakeAnkiDroidProviderClient().apply {
            scriptRows("notes/7", listOf(noteRow()))
        }
        val corrupt = DefaultAnkiDroidNoteGateway(modelMissing).queryNoteDetails("a", "7")
        assertTrue(corrupt is AnkiResult.Failure)
        assertEquals("note_type_missing_for_note", (corrupt as AnkiResult.Failure).error.let {
            (it as AnkiError.DataIntegrityFailure).detail
        })
    }

    @Test
    fun `provider failures stay typed and cancellation is not converted`() = runTest {
        val failedProvider = FakeAnkiDroidProviderClient().apply {
            scriptRows("notes/7", listOf(noteRow()))
            scriptFailure("models/2", AnkiDroidFailure(
                category = AnkiDroidFailureCategory.PERMISSION_DENIED,
                evidence = AnkiDroidFailureEvidence.SECURITY_EXCEPTION_TYPE,
                exceptionClass = "SecurityException"
            ))
        }
        val failure = DefaultAnkiDroidNoteGateway(failedProvider).queryNoteDetails("a", "7")
        assertTrue(failure is AnkiResult.Failure)
        assertTrue((failure as AnkiResult.Failure).error is AnkiError.PermissionRequired)
    }
}
