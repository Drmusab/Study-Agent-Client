package com.studyagent.client.anki.edit

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardDetails
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiNoteField
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.edit.ChangedFieldMark
import com.studyagent.client.core.anki.edit.DeckChange
import com.studyagent.client.core.anki.edit.NoteContentCanonicalization
import com.studyagent.client.core.anki.edit.NoteMutationId
import com.studyagent.client.core.anki.edit.NoteMutationIntent
import com.studyagent.client.core.anki.edit.NoteMutationOperation
import com.studyagent.client.core.anki.edit.NoteMutationPlan
import com.studyagent.client.core.anki.edit.NoteMutationReason
import com.studyagent.client.core.anki.edit.NoteMutationRecord
import com.studyagent.client.core.anki.edit.NoteMutationStatus
import com.studyagent.client.core.anki.edit.NoteMutationStep
import com.studyagent.client.core.anki.edit.NoteMutationVerification
import com.studyagent.client.core.anki.edit.NoteMutationVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 17 CONTRACT-06 / CONTRACT-15 — the post-write verification rules, and the exact
 * canonicalization they are allowed to tolerate.
 *
 * Every tolerance asserted here is a transformation the pinned backend provably performs itself
 * (rslib 25.09.2 `notes/mod.rs normalize_field`, `tags/register.rs canonify_tags`). Nothing here may
 * treat "the note now looks like the draft" as proof that *this* transaction applied: the verifier
 * answers a state question only, and the coordinator is what turns that answer into a status.
 */
class NoteMutationVerificationTest {

    private val backend = AnkiBackendId.AnkiDroidLocal
    private val otherBackend = AnkiBackendId.PcAgent("profile-1")

    private fun details(
        fields: List<String>? = listOf("front text", "back text"),
        tags: List<String>? = listOf("alpha"),
        deckId: String? = "deck-a",
        backendId: AnkiBackendId = backend,
        reportOrdinals: Boolean = true
    ): AnkiCardDetails = AnkiCardDetails(
        cardRef = AnkiCardRef(backendId, cardId = "card-1", noteId = "note-1", cardOrd = 0),
        noteRef = AnkiNoteRef(backendId, "note-1"),
        cardOrd = 0,
        deckRef = deckId?.let { AnkiDeckRef(backendId, it) },
        deckName = deckId,
        noteTypeId = "model-1",
        noteTypeName = "Basic",
        templateName = null,
        questionHtml = null,
        answerHtml = null,
        questionText = null,
        answerText = null,
        pureAnswerText = null,
        fields = fields?.mapIndexed { index, value ->
            AnkiNoteField(
                name = if (index == 0) "Front" else "Back",
                value = value,
                ordinal = if (reportOrdinals) index else null
            )
        },
        tags = tags,
        flag = null,
        cardType = null,
        queueState = null,
        scheduling = null,
        originalDeckRef = null,
        noteCreatedEpochSeconds = null,
        noteModifiedEpochSeconds = null
    )

    private fun contentStep(fields: List<String>? = null, tags: List<String>? = null) =
        NoteMutationStep.UpdateNoteContent(fieldValues = fields, tags = tags)

    private fun deckStep(toDeckId: String, fromDeckId: String? = "deck-a") =
        NoteMutationStep.ChangeDeck(
            fromDeck = fromDeckId?.let { AnkiDeckRef(backend, it) },
            toDeck = AnkiDeckRef(backend, toDeckId)
        )

    /**
     * A record whose plan mirrors the marks: a content operation exists only when fields or tags were
     * marked, and a deck operation only when a deck change was marked ([NoteMutationPlan] rejects an
     * operation that changes nothing, exactly as the planner does).
     */
    private fun record(
        changedFields: List<ChangedFieldMark> = emptyList(),
        tagsChanged: Boolean = false,
        deckChange: DeckChange? = null,
        lastEntered: Int = 0
    ): NoteMutationRecord = NoteMutationRecord(
        mutationId = NoteMutationId("m-1"),
        backendId = backend,
        cardRef = AnkiCardRef(backend, cardId = "card-1", noteId = "note-1", cardOrd = 0),
        noteRef = AnkiNoteRef(backend, "note-1"),
        noteTypeId = "model-1",
        changedFields = changedFields,
        tagsChanged = tagsChanged,
        deckChange = deckChange,
        plan = NoteMutationPlan(
            buildList {
                if (changedFields.isNotEmpty() || tagsChanged) {
                    add(
                        NoteMutationOperation.UpdateNoteContent(
                            updatesFields = changedFields.isNotEmpty(),
                            updatesTags = tagsChanged
                        )
                    )
                }
                if (deckChange != null) add(NoteMutationOperation.ChangeDeck(deckChange.toDeck))
                if (isEmpty()) add(NoteMutationOperation.ChangeDeck(AnkiDeckRef(backend, "deck-b")))
            }
        ),
        status = NoteMutationStatus.AMBIGUOUS,
        lastEnteredOperation = lastEntered,
        attemptCount = 1,
        reason = NoteMutationReason.OUTCOME_UNKNOWN,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L
    )

    private fun detailOf(verification: NoteMutationVerification): String = when (verification) {
        is NoteMutationVerification.DiffersFromIntent -> verification.detail
        is NoteMutationVerification.Unverifiable -> verification.detail
        NoteMutationVerification.MatchesIntent -> "none"
    }

    // ---- what the backend provably does to a value ------------------------------------------------

    @Test
    fun fieldCanonicalizationStripsAsciiControlsButKeepsNewlineAndTab() {
        assertEquals(
            "line one\nline two\ttabbed",
            NoteContentCanonicalization.fieldValue("line\u0000 one\nline\u0007 two\ttabbed")
        )
        assertEquals("", NoteContentCanonicalization.fieldValue("\u0001\u001e"))
    }

    @Test
    fun theFieldSeparatorIsNeverSilentlyStripped() {
        // U+001F is refused upstream. If it ever reached a comparison, hiding it would let a broken
        // field structure verify as correct.
        assertEquals("a\u001Fb", NoteContentCanonicalization.fieldValue("a\u001Fb"))
        assertFalse(
            NoteContentCanonicalization.fieldsEquivalent(listOf("a\u001Fb"), listOf("ab"))
        )
    }

    @Test
    fun fieldsAreComparedPositionallyAndNeverByName() {
        assertTrue(NoteContentCanonicalization.fieldsEquivalent(listOf("x", "y"), listOf("x", "y")))
        assertFalse(NoteContentCanonicalization.fieldsEquivalent(listOf("x", "y"), listOf("y", "x")))
        assertFalse(NoteContentCanonicalization.fieldsEquivalent(listOf("x"), listOf("x", "")))
    }

    @Test
    fun nfcEquivalentFieldsAreTheSameContent() {
        // rslib normalizes to NFC when NormalizeNoteText is on; that preference is not readable
        // through the public provider, so both sides are compared in NFC.
        assertTrue(NoteContentCanonicalization.fieldsEquivalent(listOf("e\u0301"), listOf("\u00e9")))
        // The case pinned by rslib's own canonify test: U+FA47 is stored as U+6F22.
        assertTrue(NoteContentCanonicalization.fieldsEquivalent(listOf("\ufa47"), listOf("\u6f22")))
        // A genuinely different value is still different.
        assertFalse(NoteContentCanonicalization.fieldsEquivalent(listOf("\u00e9"), listOf("e")))
    }

    @Test
    fun tagsAreOneCaseInsensitiveUnorderedSet() {
        assertTrue(NoteContentCanonicalization.tagsEquivalent(listOf("Foo", "bar"), listOf("bar", "foo")))
        assertTrue(NoteContentCanonicalization.tagsEquivalent(listOf("foo", "FOO"), listOf("foo")))
        assertTrue(NoteContentCanonicalization.tagsEquivalent(listOf("\ufa47"), listOf("\u6f22")))
        assertFalse(NoteContentCanonicalization.tagsEquivalent(listOf("foo"), listOf("foo", "bar")))
    }

    @Test
    fun emptyAndBlankTagsCarryNoInformation() {
        // canonify_tags drops empty components, so an empty request and an empty tag list agree.
        assertTrue(NoteContentCanonicalization.tagsEquivalent(listOf("", "  "), emptyList()))
    }

    @Test
    fun aTagWithABlankHierarchyComponentIsDetectedBeforeAnyWrite() {
        // The backend rewrites a blank component to the literal `blank`, so the stored tag would not
        // be the requested one. That input is refused pre-transaction, not written and then failed.
        assertTrue(NoteContentCanonicalization.tagHasBlankHierarchyComponent("a::::b"))
        assertTrue(NoteContentCanonicalization.tagHasBlankHierarchyComponent("::a"))
        assertTrue(NoteContentCanonicalization.tagHasBlankHierarchyComponent("a::"))
        assertFalse(NoteContentCanonicalization.tagHasBlankHierarchyComponent("a::b"))
        assertFalse(NoteContentCanonicalization.tagHasBlankHierarchyComponent("plain"))
    }

    // ---- verify(): the post-write gate ------------------------------------------------------------

    @Test
    fun aReadHoldingTheWrittenContentMatchesIntent() {
        val sent = listOf(contentStep(fields = listOf("NEW FRONT", "back text")))
        val read = details(fields = listOf("NEW FRONT", "back text"))
        assertEquals(NoteMutationVerification.MatchesIntent, NoteMutationVerifier.verify(sent, read, backend))
    }

    @Test
    fun noReadIsUnverifiableAndNeverApplied() {
        val sent = listOf(contentStep(fields = listOf("NEW FRONT", "back text")))
        val result = NoteMutationVerifier.verify(sent, null, backend)
        assertTrue(result is NoteMutationVerification.Unverifiable)
        assertEquals("post_write_read_unavailable", detailOf(result))
    }

    @Test
    fun nothingSentIsUnverifiable() {
        val result = NoteMutationVerifier.verify(emptyList(), details(), backend)
        assertTrue(result is NoteMutationVerification.Unverifiable)
        assertEquals("nothing_sent", detailOf(result))
    }

    @Test
    fun aReadFromAnotherBackendProvesNothing() {
        val sent = listOf(contentStep(fields = listOf("NEW FRONT", "back text")))
        val foreign = details(fields = listOf("NEW FRONT", "back text"), backendId = otherBackend)
        val result = NoteMutationVerifier.verify(sent, foreign, backend)
        assertTrue(result is NoteMutationVerification.Unverifiable)
        assertEquals("post_write_read_foreign_backend", detailOf(result))
    }

    @Test
    fun aReadWithoutTheFieldListCannotVerifyAFieldWrite() {
        val sent = listOf(contentStep(fields = listOf("NEW FRONT", "back text")))
        val result = NoteMutationVerifier.verify(sent, details(fields = null), backend)
        assertTrue(result is NoteMutationVerification.Unverifiable)
        assertEquals("post_write_fields_unavailable", detailOf(result))
    }

    @Test
    fun aStoredValueThatIsNotTheIntendedValueDiffers() {
        val sent = listOf(contentStep(fields = listOf("NEW FRONT", "back text")))
        val result = NoteMutationVerifier.verify(sent, details(fields = listOf("front text", "back text")), backend)
        assertTrue(result is NoteMutationVerification.DiffersFromIntent)
        assertEquals("field_values", detailOf(result))
    }

    @Test
    fun aStoredFieldCountThatIsNotTheIntendedCountDiffers() {
        val sent = listOf(contentStep(fields = listOf("NEW FRONT", "back text")))
        val result = NoteMutationVerifier.verify(sent, details(fields = listOf("NEW FRONT")), backend)
        assertTrue(result is NoteMutationVerification.DiffersFromIntent)
        assertEquals("field_count", detailOf(result))
    }

    @Test
    fun backendOwnedTagOrderAndCaseStillMatchIntent() {
        // One replace-only write; the backend sorts and adopts registered case, so the stored order
        // and spelling carry no information about whether the write applied.
        val sent = listOf(contentStep(tags = listOf("Zebra", "alpha")))
        val stored = details(tags = listOf("alpha", "zebra"))
        assertEquals(NoteMutationVerification.MatchesIntent, NoteMutationVerifier.verify(sent, stored, backend))
    }

    @Test
    fun aTagSetThatIsNotTheIntendedSetDiffers() {
        val sent = listOf(contentStep(tags = listOf("alpha", "beta")))
        val result = NoteMutationVerifier.verify(sent, details(tags = listOf("alpha")), backend)
        assertTrue(result is NoteMutationVerification.DiffersFromIntent)
        assertEquals("tags", detailOf(result))
    }

    @Test
    fun aReadWithoutTagsCannotVerifyATagWrite() {
        val sent = listOf(contentStep(tags = listOf("alpha")))
        val result = NoteMutationVerifier.verify(sent, details(tags = null), backend)
        assertTrue(result is NoteMutationVerification.Unverifiable)
        assertEquals("post_write_tags_unavailable", detailOf(result))
    }

    @Test
    fun aDeckWriteIsVerifiedAgainstTheCardDeckOnly() {
        val sent = listOf(deckStep("deck-b"))
        assertEquals(
            NoteMutationVerification.MatchesIntent,
            NoteMutationVerifier.verify(sent, details(deckId = "deck-b"), backend)
        )
        val notMoved = NoteMutationVerifier.verify(sent, details(deckId = "deck-a"), backend)
        assertTrue(notMoved is NoteMutationVerification.DiffersFromIntent)
        assertEquals("card_deck", detailOf(notMoved))
        val unreadable = NoteMutationVerifier.verify(sent, details(deckId = null), backend)
        assertTrue(unreadable is NoteMutationVerification.Unverifiable)
        assertEquals("post_write_deck_unavailable", detailOf(unreadable))
    }

    @Test
    fun aTwoStepPlanIsVerifiedAgainstBothWritesAndAPartialOneDiffers() {
        val sent = listOf(
            contentStep(fields = listOf("NEW FRONT", "back text")),
            deckStep("deck-b")
        )
        // Both writes landed.
        assertEquals(
            NoteMutationVerification.MatchesIntent,
            NoteMutationVerifier.verify(sent, details(fields = listOf("NEW FRONT", "back text"), deckId = "deck-b"), backend)
        )
        // Content landed, deck did not: that is partial application, never a full success.
        val partial = NoteMutationVerifier.verify(
            sent,
            details(fields = listOf("NEW FRONT", "back text"), deckId = "deck-a"),
            backend
        )
        assertTrue(partial is NoteMutationVerification.DiffersFromIntent)
        assertEquals("card_deck", detailOf(partial))
    }

    @Test
    fun aContentOnlyWriteIsNotCheckedAgainstTheDeck() {
        val sent = listOf(contentStep(fields = listOf("NEW FRONT", "back text")))
        assertEquals(
            NoteMutationVerification.MatchesIntent,
            NoteMutationVerifier.verify(sent, details(fields = listOf("NEW FRONT", "back text"), deckId = "deck-a"), backend)
        )
    }

    @Test
    fun fieldsWithoutReportedOrdinalsFallBackToReadOrder() {
        // A backend that does not report ordinals still returns fields in template order; the
        // comparison stays positional instead of declaring the read unusable.
        val sent = listOf(contentStep(fields = listOf("NEW FRONT", "back text")))
        val read = details(fields = listOf("NEW FRONT", "back text"), reportOrdinals = false)
        assertEquals(NoteMutationVerification.MatchesIntent, NoteMutationVerifier.verify(sent, read, backend))
    }

    @Test
    fun canonicalizedStorageStillMatchesIntent() {
        // The write asked for a control character and a decomposed accent; the backend stores the
        // stripped, NFC form. That difference is backend-owned, so it is not a failed verification.
        val sent = listOf(contentStep(fields = listOf("caf\u0065\u0301\u0000", "back text")))
        val stored = details(fields = listOf("caf\u00e9", "back text"))
        assertEquals(NoteMutationVerification.MatchesIntent, NoteMutationVerifier.verify(sent, stored, backend))
    }

    // ---- verifyIntent(): read-only evidence for a human -------------------------------------------

    @Test
    fun evidenceMatchesWhenTheCollectionHoldsTheEditedField() {
        val rec = record(changedFields = listOf(ChangedFieldMark(0, "Front")))
        val intent = NoteMutationIntent(fieldValues = mapOf(0 to "NEW FRONT"), tags = null, targetDeckId = null)
        val result = NoteMutationVerifier.verifyIntent(rec, intent, details(fields = listOf("NEW FRONT", "back text")))
        assertEquals(NoteMutationVerification.MatchesIntent, result)
    }

    @Test
    fun evidenceDiffersWhenTheCollectionDoesNotHoldTheEdit() {
        val rec = record(changedFields = listOf(ChangedFieldMark(0, "Front")))
        val intent = NoteMutationIntent(fieldValues = mapOf(0 to "NEW FRONT"), tags = null, targetDeckId = null)
        val result = NoteMutationVerifier.verifyIntent(rec, intent, details(fields = listOf("front text", "back text")))
        assertTrue(result is NoteMutationVerification.DiffersFromIntent)
        assertEquals("field_values", detailOf(result))
    }

    @Test
    fun evidenceWithoutTheRetainedPayloadIsUnverifiableAndNeverAGuess() {
        // After a restart the payload is gone. The honest answer is "cannot verify", not "looks right".
        val rec = record(changedFields = listOf(ChangedFieldMark(0, "Front")))
        val intent = NoteMutationIntent(fieldValues = emptyMap(), tags = null, targetDeckId = null)
        val result = NoteMutationVerifier.verifyIntent(rec, intent, details())
        assertTrue(result is NoteMutationVerification.Unverifiable)
        assertEquals("payload_not_retained", detailOf(result))
    }

    @Test
    fun evidenceWithoutAReadIsUnverifiable() {
        val rec = record(changedFields = listOf(ChangedFieldMark(0, "Front")))
        val intent = NoteMutationIntent(fieldValues = mapOf(0 to "NEW FRONT"), tags = null, targetDeckId = null)
        val result = NoteMutationVerifier.verifyIntent(rec, intent, null)
        assertTrue(result is NoteMutationVerification.Unverifiable)
        assertEquals("post_write_read_unavailable", detailOf(result))
    }

    @Test
    fun evidenceFromAnotherBackendProvesNothing() {
        val rec = record(changedFields = listOf(ChangedFieldMark(0, "Front")))
        val intent = NoteMutationIntent(fieldValues = mapOf(0 to "NEW FRONT"), tags = null, targetDeckId = null)
        val result = NoteMutationVerifier.verifyIntent(
            rec,
            intent,
            details(fields = listOf("NEW FRONT", "back text"), backendId = otherBackend)
        )
        assertTrue(result is NoteMutationVerification.Unverifiable)
        assertEquals("post_write_read_foreign_backend", detailOf(result))
    }

    @Test
    fun evidenceIgnoresTheDeckUntilTheDeckStepWasEntered() {
        val deck = DeckChange(fromDeck = AnkiDeckRef(backend, "deck-a"), toDeck = AnkiDeckRef(backend, "deck-b"))
        val intent = NoteMutationIntent(fieldValues = mapOf(0 to "NEW FRONT"), tags = null, targetDeckId = "deck-b")
        val read = details(fields = listOf("NEW FRONT", "back text"), deckId = "deck-a")

        val rec = record(changedFields = listOf(ChangedFieldMark(0, "Front")), deckChange = deck)
        assertEquals("content step first, deck step second", 2, rec.plan.operations.size)

        // Only the content step was entered: the deck was never dispatched, so it cannot differ.
        val contentOnly = NoteMutationVerifier.verifyIntent(rec.copy(lastEnteredOperation = 0), intent, read)
        assertEquals(NoteMutationVerification.MatchesIntent, contentOnly)

        // The deck step was entered: a card still in the old deck is a real difference.
        val deckEntered = NoteMutationVerifier.verifyIntent(rec.copy(lastEnteredOperation = 1), intent, read)
        assertTrue(deckEntered is NoteMutationVerification.DiffersFromIntent)
        assertEquals("card_deck", detailOf(deckEntered))
    }

    @Test
    fun evidenceForATagChangeComparesTheTagSetOnly() {
        val rec = record(tagsChanged = true)
        val intent = NoteMutationIntent(fieldValues = emptyMap(), tags = listOf("alpha", "beta"), targetDeckId = null)
        assertEquals(
            NoteMutationVerification.MatchesIntent,
            NoteMutationVerifier.verifyIntent(rec, intent, details(tags = listOf("beta", "alpha")))
        )
        val differs = NoteMutationVerifier.verifyIntent(rec, intent, details(tags = listOf("alpha")))
        assertTrue(differs is NoteMutationVerification.DiffersFromIntent)
        assertEquals("tags", detailOf(differs))

        val noPayload = NoteMutationVerifier.verifyIntent(
            rec,
            NoteMutationIntent(fieldValues = emptyMap(), tags = null, targetDeckId = null),
            details()
        )
        assertTrue(noPayload is NoteMutationVerification.Unverifiable)
        assertEquals("payload_not_retained", detailOf(noPayload))
    }

    @Test
    fun evidenceForARecordWithoutChangesIsNotAClaimAboutContent() {
        // Nothing was marked as changed, so there is nothing to compare: this is the read-only
        // "no difference detected" answer, and it is still not proof that a transaction applied.
        val rec = record()
        val intent = NoteMutationIntent(fieldValues = emptyMap(), tags = null, targetDeckId = null)
        assertEquals(NoteMutationVerification.MatchesIntent, NoteMutationVerifier.verifyIntent(rec, intent, details()))
    }

    @Test
    fun evidenceToleratesBackendCanonicalizationExactlyLikeTheWritePathDoes() {
        val rec = record(changedFields = listOf(ChangedFieldMark(0, "Front")), tagsChanged = true)
        val intent = NoteMutationIntent(
            fieldValues = mapOf(0 to "caf\u0065\u0301\u0007"),
            tags = listOf("Zebra"),
            targetDeckId = null
        )
        val stored = details(fields = listOf("caf\u00e9", "back text"), tags = listOf("zebra"))
        assertEquals(NoteMutationVerification.MatchesIntent, NoteMutationVerifier.verifyIntent(rec, intent, stored))
    }
}
