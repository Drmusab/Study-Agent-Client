package com.studyagent.client.anki.edit

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.edit.DefaultNoteMutationCoordinator
import com.studyagent.client.core.anki.edit.DefaultNoteMutationLedger
import com.studyagent.client.core.anki.edit.NoteEditBase
import com.studyagent.client.core.anki.edit.NoteEditBlock
import com.studyagent.client.core.anki.edit.NoteEditDraft
import com.studyagent.client.core.anki.edit.NoteEditSafetyPolicy
import com.studyagent.client.core.anki.edit.NoteEditValidationError
import com.studyagent.client.core.anki.edit.NoteLedgerResult
import com.studyagent.client.core.anki.edit.NoteMutationAttestation
import com.studyagent.client.core.anki.edit.NoteMutationBackendResult
import com.studyagent.client.core.anki.edit.NoteMutationCodec
import com.studyagent.client.core.anki.edit.NoteMutationId
import com.studyagent.client.core.anki.edit.NoteMutationIdSource
import com.studyagent.client.core.anki.edit.NoteMutationOutcome
import com.studyagent.client.core.anki.edit.NoteMutationReason
import com.studyagent.client.core.anki.edit.NoteMutationRecord
import com.studyagent.client.core.anki.edit.NoteMutationRecoveryOutcome
import com.studyagent.client.core.anki.edit.NoteMutationRefusal
import com.studyagent.client.core.anki.edit.NoteMutationReconciliationResult
import com.studyagent.client.core.anki.edit.NoteMutationStatus
import com.studyagent.client.core.anki.edit.NoteMutationStep
import com.studyagent.client.core.anki.edit.NoteMutationEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 17 — the coordinator's ordering, refusal and classification rules, exercised against a
 * scriptable backend and a durable in-memory store.
 */
class NoteMutationCoordinatorTest {

    private class Harness(
        val events: MutableList<String> = mutableListOf(),
        val backend: FakeNoteMutationBackend = FakeNoteMutationBackend(events = events)
    ) {
        val store = InMemoryNoteMutationStore(events)
        var blocked: NoteEditBlock? = null
        private var counter = 0
        private val ids = NoteMutationIdSource { NoteMutationId("m-${++counter}") }
        val safety = NoteEditSafetyPolicy { _ -> blocked }
        val ledger = DefaultNoteMutationLedger(store, nowEpochMs = { 1_000L })
        val coordinator = DefaultNoteMutationCoordinator(backend, ledger, safety, ids, nowEpochMs = { 1_000L })

        fun base(): NoteEditBase = backend.editableBase()

        fun restarted(): DefaultNoteMutationCoordinator = DefaultNoteMutationCoordinator(
            backend,
            DefaultNoteMutationLedger(store, nowEpochMs = { 2_000L }),
            safety,
            NoteMutationIdSource { NoteMutationId("restart-${++counter}") },
            nowEpochMs = { 2_000L }
        )

        fun persistedStatus(id: String): NoteMutationStatus? {
            val text = store.encoded ?: return null
            return NoteMutationCodec.decode(text)?.firstOrNull { it.mutationId.value == id }?.status
        }
    }

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

    // ---- ordering and durability ----------------------------------------------------------------

    @Test
    fun happyPathPersistsPreparedThenSubmittingThenWritesThenVerifiesThenApplied() {
        runBlocking {
            val h = Harness()
            val outcome = h.coordinator.save(h.base(), frontEdit("NEW FRONT"))

            assertTrue(outcome is NoteMutationOutcome.Applied)
            outcome as NoteMutationOutcome.Applied
            assertEquals(NoteMutationStatus.APPLIED, outcome.record.status)
            assertNotNull(outcome.refreshed)
            assertEquals("NEW FRONT", outcome.refreshed!!.fields?.firstOrNull()?.value)
            assertEquals("NEW FRONT", h.backend.fields[0].value)
            assertEquals(1, h.backend.applyCalls.size)

            val prepared = h.events.indexOf("persist:[PREPARED]")
            val submitting = h.events.indexOf("persist:[SUBMITTING]")
            val write = h.events.indexOf("backend:apply:0")
            val verificationRead = write + h.events.drop(write).indexOf("read:card")
            val applied = h.events.indexOf("persist:[APPLIED]")
            assertTrue("PREPARED before SUBMITTING", prepared in 0 until submitting)
            assertTrue("SUBMITTING durable before the first write", submitting < write)
            // CONTRACT-06/15: the row count is weak evidence, so APPLIED needs an authoritative read
            // first, and that read (not a later one) is what the UI is shown.
            assertTrue("the authoritative read happens after the write", write < verificationRead)
            assertTrue("APPLIED is persisted only after that read verified intent", verificationRead < applied)
            assertEquals("exactly one read after the write", verificationRead, h.events.lastIndexOf("read:card"))
        }
    }

    @Test
    fun noBackendWriteWithoutDurableSubmitting() {
        runBlocking {
            val h = Harness()
            h.store.writeFailure = { it.contains("\"status\":\"SUBMITTING\"") }
            val outcome = h.coordinator.save(h.base(), frontEdit())

            assertEquals(NoteMutationOutcome.LedgerUnavailable("write_not_committed"), outcome)
            assertTrue(h.backend.applyCalls.isEmpty())
            assertEquals(NoteMutationStatus.PREPARED, h.persistedStatus("m-1"))
        }
    }

    @Test
    fun preparedNotPersistedMeansNothingIsReadOrSent() {
        runBlocking {
            val h = Harness()
            h.store.writeFailure = { it.contains("\"status\":\"PREPARED\"") }
            val outcome = h.coordinator.save(h.base(), frontEdit())

            assertTrue(outcome is NoteMutationOutcome.LedgerUnavailable)
            assertTrue(h.backend.applyCalls.isEmpty())
            assertEquals(0, h.backend.readCount)
        }
    }

    @Test
    fun unreadableStoreFailsClosedWithoutAnyBackendEffect() {
        runBlocking {
            val h = Harness()
            h.store.readFailure = "io"
            val outcome = h.coordinator.save(h.base(), frontEdit())
            assertTrue(outcome is NoteMutationOutcome.LedgerUnavailable)
            assertTrue(h.backend.applyCalls.isEmpty())
        }
    }

    // ---- pre-transaction refusals ---------------------------------------------------------------

    @Test
    fun emptyDraftIsNoChangesAndWritesNothing() {
        runBlocking {
            val h = Harness()
            val base = h.base()
            assertEquals(NoteMutationOutcome.NoChanges, h.coordinator.save(base, NoteEditDraft(fieldValues = mapOf(0 to base.fields[0].value))))
            assertEquals(0, h.store.writes)
            assertEquals(0, h.backend.readCount)
        }
    }

    @Test
    fun validationErrorIsPreTransactionAndNeverRetryAllowed() {
        runBlocking {
            val h = Harness()
            val outcome = h.coordinator.save(h.base(), NoteEditDraft(fieldValues = mapOf(9 to "x")))
            assertEquals(NoteMutationOutcome.ValidationFailed(listOf(NoteEditValidationError.UnknownField(9))), outcome)
            assertEquals(0, h.store.writes)
        }
    }

    @Test
    fun clearingTheLastFieldIsRefusedBeforeAnyTransactionBecauseTheProviderDropsTrailingEmpties() {
        runBlocking {
            // Utils.splitFields drops trailing empty fields, so a FLDS write ending in "" fails the
            // provider's count check. The coordinator must refuse before PREPARED is recorded.
            val h = Harness()
            val base = h.base()
            val outcome = h.coordinator.save(base, NoteEditDraft(fieldValues = mapOf(base.fields.lastIndex to "")))
            assertEquals(NoteMutationOutcome.Refused(NoteMutationRefusal.TrailingEmptyFieldNotRepresentable), outcome)
            assertEquals(0, h.store.writes)
            assertEquals(0, h.backend.applyCalls.size)
        }
    }

    @Test
    fun editingAnEarlierFieldWhileTheLastFieldIsNonEmptyIsNotBlockedByThatRule() {
        runBlocking {
            val h = Harness()
            val base = h.base()
            val outcome = h.coordinator.save(base, NoteEditDraft(fieldValues = mapOf(0 to "changed")))
            assertTrue("got $outcome", outcome is NoteMutationOutcome.Applied)
        }
    }

    @Test
    fun unsupportedDimensionIsRefusedWithoutRecordingAnything() {
        runBlocking {
            val backend = FakeNoteMutationBackend(capabilities = FakeNoteMutationBackend.defaultCapabilities().copy(editNoteTags = false))
            val h = Harness(backend = backend)
            val outcome = h.coordinator.save(h.base(), NoteEditDraft(tags = listOf("new")))
            assertEquals(NoteMutationOutcome.Refused(NoteMutationRefusal.UnsupportedDimension("tags")), outcome)
            assertEquals(0, h.store.writes)
        }
    }

    @Test
    fun backendWithoutRereadCannotEditAtAll() {
        runBlocking {
            val backend = FakeNoteMutationBackend(capabilities = FakeNoteMutationBackend.defaultCapabilities().copy(cardDetails = false))
            val h = Harness(backend = backend)
            assertEquals(
                NoteMutationOutcome.Refused(NoteMutationRefusal.UnsupportedDimension("card_details")),
                h.coordinator.save(h.base(), frontEdit())
            )
        }
    }

    @Test
    fun backendMismatchIsRefused() {
        runBlocking {
            val h = Harness()
            val foreign = AnkiBackendId.PcAgent("other")
            val base = h.base().copy(
                cardRef = AnkiCardRef(foreign, cardId = "card-1", noteId = "note-1", cardOrd = 0),
                noteRef = AnkiNoteRef(foreign, "note-1"),
                deckRef = null
            )
            assertEquals(NoteMutationOutcome.Refused(NoteMutationRefusal.BackendMismatch), h.coordinator.save(base, frontEdit()))
        }
    }

    @Test
    fun studyOverlapBlocksBeforeAnyRecord() {
        runBlocking {
            val h = Harness()
            h.blocked = NoteEditBlock("review_commit")
            val outcome = h.coordinator.save(h.base(), frontEdit())
            assertEquals(NoteMutationOutcome.Refused(NoteMutationRefusal.StudyActivityOverlaps(NoteEditBlock("review_commit"))), outcome)
            assertEquals(0, h.store.writes)
            assertTrue(h.backend.applyCalls.isEmpty())
        }
    }

    @Test
    fun deckTargetsAreVerifiedAgainstTheLiveListing() {
        runBlocking {
            val h = Harness()
            val base = h.base()
            fun deck(id: String) = AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, id)
            assertEquals(
                NoteMutationOutcome.ValidationFailed(listOf(NoteEditValidationError.DeckTargetFiltered)),
                h.coordinator.save(base, NoteEditDraft(targetDeck = deck("deck-filtered")))
            )
            assertEquals(
                NoteMutationOutcome.ValidationFailed(listOf(NoteEditValidationError.DeckTargetNotFound)),
                h.coordinator.save(base, NoteEditDraft(targetDeck = deck("deck-missing")))
            )
            assertEquals(
                NoteMutationOutcome.ValidationFailed(listOf(NoteEditValidationError.DeckTargetUnverifiable)),
                h.coordinator.save(base, NoteEditDraft(targetDeck = deck("deck-unknown")))
            )
            h.backend.decksFailure = AnkiError.BackendUnavailable()
            assertTrue(
                h.coordinator.save(base, NoteEditDraft(targetDeck = deck("deck-b"))) is
                    NoteMutationOutcome.Refused
            )
            assertEquals(0, h.store.writes)
            assertTrue(h.backend.applyCalls.isEmpty())
        }
    }

    @Test
    fun deckMoveFromAnUnknownSourceDeckIsRefused() {
        runBlocking {
            val h = Harness()
            val base = h.base().copy(deckRef = null)
            val outcome = h.coordinator.save(base, NoteEditDraft(targetDeck = AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "deck-b")))
            assertEquals(NoteMutationOutcome.ValidationFailed(listOf(NoteEditValidationError.SourceDeckUnknown)), outcome)
        }
    }

    // ---- classification -------------------------------------------------------------------------

    @Test
    fun confirmedNotApplicedAtFirstStepIsRetryableWithTheSameIdentity() {
        runBlocking {
            val h = Harness()
            var calls = 0
            h.backend.applyHook = { request, backend ->
                calls++
                if (calls == 1) NoteMutationBackendResult.ConfirmedNotApplied(AnkiError.PermissionRequired())
                else { backend.applyFaithfully(request.step); NoteMutationBackendResult.ConfirmedApplied }
            }
            val first = h.coordinator.save(h.base(), frontEdit("retry me"))
            assertTrue(first is NoteMutationOutcome.RetryAvailable)
            val id = first.recordOrNull()!!.mutationId
            assertEquals(NoteMutationStatus.RETRY_ALLOWED, first.recordOrNull()!!.status)

            val second = h.coordinator.retry(id)
            assertTrue("retry applies: ${second}", second is NoteMutationOutcome.Applied)
            assertEquals(id, second.recordOrNull()!!.mutationId)
            assertEquals("retry me", h.backend.fields[0].value)
            assertEquals(2, h.backend.applyCalls.size)
            assertEquals(0, h.backend.applyCalls[1].stepIndex)
        }
    }

    @Test
    fun outcomeUnknownIsAmbiguousAndCannotBeRetried() {
        runBlocking {
            val h = Harness()
            h.backend.applyHook = { _, _ -> NoteMutationBackendResult.OutcomeUnknown(AnkiError.Unknown("timeout")) }
            val outcome = h.coordinator.save(h.base(), frontEdit())
            assertTrue(outcome is NoteMutationOutcome.VerificationRequired)
            val id = outcome.recordOrNull()!!.mutationId
            assertEquals(NoteMutationStatus.AMBIGUOUS, outcome.recordOrNull()!!.status)

            assertEquals(
                NoteMutationOutcome.Refused(NoteMutationRefusal.RetryNotAllowed(NoteMutationStatus.AMBIGUOUS)),
                h.coordinator.retry(id)
            )
            assertEquals(1, h.backend.applyCalls.size)
        }
    }

    @Test
    fun ambiguousBecomesRetryableOnlyThroughAuthoritativeEvidence() {
        runBlocking {
            val h = Harness()
            h.backend.applyHook = { _, _ -> NoteMutationBackendResult.OutcomeUnknown(AnkiError.Unknown("timeout")) }
            val id = h.coordinator.save(h.base(), frontEdit("later")).recordOrNull()!!.mutationId

            h.backend.reconcileResult = NoteMutationReconciliationResult.Unresolved()
            assertTrue(h.coordinator.recover(id) is NoteMutationRecoveryOutcome.StillAmbiguous)

            h.backend.reconcileResult = NoteMutationReconciliationResult.ConfirmedNotApplied
            val resolved = h.coordinator.recover(id)
            assertTrue(resolved is NoteMutationRecoveryOutcome.Resolved)
            assertEquals(NoteMutationStatus.RETRY_ALLOWED, (resolved as NoteMutationRecoveryOutcome.Resolved).record.status)

            h.backend.applyHook = null
            assertTrue(h.coordinator.retry(id) is NoteMutationOutcome.Applied)
        }
    }

    @Test
    fun reconciliationThatConfirmsApplicationIsTerminal() {
        runBlocking {
            val h = Harness()
            h.backend.applyHook = { _, _ -> NoteMutationBackendResult.OutcomeUnknown(AnkiError.Unknown("timeout")) }
            val id = h.coordinator.save(h.base(), frontEdit()).recordOrNull()!!.mutationId
            h.backend.reconcileResult = NoteMutationReconciliationResult.ConfirmedApplied
            val resolved = h.coordinator.recover(id) as NoteMutationRecoveryOutcome.Resolved
            assertEquals(NoteMutationStatus.APPLIED, resolved.record.status)
            assertEquals(NoteMutationReason.RECONCILED_APPLIED, resolved.record.reason)
            assertEquals(
                NoteMutationOutcome.Refused(NoteMutationRefusal.RetryNotAllowed(NoteMutationStatus.APPLIED)),
                h.coordinator.retry(id)
            )
        }
    }

    @Test
    fun backendConflictIsTerminalAndNotRetryable() {
        runBlocking {
            val h = Harness()
            h.backend.applyHook = { _, _ -> NoteMutationBackendResult.Conflict() }
            val outcome = h.coordinator.save(h.base(), frontEdit())
            assertTrue(outcome is NoteMutationOutcome.Conflict)
            val id = outcome.recordOrNull()!!.mutationId
            assertEquals(NoteMutationStatus.CONFLICT, outcome.recordOrNull()!!.status)
            assertEquals(
                NoteMutationOutcome.Refused(NoteMutationRefusal.RetryNotAllowed(NoteMutationStatus.CONFLICT)),
                h.coordinator.retry(id)
            )
        }
    }

    @Test
    fun unexpectedBackendFailureIsTreatedAsUnknownNotAsNotApplied() {
        runBlocking {
            val h = Harness()
            h.backend.applyHook = { _, _ -> throw IllegalStateException("provider crashed mid-call") }
            val outcome = h.coordinator.save(h.base(), frontEdit())
            assertTrue(outcome is NoteMutationOutcome.VerificationRequired)
            assertEquals(NoteMutationStatus.AMBIGUOUS, outcome.recordOrNull()!!.status)
        }
    }

    @Test
    fun cancellationDuringTheWriteIsPersistedAsAmbiguousBeforePropagating() {
        runBlocking {
            val h = Harness()
            h.backend.applyHook = { _, _ -> throw CancellationException("caller went away") }
            var cancelled = false
            try {
                h.coordinator.save(h.base(), frontEdit())
            } catch (propagated: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertEquals(NoteMutationStatus.AMBIGUOUS, h.persistedStatus("m-1"))
            assertEquals(1, h.backend.applyCalls.size)
        }
    }

    @Test
    fun appliedWriteWithoutAnAuthoritativeReadIsNeverReportedAsApplied() {
        runBlocking {
            val h = Harness()
            // The provider's row count says the write happened, but every read afterwards fails, so no
            // evidence ties the intended state to the collection.
            h.backend.applyHook = { request, backend ->
                backend.applyFaithfully(request.step)
                backend.readFailure = AnkiError.BackendUnavailable()
                NoteMutationBackendResult.ConfirmedApplied
            }
            val outcome = h.coordinator.save(h.base(), frontEdit("applied"))

            assertTrue(outcome is NoteMutationOutcome.VerificationRequired)
            outcome as NoteMutationOutcome.VerificationRequired
            assertEquals(NoteMutationStatus.AMBIGUOUS, outcome.record.status)
            assertEquals(NoteMutationReason.POST_WRITE_UNVERIFIED, outcome.record.reason)
            assertTrue(outcome.reason.startsWith("POST_WRITE_UNVERIFIED:"))
            assertEquals(NoteMutationStatus.AMBIGUOUS, h.persistedStatus("m-1"))
            assertEquals(1, h.backend.applyCalls.size)

            // The note stays blocked: an ambiguous record is never replayed and never re-saved over.
            val blocked = h.coordinator.save(h.base(), frontEdit("again"))
            assertTrue(blocked is NoteMutationOutcome.ActiveMutationExists)
            assertEquals(NoteMutationStatus.AMBIGUOUS, (blocked as NoteMutationOutcome.ActiveMutationExists).record.status)
            assertEquals("no second write while the first is unresolved", 1, h.backend.applyCalls.size)
        }
    }

    @Test
    fun appliedWriteThatReadsBackDifferentValuesIsRecordedAsAmbiguousNotApplied() {
        runBlocking {
            val h = Harness()
            // A backend that claims success and then stores something else (normalization, a trigger,
            // or a lie) must not be allowed to report APPLIED.
            h.backend.applyHook = { request, backend ->
                backend.applyFaithfully(request.step)
                backend.fields[0] = backend.fields[0].copy(value = "silently different")
                NoteMutationBackendResult.ConfirmedApplied
            }
            val outcome = h.coordinator.save(h.base(), frontEdit("intended"))

            assertTrue(outcome is NoteMutationOutcome.VerificationRequired)
            val record = (outcome as NoteMutationOutcome.VerificationRequired).record
            assertEquals(NoteMutationStatus.AMBIGUOUS, record.status)
            assertEquals(NoteMutationReason.POST_WRITE_VERIFICATION_MISMATCH, record.reason)
            assertTrue(outcome.reason.startsWith("POST_WRITE_VERIFICATION_MISMATCH:"))
            assertEquals(1, h.backend.applyCalls.size)
        }
    }

    @Test
    fun appliedThatCannotBeRecordedIsNeverReplayed() {
        runBlocking {
            val h = Harness()
            h.store.writeFailure = { it.contains("\"status\":\"APPLIED\"") }
            val outcome = h.coordinator.save(h.base(), frontEdit("once"))
            assertEquals(
                NoteMutationReason.NONE,
                (outcome as NoteMutationOutcome.VerificationRequired).record.reason
            )
            assertEquals("applied_not_recorded", outcome.reason)
            assertEquals(1, h.backend.applyCalls.size)

            // Restart: the durable record is SUBMITTING, so it normalizes to AMBIGUOUS and is never re-sent.
            h.store.writeFailure = null
            val restarted = h.restarted()
            assertEquals(
                NoteMutationOutcome.Refused(NoteMutationRefusal.RetryNotAllowed(NoteMutationStatus.AMBIGUOUS)),
                restarted.resumePrepared(NoteMutationId("m-1"))
            )
            assertEquals(NoteMutationStatus.AMBIGUOUS, h.persistedStatus("m-1"))
            assertEquals(1, h.backend.applyCalls.size)
        }
    }

    // ---- human attestation (CONTRACT-26) --------------------------------------------------------

    /**
     * Drives one save into AMBIGUOUS the honest way: the backend claims the write applied, but no
     * authoritative read can be obtained afterwards, so nothing ties the intent to the collection.
     */
    private suspend fun ambiguousBecauseTheCollectionCannotBeRead(h: Harness): NoteMutationRecord {
        h.backend.applyHook = { request, backend ->
            backend.applyFaithfully(request.step)
            backend.readFailure = AnkiError.BackendUnavailable()
            NoteMutationBackendResult.ConfirmedApplied
        }
        val outcome = h.coordinator.save(h.base(), frontEdit("applied"))
        assertTrue(outcome is NoteMutationOutcome.VerificationRequired)
        val record = (outcome as NoteMutationOutcome.VerificationRequired).record
        assertEquals(NoteMutationStatus.AMBIGUOUS, record.status)
        return record
    }

    @Test
    fun attestationAppliedClosesTheRecordWithoutTouchingTheBackend() {
        runBlocking {
            val h = Harness()
            val record = ambiguousBecauseTheCollectionCannotBeRead(h)
            val writesBefore = h.backend.applyCalls.size

            val resolved = h.coordinator.resolveAmbiguous(
                record.mutationId,
                NoteMutationAttestation.APPLIED_IN_COLLECTION
            )

            assertTrue(resolved is NoteMutationRecoveryOutcome.Resolved)
            val closed = (resolved as NoteMutationRecoveryOutcome.Resolved).record
            assertEquals(NoteMutationStatus.APPLIED, closed.status)
            // Recorded as a human attestation, never as backend evidence (CONTRACT-26).
            assertEquals(NoteMutationReason.USER_ATTESTED_APPLIED, closed.reason)
            assertEquals(NoteMutationStatus.APPLIED, h.persistedStatus(record.mutationId.value))
            assertEquals("an attestation is not a write", writesBefore, h.backend.applyCalls.size)
            assertTrue(h.backend.reconcileCalls.isEmpty())
        }
    }

    @Test
    fun attestationAbsentClosesTheRecordAndFreesTheNoteForAFreshEdit() {
        runBlocking {
            val h = Harness()
            val record = ambiguousBecauseTheCollectionCannotBeRead(h)

            val resolved = h.coordinator.resolveAmbiguous(
                record.mutationId,
                NoteMutationAttestation.ABSENT_FROM_COLLECTION
            ) as NoteMutationRecoveryOutcome.Resolved
            assertEquals(NoteMutationStatus.CONFLICT, resolved.record.status)
            assertEquals(NoteMutationReason.USER_ATTESTED_NOT_APPLIED, resolved.record.reason)

            // CONFLICT is terminal, so the note is no longer owned: a new edit starts with a new id.
            h.backend.readFailure = null
            h.backend.applyHook = null
            assertEquals(null, h.coordinator.activeMutationFor(h.backend.noteRef().backendId, h.backend.noteRef().noteId))
            val fresh = h.coordinator.save(h.base(), frontEdit("after attestation"))
            assertTrue(fresh is NoteMutationOutcome.Applied)
            assertNotEquals(record.mutationId, (fresh as NoteMutationOutcome.Applied).record.mutationId)
            assertEquals(2, h.backend.applyCalls.size)
        }
    }

    @Test
    fun attestationCannotChangeARecordThatIsNotAmbiguous() {
        runBlocking {
            val h = Harness()
            val applied = h.coordinator.save(h.base(), frontEdit("done")) as NoteMutationOutcome.Applied

            val unchanged = h.coordinator.resolveAmbiguous(
                applied.record.mutationId,
                NoteMutationAttestation.ABSENT_FROM_COLLECTION
            )

            assertTrue(unchanged is NoteMutationRecoveryOutcome.Unchanged)
            assertEquals(NoteMutationStatus.APPLIED, (unchanged as NoteMutationRecoveryOutcome.Unchanged).record.status)
            assertEquals(NoteMutationReason.NONE, unchanged.record.reason)
            assertEquals(NoteMutationStatus.APPLIED, h.persistedStatus(applied.record.mutationId.value))
        }
    }

    @Test
    fun attestationForAnUnknownMutationIsNotFoundAndNeverInventsARecord() {
        runBlocking {
            val h = Harness()
            val resolved = h.coordinator.resolveAmbiguous(
                NoteMutationId("never-recorded"),
                NoteMutationAttestation.APPLIED_IN_COLLECTION
            )
            assertEquals(NoteMutationRecoveryOutcome.NotFound, resolved)
        }
    }

    @Test
    fun attestationIsNeverReportedUnlessItIsDurable() {
        runBlocking {
            val h = Harness()
            val record = ambiguousBecauseTheCollectionCannotBeRead(h)

            // The durable write fails: the attestation must not be reported as a resolution, and the
            // record must still read back as AMBIGUOUS.
            h.store.writeFailure = { true }
            val unwritten = h.coordinator.resolveAmbiguous(
                record.mutationId,
                NoteMutationAttestation.APPLIED_IN_COLLECTION
            )
            assertTrue(unwritten is NoteMutationRecoveryOutcome.LedgerUnavailable)
            assertEquals(NoteMutationStatus.AMBIGUOUS, h.persistedStatus(record.mutationId.value))
            assertEquals("an attestation is not a write", 1, h.backend.applyCalls.size)
            h.store.writeFailure = null

            // After a restart with an unreadable ledger it fails closed before any transition.
            h.store.readFailure = "io"
            val unreadable = h.restarted().resolveAmbiguous(
                record.mutationId,
                NoteMutationAttestation.ABSENT_FROM_COLLECTION
            )
            assertTrue(unreadable is NoteMutationRecoveryOutcome.LedgerUnavailable)
            assertEquals(1, h.backend.applyCalls.size)
            assertTrue(h.backend.reconcileCalls.isEmpty())
        }
    }

    @Test
    fun activeMutationForReportsTheOwningRecordAndNothingOnceTerminal() {
        runBlocking {
            val h = Harness()
            val backendId = h.backend.noteRef().backendId
            val noteId = h.backend.noteRef().noteId
            assertEquals(null, h.coordinator.activeMutationFor(backendId, noteId))

            val record = ambiguousBecauseTheCollectionCannotBeRead(h)
            val active = h.coordinator.activeMutationFor(backendId, noteId)
            assertNotNull(active)
            assertEquals(record.mutationId, active!!.mutationId)
            assertEquals(NoteMutationStatus.AMBIGUOUS, active.status)

            // A different note is never blocked by this record.
            assertEquals(null, h.coordinator.activeMutationFor(backendId, "some-other-note"))
        }
    }

    // ---- multi-operation plans ------------------------------------------------------------------

    @Test
    fun contentThenDeckRunInOrderAndBothSucceed() {
        runBlocking {
            val h = Harness()
            val draft = NoteEditDraft(fieldValues = mapOf(1 to "B2"), tags = listOf("new"), targetDeck = AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "deck-b"))
            val outcome = h.coordinator.save(h.base(), draft)
            assertTrue("got $outcome", outcome is NoteMutationOutcome.Applied)
            assertEquals(listOf(0, 1), h.backend.applyCalls.map { it.stepIndex })
            assertTrue(h.backend.applyCalls[0].step is NoteMutationStep.UpdateNoteContent)
            assertTrue(h.backend.applyCalls[1].step is NoteMutationStep.ChangeDeck)
            assertEquals("deck-b", h.backend.deckId)
            assertEquals(listOf("new"), h.backend.tags)
            assertEquals("B2", h.backend.fields[1].value)
            // An untouched field keeps its value inside the full positional list.
            assertEquals("front text", h.backend.fields[0].value)
        }
    }

    @Test
    fun laterStepNotAppliedAfterEarlierSuccessIsAmbiguousAndNotRetryable() {
        runBlocking {
            val h = Harness()
            h.backend.applyHook = { request, backend ->
                if (request.stepIndex == 0) {
                    backend.applyFaithfully(request.step)
                    NoteMutationBackendResult.ConfirmedApplied
                } else {
                    NoteMutationBackendResult.ConfirmedNotApplied(AnkiError.InvalidRequest("refused"))
                }
            }
            val draft = NoteEditDraft(fieldValues = mapOf(0 to "partial"), targetDeck = AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "deck-b"))
            val outcome = h.coordinator.save(h.base(), draft)
            assertTrue(outcome is NoteMutationOutcome.VerificationRequired)
            val record = outcome.recordOrNull()!!
            assertEquals(NoteMutationStatus.AMBIGUOUS, record.status)
            assertEquals(NoteMutationReason.PARTIAL_OPERATION_UNKNOWN, record.reason)
            assertEquals(
                NoteMutationOutcome.Refused(NoteMutationRefusal.RetryNotAllowed(NoteMutationStatus.AMBIGUOUS)),
                h.coordinator.retry(record.mutationId)
            )
        }
    }

    @Test
    fun backendOperationStepsAreNeverIssuedWithoutAPersistedIndex() {
        runBlocking {
            val h = Harness()
            // Fail persisting the advance to step 1: step 1 must never reach the backend.
            h.store.writeFailure = { it.contains("\"lastEnteredOperation\":1") }
            val draft = NoteEditDraft(fieldValues = mapOf(0 to "x"), targetDeck = AnkiDeckRef(AnkiBackendId.AnkiDroidLocal, "deck-b"))
            val outcome = h.coordinator.save(h.base(), draft)
            assertTrue(outcome is NoteMutationOutcome.VerificationRequired)
            assertEquals(listOf(0), h.backend.applyCalls.map { it.stepIndex })
        }
    }

    // ---- conflicts ------------------------------------------------------------------------------

    @Test
    fun noteChangedAfterTheBaseWasReadIsAConflictBeforeAnyWrite() {
        runBlocking {
            val h = Harness()
            val base = h.base()
            h.backend.fields[1] = h.backend.fields[1].copy(value = "changed on desktop")

            val outcome = h.coordinator.save(base, frontEdit("mine"))
            assertTrue(outcome is NoteMutationOutcome.Conflict)
            assertTrue(h.backend.applyCalls.isEmpty())
            assertEquals(NoteMutationStatus.CONFLICT, outcome.recordOrNull()!!.status)
            assertEquals(NoteMutationReason.CONFLICT_BEFORE_WRITE, outcome.recordOrNull()!!.reason)
            assertEquals("changed on desktop", h.backend.fields[1].value)
        }
    }

    @Test
    fun conflictResolutionCreatesANewIdentityThatLinksBack() {
        runBlocking {
            val h = Harness()
            val stale = h.base()
            h.backend.fields[1] = h.backend.fields[1].copy(value = "desktop wins")
            val conflict = h.coordinator.save(stale, frontEdit("mine")) as NoteMutationOutcome.Conflict
            val conflictedId = conflict.record.mutationId

            val fresh = h.base()
            val resolved = h.coordinator.startAfterConflict(conflictedId, fresh, frontEdit("mine"))
            assertTrue("got $resolved", resolved is NoteMutationOutcome.Applied)
            val newRecord = resolved.recordOrNull()!!
            assertNotEquals(conflictedId, newRecord.mutationId)
            assertEquals(conflictedId, newRecord.supersedesMutationId)
            assertEquals("mine", h.backend.fields[0].value)
        }
    }

    @Test
    fun startAfterConflictRefusesWhenTheSourceIsNotAConflict() {
        runBlocking {
            val h = Harness()
            h.backend.applyHook = { _, _ -> NoteMutationBackendResult.ConfirmedNotApplied(AnkiError.PermissionRequired()) }
            val retryable = h.coordinator.save(h.base(), frontEdit()).recordOrNull()!!
            assertEquals(
                NoteMutationOutcome.Refused(NoteMutationRefusal.ConflictNotResolvable(NoteMutationStatus.RETRY_ALLOWED)),
                h.coordinator.startAfterConflict(retryable.mutationId, h.base(), frontEdit())
            )
        }
    }

    // ---- read, overlap and concurrency ----------------------------------------------------------

    @Test
    fun preWriteReadFailureLeavesPreparedAndSendsNothingThenResumes() {
        runBlocking {
            val h = Harness()
            h.backend.readFailure = AnkiError.BackendUnavailable()
            val outcome = h.coordinator.save(h.base(), frontEdit("resume me")) as NoteMutationOutcome.ReadFailed
            assertEquals(NoteMutationStatus.PREPARED, outcome.record.status)
            assertTrue(h.backend.applyCalls.isEmpty())

            // While it is PREPARED, the note is locked to this mutation.
            assertTrue(h.coordinator.save(h.base(), frontEdit("second")) is NoteMutationOutcome.ActiveMutationExists)

            h.backend.readFailure = null
            assertTrue(h.coordinator.resumePrepared(outcome.record.mutationId) is NoteMutationOutcome.Applied)
            assertEquals("resume me", h.backend.fields[0].value)
        }
    }

    @Test
    fun secondEditOfTheSameNoteWhileOneIsActiveIsRefused() {
        runBlocking {
            val h = Harness()
            h.backend.applyHook = { _, _ -> NoteMutationBackendResult.OutcomeUnknown(AnkiError.Unknown("x")) }
            val first = h.coordinator.save(h.base(), frontEdit("one")) as NoteMutationOutcome.VerificationRequired
            val second = h.coordinator.save(h.base(), frontEdit("two"))
            assertEquals(NoteMutationOutcome.ActiveMutationExists(first.record), second)
        }
    }

    @Test
    fun aConcurrentSaveWhileOneIsInFlightIsRefusedNotQueued() {
        runBlocking {
            val h = Harness()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            h.backend.applyHook = { request, backend ->
                entered.complete(Unit)
                release.await()
                backend.applyFaithfully(request.step)
                NoteMutationBackendResult.ConfirmedApplied
            }
            val first = async { h.coordinator.save(h.base(), frontEdit("first")) }
            entered.await()
            val second = h.coordinator.save(h.base(), NoteEditDraft(fieldValues = mapOf(1 to "other")))
            assertEquals(NoteMutationOutcome.Refused(NoteMutationRefusal.SaveInProgress), second)
            release.complete(Unit)
            assertTrue(first.await() is NoteMutationOutcome.Applied)
        }
    }

    @Test
    fun studyWorkStartedBetweenRecordingAndWritingStopsTheWrite() {
        runBlocking {
            val h = Harness()
            // The check passes before the record, then a Study turn starts before the re-read.
            var checks = 0
            val racing = NoteEditSafetyPolicy { _ ->
                checks++
                if (checks >= 2) NoteEditBlock("reviewer_action") else null
            }
            val coordinator = DefaultNoteMutationCoordinator(h.backend, h.ledger, racing, NoteMutationIdSource { NoteMutationId("race") }, nowEpochMs = { 1L })
            val outcome = coordinator.save(h.base(), frontEdit())
            assertEquals(NoteMutationOutcome.Refused(NoteMutationRefusal.StudyActivityOverlaps(NoteEditBlock("reviewer_action"))), outcome)
            assertTrue(h.backend.applyCalls.isEmpty())
        }
    }

    // ---- content and identity -------------------------------------------------------------------

    @Test
    fun persistedMetadataNeverContainsNoteContent() {
        runBlocking {
            val h = Harness()
            val marker = "SECRET-PLAIN-TEXT-MARKER"
            val tagMarker = "tag-marker-xyz"
            h.coordinator.save(h.base(), NoteEditDraft(fieldValues = mapOf(0 to marker), tags = listOf(tagMarker)))
            val snapshot = h.store.encoded!!
            assertFalse(snapshot.contains(marker))
            assertFalse(snapshot.contains(tagMarker))
            assertTrue(snapshot.contains("\"status\":\"APPLIED\""))
            assertEquals(marker, h.backend.fields[0].value)
        }
    }

    @Test
    fun ledgerRejectsAnAppliedRecordReplayRequestEvenIfCalledDirectly() {
        runBlocking {
            val h = Harness()
            val applied = h.coordinator.save(h.base(), frontEdit("x")) as NoteMutationOutcome.Applied
            val direct = h.ledger.apply(applied.record.mutationId, NoteMutationStatus.APPLIED, NoteMutationEvent.RetryRequested)
            assertTrue(direct is NoteLedgerResult.Rejected)
        }
    }

    @Test
    fun coordinatorRefusesWhenAFreshRestartFindsOnlyUnknownRecords() {
        runBlocking {
            val h = Harness()
            h.backend.applyHook = { _, _ -> throw CancellationException("gone") }
            try {
                h.coordinator.save(h.base(), frontEdit())
            } catch (propagated: CancellationException) {
                // expected
            }
            val restarted = h.restarted()
            val outcome = restarted.save(h.base(), frontEdit("again"))
            assertTrue(outcome is NoteMutationOutcome.ActiveMutationExists)
            assertEquals(NoteMutationStatus.AMBIGUOUS, (outcome as NoteMutationOutcome.ActiveMutationExists).record.status)
            assertFalse(outcome.record.status.isTerminal)
        }
    }
}
