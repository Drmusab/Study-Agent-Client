package com.studyagent.client.anki

import com.studyagent.client.anki.fake.InMemoryReviewCommitStore
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** GATE 11 — the durable review-commit ledger (STEP 14-§17, Part VII persistence). */
class ReviewCommitLedgerTest {
    private var now = 1_000L
    private val clock: () -> Long = { now }
    private val backend = AnkiBackendId.AnkiDroidLocal
    private val card = AnkiCardRef(backend, noteId = "42", cardOrd = 0)

    private fun request(turn: String = "t1", rating: Rating = Rating.GOOD, session: String = "s1") =
        CommitRatingRequest(ReviewCommitId(backend, session, ReviewTurnId(turn)), card, rating,
            ratedAtEpochMs = 900L, answerDurationMs = 1_200L)

    private val evidence = ReviewCommitEvidence("test-v1", "reps=3")

    @Test fun `prepare persists NOT_STARTED before returning and is idempotent per commit id`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        val prepared = ledger.prepare(request()) as ReviewCommitLedger.PrepareResult.Prepared
        assertEquals(ReviewCommitState.NOT_STARTED, prepared.record.state)
        assertEquals(0, prepared.record.attemptCount)
        // Durable before prepare returned.
        assertEquals(ReviewCommitState.NOT_STARTED, store.durableRecords().single().state)
        val again = ledger.prepare(request()) as ReviewCommitLedger.PrepareResult.Existing
        assertEquals(prepared.record, again.record)
        assertEquals(1, store.writes.size)
    }

    @Test fun `record carries every identity field and derives nothing from the card id or clock`() = runTest {
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock)
        val record = (ledger.prepare(request()) as ReviewCommitLedger.PrepareResult.Prepared).record
        assertEquals("s1", record.sessionId)
        assertEquals(ReviewTurnId("t1"), record.turnId)
        assertEquals(backend, record.backendId)
        assertEquals(card, record.cardRef)
        assertEquals(Rating.GOOD, record.rating)
        assertEquals(1_000L, record.createdAtEpochMs)
        assertEquals(1_000L, record.updatedAtEpochMs)
        // Same card, next turn = a different transaction.
        val other = request(turn = "t2").commitId
        assertNotEquals(record.commitId, other)
        assertEquals(ReviewCommitId(backend, "s1", ReviewTurnId("t1")), record.commitId)
    }

    @Test fun `a different rating for the same commit id is a conflict never an overwrite`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        ledger.prepare(request(rating = Rating.GOOD))
        val conflict = ledger.prepare(request(rating = Rating.EASY)) as ReviewCommitLedger.PrepareResult.Conflict
        assertEquals(Rating.GOOD, conflict.record.rating)
        assertEquals(Rating.GOOD, store.durableRecords().single().rating)
    }

    @Test fun `claim is an atomic compare and set under real concurrency`() = runTest {
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock)
        ledger.prepare(request())
        val results = List(64) {
            async(Dispatchers.Default) { ledger.claim(request().commitId, evidence, allowRetry = false) }
        }.awaitAll()
        assertEquals(1, results.count { it is ReviewCommitLedger.ClaimResult.Claimed })
        assertEquals(63, results.count { it is ReviewCommitLedger.ClaimResult.InFlight })
        assertEquals(1, ledger.get(request().commitId)!!.attemptCount)
    }

    @Test fun `SUBMITTING is durable before claim reports success`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        ledger.prepare(request())
        now = 2_000L
        val claimed = ledger.claim(request().commitId, evidence, allowRetry = false) as ReviewCommitLedger.ClaimResult.Claimed
        val durable = store.durableRecords().single()
        assertEquals(ReviewCommitState.SUBMITTING, durable.state)
        assertEquals(2_000L, durable.submittedAtEpochMs)
        assertEquals(evidence, durable.evidence)
        assertEquals(claimed.record, durable)
    }

    @Test fun `a failed SUBMITTING write leaves the record NOT_STARTED and reports no claim`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        ledger.prepare(request())
        store.failNextWrites = 1
        assertTrue(ledger.claim(request().commitId, evidence, false) is ReviewCommitLedger.ClaimResult.StoreFailed)
        assertEquals(ReviewCommitState.NOT_STARTED, ledger.get(request().commitId)!!.state)
        assertEquals(ReviewCommitState.NOT_STARTED, store.durableRecords().single().state)
    }

    @Test fun `complete classifies the four results and never collapses FAILED into AMBIGUOUS`() = runTest {
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock)
        val cases = listOf(
            CommitRatingResult.Committed() to ReviewCommitState.COMMITTED,
            CommitRatingResult.RetryableFailure(AnkiError.BackendUnavailable()) to ReviewCommitState.FAILED,
            CommitRatingResult.Rejected(AnkiError.CommitConflict()) to ReviewCommitState.FAILED,
            CommitRatingResult.Ambiguous(AnkiError.Unknown("timeout")) to ReviewCommitState.AMBIGUOUS
        )
        cases.forEachIndexed { i, (result, expected) ->
            val r = request(turn = "t$i")
            ledger.prepare(r)
            ledger.claim(r.commitId, null, false)
            val record = ledger.complete(r.commitId, result)!!
            assertEquals(expected, record.state)
            when (result) {
                is CommitRatingResult.RetryableFailure -> assertTrue(record.safeToRetry)
                is CommitRatingResult.Rejected -> assertFalse(record.safeToRetry)
                is CommitRatingResult.Ambiguous -> assertFalse(record.safeToRetry)
                else -> Unit
            }
        }
    }

    @Test fun `a late or duplicate completion never rewrites a resolved record`() = runTest {
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock)
        ledger.prepare(request())
        ledger.claim(request().commitId, null, false)
        ledger.complete(request().commitId, CommitRatingResult.Committed())
        val late = ledger.complete(request().commitId, CommitRatingResult.Ambiguous())!!
        assertEquals(ReviewCommitState.COMMITTED, late.state)
        val other = request(turn = "t2")
        ledger.prepare(other)
        ledger.claim(other.commitId, null, false)
        ledger.complete(other.commitId, CommitRatingResult.Ambiguous())
        assertEquals(ReviewCommitState.AMBIGUOUS, ledger.complete(other.commitId, CommitRatingResult.Committed())!!.state)
    }

    @Test fun `only an explicit retry of a safe failure may claim again and keeps identity and rating`() = runTest {
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock)
        ledger.prepare(request())
        ledger.claim(request().commitId, evidence, false)
        ledger.complete(request().commitId, CommitRatingResult.RetryableFailure(AnkiError.BackendUnavailable()))
        assertTrue(ledger.claim(request().commitId, null, allowRetry = false) is ReviewCommitLedger.ClaimResult.NotClaimable)
        val retried = ledger.claim(request().commitId, ReviewCommitEvidence("test-v1", "other"), allowRetry = true)
            as ReviewCommitLedger.ClaimResult.Claimed
        assertEquals(2, retried.record.attemptCount)
        assertEquals(Rating.GOOD, retried.record.rating)
        assertEquals("the first baseline is immutable", evidence, retried.record.evidence)
        assertEquals(request().commitId, retried.record.toRequest().commitId)
    }

    @Test fun `AMBIGUOUS and not safe FAILED are never claimable`() = runTest {
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock)
        val a = request(turn = "a")
        ledger.prepare(a); ledger.claim(a.commitId, null, false)
        ledger.complete(a.commitId, CommitRatingResult.Ambiguous())
        assertTrue(ledger.claim(a.commitId, null, allowRetry = true) is ReviewCommitLedger.ClaimResult.NotClaimable)
        val r = request(turn = "r")
        ledger.prepare(r); ledger.claim(r.commitId, null, false)
        ledger.complete(r.commitId, CommitRatingResult.Rejected(AnkiError.CommitConflict()))
        assertTrue(ledger.claim(r.commitId, null, allowRetry = true) is ReviewCommitLedger.ClaimResult.NotClaimable)
    }

    // ---------------------------------------------------------------- process death (Part VII)

    @Test fun `process death while SUBMITTING restores as AMBIGUOUS and persists that`() = runTest {
        val store = InMemoryReviewCommitStore()
        val first = ReviewCommitLedger(store, clock)
        first.prepare(request())
        first.claim(request().commitId, evidence, false)
        // The process dies here: no completion was ever written.
        now = 5_000L
        val restarted = store.restart(clock)
        val record = restarted.get(request().commitId)!!
        assertEquals(ReviewCommitState.AMBIGUOUS, record.state)
        assertEquals(ReviewCommitResolution.PROCESS_RESTART_WHILE_SUBMITTING, record.resolution)
        assertEquals(evidence, record.evidence)
        assertEquals(ReviewCommitState.AMBIGUOUS, store.durableRecords().single().state)
        assertEquals(1, restarted.recoveryReport().interruptedSubmissions)
        assertTrue(restarted.claim(request().commitId, null, allowRetry = true) is ReviewCommitLedger.ClaimResult.NotClaimable)
        assertEquals(listOf(record), restarted.pendingRecovery(backend))
    }

    @Test fun `process death at NOT_STARTED is restorable with the same identity`() = runTest {
        val store = InMemoryReviewCommitStore()
        ReviewCommitLedger(store, clock).prepare(request())
        val restarted = store.restart(clock)
        assertEquals(1, restarted.recoveryReport().restorable)
        val existing = restarted.prepare(request()) as ReviewCommitLedger.PrepareResult.Existing
        assertEquals(ReviewCommitState.NOT_STARTED, existing.record.state)
        assertTrue(restarted.claim(request().commitId, evidence, false) is ReviewCommitLedger.ClaimResult.Claimed)
    }

    @Test fun `process death after COMMITTED never allows a replay`() = runTest {
        val store = InMemoryReviewCommitStore()
        val first = ReviewCommitLedger(store, clock)
        first.prepare(request()); first.claim(request().commitId, null, false)
        first.complete(request().commitId, CommitRatingResult.Committed())
        val restarted = store.restart(clock)
        assertTrue(restarted.claim(request().commitId, null, allowRetry = true) is ReviewCommitLedger.ClaimResult.AlreadyCommitted)
        assertEquals(ReviewCommitState.COMMITTED, (restarted.prepare(request()) as ReviewCommitLedger.PrepareResult.Existing).record.state)
    }

    @Test fun `process death after FAILED keeps the retry decision`() = runTest {
        val store = InMemoryReviewCommitStore()
        val first = ReviewCommitLedger(store, clock)
        first.prepare(request()); first.claim(request().commitId, null, false)
        first.complete(request().commitId, CommitRatingResult.RetryableFailure(AnkiError.BackendUnavailable()))
        val restarted = store.restart(clock)
        assertTrue(restarted.get(request().commitId)!!.safeToRetry)
        assertTrue(restarted.claim(request().commitId, null, allowRetry = true) is ReviewCommitLedger.ClaimResult.Claimed)
    }

    @Test fun `a completion whose write failed is still AMBIGUOUS after restart never COMMITTED`() = runTest {
        val store = InMemoryReviewCommitStore()
        val first = ReviewCommitLedger(store, clock)
        first.prepare(request()); first.claim(request().commitId, null, false)
        store.failNextWrites = 1
        // In memory this process knows the outcome …
        assertEquals(ReviewCommitState.COMMITTED, first.complete(request().commitId, CommitRatingResult.Committed())!!.state)
        assertEquals(ReviewCommitState.COMMITTED, first.get(request().commitId)!!.state)
        // … but durability lagged, so a restart must be conservative.
        assertEquals(ReviewCommitState.AMBIGUOUS, store.restart(clock).get(request().commitId)!!.state)
    }

    @Test fun `reconciliation resolves AMBIGUOUS only on evidence`() = runTest {
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock)
        fun ambiguous(turn: String): CommitRatingRequest = request(turn = turn)
        for (turn in listOf("applied", "not", "still", "unsupported", "down")) {
            val r = ambiguous(turn)
            ledger.prepare(r); ledger.claim(r.commitId, evidence, false)
            ledger.complete(r.commitId, CommitRatingResult.Ambiguous())
        }
        assertEquals(ReviewCommitState.COMMITTED,
            ledger.reconcile(ambiguous("applied").commitId, ReconcileCommitResult.Applied("x"))!!.state)
        val not = ledger.reconcile(ambiguous("not").commitId, ReconcileCommitResult.NotApplied("x", safeToRetry = true))!!
        assertEquals(ReviewCommitState.FAILED, not.state)
        assertTrue(not.safeToRetry)
        assertEquals(ReviewCommitState.AMBIGUOUS,
            ledger.reconcile(ambiguous("still").commitId, ReconcileCommitResult.StillAmbiguous("x"))!!.state)
        assertEquals(ReviewCommitState.AMBIGUOUS,
            ledger.reconcile(ambiguous("unsupported").commitId, ReconcileCommitResult.Unsupported())!!.state)
        assertEquals(ReviewCommitState.AMBIGUOUS,
            ledger.reconcile(ambiguous("down").commitId, ReconcileCommitResult.Unavailable(AnkiError.BackendUnavailable()))!!.state)
    }

    // ---------------------------------------------------------------- retention and fail-closed

    @Test fun `bounded retention prunes resolved records but never unresolved AMBIGUOUS`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock, maxRecords = 12)
        val ambiguous = request(turn = "ambiguous-0")
        ledger.prepare(ambiguous); ledger.claim(ambiguous.commitId, null, false)
        ledger.complete(ambiguous.commitId, CommitRatingResult.Ambiguous())
        repeat(40) { i ->
            val r = request(turn = "c$i")
            now += 1
            ledger.prepare(r); ledger.claim(r.commitId, null, false)
            ledger.complete(r.commitId, CommitRatingResult.Committed())
        }
        val records = ledger.snapshot()
        assertTrue(records.size <= 12)
        assertTrue("an unresolved AMBIGUOUS commit is never dropped", records.any { it.commitId == ambiguous.commitId })
        assertEquals(records.size, store.durableRecords().size)
    }

    @Test fun `a ledger full of unresolved records fails closed instead of evicting`() = runTest {
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock, maxRecords = 10)
        repeat(10) { i ->
            val r = request(turn = "a$i")
            ledger.prepare(r); ledger.claim(r.commitId, null, false)
            ledger.complete(r.commitId, CommitRatingResult.Ambiguous())
        }
        assertEquals(ReviewCommitLedger.PrepareResult.Full, ledger.prepare(request(turn = "new")))
        assertEquals(10, ledger.pendingRecovery().size)
        // Acknowledging does not resolve a commit, it only allows pruning it later.
        val acknowledged = ledger.acknowledge(request(turn = "a0").commitId)!!
        assertEquals(ReviewCommitState.AMBIGUOUS, acknowledged.state)
        assertTrue(ledger.prepare(request(turn = "new")) is ReviewCommitLedger.PrepareResult.Prepared)
    }

    @Test fun `unreadable storage fails closed and is never overwritten with an empty ledger`() = runTest {
        val store = InMemoryReviewCommitStore()
        store.unreadable = true
        val ledger = ReviewCommitLedger(store, clock)
        assertTrue(ledger.health() is ReviewCommitLedger.Health.Unavailable)
        assertTrue(ledger.prepare(request()) is ReviewCommitLedger.PrepareResult.Unavailable)
        assertTrue(ledger.claim(request().commitId, null, false) is ReviewCommitLedger.ClaimResult.Unavailable)
        assertTrue(store.writes.isEmpty())
    }

    @Test fun `corrupt or future snapshots are unreadable not empty`() = runTest {
        for (raw in listOf("{not json", """{"schemaVersion":99,"records":[]}""")) {
            val store = InMemoryReviewCommitStore(raw)
            val ledger = ReviewCommitLedger(store, clock)
            assertTrue(raw, ledger.prepare(request()) is ReviewCommitLedger.PrepareResult.Unavailable)
            assertEquals(raw, store.snapshot)
        }
        // Explicit, user-invoked reset is the only way out, and only while unavailable.
        val store = InMemoryReviewCommitStore("{not json")
        val ledger = ReviewCommitLedger(store, clock)
        assertTrue(ledger.resetUnreadable())
        assertTrue(ledger.prepare(request()) is ReviewCommitLedger.PrepareResult.Prepared)
        assertFalse(ledger.resetUnreadable())
    }

    @Test fun `codec round trips every state and field`() {
        val record = ReviewCommitRecord(
            commitId = request().commitId, card = card, rating = Rating.HARD, state = ReviewCommitState.AMBIGUOUS,
            attemptCount = 2, createdAtEpochMs = 1, updatedAtEpochMs = 2, ratedAtEpochMs = 3, answerDurationMs = 4,
            evidence = evidence, submittedAtEpochMs = 5, resolvedAtEpochMs = 6,
            failure = ReviewCommitFailure("timeout", false), resolution = "backend_ambiguous", acknowledged = true
        )
        val decoded = ReviewCommitLedgerCodec.decode(ReviewCommitLedgerCodec.encode(listOf(record)))
            as ReviewCommitLedgerCodec.Decoded.Records
        assertEquals(listOf(record), decoded.records)
    }

    @Test fun `persisted snapshot contains identifiers and counters only`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        ledger.prepare(request())
        val raw = store.snapshot!!
        assertFalse(raw.contains("question", ignoreCase = true))
        assertFalse(raw.contains("answer\"", ignoreCase = true))
        assertFalse(raw.contains("html", ignoreCase = true))
        assertFalse(raw.contains("transcript", ignoreCase = true))
    }
}
