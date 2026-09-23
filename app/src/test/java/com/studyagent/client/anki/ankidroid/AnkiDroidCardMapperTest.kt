package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardQueueState
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiRenderedCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 07 — `provider row → AnkiRenderedCard` mapping (STEP 15-§16/§72-§76, INV-ANKI-CARD-15/28).
 *
 * Strict identity, lenient content and metadata, verbatim representations (no HTML stripping, no
 * Unicode normalization, no template expansion), empty ≠ missing, and degradation tokens instead
 * of invented values. Runs on the JVM against [InMemoryProviderRow] — the same view the Android
 * cursor adapter produces.
 */
class AnkiDroidCardMapperTest {

    private val request = AnkiCardRef(
        backendId = AnkiBackendId.AnkiDroidLocal,
        cardId = "42",
        noteId = "7",
        cardOrd = 0,
        collectionKey = "collection"
    )

    private fun fullRow(): Map<String, Any?> = linkedMapOf(
        "_id" to 42L,
        "note_id" to 7L,
        "ord" to 0,
        "card_name" to "Card 1",
        "deck_id" to 1L,
        "original_deck_id" to 0L,
        "question" to "<b>ما هو القلب؟</b>",
        "answer" to "<b>ما هو القلب؟</b><hr id=answer>عضلة",
        "question_simple" to "ما هو القلب؟",
        "answer_simple" to "ما هو القلب؟\n\n<hr id=answer>عضلة",
        "answer_pure" to "عضلة",
        "reps" to 12,
        "lapses" to 2,
        "interval" to 21,
        "type" to 2,
        "queue" to 2,
        "fsrs_stability" to 12.5,
        "fsrs_difficulty" to 4.75,
        "fsrs_desired_retention" to 0.9,
        "last_review_time_secs" to 1_700_000_123L
    )

    private fun map(row: Map<String, Any?>): AnkiDroidCardRowOutcome =
        AnkiDroidCardMapper.mapCardRow(InMemoryProviderRow(row), request)

    private fun valid(row: Map<String, Any?>): AnkiRenderedCard =
        (map(row) as AnkiDroidCardRowOutcome.Valid).card

    @Test fun `a full card row maps every channel verbatim`() {
        val card = valid(fullRow())
        // Identity is parsed to canonical decimal form (STEP 15).
        assertEquals("42", card.ref.cardId)
        assertEquals("7", card.ref.noteId)
        assertEquals(0, card.ref.cardOrd)
        assertEquals("collection", card.ref.collectionKey)
        // Content channels are byte-for-byte what the provider sent — RTL Arabic preserved,
        // HTML untouched (INV-ANKI-CARD-08/28).
        assertEquals("<b>ما هو القلب؟</b>", card.questionHtml)
        assertEquals("<b>ما هو القلب؟</b><hr id=answer>عضلة", card.answerHtml)
        assertEquals("ما هو القلب؟", card.questionText)
        assertEquals("ما هو القلب؟\n\n<hr id=answer>عضلة", card.answerText)
        assertEquals("عضلة", card.pureAnswerText)
        assertEquals("عضلة", card.evaluationAnswerText)
        // Optional metadata all landed.
        assertEquals("Card 1", card.metadata.templateName)
        assertEquals(AnkiCardQueueState.REVIEW, card.metadata.queueState)
        assertEquals("1", card.deckRef?.deckId)
        // `0` is "not in a filtered deck" — never a fake home deck (STEP 30).
        assertNull(card.metadata.originalDeckRef)
        // No flags column exists in the pinned contract — the flag is absent, never NONE
        // (STEP 31).
        assertNull(card.flag)
        // Stored scheduling facts (STEP 34) — separate home from GATE 06's labels (INV-24).
        assertEquals(12, card.scheduling?.reps)
        assertEquals(2, card.scheduling?.lapses)
        assertEquals(21, card.scheduling?.intervalDays)
        assertEquals(1_700_000_123L, card.scheduling?.lastReviewEpochSeconds)
        assertEquals(12.5, card.scheduling?.fsrs?.stability)
        assertEquals(0.9, card.scheduling?.fsrs?.desiredRetention)
        // Nothing degraded on a full row.
        assertEquals(emptyList<String>(), card.degradations)
    }

    @Test fun `identity is strict and never invented`() {
        // No identity column at all: the projection and the contract disagree — structural.
        val bare = map(mapOf("question" to "q")) as AnkiDroidCardRowOutcome.Malformed
        assertEquals(AnkiDroidCardRowProblem.IDENTITY_COLUMN_MISSING, bare.problem)

        // Garbage identity that leaves no addressing path is unreadable, never a surrogate id.
        val garbage = map(mapOf("_id" to "x", "note_id" to "y", "ord" to "z", "question" to "q"))
        assertEquals(AnkiDroidCardRowProblem.IDENTITY_UNREADABLE, (garbage as AnkiDroidCardRowOutcome.Malformed).problem)

        // An unreadable card id degrades to null (note + ord still address the card) + token.
        val partial = valid(mapOf("note_id" to 7L, "ord" to 1, "_id" to "garbage", "question" to "q"))
        assertNull(partial.ref.cardId)
        assertEquals("7", partial.ref.noteId)
        assertEquals(1, partial.ref.cardOrd)
        assertTrue(AnkiDroidCardMapper.DEG_CARD_ID_UNREADABLE in partial.degradations)

        // An ordinal without its note is not a path either (AnkiCardRef) — unreadable.
        val orphanOrd = map(mapOf("note_id" to "garbage", "ord" to 1, "_id" to "gone", "question" to "q"))
        assertTrue(orphanOrd is AnkiDroidCardRowOutcome.Malformed)

        // Ids parse from transported text ("3"/3.0) and reject zero/negative/blank (INV-04).
        assertEquals("1000", AnkiDroidCardMapper.parsePositiveLong("1000"))
        assertEquals("3", AnkiDroidCardMapper.parsePositiveLong("3.0"))
        assertEquals("3", AnkiDroidCardMapper.parsePositiveLong(" 3 "))
        assertNull(AnkiDroidCardMapper.parsePositiveLong("0"))
        assertNull(AnkiDroidCardMapper.parsePositiveLong("-5"))
        assertNull(AnkiDroidCardMapper.parsePositiveLong(""))
        assertNull(AnkiDroidCardMapper.parsePositiveLong(null))
    }

    @Test fun `a row without any question representation is malformed, never blank`() {
        val answerOnly = map(mapOf("note_id" to 7L, "ord" to 0, "answer" to "only an answer"))
        assertEquals(
            AnkiDroidCardRowProblem.QUESTION_CONTENT_MISSING,
            (answerOnly as AnkiDroidCardRowOutcome.Malformed).problem
        )
        // An empty question string is a (degenerate) rendering the provider supplied — present.
        // It is not the same as both channels being absent.
        val emptyQuestion = valid(mapOf("note_id" to 7L, "ord" to 0, "question" to ""))
        assertEquals("", emptyQuestion.questionHtml)
    }

    @Test fun `html without simple text becomes visual only with a degradation`() {
        val card = valid(mapOf("note_id" to 7L, "ord" to 0, "question" to "<i>q</i>", "answer" to "<i>a</i>"))
        assertEquals("<i>q</i>", card.questionHtml)
        assertNull(card.questionText)
        assertNull(card.answerText)
        // No HTML-stripping fallback: speech is unavailable, evaluation has nothing but HTML —
        // and HTML is never used as an AI reference (STEP 76/§36).
        assertNull(card.evaluationAnswerText)
        assertFalse(card.hasSpeechQuestion)
        assertEquals(
            listOf(AnkiDroidCardMapper.DEG_SPEECH_TEXT_UNAVAILABLE),
            card.degradations.filter { it == AnkiDroidCardMapper.DEG_SPEECH_TEXT_UNAVAILABLE }
        )
    }

    @Test fun `empty and missing answers stay distinct and answers never gate validity`() {
        // Absent answer → all answer channels null (STEP 75) and the card is still valid.
        val absent = valid(mapOf("note_id" to 7L, "ord" to 0, "question" to "q"))
        assertNull(absent.answerHtml)
        assertNull(absent.answerText)
        assertFalse(absent.hasEvaluationAnswer)
        // Empty answer rendering → present-but-empty, never conflated with missing (INV-13).
        val empty = valid(mapOf("note_id" to 7L, "ord" to 0, "question" to "q", "answer" to "", "answer_simple" to ""))
        assertEquals("", empty.answerHtml)
        assertEquals("", empty.answerText)
        assertTrue(empty.hasEvaluationAnswer)
    }

    @Test fun `queue and type states map to names and unknown codes stay unknown`() {
        val cases = listOf(
            -3 to AnkiCardQueueState.BURIED,
            -2 to AnkiCardQueueState.BURIED,
            -1 to AnkiCardQueueState.SUSPENDED,
            0 to AnkiCardQueueState.NEW,
            1 to AnkiCardQueueState.LEARNING,
            2 to AnkiCardQueueState.REVIEW,
            3 to AnkiCardQueueState.RELEARNING
        )
        for ((code, expected) in cases) {
            val card = valid(mapOf("note_id" to 7L, "ord" to 0, "question" to "q", "queue" to code))
            assertEquals("queue code $code", expected, card.metadata.queueState)
        }
        // The documented preview code has no domain name in this build — UNKNOWN + token,
        // never guessed into a known state (test N).
        val preview = valid(mapOf("note_id" to 7L, "ord" to 0, "question" to "q", "queue" to 4))
        assertEquals(AnkiCardQueueState.UNKNOWN, preview.metadata.queueState)
        assertTrue(AnkiDroidCardMapper.DEG_QUEUE_STATE_UNMAPPED in preview.degradations)

        // Type fallback when the queue is absent/unreadable; unknown type also degrades.
        val newByType = valid(mapOf("note_id" to 7L, "ord" to 0, "question" to "q", "type" to 0))
        assertEquals(AnkiCardQueueState.NEW, newByType.metadata.queueState)
        val badType = valid(mapOf("note_id" to 7L, "ord" to 0, "question" to "q", "type" to 9))
        assertEquals(AnkiCardQueueState.UNKNOWN, badType.metadata.queueState)
        assertTrue(AnkiDroidCardMapper.DEG_QUEUE_STATE_UNMAPPED in badType.degradations)
        // Neither state source: UNKNOWN with the token, never fabricated (STEP 33).
        val none = valid(mapOf("note_id" to 7L, "ord" to 0, "question" to "q"))
        assertEquals(AnkiCardQueueState.UNKNOWN, none.metadata.queueState)
        assertTrue(AnkiDroidCardMapper.DEG_QUEUE_STATE_UNMAPPED in none.degradations)
    }

    @Test fun `optional metadata degrades individually and never takes the card down`() {
        val card = valid(
            mapOf(
                "note_id" to 7L,
                "ord" to 0,
                "question" to "q",
                "deck_id" to "not-a-deck",
                "card_name" to "",
                "reps" to "garbage",
                "interval" to -3
            )
        )
        assertNull(card.deckRef)
        assertTrue(AnkiDroidCardMapper.DEG_DECK_ID_UNREADABLE in card.degradations)
        assertNull(card.metadata.templateName) // empty name ≠ name
        assertNull(card.scheduling) // nothing readable → null, not an empty-filled shell
        // The card itself still stands.
        assertEquals("q", card.questionHtml)
    }

    @Test fun `a filtered deck home survives as original deck ownership`() {
        val card = valid(mapOf("note_id" to 7L, "ord" to 0, "question" to "q", "original_deck_id" to 99L))
        assertEquals("99", card.metadata.originalDeckRef?.deckId)
    }

    @Test fun `parse helpers accept transported numeric text and reject garbage`() {
        assertEquals(0, AnkiDroidCardMapper.parseNonNegativeInt("0"))
        assertEquals(3, AnkiDroidCardMapper.parseNonNegativeInt("3.0"))
        assertNull(AnkiDroidCardMapper.parseNonNegativeInt("-1"))
        assertNull(AnkiDroidCardMapper.parseNonNegativeInt("3.5"))
        assertNull(AnkiDroidCardMapper.parseNonNegativeInt("x"))
    }
}
