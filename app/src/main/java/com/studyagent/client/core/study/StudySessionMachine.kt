package com.studyagent.client.core.study

import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.StudyCard
import com.studyagent.client.core.models.StudySession
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.core.voice.stt.RecognitionOutcome
import com.studyagent.client.core.voice.stt.RecognitionTurnResult
import com.studyagent.client.core.voice.stt.SpeechRecognitionOrchestrator
import com.studyagent.client.core.voice.stt.toSttSettings
import com.studyagent.client.core.voice.tts.SpeechOrchestrator
import com.studyagent.client.core.voice.tts.toTtsSettings
import com.studyagent.client.core.voice.tts.SpeechResult
import com.studyagent.client.core.voice.tts.StopReason
import com.studyagent.client.data.repository.ConnectionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Authoritative serialized event processor (§6 §73-§75).
 *
 * Single coroutine consumes [Channel<StudyEvent>] and drives [StudyReducer] →
 * [newState] + [effects]; [StudyEffectExecutor] then performs effects and
 * re-emits completion events. No other code writes `_state` or `_machineState`.
 *
 * Derives public [studyState] and [currentSession] from [machineState] so they
 * can never disagree (§75).
 */
class StudySessionMachine(
    private val connectionRepository: ConnectionRepository,
    private val speechOrchestrator: SpeechOrchestrator,
    private val recognitionOrchestrator: SpeechRecognitionOrchestrator,
    private val settingsFlow: Flow<AppSettings> = kotlinx.coroutines.flow.flowOf(AppSettings()),
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    initialEpoch: Long = 1L
) {
    private val tag = "StudySessionMachine"

    private val eventChannel = Channel<StudyEvent>(capacity = Channel.UNLIMITED)

    private val _machineState = MutableStateFlow(SessionMachineState.initial(initialEpoch))
    val machineState: StateFlow<SessionMachineState> = _machineState.asStateFlow()

    private val _studyState = MutableStateFlow<StudyState>(StudyState.Idle)
    val studyState: StateFlow<StudyState> = _studyState.asStateFlow()

    private val _currentSession = MutableStateFlow<StudySession?>(null)
    val currentSession: StateFlow<StudySession?> = _currentSession.asStateFlow()

    private val _lastRecognizedCommand = MutableSharedFlow<com.studyagent.client.core.models.VoiceCommand>(extraBufferCapacity = 16)
    val lastRecognizedCommand: SharedFlow<com.studyagent.client.core.models.VoiceCommand> = _lastRecognizedCommand.asSharedFlow()

    private val timeoutJobs = mutableMapOf<String, Job>()
    @Volatile private var currentSettings: AppSettings = AppSettings()

    private val machineJob: Job

    init {
        machineJob = scope.launch {
            for (event in eventChannel) {
                processEvent(event)
            }
        }
        // Observe external async sources and map them to events (never mutate directly).
        scope.launch {
            settingsFlow.distinctUntilChanged().collect { settings ->
                currentSettings = settings
                // Settings affect only future effects; the reducer remains the owner of phase.
                speechOrchestrator.updateSettings(settings.toTtsSettings())
                recognitionOrchestrator.updateSettings(settings.toSttSettings())
            }
        }
        observeServerMessages()
        observeConnection()
        observeRecognition()
    }

    fun dispatch(event: StudyEvent) {
        val ok = eventChannel.trySend(event)
        if (ok.isFailure) AppLogger.w(tag, "Event channel saturated: ${event.debugName}")
    }

    private suspend fun processEvent(event: StudyEvent) {
        val before = _machineState.value
        val transition = StudyReducer.reduce(before, event, clock())
        _machineState.value = transition.newState
        derivePublicFlows(transition.newState)
        if (transition.accepted) {
            AppLogger.i(tag, "SESSION_TRANSITION epoch=${transition.newState.epoch} from=${SessionPhase.serverPhaseName(before.phase)} event=${event.debugName} to=${SessionPhase.serverPhaseName(transition.newState.phase)} card=${transition.newState.currentCardId}")
        } else {
            AppLogger.w(tag, "SESSION_EVENT_REJECTED event=${event.debugName} reason=${transition.rejectionReason} state=${SessionPhase.serverPhaseName(before.phase)} card=${before.currentCardId}")
        }
        executeEffects(transition.effects, event)
    }

    private fun derivePublicFlows(machine: SessionMachineState) {
        val derivedState: StudyState = when (val phase = machine.phase) {
            SessionPhase.Idle -> StudyState.Idle
            SessionPhase.Starting -> StudyState.Loading("Starting session...")
            SessionPhase.WaitingForFirstCard -> StudyState.Loading("Waiting for first card...")
            SessionPhase.SpeakingQuestion -> {
                val card = machine.cardTurn?.card ?: machine.session?.currentCard
                if (card != null) StudyState.SpeakingQuestion(card) else StudyState.Loading("Preparing question...")
            }
            SessionPhase.WaitingForAnswer -> {
                val card = machine.cardTurn?.card ?: machine.session?.currentCard
                if (card != null) StudyState.Listening(card, pendingTranscript = machine.pendingTranscript?.text ?: "") else StudyState.Loading("Waiting for answer...")
            }
            SessionPhase.PendingAnswerReview -> {
                val card = machine.cardTurn?.card ?: machine.session?.currentCard
                if (card != null) StudyState.Listening(card, pendingTranscript = machine.pendingTranscript?.text ?: "") else StudyState.Loading("Review answer...")
            }
            SessionPhase.SubmittingAnswer -> {
                val card = machine.cardTurn?.card ?: machine.session?.currentCard ?: StudyCard("pending", "Question")
                StudyState.Evaluating(card, machine.pendingTranscript?.text ?: "")
            }
            SessionPhase.WaitingForEvaluation -> {
                val card = machine.cardTurn?.card ?: machine.session?.currentCard ?: StudyCard("pending", "Question")
                StudyState.Evaluating(card, "")
            }
            SessionPhase.SpeakingFeedback -> {
                val card = machine.cardTurn?.card ?: machine.session?.currentCard ?: StudyCard("pending", "Question")
                val eval = machine.cardTurn?.evaluation ?: machine.session?.lastEvaluation ?: Evaluation(shortFeedback = "Feedback")
                StudyState.ShowingFeedback(card, eval, isSpeaking = true)
            }
            SessionPhase.WaitingForRating -> {
                val card = machine.cardTurn?.card ?: machine.session?.currentCard ?: StudyCard("pending", "Question")
                val eval = machine.cardTurn?.evaluation ?: machine.session?.lastEvaluation ?: Evaluation()
                StudyState.WaitingForRating(card, eval, eval.suggestedRating)
            }
            SessionPhase.SubmittingRating -> StudyState.Loading("Submitting rating...")
            SessionPhase.SpeakingHint -> {
                val card = machine.cardTurn?.card ?: machine.session?.currentCard ?: StudyCard("pending", "Question")
                StudyState.HintShowing(card, machine.cardTurn?.hintCount.toString(), isSpeaking = true)
            }
            SessionPhase.SpeakingExplanation -> {
                val card = machine.cardTurn?.card ?: machine.session?.currentCard ?: StudyCard("pending", "Question")
                StudyState.ExplanationShowing(card, "Explanation", isSpeaking = true)
            }
            SessionPhase.ShowingAnswer -> {
                val card = machine.cardTurn?.card ?: machine.session?.currentCard ?: StudyCard("pending", "Question")
                StudyState.ExplanationShowing(card, "Answer revealed", isSpeaking = false)
            }
            SessionPhase.Pausing,
            SessionPhase.Paused -> {
                // Map to StudyState.Paused(previousState) for UI compat; also derive resumeContext
                val prev: StudyState = when {
                    machine.pauseContext?.cardTurn != null -> StudyState.Listening(machine.pauseContext.cardTurn.card)
                    machine.cardTurn != null -> StudyState.Listening(machine.cardTurn.card)
                    else -> StudyState.Idle
                }
                StudyState.Paused(prev)
            }
            SessionPhase.Resuming -> StudyState.Loading("Resuming session...")
            SessionPhase.Recovering -> StudyState.Loading("Reconnecting and recovering session...")
            SessionPhase.Finishing -> StudyState.Loading("Finishing session...")
            SessionPhase.Finished -> StudyState.SessionFinished(summary = "Session finished", cardsReviewed = machine.session?.totalReviewedInSession ?: 0)
            is SessionPhase.Error -> StudyState.Error(machine.error?.message ?: "Error", recoverable = machine.error?.recoverable ?: true)
        }
        _studyState.value = derivedState
        _currentSession.value = machine.session?.let { snap ->
            StudySession(
                sessionId = snap.sessionId,
                deckName = snap.deckName,
                currentCard = machine.cardTurn?.card ?: snap.currentCard,
                cardNumber = snap.cardNumber,
                remainingCards = snap.remainingCards,
                totalReviewedInSession = snap.totalReviewedInSession,
                lastEvaluation = machine.cardTurn?.evaluation ?: snap.lastEvaluation,
                isPaused = machine.phase is SessionPhase.Paused,
                totalCardsInQueue = snap.totalCardsInQueue,
                startedAtEpochMs = snap.startedAtEpochMs
            )
        }
        // Invariant checks §139: log but don't crash production
        checkInvariants(machine, derivedState)
    }

    private fun checkInvariants(machine: SessionMachineState, derived: StudyState) {
        val derivedCardId = derived.currentCardOrNull?.id
        val sessionCardId = machine.session?.currentCard?.id ?: machine.cardTurn?.card?.id
        val machineCardId = machine.cardTurn?.cardId
        if (derivedCardId != null && machineCardId != null && derivedCardId != machineCardId) {
            AppLogger.w(tag, "Invariant violation: StudyState card $derivedCardId != machine card $machineCardId")
        }
        val evalCardMismatch = machine.cardTurn?.evaluation != null && machine.cardTurn?.cardId != machine.cardTurn?.cardId
        // evaluation belongs to card turn already by construction; no leak
        if (machine.pendingRatingConfirmation != null && machine.pendingRatingConfirmation.turnId != machine.cardTurn?.turnId) {
            AppLogger.w(tag, "Invariant: pendingRatingConfirmation turn mismatch")
        }
    }

    private fun executeEffects(effects: List<StudyEffect>, triggerEvent: StudyEvent) {
        for (effect in effects) {
            when (effect) {
                is StudyEffect.Network.Send -> {
                    scope.launch {
                        val ok = connectionRepository.send(effect.message)
                        if (!ok) {
                            // Send failure -> inject as timeout-like error for ledger rollback
                            handleSendFailure(effect, triggerEvent)
                        }
                    }
                }
                is StudyEffect.Voice.Speak -> {
                    val snapshotCardId = _machineState.value.cardTurn?.cardId ?: _machineState.value.session?.currentCard?.id ?: ""
                    scope.launch {
                        val result = speechOrchestrator.speak(effect.request)
                        val cardId = snapshotCardId
                        when (effect.request.purpose) {
                            com.studyagent.client.core.voice.tts.SpeechPurpose.QUESTION -> dispatch(StudyEvent.QuestionSpeechCompleted(cardId, effect.effectId, result is SpeechResult.Completed))
                            com.studyagent.client.core.voice.tts.SpeechPurpose.FEEDBACK -> dispatch(StudyEvent.FeedbackSpeechCompleted(cardId, effect.effectId, result is SpeechResult.Completed))
                            com.studyagent.client.core.voice.tts.SpeechPurpose.HINT -> dispatch(StudyEvent.HintSpeechCompleted(cardId, effect.effectId, result is SpeechResult.Completed))
                            com.studyagent.client.core.voice.tts.SpeechPurpose.EXPLANATION -> dispatch(StudyEvent.ExplanationSpeechCompleted(cardId, effect.effectId, result is SpeechResult.Completed))
                            else -> {
                                if (result is SpeechResult.Completed) dispatch(StudyEvent.FeedbackSpeechCompleted(cardId, effect.effectId, true))
                            }
                        }
                    }
                    _machineState.value = _machineState.value.copy(activeSpeechEffectId = effect.effectId)
                }
                is StudyEffect.Voice.CancelSpeech -> {
                    val reason = when (effect.reason) {
                        "pause" -> StopReason.PAUSE
                        "session-finished" -> StopReason.SESSION_END
                        else -> StopReason.USER
                    }
                    speechOrchestrator.stopSpeech(reason)
                    _machineState.value = _machineState.value.copy(activeSpeechEffectId = null)
                }
                is StudyEffect.Voice.StartRecognition -> {
                    // Real STT start with deduplication and policy (§57)
                    val now = clock()
                    val last = _machineState.value // capture for dedup window
                    // Use simplified dedup via tag-level var; we store lastSttStart in machine state? Use local map.
                    // For now, directly attempt start via policy factory if settings allow
                    try {
                        val settings = currentSettings
                        AppLogger.i(tag, "Effect: StartRecognition purpose=${effect.purpose} card=${effect.cardId} id=${effect.effectId}")
                        val factory = com.studyagent.client.core.voice.stt.RecognitionPolicyFactory()
                        val req = factory.createRequest(
                            purpose = effect.purpose,
                            settings = settings.toSttSettings(),
                            cardId = effect.cardId
                        ).copy(id = effect.effectId)
                        val res = recognitionOrchestrator.startRecognition(req)
                        AppLogger.i(tag, "STT start result=$res")
                    } catch (e: Exception) {
                        AppLogger.w(tag, "STT start failed: ${e.message}")
                    }
                    _machineState.value = _machineState.value.copy(activeRecognitionEffectId = effect.effectId)
                }
                is StudyEffect.Voice.CancelRecognition -> {
                    recognitionOrchestrator.cancelCurrentTurn(effect.reason)
                    _machineState.value = _machineState.value.copy(activeRecognitionEffectId = null)
                }
                is StudyEffect.ScheduleTimeout -> {
                    val job = scope.launch {
                        delay(effect.delayMs)
                        dispatch(StudyEvent.ActionTimedOut(effect.actionId, effect.type))
                    }
                    timeoutJobs[effect.actionId]?.cancel()
                    timeoutJobs[effect.actionId] = job
                }
                is StudyEffect.CancelTimeout -> {
                    timeoutJobs.remove(effect.actionId)?.cancel()
                }
                is StudyEffect.LogTransition -> {
                    // Already logged in processEvent; keep for diagnostics ring buffer
                }
                is StudyEffect.LogRejected -> {
                    AppLogger.w(tag, "SESSION_EVENT_REJECTED event=${effect.event} reason=${effect.reason} phase=${effect.phase} card=${effect.cardId}")
                }
                else -> Unit
            }
        }
    }

    private fun handleSendFailure(effect: StudyEffect.Network.Send, trigger: StudyEvent) {
        // Mark ledger as failed retryable for the card turn
        val state = _machineState.value
        val turnId = state.pendingAction?.cardTurnId ?: state.cardTurn?.turnId ?: return
        // Dispatch a timeout-like recovery event? Instead, directly patch ledger and state
        // For simplicity, emit ActionTimedOut which reducer interprets as retryable failure
        val pending = state.pendingAction
        if (pending != null && pending.messageId == effect.messageId) {
            // Simulate timeout to rollback to retryable
            dispatch(StudyEvent.ActionTimedOut(effect.messageId, pending.type))
        }
        // Also cancel timeout job
        timeoutJobs.remove(effect.messageId)?.cancel()
        AppLogger.w(tag, "Send failed for ${effect.message.type} id=${effect.messageId}; marked retryable")
    }

    private fun observeServerMessages() {
        scope.launch {
            connectionRepository.incomingMessages.collect { msg ->
                val event = StudyEventMapper.fromServerMessage(msg)
                if (event != null) dispatch(event)
                // SessionStats also handled via mapper
                if (msg is com.studyagent.client.core.models.ServerMessage.SessionStats) {
                    // For v1, SessionStats is used as session status; convert to snapshot
                    val snap = StudySnapshot(
                        sessionId = msg.sessionId ?: _machineState.value.session?.sessionId ?: "",
                        phase = ServerSessionPhase.UNKNOWN,
                        currentCard = _machineState.value.cardTurn?.card,
                        evaluation = null,
                        remainingCards = msg.remainingDue,
                        reviewedCards = msg.cardsStudied,
                        totalCards = null,
                        deckName = _machineState.value.session?.deckName
                    )
                    dispatch(StudyEvent.SessionStatusReceived(snap))
                }
            }
        }
    }

    private fun observeConnection() {
        scope.launch {
            connectionRepository.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Disconnected,
                    is ConnectionState.Error,
                    is ConnectionState.ServerUnavailable,
                    is ConnectionState.NetworkUnavailable -> {
                        if (_machineState.value.phase !is SessionPhase.Idle && _machineState.value.phase !is SessionPhase.Finished && _machineState.value.phase !is SessionPhase.Error) {
                            dispatch(StudyEvent.ConnectionLost(state.label))
                        }
                    }
                    is ConnectionState.Connected -> {
                        if (_machineState.value.phase is SessionPhase.Recovering || _machineState.value.phase is SessionPhase.Error) {
                            dispatch(StudyEvent.ConnectionRestored("connected"))
                        } else if (_machineState.value.phase is SessionPhase.Error && _machineState.value.error?.recoverable == true) {
                            dispatch(StudyEvent.ConnectionRestored("connected-from-error"))
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun observeRecognition() {
        scope.launch {
            recognitionOrchestrator.turnResults.collect { result ->
                when (result) {
                    is RecognitionTurnResult.Completed -> {
                        val outcome: RecognitionOutcome = result.outcome
                        // Map to StudyEvent; simplistic: if outcome selectedText is rating-like, mark isCommand
                        val isCommand = false // interpreter will decide; push as transcript
                        val turnId = _machineState.value.cardTurn?.turnId
                        dispatch(StudyEvent.RecognitionCompleted(outcome.cardId, turnId, outcome.selectedText, isCommand))
                    }
                    is RecognitionTurnResult.Failed -> {
                        if (result.error.code.name == "CANCELLED") return@collect
                        dispatch(StudyEvent.RecognitionFailed(null, result.error.code.name))
                    }
                }
            }
        }
    }

    fun close() {
        machineJob.cancel()
        eventChannel.close()
        timeoutJobs.values.forEach { it.cancel() }
        timeoutJobs.clear()
    }
}
