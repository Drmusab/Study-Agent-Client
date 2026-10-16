package com.studyagent.client.anki.edit

import androidx.lifecycle.ViewModelStore
import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendRegistration
import com.studyagent.client.core.anki.AnkiBackendRemoval
import com.studyagent.client.core.anki.AnkiBackendResolver2
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.anki.BackendResolutionReason
import com.studyagent.client.core.anki.BackendResolutionRequest2
import com.studyagent.client.core.anki.BackendResolutionResult2
import com.studyagent.client.core.anki.edit.DefaultNoteMutationCoordinator
import com.studyagent.client.core.anki.edit.DefaultNoteMutationLedger
import com.studyagent.client.core.anki.edit.NoteDeckChangeScope
import com.studyagent.client.core.anki.edit.NoteEditBase
import com.studyagent.client.core.anki.edit.NoteEditDraft
import com.studyagent.client.core.anki.edit.NoteEditSafetyPolicy
import com.studyagent.client.core.anki.edit.NoteEditValidationError
import com.studyagent.client.core.anki.edit.NoteMutationBackendResult
import com.studyagent.client.core.anki.edit.NoteMutationCodec
import com.studyagent.client.core.anki.edit.NoteMutationId
import com.studyagent.client.core.anki.edit.NoteMutationIdSource
import com.studyagent.client.core.anki.edit.NoteMutationOutcome
import com.studyagent.client.core.anki.edit.NoteMutationReason
import com.studyagent.client.core.anki.edit.NoteMutationReconciliationResult
import com.studyagent.client.core.anki.edit.NoteMutationRecoveryOutcome
import com.studyagent.client.core.anki.edit.NoteMutationRefusal
import com.studyagent.client.core.anki.edit.NoteMutationStatus
import com.studyagent.client.core.anki.edit.NoteMutationStep
import com.studyagent.client.core.anki.edit.NoteMutationVerification
import com.studyagent.client.ui.screens.editnote.EditNoteSaveState
import com.studyagent.client.ui.screens.editnote.EditNoteUiState
import com.studyagent.client.ui.screens.editnote.EditNoteViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21J — the authoritative verification plan (VER-21J-01 … VER-21J-40), executed against two
 * fake backends whose **note, card, deck and model ids all collide** ([Gate21jBackends],
 * CHECKPOINT 21J-30). No real collection is read or written anywhere in this file.
 *
 * VER-21J-38 (endurance) lives in `Gate21jNoteEditEnduranceTest`; VER-21J-39 (real backends) is
 * BLOCKED — no device, no AnkiDroid and no PC agent backend exist in this repository — and
 * VER-21J-40 is the regression run recorded in the gate report. Every other ID is below, one test
 * per ID, so the report's matrix maps to test names without a lookup table.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Gate21jCanonicalVerificationTest {

    private val viewModelStores = mutableListOf<ViewModelStore>()

    @After
    fun tearDown() {
        viewModelStores.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    private fun draftOf(vararg changes: Pair<Int, String>) = NoteEditDraft(fieldValues = changes.toMap())

    private fun world(bReconcilesAuthoritatively: Boolean = false): Gate21jWorld =
        Gate21jWorld(Gate21jBackends(bReconcilesAuthoritatively))

    private fun Gate21jWorld.baseOfA(): NoteEditBase = NoteEditBase.from(pair.a.details())!!

    private fun Gate21jWorld.baseOfB(): NoteEditBase = NoteEditBase.from(pair.b.details())!!

    /** The first apply is refused as proven-not-applied; later applies behave faithfully. */
    private fun FakeNoteMutationBackend.refuseFirstThenApply() {
        var calls = 0
        applyHook = { request, fake ->
            if (calls++ == 0) {
                NoteMutationBackendResult.ConfirmedNotApplied(AnkiError.PermissionRequired())
            } else {
                fake.applyFaithfully(request)
                NoteMutationBackendResult.ConfirmedApplied
            }
        }
    }

    /** The write happens; the answer never comes back. */
    private fun FakeNoteMutationBackend.loseResponse() {
        applyHook = { request, fake ->
            fake.applyFaithfully(request)
            NoteMutationBackendResult.OutcomeUnknown(AnkiError.Unknown("timeout"))
        }
    }

    // ============================================================== editor binding (VER-01 … VER-08)

    @Test
    fun `VER-21J-01 a note read from AnkiDroid opens an editor bound to AnkiDroid`() {
        val w = world()
        val ref = w.pair.a.cardRef()

        // The route carries a fully qualified reference, and the route's own resolution is the owner.
        assertTrue(w.registry.find(ref.backendId) === w.pair.a)
        val base = w.baseOfA()
        assertEquals(w.pair.aId, base.backendId)
        assertEquals(Gate21jIds.NOTE, base.noteRef.noteId)
        assertEquals(Gate21jIds.MODEL, base.noteTypeId)
        assertNull("AnkiDroid reports no collection key, and none is invented", base.noteRef.collectionKey)
        assertEquals(w.pair.aId, base.cardRef.backendId)
    }

    @Test
    fun `VER-21J-02 a note bound to the PC identity is never substituted by AnkiDroid`() = runBlocking {
        val w = world()
        // The PC identity at this pin advertises no note editing at all: no mutation gateway exists.
        w.pair.b.caps.value = AnkiCapabilities()

        assertTrue(w.registry.find(w.pair.b.cardRef().backendId) === w.pair.b)
        assertNotEquals(
            "identical numeric ids are still different logical cards",
            w.pair.a.cardRef().stableKey,
            w.pair.b.cardRef().stableKey
        )

        val outcome = w.coordinator.save(w.baseOfB(), draftOf(0 to "must not be written by A"))

        assertEquals(
            NoteMutationOutcome.Refused(NoteMutationRefusal.UnsupportedDimension("card_details")),
            outcome
        )
        assertTrue("nothing was written anywhere", w.pair.a.applyCalls.isEmpty())
        assertTrue(w.pair.b.applyCalls.isEmpty())
        assertEquals("A's own note is untouched", "A front", w.pair.a.fields[0].value)
        assertNull(w.store.encoded)
    }

    @Test
    fun `VER-21J-03 a preference change during the edit never redirects the save`() = runBlocking {
        val w = world()
        val base = w.baseOfA()
        assertEquals(
            BackendResolutionResult2.Resolved(w.pair.aId, BackendResolutionReason.EXPLICIT_PIN),
            w.resolved()
        )

        w.preference = BackendPreference.Explicit(w.pair.bId)
        w.switchReadContextTo(w.pair.bId, w.pair.b.currentCollectionIdentity)
        assertEquals(
            w.pair.bId,
            (w.resolved() as BackendResolutionResult2.Resolved).backendId
        )

        val outcome = w.coordinator.save(base, draftOf(0 to "edited after the switch"))

        assertTrue("got $outcome", outcome is NoteMutationOutcome.Applied)
        assertEquals(w.pair.aId, outcome.recordOrNull()!!.backendId)
        assertEquals("edited after the switch", w.pair.a.fields[0].value)
        assertTrue("the newly preferred backend received nothing", w.pair.b.applyCalls.isEmpty())
        assertEquals("B front", w.pair.b.fields[0].value)
    }

    @Test
    fun `VER-21J-04 an AUTO availability change cannot redirect a bound editor`() = runBlocking {
        val w = world()
        val base = w.baseOfA()
        w.preference = BackendPreference.Auto

        // A stops being eligible for resolution (permission), so AUTO now answers B.
        val descriptors = w.registry.descriptors().map { descriptor ->
            if (descriptor.id == w.pair.aId) {
                descriptor.copy(availability = AnkiAvailability.PermissionRequired())
            } else {
                descriptor
            }
        }
        val resolved = AnkiBackendResolver2.resolve(
            BackendResolutionRequest2(BackendPreference.Auto, emptySet(), purpose = "gate21j-test"),
            descriptors
        )
        assertEquals(
            w.pair.bId,
            (resolved as BackendResolutionResult2.Resolved).backendId
        )

        // The editor's own owner is unaffected: the write goes to A and nowhere else.
        val outcome = w.coordinator.save(base, draftOf(1 to "A back edited"))
        assertTrue("got $outcome", outcome is NoteMutationOutcome.Applied)
        assertEquals(w.pair.aId, outcome.recordOrNull()!!.backendId)
        assertTrue(w.pair.b.applyCalls.isEmpty())
    }

    @Test
    fun `VER-21J-05 a Library context switch does not move the edit context`() = runBlocking {
        val w = world()
        val base = w.baseOfA()
        val boundContext = w.readContext
        assertEquals(w.pair.aId, boundContext.backendId)

        w.switchReadContextTo(w.pair.bId, w.pair.b.currentCollectionIdentity)

        assertNotEquals("a new logical read context is a new generation", boundContext.generation, w.readContext.generation)
        assertEquals(w.pair.bId, w.readContext.backendId)

        val outcome = w.coordinator.save(base, draftOf(0 to "still A"))
        assertTrue("got $outcome", outcome is NoteMutationOutcome.Applied)
        assertEquals(w.pair.aId, outcome.recordOrNull()!!.backendId)
        assertTrue(w.pair.b.applyCalls.isEmpty())
    }

    @Test
    fun `VER-21J-06 identical note ids on two backends are two different notes`() = runBlocking {
        val w = world()
        val onA = w.coordinator.save(w.baseOfA(), draftOf(0 to "A only"))

        assertTrue("got $onA", onA is NoteMutationOutcome.Applied)
        assertEquals("A only", w.pair.a.fields[0].value)
        assertEquals("B front", w.pair.b.fields[0].value)
        assertEquals(w.pair.aId, onA.recordOrNull()!!.noteRef.backendId)
        assertEquals(w.pair.aId, onA.recordOrNull()!!.cardRef.backendId)
        assertTrue(w.pair.b.applyCalls.isEmpty())

        val onB = w.coordinator.save(w.baseOfB(), draftOf(2 to "B extra edited"))
        assertTrue("got $onB", onB is NoteMutationOutcome.Applied)
        assertEquals("B extra edited", w.pair.b.fields[2].value)
        assertEquals("A only", w.pair.a.fields[0].value)
        assertEquals(1, w.pair.a.applyCalls.size)
        assertEquals(1, w.pair.b.applyCalls.size)
    }

    @Test
    fun `VER-21J-07 colliding card and deck ids cannot be used across backends`() = runBlocking {
        val w = world()

        // The same numeric deck id, named on the other backend, is a foreign deck.
        val foreignDeck = w.coordinator.save(
            w.baseOfA(),
            NoteEditDraft(targetDeck = AnkiDeckRef(w.pair.bId, Gate21jIds.OTHER_DECK))
        )
        assertEquals(
            NoteMutationOutcome.ValidationFailed(listOf(NoteEditValidationError.DeckTargetWrongBackend)),
            foreignDeck
        )
        assertTrue(w.pair.a.applyCalls.isEmpty())

        // On the note's own backend the same id is the legitimate target.
        val ownDeck = w.coordinator.save(
            w.baseOfA(),
            NoteEditDraft(targetDeck = AnkiDeckRef(w.pair.aId, Gate21jIds.OTHER_DECK))
        )
        assertTrue("got $ownDeck", ownDeck is NoteMutationOutcome.Applied)
        assertEquals(Gate21jIds.OTHER_DECK, w.pair.a.deckId)
        assertEquals(Gate21jIds.DECK, w.pair.b.deckId)
        assertEquals(w.pair.aId, (w.pair.a.applyCalls.single().step as NoteMutationStep.ChangeDeck).toDeck.backendId)
        assertTrue(w.pair.b.applyCalls.isEmpty())
    }

    @Test
    fun `VER-21J-08 an identical numeric model id never borrows the other backend's schema`() = runBlocking {
        val w = world()
        assertEquals(Gate21jIds.MODEL, w.pair.a.noteTypeId)
        assertEquals(Gate21jIds.MODEL, w.pair.b.noteTypeId)
        assertEquals(2, w.pair.a.fields.size)
        assertEquals(3, w.pair.b.fields.size)

        // Ordinal 2 exists only in B's schema: A's editor must refuse it before any mutation.
        val rejected = w.coordinator.save(w.baseOfA(), draftOf(2 to "does not exist on A"))
        assertEquals(
            NoteMutationOutcome.ValidationFailed(listOf(NoteEditValidationError.UnknownField(2))),
            rejected
        )
        assertTrue(w.pair.a.applyCalls.isEmpty())
        assertNull("nothing is recorded for a schema-invalid edit", w.store.encoded)

        // B's own schema does offer it, and that write still belongs to B.
        val onB = w.coordinator.save(w.baseOfB(), draftOf(2 to "B extra edited"))
        assertTrue("got $onB", onB is NoteMutationOutcome.Applied)
        assertEquals("B extra edited", w.pair.b.fields[2].value)
        assertTrue(w.pair.a.applyCalls.isEmpty())
    }

    // ====================================================== operation semantics (VER-09 … VER-14)

    @Test
    fun `VER-21J-09 a valid field edit executes through the original backend only`() = runBlocking {
        val w = world()
        val outcome = w.coordinator.save(w.baseOfA(), draftOf(0 to "new front", 1 to "new back"))

        assertTrue("got $outcome", outcome is NoteMutationOutcome.Applied)
        assertEquals(listOf("new front", "new back"), w.pair.a.fields.map { it.value })
        assertEquals(listOf("B front", "B back", "B extra"), w.pair.b.fields.map { it.value })
        assertEquals(1, w.pair.a.applyCalls.size)
        assertEquals(0, w.pair.b.applyCalls.size)
        assertEquals(0, w.pair.b.reconcileCalls.size)
    }

    @Test
    fun `VER-21J-10 a tag edit keeps replace-only semantics and the original backend`() = runBlocking {
        val w = world()
        val outcome = w.coordinator.save(w.baseOfA(), NoteEditDraft(tags = listOf("alpha", "charlie")))

        assertTrue("got $outcome", outcome is NoteMutationOutcome.Applied)
        assertEquals(listOf("alpha", "charlie"), w.pair.a.tags)
        assertEquals(listOf("bravo"), w.pair.b.tags)
        val step = w.pair.a.applyCalls.single().step as NoteMutationStep.UpdateNoteContent
        assertEquals("one whole-set replace write, never an add/remove delta", listOf("alpha", "charlie"), step.tags)
        assertNull("tags ride the content write; no field value is sent", step.fieldValues)
        assertEquals(0, w.pair.b.applyCalls.size)
    }

    @Test
    fun `VER-21J-11 a valid deck reassignment uses the source card and the owner's deck`() = runBlocking {
        val w = world()
        val outcome = w.coordinator.save(
            w.baseOfA(),
            NoteEditDraft(targetDeck = AnkiDeckRef(w.pair.aId, Gate21jIds.OTHER_DECK))
        )

        assertTrue("got $outcome", outcome is NoteMutationOutcome.Applied)
        val request = w.pair.a.applyCalls.single()
        assertEquals(Gate21jIds.CARD, request.cardRef.cardId)
        assertEquals(w.pair.aId, request.cardRef.backendId)
        val step = request.step as NoteMutationStep.ChangeDeck
        assertEquals(Gate21jIds.OTHER_DECK, step.toDeck.deckId)
        assertEquals(w.pair.aId, step.toDeck.backendId)
        assertEquals(Gate21jIds.DECK, step.fromDeck?.deckId)
        assertEquals(0, w.pair.b.applyCalls.size)
    }

    @Test
    fun `VER-21J-12 an unsupported operation is refused, never silently substituted`() = runBlocking {
        val w = world()
        w.pair.a.caps.value = FakeNoteMutationBackend.defaultCapabilities().copy(editNoteTags = false)

        val outcome = w.coordinator.save(
            w.baseOfA(),
            NoteEditDraft(fieldValues = mapOf(0 to "new"), tags = listOf("alpha", "charlie"))
        )

        assertEquals(NoteMutationOutcome.Refused(NoteMutationRefusal.UnsupportedDimension("tags")), outcome)
        assertTrue("no partial write of the supported half", w.pair.a.applyCalls.isEmpty())
        assertEquals(listOf("alpha"), w.pair.a.tags)
        assertEquals("A front", w.pair.a.fields[0].value)
        assertNull(w.store.encoded)
        assertTrue(w.pair.b.applyCalls.isEmpty())
    }

    @Test
    fun `VER-21J-13 a single-card move never moves the note's other cards`() = runBlocking {
        val w = world()
        assertEquals(Gate21jIds.DECK, w.pair.a.deckOfCard(Gate21jIds.SIBLING_CARD))
        assertEquals(Gate21jIds.DECK, w.pair.a.deckOfCard(Gate21jIds.THIRD_CARD))

        val outcome = w.coordinator.save(
            w.baseOfA(),
            NoteEditDraft(targetDeck = AnkiDeckRef(w.pair.aId, Gate21jIds.OTHER_DECK))
        )

        assertTrue("got $outcome", outcome is NoteMutationOutcome.Applied)
        assertEquals(Gate21jIds.OTHER_DECK, w.pair.a.deckOfCard(Gate21jIds.CARD))
        assertEquals("sibling 1 stayed", Gate21jIds.DECK, w.pair.a.deckOfCard(Gate21jIds.SIBLING_CARD))
        assertEquals("sibling 2 stayed", Gate21jIds.DECK, w.pair.a.deckOfCard(Gate21jIds.THIRD_CARD))
        // The plan is card-scoped, and the backend claims exactly that scope and no more.
        assertEquals(NoteDeckChangeScope.CARD_ONLY, w.pair.a.noteMutationSemantics.deckChangeScope)
        assertEquals(1, w.pair.a.applyCalls.size)
    }

    @Test
    fun `VER-21J-14 invalid field input is rejected before any mutation`() = runBlocking {
        val w = world()
        val readsBefore = w.pair.a.readCount

        assertEquals(
            NoteMutationOutcome.ValidationFailed(listOf(NoteEditValidationError.FieldContainsSeparator(0))),
            w.coordinator.save(w.baseOfA(), draftOf(0 to "a\u001Fb"))
        )
        assertEquals(
            NoteMutationOutcome.ValidationFailed(listOf(NoteEditValidationError.UnknownField(7))),
            w.coordinator.save(w.baseOfA(), draftOf(7 to "x"))
        )
        assertEquals("nothing was even re-read for a schema refusal", readsBefore, w.pair.a.readCount)
        assertTrue(w.pair.a.applyCalls.isEmpty())
        assertNull(w.store.encoded)
    }

    // ================================================ identity and conflict (VER-15 … VER-18)

    @Test
    fun `VER-21J-15 a verified collection mismatch blocks the write`() = runBlocking {
        val w = world()
        w.pair.b.collectionIdentity = AnkiCollectionIdentity(w.pair.bId, "coll-1")
        w.pair.b.refuseFirstThenApply()
        val retryable = w.coordinator.save(w.baseOfB(), draftOf(0 to "on B")).recordOrNull()!!
        assertEquals(NoteMutationStatus.RETRY_ALLOWED, retryable.status)
        assertEquals(AnkiCollectionIdentity(w.pair.bId, "coll-1"), retryable.collectionRef)

        // The same owner now reports another collection: the record is no longer provably admissible.
        w.pair.b.collectionIdentity = AnkiCollectionIdentity(w.pair.bId, "coll-2")
        val writesBefore = w.store.writes
        val retried = w.coordinator.retry(retryable.mutationId)

        assertEquals(
            NoteMutationOutcome.Refused(NoteMutationRefusal.BackendRouteBlocked("collection_mismatch")),
            retried
        )
        assertEquals("one write, no second", 1, w.pair.b.applyCalls.size)
        assertEquals("no ledger transition on a blocked route", writesBefore, w.store.writes)
        assertEquals(NoteMutationStatus.RETRY_ALLOWED, w.persistedStatus(retryable.mutationId))
    }

    @Test
    fun `VER-21J-16 unknown collection assurance is never upgraded to a match`() = runBlocking {
        val w = world()
        assertNull("AnkiDroid binds no key", w.baseOfA().noteRef.collectionKey)
        w.pair.a.loseResponse()
        val ambiguous = w.coordinator.save(w.baseOfA(), draftOf(0 to "unknown outcome")).recordOrNull()!!
        assertNull("the record says unknown, not a fabricated key", ambiguous.collectionRef)
        assertEquals(NoteMutationStatus.AMBIGUOUS, ambiguous.status)

        // In process: session continuity is not collection evidence, so reconciliation stays blocked.
        val inProcess = w.coordinator.recover(ambiguous.mutationId) as NoteMutationRecoveryOutcome.StillAmbiguous
        assertEquals(
            "continuity_is_not_collection_evidence",
            (inProcess.error as AnkiError.BackendUnavailable).message
        )
        assertEquals(NoteMutationStatus.AMBIGUOUS, inProcess.record.status)
        assertTrue("OP-M3 was never sent", w.pair.a.reconcileCalls.isEmpty())

        // After a restart the payload is gone: unknown on both sides is still not a match.
        val restarted = w.restart()
        val afterRestart = restarted.recover(ambiguous.mutationId) as NoteMutationRecoveryOutcome.StillAmbiguous
        assertEquals(
            NoteMutationVerification.Unverifiable("reconciliation_requires_authoritative_match"),
            afterRestart.evidence
        )
        assertEquals(NoteMutationStatus.AMBIGUOUS, w.persistedStatus(ambiguous.mutationId))
        assertTrue(w.pair.a.reconcileCalls.isEmpty())
        assertEquals("nothing was replayed", 1, w.pair.a.applyCalls.size)
    }

    @Test
    fun `VER-21J-17 an external edit before the write is a conflict with nothing written`() = runBlocking {
        val w = world()
        var changed = false
        w.pair.a.beforeRead = { fake ->
            if (!changed) {
                changed = true
                fake.fields[0] = fake.fields[0].copy(value = "changed by another client")
            }
        }

        val outcome = w.coordinator.save(w.baseOfA(), draftOf(0 to "mine"))

        assertTrue("got $outcome", outcome is NoteMutationOutcome.Conflict)
        val conflict = outcome as NoteMutationOutcome.Conflict
        assertEquals(NoteMutationReason.CONFLICT_BEFORE_WRITE, conflict.record.reason)
        assertEquals("changed by another client", w.pair.a.fields[0].value)
        assertTrue("the edit was never sent", w.pair.a.applyCalls.isEmpty())
        assertEquals(NoteMutationStatus.CONFLICT, w.persistedStatus(conflict.record.mutationId))
        assertTrue(w.pair.b.applyCalls.isEmpty())
    }

    @Test
    fun `VER-21J-18 a verified conflict and an uncertain write are distinct durable outcomes`() = runBlocking {
        val conflictWorld = world()
        conflictWorld.pair.a.beforeRead = { fake -> fake.fields[1] = fake.fields[1].copy(value = "theirs") }
        val conflict = conflictWorld.coordinator.save(conflictWorld.baseOfA(), draftOf(1 to "mine"))
        assertTrue(conflict is NoteMutationOutcome.Conflict)
        val conflictId = (conflict as NoteMutationOutcome.Conflict).record.mutationId
        assertEquals(NoteMutationStatus.CONFLICT, conflictWorld.persistedStatus(conflictId))
        // A conflict is closed: recovery does not turn it into an ambiguous replay.
        assertTrue(
            conflictWorld.coordinator.recover(conflictId) is NoteMutationRecoveryOutcome.Unchanged
        )
        assertTrue(conflictWorld.pair.a.applyCalls.isEmpty())

        val ambiguousWorld = world()
        ambiguousWorld.pair.a.loseResponse()
        val ambiguous = ambiguousWorld.coordinator.save(ambiguousWorld.baseOfA(), draftOf(1 to "mine"))
        assertTrue(ambiguous is NoteMutationOutcome.VerificationRequired)
        val ambiguousId = (ambiguous as NoteMutationOutcome.VerificationRequired).record.mutationId

        assertEquals(NoteMutationStatus.AMBIGUOUS, ambiguousWorld.persistedStatus(ambiguousId))
        assertNotEquals(
            conflictWorld.persistedStatus(conflictId),
            ambiguousWorld.persistedStatus(ambiguousId)
        )
        assertNotEquals(
            NoteMutationReason.CONFLICT_BEFORE_WRITE,
            ambiguousWorld.persistedRecord(ambiguousId)!!.reason
        )
    }

    // ============================================== durability barriers (VER-19 … VER-24)

    @Test
    fun `VER-21J-19 a PREPARED persistence failure causes zero backend calls`() = runBlocking {
        val w = world()
        w.store.writeFailure = { it.contains("\"status\":\"PREPARED\"") }
        val readsBefore = w.pair.a.readCount

        val outcome = w.coordinator.save(w.baseOfA(), draftOf(0 to "never sent"))

        assertTrue("got $outcome", outcome is NoteMutationOutcome.LedgerUnavailable)
        assertTrue(w.pair.a.applyCalls.isEmpty())
        assertEquals("not even the pre-write re-read happened", readsBefore, w.pair.a.readCount)
        assertNull("nothing durable was written", w.store.encoded)
        assertTrue(w.pair.b.applyCalls.isEmpty())
    }

    @Test
    fun `VER-21J-20 no irreversible mutation before the durable submission intent`() = runBlocking {
        val w = world()
        w.store.writeFailure = { it.contains("\"status\":\"SUBMITTING\"") }

        val outcome = w.coordinator.save(w.baseOfA(), draftOf(0 to "never sent"))

        assertEquals(NoteMutationOutcome.LedgerUnavailable("write_not_committed"), outcome)
        assertTrue("the boundary was never crossed", w.pair.a.applyCalls.isEmpty())
        assertEquals(
            "the durable record is still PREPARED",
            NoteMutationStatus.PREPARED,
            w.persistedStatus(NoteMutationId("m-1"))
        )
        assertTrue(w.pair.b.applyCalls.isEmpty())
    }

    @Test
    fun `VER-21J-21 a lost save response is ambiguous and is never replayed`() = runBlocking {
        val w = world()
        w.pair.a.loseResponse()

        val outcome = w.coordinator.save(w.baseOfA(), draftOf(0 to "applied but unacknowledged"))

        assertTrue("got $outcome", outcome is NoteMutationOutcome.VerificationRequired)
        val record = (outcome as NoteMutationOutcome.VerificationRequired).record
        assertEquals(NoteMutationStatus.AMBIGUOUS, record.status)
        assertEquals(NoteMutationReason.OUTCOME_UNKNOWN, w.persistedRecord(record.mutationId)!!.reason)
        assertEquals("exactly one write", 1, w.pair.a.applyCalls.size)

        // Retry is refused, recovery does not write, and nothing is replayed on any backend.
        assertEquals(
            NoteMutationOutcome.Refused(NoteMutationRefusal.RetryNotAllowed(NoteMutationStatus.AMBIGUOUS)),
            w.coordinator.retry(record.mutationId)
        )
        w.coordinator.recover(record.mutationId)
        assertEquals(1, w.pair.a.applyCalls.size)
        assertTrue(w.pair.b.applyCalls.isEmpty())
        assertTrue(w.pair.b.reconcileCalls.isEmpty())
    }

    @Test
    fun `VER-21J-22 a partial composite save is never fully applied and is not replayed`() = runBlocking {
        val w = world()
        var index = 0
        w.pair.a.applyHook = { request, fake ->
            when (index++) {
                // Fields and tags land…
                0 -> {
                    fake.applyFaithfully(request)
                    NoteMutationBackendResult.ConfirmedApplied
                }
                // …and the deck move's outcome is unknown.
                else -> NoteMutationBackendResult.OutcomeUnknown(AnkiError.Unknown("deck_write_timeout"))
            }
        }

        val outcome = w.coordinator.save(
            w.baseOfA(),
            NoteEditDraft(
                fieldValues = mapOf(0 to "new front"),
                tags = listOf("alpha", "charlie"),
                targetDeck = AnkiDeckRef(w.pair.aId, Gate21jIds.OTHER_DECK)
            )
        )

        assertTrue("got $outcome", outcome is NoteMutationOutcome.VerificationRequired)
        val record = (outcome as NoteMutationOutcome.VerificationRequired).record
        assertEquals(NoteMutationStatus.AMBIGUOUS, record.status)
        assertEquals(
            "the first operation applied, so the reason says partial",
            NoteMutationReason.PARTIAL_OPERATION_UNKNOWN,
            w.persistedRecord(record.mutationId)!!.reason
        )
        assertNotEquals(NoteMutationStatus.APPLIED, record.status)
        assertEquals("both plan steps were attempted exactly once", 2, w.pair.a.applyCalls.size)
        assertEquals("new front", w.pair.a.fields[0].value)
        assertEquals("the deck write did not land", Gate21jIds.DECK, w.pair.a.deckId)

        // Restart: the composite is still ambiguous, and neither half is re-sent.
        w.pair.a.applyHook = null
        val restarted = w.restart()
        val recovered = restarted.recover(record.mutationId) as NoteMutationRecoveryOutcome.StillAmbiguous
        assertEquals(NoteMutationStatus.AMBIGUOUS, recovered.record.status)
        assertEquals(2, w.pair.a.applyCalls.size)
        assertEquals("the applied half was not rolled back by an invented compensation", "new front", w.pair.a.fields[0].value)
    }

    @Test
    fun `VER-21J-23 a confirmed save whose APPLIED write fails is not replayed`() = runBlocking {
        val w = world()
        w.store.writeFailure = { it.contains("\"status\":\"APPLIED\"") }

        val outcome = w.coordinator.save(w.baseOfA(), draftOf(0 to "once"))

        assertTrue("got $outcome", outcome is NoteMutationOutcome.VerificationRequired)
        val required = outcome as NoteMutationOutcome.VerificationRequired
        assertEquals("applied_not_recorded", required.reason)
        assertEquals(1, w.pair.a.applyCalls.size)
        assertEquals("the durable record is still the boundary status", NoteMutationStatus.SUBMITTING, required.record.status)

        // Restart over those bytes: normalized to AMBIGUOUS, never replayed, never reported complete.
        w.store.writeFailure = null
        val restarted = w.restart()
        val recovered = restarted.recover(required.record.mutationId) as NoteMutationRecoveryOutcome.StillAmbiguous
        assertEquals(NoteMutationStatus.AMBIGUOUS, recovered.record.status)
        assertEquals(1, w.pair.a.applyCalls.size)
        assertTrue(w.pair.b.applyCalls.isEmpty())
    }

    @Test
    fun `VER-21J-24 a durable APPLIED refreshes only the owner's data`() = runBlocking {
        val w = world()
        val bReadsBefore = w.pair.b.readCount

        val outcome = w.coordinator.save(w.baseOfA(), draftOf(0 to "confirmed"))
        assertTrue("got $outcome", outcome is NoteMutationOutcome.Applied)
        val applied = outcome as NoteMutationOutcome.Applied

        assertEquals(NoteMutationStatus.APPLIED, w.persistedStatus(applied.record.mutationId))
        assertEquals("the presentation truth is A's own post-write read", w.pair.aId, applied.refreshed.cardRef.backendId)
        assertEquals("confirmed", applied.refreshed.fields!!.first().value)
        assertEquals("B was not read to refresh anything", bReadsBefore, w.pair.b.readCount)
        assertEquals(0, w.pair.b.applyCalls.size)

        w.pair.captureCounters()
        assertEquals(1, w.pair.counters.countFor(w.pair.aId))
        assertEquals(0, w.pair.counters.countFor(w.pair.bId))
        assertEquals(1, w.pair.counters.total)
    }

    // ============================================ recovery and retry (VER-25 … VER-30)

    @Test
    fun `VER-21J-25 after a restart an ambiguous record keeps owner, identity and ambiguity`() = runBlocking {
        val w = world()
        w.pair.a.loseResponse()
        val record = w.coordinator.save(
            w.baseOfA(),
            NoteEditDraft(fieldValues = mapOf(0 to "unresolved"), tags = listOf("alpha", "delta"))
        ).recordOrNull()!!

        // Everything about the world moves while the record is unresolved.
        w.preference = BackendPreference.Explicit(w.pair.bId)
        w.switchReadContextTo(w.pair.bId, w.pair.b.currentCollectionIdentity)
        val restarted = w.restart()

        val kept = w.persistedRecord(record.mutationId)!!
        assertEquals(w.pair.aId, kept.backendId)
        assertEquals(Gate21jIds.NOTE, kept.noteRef.noteId)
        assertEquals(Gate21jIds.CARD, kept.cardRef.cardId)
        assertEquals(w.pair.aId, kept.noteRef.backendId)
        assertTrue("the tag half of the edit is still recorded as changed", kept.tagsChanged)
        assertEquals(listOf(0), kept.changedFields.map { it.ordinal })
        assertEquals(NoteMutationStatus.AMBIGUOUS, kept.status)

        val recovered = restarted.recover(record.mutationId) as NoteMutationRecoveryOutcome.StillAmbiguous
        assertEquals(NoteMutationStatus.AMBIGUOUS, recovered.record.status)
        assertEquals(1, w.pair.a.applyCalls.size)
        assertTrue("the newly preferred backend is untouched", w.pair.b.applyCalls.isEmpty())
        assertTrue(w.pair.b.reconcileCalls.isEmpty())
    }

    @Test
    fun `VER-21J-26 retry permission comes from backend evidence, not from transport recovery`() = runBlocking {
        val w = world()
        w.pair.a.loseResponse()
        val ambiguous = w.coordinator.save(w.baseOfA(), draftOf(0 to "uncertain")).recordOrNull()!!
        assertEquals(NoteMutationStatus.AMBIGUOUS, ambiguous.status)

        // "The backend is reachable again" is not proof: nothing changes.
        w.pair.a.refreshAvailability()
        assertEquals(
            NoteMutationOutcome.Refused(NoteMutationRefusal.RetryNotAllowed(NoteMutationStatus.AMBIGUOUS)),
            w.coordinator.retry(ambiguous.mutationId)
        )
        assertEquals(1, w.pair.a.applyCalls.size)

        // A proven pre-write refusal, by contrast, is retryable evidence.
        val second = world()
        second.pair.a.refuseFirstThenApply()
        val retryable = second.coordinator.save(second.baseOfA(), draftOf(0 to "retry me")).recordOrNull()!!
        assertEquals(NoteMutationStatus.RETRY_ALLOWED, retryable.status)
        assertEquals(NoteMutationReason.NOT_APPLIED_BY_BACKEND, retryable.reason)
        assertTrue(second.coordinator.retry(retryable.mutationId) is NoteMutationOutcome.Applied)
        assertEquals(2, second.pair.a.applyCalls.size)
        assertTrue(second.pair.b.applyCalls.isEmpty())
    }

    @Test
    fun `VER-21J-27 an unavailable original backend blocks and never fails over`() = runBlocking {
        val w = world()
        w.pair.a.loseResponse()
        val ambiguous = w.coordinator.save(w.baseOfA(), draftOf(0 to "unresolved")).recordOrNull()!!
        w.pair.a.applyHook = null

        // A is gone; B is ready and even preferred.
        assertEquals(AnkiBackendRemoval.Removed(w.pair.aId), w.registry.remove(w.pair.aId))
        w.preference = BackendPreference.Explicit(w.pair.bId)
        assertEquals(w.pair.bId, (w.resolved() as BackendResolutionResult2.Resolved).backendId)

        val recovered = w.coordinator.recover(ambiguous.mutationId) as NoteMutationRecoveryOutcome.StillAmbiguous
        assertEquals(NoteMutationVerification.Unverifiable("backend_not_registered"), recovered.evidence)
        assertEquals(NoteMutationStatus.AMBIGUOUS, w.persistedStatus(ambiguous.mutationId))

        assertEquals(
            NoteMutationOutcome.Refused(NoteMutationRefusal.BackendRouteBlocked("backend_not_registered")),
            w.coordinator.save(w.baseOfA(), draftOf(0 to "new attempt"))
        )
        assertTrue("B received no mutation and no recovery call", w.pair.b.applyCalls.isEmpty())
        assertTrue(w.pair.b.reconcileCalls.isEmpty())
        assertEquals("B's own note is untouched", "B front", w.pair.b.fields[0].value)
    }

    @Test
    fun `VER-21J-28 reconnect alone never completes a recovery`() = runBlocking {
        // The only shape locked §3.3 permits `OP-M3` for: a collection key AND an authoritative read.
        val w = world(bReconcilesAuthoritatively = true)
        w.pair.b.applyHook = { _, _ -> NoteMutationBackendResult.OutcomeUnknown(AnkiError.Unknown("timeout")) }
        val recoverable = w.coordinator.save(w.baseOfB(), draftOf(0 to "on B")).recordOrNull()!!
        assertEquals(NoteMutationStatus.AMBIGUOUS, recoverable.status)
        w.pair.b.applyHook = null
        w.pair.b.reconcileResult = NoteMutationReconciliationResult.ConfirmedApplied

        // Reconnect by removing and re-registering the same instance, with the key no longer reported.
        w.registry.remove(w.pair.bId)
        w.pair.b.collectionIdentity = null
        assertEquals(AnkiBackendRegistration.Registered(w.pair.bId), w.registry.register(w.pair.b))
        val degraded = w.coordinator.recover(recoverable.mutationId)
        assertTrue("got $degraded", degraded is NoteMutationRecoveryOutcome.StillAmbiguous)
        assertEquals(NoteMutationStatus.AMBIGUOUS, w.persistedStatus(recoverable.mutationId))
        assertTrue("no reconciliation read was sent", w.pair.b.reconcileCalls.isEmpty())

        // With the collection key provably restored, the locked AUTHORITATIVE_MATCH cell applies.
        w.pair.b.collectionIdentity = AnkiCollectionIdentity(w.pair.bId, Gate21jIds.COLLECTION_B)
        val resolved = w.coordinator.recover(recoverable.mutationId)
        assertTrue("got $resolved", resolved is NoteMutationRecoveryOutcome.Resolved)
        assertEquals(NoteMutationStatus.APPLIED, w.persistedStatus(recoverable.mutationId))
        assertEquals(1, w.pair.b.reconcileCalls.size)
        assertTrue(w.pair.a.applyCalls.isEmpty())
        assertTrue(w.pair.a.reconcileCalls.isEmpty())
    }

    @Test
    fun `VER-21J-29 a historical transaction is never reassigned to a new backend`() = runBlocking {
        val w = world()
        w.pair.a.loseResponse()
        val ambiguous = w.coordinator.save(w.baseOfA(), draftOf(0 to "unresolved")).recordOrNull()!!
        w.pair.a.applyHook = null
        val bytes = w.store.encoded

        w.registry.remove(w.pair.aId)
        val replacement = FakeNoteMutationBackend(
            backendId = AnkiBackendId.PcAgent("gate21j-replacement"),
            noteId = Gate21jIds.NOTE,
            cardId = Gate21jIds.CARD,
            deckIds = listOf(Gate21jIds.DECK, Gate21jIds.OTHER_DECK)
        )
        assertEquals(AnkiBackendRegistration.Registered(replacement.id), w.registry.register(replacement))
        w.preference = BackendPreference.Explicit(replacement.id)

        val recovered = w.coordinator.recover(ambiguous.mutationId)
        assertTrue("got $recovered", recovered is NoteMutationRecoveryOutcome.StillAmbiguous)
        assertEquals(
            w.pair.aId,
            (recovered as NoteMutationRecoveryOutcome.StillAmbiguous).record.backendId
        )
        assertEquals("the durable bytes are untouched", bytes, w.store.encoded)
        assertTrue(replacement.applyCalls.isEmpty())
        assertTrue(replacement.reconcileCalls.isEmpty())
    }

    @Test
    fun `VER-21J-30 two unresolved backends recover independently`() = runBlocking {
        val w = world(bReconcilesAuthoritatively = true)

        w.pair.a.loseResponse()
        val onA = w.coordinator.save(w.baseOfA(), draftOf(0 to "A unresolved")).recordOrNull()!!
        w.pair.a.applyHook = null

        w.pair.b.applyHook = { _, _ -> NoteMutationBackendResult.OutcomeUnknown(AnkiError.Unknown("timeout")) }
        val onB = w.coordinator.save(w.baseOfB(), draftOf(0 to "B unresolved")).recordOrNull()!!
        w.pair.b.applyHook = null
        w.pair.b.reconcileResult = NoteMutationReconciliationResult.ConfirmedNotApplied

        assertEquals(NoteMutationStatus.AMBIGUOUS, onA.status)
        assertEquals(NoteMutationStatus.AMBIGUOUS, onB.status)
        assertNotEquals(onA.mutationId, onB.mutationId)

        val aReads = w.pair.a.readCount
        val bReconciles = w.pair.b.reconcileCalls.size
        assertTrue(w.coordinator.recover(onA.mutationId) is NoteMutationRecoveryOutcome.StillAmbiguous)
        assertEquals("A's recovery made only A's own read-only comparison", aReads + 1, w.pair.a.readCount)
        assertEquals("B was not touched by A's recovery", bReconciles, w.pair.b.reconcileCalls.size)

        assertTrue(w.coordinator.recover(onB.mutationId) is NoteMutationRecoveryOutcome.Resolved)
        assertEquals(1, w.pair.b.reconcileCalls.size)
        assertEquals(NoteMutationStatus.AMBIGUOUS, w.persistedStatus(onA.mutationId))
        assertEquals(NoteMutationStatus.RETRY_ALLOWED, w.persistedStatus(onB.mutationId))
    }

    // ============================================== editor session safety (VER-31 … VER-34)

    @Test
    fun `VER-21J-31 a repeated save never produces an uncontrolled duplicate mutation`() = runBlocking {
        val w = world()
        val hold = CompletableDeferred<Unit>()
        w.pair.a.applyHook = { request, fake ->
            hold.await()
            fake.applyFaithfully(request)
            NoteMutationBackendResult.ConfirmedApplied
        }
        val base = w.baseOfA()

        val first = async { w.coordinator.save(base, draftOf(0 to "once")) }
        while (w.pair.a.applyCalls.isEmpty()) yield()
        assertEquals(
            NoteMutationOutcome.Refused(NoteMutationRefusal.SaveInProgress),
            w.coordinator.save(base, draftOf(0 to "once"))
        )

        hold.complete(Unit)
        assertTrue("got ${first.await()}", first.await() is NoteMutationOutcome.Applied)
        assertEquals(1, w.pair.a.applyCalls.size)
        assertEquals(1, w.pair.a.applyCalls.map { it.mutationId }.distinct().size)
        assertTrue(w.pair.b.applyCalls.isEmpty())

        // Once the mutation is terminal the note is free again — that is a new edit, not a duplicate.
        val again = w.coordinator.save(w.baseOfA(), draftOf(0 to "twice"))
        assertTrue("got $again", again is NoteMutationOutcome.Applied)
        assertEquals(2, w.pair.a.applyCalls.size)
        assertNotEquals(w.pair.a.applyCalls[0].mutationId, w.pair.a.applyCalls[1].mutationId)
    }

    @Test
    fun `VER-21J-32 an old save result cannot reach a new editor session`() = runTest {
        val w = world()
        val hold = CompletableDeferred<Unit>()
        w.pair.a.applyHook = { request, fake ->
            hold.await()
            fake.applyFaithfully(request)
            NoteMutationBackendResult.ConfirmedApplied
        }
        val model = editor(w)
        advanceUntilIdle()

        model.onFieldChange(0, "from the closed editor")
        model.save()
        advanceUntilIdle()
        assertEquals(1, w.pair.a.applyCalls.size)

        // The editor session is replaced (generation bump) while the save is still in flight.
        model.load()
        advanceUntilIdle()

        hold.complete(Unit)
        advanceUntilIdle()

        val editor = (model.uiState.value as EditNoteUiState.Ready).editor
        assertFalse(
            "the stale result must not be adopted as this session's saved state",
            editor.saveState is EditNoteSaveState.Saved
        )
        assertFalse("no save is in flight for the new session", editor.saveState is EditNoteSaveState.Saving)
        assertNull("no navigation signal fired for the new session", model.savedCardRef.value)
        // …while the old transaction's own durable record is still updated honestly by its own path.
        assertEquals(NoteMutationStatus.APPLIED, w.persistedStatus(NoteMutationId("m-1")))
    }

    @Test
    fun `VER-21J-33 a restored draft keeps its owner, note and model context`() = runTest {
        val w = world()
        val model = editor(w)
        advanceUntilIdle()

        model.onFieldChange(0, "unsaved draft")
        model.addTags("charlie")
        advanceUntilIdle()
        assertTrue((model.uiState.value as EditNoteUiState.Ready).editor.dirty)

        // The session is restored from a fresh authoritative read of the same bound reference.
        model.load()
        advanceUntilIdle()

        val after = (model.uiState.value as EditNoteUiState.Ready).editor
        assertEquals(w.pair.aId, after.cardRef.backendId)
        assertEquals(Gate21jIds.NOTE, after.cardRef.noteId)
        assertEquals("the model context is A's own schema", listOf("Front", "Back"), after.fields.map { it.label })
        assertEquals("the fresh read is the base again", "A front", after.fields[0].originalValue)
        assertEquals("no draft text was carried across the reload", "A front", after.fields[0].draftValue)
        assertEquals(listOf("alpha"), after.tags.draftTags)
        assertFalse(after.dirty)
        assertNull("an unsaved draft is not a transaction", w.store.encoded)
        assertTrue(w.pair.a.applyCalls.isEmpty())
    }

    @Test
    fun `VER-21J-34 discarding an unsaved draft cannot delete an unresolved mutation`() = runTest {
        val w = world()
        w.pair.a.loseResponse()
        val record = w.coordinator.save(w.baseOfA(), draftOf(0 to "unresolved")).recordOrNull()!!
        w.pair.a.applyHook = null
        assertEquals(NoteMutationStatus.AMBIGUOUS, record.status)

        // The user left the editor and came back to the same note.
        val model = editor(w)
        advanceUntilIdle()

        val editor = (model.uiState.value as EditNoteUiState.Ready).editor
        assertNotNull("the note is still owned by the unresolved mutation", editor.blockedByMutation)
        assertEquals(record.mutationId.value, editor.blockedByMutation!!.mutationId)
        assertFalse("Save is not offered while that mutation is unresolved", editor.canSave)
        assertEquals(NoteMutationStatus.AMBIGUOUS, w.persistedStatus(record.mutationId))

        // An ordinary local draft edit cannot remove it either.
        model.onFieldChange(0, "a new draft")
        advanceUntilIdle()
        assertEquals(1, w.pair.a.applyCalls.size)
        assertEquals(NoteMutationStatus.AMBIGUOUS, w.persistedStatus(record.mutationId))
        assertFalse((model.uiState.value as EditNoteUiState.Ready).editor.canSave)
    }

    // ============================================== legacy and cache (VER-35 … VER-37)

    @Test
    fun `VER-21J-35 a record without provable ownership is preserved and blocked`() = runBlocking {
        val w = world()
        w.pair.a.loseResponse()
        val ambiguous = w.coordinator.save(w.baseOfA(), draftOf(0 to "legacy")).recordOrNull()!!
        w.pair.a.applyHook = null
        val legacyBytes = w.store.encoded!!.replace(",\"collectionRef\":null", "")
        assertFalse("the historical snapshot has no collection evidence at all", legacyBytes.contains("collectionRef"))

        val restartedStore = InMemoryNoteMutationStore().apply { encoded = legacyBytes }
        val coordinator = DefaultNoteMutationCoordinator(
            w.registry,
            DefaultNoteMutationLedger(restartedStore, nowEpochMs = { 5_000L }),
            NoteEditSafetyPolicy { null },
            NoteMutationIdSource { NoteMutationId("restart-1") },
            nowEpochMs = { 5_000L }
        )
        w.registry.remove(w.pair.aId)
        w.preference = BackendPreference.Explicit(w.pair.bId)

        val recovered = coordinator.recover(ambiguous.mutationId)
        assertTrue("got $recovered", recovered is NoteMutationRecoveryOutcome.StillAmbiguous)
        val record = (recovered as NoteMutationRecoveryOutcome.StillAmbiguous).record
        assertEquals("the record keeps the owner it was written with", w.pair.aId, record.backendId)
        assertNull(record.collectionRef)
        assertEquals("the bytes are not rewritten and not reassigned", legacyBytes, restartedStore.encoded)
        assertTrue(w.pair.b.applyCalls.isEmpty())
        assertTrue(w.pair.b.reconcileCalls.isEmpty())
        assertEquals(NoteMutationStatus.AMBIGUOUS, w.persistedStatus(ambiguous.mutationId))
    }

    @Test
    fun `VER-21J-36 a historical record with a provable owner recovers through that owner only`() = runBlocking {
        val w = world(bReconcilesAuthoritatively = true)
        w.pair.b.applyHook = { _, _ -> NoteMutationBackendResult.OutcomeUnknown(AnkiError.Unknown("timeout")) }
        val ambiguous = w.coordinator.save(w.baseOfB(), draftOf(0 to "on B")).recordOrNull()!!
        w.pair.b.applyHook = null

        // A historical snapshot: the same record without the collection evidence 21J can now add.
        val decoded = NoteMutationCodec.decode(w.store.encoded!!)!!
        val legacyBytes = NoteMutationCodec.encode(decoded.map { it.copy(collectionRef = null) })
        assertEquals(w.pair.bId, NoteMutationCodec.decode(legacyBytes)!!.single().backendId)

        val legacyStore = InMemoryNoteMutationStore().apply { encoded = legacyBytes }
        val coordinator = DefaultNoteMutationCoordinator(
            w.registry,
            DefaultNoteMutationLedger(legacyStore, nowEpochMs = { 9_000L }),
            NoteEditSafetyPolicy { null },
            NoteMutationIdSource { NoteMutationId("restart-b") },
            nowEpochMs = { 9_000L }
        )
        w.pair.b.reconcileResult = NoteMutationReconciliationResult.ConfirmedApplied

        // With no collection evidence the OP-M3 cell is the nominal one: still blocked — and, the
        // property this test exists for, never asked of the *other* backend.
        val recovered = coordinator.recover(ambiguous.mutationId)
        assertTrue("got $recovered", recovered is NoteMutationRecoveryOutcome.StillAmbiguous)
        assertEquals(w.pair.bId, (recovered as NoteMutationRecoveryOutcome.StillAmbiguous).record.backendId)
        assertTrue(w.pair.b.reconcileCalls.isEmpty())
        assertTrue(w.pair.a.applyCalls.isEmpty())
        assertTrue(w.pair.a.reconcileCalls.isEmpty())

        // The same numeric note id on the other backend is a different logical note.
        val bAppliesBefore = w.pair.b.applyCalls.size
        val onA = coordinator.save(w.baseOfA(), draftOf(0 to "A's own note"))
        assertTrue("got $onA", onA is NoteMutationOutcome.Applied)
        assertEquals("B front", w.pair.b.fields[0].value)
        assertEquals("no further write reached B", bAppliesBefore, w.pair.b.applyCalls.size)
    }

    @Test
    fun `VER-21J-37 only the owner's read data is refreshed after a confirmed mutation`() = runBlocking {
        val w = world()
        val bReadsBefore = w.pair.b.readCount
        val bFieldsBefore = w.pair.b.fields.map { it.value }

        val outcome = w.coordinator.save(
            w.baseOfA(),
            NoteEditDraft(fieldValues = mapOf(0 to "refreshed"), tags = listOf("alpha", "echo"))
        )
        assertTrue("got $outcome", outcome is NoteMutationOutcome.Applied)
        val refreshed = (outcome as NoteMutationOutcome.Applied).refreshed

        assertEquals(w.pair.aId, refreshed.cardRef.backendId)
        assertEquals(Gate21jIds.NOTE, refreshed.noteRef?.noteId)
        assertEquals("refreshed", refreshed.fields!!.first().value)
        assertEquals(listOf("alpha", "echo"), refreshed.tags)
        assertEquals("B's read data was not refreshed or invalidated", bReadsBefore, w.pair.b.readCount)
        assertEquals(bFieldsBefore, w.pair.b.fields.map { it.value })
        assertEquals(listOf("bravo"), w.pair.b.tags)
    }

    // ================================ refusal projection in the editor (CHECKPOINT 21J-28)

    @Test
    fun `CHECKPOINT-21J-28 a blocked save is projected as a refusal that points at no other backend`() = runTest {
        val w = world()
        val model = editor(w)
        advanceUntilIdle()

        model.onFieldChange(0, "edited")
        w.registry.remove(w.pair.aId)
        advanceUntilIdle()

        model.save()
        advanceUntilIdle()

        val editor = (model.uiState.value as EditNoteUiState.Ready).editor
        val state = editor.saveState
        assertTrue("got $state", state is EditNoteSaveState.Blocked)
        val message = (state as EditNoteSaveState.Blocked).message
        assertTrue(
            "the refusal must say nothing was written and that no other backend was used: $message",
            message.contains("did not switch to another backend")
        )
        assertNull("no false success", model.savedCardRef.value)
        assertEquals("no write happened anywhere", 0, w.pair.a.applyCalls.size + w.pair.b.applyCalls.size)
        assertEquals("a refused save leaves the draft usable", "edited", editor.fields[0].draftValue)
    }

    // ------------------------------------------------------------------------------------ helpers

    /** A real editor screen over the world's registry, exactly as the nav route builds it. */
    private fun TestScope.editor(w: Gate21jWorld): EditNoteViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        return EditNoteViewModel(
            initialBackend = w.registry.find(w.pair.a.cardRef().backendId)!!,
            initialCardRef = w.pair.a.cardRef(),
            coordinator = w.coordinator
        ).also { model ->
            ViewModelStore().also { store ->
                store.put("edit-note", model)
                viewModelStores += store
            }
        }
    }
}
