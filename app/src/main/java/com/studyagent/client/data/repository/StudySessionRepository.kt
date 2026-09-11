package com.studyagent.client.data.repository

import com.studyagent.client.core.audio.AudioRouteManager
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.StudyCard
import com.studyagent.client.core.models.StudySession
import com.studyagent.client.core.models.StudyState
import com.studyagent.client.core.models.VoiceCommand
import com.studyagent.client.core.voice.SpeechRecognitionManager
import com.studyagent.client.core.voice.SpeechRecognitionResult
import com.studyagent.client.core.voice.VoiceCommandManager
import com.studyagent.client.core.voice.tts.QueuePolicy
import com.studyagent.client.core.voice.tts.SpeechError
import com.studyagent.client.core.voice.tts.SpeechErrorCode
import com.studyagent.client.core.voice.tts.SpeechIds
import com.studyagent.client.core.voice.tts.SpeechOrchestrator
import com.studyagent.client.core.voice.tts.SpeechPriority
import com.studyagent.client.core.voice.tts.SpeechPurpose
import com.studyagent.client.core.voice.tts.SpeechRequest
import com.studyagent.client.core.voice.tts.SpeechResult
import com.studyagent.client.core.voice.tts.StopReason
import com.studyagent.client.core.voice.tts.TtsSettings
import com.studyagent.client.core.voice.tts.VoiceHandoffController
import com.studyagent.client.core.voice.tts.toTtsSettings
import com.studyagent.client.data.preferences.PreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

interface StudySessionRepository {
    val studyState: StateFlow<StudyState>
    val currentSession: StateFlow<StudySession?>
    val lastRecognizedCommand: Flow<VoiceCommand>

    suspend fun startStudy(deckName: String? = "Toronto Notes")
    suspend fun submitSpokenAnswer(cardId: String, transcript: String)
    suspend fun rateCurrentCard(rating: Rating)
    suspend fun requestRepeat()
    suspend fun requestHint()
    suspend fun requestExplanation()
    suspend fun requestAnswer()
    suspend fun skipCard()
    suspend fun pauseStudy()
    suspend fun resumeStudy()
    suspend fun endStudy()
    fun requestStopSpeaking()

    fun startManualPushToTalk()
    fun stopManualPushToTalk(submitIfTranscriptPresent: Boolean = true)
    fun processVoiceCommandDirectly(command: VoiceCommand)
}

/**
 * Orchestrates study state. Speech-specific concerns (voice choice, chunking,
 * normalization, focus, queue, engine callbacks) live in [SpeechOrchestrator] —
 * this class only decides *what* to say, when to listen, and how to recover.
 *
 * TTS→STT discipline (audit R1/R2):
 *  1. Speech completion is delivered as [SpeechResult] over suspension — no callback chains.
 *  2. The mic only opens after [VoiceHandoffController.afterSpeech] confirms speech ended
 *     AND [speechSettled] says nothing is queued/playing (a queued APPEND explanation must
 *     never overlap the rating listener, and vice versa).
 *  3. [maybeResumeListeningAfterSpeech] is the deterministic backstop: after *every* speech
 *     terminal, when the pipeline has fully drained and the state expects a listener but
 *     STT is off, it starts listening — so unusual orderings (queued STATUS messages,
 *     route recovery) can never silence the study loop.
 *  4. Cancelled speech performs NO transition: a cancellation means a newer transition
 *     already owns the state machine.
 */
class DefaultStudySessionRepository(
    private val connectionRepository: ConnectionRepository,
    private val speechOrchestrator: SpeechOrchestrator,
    private val sttManager: SpeechRecognitionManager,
    private val voiceCommandManager: VoiceCommandManager,
    private val preferencesDataStore: PreferencesDataStore,
    private val audioRouteManager: AudioRouteManager,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)
) : StudySessionRepository {

    private val tag = "StudySessionRepo"

    private val _studyState = MutableStateFlow<StudyState>(StudyState.Idle)
    override val studyState: StateFlow<StudyState> = _studyState.asStateFlow()

    private val _currentSession = MutableStateFlow<StudySession?>(null)
    override val currentSession: StateFlow<StudySession?> = _currentSession.asStateFlow()

    private val _lastRecognizedCommand = MutableSharedFlow<VoiceCommand>(extraBufferCapacity = 16)
    override val lastRecognizedCommand: SharedFlow<VoiceCommand> = _lastRecognizedCommand.asSharedFlow()

    private var currentSettings: AppSettings = AppSettings()
    private var currentTtsSettings: TtsSettings = TtsSettings()
    private var isPushToTalkActive = false
    private var speechJob: Job? = null

    /** Card affected by a ROUTE_LOST mid-speech, used for deterministic reconnect recovery. */
    private var routeLostCardId: String? = null

    /** Prevents double recognizer starts within the handoff window. */
    @Volatile
    private var lastSttStartMs: Long = 0L

    private val handoffController = VoiceHandoffController(
        gapProvider = { currentTtsSettings.acousticGapMs }
    )

    init {
        // Observe settings → push structured TtsSettings into the speech pipeline.
        scope.launch {
            preferencesDataStore.settingsFlow.distinctUntilChanged().collect { settings ->
                currentSettings = settings
                currentTtsSettings = settings.toTtsSettings()
                speechOrchestrator.updateSettings(currentTtsSettings)
            }
        }

        // Observe incoming server messages
        scope.launch {
            connectionRepository.incomingMessages.collect { msg ->
                handleServerMessage(msg)
            }
        }

        // Observe connection state transitions
        scope.launch {
            connectionRepository.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Disconnected,
                    is ConnectionState.Error,
                    is ConnectionState.ServerUnavailable,
                    is ConnectionState.NetworkUnavailable -> {
                        val current = _studyState.value
                        if (current !is StudyState.Idle && current !is StudyState.Error && current !is StudyState.SessionFinished) {
                            AppLogger.w(tag, "Connection dropped during active session: ${state.label}")
                            speechOrchestrator.stopSpeech(StopReason.SESSION_END)
                            sttManager.stopListening()
                            _studyState.value = StudyState.Error(
                                message = "Connection to Study Agent lost. Waiting for reconnect...",
                                recoverable = true
                            )
                        }
                    }
                    is ConnectionState.Connected -> {
                        val current = _studyState.value
                        if (current is StudyState.Error && current.recoverable) {
                            AppLogger.i(tag, "Reconnected! Requesting session status...")
                            _currentSession.value?.let { sess ->
                                connectionRepository.send(ClientMessage.RequestSessionStatus(sessionId = sess.sessionId))
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }

        // Headset reconnect recovery (§38): never silently restart — if speech died from a
        // route loss and the headset is back mid-session, repeat the current question.
        scope.launch {
            var wasConnected = audioRouteManager.isHeadsetConnected.value
            audioRouteManager.isHeadsetConnected.collect { connected ->
                val regained = !wasConnected && connected
                wasConnected = connected
                if (regained) handleHeadsetReconnected()
            }
        }

        // Observe STT recognition events
        scope.launch {
            sttManager.recognitionEvents.collect { event ->
                handleSpeechRecognitionResult(event)
            }
        }
    }

    // ------------------------------------------------------------------ server messages

    private suspend fun handleServerMessage(message: ServerMessage) {
        AppLogger.i(tag, "Processing server message: ${message.type}")
        when (message) {
            is ServerMessage.SessionStarted -> {
                val session = StudySession(
                    sessionId = message.sessionId,
                    deckName = message.deck ?: "Toronto Notes",
                    cardNumber = 0,
                    remainingCards = message.totalCards ?: 0
                )
                _currentSession.value = session
                _studyState.value = StudyState.Loading("Session started. Loading first question...")
            }

            is ServerMessage.Question -> {
                val card = StudyCard(
                    id = message.cardId,
                    question = message.question,
                    cardNumber = message.cardNumber,
                    remaining = message.remaining,
                    deckName = _currentSession.value?.deckName
                )

                _currentSession.value = _currentSession.value?.copy(
                    currentCard = card,
                    cardNumber = message.cardNumber ?: (_currentSession.value?.cardNumber ?: 0) + 1,
                    remainingCards = message.remaining ?: _currentSession.value?.remainingCards ?: 0
                )

                // Duplicate delivery guard (e.g. server re-send after reconnect): an identical
                // question for the card already being spoken must not restart speech (§30).
                val alreadySpeakingThis = run {
                    val s = _studyState.value
                    s is StudyState.SpeakingQuestion && s.card.id == card.id && s.card.question == card.question
                }

                _studyState.value = StudyState.SpeakingQuestion(card)

                if (alreadySpeakingThis) {
                    AppLogger.w(tag, "Duplicate question delivery suppressed (card=${card.id})")
                } else if (message.speak && currentSettings.autoPlayQuestion) {
                    speakQuestionAndListen(card)
                } else {
                    transitionToAnswerListening(card)
                }
            }

            is ServerMessage.EvaluationResponse -> {
                val eval = Evaluation(
                    score = message.score,
                    shortFeedback = message.shortFeedback,
                    correctPoints = message.correctPoints,
                    missingPoints = message.missingPoints,
                    incorrectPoints = message.incorrectPoints,
                    suggestedRating = message.suggestedRating
                )

                val card = _currentSession.value?.currentCard ?: StudyCard(
                    id = message.cardId,
                    question = "Question"
                )

                _currentSession.value = _currentSession.value?.copy(lastEvaluation = eval)
                _studyState.value = StudyState.ShowingFeedback(card, eval, isSpeaking = message.speak)

                if (message.speak && currentSettings.autoPlayFeedback && eval.shortFeedback.isNotBlank()) {
                    speakFeedbackAndListenForRating(card, eval)
                } else {
                    transitionToRatingListening(card, eval)
                }
            }

            is ServerMessage.Hint -> {
                val card = _currentSession.value?.currentCard ?: return
                _studyState.value = StudyState.HintShowing(card, message.hintText, isSpeaking = message.speak)
                if (message.speak) {
                    speakThenListen(
                        SpeechRequest(
                            id = SpeechIds.forPurpose(SpeechPurpose.HINT, card.id),
                            text = message.hintText,
                            purpose = SpeechPurpose.HINT,
                            priority = SpeechPriority.NORMAL,
                            queuePolicy = QueuePolicy.REPLACE
                        )
                    ) {
                        transitionToAnswerListening(card)
                    }
                } else {
                    transitionToAnswerListening(card)
                }
            }

            is ServerMessage.Explanation -> {
                val card = _currentSession.value?.currentCard ?: return
                _studyState.value = StudyState.ExplanationShowing(card, message.explanationText, isSpeaking = message.speak)
                if (message.speak) {
                    // Long explanations are interruptible APPEND work: they may play right
                    // after a feedback line, and "Skip"/"Stop speaking" aborts them fast.
                    speakSpeechRequest(
                        SpeechRequest(
                            id = SpeechIds.forPurpose(SpeechPurpose.EXPLANATION, card.id),
                            text = message.explanationText,
                            purpose = SpeechPurpose.EXPLANATION,
                            priority = SpeechPriority.NORMAL,
                            queuePolicy = QueuePolicy.APPEND,
                            interruptible = true
                        )
                    ) { result ->
                        if (result == SpeechResult.Completed || result is SpeechResult.Failed) {
                            moveToRatingAfterSpeech(card, result)
                        }
                    }
                }
            }

            is ServerMessage.Answer -> {
                val card = _currentSession.value?.currentCard ?: return
                if (message.speak) {
                    speakSpeechRequest(
                        SpeechRequest(
                            id = SpeechIds.forPurpose(SpeechPurpose.ANSWER, card.id),
                            text = message.answerText,
                            purpose = SpeechPurpose.ANSWER,
                            priority = SpeechPriority.NORMAL,
                            queuePolicy = QueuePolicy.REPLACE
                        )
                    ) { result ->
                        if (result == SpeechResult.Completed || result is SpeechResult.Failed) {
                            moveToRatingAfterSpeech(card, result)
                        }
                    }
                }
            }

            is ServerMessage.RatingSaved -> {
                AppLogger.i(tag, "Rating ${message.rating} saved for card ${message.cardId}. Next interval: ${message.nextInterval}")
                _studyState.value = StudyState.Loading("Rating saved. Fetching next card...")
            }

            is ServerMessage.SessionPaused -> {
                val current = _studyState.value
                if (current !is StudyState.Paused) {
                    speechOrchestrator.stopSpeech(StopReason.PAUSE)
                    sttManager.stopListening()
                    _studyState.value = StudyState.Paused(current)
                }
            }

            is ServerMessage.SessionResumed -> {
                val current = _studyState.value
                if (current is StudyState.Paused) {
                    _studyState.value = current.previousState
                    current.previousState.currentCardOrNull?.let { card ->
                        if (current.previousState is StudyState.SpeakingQuestion) {
                            speakQuestionAndListen(card)
                        } else if (current.previousState is StudyState.Listening) {
                            transitionToAnswerListening(card)
                        }
                    }
                }
            }

            is ServerMessage.SessionFinished -> {
                // §60: cancel irrelevant queued speech FIRST, then speak the summary.
                speechOrchestrator.stopSpeech(StopReason.SESSION_END)
                sttManager.stopListening()
                _studyState.value = StudyState.SessionFinished(
                    summary = message.summary,
                    cardsReviewed = message.totalReviewed
                )
                message.summary?.let { summary ->
                    speakSpeechRequest(
                        SpeechRequest(
                            id = SpeechIds.forPurpose(SpeechPurpose.SESSION_SUMMARY),
                            text = summary,
                            purpose = SpeechPurpose.SESSION_SUMMARY,
                            priority = SpeechPriority.HIGH,
                            queuePolicy = QueuePolicy.APPEND
                        ),
                        onTerminal = {}
                    )
                }
            }

            is ServerMessage.SessionStats -> {
                AppLogger.i(tag, "Session Stats: studied=${message.cardsStudied}, recall=${message.recallRate}%, remaining=${message.remainingDue}")
                _currentSession.value = _currentSession.value?.copy(
                    totalReviewedInSession = message.cardsStudied,
                    remainingCards = message.remainingDue
                )
                speakSpeechRequest(
                    SpeechRequest(
                        id = SpeechIds.forPurpose(SpeechPurpose.STATUS),
                        text = "You have ${message.remainingDue} cards left in this session.",
                        purpose = SpeechPurpose.STATUS,
                        priority = SpeechPriority.LOW,
                        queuePolicy = QueuePolicy.IGNORE_IF_DUPLICATE
                    ),
                    onTerminal = {}
                )
            }

            is ServerMessage.ErrorMessage -> {
                AppLogger.e(tag, "Server error: ${message.message} (code: ${message.code})")
                _studyState.value = StudyState.Error(message.message, recoverable = true)
            }

            else -> Unit
        }
    }

    // ------------------------------------------------------------------ speech helpers

    /** Speak the question, then (hands-free) open the mic after the acoustic gap. */
    private fun speakQuestionAndListen(card: StudyCard) {
        speakThenListen(
            SpeechRequest(
                id = SpeechIds.forPurpose(SpeechPurpose.QUESTION, card.id),
                text = card.question,
                purpose = SpeechPurpose.QUESTION,
                priority = SpeechPriority.NORMAL,
                queuePolicy = QueuePolicy.REPLACE
            )
        ) {
            transitionToAnswerListening(card)
        }
    }

    private fun speakFeedbackAndListenForRating(card: StudyCard, eval: Evaluation) {
        // Prefer the backend-provided concise feedback verbatim — Android never invents
        // clinical content (§42); only an empty payload gets a neutral placeholder.
        val spoken = eval.shortFeedback.ifBlank { "Feedback received." }
        speakSpeechRequest(
            SpeechRequest(
                id = SpeechIds.forPurpose(SpeechPurpose.FEEDBACK, card.id),
                text = spoken,
                purpose = SpeechPurpose.FEEDBACK,
                priority = SpeechPriority.NORMAL,
                queuePolicy = QueuePolicy.REPLACE
            )
        ) { result ->
            // Completed and Failed converge to WaitingForRating: a TTS failure must never
            // brick the study loop — the user continues visually (graceful degradation, §86).
            if (result == SpeechResult.Completed || result is SpeechResult.Failed) {
                if (result is SpeechResult.Failed) handleSpeechFailure(result.error, card)
                _studyState.value = StudyState.WaitingForRating(card, eval, eval.suggestedRating)
                if (currentSettings.handsFreeMode) {
                    handoffController.afterSpeech(result) { transitionToRatingListening(card, eval) }
                }
            }
        }
    }

    private suspend fun moveToRatingAfterSpeech(card: StudyCard, result: SpeechResult) {
        if (result is SpeechResult.Failed) handleSpeechFailure(result.error, card)
        val lastEval = _currentSession.value?.lastEvaluation
        if (lastEval != null) {
            _studyState.value = StudyState.WaitingForRating(card, lastEval, lastEval.suggestedRating)
            if (currentSettings.handsFreeMode) {
                handoffController.afterSpeech(result) { transitionToRatingListening(card, lastEval) }
            }
        } else {
            if (currentSettings.handsFreeMode) {
                handoffController.afterSpeech(result) { transitionToAnswerListening(card) }
            }
        }
    }

    /** Speak [request]; on terminal success (or degraded failure) gap-wait then [listenAction]. */
    private fun speakThenListen(request: SpeechRequest, listenAction: () -> Unit) {
        speakSpeechRequest(request) { result ->
            when {
                result == SpeechResult.Completed && currentSettings.handsFreeMode ->
                    handoffController.afterSpeech(result) { listenAction() }
                result is SpeechResult.Failed -> {
                    handleSpeechFailure(result.error, _studyState.value.currentCardOrNull)
                    if (currentSettings.handsFreeMode) {
                        handoffController.afterSpeech(result) { listenAction() }
                    }
                }
            }
        }
    }

    /**
     * Fire a logical speech request in a child job (never blocks the server-message
     * collector) and route the exact terminal result to [onTerminal]. The drain backstop
     * ([maybeResumeListeningAfterSpeech]) always runs afterwards.
     */
    private fun speakSpeechRequest(
        request: SpeechRequest,
        onTerminal: suspend (SpeechResult) -> Unit
    ) {
        AppLogger.i(tag, "Speech request: purpose=${request.purpose} chars=${request.text.length} policy=${request.queuePolicy}")
        speechJob = scope.launch {
            val result = speechOrchestrator.speak(request)
            try {
                onTerminal(result)
            } catch (t: Throwable) {
                AppLogger.w(tag, "Speech completion handler failed: ${t.message}")
            }
            maybeResumeListeningAfterSpeech()
        }
    }

    private fun handleSpeechFailure(error: SpeechError, card: StudyCard?) {
        AppLogger.w(tag, "Speech failed (${error.code}); continuing in degraded (visual) mode")
        if (error.code == SpeechErrorCode.ROUTE_LOST) {
            routeLostCardId = card?.id
        }
    }

    private fun handleHeadsetReconnected() {
        val lostCard = routeLostCardId ?: return
        routeLostCardId = null
        if (!currentSettings.handsFreeMode) return
        val card = _studyState.value.currentCardOrNull ?: return
        if (card.id != lostCard) return
        // Deterministic policy: repeat the interrupted question; never mid-word auto-restart.
        AppLogger.i(tag, "Headset reconnected after route loss; repeating current question")
        _studyState.value = StudyState.SpeakingQuestion(card)
        speakQuestionAndListen(card)
    }

    // ------------------------------------------------------------------ listening

    /** True when nothing is playing and nothing is queued — mic may open without overlap. */
    private fun speechSettled(): Boolean =
        !speechOrchestrator.isSpeaking.value && speechOrchestrator.health.value.queueDepth == 0

    /**
     * Move to Listening state; start STT immediately if speech is drained, otherwise STT
     * starts when the queued speech finishes ([maybeResumeListeningAfterSpeech]).
     */
    private fun transitionToAnswerListening(card: StudyCard) {
        _studyState.value = StudyState.Listening(card = card, partialTranscript = "", isHandsFree = currentSettings.handsFreeMode)
        if (speechSettled()) beginStt()
    }

    private fun transitionToRatingListening(card: StudyCard, eval: Evaluation) {
        _studyState.value = StudyState.WaitingForRating(card = card, evaluation = eval, suggestedRating = eval.suggestedRating)
        if (speechSettled()) beginStt()
    }

    /** The deterministic drain backstop — see class doc. */
    private fun maybeResumeListeningAfterSpeech() {
        if (!currentSettings.handsFreeMode || !speechSettled()) return
        if (sttManager.isListening.value) return
        when (_studyState.value) {
            is StudyState.Listening,
            is StudyState.WaitingForRating -> beginStt()
            else -> Unit
        }
    }

    private fun beginStt() {
        val now = System.currentTimeMillis()
        if (now - lastSttStartMs < STT_START_DEDUP_MS) return
        lastSttStartMs = now
        sttManager.startListening(currentSettings.sttLanguage, isHandsFree = currentSettings.handsFreeMode)
    }

    // ------------------------------------------------------------------ STT results

    private fun handleSpeechRecognitionResult(result: SpeechRecognitionResult) {
        when (result) {
            is SpeechRecognitionResult.Partial -> {
                val current = _studyState.value
                if (current is StudyState.Listening) {
                    _studyState.value = current.copy(partialTranscript = result.text)
                }
            }

            is SpeechRecognitionResult.Final -> {
                val transcript = result.text.trim()
                if (transcript.isEmpty()) return

                AppLogger.i(tag, "Final transcript received (${transcript.length} chars)")
                val currentState = _studyState.value
                val recognizedCmd = voiceCommandManager.parseInStudyContext(transcript, currentState)
                _lastRecognizedCommand.tryEmit(recognizedCmd)

                processVoiceCommandDirectly(recognizedCmd)
            }

            is SpeechRecognitionResult.NoSpeech -> {
                AppLogger.d(tag, "STT reported NoSpeech")
            }

            is SpeechRecognitionResult.Error -> {
                AppLogger.w(tag, "STT error: ${result.errorMessage} (code: ${result.errorCode})")
            }

            is SpeechRecognitionResult.ListeningStateChanged -> {
                AppLogger.d(tag, "STT listening state changed: ${result.isListening}")
            }

            is SpeechRecognitionResult.RmsChanged -> {
                // Audio level meter
            }
        }
    }

    // ------------------------------------------------------------------ commands

    override fun processVoiceCommandDirectly(command: VoiceCommand) {
        scope.launch {
            AppLogger.i(tag, "Executing VoiceCommand: ${command.commandName}")
            when (command) {
                is VoiceCommand.Again -> rateCurrentCard(Rating.AGAIN)
                is VoiceCommand.Hard -> rateCurrentCard(Rating.HARD)
                is VoiceCommand.Good -> rateCurrentCard(Rating.GOOD)
                is VoiceCommand.Easy -> rateCurrentCard(Rating.EASY)

                is VoiceCommand.Repeat -> requestRepeat()
                is VoiceCommand.Hint -> requestHint()
                is VoiceCommand.Explain -> requestExplanation()
                is VoiceCommand.ShowAnswer -> requestAnswer()
                is VoiceCommand.Skip -> skipCard()

                is VoiceCommand.Pause -> pauseStudy()
                is VoiceCommand.Resume -> resumeStudy()
                is VoiceCommand.StopSpeaking -> stopSpeakingNow()
                is VoiceCommand.Stop, is VoiceCommand.EndSession -> endStudy()

                is VoiceCommand.StartStudy -> startStudy(command.deck ?: "Toronto Notes")
                is VoiceCommand.StatusQuestion -> {
                    val sess = _currentSession.value
                    connectionRepository.send(ClientMessage.RequestSessionStatus(sessionId = sess?.sessionId))
                }

                is VoiceCommand.SubmitAnswer -> {
                    val card = _studyState.value.currentCardOrNull ?: _currentSession.value?.currentCard
                    if (card != null) {
                        submitSpokenAnswer(card.id, command.answer)
                    }
                }

                is VoiceCommand.Unknown -> {
                    // If in listening mode, treat whatever text as answer
                    val card = _studyState.value.currentCardOrNull ?: _currentSession.value?.currentCard
                    if (card != null && _studyState.value is StudyState.Listening) {
                        submitSpokenAnswer(card.id, command.rawText)
                    } else {
                        AppLogger.w(tag, "Unknown voice command (${command.rawText.length} chars)")
                    }
                }
            }
        }
    }

    /** "Stop speaking" (§63): cancels speech but keeps the session alive. */
    private fun stopSpeakingNow() {
        speechOrchestrator.stopSpeech(StopReason.USER)
        val current = _studyState.value
        // Deterministic landing state after interrupting a long explanation/hint:
        // return to rating if an evaluation exists, otherwise re-listen for the answer.
        when (current) {
            is StudyState.ExplanationShowing,
            is StudyState.HintShowing,
            is StudyState.ShowingFeedback -> {
                val card = current.currentCardOrNull ?: return
                val lastEval = _currentSession.value?.lastEvaluation
                if (lastEval != null) {
                    transitionToRatingListening(card, lastEval)
                } else {
                    transitionToAnswerListening(card)
                }
            }
            else -> Unit
        }
    }

    override fun requestStopSpeaking() {
        stopSpeakingNow()
    }

    // ------------------------------------------------------------------ study actions

    override suspend fun startStudy(deckName: String?) {
        val targetDeck = deckName ?: "Toronto Notes"
        AppLogger.i(tag, "Starting study session for deck: $targetDeck")
        _studyState.value = StudyState.Loading("Connecting to study session...")

        val startMsg = ClientMessage.StartSession(
            deck = targetDeck,
            mode = "review_due"
        )
        val sent = connectionRepository.send(startMsg)
        if (!sent) {
            _studyState.value = StudyState.Error("Failed to start session. Check PC connection.", recoverable = true)
        }
    }

    override suspend fun submitSpokenAnswer(cardId: String, transcript: String) {
        sttManager.stopListening()
        val card = _studyState.value.currentCardOrNull ?: _currentSession.value?.currentCard ?: StudyCard(cardId, "Question")
        _studyState.value = StudyState.Evaluating(card = card, userTranscript = transcript)

        val answerMsg = ClientMessage.SubmitAnswer(
            sessionId = _currentSession.value?.sessionId,
            cardId = cardId,
            text = transcript
        )
        connectionRepository.send(answerMsg)
    }

    override suspend fun rateCurrentCard(rating: Rating) {
        sttManager.stopListening()
        speechOrchestrator.stopSpeech(StopReason.USER) // rating dismisses spoken feedback
        val card = _studyState.value.currentCardOrNull ?: _currentSession.value?.currentCard ?: return
        _studyState.value = StudyState.Loading("Submitting rating: ${rating.displayName}...")

        val rateMsg = ClientMessage.RateCard(
            sessionId = _currentSession.value?.sessionId,
            cardId = card.id,
            rating = rating
        )
        connectionRepository.send(rateMsg)
    }

    override suspend fun requestRepeat() {
        val card = _studyState.value.currentCardOrNull ?: _currentSession.value?.currentCard ?: return
        // REPLACE inside the orchestrator interrupts low-priority speech and replays the
        // question deterministically; no duplicate server state is created here.
        _studyState.value = StudyState.SpeakingQuestion(card)
        speakQuestionAndListen(card)
        connectionRepository.send(
            ClientMessage.RepeatQuestion(
                sessionId = _currentSession.value?.sessionId,
                cardId = card.id
            )
        )
    }

    override suspend fun requestHint() {
        val card = _studyState.value.currentCardOrNull ?: _currentSession.value?.currentCard ?: return
        connectionRepository.send(
            ClientMessage.RequestHint(
                sessionId = _currentSession.value?.sessionId,
                cardId = card.id
            )
        )
    }

    override suspend fun requestExplanation() {
        val card = _studyState.value.currentCardOrNull ?: _currentSession.value?.currentCard ?: return
        connectionRepository.send(
            ClientMessage.RequestExplanation(
                sessionId = _currentSession.value?.sessionId,
                cardId = card.id
            )
        )
    }

    override suspend fun requestAnswer() {
        val card = _studyState.value.currentCardOrNull ?: _currentSession.value?.currentCard ?: return
        connectionRepository.send(
            ClientMessage.RequestAnswer(
                sessionId = _currentSession.value?.sessionId,
                cardId = card.id
            )
        )
    }

    override suspend fun skipCard() {
        val card = _studyState.value.currentCardOrNull ?: _currentSession.value?.currentCard
        connectionRepository.send(
            ClientMessage.SkipCard(
                sessionId = _currentSession.value?.sessionId,
                cardId = card?.id
            )
        )
    }

    override suspend fun pauseStudy() {
        speechOrchestrator.stopSpeech(StopReason.PAUSE)
        sttManager.stopListening()
        val current = _studyState.value
        _studyState.value = StudyState.Paused(current)
        connectionRepository.send(ClientMessage.PauseSession(sessionId = _currentSession.value?.sessionId))
    }

    override suspend fun resumeStudy() {
        val current = _studyState.value
        if (current is StudyState.Paused) {
            _studyState.value = current.previousState
            connectionRepository.send(ClientMessage.ResumeSession(sessionId = _currentSession.value?.sessionId))
            current.previousState.currentCardOrNull?.let { card ->
                if (current.previousState is StudyState.SpeakingQuestion) {
                    speakQuestionAndListen(card)
                } else if (current.previousState is StudyState.Listening) {
                    transitionToAnswerListening(card)
                }
            }
        }
    }

    override suspend fun endStudy() {
        speechOrchestrator.stopSpeech(StopReason.SESSION_END)
        sttManager.stopListening()
        val sess = _currentSession.value
        connectionRepository.send(ClientMessage.EndSession(sessionId = sess?.sessionId))
        _studyState.value = StudyState.SessionFinished(
            summary = "Session ended.",
            cardsReviewed = sess?.cardNumber ?: 0
        )
        _currentSession.value = null
    }

    override fun startManualPushToTalk() {
        isPushToTalkActive = true
        // Push-to-talk always wins over speech output, immediately.
        speechOrchestrator.stopSpeech(StopReason.USER)
        val card = _studyState.value.currentCardOrNull ?: _currentSession.value?.currentCard
        if (card != null) {
            _studyState.value = StudyState.Listening(card = card, partialTranscript = "", isHandsFree = false)
            beginStt()
        }
    }

    override fun stopManualPushToTalk(submitIfTranscriptPresent: Boolean) {
        isPushToTalkActive = false
        sttManager.stopListening()
    }

    companion object {
        /** Back-to-back STT starts within this window are deduplicated (handoff vs backstop). */
        const val STT_START_DEDUP_MS = 150L
    }
}
