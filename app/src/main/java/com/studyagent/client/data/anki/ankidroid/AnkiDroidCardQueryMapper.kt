package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiCardQuery

/**
 * Mapping result for the pinned AnkiDroid public provider contract.
 *
 * The contract has item URIs (`cards/<id>` and `notes/<id>/cards/<ord>`) for hydrating a card
 * whose identity is already known, but no collection/list URI with stable paging or card search.
 * Consequently there is intentionally no provider query plan to return from this mapper.
 */
internal sealed interface AnkiDroidCardQueryMapping {
    data class Unsupported(val feature: String) : AnkiDroidCardQueryMapping
}

internal object AnkiDroidCardQueryMapper {
    /**
     * Refuses rather than adapting a partial due queue or a known-card item lookup into a browser.
     * Component-specific tokens make unsupported search/filter/sort requests diagnosable; the
     * plain unfiltered request reports the missing public card-list endpoint.
     */
    fun map(query: AnkiCardQuery): AnkiDroidCardQueryMapping.Unsupported {
        val unsupportedFeature = when {
            query.text != null -> "card_search"
            query.filters.flags.isNotEmpty() -> "card_filter_flags"
            query.filters.tags.isNotEmpty() -> "card_filter_tags"
            query.filters.cardTypes.isNotEmpty() -> "card_filter_types"
            query.filters.suspended != null -> "card_filter_suspended"
            query.filters.buried != null -> "card_filter_buried"
            query.sort != com.studyagent.client.core.anki.AnkiCardSort.Default -> "card_sort"
            query.deckId != null -> "card_browser_deck_scope"
            else -> "card_browser_public_api"
        }
        return AnkiDroidCardQueryMapping.Unsupported(unsupportedFeature)
    }
}
