package com.studyagent.client.anki.edit

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiNoteField
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.edit.NoteEditBase
import com.studyagent.client.core.anki.edit.NoteEditDraft
import com.studyagent.client.core.anki.edit.NoteEditPlanner
import com.studyagent.client.core.anki.edit.NoteEditValidationError
import com.studyagent.client.core.anki.edit.NoteMutationOperation
import com.studyagent.client.core.anki.edit.TagNormalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GATE 17 — pre-transaction validation, minimal patch and ordered plan. Pure; no backend. */
class NoteEditPlannerTest {

    private val backend = AnkiBackendId.AnkiDroidLocal

    private fun base(
        fields: List<String> = listOf("front", "back"),
        tags: List<String> = listOf("alpha", "beta"),
        deck: String? = "deck-a",
        noteTypeId: String? = "model-1"
    ): NoteEditBase = NoteEditBase(
        cardRef = AnkiCardRef(backend, cardId = "card-1", noteId = "note-1", cardOrd = 0),
        noteRef = AnkiNoteRef(backend, "note-1"),
        noteTypeId = noteTypeId,
        fields = fields.mapIndexed { i, v -> AnkiNoteField(name = listOf("Front", "Back", "Extra")[i], value = v, ordinal = i) },
        tags = tags,
        deckRef = deck?.let { AnkiDeckRef(backend, it) }
    )

    @Test
    fun emptyDraftProducesEmptyPatchAndNoPlan() {
        val patch = NoteEditPlanner.buildPatch(base(), NoteEditDraft())
        assertTrue(patch.isEmpty)
        assertTrue(NoteEditPlanner.validateDraft(base(), NoteEditDraft()).isEmpty())
    }

    @Test
    fun unchangedValuesDoNotCreateAPatch() {
        val draft = NoteEditDraft(
            fieldValues = mapOf(0 to "front", 1 to "back"),
            tags = listOf("beta", "alpha"),   // same set, different order
            targetDeck = AnkiDeckRef(backend, "deck-a")
        )
        assertTrue(NoteEditPlanner.buildPatch(base(), draft).isEmpty)
    }

    @Test
    fun onlyChangedFieldAppearsInPatchWithIdentity() {
        val patch = NoteEditPlanner.buildPatch(base(), NoteEditDraft(fieldValues = mapOf(0 to "front", 1 to "NEW")))
        assertEquals(1, patch.fieldChanges.size)
        val change = patch.fieldChanges.single()
        assertEquals(1, change.ordinal)
        assertEquals("Back", change.name)
        assertEquals("back", change.oldValue)
        assertEquals("NEW", change.newValue)
    }

    @Test
    fun tagCaseChangeIsARealChange() {
        val patch = NoteEditPlanner.buildPatch(base(tags = listOf("Alpha")), NoteEditDraft(tags = listOf("alpha")))
        assertFalse(patch.isEmpty)
        assertEquals(listOf("alpha"), patch.tagChange?.newTags)
    }

    @Test
    fun unknownFieldOrdinalIsRejectedBeforeAnyTransaction() {
        val errors = NoteEditPlanner.validateDraft(base(), NoteEditDraft(fieldValues = mapOf(7 to "x")))
        assertEquals(listOf<NoteEditValidationError>(NoteEditValidationError.UnknownField(7)), errors)
    }

    @Test
    fun fieldSeparatorInValueIsRejected() {
        val errors = NoteEditPlanner.validateDraft(base(), NoteEditDraft(fieldValues = mapOf(0 to "a\u001Fb")))
        assertEquals(listOf<NoteEditValidationError>(NoteEditValidationError.FieldContainsSeparator(0)), errors)
    }

    @Test
    fun fieldEditWithoutNoteTypeIdentityIsRefused() {
        val errors = NoteEditPlanner.validateDraft(base(noteTypeId = null), NoteEditDraft(fieldValues = mapOf(0 to "x")))
        assertEquals(listOf<NoteEditValidationError>(NoteEditValidationError.FieldIdentityUnavailable), errors)
    }

    @Test
    fun tagNormalizationTrimsDropsBlanksAndDedupesCaseInsensitively() {
        val result = NoteEditPlanner.normalizeTags(listOf("  Alpha ", "", "alpha", "BETA", "   ", "gamma"))
        assertEquals(TagNormalization.Valid(listOf("Alpha", "BETA", "gamma")), result)
    }

    @Test
    fun tagsWithInternalWhitespaceOrControlCharactersAreRejected() {
        assertEquals(TagNormalization.Invalid("two words"), NoteEditPlanner.normalizeTags(listOf("two words")))
        assertEquals(TagNormalization.Invalid("bad\u0007tag"), NoteEditPlanner.normalizeTags(listOf("bad\u0007tag")))
        val errors = NoteEditPlanner.validateDraft(base(), NoteEditDraft(tags = listOf("two words")))
        assertEquals(listOf<NoteEditValidationError>(NoteEditValidationError.InvalidTag("two words")), errors)
    }

    @Test
    fun deckTargetIsComparedByStableIdNotName() {
        val sameIdOtherCollectionKey = AnkiDeckRef(backend, "deck-a", collectionKey = "k")
        val patch = NoteEditPlanner.buildPatch(base(), NoteEditDraft(targetDeck = sameIdOtherCollectionKey))
        assertTrue(patch.deckChange == null)
    }

    @Test
    fun deckMoveNeedsAKnownSourceDeck() {
        val errors = NoteEditPlanner.validateDraft(
            base(deck = null),
            NoteEditDraft(targetDeck = AnkiDeckRef(backend, "deck-b"))
        )
        assertEquals(listOf<NoteEditValidationError>(NoteEditValidationError.SourceDeckUnknown), errors)
    }

    @Test
    fun deckTargetFromAnotherBackendIsRejected() {
        val errors = NoteEditPlanner.validateDraft(
            base(),
            NoteEditDraft(targetDeck = AnkiDeckRef(AnkiBackendId.PcAgent("p"), "deck-b"))
        )
        assertEquals(listOf<NoteEditValidationError>(NoteEditValidationError.DeckTargetWrongBackend), errors)
    }

    @Test
    fun planOrdersContentBeforeDeckAndMergesFieldsAndTagsIntoOneContentOperation() {
        val draft = NoteEditDraft(
            fieldValues = mapOf(1 to "NEW"),
            tags = listOf("alpha", "gamma"),
            targetDeck = AnkiDeckRef(backend, "deck-b")
        )
        val patch = NoteEditPlanner.buildPatch(base(), draft)
        val plan = NoteEditPlanner.buildPlan(patch)
        assertEquals(
            listOf(
                NoteMutationOperation.UpdateNoteContent(updatesFields = true, updatesTags = true),
                NoteMutationOperation.ChangeDeck(AnkiDeckRef(backend, "deck-b"))
            ),
            plan.operations
        )
    }

    @Test
    fun deckOnlyPlanHasNoContentOperation() {
        val patch = NoteEditPlanner.buildPatch(base(), NoteEditDraft(targetDeck = AnkiDeckRef(backend, "deck-b")))
        assertEquals(1, NoteEditPlanner.buildPlan(patch).operations.size)
        assertTrue(NoteEditPlanner.buildPlan(patch).operations.single() is NoteMutationOperation.ChangeDeck)
    }

    @Test
    fun emptyPatchNeverBecomesAPlan() {
        var thrown = false
        try {
            NoteEditPlanner.buildPlan(NoteEditPlanner.buildPatch(base(), NoteEditDraft()))
        } catch (refused: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun unicodeAndRightToLeftFieldValuesAreCarriedUnchanged() {
        val arabic = "مرحبا بالعالم \u200F"
        val patch = NoteEditPlanner.buildPatch(base(), NoteEditDraft(fieldValues = mapOf(0 to arabic)))
        assertEquals(arabic, patch.fieldChanges.single().newValue)
    }

    @Test
    fun baseRefusesFieldsWhoseOrdinalsDoNotMatchTemplateOrder() {
        val misordered = listOf(
            AnkiNoteField(name = "Back", value = "b", ordinal = 1),
            AnkiNoteField(name = "Front", value = "f", ordinal = 0)
        )
        var refused = false
        try {
            NoteEditBase(
                cardRef = AnkiCardRef(backend, cardId = "c", noteId = "n", cardOrd = 0),
                noteRef = AnkiNoteRef(backend, "n"),
                noteTypeId = "m",
                fields = misordered,
                tags = emptyList(),
                deckRef = null
            )
        } catch (invalid: IllegalArgumentException) {
            refused = true
        }
        assertTrue(refused)
    }
}
