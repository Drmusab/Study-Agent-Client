package com.studyagent.client.ui

import com.studyagent.client.core.anki.*
import com.studyagent.client.ui.screens.carddetails.CardDetailsMapper
import com.studyagent.client.ui.screens.carddetails.FieldPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardDetailsMapperTest {
    private val backend = AnkiBackendId.Fake("details-mapper")
    private val noteRef = AnkiNoteRef(backend, "note-10", "collection-A")
    private val cardRef = AnkiCardRef(
        backendId = backend,
        cardId = "card-20",
        noteId = noteRef.noteId,
        cardOrd = 1,
        collectionKey = "collection-A"
    )
    private val deckRef = AnkiDeckRef(backend, "deck-3", "collection-A")

    private fun details(
        scheduling: AnkiSchedulingInfo? = null,
        fields: List<AnkiNoteField>? = listOf(
            AnkiNoteField("Back", "<p>second</p>", 1),
            AnkiNoteField("Front", "<b>first</b>", 0)
        ),
        tags: List<String>? = listOf("عربي", "mixed-English")
    ) = AnkiCardDetails(
        cardRef = cardRef,
        noteRef = noteRef,
        cardOrd = 1,
        deckRef = deckRef,
        deckName = "Language::Mixed",
        noteTypeId = "model-2",
        noteTypeName = "Basic",
        templateName = "Reverse",
        questionHtml = "<b>rendered question</b>",
        answerHtml = "<i>rendered answer</i>",
        questionText = "normalized question",
        answerText = "normalized answer",
        pureAnswerText = "pure answer",
        fields = fields,
        tags = tags,
        flag = null,
        cardType = AnkiCardType.REVIEW,
        queueState = AnkiCardQueueState.SUSPENDED,
        scheduling = scheduling,
        originalDeckRef = null,
        noteCreatedEpochSeconds = null,
        noteModifiedEpochSeconds = 1_700_000_000L,
        mediaFiles = listOf("sound.mp3"),
        degradations = emptyList()
    )

    @Test
    fun `note field ordering is preserved and safe text strips rather than executes markup`() {
        val mapped = CardDetailsMapper.map(details())
        assertEquals(listOf("Back", "Front"), mapped.fields?.map { it.name })
        assertEquals(listOf(1, 0), mapped.fields?.map { it.ordinal })
        assertEquals(listOf("second", "first"), mapped.fields?.map { it.value })
        assertEquals("Hello & welcome", CardDetailsMapper.safePlainText("<script>alert(1)</script><p>Hello &amp; welcome</p>"))
        assertFalse(mapped.fields!!.first().value.contains("<"))
    }

    @Test
    fun `rendered content remains separate from source note fields`() {
        val source = details(fields = listOf(AnkiNoteField("Front", "SOURCE FIELD", 0)))
        val mapped = CardDetailsMapper.map(source)
        assertEquals("<b>rendered question</b>", mapped.originalCard?.questionHtml)
        assertEquals("<i>rendered answer</i>", mapped.originalCard?.answerHtml)
        assertEquals("normalized question", mapped.questionText)
        assertEquals("normalized answer", mapped.answerText)
        assertEquals("pure answer", mapped.pureAnswerText)
        assertEquals("SOURCE FIELD", mapped.fields?.single()?.value)
        assertFalse(mapped.originalCard?.questionHtml?.contains("SOURCE FIELD") == true)
    }

    @Test
    fun `zero scheduling values display as zero and missing values are omitted`() {
        val zero = CardDetailsMapper.map(details(scheduling = AnkiSchedulingInfo(reps = 0, lapses = 0, intervalDays = 0)))
        assertTrue(zero.schedulingRows.any { it.label == "Reviews" && it.value == "0" })
        assertTrue(zero.schedulingRows.any { it.label == "Lapses" && it.value == "0" })
        assertTrue(zero.schedulingRows.any { it.label == "Current interval" && it.value == "0 days" })

        val unavailable = CardDetailsMapper.map(details(scheduling = AnkiSchedulingInfo(reps = null, lapses = null)))
        assertFalse(unavailable.schedulingRows.any { it.label == "Reviews" })
        assertFalse(unavailable.schedulingRows.any { it.label == "Lapses" })
    }

    @Test
    fun `raw due is not converted and FSRS is shown only when authoritative`() {
        val mapped = CardDetailsMapper.map(
            details(scheduling = AnkiSchedulingInfo(rawDue = 42L, rawOriginalDue = 0L, fsrs = AnkiFsrsInfo(stability = 4.5)))
        )
        assertEquals("42 (raw; backend-defined units)", mapped.schedulingRows.first { it.label == "Stored due value" }.value)
        assertEquals("0 (raw; backend-defined units)", mapped.schedulingRows.first { it.label == "Stored original due value" }.value)
        assertEquals("4.5", mapped.schedulingRows.first { it.label == "FSRS stability" }.value)
        assertFalse(mapped.schedulingRows.any { it.label.contains("prediction", ignoreCase = true) })
    }

    @Test
    fun `unknown field and tag metadata stay distinct from confirmed empty collections`() {
        val unavailable = CardDetailsMapper.map(details(fields = null, tags = null))
        assertNull(unavailable.fields)
        assertNull(unavailable.tags)
        assertFalse(unavailable.hasFields)
        assertFalse(unavailable.hasTagMetadata)

        val empty = CardDetailsMapper.map(details(fields = emptyList(), tags = emptyList()))
        assertEquals(emptyList<FieldPresentation>(), empty.fields)
        assertEquals(emptyList<String>(), empty.tags)
        assertTrue(empty.hasFields)
        assertTrue(empty.hasTagMetadata)
    }

    @Test
    fun `media references stay logical names and unsafe paths are not surfaced`() {
        val mapped = CardDetailsMapper.map(
            details().copy(mediaFiles = listOf("card.png", "../private.anki2", "%252e%252e%252fsecret"))
        )
        assertEquals(listOf("card.png"), mapped.mediaFiles)
        assertEquals(listOf(AnkiMediaRef.BackendStream("card.png")), mapped.originalCard?.media)
    }

    @Test
    fun `status and flag are read-only projections`() {
        val mapped = CardDetailsMapper.map(details())
        assertEquals("Review", mapped.overviewRows.first { it.label == "Card type" }.value)
        assertEquals("Suspended", mapped.overviewRows.first { it.label == "Queue status" }.value)
        assertNull(mapped.flag)
        assertNull(mapped.overviewRows.first { it.label == "Flag" }.value)
    }
}
