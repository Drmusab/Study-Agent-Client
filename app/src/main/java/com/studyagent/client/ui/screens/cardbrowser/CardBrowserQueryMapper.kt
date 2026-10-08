package com.studyagent.client.ui.screens.cardbrowser

import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardQuery
import com.studyagent.client.core.anki.AnkiPageCursor
import com.studyagent.client.core.anki.AnkiPageRequest
import com.studyagent.client.core.anki.normalizeAnkiCardSearchText

/**
 * Pure conversion from visible selections to the neutral backend query contract.
 *
 * The mapper only *copies* user-visible selection state into the domain query: it never builds a
 * cursor (cursors come back from the backend and are returned verbatim, §31), never reinterprets
 * backend semantics and never applies a filter or sort locally.
 */
object CardBrowserQueryMapper {
    fun toDomain(
        query: CardBrowserQueryUi,
        pageSize: Int = AnkiPageRequest.DEFAULT_LIMIT,
        cursor: AnkiPageCursor? = null
    ): AnkiCardQuery = AnkiCardQuery(
        scope = query.scope,
        text = normalizeAnkiCardSearchText(query.searchText),
        filters = AnkiCardFilters(
            flags = query.filters.flags.toSet(),
            tags = query.filters.tags.toSet(),
            cardTypes = query.filters.cardTypes.toSet(),
            suspension = query.filters.suspension,
            burial = query.filters.burial
        ),
        sort = query.sort,
        page = AnkiPageRequest(limit = pageSize, cursor = cursor)
    )
}
