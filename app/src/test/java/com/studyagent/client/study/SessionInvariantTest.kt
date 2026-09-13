package com.studyagent.client.study

import com.studyagent.client.core.models.StudyCard
import com.studyagent.client.core.study.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Invariant tests (§139). These assert the 10 core invariants via the
 * machine's derived StudyState and StudySession mapping.
 */
class SessionInvariantTest {

    private fun card(id: String) = StudyCard(id, "Q $id")

    @Test fun `StudyState and currentSession never disagree`() {
        var s = SessionMachineState(epoch = 1L, phase = SessionPhase.Idle)
        // Simulate machine derivation: derivePublicFlows would map cardTurn to both StudyState and Session
        // We test reducer's transitionHistory and cardTurn vs session consistency directly.
        s = StudyReducer.reduce(s, StudyEvent.UserStartRequested("deck", "m1"), 0L).newState
        s = StudyReducer.reduce(s, StudyEvent.ServerSessionStarted("s1", "deck", 5, "srv1"), 0L).newState
        s = StudyReducer.reduce(s, StudyEvent.ServerQuestionReceived("s1", "c1", "Q1", 1, 4, false, "q1", "turn1", 1L), 0L).newState
        assertEquals("c1", s.cardTurn?.cardId)
        assertEquals("c1", s.session?.currentCard?.id)
        assertEquals("c1", s.currentCardId)
    }

    @Test fun `evaluation belongs to current turn`() {
        var s = SessionMachineState(epoch = 1L, phase = SessionPhase.Idle)
        s = StudyReducer.reduce(s, StudyEvent.UserStartRequested("deck", "m1"), 0L).newState
        s = StudyReducer.reduce(s, StudyEvent.ServerSessionStarted("s1", "deck", 5, "srv1"), 0L).newState
        s = StudyReducer.reduce(s, StudyEvent.ServerQuestionReceived("s1", "c1", "Q1", 1, 4, true, "q1", "turn1", 1L), 0L).newState
        s = StudyReducer.reduce(s, StudyEvent.QuestionSpeechCompleted("c1", s.activeSpeechEffectId!!, true), 0L).newState
        s = StudyReducer.reduce(s, StudyEvent.UserSubmitAnswer("c1", "ans"), 0L).newState
        val eval = com.studyagent.client.core.models.Evaluation(shortFeedback = "Good", suggestedRating = com.studyagent.client.core.models.Rating.GOOD)
        s = StudyReducer.reduce(s, StudyEvent.ServerEvaluationReceived("s1", "c1", eval, false, "e1"), 0L).newState
        assertEquals("c1", s.cardTurn?.cardId)
        assertEquals(eval, s.cardTurn?.evaluation)
        // Stale evaluation for other card must not leak
        val stale = StudyReducer.reduce(s, StudyEvent.ServerEvaluationReceived("s1", "c2", eval, false, "e2"), 0L)
        assertFalse(stale.accepted)
        assertEquals("c1", stale.newState.cardTurn?.cardId)
        assertEquals(eval, stale.newState.cardTurn?.evaluation)
    }

    @Test fun `at most one answer per turn`() {
        var s = SessionMachineState(epoch = 1L, phase = SessionPhase.Idle)
        s = StudyReducer.reduce(s, StudyEvent.UserStartRequested("deck", "m1"), 0L).newState
        s = StudyReducer.reduce(s, StudyEvent.ServerSessionStarted("s1", "deck", 5, "srv1"), 0L).newState
        s = StudyReducer.reduce(s, StudyEvent.ServerQuestionReceived("s1", "c1", "Q1", 1, 4, false, "q1", "turn1", 1L), 0L).newState
        val a1 = StudyReducer.reduce(s, StudyEvent.UserSubmitAnswer("c1", "ans1"), 0L)
        assertTrue(a1.accepted)
        val a2 = StudyReducer.reduce(a1.newState, StudyEvent.UserSubmitAnswer("c1", "ans2"), 1L)
        assertFalse(a2.accepted)
    }

    @Test fun `at most one rating per turn`() {
        var s = SessionMachineState(epoch = 1L, phase = SessionPhase.Idle)
        s = StudyReducer.reduce(s, StudyEvent.UserStartRequested("deck", "m1"), 0L).newState
        s = StudyReducer.reduce(s, StudyEvent.ServerSessionStarted("s1", "deck", 5, "srv1"), 0L).newState
        s = StudyReducer.reduce(s, StudyEvent.ServerQuestionReceived("s1", "c1", "Q1", 1, 4, false, "q1", "turn1", 1L), 0L).newState
        s = StudyReducer.reduce(s, StudyEvent.UserSubmitAnswer("c1", "ans"), 0L).newState
        val eval = com.studyagent.client.core.models.Evaluation(shortFeedback = "Good")
        s = StudyReducer.reduce(s, StudyEvent.ServerEvaluationReceived("s1", "c1", eval, false, "e1"), 0L).newState
        val r1 = StudyReducer.reduce(s, StudyEvent.UserRateCard(com.studyagent.client.core.models.Rating.GOOD, "c1"), 0L)
        assertTrue(r1.accepted)
        val r2 = StudyReducer.reduce(r1.newState, StudyEvent.UserRateCard(com.studyagent.client.core.models.Rating.HARD, "c1"), 0L)
        assertFalse(r2.accepted)
    }

    @Test fun `finished cannot transition back`() {
        var s = SessionMachineState(epoch = 1L, phase = SessionPhase.Finished)
        val ev = StudyReducer.reduce(s, StudyEvent.ServerQuestionReceived("s1", "c1", "Q", 1, 4, false, "q1", null, 1L), 0L)
        assertFalse(ev.accepted)
        assertEquals("terminal-finished", ev.rejectionReason)
    }

    @Test fun `paused cannot open STT`() {
        var s = SessionMachineState(epoch = 1L, phase = SessionPhase.Paused, cardTurn = CardTurn(1L, card("c1"), "turn1"))
        val ev = StudyReducer.reduce(s, StudyEvent.UserSubmitAnswer("c1", "ans"), 0L)
        assertFalse(ev.accepted)
    }

    @Test fun `only active epoch may mutate`() {
        var s = SessionMachineState(epoch = 5L, phase = SessionPhase.WaitingForAnswer, cardTurn = CardTurn(1L, card("c1"), "turn1"))
        // Simulate timeout from old epoch
        val pending = PendingAction("old-msg", PendingAction.ActionType.SUBMIT_ANSWER, 4L, "turn1", "c1", 0L, 1000L)
        s = s.copy(pendingAction = pending)
        val timeout = StudyReducer.reduce(s, StudyEvent.ActionTimedOut("old-msg", PendingAction.ActionType.SUBMIT_ANSWER), 2000L)
        assertFalse(timeout.accepted)
        assertEquals("stale-epoch", timeout.rejectionReason)
    }

    @Test fun `server sessionId must match`() {
        var s = SessionMachineState(epoch = 1L, phase = SessionPhase.WaitingForAnswer, session = StudySessionSnapshot("s1","deck", card("c1")), cardTurn = CardTurn(1L, card("c1"), "turn1"))
        val stale = StudyReducer.reduce(s, StudyEvent.ServerQuestionReceived("s2", "c2", "Q2", 2, 3, false, "q2", null, 2L), 0L)
        assertFalse(stale.accepted)
        assertEquals("stale-session", stale.rejectionReason)
    }

    @Test fun `no network effect from illegal state`() {
        var s = SessionMachineState(epoch = 1L, phase = SessionPhase.Idle)
        val ev = StudyReducer.reduce(s, StudyEvent.UserRateCard(com.studyagent.client.core.models.Rating.GOOD, "c1"), 0L)
        assertFalse(ev.accepted)
        assertTrue(ev.effects.isEmpty() || ev.effects.none { it is StudyEffect.Network.Send })
    }
}
