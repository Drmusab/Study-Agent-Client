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
import com.studyagent.client.core.voice.stt.CommandContext
import com.studyagent.client.core.voice.stt.CommandDecision
import com.studyagent.client.core.voice.stt.MedicalVocabularyProvider
import com.studyagent.client.core.voice.stt.ParsedVoiceCommand
import com.studyagent.client.core.voice.stt.RecognitionError
import com.studyagent.client.core.voice.stt.RecognitionErrorCode
import com.studyagent.client.core.voice.stt.RecognitionLanguageMode
import com.studyagent.client.core.voice.stt.RecognitionOutcome
import com.studyagent.client.core.voice.stt.RecognitionPolicyFactory
import com.studyagent.client.core.voice.stt.RecognitionPurpose
import com.studyagent.client.core.voice.stt.RecognitionStartResult
import com.studyagent.client.core.voice.stt.RecognitionTurnResult
import com.studyagent.client.core.voice.stt.SpeechRecognitionOrchestrator
import com.studyagent.client.core.voice.stt.SttSettings
import com.studyagent.client.core.voice.stt.VoiceCommandInterpreter
import com.studyagent.client.core.voice.stt.toSttSettings
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
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

    /**
     * Release push-to-talk. Finishes the recognition turn; the transcript is submitted when
     * the recognizer returns its final result — never at the moment of release (§18/§105).
     */
    fun stopManualPushToTalk()

    /** Auto-submit is off and a transcript is pending review: send it as the answer. */
    fun submitPendingTranscript()

    /** Discard the pending transcript and listen again. */
    fun discardPendingTranscript()

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
    private val recognitionOrchestrator: SpeechRecognitionOrchestrator,
    private val recognitionPolicyFactory: RecognitionPolicyFactory = RecognitionPolicyFactory(),
    private val commandInterpreter: VoiceCommandInterpreter = VoiceCommandInterpreter(),
    private val vocabularyProvider: MedicalVocabularyProvider = MedicalVocabularyProvider.DEFAULT,
    /** Observed [AppSettings] stream. (A Flow, so tests can drive settings without Android.) */
    settingsFlow: Flow<AppSettings>,
    private val audioRouteManager: AudioRouteManager,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default),
    /** Injectable clock for the STT start dedup window (tests use virtual time). */
    private val clock: () -> Long = System::currentTimeMillis
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
    private var currentSttSettings: SttSettings = SttSettings()
    private var isPushToTalkActive = false
    private var speechJob: Job? = null

    // ------------------------------------------------------------------ submission ledger
    //
    // Exactly-once study actions (§10/§11). The recognition layer already guarantees one
    // terminal callback per request id; these guards extend the guarantee to the *study*
    // layer, where duplicates can still reach the server through independent paths:
    // a double-tapped rating button, a voice result racing a button tap, a transcript
    // submitted twice from a pending-review state, or a stray utterance in the rating
    // window being misread as a second answer for an already-evaluated card.

    /** Serializes ledger check-and-set with the code that decides to reset it. */
    private val submissionLock = Any()

    /** Card whose answer has been sent to the evaluator. One answer per card. */
    private var answerSubmittedForCardId: String? = null

    /** Card whose rating has been sent to the server. One rating per card. */
    private var ratingSubmittedForCardId: String? = null

    /**
     * A rating awaiting the user's yes/no after a NeedsConfirmation prompt. Cleared when
     * any other decision resolves, when a non-confirmation turn starts, or when the card
     * changes — a stale "yes" must never rate a card the prompt was not about.
     */
    private var pendingRatingConfirmation: ParsedVoiceCommand? = null

    /** One answer per card: returns true when this caller owns the submission. */
    private fun tryBeginAnswerSubmission(cardId: String): Boolean {
        synchronized(submissionLock) {
            if (answerSubmittedForCardId == cardId) return false
            answerSubmittedForCardId = cardId
        }
        return true
    }

    /** One rating per card: returns true when this caller owns the submission. */
    private fun tryBeginRatingSubmission(cardId: String): Boolean {
        synchronized(submissionLock) {
            if (ratingSubmittedForCardId == cardId) return false
            ratingSubmittedForCardId = cardId
        }
        return true
    }

    /**
     * A genuinely new card re-opens the submission window. A *re-delivered* Question for
     * the same card (server re-send after reconnect) must NOT reset the guard — that is
     * exactly the duplicate the ledger exists to absorb.
     */
    private fun onStudyTurnAdvanced(newCardId: String?) = synchronized(submissionLock) {
        if (answerSubmittedForCardId != null && answerSubmittedForCardId != newCardId) {
            answerSubmittedForCardId = null
        }
        if (ratingSubmittedForCardId != null && ratingSubmittedForCardId != newCardId) {
            ratingSubmittedForCardId = null
        }
        pendingRatingConfirmation = null
    }

    private fun clearPendingRatingConfirmation() = synchronized(submissionLock) {
        pendingRatingConfirmation = null
    }

    private fun setPendingRatingConfirmation(match: ParsedVoiceCommand?) = synchronized(submissionLock) {
        pendingRatingConfirmation = match
    }

    private fun peekPendingRatingConfirmation(): ParsedVoiceCommand? = synchronized(submissionLock) {
        pendingRatingConfirmation
    }


    /**
     * Consecutive recognition failures without an intervening success. Recognition has its
     * own bounded retry inside the orchestrator; this stops the *repository* from re-opening
     * the microphone forever when the underlying problem is persistent (dead mic, revoked
     * permission, no service) — which would otherwise loop for the whole session.
     */
    private var consecutiveRecognitionFailures = 0

    /** Card affected by a ROUTE_LOST mid-speech, used for deterministic reconnect recovery. */
    private var routeLostCardId: String? = null

    /** Prevents double recognizer starts within the handoff window. */
    @Volatile
    private var lastSttStartMs: Long = Long.MIN_VALUE / 2

    private val handoffController = VoiceHandoffController(
        gapProvider = { currentTtsSettings.acousticGapMs }
    )

    init {
        // Observe settings → push structured TtsSettings into the speech pipeline.
        scope.launch {
            settingsFlow.distinctUntilChanged().collect { settings ->
                currentSettings = settings
                currentTtsSettings = settings.toTtsSettings()
                currentSttSettings = settings.toSttSettings()
                speechOrchestrator.updateSettings(currentTtsSettings)
                recognitionOrchestrator.updateSettings(currentSttSettings)
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
                            // A pending PTT start must not fire into the connection-error state.
                            isPushToTalkActive = false
                            speechOrchestrator.stopSpeech(StopReason.SESSION_END)
                            // §98: do not keep recording an answer that cannot be submitted.
                            recognitionOrchestrator.cancelCurrentTurn("connection-lost")
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

        // Terminal recognition results. This is the only place a transcript can become an
        // answer or a rating — partials never do (§42).
        scope.launch {
            recognitionOrchestrator.turnResults.collect { result ->
                handleTurnResult(result)
            }
        }

        // Live partial transcript → UI only. Conflated upstream, so a chatty recognizer
        // cannot drive unbounded recomposition (§43/§71).
        scope.launch {
            recognitionOrchestrator.partialTranscript.distinctUntilChanged().collect { partial ->
                val current = _studyState.value
                if (current is StudyState.Listening && current.partialTranscript != partial) {
                    _studyState.value = current.copy(partialTranscript = partial)
                }
            }
        }

        // §63: losing the microphone route mid-turn must never let a half-recognised answer
        // through. Cancel, keep the card, and let the reconnect handler recover.
        scope.launch {
            var wasConnected = audioRouteManager.isHeadsetConnected.value
            audioRouteManager.isHeadsetConnected.collect { connected ->
                val lost = wasConnected && !connected
                wasConnected = connected
                if (lost && recognitionOrchestrator.state.value.isActive) {
                    AppLogger.w(tag, "Headset lost during recognition; cancelling turn")
                    recognitionOrchestrator.cancelCurrentTurn("input-route-lost")
                    routeLostCardId = _studyState.value.currentCardOrNull?.id
                }
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

                // New study turn: previous card's submissions can no longer recur, a stale
                // confirmation prompt is void, and (unless this is a duplicate re-delivery)
                // any recognition still running for the previous card is invalidated — the
                // orchestrator drops its late callback by request id, so a slow transcript
                // can never land on the card that follows it (§27/§30/§97).
                onStudyTurnAdvanced(card.id)

                // Duplicate delivery guard (e.g. server re-send after reconnect): an identical
                // question must not restart speech, and must not cancel an answer recognition
                // the user is mid-way through for THIS card.
                val duplicateDelivery = run {
                    val s = _studyState.value
                    (s is StudyState.SpeakingQuestion && s.card.id == card.id && s.card.question == card.question) ||
                        (s is StudyState.Listening && s.card.id == card.id)
                }

                if (duplicateDelivery) {
                    AppLogger.w(tag, "Duplicate question delivery suppressed (card=${card.id})")
                } else {
                    recognitionOrchestrator.cancelCurrentTurn("new-question")
                    _studyState.value = StudyState.SpeakingQuestion(card)

                    if (message.speak && currentSettings.autoPlayQuestion) {
                        speakQuestionAndListen(card)
                    } else {
                        transitionToAnswerListening(card)
                    }
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
                    recognitionOrchestrator.cancelCurrentTurn("session-paused")
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
                recognitionOrchestrator.cancelCurrentTurn("session-finished")
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
     *
     * Turn-identity guard (§27): a speech terminal from a *previous* card (a question still
     * being spoken when the server already moved on) must not drag the study state back to
     * that card or open its microphone. Only the current card may transition.
     */
    private fun transitionToAnswerListening(card: StudyCard) {
        if (_studyState.value.currentCardOrNull?.id != card.id) {
            AppLogger.w(tag, "Listen transition skipped: card ${card.id} is no longer current")
            return
        }
        _studyState.value = StudyState.Listening(
            card = card,
            partialTranscript = "",
            isHandsFree = currentSettings.handsFreeMode
        )
        if (speechSettled()) beginStt(RecognitionPurpose.ANSWER, card)
    }

    private fun transitionToRatingListening(card: StudyCard, eval: Evaluation) {
        if (_studyState.value.currentCardOrNull?.id != card.id) {
            AppLogger.w(tag, "Rating transition skipped: card ${card.id} is no longer current")
            return
        }
        _studyState.value = StudyState.WaitingForRating(card = card, evaluation = eval, suggestedRating = eval.suggestedRating)
        // §20: the spoken-rating preference is now actually honoured. When it is off the
        // rating window still exists — the on-screen buttons work — the microphone just
        // stays closed.
        if (!currentSettings.listenForSpokenRating) {
            AppLogger.d(tag, "Spoken ratings disabled; rating window is manual only")
            return
        }
        if (speechSettled()) beginStt(RecognitionPurpose.RATING, card)
    }

    /** The deterministic drain backstop — see class doc. */
    private fun maybeResumeListeningAfterSpeech() {
        if (!currentSettings.handsFreeMode || !speechSettled()) return
        // Readiness comes from the recognition state machine, not a boolean: a turn that has
        // ended speech but not yet finalised is still busy, and starting here used to produce
        // ERROR_RECOGNIZER_BUSY.
        if (!recognitionOrchestrator.state.value.isReadyForNewRequest) return
        when (val state = _studyState.value) {
            is StudyState.Listening ->
                if (state.pendingTranscript.isBlank()) beginStt(RecognitionPurpose.ANSWER, state.card)

            is StudyState.WaitingForRating ->
                if (currentSettings.listenForSpokenRating) {
                    if (peekPendingRatingConfirmation() != null) {
                        // A "I heard X — correct?" prompt is outstanding: re-open the SHORT
                        // confirmation listener, not the rating one, so a spoken "yes" can
                        // resolve the pending rating instead of falling through to answer
                        // handling (§14/§140).
                        beginStt(RecognitionPurpose.SHORT_CONFIRMATION, state.card)
                    } else {
                        beginStt(RecognitionPurpose.RATING, state.card)
                    }
                }

            else -> Unit
        }
    }

    /**
     * Open the microphone for one explicit purpose (§4/§54).
     *
     * The purpose drives everything downstream — endpoint profile, watchdog budget,
     * vocabulary biasing, candidate selection and acceptance thresholds — so study code
     * never has to configure a recognizer.
     */
    private fun beginStt(purpose: RecognitionPurpose, card: StudyCard?) {
        val now = clock()
        if (now - lastSttStartMs < STT_START_DEDUP_MS) {
            // The handoff path and the drain backstop can race by design; whichever loses
            // is dropped here. Logged so a swallowed start is never silent.
            AppLogger.d(
                tag,
                "STT start deduplicated (${now - lastSttStartMs}ms since previous, purpose=${purpose.name})"
            )
            return
        }
        lastSttStartMs = now

        // Turn-identity guard (§27): never open the microphone for a card that is no longer
        // current, whatever path asked for it.
        if (card != null && _studyState.value.currentCardOrNull?.id != card.id) {
            AppLogger.w(tag, "STT start skipped: card ${card.id} is no longer current")
            return
        }

        // A new turn that is not the confirmation turn itself voids any outstanding
        // "is that correct?" prompt — a stale yes must not rate a card retroactively.
        if (purpose != RecognitionPurpose.SHORT_CONFIRMATION) {
            clearPendingRatingConfirmation()
        }

        val request = recognitionPolicyFactory.createRequest(
            purpose = purpose,
            settings = currentSttSettings,
            cardId = card?.id,
            // Bias on the question's own medical terms only. The expected answer never
            // reaches the client, so it cannot leak into the bias list (§34).
            contextTerms = vocabularyProvider.contextTermsFrom(card?.question)
        )
        when (val result = recognitionOrchestrator.startRecognition(request)) {
            is RecognitionStartResult.Started ->
                AppLogger.i(tag, "Listening for ${purpose.name} (id=${result.requestId})")

            is RecognitionStartResult.Rejected ->
                AppLogger.w(tag, "Recognition start rejected: ${result.error.code.name}")
        }
    }

    // ------------------------------------------------------------------ STT results

    private fun handleTurnResult(result: RecognitionTurnResult) {
        when (result) {
            is RecognitionTurnResult.Completed -> handleCompletedTurn(result.outcome)
            is RecognitionTurnResult.Failed -> handleRecognitionFailure(result.error)
        }
    }

    /**
     * Turn a finished recognition turn into at most one study action.
     *
     * Ordering matters: the card check runs before interpretation, so a transcript that
     * belongs to a card we have already moved on from is discarded instead of being applied
     * to whatever is on screen now (§10/§97).
     */
    private fun handleCompletedTurn(outcome: RecognitionOutcome) {
        consecutiveRecognitionFailures = 0

        val state = _studyState.value
        val activeCardId = state.currentCardOrNull?.id
        val turnCardId = outcome.cardId
        if (turnCardId != null && activeCardId != null && turnCardId != activeCardId) {
            AppLogger.w(
                tag,
                "Discarding stale transcript: turn card=$turnCardId, active card=$activeCardId"
            )
            return
        }

        val context = when (state) {
            is StudyState.Listening -> CommandContext.ANSWER_EXPECTED
            is StudyState.WaitingForRating -> CommandContext.RATING_EXPECTED
            is StudyState.ShowingFeedback,
            is StudyState.HintShowing,
            is StudyState.ExplanationShowing -> CommandContext.FEEDBACK_SHOWING
            is StudyState.Paused -> CommandContext.PAUSED
            else -> CommandContext.IDLE
        }

        // Privacy-safe: character count and confidence, never the transcript itself (§46).
        AppLogger.i(
            tag,
            "Recognition turn complete purpose=${outcome.purpose.name} ${outcome.logSummary()}"
        )

        // A pending rating confirmation is only ever resolvable in the rating window; it is
        // also cleared by beginStt() when any non-confirmation turn starts.
        val pendingConfirmation = if (context == CommandContext.RATING_EXPECTED) {
            peekPendingRatingConfirmation()
        } else {
            clearPendingRatingConfirmation()
            null
        }

        applyCommandDecision(
            commandInterpreter.interpret(outcome, context, currentSttSettings, pendingConfirmation),
            state
        )
    }

    private fun applyCommandDecision(decision: CommandDecision, state: StudyState) {
        when (decision) {
            is CommandDecision.Execute -> {
                AppLogger.i(tag, "Voice command accepted: ${decision.parsed.command.commandName}")
                clearPendingRatingConfirmation()
                _lastRecognizedCommand.tryEmit(decision.parsed.command)
                processVoiceCommandDirectly(decision.parsed.command)
            }

            is CommandDecision.SubmitAnswer -> {
                clearPendingRatingConfirmation()
                submitOrHoldAnswer(decision.text, state)
            }

            is CommandDecision.NeedsConfirmation -> {
                // A rating was heard but not confidently enough to reschedule a card. Ask,
                // then listen for a short yes/no rather than guessing (§14/§140).
                AppLogger.i(tag, "Rating needs confirmation; prompting")
                setPendingRatingConfirmation(decision.options.firstOrNull())
                promptAndListen(decision.prompt, RecognitionPurpose.SHORT_CONFIRMATION)
            }

            is CommandDecision.Retry -> {
                // A declined or unresolved confirmation is void; the re-listen starts fresh.
                clearPendingRatingConfirmation()
                AppLogger.d(tag, "Nothing actionable (${decision.reason}); re-listening")
                if (currentSettings.handsFreeMode) {
                    promptAndListen(decision.prompt, purposeForState(state))
                }
            }

            is CommandDecision.Ignore -> {
                clearPendingRatingConfirmation()
                AppLogger.d(tag, "Ignoring utterance: ${decision.reason}")
            }
        }
    }

    /**
     * §19: `autoSubmitTranscript` is finally load-bearing.
     *
     * On: the transcript goes straight to the evaluator. Off: it is parked on the study state
     * so the user can review, edit, retry or submit it — the microphone closes and nothing is
     * sent until the user acts.
     *
     * Study-state gate (§10): an answer is only ever submitted while an answer is actually
     * expected. A long utterance that lands in the *rating* window (the interpreter's
     * "user may be answering again" fallback) is ignored here rather than sent — the card
     * has already been answered and evaluated, and a second SubmitAnswer would make the PC
     * evaluate the same card twice.
     */
    private fun submitOrHoldAnswer(text: String, state: StudyState) {
        val card = state.currentCardOrNull ?: _currentSession.value?.currentCard ?: return
        when (state) {
            is StudyState.Listening ->
                if (currentSettings.autoSubmitTranscript) {
                    submitSpokenAnswer(card.id, text)
                } else {
                    AppLogger.i(tag, "Auto-submit off; holding transcript for review (${text.length} chars)")
                    _studyState.value = StudyState.Listening(
                        card = card,
                        partialTranscript = "",
                        isHandsFree = currentSettings.handsFreeMode,
                        pendingTranscript = text
                    )
                }

            is StudyState.WaitingForRating,
            is StudyState.ShowingFeedback,
            is StudyState.ExplanationShowing,
            is StudyState.HintShowing -> {
                AppLogger.w(
                    tag,
                    "Answer-like utterance ignored: card already answered (${text.length} chars); staying in rating window"
                )
                if (currentSettings.handsFreeMode &&
                    currentSettings.listenForSpokenRating &&
                    speechSettled()
                ) {
                    // Keep the rating window alive rather than stranding hands-free mode.
                    beginStt(RecognitionPurpose.RATING, card)
                }
            }

            else -> AppLogger.w(
                tag,
                "Answer-like utterance ignored in state ${state::class.simpleName} (${text.length} chars)"
            )
        }
    }

    override fun submitPendingTranscript() {
        val state = _studyState.value
        if (state !is StudyState.Listening || state.pendingTranscript.isBlank()) return
        submitSpokenAnswer(state.card.id, state.pendingTranscript)
    }

    override fun discardPendingTranscript() {
        val state = _studyState.value
        if (state !is StudyState.Listening) return
        _studyState.value = state.copy(partialTranscript = "", pendingTranscript = "")
        if (currentSettings.handsFreeMode && speechSettled()) {
            beginStt(RecognitionPurpose.ANSWER, state.card)
        }
    }

    private fun purposeForState(state: StudyState): RecognitionPurpose = when (state) {
        is StudyState.WaitingForRating -> RecognitionPurpose.RATING
        is StudyState.Paused, StudyState.Idle -> RecognitionPurpose.COMMAND
        else -> RecognitionPurpose.ANSWER
    }

    /** Speak a short prompt, then re-open the microphone for [purpose]. */
    private fun promptAndListen(prompt: String, purpose: RecognitionPurpose) {
        val card = _studyState.value.currentCardOrNull
        speakSpeechRequest(
            SpeechRequest(
                id = SpeechIds.forPurpose(SpeechPurpose.FEEDBACK, card?.id),
                text = prompt,
                purpose = SpeechPurpose.FEEDBACK,
                priority = SpeechPriority.NORMAL,
                queuePolicy = QueuePolicy.REPLACE
            )
        ) { result ->
            // maybeResumeListeningAfterSpeech() re-opens the mic once speech drains, using the
            // current study state — no separate start path to fall out of sync.
            if (result is SpeechResult.Failed) handleSpeechFailure(result.error, card)
        }
    }

    /**
     * Errors the orchestrator's own bounded retry could not resolve.
     *
     * Each branch is deliberately different (§50): a permission problem shows actionable UI
     * and never retries; a missing language model offers a download; transient audio problems
     * re-listen, but only up to [MAX_CONSECUTIVE_RECOGNITION_FAILURES] so a dead microphone
     * cannot spin for the rest of the session.
     */
    private fun handleRecognitionFailure(error: RecognitionError) {
        AppLogger.w(
            tag,
            "Recognition failed code=${error.code.name} raw=${error.rawCode} detail=${error.detail}"
        )

        when (error.code) {
            RecognitionErrorCode.CANCELLED -> return // Expected: we cancelled it.

            RecognitionErrorCode.PERMISSION_DENIED -> {
                _studyState.value = StudyState.Error(
                    message = "Microphone permission is needed for voice study. " +
                        "Grant it in system settings, or use the on-screen controls.",
                    recoverable = true
                )
                return
            }

            RecognitionErrorCode.UNAVAILABLE -> {
                _studyState.value = StudyState.Error(
                    message = "No speech recognition service is available on this device. " +
                        "You can still study using the on-screen controls.",
                    recoverable = true
                )
                return
            }

            RecognitionErrorCode.LANGUAGE_UNSUPPORTED,
            RecognitionErrorCode.LANGUAGE_MODEL_UNAVAILABLE -> {
                val locale = if (currentSttSettings.languageMode == RecognitionLanguageMode.ARABIC) {
                    currentSttSettings.arabicLocale
                } else {
                    currentSttSettings.englishLocale
                }
                recognitionOrchestrator.requestModelDownload(locale)
                _studyState.value = StudyState.Error(
                    message = "The speech model for $locale is not installed. " +
                        "Download started, or switch recognition to network mode in Settings.",
                    recoverable = true
                )
                return
            }

            RecognitionErrorCode.TOO_MANY_REQUESTS -> {
                // The orchestrator deliberately does not auto-retry throttled starts (§52);
                // immediately re-opening the mic here would recreate exactly the rapid
                // start/timeout/restart cycle the rate limiter exists to prevent. Back off
                // to the user instead — the study state is preserved and PTT/buttons work.
                AppLogger.w(tag, "Recognition rate limited; handing control back to the user")
                _studyState.value = StudyState.Error(
                    message = "Speech recognition is rate limited. Wait a moment, then use " +
                        "push-to-talk or the on-screen controls.",
                    recoverable = true
                )
                return
            }

            else -> Unit
        }

        consecutiveRecognitionFailures++
        if (consecutiveRecognitionFailures >= MAX_CONSECUTIVE_RECOGNITION_FAILURES) {
            AppLogger.e(tag, "Recognition failing repeatedly; stopping hands-free listening")
            _studyState.value = StudyState.Error(
                message = "Voice recognition is not working (${error.code.label}). " +
                    "You can continue with the on-screen controls.",
                recoverable = true
            )
            return
        }

        if (!currentSettings.handsFreeMode || !speechSettled()) return
        val state = _studyState.value
        val card = state.currentCardOrNull ?: return
        val prompt = when (error.code) {
            RecognitionErrorCode.NO_SPEECH -> "Sorry, I didn't catch that. Please try again."
            RecognitionErrorCode.NO_MATCH -> "Sorry, I didn't catch that. Please try again."
            else -> null
        }
        if (prompt != null) {
            promptAndListen(prompt, purposeForState(state))
        } else {
            beginStt(purposeForState(state), card)
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
        // Fresh session: the per-card submission window reopens.
        synchronized(submissionLock) {
            pendingRatingConfirmation = null
            answerSubmittedForCardId = null
            ratingSubmittedForCardId = null
        }
        isPushToTalkActive = false
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
        // Exactly-once answer submission (§10). The ledger wins the race regardless of how
        // many paths converge here — voice final, pending-transcript confirm, button —
        // because the check-and-set is atomic and happens before anything is sent.
        if (!tryBeginAnswerSubmission(cardId)) {
            AppLogger.w(
                tag,
                "Duplicate answer submission suppressed (card=$cardId chars=${transcript.length})"
            )
            return
        }
        // Cancel rather than finish: the answer is already in hand, so there is nothing left
        // to finalise — and cancelling invalidates the request id immediately (§97).
        recognitionOrchestrator.cancelCurrentTurn("answer-submitted")
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
        // Button-wins invariant (§70/§71): invalidate any rating recognition immediately, so
        // a voice result in flight can never land after the explicit user action.
        recognitionOrchestrator.cancelCurrentTurn("rating-submitted")
        val card = _studyState.value.currentCardOrNull ?: _currentSession.value?.currentCard ?: return
        // Exactly-once rating submission (§11): one rating per card, whoever calls first —
        // voice command, rating button, or both racing each other.
        if (!tryBeginRatingSubmission(card.id)) {
            AppLogger.w(tag, "Duplicate rating suppressed (card=${card.id} rating=${rating.name})")
            return
        }
        speechOrchestrator.stopSpeech(StopReason.USER) // rating dismisses spoken feedback
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
        // Skipping discards whatever was being recognised for this card; it must not be
        // submitted after the card has gone.
        recognitionOrchestrator.cancelCurrentTurn("card-skipped")
        val card = _studyState.value.currentCardOrNull ?: _currentSession.value?.currentCard
        connectionRepository.send(
            ClientMessage.SkipCard(
                sessionId = _currentSession.value?.sessionId,
                cardId = card?.id
            )
        )
    }

    override suspend fun pauseStudy() {
        // A pending PTT delayed-start must not fire after the pause cancelled everything.
        isPushToTalkActive = false
        speechOrchestrator.stopSpeech(StopReason.PAUSE)
        // §95: cancelling invalidates the request id, so a final callback that lands after
        // the pause cannot submit an answer into a paused session.
        recognitionOrchestrator.cancelCurrentTurn("user-paused")
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
        // Kill any pending PTT delayed-start together with the session itself.
        isPushToTalkActive = false
        synchronized(submissionLock) {
            pendingRatingConfirmation = null
            answerSubmittedForCardId = null
            ratingSubmittedForCardId = null
        }
        speechOrchestrator.stopSpeech(StopReason.SESSION_END)
        recognitionOrchestrator.cancelCurrentTurn("session-ended")
        val sess = _currentSession.value
        connectionRepository.send(ClientMessage.EndSession(sessionId = sess?.sessionId))
        _studyState.value = StudyState.SessionFinished(
            summary = "Session ended.",
            cardsReviewed = sess?.cardNumber ?: 0
        )
        _currentSession.value = null
    }

    /**
     * Press push-to-talk (§18/§59).
     *
     * TTS is interrupted first, then the microphone opens after a short acoustic gap so the
     * recognizer cannot capture the tail of the app's own speech. The user still controls
     * when recognition ends.
     *
     * Gating (§95): PTT is only meaningful inside an active study window. Outside one —
     * paused, loading, errored out, finished — it is refused instead of silently dragging
     * the study state to Listening. In the rating window PTT keeps the state and listens
     * for a rating/command; in answer windows it opens the answer listener.
     */
    override fun startManualPushToTalk() {
        val state = _studyState.value
        val inStudyWindow = state is StudyState.SpeakingQuestion ||
            state is StudyState.Listening ||
            state is StudyState.ShowingFeedback ||
            state is StudyState.WaitingForRating ||
            state is StudyState.HintShowing ||
            state is StudyState.ExplanationShowing
        if (!inStudyWindow) {
            AppLogger.d(tag, "PTT ignored: no active study window (${state::class.simpleName})")
            return
        }

        isPushToTalkActive = true
        speechOrchestrator.stopSpeech(StopReason.USER)
        val card = state.currentCardOrNull ?: _currentSession.value?.currentCard ?: return

        // The purpose must match the window: re-answering in the rating window is not a
        // thing (the ledger would reject it), so there PTT captures a rating/command.
        val purpose: RecognitionPurpose
        when (state) {
            is StudyState.WaitingForRating,
            is StudyState.ShowingFeedback -> purpose = RecognitionPurpose.PUSH_TO_TALK_COMMAND
            else -> {
                purpose = RecognitionPurpose.PUSH_TO_TALK_ANSWER
                _studyState.value = StudyState.Listening(
                    card = card,
                    partialTranscript = "",
                    isHandsFree = false
                )
            }
        }

        val gapMs = handoffController.policy().failedGapMs.toLong()
        scope.launch {
            if (gapMs > 0) delay(gapMs)
            // The user may already have released, paused or ended the session while the
            // acoustic gap elapsed; a stale delayed start would reopen the mic into a
            // state that no longer expects it.
            if (!isPushToTalkActive) return@launch
            if (!recognitionOrchestrator.state.value.isReadyForNewRequest) return@launch
            beginStt(purpose, card)
        }
    }

    /**
     * Release push-to-talk.
     *
     * `stopListening`, not `cancel`, and **no submission here**: the turn stays active until
     * the recognizer returns `onResults`, and [handleCompletedTurn] submits it. Submitting at
     * release time used to lose the last words of every long medical answer.
     */
    override fun stopManualPushToTalk() {
        if (!isPushToTalkActive) return
        isPushToTalkActive = false
        if (recognitionOrchestrator.state.value.isActive) {
            recognitionOrchestrator.finishCurrentTurn()
        }
    }

    companion object {
        /** Back-to-back STT starts within this window are deduplicated (handoff vs backstop). */
        const val STT_START_DEDUP_MS = 150L

        /**
         * Consecutive recognition failures tolerated before hands-free listening gives up and
         * falls back to the on-screen controls.
         */
        const val MAX_CONSECUTIVE_RECOGNITION_FAILURES = 3
    }
}
