package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.edit.BackendNoteMutationRequest
import com.studyagent.client.core.anki.edit.NoteMutationBackendResult
import com.studyagent.client.core.anki.edit.NoteMutationId
import com.studyagent.client.core.anki.edit.NoteMutationStep
import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiContract
import com.studyagent.client.data.anki.ankidroid.AnkiDroidNoteMutationMapper
import com.studyagent.client.data.anki.ankidroid.AnkiDroidNoteWrite
import com.studyagent.client.data.anki.ankidroid.AnkiDroidNoteWriteDispatch
import com.studyagent.client.data.anki.ankidroid.AnkiDroidNoteWriteMapping
import com.studyagent.client.data.anki.ankidroid.ProviderValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 17 — the pure domain-to-provider translation and the boundary classification. Runs on the JVM:
 * the mapper touches no Android type.
 */
class AnkiDroidNoteMutationMapperTest {

    private val backendId = AnkiBackendId.AnkiDroidLocal
    private val noteId = 1700000000123L

    private fun request(
        step: NoteMutationStep,
        noteIdText: String = noteId.toString(),
        cardOrd: Int? = 0,
        cardNoteId: String? = noteIdText
    ) = BackendNoteMutationRequest(
        mutationId = NoteMutationId("m-1"),
        // AnkiCardRef needs a cardId or (noteId + ordinal); a ref with no ordinal is identified by cardId.
        cardRef = if (cardOrd == null) {
            AnkiCardRef(backendId = backendId, cardId = "card-x", noteId = cardNoteId)
        } else {
            AnkiCardRef(backendId = backendId, noteId = cardNoteId, cardOrd = cardOrd)
        },
        noteRef = AnkiNoteRef(backendId = backendId, noteId = noteIdText),
        stepIndex = 0,
        step = step
    )

    private fun content(fields: List<String>? = null, tags: List<String>? = null) =
        NoteMutationStep.UpdateNoteContent(fieldValues = fields, tags = tags)

    private fun ready(mapping: AnkiDroidNoteWriteMapping): AnkiDroidNoteWrite {
        assertTrue("expected Ready, got $mapping", mapping is AnkiDroidNoteWriteMapping.Ready)
        return (mapping as AnkiDroidNoteWriteMapping.Ready).write
    }

    private fun refusedError(mapping: AnkiDroidNoteWriteMapping): AnkiError {
        assertTrue("expected Refused, got $mapping", mapping is AnkiDroidNoteWriteMapping.Refused)
        return (mapping as AnkiDroidNoteWriteMapping.Refused).error
    }

    @Test
    fun fieldsAndTagsGoInOneNoteUpdateWithAFullPositionalArrayAndTwoExpectedRows() {
        val write = ready(
            AnkiDroidNoteMutationMapper.map(
                request(content(fields = listOf("front", "back"), tags = listOf("alpha", "beta")))
            )
        )
        assertEquals("notes/$noteId", write.path)
        assertEquals(
            listOf(
                ProviderValue.StringValue("flds", "front\u001Fback"),
                ProviderValue.StringValue("tags", "alpha beta")
            ),
            write.values
        )
        assertEquals(2, write.expectedRows)
    }

    @Test
    fun fieldsOnlyNeverSendsTags() {
        val write = ready(AnkiDroidNoteMutationMapper.map(request(content(fields = listOf("a", "b")))))
        assertEquals(listOf<ProviderValue>(ProviderValue.StringValue("flds", "a\u001Fb")), write.values)
        assertEquals(1, write.expectedRows)
    }

    @Test
    fun tagsOnlyNeverSendsFields() {
        val write = ready(AnkiDroidNoteMutationMapper.map(request(content(tags = emptyList()))))
        // An empty tag list is a legitimate "clear all tags": the empty string, not a missing column.
        assertEquals(listOf<ProviderValue>(ProviderValue.StringValue("tags", "")), write.values)
        assertEquals(1, write.expectedRows)
    }

    @Test
    fun middleEmptyFieldsArePreservedAndOnlyTheLastFieldIsRefused() {
        val write = ready(AnkiDroidNoteMutationMapper.map(request(content(fields = listOf("", "x", "y")))))
        assertEquals(ProviderValue.StringValue("flds", "\u001Fx\u001Fy"), write.values.single())
    }

    @Test
    fun aTrailingEmptyFieldIsRefusedBecauseTheProviderDropsIt() {
        val error = refusedError(AnkiDroidNoteMutationMapper.map(request(content(fields = listOf("x", "")))))
        assertEquals(AnkiError.InvalidRequest("trailing_empty_field_not_representable"), error)
    }

    @Test
    fun aFieldContainingTheSeparatorIsRefusedBeforeItCanSplit() {
        val error = refusedError(AnkiDroidNoteMutationMapper.map(request(content(fields = listOf("a\u001Fb", "c")))))
        assertEquals(AnkiError.InvalidRequest("field_contains_separator"), error)
    }

    @Test
    fun aTagWithWhitespaceOrEmptyIsRefused() {
        assertEquals(
            AnkiError.InvalidRequest("invalid_tag"),
            refusedError(AnkiDroidNoteMutationMapper.map(request(content(tags = listOf("two words")))))
        )
        assertEquals(
            AnkiError.InvalidRequest("invalid_tag"),
            refusedError(AnkiDroidNoteMutationMapper.map(request(content(tags = listOf("")))))
        )
    }

    @Test
    fun aContentStepWithNothingToWriteIsRefused() {
        assertEquals(
            AnkiError.InvalidRequest("empty_content_step"),
            refusedError(AnkiDroidNoteMutationMapper.map(request(content())))
        )
    }

    @Test
    fun deckMoveIsCardScopedWithOneDeckIdColumnAndOneExpectedRow() {
        val write = ready(
            AnkiDroidNoteMutationMapper.map(
                request(NoteMutationStep.ChangeDeck(fromDeck = null, toDeck = AnkiDeckRef(backendId, "42")), cardOrd = 3)
            )
        )
        assertEquals("notes/$noteId/cards/3", write.path)
        assertEquals(listOf<ProviderValue>(ProviderValue.LongValue("deck_id", 42L)), write.values)
        assertEquals(1, write.expectedRows)
    }

    @Test
    fun ids_that_the_provider_cannot_address_are_refused() {
        assertEquals(
            AnkiError.InvalidRequest("note_id_not_provider_numeric"),
            refusedError(AnkiDroidNoteMutationMapper.map(request(content(listOf("a")), noteIdText = "abc")))
        )
        assertEquals(
            AnkiError.InvalidRequest("deck_id_not_provider_numeric"),
            refusedError(
                AnkiDroidNoteMutationMapper.map(
                    request(NoteMutationStep.ChangeDeck(null, AnkiDeckRef(backendId, "0")))
                )
            )
        )
        assertEquals(
            AnkiError.InvalidRequest("card_ord_missing"),
            refusedError(
                AnkiDroidNoteMutationMapper.map(
                    request(NoteMutationStep.ChangeDeck(null, AnkiDeckRef(backendId, "42")), cardOrd = null)
                )
            )
        )
    }

    @Test
    fun aCardFromAnotherNoteIsRefused() {
        val error = refusedError(
            AnkiDroidNoteMutationMapper.map(
                request(content(listOf("a")), cardNoteId = "999")
            )
        )
        assertEquals(AnkiError.InvalidRequest("card_note_mismatch"), error)
    }

    // --- classification at the mutation boundary --------------------------------------------------

    @Test
    fun aMatchingRowCountIsConfirmedApplied() {
        assertEquals(
            NoteMutationBackendResult.ConfirmedApplied,
            AnkiDroidNoteMutationMapper.classify(AnkiDroidNoteWriteDispatch.Returned(2), expectedRows = 2)
        )
    }

    @Test
    fun aRowCountThatDoesNotMatchIsNeverTakenAsSuccess() {
        val result = AnkiDroidNoteMutationMapper.classify(AnkiDroidNoteWriteDispatch.Returned(1), expectedRows = 2)
        assertTrue(result is NoteMutationBackendResult.OutcomeUnknown)
        val minusOne = AnkiDroidNoteMutationMapper.classify(AnkiDroidNoteWriteDispatch.Returned(-1), expectedRows = 1)
        assertTrue(minusOne is NoteMutationBackendResult.OutcomeUnknown)
    }

    @Test
    fun refusalBeforeDispatchIsConfirmedNotApplied() {
        val error = AnkiError.QueryFailure("provider_write_busy")
        assertEquals(
            NoteMutationBackendResult.ConfirmedNotApplied(error),
            AnkiDroidNoteMutationMapper.classify(AnkiDroidNoteWriteDispatch.NotDispatched(error), expectedRows = 1)
        )
    }

    @Test
    fun securityAndArgumentFailuresAreConfirmedNotAppliedButOtherThrowsAreUnknown() {
        assertTrue(
            AnkiDroidNoteMutationMapper.classify(AnkiDroidNoteWriteDispatch.Threw("SecurityException"), 1)
                is NoteMutationBackendResult.ConfirmedNotApplied
        )
        assertTrue(
            AnkiDroidNoteMutationMapper.classify(AnkiDroidNoteWriteDispatch.Threw("IllegalArgumentException"), 1)
                is NoteMutationBackendResult.ConfirmedNotApplied
        )
        assertTrue(
            AnkiDroidNoteMutationMapper.classify(AnkiDroidNoteWriteDispatch.Threw("IllegalStateException"), 1)
                is NoteMutationBackendResult.OutcomeUnknown
        )
        assertTrue(
            AnkiDroidNoteMutationMapper.classify(AnkiDroidNoteWriteDispatch.Unknown("write_timeout"), 1)
                is NoteMutationBackendResult.OutcomeUnknown
        )
    }

    @Test
    fun theProviderPathsUseOnlyPinnedConstants() {
        assertEquals("notes", AnkiDroidApiContract.NOTE_ITEM_PATH)
        assertEquals("cards", AnkiDroidApiContract.NOTE_CARDS_PATH)
        assertEquals("flds", AnkiDroidApiContract.NOTE_FIELDS_COLUMN)
        assertEquals("tags", AnkiDroidApiContract.NOTE_TAGS_COLUMN)
        assertEquals("deck_id", AnkiDroidApiContract.CARD_DECK_ID_COLUMN)
    }
}
