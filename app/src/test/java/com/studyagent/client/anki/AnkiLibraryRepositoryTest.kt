package com.studyagent.client.anki

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckCounts
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.LibraryDataState
import com.studyagent.client.core.anki.LibraryFreshness
import com.studyagent.client.data.anki.AnkiLibraryRepository
import com.studyagent.client.testutil.TestClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnkiLibraryRepositoryTest {

    private val backendId = AnkiBackendId.Fake("lib")

    private fun deck(id: String, name: String, counts: AnkiDeckCounts? = null) = AnkiDeck(
        ref = AnkiDeckRef(backendId, id),
        name = name,
        counts = counts
    )

    private fun backend(vararg decks: AnkiDeck, latencyMs: Long = 0, error: AnkiError? = null) = FakeAnkiBackend(
        id = backendId,
        decks = decks.toList(),
        initialCapabilities = FakeAnkiBackend.REVIEW_CAPABILITIES,
        latencyMs = latencyMs,
        decksError = error
    )

    @Test
    fun `first load goes loading then ready`() = runTest {
        val repo = AnkiLibraryRepository(backend(deck("1", "Default")), TestClock())
        assertTrue(repo.state.value is LibraryDataState.Idle)
        val state = repo.refresh() as LibraryDataState.Ready
        assertFalse(state.isRefreshing)
        assertEquals(1, state.snapshot.deckCount)
        assertEquals(LibraryFreshness.FRESH, state.snapshot.freshness)
        assertEquals("Default", state.snapshot.decks.single().name)
        assertEquals(backendId, state.snapshot.backendId)
        assertNull(state.snapshot.lastRefreshError)
    }

    @Test
    fun `empty collection is ready not failed`() = runTest {
        val repo = AnkiLibraryRepository(backend(), TestClock())
        val state = repo.refresh() as LibraryDataState.Ready
        assertTrue(state.snapshot.isEmpty)
        assertEquals(0, state.snapshot.tree.deckCount)
    }

    @Test
    fun `provider failure with no cache is failed not empty ready`() = runTest {
        val repo = AnkiLibraryRepository(
            backend(error = AnkiError.PermissionRequired()),
            TestClock()
        )
        val state = repo.refresh() as LibraryDataState.Failed
        assertTrue(state.error is AnkiError.PermissionRequired)
        assertNull(state.snapshot)
    }

    @Test
    fun `refresh failure keeps last snapshot and marks it stale`() = runTest {
        val fake = backend(deck("1", "Default"))
        val repo = AnkiLibraryRepository(fake, TestClock())
        repo.refresh()
        fake.setAvailability(AnkiAvailability.PermissionRequired())
        val state = repo.refresh() as LibraryDataState.Ready
        assertEquals(LibraryFreshness.STALE, state.snapshot.freshness)
        assertTrue(state.snapshot.lastRefreshError is AnkiError.PermissionRequired)
        assertEquals("Default", state.snapshot.decks.single().name)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun `unknown counts stay null zeros stay zero`() = runTest {
        val repo = AnkiLibraryRepository(
            backend(
                deck("1", "UnknownCounts"),
                deck("2", "Zero", AnkiDeckCounts(new = 0, learning = 0, review = 0))
            ),
            TestClock()
        )
        val snapshot = (repo.refresh() as LibraryDataState.Ready).snapshot
        assertNull(snapshot.deck(AnkiDeckRef(backendId, "1"))?.counts)
        assertEquals(0, snapshot.deck(AnkiDeckRef(backendId, "2"))?.counts?.new)
        assertEquals(1, snapshot.decksWithCounts)
    }

    @Test
    fun `backend selected deck is distinct from identity and may be absent`() = runTest {
        val d = deck("1", "Default")
        val fake = backend(d)
        fake.selectedDeckRef = d.ref
        val repo = AnkiLibraryRepository(fake, TestClock())
        val snapshot = (repo.refresh() as LibraryDataState.Ready).snapshot
        assertEquals(d.ref, snapshot.selectedDeck)
        assertEquals(true, snapshot.isSelectedByBackend(d.ref))
        fake.selectedDeckRef = AnkiDeckRef(backendId, "missing")
        val after = (repo.refresh() as LibraryDataState.Ready).snapshot
        assertEquals("missing", after.selectedDeck?.deckId)
        assertNull(after.deck(after.selectedDeck!!))
    }

    @Test
    fun `tree virtual nodes are not real decks`() = runTest {
        val repo = AnkiLibraryRepository(backend(deck("1", "Medicine::Cardiology")), TestClock())
        val snapshot = (repo.refresh() as LibraryDataState.Ready).snapshot
        assertEquals(1, snapshot.deckCount)
        assertEquals(1, snapshot.tree.virtualGroupCount)
        assertEquals("Medicine", snapshot.tree.roots.single().name)
    }

    @Test
    fun `concurrent refresh is single flight`() = runTest {
        val fake = backend(deck("1", "Default"), latencyMs = 40)
        val repo = AnkiLibraryRepository(fake, TestClock())
        val a = async { repo.refresh() }
        val b = async { repo.refresh() }
        a.await()
        b.await()
        assertEquals(1, fake.getDecksCalls)
    }

    @Test
    fun `cancellation does not publish a failure`() = runTest {
        val fake = backend(deck("1", "Default"), latencyMs = 5_000)
        val repo = AnkiLibraryRepository(fake, TestClock())
        val job = launch { repo.refresh() }
        job.cancel()
        try {
            job.join()
        } catch (_: CancellationException) {
        }
        assertTrue(repo.state.value is LibraryDataState.Idle || repo.state.value is LibraryDataState.Loading)
        if (repo.state.value is LibraryDataState.Failed) fail("cancellation must not become Failed")
    }

    @Test
    fun `backend qualification is preserved in the snapshot`() = runTest {
        val other = AnkiBackendId.AnkiDroidLocal
        val fake = FakeAnkiBackend(
            id = backendId,
            decks = listOf(deck("123", "SharedName")),
            initialCapabilities = FakeAnkiBackend.REVIEW_CAPABILITIES
        )
        val snapshot = (AnkiLibraryRepository(fake, TestClock()).refresh() as LibraryDataState.Ready).snapshot
        assertEquals(backendId, snapshot.decks.single().ref.backendId)
        assertTrue(snapshot.decks.single().ref != AnkiDeckRef(other, "123"))
    }

    @Test
    fun `replace decks then refresh sees new topology`() = runTest {
        val fake = backend(deck("1", "A"))
        val repo = AnkiLibraryRepository(fake, TestClock())
        repo.refresh()
        fake.replaceDecks(listOf(deck("2", "B::C"), deck("3", "B")))
        val snapshot = (repo.refresh() as LibraryDataState.Ready).snapshot
        assertEquals(2, snapshot.deckCount)
        assertEquals(listOf("B", "B::C"), snapshot.decks.map { it.name })
    }

    @Test
    fun `summaries expose backend selection without copying identity onto the name`() = runTest {
        val d = deck("1", "Default")
        val fake = backend(d)
        fake.selectedDeckRef = d.ref
        val snapshot = (AnkiLibraryRepository(fake, TestClock()).refresh() as LibraryDataState.Ready).snapshot
        val summary = snapshot.summaries().single()
        assertEquals(true, summary.isSelectedByBackend)
        assertEquals(d.ref, summary.deck.ref)
    }
}
