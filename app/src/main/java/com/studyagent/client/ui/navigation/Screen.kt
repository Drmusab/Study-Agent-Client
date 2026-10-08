package com.studyagent.client.ui.navigation

import java.nio.charset.StandardCharsets
import java.util.Base64

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Library : Screen("library")
    data object Study : Screen("study")

    /** Stable deck identity is encoded into a single navigation segment; deck names are never routes. */
    data object DeckDetails : Screen("deck-details/{deckId}") {
        const val ARG_DECK_ID: String = "deckId"

        fun createRoute(deckId: String): String {
            require(deckId.isNotBlank())
            val token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(deckId.toByteArray(StandardCharsets.UTF_8))
            return "deck-details/$token"
        }

        fun decodeDeckId(token: String?): String? = runCatching {
            token?.let { String(Base64.getUrlDecoder().decode(it), StandardCharsets.UTF_8) }
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
    }

    /** Study Control Center: how the PC Study Agent studies (§45). */
    data object Control : Screen("control")

    data object Connection : Screen("connection")
    data object Settings : Screen("settings")
    data object Diagnostics : Screen("diagnostics")

    companion object {
        /** Primary product destinations shown in the bottom navigation (§47). */
        val primaryRoutes: List<String> = listOf(Home.route, Library.route, Study.route, Control.route)
    }
}
