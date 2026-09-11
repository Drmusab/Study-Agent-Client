package com.studyagent.client.stt

import com.studyagent.client.core.models.VoiceCommand
import com.studyagent.client.core.voice.stt.CommandContext
import com.studyagent.client.core.voice.stt.CommandDecision
import com.studyagent.client.core.voice.stt.CommandNormalizer
import com.studyagent.client.core.voice.stt.RecognitionHypothesis
import com.studyagent.client.core.voice.stt.RecognitionOutcome
import com.studyagent.client.core.voice.stt.RecognitionPurpose
import com.studyagent.client.core.voice.stt.SttSettings
import com.studyagent.client.core.voice.stt.VoiceCommandInterpreter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Context-authoritative command interpretation (§13/§14/§79/§80/§106/§107).
 *
 * These are the tests that guard the failure mode that actually damages a study session:
 * an ordinary medical answer being executed as a rating.
 */
class VoiceCommandInterpreterTest {

    private val interpreter = VoiceCommandInterpreter()
    private val settings = SttSettings()

    private fun outcome(
        vararg hypotheses: Pair<String, Float?>,
        purpose: RecognitionPurpose = RecognitionPurpose.ANSWER
    ): RecognitionOutcome {
        val list = hypotheses.mapIndexed { index, (text, confidence) ->
            RecognitionHypothesis(text = text, confidence = confidence, rank = index)
        }
        return RecognitionOutcome(
            requestId = "test",
            purpose = purpose,
            hypotheses = list,
            selectedText = list.firstOrNull()?.text.orEmpty(),
            selectedHypothesis = list.firstOrNull()
        )
    }

    // ------------------------------------------------------------------ §13 answer safety

    @Test
    fun `a one word answer that happens to be a rating word is submitted, not rated`() {
        val decision = interpreter.interpret(
            outcome("good" to 0.95f),
            CommandContext.ANSWER_EXPECTED,
            settings
        )
        assertTrue(
            "\"good\" during an answer must be the answer, not a rating",
            decision is CommandDecision.SubmitAnswer
        )
        assertEquals("good", (decision as CommandDecision.SubmitAnswer).text)
    }

    @Test
    fun `an answer beginning with again is not treated as the Again rating`() {
        // The previous implementation used startsWith("again") and rated the card.
        val decision = interpreter.interpret(
            outcome("again, there is a midline shift of more than five millimeters" to 0.9f),
            CommandContext.ANSWER_EXPECTED,
            settings
        )
        assertTrue(decision is CommandDecision.SubmitAnswer)
    }

    @Test
    fun `medical answers containing command words are submitted verbatim`() {
        listOf(
            "stop the infusion if the pressure drops",
            "the next step is a decompressive craniectomy",
            "easy to miss on a non-contrast CT",
            "why the ICP rises is explained by the Monro-Kellie doctrine"
        ).forEach { text ->
            val decision = interpreter.interpret(
                outcome(text to 0.9f),
                CommandContext.ANSWER_EXPECTED,
                settings
            )
            assertTrue("must submit: \"$text\"", decision is CommandDecision.SubmitAnswer)
            assertEquals(text, (decision as CommandDecision.SubmitAnswer).text)
        }
    }

    @Test
    fun `explicit control phrases still work during an answer`() {
        // §81: the escape hatch is a clear multi-word phrase, never a single word.
        val decision = interpreter.interpret(
            outcome("repeat question" to 0.9f),
            CommandContext.ANSWER_EXPECTED,
            settings
        )
        assertTrue(decision is CommandDecision.Execute)
        assertEquals(VoiceCommand.Repeat, (decision as CommandDecision.Execute).parsed.command)
    }

    @Test
    fun `answer text is not rewritten on the way through`() {
        // §38: STT never "corrects" medical content.
        val decision = interpreter.interpret(
            outcome("The dose is fifteen milligrams of mannitol" to 0.9f),
            CommandContext.ANSWER_EXPECTED,
            settings
        )
        assertEquals(
            "The dose is fifteen milligrams of mannitol",
            (decision as CommandDecision.SubmitAnswer).text
        )
    }

    // ------------------------------------------------------------------ §12/§106 alternatives

    @Test
    fun `a lower ranked candidate that matches the grammar wins while a rating is expected`() {
        // Recognizer returns "could" first; "good" is second but matches exactly.
        val decision = interpreter.interpret(
            outcome("could" to 0.53f, "good" to 0.92f, "hood" to 0.31f),
            CommandContext.RATING_EXPECTED,
            settings,
        )
        assertTrue(decision is CommandDecision.Execute)
        assertEquals(VoiceCommand.Good, (decision as CommandDecision.Execute).parsed.command)
    }

    @Test
    fun `the same alternatives are NOT reinterpreted as a rating during an answer`() {
        val decision = interpreter.interpret(
            outcome("could" to 0.53f, "good" to 0.92f, "hood" to 0.31f),
            CommandContext.ANSWER_EXPECTED,
            settings
        )
        assertTrue(
            "in answer mode the top hypothesis is the answer",
            decision is CommandDecision.SubmitAnswer
        )
        assertEquals("could", (decision as CommandDecision.SubmitAnswer).text)
    }

    // ------------------------------------------------------------------ §14/§107 low confidence

    @Test
    fun `a low confidence rating never silently reschedules the card`() {
        val decision = interpreter.interpret(
            outcome("easy" to 0.12f),
            CommandContext.RATING_EXPECTED,
            settings
        )
        assertTrue(
            "a 0.12 confidence \"easy\" must not rate the card",
            decision !is CommandDecision.Execute
        )
    }

    @Test
    fun `an uncertain but plausible rating asks for confirmation when that is enabled`() {
        val decision = interpreter.interpret(
            outcome("good" to 0.50f),
            CommandContext.RATING_EXPECTED,
            settings.copy(confirmAmbiguousRating = true)
        )
        assertTrue("expected confirmation, got $decision", decision is CommandDecision.NeedsConfirmation)
    }

    @Test
    fun `an uncertain rating re-listens instead of confirming when confirmation is off`() {
        val decision = interpreter.interpret(
            outcome("good" to 0.50f),
            CommandContext.RATING_EXPECTED,
            settings.copy(confirmAmbiguousRating = false)
        )
        assertTrue(decision is CommandDecision.Retry)
    }

    @Test
    fun `a confident exact rating is applied without friction`() {
        val decision = interpreter.interpret(
            outcome("good" to 0.93f),
            CommandContext.RATING_EXPECTED,
            settings
        )
        assertTrue(decision is CommandDecision.Execute)
        assertEquals(VoiceCommand.Good, (decision as CommandDecision.Execute).parsed.command)
    }

    @Test
    fun `a rating still works on a recognizer that reports no confidence at all`() {
        // Devices below API 34 and many providers never populate CONFIDENCE_SCORES; blocking
        // on a missing score would disable spoken ratings there entirely.
        val decision = interpreter.interpret(
            outcome("hard" to null),
            CommandContext.RATING_EXPECTED,
            settings
        )
        assertTrue(decision is CommandDecision.Execute)
        assertEquals(VoiceCommand.Hard, (decision as CommandDecision.Execute).parsed.command)
    }

    // ------------------------------------------------------------------ §79 destructive commands

    @Test
    fun `a destructive command needs an exact phrase, not a near miss`() {
        val decision = interpreter.interpret(
            outcome("stoop" to 0.99f),
            CommandContext.PAUSED,
            settings
        )
        // "stoop" is one edit from "stop"; EndSession must not fire on a fuzzy match.
        assertTrue("fuzzy matches must not end a session", decision !is CommandDecision.Execute)
    }

    @Test
    fun `end session works when the user actually says it`() {
        val decision = interpreter.interpret(
            outcome("end session" to 0.95f),
            CommandContext.PAUSED,
            settings
        )
        assertTrue(decision is CommandDecision.Execute)
        assertEquals(VoiceCommand.EndSession, (decision as CommandDecision.Execute).parsed.command)
    }

    // ------------------------------------------------------------------ §80 restricted contexts

    @Test
    fun `only resume and end are honoured while paused`() {
        val resume = interpreter.interpret(
            outcome("resume" to 0.95f), CommandContext.PAUSED, settings
        )
        assertTrue(resume is CommandDecision.Execute)

        val rating = interpreter.interpret(
            outcome("good" to 0.99f), CommandContext.PAUSED, settings
        )
        assertTrue("a rating is meaningless while paused", rating !is CommandDecision.Execute)
    }

    @Test
    fun `an empty turn asks the user to try again`() {
        val decision = interpreter.interpret(
            RecognitionOutcome(
                requestId = "t",
                purpose = RecognitionPurpose.ANSWER,
                hypotheses = emptyList(),
                selectedText = "",
                selectedHypothesis = null
            ),
            CommandContext.ANSWER_EXPECTED,
            settings
        )
        assertTrue(decision is CommandDecision.Retry)
    }

    // ------------------------------------------------------------------ §75/§76 normalization

    @Test
    fun `arabic alef and ta marbuta variants normalise for command matching`() {
        // أعد / اعد and إنهاء / انهاء must both match.
        assertEquals(CommandNormalizer.forCommand("أعد"), CommandNormalizer.forCommand("اعد"))
        assertEquals(CommandNormalizer.forCommand("إنهاء"), CommandNormalizer.forCommand("انهاء"))

        val decision = interpreter.interpret(
            outcome("أعد السؤال" to 0.9f),
            CommandContext.ANSWER_EXPECTED,
            settings
        )
        assertTrue(decision is CommandDecision.Execute)
        assertEquals(VoiceCommand.Repeat, (decision as CommandDecision.Execute).parsed.command)
    }

    @Test
    fun `arabic diacritics do not break command matching`() {
        assertEquals(CommandNormalizer.forCommand("جَيِّد"), CommandNormalizer.forCommand("جيد"))
    }

    @Test
    fun `answer normalization is conservative and never rewrites content`() {
        // Only whitespace and stray edge punctuation are touched.
        assertEquals(
            "thirty milliliters, not 30 mL",
            CommandNormalizer.forAnswer("  thirty   milliliters, not 30 mL. ")
        )
        assertEquals(
            "GCS fifteen",
            CommandNormalizer.forAnswer("GCS fifteen")
        )
    }

    @Test
    fun `command and answer normalization are separate operations`() {
        // "Good" as a command folds case; as an answer it must be preserved.
        assertEquals("good", CommandNormalizer.forCommand("Good!"))
        assertEquals("Good!", CommandNormalizer.forAnswer("Good!"))
    }
}
