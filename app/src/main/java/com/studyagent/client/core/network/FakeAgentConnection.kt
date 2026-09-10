package com.studyagent.client.core.network

import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.Evaluation
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.core.models.StudyCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class FakeAgentConnection(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val simulateNetworkDelayMs: Long = 50L
) : AgentConnection {

    private val tag = "FakeAgentConnection"
    private var isConnected = false
    private var activeSessionId: String? = null
    private var currentCardIndex = 0

    private val sampleCards = listOf(
        StudyCard(
            id = "card-001",
            question = "What are the indications for evacuation of an epidural hematoma?",
            cardNumber = 1,
            remaining = 4,
            deckName = "Toronto Notes - Neurosurgery"
        ),
        StudyCard(
            id = "card-002",
            question = "What are the classic ECG findings in acute pericarditis?",
            cardNumber = 2,
            remaining = 3,
            deckName = "Toronto Notes - Cardiology"
        ),
        StudyCard(
            id = "card-003",
            question = "What is the immediate emergency management of a tension pneumothorax?",
            cardNumber = 3,
            remaining = 2,
            deckName = "Toronto Notes - Trauma Surgery"
        ),
        StudyCard(
            id = "card-004",
            question = "What are the three components of Virchow's triad for thrombogenesis?",
            cardNumber = 4,
            remaining = 1,
            deckName = "Toronto Notes - Hematology"
        ),
        StudyCard(
            id = "card-005",
            question = "What is the first-line empiric treatment for uncomplicated community-acquired pneumonia in a healthy outpatient?",
            cardNumber = 5,
            remaining = 0,
            deckName = "Toronto Notes - Respirology"
        )
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<ServerMessage>(replay = 1, extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<ServerMessage> = _incomingMessages.asSharedFlow()

    private var profile: ServerProfile? = null
    override val currentProfile: ServerProfile?
        get() = profile

    override suspend fun connect(profile: ServerProfile) {
        this.profile = profile
        AppLogger.i(tag, "Connecting to Fake Agent: ${profile.name}")
        _connectionState.value = ConnectionState.Connecting(profile.host, profile.port)
        if (simulateNetworkDelayMs > 0) delay(simulateNetworkDelayMs)
        isConnected = true
        _connectionState.value = ConnectionState.Connected(
            host = profile.host,
            port = profile.port,
            serverName = "Fake Study Agent (Mock Mode)",
            latencyMs = 5L
        )
    }

    override suspend fun disconnect(reason: String) {
        AppLogger.i(tag, "Disconnecting Fake Agent: $reason")
        isConnected = false
        activeSessionId = null
        currentCardIndex = 0
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun send(message: ClientMessage): Boolean {
        if (!isConnected) {
            AppLogger.w(tag, "Cannot send message while disconnected")
            return false
        }

        AppLogger.i(tag, "FakeAgent received client message: ${message.type}")
        scope.launch {
            handleClientMessage(message)
        }
        return true
    }

    private suspend fun handleClientMessage(message: ClientMessage) {
        if (simulateNetworkDelayMs > 0) delay(simulateNetworkDelayMs)

        when (message) {
            is ClientMessage.Hello -> {
                // Agent is ready
            }

            is ClientMessage.Authenticate -> {
                AppLogger.i(tag, "Authenticated with token")
            }

            is ClientMessage.Ping -> {
                _incomingMessages.emit(ServerMessage.Pong(sessionId = activeSessionId))
            }

            is ClientMessage.StartSession -> {
                val newSessionId = UUID.randomUUID().toString().take(8)
                activeSessionId = newSessionId
                currentCardIndex = 0

                _incomingMessages.emit(
                    ServerMessage.SessionStarted(
                        sessionId = newSessionId,
                        deck = message.deck ?: "Toronto Notes",
                        totalCards = sampleCards.size
                    )
                )

                if (simulateNetworkDelayMs > 0) delay(simulateNetworkDelayMs)
                sendCurrentCard()
            }

            is ClientMessage.SubmitAnswer -> {
                val card = sampleCards.getOrNull(currentCardIndex) ?: sampleCards.first()
                val (score, feedback, correct, missing, suggested) = evaluateMockAnswer(card.id, message.text)

                val eval = ServerMessage.EvaluationResponse(
                    sessionId = activeSessionId,
                    cardId = card.id,
                    score = score,
                    correctPoints = correct,
                    missingPoints = missing,
                    shortFeedback = feedback,
                    suggestedRating = suggested,
                    speak = true
                )
                _incomingMessages.emit(eval)
            }

            is ClientMessage.RateCard -> {
                _incomingMessages.emit(
                    ServerMessage.RatingSaved(
                        sessionId = activeSessionId,
                        cardId = message.cardId,
                        rating = message.rating,
                        nextInterval = when (message.rating) {
                            Rating.AGAIN -> "10 minutes"
                            Rating.HARD -> "1 day"
                            Rating.GOOD -> "3 days"
                            Rating.EASY -> "7 days"
                        }
                    )
                )

                if (simulateNetworkDelayMs > 0) delay(simulateNetworkDelayMs)
                currentCardIndex++
                if (currentCardIndex < sampleCards.size) {
                    sendCurrentCard()
                } else {
                    _incomingMessages.emit(
                        ServerMessage.SessionFinished(
                            sessionId = activeSessionId,
                            totalReviewed = sampleCards.size,
                            summary = "Great job! You reviewed all ${sampleCards.size} cards in this deck."
                        )
                    )
                }
            }

            is ClientMessage.RepeatQuestion -> {
                sendCurrentCard()
            }

            is ClientMessage.RequestHint -> {
                val card = sampleCards.getOrNull(currentCardIndex) ?: sampleCards.first()
                val hint = when (card.id) {
                    "card-001" -> "Consider hematoma volume over thirty milliliters and midline shift."
                    "card-002" -> "Think about PR segment depressions and widespread ST segment elevations."
                    "card-003" -> "Immediate needle thoracostomy decompression before chest tube."
                    "card-004" -> "Endothelial injury, stasis, and hypercoagulability."
                    else -> "Focus on atypical vs typical coverage with high-dose amoxicillin or doxycycline."
                }
                _incomingMessages.emit(
                    ServerMessage.Hint(
                        sessionId = activeSessionId,
                        cardId = card.id,
                        hintText = hint,
                        speak = true
                    )
                )
            }

            is ClientMessage.RequestExplanation -> {
                val card = sampleCards.getOrNull(currentCardIndex) ?: sampleCards.first()
                val explanation = when (card.id) {
                    "card-001" -> "Evacuation is indicated for EDH volume > 30 cm3 regardless of GCS, or GCS < 9 with pupillary anisocoria or midline shift > 5 mm."
                    "card-002" -> "Pericarditis typically presents with widespread upward-concave ST segment elevation and PR segment depression (especially in II, aVF, V4-V6), with reciprocal PR elevation in aVR."
                    else -> "Detailed clinical explanation from Study PC Agent."
                }
                _incomingMessages.emit(
                    ServerMessage.Explanation(
                        sessionId = activeSessionId,
                        cardId = card.id,
                        explanationText = explanation,
                        speak = true
                    )
                )
            }

            is ClientMessage.RequestAnswer -> {
                val card = sampleCards.getOrNull(currentCardIndex) ?: sampleCards.first()
                _incomingMessages.emit(
                    ServerMessage.Answer(
                        sessionId = activeSessionId,
                        cardId = card.id,
                        answerText = "The full answer for ${card.id} is available in your notes.",
                        speak = true
                    )
                )
            }

            is ClientMessage.SkipCard -> {
                currentCardIndex++
                if (currentCardIndex < sampleCards.size) {
                    sendCurrentCard()
                } else {
                    _incomingMessages.emit(
                        ServerMessage.SessionFinished(
                            sessionId = activeSessionId,
                            totalReviewed = currentCardIndex,
                            summary = "Deck completed."
                        )
                    )
                }
            }

            is ClientMessage.PauseSession -> {
                _incomingMessages.emit(ServerMessage.SessionPaused(sessionId = activeSessionId))
            }

            is ClientMessage.ResumeSession -> {
                _incomingMessages.emit(ServerMessage.SessionResumed(sessionId = activeSessionId))
            }

            is ClientMessage.EndSession -> {
                _incomingMessages.emit(
                    ServerMessage.SessionFinished(
                        sessionId = activeSessionId,
                        totalReviewed = currentCardIndex,
                        summary = "Session ended by user."
                    )
                )
                activeSessionId = null
            }

            is ClientMessage.RequestSessionStatus -> {
                _incomingMessages.emit(
                    ServerMessage.SessionStats(
                        sessionId = activeSessionId,
                        cardsStudied = currentCardIndex,
                        recallRate = 85.0,
                        remainingDue = sampleCards.size - currentCardIndex
                    )
                )
            }
        }
    }

    private suspend fun sendCurrentCard() {
        val card = sampleCards.getOrNull(currentCardIndex) ?: return
        _incomingMessages.emit(
            ServerMessage.Question(
                sessionId = activeSessionId,
                cardId = card.id,
                question = card.question,
                cardNumber = card.cardNumber,
                remaining = card.remaining,
                speak = true
            )
        )
    }

    private fun evaluateMockAnswer(cardId: String, answer: String): EvaluationTuple {
        val lower = answer.lowercase()
        return when (cardId) {
            "card-001" -> {
                val hasVol = lower.contains("30") || lower.contains("volume") || lower.contains("thirty")
                val hasShift = lower.contains("midline") || lower.contains("5") || lower.contains("five")
                val hasNeuro = lower.contains("deterioration") || lower.contains("gcs") || lower.contains("coma") || lower.contains("pupil")

                val correct = mutableListOf<String>()
                val missing = mutableListOf<String>()

                if (hasVol) correct.add("Volume greater than 30 mL") else missing.add("Volume > 30 mL")
                if (hasShift) correct.add("Midline shift greater than 5 mm") else missing.add("Midline shift > 5 mm")
                if (hasNeuro) correct.add("Neurological deterioration or GCS < 9") else missing.add("Neurological deterioration")

                val score = (correct.size * 100) / 3
                val feedback = if (missing.isEmpty()) {
                    "Excellent and complete answer!"
                } else {
                    "Good answer. You missed ${missing.joinToString(", ")}."
                }
                val rating = if (score >= 90) Rating.GOOD else if (score >= 60) Rating.HARD else Rating.AGAIN
                EvaluationTuple(score, feedback, correct, missing, rating)
            }
            "card-002" -> {
                EvaluationTuple(
                    score = 92,
                    feedback = "Great answer! PR depression and diffuse ST elevation are key findings.",
                    correct = listOf("PR depression", "Diffuse ST elevation"),
                    missing = listOf("Reciprocal ST depression in aVR"),
                    suggested = Rating.GOOD
                )
            }
            else -> {
                EvaluationTuple(
                    score = 88,
                    feedback = "Well done, key points covered accurately.",
                    correct = listOf("First-line management steps"),
                    missing = emptyList(),
                    suggested = Rating.GOOD
                )
            }
        }
    }

    private data class EvaluationTuple(
        val score: Int,
        val feedback: String,
        val correct: List<String>,
        val missing: List<String>,
        val suggested: Rating
    )
}
