package com.studyagent.client.anki.edit

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.AnkiOperationClass
import com.studyagent.client.core.anki.edit.ChangedFieldMark
import com.studyagent.client.core.anki.edit.NoteMutationId
import com.studyagent.client.core.anki.edit.NoteMutationOperation
import com.studyagent.client.core.anki.edit.NoteMutationPlan
import com.studyagent.client.core.anki.edit.NoteMutationReason
import com.studyagent.client.core.anki.edit.NoteMutationRecord
import com.studyagent.client.core.anki.edit.NoteMutationRoute
import com.studyagent.client.core.anki.edit.NoteMutationRoutingPolicy
import com.studyagent.client.core.anki.edit.NoteMutationSemantics
import com.studyagent.client.core.anki.edit.NoteMutationStatus
import com.studyagent.client.core.anki.edit.mutationOperationClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21J — the note family's routing verdicts, one cell at a time. Pure policy: no ledger, no
 * coordinator, no backend call. Every backend here is a [FakeNoteMutationBackend].
 */
class NoteMutationRoutingPolicyTest {

    private val ankiDroid = AnkiBackendId.AnkiDroidLocal
    private val pcB = AnkiBackendId.PcAgent("profile-b")
    private val collection1 = AnkiCollectionIdentity(pcB, "coll-1")
    private val collection2 = AnkiCollectionIdentity(pcB, "coll-2")

    private fun record(
        backendId: AnkiBackendId,
        status: NoteMutationStatus = NoteMutationStatus.PREPARED,
        attempts: Int = 0,
        collection: AnkiCollectionIdentity? = null
    ) = NoteMutationRecord(
        mutationId = NoteMutationId("m-1"),
        backendId = backendId,
        cardRef = AnkiCardRef(backendId, cardId = "card-1", noteId = "note-1", cardOrd = 0),
        noteRef = AnkiNoteRef(backendId, "note-1"),
        noteTypeId = "model-1",
        changedFields = listOf(ChangedFieldMark(0, "Front")),
        tagsChanged = false,
        deckChange = null,
        plan = NoteMutationPlan(
            listOf(NoteMutationOperation.UpdateNoteContent(updatesFields = true, updatesTags = false))
        ),
        status = status,
        lastEnteredOperation = -1,
        attemptCount = attempts,
        reason = NoteMutationReason.NONE,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
        collectionRef = collection
    )

    /** One registered owner. [collection] is what the live instance reports now. */
    private fun policy(
        owner: AnkiBackendId,
        collection: AnkiCollectionIdentity?,
        authoritativeReconciliation: Boolean = false
    ): NoteMutationRoutingPolicy {
        val backend = FakeNoteMutationBackend(
            backendId = owner,
            // The locked `OP-M3` cell needs BOTH an authoritative match and this claim, so the tests
            // that exercise a permitted reconciliation must model a backend that honestly makes it.
            noteMutationSemantics = NoteMutationSemantics.ANKIDROID_V2_24_1.copy(
                authoritativeReconciliation = authoritativeReconciliation
            )
        )
        backend.collectionIdentity = collection
        return NoteMutationRoutingPolicy(AnkiBackendRegistry(listOf(backend)))
    }

    private fun NoteMutationRoute.tokenOrNull(): String? = (this as? NoteMutationRoute.Blocked)?.token

    private fun NoteMutationRoute.isRouted(): Boolean = this is NoteMutationRoute.Routed

    /** Stable token for the locked `OP-M3` block: no authoritative match, or no authoritative read. */
    private val RECONCILIATION_BLOCKED = "reconciliation_requires_authoritative_match"

    // ---------------------------------------------------------------- operation classes

    @Test
    fun preparedWithNoAttemptIsAdmissionAndEverythingElseIsARepeat() {
        assertEquals(AnkiOperationClass.OP_M1, record(pcB).mutationOperationClass())
        assertEquals(
            AnkiOperationClass.OP_M2,
            record(pcB, NoteMutationStatus.RETRY_ALLOWED, attempts = 1).mutationOperationClass()
        )
        assertEquals(
            AnkiOperationClass.OP_M2,
            record(pcB, NoteMutationStatus.PREPARED, attempts = 1).mutationOperationClass()
        )
        assertEquals(
            AnkiOperationClass.OP_M2,
            record(pcB, NoteMutationStatus.AMBIGUOUS, attempts = 1).mutationOperationClass()
        )
    }

    // ---------------------------------------------------------------- admission

    @Test
    fun admissionNeedsOnlyRegistryExistence() {
        val p = policy(pcB, collection = null)
        assertTrue(p.admit(pcB).isRouted())
        assertEquals("backend_not_registered", p.admit(ankiDroid).tokenOrNull())
    }

    @Test
    fun admissionRoutesToTheInstanceWithTheSameIdentity() {
        val owner = FakeNoteMutationBackend(backendId = pcB)
        val other = FakeNoteMutationBackend(backendId = ankiDroid)
        val p = NoteMutationRoutingPolicy(AnkiBackendRegistry(listOf(other, owner)))
        val routed = p.admit(pcB) as NoteMutationRoute.Routed
        assertTrue(routed.backend === owner)
    }

    // ---------------------------------------------------------------- registry existence

    @Test
    fun unregisteredOwnerIsBlockedForEveryOperation() {
        val p = policy(ankiDroid, collection = null)
        for (op in listOf(AnkiOperationClass.OP_M1, AnkiOperationClass.OP_M2, AnkiOperationClass.OP_M3)) {
            assertEquals(
                "op=$op",
                "backend_not_registered",
                p.route(record(pcB, collection = collection1), op, boundIsLiveInThisProcess = true).tokenOrNull()
            )
        }
    }

    // ---------------------------------------------------------------- collection evidence

    @Test
    fun verifiedMismatchBlocksEveryOperation() {
        val p = policy(pcB, collection = collection2)
        val bound = record(pcB, collection = collection1)
        for (op in listOf(AnkiOperationClass.OP_M1, AnkiOperationClass.OP_M2, AnkiOperationClass.OP_M3)) {
            assertEquals(
                "op=$op",
                "collection_mismatch",
                p.route(bound, op, boundIsLiveInThisProcess = true).tokenOrNull()
            )
        }
    }

    @Test
    fun matchingCollectionRoutesWritesButNotReconciliationWithoutTheFamilyClaim() {
        // §3.3 `OP-M3` / AUTHORITATIVE_MATCH: permitted *only* if the family also exposes an
        // authoritative reconciliation read. AnkiDroid's semantics claim none, so it stays blocked.
        val p = policy(pcB, collection = collection1)
        val bound = record(pcB, collection = collection1)
        for (op in listOf(AnkiOperationClass.OP_M1, AnkiOperationClass.OP_M2)) {
            assertTrue("op=$op", p.route(bound, op, boundIsLiveInThisProcess = false).isRouted())
        }
        assertEquals(
            RECONCILIATION_BLOCKED,
            p.route(bound, AnkiOperationClass.OP_M3, boundIsLiveInThisProcess = false).tokenOrNull()
        )
    }

    @Test
    fun matchingCollectionRoutesReconciliationWhenTheFamilyExposesAnAuthoritativeRead() {
        val p = policy(pcB, collection = collection1, authoritativeReconciliation = true)
        val bound = record(pcB, collection = collection1)
        assertTrue(p.route(bound, AnkiOperationClass.OP_M3, boundIsLiveInThisProcess = false).isRouted())
    }

    @Test
    fun aKeyThatDisappearsIsDegradedAndBlockedForRepeatsAndReconciliation() {
        val p = policy(pcB, collection = null)
        val bound = record(pcB, collection = collection1)
        assertEquals(
            "identity_unverifiable_degraded",
            p.route(bound, AnkiOperationClass.OP_M2, boundIsLiveInThisProcess = true).tokenOrNull()
        )
        assertEquals(
            "identity_unverifiable_degraded",
            p.route(bound, AnkiOperationClass.OP_M3, boundIsLiveInThisProcess = true).tokenOrNull()
        )
    }

    @Test
    fun aKeyThatAppearsWhereNoneWasBoundIsDegradedAndBlocked() {
        val p = policy(pcB, collection = collection2)
        val unknownAtAdmission = record(pcB, collection = null)
        assertEquals(
            "identity_unverifiable_degraded",
            p.route(unknownAtAdmission, AnkiOperationClass.OP_M2, boundIsLiveInThisProcess = true).tokenOrNull()
        )
        assertEquals(
            "identity_unverifiable_degraded",
            p.route(unknownAtAdmission, AnkiOperationClass.OP_M3, boundIsLiveInThisProcess = true).tokenOrNull()
        )
    }

    // ------------------------------------------------- DECISION-21J-03 REJECTED: locked §3.3 `OP-M3`

    @Test
    fun reconciliationIsBlockedWhenNeitherSideHasAKeyEvenWithAnAuthoritativeRead() {
        // §3.3 NOMINAL / `OP-M3` is Blocked. The family claim does not reach this cell: an unknown
        // collection is not a match, so there is nothing for an authoritative read to be authoritative
        // about. This is the cell DECISION-21J-03 tried to open; the audit rejected it.
        val p = policy(pcB, collection = null, authoritativeReconciliation = true)
        val unknownBoth = record(pcB, collection = null)
        assertEquals(
            RECONCILIATION_BLOCKED,
            p.route(unknownBoth, AnkiOperationClass.OP_M3, boundIsLiveInThisProcess = false).tokenOrNull()
        )
    }

    @Test
    fun reconciliationIsBlockedOnInProcessContinuityAlone() {
        // §3.3 SESSION_CONTINUITY_VERIFIED / `OP-M3`: "continuity cannot establish whether the
        // mutation applied". Holding the in-process payload is not collection evidence.
        val p = policy(pcB, collection = null, authoritativeReconciliation = true)
        val unknownBoth = record(pcB, collection = null)
        assertEquals(
            "continuity_is_not_collection_evidence",
            p.route(unknownBoth, AnkiOperationClass.OP_M3, boundIsLiveInThisProcess = true).tokenOrNull()
        )
    }

    // ---------------------------------------------------------------- proof of non-application

    @Test
    fun repeatAfterProvenRefusalIsRoutedWithoutCollectionEvidence() {
        // DECISION-21J-02: the pre-write refusal classification does not rest on collection identity.
        val p = policy(pcB, collection = null)
        val unknownBoth = record(pcB, NoteMutationStatus.RETRY_ALLOWED, attempts = 1, collection = null)
        assertTrue(p.route(unknownBoth, AnkiOperationClass.OP_M2, boundIsLiveInThisProcess = false).isRouted())
        assertTrue(p.route(unknownBoth, AnkiOperationClass.OP_M2, boundIsLiveInThisProcess = true).isRouted())
    }

    @Test
    fun routeNeverNamesAnotherBackend() {
        val owner = FakeNoteMutationBackend(backendId = pcB)
        val other = FakeNoteMutationBackend(backendId = ankiDroid)
        val p = NoteMutationRoutingPolicy(AnkiBackendRegistry(listOf(other, owner)))
        val routed = p.route(record(pcB), AnkiOperationClass.OP_M2, boundIsLiveInThisProcess = false)
        assertTrue(routed is NoteMutationRoute.Routed)
        assertTrue((routed as NoteMutationRoute.Routed).backend === owner)
    }
}
