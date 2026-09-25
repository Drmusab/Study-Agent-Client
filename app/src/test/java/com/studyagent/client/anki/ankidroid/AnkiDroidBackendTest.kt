package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.BeginReviewRequest
import com.studyagent.client.core.anki.AnkiSessionContext
import com.studyagent.client.core.anki.CommitRatingRequest
import com.studyagent.client.core.anki.CommitRatingResult
import com.studyagent.client.core.anki.NextCardResult
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.anki.ReviewCommitId
import com.studyagent.client.core.models.Rating
import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiCapabilityReport
import com.studyagent.client.data.anki.ankidroid.AnkiDroidBackend
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCapabilityProbeResult
import com.studyagent.client.data.anki.ankidroid.AnkiDroidEndpoints
import com.studyagent.client.data.anki.ankidroid.AnkiDroidGateway
import com.studyagent.client.data.anki.ankidroid.AnkiDroidProviderSpecSource
import com.studyagent.client.data.anki.ankidroid.AnkiDroidGatewayHealthSnapshot
import com.studyagent.client.data.anki.ankidroid.AnkiDroidHealthSnapshot
import com.studyagent.client.data.anki.ankidroid.AnkiDroidIntegrationState
import com.studyagent.client.data.anki.ankidroid.AnkiDroidMetadata
import com.studyagent.client.data.anki.ankidroid.CapabilitySupport
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidCardGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidDeckGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidReviewGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 04 — backend tests (§22-§25, §80-§90).
 */
class AnkiDroidBackendTest {

    private fun readyState(): AnkiDroidIntegrationState = AnkiDroidIntegrationState(
        availability = AnkiAvailability.Ready(AnkiCapabilities.NONE),
        capabilities = AnkiCapabilities.NONE,
        apiCapabilities = AnkiDroidApiCapabilityReport(
            deckListing = CapabilitySupport.SUPPORTED,
            deckCounts = CapabilitySupport.SUPPORTED,
            scheduledReview = CapabilitySupport.SUPPORTED,
            renderedCards = CapabilitySupport.SUPPORTED,
            simpleCardText = CapabilitySupport.SUPPORTED,
            nextReviewIntervals = CapabilitySupport.SUPPORTED,
            ratingCommit = CapabilitySupport.SUPPORTED,
            flags = CapabilitySupport.UNSUPPORTED,
            bury = CapabilitySupport.SUPPORTED,
            suspend = CapabilitySupport.SUPPORTED,
            noteRead = CapabilitySupport.SUPPORTED,
            noteEdit = CapabilitySupport.SUPPORTED,
            noteCreate = CapabilitySupport.SUPPORTED,
            mediaRead = CapabilitySupport.SUPPORTED,
            mediaWrite = CapabilitySupport.SUPPORTED,
            search = CapabilitySupport.SUPPORTED,
            noteTypes = CapabilitySupport.SUPPORTED,
            cardTemplates = CapabilitySupport.SUPPORTED,
            specVersion = 2,
            reason = "provider contract"
        ),
        metadata = AnkiDroidMetadata(
            packageName = "com.ichi2.anki",
            providerPackage = "com.ichi2.anki",
            authority = "com.ichi2.anki.flashcards",
            endpointLabel = "release",
            providerSpec = 2,
            providerSpecSource = AnkiDroidProviderSpecSource.METADATA,
            packageVersion = "2.24.1",
            providerReachable = true,
            permissionGranted = true,
            collectionReady = true
        ),
        lastCheckAtMs = 1_700_000_000_000L,
        latencyMs = 20L,
        lastError = null,
        healthSnapshot = testSnapshot(testDetection(AnkiAvailability.Ready(AnkiCapabilities.NONE))),
        capabilityDetails = emptyList()
    )

    @Test
    fun `backend id is ANKIDROID_LOCAL`() = runTest {
        val gateway = FakeAnkiDroidGateway(stateToReturn = readyState())
        val backend = AnkiDroidBackend(
            gateway = gateway,
            scope = backgroundScope,
            deckGateway = FakeAnkiDroidDeckGateway(),
            reviewGateway = FakeAnkiDroidReviewGateway(),
            cardGateway = FakeAnkiDroidCardGateway()
        )
        assertEquals(AnkiBackendId.AnkiDroidLocal, backend.id)
        assertEquals("ankidroid_local", backend.id.stableId)
    }

    @Test
    fun `backend exposes availability and capabilities flows`() = runTest {
        val gateway = FakeAnkiDroidGateway(stateToReturn = readyState())
        val backend = AnkiDroidBackend(gateway = gateway, scope = backgroundScope, deckGateway = FakeAnkiDroidDeckGateway(), reviewGateway = FakeAnkiDroidReviewGateway(), cardGateway = FakeAnkiDroidCardGateway())

        assertTrue(backend.availability.value is AnkiAvailability.Checking || backend.availability.value is AnkiAvailability.Ready)
        assertEquals(AnkiCapabilities.NONE, backend.capabilities.value)
    }

    @Test
    fun `backend refreshAvailability updates state`() = runTest {
        val gateway = FakeAnkiDroidGateway(stateToReturn = readyState())
        val backend = AnkiDroidBackend(gateway = gateway, scope = backgroundScope, deckGateway = FakeAnkiDroidDeckGateway(), reviewGateway = FakeAnkiDroidReviewGateway(), cardGateway = FakeAnkiDroidCardGateway())

        backend.refreshAvailability()

        assertTrue(backend.availability.value is AnkiAvailability.Ready)
        assertEquals(1, gateway.refreshCalls)
    }

    @Test
    fun `backend getDecks returns UnsupportedAction not empty list`() = runTest {
        val gateway = FakeAnkiDroidGateway(stateToReturn = readyState())
        val backend = AnkiDroidBackend(gateway = gateway, scope = backgroundScope, deckGateway = FakeAnkiDroidDeckGateway(), reviewGateway = FakeAnkiDroidReviewGateway(), cardGateway = FakeAnkiDroidCardGateway())

        val result = backend.getDecks()

        assertTrue(result is AnkiResult.Failure)
        val error = (result as AnkiResult.Failure).error
        assertTrue(error is AnkiError.UnsupportedAction)
        assertEquals("deckListing", (error as AnkiError.UnsupportedAction).action)
    }

    @Test
    fun `backend beginReview without the scheduledReview capability is refused truthfully`() = runTest {
        val gateway = FakeAnkiDroidGateway(stateToReturn = readyState())
        val backend = AnkiDroidBackend(gateway = gateway, scope = backgroundScope, deckGateway = FakeAnkiDroidDeckGateway(), reviewGateway = FakeAnkiDroidReviewGateway(), cardGateway = FakeAnkiDroidCardGateway())

        val context = AnkiSessionContext(
            backendId = AnkiBackendId.AnkiDroidLocal,
            collection = null,
            deckRef = AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "1"),
            startedAtEpochMs = 0L,
            capabilities = AnkiCapabilities.NONE,
            studySessionId = "session-1"
        )
        val result = backend.beginReview(BeginReviewRequest(context))

        assertTrue(result is AnkiResult.Failure)
        val error = (result as AnkiResult.Failure).error
        assertTrue(error is AnkiError.UnsupportedAction)
        // Capability truthfulness: a backend that cannot start scheduled review says so, and does
        // not half-open a session it cannot serve (§75/§147).
        assertEquals("scheduledReview", (error as AnkiError.UnsupportedAction).action)
    }

    @Test
    fun `backend nextCard on an unknown session handle returns typed Failure`() = runTest {
        val gateway = FakeAnkiDroidGateway(stateToReturn = readyState())
        val backend = AnkiDroidBackend(gateway = gateway, scope = backgroundScope, deckGateway = FakeAnkiDroidDeckGateway(), reviewGateway = FakeAnkiDroidReviewGateway(), cardGateway = FakeAnkiDroidCardGateway())

        val context = AnkiSessionContext(
            backendId = AnkiBackendId.AnkiDroidLocal,
            collection = null,
            deckRef = null,
            startedAtEpochMs = 0L,
            capabilities = AnkiCapabilities.NONE,
            studySessionId = "session-1"
        )
        val session = com.studyagent.client.core.anki.AnkiReviewSession(context, "ref-1")
        val result = backend.nextCard(session)

        assertTrue(result is NextCardResult.Failure)
        // A handle this backend never issued is refused, never reinterpreted (§162).
        assertTrue((result as NextCardResult.Failure).error is AnkiError.SessionInvalid)
    }

    @Test
    fun `backend commitRating returns Rejected not crash`() = runTest {
        val gateway = FakeAnkiDroidGateway(stateToReturn = readyState())
        val backend = AnkiDroidBackend(gateway = gateway, scope = backgroundScope, deckGateway = FakeAnkiDroidDeckGateway(), reviewGateway = FakeAnkiDroidReviewGateway(), cardGateway = FakeAnkiDroidCardGateway())

        val cardRef = AnkiCardRef(AnkiBackendId.AnkiDroidLocal, cardId = "1")
        val request = CommitRatingRequest(
            commitId = ReviewCommitId(AnkiBackendId.AnkiDroidLocal, "session-1", ReviewTurnId("turn-1")),
            card = cardRef,
            rating = Rating.GOOD,
            ratedAtEpochMs = 0L
        )
        val result = backend.commitRating(request)

        assertTrue(result is CommitRatingResult.Rejected)
    }

    @Test
    fun `backend cancellation propagates`() = runTest {
        val gateway = FakeAnkiDroidGateway(
            stateToReturn = readyState(),
            throwable = CancellationException("cancelled")
        )
        val backend = AnkiDroidBackend(gateway = gateway, scope = backgroundScope, deckGateway = FakeAnkiDroidDeckGateway(), reviewGateway = FakeAnkiDroidReviewGateway(), cardGateway = FakeAnkiDroidCardGateway())

        try {
            backend.refreshAvailability()
            fail("Should propagate cancellation")
        } catch (e: CancellationException) {
            // expected §79
        }
    }

    @Test
    fun `backend concurrent refresh is consistent`() = runTest {
        val gateway = FakeAnkiDroidGateway(stateToReturn = readyState())
        val backend = AnkiDroidBackend(gateway = gateway, scope = backgroundScope, deckGateway = FakeAnkiDroidDeckGateway(), reviewGateway = FakeAnkiDroidReviewGateway(), cardGateway = FakeAnkiDroidCardGateway())

        val jobs = (1..10).map {
            async { backend.refreshAvailability() }
        }
        jobs.forEach { it.await() }

        // Final state should be Ready
        assertTrue(backend.availability.value is AnkiAvailability.Ready)
    }

    @Test
    fun `backend availability remains independent from PC agent`() = runTest {
        // AnkiDroid Ready + PC Disconnected is valid (§88)
        val gateway = FakeAnkiDroidGateway(stateToReturn = readyState())
        val backend = AnkiDroidBackend(gateway = gateway, scope = backgroundScope, deckGateway = FakeAnkiDroidDeckGateway(), reviewGateway = FakeAnkiDroidReviewGateway(), cardGateway = FakeAnkiDroidCardGateway())

        backend.refreshAvailability()

        // AnkiDroid backend is Ready regardless of PC state
        assertTrue(backend.availability.value is AnkiAvailability.Ready)
        // PC state would be AgentDisconnected, but AnkiDroid remains Ready — no coupling
    }

    @Test
    fun `backend optional capability absence does not make backend unavailable`() = runTest {
        // Backend should remain Ready even if optional editNotes is unavailable (§85)
        val state = readyState().copy(
            capabilities = AnkiCapabilities.NONE, // no editNotes
            availability = AnkiAvailability.Ready(AnkiCapabilities.NONE)
        )
        val gateway = FakeAnkiDroidGateway(stateToReturn = state)
        val backend = AnkiDroidBackend(gateway = gateway, scope = backgroundScope, deckGateway = FakeAnkiDroidDeckGateway(), reviewGateway = FakeAnkiDroidReviewGateway(), cardGateway = FakeAnkiDroidCardGateway())

        backend.refreshAvailability()

        assertTrue(backend.availability.value is AnkiAvailability.Ready)
        assertFalse(backend.capabilities.value.editNotes)
    }

    @Test
    fun `backend with permission required reports PermissionRequired`() = runTest {
        val permState = readyState().copy(
            availability = AnkiAvailability.PermissionRequired(),
            lastError = AnkiError.PermissionRequired()
        )
        val gateway = FakeAnkiDroidGateway(stateToReturn = permState)
        val backend = AnkiDroidBackend(gateway = gateway, scope = backgroundScope, deckGateway = FakeAnkiDroidDeckGateway(), reviewGateway = FakeAnkiDroidReviewGateway(), cardGateway = FakeAnkiDroidCardGateway())

        backend.refreshAvailability()

        assertTrue(backend.availability.value is AnkiAvailability.PermissionRequired)
    }

    private fun listingReady(): AnkiDroidIntegrationState = readyState().copy(
        availability = AnkiAvailability.Ready(AnkiCapabilities(deckListing = true)),
        capabilities = AnkiCapabilities(deckListing = true)
    )

    @Test
    fun `backend getDecks returns mapped decks when listing is implemented`() = runTest {
        val decks = listOf(
            AnkiDeck(AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "1"), "Default"),
            AnkiDeck(AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "2"), "Medicine::Cardiology")
        )
        val deckGateway = FakeAnkiDroidDeckGateway()
        deckGateway.succeed(decks, selected = decks.first().ref)
        val backend = AnkiDroidBackend(
            gateway = FakeAnkiDroidGateway(stateToReturn = listingReady()),
            scope = backgroundScope,
            deckGateway = deckGateway, reviewGateway = FakeAnkiDroidReviewGateway(),
            cardGateway = FakeAnkiDroidCardGateway()
        )
        val result = backend.getDecks() as AnkiResult.Success
        assertEquals(decks, result.value)
        assertEquals(1, deckGateway.queryDecksCalls)
        assertEquals("com.ichi2.anki.flashcards", deckGateway.queriedAuthorities.single())
        val selected = backend.getSelectedDeck() as AnkiResult.Success
        assertEquals("1", selected.value?.deckId)
    }

    @Test
    fun `backend getDecks empty success is not a failure`() = runTest {
        val deckGateway = FakeAnkiDroidDeckGateway()
        val backend = AnkiDroidBackend(
            gateway = FakeAnkiDroidGateway(stateToReturn = listingReady()),
            scope = backgroundScope,
            deckGateway = deckGateway, reviewGateway = FakeAnkiDroidReviewGateway(),
            cardGateway = FakeAnkiDroidCardGateway()
        )
        val result = backend.getDecks() as AnkiResult.Success
        assertTrue(result.value.isEmpty())
    }

    @Test
    fun `backend getDecks maps permission loss to PermissionRequired`() = runTest {
        val backend = AnkiDroidBackend(
            gateway = FakeAnkiDroidGateway(stateToReturn = listingReady().copy(
                availability = AnkiAvailability.PermissionRequired()
            )),
            scope = backgroundScope,
            deckGateway = FakeAnkiDroidDeckGateway(), reviewGateway = FakeAnkiDroidReviewGateway(),
            cardGateway = FakeAnkiDroidCardGateway()
        )
        val result = backend.getDecks() as AnkiResult.Failure
        assertTrue(result.error is AnkiError.PermissionRequired)
    }

    @Test
    fun `backend getDecks maps collection loss to CollectionUnavailable`() = runTest {
        val backend = AnkiDroidBackend(
            gateway = FakeAnkiDroidGateway(stateToReturn = listingReady().copy(
                availability = AnkiAvailability.CollectionNotInitialized
            )),
            scope = backgroundScope,
            deckGateway = FakeAnkiDroidDeckGateway(), reviewGateway = FakeAnkiDroidReviewGateway(),
            cardGateway = FakeAnkiDroidCardGateway()
        )
        val result = backend.getDecks() as AnkiResult.Failure
        assertTrue(result.error is AnkiError.CollectionUnavailable)
    }
}
