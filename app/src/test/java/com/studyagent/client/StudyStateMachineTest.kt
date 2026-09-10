package com.studyagent.client

import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.StudyCard
import com.studyagent.client.core.models.StudyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyStateMachineTest {

    private val sampleCard = StudyCard(
        id = "card-123",
        question = "What are the indications for evacuation of an epidural hematoma?",
        cardNumber = 1,
        remaining = 10
    )

    private val sampleEval = Evaluation(
        score = 80,
        shortFeedback = "Good answer. You missed neurological deterioration.",
        correctPoints = listOf("Volume > 30 mL", "Midline shift > 5 mm"),
        missingPoints = listOf("Neurological deterioration"),
        suggestedRating = Rating.HARD
    )

    @Test
    fun testStudyStateHierarchyAndCardExtraction() {
        val idle = StudyState.Idle
        assertNull(idle.currentCardOrNull)

        val speaking = StudyState.SpeakingQuestion(sampleCard)
        assertEquals(sampleCard, speaking.currentCardOrNull)

        val listening = StudyState.Listening(sampleCard, "Volume greater than 30")
        assertEquals(sampleCard, listening.currentCardOrNull)
        assertEquals("Volume greater than 30", listening.partialTranscript)

        val evaluating = StudyState.Evaluating(sampleCard, "Full transcript")
        assertEquals(sampleCard, evaluating.currentCardOrNull)

        val showingFeedback = StudyState.ShowingFeedback(sampleCard, sampleEval)
        assertEquals(sampleCard, showingFeedback.currentCardOrNull)
        assertEquals(80, showingFeedback.evaluation.score)

        val waitingRating = StudyState.WaitingForRating(sampleCard, sampleEval, Rating.HARD)
        assertEquals(sampleCard, waitingRating.currentCardOrNull)
        assertEquals(Rating.HARD, waitingRating.suggestedRating)

        // Paused state preserves previous card
        val paused = StudyState.Paused(speaking)
        assertEquals(sampleCard, paused.currentCardOrNull)
    }

    @Test
    fun testRatingFromStringParser() {
        assertEquals(Rating.AGAIN, Rating.fromString("again"))
        assertEquals(Rating.AGAIN, Rating.fromString("مرة أخرى"))
        assertEquals(Rating.HARD, Rating.fromString("hard"))
        assertEquals(Rating.HARD, Rating.fromString("صعب"))
        assertEquals(Rating.GOOD, Rating.fromString("good"))
        assertEquals(Rating.GOOD, Rating.fromString("جيد"))
        assertEquals(Rating.EASY, Rating.fromString("easy"))
        assertEquals(Rating.EASY, Rating.fromString("سهل"))
        assertNull(Rating.fromString("completely unrelated string"))
    }
}
