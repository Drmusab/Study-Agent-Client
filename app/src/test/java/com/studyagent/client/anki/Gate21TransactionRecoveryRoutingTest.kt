package com.studyagent.client.anki

import com.studyagent.client.anki.edit.InMemoryNoteMutationStore
import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.InMemoryReviewCommitStore
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.anki.edit.DefaultNoteMutationLedger
import com.studyagent.client.core.anki.edit.NoteEditBase
import com.studyagent.client.core.anki.edit.NoteEditDraft
import com.studyagent.client.core.anki.edit.NoteEditPlanner
import com.studyagent.client.core.anki.edit.NoteMutationId
import com.studyagent.client.core.anki.edit.newPreparedNoteMutationRecord
import com.studyagent.client.core.models.Rating
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21 PART III — VER-21-23 / VER-21-24: transaction recovery routing.
 *
 * Two unresolved transactions, written against two different backends, persisted in the same app.
 * After a restart each must recover against **its own** backend: the PC one stays blocked while the
 * PC backend is missing, and the AnkiDroid one still recovers (DECISION-21-14/15/30/31,
 * INV-21-03/04/05). Nothing here may consult AUTO, the preference, or "whichever backend is ready".
 */
class Gate21TransactionRecoveryRoutingTest {

    private val localId = AnkiBackendId.AnkiDroidLocal
    private val pcId = AnkiBackendId.PcAgent("gate21-desktop")

    private fun local() = FakeAnkiBackend(id = localId, instanceId = "gate21-local")
    private fun pc() = FakeAnkiBackend(id = pcId, instanceId = "gate21-pc")

    private fun editBase(id: AnkiBackendId) = NoteEditBase(
        cardRef = AnkiCardRef(id, cardId = "card-1", noteId = "note-1", cardOrd = 0),
        noteRef = AnkiNoteRef(id, "note-1"),
        noteTypeId = "model-1",
        fields = listOf(AnkiNoteField("Front", "f", 0), AnkiNoteField("Back", "b", 1)),
        tags = emptyList(),
        deckRef = AnkiDeckRef(id, "deck-a")
    )

    /** A durable, non-terminal note mutation against [id] — the GATE 17 recovery surface. */
    private suspend fun persistMutation(id: AnkiBackendId, mutationId: String): DefaultNoteMutationLedger {
        val ledger = DefaultNoteMutationLedger(InMemoryNoteMutationStore(), nowEpochMs = { 1L })
        val base = editBase(id)
        val patch = NoteEditPlanner.buildPatch(base, NoteEditDraft(fieldValues = mapOf(1 to "changed")))
        val record = newPreparedNoteMutationRecord(
            NoteMutationId(mutationId),
            base,
            patch,
            NoteEditPlanner.buildPlan(patch),
            1L
        )
        ledger.create(record)
        return ledger
    }

    // -------------------------------------------------------------------------- VER-21-23

    @Test
    fun `unresolved transactions for two backends are grouped by their own persisted identity`() =
        runTest {
            val localLedger = persistMutation(localId, "m-local")
            val pcLedger = persistMutation(pcId, "m-pc")

            val localUnresolved = (localLedger.unresolved() as com.studyagent.client.core.anki.edit.NoteLedgerResult.Ok).value
            val pcUnresolved = (pcLedger.unresolved() as com.studyagent.client.core.anki.edit.NoteLedgerResult.Ok).value

            val grouped = AnkiTransactionRecoveryPlanner.groupByOwnerBackend(
                localUnresolved + pcUnresolved
            ) { it.backendId }

            assertEquals(setOf(localId, pcId), grouped.keys)
            assertEquals(listOf("m-local"), grouped.getValue(localId).map { it.mutationId.value })
            assertEquals(listOf("m-pc"), grouped.getValue(pcId).map { it.mutationId.value })
        }

    @Test
    fun `each backend group routes to its own registered backend`() = runTest {
        val localBackend = local()
        val pcBackend = pc()
        val planner = AnkiTransactionRecoveryPlanner(
            AnkiBackendRegistry(listOf(localBackend, pcBackend))
        )

        val plan = planner.plan(listOf(localId, pcId))

        val localRoute = plan.getValue(localId)
        val pcRoute = plan.getValue(pcId)
        assertTrue(localRoute is BackendTransactionRoute.Routed)
        assertTrue(pcRoute is BackendTransactionRoute.Routed)
        assertSame(localBackend, (localRoute as BackendTransactionRoute.Routed).backend)
        assertSame(pcBackend, (pcRoute as BackendTransactionRoute.Routed).backend)
    }

    // -------------------------------------------------------------------------- VER-21-24

    @Test
    fun `a missing backend blocks only its own group and never borrows the other one`() = runTest {
        val localBackend = local()
        // The PC backend is NOT registered in this process.
        val planner = AnkiTransactionRecoveryPlanner(AnkiBackendRegistry(listOf(localBackend)))

        val plan = planner.plan(listOf(localId, pcId))

        assertEquals(
            BackendTransactionRoute.Blocked(pcId, BackendTransactionBlockReason.BACKEND_NOT_REGISTERED),
            plan.getValue(pcId)
        )
        // The AnkiDroid group is untouched by the PC outage (DECISION-21-31).
        assertTrue(plan.getValue(localId) is BackendTransactionRoute.Routed)
    }

    @Test
    fun `an unreachable backend keeps its transactions blocked while another backend is ready`() =
        runTest {
            val localBackend = local()
            val pcBackend = pc()
            pcBackend.setAvailability(AnkiAvailability.AgentDisconnected)
            val planner = AnkiTransactionRecoveryPlanner(
                AnkiBackendRegistry(listOf(localBackend, pcBackend))
            )

            val plan = planner.plan(listOf(localId, pcId))

            assertEquals(
                BackendTransactionRoute.Blocked(pcId, BackendTransactionBlockReason.BACKEND_UNREACHABLE),
                plan.getValue(pcId)
            )
            assertTrue(plan.getValue(localId) is BackendTransactionRoute.Routed)
        }

    // ------------------------------------------------------------------ DECISION-21-16

    @Test
    fun `a reconnected backend on a different collection stays blocked`() = runTest {
        val localBackend = local()
        val planner = AnkiTransactionRecoveryPlanner(AnkiBackendRegistry(listOf(localBackend)))

        val plan = planner.plan(
            ownerBackendIds = listOf(localId),
            collectionKeys = mapOf(localId to "collection-a"),
            activeCollectionKeys = mapOf(localId to "collection-b")
        )

        assertEquals(
            BackendTransactionRoute.Blocked(localId, BackendTransactionBlockReason.COLLECTION_MISMATCH),
            plan.getValue(localId)
        )
    }

    @Test
    fun `absent collection identity is recorded honestly and blocks nothing`() = runTest {
        // AnkiDroid exposes no authoritative collection identity today (DISCOVERY-21-03): an unknown
        // key is not a mismatch, and inventing one would either block all recovery or fake equality.
        val localBackend = local()
        val planner = AnkiTransactionRecoveryPlanner(AnkiBackendRegistry(listOf(localBackend)))

        val plan = planner.plan(
            ownerBackendIds = listOf(localId),
            collectionKeys = mapOf(localId to null),
            activeCollectionKeys = mapOf(localId to "whatever-is-open")
        )

        assertTrue(plan.getValue(localId) is BackendTransactionRoute.Routed)
    }

    // --------------------------------------- VER-21-23: routing is wired into commit recovery

    /**
     * The planner is not decorative: commit recovery actually routes through it, and a record whose
     * backend is absent is refused with the *specific* reason the planner computed. Before GATE 21
     * every such record produced one indistinguishable "backend_missing", which could not tell a
     * support report whether the backend had vanished or was never in this build (DECISION-21-14,
     * INV-21-03).
     */
    @Test
    fun `commit recovery refuses an orphaned record with the planner's own reason`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock = { 1L })
        val card = AnkiCardRef(localId, cardId = "card-1", collectionKey = "collection")
        val request = CommitRatingRequest(
            commitId = ReviewCommitId(localId, "study-1", ReviewTurnId("turn-1")),
            card = card,
            rating = Rating.GOOD,
            ratedAtEpochMs = 1L,
            deckRef = AnkiDeckRef(localId, "deck-a", "collection"),
            collectionRef = AnkiCollectionIdentity(localId, "collection")
        )
        assertTrue(ledger.prepare(request) is ReviewCommitLedger.PrepareResult.Prepared)

        // An executor for a process where ONLY the PC backend exists: the AnkiDroid record's own
        // backend is unregistered here. Recovery must refuse it — and must not borrow the PC
        // backend that is standing right there and ready (INV-21-04).
        val executor = com.studyagent.client.core.study.AnkiStudyEffectExecutor(
            registry = AnkiBackendRegistry(listOf(pc())),
            ledger = ledger,
            clock = { 1L }
        )

        executor.commit(request)

        // The canonical status set is closed (GATE 11B): a refusal is RETRY_ALLOWED plus the
        // refused_before_dispatch resolution token, never a dedicated REFUSED status. The new part
        // is the *reason* — it now names why the backend could not be reached.
        val refused = ledger.get(request.commitId)
        assertEquals(ReviewCommitStatus.RETRY_ALLOWED, refused?.status)
        assertEquals(ReviewCommitResolution.REFUSED_BEFORE_DISPATCH, refused?.resolution)
        assertEquals("backend_not_registered", refused?.failure?.category)
        // Nothing was dispatched anywhere: no scheduler on any backend saw this rating.
        assertEquals(0, ledger.pendingRecovery(localId).size)
    }

    /**
     * The same orphaned record routed through a registry that *does* hold its backend must not be
     * refused for that reason — otherwise the new reason would be a blanket failure rather than a
     * distinction.
     */
    @Test
    fun `commit recovery does not call a registered backend orphaned`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock = { 1L })
        val card = AnkiCardRef(localId, cardId = "card-1", collectionKey = "collection")
        val request = CommitRatingRequest(
            commitId = ReviewCommitId(localId, "study-2", ReviewTurnId("turn-1")),
            card = card,
            rating = Rating.GOOD,
            ratedAtEpochMs = 1L,
            deckRef = AnkiDeckRef(localId, "deck-a", "collection"),
            collectionRef = AnkiCollectionIdentity(localId, "collection")
        )
        assertTrue(ledger.prepare(request) is ReviewCommitLedger.PrepareResult.Prepared)

        val executor = com.studyagent.client.core.study.AnkiStudyEffectExecutor(
            registry = AnkiBackendRegistry(listOf(local())),
            ledger = ledger,
            clock = { 1L }
        )

        executor.commit(request)

        val record = ledger.get(request.commitId)
        // This bare fake has no live review session, so the commit is legitimately refused for a
        // *different* reason. The point of this test is the contrast: a registered backend is
        // never reported as orphaned, so "backend_not_registered" is a real distinction rather
        // than a blanket failure that would fire either way.
        assertTrue(
            "registered backend must not be called orphaned, got ${record?.failure?.category}",
            record?.failure?.category != "backend_not_registered"
        )
        assertNotEquals("backend_not_registered", record?.failure?.category)
    }

    // -------------------------------------------------------------- review-commit identity

    @Test
    fun `a durable rating transaction carries the backend it was written against`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock = { 1L })
        val card = AnkiCardRef(localId, cardId = "card-1", collectionKey = "collection")
        val request = CommitRatingRequest(
            commitId = ReviewCommitId(localId, "study-1", ReviewTurnId("turn-1")),
            card = card,
            rating = Rating.GOOD,
            ratedAtEpochMs = 1L,
            deckRef = AnkiDeckRef(localId, "deck-a", "collection"),
            collectionRef = AnkiCollectionIdentity(localId, "collection")
        )
        assertTrue(ledger.prepare(request) is ReviewCommitLedger.PrepareResult.Prepared)

        // Recovery reads the identity persisted IN the record, never the current preference: the
        // unresolved surface (ReviewCommitLedger.unresolved) is what a startup scan groups by.
        val unresolved = ledger.unresolved()
        assertEquals(1, unresolved.size)
        assertEquals(localId, unresolved.single().backendId)
        assertEquals(localId, unresolved.single().commitId.backendId)
        // Grouping by the persisted identity yields exactly one group, addressed to AnkiDroid.
        val grouped = AnkiTransactionRecoveryPlanner.groupByOwnerBackend(unresolved) { it.backendId }
        assertEquals(setOf(localId), grouped.keys)
        // Nothing is addressable to the PC backend, so nothing can be replayed there.
        assertEquals(0, ledger.pendingRecovery(pcId).size)
        assertEquals(0, unresolved.count { it.backendId == pcId })
    }
}
