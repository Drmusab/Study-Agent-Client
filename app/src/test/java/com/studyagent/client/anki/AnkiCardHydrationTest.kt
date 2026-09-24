package com.studyagent.client.anki

import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 07 — the core hydration helpers: identity verification between a scheduled card and a
 * hydrated card, media-reference merging, the deck-move degradation and turn association.
 *
 * These are the pieces every backend's hydration path runs (a future desktop backend included):
 * identity is confirmed one-way, degradations are explicit tokens, and association never forges
 * identity or content (§54/§55/§77-§78, INV-ANKI-CARD-05/19/23).
 */
class AnkiCardHydrationTest {

    private fun full(): AnkiRenderedCard = card("A").copy(
        questionHtml = "<b>Front</b>",
        answerHtml = "Back<br>[sound:a.mp3]",
        questionText = "Front",
        answerText = "Back",
        pureAnswerText = "Back"
    )

    private fun turnOf(card: AnkiRenderedCard, turnId: String = "turn-1"): AnkiReviewTurn = AnkiReviewTurn(
        ReviewTurnId(turnId),
        "study-1",
        AnkiReviewTurnContent.Scheduled(scheduledOf(card)),
        position = 1,
        remaining = null
    )

    @Test fun `identity verification confirms matching claims and tolerates enrichment`() {
        val scheduled = card("A")
        val hydrated = full()
        // Exact match…
        assertTrue(AnkiCardHydration.identityMatches(scheduled.ref, hydrated.ref))
        // …and enrichment: the GATE 06 shape addresses by note + ordinal and the hydrated ref
        // learns the card id (§53/§54). The confirmation is one-way — enrich, never contradict.
        val byNoteAndOrd = scheduled.ref.copy(cardId = null)
        assertTrue(AnkiCardHydration.identityMatches(byNoteAndOrd, hydrated.ref))
    }

    @Test fun `unconfirmed claims pass and contradicted claims never do`() {
        val hydrated = full()
        // An absent claim is unconfirmed, not contradicted (§53): a card-id-only reference
        // (note identity unknown) confirms on the card id alone.
        val byCardIdOnly = AnkiCardRef(fakeId, cardId = "A", noteId = null, cardOrd = null, collectionKey = "collection")
        assertTrue(AnkiCardHydration.identityMatches(byCardIdOnly, hydrated.ref))
        // Any contradiction in any known component refuses (§54/§55, STEP 53).
        assertFalse(AnkiCardHydration.identityMatches(card("A").ref.copy(noteId = "other", cardId = null), hydrated.ref))
        assertFalse(AnkiCardHydration.identityMatches(card("A").ref.copy(cardOrd = 1), hydrated.ref))
        assertFalse(AnkiCardHydration.identityMatches(card("A").ref.copy(cardId = "9"), hydrated.ref))
        // Collection identity is strict equality — unknown is never a wildcard (§13/§47).
        assertFalse(
            AnkiCardHydration.identityMatches(card("A").ref.copy(collectionKey = "other-collection"), hydrated.ref)
        )
        // Backend identity is compared strictly in both directions (§55).
        assertFalse(AnkiCardHydration.identityMatches(card("A", id = AnkiBackendId.PcAgent("p")).ref, hydrated.ref))
    }

    @Test fun `media merge preserves order keeps references unresolved and dedupes by kind`() {
        val scheduledMedia = listOf(
            AnkiMediaRef.BackendStream("a.mp3"),
            AnkiMediaRef.RemoteUrl("https://example.invalid/u.png")
        )
        val hydratedMedia = listOf(
            AnkiMediaRef.BackendStream("a.mp3"),
            AnkiMediaRef.Unavailable("gone.wav")
        )
        // First-seen order, scheduled side first, duplicates dropped by kind + name (§77/§78) —
        // nothing is fetched, opened or resolved (GATE 09's job).
        assertEquals(
            listOf(
                AnkiMediaRef.BackendStream("a.mp3"),
                AnkiMediaRef.RemoteUrl("https://example.invalid/u.png"),
                AnkiMediaRef.Unavailable("gone.wav")
            ),
            AnkiCardHydration.mergeMedia(scheduledMedia, hydratedMedia)
        )
        // An empty side is simply "no references", never synthesized (§53: absence ≠ empty ≠ error).
        assertEquals(scheduledMedia, AnkiCardHydration.mergeMedia(scheduledMedia, emptyList()))
        assertEquals(hydratedMedia, AnkiCardHydration.mergeMedia(emptyList(), hydratedMedia))
    }

    @Test fun `compose keeps the card intact and reports the deck move as a degradation`() {
        val scheduledDeck = deck(id = fakeId, key = "collection").ref
        val scheduled = scheduledOf(full())
        val original = AnkiDeckRef(fakeId, "deck-1", "collection")

        // Same deck — no token (§77/§78).
        val same = AnkiCardHydration.compose(scheduled, full()) as AnkiResult.Success
        assertEquals(emptyList<String>(), same.value.degradations)

        // Moved to another deck — token — and the card's own deck fact is never rewritten (§78/§85).
        val other = AnkiDeckRef(fakeId, "deck-2", "collection")
        val moved = full().copy(deckRef = other, metadata = AnkiCardMetadata(originalDeckRef = original))
        val degraded = AnkiCardHydration.compose(scheduled, moved) as AnkiResult.Success
        assertEquals(listOf(AnkiCardHydration.DECK_MOVED), degraded.value.degradations)
        assertEquals(other, degraded.value.deckRef)

        // A filtered-deck home explains the move: the scheduled deck IS the card's home deck
        // (INV-ANKI-CARD-25) — no token.
        val explained = full().copy(deckRef = other, metadata = AnkiCardMetadata(originalDeckRef = scheduledDeck))
        val filtered = AnkiCardHydration.compose(scheduled, explained) as AnkiResult.Success
        assertEquals(emptyList<String>(), filtered.value.degradations)
    }

    @Test fun `compose refuses unconfirmed identity instead of attaching the wrong card`() {
        val scheduled = scheduledOf(full())
        val wrongNote = full().copy(
            ref = full().ref.copy(noteId = "note-B"),
            noteRef = AnkiNoteRef(fakeId, "note-B", "collection")
        )
        val result = AnkiCardHydration.compose(scheduled, wrongNote)
        val failure = result as AnkiResult.Failure
        assertTrue(failure.error is AnkiError.StaleCardReference)
    }

    @Test fun `attach keeps the turn identity and the scheduled scheduler metadata`() {
        val fixtureCard = full()
        val turn = turnOf(fixtureCard)
        val hydratedTurn = (AnkiCardHydration.attach(turn, fixtureCard) as AnkiResult.Success).value
        // INV-ANKI-CARD-22/23 — hydration enriches, never replaces the turn's identity or the
        // scheduler's own metadata (rating options / next-review labels survive).
        assertEquals(turn.turnId, hydratedTurn.turnId)
        assertEquals(turn.scheduledCard, hydratedTurn.scheduledCard)
        assertEquals(AnkiReviewTurnContent.Rendered(fixtureCard, turn.scheduledCard), hydratedTurn.content)
        assertEquals(fixtureCard, hydratedTurn.renderedCard)

        // Re-attachment of current content is allowed and idempotent (STEP 45/§50).
        val updated = fixtureCard.copy(answerText = "edited")
        val reattached = (AnkiCardHydration.attach(hydratedTurn, updated) as AnkiResult.Success).value
        assertEquals(turn.turnId, reattached.turnId)
        assertEquals("edited", reattached.renderedCard?.answerText)

        // A different card cannot land on the turn (INV-ANKI-CARD-30).
        val stranger = fixtureCard.copy(
            ref = fixtureCard.ref.copy(cardId = "9", noteId = "note-B"),
            noteRef = AnkiNoteRef(fakeId, "note-B", "collection")
        )
        assertTrue(AnkiCardHydration.attach(turn, stranger) is AnkiResult.Failure)
    }

    @Test fun `a rendered card without any question channel is not representable`() {
        // §16/§45 — a blank card is worse than a typed failure; the mapper reports a typed
        // malformed-card failure instead of ever constructing this object.
        var threw = false
        try {
            AnkiRenderedCard(
                ref = card("A").ref,
                questionHtml = null,
                answerHtml = null,
                questionText = null,
                answerText = null,
                pureAnswerText = null // required parameter (pre-existing compile error fixed in GATE 11)
            )
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test fun `empty and missing answers are distinct and neither is invented`() {
        val empty = full().copy(answerHtml = "", answerText = "", pureAnswerText = "")
        assertEquals("", empty.answerHtml)
        assertTrue(empty.hasVisualAnswer)
        assertTrue(empty.hasSpeechAnswer)
        assertEquals("", empty.evaluationAnswerText)
        val missing = full().copy(answerHtml = null, answerText = null, pureAnswerText = null)
        assertNull(missing.answerHtml)
        assertFalse(missing.hasVisualAnswer)
        assertFalse(missing.hasSpeechAnswer)
        assertFalse(missing.hasEvaluationAnswer)
        assertNull(missing.evaluationAnswerText)
    }

    @Test fun `visual only cards keep their degradation and never borrow html as speech`() {
        val visual = full().copy(
            questionText = null,
            answerText = null,
            pureAnswerText = null,
            degradations = listOf("card_speech_text_unavailable")
        )
        assertTrue(visual.hasVisualQuestion)
        assertFalse(visual.hasSpeechQuestion)
        assertFalse(visual.hasEvaluationAnswer)
        // Evaluation falls back to answerText only — never to raw HTML (§36/§38).
        assertNull(visual.evaluationAnswerText)
        // The documented fallback is pure first, clean text second.
        assertEquals("Back", full().copy(pureAnswerText = null).evaluationAnswerText)
    }

    @Test fun `flag codes map exactly and unknown codes are never claimed as none`() {
        assertEquals(AnkiFlag.NONE, AnkiFlag.fromBackendCode(0))
        assertEquals(AnkiFlag.RED, AnkiFlag.fromBackendCode(1))
        assertEquals(AnkiFlag.PURPLE, AnkiFlag.fromBackendCode(7))
        assertNull(AnkiFlag.fromBackendCode(null))
        assertEquals(AnkiFlag.UNKNOWN, AnkiFlag.fromBackendCode(8))
        assertEquals(AnkiFlag.UNKNOWN, AnkiFlag.fromBackendCode(-1))
    }

    @Test fun `turn request timing uses the turn scope and never the scheduler`() {
        val fixtureCard = full().copy(
            scheduling = AnkiSchedulingInfo(nextReviewTimes = mapOf(Rating.GOOD to "4d"))
        )
        val turn = turnOf(fixtureCard)
        val request = turn.request(Rating.GOOD)
        assertEquals(turn.turnId, request.commitId.turnId)
        assertEquals(fixtureCard.ref, request.card)
        assertEquals(Rating.GOOD, request.rating)
    }
}
