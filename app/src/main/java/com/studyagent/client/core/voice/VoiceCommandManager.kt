package com.studyagent.client.core.voice

import com.studyagent.client.core.models.StudyState
import com.studyagent.client.core.models.VoiceCommand
import com.studyagent.client.core.voice.stt.CommandContext
import com.studyagent.client.core.voice.stt.CommandDecision
import com.studyagent.client.core.voice.stt.CommandNormalizer
import com.studyagent.client.core.voice.stt.RecognitionHypothesis
import com.studyagent.client.core.voice.stt.RecognitionOutcome
import com.studyagent.client.core.voice.stt.RecognitionPurpose
import com.studyagent.client.core.voice.stt.SttSettings
import com.studyagent.client.core.voice.stt.VoiceCommandGrammar
import com.studyagent.client.core.voice.stt.VoiceCommandInterpreter

/**
 * Voice command facade.
 *
 * Parsing now lives in [VoiceCommandGrammar] (one explicit phrase table) and
 * context/confidence gating in [VoiceCommandInterpreter]. This class remains the
 * compatibility entry point used by tests and by callers that only have a plain string.
 *
 * ## What changed, and why
 *
 * The previous implementation had two defects that could alter Anki scheduling by accident:
 *
 *  1. **Prefix matching.** `matchesAgain` used `t.startsWith("again")`, so the perfectly
 *     ordinary answer *"again, there is a midline shift"* was parsed as the `Again` rating.
 *     All phrases are now matched whole.
 *  2. **`parseInStudyContext` did not actually constrain anything.** Every branch ended in
 *     `else -> cmd`, so while the app was listening for a *medical answer*, a one-word
 *     transcript such as "good", "stop", "next" or "easy" was executed as a command instead
 *     of being submitted as the answer. Context is now authoritative: during an answer only
 *     explicit multi-word control phrases ("repeat question", "end session") may fire.
 */
class VoiceCommandManager(
    private val grammar: VoiceCommandGrammar = VoiceCommandGrammar(),
    private val interpreter: VoiceCommandInterpreter = VoiceCommandInterpreter()
) {

    /**
     * Parse a single utterance against the controlled grammar, with no study context.
     *
     * Uncontextualised on purpose: it answers "what command does this phrase name?", not
     * "should this phrase be executed right now?". Use [parseInStudyContext] — or better,
     * the interpreter directly — for the second question.
     */
    fun parseCommand(input: String): VoiceCommand {
        val normalized = CommandNormalizer.forCommand(input)
        if (normalized.isBlank()) return VoiceCommand.Unknown("")
        return grammar.parse(normalized, input)?.command ?: VoiceCommand.Unknown(input)
    }

    /**
     * Context-aware parse retained for callers that only have a raw string.
     *
     * New code should go through [VoiceCommandInterpreter.interpret] so that recognizer
     * alternatives and confidence scores can inform the decision — a single string cannot
     * express "rank 0 was 'could' but rank 1 was 'good' at 0.9".
     */
    fun parseInStudyContext(input: String, currentState: StudyState): VoiceCommand {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return VoiceCommand.Unknown("")

        val hypothesis = RecognitionHypothesis(text = trimmed, confidence = null, rank = 0)
        val outcome = RecognitionOutcome(
            requestId = "uncontextualized",
            purpose = if (currentState is StudyState.WaitingForRating) {
                RecognitionPurpose.RATING
            } else {
                RecognitionPurpose.ANSWER
            },
            cardId = currentState.currentCardOrNull?.id,
            hypotheses = listOf(hypothesis),
            selectedText = trimmed,
            selectedHypothesis = hypothesis
        )

        return when (val decision = interpreter.interpret(outcome, contextFor(currentState), SttSettings())) {
            is CommandDecision.Execute -> decision.parsed.command
            is CommandDecision.SubmitAnswer -> VoiceCommand.SubmitAnswer(decision.text)
            // No confident command and no usable answer: report nothing rather than letting
            // a retry prompt or a guess flow back into study logic.
            is CommandDecision.NeedsConfirmation,
            is CommandDecision.Retry,
            is CommandDecision.Ignore -> VoiceCommand.Unknown("")
        }
    }

    /** `StudyState` → the interpreter's framework-free notion of "what is expected now". */
    fun contextFor(state: StudyState): CommandContext = when (state) {
        is StudyState.Listening -> CommandContext.ANSWER_EXPECTED
        is StudyState.WaitingForRating -> CommandContext.RATING_EXPECTED
        is StudyState.ShowingFeedback,
        is StudyState.HintShowing,
        is StudyState.ExplanationShowing -> CommandContext.FEEDBACK_SHOWING
        is StudyState.Paused -> CommandContext.PAUSED
        else -> CommandContext.IDLE
    }
}
