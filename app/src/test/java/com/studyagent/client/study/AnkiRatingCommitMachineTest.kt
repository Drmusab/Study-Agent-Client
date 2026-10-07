package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.InMemoryReviewCommitStore
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.diagnostics.DiagnosticCategory
import com.studyagent.client.core.diagnostics.DiagnosticTimeline
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.core.study.*
import com.studyagent.client.testutil.FakeConnectionRepository
import com.studyagent.client.testutil.FakeRecognitionOrchestrator
import com.studyagent.client.testutil.FakeSpeechOrchestrator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 11 through the production [StudySessionMachine]: write jobs survive session end, results are
 * dispatched back and correlated, the UI state carries the pending rating, and diagnostics are
 * metadata only.
 */
class AnkiRatingCommitMachineTest {

    private inner class Rig(test: TestScope) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val store = InMemoryReviewCommitStore()
        val ledger = ReviewCommitLedger(store, { 1_000L })
        val fake = FakeAnkiBackend(id = AnkiCommitHarness.BACKEND, decks = listOf(AnkiDeck(AnkiCommitHarness.DECK, "Deck")),
            cards = listOf(AnkiCommitHarness.card("A"), AnkiCommitHarness.card("B")), instanceId = "machine")
        val timeline = DiagnosticTimeline()
        val machine = StudySessionMachine(
            connectionRepository = FakeConnectionRepository(),
            speechOrchestrator = FakeSpeechOrchestrator(),
            recognitionOrchestrator = FakeRecognitionOrchestrator(),
            scope = scope,
            timeline = timeline,
            ankiEffects = AnkiStudyEffectExecutor(AnkiBackendRegistry(listOf(fake)), ledger, { 1_000L },
                phases = CommitPhaseSink { phase, attempt, commitId ->
                    timeline.record(DiagnosticCategory.SESSION, "REVIEW_COMMIT_$phase",
                        turnId = commitId.turnId.value,
                        metadata = mapOf("commit" to Integer.toHexString(commitId.stableKey.hashCode()),
                            "backend" to commitId.backendId.stableId, "attempt" to attempt.toString()))
                })
        )
        val state get() = machine.machineState.value

        fun start() = machine.dispatch(AnkiStudyEvent.Start(
            AnkiStudyRequest("study-m", AnkiBackendMode.ANKIDROID_LOCAL, AnkiCommitHarness.DECK, speakQuestion = false)))

        fun reveal() = machine.dispatch(StudyEvent.UserRequestAnswer(state.currentCardId))
        fun rate(r: Rating = Rating.GOOD) = machine.dispatch(StudyEvent.UserRateCard(r, state.currentCardId!!))
        fun names() = timeline.snapshot().map { it.event }
        fun close() { machine.close(); scope.cancel() }
    }

    @Test fun `a proven safe failure is diagnosed as a safe failure with full correlation`() = runTest {
        val r = Rig(this)
        r.start(); advanceUntilIdle()
        r.reveal(); advanceUntilIdle()
        r.fake.prepareRefusal = CommitPreparation.Refused(AnkiError.QueryFailure("transient"), retryable = true)
        r.rate(Rating.HARD); advanceUntilIdle()

        val snapshots = r.timeline.snapshot()
        val names = snapshots.map { it.event }
        assertTrue("safe failure must be its own event: $names", "ANKI_COMMIT_SAFE_FAILURE" in names)
        assertFalse("a refusal before the boundary never claims COMMITTED", "ANKI_COMMIT_COMMITTED" in names)
        val failure = snapshots.first { it.event == "ANKI_COMMIT_SAFE_FAILURE" }
        assertEquals("the event carries the review turn itself", r.state.anki!!.turn!!.turnId.value, failure.turnId)
        val metadata = failure.metadata
        // Exactly the eight correlation keys the timeline keeps, most important first.
        for (key in listOf("commit", "session", "backend", "guarantee", "rating", "committed",
            "state", "attempt")) {
            assertTrue("missing $key in $metadata", key in metadata)
        }
        assertEquals("hard", metadata["rating"])
        assertEquals("-", metadata["committed"])
        assertTrue("the durable status and its reason survive together: ${metadata["state"]}",
            metadata["state"]!!.startsWith("RETRY_ALLOWED("))
        assertEquals("1", metadata["attempt"])
        // The guarantee is the frozen one, not a later restatement: the same value the ledger row
        // carries. The fake advertises authoritative reconciliation, which the AnkiDroid identity
        // may not claim, so the ledger freezes the clamped fail-closed value — checkpoint 12's
        // "a guarantee is only as strong as the evidence" — and diagnostics must show exactly that.
        val row = r.ledger.snapshot().single()
        assertEquals(row.frozenGuarantee!!.name, metadata["guarantee"])
        assertNotEquals("unfrozen", metadata["guarantee"])
        assertNotEquals(CommitGuaranteeLevel.END_TO_END_EXACTLY_ONCE.name, metadata["guarantee"])
        assertEquals(row.commitId.backendId.stableId, metadata["backend"])
        assertTrue("session prefix is present", metadata["session"]!!.isNotEmpty())
        // Phase markers from the executor carry the same identity.
        assertTrue("no card text in metadata: $metadata", metadata.values.none { it.contains("Q ") })
        val phases = snapshots.filter { it.event.startsWith("REVIEW_COMMIT_") }.map { it.event }
        assertTrue("phase markers have correlation: $phases", phases.any { it == "REVIEW_COMMIT_CREATED" })
        assertTrue(phases.none { it == "REVIEW_COMMIT_CALL_ENTERED" })
    }

    @Test fun `commit then next card with metadata only diagnostics`() = runTest {
        val r = Rig(this)
        r.start(); advanceUntilIdle()
        r.reveal(); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        r.fake.commitGate = gate
        r.rate(Rating.GOOD); runCurrent()
        // The pending rating is data on the public state, and the rating controls are locked.
        val loading = r.machine.studyState.value as StudyState.Loading
        assertEquals(Rating.GOOD, loading.pendingRating)
        assertTrue("r.machine.ratingCommitRecovery.value!!.commitUiState", r.machine.ratingCommitRecovery.value!!.commitUiState is RatingCommitUiState.Saving)
        gate.complete(Unit); advanceUntilIdle()

        assertEquals("B", r.state.anki!!.turn!!.cardRef.cardId)
        assertNull(r.machine.ratingCommitRecovery.value)
        val names = r.names()
        for (expected in listOf("ANKI_COMMIT_PREPARED", "ANKI_COMMIT_STARTED", "ANKI_COMMIT_COMMITTED",
                "ANKI_NEXT_CARD_AFTER_COMMIT_STARTED")) {
            assertTrue("$expected missing from $names", expected in names)
        }
        assertTrue(names.indexOf("ANKI_COMMIT_STARTED") < names.indexOf("ANKI_COMMIT_COMMITTED"))
        val metadata = r.timeline.snapshot().filter { it.event.startsWith("ANKI_") }.flatMap { it.metadata.values }
        assertTrue("no card text in diagnostics: $metadata",
            metadata.none { it.contains("Question") || it.contains("Answer") || it.contains("<b>") })
        assertEquals(0L, r.machine.invariantViolationCount)
        assertEquals(1, r.fake.physicalCommitCalls)
        r.close()
    }

    @Test fun `ending the session during an in flight commit neither cancels nor duplicates it`() = runTest {
        val r = Rig(this)
        r.start(); advanceUntilIdle()
        r.reveal(); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        r.fake.commitGate = gate
        r.rate(); runCurrent()
        val commitId = r.state.anki!!.commit!!.commitId
        r.machine.dispatch(StudyEvent.UserEndRequested("stop")); runCurrent()
        assertEquals(SessionPhase.Finished, r.state.phase)
        // UI recreation during the in-flight commit is a no-op too.
        r.machine.dispatch(StudyEvent.UiRecreated); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(ReviewCommitStatus.COMMITTED, r.ledger.get(commitId)!!.status)
        assertEquals(SessionPhase.Finished, r.state.phase)
        assertEquals(1, r.fake.physicalCommitCalls)
        assertEquals(1, r.fake.endReviewCalls)
        assertFalse("a late result never advances a finished session", "ANKI_NEXT_CARD_AFTER_COMMIT_STARTED" in r.names())
        assertEquals(0L, r.machine.invariantViolationCount)
        r.close()
    }

    @Test fun `touch voice and keyboard bursts through the machine commit once`() = runTest {
        val r = Rig(this)
        r.start(); advanceUntilIdle()
        r.reveal(); advanceUntilIdle()
        val turn = r.state.anki!!.turn!!
        r.rate(Rating.GOOD)
        r.rate(Rating.GOOD)
        r.machine.dispatch(AnkiStudyEvent.SelectRating(r.state.epoch, turn.turnId, Rating.EASY))
        r.rate(Rating.HARD)
        advanceUntilIdle()
        assertEquals(1, r.fake.physicalCommitCalls)
        assertEquals(Rating.GOOD, r.fake.recordedCommits().single().request.rating)
        assertEquals(1, r.state.session!!.totalReviewedInSession)
        r.close()
    }
}
