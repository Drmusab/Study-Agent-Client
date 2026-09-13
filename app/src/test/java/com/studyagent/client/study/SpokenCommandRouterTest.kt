package com.studyagent.client.study

import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.VoiceCommand
import com.studyagent.client.core.study.SpokenCommandRouter
import com.studyagent.client.core.study.StudyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hands-free Phone Mode depends on this mapping (§13/§79): the user may be sitting at a desk
 * with no headphones and never touch the phone, so a spoken "good" has to reach exactly the
 * same reducer path as the on-screen rating button. No phone-only commands exist.
 */
class SpokenCommandRouterTest {

    @Test
    fun `rating commands map to the shared rating event`() {
        assertEquals(
            StudyEvent.UserRateCard(Rating.AGAIN, "c1"),
            SpokenCommandRouter.toEvent(VoiceCommand.Again, "c1")
        )
        assertEquals(
            StudyEvent.UserRateCard(Rating.HARD, "c1"),
            SpokenCommandRouter.toEvent(VoiceCommand.Hard, "c1")
        )
        assertEquals(
            StudyEvent.UserRateCard(Rating.GOOD, "c1"),
            SpokenCommandRouter.toEvent(VoiceCommand.Good, "c1")
        )
        assertEquals(
            StudyEvent.UserRateCard(Rating.EASY, "c1"),
            SpokenCommandRouter.toEvent(VoiceCommand.Easy, "c1")
        )
    }

    @Test
    fun `a rating without an active card is dropped, never applied to a stale card`() {
        assertNull(SpokenCommandRouter.toEvent(VoiceCommand.Good, cardId = null))
    }

    @Test
    fun `navigation and session commands map onto existing events`() {
        assertEquals(StudyEvent.UserRequestRepeat("c1"), SpokenCommandRouter.toEvent(VoiceCommand.Repeat, "c1"))
        assertEquals(StudyEvent.UserRequestHint("c1"), SpokenCommandRouter.toEvent(VoiceCommand.Hint, "c1"))
        assertEquals(StudyEvent.UserRequestExplanation("c1"), SpokenCommandRouter.toEvent(VoiceCommand.Explain, "c1"))
        assertEquals(StudyEvent.UserRequestAnswer("c1"), SpokenCommandRouter.toEvent(VoiceCommand.ShowAnswer, "c1"))
        assertEquals(StudyEvent.UserSkipRequested("c1"), SpokenCommandRouter.toEvent(VoiceCommand.Skip, "c1"))
        assertEquals(StudyEvent.UserStopSpeaking, SpokenCommandRouter.toEvent(VoiceCommand.StopSpeaking, "c1"))
        assertTrue(SpokenCommandRouter.toEvent(VoiceCommand.Pause, "c1") is StudyEvent.UserPauseRequested)
        assertTrue(SpokenCommandRouter.toEvent(VoiceCommand.Resume, "c1") is StudyEvent.UserResumeRequested)
        assertTrue(SpokenCommandRouter.toEvent(VoiceCommand.Stop, "c1") is StudyEvent.UserEndRequested)
        assertTrue(SpokenCommandRouter.toEvent(VoiceCommand.EndSession, "c1") is StudyEvent.UserEndRequested)
    }

    @Test
    fun `transcript-only commands are not session events`() {
        assertNull(SpokenCommandRouter.toEvent(VoiceCommand.Unknown("some answer"), "c1"))
        assertNull(SpokenCommandRouter.toEvent(VoiceCommand.StatusQuestion, "c1"))
        assertNull(SpokenCommandRouter.toEvent(VoiceCommand.SubmitAnswer("answer"), "c1"))
    }

    @Test
    fun `rating commands are recognised as the scheduling-sensitive family`() {
        assertTrue(SpokenCommandRouter.isRatingCommand(VoiceCommand.Good))
        assertTrue(SpokenCommandRouter.isRatingCommand(VoiceCommand.Again))
        assertFalse(SpokenCommandRouter.isRatingCommand(VoiceCommand.Repeat))
        assertFalse(SpokenCommandRouter.isRatingCommand(VoiceCommand.Unknown("good")))
    }
}
