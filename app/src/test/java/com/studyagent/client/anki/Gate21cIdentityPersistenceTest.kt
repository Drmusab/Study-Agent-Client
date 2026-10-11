package com.studyagent.client.anki

import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 21C PART III — persisted identity and historical data safety (VER-21C-14 … VER-21C-16).
 *
 * The rule is DECISION-21B-17: a durable record already carries a full backend identity, and a
 * migration may rewrite *vocabulary* but never *ownership*. A record whose owner cannot be
 * established is unreadable — it is never assigned to the currently preferred backend, and a missing
 * collection key is honoured as unknown rather than back-filled.
 */
class Gate21cIdentityPersistenceTest {

    private val backend = AnkiBackendId.PcAgent("pc-a")
    private val otherBackend = AnkiBackendId.PcAgent("pc-b")
    private val json = Json { encodeDefaults = true }

    private fun record(
        backendId: AnkiBackendId = backend,
        collectionKey: String? = "collection-a",
        turn: String = "turn-1"
    ): ReviewCommitRecord {
        val card = AnkiCardRef(
            backendId = backendId,
            cardId = "123",
            noteId = "456",
            cardOrd = 0,
            collectionKey = collectionKey
        )
        return ReviewCommitRecord(
            commitId = ReviewCommitId(backendId, "study-1", ReviewTurnId(turn)),
            card = card,
            rating = Rating.GOOD,
            status = ReviewCommitStatus.PREPARED,
            attemptCount = 0,
            phase = ReviewCommitPhase.INTENT_PERSISTED,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L,
            ratedAtEpochMs = 900L,
            deckRef = AnkiDeckRef(backendId, "10", collectionKey),
            collectionRef = collectionKey?.let { AnkiCollectionIdentity(backendId, it) }
        )
    }

    private fun decoded(snapshot: String): ReviewCommitRecord {
        val result = ReviewCommitLedgerCodec.decode(snapshot)
        assertTrue("expected a decodable snapshot, got $result", result is ReviewCommitLedgerCodec.Decoded.Records)
        return (result as ReviewCommitLedgerCodec.Decoded.Records).records.single()
    }

    /** The identity subtree of the first persisted record — the evidence a migration must not touch. */
    private fun persistedIdentity(snapshot: String): JsonObject =
        Json.parseToJsonElement(snapshot).jsonObject.getValue("records").jsonArray[0].jsonObject
            .getValue("commitId").jsonObject

    // ----------------------------------------------------------------------------- VER-21C-14

    @Test
    fun `a record is restored with the backend it was written against`() {
        val original = record()
        val snapshot = ReviewCommitLedgerCodec.encode(listOf(original))
        val restored = decoded(snapshot)

        assertEquals(backend, restored.backendId)
        assertEquals(backend, restored.commitId.backendId)
        assertEquals(backend, restored.card.backendId)
        assertEquals("collection-a", restored.collectionKey)
        assertEquals(original, restored)

        // Reading the ledger never rewrites the persisted identity: re-encoding the restored record
        // produces the same commit identity byte for byte.
        assertEquals(
            persistedIdentity(snapshot),
            persistedIdentity(ReviewCommitLedgerCodec.encode(listOf(restored)))
        )
    }

    @Test
    fun `a legacy schema record keeps its owner and its unknown collection`() {
        val snapshot = ReviewCommitLedgerCodec.encode(listOf(record()))
        val legacy = legacySchema1(snapshot)
        val restored = decoded(legacy)

        // Ownership is preserved exactly; it is never re-derived from anything else.
        assertEquals(backend, restored.backendId)
        assertEquals(
            persistedIdentity(snapshot),
            persistedIdentity(ReviewCommitLedgerCodec.encode(listOf(restored)))
        )

        // The legacy row carried no collectionRef. Absence is honoured as unknown — no invented key
        // is added to the record — while the card's own evidence is left untouched.
        assertNull(restored.collectionRef)
        assertEquals("collection-a", restored.card.collectionKey)
        assertEquals("collection-a", restored.deckRef?.collectionKey)

        // A record written against another instance stays that instance's record.
        val other = decoded(legacySchema1(ReviewCommitLedgerCodec.encode(listOf(record(otherBackend)))))
        assertEquals(otherBackend, other.backendId)
        assertNotEquals(backend, other.backendId)
    }

    // ----------------------------------------------------------------------------- VER-21C-15

    @Test
    fun `an identity this build cannot decode is unreadable and never re-attributed`() {
        val snapshot = ReviewCommitLedgerCodec.encode(listOf(record()))

        // A future identity variant: the record is unreadable, not decoded into a neighbouring owner.
        val futureVariant = snapshot.replace("\"pc_agent\"", "\"pc_agent_v2\"")
        assertNotEquals(snapshot, futureVariant)
        assertTrue(
            ReviewCommitLedgerCodec.decode(futureVariant) is ReviewCommitLedgerCodec.Decoded.Unreadable
        )

        // A backend *kind* where an instance belongs is a schema defect, not a resolvable owner.
        val kindOnly = snapshot.replace(
            "{\"type\":\"pc_agent\",\"profileId\":\"pc-a\"}",
            "\"PC_AGENT\""
        )
        assertNotEquals(snapshot, kindOnly)
        assertTrue(ReviewCommitLedgerCodec.decode(kindOnly) is ReviewCommitLedgerCodec.Decoded.Unreadable)

        // An unsupported envelope version is refused rather than read with a default owner.
        val bumped = Json.parseToJsonElement(snapshot).jsonObject.toMutableMap().apply {
            this["schemaVersion"] = JsonPrimitive(99)
        }
        assertTrue(
            ReviewCommitLedgerCodec.decode(JsonObject(bumped).toString()) is
                ReviewCommitLedgerCodec.Decoded.Unreadable
        )
    }

    @Test
    fun `a durable record's identity context is derived from its own evidence`() {
        val restored = decoded(ReviewCommitLedgerCodec.encode(listOf(record())))
        assertEquals(
            AnkiBackendContext(backend, AnkiCollectionIdentity(backend, "collection-a")),
            restored.backendContext
        )

        // A record with no collection evidence maps to an unknown-collection context, never to a
        // default key — so a comparison against a live backend cannot accidentally match.
        val unknown = decoded(ReviewCommitLedgerCodec.encode(listOf(record(collectionKey = null))))
        assertEquals(AnkiBackendContext(backend), unknown.backendContext)
        assertNull(unknown.backendContext.collectionKey)
    }

    @Test
    fun `an unknown collection key is never back-filled while reading`() {
        val unknown = record(collectionKey = null)
        val restored = decoded(ReviewCommitLedgerCodec.encode(listOf(unknown)))

        assertNull(restored.collectionRef)
        assertNull(restored.card.collectionKey)
        assertNull(restored.collectionKey)
        assertEquals(unknown, restored)
        // The record is still recoverable evidence about *who* owes the mutation.
        assertEquals(backend, restored.backendId)
    }

    // ----------------------------------------------------------------------------- VER-21C-16

    @Test
    fun `a corrupt snapshot fails closed instead of producing a default owner`() {
        listOf("{not json", "", """{"schemaVersion":1}""", """{"schemaVersion":1,"records":[]}""").forEach { raw ->
            when (val result = ReviewCommitLedgerCodec.decode(raw)) {
                is ReviewCommitLedgerCodec.Decoded.Unreadable -> assertTrue(result.reason.isNotBlank())
                is ReviewCommitLedgerCodec.Decoded.Records -> assertTrue(
                    "an empty or malformed row must not silently become a record",
                    result.records.isEmpty()
                )
            }
        }
    }

    // ----------------------------------------------------------------- the other three families

    @Test
    fun `the reviewer action ledger preserves the owning backend and collection`() {
        val record = ReviewerActionRequest(
            sessionId = "study-13",
            turnId = ReviewTurnId("turn-1"),
            cardRef = AnkiCardRef(backend, cardId = "123", collectionKey = "collection-a"),
            action = ReviewerAction.BuryCard,
            collectionRef = AnkiCollectionIdentity(backend, "collection-a"),
            semantics = ReviewerActionSemantics(supportsIdempotentReplay = false, supportsAuthoritativeReconciliation = false)
        ).toRecord(1_000L)

        val snapshot = ReviewerActionLedgerCodec.encode(listOf(record))
        val result = ReviewerActionLedgerCodec.decode(snapshot)
        assertTrue("expected a decodable snapshot, got $result", result is ReviewerActionLedgerCodec.Decoded.Records)
        val restored = (result as ReviewerActionLedgerCodec.Decoded.Records).records.single()

        assertEquals(backend, restored.backendId)
        assertEquals(backend, restored.cardRef.backendId)
        assertEquals(AnkiCollectionIdentity(backend, "collection-a"), restored.collectionRef)
        assertEquals(record.actionId, restored.actionId)
        assertEquals(record.sessionId, restored.sessionId)
        assertEquals(record.turnId, restored.turnId)
        assertEquals(record.cardRef, restored.cardRef)
    }

    /**
     * Schemas 1/2/3 spelled the status as `state` and had no attempt phase; the adapter maps the
     * vocabulary forward. Identity fields are not part of that mapping — which is what this fixture
     * exercises. The downgrade mirrors the one `ReviewCommitLedgerTest` uses.
     */
    private fun legacySchema1(snapshot: String): String {
        val root = Json.parseToJsonElement(snapshot).jsonObject.toMutableMap()
        root["schemaVersion"] = JsonPrimitive(1)
        root["records"] = JsonArray(root.getValue("records").jsonArray.map { element ->
            JsonObject(element.jsonObject.toMutableMap().apply {
                remove("phase")
                remove("collectionRef")
                remove("committedRating")
                this["state"] = remove("status") ?: error("status missing in fixture")
            })
        })
        return JsonObject(root).toString()
    }
}
