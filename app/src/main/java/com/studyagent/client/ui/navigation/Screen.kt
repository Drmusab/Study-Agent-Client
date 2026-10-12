package com.studyagent.client.ui.navigation

import com.studyagent.client.core.anki.AnkiBackendContext
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiReadContext
import com.studyagent.client.core.anki.AnkiReadContextGeneration
import java.nio.charset.StandardCharsets
import java.util.Base64

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Library : Screen("library")
    data object Study : Screen("study")

    /**
     * A deck-scoped browser route carries the exact backend-qualified deck identity and, when
     * continuing navigation, the source read-context generation. A raw deck ID is never enough.
     */
    data object CardBrowser : Screen(
        "card-browser?deckRef={deckRef}&readContext={readContext}"
    ) {
        const val ARG_DECK_REF: String = "deckRef"
        const val ARG_READ_CONTEXT: String = "readContext"

        fun createRoute(
            deckRef: AnkiDeckRef? = null,
            readContext: AnkiReadContext? = null
        ): String {
            require(deckRef != null || readContext == null)
            if (deckRef == null) return "card-browser"
            require(readContext == null || readContext.backendId == deckRef.backendId)
            val base = "card-browser?deckRef=${deckRefToken(deckRef)}"
            return readContext?.let { "$base&readContext=${readContextToken(it)}" } ?: base
        }

        fun decodeDeckRef(token: String?): AnkiDeckRef? = deckRefFromToken(token)
        fun decodeReadContext(token: String?): AnkiReadContext? = readContextFromToken(token)
    }

    /** Card Details carries only backend-qualified card identity plus its optional read-context owner. */
    data object CardDetails : Screen(
        "card-details/{cardRef}?readContext={readContext}"
    ) {
        const val ARG_CARD_REF: String = "cardRef"
        const val ARG_READ_CONTEXT: String = "readContext"

        fun createRoute(cardRef: AnkiCardRef, readContext: AnkiReadContext? = null): String {
            require(readContext == null || readContext.backendId == cardRef.backendId)
            val base = "card-details/${cardRefToken(cardRef)}"
            return readContext?.let { "$base?readContext=${readContextToken(it)}" } ?: base
        }

        fun decodeCardRef(token: String?): AnkiCardRef? = cardRefFromToken(token)
        fun decodeReadContext(token: String?): AnkiReadContext? = readContextFromToken(token)
    }

    /**
     * GATE 17 — the note editor. It carries exactly the same backend-qualified card identity as Card
     * Details and nothing else: no note content, no field values, no deck label (INV-17-10).
     */
    data object EditNote : Screen("edit-note/{cardRef}") {
        const val ARG_CARD_REF: String = "cardRef"

        fun createRoute(cardRef: AnkiCardRef): String = "edit-note/${cardRefToken(cardRef)}"

        fun decodeCardRef(token: String?): AnkiCardRef? = cardRefFromToken(token)
    }

    /** GATE 18 — creation target stays exact; the read-context token only scopes model/deck reads. */
    data object AddNote : Screen("add-note/{backendId}") {
        const val ARG_BACKEND_ID: String = "backendId"

        fun createRoute(backendId: AnkiBackendId): String = "add-note/${encode(backendId.stableId)}"

        fun decodeBackendId(token: String?): AnkiBackendId? =
            decode(token)?.let(AnkiBackendId::fromStableId)
    }

    /** A deck details route must preserve backend + collection + local deck ID as one reference. */
    data object DeckDetails : Screen(
        "deck-details/{deckRef}?readContext={readContext}"
    ) {
        const val ARG_DECK_REF: String = "deckRef"
        const val ARG_READ_CONTEXT: String = "readContext"

        fun createRoute(deckRef: AnkiDeckRef, readContext: AnkiReadContext? = null): String {
            require(readContext == null || readContext.backendId == deckRef.backendId)
            val base = "deck-details/${deckRefToken(deckRef)}"
            return readContext?.let { "$base?readContext=${readContextToken(it)}" } ?: base
        }

        fun decodeDeckRef(token: String?): AnkiDeckRef? = deckRefFromToken(token)
        fun decodeReadContext(token: String?): AnkiReadContext? = readContextFromToken(token)
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

/** One encoded card-identity token, shared by every route that carries an [AnkiCardRef]. */
private fun cardRefToken(cardRef: AnkiCardRef): String {
    val fields = listOf(
        cardRef.backendId.stableId,
        cardRef.collectionKey,
        cardRef.cardId,
        cardRef.noteId,
        cardRef.cardOrd?.toString()
    )
    return fields.joinToString(".") { it?.let(::encode) ?: NULL_TOKEN }
}

private fun cardRefFromToken(token: String?): AnkiCardRef? = try {
    val fields = token?.split('.')?.takeIf { it.size == 5 } ?: return null
    val decoded = arrayOfNulls<String>(fields.size)
    for (index in fields.indices) {
        val value = fields[index]
        decoded[index] = if (value == NULL_TOKEN) null else decode(value) ?: return null
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

/** Deck identity route encoding mirrors card identity; names and numeric IDs are never reinterpreted. */
private fun deckRefToken(deckRef: AnkiDeckRef): String = listOf(
    deckRef.backendId.stableId,
    deckRef.collectionKey,
    deckRef.deckId
).joinToString(".") { it?.let(::encode) ?: NULL_TOKEN }

private fun deckRefFromToken(token: String?): AnkiDeckRef? = try {
    val fields = token?.split('.')?.takeIf { it.size == 3 } ?: return null
    val decoded = fields.map { value -> if (value == NULL_TOKEN) null else decode(value) ?: return null }
    val backendId = AnkiBackendId.fromStableId(decoded[0]) ?: return null
    val deckId = decoded[2]?.takeIf(String::isNotBlank) ?: return null
    AnkiDeckRef(backendId = backendId, deckId = deckId, collectionKey = decoded[1])
} catch (_: RuntimeException) {
    null
}

/** Full route owner token keeps backend, collection and generation independent of resource identity. */
private fun readContextToken(context: AnkiReadContext): String = listOf(
    context.backendId.stableId,
    context.collectionKey,
    context.generation.value
).joinToString(".") { it?.let(::encode) ?: NULL_TOKEN }

/** A malformed owner token is invalid; callers distinguish it from an omitted legacy token. */
private fun readContextFromToken(token: String?): AnkiReadContext? = try {
    val fields = token?.split('.')?.takeIf { it.size == 3 } ?: return null
    val decoded = fields.map { value -> if (value == NULL_TOKEN) null else decode(value) ?: return null }
    val backendId = AnkiBackendId.fromStableId(decoded[0]) ?: return null
    val generation = decoded[2]?.takeIf(String::isNotBlank)
        ?.let { runCatching { AnkiReadContextGeneration(it) }.getOrNull() } ?: return null
    val collection = decoded[1]?.let { AnkiCollectionIdentity(backendId, it) }
    AnkiReadContext(AnkiBackendContext(backendId, collection), generation)
} catch (_: RuntimeException) {
    null
}

private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decode(value: String?): String? = runCatching {
    value?.let { String(Base64.getUrlDecoder().decode(it), StandardCharsets.UTF_8) }
}.getOrNull()
