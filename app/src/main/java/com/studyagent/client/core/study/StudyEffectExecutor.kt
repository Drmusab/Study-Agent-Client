package com.studyagent.client.core.study

import com.studyagent.client.core.audio.AudioRouteManager
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.voice.stt.RecognitionPolicyFactory
import com.studyagent.client.core.voice.stt.RecognitionPurpose
import com.studyagent.client.core.voice.stt.SpeechRecognitionOrchestrator
import com.studyagent.client.core.voice.stt.toSttSettings
import com.studyagent.client.core.voice.tts.SpeechOrchestrator
import com.studyagent.client.core.voice.tts.SpeechResult
import com.studyagent.client.core.voice.tts.StopReason
import com.studyagent.client.core.voice.tts.toTtsSettings
import com.studyagent.client.data.repository.ConnectionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Executes effects emitted by the reducer.
 * Completion of async effects produces new events via [dispatch].
 * This is the sole place that performs I/O (network, TTS, STT).
 */
class StudyEffectExecutor(
    private val connectionRepository: ConnectionRepository,
    private val speechOrchestrator: SpeechOrchestrator,
    private val recognitionOrchestrator: SpeechRecognitionOrchestrator,
    private val audioRouteManager: AudioRouteManager,
    private val settingsFlow: Flow<AppSettings>,
    private val scope: CoroutineScope,
    private val dispatch: (StudyEvent) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var currentSettings = AppSettings()
    private var settingsJob: Job? = null
    private var lastSttStartMs: Long = Long.MIN_VALUE / 2

    private val policyFactory = RecognitionPolicyFactory()
    private val tag = "EffectExecutor"

    init {
        settingsJob = scope.launch {
            settingsFlow.collect { s ->
                currentSettings = s
                speechOrchestrator.updateSettings(s.toTtsSettings())
                recognitionOrchestrator.updateSettings(s.toSttSettings())
            }
        }
    }

    suspend fun execute(effect: StudyEffect) {
        when (effect) {
            is StudyEffect.Network.Send -> {
                val ok = connectionRepository.send(effect.message)
                if (!ok) {
                    AppLogger.w(tag, "Send failed for ${effect.message.type} id=${effect.messageId}")
                    // Map to ledger retryable: trigger timeout handling
                    dispatch(StudyEvent.ActionTimedOut(effect.messageId, mapActionType(effect.message)))
                }
            }
            is StudyEffect.Voice.Speak -> {
                scope.launch {
                    val result = speechOrchestrator.speak(effect.request)
                    // Map to typed completion with effectId
                    val cardId = effect.request.id // may contain prefix; extract from cardTurn instead? reducer should have provided cardId.
                    // We use request.text's card association via effectId tracking in machine; dispatch generic.
                    // The machine tracks activeSpeechEffectId; we dispatch based on purpose.
                    when (effect.request.purpose) {
                        com.studyagent.client.core.voice.tts.SpeechPurpose.QUESTION -> dispatch(StudyEvent.QuestionSpeechCompleted(cardId, effect.effectId, result is SpeechResult.Completed))
                        com.studyagent.client.core.voice.tts.SpeechPurpose.FEEDBACK -> dispatch(StudyEvent.FeedbackSpeechCompleted(cardId, effect.effectId, result is SpeechResult.Completed))
                        com.studyagent.client.core.voice.tts.SpeechPurpose.HINT -> dispatch(StudyEvent.HintSpeechCompleted(cardId, effect.effectId, result is SpeechResult.Completed))
                        com.studyagent.client.core.voice.tts.SpeechPurpose.EXPLANATION -> dispatch(StudyEvent.ExplanationSpeechCompleted(cardId, effect.effectId, result is SpeechResult.Completed))
                        else -> dispatch(StudyEvent.FeedbackSpeechCompleted(cardId, effect.effectId, result is SpeechResult.Completed))
                    }
                }
            }
            is StudyEffect.Voice.CancelSpeech -> {
                val reason = when (effect.reason) {
                    "pause" -> StopReason.PAUSE
                    "session-finished" -> StopReason.SESSION_END
                    "session-finished-reconcile" -> StopReason.SESSION_END
                    "route-lost" -> StopReason.ROUTE_LOST
                    else -> StopReason.USER
                }
                speechOrchestrator.stopSpeech(reason)
            }
            is StudyEffect.Voice.StartRecognition -> {
                beginStt(effect.purpose, effect.cardId, effect.effectId)
            }
            is StudyEffect.Voice.CancelRecognition -> {
                recognitionOrchestrator.cancelCurrentTurn(effect.reason)
            }
            is StudyEffect.Voice.StopListening -> {
                recognitionOrchestrator.finishCurrentTurn()
            }
            is StudyEffect.ScheduleTimeout -> {
                scope.launch {
                    delay(effect.delayMs)
                    dispatch(StudyEvent.ActionTimedOut(effect.actionId, effect.type))
                }
            }
            is StudyEffect.CancelTimeout -> {
                // Handled in machine's timeoutJobs; no-op here
            }
            else -> Unit
        }
    }

    private fun beginStt(purpose: RecognitionPurpose, cardId: String?, effectId: String) {
        val now = clock()
        if (now - lastSttStartMs < 150L) {
            AppLogger.d(tag, "STT start deduplicated (${now - lastSttStartMs}ms)")
            return
        }
        lastSttStartMs = now
        // TTS settled check
        if (speechOrchestrator.isSpeaking.value || speechOrchestrator.health.value.queueDepth > 0) {
            AppLogger.d(tag, "STT start deferred: speech active")
            return
        }
        if (!recognitionOrchestrator.state.value.isReadyForNewRequest) {
            AppLogger.w(tag, "Recognition not ready")
            return
        }
        if (!currentSettings.handsFreeMode && purpose != RecognitionPurpose.PUSH_TO_TALK_ANSWER && purpose != RecognitionPurpose.PUSH_TO_TALK_COMMAND) {
            // Hands-free off: only PTT or manual rating? But reducer already respects settings for rating; we allow if not handsFree but waiting for answer still may need PTT only
            // For now, respect reducer's decision: if reducer emitted StartRecognition while handsFree=false, honor it only if it's PTT
            return
        }
        if (purpose == RecognitionPurpose.RATING && !currentSettings.listenForSpokenRating) {
            AppLogger.d(tag, "Spoken ratings disabled")
            return
        }
        val request = policyFactory.createRequest(
            purpose = purpose,
            settings = currentSettings.toSttSettings(),
            cardId = cardId,
            contextTerms = emptyList()
        ).copy(id = effectId) // preserve effectId for turn ownership
        val result = recognitionOrchestrator.startRecognition(request)
        AppLogger.i(tag, "STT start purpose=${purpose.name} result=${result::class.simpleName} id=$effectId")
    }

    private fun mapActionType(msg: com.studyagent.client.core.models.ClientMessage): PendingAction.ActionType = when (msg) {
        is com.studyagent.client.core.models.ClientMessage.StartSession -> PendingAction.ActionType.START_SESSION
        is com.studyagent.client.core.models.ClientMessage.SubmitAnswer -> PendingAction.ActionType.SUBMIT_ANSWER
        is com.studyagent.client.core.models.ClientMessage.RateCard -> PendingAction.ActionType.RATE_CARD
        is com.studyagent.client.core.models.ClientMessage.PauseSession -> PendingAction.ActionType.PAUSE_SESSION
        is com.studyagent.client.core.models.ClientMessage.ResumeSession -> PendingAction.ActionType.RESUME_SESSION
        is com.studyagent.client.core.models.ClientMessage.EndSession -> PendingAction.ActionType.END_SESSION
        is com.studyagent.client.core.models.ClientMessage.SkipCard -> PendingAction.ActionType.SKIP_CARD
        is com.studyagent.client.core.models.ClientMessage.RequestHint -> PendingAction.ActionType.REQUEST_HINT
        is com.studyagent.client.core.models.ClientMessage.RequestExplanation -> PendingAction.ActionType.REQUEST_EXPLANATION
        is com.studyagent.client.core.models.ClientMessage.RequestAnswer -> PendingAction.ActionType.REQUEST_ANSWER
        is com.studyagent.client.core.models.ClientMessage.RepeatQuestion -> PendingAction.ActionType.REPEAT_QUESTION
        is com.studyagent.client.core.models.ClientMessage.RequestSessionStatus -> PendingAction.ActionType.REQUEST_SESSION_STATUS
        is com.studyagent.client.core.models.ClientMessage.RequestSessionSnapshot -> PendingAction.ActionType.REQUEST_SESSION_STATUS
        else -> PendingAction.ActionType.REQUEST_SESSION_STATUS
    }

}
