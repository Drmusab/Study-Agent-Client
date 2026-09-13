package com.studyagent.client.core.study

import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.StudyCard
import com.studyagent.client.core.voice.stt.RecognitionPurpose
import com.studyagent.client.core.voice.tts.SpeechRequest

/**
 * Side effects emitted by the reducer. The executor performs them; completion
 * produces new events (§63). Effects are separated from state so intent is
 * explicit and testable without real I/O.
 */
sealed interface StudyEffect {
    /** Network effects carry an explicit messageId for idempotency & dedup. */
    sealed interface Network : StudyEffect {
        val messageId: String
        data class Send(override val messageId: String, val message: ClientMessage) : Network
    }

    sealed interface Voice : StudyEffect {
        data class Speak(val request: SpeechRequest, val effectId: String) : Voice
        data class CancelSpeech(val reason: String) : Voice
        data class StartRecognition(val purpose: RecognitionPurpose, val cardId: String?, val effectId: String) : Voice
        data class CancelRecognition(val reason: String) : Voice
        data class StopListening(val reason: String) : Voice
    }

    data class ScheduleTimeout(val actionId: String, val delayMs: Long, val type: PendingAction.ActionType) : StudyEffect
    data class CancelTimeout(val actionId: String) : StudyEffect
    data class PersistRecoverySnapshot(val sessionId: String, val deck: String?, val cardId: String?, val timestampMs: Long) : StudyEffect
    data class LogTransition(val from: SessionPhase, val event: String, val to: SessionPhase, val cardId: String?, val epoch: Long) : StudyEffect
    data class LogRejected(val event: String, val reason: String, val phase: SessionPhase, val cardId: String?) : StudyEffect
    data class UpdateDiagnostics(val state: SessionMachineState) : StudyEffect
    data object ClearRecoverySnapshot : StudyEffect
}

/**
 * Short utterance/effect identity (§14/§104).
 *
 * The id only has to be unique *within one process run*: every consumer compares ids it received
 * earlier in the same run (stale-callback validation, turn ownership). It deliberately contains
 * no wall clock and no randomness, so two runs of the same event sequence produce the same ids —
 * which is what makes a seeded chaos run reproducible (§148).
 *
 * `System.nanoTime()` used to be part of the id. Besides being nondeterministic, it made the ids
 * unsortable and unreadable in a failure report for no benefit.
 */
object EffectIds {
    private var counter = 0L

    fun next(prefix: String): String = synchronized(this) { "${prefix}-${++counter}" }

    /** Test hook: returns the generator to a known state so ids are comparable across tests. */
    fun resetForTests() = synchronized(this) { counter = 0L }

    /** How many ids have been handed out — used by leak assertions in long simulations. */
    val issued: Long get() = synchronized(this) { counter }
}
