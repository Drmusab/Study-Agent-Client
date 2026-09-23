package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.models.Rating
import com.studyagent.client.testutil.TestClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 07 — the shape of the conversation with AnkiDroid's card endpoint (STEP 11-§13/§43-§50).
 *
 * Which endpoint, which columns, which failures and — just as importantly — that the
 * conversation is read-only and identity-verified. Runs on the JVM against
 * [FakeAnkiDroidProviderClient]; AnkiDroid's own rendering behaviour is not asserted here (no
 * JVM test can prove it).
 */
class AnkiDroidCardGatewayTest {

    private val authority = AnkiDroidApiContract.RELEASE_AUTHORITY

    private val byCardId = AnkiCardRef(
        backendId = AnkiBackendId.AnkiDroidLocal, cardId = "42", noteId = "7", cardOrd = 0, collectionKey = null
    )
    private val byNoteOrd = AnkiCardRef(
        backendId = AnkiBackendId.AnkiDroidLocal, cardId = null, noteId = "7", cardOrd = 1, collectionKey = null
    )

    private fun cardRow(cardId: Any? = 42L, noteId: Any? = 7L, ord: Any? = 0): Map<String, Any?> = linkedMapOf(
        "_id" to cardId,
        "note_id" to noteId,
        "ord" to ord,
        "deck_id" to 1L,
        "question" to "<b>Front</b>",
        "answer" to "Back<hr id=answer>Tail",
        "question_simple" to "Front",
        "answer_simple" to "Back",
        "answer_pure" to "Tail"
    )

    private fun gateway(client: FakeAnkiDroidProviderClient) = DefaultAnkiDroidCardGateway(client, TestClock())

    // ---------------------------------------------------------------- the request

    @Test fun `a card id reads the card endpoint with the pinned explicit projection`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows("cards/42", listOf(cardRow()))
        val result = gateway(client).queryCard(authority, byCardId)

        val card = (result as AnkiResult.Success).value
        assertEquals("42", card.ref.cardId)
        assertEquals("<b>Front</b>", card.questionHtml)
        assertEquals("Back<hr id=answer>Tail", card.answerHtml)
        assertEquals("Front", card.questionText)
        assertEquals("Back", card.answerText)
        assertEquals("Tail", card.pureAnswerText)
        // The projected columns are pinned — an explicit projection, never SELECT * (STEP 13/§14).
        assertEquals(listOf("$authority/cards/42"), client.queryLog)
        assertEquals(listOf(AnkiDroidApiContract.CARD_PROJECTION.toList()), client.projectionLog)
    }

    @Test fun `a note plus ordinal reads the note card endpoint`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows("notes/7/cards/1", listOf(cardRow(cardId = 84L, noteId = 7L, ord = 1)))
        val result = gateway(client).queryCard(authority, byNoteOrd) as AnkiResult.Success
        assertEquals("84", result.value.ref.cardId) // enrichment: the row learned the card id
        assertEquals(listOf("$authority/notes/7/cards/1"), client.queryLog)
    }

    @Test fun `a foreign backend reference fails before any provider traffic`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        val foreign = byCardId.copy(backendId = AnkiBackendId.PcAgent("desktop"))
        val result = gateway(client).queryCard(authority, foreign) as AnkiResult.Failure
        assertTrue(result.error is AnkiError.InvalidRequest)
        assertTrue(client.queryLog.isEmpty())
    }

    // ---------------------------------------------------------------- the answers

    @Test fun `an empty singular lookup is CardNotFound, never a blank card`() = runTest {
        val client = FakeAnkiDroidProviderClient() // unscripted → Empty
        val result = gateway(client).queryCard(authority, byCardId) as AnkiResult.Failure
        val notFound = result.error as AnkiError.CardNotFound
        assertEquals("42", notFound.card?.cardId)
    }

    @Test fun `the provider's documented not found signatures map to CardNotFound`() = runTest {
        for (signature in AnkiDroidContractSignatures.ENTITY_NOT_FOUND) {
            val client = FakeAnkiDroidProviderClient()
            client.scriptFailure(
                "cards/42",
                AnkiDroidFailure(
                    category = AnkiDroidFailureCategory.ENTITY_NOT_FOUND,
                    evidence = AnkiDroidFailureEvidence.UNCLASSIFIED,
                    exceptionClass = "BackendNotFoundException",
                    evidenceToken = signature
                )
            )
            val result = gateway(client).queryCard(authority, byCardId) as AnkiResult.Failure
            assertTrue("signature $signature", result.error is AnkiError.CardNotFound)
        }
    }

    @Test fun `a row that does not confirm the requested identity is StaleCardReference`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        // The row answers with a completely different note + ordinal.
        client.scriptRows("cards/42", listOf(cardRow(noteId = 99L, ord = 3)))
        val result = gateway(client).queryCard(authority, byCardId) as AnkiResult.Failure
        val stale = result.error as AnkiError.StaleCardReference
        assertEquals("card_identity_mismatch", stale.detail)
        assertEquals("42", stale.card?.cardId)
    }

    @Test fun `enrichment is not a mismatch`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        // Asked by note + ord (no card id); the row confirms and adds the card id (STEP 54).
        client.scriptRows("notes/7/cards/1", listOf(cardRow(cardId = 5L, noteId = 7L, ord = 1)))
        val result = gateway(client).queryCard(authority, byNoteOrd) as AnkiResult.Success
        assertEquals("5", result.value.ref.cardId)
    }

    @Test fun `structural row problems surface as MalformedResponse with content free detail`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows("cards/42", listOf(mapOf("unrelated" to "x")))
        val result = gateway(client).queryCard(authority, byCardId) as AnkiResult.Failure
        val malformed = result.error as AnkiError.MalformedResponse
        assertEquals(AnkiDroidCardRowProblem.IDENTITY_COLUMN_MISSING.token, malformed.detail)

        val missingQuestion = FakeAnkiDroidProviderClient()
        missingQuestion.scriptRows("cards/42", listOf(mapOf("_id" to 42L, "note_id" to 7L, "ord" to 0)))
        val second = gateway(missingQuestion).queryCard(authority, byCardId) as AnkiResult.Failure
        assertEquals(
            AnkiDroidCardRowProblem.QUESTION_CONTENT_MISSING.token,
            (second.error as AnkiError.MalformedResponse).detail
        )
    }

    @Test fun `permission and collection losses keep their typed errors`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptFailure(
            "cards/42",
            AnkiDroidFailure(
                category = AnkiDroidFailureCategory.PERMISSION_DENIED,
                evidence = AnkiDroidFailureEvidence.UNCLASSIFIED,
                exceptionClass = "SecurityException"
            )
        )
        assertTrue((gateway(client).queryCard(authority, byCardId) as AnkiResult.Failure).error is AnkiError.PermissionRequired)

        val locked = FakeAnkiDroidProviderClient()
        locked.scriptFailure(
            "cards/42",
            AnkiDroidFailure(
                category = AnkiDroidFailureCategory.COLLECTION_NOT_READY,
                evidence = AnkiDroidFailureEvidence.UNCLASSIFIED,
                exceptionClass = "IllegalStateException"
            )
        )
        assertTrue((gateway(locked).queryCard(authority, byCardId) as AnkiResult.Failure).error is AnkiError.CollectionUnavailable)
    }

    // ---------------------------------------------------------------- the guarantees

    @Test fun `cancellation propagates and is never converted into a domain failure`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.queryResponses["cards/42"] = FakeProviderQueryResponse.Throw(CancellationException("cancelled"))
        var caught: Throwable? = null
        try {
            gateway(client).queryCard(authority, byCardId)
        } catch (throwable: Throwable) {
            caught = throwable
        }
        assertTrue(caught is CancellationException)
    }

    @Test fun `concurrent lookups of one card are serialised and complete`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows("cards/42", listOf(cardRow()))
        client.queryDelayMs = 5L
        val gateway = gateway(client)
        val results = List(10) { async { gateway.queryCard(authority, byCardId) } }.awaitAll()
        assertTrue(results.all { it is AnkiResult.Success })
        // One bounded projected query per completed lookup, never a burst of diverging queries.
        assertEquals(10, client.queryLog.size)
        assertTrue(client.projectionLog.all { it == AnkiDroidApiContract.CARD_PROJECTION.toList() })
    }

    @Test fun `diagnostics are metadata only and count completed provider queries`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows("cards/42", listOf(cardRow()))
        val gateway = gateway(client)
        assertEquals(AnkiDroidCardQueryDiagnostics.NONE, gateway.lastQueryDiagnostics())
        gateway.queryCard(authority, byCardId)

        val diagnostics = gateway.lastQueryDiagnostics()
        assertEquals("HYDRATED", diagnostics.lastStatus)
        assertEquals("42", diagnostics.lastCardId)
        assertEquals("7", diagnostics.lastNoteId)
        assertEquals(0, diagnostics.lastCardOrd)
        assertEquals(9, diagnostics.questionHtmlLength) // "<b>Front</b>"
        assertEquals(1, diagnostics.providerQueryCount)
        // Lengths and availability only — the payload itself must not be observable (STEP 88/89).
        val serialized = diagnostics.toString()
        assertFalse("diagnostics must not carry question text", serialized.contains("Front"))
        assertFalse("diagnostics must not carry answer text", serialized.contains("Back"))
    }

    @Test fun `degradation tokens surface as metadata without failing the card`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows("cards/42", listOf(cardRow(cardId = "garbage")))
        val gateway = gateway(client)
        val result = gateway.queryCard(authority, byCardId) as AnkiResult.Success
        assertEquals(listOf(AnkiDroidCardMapper.DEG_CARD_ID_UNREADABLE), result.value.degradations)
        // The content-free token is observable in diagnostics; the card still hydrated.
        assertEquals(listOf(AnkiDroidCardMapper.DEG_CARD_ID_UNREADABLE), gateway.lastQueryDiagnostics().lastDegradations)
    }

    @Test fun `the media references of a card stay references and flags stay absent`() = runTest {
        val client = FakeAnkiDroidProviderClient()
        client.scriptRows("cards/42", listOf(cardRow()))
        val card = (gateway(client).queryCard(authority, byCardId) as AnkiResult.Success).value
        // The pinned card surface has no media and no flags column (v2.24.1) — nothing invented
        // (STEP 31/38, INV-ANKI-CARD-20/21).
        assertTrue(card.media.isEmpty())
        assertNull(card.flag)
    }

    @Test fun `a rating is never part of this conversation`() = runTest {
        // Structural guarantee of the interface above: queryCard takes an identity and nothing
        // else. This test documents that there is no rating parameter to misuse (STEP 09/§146) —
        // the compile-time shape is the assertion.
        val parameters = AnkiDroidCardGateway::class.java.methods
            .first { it.name == "queryCard" }
            .parameters
            .map { it.type }
            .toSet()
        assertTrue(Rating::class.java !in parameters)
    }
}
