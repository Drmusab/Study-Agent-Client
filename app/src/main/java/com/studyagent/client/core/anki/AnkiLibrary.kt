package com.studyagent.client.core.anki

/**
 * GATE 05 — how much the current [LibrarySnapshot] can be trusted to mirror the backend.
 *
 * - [FRESH]: the most recent refresh succeeded and nothing since then suggests the backend moved.
 * - [STALE]: data is still shown, but a later refresh failed or the backend became unavailable
 *   after this snapshot was taken (stale-while-error, §53/§54).
 * - [UNKNOWN]: the repository cannot currently tell (backend availability is being re-checked).
 */
enum class LibraryFreshness { FRESH, STALE, UNKNOWN }

/**
 * GATE 05 — immutable, Library-ready projection of one backend's decks (§24).
 *
 * Scope is one backend and, when the backend exposes it, one collection ([collection]); the
 * repository never merges snapshots across either boundary (INV-ANKI-DECK-10). [decks] is in
 * [AnkiDeckOrder]; [tree] is derived from exactly that list. [selectedDeck] is the backend's own
 * current deck as *reported*, informational only — Study-Agent never writes it and it is not the
 * session deck (§14/§43). [refreshedAtMs] comes from the injected app clock.
 *
 * [lastRefreshError] coexists with data on purpose: a failed refresh keeps the previous decks
 * visible and marks them [LibraryFreshness.STALE] instead of blanking the Library (§54).
 */
data class LibrarySnapshot(
    val backendId: AnkiBackendId,
    val collection: AnkiCollectionIdentity?,
    val decks: List<AnkiDeck>,
    val tree: AnkiDeckTree,
    val selectedDeck: AnkiDeckRef?,
    val refreshedAtMs: Long,
    val freshness: LibraryFreshness,
    val lastRefreshError: AnkiError? = null,
    val lastRefreshLatencyMs: Long? = null
) {
    init {
        require(refreshedAtMs >= 0L)
        require(lastRefreshLatencyMs == null || lastRefreshLatencyMs >= 0L)
        require(collection == null || collection.backendId == backendId)
        require(selectedDeck == null || selectedDeck.backendId == backendId)
        require(decks.all { it.ref.backendId == backendId }) { "Snapshot decks must belong to $backendId" }
        require(decks.size == tree.deckCount) { "Tree must be derived from this snapshot's deck list" }
    }

    val deckCount: Int get() = decks.size

    /** Decks the backend *reported* as filtered; `isFiltered == null` decks are not counted. */
    val filteredDeckCount: Int get() = decks.count { it.isFiltered == true }

    /** Decks for which the backend reported any count at all. */
    val decksWithCounts: Int get() = decks.count { it.counts != null }

    val isEmpty: Boolean get() = decks.isEmpty()

    fun deck(ref: AnkiDeckRef): AnkiDeck? = decks.firstOrNull { it.ref == ref }

    /**
     * Backend-selected flag is informational (INV-ANKI-DECK-11). `null` when this snapshot does
     * not know which deck Anki currently has selected; never guessed from name or first deck.
     */
    fun isSelectedByBackend(ref: AnkiDeckRef): Boolean? =
        selectedDeck?.let { it == ref }

    fun summaries(): List<AnkiDeckSummary> = decks.map { deck ->
        AnkiDeckSummary(
            deck = deck,
            counts = deck.counts,
            isSelectedByBackend = isSelectedByBackend(deck.ref),
            isFiltered = deck.isFiltered
        )
    }

    /** Same data, now known to be behind the backend; [error] explains why when known. */
    fun markStale(error: AnkiError? = null): LibrarySnapshot =
        copy(freshness = LibraryFreshness.STALE, lastRefreshError = error ?: lastRefreshError)

    fun markUnknown(): LibrarySnapshot = copy(freshness = LibraryFreshness.UNKNOWN)
}

/**
 * Display-oriented projection of one deck inside a [LibrarySnapshot] (GATE 05 §15).
 *
 * Counts and the filtered flag are copied from [AnkiDeck] — they are nullable for the same
 * reason (`null` = the backend did not say). Description is omitted: AnkiDroid's provider
 * currently writes the *selected* deck's description onto every row, so it is not a per-deck
 * fact we can trust. [isSelectedByBackend] is Anki's current deck, not Study-Agent's session deck.
 */
data class AnkiDeckSummary(
    val deck: AnkiDeck,
    val counts: AnkiDeckCounts?,
    val isSelectedByBackend: Boolean?,
    val isFiltered: Boolean?
)

/**
 * GATE 05 — observable Library data state (§24/§56). One value, one `StateFlow`; no separate
 * flags that can disagree with each other.
 *
 * `Ready(snapshot with zero decks)` is a valid, successful state ("this collection has no decks")
 * and is never represented as [Failed] (§17). [Failed] exists only while no snapshot has ever
 * been produced for the backend; once data exists, later failures are carried *inside* the
 * snapshot ([LibrarySnapshot.lastRefreshError], [LibraryFreshness.STALE]) so the UI keeps
 * something to show (§54).
 */
sealed interface LibraryDataState {
    val snapshot: LibrarySnapshot?
    val isRefreshing: Boolean

    /** Nothing requested yet. */
    data object Idle : LibraryDataState {
        override val snapshot: LibrarySnapshot? get() = null
        override val isRefreshing: Boolean get() = false
    }

    /** First load in flight, nothing to show yet. */
    data object Loading : LibraryDataState {
        override val snapshot: LibrarySnapshot? get() = null
        override val isRefreshing: Boolean get() = true
    }

    /** Data present (fresh, stale or of unknown freshness); a refresh may be running behind it. */
    data class Ready(
        override val snapshot: LibrarySnapshot,
        override val isRefreshing: Boolean = false
    ) : LibraryDataState

    /** No snapshot could ever be produced; typed error, distinct from an empty library. */
    data class Failed(
        val error: AnkiError,
        val failedAtMs: Long,
        override val isRefreshing: Boolean = false
    ) : LibraryDataState {
        init { require(failedAtMs >= 0L) }
        override val snapshot: LibrarySnapshot? get() = null
    }
}
