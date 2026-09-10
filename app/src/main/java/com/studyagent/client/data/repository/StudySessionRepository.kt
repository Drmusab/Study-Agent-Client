package com.studyagent.client.data.repository

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
import com.studyagent.client.core.voice.TextToSpeechManager
import com.studyagent.client.core.voice.VoiceCommandManager
import com.studyagent.client.data.preferences.PreferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale

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

    fun startManualPushToTalk()
    fun stopManualPushToTalk(submitIfTranscriptPresent: Boolean = true)
    fun processVoiceCommandDirectly(command: VoiceCommand)
}

class DefaultStudySessionRepository(
    private val connectionRepository: ConnectionRepository,
    private val ttsManager: TextToSpeechManager,
    private val sttManager: SpeechRecognitionManager,
    private val voiceCommandManager: VoiceCommandManager,
    private val preferencesDataStore: PreferencesDataStore,
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
    private var isPushToTalkActive = false
    private var lastRecordedTranscript = ""
    private var listeningJob: Job? = null

    init {
        // Observe settings changes
        scope.launch {
            preferencesDataStore.settingsFlow.distinctUntilChanged().collect { settings ->
                currentSettings = settings
                val ttsLocale = parseLocale(settings.ttsLanguage)
                ttsManager.setLanguage(ttsLocale)
                ttsManager.setSpeechRate(settings.speechRate)
                ttsManager.setPitch(settings.speechPitch)
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
                            ttsManager.stop()
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

        // Observe STT recognition events
        scope.launch {
            sttManager.recognitionEvents.collect { event ->
                handleSpeechRecognitionResult(event)
            }
        }
    }

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

                _studyState.value = StudyState.SpeakingQuestion(card)

                if (message.speak && currentSettings.autoPlayQuestion) {
                    speakQuestionAndListen(card)
                } else {
                    startListeningForAnswer(card)
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
                    ttsManager.speak(
                        text = eval.shortFeedback,
                        flushQueue = true,
                        utteranceId = "eval_${card.id}",
                        onDone = {
                            _studyState.value = StudyState.WaitingForRating(card, eval, eval.suggestedRating)
                            if (currentSettings.handsFreeMode) {
                                startListeningForRating(card, eval)
                            }
                        },
                        onError = {
                            _studyState.value = StudyState.WaitingForRating(card, eval, eval.suggestedRating)
                            if (currentSettings.handsFreeMode) {
                                startListeningForRating(card, eval)
                            }
                        }
                    )
                } else {
                    _studyState.value = StudyState.WaitingForRating(card, eval, eval.suggestedRating)
                    if (currentSettings.handsFreeMode) {
                        startListeningForRating(card, eval)
                    }
                }
            }

            is ServerMessage.Hint -> {
                val card = _currentSession.value?.currentCard ?: return
                _studyState.value = StudyState.HintShowing(card, message.hintText, isSpeaking = message.speak)
                if (message.speak) {
                    ttsManager.speak(
                        text = "Hint: ${message.hintText}",
                        flushQueue = true,
                        utteranceId = "hint_${card.id}",
                        onDone = {
                            startListeningForAnswer(card)
                        }
                    )
                } else {
                    startListeningForAnswer(card)
                }
            }

            is ServerMessage.Explanation -> {
                val card = _currentSession.value?.currentCard ?: return
                _studyState.value = StudyState.ExplanationShowing(card, message.explanationText, isSpeaking = message.speak)
                if (message.speak) {
                    ttsManager.speak(
                        text = message.explanationText,
                        flushQueue = true,
                        utteranceId = "explain_${card.id}",
                        onDone = {
                            val lastEval = _currentSession.value?.lastEvaluation
                            if (lastEval != null) {
                                _studyState.value = StudyState.WaitingForRating(card, lastEval, lastEval.suggestedRating)
                                if (currentSettings.handsFreeMode) {
                                    startListeningForRating(card, lastEval)
                                }
                            } else {
                                startListeningForAnswer(card)
                            }
                        }
                    )
                }
            }

            is ServerMessage.Answer -> {
                val card = _currentSession.value?.currentCard ?: return
                if (message.speak) {
                    ttsManager.speak(
                        text = "Answer: ${message.answerText}",
                        flushQueue = true,
                        utteranceId = "ans_${card.id}",
                        onDone = {
                            val lastEval = _currentSession.value?.lastEvaluation ?: Evaluation(
                                shortFeedback = message.answerText
                            )
                            _studyState.value = StudyState.WaitingForRating(card, lastEval, null)
                            if (currentSettings.handsFreeMode) {
                                startListeningForRating(card, lastEval)
                            }
                        }
                    )
                }
            }

            is ServerMessage.RatingSaved -> {
                AppLogger.i(tag, "Rating ${message.rating} saved for card ${message.cardId}. Next interval: ${message.nextInterval}")
                _studyState.value = StudyState.Loading("Rating saved. Fetching next card...")
            }

            is ServerMessage.SessionPaused -> {
                val current = _studyState.value
                if (current !is StudyState.Paused) {
                    ttsManager.stop()
                    sttManager.stopListening()
                    _studyState.value = StudyState.Paused(current)
                }
            }

            is ServerMessage.SessionResumed -> {
                val current = _studyState.value
                if (current is StudyState.Paused) {
                    _studyState.value = current.previousState
                    // Resume action
                    current.previousState.currentCardOrNull?.let { card ->
                        if (current.previousState is StudyState.SpeakingQuestion) {
                            speakQuestionAndListen(card)
                        } else if (current.previousState is StudyState.Listening) {
                            startListeningForAnswer(card)
                        }
                    }
                }
            }

            is ServerMessage.SessionFinished -> {
                ttsManager.stop()
                sttManager.stopListening()
                _studyState.value = StudyState.SessionFinished(
                    summary = message.summary,
                    cardsReviewed = message.totalReviewed
                )
                message.summary?.let { sum ->
                    ttsManager.speak(sum, flushQueue = true, utteranceId = "session_finished")
                }
            }

            is ServerMessage.SessionStats -> {
                AppLogger.i(tag, "Session Stats: studied=${message.cardsStudied}, recall=${message.recallRate}%, remaining=${message.remainingDue}")
                _currentSession.value = _currentSession.value?.copy(
                    totalReviewedInSession = message.cardsStudied,
                    remainingCards = message.remainingDue
                )
                ttsManager.speak("You have ${message.remainingDue} cards left in this session.")
            }

            is ServerMessage.ErrorMessage -> {
                AppLogger.e(tag, "Server error: ${message.message} (code: ${message.code})")
                _studyState.value = StudyState.Error(message.message, recoverable = true)
            }

            else -> Unit
        }
    }

    private fun speakQuestionAndListen(card: StudyCard) {
        ttsManager.speak(
            text = card.question,
            flushQueue = true,
            utteranceId = "q_${card.id}",
            onDone = {
                if (currentSettings.handsFreeMode) {
                    scope.launch {
                        delay(200) // brief pause for acoustic separation
                        startListeningForAnswer(card)
                    }
                }
            },
            onError = { err ->
                AppLogger.w(tag, "TTS failed to speak question: $err")
                if (currentSettings.handsFreeMode) {
                    startListeningForAnswer(card)
                }
            }
        )
    }

    private fun startListeningForAnswer(card: StudyCard) {
        ttsManager.stop()
        _studyState.value = StudyState.Listening(card = card, partialTranscript = "", isHandsFree = currentSettings.handsFreeMode)
        sttManager.startListening(currentSettings.sttLanguage, isHandsFree = currentSettings.handsFreeMode)
    }

    private fun startListeningForRating(card: StudyCard, eval: Evaluation) {
        ttsManager.stop()
        _studyState.value = StudyState.WaitingForRating(card = card, evaluation = eval, suggestedRating = eval.suggestedRating)
        sttManager.startListening(currentSettings.sttLanguage, isHandsFree = true)
    }

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

                AppLogger.i(tag, "Handling final transcript: '$transcript'")
                val currentState = _studyState.value
                val recognizedCmd = voiceCommandManager.parseInStudyContext(transcript, currentState)
                _lastRecognizedCommand.tryEmit(recognizedCmd)

                processVoiceCommandDirectly(recognizedCmd)
            }

            is SpeechRecognitionResult.NoSpeech -> {
                val current = _studyState.value
                AppLogger.d(tag, "STT reported NoSpeech in state: $current")
                if (current is StudyState.Listening && current.isHandsFree) {
                    // In hands free mode, if no speech heard, keep waiting or gently prompt
                } else if (current is StudyState.WaitingForRating && currentSettings.handsFreeMode) {
                    // Retry listening for rating
                }
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
                        AppLogger.w(tag, "Unknown voice command: '${command.rawText}'")
                    }
                }
            }
        }
    }

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
        ttsManager.stop()
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
                    startListeningForAnswer(card)
                }
            }
        }
    }

    override suspend fun endStudy() {
        ttsManager.stop()
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
        ttsManager.stop()
        val card = _studyState.value.currentCardOrNull ?: _currentSession.value?.currentCard
        if (card != null) {
            _studyState.value = StudyState.Listening(card = card, partialTranscript = "", isHandsFree = false)
            sttManager.startListening(currentSettings.sttLanguage, isHandsFree = false)
        }
    }

    override fun stopManualPushToTalk(submitIfTranscriptPresent: Boolean) {
        isPushToTalkActive = false
        sttManager.stopListening()
    }

    private fun parseLocale(localeCode: String): Locale {
        return try {
            when {
                localeCode.startsWith("ar") -> Locale("ar", "SA")
                localeCode.startsWith("en") -> Locale.US
                else -> Locale.forLanguageTag(localeCode)
            }
        } catch (_: Exception) {
            Locale.US
        }
    }
}
