package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiNoteModelKind
import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiContract
import com.studyagent.client.data.anki.ankidroid.AnkiDroidEnrichedModelSnapshot
import com.studyagent.client.data.anki.ankidroid.AnkiDroidModelMapper
import com.studyagent.client.data.anki.ankidroid.InMemoryProviderRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiDroidModelMapperTest {

    private val backendId = AnkiBackendId.AnkiDroidLocal

    @Test
    fun `maps basic model with one template and css`() {
        val modelRow = InMemoryProviderRow(
            mapOf(
                AnkiDroidApiContract.MODEL_ID_COLUMN to 12345L,
                AnkiDroidApiContract.MODEL_NAME_COLUMN to "Basic",
                AnkiDroidApiContract.MODEL_FIELD_NAMES_COLUMN to "Front\u001FBack",
                AnkiDroidApiContract.MODEL_TYPE_COLUMN to 0,
                AnkiDroidApiContract.MODEL_NUM_CARDS_COLUMN to 1,
                AnkiDroidApiContract.MODEL_DECK_ID_COLUMN to 1L,
                AnkiDroidApiContract.MODEL_CSS_COLUMN to ".card { font-family: arial; }",
                AnkiDroidApiContract.MODEL_SORT_FIELD_INDEX_COLUMN to 0,
                AnkiDroidApiContract.MODEL_NOTE_COUNT_COLUMN to 42
            )
        )
        val templateRow = InMemoryProviderRow(
            mapOf(
                AnkiDroidApiContract.TEMPLATE_ORD_COLUMN to 0,
                AnkiDroidApiContract.TEMPLATE_NAME_COLUMN to "Card 1",
                AnkiDroidApiContract.TEMPLATE_QFMT_COLUMN to "{{Front}}",
                AnkiDroidApiContract.TEMPLATE_AFMT_COLUMN to "{{FrontSide}}<hr id=answer>{{Back}}",
                AnkiDroidApiContract.TEMPLATE_DECK_ID_COLUMN to null
            )
        )
        val snapshot = AnkiDroidEnrichedModelSnapshot(
            modelRow = modelRow,
            templateRows = listOf(templateRow),
            templatesFailed = false,
            templatesFailureToken = null
        )
        val outcome = AnkiDroidModelMapper.mapEnrichedModel(backendId, snapshot)
        assertTrue(outcome is AnkiDroidModelMapper.EnrichedModelOutcome.Model)
        val model = (outcome as AnkiDroidModelMapper.EnrichedModelOutcome.Model).enriched
        assertEquals("12345", model.ref.modelId)
        assertEquals("Basic", model.name)
        assertEquals(AnkiNoteModelKind.NORMAL, model.kind)
        assertEquals(2, model.fields.size)
        assertEquals("Front", model.fields[0].name)
        assertEquals(0, model.fields[0].ordinal)
        assertEquals("Back", model.fields[1].name)
        assertEquals(1, model.templateCount)
        assertEquals(".card { font-family: arial; }", model.css)
        assertEquals(0, model.sortFieldIndex)
        assertEquals(42, model.noteCount)
        assertEquals(1, model.templates.size)
        val t0 = model.templates[0]
        assertEquals("Card 1", t0.name)
        assertEquals(0, t0.ref.ordinal)
        assertEquals("{{Front}}", t0.qfmt)
        assertTrue(t0.afmt!!.contains("{{FrontSide}}"))
        assertNull(t0.targetDeckId)
    }

    @Test
    fun `maps cloze model`() {
        val modelRow = InMemoryProviderRow(
            mapOf(
                AnkiDroidApiContract.MODEL_ID_COLUMN to 200L,
                AnkiDroidApiContract.MODEL_NAME_COLUMN to "Cloze",
                AnkiDroidApiContract.MODEL_FIELD_NAMES_COLUMN to "Text\u001FExtra",
                AnkiDroidApiContract.MODEL_TYPE_COLUMN to 1, // CLOZE
                AnkiDroidApiContract.MODEL_NUM_CARDS_COLUMN to 1,
                AnkiDroidApiContract.MODEL_CSS_COLUMN to ".card { color: blue; }"
            )
        )
        val templateRow = InMemoryProviderRow(
            mapOf(
                AnkiDroidApiContract.TEMPLATE_ORD_COLUMN to 0,
                AnkiDroidApiContract.TEMPLATE_NAME_COLUMN to "Cloze",
                AnkiDroidApiContract.TEMPLATE_QFMT_COLUMN to "{{cloze:Text}}",
                AnkiDroidApiContract.TEMPLATE_AFMT_COLUMN to "{{cloze:Text}}<br>{{Extra}}"
            )
        )
        val snapshot = AnkiDroidEnrichedModelSnapshot(modelRow, listOf(templateRow), false, null)
        val outcome = AnkiDroidModelMapper.mapEnrichedModel(backendId, snapshot)
        val model = (outcome as AnkiDroidModelMapper.EnrichedModelOutcome.Model).enriched
        assertEquals(AnkiNoteModelKind.CLOZE, model.kind)
        assertTrue(model.templates[0].qfmt!!.contains("cloze"))
    }

    @Test
    fun `malformed model row returns Malformed outcome`() {
        // Blank name — mimicking the GATE 18 malformed case.
        val modelRow = InMemoryProviderRow(
            mapOf(
                AnkiDroidApiContract.MODEL_ID_COLUMN to 1L,
                AnkiDroidApiContract.MODEL_NAME_COLUMN to "",
                AnkiDroidApiContract.MODEL_FIELD_NAMES_COLUMN to "Front",
                AnkiDroidApiContract.MODEL_TYPE_COLUMN to 0,
                AnkiDroidApiContract.MODEL_NUM_CARDS_COLUMN to 1
            )
        )
        val snapshot = AnkiDroidEnrichedModelSnapshot(modelRow, emptyList(), false, null)
        val outcome = AnkiDroidModelMapper.mapEnrichedModel(backendId, snapshot)
        assertTrue(outcome is AnkiDroidModelMapper.EnrichedModelOutcome.Malformed)
    }

    @Test
    fun `missing optional columns degrade to null`() {
        val modelRow = InMemoryProviderRow(
            mapOf(
                AnkiDroidApiContract.MODEL_ID_COLUMN to 1L,
                AnkiDroidApiContract.MODEL_NAME_COLUMN to "Basic",
                AnkiDroidApiContract.MODEL_FIELD_NAMES_COLUMN to "Front\u001FBack",
                AnkiDroidApiContract.MODEL_TYPE_COLUMN to 0,
                AnkiDroidApiContract.MODEL_NUM_CARDS_COLUMN to 1
                // No CSS, no sort field, no note count, no latex
            )
        )
        val templateRow = InMemoryProviderRow(
            mapOf(
                AnkiDroidApiContract.TEMPLATE_ORD_COLUMN to 0,
                AnkiDroidApiContract.TEMPLATE_NAME_COLUMN to "Card 1",
                AnkiDroidApiContract.TEMPLATE_QFMT_COLUMN to "{{Front}}",
                AnkiDroidApiContract.TEMPLATE_AFMT_COLUMN to "{{Back}}"
                // No deck override, no browser formats
            )
        )
        val snapshot = AnkiDroidEnrichedModelSnapshot(modelRow, listOf(templateRow), false, null)
        val outcome = AnkiDroidModelMapper.mapEnrichedModel(backendId, snapshot)
        val model = (outcome as AnkiDroidModelMapper.EnrichedModelOutcome.Model).enriched
        assertNull(model.css)
        assertNull(model.sortFieldIndex)
        assertNull(model.noteCount)
        assertNull(model.latexPreamble)
        assertNull(model.latexSvg)
        assertNull(model.templates[0].targetDeckId)
        assertNull(model.templates[0].browserQfmt)
        assertNull(model.templates[0].browserAfmt)
    }

    @Test
    fun `failed template load returns model with empty templates and degradation token`() {
        val modelRow = InMemoryProviderRow(
            mapOf(
                AnkiDroidApiContract.MODEL_ID_COLUMN to 1L,
                AnkiDroidApiContract.MODEL_NAME_COLUMN to "Basic",
                AnkiDroidApiContract.MODEL_FIELD_NAMES_COLUMN to "Front",
                AnkiDroidApiContract.MODEL_TYPE_COLUMN to 0,
                AnkiDroidApiContract.MODEL_NUM_CARDS_COLUMN to 1
            )
        )
        val snapshot = AnkiDroidEnrichedModelSnapshot(
            modelRow = modelRow,
            templateRows = emptyList(),
            templatesFailed = true,
            templatesFailureToken = "templates_query_failed:PROVIDER_ERROR"
        )
        val outcome = AnkiDroidModelMapper.mapEnrichedModel(backendId, snapshot)
        val result = outcome as AnkiDroidModelMapper.EnrichedModelOutcome.Model
        assertTrue(result.degradations.contains("templates_query_failed:PROVIDER_ERROR"))
        assertEquals(0, result.enriched.templates.size)
    }

    @Test
    fun `collectionKey is propagated to model and template refs`() {
        val modelRow = InMemoryProviderRow(
            mapOf(
                AnkiDroidApiContract.MODEL_ID_COLUMN to 5L,
                AnkiDroidApiContract.MODEL_NAME_COLUMN to "Basic",
                AnkiDroidApiContract.MODEL_FIELD_NAMES_COLUMN to "Front\u001FBack",
                AnkiDroidApiContract.MODEL_TYPE_COLUMN to 0,
                AnkiDroidApiContract.MODEL_NUM_CARDS_COLUMN to 1
            )
        )
        val templateRow = InMemoryProviderRow(
            mapOf(
                AnkiDroidApiContract.TEMPLATE_ORD_COLUMN to 0,
                AnkiDroidApiContract.TEMPLATE_NAME_COLUMN to "Card 1",
                AnkiDroidApiContract.TEMPLATE_QFMT_COLUMN to "{{Front}}",
                AnkiDroidApiContract.TEMPLATE_AFMT_COLUMN to "{{Back}}"
            )
        )
        val snapshot = AnkiDroidEnrichedModelSnapshot(modelRow, listOf(templateRow), false, null)
        val result = AnkiDroidModelMapper.mapEnrichedModel(
            backendId, snapshot, collectionKey = "col1"
        ) as AnkiDroidModelMapper.EnrichedModelOutcome.Model
        assertEquals("col1", result.enriched.ref.collectionKey)
        assertEquals("col1", result.enriched.templates[0].ref.collectionKey)
    }
}
