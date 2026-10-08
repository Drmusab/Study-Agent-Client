package com.studyagent.client.anki.contract

import com.studyagent.client.core.anki.AnkiCardBrowserCapabilities
import com.studyagent.client.core.anki.AnkiCardFilterCapability
import com.studyagent.client.core.anki.AnkiCardFilters
import com.studyagent.client.core.anki.AnkiCardPage
import com.studyagent.client.core.anki.AnkiCardQuery
import com.studyagent.client.core.anki.AnkiCardScope
import com.studyagent.client.core.anki.AnkiCardSort
import com.studyagent.client.core.anki.AnkiCardSortCapability
import com.studyagent.client.core.anki.AnkiCardType
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.AnkiPageCursor
import com.studyagent.client.core.anki.AnkiPageRequest
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.BurialFilter
import com.studyagent.client.core.anki.SortDirection
import com.studyagent.client.core.anki.SuspensionFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * GATE 15 §75/§76 — the exact backend contract suite.
 *
 * One abstract suite, reusable for every [com.studyagent.client.core.anki.AnkiBackend]
 * implementation (the deterministic test fake today, AnkiDroid/PcAnki once they implement the
 * contract). A subclass only has to describe its collection and its advertised capabilities; every
 * semantic rule below is asserted here, so two backends cannot quietly disagree about what a query
 * means.
 *
 * The suite deliberately writes its expectations from the fixture (§75) and from the *capability*
 * set, never from the backend's output: when a capability is missing the backend must **reject**
 * the request, and the suite proves that by asserting the typed refusal rather than a silently
 * smaller page (§20/§76).
 */
abstract class AnkiCardBrowserContractSuite {

    /** A fresh backend + its fixture-side collection description for one test case. */
    protected abstract fun fixture(): ContractFixture

    private fun query(
        fixture: ContractFixture,
        scope: AnkiCardScope = AnkiCardScope.AllCards,
        text: String? = null,
        filters: AnkiCardFilters = AnkiCardFilters(),
        sort: AnkiCardSort = AnkiCardSort.Default,
        limit: Int = AnkiPageRequest.DEFAULT_LIMIT,
        cursor: AnkiPageCursor? = null
    ) = AnkiCardQuery(
        scope = scope,
        text = text,
        filters = filters,
        sort = sort,
        page = AnkiPageRequest(limit = limit, cursor = cursor)
    )

    private suspend fun pageOf(fixture: ContractFixture, query: AnkiCardQuery): AnkiCardPage =
        when (val result = fixture.backend.browseCards(query)) {
            is AnkiResult.Success -> result.value
            is AnkiResult.Failure -> fail("Expected a page for $query but got ${result.error}").let { error("unreachable") }
        }

    private suspend fun errorOf(fixture: ContractFixture, query: AnkiCardQuery): AnkiError =
        when (val result = fixture.backend.browseCards(query)) {
            is AnkiResult.Success -> fail("Expected a typed refusal for $query but got ${result.value.items.size} rows")
                .let { error("unreachable") }
            is AnkiResult.Failure -> result.error
        }

    private fun assertPageShape(page: AnkiCardPage) {
        // §38/§35 — the structural paging invariant every backend must satisfy.
        if (page.items.isEmpty()) assertNull("An empty page must not carry a cursor", page.nextCursor)
        assertEquals(
            "Every row must be addressable",
            page.items.size,
            page.items.count { ContractCard.identityOf(it) != null && it.deckRef != null }
        )
        page.items.forEach { item ->
            // §47 — no full-render payload may travel in a browse page.
            assertTrue("Browse rows carry plain-text previews only", item.questionText?.contains("<") != true)
        }
    }

    // ---------------------------------------------------------------------------- §75 case list

    @Test
    fun `valid empty query returns a bounded first page`() = runBlocking {
        val fixture = fixture()
        val page = pageOf(fixture, query(fixture, limit = 10))
        assertPageShape(page)
        assertTrue(page.items.size <= 10)
        if (fixture.capabilities.canBrowseAllCards) {
            assertEquals(
                fixture.expected(query(fixture, limit = 10)).map { it.cardRef },
                page.items.map { it.cardRef }
            )
            if (fixture.capabilities.supportsTotalCount) {
                assertEquals(fixture.cards.size, page.totalCount)
            } else {
                assertNull("totalCount is unavailable, not zero", page.totalCount)
            }
        }
    }

    @Test
    fun `deck scope returns exactly that deck`() = runBlocking {
        val fixture = fixture()
        val deckId = fixture.cards.first().deckId
        val request = query(fixture, scope = AnkiCardScope.Deck(deckId, includeChildren = false), limit = 100)
        if (!fixture.capabilities.canBrowseDeck || !fixture.capabilities.canIncludeChildDecks) {
            val error = errorOf(fixture, request)
            assertTrue("Unsupported deck scope is refused", error is AnkiError.UnsupportedQueryFeature)
            return@runBlocking
        }
        val page = pageOf(fixture, request)
        assertEquals(fixture.expected(request).map { it.cardRef }, page.items.map { it.cardRef })
        assertTrue(page.items.all { it.deckId == deckId })
    }

    @Test
    fun `deck scope with children uses the authoritative hierarchy`() = runBlocking {
        val fixture = fixture()
        val top = fixture.deckParents.entries.first { it.value == null }.key
        val withChildren = query(fixture, scope = AnkiCardScope.Deck(top, includeChildren = true), limit = 100)
        if (!fixture.capabilities.canBrowseDeck || !fixture.capabilities.canIncludeChildDecks) {
            assertTrue(errorOf(fixture, withChildren) is AnkiError.UnsupportedQueryFeature)
            return@runBlocking
        }
        val childPage = pageOf(fixture, withChildren)
        assertEquals(fixture.expected(withChildren).map { it.cardRef }, childPage.items.map { it.cardRef })

        val exact = withChildren.copy(scope = AnkiCardScope.Deck(top, includeChildren = false))
        val exactPage = pageOf(fixture, exact)
        assertEquals(fixture.expected(exact).map { it.cardRef }, exactPage.items.map { it.cardRef })
        assertTrue(
            "Exact scope can never exceed the subtree scope",
            exactPage.items.size <= childPage.items.size
        )
    }

    @Test
    fun `text search matches searchable text only and preserves case`() = runBlocking {
        val fixture = fixture()
        val sample = fixture.cards.first { it.searchableText.any { text -> text.length > 8 } }
        val needle = sample.searchableText.first().trim().take(12)
        val request = query(fixture, text = needle.uppercase(), limit = 100)
        if (!fixture.capabilities.canSearchText) {
            assertEquals("card_search", (errorOf(fixture, request) as AnkiError.UnsupportedQueryFeature).feature)
            return@runBlocking
        }
        val page = pageOf(fixture, request)
        val expected = fixture.expected(request)
        assertEquals(expected.map { it.cardRef }, page.items.map { it.cardRef })
        assertTrue(page.items.isNotEmpty())
    }

    @Test
    fun `text search never matches deck names or tags`() = runBlocking {
        val fixture = fixture()
        val tagOnlyToken = fixture.cards
            .firstOrNull { card -> card.tags.any { tag -> tag.length >= 6 } }
            ?.tags?.first { it.length >= 6 }
            ?: return@runBlocking
        val request = query(fixture, text = tagOnlyToken, limit = 100)
        if (!fixture.capabilities.canSearchText) {
            assertTrue(errorOf(fixture, request) is AnkiError.UnsupportedQueryFeature)
            return@runBlocking
        }
        val page = pageOf(fixture, request)
        assertEquals(
            "Searching text must be text-only: the expectation excludes tag/deck-only matches",
            fixture.expected(request).map { it.cardRef },
            page.items.map { it.cardRef }
        )
        assertTrue(
            "A row that only matched through its tag or deck name must not be returned",
            page.items.all { item ->
                item.questionText?.contains(tagOnlyToken, ignoreCase = true) == true ||
                    item.answerText?.contains(tagOnlyToken, ignoreCase = true) == true
            }
        )
    }

    @Test
    fun `text plus deck scope combines with AND`() = runBlocking {
        val fixture = fixture()
        val deckId = fixture.cards.first().deckId
        val request = query(
            fixture,
            scope = AnkiCardScope.Deck(deckId, includeChildren = true),
            text = "e",
            limit = 100
        )
        if (!fixture.capabilities.canBrowseDeck || !fixture.capabilities.canIncludeChildDecks ||
            !fixture.capabilities.canSearchText
        ) {
            assertTrue(errorOf(fixture, request) is AnkiError.UnsupportedQueryFeature)
            return@runBlocking
        }
        val page = pageOf(fixture, request)
        assertEquals(fixture.expected(request).map { it.cardRef }, page.items.map { it.cardRef })
        assertTrue(page.items.all { it.deckId in fixture.subtree(deckId) })
    }

    @Test
    fun `multiple flags combine with OR`() = runBlocking {
        val fixture = fixture()
        val flags = fixture.cards.mapNotNull { it.flag }.distinct().take(2).toSet()
        if (flags.size < 2) return@runBlocking
        val request = query(fixture, filters = AnkiCardFilters(flags = flags), limit = 100)
        if (!fixture.capabilities.supports(AnkiCardFilterCapability.FLAGS)) {
            assertEquals(
                "card_filter_flags",
                (errorOf(fixture, request) as AnkiError.UnsupportedQueryFeature).feature
            )
            return@runBlocking
        }
        val page = pageOf(fixture, request)
        assertEquals(fixture.expected(request).map { it.cardRef }, page.items.map { it.cardRef })
        assertTrue(page.items.all { it.flag in flags })
        assertTrue("An OR of two flags returns more than one flag's worth", page.items.mapNotNull { it.flag }.distinct().size >= 2)
    }

    @Test
    fun `multiple tags combine with AND`() = runBlocking {
        val fixture = fixture()
        val shared = fixture.cards.firstOrNull { card -> card.tags.size >= 2 }?.tags?.take(2)?.toSet()
            ?: fixture.cards.firstOrNull { it.tags.size == 1 }?.tags
            ?: return@runBlocking
        val request = query(fixture, filters = AnkiCardFilters(tags = shared), limit = 100)
        if (!fixture.capabilities.supports(AnkiCardFilterCapability.TAGS)) {
            assertEquals("card_filter_tags", (errorOf(fixture, request) as AnkiError.UnsupportedQueryFeature).feature)
            return@runBlocking
        }
        val page = pageOf(fixture, request)
        assertEquals(fixture.expected(request).map { it.cardRef }, page.items.map { it.cardRef })
        assertTrue(page.items.all { item -> shared.all { it in item.tags } })

        if (shared.size >= 2) {
            val single = query(fixture, filters = AnkiCardFilters(tags = setOf(shared.first())), limit = 100)
            val singlePage = pageOf(fixture, single)
            assertTrue(
                "AND semantics can never return more rows than a single tag",
                page.items.size <= singlePage.items.size
            )
        }
    }

    @Test
    fun `filter categories combine with AND`() = runBlocking {
        val fixture = fixture()
        val tag = fixture.cards.first { it.tags.isNotEmpty() }.tags.first()
        val categories = query(
            fixture,
            filters = AnkiCardFilters(
                flags = setOfNotNull(fixture.cards.firstNotNullOfOrNull { it.flag }),
                tags = setOf(tag),
                suspension = SuspensionFilter.NotSuspended
            ),
            limit = 100
        )
        val tagOnly = query(fixture, filters = AnkiCardFilters(tags = setOf(tag)), limit = 100)
        if (!fixture.capabilities.supports(AnkiCardFilterCapability.TAGS) ||
            !fixture.capabilities.supports(AnkiCardFilterCapability.FLAGS) ||
            !fixture.capabilities.supports(AnkiCardFilterCapability.SUSPENSION)
        ) {
            assertTrue(errorOf(fixture, categories) is AnkiError.UnsupportedQueryFeature)
            return@runBlocking
        }
        val combined = pageOf(fixture, categories)
        val single = pageOf(fixture, tagOnly)
        assertEquals(fixture.expected(categories).map { it.cardRef }, combined.items.map { it.cardRef })
        assertTrue("AND can never widen a result set", combined.items.size <= single.items.size)
    }

    @Test
    fun `tri-state suspension and burial filters are exact predicates`() = runBlocking {
        val fixture = fixture()
        val suspended = query(fixture, filters = AnkiCardFilters(suspension = SuspensionFilter.SuspendedOnly), limit = 100)
        val notSuspended = query(fixture, filters = AnkiCardFilters(suspension = SuspensionFilter.NotSuspended), limit = 100)
        if (!fixture.capabilities.supports(AnkiCardFilterCapability.SUSPENSION)) {
            assertEquals("card_filter_suspended", (errorOf(fixture, suspended) as AnkiError.UnsupportedQueryFeature).feature)
        } else {
            assertTrue(pageOf(fixture, suspended).items.all { it.suspended == true })
            assertTrue(pageOf(fixture, notSuspended).items.all { it.suspended == false })
        }

        val buried = query(fixture, filters = AnkiCardFilters(burial = BurialFilter.BuriedOnly), limit = 100)
        if (!fixture.capabilities.supports(AnkiCardFilterCapability.BURIAL)) {
            assertEquals("card_filter_buried", (errorOf(fixture, buried) as AnkiError.UnsupportedQueryFeature).feature)
        } else {
            assertTrue(pageOf(fixture, buried).items.all { it.buried == true })
        }
    }

    @Test
    fun `card type filter is OR within the category`() = runBlocking {
        val fixture = fixture()
        val types = fixture.cards.mapNotNull { it.type }.distinct().take(2).toSet()
        if (types.isEmpty()) return@runBlocking
        val request = query(fixture, filters = AnkiCardFilters(cardTypes = types), limit = 100)
        if (!fixture.capabilities.supports(AnkiCardFilterCapability.CARD_TYPES)) {
            assertEquals("card_filter_types", (errorOf(fixture, request) as AnkiError.UnsupportedQueryFeature).feature)
            return@runBlocking
        }
        val page = pageOf(fixture, request)
        assertEquals(fixture.expected(request).map { it.cardRef }, page.items.map { it.cardRef })
        assertTrue(page.items.all { it.type in types })
    }

    @Test
    fun `explicit sort is honoured in both directions with a stable tie-break`() = runBlocking {
        val fixture = fixture()
        for (sort in listOf(
            AnkiCardSort.Reps(SortDirection.ASCENDING),
            AnkiCardSort.Reps(SortDirection.DESCENDING),
            AnkiCardSort.Due(SortDirection.ASCENDING),
            AnkiCardSort.Created(SortDirection.DESCENDING),
            AnkiCardSort.Modified(SortDirection.ASCENDING),
            AnkiCardSort.Lapses(SortDirection.DESCENDING)
        )) {
            val request = query(fixture, sort = sort, limit = 100)
            val capability = sortCapabilityOf(sort)
            if (capability !in fixture.capabilities.supportedSorts) {
                assertEquals(
                    "Unsupported sort must be refused, never approximated",
                    "card_sort_${sort.sortName()}",
                    (errorOf(fixture, request) as AnkiError.UnsupportedQueryFeature).feature
                )
                continue
            }
            val page = pageOf(fixture, request)
            assertEquals(
                "Sort ${sort.sortName()} must match the reference ordering exactly",
                fixture.expected(request).map { it.cardRef },
                page.items.map { it.cardRef }
            )
        }
    }

    @Test
    fun `paging is bounded, ordered and free of duplicates and skips`() = runBlocking {
        val fixture = fixture()
        val base = query(fixture, limit = 3)
        if (!fixture.capabilities.canBrowseAllCards) return@runBlocking
        val seen = LinkedHashSet<com.studyagent.client.core.anki.AnkiCardRef>()
        var cursor: AnkiPageCursor? = null
        var pages = 0
        var previousIndex = -1
        val reference = fixture.expected(base)
        while (true) {
            val request = base.copy(page = AnkiPageRequest(limit = 3, cursor = cursor))
            val page = pageOf(fixture, request)
            assertPageShape(page)
            assertTrue("Pages are bounded by the request", page.items.size <= 3)
            if (page.items.isEmpty()) break
            val indices = page.items.map { reference.indexOfFirst { expected -> expected.cardRef == it.cardRef } }
            assertTrue("Paging never reorders a stable query result", indices.first() > previousIndex)
            previousIndex = indices.last()
            page.items.forEach { seen += it.cardRef }
            pages += 1
            cursor = page.nextCursor ?: break
            assertTrue("Paging terminates", pages <= reference.size + 2)
        }
        assertEquals("Every matching card is delivered exactly once", reference.map { it.cardRef }, seen.toList())
    }

    @Test
    fun `empty result is a valid page and not an error`() = runBlocking {
        val fixture = fixture()
        val request = query(fixture, text = "no-such-card-text-$%^&", limit = 10)
        if (!fixture.capabilities.canSearchText) {
            assertTrue(errorOf(fixture, request) is AnkiError.UnsupportedQueryFeature)
            return@runBlocking
        }
        val page = pageOf(fixture, request)
        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
        if (fixture.capabilities.supportsTotalCount) assertEquals(0, page.totalCount)
    }

    @Test
    fun `duplicate-looking cards keep distinct identities`() = runBlocking {
        val fixture = fixture()
        val duplicates = fixture.cards.groupBy { it.searchableText.firstOrNull() ?: "" }.entries
            .firstOrNull { (text, cards) -> text.isNotEmpty() && cards.size >= 2 }
        val request = query(fixture, text = duplicates?.key ?: return@runBlocking, limit = 100)
        if (!fixture.capabilities.canSearchText) {
            assertTrue(errorOf(fixture, request) is AnkiError.UnsupportedQueryFeature)
            return@runBlocking
        }
        val page = pageOf(fixture, request)
        assertTrue("Identical-looking rows are all delivered", page.items.size >= 2)
        assertEquals(page.items.size, page.items.map { it.cardRef }.distinct().size)
    }

    @Test
    fun `missing deck is a typed NotFound and never an empty page`() = runBlocking {
        val fixture = fixture()
        val request = query(fixture, scope = AnkiCardScope.Deck(fixture.missingDeckId), limit = 10)
        if (!fixture.capabilities.canBrowseDeck) {
            assertTrue(errorOf(fixture, request) is AnkiError.UnsupportedQueryFeature)
            return@runBlocking
        }
        val error = errorOf(fixture, request)
        assertTrue("A vanished deck must not look like an empty deck: $error", error is AnkiError.DeckNotFound)
    }

    @Test
    fun `a real but empty deck is an empty page, not an error`() = runBlocking {
        val fixture = fixture()
        val request = query(fixture, scope = AnkiCardScope.Deck(fixture.emptyDeckId), limit = 10)
        if (!fixture.capabilities.canBrowseDeck) {
            assertTrue(errorOf(fixture, request) is AnkiError.UnsupportedQueryFeature)
            return@runBlocking
        }
        val page = pageOf(fixture, request)
        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
        if (fixture.capabilities.supportsTotalCount) assertEquals(0, page.totalCount)
    }

    @Test
    fun `invalid cursors are refused instead of silently restarting`() = runBlocking {
        val fixture = fixture()
        val malformed = query(fixture, limit = 2, cursor = AnkiPageCursor("not-a-real-cursor"))
        val error = errorOf(fixture, malformed)
        assertTrue("Malformed cursor must be a typed paging error: $error", error is AnkiError.InvalidCursor)
    }

    @Test
    fun `a cursor is bound to its query and is refused after a query change`() = runBlocking {
        val fixture = fixture()
        if (!fixture.capabilities.canBrowseAllCards) return@runBlocking
        val first = pageOf(fixture, query(fixture, limit = 2))
        val cursor = first.nextCursor ?: return@runBlocking

        val changedText = query(fixture, text = "changed", limit = 2, cursor = cursor)
        if (fixture.capabilities.canSearchText) {
            assertTrue(errorOf(fixture, changedText) is AnkiError.InvalidCursor)
        }

        // A different sort/scope is a different query identity too (§57-§60).
        val changedSort = query(fixture, sort = AnkiCardSort.Reps(SortDirection.DESCENDING), limit = 2, cursor = cursor)
        if (AnkiCardSortCapability.REPS in fixture.capabilities.supportedSorts) {
            assertTrue(errorOf(fixture, changedSort) is AnkiError.InvalidCursor)
        }

        // The unchanged query still resumes, which proves the rejection was about identity.
        val resumed = pageOf(fixture, query(fixture, limit = 2, cursor = cursor))
        assertEquals(2, resumed.items.size.coerceAtMost(2))
    }

    @Test
    fun `a cursor from another backend or collection is refused`() = runBlocking {
        val fixture = fixture()
        if (!fixture.capabilities.canBrowseAllCards) return@runBlocking
        // Structurally plausible but bound to a different backend/collection fingerprint (§30/§55).
        val foreignCursor = AnkiPageCursor("v1:o=2:k=00000000000000000000000000000000")
        val request = query(fixture, limit = 2, cursor = foreignCursor)
        val error = errorOf(fixture, request)
        assertTrue("A foreign or unknown cursor must be refused: $error", error is AnkiError.InvalidCursor)
    }

    @Test
    fun `page limits outside the contract bounds are refused`() = runBlocking {
        val fixture = fixture()
        val tooSmall = query(fixture, limit = 0)
        assertTrue(errorOf(fixture, tooSmall) is AnkiError.InvalidQuery)
        val tooLarge = query(fixture, limit = AnkiPageRequest.MAX_LIMIT + 1)
        assertTrue(errorOf(fixture, tooLarge) is AnkiError.InvalidQuery)
        if (fixture.capabilities.maxPageSize < AnkiPageRequest.MAX_LIMIT) {
            val aboveBackendMax = query(fixture, limit = fixture.capabilities.maxPageSize + 1)
            val error = errorOf(fixture, aboveBackendMax)
            assertTrue(
                "A page larger than the backend maximum is refused, never clamped: $error",
                error is AnkiError.UnsupportedQueryFeature && error.feature == "card_page_limit"
            )
        }
    }

    @Test
    fun `browsing never mutates the collection or advances the scheduler`() = runBlocking {
        val fixture = fixture()
        if (!fixture.capabilities.canBrowseAllCards) return@runBlocking
        val probe = fixture.readOnlyProbe ?: return@runBlocking
        val before = probe.commitInvocations to probe.backendEffectCount
        // Only queries this backend advertises: the invariant under test is "reads do not write",
        // not "unsupported features are accepted".
        pageOf(fixture, query(fixture, limit = 5))
        val supportedFilter = when {
            fixture.capabilities.supports(AnkiCardFilterCapability.TAGS) &&
                fixture.cards.any { it.tags.isNotEmpty() } ->
                AnkiCardFilters(tags = setOf(fixture.cards.first { it.tags.isNotEmpty() }.tags.first()))
            else -> AnkiCardFilters()
        }
        pageOf(fixture, query(fixture, filters = supportedFilter, limit = 5))
        assertEquals(before, probe.commitInvocations to probe.backendEffectCount)
        assertTrue("Browse reads were actually issued", probe.browseCalls > 0)
    }

    @Test
    fun `cancellation is not reported as a query failure`() = runBlocking {
        val fixture = fixture()
        val probe = fixture.readOnlyProbe ?: return@runBlocking
        val gate = probe.holdNextBrowse()
        var failure: Throwable? = null
        val job = launch {
            try {
                fixture.backend.browseCards(query(fixture, limit = 5))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                failure = throwable
            }
        }
        val entered = CompletableDeferred<Unit>()
        launch {
            while (true) {
                if (probe.browseCalls > 0) {
                    entered.complete(Unit)
                    break
                }
                yield()
            }
        }
        entered.await()
        job.cancel()
        gate.release()
        job.join()
        assertTrue("Cancelling a browse cancels the call", job.isCancelled)
        assertNull("Cancellation is never a user-visible query error", failure)
        // The backend stays usable afterwards.
        val after = pageOf(fixture, query(fixture, limit = 1))
        assertTrue(after.items.size <= 1)
    }

    private fun sortCapabilityOf(sort: AnkiCardSort): AnkiCardSortCapability = when (sort) {
        is AnkiCardSort.Due -> AnkiCardSortCapability.DUE
        is AnkiCardSort.Created -> AnkiCardSortCapability.CREATED
        is AnkiCardSort.Modified -> AnkiCardSortCapability.MODIFIED
        is AnkiCardSort.Reps -> AnkiCardSortCapability.REPS
        is AnkiCardSort.Lapses -> AnkiCardSortCapability.LAPSES
        AnkiCardSort.Default -> error("Default sort has no capability")
    }

    private fun AnkiCardSort.sortName(): String = when (this) {
        is AnkiCardSort.Due -> "due"
        is AnkiCardSort.Created -> "created"
        is AnkiCardSort.Modified -> "modified"
        is AnkiCardSort.Reps -> "reps"
        is AnkiCardSort.Lapses -> "lapses"
        AnkiCardSort.Default -> "default"
    }
}
