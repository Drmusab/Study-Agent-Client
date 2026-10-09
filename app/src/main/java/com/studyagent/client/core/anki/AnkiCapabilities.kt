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
     * GATE 16 — can serve [AnkiBackend.getCardDetails] (deep, read-only details of one exact card).
     * Separate from [renderedCards] (GATE 07 content hydration) because a backend may render cards
     * for review yet expose no note fields/tags/details surface — and a capability that lies is
     * worse than a missing one. Per-section truth (fields, scheduling, flags, media) is carried by
     * the details model's own nullability, so no further detail granularity is claimed here
     * (GATE 16 CHECKPOINT 04: only where the capability system requires it).
     */
    val cardDetails: Boolean = false,
    /**
     * Read-only card-browser features (§61/§62). Deliberately separate from [flags], which means
     * the backend can *mutate* flags through the reviewer-action contract; browsing may read and
     * filter flags without exposing any mutation path.
     */
    val cardBrowser: AnkiCardBrowserCapabilities = AnkiCardBrowserCapabilities.NONE,
    /**
     * GATE 17 — can replace the field values of one existing note through the backend's public
     * write path. Each dimension is claimed separately: a backend that can edit tags but not
     * fields must say so, and the editor disables the controls it cannot honour. The coarse
     * [editNotes] flag stays `false` in GATE 17; it is not a substitute for these.
     */
    val editNoteFields: Boolean = false,
    /** GATE 17 — can replace the full tag set of one existing note (see [editNoteFields]). */
    val editNoteTags: Boolean = false,
    /** GATE 17 — can move one card to another existing, non-filtered deck by stable deck id. */
    val changeCardDeck: Boolean = false,
    /**
     * GATE 17 — can authoritatively reconcile an ambiguous note mutation (read-only evidence that
     * a previously submitted write did or did not apply). `false` means an ambiguous outcome is
     * shown for user verification and never auto-resolved.
     */
    val authoritativeMutationReconciliation: Boolean = false,
    /** GATE 17 — how strongly the backend protects a note edit against concurrent changes. */
    val noteEditConflictGuarantee: NoteConflictGuarantee = NoteConflictGuarantee.NONE,

    /**
     * GATE 18 — can list note types with their authoritative creation schema (ordered fields,
     * kind, template count, stored default deck). The creation UI is built on this read
     * (INV-18-04); without it, creation is refused rather than guessed.
     */
    val noteModelListing: Boolean = false,

    /**
     * GATE 18 — can store one media file through the backend's PUBLIC media API and report the
     * authoritative stored name (CONTRACT-18-09). False means the media attachment surface is
     * absent — never approximated by writing into Anki's private storage (INV-18-11/12).
     */
    val storeMedia: Boolean = false,

    /**
     * GATE 18 — can authoritatively resolve whether an ambiguous creation produced a note.
     * `false` (everywhere at this pin) means an ambiguous creation is shown for user attestation
     * and never auto-resolved — no heuristic search is ever treated as proof (CONTRACT-18-20/21).
     */
    val authoritativeCreationReconciliation: Boolean = false
) {
    companion object {
        /** Safe default for a backend whose probe has not completed. */
        val NONE = AnkiCapabilities()
    }
}

/**
 * GATE 17 — the concurrency protection a backend can honestly claim for a note edit.
 *
 * - [NONE]: no protection; the editor must say so.
 * - [BEST_EFFORT_PRE_SAVE_REREAD]: the adapter re-reads the note immediately before its write and
 *   refuses on drift. A change between that read and the write is NOT excluded.
 * - [ATOMIC_COMPARE_AND_SET]: the write itself is conditional on the base state. No backend in
 *   GATE 17 claims this.
 */
enum class NoteConflictGuarantee {
    NONE,
    BEST_EFFORT_PRE_SAVE_REREAD,
    ATOMIC_COMPARE_AND_SET
}
