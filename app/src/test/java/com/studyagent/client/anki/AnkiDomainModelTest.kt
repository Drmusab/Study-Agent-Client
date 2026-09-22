package com.studyagent.client.anki

import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class AnkiDomainModelTest {
    private fun invalid(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { block() }
    }

    @Test fun `backend-qualified cards decks and notes never collide`() {
        val local = AnkiBackendId.AnkiDroidLocal
        val pc = AnkiBackendId.PcAgent("desktop")
        assertNotEquals(AnkiCardRef(local, "123"), AnkiCardRef(pc, "123"))
        assertNotEquals(AnkiDeckRef(local, "123"), AnkiDeckRef(pc, "123"))
        assertNotEquals(AnkiNoteRef(local, "123"), AnkiNoteRef(pc, "123"))
        assertNotEquals(AnkiBackendId.PcAgent("one"), AnkiBackendId.PcAgent("two"))
        assertNotEquals(AnkiBackendId.Fake("one"), AnkiBackendId.Fake("two"))
    }

    @Test fun `card equality and stable key include the same complete structural identity`() {
        val ref = card().ref
        assertEquals(ref, ref.copy())
        assertEquals(ref.stableKey, ref.copy().stableKey)
        listOf(ref.copy(collectionKey = "other"), ref.copy(collectionKey = null), ref.copy(cardOrd = 1),
            ref.copy(noteId = "other"), ref.copy(cardId = null)).forEach {
            assertNotEquals(ref, it)
            assertNotEquals(ref.stableKey, it.stableKey)
        }
        assertNotEquals(deck().ref, deck(key = "other").ref)
        assertNotEquals(AnkiNoteRef(fakeId, "1", "a"), AnkiNoteRef(fakeId, "1", "b"))
    }

    @Test fun `note and card are distinct and note plus ordinal is valid without card id`() {
        val note = AnkiNoteRef(fakeId, "note", "collection")
        val first = note.toCardRef(0)
        val second = note.toCardRef(1)
        assertNull(first.cardId)
        assertEquals(note.noteId, first.noteId)
        assertNotEquals(first, second)
        assertNotEquals(note.stableKey, first.stableKey)
    }

    @Test fun `stable keys are delimiter-safe and null is not a sentinel string`() {
        val one = ReviewCommitId(fakeId, "s|t", ReviewTurnId("u"))
        val two = ReviewCommitId(fakeId, "s", ReviewTurnId("t|u"))
        assertNotEquals(one.stableKey, two.stableKey)
        assertNotEquals(AnkiCardRef(fakeId, "1", collectionKey = null).stableKey,
            AnkiCardRef(fakeId, "1", collectionKey = "?").stableKey)
        assertNotEquals(AnkiDeckRef(fakeId, "x|deck:y", "z").stableKey,
            AnkiDeckRef(fakeId, "y", "z|deck:x").stableKey)
    }

    @Test fun `all identity primitives reject blank identifiers`() {
        invalid { AnkiBackendId.PcAgent("") }
        invalid { AnkiBackendId.Fake(" ") }
        invalid { AnkiCollectionIdentity(fakeId, " ") }
        invalid { AnkiDeckRef(fakeId, "") }
        invalid { AnkiDeckRef(fakeId, "d", " ") }
        invalid { AnkiNoteRef(fakeId, " ") }
        invalid { AnkiNoteRef(fakeId, "n", "") }
        invalid { AnkiCardRef(fakeId, "") }
        invalid { AnkiCardRef(fakeId, "c", noteId = " ") }
        invalid { AnkiCardRef(fakeId, "c", collectionKey = "") }
        invalid { ReviewTurnId(" ") }
        invalid { ReviewCommitId(fakeId, "", ReviewTurnId("t")) }
    }

    @Test fun `card requires usable address and valid ordinal`() {
        invalid { AnkiCardRef(fakeId) }
        invalid { AnkiCardRef(fakeId, noteId = "n") }
        invalid { AnkiCardRef(fakeId, noteId = "n", cardOrd = -1) }
        invalid { AnkiCardRef(fakeId, cardId = "c", cardOrd = 0) }
    }

    @Test fun `context rejects mixed backends collections and absent study identity`() {
        val context = context()
        invalid { context.copy(backendId = AnkiBackendId.AnkiDroidLocal) }
        invalid { context.copy(collection = AnkiCollectionIdentity(AnkiBackendId.PcAgent("p"), null)) }
        invalid { context.copy(deckRef = context.deckRef?.copy(collectionKey = "other")) }
        invalid { context.copy(studySessionId = "") }
        invalid { context.copy(startedAtEpochMs = -1) }
        assertNull(context.copy(collection = null, deckRef = null).collection)
    }

    @Test fun `context and turn identity fields have no mutable holders or setters`() {
        listOf(AnkiSessionContext::class.java, AnkiReviewTurn::class.java, AnkiReviewSession::class.java,
            AnkiCardRef::class.java, ReviewTurnId::class.java, ReviewCommitId::class.java).forEach { type ->
            assertTrue(type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
                .all { Modifier.isFinal(it.modifiers) })
            assertFalse(type.methods.any { it.name.startsWith("set") })
        }
        val old = context()
        val new = old.copy(studySessionId = "another")
        assertNotEquals(old, new)
        assertEquals("study-1", old.studySessionId)
    }

    @Test fun `rendered card cannot mix backend or note identity`() {
        val card = card()
        invalid { card.copy(deckRef = deck(AnkiBackendId.AnkiDroidLocal).ref) }
        invalid { card.copy(noteRef = AnkiNoteRef(fakeId, "wrong")) }
        invalid { card.copy(noteRef = card.noteRef?.copy(collectionKey = "other")) }
        invalid { card.copy(deckRef = card.deckRef?.copy(collectionKey = "other")) }
    }

    @Test fun `request validation is local and does not invent scheduler values`() {
        invalid { BeginReviewRequest(context(), 0) }
        invalid { BeginReviewRequest(context(), -1) }
        invalid { AnkiReviewSession(context(), " ") }
        val turn = AnkiReviewTurn(ReviewTurnId("t"), "s", card())
        invalid { turn.copy(studySessionId = " ") }
        invalid { turn.copy(position = 0) }
        invalid { turn.copy(remaining = -1) }
        invalid { turn.request().copy(answerDurationMs = -1) }
        invalid { turn.request().copy(ratedAtEpochMs = -1) }
        invalid { turn.request().copy(card = card(id = AnkiBackendId.AnkiDroidLocal).ref) }
        invalid { CardActionRequest(card(id = AnkiBackendId.AnkiDroidLocal).ref, AnkiReviewSession(context(), "h")) }
        invalid { CardActionRequest(card(key = "other").ref,
            AnkiReviewSession(context().copy(collection = null), "h")) }
    }

    @Test fun `deck hierarchy is display only and unknown counts stay unknown`() {
        val deck = deck()
        assertEquals(listOf("Medicine", "Cardiology", "Arrhythmias"), deck.path)
        assertEquals(deck.ref, deck.copy(name = "Renamed").ref)
        assertNull(deck.counts)
        assertNull(deck.isFiltered)
        assertNull(AnkiDeckCounts(new = 2).totalDue)
        invalid { AnkiDeckCounts(review = -1) }
        invalid { deck.copy(parentRef = deck.ref) }
    }

    @Test fun `rating labels and pure answer remain informational backend content`() {
        val scheduling = AnkiSchedulingInfo(nextReviewTimes = mapOf(Rating.GOOD to "6m"),
            fsrs = AnkiFsrsInfo(stability = 2.0))
        assertEquals("6m", scheduling.nextReviewTimes[Rating.GOOD])
        assertNull(card().pureAnswerText)
        assertEquals("Answer A", card().answerText)
        assertEquals(setOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY), Rating.entries.toSet())
    }

    @Test fun `backend identities and preference serialize independently`() {
        val identities: List<AnkiBackendId> = listOf(AnkiBackendId.AnkiDroidLocal,
            AnkiBackendId.PcAgent("p:| profile العربية"), AnkiBackendId.Fake("fake:1"))
        identities.forEach {
            assertEquals(it, Json.decodeFromString<AnkiBackendId>(Json.encodeToString(it)))
            assertEquals(it, AnkiBackendId.fromStableId(it.stableId))
        }
        AnkiBackendMode.entries.forEach {
            assertEquals(it, Json.decodeFromString<AnkiBackendMode>(Json.encodeToString(it)))
        }
        assertNull(AnkiBackendId.fromStableId("pc_agent:"))
        assertNull(AnkiBackendId.fromStableId("fake: "))
        assertNull(AnkiBackendId.fromStableId("pc_agent")) // cannot invent a profile on restore
    }

    @Test fun `persistable refs and retry keys round trip without rendered content`() {
        val card = card().ref
        val deck = deck().ref
        val note = AnkiNoteRef(fakeId, "note", "collection")
        val collection = AnkiCollectionIdentity(fakeId, null)
        val turn = ReviewTurnId("turn-1")
        val commit = ReviewCommitId(fakeId, "study", turn)
        assertEquals(card, Json.decodeFromString<AnkiCardRef>(Json.encodeToString(card)))
        assertEquals(deck, Json.decodeFromString<AnkiDeckRef>(Json.encodeToString(deck)))
        assertEquals(note, Json.decodeFromString<AnkiNoteRef>(Json.encodeToString(note)))
        assertEquals(collection, Json.decodeFromString<AnkiCollectionIdentity>(Json.encodeToString(collection)))
        assertEquals(turn, Json.decodeFromString<ReviewTurnId>(Json.encodeToString(turn)))
        assertEquals(commit, Json.decodeFromString<ReviewCommitId>(Json.encodeToString(commit)))
        val byNote = AnkiCardRef(fakeId, noteId = "n", cardOrd = 0)
        assertEquals(byNote, Json.decodeFromString<AnkiCardRef>(Json.encodeToString(byNote)))
        assertFalse(Json.encodeToString(card).contains("question"))
    }

    @Test fun `invalid persisted ids are rejected on decoding`() {
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString<ReviewTurnId>("""{"value":""}""")
        }
    }
}
