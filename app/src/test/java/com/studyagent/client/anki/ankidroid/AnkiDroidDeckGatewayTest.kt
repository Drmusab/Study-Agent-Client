package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiContract
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailure
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureCategory
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureEvidence
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidDeckGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidProviderClient
import com.studyagent.client.data.anki.ankidroid.FakeProviderQueryResponse
import com.studyagent.client.testutil.TestClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnkiDroidDeckGatewayTest {

    private val authority = AnkiDroidApiContract.RELEASE_AUTHORITY

    private fun row(id: Any, name: String, counts: String? = "[0, 1, 2]", dyn: Any? = false) = buildMap<String, Any?> {
        put("deck_id", id)
        put("deck_name", name)
        if (counts != null) put("deck_count", counts)
        if (dyn != null) put("deck_dyn", dyn)
    }

    @Test
    fun `empty provider result is success empty not failure`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(AnkiDroidApiContract.DECKS_PATH, emptyList())
        val gateway = DefaultAnkiDroidDeckGateway(client, TestClock())
        val result = gateway.queryDecks(authority) as AnkiResult.Success
        assertTrue(result.value.decks.isEmpty())
        assertEquals(0, result.value.rowsSeen)
        assertEquals("SUCCEEDED", gateway.lastListingDiagnostics().lastStatus)
        assertEquals(0, gateway.lastListingDiagnostics().lastDeckCount)
    }

    @Test
    fun `unscripted query is empty success from fake default`() = runTest {
        val gateway = DefaultAnkiDroidDeckGateway(FakeAnkiDroidProviderClient(), TestClock())
        val result = gateway.queryDecks(authority) as AnkiResult.Success
        assertTrue(result.value.decks.isEmpty())
    }

    @Test
    fun `maps nested decks preserves names and sorts parents before children`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(
            AnkiDroidApiContract.DECKS_PATH,
            listOf(
                row(3L, "Medicine::Neurology"),
                row(1L, "Default"),
                row(2L, "Medicine::Cardiology"),
                row(4L, "Medicine")
            )
        )
        val result = DefaultAnkiDroidDeckGateway(client, TestClock()).queryDecks(authority) as AnkiResult.Success
        assertEquals(listOf("Default", "Medicine", "Medicine::Cardiology", "Medicine::Neurology"), result.value.decks.map { it.name })
        assertEquals(listOf("1", "4", "2", "3"), result.value.decks.map { it.ref.deckId })
        assertEquals(4, result.value.countsParsed)
        assertEquals(arrayOf("deck_id", "deck_name", "deck_count", "deck_dyn").toList(), client.projectionLog.single())
    }

    @Test
    fun `skips optional bad rows and fails when every row is unusable`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(
            AnkiDroidApiContract.DECKS_PATH,
            listOf(row(0L, "Bad"), row(5L, "Good"), row(6L, "   "))
        )
        val mixed = DefaultAnkiDroidDeckGateway(client, TestClock()).queryDecks(authority) as AnkiResult.Success
        assertEquals(listOf("Good"), mixed.value.decks.map { it.name })
        assertEquals(2, mixed.value.skippedRowCount)

        client.scriptRows(AnkiDroidApiContract.DECKS_PATH, listOf(row(0L, "Bad"), row(-1L, "Also")))
        val allBad = DefaultAnkiDroidDeckGateway(client, TestClock()).queryDecks(authority) as AnkiResult.Failure
        assertTrue(allBad.error is AnkiError.MalformedResponse)
        assertEquals("all_deck_rows_unusable", (allBad.error as AnkiError.MalformedResponse).detail)
    }

    @Test
    fun `missing identity column fails the query structurally`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(AnkiDroidApiContract.DECKS_PATH, listOf(mapOf("deck_name" to "Default")))
        val result = DefaultAnkiDroidDeckGateway(client, TestClock()).queryDecks(authority) as AnkiResult.Failure
        assertEquals("deck_id_column_missing", (result.error as AnkiError.MalformedResponse).detail)
    }

    @Test
    fun `provider permission failure is typed and never an empty list`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptFailure(
            AnkiDroidApiContract.DECKS_PATH,
            AnkiDroidFailure(
                category = AnkiDroidFailureCategory.PERMISSION_DENIED,
                evidence = AnkiDroidFailureEvidence.SECURITY_EXCEPTION_TYPE
            )
        )
        val gateway = DefaultAnkiDroidDeckGateway(client, TestClock())
        val result = gateway.queryDecks(authority) as AnkiResult.Failure
        assertTrue(result.error is AnkiError.PermissionRequired)
        assertEquals("FAILED", gateway.lastListingDiagnostics().lastStatus)
    }

    @Test
    fun `collection not ready is typed`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptFailure(
            AnkiDroidApiContract.DECKS_PATH,
            AnkiDroidFailure(
                category = AnkiDroidFailureCategory.COLLECTION_NOT_READY,
                evidence = AnkiDroidFailureEvidence.COLLECTION_SETUP_SIGNATURE
            )
        )
        val result = DefaultAnkiDroidDeckGateway(client, TestClock()).queryDecks(authority) as AnkiResult.Failure
        assertTrue(result.error is AnkiError.CollectionUnavailable)
    }

    @Test
    fun `duplicate ids keep the first sorted row`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(
            AnkiDroidApiContract.DECKS_PATH,
            listOf(row(1L, "First"), row(1L, "Duplicate"))
        )
        val result = DefaultAnkiDroidDeckGateway(client, TestClock()).queryDecks(authority) as AnkiResult.Success
        assertEquals(1, result.value.decks.size)
        assertEquals("First", result.value.decks.single().name)
        assertEquals(1, result.value.skippedRowCount)
    }

    @Test
    fun `selected deck empty is success null`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(AnkiDroidApiContract.SELECTED_DECK_PATH, emptyList())
        val result = DefaultAnkiDroidDeckGateway(client, TestClock()).querySelectedDeck(authority) as AnkiResult.Success
        assertEquals(null, result.value)
    }

    @Test
    fun `selected deck maps id`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(AnkiDroidApiContract.SELECTED_DECK_PATH, listOf(row(99L, "Default")))
        val result = DefaultAnkiDroidDeckGateway(client, TestClock()).querySelectedDeck(authority) as AnkiResult.Success
        assertEquals("99", result.value?.deckId)
    }

    @Test
    fun `cancellation propagates from provider`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.queryResponses[AnkiDroidApiContract.DECKS_PATH] =
            FakeProviderQueryResponse.Throw(CancellationException("cancelled"))
        try {
            DefaultAnkiDroidDeckGateway(client, TestClock()).queryDecks(authority)
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `concurrent queries single-flight the provider`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(AnkiDroidApiContract.DECKS_PATH, listOf(row(1L, "Default")))
        client.queryDelayMs = 50
        val gateway = DefaultAnkiDroidDeckGateway(client, TestClock())
        val a = async { gateway.queryDecks(authority) }
        val b = async { gateway.queryDecks(authority) }
        val ra = a.await() as AnkiResult.Success
        val rb = b.await() as AnkiResult.Success
        assertEquals(ra.value.decks, rb.value.decks)
        assertEquals(1, client.queryLog.size)
    }

    @Test
    fun `unicode names round trip`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(AnkiDroidApiContract.DECKS_PATH, listOf(row(8L, "طب::جراحة")))
        val result = DefaultAnkiDroidDeckGateway(client, TestClock()).queryDecks(authority) as AnkiResult.Success
        assertEquals("طب::جراحة", result.value.decks.single().name)
    }
}
