package com.studyagent.client.anki.edit

import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiDeck
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiNoteField
import com.studyagent.client.core.anki.edit.DefaultNoteMutationCoordinator
import com.studyagent.client.core.anki.edit.DefaultNoteMutationLedger
import com.studyagent.client.core.anki.edit.NoteEditDraft
import com.studyagent.client.core.anki.edit.NoteEditSafetyPolicy
import com.studyagent.client.core.anki.edit.NoteEditValidationError
import com.studyagent.client.core.anki.edit.NoteMutationBackendResult
import com.studyagent.client.core.anki.edit.NoteMutationCodec
import com.studyagent.client.core.anki.edit.NoteMutationId
import com.studyagent.client.core.anki.edit.NoteMutationIdSource
import com.studyagent.client.core.anki.edit.NoteMutationOutcome
import com.studyagent.client.core.anki.edit.NoteMutationReconciliationResult
import com.studyagent.client.core.anki.edit.NoteMutationRecord
import com.studyagent.client.core.anki.edit.NoteMutationSemantics
import com.studyagent.client.core.anki.edit.NoteMutationRecoveryOutcome
import com.studyagent.client.core.anki.edit.NoteMutationRefusal
import com.studyagent.client.core.anki.edit.NoteMutationStatus
import com.studyagent.client.core.anki.edit.NoteMutationVerification
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21J — a note edit is owned by the backend that produced its base, and every touch of that
 * mutation (save, retry, recovery, reconciliation) goes to that owner and no other. Two Fake
 * backends with colliding note, card and deck ids; no real collection; no preference.
 */
class Gate21jNoteEditOwnershipTest {

    private val ankiDroid = AnkiBackendId.AnkiDroidLocal
    private val pcB = AnkiBackendId.PcAgent("profile-b")

    /** Two owners whose ids collide on purpose: both have note-1, card-1 and deck-a. */
    private inner class Owners(
        /**
         * Models a PC owner that reports a collection key AND exposes an authoritative reconciliation
         * read — the only combination under which locked §3.3 permits `OP-M3`. Off by default, so the
         * default fixture is the honest AnkiDroid-shaped one: no key, no authoritative read.
         */
        bReconcilesAuthoritatively: Boolean = false
    ) {
        val events = mutableListOf<String>()
        val a = FakeNoteMutationBackend(backendId = ankiDroid, events = events)
        val b = FakeNoteMutationBackend(
            backendId = pcB,
            noteMutationSemantics = NoteMutationSemantics.ANKIDROID_V2_24_1.copy(
                authoritativeReconciliation = bReconcilesAuthoritatively
            ),
            events = events
        ).apply {
            fields[0] = AnkiNoteField(name = "Front", value = "B front", ordinal = 0)
            fields[1] = AnkiNoteField(name = "Back", value = "B back", ordinal = 1)
            tags = listOf("bravo")
            if (bReconcilesAuthoritatively) collectionIdentity = AnkiCollectionIdentity(pcB, "coll-b")
        }
        val store = InMemoryNoteMutationStore(events)
        private var counter = 0

        fun coordinator(
            vararg backends: AnkiBackend,
            durable: InMemoryNoteMutationStore = store
        ): DefaultNoteMutationCoordinator = DefaultNoteMutationCoordinator(
            AnkiBackendRegistry(backends.toList()),
            DefaultNoteMutationLedger(durable, nowEpochMs = { 1_000L }),
            NoteEditSafetyPolicy { _ -> null },
            NoteMutationIdSource { NoteMutationId("m-${++counter}") },
            nowEpochMs = { 1_000L }
        )

        fun persistedStatus(id: NoteMutationId): NoteMutationStatus? {
            val text = store.encoded ?: return null
            return NoteMutationCodec.decode(text)?.firstOrNull { it.mutationId == id }?.status
        }
    }

    /** Stable token for the locked `OP-M3` block under §3.3. */
    private val RECONCILIATION_BLOCKED = "reconciliation_requires_authoritative_match"

    private fun frontEdit(value: String = "edited") = NoteEditDraft(fieldValues = mapOf(0 to value))

    private fun NoteMutationOutcome.recordOrNull(): NoteMutationRecord? = when (this) {
        is NoteMutationOutcome.Applied -> record
        is NoteMutationOutcome.Conflict -> record
        is NoteMutationOutcome.RetryAvailable -> record
        is NoteMutationOutcome.VerificationRequired -> record
        is NoteMutationOutcome.ReadFailed -> record
        is NoteMutationOutcome.ActiveMutationExists -> record
        else -> null
    }

    /** The first apply is refused as not-applied (retryable); later applies go through faithfully. */
    private fun FakeNoteMutationBackend.refuseFirstThenApply() {
        var calls = 0
        applyHook = { request, fake ->
            if (calls++ == 0) {
                NoteMutationBackendResult.ConfirmedNotApplied(AnkiError.PermissionRequired())
            } else {
                fake.applyFaithfully(request.step)
                NoteMutationBackendResult.ConfirmedApplied
            }
        }
    }

    /** The first apply's outcome is lost on the wire: the record ends AMBIGUOUS. */
    private fun FakeNoteMutationBackend.loseFirstResponse() {
        applyHook = { _, _ -> NoteMutationBackendResult.OutcomeUnknown(AnkiError.Unknown("timeout")) }
    }

    // ---------------------------------------------------------------- owner isolation

    @Test
    fun editOpenedOnBIsWrittenOnlyToB() {
        runBlocking {
            val o = Owners()
            val outcome = o.coordinator(o.a, o.b).save(o.b.editableBase(), frontEdit("edited on B"))

            assertTrue("got $outcome", outcome is NoteMutationOutcome.Applied)
            assertEquals(pcB, outcome.recordOrNull()!!.backendId)
            assertEquals(1, o.b.applyCalls.size)
            assertTrue(o.a.applyCalls.isEmpty())
            assertEquals(0, o.a.readCount)
            assertEquals("edited on B", o.b.fields[0].value)
            assertEquals("front text", o.a.fields[0].value)
        }
    }

    @Test
    fun registryOrderNeverChangesTheOwner() {
        runBlocking {
            val o = Owners()
            val outcome = o.coordinator(o.b, o.a).save(o.b.editableBase(), frontEdit("edited on B"))

            assertTrue("got $outcome", outcome is NoteMutationOutcome.Applied)
            assertEquals(pcB, outcome.recordOrNull()!!.backendId)
            assertEquals(1, o.b.applyCalls.size)
            assertTrue(o.a.applyCalls.isEmpty())
        }
    }

    @Test
    fun collidingIdsOnAnotherBackendAreNeverTheSameNote() {
        runBlocking {
            val o = Owners()
            val coordinator = o.coordinator(o.a, o.b)
            o.a.loseFirstResponse()
            val ambiguous = coordinator.save(o.a.editableBase(), frontEdit("on A")).recordOrNull()!!
            assertEquals(NoteMutationStatus.AMBIGUOUS, ambiguous.status)

            // Same note-1 string on the other owner: not the active mutation of that note.
            assertNull(coordinator.activeMutationFor(pcB, "note-1"))
            assertEquals(ambiguous.mutationId, coordinator.activeMutationFor(ankiDroid, "note-1")?.mutationId)

            // A save on B for the same note id is not refused as an active duplicate.
            val onB = coordinator.save(o.b.editableBase(), frontEdit("on B"))
            assertTrue("got $onB", onB is NoteMutationOutcome.Applied)
            assertEquals("on B", o.b.fields[0].value)
        }
    }

    @Test
    fun recoveryOfAnAmbiguousANeverTouchesBAndStaysAmbiguousWithoutAnAuthoritativeRead() {
        runBlocking {
            val o = Owners()
            val coordinator = o.coordinator(o.a, o.b)
            o.a.loseFirstResponse()
            val ambiguous = coordinator.save(o.a.editableBase(), frontEdit("on A")).recordOrNull()!!

            val recovered = coordinator.recover(ambiguous.mutationId)

            // §3.3 blocks `OP-M3` without an authoritative match AND an authoritative read, so the
            // record stays AMBIGUOUS and NO backend is read — including its own owner. B is never
            // touched either, which is the owner-isolation property this test exists for.
            assertTrue("got $recovered", recovered is NoteMutationRecoveryOutcome.StillAmbiguous)
            // The record is still in this process, so the assurance is SESSION_CONTINUITY_VERIFIED
            // and §3.3 blocks `OP-M3` there: continuity cannot establish whether the write applied.
            recovered as NoteMutationRecoveryOutcome.StillAmbiguous
            assertEquals(
                "continuity_is_not_collection_evidence",
                (recovered.error as AnkiError.BackendUnavailable).message
            )
            assertEquals(NoteMutationStatus.AMBIGUOUS, recovered.record.status)
            assertTrue(o.a.reconcileCalls.isEmpty())
            assertTrue(o.b.reconcileCalls.isEmpty())
            assertTrue(o.b.applyCalls.isEmpty())
        }
    }

    @Test
    fun ambiguousRecordOfBIsReconciledByBAndNeverByAWhenTheOwnerExposesAnAuthoritativeRead() {
        runBlocking {
            val o = Owners(bReconcilesAuthoritatively = true)
            val coordinator = o.coordinator(o.a, o.b)
            o.b.loseFirstResponse()
            val ambiguous = coordinator.save(o.b.editableBase(), frontEdit("on B")).recordOrNull()!!
            assertEquals(NoteMutationStatus.AMBIGUOUS, ambiguous.status)
            o.b.applyHook = null
            o.b.reconcileResult = NoteMutationReconciliationResult.ConfirmedApplied

            val recovered = coordinator.recover(ambiguous.mutationId)

            // Owner isolation under the one cell §3.3 permits: B resolves its own ambiguous record,
            // and A — which holds a colliding note-1/card-1 — is never read or written.
            assertTrue("got $recovered", recovered is NoteMutationRecoveryOutcome.Resolved)
            assertEquals(NoteMutationStatus.APPLIED, (recovered as NoteMutationRecoveryOutcome.Resolved).record.status)
            assertEquals(1, o.b.reconcileCalls.size)
            assertEquals(pcB, o.b.reconcileCalls.single().noteRef.backendId)
            assertTrue(o.a.reconcileCalls.isEmpty())
            assertTrue(o.a.applyCalls.isEmpty())
            assertEquals(0, o.a.readCount)
        }
    }

    // ---------------------------------------------------------------- retry and recovery go to the owner

    @Test
    fun ambiguousRecordOfBIsNeverReconciledByA() {
        runBlocking {
            val o = Owners()
            val coordinator = o.coordinator(o.a, o.b)
            o.b.loseFirstResponse()
            val ambiguous = coordinator.save(o.b.editableBase(), frontEdit("on B")).recordOrNull()!!
            assertEquals(NoteMutationStatus.AMBIGUOUS, ambiguous.status)
            o.b.applyHook = null

            val recovered = coordinator.recover(ambiguous.mutationId)

            assertTrue("got $recovered", recovered is NoteMutationRecoveryOutcome.StillAmbiguous)
            assertEquals(NoteMutationStatus.AMBIGUOUS, (recovered as NoteMutationRecoveryOutcome.StillAmbiguous).record.status)
            assertTrue(o.b.reconcileCalls.isEmpty())
            assertTrue(o.a.reconcileCalls.isEmpty())
            assertEquals(0, o.a.readCount)
        }
    }

    @Test
    fun afterRestartAnAmbiguousRecordIsBlockedForWantOfAnAuthoritativeReconciliationRead() {
        runBlocking {
            val o = Owners()
            val first = o.coordinator(o.a, o.b)
            o.b.loseFirstResponse()
            val ambiguous = first.save(o.b.editableBase(), frontEdit("on B")).recordOrNull()!!
            assertEquals(NoteMutationStatus.AMBIGUOUS, ambiguous.status)
            assertEquals(NoteMutationStatus.AMBIGUOUS, o.persistedStatus(ambiguous.mutationId))

            // Process death: a new coordinator over the same durable ledger, so nothing is in-process.
            val restarted = o.coordinator(o.a, o.b)
            val recovered = restarted.recover(ambiguous.mutationId)

            // No collection key on either side means §3.3 NOMINAL, and `OP-M3` is Blocked there. The
            // record keeps its owner and its ambiguity; it is not silently resolved or reassigned.
            assertTrue("got $recovered", recovered is NoteMutationRecoveryOutcome.StillAmbiguous)
            recovered as NoteMutationRecoveryOutcome.StillAmbiguous
            assertEquals(
                RECONCILIATION_BLOCKED,
                (recovered.error as AnkiError.BackendUnavailable).message
            )
            // No in-process payload survives the restart, so there is nothing to compare; the record
            // names the block instead of inventing a comparison.
            assertEquals(
                RECONCILIATION_BLOCKED,
                (recovered.evidence as NoteMutationVerification.Unverifiable).detail
            )
            assertEquals(pcB, recovered.record.backendId)
            assertEquals(NoteMutationStatus.AMBIGUOUS, o.persistedStatus(ambiguous.mutationId))
            assertTrue(o.b.reconcileCalls.isEmpty())
            assertTrue(o.a.reconcileCalls.isEmpty())
        }
    }

    @Test
    fun retryOfARecordOwnedByBGoesToBAndNeverToA() {
        runBlocking {
            val o = Owners()
            val coordinator = o.coordinator(o.a, o.b)
            o.b.refuseFirstThenApply()
            val retryable = coordinator.save(o.b.editableBase(), frontEdit("on B")).recordOrNull()!!
            assertEquals(NoteMutationStatus.RETRY_ALLOWED, retryable.status)

            val retried = coordinator.retry(retryable.mutationId)

            assertTrue("got $retried", retried is NoteMutationOutcome.Applied)
            assertEquals(2, o.b.applyCalls.size)
            assertTrue(o.a.applyCalls.isEmpty())
            assertEquals("on B", o.b.fields[0].value)
            assertEquals("front text", o.a.fields[0].value)
        }
    }

    @Test
    fun unregisteredOwnerRefusesBeforeAnyRecordOrCall() {
        runBlocking {
            val o = Owners()
            val outcome = o.coordinator(o.a).save(o.b.editableBase(), frontEdit("on B"))

            assertEquals(
                NoteMutationOutcome.Refused(NoteMutationRefusal.BackendRouteBlocked("backend_not_registered")),
                outcome
            )
            assertTrue(o.a.applyCalls.isEmpty())
            assertTrue(o.b.applyCalls.isEmpty())
            assertEquals(0, o.store.writes)
            assertNull(o.store.encoded)
        }
    }

    @Test
    fun ambiguousRecordOfBIsUnresolvedAfterRestartWithoutB() {
        runBlocking {
            val o = Owners()
            o.b.loseFirstResponse()
            val ambiguous = o.coordinator(o.a, o.b).save(o.b.editableBase(), frontEdit("on B")).recordOrNull()!!
            o.b.applyHook = null
            val readsBefore = o.a.readCount

            // Restarted with only A registered: B's record must not be reconciled by A or silently dropped.
            val restarted = o.coordinator(o.a)
            val outcome = restarted.recover(ambiguous.mutationId)

            assertTrue("got $outcome", outcome is NoteMutationRecoveryOutcome.StillAmbiguous)
            val evidence = (outcome as NoteMutationRecoveryOutcome.StillAmbiguous).evidence
            assertEquals(NoteMutationVerification.Unverifiable("backend_not_registered"), evidence)
            assertTrue(o.a.reconcileCalls.isEmpty())
            assertTrue(o.b.reconcileCalls.isEmpty())
            assertEquals(readsBefore, o.a.readCount)
            assertEquals(NoteMutationStatus.AMBIGUOUS, o.persistedStatus(ambiguous.mutationId))
        }
    }

    // ---------------------------------------------------------------- collection context

    @Test
    fun collectionMismatchBlocksRetryBeforeAnyWrite() {
        runBlocking {
            val o = Owners()
            val coordinator = o.coordinator(o.a, o.b)
            o.b.collectionIdentity = AnkiCollectionIdentity(pcB, "coll-1")
            o.b.refuseFirstThenApply()
            val retryable = coordinator.save(o.b.editableBase(), frontEdit("on B")).recordOrNull()!!
            assertEquals(AnkiCollectionIdentity(pcB, "coll-1"), retryable.collectionRef)

            // The same owner now reports another collection.
            o.b.collectionIdentity = AnkiCollectionIdentity(pcB, "coll-2")
            val writesBefore = o.store.writes
            val retried = coordinator.retry(retryable.mutationId)

            assertEquals(
                NoteMutationOutcome.Refused(NoteMutationRefusal.BackendRouteBlocked("collection_mismatch")),
                retried
            )
            assertEquals("no second write", 1, o.b.applyCalls.size)
            assertEquals("no ledger transition", writesBefore, o.store.writes)
            assertEquals(NoteMutationStatus.RETRY_ALLOWED, o.persistedStatus(retryable.mutationId))
        }
    }

    @Test
    fun keyThatAppearsAfterAnUnknownAdmissionIsDegradedAndBlocksRetry() {
        runBlocking {
            val o = Owners()
            val coordinator = o.coordinator(o.a, o.b)
            o.b.collectionIdentity = null
            o.b.refuseFirstThenApply()
            val retryable = coordinator.save(o.b.editableBase(), frontEdit("on B")).recordOrNull()!!
            assertNull(retryable.collectionRef)

            o.b.collectionIdentity = AnkiCollectionIdentity(pcB, "coll-2")
            val retried = coordinator.retry(retryable.mutationId)

            assertEquals(
                NoteMutationOutcome.Refused(NoteMutationRefusal.BackendRouteBlocked("identity_unverifiable_degraded")),
                retried
            )
            assertEquals(1, o.b.applyCalls.size)
        }
    }

    @Test
    fun keyThatDisappearsAfterAdmissionIsDegradedAndBlocksRetry() {
        runBlocking {
            val o = Owners()
            val coordinator = o.coordinator(o.a, o.b)
            o.b.collectionIdentity = AnkiCollectionIdentity(pcB, "coll-1")
            o.b.refuseFirstThenApply()
            val retryable = coordinator.save(o.b.editableBase(), frontEdit("on B")).recordOrNull()!!

            o.b.collectionIdentity = null
            val retried = coordinator.retry(retryable.mutationId)

            assertEquals(
                NoteMutationOutcome.Refused(NoteMutationRefusal.BackendRouteBlocked("identity_unverifiable_degraded")),
                retried
            )
            assertEquals(1, o.b.applyCalls.size)
        }
    }

    // ---------------------------------------------------------------- deck targets

    @Test
    fun aForeignFilteredDeckWithTheSameIdListedFirstDoesNotBlockAMoveWithinItsOwnBackend() {
        runBlocking {
            val o = Owners()
            // Regression: a deck of another owner with the same id, filtered, listed before the source.
            o.a.decks.add(
                0,
                AnkiDeck(ref = AnkiDeckRef(pcB, "deck-a"), name = "foreign", isFiltered = true)
            )
            val outcome = o.coordinator(o.a, o.b).save(
                o.a.editableBase(),
                NoteEditDraft(targetDeck = AnkiDeckRef(ankiDroid, "deck-b"))
            )

            assertTrue("got $outcome", outcome is NoteMutationOutcome.Applied)
            assertEquals("deck-b", o.a.deckId)
            assertEquals("deck-a", o.b.deckId)
        }
    }

    @Test
    fun aForeignDeckTargetIsRefusedNotSilentlyDropped() {
        runBlocking {
            val o = Owners()
            val outcome = o.coordinator(o.a, o.b).save(
                o.a.editableBase(),
                NoteEditDraft(targetDeck = AnkiDeckRef(pcB, "deck-b"))
            )

            assertEquals(
                NoteMutationOutcome.ValidationFailed(listOf(NoteEditValidationError.DeckTargetWrongBackend)),
                outcome
            )
            assertTrue(o.a.applyCalls.isEmpty())
            assertTrue(o.b.applyCalls.isEmpty())
            assertNull(o.store.encoded)
        }
    }

    // ---------------------------------------------------------------- conflict ownership

    @Test
    fun conflictOwnedByBCanOnlyBeContinuedOnB() {
        runBlocking {
            val o = Owners()
            val coordinator = o.coordinator(o.a, o.b)
            // Someone else changes B's note between the base and the pre-write read.
            var changed = false
            o.b.beforeRead = { fake ->
                if (!changed) {
                    changed = true
                    fake.fields[0] = fake.fields[0].copy(value = "changed elsewhere")
                }
            }
            val conflict = coordinator.save(o.b.editableBase(), frontEdit("mine")).recordOrNull()!!
            assertEquals(NoteMutationStatus.CONFLICT, conflict.status)
            assertTrue(o.b.applyCalls.isEmpty())

            val onA = coordinator.startAfterConflict(conflict.mutationId, o.a.editableBase(), frontEdit("mine"))
            assertEquals(NoteMutationOutcome.Refused(NoteMutationRefusal.BackendMismatch), onA)
            assertTrue(o.a.applyCalls.isEmpty())

            val onB = coordinator.startAfterConflict(conflict.mutationId, o.b.editableBase(), frontEdit("mine"))
            assertTrue("got $onB", onB is NoteMutationOutcome.Applied)
            assertEquals(pcB, onB.recordOrNull()!!.backendId)
            assertEquals("mine", o.b.fields[0].value)
            assertEquals("front text", o.a.fields[0].value)
        }
    }

    // ---------------------------------------------------------------- persisted shape

    @Test
    fun legacyRecordWithoutCollectionRefDecodesAndStaysWithItsOwner() {
        runBlocking {
            val o = Owners()
            o.b.loseFirstResponse()
            val ambiguous = o.coordinator(o.a, o.b).save(o.b.editableBase(), frontEdit("on B")).recordOrNull()!!
            o.b.applyHook = null
            val written = o.store.encoded!!
            assertTrue("codec writes the optional field", written.contains("\"collectionRef\":null"))

            // A snapshot written before GATE 21J has no such key at all.
            val legacy = written.replace(",\"collectionRef\":null", "")
            assertFalse(legacy.contains("collectionRef"))

            val decoded = NoteMutationCodec.decode(legacy)!!.first { it.mutationId == ambiguous.mutationId }
            assertNull(decoded.collectionRef)
            assertEquals(pcB, decoded.backendId)

            val reopened = InMemoryNoteMutationStore().apply { encoded = legacy }
            val outcome = o.coordinator(o.a, o.b, durable = reopened).recover(ambiguous.mutationId)

            assertTrue("got $outcome", outcome is NoteMutationRecoveryOutcome.StillAmbiguous)
            assertEquals(pcB, (outcome as NoteMutationRecoveryOutcome.StillAmbiguous).record.backendId)
            // A legacy record keeps its owner; it is not reassigned, and it is not reconciled by A.
            assertTrue(o.b.reconcileCalls.isEmpty())
            assertTrue(o.a.reconcileCalls.isEmpty())
        }
    }
}
