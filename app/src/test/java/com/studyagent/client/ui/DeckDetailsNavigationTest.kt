package com.studyagent.client.ui

import com.studyagent.client.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeckDetailsNavigationTest {
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
