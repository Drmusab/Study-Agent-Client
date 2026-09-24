package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.InMemoryReviewCommitStore
import com.studyagent.client.core.anki.*
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
            ankiEffects = AnkiStudyEffectExecutor(AnkiBackendRegistry(listOf(fake)), ledger, { 1_000L })
        )
        val state get() = machine.machineState.value

        fun start() = machine.dispatch(AnkiStudyEvent.Start(
            AnkiStudyRequest("study-m", AnkiBackendMode.ANKIDROID_LOCAL, AnkiCommitHarness.DECK, speakQuestion = false)))

        fun reveal() = machine.dispatch(StudyEvent.UserRequestAnswer(state.currentCardId))
        fun rate(r: Rating = Rating.GOOD) = machine.dispatch(StudyEvent.UserRateCard(r, state.currentCardId!!))
        fun names() = timeline.snapshot().map { it.event }
        fun close() { machine.close(); scope.cancel() }
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
        assertEquals(RatingCommitRecoveryUi.Status.SAVING, r.machine.ratingCommitRecovery.value!!.status)
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
        assertEquals(ReviewCommitState.COMMITTED, r.ledger.get(commitId)!!.state)
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
