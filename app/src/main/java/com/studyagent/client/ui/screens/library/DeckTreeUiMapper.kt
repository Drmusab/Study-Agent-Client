package com.studyagent.client.ui.screens.library

import com.studyagent.client.core.anki.AnkiDeckFilter
import com.studyagent.client.core.anki.AnkiDeckSummary
import com.studyagent.client.core.anki.AnkiDeckTreeBuilder
import com.studyagent.client.core.anki.AnkiDeckTreeNode

/**
 * Pure Library projection. It filters the already-loaded list locally and builds the existing
 * backend-neutral hierarchy; it never asks a backend for per-row data.
 */
object DeckTreeUiMapper {
    fun build(items: List<DeckListItem>, query: String = ""): List<DeckTreeItem> {
        if (items.isEmpty()) return emptyList()
        val summaries: Map<com.studyagent.client.core.anki.AnkiDeckRef, AnkiDeckSummary> =
            items.associate { it.deck.ref to it.summary }
        val visibleDecks = AnkiDeckFilter.filter(items.map { it.deck }, query)
        if (visibleDecks.isEmpty()) return emptyList()
        val tree = AnkiDeckTreeBuilder.build(visibleDecks)

        fun mapNode(node: AnkiDeckTreeNode): DeckTreeItem = when (node) {
            is AnkiDeckTreeNode.VirtualGroup -> DeckTreeItem.Group(
                fullName = node.fullName,
                depth = node.depth,
                children = node.children.map(::mapNode)
            )
            is AnkiDeckTreeNode.Deck -> {
                val summary = checkNotNull(summaries[node.ref]) {
                    "Every tree deck must map to a summary with the same stable ID"
                }
                DeckTreeItem.Deck(
                    item = DeckListItem(summary),
                    depth = node.depth,
                    children = node.children.map(::mapNode)
                )
            }
        }

        return tree.roots.map(::mapNode)
    }
}
