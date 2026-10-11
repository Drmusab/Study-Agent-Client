package com.studyagent.client.anki

import com.studyagent.client.core.anki.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 21C PART III — identity assurance (VER-21C-01 … VER-21C-03, VER-21C-09, VER-21C-10).
 *
 * The rules under test are DECISION-21B-01, DECISION-21B-02 and DECISION-21B-03: a configured
 * instance is not a kind, an unknown collection is never a match, assurance is contextual evidence
 * that never rewrites identity, and the §3.3 matrix answers per *operation class*, not per category.
 */
class Gate21cIdentityAssuranceTest {

    private val json = Json

    private val desktopA = AnkiBackendId.PcAgent("desktop-a")
    private val desktopB = AnkiBackendId.PcAgent("desktop-b")
    private val local = AnkiBackendId.AnkiDroidLocal

    private fun observation(id: AnkiBackendId, key: String? = null) = AnkiIdentityObservation(id, key)

    private fun sameBackend(check: AnkiIdentityCheck): AnkiIdentityAssurance =
        (check as AnkiIdentityCheck.SameBackend).assurance

    // ------------------------------------------------------------- VER-21C-01 / VER-21C-03

    @Test
    fun `two configured instances can share one kind without becoming equal`() {
        assertEquals(AnkiBackendKind.PC_AGENT, desktopA.kind)
        assertEquals(AnkiBackendKind.PC_AGENT, desktopB.kind)
        assertEquals(AnkiBackendKind.ANKIDROID_LOCAL, local.kind)

        // Kind is metadata about an identity; it is never the identity itself.
        assertNotEquals(desktopA, desktopB)
        assertNotEquals(desktopA.stableId, desktopB.stableId)
        assertNotEquals(AnkiBackendContext(desktopA), AnkiBackendContext(desktopB))

        // Two configured instances are not even comparable: the answer is typed, and it is not
        // "unverifiable" (which would invite a caller to treat them as possibly the same).
        assertEquals(
            AnkiIdentityCheck.ForeignBackend(desktopA, desktopB),
            AnkiBackendContext(desktopA).resourceRelation(desktopB, null)
        )
    }

    @Test
    fun `a stable identity survives reconstruction from its own stable id`() {
        listOf(local, desktopA, AnkiBackendId.Fake("contract")).forEach { id ->
            assertTrue(id.stableId, id.roundTripsStableId)
            assertTrue(id.stableId, id.discriminatorIsNormalized)
            assertEquals(id, AnkiBackendId.fromStableId(id.stableId))
        }
        // Nothing unrecognized may decode into a default identity.
        assertNull(AnkiBackendId.fromStableId(null))
        assertNull(AnkiBackendId.fromStableId(""))
        assertNull(AnkiBackendId.fromStableId("pc_agent"))
        assertNull(AnkiBackendId.fromStableId("pc_agent:"))
        assertNull(AnkiBackendId.fromStableId("ankidroid"))
        assertNull(AnkiBackendId.fromStableId("pc_agent_v2:desktop-a"))

        // A3/A5: the discriminator is normalized at allocation, and a stored token is never rewritten.
        assertFalse(AnkiBackendId.PcAgent("  desktop-a  ").discriminatorIsNormalized)
        assertEquals("pc_agent:  desktop-a  ", AnkiBackendId.PcAgent("  desktop-a  ").stableId)
    }

    @Test
    fun `an unknown collection identity is never an authoritative match`() {
        val context = AnkiBackendContext(desktopA)
        assertFalse(context.hasAuthoritativeCollectionIdentity)
        assertNull(context.collectionKey)

        // Two unknowns are not evidence of each other: the honest answer is nominal unverifiability.
        assertEquals(
            AnkiIdentityAssurance.IdentityUnverifiable(AnkiUnverifiabilityQualifier.NOMINAL),
            sameBackend(context.resourceRelation(desktopA, null))
        )
        assertNotEquals(
            AnkiIdentityAssurance.AuthoritativeMatch("anything"),
            sameBackend(context.resourceRelation(desktopA, null))
        )

        // A durable record with no key is nominal too — never upgraded by being loaded.
        assertEquals(
            AnkiIdentityAssurance.IdentityUnverifiable(AnkiUnverifiabilityQualifier.NOMINAL),
            AnkiIdentityAssurancePolicy.assuranceFor(
                bound = observation(desktopA, null),
                live = observation(desktopA, null),
                boundIsLiveInThisProcess = false
            )
        )
    }

    @Test
    fun `an unknown collection key survives serialization as unknown`() {
        val encoded = json.encodeToString(
            AnkiCollectionIdentity.serializer(),
            AnkiCollectionIdentity(desktopA, null)
        )
        assertTrue(encoded, encoded.contains("\"collectionKey\":null"))
        assertFalse("no sentinel may stand in for absence", encoded.contains("?"))
        assertEquals(
            AnkiCollectionIdentity(desktopA, null),
            json.decodeFromString(AnkiCollectionIdentity.serializer(), encoded)
        )
        // A known key is preserved byte-identically, whatever it contains.
        val keyed = AnkiCollectionIdentity(desktopA, "profile:α|1")
        assertEquals(
            keyed,
            json.decodeFromString(
                AnkiCollectionIdentity.serializer(),
                json.encodeToString(AnkiCollectionIdentity.serializer(), keyed)
            )
        )
    }

    // ----------------------------------------------------------------------------- VER-21C-10

    @Test
    fun `assurance follows the connection while the identity value does not change`() {
        val context = AnkiBackendContext(desktopA, AnkiCollectionIdentity(desktopA, "collection-a"))

        val matched = sameBackend(
            AnkiIdentityAssurancePolicy.check(context.observation, observation(desktopA, "collection-a"), true)
        )
        val degraded = sameBackend(
            AnkiIdentityAssurancePolicy.check(context.observation, observation(desktopA, null), true)
        )
        val mismatched = sameBackend(
            AnkiIdentityAssurancePolicy.check(context.observation, observation(desktopA, "collection-b"), true)
        )

        assertEquals(AnkiIdentityAssurance.AuthoritativeMatch("collection-a"), matched)
        assertEquals(
            AnkiIdentityAssurance.IdentityUnverifiable(AnkiUnverifiabilityQualifier.DEGRADED),
            degraded
        )
        assertEquals(AnkiIdentityAssurance.AuthoritativeMismatch("collection-a", "collection-b"), mismatched)

        // Recomputing is pure: an earlier success is never cached into a later answer, and the
        // identity itself was never rewritten by any of the three observations.
        assertEquals("collection-a", context.collectionKey)
        assertEquals(
            matched,
            sameBackend(
                AnkiIdentityAssurancePolicy.check(context.observation, observation(desktopA, "collection-a"), true)
            )
        )
        assertEquals(
            degraded,
            sameBackend(
                AnkiIdentityAssurancePolicy.check(context.observation, observation(desktopA, null), true)
            )
        )
    }

    // ------------------------------------------------------------------- §3.5 computation

    @Test
    fun `the assurance computation implements every branch of the locked algorithm`() {
        assertEquals(
            AnkiIdentityAssurance.AuthoritativeMatch("k"),
            AnkiIdentityAssurancePolicy.assuranceFor(observation(desktopA, "k"), observation(desktopA, "k"), true)
        )
        assertEquals(
            AnkiIdentityAssurance.AuthoritativeMismatch("k1", "k2"),
            AnkiIdentityAssurancePolicy.assuranceFor(observation(desktopA, "k1"), observation(desktopA, "k2"), false)
        )
        // We could tell before and cannot now → degraded, never "no key, therefore fine".
        assertEquals(
            AnkiIdentityAssurance.IdentityUnverifiable(AnkiUnverifiabilityQualifier.DEGRADED),
            AnkiIdentityAssurancePolicy.assuranceFor(observation(desktopA, "k"), observation(desktopA, null), true)
        )
        // A key appeared for a binding that had none → degraded too: the reporting contract moved.
        assertEquals(
            AnkiIdentityAssurance.IdentityUnverifiable(AnkiUnverifiabilityQualifier.DEGRADED),
            AnkiIdentityAssurancePolicy.assuranceFor(observation(desktopA, null), observation(desktopA, "k"), true)
        )
        // No key either side, live in-process binding on the same instance → continuity only.
        assertEquals(
            AnkiIdentityAssurance.SessionContinuityVerified,
            AnkiIdentityAssurancePolicy.assuranceFor(observation(desktopA, null), observation(desktopA, null), true)
        )
        // No key either side, no live binding (a restored record) → nominal unverifiability.
        assertEquals(
            AnkiIdentityAssurance.IdentityUnverifiable(AnkiUnverifiabilityQualifier.NOMINAL),
            AnkiIdentityAssurancePolicy.assuranceFor(observation(desktopA, null), observation(desktopA, null), false)
        )
    }

    @Test
    fun `the strict algorithm refuses to compare two configured instances`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnkiIdentityAssurancePolicy.assuranceFor(
                observation(desktopA, null),
                observation(desktopB, null),
                true
            )
        }
    }

    // ------------------------------------------------------------------- §3.3 matrix

    @Test
    fun `nominal unverifiability continues bound work but never reconciles an ambiguity`() {
        val nominal = AnkiIdentityAssurance.IdentityUnverifiable(AnkiUnverifiabilityQualifier.NOMINAL)
        val check = AnkiIdentityCheck.SameBackend(nominal)

        listOf(
            AnkiOperationClass.OP_R1, AnkiOperationClass.OP_R2, AnkiOperationClass.OP_S1,
            AnkiOperationClass.OP_S2, AnkiOperationClass.OP_M1
        ).forEach { operation ->
            assertEquals(
                operation.name,
                nominal,
                (AnkiIdentityContinuation.continuationVerdict(operation, check) as AnkiContinuationVerdict.Permitted).assurance
            )
        }

        // An ambiguous mutation is never reconciled on unverifiable evidence, and a proven
        // non-application may only be retried when the family's proof is collection-independent.
        assertEquals(
            AnkiContinuationBlockReason.RECONCILIATION_REQUIRES_AUTHORITATIVE_MATCH,
            blockedReason(AnkiOperationClass.OP_M3, check)
        )
        assertEquals(
            AnkiContinuationBlockReason.NON_APPLICATION_PROOF_NEEDS_COLLECTION_IDENTITY,
            blockedReason(AnkiOperationClass.OP_M2, check)
        )
        assertTrue(
            AnkiIdentityContinuation.continuationVerdict(
                AnkiOperationClass.OP_M2,
                check,
                AnkiOperationFacts(nonApplicationProofIsCollectionIndependent = true)
            ) is AnkiContinuationVerdict.Permitted
        )

        // Recovery after process death is decided by the family's own rule, conservatively by default.
        assertEquals(
            AnkiContinuationBlockReason.FAMILY_REQUIRES_COLLECTION_PROOF,
            blockedReason(AnkiOperationClass.OP_P, check)
        )
        assertTrue(
            AnkiIdentityContinuation.continuationVerdict(
                AnkiOperationClass.OP_P,
                check,
                AnkiOperationFacts(familyRecoveryRequiresCollectionProof = false)
            ) is AnkiContinuationVerdict.Permitted
        )
    }

    @Test
    fun `degraded evidence is mismatch-grade and fails closed`() {
        val degraded = AnkiIdentityCheck.SameBackend(
            AnkiIdentityAssurance.IdentityUnverifiable(AnkiUnverifiabilityQualifier.DEGRADED)
        )
        // Default facts: strictly nothing is admitted, including the conditional OP-R1 cell, whose
        // block reason names the §3.5 branch that produced it (a key appeared for a keyless binding).
        AnkiOperationClass.entries.forEach { operation ->
            assertTrue(operation.name, AnkiIdentityContinuation.continuationVerdict(operation, degraded) is AnkiContinuationVerdict.Blocked)
        }
        assertEquals(
            AnkiContinuationBlockReason.NEW_BINDING_BLOCKED_BY_APPEARED_KEY,
            blockedReason(AnkiOperationClass.OP_R1, degraded)
        )
        assertEquals(
            AnkiContinuationBlockReason.IDENTITY_UNVERIFIABLE_DEGRADED,
            blockedReason(AnkiOperationClass.OP_M1, degraded)
        )

        // §3.3's OP-R1 degraded cell is conditional: "Permitted for a new binding only if the backend
        // currently offers no key at all". That is the *key lost* direction, where nothing can be
        // misattributed, and it is the only permission the degraded column grants.
        val backendOffersNoKey = AnkiOperationFacts(backendCurrentlyOffersNoCollectionKey = true)
        assertEquals(
            AnkiContinuationVerdict.Permitted(degraded.assurance),
            AnkiIdentityContinuation.continuationVerdict(AnkiOperationClass.OP_R1, degraded, backendOffersNoKey)
        )
        listOf(
            AnkiOperationClass.OP_R2, AnkiOperationClass.OP_S1, AnkiOperationClass.OP_S2,
            AnkiOperationClass.OP_M1, AnkiOperationClass.OP_M2, AnkiOperationClass.OP_M3,
            AnkiOperationClass.OP_P
        ).forEach { operation ->
            assertTrue(
                operation.name,
                AnkiIdentityContinuation.continuationVerdict(operation, degraded, backendOffersNoKey)
                    is AnkiContinuationVerdict.Blocked
            )
        }
    }

    @Test
    fun `a verified mismatch blocks every operation class and every cell is answered`() {
        val mismatch = AnkiIdentityCheck.SameBackend(AnkiIdentityAssurance.AuthoritativeMismatch("a", "b"))
        AnkiOperationClass.entries.forEach { operation ->
            assertTrue(operation.name, AnkiIdentityContinuation.continuationVerdict(operation, mismatch) is AnkiContinuationVerdict.Blocked)
        }
        assertEquals(
            AnkiContinuationBlockReason.NEW_BINDING_HAS_NO_PRIOR_IDENTITY,
            blockedReason(AnkiOperationClass.OP_R1, mismatch)
        )
        assertEquals(
            AnkiContinuationBlockReason.COLLECTION_MISMATCH,
            blockedReason(AnkiOperationClass.OP_M1, mismatch)
        )
    }

    @Test
    fun `continuity is neither collection proof nor reconciliation authority`() {
        val continuity = AnkiIdentityCheck.SameBackend(AnkiIdentityAssurance.SessionContinuityVerified)
        listOf(
            AnkiOperationClass.OP_R1, AnkiOperationClass.OP_R2, AnkiOperationClass.OP_S1,
            AnkiOperationClass.OP_S2, AnkiOperationClass.OP_M1
        ).forEach { operation ->
            assertTrue(
                operation.name,
                AnkiIdentityContinuation.continuationVerdict(operation, continuity) is AnkiContinuationVerdict.Permitted
            )
        }
        assertEquals(
            AnkiContinuationBlockReason.CONTINUITY_IS_NOT_COLLECTION_EVIDENCE,
            blockedReason(AnkiOperationClass.OP_M3, continuity)
        )
        assertEquals(
            AnkiContinuationBlockReason.NO_IN_PROCESS_BINDING,
            blockedReason(AnkiOperationClass.OP_P, continuity)
        )
        assertEquals(
            AnkiContinuationBlockReason.NON_APPLICATION_PROOF_NEEDS_COLLECTION_IDENTITY,
            blockedReason(AnkiOperationClass.OP_M2, continuity)
        )
    }

    @Test
    fun `an authoritative match authorizes reconciliation only with the family's own read`() {
        val matched = AnkiIdentityCheck.SameBackend(AnkiIdentityAssurance.AuthoritativeMatch("k"))
        assertEquals(
            AnkiContinuationBlockReason.RECONCILIATION_REQUIRES_AUTHORITATIVE_MATCH,
            blockedReason(AnkiOperationClass.OP_M3, matched)
        )
        assertTrue(
            AnkiIdentityContinuation.continuationVerdict(
                AnkiOperationClass.OP_M3,
                matched,
                AnkiOperationFacts(authoritativeReconciliationAvailable = true)
            ) is AnkiContinuationVerdict.Permitted
        )
        AnkiOperationClass.entries
            .filterNot { it == AnkiOperationClass.OP_M3 }
            .forEach { operation ->
                assertTrue(
                    operation.name,
                    AnkiIdentityContinuation.continuationVerdict(operation, matched) is AnkiContinuationVerdict.Permitted
                )
            }
    }

    @Test
    fun `a foreign backend is blocked for every operation and never becomes unverifiable`() {
        val foreign = AnkiIdentityCheck.ForeignBackend(desktopA, local)
        AnkiOperationClass.entries.forEach { operation ->
            assertEquals(
                operation.name,
                AnkiContinuationBlockReason.FOREIGN_BACKEND,
                blockedReason(operation, foreign)
            )
        }
    }

    @Test
    fun `the verdict is a pure function of its inputs and carries its evidence`() {
        val check = AnkiIdentityCheck.SameBackend(AnkiIdentityAssurance.AuthoritativeMatch("k"))
        assertEquals(
            AnkiIdentityContinuation.continuationVerdict(AnkiOperationClass.OP_S1, check),
            AnkiIdentityContinuation.continuationVerdict(AnkiOperationClass.OP_S1, check)
        )

        // A blocked verdict carries the observation so a caller can explain the refusal, and a
        // block never substitutes a different backend.
        val foreignCheck = AnkiIdentityCheck.ForeignBackend(desktopA, desktopB)
        val blocked = AnkiIdentityContinuation.continuationVerdict(AnkiOperationClass.OP_S2, foreignCheck)
        assertEquals(foreignCheck, (blocked as AnkiContinuationVerdict.Blocked).observed)
    }

    private fun blockedReason(operation: AnkiOperationClass, check: AnkiIdentityCheck): AnkiContinuationBlockReason =
        (AnkiIdentityContinuation.continuationVerdict(operation, check) as AnkiContinuationVerdict.Blocked).reason
}
