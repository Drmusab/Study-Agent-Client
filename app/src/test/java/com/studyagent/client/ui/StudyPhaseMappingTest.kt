package com.studyagent.client.ui

import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.StudyCard
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.ui.components.StudyPhase
import com.studyagent.client.ui.components.studyPhaseOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Study screen renders exactly one explicit phase (§23). This pins the pure mapping from
 * the protocol state machine to that presentation phase so a UI refactor cannot silently
 * change what the user is told to do.
 */
class StudyPhaseMappingTest {

    private val card = StudyCard("c1", "What is the Monro–Kellie doctrine?", 1, 5)

    @Test
    fun `idle and loading map to their own phases`() {
        assertEquals(StudyPhase.IDLE, studyPhaseOf(StudyState.Idle))
        assertEquals(StudyPhase.LOADING, studyPhaseOf(StudyState.Loading("Loading cards…")))
    }

    @Test
    fun `listening splits into listening vs review by pending transcript`() {
        assertEquals(StudyPhase.LISTENING, studyPhaseOf(StudyState.Listening(card)))
        assertEquals(
            StudyPhase.REVIEW,
            studyPhaseOf(StudyState.Listening(card, pendingTranscript = "raised ICP"))
        )
    }

    @Test
    fun `voice turn phases are explicit and distinct`() {
        assertEquals(StudyPhase.SPEAKING, studyPhaseOf(StudyState.SpeakingQuestion(card)))
        assertEquals(StudyPhase.EVALUATING, studyPhaseOf(StudyState.Evaluating(card, "answer")))
        assertEquals(StudyPhase.FEEDBACK, studyPhaseOf(StudyState.ShowingFeedback(card, Evaluation())))
        assertEquals(StudyPhase.RATING, studyPhaseOf(StudyState.WaitingForRating(card, Evaluation())))
        assertEquals(StudyPhase.HINT, studyPhaseOf(StudyState.HintShowing(card, "hint")))
        assertEquals(StudyPhase.EXPLANATION, studyPhaseOf(StudyState.ExplanationShowing(card, "why")))
    }

    @Test
    fun `paused finished and error are terminal presentation phases`() {
        assertEquals(StudyPhase.PAUSED, studyPhaseOf(StudyState.Paused(StudyState.Listening(card))))
        assertEquals(StudyPhase.FINISHED, studyPhaseOf(StudyState.SessionFinished(cardsReviewed = 3)))
        assertEquals(StudyPhase.ERROR, studyPhaseOf(StudyState.Error("boom")))
    }
}
