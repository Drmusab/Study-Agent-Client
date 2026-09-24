package com.studyagent.client.ui

import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.StudyCard
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.ui.components.ratingControlsEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GATE 11 — rating controls are offered only where a rating can be accepted (STEP 91-§92). */
class RatingControlsPolicyTest {
    private val card = StudyCard("c1", "Question")

    @Test fun `rating controls are enabled only while a rating can be accepted`() {
        assertTrue(ratingControlsEnabled(StudyState.WaitingForRating(card, Evaluation())))
        assertTrue(ratingControlsEnabled(StudyState.ShowingFeedback(card, Evaluation())))
        val disabled = listOf(
            StudyState.Idle,
            StudyState.Loading("Saving rating: Good...", pendingRating = Rating.GOOD),
            StudyState.Listening(card),
            StudyState.Evaluating(card, "answer"),
            StudyState.Error("Rating not confirmed"),
            StudyState.SessionFinished()
        )
        disabled.forEach { assertFalse("$it", ratingControlsEnabled(it)) }
    }

    @Test fun `the submitting state carries the pending rating as data`() {
        val loading = StudyState.Loading("Saving rating: Hard...", pendingRating = Rating.HARD)
        assertEquals(Rating.HARD, loading.pendingRating)
        assertEquals(null, StudyState.Loading("Loading").pendingRating)
    }
}
