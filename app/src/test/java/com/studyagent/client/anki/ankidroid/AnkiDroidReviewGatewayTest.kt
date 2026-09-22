package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiRatingOptions
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.models.Rating
import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiContract
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailure
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureCategory
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureEvidence
import com.studyagent.client.data.anki.ankidroid.AnkiDroidReviewQueryDiagnostics
import com.studyagent.client.data.anki.ankidroid.AnkiDroidScheduledCardQuery
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidReviewGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidProviderClient
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidReviewGateway
import com.studyagent.client.testutil.TestClock
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 06 §88-§92 — the scheduled-review boundary.
 *
 * These tests are about the *shape of the conversation* with AnkiDroid: which endpoint, which
 * columns, which arguments, how many provider calls, and how each possible answer is classified.
 * They run on the JVM against [FakeAnkiDroidProviderClient], so they assert Study-Agent's contract
 * with the provider — never AnkiDroid's scheduler behaviour, which no JVM test can prove.
 */
class AnkiDroidReviewGatewayTest {

    private val authority = AnkiDroidApiContract.RELEASE_AUTHORITY
    private val deckRef = AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "1700000000000")

    private fun scheduledRow(
        noteId: Any? = 1700000000123L,
        ord: Any? = 0,
        buttons: Any? = 4,
        times: Any? = "[\"1m\",\"10m\",\"1d\",\"4d\"]",
        media: Any? = "[]"
    ): Map<String, Any?> = buildMap {
        if (noteId != null) put("note_id", noteId)
        if (ord != null) put("ord", ord)
        if (buttons != null) put("button_count", buttons)
        if (times != null) put("next_review_times", times)
        if (media != null) put("media_files", media)
    }

    private fun gateway(client: FakeAnkiDroidProviderClient, clock: TestClock = TestClock()) =
        DefaultAnkiDroidReviewGateway(client, clock)

    // ---------------------------------------------------------------- the request

    @Test
    fun `queries the schedule endpoint with one card and the deck scope`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(AnkiDroidApiContract.SCHEDULE_PATH, listOf(scheduledRow()))

        gateway(client).queryNextScheduledCard(authority, deckRef)

        assertEquals(listOf("$authority/${AnkiDroidApiContract.SCHEDULE_PATH}"), client.queryLog)
        // Only recognised columns may be requested: the endpoint *throws* on an unknown one,
        // unlike the deck listing which silently skips it.
        assertEquals(AnkiDroidApiContract.REVIEW_PROJECTION.toList(), client.projectionLog.single())
    }

    @Test
    fun `one logical query performs exactly one provider read`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(AnkiDroidApiContract.SCHEDULE_PATH, listOf(scheduledRow()))
        val gateway = gateway(client)

        gateway.queryNextScheduledCard(authority, deckRef)
        gateway.queryNextScheduledCard(authority, deckRef)

        // §143: a review turn must not cost a burst of full provider queries.
        assertEquals(2, client.queryLog.size)
        assertEquals(2L, gateway.lastQueryDiagnostics().providerQueryCount)
    }

    @Test
    fun `a foreign backend deck is refused before any provider traffic`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        val foreign = AnkiDeckRef(AnkiBackendId.PcAgent("desktop"), "1700000000000")

        val result = gateway(client).queryNextScheduledCard(authority, foreign)

        val error = (result as AnkiResult.Failure).error
        assertTrue(error is AnkiError.InvalidRequest)
        assertEquals("review_deck_foreign_backend", (error as AnkiError.InvalidRequest).detail)
        assertTrue("a foreign reference must never be reinterpreted", client.queryLog.isEmpty())
    }

    @Test
    fun `an unmappable deck id is a typed rejection not a parse crash`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        val unmappable = AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "not-a-number")

        val result = gateway(client).queryNextScheduledCard(authority, unmappable)

        val error = (result as AnkiResult.Failure).error
        assertEquals("deck_id_unmappable", (error as AnkiError.InvalidRequest).detail)
        assertTrue(client.queryLog.isEmpty())
    }

    @Test
    fun `a non positive or oversized limit is refused rather than forwarded`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        val gateway = gateway(client)

        for (bad in listOf(0, -1, AnkiDroidApiContract.REVIEW_MAX_LIMIT + 1)) {
            val error = (gateway.queryNextScheduledCard(authority, deckRef, bad) as AnkiResult.Failure).error
            assertTrue("limit $bad", error is AnkiError.InvalidRequest)
        }
        // limit = 0 is not "unlimited" and not "nothing due": it is refused, because Anki's queue
        // builder applies it as take(0) (§41/§42).
        assertTrue(client.queryLog.isEmpty())
    }

    // ---------------------------------------------------------------- the answers

    @Test
    fun `a scheduled card maps fully and reports its own latency`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(
            AnkiDroidApiContract.SCHEDULE_PATH,
            listOf(scheduledRow(media = "[\"front.png\"]", times = "[\"1m\",\"6m\",\"1d\",\"4d\"]"))
        )
        val clock = TestClock()
        val result = gateway(client, clock).queryNextScheduledCard(authority, deckRef, limit = 1)

        val scheduled = (result as AnkiResult.Success).value as AnkiDroidScheduledCardQuery.Scheduled
        assertEquals("1700000000123", scheduled.card.ref.noteId)
        assertEquals(0, scheduled.card.ref.cardOrd)
        assertNull(scheduled.card.ref.cardId)
        assertEquals(deckRef, scheduled.card.deckRef)
        assertEquals(
            listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY),
            (scheduled.card.ratingOptions as AnkiRatingOptions.Known).ratings
        )
        assertEquals("4d", scheduled.card.scheduling?.nextReviewTimes?.get(Rating.EASY))
        assertEquals(1, scheduled.card.media.size)
        assertTrue(scheduled.queryDurationMs >= 0L)
    }

    @Test
    fun `an empty answer is a successful no card due not a failure`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(AnkiDroidApiContract.SCHEDULE_PATH, emptyList())

        val gateway = gateway(client)
        val result = gateway.queryNextScheduledCard(authority, deckRef)

        // §31/§32: an exhausted deck is a valid state, never an error.
        assertTrue((result as AnkiResult.Success).value is AnkiDroidScheduledCardQuery.NoCardDue)
        assertEquals("NO_CARD", gateway.lastQueryDiagnostics().lastStatus)
        assertEquals(1L, gateway.lastQueryDiagnostics().providerQueryCount)
    }

    @Test
    fun `no card due is distinguishable from a provider failure`() = runTest {
        val empty = FakeAnkiDroidProviderClient()
        empty.scriptRows(AnkiDroidApiContract.SCHEDULE_PATH, emptyList())
        val emptyResult = gateway(empty).queryNextScheduledCard(authority, deckRef)
        assertTrue(emptyResult is AnkiResult.Success)

        val failing = FakeAnkiDroidProviderClient()
        failing.scriptFailure(
            AnkiDroidApiContract.SCHEDULE_PATH,
            AnkiDroidFailure(
                category = AnkiDroidFailureCategory.PROVIDER_ERROR,
                evidence = AnkiDroidFailureEvidence.UNCLASSIFIED_PROVIDER_STATE,
                exceptionClass = "IllegalStateException",
                evidenceToken = "collection_locked"
            )
        )
        val failingResult = gateway(failing).queryNextScheduledCard(authority, deckRef)
        assertTrue(failingResult is AnkiResult.Failure)
        assertTrue((failingResult as AnkiResult.Failure).error is AnkiError.QueryFailure)
    }

    @Test
    fun `permission loss becomes PermissionRequired through the shared mapper`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptFailure(
            AnkiDroidApiContract.SCHEDULE_PATH,
            AnkiDroidFailure(
                category = AnkiDroidFailureCategory.PERMISSION_DENIED,
                evidence = AnkiDroidFailureEvidence.SECURITY_EXCEPTION_TYPE,
                exceptionClass = "SecurityException",
                evidenceToken = "Permission not granted"
            )
        )
        val error = (gateway(client).queryNextScheduledCard(authority, deckRef) as AnkiResult.Failure).error
        assertTrue(error is AnkiError.PermissionRequired)
    }

    @Test
    fun `a malformed identity fails the turn instead of presenting a bare ordinal`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(AnkiDroidApiContract.SCHEDULE_PATH, listOf(scheduledRow(noteId = "not-a-note")))

        val error = (gateway(client).queryNextScheduledCard(authority, deckRef) as AnkiResult.Failure).error

        assertTrue(error is AnkiError.MalformedResponse)
        assertEquals("review_note_id_invalid", (error as AnkiError.MalformedResponse).detail)
    }

    @Test
    fun `a missing identity column is reported as a contract mismatch`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(AnkiDroidApiContract.SCHEDULE_PATH, listOf(mapOf("button_count" to 4)))

        val error = (gateway(client).queryNextScheduledCard(authority, deckRef) as AnkiResult.Failure).error

        assertEquals("review_note_id_column_missing", (error as AnkiError.MalformedResponse).detail)
    }

    @Test
    fun `only the first row is presented even if the provider ignores the limit`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(
            AnkiDroidApiContract.SCHEDULE_PATH,
            listOf(scheduledRow(noteId = 11L, ord = 0), scheduledRow(noteId = 22L, ord = 1))
        )

        val scheduled = (gateway(client).queryNextScheduledCard(authority, deckRef) as AnkiResult.Success)
            .value as AnkiDroidScheduledCardQuery.Scheduled

        // INV-ANKI-REV-03/08: a second card is never "the" current one.
        assertEquals("11", scheduled.card.ref.noteId)
    }

    @Test
    fun `cancellation propagates and leaves no poisoned state behind`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(AnkiDroidApiContract.SCHEDULE_PATH, listOf(scheduledRow()))
        client.queryDelayMs = 50L
        val gateway = gateway(client)

        val job = launch { gateway.queryNextScheduledCard(authority, deckRef) }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)

        // §140/INV-ANKI-REV-13: cancellation is not a domain failure, and the mutex must have been
        // released rather than converted into a permanent lock.
        client.queryDelayMs = 0L
        assertTrue(gateway.queryNextScheduledCard(authority, deckRef) is AnkiResult.Success)
    }

    @Test
    fun `concurrent requests cannot interleave two provider reads on one gateway`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(AnkiDroidApiContract.SCHEDULE_PATH, listOf(scheduledRow()))
        client.queryDelayMs = 5L
        val gateway = gateway(client)

        val first = async { gateway.queryNextScheduledCard(authority, deckRef) }
        val second = async { gateway.queryNextScheduledCard(authority, deckRef) }
        first.await()
        second.await()

        // §63: the provider is serialized, so the two calls are ordered, never overlapping.
        assertEquals(2, client.queryLog.size)
        assertEquals(2L, gateway.lastQueryDiagnostics().providerQueryCount)
    }

    // ---------------------------------------------------------------- diagnostics

    @Test
    fun `diagnostics carry identifiers and counts but never content`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows(
            AnkiDroidApiContract.SCHEDULE_PATH,
            listOf(scheduledRow(media = "[\"secret-name.png\"]"))
        )
        val gateway = gateway(client)
        gateway.queryNextScheduledCard(authority, deckRef)

        val diagnostics = gateway.lastQueryDiagnostics()
        assertEquals("SCHEDULED", diagnostics.lastStatus)
        assertEquals("1700000000000", diagnostics.lastDeckId)
        assertEquals("1700000000123", diagnostics.lastNoteId)
        assertEquals(0, diagnostics.lastCardOrd)
        assertEquals(4, diagnostics.lastButtonCount)
        assertEquals(1, diagnostics.lastMediaRefCount)
        assertEquals(1L, diagnostics.providerQueryCount)
        // Content stays out: the media filename and the interval labels appear in the *card*, and
        // only identifiers/counts may appear in diagnostics (§83/§136).
        val rendered = diagnostics.toString()
        assertTrue(rendered.contains("1700000000123"))
        assertTrue(rendered.none { it == '\u00ea' })
        assertTrue(!rendered.contains("secret-name.png"))
        assertTrue(!rendered.contains("1m"))
        assertEquals(0, diagnostics.lastDegradations.size)
    }

    @Test
    fun `a failed query records the error category and keeps the count honest`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptFailure(
            AnkiDroidApiContract.SCHEDULE_PATH,
            AnkiDroidFailure(
                category = AnkiDroidFailureCategory.TIMEOUT,
                evidence = AnkiDroidFailureEvidence.TIMEOUT_BUDGET,
                exceptionClass = null,
                evidenceToken = "review_timeout"
            )
        )
        val gateway = gateway(client)
        gateway.queryNextScheduledCard(authority, deckRef)

        val diagnostics = gateway.lastQueryDiagnostics()
        assertEquals("FAILED", diagnostics.lastStatus)
        assertEquals("QueryFailure", diagnostics.lastErrorCategory)
        assertEquals(1L, diagnostics.providerQueryCount)
    }

    @Test
    fun `a refusal never counts as a provider query`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        val gateway = gateway(client)
        gateway.queryNextScheduledCard(authority, AnkiDeckRef(AnkiBackendId.PcAgent("p"), "1"))

        assertEquals(0L, gateway.lastQueryDiagnostics().providerQueryCount)
        assertEquals("NONE", gateway.lastQueryDiagnostics().lastStatus)
    }

    // ---------------------------------------------------------------- the fake itself

    @Test
    fun `the fake gateway mirrors the contract it stands in for`() = runTest {
        val fake = FakeAnkiDroidReviewGateway()
        val first = fake.queryNextScheduledCard(authority, deckRef)
        assertEquals(1, fake.queryCalls)
        assertEquals(listOf("1700000000000"), fake.requestedDeckIds)
        // Unscripted means "nothing due", which is a success — the same shape the real gateway has.
        assertTrue(first is AnkiResult.Success)
        assertEquals(AnkiDroidReviewQueryDiagnostics.NONE, fake.lastQueryDiagnostics())
    }
}
