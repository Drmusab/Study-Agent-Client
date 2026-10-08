package com.studyagent.client.ui.screens.library

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckSummary
import com.studyagent.client.core.anki.AnkiError

/** One real deck row. [deck.ref] is the only navigation identity; the name is display-only. */
data class DeckListItem(
    val summary: AnkiDeckSummary
) {
    val deck: AnkiDeck get() = summary.deck
}

/** UI projection of the already-built domain tree. Virtual groups deliberately have no deck ID. */
sealed interface DeckTreeItem {
    val key: String
    val name: String
    val fullName: String
    val depth: Int
    val children: List<DeckTreeItem>

    data class Deck(
        val item: DeckListItem,
        override val depth: Int,
        override val children: List<DeckTreeItem>
    ) : DeckTreeItem {
        override val key: String get() = "deck:${item.deck.ref.stableKey}"
        override val name: String get() = item.deck.leafName
        override val fullName: String get() = item.deck.name
    }

    data class Group(
        override val fullName: String,
        override val depth: Int,
        override val children: List<DeckTreeItem>
    ) : DeckTreeItem {
        override val key: String get() = "group:$fullName"
        override val name: String get() = fullName.substringAfterLast("::")
    }
}

/**
 * The Library's one canonical render state. Empty is a successful ready collection with no decks;
 * Unavailable and Error are distinct outcomes and never masquerade as that empty state.
 */
sealed interface LibraryUiState {
    data object Loading : LibraryUiState

    data class Ready(
        val decks: List<DeckListItem>,
        val tree: List<DeckTreeItem>,
        val backend: AnkiBackendId,
        val availability: AnkiAvailability.Ready,
        val capabilities: AnkiCapabilities,
        val isRefreshing: Boolean,
        val staleError: AnkiError? = null
    ) : LibraryUiState

    data class Empty(
        val backend: AnkiBackendId,
        val availability: AnkiAvailability.Ready,
        val capabilities: AnkiCapabilities,
        val isRefreshing: Boolean,
        val staleError: AnkiError? = null
    ) : LibraryUiState

    data class Unavailable(
        val backend: AnkiBackendId,
        val reason: AnkiAvailability,
        val isRetrying: Boolean = false
    ) : LibraryUiState

    data class Error(
        val backend: AnkiBackendId,
        val error: AnkiError,
        val isRetrying: Boolean = false
    ) : LibraryUiState
}

/** Pure flattening for a tree/list viewport. Composition does not call a backend. */
object DeckTreeProjection {
    fun flattenVisible(
        roots: List<DeckTreeItem>,
        expandedKeys: Set<String>,
        forceExpanded: Boolean = false
    ): List<DeckTreeItem> {
        if (roots.isEmpty()) return emptyList()
        val result = ArrayList<DeckTreeItem>()
        val stack = ArrayDeque<DeckTreeItem>()
        for (index in roots.indices.reversed()) stack.addLast(roots[index])
        while (stack.isNotEmpty()) {
            val item = stack.removeLast()
            result += item
            if (item.children.isNotEmpty() && (forceExpanded || item.key in expandedKeys)) {
                for (index in item.children.indices.reversed()) stack.addLast(item.children[index])
            }
        }
        return result
    }

    fun defaultExpandedKeys(roots: List<DeckTreeItem>): List<String> =
        roots.filter { it.children.isNotEmpty() }.map { it.key }
}

/** Safe, localized-at-the-UI-boundary copy for domain errors; no provider exception text is shown. */
fun AnkiError.libraryMessage(): String = when (this) {
    is AnkiError.PermissionRequired -> "Anki access permission is required. Grant access, then retry."
    is AnkiError.ProviderUnavailable -> "The Anki integration provider is not reachable right now."
    is AnkiError.UnsupportedApi -> "This Anki backend version is not supported by the app."
    is AnkiError.CollectionUnavailable -> "The Anki collection is not available right now."
    is AnkiError.BackendUnavailable -> "The selected Anki backend is not available right now."
    is AnkiError.QueryFailure -> "Anki could not load the deck list. Please try again."
    is AnkiError.MalformedResponse -> "Anki returned deck data the app could not read. Please update Anki and retry."
    is AnkiError.UnsupportedAction -> "This backend does not support deck browsing."
    is AnkiError.DeckNotFound -> "This deck is no longer in the current Anki collection."
    is AnkiError.Unknown -> "The deck request could not be completed. Please retry."
    else -> "The deck request could not be completed. Please retry."
}

fun AnkiAvailability.displayLabel(): String = when (this) {
    AnkiAvailability.Checking -> "Checking backend"
    AnkiAvailability.NotInstalled -> "Backend not installed"
    is AnkiAvailability.ProviderUnavailable -> "Integration unavailable"
    is AnkiAvailability.PermissionRequired -> "Permission required"
    AnkiAvailability.CollectionNotInitialized -> "Collection not ready"
    is AnkiAvailability.Ready -> "Ready"
    is AnkiAvailability.TemporarilyUnavailable ->
        if (reason == "collection_unavailable") "Collection unavailable" else "Temporarily unavailable"
    AnkiAvailability.AgentDisconnected -> "Agent disconnected"
    AnkiAvailability.AgentAnkiUnavailable -> "Anki unavailable"
    is AnkiAvailability.Unsupported -> "Unsupported backend"
    is AnkiAvailability.Fault -> "Backend check failed"
}

fun AnkiAvailability.displayMessage(): String = when (this) {
    AnkiAvailability.Checking -> "Checking the selected Anki backend."
    AnkiAvailability.NotInstalled -> "The selected Anki source is not installed."
    is AnkiAvailability.ProviderUnavailable -> "The Anki integration provider is not reachable. Open the Anki app and retry."
    is AnkiAvailability.PermissionRequired -> "Grant Anki access in the connected app, then retry."
    AnkiAvailability.CollectionNotInitialized -> "Open Anki and finish setting up a collection, then retry."
    is AnkiAvailability.Ready -> "The selected Anki backend is ready."
    is AnkiAvailability.TemporarilyUnavailable -> if (reason == "collection_unavailable") {
        "The Anki collection is not available right now. Open Anki and retry when it is ready."
    } else {
        "The backend is busy or offline. Try again in a moment."
    }
    AnkiAvailability.AgentDisconnected -> "The selected study agent is offline."
    AnkiAvailability.AgentAnkiUnavailable -> "The study agent is online, but its Anki collection is unavailable."
    is AnkiAvailability.Unsupported -> "This backend cannot provide the requested library feature."
    is AnkiAvailability.Fault -> "The backend could not be checked. Please retry."
}
