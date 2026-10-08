package com.studyagent.client.anki.contract

import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiCardBrowserCapabilities
import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardListItem
import com.studyagent.client.core.anki.AnkiCardQuery
import com.studyagent.client.core.anki.AnkiCardScope
import com.studyagent.client.core.anki.AnkiCardSort
import com.studyagent.client.core.anki.AnkiCardType
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.AnkiPageRequest
import com.studyagent.client.core.anki.BurialFilter
import com.studyagent.client.core.anki.SortDirection
import com.studyagent.client.core.anki.SuspensionFilter

/**
 * GATE 15 §75 — the *fixture-side* view of one collection.
 *
 * Everything in [cards] and [deckParents] is the fixture's own definition of its collection: it is
 * never read back through the backend under test. The contract suite therefore compares the
 * backend's answers against an independently specified expectation instead of against the
 * implementation it is checking.
 */
class ContractFixture(
    val backend: AnkiBackend,
    val capabilities: AnkiCardBrowserCapabilities,
    val collectionKey: String?,
    val cards: List<ContractCard>,
    /** deckId → authoritative parent deckId (`null` = top level). */
    val deckParents: Map<String, String?>,
    /** A deck identity this collection does not contain. */
    val missingDeckId: String,
    /** A real deck that exists but holds no cards. */
    val emptyDeckId: String,
    /** Optional read-only probe; the fake supplies counters, other backends may not. */
    val readOnlyProbe: ContractReadOnlyProbe? = null
) {
    val deckIds: Set<String> get() = deckParents.keys

    fun subtree(deckId: String): Set<String> {
        val result = LinkedHashSet<String>()
        val pending = ArrayDeque(listOf(deckId))
        val children = deckParents.entries.groupBy({ it.value }, { it.key })
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!result.add(current)) continue
            children[current]?.forEach { pending += it }
        }
        return result
    }

    // ------------------------------------------------------------- independent query expectation

    /**
     * §19/§71 reference semantics: scope AND text AND flag filter AND tag filter AND card-type
     * filter AND suspension filter AND burial filter, computed over the whole fixture collection.
     */
    fun expected(query: AnkiCardQuery): List<ContractCard> {
        val scopeDeckIds: Set<String>? = when (val scope = query.scope) {
            AnkiCardScope.AllCards -> null
            is AnkiCardScope.Deck -> if (scope.includeChildren) subtree(scope.deckId) else setOf(scope.deckId)
        }
        val text = query.normalizedText
        val filters = query.filters
        val filtered = cards.filter { card ->
            (scopeDeckIds == null || card.deckId in scopeDeckIds) &&
                (text == null || card.searchableText.any { it.contains(text, ignoreCase = true) }) &&
                matches(filters.flags, card.flag) &&
                filters.tags.all { it in card.tags } &&
                matches(filters.cardTypes, card.type) &&
                when (filters.suspension) {
                    SuspensionFilter.Any -> true
                    SuspensionFilter.SuspendedOnly -> card.suspended == true
                    SuspensionFilter.NotSuspended -> card.suspended == false
                } &&
                when (filters.burial) {
                    BurialFilter.Any -> true
                    BurialFilter.BuriedOnly -> card.buried == true
                    BurialFilter.NotBuried -> card.buried == false
                }
        }
        return filtered.sortedWith(referenceComparator(query.sort))
    }

    private fun <T> matches(selection: Set<T>, value: T?): Boolean =
        selection.isEmpty() || (value != null && value in selection)

    /**
     * §26 reference ordering: requested key first (missing keys last), stable card identity as the
     * deterministic tie-breaker. This is written from the fixture data only.
     */
    private fun referenceComparator(sort: AnkiCardSort): Comparator<ContractCard> = when (sort) {
        AnkiCardSort.Default -> Comparator { first, second ->
            // The backend owns its default order; the fixture checks it is a stable permutation.
            first.defaultOrder.compareTo(second.defaultOrder)
        }
        is AnkiCardSort.Due -> keyComparator(sort.direction) { it.due }
        is AnkiCardSort.Created -> keyComparator(sort.direction) { it.created }
        is AnkiCardSort.Modified -> keyComparator(sort.direction) { it.modified }
        is AnkiCardSort.Reps -> keyComparator(sort.direction) { it.reps?.toLong() }
        is AnkiCardSort.Lapses -> keyComparator(sort.direction) { it.lapses?.toLong() }
    }

    private fun <T : Comparable<T>> keyComparator(
        direction: SortDirection,
        key: (ContractCard) -> T?
    ): Comparator<ContractCard> = Comparator { first, second ->
        val left = key(first)
        val right = key(second)
        val primary = when {
            left == null && right == null -> 0
            left == null -> 1
            right == null -> -1
            direction == SortDirection.ASCENDING -> left.compareTo(right)
            else -> right.compareTo(left)
        }
        if (primary != 0) primary else first.cardRef.stableKey.compareTo(second.cardRef.stableKey)
    }

    fun page(query: AnkiCardQuery, cursorOffset: Int): List<ContractCard> {
        val expected = expected(query)
        return expected.drop(cursorOffset).take(query.page.limit)
    }
}

/** One fixture card, expressed independently of the backend under test. */
data class ContractCard(
    val cardRef: com.studyagent.client.core.anki.AnkiCardRef,
    val noteId: String,
    val deckId: String,
    val tags: Set<String>,
    val flag: AnkiFlag?,
    val type: AnkiCardType?,
    val suspended: Boolean?,
    val buried: Boolean?,
    val reps: Int?,
    val lapses: Int?,
    val due: Long?,
    val created: Long?,
    val modified: Long?,
    val searchableText: List<String>,
    val defaultOrder: Int
) {
    /** Assert-safe view of a backend row: identity must always be present (§43). */
    companion object {
        fun identityOf(item: AnkiCardListItem): Pair<String, String>? {
            val noteId = item.noteId ?: return null
            val deckId = item.deckId ?: return null
            return noteId to deckId
        }

    }
}

/**
 * Read-only probe for the invariants that need counters: a browse must never cross the mutation
 * boundary, and cancellation must be observable without a user-visible query error.
 */
interface ContractReadOnlyProbe {
    val commitInvocations: Int
    val backendEffectCount: Int
    val browseCalls: Int

    /** Holds the next browse call inside the backend until the returned gate completes. */
    fun holdNextBrowse(): Gate

    interface Gate {
        /** Lets the held call continue; the test asserts what happened while it was held. */
        fun release()
    }
}
