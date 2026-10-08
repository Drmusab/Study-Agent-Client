package com.studyagent.client.core.anki

/**
 * GATE 15 §61/§62 — structured card-browser capability truth.
 *
 * A coarse `CARD_FILTERS = true` flag cannot express "this backend can browse and search but
 * cannot filter by burial", and a capability that lies is worse than a missing one. Every field
 * below independently describes one query component, so the UI can preflight a query (§21) and a
 * backend can reject exactly what it cannot honour (§20).
 *
 * `capabilities` is a *claim by the backend*, never inferred from the backend's name. The default
 * value is "cannot browse anything", which is always the safe answer.
 */
data class AnkiCardBrowserCapabilities(
    /** §4 `AnkiCardScope.AllCards` — every card the collection can expose. */
    val canBrowseAllCards: Boolean = false,
    /** §5 `AnkiCardScope.Deck` — one deck by stable backend deck id. */
    val canBrowseDeck: Boolean = false,
    /** §5/§66 `includeChildren = true` through authoritative deck hierarchy. */
    val canIncludeChildDecks: Boolean = false,
    /** §7 literal user text search across backend-supported searchable text fields. */
    val canSearchText: Boolean = false,
    /** §12 filter families this backend can evaluate authoritatively. */
    val supportedFilters: Set<AnkiCardFilterCapability> = emptySet(),
    /** §22 sort keys this backend can order by (never includes [AnkiCardSort.Default]). */
    val supportedSorts: Set<AnkiCardSortCapability> = emptySet(),
    /** §36 exact total match count is available for a query. */
    val supportsTotalCount: Boolean = false,
    /** §29 largest page this backend accepts; a larger request is rejected, never clamped. */
    val maxPageSize: Int = AnkiPageRequest.MAX_LIMIT
) {
    init {
        require(maxPageSize >= AnkiPageRequest.MIN_LIMIT) { "A page size below the domain minimum is not usable" }
        require(!canIncludeChildDecks || canBrowseDeck) { "Child-deck inclusion requires deck browsing" }
        require(
            canBrowseAllCards || canBrowseDeck || supportedFilters.isEmpty()
        ) { "Filter capabilities require card browsing" }
        require(
            canBrowseAllCards || canBrowseDeck || supportedSorts.isEmpty()
        ) { "Sort capabilities require card browsing" }
        require(
            canBrowseAllCards || canBrowseDeck || (!canSearchText && !supportsTotalCount)
        ) { "Search/total capabilities require card browsing" }
    }

    /** True when this backend can serve at least one browse scope. */
    val canBrowse: Boolean get() = canBrowseAllCards || canBrowseDeck

    /** The largest *domain-legal* page size this backend accepts. */
    val effectiveMaxPageSize: Int get() = minOf(maxPageSize, AnkiPageRequest.MAX_LIMIT)

    fun supports(filter: AnkiCardFilterCapability): Boolean = filter in supportedFilters

    fun supports(sort: AnkiCardSortCapability): Boolean = sort in supportedSorts

    companion object {
        /** A backend that cannot browse at all — the safe default. */
        val NONE = AnkiCardBrowserCapabilities()
    }
}

/** §12 filter families a browser backend may advertise. */
enum class AnkiCardFilterCapability {
    FLAGS,
    TAGS,
    CARD_TYPES,
    SUSPENSION,
    BURIAL
}

/** §22 explicit sort keys a browser backend may advertise. */
enum class AnkiCardSortCapability {
    DUE,
    CREATED,
    MODIFIED,
    REPS,
    LAPSES
}

/**
 * GATE 01 contract — what one Anki backend can actually do (§16).
 *
 * UI and policy must consult these flags, never infer capability from the
 * backend name (INV-ANKI: capability truthfulness; §72 feature fallback).
 * A backend reporting `review = true` but `editNotes = false` is fully usable —
 * the edit affordance hides, the backend is not "unavailable".
 *
 * `capabilities` is deliberately flat and small. New capabilities are added
 * as new flags (defaulting to `false`, which is always the safe answer for a
 * backend that cannot do something).
 */
data class AnkiCapabilities(
    /**
     * Can serve due cards **and commit ratings** — the complete review loop (the minimum viable
     * backend).
     *
     * GATE 06 split this from [scheduledReview] rather than widening it: an implementation that
     * can ask the scheduler for the next card but cannot yet write a rating is genuinely usable
     * and genuinely *not* able to run a review loop, and a single flag would have had to lie about
     * one of the two (§75/§146/§147).
     */
    val review: Boolean = false,
    /**
     * Can ask the backend's scheduler which card to review next and represent it
     * (GATE 06 — scheduled review without rating mutation). Implies nothing about [review].
     */
    val scheduledReview: Boolean = false,
    /** Can list decks with backend-qualified identity (GATE 05 — read-only deck foundation). */
    val deckListing: Boolean = false,
    /**
     * Deck due counts are exposed *and their semantics were verified against the backend*.
     * A backend may still populate `AnkiDeck.counts` best-effort while this stays `false`; UI
     * must treat such counts as advisory and nullable (GATE 05 §13/§45).
     */
    val deckCounts: Boolean = false,
    val renderedCards: Boolean = false,
    val reviewIntervals: Boolean = false,
    val media: Boolean = false,
    val flags: Boolean = false,
    val bury: Boolean = false,
    /** Named `suspendCards` because `suspend` is a Kotlin keyword (Anki "suspend card"). */
    val suspendCards: Boolean = false,
    val editNotes: Boolean = false,
    val createNotes: Boolean = false,
    val search: Boolean = false,
    /**
     * Read-only card-browser features (§61/§62). Deliberately separate from [flags], which means
     * the backend can *mutate* flags through the reviewer-action contract; browsing may read and
     * filter flags without exposing any mutation path.
     */
    val cardBrowser: AnkiCardBrowserCapabilities = AnkiCardBrowserCapabilities.NONE
) {
    companion object {
        /** Safe default for a backend whose probe has not completed. */
        val NONE = AnkiCapabilities()
    }
}
