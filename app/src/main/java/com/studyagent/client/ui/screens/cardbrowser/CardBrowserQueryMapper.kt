package com.studyagent.client.ui.screens.cardbrowser

import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardQuery
import com.studyagent.client.core.anki.AnkiPageRequest
import com.studyagent.client.core.anki.normalizeAnkiCardSearchText

/** Pure conversion from visible selections to the neutral backend query contract. */
object CardBrowserQueryMapper {
    fun toDomain(
        query: CardBrowserQueryUi,
        pageSize: Int = AnkiPageRequest.DEFAULT_LIMIT,
        cursor: String? = null
    ): AnkiCardQuery = AnkiCardQuery(
        deckId = query.deckId,
        text = normalizeAnkiCardSearchText(query.searchText),
        filters = AnkiCardFilters(
            flags = query.filters.flags.toSet(),
            tags = query.filters.tags.toSet(),
            cardTypes = query.filters.cardTypes.toSet(),
            suspended = query.filters.suspended,
            buried = query.filters.buried
        ),
        sort = query.sort,
        page = AnkiPageRequest(limit = pageSize, cursor = cursor)
    )
}
