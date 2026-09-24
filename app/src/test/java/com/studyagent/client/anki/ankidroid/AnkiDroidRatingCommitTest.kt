package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.*
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.models.Rating
import com.studyagent.client.data.anki.ankidroid.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 11 — the AnkiDroid rating protocol against a scripted provider that reproduces the pinned
 * v2.24.1 behaviour: the answer is applied, or the scheduler exception is swallowed and `update`
 * still answers 1, or the provider process dies before/after applying, or it hangs.
 */
class AnkiDroidRatingCommitTest {
    private val backendId = AnkiBackendId.AnkiDroidLocal
    private val authority = "com.ichi2.anki.flashcards"
    private val sessionDeck = AnkiDeckRef(backendId, "1700000000000")
    private val noteId = 1700000000123L
    private val cardPath = "notes/$noteId/cards/0"

    /** The provider's observable collection for one card, plus AnkiDroid's selected deck. */
    private class ProviderModel(var clockMs: () -> Long) {
        var reps = 5
        var lastReview: Long? = 1_600_000_000L
        var due = 19_000L
        var queue = 2
        var selectedDeck = 42L
        var frontNote = 1700000000123L
        fun applyAnswer() {
            reps += 1
            lastReview = clockMs() / 1000
            due += 4
        }
    }

    private inner class Rig(scope: CoroutineScope, val clock: MutableClock) {
        val model = ProviderModel { clock.now }
        val provider = FakeAnkiDroidProviderClient()
        val gateway = DefaultAnkiDroidRatingGateway(provider, scope, clock)
        val review = FakeAnkiDroidReviewGateway(results = mutableListOf(scheduled()))
        val backend = AnkiDroidBackend(
            gateway = FakeAnkiDroidGateway(stateToReturn = readyState()),
            scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            deckGateway = FakeAnkiDroidDeckGateway().also { it.succeed(listOf(AnkiDeck(sessionDeck, "Medicine"))) },
            reviewGateway = review,
            cardGateway = FakeAnkiDroidCardGateway(),
            turnIds = SequentialReviewTurnIdSource(instancePrefix = "g11"),
            ratingGateway = gateway,
            clock = clock // AnkiDroid and Study-Agent share the device clock
        )
        /** What `update(schedule)` does; default = applies and answers 1. */
        var onAnswer: suspend () -> ProviderUpdateResult = { model.applyAnswer(); ProviderUpdateResult.Returned(1) }

        init { refreshProviderRows() }

        fun refreshProviderRows() {
            provider.scriptRows(cardPath, listOf(mapOf(
                "_id" to 11L, "note_id" to noteId, "ord" to 0, "deck_id" to sessionDeck.deckId.toLong(),
                "original_deck_id" to 0L, "reps" to model.reps, "lapses" to 1, "interval" to 3, "type" to 2,
                "queue" to model.queue, "due" to model.due, "last_review_time_secs" to model.lastReview
            )))
            provider.scriptRows("selected_deck", listOf(mapOf("deck_id" to model.selectedDeck)))
            provider.scriptRows("schedule", listOf(mapOf("note_id" to model.frontNote, "ord" to 0)))
        }

        init {
            provider.updateHandlers["schedule"] = { onAnswer().also { refreshProviderRows() } }
            provider.updateHandlers["selected_deck"] = { values ->
                model.selectedDeck = (values.single() as ProviderValue.LongValue).value
                refreshProviderRows()
                ProviderUpdateResult.Returned(1)
            }
        }

        lateinit var session: AnkiReviewSession

        suspend fun openTurn(): AnkiReviewTurn {
            backend.refreshAvailability()
            session = (backend.beginReview(BeginReviewRequest(AnkiSessionContext(
                backendId, null, sessionDeck, 1_700_000_000_000L,
                AnkiCapabilities(review = true, deckListing = true, scheduledReview = true), "session-1"
            ))) as AnkiResult.Success).value
            return (backend.nextCard(session) as NextCardResult.Card).turn
        }

        suspend fun commit(turn: AnkiReviewTurn, rating: Rating = Rating.GOOD): Pair<CommitRatingRequest, CommitRatingResult> {
            val base = CommitRatingRequest(turn.commitId, turn.cardRef, rating, clock.now, answerDurationMs = 3_000)
            val prepared = backend.prepareCommit(base) as CommitPreparation.Ready
            val request = base.copy(evidence = prepared.evidence)
            return request to backend.commitRating(request)
        }

        val answerCalls get() = provider.updateLog.count { it.first == "schedule" }
    }

    private class MutableClock(var now: Long = 1_700_000_100_000L) : AppClock {
        override fun nowMillis(): Long = now
    }

    private fun TestScope.rig() = Rig(backgroundScope, MutableClock())

    // ---------------------------------------------------------------- committed

    @Test fun `an applied answer is Committed on evidence and releases the turn`() = runTest {
        val r = rig()
        val turn = r.openTurn()
        val (_, result) = r.commit(turn)
        assertEquals(CommitRatingResult.Committed(), result)
        assertEquals(1, r.answerCalls)
        val sent = r.provider.updateLog.single { it.first == "schedule" }.second
        assertTrue(sent.contains(ProviderValue.IntValue("answer_ease", 3)))
        assertTrue(sent.contains(ProviderValue.LongValue("time_taken", 3_000)))
        assertTrue(sent.contains(ProviderValue.LongValue("note_id", noteId)))
        assertTrue(sent.contains(ProviderValue.IntValue("ord", 0)))
        assertTrue("never bury or suspend", sent.none { it.column == "buried" || it.column == "suspended" })
        // Released: the next nextCard() asks the scheduler again instead of re-serving the turn.
        assertEquals(1, r.review.queryCalls)
        r.backend.nextCard(r.session)
        assertEquals(2, r.review.queryCalls)
    }

    @Test fun `the v2_24_1 queue front precondition selects the session deck and always restores it`() = runTest {
        val r = rig()
        val turn = r.openTurn()
        r.commit(turn)
        val order = r.provider.updateLog.map { (path, values) ->
            if (path == "selected_deck") "select:${(values.single() as ProviderValue.LongValue).value}" else path
        }
        assertEquals(listOf("select:${sessionDeck.deckId}", "schedule", "select:42"), order)
        assertEquals(42L, r.model.selectedDeck)
        assertTrue("the queue front is read without deckID", r.provider.selectionLog.contains("limit=1"))
    }

    // ---------------------------------------------------------------- not applied

    @Test fun `a swallowed scheduler exception answering 1 is proven not applied and retryable`() = runTest {
        val r = rig()
        r.onAnswer = { ProviderUpdateResult.Returned(1) } // v2.24.1: caught RuntimeException, updated++
        val turn = r.openTurn()
        val (request, result) = r.commit(turn)
        assertEquals(CommitRatingResult.RetryableFailure(AnkiError.QueryFailure("answer_not_applied")), result)
        // The same request may be sent again, and then applies once.
        r.onAnswer = { r.model.applyAnswer(); ProviderUpdateResult.Returned(1) }
        assertEquals(CommitRatingResult.Committed(), r.backend.commitRating(request))
        assertEquals(2, r.answerCalls)
        assertEquals(6, r.model.reps)
    }

    @Test fun `a card that is no longer at the queue front is refused before any answer is sent`() = runTest {
        val r = rig()
        val turn = r.openTurn()
        r.model.frontNote = 999L // a learning card became due first
        r.refreshProviderRows()
        val (_, result) = r.commit(turn)
        assertTrue(result is CommitRatingResult.Rejected)
        assertEquals(0, r.answerCalls)
        assertEquals("selection restored", 42L, r.model.selectedDeck)
    }

    @Test fun `a card changed elsewhere since preparation is refused before mutation`() = runTest {
        val r = rig()
        val turn = r.openTurn()
        val base = CommitRatingRequest(turn.commitId, turn.cardRef, Rating.GOOD, 1, 1)
        val evidence = (r.backend.prepareCommit(base) as CommitPreparation.Ready).evidence
        r.model.applyAnswer() // reviewed in AnkiDroid itself meanwhile
        r.refreshProviderRows()
        val result = r.backend.commitRating(base.copy(evidence = evidence))
        assertEquals(CommitRatingResult.Rejected(AnkiError.CommitConflict(turn.cardRef)), result)
        assertEquals(0, r.answerCalls)
    }

    @Test fun `pre mutation provider exceptions are failures, never ambiguous`() = runTest {
        val r = rig()
        r.onAnswer = { ProviderUpdateResult.Threw("SecurityException",
            AnkiDroidFailure(AnkiDroidFailureCategory.PERMISSION_DENIED, AnkiDroidFailureEvidence.SECURITY_EXCEPTION_TYPE)) }
        val (_, result) = r.commit(r.openTurn())
        assertEquals(CommitRatingResult.RetryableFailure(AnkiError.PermissionRequired()), result)
    }

    // ---------------------------------------------------------------- ambiguous vs evidence

    @Test fun `provider death after applying is Committed from evidence and before applying is retryable`() = runTest {
        val applied = rig()
        applied.onAnswer = { applied.model.applyAnswer(); ProviderUpdateResult.Returned(-1) }
        assertEquals(CommitRatingResult.Committed(), applied.commit(applied.openTurn()).second)

        val notApplied = rig()
        notApplied.onAnswer = { ProviderUpdateResult.Returned(-1) }
        assertEquals(CommitRatingResult.RetryableFailure(AnkiError.QueryFailure("provider_died_before_applying")),
            notApplied.commit(notApplied.openTurn()).second)
    }

    @Test fun `a hung provider call is AMBIGUOUS and blocks both a retry and reconciliation while in flight`() = runTest {
        val r = rig()
        r.onAnswer = { delay(60_000); r.model.applyAnswer(); ProviderUpdateResult.Returned(1) }
        val turn = r.openTurn()
        val (request, result) = r.commit(turn)
        assertTrue(result is CommitRatingResult.Ambiguous)
        assertTrue(r.gateway.writeInFlight)
        assertEquals("known ambiguous answers from memory, no second dispatch", result, r.backend.commitRating(request))
        val reconcile = r.backend.reconcileCommit(ReconcileCommitRequest(
            request.commitId, request.card, request.rating, request.evidence, r.clock.now - 20_000, r.clock.now))
        assertEquals(ReconcileCommitResult.StillAmbiguous("provider_call_in_flight"), reconcile)
        assertEquals(1, r.answerCalls)
    }

    @Test fun `an unreadable verification is AMBIGUOUS never failed`() = runTest {
        val r = rig()
        // Session deck already selected: no restore write re-scripts the provider in between.
        r.model.selectedDeck = sessionDeck.deckId.toLong()
        r.refreshProviderRows()
        val turn = r.openTurn()
        r.onAnswer = {
            r.model.applyAnswer()
            ProviderUpdateResult.Returned(1).also {
                r.provider.scriptFailure(cardPath, AnkiDroidFailure(AnkiDroidFailureCategory.PROVIDER_ERROR,
                    AnkiDroidFailureEvidence.UNCLASSIFIED))
            }
        }
        r.provider.updateHandlers["schedule"] = { r.onAnswer() } // keep the failure scripted
        val (_, result) = r.commit(turn)
        assertEquals(CommitRatingResult.Ambiguous(AnkiError.Unknown("verification_read_failed")), result)
    }

    // ---------------------------------------------------------------- identity / idempotency

    @Test fun `a repeated commit answers from memory and a changed rating is a conflict`() = runTest {
        val r = rig()
        val turn = r.openTurn()
        val (request, first) = r.commit(turn)
        assertEquals(first, r.backend.commitRating(request))
        assertEquals(CommitRatingResult.Rejected(AnkiError.CommitConflict(request.card)),
            r.backend.commitRating(request.copy(rating = Rating.EASY)))
        assertEquals(1, r.answerCalls)
    }

    @Test fun `ratings the scheduler did not offer and foreign turns are refused before mutation`() = runTest {
        val r = rig()
        val turn = r.openTurn()
        val stale = CommitRatingRequest(turn.commitId.copy(turnId = ReviewTurnId("old")), turn.cardRef, Rating.GOOD, 1)
        assertEquals(CommitRatingResult.Rejected(AnkiError.StaleTurn()), r.backend.commitRating(stale))
        assertEquals(0, r.answerCalls)
    }

    @Test fun `without a rating gateway nothing is written and review is not claimed`() = runTest {
        val backend = AnkiDroidBackend(
            gateway = FakeAnkiDroidGateway(stateToReturn = readyState()),
            scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            deckGateway = FakeAnkiDroidDeckGateway(), reviewGateway = FakeAnkiDroidReviewGateway(),
            cardGateway = FakeAnkiDroidCardGateway()
        )
        backend.refreshAvailability()
        assertFalse(backend.capabilities.value.review)
    }

    // ---------------------------------------------------------------- reconciliation after restart

    @Test fun `reconciliation after process death uses the durable baseline`() = runTest {
        val r = rig()
        val turn = r.openTurn()
        val base = CommitRatingRequest(turn.commitId, turn.cardRef, Rating.GOOD, r.clock.now, 1)
        val evidence = (r.backend.prepareCommit(base) as CommitPreparation.Ready).evidence
        val submittedAt = r.clock.now
        r.model.applyAnswer() // the write landed, then the app died before recording it
        r.refreshProviderRows()
        r.clock.now += 1_000
        // A fresh backend instance: no session, only the ledger's evidence.
        val fresh = rig().also { it.model.reps = r.model.reps; it.model.lastReview = r.model.lastReview; it.model.due = r.model.due; it.refreshProviderRows() }
        fresh.backend.refreshAvailability()
        val applied = fresh.backend.reconcileCommit(ReconcileCommitRequest(
            base.commitId, base.card, base.rating, evidence, submittedAt, r.clock.now))
        assertTrue(applied is ReconcileCommitResult.Applied)

        val untouched = rig()
        untouched.backend.refreshAvailability()
        val notApplied = untouched.backend.reconcileCommit(ReconcileCommitRequest(
            base.commitId, base.card, base.rating, evidence, submittedAt, r.clock.now))
        assertEquals(ReconcileCommitResult.NotApplied("no_state_change", safeToRetry = true), notApplied)

        val noBaseline = untouched.backend.reconcileCommit(ReconcileCommitRequest(
            base.commitId, base.card, base.rating, null, submittedAt, r.clock.now))
        assertTrue(noBaseline is ReconcileCommitResult.StillAmbiguous)
        assertEquals("reconciliation never writes", 0, untouched.answerCalls + fresh.answerCalls)
    }

    // ---------------------------------------------------------------- fixtures

    private fun scheduled() = AnkiResult.Success<AnkiDroidScheduledCardQuery>(AnkiDroidScheduledCardQuery.Scheduled(
        AnkiScheduledCard(
            ref = AnkiCardRef(backendId, cardId = null, noteId = noteId.toString(), cardOrd = 0),
            noteRef = AnkiNoteRef(backendId, noteId.toString()),
            deckRef = sessionDeck,
            ratingOptions = AnkiRatingOptions.Known(listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY))
        ), 1L, 1L))

    private fun readyState(): AnkiDroidIntegrationState {
        val caps = AnkiDroidCompatibilityPolicy.implementedCapabilitiesFor(spec = 2, isReady = true)
        return AnkiDroidIntegrationState(
            availability = AnkiAvailability.Ready(caps),
            capabilities = caps,
            apiCapabilities = AnkiDroidCompatibilityPolicy.apiCapabilitiesForSpec(2),
            metadata = AnkiDroidMetadata(
                packageName = "com.ichi2.anki", providerPackage = "com.ichi2.anki", authority = authority,
                endpointLabel = "release", providerSpec = 2, providerSpecSource = AnkiDroidProviderSpecSource.METADATA,
                packageVersion = "2.24.1", providerReachable = true, permissionGranted = true, collectionReady = true
            ),
            lastCheckAtMs = 1L, latencyMs = 1L, lastError = null,
            healthSnapshot = AnkiDroidHealthSnapshot.checking(1L), capabilityDetails = emptyList()
        )
    }
}
