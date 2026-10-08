package com.studyagent.client.ui.navigation

import java.nio.charset.StandardCharsets
import java.util.Base64
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Library : Screen("library")
    data object Study : Screen("study")

    /** Card Browser accepts an optional stable deck ID; names/content are never route arguments. */
    data object CardBrowser : Screen("card-browser?deckId={deckId}") {
        const val ARG_DECK_ID: String = "deckId"

        fun createRoute(deckId: String? = null): String {
            require(deckId == null || deckId.isNotBlank())
            return if (deckId == null) "card-browser" else "card-browser?deckId=${encode(deckId)}"
        }

        fun decodeDeckId(token: String?): String? = decode(token)?.takeIf(String::isNotBlank)
    }

    /** Card Details contract carries only backend-qualified card identity, never a card object. */
    data object CardDetails : Screen("card-details/{cardRef}") {
        const val ARG_CARD_REF: String = "cardRef"

        fun createRoute(cardRef: AnkiCardRef): String {
            val fields = listOf(
                cardRef.backendId.stableId,
                cardRef.collectionKey,
                cardRef.cardId,
                cardRef.noteId,
                cardRef.cardOrd?.toString()
            )
            return "card-details/${fields.joinToString(".") { it?.let(::encode) ?: NULL_TOKEN }}"
        }

        fun decodeCardRef(token: String?): AnkiCardRef? {
            return try {
                val fields = token?.split('.')?.takeIf { it.size == 5 } ?: return null
                val decoded = arrayOfNulls<String>(fields.size)
                for (index in fields.indices) {
                    val value = fields[index]
                    val decodedValue = if (value == NULL_TOKEN) null else decode(value) ?: return null
                    decoded[index] = decodedValue
                }
                val backendId = AnkiBackendId.fromStableId(decoded[0]) ?: return null
                val cardOrdToken = decoded[4]
                val cardOrd = cardOrdToken?.toIntOrNull()
                if (cardOrdToken != null && cardOrd == null) return null
                AnkiCardRef(
                    backendId = backendId,
                    collectionKey = decoded[1],
                    cardId = decoded[2],
                    noteId = decoded[3],
                    cardOrd = cardOrd
                )
            } catch (_: RuntimeException) {
                null
            }
        }
    }

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

private const val NULL_TOKEN: String = "~"

private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decode(value: String?): String? = runCatching {
    value?.let { String(Base64.getUrlDecoder().decode(it), StandardCharsets.UTF_8) }
}.getOrNull()
