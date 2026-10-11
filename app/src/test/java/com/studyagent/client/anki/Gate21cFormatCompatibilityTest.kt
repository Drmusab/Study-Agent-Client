package com.studyagent.client.anki

import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 21C PART III — compatibility of already-serialized identity (VER-21C-13, §4.5).
 *
 * Three properties are asserted here that the per-type round-trip tests cannot express:
 *
 * 1. §4.5.3 — a pruned transaction's identity lives *inside* the persisted `commitKey`, so the
 *    length-prefixed composition is a wire format, not an implementation detail. Reading a snapshot
 *    written by an older build re-derives that identity **from the key**, never from the current
 *    preference and never from a default.
 * 2. §4.5.4 — a key this build cannot resolve (a future variant, a truncated key, a foreign string)
 *    makes exactly the records it owns unreadable, with a stable diagnostic token.
 * 3. §4.5.2 + §3.5 — a *restored* record's own evidence bounds the assurance it can ever support:
 *    a durable record is never promoted to `SESSION_CONTINUITY_VERIFIED`, an absent key is never
 *    upgraded to a match, and comparing two instances never yields "unverifiable".
 */
class Gate21cFormatCompatibilityTest {

    private val backendA = AnkiBackendId.PcAgent("pc-a")
    private val backendB = AnkiBackendId.PcAgent("pc-b")

    private fun record(
        backendId: AnkiBackendId = backendA,
        session: String = "study-1",
        turn: String = "turn-1",
        collectionKey: String? = "collection-a"
    ): ReviewCommitRecord = ReviewCommitRecord(
        commitId = ReviewCommitId(backendId, session, ReviewTurnId(turn)),
        card = AnkiCardRef(
            backendId = backendId,
            cardId = "123",
            noteId = "456",
            cardOrd = 0,
            collectionKey = collectionKey
        ),
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

    /** The documented wire composition (BACKEND_IDENTITY_CONTRACT §4.2): `<len>:<value>`, repeated. */
    private fun key(vararg parts: String): String = parts.joinToString("") { "${it.length}:$it" }

    private fun recordsJsonOf(records: List<ReviewCommitRecord>): String =
        Json.parseToJsonElement(ReviewCommitLedgerCodec.encode(records))
            .jsonObject.getValue("records").toString()

    private fun tombstoneJson(vararg tombstones: String): String =
        """{"schemaVersion":${ReviewCommitLedgerCodec.SCHEMA_VERSION},""" +
            """"records":${recordsJsonOf(listOf(record()))},"tombstones":[${tombstones.joinToString(",")}]}"""

    private fun decoded(snapshot: String): ReviewCommitLedgerCodec.Decoded.Records {
        val result = ReviewCommitLedgerCodec.decode(snapshot)
        assertTrue("expected a decodable snapshot, got $result", result is ReviewCommitLedgerCodec.Decoded.Records)
        return result as ReviewCommitLedgerCodec.Decoded.Records
    }

    private fun unreadableReason(snapshot: String): String {
        val result = ReviewCommitLedgerCodec.decode(snapshot)
        assertTrue("expected an unreadable snapshot, got $result", result is ReviewCommitLedgerCodec.Decoded.Unreadable)
        return (result as ReviewCommitLedgerCodec.Decoded.Unreadable).reason
    }

    // ------------------------------------------------------------------ §4.5.3 persisted composition

    @Test
    fun `a pruned identity is carried inside the persisted key and survives re-encoding`() {
        // The live record holds turn-1; the pruned identity is a *different* turn of the same
        // session, which is the only way a pruned key can coexist with a live one.
        val record = record()
        val tombstone = CommitIdentityTombstone(
            commitKey = ReviewCommitId(backendA, "study-1", ReviewTurnId("turn-2")).stableKey,
            prunedAtEpochMs = 5L,
            backendId = backendA,
            turnId = ReviewTurnId("turn-2")
        )
        val snapshot = ReviewCommitLedgerCodec.encode(listOf(record), listOf(tombstone))
        val restored = decoded(snapshot)

        // The pruned identity is the persisted key, decoded back to exactly the instance that wrote it.
        val restoredTombstone = restored.tombstones.single()
        assertEquals(tombstone.commitKey, restoredTombstone.commitKey)
        assertEquals(backendA, restoredTombstone.backendId)
        assertEquals(ReviewTurnId("turn-2"), restoredTombstone.turnId)
        assertEquals(tombstone, restoredTombstone)

        // Byte stability: reading a snapshot never rewrites its identity bytes, and the composition
        // is the length-prefixed form — an unambiguously segmented key, not a joined string.
        assertEquals(
            Json.parseToJsonElement(snapshot).jsonObject.getValue("tombstones").jsonArray[0],
            Json.parseToJsonElement(ReviewCommitLedgerCodec.encode(restored.records, restored.tombstones))
                .jsonObject.getValue("tombstones").jsonArray[0]
        )
        assertEquals(key(backendA.stableId, "study-1", "turn-1"), record.commitId.stableKey)
        assertNotEquals(
            ReviewCommitId(backendA, "study-1", ReviewTurnId("turn-1")).stableKey + "1",
            ReviewCommitId(backendA, "study-1", ReviewTurnId("turn-11")).stableKey
        )
    }

    // -------------------------------------------------------- §4.5.1 / §4.5.4 older and unknown rows

    @Test
    fun `a tombstone written before the identity fields existed is re-derived from its key alone`() {
        val commitKey = ReviewCommitId(backendA, "study-1", ReviewTurnId("turn-2")).stableKey

        // The row an older build wrote: no identity fields at all.
        val absent = tombstoneJson("""{"commitKey":"$commitKey","prunedAtEpochMs":5}""")
        // The row an older encoder writes when it serializes defaults as explicit nulls.
        val explicitNulls = tombstoneJson(
            """{"commitKey":"$commitKey","prunedAtEpochMs":5,"backendId":null,"turnId":null}"""
        )

        listOf(absent, explicitNulls).forEach { snapshot ->
            val restored = decoded(snapshot).tombstones.single()
            assertEquals(backendA, restored.backendId)
            assertEquals(ReviewTurnId("turn-2"), restored.turnId)
            // Re-derived from the key its owner wrote — never from the other configured instance.
            assertNotEquals(backendB, restored.backendId)
            assertEquals(commitKey, restored.commitKey)
        }

        // A key encoding another instance keeps *that* instance, even though this build only ever
        // writes backendA records in this fixture.
        val otherKey = ReviewCommitId(backendB, "study-1", ReviewTurnId("turn-1")).stableKey
        val other = decoded(
            """{"schemaVersion":${ReviewCommitLedgerCodec.SCHEMA_VERSION},"records":[],""" +
                """"tombstones":[{"commitKey":"$otherKey","prunedAtEpochMs":5}]}"""
        ).tombstones.single()
        assertEquals(backendB, other.backendId)
    }

    @Test
    fun `a key that names no configured instance is unreadable and never re-attributed`() {
        // A future identity variant: an internally consistent key this build cannot resolve. The
        // decoder must refuse rather than decode it into the neighbouring resolved instance.
        val futureToken = "pc_agent_v2:pc-a"
        val futureKey = key(futureToken, "study-1", "turn-1")
        assertNull(AnkiBackendId.fromStableId(futureToken))

        listOf(
            futureKey,
            key("pc_agent:pc-a"),            // truncated: only one of the three components
            "not-a-key",                     // no length prefixes at all
            key("pc_agent:pc-a", "", "turn") // a component the identity constructor rejects
        ).forEach { unreadableKey ->
            assertEquals(
                "expected a refused key for `$unreadableKey`",
                "unreadable_tombstone_identity",
                unreadableReason(tombstoneJson("""{"commitKey":"$unreadableKey","prunedAtEpochMs":5}"""))
            )
        }
    }

    @Test
    fun `a pruned identity conflicts only with its own instance and its own turn`() {
        // Same turn *text*, different instance: turn uniqueness is per backend, so this is decodable
        // and the tombstone still reports the instance that wrote it.
        val foreignKey = ReviewCommitId(backendB, "study-1", ReviewTurnId("turn-1")).stableKey
        val foreign = decoded(tombstoneJson("""{"commitKey":"$foreignKey","prunedAtEpochMs":5}"""))
        assertEquals(1, foreign.records.size)
        assertEquals(backendA, foreign.records.single().backendId)
        assertEquals(backendB, foreign.tombstones.single().backendId)

        // Same (instance, turn) as a live record reached through a *different* session key: the
        // re-derived identity — not the key string — is what decides, so this is an integrity
        // failure rather than an accepted second transaction.
        val collidingKey = ReviewCommitId(backendA, "study-other", ReviewTurnId("turn-1")).stableKey
        assertEquals(
            "tombstone_turn_conflict",
            unreadableReason(tombstoneJson("""{"commitKey":"$collidingKey","prunedAtEpochMs":5}"""))
        )

        // A pruned key that is still live cannot be laundered by attaching a different identity pair.
        val recordKey = record().commitId.stableKey
        assertEquals(
            "tombstone_overlaps_record",
            unreadableReason(
                tombstoneJson(
                    """{"commitKey":"$recordKey","prunedAtEpochMs":5,""" +
                        """"backendId":{"type":"pc_agent","profileId":"pc-b"},"turnId":{"value":"turn-9"}}"""
                )
            )
        )
    }

    @Test
    fun `the format refuses an unimplemented schema with a stable token`() {
        val record = recordsJsonOf(listOf(record()))
        assertEquals(
            "unsupported_schema_99",
            unreadableReason("""{"schemaVersion":99,"records":$record,"tombstones":[]}""")
        )
        assertEquals(
            "unsupported_schema_0",
            unreadableReason("""{"schemaVersion":0,"records":$record,"tombstones":[]}""")
        )
        assertEquals(
            "malformed_snapshot",
            unreadableReason("""{"records":$record,"tombstones":[]}""")
        )
        assertEquals("malformed_snapshot", unreadableReason(""))
    }

    // -------------------------------------------------- §3.5 bounded by the record's own evidence

    @Test
    fun `a restored record's own evidence is the upper bound of the assurance it can support`() {
        val restored = decoded(ReviewCommitLedgerCodec.encode(listOf(record()))).records.single()
        val context = restored.backendContext
        assertEquals(backendA, context.backendId)
        assertEquals("collection-a", context.collectionKey)
        assertTrue(context.hasAuthoritativeCollectionIdentity)

        // Its own evidence verifies itself, and only against the key it actually carries.
        assertEquals(
            AnkiIdentityCheck.SameBackend(AnkiIdentityAssurance.AuthoritativeMatch("collection-a")),
            context.resourceRelation(backendA, "collection-a")
        )
        assertEquals(
            AnkiIdentityCheck.SameBackend(
                AnkiIdentityAssurance.AuthoritativeMismatch("collection-a", "collection-b")
            ),
            context.resourceRelation(backendA, "collection-b")
        )
        // A key that disappears is mismatch-grade, never "no key, therefore fine".
        assertEquals(
            AnkiIdentityCheck.SameBackend(
                AnkiIdentityAssurance.IdentityUnverifiable(AnkiUnverifiabilityQualifier.DEGRADED)
            ),
            context.resourceRelation(backendA, null)
        )
        // Another configured instance is never comparable, so it can never read as unverifiable.
        assertEquals(
            AnkiIdentityCheck.ForeignBackend(backendA, backendB),
            context.resourceRelation(backendB, "collection-a")
        )

        // A record that never had a key stays unknown, and a durable record is not an in-process
        // binding: the honest category is nominal unverifiability — never continuity, never a match.
        val keyless = decoded(
            ReviewCommitLedgerCodec.encode(listOf(record(collectionKey = null)))
        ).records.single()
        assertNull(keyless.backendContext.collectionKey)
        assertEquals(
            AnkiIdentityCheck.SameBackend(
                AnkiIdentityAssurance.IdentityUnverifiable(AnkiUnverifiabilityQualifier.NOMINAL)
            ),
            keyless.backendContext.resourceRelation(backendA, null)
        )
        assertFalse(keyless.backendContext.resourceRelation(backendA, null) ==
            AnkiIdentityCheck.SameBackend(AnkiIdentityAssurance.SessionContinuityVerified))
        assertEquals(
            AnkiIdentityCheck.SameBackend(
                AnkiIdentityAssurance.IdentityUnverifiable(AnkiUnverifiabilityQualifier.DEGRADED)
            ),
            keyless.backendContext.resourceRelation(backendA, "collection-b")
        )

        // Assurance is computed, never written back: comparing against any live side leaves the
        // record's own persisted identity untouched.
        assertEquals("collection-a", restored.collectionKey)
        assertEquals("collection-a", restored.card.collectionKey)
        assertEquals(keyless, decoded(ReviewCommitLedgerCodec.encode(listOf(keyless))).records.single())
    }
}
