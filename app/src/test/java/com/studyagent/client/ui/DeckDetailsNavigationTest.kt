package com.studyagent.client.ui

import com.studyagent.client.core.anki.AnkiBackendContext
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiReadContext
import com.studyagent.client.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeckDetailsNavigationTest {
    @Test
    fun `card browser routes carry exact backend qualified deck and context identity`() {
        val backend = AnkiBackendId.PcAgent("profile:one")
        val collectionKey = "collection|one"
        val deckRef = AnkiDeckRef(backend, "deck/with::unicode العربية", collectionKey)
        val context = AnkiReadContext.create(
            AnkiBackendContext(backend, AnkiCollectionIdentity(backend, collectionKey))
        )
        val route = Screen.CardBrowser.createRoute(deckRef, context)
        val deckToken = route.substringAfter("deckRef=").substringBefore('&')
        val contextToken = route.substringAfter("readContext=")

        assertEquals("card-browser", route.substringBefore('?'))
        assertEquals(deckRef, Screen.CardBrowser.decodeDeckRef(deckToken))
        assertEquals(context, Screen.CardBrowser.decodeReadContext(contextToken))
        assertEquals("card-browser", Screen.CardBrowser.createRoute())
        assertNull(Screen.CardBrowser.decodeDeckRef("%%%"))
        assertNull(Screen.CardBrowser.decodeReadContext("%%%"))
    }

    @Test
    fun `card details route preserves resource and owner collection independently`() {
        val backend = AnkiBackendId.PcAgent("profile:one")
        val cardRef = AnkiCardRef(
            backendId = backend,
            cardId = "card/1",
            noteId = "note:α",
            cardOrd = 0,
            collectionKey = null
        )
        val context = AnkiReadContext.create(
            AnkiBackendContext(backend, AnkiCollectionIdentity(backend, "context collection"))
        )
        val route = Screen.CardDetails.createRoute(cardRef, context)
        val resourceToken = route.substringAfter('/').substringBefore('?')
        val contextToken = route.substringAfter("readContext=")

        assertEquals("card-details", route.substringBefore('/'))
        assertEquals(cardRef, Screen.CardDetails.decodeCardRef(resourceToken))
        assertEquals(context, Screen.CardDetails.decodeReadContext(contextToken))
        assertNull(Screen.CardDetails.decodeCardRef("bad-ref"))
        assertNull(Screen.CardDetails.decodeReadContext("%%%"))
        val malformedOrdinal = resourceToken.split('.').toMutableList().apply {
            this[4] = "bm90LW51bWJlci" // Base64url("not-number")
        }.joinToString(".")
        assertNull(Screen.CardDetails.decodeCardRef(malformedOrdinal))
    }

    @Test
    fun `deck details route never sends an unqualified deck id`() {
        val backend = AnkiBackendId.PcAgent("profile:one")
        val first = AnkiDeckRef(backend, "123/with separators::and unicode أ", "collection-a")
        val second = AnkiDeckRef(backend, "another-id", "collection-a")
        val context = AnkiReadContext.create(
            AnkiBackendContext(backend, AnkiCollectionIdentity(backend, "collection-a"))
        )
        val firstRoute = Screen.DeckDetails.createRoute(first, context)
        val secondRoute = Screen.DeckDetails.createRoute(second)
        val refToken = firstRoute.substringAfter('/').substringBefore('?')
        val contextToken = firstRoute.substringAfter("readContext=")

        assertEquals("deck-details", firstRoute.substringBefore('/'))
        assertNotEquals(firstRoute, secondRoute)
        assertEquals(first, Screen.DeckDetails.decodeDeckRef(refToken))
        assertEquals(context, Screen.DeckDetails.decodeReadContext(contextToken))
        assertNull(Screen.DeckDetails.decodeDeckRef("not-a-deck-ref"))
    }

    @Test
    fun `Add Note route keeps the existing explicit creation backend`() {
        val route = Screen.AddNote.createRoute(AnkiBackendId.AnkiDroidLocal)
        val backendToken = route.substringAfter('/')
        assertEquals(AnkiBackendId.AnkiDroidLocal, Screen.AddNote.decodeBackendId(backendToken))
    }
}
