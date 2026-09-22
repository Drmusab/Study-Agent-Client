package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiMediaRef
import com.studyagent.client.core.anki.AnkiRatingOptions
import com.studyagent.client.core.models.Rating
import com.studyagent.client.data.anki.ankidroid.AnkiDroidReviewMapper
import com.studyagent.client.data.anki.ankidroid.AnkiDroidReviewRowOutcome
import com.studyagent.client.data.anki.ankidroid.AnkiDroidReviewRowProblem
import com.studyagent.client.data.anki.ankidroid.InMemoryProviderRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 06 §100 — the review-info contract fixtures.
 *
 * Every fixture below is a *shape a provider can actually send*, including the ones this build
 * hopes never to see: a two-button card, a label array whose length disagrees with the button
 * count, media that is not JSON, and a row with no usable identity. The point of the suite is the
 * rule in §51 — a broken *identity* fails the turn, broken *metadata* degrades and the card
 * survives — so the assertions are about which of the two happened, not merely that nothing threw.
 */
class AnkiDroidReviewMapperTest {

    private val deckRef = AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "1700000000000")

    private fun row(values: Map<String, Any?>) = InMemoryProviderRow(values)

    private fun complete(
        noteId: Any? = 1700000000123L,
        ord: Any? = 0,
        buttons: Any? = 4,
        times: Any? = "[\"1m\",\"6m\",\"1d\",\"4d\"]",
        media: Any? = "[]"
    ) = row(
        buildMap {
            if (noteId != ABSENT) put("note_id", noteId)
            if (ord != ABSENT) put("ord", ord)
            if (buttons != ABSENT) put("button_count", buttons)
            if (times != ABSENT) put("next_review_times", times)
            if (media != ABSENT) put("media_files", media)
        }
    )

    private fun mapped(outcome: AnkiDroidReviewRowOutcome) =
        (outcome as AnkiDroidReviewRowOutcome.Valid).card

    // ---------------------------------------------------------------- identity

    @Test
    fun `normal four button card maps identity ratings and intervals`() {
        val card = mapped(AnkiDroidReviewMapper.mapScheduledRow(complete(), deckRef))

        // The provider addresses a card by note + ordinal and exposes no card id, so that is
        // exactly what is stored — nothing is synthesized (§17/§18).
        assertEquals("1700000000123", card.ref.noteId)
        assertEquals(0, card.ref.cardOrd)
        assertNull(card.ref.cardId)
        assertEquals(AnkiBackendId.AnkiDroidLocal, card.ref.backendId)
        assertEquals(deckRef, card.deckRef)
        assertEquals("1700000000123", card.noteRef?.noteId)

        // Index order is the scheduler's own: again, hard, good, easy (verified against the
        // pinned proto enum), never "the first four ratings in some order" (§22).
        assertEquals(
            listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY),
            (card.ratingOptions as AnkiRatingOptions.Known).ratings
        )
        assertEquals("1m", card.scheduling?.nextReviewTimes?.get(Rating.AGAIN))
        assertEquals("6m", card.scheduling?.nextReviewTimes?.get(Rating.HARD))
        assertEquals("1d", card.scheduling?.nextReviewTimes?.get(Rating.GOOD))
        assertEquals("4d", card.scheduling?.nextReviewTimes?.get(Rating.EASY))
        assertTrue(card.degradations.isEmpty())
    }

    @Test
    fun `identity is text-parsed so garbage never becomes a plausible ordinal`() {
        // A typed getter would turn "x" into 0, which is a *real* ordinal. Reading as text and
        // parsing means a bad cell kills the row instead of inventing card 0 (§43).
        for (bad in listOf(0L, -5L, "0", "-5", "abc", "  ", "")) {
            val outcome = AnkiDroidReviewMapper.mapScheduledRow(complete(noteId = bad), deckRef)
            assertEquals(
                "note id '$bad' must not become a card",
                AnkiDroidReviewRowProblem.NOTE_ID_INVALID,
                (outcome as AnkiDroidReviewRowOutcome.Malformed).problem
            )
        }
        for (bad in listOf(-1, "-1", "abc", "")) {
            val outcome = AnkiDroidReviewMapper.mapScheduledRow(complete(ord = bad), deckRef)
            assertEquals(
                AnkiDroidReviewRowProblem.CARD_ORD_INVALID,
                (outcome as AnkiDroidReviewRowOutcome.Malformed).problem
            )
        }
    }

    @Test
    fun `a missing identity column is a structural contract mismatch`() {
        val outcome = AnkiDroidReviewMapper.mapScheduledRow(complete(noteId = ABSENT), deckRef)
        assertEquals(
            AnkiDroidReviewRowProblem.NOTE_ID_COLUMN_MISSING,
            (outcome as AnkiDroidReviewRowOutcome.Malformed).problem
        )
        assertTrue(AnkiDroidReviewRowProblem.NOTE_ID_COLUMN_MISSING.structural)

        val ord = AnkiDroidReviewMapper.mapScheduledRow(complete(ord = ABSENT), deckRef)
        assertEquals(
            AnkiDroidReviewRowProblem.CARD_ORD_COLUMN_MISSING,
            (ord as AnkiDroidReviewRowOutcome.Malformed).problem
        )
        assertTrue(AnkiDroidReviewRowProblem.CARD_ORD_COLUMN_MISSING.structural)
    }

    @Test
    fun `the ordinal alone is never treated as unique`() {
        val first = mapped(AnkiDroidReviewMapper.mapScheduledRow(complete(noteId = 1L, ord = 0), deckRef))
        val second = mapped(AnkiDroidReviewMapper.mapScheduledRow(complete(noteId = 2L, ord = 0), deckRef))
        assertEquals(first.ref.cardOrd, second.ref.cardOrd)
        assertTrue("note + ord is the identity, not ord", first.ref != second.ref)
        assertTrue(first.ref.stableKey != second.ref.stableKey)
    }

    // ---------------------------------------------------------------- rating options

    @Test
    fun `any button count this build cannot name is unmapped rather than guessed`() {
        // The endpoint documents 2..4. Only the four-button ordering has been verified, so a 2 or
        // 3 must become "unmapped" — picking [again, hard] out of a bare 2 would invent a
        // scheduling mapping the API never promised (§20-§22).
        for (count in listOf(1, 2, 3, 5, 8)) {
            val card = mapped(AnkiDroidReviewMapper.mapScheduledRow(complete(buttons = count), deckRef))
            assertEquals(AnkiRatingOptions.Unmapped(count), card.ratingOptions)
            assertTrue(
                "count $count must be recorded as a degradation",
                AnkiDroidReviewMapper.DEGRADATION_BUTTON_COUNT_UNMAPPED in card.degradations
            )
            // Labels are keyed by rating, so an unmapped button set cannot claim any of them.
            assertTrue("unmapped buttons must not be associated with labels", card.scheduling?.nextReviewTimes.isNullOrEmpty())
        }
    }

    @Test
    fun `impossible or absurd button counts are refused not rendered`() {
        for (bad in listOf(-1, "-1", 9, 100, "abc")) {
            val outcome = AnkiDroidReviewMapper.mapScheduledRow(complete(buttons = bad), deckRef)
            assertEquals(
                "button count '$bad' is not a real answer",
                AnkiDroidReviewRowProblem.BUTTON_COUNT_INVALID,
                (outcome as AnkiDroidReviewRowOutcome.Malformed).problem
            )
        }
    }

    @Test
    fun `a missing button count degrades the card instead of losing it`() {
        val card = mapped(AnkiDroidReviewMapper.mapScheduledRow(complete(buttons = ABSENT), deckRef))
        assertEquals(AnkiRatingOptions.Unmapped(0), card.ratingOptions)
        assertEquals("1700000000123", card.ref.noteId)
    }

    // ---------------------------------------------------------------- interval metadata

    @Test
    fun `interval labels that disagree with the button count are dropped not fatal`() {
        // §53 policy, chosen and asserted: the buttons a user may press are what the UI keys on;
        // a display-string mismatch must not take away a card Anki already scheduled.
        val card = mapped(
            AnkiDroidReviewMapper.mapScheduledRow(complete(times = "[\"1m\",\"4d\"]"), deckRef)
        )
        assertEquals(
            listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY),
            (card.ratingOptions as AnkiRatingOptions.Known).ratings
        )
        assertTrue(card.scheduling?.nextReviewTimes.isNullOrEmpty())
        assertTrue(AnkiDroidReviewMapper.DEGRADATION_INTERVALS_ARITY_MISMATCH in card.degradations)
    }

    @Test
    fun `malformed interval metadata leaves the card usable`() {
        for (bad in listOf("not json", "[1,2", "{\"a\":1}")) {
            val card = mapped(AnkiDroidReviewMapper.mapScheduledRow(complete(times = bad), deckRef))
            assertNull(card.scheduling)
            assertTrue(AnkiDroidReviewMapper.DEGRADATION_INTERVALS_UNPARSEABLE in card.degradations)
            assertEquals("1700000000123", card.ref.noteId)
        }
    }

    @Test
    fun `an absent interval column is not a degradation when it was not requested`() {
        val card = mapped(AnkiDroidReviewMapper.mapScheduledRow(complete(times = ABSENT), deckRef))
        assertTrue(card.degradations.none { it.contains("next_review_times") })
    }

    @Test
    fun `interval labels stay presentation data and are never parsed into numbers`() {
        val card = mapped(
            AnkiDroidReviewMapper.mapScheduledRow(
                complete(times = "[\"soon\",\"<1m again\",\"3.5d\",\"\"]"), deckRef
            )
        )
        // Whatever the backend renders is preserved verbatim — including forms this build has no
        // business understanding (§23/INV-ANKI-REV-10).
        assertEquals("soon", card.scheduling?.nextReviewTimes?.get(Rating.AGAIN))
        assertEquals("<1m again", card.scheduling?.nextReviewTimes?.get(Rating.HARD))
        assertEquals("3.5d", card.scheduling?.nextReviewTimes?.get(Rating.GOOD))
        assertEquals("", card.scheduling?.nextReviewTimes?.get(Rating.EASY))
    }

    // ---------------------------------------------------------------- media

    @Test
    fun `no media and multiple media both map`() {
        val none = mapped(AnkiDroidReviewMapper.mapScheduledRow(complete(media = "[]"), deckRef))
        assertTrue(none.media.isEmpty())

        val many = mapped(
            AnkiDroidReviewMapper.mapScheduledRow(
                complete(media = "[\"front.png\",\"sound.mp3\",\"diagram.svg\"]"), deckRef
            )
        )
        assertEquals(
            listOf("front.png", "sound.mp3", "diagram.svg"),
            many.media.map { (it as AnkiMediaRef.BackendStream).streamId }
        )
        assertTrue(many.degradations.isEmpty())
    }

    @Test
    fun `malformed media degrades to no media and never destroys the card`() {
        // §54: media metadata is the least trustworthy input and the least important to the turn.
        for (bad in listOf("oops", "[\"a", "{media:1}")) {
            val card = mapped(AnkiDroidReviewMapper.mapScheduledRow(complete(media = bad), deckRef))
            assertTrue(card.media.isEmpty())
            assertTrue(AnkiDroidReviewMapper.DEGRADATION_MEDIA_UNPARSEABLE in card.degradations)
            assertEquals("1700000000123", card.ref.noteId)
        }
    }

    @Test
    fun `blank media names are skipped rather than turned into unusable references`() {
        val card = mapped(
            AnkiDroidReviewMapper.mapScheduledRow(complete(media = "[\"\",null,\"  \",\"ok.png\"]"), deckRef)
        )
        assertEquals(
            listOf("ok.png"),
            card.media.map { (it as AnkiMediaRef.BackendStream).streamId }
        )
    }

    // ---------------------------------------------------------------- collections

    @Test
    fun `collection identity is carried through when the deck ref has one`() {
        val collectionDeck = deckRef.copy(collectionKey = "default")
        val card = mapped(AnkiDroidReviewMapper.mapScheduledRow(complete(), collectionDeck))
        assertEquals("default", card.ref.collectionKey)
        assertEquals("default", card.noteRef?.collectionKey)
        assertEquals("default", card.deckRef.collectionKey)
    }

    private companion object {
        /** Sentinel for "this column is absent from the projection/row". */
        val ABSENT = Any()
    }
}
