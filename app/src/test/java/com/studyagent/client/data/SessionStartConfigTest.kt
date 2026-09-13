package com.studyagent.client.data

import com.studyagent.client.core.models.AutoRatingMode
import com.studyagent.client.core.models.SessionTargetType
import com.studyagent.client.core.models.StudyControlConfig
import com.studyagent.client.core.models.StudyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §75/§76/§135: start_session carries the Control Center configuration on
 * Protocol v2; nothing but deck+mode on v1. The client never forces
 * `review_due` when a richer config exists.
 */
class SessionStartConfigTest {

    @Test
    fun `v2 start payload mirrors the control configuration`() {
        val config = StudyControlConfig(
            activeDeck = "MCCQE::Cardiology",
            studyMode = StudyMode.DUE_AND_NEW,
            sessionTargetType = SessionTargetType.MINUTES,
            sessionTargetValue = 45,
            newPerDay = 20,
            reviewLimitPerDay = null,
            ratingMode = AutoRatingMode.SUGGEST
        )

        val payload = config.toSessionStartConfig()

        assertEquals("minutes", payload.targetType)
        assertEquals(45, payload.targetValue)
        assertEquals(20, payload.newPerDay)
        assertNull(payload.reviewLimitPerDay)
        assertEquals("balanced", payload.evaluationStrictness)
        assertEquals("normal", payload.feedbackDepth)
        assertEquals("suggest", payload.ratingMode)
        assertEquals("score_only", payload.transcriptRetention)
        // Suggest mode does not leak the confidence threshold.
        assertNull(payload.autoRateConfidence)
    }

    @Test
    fun `finish-due target omits the value`() {
        val config = StudyControlConfig(
            sessionTargetType = SessionTargetType.FINISH_DUE,
            sessionTargetValue = 50
        )
        assertNull(config.toSessionStartConfig().targetValue)
    }

    @Test
    fun `auto-rate confidence only travels in auto_confident mode`() {
        val confident = StudyControlConfig(
            ratingMode = AutoRatingMode.AUTO_CONFIDENT,
            autoRateConfidence = 90
        )
        assertEquals(90, confident.toSessionStartConfig().autoRateConfidence)

        val automatic = StudyControlConfig(ratingMode = AutoRatingMode.AUTOMATIC, autoRateConfidence = 90)
        assertNull(automatic.toSessionStartConfig().autoRateConfidence)
    }

    @Test
    fun `model validation rejects invalid session targets`() {
        // §136: invalid values must never be sent; validation lives in the model.
        val zeroCards = StudyControlConfig(sessionTargetType = SessionTargetType.CARDS, sessionTargetValue = 0)
        assertTrueNonEmpty(zeroCards.validate())

        val hugeMinutes = StudyControlConfig(sessionTargetType = SessionTargetType.MINUTES, sessionTargetValue = 500)
        assertTrueNonEmpty(hugeMinutes.validate())

        val lowConfidence = StudyControlConfig(
            ratingMode = AutoRatingMode.AUTO_CONFIDENT,
            autoRateConfidence = 20
        )
        assertTrueNonEmpty(lowConfidence.validate())

        val valid = StudyControlConfig()
        assertEquals(emptyList<String>(), valid.validate())
    }

    private fun assertTrueNonEmpty(errors: List<String>) {
        if (errors.isEmpty()) throw AssertionError("Expected validation errors, got none")
    }
}
