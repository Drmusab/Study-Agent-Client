package com.studyagent.client.anki

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiRequiredCapability
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.LibraryDataState
import com.studyagent.client.data.anki.AnkiLibraryRepository
import com.studyagent.client.data.anki.context.AnkiReadContextBindingResult
import com.studyagent.client.data.anki.context.AnkiReadContextController
import com.studyagent.client.testutil.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Library snapshot/cursor ownership checks against deterministic in-memory backends. */
class Gate21fLibraryIsolationTest {
    private val backendId = AnkiBackendId.AnkiDroidLocal
    private val required = setOf(AnkiRequiredCapability.DECKS)

    private fun backend(collectionKey: String): FakeAnkiBackend {
        val capabilities = AnkiCapabilities(deckListing = true)
        return FakeAnkiBackend(
            id = backendId,
            decks = listOf(AnkiDeck(
                AnkiDeckRef(backendId, "deck-1", collectionKey),
                "Collection $collectionKey"
            )),
            initialCapabilities = capabilities,
            initialAvailability = AnkiAvailability.Ready(capabilities)
        ).apply {
            currentCollectionIdentity = AnkiCollectionIdentity(backendId, collectionKey)
        }
    }

    private fun controller(backend: FakeAnkiBackend) = AnkiReadContextController(
        registry = AnkiBackendRegistry(listOf(backend)),
        preference = MutableStateFlow(BackendPreference.Auto)
    )

    @Test
    fun `collection change clears old library snapshot and a new generation starts empty`() = runTest {
        val fake = backend("collection-a")
        val controller = controller(fake)
        val oldBinding = (controller.bindNewContext(required, "library") as AnkiReadContextBindingResult.Bound).binding
        val oldRepository = AnkiLibraryRepository(oldBinding, TestClock())
        val oldSnapshot = (oldRepository.refresh() as LibraryDataState.Ready).snapshot
        assertEquals(oldBinding.context, oldSnapshot.readContext)
        assertEquals("collection-a", oldSnapshot.decks.single().ref.collectionKey)

        fake.currentCollectionIdentity = AnkiCollectionIdentity(backendId, "collection-b")
        val failure = oldRepository.refresh() as LibraryDataState.Failed
        assertEquals("read_context_collection_identity_mismatch",
            (failure.error as AnkiError.InvalidRequest).detail)
        assertNull(failure.snapshot)
        assertTrue(!oldBinding.isValid)

        fake.replaceDecks(listOf(AnkiDeck(
            AnkiDeckRef(backendId, "deck-1", "collection-b"), "Collection B"
        )))
        fake.selectedDeckRef = AnkiDeckRef(backendId, "deck-1", "collection-b")
        val newBinding = (controller.bindNewContext(required, "library") as AnkiReadContextBindingResult.Bound).binding
        val newRepository = AnkiLibraryRepository(newBinding, TestClock())
        val newSnapshot = (newRepository.refresh() as LibraryDataState.Ready).snapshot

        assertEquals("collection-b", newSnapshot.decks.single().ref.collectionKey)
        assertEquals(newBinding.context, newSnapshot.readContext)
        assertTrue(oldSnapshot.readContext != newSnapshot.readContext)
        assertTrue(oldSnapshot.decks.single() != newSnapshot.decks.single())
        controller.release(newBinding)
    }

    @Test
    fun `same backend and collection still receive a fresh cache for another generation`() = runTest {
        val fake = backend("collection-a")
        val controller = controller(fake)
        val firstBinding = (controller.bindNewContext(required, "library") as AnkiReadContextBindingResult.Bound).binding
        val firstRepository = AnkiLibraryRepository(firstBinding, TestClock())
        val firstSnapshot = (firstRepository.refresh() as LibraryDataState.Ready).snapshot

        fake.replaceDecks(listOf(AnkiDeck(
            AnkiDeckRef(backendId, "deck-1", "collection-a"), "Updated in generation two"
        )))
        val secondBinding = (controller.bindNewContext(required, "library") as AnkiReadContextBindingResult.Bound).binding
        val secondRepository = AnkiLibraryRepository(secondBinding, TestClock())
        val secondSnapshot = (secondRepository.refresh() as LibraryDataState.Ready).snapshot

        assertEquals(firstBinding.context.backendId, secondBinding.context.backendId)
        assertEquals(firstBinding.context.collectionKey, secondBinding.context.collectionKey)
        assertTrue(firstBinding.context.generation != secondBinding.context.generation)
        assertTrue(firstSnapshot.readContext != secondSnapshot.readContext)
        assertEquals("Collection collection-a", firstSnapshot.decks.single().name)
        assertEquals("Updated in generation two", secondSnapshot.decks.single().name)
        controller.release(firstBinding)
        controller.release(secondBinding)
    }

    @Test
    fun `late library result after owner disposal cannot restore its cache`() = runTest {
        val fake = backend("collection-a")
        val controller = controller(fake)
        val binding = (controller.bindNewContext(required, "library") as AnkiReadContextBindingResult.Bound).binding
        val repository = AnkiLibraryRepository(binding, TestClock())
        assertTrue(repository.refresh() is LibraryDataState.Ready)

        val gate = CompletableDeferred<Unit>()
        fake.decksGate = gate
        val pending = async { repository.refresh() }
        runCurrent() // advance the fake read to its deterministic gate
        repository.invalidateContext()
        gate.complete(Unit)
        pending.await()

        assertTrue(repository.state.value is LibraryDataState.Idle)
        assertNull(repository.state.value.snapshot)
    }
}
