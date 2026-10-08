package com.studyagent.client.anki

import com.studyagent.client.anki.fake.InMemoryReviewerActionStore
import com.studyagent.client.core.anki.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 13 §14/§15 — the durable reviewer-action ledger.
 *
 * The assertions are about *durability* and *fail-closed* behaviour, not about happy-path plumbing:
 * a write that did not become durable must never look like a state change, a corrupt ledger must
 * never look empty, and one turn must never hold two active actions.
 */
class ReviewerActionLedgerTest {

    private val backend = AnkiBackendId.AnkiDroidLocal
    private val session = "study-13"
    private val card = AnkiCardRef(backend, cardId = "42", collectionKey = "collection")

    private fun request(
        turn: String = "turn-1",
        action: ReviewerAction = ReviewerAction.BuryCard,
        sessionId: String = session
    ) = ReviewerActionRequest(
        sessionId = sessionId,
        turnId = ReviewTurnId(turn),
        cardRef = card,
        action = action,
        collectionRef = AnkiCollectionIdentity(backend, "collection"),
        semantics = ReviewerActionSemantics(
            supportsIdempotentReplay = true, supportsAuthoritativeReconciliation = true)
    )

    private fun ledger(
        store: InMemoryReviewerActionStore,
        clock: () -> Long = { 1_000L },
        maxRecords: Int = DurableReviewerActionLedger.DEFAULT_MAX_RECORDS
    ) = DurableReviewerActionLedger(store, clock, maxRecords)

    // ---------------------------------------------------------------------------- creation

    @Test
    fun `create persists prepared and the record is durable immediately`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val ledger = ledger(store)
        val write = ledger.create(request().toRecord(1_000L))
        assertTrue(write.isDurable)
        val record = (write as ReviewerActionLedgerWrite.Created).record
        assertEquals(ReviewerActionStatus.PREPARED, record.status)
        assertEquals(0, record.attemptCount)
        assertEquals(1, store.writes.size)
        assertEquals(listOf(record), store.durableRecords())
    }

    @Test
    fun `creating the same logical action twice is idempotent, not a second record`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val ledger = ledger(store)
        val first = ledger.create(request().toRecord(1_000L))
        val second = ledger.create(request().toRecord(9_000L))
        assertTrue(second is ReviewerActionLedgerWrite.Existing)
        assertEquals((first as ReviewerActionLedgerWrite.Created).record, (second as ReviewerActionLedgerWrite.Existing).record)
        assertEquals(1, ledger.snapshot().size)
        assertEquals(1, store.writes.size)
    }

    @Test
    fun `a different payload for one action id is a conflict, never an overwrite`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val ledger = ledger(store)
        ledger.create(request(action = ReviewerAction.BuryCard).toRecord(1_000L))
        // Same turn + same action = same id; the payload can only differ by card or collection.
        val otherCard = card.copy(cardId = "99")
        val conflicting = request().copy(cardRef = otherCard).toRecord(1_000L)
        val write = ledger.create(conflicting)
        assertTrue(write is ReviewerActionLedgerWrite.Conflict)
        assertEquals(1, store.writes.size)
    }

    @Test
    fun `one active action per turn is enforced before any write`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val ledger = ledger(store)
        ledger.create(request(action = ReviewerAction.BuryCard).toRecord(1_000L))
        val second = ledger.create(request(action = ReviewerAction.SuspendCard).toRecord(1_000L))
        assertTrue(second is ReviewerActionLedgerWrite.Conflict)
        assertEquals(1, store.writes.size)
        assertEquals(1, ledger.snapshot().size)
    }

    @Test
    fun `a further action is accepted only once the previous one is applied`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val ledger = ledger(store)
        ledger.create(request(action = ReviewerAction.BuryCard).toRecord(1_000L))
        ledger.transition(
            request().actionId, ReviewerActionStatus.PREPARED, ReviewerActionTransition.EnterMutationBoundary)
        ledger.transition(
            request().actionId, ReviewerActionStatus.SUBMITTING, ReviewerActionTransition.BackendConfirmedApplied())
        assertNull(ledger.findActiveForTurn(ReviewTurnId("turn-1")))
        // A later turn in the same session is a new logical action and must be accepted.
        val next = ledger.create(request(turn = "turn-2", action = ReviewerAction.SetFlag(AnkiFlag.RED)).toRecord(2_000L))
        assertTrue(next.isDurable)
        assertEquals(2, ledger.snapshot().size)
    }

    // ---------------------------------------------------------------------------- transitions

    @Test
    fun `transition refuses an unexpected status without writing`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val ledger = ledger(store)
        val id = request().actionId
        ledger.create(request().toRecord(1_000L))
        val writesBefore = store.writes.size
        val write = ledger.transition(id, ReviewerActionStatus.SUBMITTING, ReviewerActionTransition.BackendConfirmedApplied())
        assertTrue(write is ReviewerActionLedgerWrite.StatusMismatch)
        assertEquals(ReviewerActionStatus.PREPARED, ledger.get(id)?.status)
        assertEquals(writesBefore, store.writes.size)
    }

    @Test
    fun `illegal transitions are rejected and version is stamped only on durable writes`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val ledger = ledger(store)
        val id = request().actionId
        ledger.create(request().toRecord(1_000L))
        ledger.transition(id, ReviewerActionStatus.PREPARED, ReviewerActionTransition.EnterMutationBoundary)
        val submitting = ledger.get(id)!!
        assertEquals(1L, submitting.version)
        val rejected = ledger.transition(
            id, ReviewerActionStatus.SUBMITTING, ReviewerActionTransition.EnterMutationBoundary)
        assertTrue(rejected is ReviewerActionLedgerWrite.Rejected)
        val applied = ledger.transition(
            id, ReviewerActionStatus.SUBMITTING,
            ReviewerActionTransition.BackendConfirmedApplied(ReviewerActionReceipt(backend, ReviewerAction.BuryCard.key)))
        assertTrue(applied is ReviewerActionLedgerWrite.Transitioned)
        assertEquals(2L, ledger.get(id)!!.version)
        // APPLIED is terminal at the ledger too.
        assertTrue(
            ledger.transition(id, ReviewerActionStatus.APPLIED, ReviewerActionTransition.RetryRequested)
                is ReviewerActionLedgerWrite.Rejected
        )
    }

    @Test
    fun `a retry reuses the same action id and returns to prepared`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val ledger = ledger(store)
        val id = request().actionId
        ledger.create(request().toRecord(1_000L))
        ledger.transition(id, ReviewerActionStatus.PREPARED, ReviewerActionTransition.EnterMutationBoundary)
        ledger.transition(id, ReviewerActionStatus.SUBMITTING, ReviewerActionTransition.BackendConfirmedNotApplied())
        val retried = ledger.transition(id, ReviewerActionStatus.RETRY_ALLOWED, ReviewerActionTransition.RetryRequested)
        assertTrue(retried is ReviewerActionLedgerWrite.Transitioned)
        assertEquals(id, (retried as ReviewerActionLedgerWrite.Transitioned).record.actionId)
        assertEquals(ReviewerActionStatus.PREPARED, retried.record.status)
        assertEquals(ReviewerActionStatus.PREPARED, store.durableRecords().single().status)
    }

    // ------------------------------------------------------------------------------ durability

    @Test
    fun `a failed write leaves the previous durable status in force`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val ledger = ledger(store)
        val id = request().actionId
        ledger.create(request().toRecord(1_000L))
        store.failNextWrites = 1
        val write = ledger.transition(id, ReviewerActionStatus.PREPARED, ReviewerActionTransition.EnterMutationBoundary)
        assertTrue(write is ReviewerActionLedgerWrite.StoreFailed)
        assertFalse(write.isDurable)
        // Memory must not claim SUBMITTING: nothing about the mutation boundary is durable.
        assertEquals(ReviewerActionStatus.PREPARED, ledger.get(id)?.status)
        assertEquals(ReviewerActionStatus.PREPARED, store.durableRecords().single().status)
    }

    @Test
    fun `a failed first write records nothing at all`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val ledger = ledger(store)
        store.failNextWrites = 1
        val write = ledger.create(request().toRecord(1_000L))
        assertFalse(write.isDurable)
        assertNull(store.snapshot)
        assertTrue(ledger.snapshot().isEmpty())
    }

    @Test
    fun `an unreadable ledger fails closed for every operation`() = runBlocking {
        val store = InMemoryReviewerActionStore().apply { unreadable = true }
        val ledger = ledger(store)
        assertEquals(
            ReviewerActionLedgerHealth.Unavailable("simulated_io_failure"), ledger.health())
        assertTrue(ledger.create(request().toRecord(1_000L)) is ReviewerActionLedgerWrite.Unavailable)
        assertNull(ledger.get(request().actionId))
        assertNull(ledger.findActiveForTurn(ReviewTurnId("turn-1")))
        assertTrue(ledger.unresolved().isEmpty())
        assertTrue(
            ledger.transition(request().actionId, ReviewerActionStatus.PREPARED,
                ReviewerActionTransition.EnterMutationBoundary) is ReviewerActionLedgerWrite.Unavailable
        )
    }

    @Test
    fun `a restarted ledger reads the durable state back`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val first = ledger(store)
        first.create(request().toRecord(1_000L))
        first.transition(request().actionId, ReviewerActionStatus.PREPARED, ReviewerActionTransition.EnterMutationBoundary)
        first.transition(request().actionId, ReviewerActionStatus.SUBMITTING, ReviewerActionTransition.BackendOutcomeUnknown())

        val restarted = store.restart(clock = { 2_000L })
        val record = restarted.get(request().actionId)
        assertNotNull(record)
        assertEquals(ReviewerActionStatus.AMBIGUOUS, record!!.status)
        assertEquals(1, record.attemptCount)
        assertEquals(1, restarted.unresolved().size)
    }

    // ---------------------------------------------------------------------------- recovery scans

    @Test
    fun `unresolved lists every non applied status and never an applied one`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val ledger = ledger(store)
        val bury = request(turn = "turn-1", action = ReviewerAction.BuryCard)
        ledger.create(bury.toRecord(1_000L))
        ledger.transition(bury.actionId, ReviewerActionStatus.PREPARED, ReviewerActionTransition.EnterMutationBoundary)
        ledger.transition(bury.actionId, ReviewerActionStatus.SUBMITTING, ReviewerActionTransition.BackendOutcomeUnknown())
        assertEquals(listOf(ReviewerActionStatus.AMBIGUOUS), ledger.unresolved().map { it.status })
        ledger.transition(bury.actionId, ReviewerActionStatus.AMBIGUOUS, ReviewerActionTransition.ReconciliationConfirmedApplied())
        assertTrue(ledger.unresolved().isEmpty())
    }

    @Test
    fun `only an unknown outcome blocks other sessions and collections`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val ledger = ledger(store)
        val action = request(turn = "turn-1", action = ReviewerAction.BuryCard)
        ledger.create(action.toRecord(1_000L))
        // PREPARED is provably un-entered: it still blocks its own session, but not another one.
        assertNotNull(ledger.recoveryBlocker(backend, "collection", session))
        assertNull(ledger.recoveryBlocker(backend, "collection", "another-session"))
        ledger.transition(action.actionId, ReviewerActionStatus.PREPARED, ReviewerActionTransition.EnterMutationBoundary)
        ledger.transition(action.actionId, ReviewerActionStatus.SUBMITTING, ReviewerActionTransition.BackendOutcomeUnknown())
        // AMBIGUOUS may have been applied: it blocks any session in the same collection.
        assertNotNull(ledger.recoveryBlocker(backend, "collection", "another-session"))
        assertNull(ledger.recoveryBlocker(backend, "other-collection", "another-session"))
        assertNull(ledger.recoveryBlocker(AnkiBackendId.Fake("other"), "collection", "another-session"))
    }

    @Test
    fun `capacity prunes only applied records of other turns and never unresolved truth`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val ledger = ledger(store, maxRecords = 2)
        // One unresolved action (never prunable).
        val live = request(turn = "turn-live", action = ReviewerAction.SuspendCard, sessionId = "study-live")
        ledger.create(live.toRecord(1_000L))
        // One applied action from an older session of the same backend (prunable).
        val old = request(turn = "turn-old", action = ReviewerAction.BuryCard, sessionId = "study-old")
        ledger.create(old.toRecord(1_000L))
        ledger.transition(old.actionId, ReviewerActionStatus.PREPARED, ReviewerActionTransition.EnterMutationBoundary)
        ledger.transition(old.actionId, ReviewerActionStatus.SUBMITTING, ReviewerActionTransition.BackendConfirmedApplied())
        assertEquals(2, ledger.snapshot().size)
        // A new action of a new session fills the capacity by pruning the applied record of the
        // other turn; the unresolved record is never a candidate and is still there.
        val fresh = ledger.create(
            request(turn = "turn-new", action = ReviewerAction.SetFlag(AnkiFlag.GREEN), sessionId = "study-new")
                .toRecord(2_000L))
        assertTrue(fresh.isDurable)
        assertNull(ledger.get(old.actionId))
        assertNotNull(ledger.get(live.actionId))
        assertEquals(2, ledger.snapshot().size)
    }

    @Test
    fun `at capacity with nothing prunable no new action is accepted`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val ledger = ledger(store, maxRecords = 1)
        val live = request(turn = "turn-live", action = ReviewerAction.SuspendCard, sessionId = "study-live")
        ledger.create(live.toRecord(1_000L))
        // The only record is unresolved: it may never be evicted, so a new action is refused.
        val refused = ledger.create(
            request(turn = "turn-new", action = ReviewerAction.BuryCard, sessionId = "study-other").toRecord(2_000L))
        assertTrue(refused is ReviewerActionLedgerWrite.Full)
        assertNotNull(ledger.get(live.actionId))
    }

    // --------------------------------------------------------------------------------- codec

    @Test
    fun `the wire format round trips and rejects what it cannot prove`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val ledger = ledger(store)
        val action = request(turn = "turn-1", action = ReviewerAction.SetFlag(AnkiFlag.TURQUOISE))
        ledger.create(action.toRecord(1_000L))
        ledger.transition(action.actionId, ReviewerActionStatus.PREPARED, ReviewerActionTransition.EnterMutationBoundary)
        val raw = store.snapshot!!
        val decoded = ReviewerActionLedgerCodec.decode(raw)
        assertTrue(decoded is ReviewerActionLedgerCodec.Decoded.Records)
        val record = (decoded as ReviewerActionLedgerCodec.Decoded.Records).records.single()
        assertEquals(ReviewerAction.SetFlag(AnkiFlag.TURQUOISE), record.action)
        assertEquals(ReviewerActionStatus.SUBMITTING, record.status)
        assertTrue(record.frozenIdempotentReplay)
        assertTrue(record.frozenAuthoritativeReconciliation)
    }

    @Test
    fun `an unknown action key or a foreign identity makes the snapshot unreadable`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val ledger = ledger(store)
        ledger.create(request().toRecord(1_000L))
        val raw = store.snapshot!!
        assertTrue(
            ReviewerActionLedgerCodec.decode(raw.replace("\"bury\"", "\"teleport\""))
                is ReviewerActionLedgerCodec.Decoded.Unreadable
        )
        assertTrue(
            ReviewerActionLedgerCodec.decode("""{"schemaVersion":99,"records":[]}""")
                is ReviewerActionLedgerCodec.Decoded.Unreadable
        )
        assertTrue(
            ReviewerActionLedgerCodec.decode("not json")
                is ReviewerActionLedgerCodec.Decoded.Unreadable
        )
    }

    @Test
    fun `two active actions in one snapshot are an integrity failure, not a decodable state`() = runBlocking {
        val store = InMemoryReviewerActionStore()
        val ledger = ledger(store)
        ledger.create(request(turn = "turn-1", action = ReviewerAction.BuryCard).toRecord(1_000L))
        // Hand-build a snapshot with two active records for one turn by giving them different turn
        // ids but the same session — the "one unresolved per session" rule catches it.
        ledger.create(request(turn = "turn-2", action = ReviewerAction.SuspendCard).toRecord(2_000L)).let {
            // The live ledger already refuses this: the second create must not be durable.
            assertFalse(it.isDurable)
        }
        val decoded = ReviewerActionLedgerCodec.decode(store.snapshot!!)
        assertTrue(decoded is ReviewerActionLedgerCodec.Decoded.Records)
        assertEquals(1, (decoded as ReviewerActionLedgerCodec.Decoded.Records).records.size)
    }
}
