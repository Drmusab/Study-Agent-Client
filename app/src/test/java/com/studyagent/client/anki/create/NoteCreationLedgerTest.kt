package com.studyagent.client.anki.create

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.create.CreationLedgerResult
import com.studyagent.client.core.anki.create.CreationMediaKind
import com.studyagent.client.core.anki.create.CreationMediaStep
import com.studyagent.client.core.anki.create.DefaultNoteCreationLedger
import com.studyagent.client.core.anki.create.NoteCreationCodec
import com.studyagent.client.core.anki.create.NoteCreationEvent
import com.studyagent.client.core.anki.create.NoteCreationId
import com.studyagent.client.core.anki.create.NoteCreationReason
import com.studyagent.client.core.anki.create.NoteCreationRecord
import com.studyagent.client.core.anki.create.NoteCreationStatus
import com.studyagent.client.core.anki.create.NoteCreationStore
import com.studyagent.client.core.anki.create.NoteCreationStoreRead
import com.studyagent.client.core.anki.create.newPreparedNoteCreationRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory durable store with scriptable failures. */
class InMemoryNoteCreationStore(private val events: MutableList<String>? = null) : NoteCreationStore {
    var encoded: String? = null
    var readFailure: String? = null
    var writeFailure: ((String) -> Boolean)? = null
    var writes: Int = 0

    override suspend fun read(): NoteCreationStoreRead {
        readFailure?.let { return NoteCreationStoreRead.Unreadable(it) }
        return NoteCreationStoreRead.Snapshot(encoded)
    }

    override suspend fun write(encoded: String): Boolean {
        if (writeFailure?.invoke(encoded) == true) return false
        this.encoded = encoded
        writes++
        events?.add(
            "persist:[" + (NoteCreationCodec.decode(encoded)?.joinToString(",") { it.status.name } ?: "?") + "]"
        )
        return true
    }
}

/**
 * GATE 18 — the durable creation ledger (CONTRACT-18-15/41). Own file, own statuses, own restart
 * rule, metadata only, fail closed on corruption.
 */
class NoteCreationLedgerTest {

    private val backend = AnkiBackendId.AnkiDroidLocal

    private fun prepared(
        id: String,
        mediaSteps: List<CreationMediaStep> = emptyList(),
        nowMs: Long = 1L
    ): NoteCreationRecord = newPreparedNoteCreationRecord(
        creationId = NoteCreationId(id),
        backendId = backend,
        collectionKey = null,
        modelId = "model-basic",
        modelName = "Basic",
        fieldCount = 2,
        tagCount = 0,
        mediaSteps = mediaSteps,
        nowEpochMs = nowMs
    )

    private fun creating(id: String): NoteCreationRecord =
        prepared(id).copy(
            status = NoteCreationStatus.CREATING_NOTE,
            lastEnteredStep = 0,
            attemptCount = 1
        )

    /** A record can only enter the ledger as PREPARED; advance it through the locked table. */
    private suspend fun createAndEnterNoteBoundary(
        ledger: com.studyagent.client.core.anki.create.NoteCreationLedger,
        id: String
    ) {
        assertTrue(ledger.create(prepared(id)) is CreationLedgerResult.Ok)
        assertTrue(
            ledger.apply(NoteCreationId(id), NoteCreationStatus.PREPARED, NoteCreationEvent.EnterNoteBoundary)
                is CreationLedgerResult.Ok
        )
    }

    private fun ok(result: CreationLedgerResult<NoteCreationRecord>): NoteCreationRecord =
        (result as CreationLedgerResult.Ok).value

    private fun found(result: CreationLedgerResult<NoteCreationRecord?>): NoteCreationRecord =
        (result as CreationLedgerResult.Ok).value!!

    @Test
    fun `create persists a prepared record and rejects duplicates and non-prepared`() = runBlocking {
        val store = InMemoryNoteCreationStore()
        val ledger = DefaultNoteCreationLedger(store)
        val record = prepared("c-1")
        assertTrue(ledger.create(record) is CreationLedgerResult.Ok)
        assertTrue(store.encoded!!.contains("PREPARED"))
        assertTrue(ledger.create(record) is CreationLedgerResult.Rejected)

        val creating = creating("c-2")
        assertTrue(ledger.create(creating) is CreationLedgerResult.Rejected)
    }

    @Test
    fun `apply is compare-and-set on the expected status`() = runBlocking {
        val ledger = DefaultNoteCreationLedger(InMemoryNoteCreationStore())
        createAndEnterNoteBoundary(ledger, "c-1")
        // Correct expectation advances.
        val done = ledger.apply(
            NoteCreationId("c-1"), NoteCreationStatus.CREATING_NOTE,
            NoteCreationEvent.BackendConfirmedCreated("77")
        )
        assertEquals(NoteCreationStatus.CREATED, ok(done).status)
        // A stale expectation refuses, never overwrites.
        assertTrue(
            ledger.apply(
                NoteCreationId("c-1"), NoteCreationStatus.CREATING_NOTE,
                NoteCreationEvent.BackendOutcomeUnknown
            ) is CreationLedgerResult.Rejected
        )
        // An unknown id refuses.
        assertTrue(
            ledger.apply(
                NoteCreationId("nope"), NoteCreationStatus.CREATING_NOTE,
                NoteCreationEvent.BackendOutcomeUnknown
            ) is CreationLedgerResult.Rejected
        )
    }

    @Test
    fun `restart normalization closes an unclosed note boundary as ambiguous`() = runBlocking {
        val store = InMemoryNoteCreationStore()
        val first = DefaultNoteCreationLedger(store)
        createAndEnterNoteBoundary(first, "c-1")

        // A new process (new ledger over the same store) must see AMBIGUOUS, durably.
        val second = DefaultNoteCreationLedger(store)
        val record = found(second.get(NoteCreationId("c-1"))!!)
        assertEquals(NoteCreationStatus.AMBIGUOUS, record.status)
        assertEquals(NoteCreationReason.RECOVERED_AFTER_RESTART, record.reason)
        // The normalization was persisted, so a third load does not re-decide anything.
        val third = DefaultNoteCreationLedger(store)
        assertEquals(NoteCreationStatus.AMBIGUOUS, found(third.get(NoteCreationId("c-1"))!!).status)
    }

    @Test
    fun `restart normalization abandons pre-note-boundary records`() = runBlocking {
        val store = InMemoryNoteCreationStore()
        val first = DefaultNoteCreationLedger(store)
        first.create(prepared("c-prepared"))
        val media = prepared(
            "c-media",
            mediaSteps = listOf(CreationMediaStep(0, "a.png", CreationMediaKind.IMAGE, "a.png"))
        ).copy(status = NoteCreationStatus.STORING_MEDIA, lastEnteredStep = 0)
        first.create(prepared("tmp"))
        first.apply(NoteCreationId("tmp"), NoteCreationStatus.PREPARED, NoteCreationEvent.RestartAbandoned)
        // Manually store a STORING_MEDIA record via the codec to simulate process death mid-media.
        val snapshot = NoteCreationCodec.decode(store.encoded!!)!!.filter { it.creationId.value != "tmp" } + media
        store.encoded = NoteCreationCodec.encode(snapshot)

        val second = DefaultNoteCreationLedger(store)
        assertEquals(NoteCreationStatus.ABANDONED, found(second.get(NoteCreationId("c-prepared"))!!).status)
        assertEquals(
            NoteCreationReason.ABANDONED_ON_RESTART,
            found(second.get(NoteCreationId("c-prepared"))!!).reason
        )
        val abandonedMedia = found(second.get(NoteCreationId("c-media"))!!)
        assertEquals(NoteCreationStatus.ABANDONED, abandonedMedia.status)
        // The orphan-media note is still visible from the record itself.
        assertTrue(abandonedMedia.mediaBoundaryEntered)
    }

    @Test
    fun `created and ambiguous records survive restart untouched`() = runBlocking {
        val store = InMemoryNoteCreationStore()
        val first = DefaultNoteCreationLedger(store)
        createAndEnterNoteBoundary(first, "c-created")
        first.apply(
            NoteCreationId("c-created"), NoteCreationStatus.CREATING_NOTE,
            NoteCreationEvent.BackendConfirmedCreated("42")
        )
        createAndEnterNoteBoundary(first, "c-amb")
        first.apply(
            NoteCreationId("c-amb"), NoteCreationStatus.CREATING_NOTE,
            NoteCreationEvent.BackendOutcomeUnknown
        )

        val second = DefaultNoteCreationLedger(store)
        assertEquals(NoteCreationStatus.CREATED, found(second.get(NoteCreationId("c-created"))!!).status)
        assertEquals("42", found(second.get(NoteCreationId("c-created"))!!).createdNoteId)
        assertEquals(NoteCreationStatus.AMBIGUOUS, found(second.get(NoteCreationId("c-amb"))!!).status)
    }

    @Test
    fun `a corrupt snapshot fails closed`() = runBlocking {
        val store = InMemoryNoteCreationStore()
        store.encoded = "{not the codec"
        val ledger = DefaultNoteCreationLedger(store)
        assertTrue(ledger.get(NoteCreationId("x")) is CreationLedgerResult.Unavailable)
        assertTrue(ledger.create(prepared("y")) is CreationLedgerResult.Unavailable)
    }

    @Test
    fun `an unreadable store fails closed`() = runBlocking {
        val store = InMemoryNoteCreationStore()
        store.readFailure = "io"
        val ledger = DefaultNoteCreationLedger(store)
        assertTrue(ledger.unresolved() is CreationLedgerResult.Unavailable)
    }

    @Test
    fun `a failed durable write reports unavailable and reloads truth next time`() = runBlocking {
        val store = InMemoryNoteCreationStore()
        val ledger = DefaultNoteCreationLedger(store)
        store.writeFailure = { true }
        assertTrue(ledger.create(prepared("c-1")) is CreationLedgerResult.Unavailable)
        store.writeFailure = null
        // The cache was dropped: the next call re-reads the store (still empty) and succeeds.
        assertTrue(ledger.create(prepared("c-1")) is CreationLedgerResult.Ok)
    }

    @Test
    fun `unresolved and activeFor views follow the locked definitions`() = runBlocking {
        val ledger = DefaultNoteCreationLedger(InMemoryNoteCreationStore())
        createAndEnterNoteBoundary(ledger, "c-open")
        ledger.create(prepared("c-prepared"))
        createAndEnterNoteBoundary(ledger, "c-done")
        ledger.apply(
            NoteCreationId("c-done"), NoteCreationStatus.CREATING_NOTE,
            NoteCreationEvent.BackendConfirmedCreated("1")
        )

        val unresolved = (ledger.unresolved() as CreationLedgerResult.Ok).value
        assertEquals(listOf("c-open"), unresolved.map { it.creationId.value })

        val active = (ledger.activeFor(backend) as CreationLedgerResult.Ok).value
        assertEquals(setOf("c-open", "c-prepared"), active.map { it.creationId.value }.toSet())
        assertTrue((ledger.activeFor(AnkiBackendId.Fake("x")) as CreationLedgerResult.Ok).value.isEmpty())
    }

    @Test
    fun `terminal records are retained with a cap`() = runBlocking {
        val store = InMemoryNoteCreationStore()
        var clock = 0L
        val ledgerWithClock = DefaultNoteCreationLedger(store) { clock }
        repeat(DefaultNoteCreationLedger.TERMINAL_RETENTION + 25) { index ->
            clock += 1
            ledgerWithClock.create(prepared("r-$index"))
            ledgerWithClock.apply(
                NoteCreationId("r-$index"), NoteCreationStatus.PREPARED,
                NoteCreationEvent.EnterNoteBoundary
            )
            ledgerWithClock.apply(
                NoteCreationId("r-$index"), NoteCreationStatus.CREATING_NOTE,
                NoteCreationEvent.BackendConfirmedCreated("n$index")
            )
        }
        val snapshot = NoteCreationCodec.decode(store.encoded!!)!!
        assertEquals(DefaultNoteCreationLedger.TERMINAL_RETENTION, snapshot.size)
        // The newest survive, the oldest are dropped.
        assertTrue(snapshot.any { it.creationId.value == "r-${DefaultNoteCreationLedger.TERMINAL_RETENTION + 24}" })
        assertTrue(snapshot.none { it.creationId.value == "r-0" })
        // A fresh ledger over the same store confirms the pruned view (r-0 is gone, resolved as null).
        val reread = DefaultNoteCreationLedger(store)
        assertEquals(null, (reread.get(NoteCreationId("r-0")) as CreationLedgerResult.Ok).value)
    }

    @Test
    fun `the snapshot carries metadata only`() = runBlocking {
        val store = InMemoryNoteCreationStore()
        val ledger = DefaultNoteCreationLedger(store)
        ledger.create(
            prepared(
                "c-1",
                mediaSteps = listOf(CreationMediaStep(0, "photo.png", CreationMediaKind.IMAGE))
            ).copy(status = NoteCreationStatus.PREPARED)
        )
        val encoded = store.encoded!!
        // Identities and counts only: no field values, tag text, URIs or content may be persisted.
        assertTrue(encoded.contains("c-1"))
        assertTrue(encoded.contains("photo.png"))
        assertTrue(!encoded.contains("content://"))
        // The record type itself carries no content-bearing member.
        val memberNames = NoteCreationRecord::class.java.declaredFields.map { it.name }
        for (forbidden in listOf(
            "fieldValues", "fields", "tags", "tagText", "uri", "content", "contentUri", "rendered"
        )) {
            assertTrue(
                "NoteCreationRecord must not carry '$forbidden'",
                memberNames.none { it == forbidden }
            )
        }
    }

    @Test
    fun `the codec rejects other versions and unknown keys`() {
        val record = prepared("c-1")
        val good = NoteCreationCodec.encode(listOf(record))
        assertEquals(listOf(record), NoteCreationCodec.decode(good))
        assertNull(NoteCreationCodec.decode(good.replace("\"version\":1", "\"version\":2")))
        assertNull(NoteCreationCodec.decode(good.replaceFirst("\"creationId\"", "\"creationIdX\"")))
        assertNull(NoteCreationCodec.decode(""))
    }
}
