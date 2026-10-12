package com.studyagent.client.anki

import com.studyagent.client.core.anki.*
import com.studyagent.client.data.anki.ankidroid.AnkiDroidDeckMapper
import com.studyagent.client.data.anki.ankidroid.AnkiDroidDeckRowOutcome
import com.studyagent.client.data.anki.ankidroid.InMemoryProviderRow
import com.studyagent.client.ui.navigation.Screen
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 21C PART III — backend-qualified resource identity (VER-21C-04 … VER-21C-08, VER-21C-11 …
 * VER-21C-13, VER-21C-17, VER-21C-18).
 *
 * The rule is DECISION-21B-04: a backend-local numeric id means nothing outside its own configured
 * backend and collection context. Two backends that happen to use the same numbers describe
 * different logical resources, and no translation table exists — or may be added — to make them
 * interchangeable.
 */
class Gate21cResourceQualificationTest {

    private val backendA = AnkiBackendId.PcAgent("pc-a")
    private val backendB = AnkiBackendId.PcAgent("pc-b")
    private val json = Json

    // --------------------------------------------------------- VER-21C-04 / 05 / 06 / 07

    @Test
    fun `identical card ids on two backends are different logical cards`() {
        val a = AnkiCardRef(backendA, cardId = "123")
        val b = AnkiCardRef(backendB, cardId = "123")

        // The *number* is the same; the *resource* is not.
        assertEquals(a.cardId, b.cardId)
        assertNotEquals(a, b)
        assertNotEquals(a.stableKey, b.stableKey)
        assertEquals(2, setOf(a, b).size)

        // Same identity ⇒ same hash, and the set deduplicates it.
        assertEquals(a.hashCode(), AnkiCardRef(backendA, cardId = "123").hashCode())
        assertEquals(1, setOf(a, AnkiCardRef(backendA, cardId = "123")).size)
    }

    @Test
    fun `identical note ids on two backends are different logical notes`() {
        val a = AnkiNoteRef(backendA, "456")
        val b = AnkiNoteRef(backendB, "456")
        assertEquals(a.noteId, b.noteId)
        assertNotEquals(a, b)
        assertNotEquals(a.stableKey, b.stableKey)

        // A card is not a note: two cards of one note share the note, not the card identity.
        val first = a.toCardRef(cardOrd = 0)
        val second = a.toCardRef(cardOrd = 1)
        assertNotEquals(first, second)
        assertEquals(first.noteId, second.noteId)
    }

    @Test
    fun `identical deck ids on two backends are different logical decks`() {
        val a = AnkiDeckRef(backendA, "10")
        val b = AnkiDeckRef(backendB, "10")
        assertEquals(a.deckId, b.deckId)
        assertNotEquals(a, b)
        assertNotEquals(a.stableKey, b.stableKey)
    }

    @Test
    fun `identical model and template ids on two backends stay distinct`() {
        val modelA = AnkiNoteModelRef(backendA, "5")
        val modelB = AnkiNoteModelRef(backendB, "5")
        assertNotEquals(modelA, modelB)
        assertNotEquals(modelA.stableKey, modelB.stableKey)

        val templateA = AnkiCardTemplateRef(modelA, ordinal = 0)
        val templateB = AnkiCardTemplateRef(modelB, ordinal = 0)
        assertNotEquals(templateA, templateB)
        assertNotEquals(templateA.stableKey, templateB.stableKey)
        assertEquals(backendA, templateA.backendId)

        // A template ordinal is scoped to its own model and backend, never to the id alone.
        assertNotEquals(templateA, AnkiCardTemplateRef(AnkiNoteModelRef(backendA, "6"), ordinal = 0))
        assertNotEquals(templateA, AnkiCardTemplateRef(modelA, ordinal = 1))
    }

    @Test
    fun `model and template keys distinguish an absent collection from any literal key`() {
        val model = AnkiNoteModelRef(backendA, "5")
        assertNotEquals(model.stableKey, model.copy(collectionKey = "?").stableKey)

        // The crafted ambiguity a delimiter-joined key allowed: two different (key, modelId) pairs
        // must never compose the same identity.
        val first = AnkiNoteModelRef(backendA, modelId = "b:model:c", collectionKey = "a")
        val second = AnkiNoteModelRef(backendA, modelId = "c", collectionKey = "a:model:b")
        assertNotEquals(first.stableKey, second.stableKey)

        val template = AnkiCardTemplateRef(AnkiNoteModelRef(backendA, "5", "coll"), ordinal = 2)
        assertTrue(template.stableKey.contains(backendA.stableId))
        assertTrue(template.stableKey.contains("5"))
        assertTrue(template.stableKey.contains("2"))
        // The template's effective collection is its own when present, otherwise the model's.
        assertNotEquals(
            template.stableKey,
            AnkiCardTemplateRef(AnkiNoteModelRef(backendA, "5", "other"), ordinal = 2).stableKey
        )
        assertNotEquals(
            template.stableKey,
            AnkiCardTemplateRef(AnkiNoteModelRef(backendA, "5"), ordinal = 2).stableKey
        )
    }

    // ----------------------------------------------------------------------------- VER-21C-08

    @Test
    fun `identical ids in two verified collections are not the same resource`() {
        val inCollectionA = AnkiCardRef(backendA, cardId = "123", collectionKey = "collection-a")
        val inCollectionB = AnkiCardRef(backendA, cardId = "123", collectionKey = "collection-b")
        assertNotEquals(inCollectionA, inCollectionB)
        assertNotEquals(inCollectionA.stableKey, inCollectionB.stableKey)

        // …and the context check says *mismatch*, never equality and never unverifiable.
        val context = AnkiBackendContext(backendA, AnkiCollectionIdentity(backendA, "collection-a"))
        assertEquals(
            AnkiIdentityAssurance.AuthoritativeMismatch("collection-a", "collection-b"),
            (context.resourceRelation(backendA, "collection-b") as AnkiIdentityCheck.SameBackend).assurance
        )
        assertTrue(
            AnkiIdentityContinuation.continuationVerdict(
                AnkiOperationClass.OP_R2,
                context.resourceRelation(backendA, "collection-b")
            ) is AnkiContinuationVerdict.Blocked
        )

        // An unknown collection is not a wildcard that would make the same id "probably the same".
        val unknown = AnkiCardRef(backendA, cardId = "123", collectionKey = null)
        assertNotEquals(unknown, inCollectionA)
        assertNotEquals(unknown, inCollectionB)
    }

    // ----------------------------------------------------------------------- VER-21C-11 / 12

    @Test
    fun `representative identities survive a serialization round trip unchanged`() {
        val card = AnkiCardRef(backendA, cardId = "123", noteId = "456", cardOrd = 1, collectionKey = "collection-a")
        val note = AnkiNoteRef(backendA, "456", "collection-a")
        val deck = AnkiDeckRef(backendA, "10", "collection-a")
        val keyed = AnkiBackendContext(backendA, AnkiCollectionIdentity(backendA, "collection-a"))
        val unknown = AnkiBackendContext(backendB)

        assertEquals(card, roundTrip(AnkiCardRef.serializer(), card))
        assertEquals(note, roundTrip(AnkiNoteRef.serializer(), note))
        assertEquals(deck, roundTrip(AnkiDeckRef.serializer(), deck))
        assertEquals(keyed, roundTrip(AnkiBackendContext.serializer(), keyed))
        // Absence survives as absence: an unknown collection is not defaulted on the way through.
        assertEquals(unknown, roundTrip(AnkiBackendContext.serializer(), unknown))
        assertNull(roundTrip(AnkiBackendContext.serializer(), unknown).collectionKey)
    }

    @Test
    fun `a card identity that omits its backend cannot be constructed`() {
        // The type boundary is the compiler: there is no constructor that takes an id alone. The
        // runtime form of the same rule is that the backend participates in equality and hashing.
        val unknownCollectionA = AnkiCardRef(backendA, cardId = "1")
        val unknownCollectionB = AnkiCardRef(backendB, cardId = "1")
        assertFalse(unknownCollectionA == unknownCollectionB)
        assertNotEquals(unknownCollectionA.hashCode(), unknownCollectionB.hashCode())
        assertNotEquals(unknownCollectionA.stableKey, unknownCollectionB.stableKey)
    }

    // ----------------------------------------------------------------------------- VER-21C-13

    @Test
    fun `navigation tokens keep their own backend and cannot be re-bound`() {
        val ref = AnkiCardRef(backendA, cardId = "123", collectionKey = "collection-a")
        val token = Screen.CardDetails.createRoute(ref).substringAfter('/')
        assertEquals(ref, Screen.CardDetails.decodeCardRef(token))

        // A reference that came from another backend decodes to *that* backend. Nothing in the
        // route layer can re-point it at a "current" backend, because it never asks for one.
        val foreign = AnkiCardRef(backendB, cardId = "123", collectionKey = "collection-b")
        val decoded = Screen.CardDetails.decodeCardRef(Screen.CardDetails.createRoute(foreign).substringAfter('/'))
        assertEquals(foreign, decoded)
        assertEquals(backendB, decoded!!.backendId)
        assertNull(Screen.CardDetails.decodeCardRef("not-a-token"))

        // GATE 21F closes the interim raw-deck-ID route: backend and collection identity travel
        // independently from the read-context generation and are never decoded against preference.
        val deckRef = AnkiDeckRef(backendA, "10", "collection-a")
        val context = AnkiReadContext.create(
            AnkiBackendContext(backendA, AnkiCollectionIdentity(backendA, "collection-a"))
        )
        val detailsRoute = Screen.DeckDetails.createRoute(deckRef, context)
        val deckToken = detailsRoute.substringAfter('/').substringBefore('?')
        assertEquals(deckRef, Screen.DeckDetails.decodeDeckRef(deckToken))
        val browserRoute = Screen.CardBrowser.createRoute(deckRef, context)
        val browserToken = browserRoute.substringAfter("deckRef=").substringBefore('&')
        assertEquals(deckRef, Screen.CardBrowser.decodeDeckRef(browserToken))
        val ownerToken = detailsRoute.substringAfter("readContext=")
        assertEquals(context, Screen.DeckDetails.decodeReadContext(ownerToken))
    }

    // ----------------------------------------------------------------------------- VER-21C-17

    @Test
    fun `the AnkiDroid adapter keeps backend-local ids and invents no collection key`() {
        val outcome = AnkiDroidDeckMapper.mapDeckRow(
            InMemoryProviderRow(linkedMapOf("deck_id" to 10L, "deck_name" to "Default"))
        ) as AnkiDroidDeckRowOutcome.Valid

        assertEquals(AnkiBackendId.AnkiDroidLocal, outcome.deck.ref.backendId)
        // The backend-local id is preserved exactly as the provider reported it.
        assertEquals("10", outcome.deck.ref.deckId)
        // AnkiDroid produces no authoritative collection discriminator: absence, never a sentinel.
        assertNull(outcome.deck.ref.collectionKey)
        assertFalse(AnkiCollectionIdentity(AnkiBackendId.AnkiDroidLocal, outcome.deck.ref.collectionKey).hasAuthoritativeKey)

        // The same numeric deck id on another backend is a different logical deck.
        assertNotEquals(outcome.deck.ref, outcome.deck.ref.copy(backendId = backendA))
        assertNotEquals(outcome.deck.ref.stableKey, outcome.deck.ref.copy(backendId = backendA).stableKey)
    }

    private fun <T> roundTrip(serializer: kotlinx.serialization.KSerializer<T>, value: T): T =
        json.decodeFromString(serializer, json.encodeToString(serializer, value))
}
