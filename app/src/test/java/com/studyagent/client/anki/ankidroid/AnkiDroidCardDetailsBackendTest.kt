package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.*
import com.studyagent.client.data.anki.ankidroid.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiDroidCardDetailsBackendTest {
    private val backendId = AnkiBackendId.AnkiDroidLocal
    private val collection = "collection-A"
    private val cardRef = AnkiCardRef(backendId, "100", "7", 0, collection)
    private val deckRef = AnkiDeckRef(backendId, "3", collection)
    private val noteRef = AnkiNoteRef(backendId, "7", collection)
    private val caps = AnkiCapabilities(
        deckListing = true,
        renderedCards = true,
        cardDetails = true
    )

    private fun integrationState(): AnkiDroidIntegrationState {
        val metadata = AnkiDroidMetadata(
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
        )
        return AnkiDroidIntegrationState(
            availability = AnkiAvailability.Ready(caps),
            capabilities = caps,
            apiCapabilities = AnkiDroidApiCapabilityReport.UNKNOWN,
            metadata = metadata,
            lastCheckAtMs = 0L,
            latencyMs = 0L,
            lastError = null,
            healthSnapshot = null
        )
    }

    private fun rendered() = AnkiRenderedCard(
        ref = cardRef,
        questionHtml = "<b>rendered Q</b>",
        answerHtml = "<i>rendered A</i>",
        questionText = "Q simple",
        answerText = "A simple",
        pureAnswerText = "pure A",
        scheduling = AnkiSchedulingInfo(reps = 0, lapses = 0, intervalDays = 0, rawDue = 0L),
        metadata = AnkiCardMetadata(
            deckName = null,
            noteTypeName = null,
            templateName = "Forward",
            queueState = AnkiCardQueueState.NEW,
            cardType = AnkiCardType.NEW
        ),
        noteRef = noteRef,
        deckRef = deckRef
    )

    private fun backend(
        provider: FakeAnkiDroidProviderClient,
        cardGateway: FakeAnkiDroidCardGateway = FakeAnkiDroidCardGateway(
            results = mutableListOf(AnkiResult.Success(rendered()))
        ),
        notes: AnkiDroidNoteGateway = DefaultAnkiDroidNoteGateway(provider),
        deckGateway: FakeAnkiDroidDeckGateway = FakeAnkiDroidDeckGateway().also {
            it.succeed(listOf(AnkiDeck(deckRef, "Languages::Arabic")))
        },
        reviewGateway: FakeAnkiDroidReviewGateway = FakeAnkiDroidReviewGateway(),
        scope: kotlinx.coroutines.CoroutineScope
    ) = AnkiDroidBackend(
        gateway = FakeAnkiDroidGateway(stateToReturn = integrationState()),
        scope = scope,
        deckGateway = deckGateway,
        reviewGateway = reviewGateway,
        cardGateway = cardGateway,
        noteGateway = notes
    )

    @Test
    fun `backend coordinates exact card note type and deck reads into one details value`() = runTest {
        val provider = FakeAnkiDroidProviderClient().apply {
            scriptRows("notes/7", listOf(mapOf(
                "_id" to "7", "mid" to "2", "mod" to "1700000000",
                "tags" to "zeta alpha", "flds" to "front${AnkiDroidApiContract.FIELD_SEPARATOR}back"
            )))
            scriptRows("models/2", listOf(mapOf(
                "_id" to "2", "name" to "Basic", "field_names" to "Front${AnkiDroidApiContract.FIELD_SEPARATOR}Back",
                "type" to 0, "num_cards" to 1
            )))
        }
        val cardGateway = FakeAnkiDroidCardGateway(mutableListOf(AnkiResult.Success(rendered())))
        val reviewGateway = FakeAnkiDroidReviewGateway()
        val backend = backend(provider, cardGateway, reviewGateway = reviewGateway, scope = backgroundScope)

        val result = backend.getCardDetails(cardRef)
        assertTrue(result is AnkiResult.Success)
        val details = (result as AnkiResult.Success).value
        assertEquals(cardRef, details.cardRef)
        assertEquals("7", details.noteId)
        assertEquals(noteRef, details.noteRef)
        assertEquals(0, details.cardOrd)
        assertEquals("Languages::Arabic", details.deckName)
        assertEquals("Basic", details.noteTypeName)
        assertEquals("Forward", details.templateName)
        assertEquals("<b>rendered Q</b>", details.questionHtml)
        assertEquals("Q simple", details.questionText)
        assertEquals("pure A", details.pureAnswerText)
        assertEquals(listOf("Front", "Back"), details.fields?.map { it.name })
        assertEquals(listOf("front", "back"), details.fields?.map { it.value })
        assertEquals(listOf("zeta", "alpha"), details.tags)
        assertNull(details.flag)
        assertEquals(AnkiCardType.NEW, details.cardType)
        assertEquals(AnkiCardQueueState.NEW, details.queueState)
        assertEquals(0, details.scheduling?.reps)
        assertEquals(0L, details.scheduling?.rawDue)
        assertEquals(1, cardGateway.queryCalls)
        assertEquals(listOf("cards/100"), cardGateway.requestedPaths)
        assertEquals(listOf("100"), cardGateway.requestedCardIds)
        assertEquals(listOf("com.ichi2.anki.flashcards/notes/7", "com.ichi2.anki.flashcards/models/2"), provider.queryLog)
        assertEquals(0, provider.updateLog.size)
        assertEquals(0L, backend.ratingMutationInvocationCount)
        assertEquals(0L, backend.reviewerActionInvocationCount)
        assertEquals(0, reviewGateway.queryCalls)
    }

    @Test
    fun `missing note for exact live card is data integrity failure`() = runTest {
        val provider = FakeAnkiDroidProviderClient()
        val backend = backend(provider, scope = backgroundScope)
        val result = backend.getCardDetails(cardRef)
        assertTrue(result is AnkiResult.Failure)
        assertEquals("note_missing_for_card", ((result as AnkiResult.Failure).error as AnkiError.DataIntegrityFailure).detail)
    }

    @Test
    fun `card not found stops before note read and never chooses sibling`() = runTest {
        val provider = FakeAnkiDroidProviderClient()
        val cardGateway = FakeAnkiDroidCardGateway(
            mutableListOf(AnkiResult.Failure(AnkiError.CardNotFound(cardRef)))
        )
        val backend = backend(provider, cardGateway, scope = backgroundScope)
        val result = backend.getCardDetails(cardRef)
        assertTrue(result is AnkiResult.Failure)
        assertEquals(cardRef, (result as AnkiResult.Failure).error.let { (it as AnkiError.CardNotFound).card })
        assertTrue(provider.queryLog.isEmpty())
    }

    @Test
    fun `details capability is not claimed without note gateway`() = runTest {
        val provider = FakeAnkiDroidProviderClient()
        val unconfigured = AnkiDroidBackend(
            gateway = FakeAnkiDroidGateway(stateToReturn = integrationState()),
            scope = backgroundScope,
            deckGateway = FakeAnkiDroidDeckGateway(),
            reviewGateway = FakeAnkiDroidReviewGateway(),
            cardGateway = FakeAnkiDroidCardGateway()
        )
        assertEquals(false, unconfigured.capabilities.value.cardDetails)
        val result = unconfigured.getCardDetails(cardRef)
        assertTrue(result is AnkiResult.Failure)
        assertTrue((result as AnkiResult.Failure).error is AnkiError.UnsupportedAction)
        assertTrue(provider.queryLog.isEmpty())
    }
}
