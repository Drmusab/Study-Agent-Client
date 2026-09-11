package com.studyagent.client

import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.StudyCard
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.core.models.VoiceCommand
import com.studyagent.client.core.voice.VoiceCommandManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceCommandManagerTest {

    private lateinit var manager: VoiceCommandManager

    @Before
    fun setUp() {
        manager = VoiceCommandManager()
    }

    @Test
    fun testEnglishRatings() {
        assertEquals(VoiceCommand.Again, manager.parseCommand("again"))
        assertEquals(VoiceCommand.Again, manager.parseCommand("Again."))
        assertEquals(VoiceCommand.Hard, manager.parseCommand("hard"))
        assertEquals(VoiceCommand.Good, manager.parseCommand("Good!"))
        assertEquals(VoiceCommand.Easy, manager.parseCommand("easy"))
    }

    @Test
    fun testArabicRatings() {
        assertEquals(VoiceCommand.Again, manager.parseCommand("مرة أخرى"))
        assertEquals(VoiceCommand.Again, manager.parseCommand("مرة اخرى"))
        assertEquals(VoiceCommand.Hard, manager.parseCommand("صعب"))
        assertEquals(VoiceCommand.Good, manager.parseCommand("جيد"))
        assertEquals(VoiceCommand.Good, manager.parseCommand("تمام"))
        assertEquals(VoiceCommand.Easy, manager.parseCommand("سهل"))
    }

    @Test
    fun testSessionControlCommands() {
        assertEquals(VoiceCommand.Repeat, manager.parseCommand("repeat question"))
        assertEquals(VoiceCommand.Repeat, manager.parseCommand("أعد السؤال"))
        assertEquals(VoiceCommand.Hint, manager.parseCommand("give me a hint"))
        assertEquals(VoiceCommand.Hint, manager.parseCommand("تلميح"))
        assertEquals(VoiceCommand.Explain, manager.parseCommand("explain"))
        assertEquals(VoiceCommand.Explain, manager.parseCommand("اشرح"))
        assertEquals(VoiceCommand.ShowAnswer, manager.parseCommand("show answer"))
        assertEquals(VoiceCommand.ShowAnswer, manager.parseCommand("اظهر الجواب"))
        assertEquals(VoiceCommand.Skip, manager.parseCommand("skip"))
        assertEquals(VoiceCommand.Skip, manager.parseCommand("تخطي"))
        assertEquals(VoiceCommand.Pause, manager.parseCommand("pause"))
        assertEquals(VoiceCommand.Pause, manager.parseCommand("توقف مؤقت"))
        assertEquals(VoiceCommand.Resume, manager.parseCommand("resume"))
        assertEquals(VoiceCommand.Resume, manager.parseCommand("اكمل"))
        assertEquals(VoiceCommand.EndSession, manager.parseCommand("end session"))
        assertEquals(VoiceCommand.EndSession, manager.parseCommand("انهاء"))
        assertEquals(VoiceCommand.StatusQuestion, manager.parseCommand("how many cards left?"))
        assertEquals(VoiceCommand.StatusQuestion, manager.parseCommand("كم بطاقة متبقية"))
    }

    @Test
    fun testStartStudyDeckExtraction() {
        val cmd = manager.parseCommand("start studying Toronto Notes")
        assertTrue(cmd is VoiceCommand.StartStudy)
        assertEquals("Toronto Notes", (cmd as VoiceCommand.StartStudy).deck)

        val cmdAr = manager.parseCommand("ابدأ دراسة القلبية")
        assertTrue(cmdAr is VoiceCommand.StartStudy)
        assertEquals("القلبية", (cmdAr as VoiceCommand.StartStudy).deck)
    }

    @Test
    fun testStopSpeakingCommands() {
        // "stop speaking" cancels speech only — plain "stop" still ends the session.
        assertEquals(VoiceCommand.StopSpeaking, manager.parseCommand("stop speaking"))
        assertEquals(VoiceCommand.StopSpeaking, manager.parseCommand("quiet"))
        assertEquals(VoiceCommand.StopSpeaking, manager.parseCommand("enough"))
        assertEquals(VoiceCommand.StopSpeaking, manager.parseCommand("اسكت"))
        assertEquals(VoiceCommand.EndSession, manager.parseCommand("stop"))
        assertEquals(VoiceCommand.EndSession, manager.parseCommand("end session"))
    }

    @Test
    fun testContextAwareStudyParsing() {
        val dummyCard = StudyCard("c1", "Test Question")

        // While Listening: long spoken content is an answer, not an unknown command
        val stateListening = StudyState.Listening(dummyCard, "")
        val resultAnswer = manager.parseInStudyContext("Volume greater than 30 mL and midline shift", stateListening)
        assertTrue(resultAnswer is VoiceCommand.SubmitAnswer)
        assertEquals("Volume greater than 30 mL and midline shift", (resultAnswer as VoiceCommand.SubmitAnswer).answer)

        // While Listening: voice command "repeat" or "hint" is recognized as command
        val resultRepeat = manager.parseInStudyContext("repeat question", stateListening)
        assertEquals(VoiceCommand.Repeat, resultRepeat)

        // While Waiting for Rating: "hard" -> Hard
        val stateWaiting = StudyState.WaitingForRating(dummyCard, com.studyagent.client.core.models.Evaluation())
        val resultHard = manager.parseInStudyContext("Hard", stateWaiting)
        assertEquals(VoiceCommand.Hard, resultHard)
    }
}
