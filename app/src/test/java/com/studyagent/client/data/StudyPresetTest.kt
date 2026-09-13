package com.studyagent.client.data

import com.studyagent.client.core.models.AutoRatingMode
import com.studyagent.client.core.models.FeedbackDepth
import com.studyagent.client.core.models.StudyControlConfig
import com.studyagent.client.core.models.StudyMode
import com.studyagent.client.core.models.StudyPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §71/§141/§142: presets are centralized model logic producing explicit,
 * deterministic configurations — never scattered UI conditionals.
 */
class StudyPresetTest {

    private val base = StudyControlConfig(activeDeck = "MCCQE::Cardiology", newPerDay = 15)

    @Test
    fun `every preset produces a deterministic explicit config`() {
        StudyPreset.entries.filter { it != StudyPreset.CUSTOM }.forEach { preset ->
            val first = preset.applyTo(base)
            val second = preset.applyTo(base)
            assertEquals("Preset ${preset.name} must be deterministic", first, second)
        }
    }

    @Test
    fun `presets preserve user identity fields`() {
        StudyPreset.entries.filter { it != StudyPreset.CUSTOM }.forEach { preset ->
            val applied = preset.applyTo(base)
            assertEquals("deck preserved for ${preset.name}", "MCCQE::Cardiology", applied.activeDeck)
            assertEquals("new limit preserved for ${preset.name}", 15, applied.newPerDay)
        }
    }

    @Test
    fun `each preset is recognized by matching`() {
        StudyPreset.entries.filter { it != StudyPreset.CUSTOM }.forEach { preset ->
            val applied = preset.applyTo(base)
            assertEquals("matching() must detect ${preset.name}", preset, StudyPreset.matching(applied))
        }
    }

    @Test
    fun `preset-specific expectations hold`() {
        val walking = StudyPreset.WALKING_MODE.applyTo(base)
        assertEquals(FeedbackDepth.MINIMAL, walking.feedbackDepth)
        assertFalse(walking.socratic.enabled)

        val deep = StudyPreset.DEEP_STUDY.applyTo(base)
        assertTrue(deep.socratic.enabled)
        assertEquals(FeedbackDepth.DETAILED, deep.feedbackDepth)
        assertEquals(AutoRatingMode.MANUAL, deep.ratingMode)

        val exam = StudyPreset.EXAM_MODE.applyTo(base)
        assertFalse(exam.evaluation.partialCredit)
        assertEquals(StudyMode.DUE_REVIEWS, exam.studyMode)

        val weak = StudyPreset.WEAKNESS_TRAINING.applyTo(base)
        assertEquals(StudyMode.WEAK_CARDS, weak.studyMode)
    }

    @Test
    fun `editing a field after preset selection becomes Custom`() {
        // §142: Deep Study, then feedback changed to Minimal -> Custom.
        val deep = StudyPreset.DEEP_STUDY.applyTo(base)
        assertEquals(StudyPreset.DEEP_STUDY, StudyPreset.matching(deep))

        val edited = deep.copy(feedbackDepth = FeedbackDepth.MINIMAL)
        assertEquals(StudyPreset.CUSTOM, StudyPreset.matching(edited))
    }

    @Test
    fun `walking mode provides hands-free local behavior, deep study does not`() {
        val walkingBehavior = StudyPreset.WALKING_MODE.localBehavior()
        assertEquals(true, walkingBehavior?.handsFreeMode)
        assertEquals(true, walkingBehavior?.listenForSpokenRating)

        val deepBehavior = StudyPreset.DEEP_STUDY.localBehavior()
        assertEquals(false, deepBehavior?.handsFreeMode)

        assertEquals(null, StudyPreset.CUSTOM.localBehavior())
    }
}
