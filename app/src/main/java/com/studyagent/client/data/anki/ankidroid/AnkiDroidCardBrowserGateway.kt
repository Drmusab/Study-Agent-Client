package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiCardPage
import com.studyagent.client.core.anki.AnkiCardQuery
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult

/** Provider-specific edge for the read-only Card Browser operation. */
interface AnkiDroidCardBrowserGateway {
    suspend fun browseCards(query: AnkiCardQuery): AnkiResult<AnkiCardPage>
}

/**
 * Honest implementation for the currently pinned public AnkiDroid API. It issues no provider
 * request: the public API only exposes known-card item reads, which cannot implement collection
 * browsing, backend text search, or stable bounded paging. A future audited API can replace this
 * adapter without changing the domain contract.
 */
class UnsupportedAnkiDroidCardBrowserGateway internal constructor(
    private val queryMapper: (AnkiCardQuery) -> AnkiDroidCardQueryMapping.Unsupported =
        AnkiDroidCardQueryMapper::map
) : AnkiDroidCardBrowserGateway {
    override suspend fun browseCards(query: AnkiCardQuery): AnkiResult<AnkiCardPage> =
        when (val mapping = queryMapper(query)) {
            is AnkiDroidCardQueryMapping.Unsupported ->
                AnkiResult.Failure(AnkiError.UnsupportedQueryFeature(feature = mapping.feature))
        }
}
