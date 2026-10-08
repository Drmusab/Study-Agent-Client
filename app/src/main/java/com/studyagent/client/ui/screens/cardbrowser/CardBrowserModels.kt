package com.studyagent.client.ui.screens.cardbrowser

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardBrowserCapabilities
import com.studyagent.client.core.anki.AnkiCardFilterCapability
import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardListItem
import com.studyagent.client.core.anki.AnkiCardScope
import com.studyagent.client.core.anki.AnkiCardSort
import com.studyagent.client.core.anki.AnkiCardSortCapability
import com.studyagent.client.core.anki.AnkiCardType
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.AnkiSchedulingInfo
import com.studyagent.client.core.anki.BurialFilter
import com.studyagent.client.core.anki.SortDirection
import com.studyagent.client.core.anki.SuspensionFilter

/**
 * Query controls as user-visible state. The scope is a domain [AnkiCardScope] — never a nullable
 * deck id, which would make "global" and "selected deck" indistinguishable. Cursor, page size and
 * fingerprint stay ViewModel/backend concerns and never appear here.
 */
data class CardBrowserQueryUi(
    val scope: AnkiCardScope = AnkiCardScope.AllCards,
    val searchText: String = "",
    val filters: AnkiCardFilters = AnkiCardFilters(),
    val sort: AnkiCardSort = AnkiCardSort.Default
) {
    /** The exact deck identity this query is bound to, or `null` for the all-cards scope. */
    val deckId: String? get() = (scope as? AnkiCardScope.Deck)?.deckId

    val hasSearchOrFilters: Boolean
        get() = searchText.isNotBlank() || !filters.isEmpty

    companion object {
        /** Deck-scoped browser entry point used by Deck Details (§66: children included by default). */
        fun forDeck(deckId: String, includeChildren: Boolean = true): CardBrowserQueryUi =
            CardBrowserQueryUi(scope = AnkiCardScope.Deck(deckId = deckId, includeChildren = includeChildren))
    }
}

/** Display-only projection. The original AnkiCardListItem / full card never reaches Compose. */
data class CardBrowserRow(
    val cardRef: com.studyagent.client.core.anki.AnkiCardRef,
    val questionPreview: String?,
    val answerPreview: String?,
    val deckName: String?,
    val tags: List<String>,
    val flag: AnkiFlag?,
    val type: AnkiCardType?,
    val scheduling: AnkiSchedulingInfo?,
    val suspended: Boolean?,
    val buried: Boolean?
) {
    val stableKey: String get() = cardRef.stableKey

    companion object {
        fun from(item: AnkiCardListItem, answerPreviewAllowed: Boolean): CardBrowserRow = CardBrowserRow(
            cardRef = item.cardRef,
            questionPreview = item.questionText?.let(::safePreview),
            answerPreview = if (answerPreviewAllowed) item.answerText?.let(::safePreview) else null,
            deckName = item.deckName?.let(::safePreview),
            tags = item.tags.take(3).map(::safePreview).filter(String::isNotBlank),
            flag = item.flag,
            type = item.type,
            scheduling = item.scheduling,
            suspended = item.suspended,
            buried = item.buried
        )

        /**
         * Enforce a text-only row boundary even if a buggy adapter sends markup in a preview field.
         * This is intentionally not an HTML renderer or a full HTML parser; Compose always receives
         * ordinary text and can never execute provider markup or load media.
         */
        private fun safePreview(value: String): String {
            val textOnly = value
                .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>"), " ")
                .replace(Regex("<[^>]*>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
            val normalized = buildString(textOnly.length) {
                var pendingSpace = false
                textOnly.forEach { character ->
                    if (character.isWhitespace() || Character.isSpaceChar(character)) {
                        if (isNotEmpty()) pendingSpace = true
                    } else {
                        if (pendingSpace) append(' ')
                        append(character)
                        pendingSpace = false
                    }
                }
            }
            return if (normalized.length <= PREVIEW_MAX_CHARS) normalized
            else normalized.take(PREVIEW_MAX_CHARS - 1).trimEnd() + "…"
        }

        private const val PREVIEW_MAX_CHARS = 220
    }
}

/**
 * One rendered filter affordance. [key] is the *only* thing the UI sends back to
 * [AnkiCardFilters.toggleChip]; it never encodes backend query syntax (§10) and never carries a
 * cursor or page identity.
 */
data class FilterChipModel(
    val key: String,
    val label: String,
    val selected: Boolean,
    val removable: Boolean = false,
    val contentDescription: String = label
)

/** One labelled filter family. The family is rendered only when the backend advertises it (§21). */
data class FilterChipGroup(
    val key: String,
    val label: String,
    val chips: List<FilterChipModel>
)

data class SortOptionModel(
    val sort: AnkiCardSort,
    val label: String
)

/** One canonical state; paging activity only exists inside Ready. */
sealed interface CardBrowserUiState {
    val query: CardBrowserQueryUi
    val capabilities: AnkiCardBrowserCapabilities

    data class Loading(
        override val query: CardBrowserQueryUi,
        override val capabilities: AnkiCardBrowserCapabilities
    ) : CardBrowserUiState

    data class Ready(
        val rows: List<CardBrowserRow>,
        override val query: CardBrowserQueryUi,
        override val capabilities: AnkiCardBrowserCapabilities,
        val hasMore: Boolean,
        val isLoadingMore: Boolean,
        val totalCount: Int?,
        val appendError: AnkiError? = null
    ) : CardBrowserUiState

    data class Empty(
        override val query: CardBrowserQueryUi,
        val reason: Reason,
        override val capabilities: AnkiCardBrowserCapabilities,
        val totalCount: Int? = null
    ) : CardBrowserUiState {
        enum class Reason { NO_CARDS_IN_SCOPE, NO_MATCHES }
    }

    data class Unavailable(
        override val query: CardBrowserQueryUi,
        val backendId: AnkiBackendId,
        val availability: AnkiAvailability,
        override val capabilities: AnkiCardBrowserCapabilities,
        /** Non-null for a Ready backend that does not advertise the requested browse capability. */
        val unsupportedFeature: String? = null
    ) : CardBrowserUiState

    data class Error(
        override val query: CardBrowserQueryUi,
        val backendId: AnkiBackendId,
        val error: AnkiError,
        override val capabilities: AnkiCardBrowserCapabilities
    ) : CardBrowserUiState
}

/** Sort label including the explicit direction, because direction is part of the query (§22). */
fun AnkiCardSort.displayLabel(): String = when (this) {
    AnkiCardSort.Default -> "Default order"
    is AnkiCardSort.Due -> "Due ${direction.arrow()}"
    is AnkiCardSort.Created -> "Created ${direction.arrow()}"
    is AnkiCardSort.Modified -> "Modified ${direction.arrow()}"
    is AnkiCardSort.Reps -> "Repetitions ${direction.arrow()}"
    is AnkiCardSort.Lapses -> "Lapses ${direction.arrow()}"
}

private fun SortDirection.arrow(): String = when (this) {
    SortDirection.ASCENDING -> "↑"
    SortDirection.DESCENDING -> "↓"
}

/**
 * §12-§19 filter affordances derived from structured capabilities. A family the backend does not
 * advertise is not rendered at all — the UI never offers a control whose semantics the backend
 * would have to ignore or approximate. Empty selection means "no restriction of this kind".
 */
fun filterChipGroups(
    capabilities: AnkiCardBrowserCapabilities,
    filters: AnkiCardFilters
): List<FilterChipGroup> = buildList {
    if (AnkiCardFilterCapability.FLAGS in capabilities.supportedFilters) {
        add(
            FilterChipGroup(
                key = "flags",
                label = "Flag",
                chips = AnkiFlag.entries.map { flag ->
                    FilterChipModel(
                        key = CardBrowserFilterKeys.flag(flag),
                        label = flag.chipLabel(),
                        selected = flag in filters.flags,
                        contentDescription = "${flag.chipLabel()} flag filter"
                    )
                }
            )
        )
    }
    if (AnkiCardFilterCapability.CARD_TYPES in capabilities.supportedFilters) {
        add(
            FilterChipGroup(
                key = "cardTypes",
                label = "Card type",
                chips = AnkiCardType.entries.map { type ->
                    FilterChipModel(
                        key = CardBrowserFilterKeys.cardType(type),
                        label = type.chipLabel(),
                        selected = type in filters.cardTypes,
                        contentDescription = "${type.chipLabel()} card type filter"
                    )
                }
            )
        )
    }
    if (AnkiCardFilterCapability.TAGS in capabilities.supportedFilters) {
        add(
            FilterChipGroup(
                key = "tags",
                label = "Tags (all must match)",
                chips = filters.tags.sorted().map { tag ->
                    FilterChipModel(
                        key = CardBrowserFilterKeys.tag(tag),
                        label = tag,
                        selected = true,
                        removable = true,
                        contentDescription = "Remove tag filter $tag"
                    )
                }
            )
        )
    }
    if (AnkiCardFilterCapability.SUSPENSION in capabilities.supportedFilters) {
        add(
            FilterChipGroup(
                key = "suspension",
                label = "Suspension",
                chips = listOf(triStateChip(CardBrowserFilterKeys.SUSPENSION, "Suspended", filters.suspension))
            )
        )
    }
    if (AnkiCardFilterCapability.BURIAL in capabilities.supportedFilters) {
        add(
            FilterChipGroup(
                key = "burial",
                label = "Burial",
                chips = listOf(triStateChip(CardBrowserFilterKeys.BURIAL, "Buried", filters.burial))
            )
        )
    }
}

/**
 * The single activation path for every chip: the chip key decides which category toggles. Flags and
 * card types are OR sets, tags are an AND set, suspension/burial cycle through their explicit
 * tri-state — a nullable boolean is never used, so "any" can never be confused with "exclude".
 */
fun AnkiCardFilters.toggleChip(key: String): AnkiCardFilters = when {
    key.startsWith(CardBrowserFilterKeys.FLAG_PREFIX) ->
        CardBrowserFilterKeys.parseFlag(key)?.let { flag ->
            copy(flags = if (flag in flags) flags - flag else flags + flag)
        } ?: this
    key.startsWith(CardBrowserFilterKeys.TYPE_PREFIX) ->
        CardBrowserFilterKeys.parseCardType(key)?.let { type ->
            copy(cardTypes = if (type in cardTypes) cardTypes - type else cardTypes + type)
        } ?: this
    key.startsWith(CardBrowserFilterKeys.TAG_PREFIX) -> {
        val tag = key.removePrefix(CardBrowserFilterKeys.TAG_PREFIX)
        if (tag in tags) copy(tags = tags - tag) else this
    }
    key == CardBrowserFilterKeys.SUSPENSION -> copy(suspension = filtersSuspensionCycle(suspension))
    key == CardBrowserFilterKeys.BURIAL -> copy(burial = filtersBurialCycle(burial))
    else -> this
}

/** Exact tag add: the value is user text, never backend query syntax. */
fun AnkiCardFilters.withTag(tag: String): AnkiCardFilters {
    val normalized = tag.trim()
    return if (normalized.isEmpty() || normalized in tags) this else copy(tags = tags + normalized)
}

/** Advertised sort keys, each with both explicit directions (§22) plus the backend default order. */
fun sortOptions(capabilities: AnkiCardBrowserCapabilities): List<SortOptionModel> = buildList {
    add(SortOptionModel(AnkiCardSort.Default, AnkiCardSort.Default.displayLabel()))
    AnkiCardSortCapability.entries
        .filter(capabilities.supportedSorts::contains)
        .forEach { capability ->
            SortDirection.entries.forEach { direction ->
                val sort = capability.sort(direction)
                add(SortOptionModel(sort, sort.displayLabel()))
            }
        }
}

/** Keys are UI-local identifiers; they never contain backend query syntax, ids or cursors. */
object CardBrowserFilterKeys {
    const val FLAG_PREFIX = "filter.flag:"
    const val TYPE_PREFIX = "filter.type:"
    const val TAG_PREFIX = "filter.tag:"
    const val SUSPENSION = "filter.suspension"
    const val BURIAL = "filter.burial"

    fun flag(flag: AnkiFlag): String = FLAG_PREFIX + flag.name
    fun cardType(type: AnkiCardType): String = TYPE_PREFIX + type.name
    fun tag(tag: String): String = TAG_PREFIX + tag

    internal fun parseFlag(key: String): AnkiFlag? =
        AnkiFlag.entries.firstOrNull { flag(it) == key }

    internal fun parseCardType(key: String): AnkiCardType? =
        AnkiCardType.entries.firstOrNull { cardType(it) == key }
}

private fun triStateChip(key: String, label: String, suspension: SuspensionFilter): FilterChipModel =
    FilterChipModel(
        key = key,
        label = "$label: ${suspension.chipLabel()}",
        selected = suspension != SuspensionFilter.Any,
        contentDescription = "$label filter, ${suspension.chipLabel()}. Activate to cycle."
    )

private fun triStateChip(key: String, label: String, burial: BurialFilter): FilterChipModel =
    FilterChipModel(
        key = key,
        label = "$label: ${burial.chipLabel()}",
        selected = burial != BurialFilter.Any,
        contentDescription = "$label filter, ${burial.chipLabel()}. Activate to cycle."
    )

private fun SuspensionFilter.chipLabel(): String = when (this) {
    SuspensionFilter.Any -> "Any"
    SuspensionFilter.SuspendedOnly -> "Only"
    SuspensionFilter.NotSuspended -> "Exclude"
}

private fun BurialFilter.chipLabel(): String = when (this) {
    BurialFilter.Any -> "Any"
    BurialFilter.BuriedOnly -> "Only"
    BurialFilter.NotBuried -> "Exclude"
}

internal fun filtersSuspensionCycle(value: SuspensionFilter): SuspensionFilter = when (value) {
    SuspensionFilter.Any -> SuspensionFilter.SuspendedOnly
    SuspensionFilter.SuspendedOnly -> SuspensionFilter.NotSuspended
    SuspensionFilter.NotSuspended -> SuspensionFilter.Any
}

internal fun filtersBurialCycle(value: BurialFilter): BurialFilter = when (value) {
    BurialFilter.Any -> BurialFilter.BuriedOnly
    BurialFilter.BuriedOnly -> BurialFilter.NotBuried
    BurialFilter.NotBuried -> BurialFilter.Any
}

private fun AnkiCardSortCapability.sort(direction: SortDirection): AnkiCardSort = when (this) {
    AnkiCardSortCapability.DUE -> AnkiCardSort.Due(direction)
    AnkiCardSortCapability.CREATED -> AnkiCardSort.Created(direction)
    AnkiCardSortCapability.MODIFIED -> AnkiCardSort.Modified(direction)
    AnkiCardSortCapability.REPS -> AnkiCardSort.Reps(direction)
    AnkiCardSortCapability.LAPSES -> AnkiCardSort.Lapses(direction)
}

private fun AnkiFlag.chipLabel(): String = when (this) {
    AnkiFlag.NONE -> "No flag"
    AnkiFlag.RED -> "Red"
    AnkiFlag.ORANGE -> "Orange"
    AnkiFlag.GREEN -> "Green"
    AnkiFlag.BLUE -> "Blue"
    AnkiFlag.PINK -> "Pink"
    AnkiFlag.TURQUOISE -> "Turquoise"
    AnkiFlag.PURPLE -> "Purple"
    AnkiFlag.UNKNOWN -> "Unknown flag"
}

private fun AnkiCardType.chipLabel(): String = when (this) {
    AnkiCardType.NEW -> "New"
    AnkiCardType.LEARNING -> "Learning"
    AnkiCardType.REVIEW -> "Review"
    AnkiCardType.RELEARNING -> "Relearning"
    AnkiCardType.UNKNOWN -> "Unknown type"
}
