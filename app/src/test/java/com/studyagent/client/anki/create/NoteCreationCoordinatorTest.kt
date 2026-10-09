package com.studyagent.client.anki.create

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiResult
import com.studyagent.client.core.anki.create.AddNoteDraft
import com.studyagent.client.core.anki.create.CreationLedgerResult
import com.studyagent.client.core.anki.create.CreationMediaKind
import com.studyagent.client.core.anki.create.DefaultNoteCreationCoordinator
import com.studyagent.client.core.anki.create.NoteCreationCodec
import com.studyagent.client.core.anki.create.MediaStoreBackendResult
import com.studyagent.client.core.anki.create.NoteCreationAttestation
import com.studyagent.client.core.anki.create.NoteCreationOutcome
import com.studyagent.client.core.anki.create.NoteCreationRecoveryOutcome
import com.studyagent.client.core.anki.create.NoteCreationRefusal
import com.studyagent.client.core.anki.create.NoteCreationStatus
import com.studyagent.client.core.anki.create.PendingMedia
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 18 — the creation coordinator. These tests pin the locked ordering (validate → PREPARED →
 * media boundaries → schema re-read → note boundary → CREATED → hydrate), the fail-closed branches,
 * the retry policy and the one-call-at-a-time guarantee (docs/GATE_18 §15, VER-18-13).
 */
class NoteCreationCoordinatorTest {

    private val backendId = AnkiBackendId.AnkiDroidLocal

    private class Harness(events: MutableList<String> = mutableListOf()) {
        val backend = FakeNoteCreationBackend(events = events)
        val store = InMemoryNoteCreationStore(events = events)
        val ledger = com.studyagent.client.core.anki.create.DefaultNoteCreationLedger(store)
        val coordinator = DefaultNoteCreationCoordinator(backend = backend, ledger = ledger)
        val model get() = backend.models.first()
    }

    private fun Harness.draft(
        values: Map<Int, String> = mapOf(0 to "front", 1 to "back"),
        tags: List<String> = listOf("alpha"),
        media: List<PendingMedia> = emptyList()
    ) = AddNoteDraft(
        backendId = backendId,
        collectionKey = null,
        model = model,
        fieldValues = values,
        tags = tags,
        media = media
    )

    private fun image(target: Int = 1) = PendingMedia(
        contentUri = "content://picker/img",
        sourceName = "diagram.png",
        mimeType = "image/png",
        extension = "png",
        sizeBytes = 2048,
        kind = CreationMediaKind.IMAGE,
        targetFieldOrdinal = target
    )

    private fun sound(target: Int = 0) = PendingMedia(
        contentUri = "content://picker/snd",
        sourceName = "clip.mp3",
        mimeType = "audio/mpeg",
        extension = "mp3",
        sizeBytes = 1024,
        kind = CreationMediaKind.AUDIO,
        targetFieldOrdinal = target
    )

    // ---------------------------------------------------------------- happy paths

    @Test
    fun `a plain note creation follows the locked ordering end to end`() = runBlocking {
        val harness = Harness()
        val outcome = harness.coordinator.create(harness.draft())
        val created = outcome as NoteCreationOutcome.Created
        // Hydration truth, not the request: the fields come from the backend's stored note.
        assertEquals(listOf("front", "back"), created.created.fields)
        assertEquals(listOf("alpha"), created.created.tags)
        assertEquals(1, created.created.cards.size)
        // Durable record is CREATED with the backend's note id.
        assertEquals(NoteCreationStatus.CREATED, created.record.status)
        assertEquals(created.created.noteRef.noteId, created.record.createdNoteId)

        val order = harness.backend.events
        assertEquals("backend:models", order[0]) // preflight listing
        assertTrue(order.indexOf("backend:create") > order.indexOf("backend:models"))
        assertTrue(order.indexOf("backend:hydrate:${created.created.noteRef.noteId}") > order.indexOf("backend:create"))
        // Exactly one create dispatch.
        assertEquals(1, harness.backend.createNoteCalls.size)
    }

    @Test
    fun `media is stored before the note and fields reference only stored names`() = runBlocking {
        val harness = Harness()
        val outcome = harness.coordinator.create(harness.draft(media = listOf(image(target = 1), sound(target = 0))))
        val created = outcome as NoteCreationOutcome.Created
        // The fake stores under the requested name; the fields must carry exactly those names.
        // The image targets field 1, the sound targets field 0 — placement follows the targets.
        assertEquals(listOf("front<br>[sound:clip.mp3]", "back<br><img src=\"diagram.png\">"),
            created.created.fields)
        // Ordering: both media stores strictly before the single note insert.
        val order = harness.backend.events
        val firstMedia = order.indexOf("backend:media:diagram.png")
        val secondMedia = order.indexOf("backend:media:clip.mp3")
        val create = order.indexOf("backend:create")
        assertTrue(firstMedia in 0 until secondMedia)
        assertTrue(secondMedia < create)
        assertEquals(1, harness.backend.createNoteCalls.size)
        // Stored names are durable on the record.
        assertEquals(listOf("diagram.png", "clip.mp3"), created.record.storedMediaNames)
    }

    // ---------------------------------------------------------------- preflight refusals

    @Test
    fun `preflight refuses a foreign backend before any record`() = runBlocking {
        val harness = Harness()
        // The draft requires the model's backend to equal the draft's backend; build accordingly.
        val outcome = harness.coordinator.create(
            AddNoteDraft(
                backendId = AnkiBackendId.Fake("other"),
                collectionKey = null,
                model = FakeNoteCreationBackend.basicModel(backendId = AnkiBackendId.Fake("other")),
                fieldValues = mapOf(0 to "a", 1 to "b"),
                tags = emptyList(),
                media = emptyList()
            )
        )
        assertEquals(
            NoteCreationRefusal.BackendMismatch,
            (outcome as NoteCreationOutcome.Refused).reason
        )
        assertTrue(harness.store.encoded == null)
        assertEquals(0, harness.backend.createNoteCalls.size)
    }

    @Test
    fun `preflight refuses missing capabilities and never writes`() = runBlocking {
        val harness = Harness()
        harness.backend.caps.value = harness.backend.caps.value.copy(createNotes = false)
        val outcome = harness.coordinator.create(harness.draft())
        assertTrue(outcome is NoteCreationOutcome.Refused)
        assertNull(harness.store.encoded)
    }

    @Test
    fun `validation failures are reported and nothing is recorded`() = runBlocking {
        val harness = Harness()
        val outcome = harness.coordinator.create(harness.draft(values = mapOf(0 to "", 1 to "back")))
        assertTrue((outcome as NoteCreationOutcome.ValidationFailed).issues.contains("first_field_empty"))
        assertNull(harness.store.encoded)
        assertEquals(0, harness.backend.createNoteCalls.size)
    }

    @Test
    fun `a drifted schema is refused pre-boundary after the durable record is abandoned`() = runBlocking {
        val harness = Harness()
        // Freeze the draft on the ORIGINAL schema, then drift the collection between the
        // preflight listing and the pre-boundary re-read.
        val draft = harness.draft()
        harness.backend.modelListingResults += AnkiResult.Success(harness.backend.models.toList())
        harness.backend.models[0] = harness.model.copy(
            fields = harness.model.fields.map { it.copy(name = it.name + "!") }
        )
        val outcome = harness.coordinator.create(draft)
        assertEquals(NoteCreationRefusal.SchemaDrifted, (outcome as NoteCreationOutcome.Refused).reason)
        assertEquals(0, harness.backend.createNoteCalls.size)
        // The record exists (it was PREPARED durably) and is ABANDONED pre-boundary.
        val record = (harness.ledger.unresolved() as CreationLedgerResult.Ok).value
        assertTrue(record.isEmpty())
        val stored = NoteCreationCodec.decode(harness.store.encoded!!)!!
        assertEquals(NoteCreationStatus.ABANDONED, stored.single().status)
        assertEquals(com.studyagent.client.core.anki.create.NoteCreationReason.PRE_BOUNDARY_REFUSED,
            stored.single().reason)
    }

    // ---------------------------------------------------------------- failure branches

    @Test
    fun `an unknown media outcome abandons before the note boundary`() = runBlocking {
        val harness = Harness()
        harness.backend.mediaStoreResults += MediaStoreBackendResult.OutcomeUnknown(
            AnkiError.QueryFailure("media_null")
        )
        val outcome = harness.coordinator.create(harness.draft(media = listOf(image())))
        val unclear = outcome as NoteCreationOutcome.MediaOutcomeUnclear
        assertEquals(NoteCreationStatus.ABANDONED, unclear.record.status)
        // No note boundary was ever entered.
        assertFalse(unclear.record.noteBoundaryEntered)
        assertEquals(0, harness.backend.createNoteCalls.size)
    }

    @Test
    fun `a confirmed not-stored media step is retryable and sends no note`() = runBlocking {
        val harness = Harness()
        harness.backend.mediaStoreResults += MediaStoreBackendResult.ConfirmedNotStored(
            AnkiError.MediaRejected("empty_file")
        )
        val outcome = harness.coordinator.create(harness.draft(media = listOf(image())))
        assertTrue(outcome is NoteCreationOutcome.RetryAvailable)
        assertEquals(NoteCreationStatus.RETRY_ALLOWED, (outcome as NoteCreationOutcome.RetryAvailable).record.status)
        assertEquals(0, harness.backend.createNoteCalls.size)
    }

    @Test
    fun `an unknown note outcome is ambiguous and never retried`() = runBlocking {
        val harness = Harness()
        harness.backend.failCreateWithUnknown = true
        val outcome = harness.coordinator.create(harness.draft())
        val ambiguous = outcome as NoteCreationOutcome.AmbiguousCreation
        assertEquals(NoteCreationStatus.AMBIGUOUS, ambiguous.record.status)
        // Retry is refused for AMBIGUOUS: only attestation can close it.
        val retry = harness.coordinator.retry(ambiguous.record.creationId)
        assertTrue(retry is NoteCreationOutcome.Refused)
        assertTrue((retry as NoteCreationOutcome.Refused).reason is NoteCreationRefusal.RetryNotAllowed)
        // Still exactly one dispatch: ambiguity never produces a second write.
        assertEquals(1, harness.backend.createNoteCalls.size)
    }

    @Test
    fun `a confirmed non-creation is retryable under the same id`() = runBlocking {
        val harness = Harness()
        harness.backend.createNoteResults += com.studyagent.client.core.anki.create.CreateNoteBackendResult
            .ConfirmedNotCreated(AnkiError.InvalidRequest("field_count_mismatch"))
        val first = harness.coordinator.create(harness.draft())
        val retryable = first as NoteCreationOutcome.RetryAvailable
        assertEquals(NoteCreationStatus.RETRY_ALLOWED, retryable.record.status)
        assertEquals(0, harness.backend.storedNotes.size)

        // The retry re-runs the full pipeline with the SAME creation id and succeeds.
        val second = harness.coordinator.retry(retryable.record.creationId)
        val created = second as NoteCreationOutcome.Created
        assertEquals(retryable.record.creationId, created.record.creationId)
        assertEquals(2, created.record.attemptCount)
        assertEquals(2, harness.backend.createNoteCalls.size)
        assertEquals(1, harness.backend.storedNotes.size)
    }

    @Test
    fun `a retry revalidates and rereads the schema freshly`() = runBlocking {
        val harness = Harness()
        harness.backend.createNoteResults += com.studyagent.client.core.anki.create.CreateNoteBackendResult
            .ConfirmedNotCreated(AnkiError.InvalidRequest("x"))
        val first = harness.coordinator.create(harness.draft()) as NoteCreationOutcome.RetryAvailable

        // Drift the schema before the retry: the retry must refuse, not remap.
        harness.backend.models[0] = harness.model.copy(
            fields = harness.model.fields + com.studyagent.client.core.anki.AnkiNoteModelField(2, "Extra")
        )
        val second = harness.coordinator.retry(first.record.creationId)
        assertEquals(NoteCreationRefusal.SchemaDrifted, (second as NoteCreationOutcome.Refused).reason)
        assertEquals(1, harness.backend.createNoteCalls.size) // no second dispatch
    }

    @Test
    fun `a hydration failure keeps the creation created and can be re-read later`() = runBlocking {
        val harness = Harness()
        harness.backend.hydrationFailures += AnkiError.QueryFailure("read_died")
        val outcome = harness.coordinator.create(harness.draft())
        val failed = outcome as NoteCreationOutcome.CreatedHydrationFailed
        assertEquals(NoteCreationStatus.CREATED, failed.record.status)
        assertEquals(
            com.studyagent.client.core.anki.create.NoteCreationReason.HYDRATION_FAILED,
            (harness.ledger.get(failed.record.creationId) as CreationLedgerResult.Ok).value!!.reason
        )
        // Re-reading later succeeds without any second creation dispatch.
        val reread = harness.coordinator.hydrate(failed.record.creationId)
        assertTrue(reread is NoteCreationRecoveryOutcome.Hydrated)
        assertEquals(1, harness.backend.createNoteCalls.size)
    }

    @Test
    fun `finalization failure after confirmation fails closed to ambiguous`() = runBlocking {
        val harness = Harness()
        // The durable CREATED write fails; everything else persists.
        harness.store.writeFailure = { encoded -> encoded.contains("\"CREATED\"") }
        val outcome = harness.coordinator.create(harness.draft())
        val ambiguous = outcome as NoteCreationOutcome.AmbiguousCreation
        assertEquals(NoteCreationStatus.AMBIGUOUS, ambiguous.record.status)
        // The backend did create the note exactly once.
        assertEquals(1, harness.backend.createNoteCalls.size)
        assertEquals(1, harness.backend.storedNotes.size)
    }

    @Test
    fun `ledger unavailability refuses before any backend dispatch`() = runBlocking {
        val harness = Harness()
        harness.store.writeFailure = { true }
        val outcome = harness.coordinator.create(harness.draft())
        assertTrue(outcome is NoteCreationOutcome.LedgerUnavailable)
        assertEquals(0, harness.backend.createNoteCalls.size)
        assertEquals(0, harness.backend.mediaStoreCalls.size)
    }

    // ---------------------------------------------------------------- concurrency + recovery

    @Test
    fun `a second create while one runs is refused, never queued`() = runBlocking {
        val harness = Harness()
        harness.backend.beforeCreate = { delay(80) }
        val first = async { harness.coordinator.create(harness.draft()) }
        delay(20) // let the first call take the execution gate
        val second = harness.coordinator.create(harness.draft())
        assertTrue(second is NoteCreationOutcome.CreationInProgress)
        assertTrue(first.await() is NoteCreationOutcome.Created)
        assertEquals(1, harness.backend.createNoteCalls.size)
    }

    @Test
    fun `attestation closes an ambiguous record exactly as the human concluded`() = runBlocking {
        val harness = Harness()
        harness.backend.failCreateWithUnknown = true
        val ambiguous = harness.coordinator.create(harness.draft()) as NoteCreationOutcome.AmbiguousCreation

        val attested = harness.coordinator.resolveAmbiguous(
            ambiguous.record.creationId, NoteCreationAttestation.ABSENT_FROM_COLLECTION
        )
        val resolved = attested as NoteCreationRecoveryOutcome.Resolved
        assertEquals(NoteCreationStatus.ABANDONED, resolved.record.status)
        assertEquals(
            com.studyagent.client.core.anki.create.NoteCreationReason.USER_ATTESTED_ABSENT,
            resolved.record.reason
        )
        // Attestation never dispatches anything.
        assertEquals(1, harness.backend.createNoteCalls.size)
    }

    @Test
    fun `recovery views list unresolved and active records per backend`() = runBlocking {
        val harness = Harness()
        harness.backend.failCreateWithUnknown = true
        harness.coordinator.create(harness.draft())

        assertEquals(1, harness.coordinator.unresolvedCreations().size)
        assertEquals(1, harness.coordinator.activeCreationsFor(backendId).size)
        assertTrue(harness.coordinator.activeCreationsFor(AnkiBackendId.Fake("x")).isEmpty())
    }
}
