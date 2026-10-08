package com.studyagent.client.core.anki

/**
 * Backend-neutral, read-only card browser query.
 *
 * [deckId] is interpreted by the backend instance that receives this request; backend identity
 * is supplied by [AnkiBackend], never encoded as provider/AnkiConnect syntax here. Search text is
 * plain text and the backend owns its search semantics. The UI must not imitate a global query by
 * filtering only the currently loaded page.
 */
data class AnkiCardQuery(
    val deckId: String? = null,
    val text: String? = null,
    val filters: AnkiCardFilters = AnkiCardFilters(),
    val sort: AnkiCardSort = AnkiCardSort.Default,
    val page: AnkiPageRequest = AnkiPageRequest.DEFAULT
) {
    init {
        require(deckId == null || deckId.isNotBlank()) { "A deck ID must be null or non-blank" }
    }
}

/**
 * Read-only filters. Multi-value flag and card-type selections use OR semantics; selected tags
 * use AND semantics (a card must contain every selected tag). `suspended` and `buried` are exact
 * tri-state predicates: null means any, true means only set, false means only unset. A backend
 * that cannot authoritatively evaluate a requested field must reject the query, not ignore it.
 */
data class AnkiCardFilters(
    val flags: Set<AnkiFlag> = emptySet(),
    val tags: Set<String> = emptySet(),
    val cardTypes: Set<AnkiCardType> = emptySet(),
    val suspended: Boolean? = null,
    val buried: Boolean? = null
) {
    init {
        require(tags.none(String::isBlank)) { "Tags must be non-blank" }
    }

    val isEmpty: Boolean
        get() = flags.isEmpty() && tags.isEmpty() && cardTypes.isEmpty() &&
            suspended == null && buried == null
}

/** A backend-authoritative sort field; Default preserves the backend's own order. */
sealed interface AnkiCardSort {
    data object Default : AnkiCardSort
    data object Due : AnkiCardSort
    data object Created : AnkiCardSort
    data object Modified : AnkiCardSort
    data object Reps : AnkiCardSort
    data object Lapses : AnkiCardSort
}

/** Bounded page request. Cursor meaning is opaque to callers and owned by the backend. */
data class AnkiPageRequest(
    val limit: Int = DEFAULT_LIMIT,
    val cursor: String? = null
) {
    init {
        require(limit in MIN_LIMIT..MAX_LIMIT) { "Page limit must be between $MIN_LIMIT and $MAX_LIMIT" }
        require(cursor == null || cursor.isNotBlank()) { "A cursor must be null or non-blank" }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 40
        const val MIN_LIMIT: Int = 1
        const val MAX_LIMIT: Int = 100
        val DEFAULT = AnkiPageRequest()
    }
}

/** One bounded backend response. `totalCount` is exact for the current query or null if unknown. */
data class AnkiCardPage(
    val items: List<AnkiCardListItem>,
    val nextCursor: String?,
    val totalCount: Int?
) {
    init {
        require(nextCursor == null || nextCursor.isNotBlank())
        require(totalCount == null || totalCount >= 0)
    }
}

/**
 * Safe, pure normalizer for interactive search text. It trims and collapses Unicode whitespace
 * without changing case, normalizing Unicode, or otherwise changing backend search semantics.
 */
fun normalizeAnkiCardSearchText(value: String?): String? {
    if (value.isNullOrEmpty()) return null
    val result = StringBuilder(value.length)
    var pendingSpace = false
    for (character in value) {
        if (character.isWhitespace() || Character.isSpaceChar(character)) {
            if (result.isNotEmpty()) pendingSpace = true
        } else {
            if (pendingSpace) result.append(' ')
            result.append(character)
            pendingSpace = false
        }
    }
    return result.toString().takeIf(String::isNotEmpty)
}

/**
 * Returns a stable feature token when [query] requests semantics this backend has not advertised.
 * Validation lives beside the contract so callers and test backends cannot silently drop a field.
 */
fun AnkiCardQuery.unsupportedFeature(
    capabilities: AnkiCardBrowserCapabilities
): String? = when {
    !capabilities.browse -> "card_browser"
    deckId != null && !capabilities.deckScope -> "card_browser_deck_scope"
    text != null && !capabilities.textSearch -> "card_search"
    filters.flags.isNotEmpty() && !capabilities.flagFilter -> "card_filter_flags"
    filters.tags.isNotEmpty() && !capabilities.tagFilter -> "card_filter_tags"
    filters.cardTypes.isNotEmpty() && !capabilities.cardTypeFilter -> "card_filter_types"
    filters.suspended != null && !capabilities.suspendedFilter -> "card_filter_suspended"
    filters.buried != null && !capabilities.buriedFilter -> "card_filter_buried"
    sort != AnkiCardSort.Default && sort !in capabilities.sorts -> "card_sort_${sort.token()}"
    else -> null
}

private fun AnkiCardSort.token(): String = when (this) {
    AnkiCardSort.Default -> "default"
    AnkiCardSort.Due -> "due"
    AnkiCardSort.Created -> "created"
    AnkiCardSort.Modified -> "modified"
    AnkiCardSort.Reps -> "reps"
    AnkiCardSort.Lapses -> "lapses"
}
