package com.studyagent.client.core.voice.stt

import com.studyagent.client.core.models.VoiceCommand

/**
 * What the study loop currently expects to hear. Mapped from `StudyState` by the
 * repository; kept as its own enum so the interpreter stays framework-free and testable.
 */
enum class CommandContext {
    /** A medical answer is expected (`StudyState.Listening`). */
    ANSWER_EXPECTED,

    /** A rating is expected (`StudyState.WaitingForRating`). */
    RATING_EXPECTED,

    /** Feedback / hint / explanation is on screen. */
    FEEDBACK_SHOWING,

    /** Session paused: only resume/end/status make sense. */
    PAUSED,

    /** No session: only start/resume make sense. */
    IDLE
}

/** What study logic should do with a finished recognition turn (§13/§80). */
sealed interface CommandDecision {
    /** A command cleared its context and confidence gates. Safe to execute. */
    data class Execute(val parsed: ParsedVoiceCommand) : CommandDecision

    /** The transcript is an answer; submit it verbatim to the PC evaluator. */
    data class SubmitAnswer(val text: String, val hypothesis: RecognitionHypothesis?) : CommandDecision

    /**
     * A rating-like utterance was heard but not confidently enough to change Anki
     * scheduling. The caller may ask "I heard 'Good'. Is that correct?" (§14).
     */
    data class NeedsConfirmation(
        val options: List<ParsedVoiceCommand>,
        val prompt: String
    ) : CommandDecision

    /** Nothing actionable; re-open the microphone with a short prompt. */
    data class Retry(val reason: String, val prompt: String) : CommandDecision

    /** Deliberately do nothing (e.g. an ambient utterance outside any expected window). */
    data class Ignore(val reason: String) : CommandDecision
}

/**
 * Context-authoritative interpretation of a recognition turn (§13/§14/§79/§80).
 *
 * The governing rule: **study state decides whether speech is a command at all.** While an
 * answer is expected, only the explicit multi-word control phrases in
 * [VoiceCommandGrammar.answerSafePhrases] may fire — so a medical answer that happens to
 * contain "good", "stop", "next" or "again" is submitted as an answer instead of silently
 * rating or ending the session.
 */
class VoiceCommandInterpreter(
    private val selector: RecognitionCandidateSelector = RecognitionCandidateSelector(),
    private val grammar: VoiceCommandGrammar = VoiceCommandGrammar()
) {

    fun interpret(
        outcome: RecognitionOutcome,
        context: CommandContext,
        settings: SttSettings
    ): CommandDecision {
        val hypotheses = outcome.hypotheses
        if (hypotheses.all { it.isBlank }) {
            return CommandDecision.Retry("empty", PROMPT_NOTHING_HEARD)
        }

        return when (context) {
            CommandContext.ANSWER_EXPECTED -> interpretAnswer(hypotheses, settings)
            CommandContext.RATING_EXPECTED -> interpretRating(hypotheses, settings)
            CommandContext.FEEDBACK_SHOWING -> interpretFeedback(hypotheses, settings)
            CommandContext.PAUSED -> interpretRestricted(
                hypotheses, settings,
                allowed = PAUSED_COMMANDS,
                fallback = CommandDecision.Retry("not-a-resume-command", PROMPT_PAUSED)
            )

            CommandContext.IDLE -> interpretRestricted(
                hypotheses, settings,
                allowed = IDLE_COMMANDS,
                fallback = CommandDecision.Ignore("no active session")
            )
        }
    }

    // ------------------------------------------------------------------ answer window

    private fun interpretAnswer(
        hypotheses: List<RecognitionHypothesis>,
        settings: SttSettings
    ): CommandDecision {
        // Only explicit control phrases ("repeat question", "end session") may interrupt an
        // answer. A lone word never does — that is the whole point of §81.
        val command = selector.selectForCommand(
            hypotheses = hypotheses,
            minConfidence = settings.destructiveCommandMinConfidence,
            allowAnswerSafeOnly = true
        )
        if (command != null && command.isExecutable && passesDestructiveBar(command, settings)) {
            return CommandDecision.Execute(command)
        }

        val selected = selector.selectForAnswer(hypotheses)
            ?: return CommandDecision.Retry("no-usable-hypothesis", PROMPT_REPEAT_ANSWER)
        if (selected.isBlank) {
            return CommandDecision.Retry("blank-transcript", PROMPT_REPEAT_ANSWER)
        }
        return CommandDecision.SubmitAnswer(CommandNormalizer.forAnswer(selected.text), selected)
    }

    // ------------------------------------------------------------------ rating window

    private fun interpretRating(
        hypotheses: List<RecognitionHypothesis>,
        settings: SttSettings
    ): CommandDecision {
        val match = selector.selectForCommand(hypotheses, settings.ratingMinConfidence)

        if (match != null && match.command.commandName in RATING_COMMAND_NAMES) {
            val confidence = match.sourceHypothesis?.confidence
            return when {
                // No score reported at all: an exact grammar hit is the only evidence we
                // will ever get, and blocking it would disable spoken ratings on every
                // device that does not publish CONFIDENCE_SCORES.
                confidence == null && match.confidence == CommandConfidence.EXACT ->
                    CommandDecision.Execute(match)

                confidence != null && confidence >= settings.ratingConfirmationThreshold ->
                    CommandDecision.Execute(match)

                // Heard something rating-like but not confidently enough to reschedule a
                // card. Confirm if the user asked for it, otherwise simply re-listen (§140).
                confidence != null && confidence >= settings.ratingMinConfidence ->
                    if (settings.confirmAmbiguousRating) {
                        CommandDecision.NeedsConfirmation(
                            options = listOf(match),
                            prompt = confirmationPrompt(match)
                        )
                    } else {
                        CommandDecision.Retry("low-confidence-rating", PROMPT_REPEAT_RATING)
                    }

                else -> CommandDecision.Retry("unconfirmed-rating", PROMPT_REPEAT_RATING)
            }
        }

        // Not a rating. Navigation commands are still valid here ("repeat question").
        val navigation = selector.selectForCommand(hypotheses, settings.destructiveCommandMinConfidence)
        if (navigation != null && navigation.isExecutable && passesDestructiveBar(navigation, settings)) {
            return CommandDecision.Execute(navigation)
        }

        // The user may simply be answering again. Submitting is reversible in a way that a
        // wrong rating is not, so prefer it over discarding the utterance — however long it is.
        val selected = selector.selectForAnswer(hypotheses)
        if (selected != null && !selected.isBlank) {
            return CommandDecision.SubmitAnswer(CommandNormalizer.forAnswer(selected.text), selected)
        }

        return CommandDecision.Retry("no-rating-detected", PROMPT_REPEAT_RATING)
    }

    // ------------------------------------------------------------------ feedback window

    private fun interpretFeedback(
        hypotheses: List<RecognitionHypothesis>,
        settings: SttSettings
    ): CommandDecision {
        val match = selector.selectForCommand(hypotheses, settings.ratingMinConfidence)
        if (match != null && match.isExecutable && passesDestructiveBar(match, settings)) {
            return CommandDecision.Execute(match)
        }
        if (match != null && match.confidence == CommandConfidence.AMBIGUOUS) {
            return CommandDecision.Retry("ambiguous-command", PROMPT_NOT_CAUGHT)
        }
        return CommandDecision.Retry("no-command-detected", PROMPT_RATING_OR_COMMAND)
    }

    // ------------------------------------------------------------------ restricted windows

    private fun interpretRestricted(
        hypotheses: List<RecognitionHypothesis>,
        settings: SttSettings,
        allowed: Set<String>,
        fallback: CommandDecision
    ): CommandDecision {
        val match = selector.selectForCommand(hypotheses, settings.destructiveCommandMinConfidence)
            ?: return fallback
        if (match.command.commandName !in allowed) return fallback
        if (!match.isExecutable || !passesDestructiveBar(match, settings)) return fallback
        return CommandDecision.Execute(match)
    }

    // ------------------------------------------------------------------ gates

    /**
     * Destructive commands (Again / Skip / EndSession) require a **verbatim** grammar hit.
     *
     * A one-edit fuzzy match is deliberately not enough, however confident the recognizer
     * claims to be: "stoop" → "stop" would otherwise end a study session, and no confidence
     * score makes that trade worth it (§79). Navigation commands keep the looser HIGH bar.
     */
    private fun passesDestructiveBar(match: ParsedVoiceCommand, settings: SttSettings): Boolean {
        if (match.command.commandName !in grammar.destructiveCommands()) {
            return match.isExecutable
        }
        if (match.confidence != CommandConfidence.EXACT) return false
        // A reported score still has to clear the destructive floor; a missing score means
        // the recognizer publishes none at all, so the verbatim phrase is the only evidence
        // available and is accepted.
        val confidence = match.sourceHypothesis?.confidence ?: return true
        return confidence >= settings.destructiveCommandMinConfidence
    }

    private fun confirmationPrompt(match: ParsedVoiceCommand): String =
        "I heard \"${match.command.commandName}\". Is that correct?"

    companion object {
        val RATING_COMMAND_NAMES = setOf("Again", "Hard", "Good", "Easy")

        private val PAUSED_COMMANDS = setOf("Resume", "EndSession", "StopSpeaking", "StatusQuestion")
        private val IDLE_COMMANDS = setOf("StartStudy", "Resume", "StatusQuestion")

        const val PROMPT_NOTHING_HEARD = "Sorry, I didn't catch that. Please try again."
        const val PROMPT_REPEAT_ANSWER = "Could you repeat your answer?"
        const val PROMPT_REPEAT_RATING = "Sorry, I didn't catch that. Please say your rating again."
        const val PROMPT_NOT_CAUGHT = "Sorry, I didn't catch that. Please try again."
        const val PROMPT_RATING_OR_COMMAND = "Say your rating, or a command like repeat or explain."
        const val PROMPT_PAUSED = "Study is paused. Say resume to continue."

        /** True for commands that alter Anki scheduling — surfaced for diagnostics. */
        fun isRating(command: VoiceCommand): Boolean = command.commandName in RATING_COMMAND_NAMES
    }
}
