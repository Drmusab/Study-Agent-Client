package com.studyagent.client.anki.edit

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.AnkiNoteField
import com.studyagent.client.core.anki.AnkiNoteRef
import com.studyagent.client.core.anki.edit.DefaultNoteMutationLedger
import com.studyagent.client.core.anki.edit.NoteEditBase
import com.studyagent.client.core.anki.edit.NoteEditDraft
import com.studyagent.client.core.anki.edit.NoteEditPlanner
import com.studyagent.client.core.anki.edit.NoteLedgerResult
import com.studyagent.client.core.anki.edit.NoteMutationCodec
import com.studyagent.client.core.anki.edit.NoteMutationEvent
import com.studyagent.client.core.anki.edit.NoteMutationId
import com.studyagent.client.core.anki.edit.NoteMutationReason
import com.studyagent.client.core.anki.edit.NoteMutationRecord
import com.studyagent.client.core.anki.edit.NoteMutationStatus
import com.studyagent.client.core.anki.edit.NoteMutationStore
import com.studyagent.client.core.anki.edit.NoteMutationStoreRead
import com.studyagent.client.core.anki.edit.newPreparedNoteMutationRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory durable store with scriptable failures. Shared by the GATE 17 suites. */
class InMemoryNoteMutationStore(private val events: MutableList<String>? = null) : NoteMutationStore {
    var encoded: String? = null
    var readFailure: String? = null
    /** Return true for an encoded snapshot to simulate a failed durable write. */
    var writeFailure: ((String) -> Boolean)? = null
    var writes: Int = 0

    override suspend fun read(): NoteMutationStoreRead {
        readFailure?.let { return NoteMutationStoreRead.Unreadable(it) }
        return NoteMutationStoreRead.Snapshot(encoded)
    }

    override suspend fun write(encoded: String): Boolean {
        if (writeFailure?.invoke(encoded) == true) return false
        this.encoded = encoded
        writes++
        events?.add("persist:[" + (NoteMutationCodec.decode(encoded)?.joinToString(",") { it.status.name } ?: "?") + "]")
        return true
    }
}

class NoteMutationLedgerTest {

    private val backend = AnkiBackendId.AnkiDroidLocal

    private fun base(noteId: String = "note-1"): NoteEditBase = NoteEditBase(
        cardRef = AnkiCardRef(backend, cardId = "c-$noteId", noteId = noteId, cardOrd = 0),
        noteRef = AnkiNoteRef(backend, noteId),
        noteTypeId = "model-1",
        fields = listOf(AnkiNoteField("Front", "f", 0), AnkiNoteField("Back", "b", 1)),
        tags = emptyList(),
        deckRef = AnkiDeckRef(backend, "deck-a")
    )

    private fun prepared(id: String, noteId: String = "note-1", nowMs: Long = 1L): NoteMutationRecord {
        val b = base(noteId)
        val patch = NoteEditPlanner.buildPatch(b, NoteEditDraft(fieldValues = mapOf(1 to "x")))
        return newPreparedNoteMutationRecord(NoteMutationId(id), b, patch, NoteEditPlanner.buildPlan(patch), nowMs)
    }

    @Test
    fun createPersistsPreparedAndRejectsAnythingElse() {
        runBlocking {
            val store = InMemoryNoteMutationStore()
            val ledger = DefaultNoteMutationLedger(store, nowEpochMs = { 5L })
            val record = prepared("m-1")
            assertTrue(ledger.create(record) is NoteLedgerResult.Ok)
            assertEquals(1, store.writes)

            val submitting = record.copy(status = NoteMutationStatus.SUBMITTING)
            val rejected = ledger.create(submitting.copy(mutationId = NoteMutationId("m-2")))
            assertEquals(NoteLedgerResult.Rejected("create_requires_prepared"), rejected)
        }
    }

    @Test
    fun duplicateIdAndSecondActiveMutationForSameNoteAreRejected() {
        runBlocking {
            val ledger = DefaultNoteMutationLedger(InMemoryNoteMutationStore())
            assertTrue(ledger.create(prepared("m-1")) is NoteLedgerResult.Ok)
            assertEquals(NoteLedgerResult.Rejected("duplicate_mutation_id"), ledger.create(prepared("m-1")))
            assertEquals(NoteLedgerResult.Rejected("active_mutation_exists"), ledger.create(prepared("m-2")))
            // A different note is not blocked.
            assertTrue(ledger.create(prepared("m-3", noteId = "note-2")) is NoteLedgerResult.Ok)
        }
    }

    @Test
    fun terminalRecordDoesNotBlockANewMutationForTheSameNote() {
        runBlocking {
            val ledger = DefaultNoteMutationLedger(InMemoryNoteMutationStore())
            ledger.create(prepared("m-1"))
            ledger.apply(NoteMutationId("m-1"), NoteMutationStatus.PREPARED, NoteMutationEvent.PreBoundaryConflict(NoteMutationReason.CONFLICT_BEFORE_WRITE))
            assertTrue(ledger.create(prepared("m-2")) is NoteLedgerResult.Ok)
        }
    }

    @Test
    fun transitionRequiresTheExpectedCurrentStatus() {
        runBlocking {
            val ledger = DefaultNoteMutationLedger(InMemoryNoteMutationStore())
            ledger.create(prepared("m-1"))
            val stale = ledger.apply(NoteMutationId("m-1"), NoteMutationStatus.SUBMITTING, NoteMutationEvent.BackendConfirmedApplied)
            assertEquals(NoteLedgerResult.Rejected("stale_expectation"), stale)
            val unknown = ledger.apply(NoteMutationId("nope"), NoteMutationStatus.PREPARED, NoteMutationEvent.EnterMutationBoundary)
            assertEquals(NoteLedgerResult.Rejected("unknown_mutation"), unknown)
            val illegal = ledger.apply(NoteMutationId("m-1"), NoteMutationStatus.PREPARED, NoteMutationEvent.BackendConfirmedApplied)
            assertEquals(NoteLedgerResult.Rejected("transition_rejected"), illegal)
        }
    }

    @Test
    fun restartTurnsSubmittingIntoAmbiguousAndAbandonsPreparedAndRetryAllowed() {
        runBlocking {
            val store = InMemoryNoteMutationStore()
            val first = DefaultNoteMutationLedger(store)
            first.create(prepared("m-submitting", noteId = "n1"))
            first.apply(NoteMutationId("m-submitting"), NoteMutationStatus.PREPARED, NoteMutationEvent.EnterMutationBoundary)
            first.create(prepared("m-prepared", noteId = "n2"))

            // A new process reads the same durable snapshot.
            val restarted = DefaultNoteMutationLedger(store)
            val submitting = (restarted.get(NoteMutationId("m-submitting")) as NoteLedgerResult.Ok).value!!
            val preparedAgain = (restarted.get(NoteMutationId("m-prepared")) as NoteLedgerResult.Ok).value!!
            assertEquals(NoteMutationStatus.AMBIGUOUS, submitting.status)
            assertEquals(NoteMutationReason.RECOVERED_AFTER_RESTART, submitting.reason)
            assertEquals(NoteMutationStatus.CONFLICT, preparedAgain.status)
            assertEquals(NoteMutationReason.ABANDONED_ON_RESTART, preparedAgain.reason)

            // The normalization itself is durable, so a third process sees the same states.
            val third = DefaultNoteMutationLedger(store)
            assertEquals(
                NoteMutationStatus.AMBIGUOUS,
                (third.get(NoteMutationId("m-submitting")) as NoteLedgerResult.Ok).value!!.status
            )
        }
    }

    @Test
    fun corruptSnapshotFailsClosed() {
        runBlocking {
            val store = InMemoryNoteMutationStore().apply { encoded = "{not json" }
            val ledger = DefaultNoteMutationLedger(store)
            assertEquals(NoteLedgerResult.Unavailable("snapshot_corrupt"), ledger.get(NoteMutationId("m-1")))
            assertTrue(ledger.create(prepared("m-1")) is NoteLedgerResult.Unavailable)
            assertEquals("{not json", store.encoded)
        }
    }

    @Test
    fun unreadableStoreFailsClosed() {
        runBlocking {
            val store = InMemoryNoteMutationStore().apply { readFailure = "io" }
            val ledger = DefaultNoteMutationLedger(store)
            assertTrue(ledger.unresolved() is NoteLedgerResult.Unavailable)
        }
    }

    @Test
    fun failedWriteLeavesDurableStateUnchangedAndNextReadReloadsIt() {
        runBlocking {
            val store = InMemoryNoteMutationStore()
            val ledger = DefaultNoteMutationLedger(store)
            ledger.create(prepared("m-1"))
            store.writeFailure = { it.contains("\"status\":\"SUBMITTING\"") }

            val result = ledger.apply(NoteMutationId("m-1"), NoteMutationStatus.PREPARED, NoteMutationEvent.EnterMutationBoundary)
            assertEquals(NoteLedgerResult.Unavailable("write_not_committed"), result)

            // The durable record is still PREPARED: the in-memory SUBMITTING never escaped.
            store.writeFailure = null
            val reread = (ledger.get(NoteMutationId("m-1")) as NoteLedgerResult.Ok).value!!
            assertEquals(NoteMutationStatus.PREPARED, reread.status)
        }
    }

    @Test
    fun terminalRetentionKeepsAllActiveRecordsAndTheNewest100Terminal() {
        runBlocking {
            val store = InMemoryNoteMutationStore()
            var clock = 1L
            // Strictly increasing clock so "newest" is well defined.
            val ledger = DefaultNoteMutationLedger(store, nowEpochMs = { clock++ })
            for (i in 0 until 105) {
                val id = NoteMutationId("t-$i")
                ledger.create(prepared(id.value, noteId = "n-$i", nowMs = i.toLong()))
                ledger.apply(id, NoteMutationStatus.PREPARED, NoteMutationEvent.PreBoundaryConflict(NoteMutationReason.CONFLICT_BEFORE_WRITE))
            }
            ledger.create(prepared("active", noteId = "n-active"))
            val kept = NoteMutationCodec.decode(store.encoded!!)!!
            assertEquals(101, kept.size)
            assertTrue(kept.any { it.mutationId.value == "active" })
            assertFalse(kept.any { it.mutationId.value == "t-0" })
            assertTrue(kept.any { it.mutationId.value == "t-104" })
        }
    }

    @Test
    fun codecRoundTripsEveryField() {
        val record = prepared("round-trip")
        val decoded = NoteMutationCodec.decode(NoteMutationCodec.encode(listOf(record)))
        assertEquals(listOf(record), decoded)
    }

    @Test
    fun codecRejectsAnUnknownVersion() {
        val text = NoteMutationCodec.encode(listOf(prepared("v")))
            .replace("\"version\":${NoteMutationCodec.VERSION}", "\"version\":999")
        assertEquals(null, NoteMutationCodec.decode(text))
    }

    @Test
    fun persistedSnapshotNeverContainsFieldValues() {
        runBlocking {
            val store = InMemoryNoteMutationStore()
            val ledger = DefaultNoteMutationLedger(store)
            val b = base()
            val marker = "SECRET-NOTE-BODY-MARKER"
            val patch = NoteEditPlanner.buildPatch(b, NoteEditDraft(fieldValues = mapOf(1 to marker)))
            ledger.create(newPreparedNoteMutationRecord(NoteMutationId("m-p"), b, patch, NoteEditPlanner.buildPlan(patch), 1L))
            assertFalse(store.encoded!!.contains(marker))
        }
    }
}
