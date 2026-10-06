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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Controlled process-death windows: the fake store is the disk, not a mock of in-memory state. */
class ReviewCommitLedgerTest {
    private var now = 1_000L
    private val clock: () -> Long = { now }
    private val backend = AnkiBackendId.AnkiDroidLocal
    private val deck = AnkiDeckRef(backend, "1", "collection")
    private val card = AnkiCardRef(backend, noteId = "42", cardOrd = 0, collectionKey = "collection")
    private val evidence = ReviewCommitEvidence("test-v1", "counter=3")

    private fun request(turn: String = "t1", rating: Rating = Rating.GOOD, session: String = "s1") =
        CommitRatingRequest(ReviewCommitId(backend, session, ReviewTurnId(turn)), card, rating,
            ratedAtEpochMs = 900L, answerDurationMs = 1_200L, deckRef = deck,
            collectionRef = AnkiCollectionIdentity(backend, "collection"))

    private suspend fun enter(ledger: ReviewCommitLedger, request: CommitRatingRequest = request()) {
        assertTrue(ledger.prepare(request) is ReviewCommitLedger.PrepareResult.Prepared)
        assertTrue(ledger.claim(request.commitId, evidence, allowRetry = false) is ReviewCommitLedger.ClaimResult.Claimed)
        assertEquals(CommitAttemptPhase.MUTATION_CALL_ENTERED, ledger.markMutationEntered(request.commitId)?.phase)
    }

    private suspend fun finish(ledger: ReviewCommitLedger, request: CommitRatingRequest, result: CommitRatingResult): ReviewCommitRecord {
        assertEquals(CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED,
            ledger.markResponseReceived(request.commitId, result)?.phase)
        return ledger.complete(request.commitId, result)!!
    }

    @Test fun `prepare and claim persist distinct intent and phase before mutation`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        val r = request()
        val prepared = ledger.prepare(r) as ReviewCommitLedger.PrepareResult.Prepared
        assertEquals(ReviewCommitState.NOT_STARTED, store.durableRecords().single().state)
        assertNull(prepared.record.phase)
        assertEquals(prepared.record, (ledger.prepare(r) as ReviewCommitLedger.PrepareResult.Existing).record)
        assertEquals(r.commitId, prepared.record.toRequest().commitId)
        val claimed = ledger.claim(r.commitId, evidence, false) as ReviewCommitLedger.ClaimResult.Claimed
        assertEquals(ReviewCommitState.SUBMITTING, claimed.record.state)
        assertEquals(CommitAttemptPhase.PREPARED, store.durableRecords().single().phase)
        assertEquals(CommitAttemptPhase.MUTATION_CALL_ENTERED, ledger.markMutationEntered(r.commitId)?.phase)
        assertEquals(CommitAttemptPhase.MUTATION_CALL_ENTERED, store.durableRecords().single().phase)
        assertEquals(listOf(null, CommitAttemptPhase.PREPARED, CommitAttemptPhase.MUTATION_CALL_ENTERED),
            store.writes.map { (ReviewCommitLedgerCodec.decode(it) as ReviewCommitLedgerCodec.Decoded.Records).records.single().phase })
    }

    @Test fun `a cancelled first load does not disable the ledger but a throwing store fails closed`() = runTest {
        val backing = InMemoryReviewCommitStore()
        var mode = "cancel"
        val store = object : ReviewCommitStore {
            override suspend fun read(): ReviewCommitStoreRead = when (mode) {
                "cancel" -> throw kotlinx.coroutines.CancellationException("caller went away")
                "throw" -> throw IllegalStateException("io")
                else -> backing.read()
            }
            override suspend fun write(snapshot: String): Boolean = backing.write(snapshot)
        }
        val ledger = ReviewCommitLedger(store, clock)
        try {
            ledger.prepare(request())
            fail("cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        mode = "ok"
        assertTrue(ledger.prepare(request()) is ReviewCommitLedger.PrepareResult.Prepared)

        mode = "throw"
        val broken = ReviewCommitLedger(store, clock)
        assertTrue(broken.prepare(request()) is ReviewCommitLedger.PrepareResult.Unavailable)
        mode = "ok"
        assertTrue("an unexplained store throw stays fail-closed for this ledger instance",
            broken.prepare(request()) is ReviewCommitLedger.PrepareResult.Unavailable)
    }

    @Test fun `a failed initial intent write never appears in memory or on disk`() = runTest {
        val store = InMemoryReviewCommitStore()
        store.failNextWrites = 1
        val ledger = ReviewCommitLedger(store, clock)
        assertTrue(ledger.prepare(request()) is ReviewCommitLedger.PrepareResult.StoreFailed)
        assertNull(store.snapshot)
        assertTrue(ledger.snapshot().isEmpty())
        assertTrue(ledger.diagnosticsSnapshot().lastWriteFailed)
    }

    @Test fun `different rating or new turn with unresolved commit is refused`() = runTest {
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock)
        val r = request()
        ledger.prepare(r)
        assertTrue(ledger.prepare(request(rating = Rating.EASY)) is ReviewCommitLedger.PrepareResult.Conflict)
        assertTrue(ledger.prepare(request(turn = "other")) is ReviewCommitLedger.PrepareResult.Conflict)
        assertEquals(1, ledger.snapshot().size)
    }

    @Test fun `one review turn cannot be assigned a second commit id across sessions`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        val first = request(turn = "same-turn", session = "session-a")
        assertTrue(ledger.prepare(first) is ReviewCommitLedger.PrepareResult.Prepared)
        val second = request(turn = "same-turn", session = "session-b")
        assertNotEquals(first.commitId, second.commitId)
        assertTrue(ledger.prepare(second) is ReviewCommitLedger.PrepareResult.Conflict)
        assertEquals(1, ledger.snapshot().size)

        val entered = ledger.claim(first.commitId, evidence, false) as ReviewCommitLedger.ClaimResult.Claimed
        ledger.markMutationEntered(first.commitId)
        ledger.markResponseReceived(first.commitId, CommitRatingResult.Committed())
        ledger.complete(first.commitId, CommitRatingResult.Committed())
        assertEquals(ReviewCommitState.COMMITTED, ledger.get(entered.record.commitId)?.state)
        assertTrue(ledger.prepare(second) is ReviewCommitLedger.PrepareResult.Conflict)

        now = 10_000
        assertEquals(1, ledger.pruneCommitted(olderThanEpochMs = 9_000))
        assertTrue(ledger.prepare(second) is ReviewCommitLedger.PrepareResult.Tombstoned)
    }

    @Test fun `one commit payload keeps collection and deck identity immutable`() = runTest {
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock)
        val original = request()
        ledger.prepare(original)
        assertTrue(ledger.prepare(original.copy(collectionRef = AnkiCollectionIdentity(backend, "other")))
            is ReviewCommitLedger.PrepareResult.Conflict)
        assertTrue(ledger.prepare(original.copy(deckRef = AnkiDeckRef(backend, "other", "collection")))
            is ReviewCommitLedger.PrepareResult.Conflict)
    }

    @Test fun `only one mutation attempt can be claimed under concurrent duplicate input`() = runTest {
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock)
        ledger.prepare(request())
        val results = List(64) { async(Dispatchers.Default) { ledger.claim(request().commitId, evidence, false) } }.awaitAll()
        assertEquals(1, results.count { it is ReviewCommitLedger.ClaimResult.Claimed })
        assertEquals(63, results.count { it is ReviewCommitLedger.ClaimResult.InFlight })
        assertEquals(1, ledger.get(request().commitId)?.attemptCount)
    }

    @Test fun `committed result is durable before reported and never downgraded or replayed`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        val r = request()
        enter(ledger, r)
        val saved = finish(ledger, r, CommitRatingResult.Committed())
        assertEquals(ReviewCommitState.COMMITTED, saved.state)
        assertEquals(Rating.GOOD, saved.selectedRating)
        assertEquals(Rating.GOOD, saved.committedRating)
        assertEquals(CommitAttemptPhase.LOCAL_RESULT_PERSISTED, store.durableRecords().single().phase)
        assertEquals(CommitResponseKind.CONFIRMED_COMMITTED, saved.response?.kind)
        assertNull(ledger.complete(r.commitId, CommitRatingResult.Ambiguous()))
        assertTrue(store.restart(clock).claim(r.commitId, null, true) is ReviewCommitLedger.ClaimResult.AlreadyCommitted)
        assertEquals(ReviewCommitState.COMMITTED, ledger.get(r.commitId)?.state)
    }

    @Test fun `pre-call failure and retry use the same commit and original rating`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        val r = request(rating = Rating.HARD)
        ledger.prepare(r)
        val failed = ledger.markRefused(r.commitId, "permission_required", true)!!
        assertEquals(0, failed.attemptCount)
        assertTrue(failed.safeToRetry)
        assertTrue(ledger.claim(r.commitId, null, false) is ReviewCommitLedger.ClaimResult.NotClaimable)
        val claimed = ledger.claim(r.commitId, evidence, true) as ReviewCommitLedger.ClaimResult.Claimed
        assertEquals(1, claimed.record.attemptCount)
        assertEquals(r.rating, claimed.record.toRequest().rating)
        assertEquals(r.commitId, claimed.record.toRequest().commitId)
        ledger.markMutationEntered(r.commitId)
        assertEquals(ReviewCommitState.COMMITTED, finish(ledger, r, CommitRatingResult.Committed()).state)
        assertEquals(1, ledger.snapshot().size)
    }

    @Test fun `prepared process interruption is safe but entered interruption is ambiguous`() = runTest {
        val store = InMemoryReviewCommitStore()
        val first = ReviewCommitLedger(store, clock)
        val prepared = request()
        first.prepare(prepared); first.claim(prepared.commitId, evidence, false)
        val restored = store.restart(clock)
        val safe = restored.get(prepared.commitId)!!
        assertEquals(ReviewCommitState.FAILED_SAFE_TO_RETRY, safe.state)
        assertTrue(safe.safeToRetry)
        assertEquals(ReviewCommitResolution.RECOVERED_PREPARED, safe.resolution)
        assertEquals(0, restored.pendingRecovery(backend).size)

        val other = request(session = "s2")
        enter(restored, other)
        val again = store.restart(clock)
        val uncertain = again.get(other.commitId)!!
        assertEquals(ReviewCommitState.AMBIGUOUS, uncertain.state)
        assertEquals(ReviewCommitResolution.PROCESS_RESTART_WHILE_SUBMITTING, uncertain.resolution)
        assertFalse(uncertain.safeToRetry)
        assertTrue(again.claim(other.commitId, null, true) is ReviewCommitLedger.ClaimResult.NotClaimable)
        assertEquals(listOf(uncertain), again.pendingRecovery(backend))
        assertEquals(1, again.recoveryReport().interruptedSubmissions)
    }

    @Test fun `durably received success finishes after restart without a second backend call`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        val r = request()
        enter(ledger, r)
        ledger.markResponseReceived(r.commitId, CommitRatingResult.Committed())
        assertEquals(ReviewCommitState.SUBMITTING, store.durableRecords().single().state)
        assertEquals(CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED, store.durableRecords().single().phase)
        val restarted = store.restart(clock)
        assertEquals(ReviewCommitState.COMMITTED, restarted.get(r.commitId)!!.state)
        assertEquals(ReviewCommitResolution.RECOVERED_RESPONSE, restarted.get(r.commitId)!!.resolution)
    }

    @Test fun `failed COMMITTED write keeps in-memory and disk nonterminal until durable recovery`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        val r = request()
        enter(ledger, r)
        store.failNextWrites = 1
        assertNull(ledger.markResponseReceived(r.commitId, CommitRatingResult.Committed()))
        assertEquals(CommitAttemptPhase.MUTATION_CALL_ENTERED, ledger.get(r.commitId)?.phase)
        assertEquals(ReviewCommitState.AMBIGUOUS, store.restart(clock).get(r.commitId)?.state)

        // In an independent run, the *terminal* write alone fails; the durable response
        // lets the next process finish without replaying a mutation.
        val secondStore = InMemoryReviewCommitStore()
        val second = ReviewCommitLedger(secondStore, clock)
        val r2 = request(session = "s2")
        enter(second, r2)
        second.markResponseReceived(r2.commitId, CommitRatingResult.Committed())
        secondStore.failNextWrites = 1
        assertNull(second.complete(r2.commitId, CommitRatingResult.Committed()))
        assertEquals("failed durable write is not counted as success", 0L,
            second.diagnosticsSnapshot().successTotal)
        assertEquals(ReviewCommitState.SUBMITTING, second.get(r2.commitId)?.state)
        assertEquals(CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED, secondStore.durableRecords().single().phase)
        val recovered = secondStore.restart(clock)
        assertEquals(ReviewCommitState.COMMITTED, recovered.get(r2.commitId)?.state)
        assertEquals("durable response recovery counts once", 1L, recovered.diagnosticsSnapshot().successTotal)
    }

    @Test fun `failed restart write fails closed rather than exposing a nondurable recovery`() = runTest {
        val store = InMemoryReviewCommitStore()
        val r = request()
        enter(ReviewCommitLedger(store, clock), r)
        store.failNextWrites = 1
        val restarted = store.restart(clock)
        assertTrue(restarted.health() is ReviewCommitLedger.Health.Unavailable)
        assertTrue(restarted.prepare(request(session = "other")) is ReviewCommitLedger.PrepareResult.Unavailable)
        assertEquals(CommitAttemptPhase.MUTATION_CALL_ENTERED, store.durableRecords().single().phase)
    }

    @Test fun `no false COMMITTED on contradictory response or unsupported transition`() = runTest {
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock)
        val r = request()
        ledger.prepare(r)
        assertNull(ledger.markMutationEntered(r.commitId))
        ledger.claim(r.commitId, evidence, false)
        assertNull(ledger.markResponseReceived(r.commitId, CommitRatingResult.Committed()))
        ledger.markMutationEntered(r.commitId)
        ledger.markResponseReceived(r.commitId, CommitRatingResult.Committed())
        assertNull(ledger.complete(r.commitId, CommitRatingResult.RetryableFailure(AnkiError.BackendUnavailable())))
        assertEquals(ReviewCommitState.COMMITTED, ledger.finalizeRecordedResponse(r.commitId)?.state)
        assertNull(ledger.markAmbiguous(r.commitId, "late_unknown"))
    }

    @Test fun `ambiguous reconciliation remains blocked unless the adapter provides proof`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        val a = request(session = "s1")
        enter(ledger, a)
        finish(ledger, a, CommitRatingResult.Ambiguous())
        assertEquals(ReviewCommitState.AMBIGUOUS,
            ledger.reconcile(a.commitId, ReconcileCommitResult.StillAmbiguous("no_receipt"))?.state)
        assertFalse(ledger.get(a.commitId)!!.safeToRetry)
        assertEquals(ReviewCommitState.COMMITTED,
            ledger.reconcile(a.commitId, ReconcileCommitResult.Applied("backend_receipt"))?.state)
        assertEquals(ReviewCommitState.COMMITTED, store.restart(clock).get(a.commitId)?.state)
        assertNull(ledger.markMutationEntered(a.commitId))
        val b = request(session = "s2")
        enter(ledger, b); finish(ledger, b, CommitRatingResult.Ambiguous())
        assertTrue(ledger.reconcile(b.commitId, ReconcileCommitResult.NotApplied("backend_receipt", true))!!.safeToRetry)
        assertTrue(store.restart(clock).get(b.commitId)!!.safeToRetry)
    }

    @Test fun `unresolved collection is blocked but unrelated backend and collection are not`() = runTest {
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock)
        val r = request()
        enter(ledger, r); finish(ledger, r, CommitRatingResult.Ambiguous())
        assertEquals(r.commitId, ledger.recoveryBlocker(backend, "collection", "new")?.commitId)
        assertEquals(r.commitId, ledger.recoveryBlocker(backend, "other", "s1")?.commitId)
        assertNull(ledger.recoveryBlocker(backend, "other", "new"))
        assertNull(ledger.recoveryBlocker(AnkiBackendId.Fake("separate"), "collection", "new"))
        ledger.acknowledge(r.commitId)
        assertEquals(r.commitId, ledger.recoveryBlocker(backend, "collection", "new")?.commitId)
    }

    @Test fun `backend and known collection scope the blocker and separate logical sessions`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        val original = request(session = "shared")
        enter(ledger, original)
        assertEquals(original.commitId, ledger.recoveryBlocker(backend, "collection", "new")?.commitId)
        assertNull(ledger.recoveryBlocker(backend, "other", "new"))
        val otherBackend = AnkiBackendId.Fake("different")
        val other = CommitRatingRequest(
            ReviewCommitId(otherBackend, "shared", ReviewTurnId("t1")),
            AnkiCardRef(otherBackend, "c2", "n2", 0, "collection"), Rating.GOOD, 900L)
        assertTrue(ledger.prepare(other) is ReviewCommitLedger.PrepareResult.Prepared)
        assertTrue(store.restart(clock).health() is ReviewCommitLedger.Health.Ready)
        assertNull(ledger.recoveryBlocker(otherBackend, "collection", "new"))
    }

    @Test fun `diagnostic counts show durable state and last write failure without IDs`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        assertEquals("Not checked", ledger.diagnosticsSnapshot().health)
        enter(ledger)
        val active = ledger.diagnosticsSnapshot()
        assertEquals("Ready", active.health)
        assertEquals(1, active.submitting)
        store.failNextWrites = 1
        assertNull(ledger.markResponseReceived(request().commitId, CommitRatingResult.Committed()))
        val failed = ledger.diagnosticsSnapshot()
        assertEquals("Last write failed", failed.health)
        assertTrue(failed.lastWriteFailed)
        assertEquals(1, failed.submitting)
        assertEquals(0, failed.committed)
        assertFalse(failed.toString().contains("collection"))
    }

    @Test fun `capacity never evicts ambiguous or committed identity even after acknowledgment`() = runTest {
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock, maxRecords = 2)
        val a = request(session = "s1")
        enter(ledger, a); finish(ledger, a, CommitRatingResult.Ambiguous())
        ledger.acknowledge(a.commitId)
        val b = request(session = "s2")
        enter(ledger, b); finish(ledger, b, CommitRatingResult.Committed())
        assertEquals(ReviewCommitLedger.PrepareResult.Full, ledger.prepare(request(session = "s3")))
        assertEquals(2, ledger.snapshot().size)
        assertEquals(1, ledger.pendingRecovery().size)
    }

    @Test fun `corrupt snapshots multiple unresolved records and invalid phase fail closed`() = runTest {
        for (raw in listOf("{not json", """{"schemaVersion":99,"records":[]}""")) {
            val store = InMemoryReviewCommitStore(raw)
            assertTrue(ReviewCommitLedger(store, clock).health() is ReviewCommitLedger.Health.Unavailable)
            assertEquals(raw, store.snapshot)
        }
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        val a = (ledger.prepare(request()) as ReviewCommitLedger.PrepareResult.Prepared).record
        store.overwrite(ReviewCommitLedgerCodec.encode(listOf(a, a.copy(commitId = request(turn = "t2").commitId))))
        assertTrue(store.restart(clock).health() is ReviewCommitLedger.Health.Unavailable)
        store.overwrite(ReviewCommitLedgerCodec.encode(listOf(a.copy(state = ReviewCommitState.SUBMITTING,
            attemptCount = 1, phase = CommitAttemptPhase.LOCAL_RESULT_PERSISTED))))
        assertTrue(store.restart(clock).health() is ReviewCommitLedger.Health.Unavailable)
    }

    @Test fun `legacy SUBMITTING is treated as entered not prepared`() = runTest {
        val store = InMemoryReviewCommitStore()
        val r = request()
        enter(ReviewCommitLedger(store, clock), r)
        val root = Json.parseToJsonElement(store.snapshot!!).jsonObject.toMutableMap()
        root["schemaVersion"] = JsonPrimitive(1)
        root["records"] = JsonArray(root.getValue("records").jsonArray.map { element ->
            element.jsonObject.toMutableMap().apply {
                remove("phase")
                remove("collectionRef")
                remove("committedRating")
            }.let(::JsonObject)
        })
        store.overwrite(JsonObject(root).toString())
        assertEquals(ReviewCommitState.AMBIGUOUS, store.restart(clock).get(r.commitId)?.state)
    }

    @Test fun `schema v2 generic failure migrates using the explicit safe-to-retry fact`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        val r = request()
        ledger.prepare(r)
        ledger.markRefused(r.commitId, "safe_preflight", safeToRetry = true)
        val root = Json.parseToJsonElement(store.snapshot!!).jsonObject.toMutableMap()
        root["schemaVersion"] = JsonPrimitive(2)
        val legacyRecords = root.getValue("records").jsonArray.map { element ->
            element.jsonObject.toMutableMap().apply {
                this["state"] = JsonPrimitive("FAILED")
                remove("collectionRef")
                remove("committedRating")
            }.let(::JsonObject)
        }
        root["records"] = JsonArray(legacyRecords)
        store.overwrite(JsonObject(root).toString())
        val migrated = store.restart(clock).get(r.commitId)!!
        assertEquals(ReviewCommitState.FAILED_SAFE_TO_RETRY, migrated.state)
        assertTrue(migrated.safeToRetry)
    }

    @Test fun `schema v2 generic failure with safeToRetry false migrates as non-retryable`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        val r = request()
        ledger.prepare(r)
        ledger.markRefused(r.commitId, "not_retryable", safeToRetry = false)
        val root = Json.parseToJsonElement(store.snapshot!!).jsonObject.toMutableMap()
        root["schemaVersion"] = JsonPrimitive(2)
        root["records"] = JsonArray(root.getValue("records").jsonArray.map { element ->
            element.jsonObject.toMutableMap().apply {
                this["state"] = JsonPrimitive("FAILED")
                remove("collectionRef")
                remove("committedRating")
            }.let(::JsonObject)
        })
        store.overwrite(JsonObject(root).toString())

        val migrated = store.restart(clock).get(r.commitId)!!
        assertEquals(ReviewCommitState.FAILED_NOT_RETRYABLE, migrated.state)
        assertFalse(migrated.safeToRetry)
    }

    @Test fun `schema v3 persists explicit failure names and rejects legacy ambiguity`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        val r = request()
        ledger.prepare(r)
        ledger.markRefused(r.commitId, "safe_preflight", safeToRetry = true)
        val raw = store.snapshot!!
        assertTrue(raw.contains("\"state\":\"FAILED_SAFE_TO_RETRY\""))
        assertFalse(raw.contains("\"state\":\"FAILED\""))
        val ambiguousLegacyName = raw.replace(
            "\"state\":\"FAILED_SAFE_TO_RETRY\"", "\"state\":\"FAILED\"")
        assertTrue(ReviewCommitLedgerCodec.decode(ambiguousLegacyName) is ReviewCommitLedgerCodec.Decoded.Unreadable)
    }

    @Test fun `codec preserves metadata but no educational content`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        val r = request()
        enter(ledger, r)
        val result = CommitRatingResult.Committed(receipt = CommitReceipt(backend, "receipt-from-fake", r.rating))
        finish(ledger, r, result)
        assertEquals("receipt-from-fake", store.durableRecords().single().response?.backendReceiptId)
        assertEquals(Rating.GOOD, store.durableRecords().single().receipt?.committedRating)
        assertEquals(ReviewCommitLedgerCodec.Decoded.Records(store.durableRecords()),
            ReviewCommitLedgerCodec.decode(store.snapshot!!))
        val raw = store.snapshot!!
        for (text in listOf("question", "answer\"", "html", "transcript")) {
            assertFalse(text, raw.contains(text, ignoreCase = true))
        }
    }

    @Test fun `illegal transition is rejected before the snapshot changes`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        val r = request()
        val prepared = ledger.prepare(r) as ReviewCommitLedger.PrepareResult.Prepared
        val before = store.snapshot
        val rejected = ledger.transition(
            r.commitId, ReviewCommitState.NOT_STARTED, prepared.record.version,
            ReviewCommitTransition.MarkCommitted(CommitRatingResult.Committed(), "forged")
        )
        assertTrue(rejected is ReviewCommitLedger.TransitionResult.Rejected)
        assertEquals(ReviewCommitTransitionRejection.INVALID_BACKEND_RESULT,
            (rejected as ReviewCommitLedger.TransitionResult.Rejected).reason)
        assertEquals(before, store.snapshot)
        assertEquals(ReviewCommitState.NOT_STARTED, ledger.get(r.commitId)?.state)
        assertEquals(0, ledger.diagnosticsSnapshot().successTotal)
    }

    @Test fun `store failure is distinct from an illegal transition and changes no record`() = runTest {
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, clock)
        val r = request()
        val prepared = ledger.prepare(r) as ReviewCommitLedger.PrepareResult.Prepared
        val before = store.snapshot
        store.failNextWrites = 1
        val result = ledger.transition(
            r.commitId, ReviewCommitState.NOT_STARTED, prepared.record.version,
            ReviewCommitTransition.NoteAbandoned(abandonedAtEpochMs = 50L)
        )
        assertTrue(result is ReviewCommitLedger.TransitionResult.StoreFailed)
        assertEquals(before, store.snapshot)
        assertNull(ledger.get(r.commitId)?.abandonedAtEpochMs)
        assertTrue(ledger.diagnosticsSnapshot().lastWriteFailed)
    }

    @Test fun `stale version is not written`() = runTest {
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock)
        val r = request()
        val prepared = ledger.prepare(r) as ReviewCommitLedger.PrepareResult.Prepared
        val first = ledger.transition(
            r.commitId, ReviewCommitState.NOT_STARTED, prepared.record.version,
            ReviewCommitTransition.NoteAbandoned(abandonedAtEpochMs = 50L)
        ) as ReviewCommitLedger.TransitionResult.Applied
        val stale = ledger.transition(
            r.commitId, ReviewCommitState.NOT_STARTED, prepared.record.version,
            ReviewCommitTransition.NoteAbandoned(abandonedAtEpochMs = 99L)
        )
        assertTrue(stale is ReviewCommitLedger.TransitionResult.VersionMismatch ||
            stale is ReviewCommitLedger.TransitionResult.StateMismatch)
        assertEquals(50L, first.record.abandonedAtEpochMs)
        assertEquals(ReviewCommitState.NOT_STARTED, ledger.get(r.commitId)?.state)
        assertEquals(50L, ledger.get(r.commitId)?.abandonedAtEpochMs)
    }

    @Test fun `abandoning a commit does not change its state or make it retryable`() = runTest {
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock)
        val r = request()
        enter(ledger, r)
        ledger.markAmbiguous(r.commitId, "lost")
        val noted = ledger.noteAbandoned(r.commitId, 80L)
        assertEquals(ReviewCommitState.AMBIGUOUS, noted?.state)
        assertFalse(noted!!.safeToRetry)
        assertEquals(80L, noted.abandonedAtEpochMs)
        assertTrue(ledger.claim(r.commitId, evidence, allowRetry = true) is ReviewCommitLedger.ClaimResult.NotClaimable)
    }

    @Test fun `pruned committed identity cannot be prepared again and unresolved rows stay`() = runTest {
        val ledger = ReviewCommitLedger(InMemoryReviewCommitStore(), clock)
        val committed = request(turn = "done", session = "s-done")
        enter(ledger, committed)
        finish(ledger, committed, CommitRatingResult.Committed())
        val ambiguous = request(turn = "open", session = "s-open")
        enter(ledger, ambiguous)
        ledger.markAmbiguous(ambiguous.commitId, "lost")
        now = 10_000L
        assertEquals(1, ledger.pruneCommitted(olderThanEpochMs = 9_000L))
        assertTrue(ledger.get(committed.commitId) == null)
        assertEquals(ReviewCommitState.AMBIGUOUS, ledger.get(ambiguous.commitId)?.state)
        assertTrue(ledger.prepare(committed) is ReviewCommitLedger.PrepareResult.Tombstoned)
        assertEquals(1, ledger.unresolved().size)
    }

}
