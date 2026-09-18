package com.studyagent.client.core.study

import com.studyagent.client.core.audio.AudioRouteSnapshot
import com.studyagent.client.core.audio.DefaultStudyAudioModeResolver
import com.studyagent.client.core.audio.EffectiveStudyAudioRoute
import com.studyagent.client.core.audio.PhoneModeDiagnostics
import com.studyagent.client.core.audio.SelfEchoDetector
import com.studyagent.client.core.audio.StudyAudioDisconnectPolicy
import com.studyagent.client.core.audio.StudyAudioMode
import com.studyagent.client.core.audio.StudyAudioRouteCoordinator
import com.studyagent.client.core.audio.StudyAudioRouteEvent
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.diagnostics.DiagnosticCategory
import com.studyagent.client.core.diagnostics.DiagnosticTimeline
import com.studyagent.client.core.diagnostics.DiagnosticsFormatting
import com.studyagent.client.core.diagnostics.PerformanceMetrics
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.StudyCard
import com.studyagent.client.core.models.StudySession
import com.studyagent.client.core.models.VoiceCommand
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.core.voice.stt.RecognitionErrorCode
import com.studyagent.client.core.voice.stt.RecognitionOutcome
import com.studyagent.client.core.voice.stt.RecognitionPurpose
import com.studyagent.client.core.voice.stt.CommandContext
import com.studyagent.client.core.voice.stt.CommandDecision
import com.studyagent.client.core.voice.stt.RecognitionTurnResult
import com.studyagent.client.core.voice.stt.VoiceCommandInterpreter
import com.studyagent.client.core.voice.StudyVoiceTurnGate
import com.studyagent.client.core.voice.VoiceTurnBlock
import com.studyagent.client.core.voice.VoiceTurnDecision
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Authoritative serialized event processor (§6 §73-§75).
 *
 * Single coroutine consumes [Channel<StudyEvent>] and drives [StudyReducer] →
 * [newState] + [effects]; effects are then executed inline and completion events
 * re-emitted. No other code writes `_state` or `_machineState`.
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
    initialEpoch: Long = 1L,
    /**
     * Study-audio routing (§70/§112). Optional so headless tests can run the machine without a
     * device: when absent the machine behaves like a bare phone in Auto mode.
     */
    private val audioRouteCoordinator: StudyAudioRouteCoordinator? = null,
    /**
     * Bounded technical metrics (§51/§57). Nullable so a headless unit test can run the machine
     * without a metrics graph; the app always passes the shared instance.
     */
    private val performance: PerformanceMetrics? = null,
    /**
     * Structured diagnostic timeline (§67). Bounded ring buffer; recording an event is a few
     * field writes, so instrumenting the voice path does not create the latency it measures (§55).
     */
    private val timeline: DiagnosticTimeline? = null
) {
    private val tag = "StudySessionMachine"

    private val eventChannel = Channel<StudyEvent>(capacity = Channel.UNLIMITED)

    private val _machineState = MutableStateFlow(SessionMachineState.initial(initialEpoch))
    val machineState: StateFlow<SessionMachineState> = _machineState.asStateFlow()

    private val _studyState = MutableStateFlow<StudyState>(StudyState.Idle)
    val studyState: StateFlow<StudyState> = _studyState.asStateFlow()

    private val _currentSession = MutableStateFlow<StudySession?>(null)
    val currentSession: StateFlow<StudySession?> = _currentSession.asStateFlow()

    private val _lastRecognizedCommand = MutableSharedFlow<VoiceCommand>(extraBufferCapacity = 16)
    val lastRecognizedCommand: SharedFlow<VoiceCommand> = _lastRecognizedCommand.asSharedFlow()

    /**
     * Timeout handles, keyed by action id.
     *
     * Concurrent because effects are executed from `scope.launch` bodies on a dispatcher while the
     * reducer runs on the machine coroutine. Entries are removed by the job's own completion
     * handler, so the map cannot grow with the number of turns (§19/§99).
     */
    private val timeoutJobs = ConcurrentHashMap<String, Job>()
    @Volatile private var currentSettings: AppSettings = AppSettings()

    /** Dispatch/processing counters behind [resourceSnapshot]; never grow without bound. */
    private val eventsDispatched = AtomicLong(0L)
    private val eventsProcessed = AtomicLong(0L)

    /** Invariant violations seen since construction — must stay 0 in every test (§22/§139). */
    private val invariantViolations = AtomicLong(0L)

    /**
     * Wall-clock anchors for the voice-turn latencies (§51). Each is set when the *transport
     * write* happens, not when the UI thinks it happened, so the measurement includes the
     * handoff and not just the coroutine hop.
     */
    @Volatile private var startRequestedAtMs = -1L
    @Volatile private var questionReceivedAtMs = -1L
    @Volatile private var answerSentAtMs = -1L
    @Volatile private var ratingSentAtMs = -1L

    // ------------------------------------------------------------------ phone-mode voice gate
    //
    // Every study-audio route (headphones or phone) goes through this gate before the
    // microphone opens, so there is exactly one implementation of "speak, silence, listen"
    // (§15/§59/§113/§114). The gate is what makes Phone Mode safe: TTS completion, drained
    // queue, route stability, an acoustic gap sized for the speaker, request-generation
    // validation, STT readiness — in that order.

    private val fallbackRoute: EffectiveStudyAudioRoute =
        DefaultStudyAudioModeResolver().resolve(StudyAudioMode.AUTO, AudioRouteSnapshot.PHONE_ONLY)

    private val selfEchoDetector = SelfEchoDetector()

    /** Interprets spoken transcripts as study commands within the current phase context. */
    private val commandInterpreter = VoiceCommandInterpreter()

    /** Invalidates in-flight delayed microphone starts when a turn is superseded (§102-§104). */
    private val sttGeneration = AtomicLong(0L)
    private val sttStartCount = AtomicLong(0L)

    private val voiceTurnGate: StudyVoiceTurnGate = StudyVoiceTurnGate(
        routeProvider = { audioRouteCoordinator?.effectiveRoute?.value ?: fallbackRoute },
        configuredGapMs = { currentSettings.ttsAcousticGapMs },
        speechActive = { speechOrchestrator.isSpeaking.value },
        queueDepth = { speechOrchestrator.health.value.queueDepth },
        voicePaused = {
            val phase = _machineState.value.phase
            phase is SessionPhase.Paused ||
                phase is SessionPhase.Pausing ||
                audioRouteCoordinator?.attention?.value != null
        },
        nowMs = { clock() }
    )

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
        observeAudioRoute()
    }

    fun dispatch(event: StudyEvent) {
        eventsDispatched.incrementAndGet()
        val ok = eventChannel.trySend(event)
        if (ok.isFailure) {
            AppLogger.w(tag, "Event channel saturated: ${event.debugName}")
        }
    }

    private suspend fun processEvent(event: StudyEvent) {
        val before = _machineState.value
        val transition = StudyReducer.reduce(before, event, clock())
        _machineState.value = transition.newState
        derivePublicFlows(transition.newState)
        eventsProcessed.incrementAndGet()
        recordEventDiagnostics(event, before, transition)
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
        // Route changes that are not losses wait for a turn boundary (§40/§41). Everything
        // below this point is "no question/answer in flight", so a preferred route (a headset
        // that just appeared) may be promoted now.
        if (!isVoicePhase(machine.phase)) {
            audioRouteCoordinator?.onSafeTurnBoundary()
        }

        // Invariant checks §139: log but don't crash production
        checkInvariants(machine, derivedState)
    }

    /**
     * Cross-system invariants checked after every state derivation (§22/§139).
     *
     * These are *observations*, not repairs: a violation is logged (and counted) so the chaos and
     * endurance tests fail loudly, but production never mutates state from here. The checks are
     * deliberately about relationships that must hold in every phase — they are the cheap version
     * of the assertions the JVM harness re-runs after every chaos event.
     */
    private fun checkInvariants(machine: SessionMachineState, derived: StudyState) {
        val derivedCardId = derived.currentCardOrNull?.id
        val machineCardId = machine.cardTurn?.cardId ?: machine.session?.currentCard?.id
        if (derivedCardId != null && machineCardId != null && derivedCardId != machineCardId) {
            recordInvariantViolation("card-mismatch", "StudyState card $derivedCardId != machine card $machineCardId")
        }

        // An evaluation belongs to the turn that was answered. If the machine still shows an
        // evaluation while the turn id has moved on, a stale evaluation leaked into a new turn.
        val turn = machine.cardTurn
        if (turn?.evaluation != null) {
            if (turn.turnId.isBlank()) {
                recordInvariantViolation("evaluation-without-turn", "evaluation present on a turn without an id")
            }
            if (machine.currentCardId != null && machine.currentCardId != turn.cardId) {
                recordInvariantViolation("evaluation-card-drift", "evaluation on ${turn.cardId} while current card is ${machine.currentCardId}")
            }
            val legal = machine.phase is SessionPhase.SpeakingFeedback ||
                machine.phase is SessionPhase.WaitingForRating ||
                machine.phase is SessionPhase.SubmittingRating ||
                machine.phase is SessionPhase.Paused ||
                machine.phase is SessionPhase.Pausing
            if (!legal) {
                recordInvariantViolation("evaluation-in-illegal-phase", "evaluation visible in ${SessionPhase.serverPhaseName(machine.phase)}")
            }
        }

        // A pending rating confirmation must belong to the live turn; otherwise it is a stale
        // confirmation from the previous card that could rate the next one (§103).
        machine.pendingRatingConfirmation?.let { pending ->
            if (machine.cardTurn != null && pending.turnId != machine.cardTurn.turnId) {
                recordInvariantViolation("rating-confirmation-turn-mismatch", "pendingRatingConfirmation turn ${pending.turnId} != ${machine.cardTurn.turnId}")
            }
            if (pending.epoch != machine.epoch) {
                recordInvariantViolation("rating-confirmation-epoch-mismatch", "pendingRatingConfirmation epoch ${pending.epoch} != ${machine.epoch}")
            }
        }

        // A paused session must not hold the microphone: pausing is the mechanism that guarantees
        // the user is not recorded while they think study is stopped (§22).
        if (machine.phase.isPaused && machine.activeRecognitionEffectId != null) {
            recordInvariantViolation("paused-with-open-mic", "paused phase still holds recognition effect ${machine.activeRecognitionEffectId}")
        }

        // Finished is terminal for this epoch: no turn may still be open after the session ended.
        if (machine.phase is SessionPhase.Finished && machine.cardTurn != null && machine.pendingAction != null) {
            recordInvariantViolation("finished-with-pending-action", "finished session still holds ${machine.pendingAction.type}")
        }
    }

    /** Logs (and counts) one invariant violation. Never throws: production must not die here. */
    private fun recordInvariantViolation(code: String, detail: String) {
        invariantViolations.incrementAndGet()
        AppLogger.w(tag, "invariant_violation code=$code detail=$detail")
        timeline?.record(
            DiagnosticCategory.SESSION,
            "INVARIANT_VIOLATION",
            sessionEpoch = _machineState.value.epoch,
            turnId = _machineState.value.cardTurn?.turnId,
            metadata = mapOf("code" to code, "detail" to detail)
        )
    }

    /** Violations observed since construction; surfaced through [resourceSnapshot] consumers. */
    val invariantViolationCount: Long get() = invariantViolations.get()

    /** Fallback metrics store when no coordinator is wired (headless tests). */
    private val localMetrics = PhoneModeDiagnostics()

    /**
     * Open the recognizer for a validated turn. Kept separate from the gate so the gate stays
     * free of orchestration details.
     */
    private fun startRecognitionNow(effect: StudyEffect.Voice.StartRecognition) {
        try {
            AppLogger.i(tag, "Effect: StartRecognition purpose=${effect.purpose} card=${effect.cardId} id=${effect.effectId}")
            val factory = com.studyagent.client.core.voice.stt.RecognitionPolicyFactory()
            val req = factory.createRequest(
                purpose = effect.purpose,
                settings = currentSettings.toSttSettings(),
                cardId = effect.cardId,
                contextTerms = emptyList()
            ).copy(id = effect.effectId)
            val res = recognitionOrchestrator.startRecognition(req)
            AppLogger.i(tag, "STT start result=$res")
            performance?.onSttActiveRequests(if (res is com.studyagent.client.core.voice.stt.RecognitionStartResult.Started) 1 else 0)
            sttStartCount.incrementAndGet()
        } catch (e: Exception) {
            AppLogger.w(tag, "STT start failed: ${e.message}")
        }
        _machineState.value = _machineState.value.copy(activeRecognitionEffectId = effect.effectId)
    }

    /**
     * Turn-identity validation for a delayed microphone start (§27/§102/§103/§104). Anything
     * that happened during the acoustic gap — skip, pause, end, a new card, a manual rating —
     * invalidates the pending start, so a stale gap can never open the microphone.
     */
    private fun isSttStartStillValid(effect: StudyEffect.Voice.StartRecognition, generation: Long): Boolean {
        if (sttGeneration.get() != generation) return false
        val state = _machineState.value
        val cardId = state.cardTurn?.cardId ?: state.session?.currentCard?.id
        if (effect.cardId != null && cardId != effect.cardId) return false
        return when (state.phase) {
            is SessionPhase.Idle,
            is SessionPhase.Finished,
            is SessionPhase.Error,
            is SessionPhase.Paused,
            is SessionPhase.Pausing -> false

            is SessionPhase.WaitingForAnswer,
            is SessionPhase.PendingAnswerReview,
            is SessionPhase.SpeakingQuestion,
            is SessionPhase.SpeakingHint,
            is SessionPhase.ShowingAnswer ->
                effect.purpose == RecognitionPurpose.ANSWER ||
                    effect.purpose == RecognitionPurpose.PUSH_TO_TALK_ANSWER
                    // Answers first: that is the only listening window that may open while the
                    // question is still being spoken (push-to-talk cuts speech short).
                    || effect.purpose == RecognitionPurpose.SHORT_CONFIRMATION

            is SessionPhase.WaitingForRating,
            is SessionPhase.SpeakingFeedback,
            is SessionPhase.SpeakingExplanation,
            is SessionPhase.SpeakingHint ->
                effect.purpose == RecognitionPurpose.RATING ||
                    effect.purpose == RecognitionPurpose.PUSH_TO_TALK_COMMAND ||
                    effect.purpose == RecognitionPurpose.SHORT_CONFIRMATION

            is SessionPhase.WaitingForFirstCard,
            is SessionPhase.SubmittingAnswer,
            is SessionPhase.SubmittingRating,
            is SessionPhase.WaitingForEvaluation,
            is SessionPhase.Starting,
            is SessionPhase.Resuming,
            is SessionPhase.Recovering,
            is SessionPhase.Finishing -> true
        }
    }

    /**
     * Voice commands (§13/§14/§79): interpret with the study context, then let the reducer be
     * the sole authority on whether the command is legal right now. `NeedsConfirmation` and
     * `Retry` are deliberately *not* executed — an ambiguous utterance must never re-schedule a
     * card, and there is no phone-specific confirmation flow (§113).
     */
    private fun handleCommandOutcome(outcome: RecognitionOutcome) {
        val state = _machineState.value
        val decided = commandInterpreter.interpret(
            outcome = outcome,
            context = commandContextFor(state.phase),
            settings = currentSettings.toSttSettings()
        )
        when (decided) {
            is CommandDecision.Execute -> {
                if (!decided.parsed.isExecutable) {
                    AppLogger.d(tag, "Voice command ignored (confidence=${decided.parsed.confidence})")
                    return
                }
                _lastRecognizedCommand.tryEmit(decided.parsed.command)
                val event = SpokenCommandRouter.toEvent(decided.parsed.command, state.currentCardId)
                if (event != null) {
                    dispatch(event)
                } else {
                    AppLogger.d(tag, "Voice command has no session event: ${decided.parsed.command.commandName}")
                }
            }
            is CommandDecision.SubmitAnswer -> {
                dispatch(
                    StudyEvent.RecognitionCompleted(
                        outcome.cardId,
                        _machineState.value.cardTurn?.turnId,
                        decided.text,
                        false
                    )
                )
            }
            is CommandDecision.NeedsConfirmation ->
                AppLogger.i(tag, "Voice command needs confirmation; not auto-applied (options=${decided.options.size})")

            is CommandDecision.Retry ->
                AppLogger.d(tag, "Voice command retry: ${decided.reason}")

            is CommandDecision.Ignore ->
                AppLogger.d(tag, "Voice command ignored: ${decided.reason}")
        }
    }

    /** Which window the study loop is in — the interpreter's only input beyond the transcript. */
    private fun commandContextFor(phase: SessionPhase): CommandContext = when (phase) {
        is SessionPhase.Idle, is SessionPhase.Starting, is SessionPhase.WaitingForFirstCard ->
            CommandContext.IDLE
        is SessionPhase.Paused, is SessionPhase.Pausing -> CommandContext.PAUSED
        is SessionPhase.WaitingForRating -> CommandContext.RATING_EXPECTED
        is SessionPhase.SpeakingFeedback,
        is SessionPhase.SpeakingExplanation,
        is SessionPhase.SpeakingHint -> CommandContext.FEEDBACK_SHOWING
        else -> CommandContext.ANSWER_EXPECTED
    }

    /**
     * Reacts to route facts (§36/§39/§44/§96/§97/§118).
     *
     * Note what is missing: any reaction to "no headset at app start". That is not an event,
     * it is the normal Phone Mode environment, and manufacturing a loss from it is exactly the
     * bug this design removes (§44/§119).
     */
    private fun observeAudioRoute() {
        val coordinator = audioRouteCoordinator ?: return
        scope.launch {
            coordinator.events.collect { event -> handleAudioRouteEvent(event) }
        }
    }

    private fun handleAudioRouteEvent(event: StudyAudioRouteEvent) {
        when (event) {
            is StudyAudioRouteEvent.ExternalHeadsetLost -> {
                val cardId = _machineState.value.currentCardId
                // Cancel the interrupted turn safely: speech and recognition both stop, the
                // card is preserved, and nothing resumes mid-sentence (§38/§96/§97).
                sttGeneration.incrementAndGet()
                speechOrchestrator.stopSpeech(StopReason.ROUTE_LOST)
                recognitionOrchestrator.cancelCurrentTurn("input-route-lost")
                performance?.onRouteInterruption()
                timeline?.record(
                    DiagnosticCategory.AUDIO,
                    "ROUTE_LOST",
                    sessionEpoch = _machineState.value.epoch,
                    turnId = _machineState.value.cardTurn?.turnId,
                    metadata = mapOf("policy" to event.policy.name)
                )
                AppLogger.w(tag, "Audio route lost mid-session (policy=${event.policy})")

                when (event.policy) {
                    StudyAudioDisconnectPolicy.PAUSE_VOICE -> {
                        val phase = _machineState.value.phase
                        if (phase !is SessionPhase.Paused && phase !is SessionPhase.Pausing) {
                            dispatch(StudyEvent.UserPauseRequested("route-loss-${clock()}"))
                        }
                    }

                    StudyAudioDisconnectPolicy.CONTINUE_ON_PHONE -> {
                        if (cardId != null && isVoicePhase(_machineState.value.phase)) {
                            // The coordinator has already resolved the phone route; repeat the
                            // current question on the new route. Exactly once (§97).
                            dispatch(StudyEvent.AudioRouteRestored(cardId))
                        }
                    }
                }
            }

            is StudyAudioRouteEvent.ExternalHeadsetConnected ->
                if (event.applied) {
                    AppLogger.i(tag, "Headset route applied at a safe boundary")
                } else {
                    AppLogger.i(tag, "Headset available; switching at the next turn boundary (§41)")
                }

            is StudyAudioRouteEvent.RouteChanged -> {
                if (!event.atSafeBoundary && isVoicePhase(_machineState.value.phase)) {
                    // Manual switch (§42/§84): cancel the in-flight utterance and any pending
                    // microphone start, then repeat the current question on the new route
                    // rather than swapping hardware mid-word.
                    val cardId = _machineState.value.currentCardId
                    if (cardId != null && event.to.generation != event.from.generation) {
                        sttGeneration.incrementAndGet()
                        speechOrchestrator.stopSpeech(StopReason.USER)
                        recognitionOrchestrator.cancelCurrentTurn("route-changed")
                        performance?.onRouteInterruption()
                        timeline?.record(
                            DiagnosticCategory.AUDIO,
                            "ROUTE_CHANGED",
                            sessionEpoch = _machineState.value.epoch,
                            turnId = _machineState.value.cardTurn?.turnId,
                            metadata = mapOf("to" to event.to.effective.name)
                        )
                        dispatch(StudyEvent.AudioRouteRestored(cardId))
                    }
                }
            }

            is StudyAudioRouteEvent.RouteBlocked -> {
                AppLogger.w(tag, "Study audio route blocked: ${event.route.reason ?: "unspecified"}")
                // A preference that forbids headset-free study while a turn is live must not
                // keep talking into the room: cancel the turn and report it as a recoverable
                // problem (§7/§82).
                if (isVoicePhase(_machineState.value.phase)) {
                    sttGeneration.incrementAndGet()
                    speechOrchestrator.stopSpeech(StopReason.USER)
                    recognitionOrchestrator.cancelCurrentTurn("route-blocked")
                    dispatch(StudyEvent.VoiceRouteBlocked(event.route.reason ?: "Study audio route unavailable"))
                }
            }
        }
    }

    private fun isVoicePhase(phase: SessionPhase): Boolean = when (phase) {
        is SessionPhase.SpeakingQuestion,
        is SessionPhase.WaitingForAnswer,
        is SessionPhase.PendingAnswerReview,
        is SessionPhase.SpeakingFeedback,
        is SessionPhase.WaitingForRating,
        is SessionPhase.SpeakingHint,
        is SessionPhase.SpeakingExplanation,
        is SessionPhase.ShowingAnswer -> true
        else -> false
    }

    private fun executeEffects(effects: List<StudyEffect>, triggerEvent: StudyEvent) {
        for (effect in effects) {
            when (effect) {
                is StudyEffect.Network.Send -> {
                    recordOutboundEffect(effect.message)
                    scope.launch {
                        val ok = connectionRepository.send(effect.message)
                        if (!ok) {
                            // Send failure -> inject as timeout-like error for ledger rollback
                            recordSendFailure(effect.message)
                            handleSendFailure(effect, triggerEvent)
                        }
                    }
                }
                is StudyEffect.Voice.Speak -> {
                    val snapshotCardId = _machineState.value.cardTurn?.cardId ?: _machineState.value.session?.currentCard?.id ?: ""
                    if (effect.request.purpose == com.studyagent.client.core.voice.tts.SpeechPurpose.QUESTION) {
                        recordQuestionToSpeechStart()
                    }
                    scope.launch {
                        val result = speechOrchestrator.speak(effect.request)
                        recordSpeechResult(effect.request, result)
                        val cardId = snapshotCardId
                        if (result is SpeechResult.Completed && effect.request.purpose != com.studyagent.client.core.voice.tts.SpeechPurpose.PREVIEW) {
                            // The self-echo window opens when the app stops talking (§18).
                            selfEchoDetector.noteSpoken(effect.request.text, clock())
                        }
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
                    // The microphone never opens from here directly. It opens through the turn
                    // gate, which re-validates the turn after the acoustic gap (§15/§102-§104).
                    val generation = sttGeneration.incrementAndGet()
                    scope.launch {
                        val ptt = effect.purpose == RecognitionPurpose.PUSH_TO_TALK_ANSWER ||
                            effect.purpose == RecognitionPurpose.PUSH_TO_TALK_COMMAND
                        val decision = voiceTurnGate.awaitListenWindow(
                            stillValid = { isSttStartStillValid(effect, generation) },
                            waitForSpeechToSettleMs = if (ptt) PTT_SETTLE_BUDGET_MS else 0L
                        )
                        when (decision) {
                            is VoiceTurnDecision.Blocked -> {
                                AppLogger.d(tag, "STT start blocked (${decision.reason}) purpose=${effect.purpose}")
                                if (decision.reason == VoiceTurnBlock.NO_MICROPHONE) {
                                    (audioRouteCoordinator?.metrics ?: localMetrics).recordMicUnavailableSkip()
                                }
                            }
                            is VoiceTurnDecision.Ready -> {
                                (audioRouteCoordinator?.metrics ?: localMetrics)
                                    .recordHandoffLatency(decision.handoffLatencyMs)
                                val profile = (audioRouteCoordinator?.effectiveRoute?.value ?: fallbackRoute).acousticProfile
                                (audioRouteCoordinator?.metrics ?: localMetrics).recordListenTurn(profile)
                                performance?.let { perf ->
                                    perf.recordSpeechDoneToListen(decision.handoffLatencyMs)
                                    if (profile == com.studyagent.client.core.audio.AcousticProfile.PHONE_SPEAKER) {
                                        perf.onListenTurnOnPhone()
                                    } else {
                                        perf.onListenTurnOnHeadset()
                                    }
                                }
                                timeline?.record(
                                    DiagnosticCategory.STT,
                                    "STT_READY",
                                    sessionEpoch = _machineState.value.epoch,
                                    turnId = _machineState.value.cardTurn?.turnId,
                                    requestId = effect.effectId,
                                    metadata = mapOf(
                                        "purpose" to effect.purpose.name,
                                        "gapMs" to decision.gapMs.toString()
                                    )
                                )
                                startRecognitionNow(effect)
                            }
                        }
                    }
                }
                is StudyEffect.Voice.StopListening -> {
                    // Push-to-talk release: finish the turn and wait for the recognizer's
                    // terminal result. Nothing is submitted at the moment of release (§18/§105).
                    recognitionOrchestrator.finishCurrentTurn()
                    AppLogger.d(tag, "Effect: StopListening (${effect.reason})")
                }
                is StudyEffect.Voice.CancelRecognition -> {
                    // Invalidate any delayed microphone start immediately: the turn this start
                    // belonged to no longer exists (§102 stale gap / §103 pause / §104 end).
                    sttGeneration.incrementAndGet()
                    recognitionOrchestrator.cancelCurrentTurn(effect.reason)
                    performance?.onSttActiveRequests(0)
                    _machineState.value = _machineState.value.copy(activeRecognitionEffectId = null)
                }
                is StudyEffect.ScheduleTimeout -> {
                    val job = scope.launch {
                        delay(effect.delayMs)
                        dispatch(StudyEvent.ActionTimedOut(effect.actionId, effect.type))
                    }
                    // The completion handler is what keeps this map bounded: without it every
                    // action that timed out (or was superseded) left its Job behind for the rest
                    // of the session (§19/§99).
                    job.invokeOnCompletion {
                        timeoutJobs.remove(effect.actionId, job)
                    }
                    timeoutJobs.put(effect.actionId, job)?.cancel()
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
                        timeline?.record(
                            DiagnosticCategory.NETWORK,
                            "CONNECTION_LOST",
                            sessionEpoch = _machineState.value.epoch,
                            turnId = _machineState.value.cardTurn?.turnId,
                            metadata = mapOf("state" to state.label)
                        )
                        if (_machineState.value.phase !is SessionPhase.Idle && _machineState.value.phase !is SessionPhase.Finished && _machineState.value.phase !is SessionPhase.Error) {
                            dispatch(StudyEvent.ConnectionLost(state.label))
                        }
                    }
                    is ConnectionState.Ready,
                    is ConnectionState.ReadyLegacy,
                    is ConnectionState.Connected -> {
                        timeline?.record(
                            DiagnosticCategory.NETWORK,
                            "CONNECTION_RESTORED",
                            sessionEpoch = _machineState.value.epoch,
                            turnId = _machineState.value.cardTurn?.turnId
                        )
                        // Ready/ReadyLegacy are what the real transport emits after a
                        // handshake; Connected is kept for legacy and fake transports.
                        if (_machineState.value.phase is SessionPhase.Recovering || _machineState.value.phase is SessionPhase.Error) {
                            dispatch(StudyEvent.ConnectionRestored("connected"))
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
                        performance?.let { perf ->
                            perf.onSttCompleted()
                            perf.onSttActiveRequests(0)
                            perf.recordSttFinalize(recognitionOrchestrator.health.value.metrics.lastFinalizationMs)
                            perf.onSttReady(recognitionOrchestrator.health.value.metrics.lastReadyLatencyMs)
                            recognitionOrchestrator.health.value.metrics.staleCallbacksDropped.let { dropped ->
                                // Mirror the orchestrator's own counter so the session summary can
                                // report dropped stale callbacks without reaching into the STT layer.
                                if (dropped > perf.staleCallbackWatermark) {
                                    repeat((dropped - perf.staleCallbackWatermark).toInt()) { perf.onSttStaleCallbackDropped() }
                                    perf.staleCallbackWatermark = dropped.toLong()
                                }
                            }
                        }
                        timeline?.record(
                            DiagnosticCategory.STT,
                            "STT_FINAL",
                            sessionEpoch = _machineState.value.epoch,
                            turnId = _machineState.value.cardTurn?.turnId,
                            requestId = outcome.requestId,
                            metadata = mapOf(
                                "purpose" to outcome.purpose.name,
                                "chars" to outcome.selectedText.length.toString()
                            )
                        )
                        // Diagnostic only (§18/§52): the transcript is *never* discarded because
                        // of similarity — a user may legitimately repeat the question's words.
                        val profile = (audioRouteCoordinator?.effectiveRoute?.value ?: fallbackRoute).acousticProfile
                        if (profile == com.studyagent.client.core.audio.AcousticProfile.PHONE_SPEAKER &&
                            selfEchoDetector.isSuspectedSelfEcho(outcome.selectedText, clock())
                        ) {
                            (audioRouteCoordinator?.metrics ?: localMetrics).recordSuspectedSelfEcho()
                            performance?.onSuspectedSelfEcho()
                            AppLogger.w(tag, "suspected_self_echo purpose=${outcome.purpose} chars=${outcome.selectedText.length}")
                        }
                        if (outcome.purpose == RecognitionPurpose.ANSWER ||
                            outcome.purpose == RecognitionPurpose.PUSH_TO_TALK_ANSWER
                        ) {
                            if (outcome.isEmpty) {
                                (audioRouteCoordinator?.metrics ?: localMetrics).recordNoSpeech()
                            } else if (outcome.hypotheses.isEmpty() && outcome.selectedText.isBlank()) {
                                (audioRouteCoordinator?.metrics ?: localMetrics).recordNoMatch()
                            }
                        }
                        val turnId = _machineState.value.cardTurn?.turnId
                        val commandWindow = outcome.purpose == RecognitionPurpose.RATING ||
                            outcome.purpose == RecognitionPurpose.PUSH_TO_TALK_COMMAND
                        if (commandWindow) {
                            // Rating/command windows go through the one interpreter. Answer
                            // windows never reach here, so a medical answer that contains
                            // "good" or "next" can never rate or end the session (§13).
                            handleCommandOutcome(outcome)
                        } else {
                            dispatch(
                                StudyEvent.RecognitionCompleted(
                                    outcome.cardId,
                                    turnId,
                                    outcome.selectedText,
                                    isCommand = false
                                )
                            )
                        }
                    }
                    is RecognitionTurnResult.Failed -> {
                        if (result.error.code.name == "CANCELLED") return@collect
                        when (result.error.code) {
                            RecognitionErrorCode.NO_SPEECH ->
                                (audioRouteCoordinator?.metrics ?: localMetrics).recordNoSpeech()
                            RecognitionErrorCode.NO_MATCH ->
                                (audioRouteCoordinator?.metrics ?: localMetrics).recordNoMatch()
                            RecognitionErrorCode.TIMEOUT ->
                                (audioRouteCoordinator?.metrics ?: localMetrics).recordTimeout()
                            else -> Unit
                        }
                        performance?.let { perf ->
                            perf.onSttFailed()
                            perf.onSttActiveRequests(0)
                            when (result.error.code) {
                                RecognitionErrorCode.NO_SPEECH -> perf.onSttNoSpeech()
                                RecognitionErrorCode.NO_MATCH -> perf.onSttNoMatch()
                                RecognitionErrorCode.BUSY -> perf.onSttBusy()
                                RecognitionErrorCode.TOO_MANY_REQUESTS -> perf.onSttRateLimited()
                                else -> Unit
                            }
                        }
                        timeline?.record(
                            DiagnosticCategory.STT,
                            "STT_FAILED",
                            sessionEpoch = _machineState.value.epoch,
                            turnId = _machineState.value.cardTurn?.turnId,
                            requestId = result.error.requestId,
                            metadata = mapOf("code" to result.error.code.name)
                        )
                        dispatch(StudyEvent.RecognitionFailed(null, result.error.code.name))
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ diagnostics (§19/§51/§59/§67)

    /**
     * Records the reducer's own verdict for every processed event.
     *
     * Rejections are recorded with the reason the reducer gave, not a guess: when a chaos run
     * fails, "EVENT_REJECTED event=ServerEvaluationReceived reason=stale-card" is the answer to
     * "why did nothing happen?" (§25/§172).
     */
    private fun recordEventDiagnostics(event: StudyEvent, before: SessionMachineState, transition: Transition) {
        val tl = timeline
        val epoch = transition.newState.epoch
        val turnId = transition.newState.cardTurn?.turnId
        if (!transition.accepted) {
            tl?.record(
                DiagnosticCategory.SESSION,
                "EVENT_REJECTED",
                sessionEpoch = before.epoch,
                turnId = before.cardTurn?.turnId,
                metadata = mapOf(
                    "event" to event.debugName,
                    "reason" to (transition.rejectionReason ?: "unspecified")
                )
            )
            return
        }
        when (event) {
            is StudyEvent.UserStartRequested -> {
                startRequestedAtMs = clock()
                tl?.record(
                    DiagnosticCategory.SESSION,
                    "SESSION_START_REQUESTED",
                    sessionEpoch = epoch,
                    metadata = mapOf("mode" to event.mode)
                )
            }

            is StudyEvent.ServerSessionStarted -> {
                performance?.onSessionStarted()
                tl?.record(
                    DiagnosticCategory.SESSION,
                    "SESSION_STARTED",
                    sessionEpoch = epoch,
                    metadata = mapOf(
                        "session" to DiagnosticsFormatting.abbreviate(event.sessionId),
                        "cards" to (event.totalCards?.toString() ?: "-")
                    )
                )
            }

            is StudyEvent.ServerQuestionReceived -> {
                questionReceivedAtMs = clock()
                val sentAt = ratingSentAtMs
                if (sentAt > 0L) {
                    performance?.recordRatingToNextQuestion((clock() - sentAt).coerceAtLeast(0L))
                    ratingSentAtMs = -1L
                }
                performance?.onTurnStarted()
                tl?.record(
                    DiagnosticCategory.SESSION,
                    "QUESTION_RECEIVED",
                    sessionEpoch = epoch,
                    turnId = turnId,
                    metadata = mapOf(
                        "card" to DiagnosticsFormatting.abbreviate(event.cardId),
                        "remaining" to (event.remaining?.toString() ?: "-")
                    )
                )
            }

            is StudyEvent.ServerEvaluationReceived -> {
                val sentAt = answerSentAtMs
                if (sentAt > 0L) {
                    performance?.recordEvaluationRoundTrip((clock() - sentAt).coerceAtLeast(0L))
                    answerSentAtMs = -1L
                }
                tl?.record(
                    DiagnosticCategory.SESSION,
                    "EVALUATION_RECEIVED",
                    sessionEpoch = epoch,
                    turnId = turnId,
                    metadata = mapOf("card" to DiagnosticsFormatting.abbreviate(event.cardId))
                )
            }

            is StudyEvent.ServerRatingSaved -> tl?.record(
                DiagnosticCategory.SESSION,
                "RATING_SAVED",
                sessionEpoch = epoch,
                turnId = turnId,
                metadata = mapOf("rating" to event.rating.name.lowercase())
            )

            is StudyEvent.ServerSessionPaused -> tl?.record(
                DiagnosticCategory.SESSION,
                "SESSION_PAUSED",
                sessionEpoch = epoch,
                turnId = turnId
            )

            is StudyEvent.ServerSessionResumed -> tl?.record(
                DiagnosticCategory.SESSION,
                "SESSION_RESUMED",
                sessionEpoch = epoch,
                turnId = turnId
            )

            is StudyEvent.ServerSessionFinished -> {
                performance?.onSessionFinished()
                tl?.record(
                    DiagnosticCategory.SESSION,
                    "SESSION_FINISHED",
                    sessionEpoch = epoch,
                    metadata = mapOf("reviewed" to event.totalReviewed.toString())
                )
            }

            else -> Unit
        }
    }

    /** Transport writes: what the app actually put on the wire, and when (§51/§108-§110). */
    private fun recordOutboundEffect(message: ClientMessage) {
        val state = _machineState.value
        val turnId = state.cardTurn?.turnId
        when (message) {
            is ClientMessage.StartSession -> {
                val startedAt = startRequestedAtMs
                if (startedAt > 0L) {
                    performance?.recordStartToRequest((clock() - startedAt).coerceAtLeast(0L))
                    startRequestedAtMs = -1L
                }
                timeline?.record(
                    DiagnosticCategory.NETWORK,
                    "START_SESSION_SENT",
                    sessionEpoch = state.epoch,
                    metadata = mapOf("deck" to DiagnosticsFormatting.abbreviate(message.deck))
                )
            }

            is ClientMessage.SubmitAnswer -> {
                answerSentAtMs = clock()
                performance?.onAnswerSubmitted()
                timeline?.record(
                    DiagnosticCategory.NETWORK,
                    "ANSWER_SENT",
                    sessionEpoch = state.epoch,
                    turnId = turnId,
                    requestId = message.messageId.take(8),
                    // Length, never content: an answer can be clinical detail (§81).
                    metadata = mapOf("chars" to message.text.length.toString())
                )
            }

            is ClientMessage.RateCard -> {
                ratingSentAtMs = clock()
                performance?.onRatingSubmitted()
                timeline?.record(
                    DiagnosticCategory.NETWORK,
                    "RATING_SENT",
                    sessionEpoch = state.epoch,
                    turnId = turnId,
                    requestId = message.messageId.take(8),
                    metadata = mapOf("rating" to message.rating.name.lowercase())
                )
            }

            is ClientMessage.PauseSession -> timeline?.record(
                DiagnosticCategory.SESSION,
                "PAUSE_SENT",
                sessionEpoch = state.epoch,
                turnId = turnId
            )

            is ClientMessage.ResumeSession -> timeline?.record(
                DiagnosticCategory.SESSION,
                "RESUME_SENT",
                sessionEpoch = state.epoch,
                turnId = turnId
            )

            is ClientMessage.EndSession -> timeline?.record(
                DiagnosticCategory.SESSION,
                "END_SENT",
                sessionEpoch = state.epoch,
                turnId = turnId
            )

            else -> Unit
        }
    }

    private fun recordSendFailure(message: ClientMessage) {
        val state = _machineState.value
        timeline?.record(
            DiagnosticCategory.NETWORK,
            "SEND_FAILED",
            sessionEpoch = state.epoch,
            turnId = state.cardTurn?.turnId,
            metadata = mapOf("type" to message.type)
        )
    }

    /** Question received → the engine actually started speaking (§51). */
    private fun recordQuestionToSpeechStart() {
        val receivedAt = questionReceivedAtMs
        if (receivedAt > 0L) {
            performance?.recordQuestionToSpeechStart((clock() - receivedAt).coerceAtLeast(0L))
        }
        val state = _machineState.value
        timeline?.record(
            DiagnosticCategory.TTS,
            "TTS_START",
            sessionEpoch = state.epoch,
            turnId = state.cardTurn?.turnId
        )
    }

    private fun recordSpeechResult(request: com.studyagent.client.core.voice.tts.SpeechRequest, result: SpeechResult) {
        performance?.let { perf ->
            perf.onTtsQueueDepth(speechOrchestrator.health.value.queueDepth)
            val lastStart = speechOrchestrator.health.value.metrics.lastRequestToStartMs
            if (lastStart >= 0L) perf.onTtsRequestToStart(lastStart)
            when (result) {
                is SpeechResult.Completed -> perf.onTtsCompleted()
                is SpeechResult.Failed -> perf.onTtsFailed()
                is SpeechResult.Cancelled -> perf.onTtsCancelled()
            }
        }
        if (request.purpose == com.studyagent.client.core.voice.tts.SpeechPurpose.PREVIEW) return
        val state = _machineState.value
        val name = when (result) {
            is SpeechResult.Completed -> "TTS_DONE"
            is SpeechResult.Failed -> "TTS_FAILED"
            is SpeechResult.Cancelled -> "TTS_CANCELLED"
        }
        timeline?.record(
            DiagnosticCategory.TTS,
            name,
            sessionEpoch = state.epoch,
            turnId = state.cardTurn?.turnId,
            metadata = mapOf(
                "purpose" to request.purpose.name,
                "chars" to request.text.length.toString()
            )
        )
    }

    /**
     * Internal resource inventory (§19). Every field is a bounded structure, so the endurance
     * tests can assert "back to quiescent" instead of guessing from wall-clock behaviour.
     */
    fun resourceSnapshot(): MachineResourceCounts {
        val state = _machineState.value
        return MachineResourceCounts(
            pendingTimers = timeoutJobs.size,
            eventsAwaitingProcessing = (eventsDispatched.get() - eventsProcessed.get()).coerceAtLeast(0L),
            ledgerEntries = state.ledger.entries.size,
            recentServerMessageIds = state.recentServerMessageIds.size,
            transitionHistory = state.transitionHistory.size,
            cardTurnHistory = state.cardTurnHistory.size,
            activeSpeechEffects = if (state.activeSpeechEffectId != null) 1 else 0,
            activeRecognitionEffects = if (state.activeRecognitionEffectId != null) 1 else 0,
            hasPendingAction = state.pendingAction != null
        )
    }

    /** Sanitized session snapshot for Diagnostics (§59). */
    fun diagnosticsSnapshot(): SessionDiagnosticsSnapshot = _machineState.value.toDiagnostics(clock())

    companion object {
        /**
         * Push-to-talk is explicit user intent: wait briefly for the cancelled utterance to
         * stop rather than refusing to listen, then apply the normal acoustic gap (§27).
         */
        const val PTT_SETTLE_BUDGET_MS = 600L
    }

    /** Stops the machine and releases every timer it owns (§98/§121). */
    fun close() {
        machineJob.cancel()
        eventChannel.close()
        timeoutJobs.values.forEach { it.cancel() }
        timeoutJobs.clear()
        performance?.onSttActiveRequests(0)
    }
}
