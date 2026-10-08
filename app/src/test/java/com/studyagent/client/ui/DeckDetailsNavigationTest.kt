package com.studyagent.client.ui

import com.studyagent.client.ui.navigation.Screen
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeckDetailsNavigationTest {
    @Test
    fun `card browser route accepts an optional stable deck ID`() {
        val deckId = "deck/with::unicode العربية"
        val route = Screen.CardBrowser.createRoute(deckId)
        assertEquals("card-browser", route.substringBefore('?'))
        assertEquals(deckId, Screen.CardBrowser.decodeDeckId(route.substringAfter("deckId=")))
        assertEquals("card-browser", Screen.CardBrowser.createRoute())
        assertEquals(null, Screen.CardBrowser.decodeDeckId("%%%"))
    }

    @Test
    fun `card details route carries only a backend qualified stable card reference`() {
        val ref = AnkiCardRef(
            backendId = AnkiBackendId.PcAgent("profile:one"),
            cardId = "card/1",
            noteId = "note:α",
            cardOrd = 0,
            collectionKey = "collection|one"
        )
        val route = Screen.CardDetails.createRoute(ref)
        assertEquals("card-details", route.substringBefore('/'))
        assertEquals(ref, Screen.CardDetails.decodeCardRef(route.substringAfter('/')))
        assertEquals(null, Screen.CardDetails.decodeCardRef("bad-ref"))
        val malformedOrdinal = route.substringAfter('/').split('.').toMutableList().apply {
            this[4] = "bm90LW51bWJlci" // Base64url("not-number")
        }.joinToString(".")
        assertEquals(null, Screen.CardDetails.decodeCardRef(malformedOrdinal))
    }

    @Test
    fun `details route round trips stable backend IDs without deck names`() {
        val firstId = "123/with separators::and unicode أ"
        val secondId = "another-id"
        val firstRoute = Screen.DeckDetails.createRoute(firstId)
        val secondRoute = Screen.DeckDetails.createRoute(secondId)
        assertEquals("deck-details", firstRoute.substringBefore('/'))
        assertNotEquals(firstRoute, secondRoute)
        assertEquals(firstId, Screen.DeckDetails.decodeDeckId(firstRoute.substringAfter('/')))
        assertEquals(secondId, Screen.DeckDetails.decodeDeckId(secondRoute.substringAfter('/')))
    }
}
