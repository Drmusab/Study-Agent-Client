package com.studyagent.client.core.anki

/**
 * GATE 15 — the normative, backend-neutral card browser query contract.
 *
 * ```text
 * AnkiCardQuery = scope + optional textual search + explicit normalized filters
 *               + explicit deterministic sort + opaque bounded pagination
 * ```
 *
 * A backend has exactly two valid behaviours: execute the requested semantics correctly, or
 * reject the unsupported semantic explicitly. It must never silently ignore, approximate or
 * downgrade a query component, and it must never implement a global filter or sort by post
 * processing already loaded pages ([AnkiCardQuery.preflightError] + the contract tests enforce
 * this).
 *
 * ## Scope
 *
 * [AnkiCardScope] makes the search space explicit. A nullable `deckId` is deliberately not used:
 * `null` would ambiguously mean "global" on one backend and "the selected deck" on another.
 *
 * ## Search text
 *
 * [text] is *literal user search text* over backend-supported searchable card/note text fields
 * (question, answer, note fields). It never carries a backend query language: no `deck:…`,
 * `tag:…`, `is:due` or `prop:…` syntax reaches a backend through this field, and no backend
 * syntax ever reaches the UI (INV-15-Q18). Normalization is exactly:
 *
 * ```text
 * trim leading/trailing whitespace
 * empty string → null
 * preserve Unicode, preserve case, preserve internal spacing
 * ```
 *
 * No `lowercase()`, accent stripping, diacritic removal or manual tokenization happens here.
 *
 * ## Filters
 *
 * Different filter categories combine with AND. Within one category: `flags` and `cardTypes`
 * combine with OR, `tags` combine with AND (every selected tag must be present). An empty set
 * means "no restriction of this kind" — never "unflagged only".
 *
 * ## Sorting
 *
 * [AnkiCardSort.Default] is the backend's own deterministic browsing order; every explicit sort
 * must be honoured exactly and must have a deterministic tie-breaker
 * (primary key, then the stable card identity). Search relevance never overrides an explicit sort.
 *
 * ## Paging
 *
 * [AnkiPageRequest.cursor] is opaque ([AnkiPageCursor]) and bound to one logical query identity
 * ([consistencyKey]). Any change of backend, collection, scope, text, filters or sort invalidates
 * it — see [AnkiCardPage].
 */
data class AnkiCardQuery(
    val scope: AnkiCardScope = AnkiCardScope.AllCards,
    val text: String? = null,
    val filters: AnkiCardFilters = AnkiCardFilters(),
    val sort: AnkiCardSort = AnkiCardSort.Default,
    val page: AnkiPageRequest = AnkiPageRequest.firstPage()
) {
    /** The canonical normalized search text this query will actually execute with. */
    val normalizedText: String? get() = normalizeAnkiCardSearchText(text)

    /**
     * The same query with the canonical normalization applied. Backends call this before
     * translating a query, so no adapter needs its own text rules.
     */
    fun normalized(): AnkiCardQuery {
        val normalized = normalizedText
        return if (normalized == text) this else copy(text = normalized)
    }

    /**
     * §42 query consistency key — the canonical logical fingerprint of this query *without* its
     * page cursor. Cursors and stale-response checks are bound to this value, so changing scope,
     * text, filters, sort, backend or collection always produces a different key.
     */
    fun consistencyKey(backendId: AnkiBackendId, collectionKey: String? = null): String = buildString {
        append("backend=").append(canonicalToken(backendId.stableId))
        append("|collection=").append(canonicalToken(collectionKey))
        append("|scope=").append(
            when (val scope = scope) {
                AnkiCardScope.AllCards -> "all"
                is AnkiCardScope.Deck ->
                    "deck:${canonicalToken(scope.deckId)}:children=${scope.includeChildren}"
            }
        )
        append("|text=").append(canonicalToken(normalizedText))
        append("|flags=").append(filters.flags.map(AnkiFlag::name).sorted().joinToString(","))
        append("|tags=").append(filters.tags.sorted().joinToString(",") { canonicalToken(it) })
        append("|types=").append(filters.cardTypes.map(AnkiCardType::name).sorted().joinToString(","))
        append("|suspension=").append(filters.suspension.name)
        append("|burial=").append(filters.burial.name)
        append("|sort=").append(sort.canonicalToken())
    }

    /** The filter set the canonical key is built from, canonicalized (order-insensitive sets). */
    val canonicalFilters: AnkiCardFilters get() = filters.canonical()

    /**
     * §50 structural validation. Returns a typed error for a structurally invalid request and
     * `null` for a well-formed one. This runs *before* capability checks (§63 order) and never
     * throws, so the typed error — not a Kotlin exception — is what crosses the backend boundary.
     */
    fun structuralError(): AnkiError.InvalidQuery? = when {
        page.limit < AnkiPageRequest.MIN_LIMIT -> AnkiError.InvalidQuery(detail = "card_page_limit_below_minimum")
        page.limit > AnkiPageRequest.MAX_LIMIT -> AnkiError.InvalidQuery(detail = "card_page_limit_above_maximum")
        (scope as? AnkiCardScope.Deck)?.deckId?.isBlank() == true -> AnkiError.InvalidQuery(detail = "card_scope_deck_id_blank")
        filters.tags.any(String::isBlank) -> AnkiError.InvalidQuery(detail = "card_filter_tag_blank")
        page.cursor?.value?.isBlank() == true -> AnkiError.InvalidQuery(detail = "card_cursor_blank")
        else -> null
    }

    /**
     * §20/§61 capability preflight. Returns the stable feature token this backend cannot honour,
     * or `null` when every requested component is advertised. A backend must reject the query
     * with [AnkiError.UnsupportedQueryFeature] instead of ignoring the component.
     */
    fun unsupportedFeature(capabilities: AnkiCardBrowserCapabilities): String? = when {
        !capabilities.canBrowseAllCards && !capabilities.canBrowseDeck -> "card_browser"
        scope is AnkiCardScope.AllCards && !capabilities.canBrowseAllCards -> "card_browser_scope_all"
        scope is AnkiCardScope.Deck && !capabilities.canBrowseDeck -> "card_browser_deck_scope"
        scope is AnkiCardScope.Deck && scope.includeChildren && !capabilities.canIncludeChildDecks ->
            "card_browser_child_decks"
        page.limit > capabilities.maxPageSize -> "card_page_limit"
        normalizedText != null && !capabilities.canSearchText -> "card_search"
        filters.flags.isNotEmpty() && AnkiCardFilterCapability.FLAGS !in capabilities.supportedFilters ->
            "card_filter_flags"
        filters.tags.isNotEmpty() && AnkiCardFilterCapability.TAGS !in capabilities.supportedFilters ->
            "card_filter_tags"
        filters.cardTypes.isNotEmpty() && AnkiCardFilterCapability.CARD_TYPES !in capabilities.supportedFilters ->
            "card_filter_types"
        filters.suspension != SuspensionFilter.Any &&
            AnkiCardFilterCapability.SUSPENSION !in capabilities.supportedFilters -> "card_filter_suspended"
        filters.burial != BurialFilter.Any &&
            AnkiCardFilterCapability.BURIAL !in capabilities.supportedFilters -> "card_filter_buried"
        sort != AnkiCardSort.Default &&
            sort.sortCapability() !in capabilities.supportedSorts -> "card_sort_${sort.sortToken()}"
        else -> null
    }

    /**
     * §63 ordered preflight: structural validity first, then requested-feature support. Backend
     * availability and collection validity are checked by the adapter before this call, and cursor
     * validity after it (a cursor is only meaningful once the query is accepted).
     */
    fun preflightError(capabilities: AnkiCardBrowserCapabilities): AnkiError? =
        structuralError() ?: unsupportedFeature(capabilities)?.let { feature ->
            AnkiError.UnsupportedQueryFeature(feature = feature)
        }
}

/**
 * Explicit browse scope (§3-§6). [AllCards] means every card the backend collection can expose —
 * never "the due queue", "the review session deck" or "the currently selected deck".
 */
sealed interface AnkiCardScope {
    /** Every card reachable through the selected backend collection. */
    data object AllCards : AnkiCardScope

    /**
     * One deck addressed by its stable backend deck id, optionally including descendant subdecks.
     * Descendants are resolved through authoritative deck relationships — never by deck-name
     * prefix matching.
     */
    data class Deck(
        val deckId: String,
        val includeChildren: Boolean = true
    ) : AnkiCardScope
}

/**
 * Explicit read-only filters (§12-§19). `flags`/`cardTypes` are OR sets, `tags` is an AND set, and
 * suspension/burial are tri-state enums rather than nullable booleans so "any" is never confused
 * with "false". A backend that cannot evaluate a requested field authoritatively must reject the
 * query — it must not ignore the field or filter the loaded page locally.
 */
data class AnkiCardFilters(
    val flags: Set<AnkiFlag> = emptySet(),
    val tags: Set<String> = emptySet(),
    val cardTypes: Set<AnkiCardType> = emptySet(),
    val suspension: SuspensionFilter = SuspensionFilter.Any,
    val burial: BurialFilter = BurialFilter.Any
) {
    val isEmpty: Boolean
        get() = flags.isEmpty() && tags.isEmpty() && cardTypes.isEmpty() &&
            suspension == SuspensionFilter.Any && burial == BurialFilter.Any

    /** Canonical (order-insensitive) copy, used by the query consistency key. */
    fun canonical(): AnkiCardFilters = copy(
        flags = flags.toSet(),
        tags = tags.toSet(),
        cardTypes = cardTypes.toSet()
    )
}

/** §13 suspension filter — explicit tri-state; never inferred from queue numbers by the UI. */
enum class SuspensionFilter {
    /** Suspension status does not restrict the result. */
    Any,

    /** Only cards the backend authoritatively reports as suspended. */
    SuspendedOnly,

    /** Only cards the backend authoritatively reports as not suspended. */
    NotSuspended
}

/** §14 burial filter — same explicit semantics as [SuspensionFilter]. */
enum class BurialFilter {
    Any,
    BuriedOnly,
    NotBuried
}

/** §22 sort direction. */
enum class SortDirection {
    ASCENDING,
    DESCENDING
}

/**
 * §22 canonical sort model. Sorts are executed by the backend over the whole result set; the UI
 * never re-sorts a partially loaded page. Each variant carries an explicit direction, and every
 * supported sort has a deterministic tie-breaker (primary key, then stable card identity).
 */
sealed interface AnkiCardSort {
    /** §23 backend-defined deterministic browsing order — not the scheduler queue, not random. */
    data object Default : AnkiCardSort

    /** §24 backend-reported due/scheduling value. Informational; no scheduler simulation. */
    data class Due(val direction: SortDirection) : AnkiCardSort

    /** Note/card creation time, only when the backend reports it authoritatively (§25). */
    data class Created(val direction: SortDirection) : AnkiCardSort

    /** Note/card modification time, only when the backend reports it authoritatively (§25). */
    data class Modified(val direction: SortDirection) : AnkiCardSort

    data class Reps(val direction: SortDirection) : AnkiCardSort

    data class Lapses(val direction: SortDirection) : AnkiCardSort
}

/** The stable token used by capability checks, cursor binding and diagnostics. */
fun AnkiCardSort.sortToken(): String = when (this) {
    AnkiCardSort.Default -> "default"
    is AnkiCardSort.Due -> "due"
    is AnkiCardSort.Created -> "created"
    is AnkiCardSort.Modified -> "modified"
    is AnkiCardSort.Reps -> "reps"
    is AnkiCardSort.Lapses -> "lapses"
}

/** The capability a non-default sort requires, or `null` for [AnkiCardSort.Default]. */
fun AnkiCardSort.sortCapability(): AnkiCardSortCapability? = when (this) {
    AnkiCardSort.Default -> null
    is AnkiCardSort.Due -> AnkiCardSortCapability.DUE
    is AnkiCardSort.Created -> AnkiCardSortCapability.CREATED
    is AnkiCardSort.Modified -> AnkiCardSortCapability.MODIFIED
    is AnkiCardSort.Reps -> AnkiCardSortCapability.REPS
    is AnkiCardSort.Lapses -> AnkiCardSortCapability.LAPSES
}

/** Direction of a non-default sort; [AnkiCardSort.Default] has none. */
val AnkiCardSort?.directionOrNull: SortDirection?
    get() = when (this) {
        is AnkiCardSort.Due -> direction
        is AnkiCardSort.Created -> direction
        is AnkiCardSort.Modified -> direction
        is AnkiCardSort.Reps -> direction
        is AnkiCardSort.Lapses -> direction
        else -> null
    }

internal fun AnkiCardSort.canonicalToken(): String =
    sortCapability()?.let { "${it.name.lowercase()}:${directionOrNull?.name}" } ?: "default"

/**
 * Canonical search-text normalization: trim leading/trailing whitespace, empty → `null`, and
 * nothing else. Case, Unicode content and internal spacing are preserved exactly because the
 * browser contract treats [AnkiCardQuery.text] as literal user search text (§9).
 */
fun normalizeAnkiCardSearchText(value: String?): String? {
    if (value == null) return null
    val trimmed = value.trim { it.isWhitespace() || Character.isSpaceChar(it) }
    return trimmed.takeIf(String::isNotEmpty)
}

/**
 * Length-prefixed-ish escaping so a fingerprint cannot be forged by a value containing the
 * delimiter characters. Deterministic and platform independent.
 */
internal fun canonicalToken(value: String?): String {
    if (value == null) return "~"
    val builder = StringBuilder(value.length + 2)
    builder.append('"')
    for (character in value) {
        when (character) {
            '\\' -> builder.append("\\\\")
            '"' -> builder.append("\\\"")
            else -> builder.append(character)
        }
    }
    builder.append('"')
    return builder.toString()
}
