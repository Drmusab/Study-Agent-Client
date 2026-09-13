package com.studyagent.client.core.study

import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.VoiceCommand
import java.util.UUID

/**
 * Maps an interpreted [VoiceCommand] onto the [StudyEvent] that performs it (§13/§79).
 *
 * Pure and total: no clock, no Android, no orchestration. Interpretation itself stays in
 * `VoiceCommandInterpreter`, so there is still exactly one grammar and one set of confidence
 * gates; this object only translates an already-accepted command into a session event.
 *
 * Hands-free Phone Mode needs this: on a desk with no headphones the user may never touch the
 * screen, so "good" / "again" / "repeat" have to reach the same reducer path as the buttons —
 * not a parallel phone-only code path (§113).
 */
object SpokenCommandRouter {

    /**
     * @param cardId the card the command applies to (the active turn, never a stale card).
     * @param messageId idempotency id for commands that travel to the server.
     */
    fun toEvent(
        command: VoiceCommand,
        cardId: String?,
        messageId: String = UUID.randomUUID().toString()
    ): StudyEvent? = when (command) {
        VoiceCommand.Again -> cardId?.let { StudyEvent.UserRateCard(Rating.AGAIN, it) }
        VoiceCommand.Hard -> cardId?.let { StudyEvent.UserRateCard(Rating.HARD, it) }
        VoiceCommand.Good -> cardId?.let { StudyEvent.UserRateCard(Rating.GOOD, it) }
        VoiceCommand.Easy -> cardId?.let { StudyEvent.UserRateCard(Rating.EASY, it) }

        VoiceCommand.Repeat -> StudyEvent.UserRequestRepeat(cardId)
        VoiceCommand.Hint -> StudyEvent.UserRequestHint(cardId)
        VoiceCommand.Explain -> StudyEvent.UserRequestExplanation(cardId)
        VoiceCommand.ShowAnswer -> StudyEvent.UserRequestAnswer(cardId)
        VoiceCommand.Skip -> StudyEvent.UserSkipRequested(cardId)

        VoiceCommand.Pause -> StudyEvent.UserPauseRequested(messageId)
        VoiceCommand.Resume -> StudyEvent.UserResumeRequested(messageId)
        VoiceCommand.StopSpeaking -> StudyEvent.UserStopSpeaking
        VoiceCommand.Stop, VoiceCommand.EndSession -> StudyEvent.UserEndRequested(messageId)

        is VoiceCommand.StartStudy -> StudyEvent.UserStartRequested(command.deck, messageId)

        // Nothing actionable as a session event: the transcript path owns these.
        VoiceCommand.StatusQuestion,
        is VoiceCommand.SubmitAnswer,
        is VoiceCommand.Unknown -> null
    }

    /**
     * Rating commands change Anki scheduling, so they are the one family that must be
     * unambiguous: an utterance that only *might* be a rating is never executed (§14).
     */
    fun isRatingCommand(command: VoiceCommand): Boolean = when (command) {
        VoiceCommand.Again, VoiceCommand.Hard, VoiceCommand.Good, VoiceCommand.Easy -> true
        else -> false
    }
}
