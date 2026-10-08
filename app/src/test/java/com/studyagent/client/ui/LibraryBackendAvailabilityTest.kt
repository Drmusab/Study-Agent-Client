package com.studyagent.client.ui

import androidx.lifecycle.ViewModelStore
import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.data.anki.AnkiLibraryRepository
import com.studyagent.client.ui.screens.library.LibraryUiState
import com.studyagent.client.ui.screens.library.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryBackendAvailabilityTest {
    @Test
    fun `backend loss is visible and recovery refreshes rather than mixing stale data`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var viewModelStore: ViewModelStore? = null
        try {
            val id = AnkiBackendId.Fake("switch-check")
            val capabilities = AnkiCapabilities(review = true, scheduledReview = true, deckListing = true)
            val backend = FakeAnkiBackend(
                id = id,
                decks = listOf(AnkiDeck(AnkiDeckRef(id, "1"), "Backend A deck")),
                initialCapabilities = capabilities,
                initialAvailability = AnkiAvailability.Ready(capabilities)
            )
            val viewModel = LibraryViewModel(backend, AnkiLibraryRepository(backend))
            viewModelStore = ViewModelStore().apply { put("availability-test", viewModel) }
            advanceUntilIdle()
            assertEquals("Backend A deck", (viewModel.uiState.value as LibraryUiState.Ready).decks.single().deck.name)
            assertEquals(1, backend.getDecksCalls)

            backend.setAvailability(AnkiAvailability.PermissionRequired())
            advanceUntilIdle()
            val unavailable = viewModel.uiState.value as LibraryUiState.Unavailable
            assertTrue(unavailable.reason is AnkiAvailability.PermissionRequired)

            backend.setAvailability(AnkiAvailability.Ready(capabilities))
            advanceUntilIdle()
            val recovered = viewModel.uiState.value as LibraryUiState.Ready
            assertEquals("Backend A deck", recovered.decks.single().deck.name)
            assertEquals(2, backend.getDecksCalls)
        } finally {
            viewModelStore?.clear()
            Dispatchers.resetMain()
        }
    }
}
