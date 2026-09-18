package com.studyagent.client.core.network

import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.models.AgentCapability
import com.studyagent.client.core.models.AiUsageSummary
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ComponentHealthEntry
import com.studyagent.client.core.models.ComponentStatus
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.DashboardSnapshotPayload
import com.studyagent.client.core.models.DayStats
import com.studyagent.client.core.models.DeckSummary
import com.studyagent.client.core.models.LearningInsight
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.models.RatingDistribution
import com.studyagent.client.core.models.RecentPerformance
import com.studyagent.client.core.models.ComponentHealth
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.core.models.SessionSummaryPayload
import com.studyagent.client.core.models.StudyCard
import com.studyagent.client.core.models.StudyControlConfig
import com.studyagent.client.core.models.StudyGoalProgress
import com.studyagent.client.core.models.StudyHistoryPayload
import com.studyagent.client.core.models.StudyMode
import com.studyagent.client.core.models.StudyRecommendation
import com.studyagent.client.core.models.TodayStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

enum class FakeCapabilityMode { FULL_V2, PARTIAL_V2, V1_ONLY }

class FakeAgentConnection(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val simulateNetworkDelayMs: Long = 50L,
    private val enableChaos: Boolean = false,
    private val chaosSeed: Long = 42L
) : AgentConnection {

    var capabilityMode: FakeCapabilityMode = FakeCapabilityMode.FULL_V2
    var simulateConfigRejection: Boolean = false
    var simulateAuthFailure: Boolean = false

    private val tag = "FakeAgentConnection"
    private var isConnected = false
    private var activeSessionId: String? = null
    private var activeSessionDeck: String? = null
    private var activeSessionMode: String = "review_due"
    private var sessionPaused = false
    private var sessionElapsedSeconds = 0L
    private var currentCardIndex = 0
    private var sessionRevision: Long = 0L
    private var messageSeq: Long = 0L
    private var connectionGeneration: Long = 0L

    private val handledMessageIds = mutableSetOf<String>()
    private val chaosRandom = Random(chaosSeed)

    var chaosDuplicateProb: Double = 0.15
    var chaosDelayProb: Double = 0.15
    var chaosDropProb: Double = 0.05
    var chaosReorderProb: Double = 0.05
    var chaosMaxExtraDelayMs: Long = 300L

    private val sampleCards = listOf(
        StudyCard(id = "card-001", question = "What are the indications for evacuation of an epidural hematoma?", cardNumber = 1, remaining = 4, deckName = "Surgery::Neurosurgery"),
        StudyCard(id = "card-002", question = "What are the classic ECG findings in acute pericarditis?", cardNumber = 2, remaining = 3, deckName = "MCCQE::Cardiology"),
        StudyCard(id = "card-003", question = "What is the immediate emergency management of a tension pneumothorax?", cardNumber = 3, remaining = 2, deckName = "Surgery::Trauma"),
        StudyCard(id = "card-004", question = "What are the three components of Virchow's triad for thrombogenesis?", cardNumber = 4, remaining = 1, deckName = "MCCQE::Hematology"),
        StudyCard(id = "card-005", question = "What is the first-line empiric treatment for uncomplicated community-acquired pneumonia in a healthy outpatient?", cardNumber = 5, remaining = 0, deckName = "MCCQE::Respirology")
    )

    private val fakeDecks = listOf(
        DeckSummary("MCCQE::Cardiology", dueCount = 42, newCount = 8, learningCount = 5, totalCount = 620, isFavorite = true),
        DeckSummary("MCCQE::Neurology", dueCount = 31, newCount = 4, learningCount = 2, totalCount = 540),
        DeckSummary("MCCQE::Pediatrics", dueCount = 18, newCount = 12, learningCount = 1, totalCount = 480),
        DeckSummary("MCCQE::Hematology", dueCount = 9, newCount = 2, learningCount = 0, totalCount = 260),
        DeckSummary("MCCQE::Respirology", dueCount = 14, newCount = 6, learningCount = 3, totalCount = 350),
        DeckSummary("Surgery::Neurosurgery", dueCount = 22, newCount = 3, learningCount = 2, totalCount = 310),
        DeckSummary("Surgery::Trauma", dueCount = 25, newCount = 0, learningCount = 3, totalCount = 280),
        DeckSummary("Pharmacology", dueCount = 55, newCount = 20, learningCount = 8, totalCount = 900)
    )

    private var fakeConfig = StudyControlConfig(activeDeck = "MCCQE::Cardiology", studyMode = StudyMode.DUE_AND_NEW, sessionTargetValue = 45)
    private var fakeToday = TodayStats(cardsReviewed = 427, newStudied = 32, dueRemaining = 47, recallRate = 84.0, studyTimeSeconds = 6120, avgSecondsPerCard = 14.3, dailyGoalCards = 500, dailyGoalMinutes = 120)
    private var fakeReviewedToday = 427

    private fun fakeWeek(): List<DayStats> {
        val base = System.currentTimeMillis()
        val counts = listOf(120, 142, 96, 165, 181, 154, 173)
        return counts.mapIndexed { index, reviewed ->
            DayStats(date = java.time.Instant.ofEpochMilli(base - (6L - index) * 86_400_000L).toString().substringBefore('T'), cardsReviewed = reviewed, newCards = (reviewed / 8), recallRate = 70.0 + index * 2, studyTimeSeconds = reviewed * 14L)
        }
    }

    private fun fakeGoal() = StudyGoalProgress(deck = "MCCQE::Cardiology", targetCards = 2000, targetDate = java.time.LocalDate.now().plusDays(90).toString(), learnedCards = 1240, remainingCards = 760, percentComplete = 62.0, requiredPerDay = 9.0, currentPerDay = 12.0, estimatedCompletionDate = java.time.LocalDate.now().plusDays(64).toString(), paceStatus = "ahead")
    private fun fakeInsight() = LearningInsight(deck = "MCCQE::Cardiology", weakTopic = "Cardiology", weakSubtopic = "Arrhythmias", recallRate = 62.0, missedPoints = listOf("Indications for cardioversion", "Unstable atrial fibrillation management"), advice = "Review this area for 15 minutes.", generatedAt = java.time.Instant.ofEpochMilli(System.currentTimeMillis() - 3 * 3_600_000L).toString())
    private fun fakeRecommendation() = StudyRecommendation(recommendedDeck = "MCCQE::Cardiology", recommendedMode = StudyMode.WEAK_CARDS.wireValue, reason = "Recall has fallen over the last 7 days.", estimatedCards = 30, estimatedMinutes = 20)
    private fun fakeAiUsage(range: String) = AiUsageSummary(range = range, evaluations = if (range == "today") 427 else 5210, inputTokens = if (range == "today") 380_000L else 4_600_000L, outputTokens = if (range == "today") 96_000L else 1_150_000L, estimatedCost = if (range == "today") 1.84 else 22.60, currency = "$")
    private fun fakeHealth() = listOf(ComponentHealthEntry(name = "anki", status = ComponentStatus.READY, latencyMs = 12), ComponentHealthEntry(name = "llm", status = ComponentStatus.READY, message = "Evaluator online", latencyMs = 240), ComponentHealthEntry(name = "audio", status = ComponentStatus.READY, message = "Handled by phone"))

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<ServerMessage>(replay = 0, extraBufferCapacity = 128)
    override val incomingMessages: SharedFlow<ServerMessage> = _incomingMessages.asSharedFlow()

    private val _connectionSnapshot = MutableStateFlow(AgentConnectionSnapshot.disconnected())
    override val connectionSnapshot: StateFlow<AgentConnectionSnapshot> = _connectionSnapshot.asStateFlow()

    private var profile: ServerProfile? = null
    override val currentProfile: ServerProfile?
        get() = profile

    override suspend fun connect(profile: ServerProfile) {
        connectionGeneration++
        val gen = connectionGeneration
        this.profile = profile
        AppLogger.i(tag, "Connecting to Fake Agent gen=$gen: ${profile.name} (mode=$capabilityMode)")

        _connectionState.value = ConnectionState.Resolving(profile.host, profile.port)
        updateSnapshot(TransportStatus.RESOLVING, gen)

        if (simulateNetworkDelayMs > 0) delay(simulateNetworkDelayMs / 2)

        _connectionState.value = ConnectionState.ConnectingTransport(profile.host, profile.port)
        updateSnapshot(TransportStatus.CONNECTING, gen)

        if (simulateNetworkDelayMs > 0) delay(simulateNetworkDelayMs / 2)

        if (simulateAuthFailure) {
            _connectionState.value = ConnectionState.AuthenticationFailed("Invalid token (simulated)")
            updateSnapshot(TransportStatus.FAILED, gen, problem = ConnectionProblem.AuthenticationRejected("Invalid token"))
            return
        }

        _connectionState.value = ConnectionState.TransportConnected(profile.host, profile.port, profile.name)
        updateSnapshot(TransportStatus.OPEN, gen)

        if (simulateNetworkDelayMs > 0) delay(simulateNetworkDelayMs)

        isConnected = true
        sessionRevision = 0L
        handledMessageIds.clear()

        // Simulate handshake -> welcome
        _connectionState.value = ConnectionState.Handshaking(profile.host, profile.port)
        updateSnapshot(TransportStatus.OPEN, gen)

        if (simulateNetworkDelayMs > 0) delay(simulateNetworkDelayMs)

        if (capabilityMode != FakeCapabilityMode.V1_ONLY) {
            // Send welcome (v2 handshake)
            val welcome = ServerMessage.Welcome(
                protocolVersion = "2",
                messageId = UUID.randomUUID().toString(),
                serverName = "Fake Study Agent (Mock Mode)",
                serverVersion = "2.4.0-fake",
                selectedProtocol = "2",
                capabilities = when (capabilityMode) {
                    FakeCapabilityMode.FULL_V2 -> listOf(
                        AgentCapability.DASHBOARD, AgentCapability.DECK_LIST, AgentCapability.STUDY_CONFIG,
                        AgentCapability.HISTORY, AgentCapability.COMPONENT_HEALTH, AgentCapability.LEARNING_INSIGHTS,
                        AgentCapability.AI_USAGE, AgentCapability.SESSION_PROGRESS
                    )
                    FakeCapabilityMode.PARTIAL_V2 -> listOf(AgentCapability.DASHBOARD, AgentCapability.DECK_LIST, AgentCapability.STUDY_CONFIG, AgentCapability.HISTORY)
                    FakeCapabilityMode.V1_ONLY -> emptyList()
                },
                authentication = ServerMessage.AuthInfo(required = false, authenticated = true, methods = listOf("bearer")),
                agentId = "fake-agent-${profile.id}"
            )
            _incomingMessages.emit(welcome)

            _connectionState.value = ConnectionState.NegotiatingCapabilities(profile.host, profile.port, welcome.serverName, "2")
            updateSnapshot(TransportStatus.OPEN, gen, serverName = welcome.serverName, serverVersion = welcome.serverVersion, protocolVersion = "2", capabilities = welcome.capabilities.toSet())

            if (simulateNetworkDelayMs > 0) delay(simulateNetworkDelayMs)

            // Also send capabilities frame for backward compat
            _incomingMessages.emit(
                ServerMessage.Capabilities(
                    protocolVersion = "2",
                    capabilities = welcome.capabilities,
                    serverName = welcome.serverName,
                    serverVersion = welcome.serverVersion,
                    agentId = welcome.agentId
                )
            )
        }

        // Final ready
        if (capabilityMode == FakeCapabilityMode.V1_ONLY) {
            _connectionState.value = ConnectionState.ReadyLegacy(profile.host, profile.port, "Fake Study Agent (Mock Mode)", 5L)
            updateSnapshot(TransportStatus.OPEN, gen, serverName = "Fake Study Agent (Mock Mode)", protocolVersion = "1", capabilities = emptySet(), authenticated = true, latencyMs = 5L)
        } else {
            val caps = when (capabilityMode) {
                FakeCapabilityMode.FULL_V2 -> setOf(AgentCapability.DASHBOARD, AgentCapability.DECK_LIST, AgentCapability.STUDY_CONFIG, AgentCapability.HISTORY, AgentCapability.COMPONENT_HEALTH, AgentCapability.LEARNING_INSIGHTS, AgentCapability.AI_USAGE, AgentCapability.SESSION_PROGRESS)
                FakeCapabilityMode.PARTIAL_V2 -> setOf(AgentCapability.DASHBOARD, AgentCapability.DECK_LIST, AgentCapability.STUDY_CONFIG, AgentCapability.HISTORY)
                else -> emptySet()
            }
            _connectionState.value = ConnectionState.Ready(profile.host, profile.port, "Fake Study Agent (Mock Mode)", "2.4.0-fake", "2", 5L, caps, "fake-agent-${profile.id}", true)
            updateSnapshot(TransportStatus.OPEN, gen, serverName = "Fake Study Agent (Mock Mode)", serverVersion = "2.4.0-fake", protocolVersion = "2", capabilities = caps, authenticated = true, latencyMs = 5L)
        }
    }

    private fun updateSnapshot(transport: TransportStatus, gen: Long, serverName: String? = null, serverVersion: String? = null, protocolVersion: String? = null, capabilities: Set<String>? = null, authenticated: Boolean? = null, latencyMs: Long? = null, problem: ConnectionProblem? = null) {
        _connectionSnapshot.value = AgentConnectionSnapshot(
            phase = _connectionState.value,
            transport = transport,
            profile = profile,
            protocolVersion = protocolVersion ?: _connectionSnapshot.value.protocolVersion,
            serverName = serverName ?: _connectionSnapshot.value.serverName,
            serverVersion = serverVersion ?: _connectionSnapshot.value.serverVersion,
            agentId = _connectionSnapshot.value.agentId,
            capabilities = capabilities ?: _connectionSnapshot.value.capabilities,
            authenticated = authenticated ?: _connectionSnapshot.value.authenticated,
            latencyMs = latencyMs ?: _connectionSnapshot.value.latencyMs,
            lastMessageAgeMs = 100L,
            retry = null,
            problem = problem,
            connectionGeneration = gen
        )
    }

    private fun updateSnapshot(transport: TransportStatus, gen: Long) {
        updateSnapshot(transport, gen, null, null, null, null, null, null, null)
    }

    override suspend fun disconnect(reason: String) {
        AppLogger.i(tag, "Disconnecting Fake Agent: $reason")
        isConnected = false
        activeSessionId = null
        activeSessionDeck = null
        currentCardIndex = 0
        sessionRevision = 0L
        sessionPaused = false
        sessionElapsedSeconds = 0L
        ratingCounts.clear()
        _connectionState.value = ConnectionState.Disconnected
        _connectionSnapshot.value = AgentConnectionSnapshot.disconnected()
    }

    suspend fun simulateConnectionDrop() {
        _connectionState.value = ConnectionState.Disconnected
        _connectionSnapshot.value = _connectionSnapshot.value.copy(transport = TransportStatus.DISCONNECTED, phase = ConnectionState.Disconnected)
        isConnected = false
    }

    suspend fun simulateReconnect() {
        _connectionState.value = ConnectionState.Ready(profile?.host ?: "10.0.0.2", profile?.port ?: 8765, "Fake Study Agent", "2.4.0-fake", "2", 5L, emptySet(), null, true)
        isConnected = true
    }

    override suspend fun send(message: ClientMessage): Boolean {
        if (!isConnected) {
            AppLogger.w(tag, "Cannot send message while disconnected")
            return false
        }
        if (handledMessageIds.contains(message.messageId)) {
            AppLogger.i(tag, "Duplicate client messageId suppressed (idempotency) ${message.messageId} type=${message.type}")
            return true
        }
        handledMessageIds.add(message.messageId)
        if (handledMessageIds.size > 200) handledMessageIds.remove(handledMessageIds.first())
        AppLogger.i(tag, "FakeAgent received client message: ${message.type} id=${message.messageId}")
        scope.launch { handleClientMessageWithChaos(message) }
        return true
    }

    private suspend fun handleClientMessageWithChaos(message: ClientMessage) {
        if (!enableChaos) { handleClientMessage(message); return }
        if (chaosRandom.nextDouble() < chaosDropProb && message.type !in setOf("hello", "authenticate", "ping")) {
            AppLogger.w(tag, "Chaos: dropping message ${message.type} id=${message.messageId}"); return
        }
        val extraDelay = if (chaosRandom.nextDouble() < chaosDelayProb) chaosRandom.nextLong(chaosMaxExtraDelayMs) else 0L
        if (extraDelay > 0) delay(extraDelay)
        handleClientMessage(message)
        if (chaosRandom.nextDouble() < chaosDuplicateProb) {
            delay(chaosRandom.nextLong(50L, 150L))
            AppLogger.i(tag, "Chaos: duplicating message response for ${message.type}")
            handleClientMessage(message)
        }
    }

    private suspend fun handleClientMessage(message: ClientMessage) {
        if (simulateNetworkDelayMs > 0) delay(simulateNetworkDelayMs)
        when (message) {
            is ClientMessage.Hello -> {
                // Respond with welcome if not already sent (for test that sends hello manually)
                if (capabilityMode != FakeCapabilityMode.V1_ONLY) {
                    _incomingMessages.emit(
                        ServerMessage.Welcome(
                            protocolVersion = "2",
                            messageId = UUID.randomUUID().toString(),
                            inReplyTo = message.messageId,
                            serverName = "Fake Study Agent (Mock Mode)",
                            serverVersion = "2.4.0-fake",
                            selectedProtocol = "2",
                            capabilities = listOf(AgentCapability.DASHBOARD, AgentCapability.DECK_LIST, AgentCapability.STUDY_CONFIG),
                            authentication = ServerMessage.AuthInfo(required = false, authenticated = true),
                            agentId = "fake-agent-id"
                        )
                    )
                }
            }
            is ClientMessage.Authenticate -> AppLogger.i(tag, "Authenticated with token")
            is ClientMessage.Ping -> _incomingMessages.emit(ServerMessage.Pong(sessionId = activeSessionId, messageId = UUID.randomUUID().toString(), inReplyTo = message.messageId))
            is ClientMessage.StartSession -> {
                val newSessionId = UUID.randomUUID().toString().take(8)
                activeSessionId = newSessionId
                activeSessionDeck = message.deck ?: fakeConfig.activeDeck ?: "MCCQE::Cardiology"
                activeSessionMode = message.mode
                currentCardIndex = 0
                sessionRevision = 1L
                _incomingMessages.emit(ServerMessage.SessionStarted(sessionId = newSessionId, deck = activeSessionDeck, totalCards = sampleCards.size, sessionRevision = sessionRevision, messageId = UUID.randomUUID().toString(), inReplyTo = message.messageId))
                if (simulateNetworkDelayMs > 0) delay(simulateNetworkDelayMs)
                sendCurrentCard(inReplyTo = null)
            }
            is ClientMessage.SubmitAnswer -> {
                val card = sampleCards.getOrNull(currentCardIndex) ?: sampleCards.first()
                if (enableChaos && chaosRandom.nextDouble() < 0.2) delay(200L)
                val (score, feedback, correct, missing, suggested) = evaluateMockAnswer(card.id, message.text)
                sessionRevision++
                val eval = ServerMessage.EvaluationResponse(sessionId = activeSessionId, cardId = card.id, score = score, correctPoints = correct, missingPoints = missing, shortFeedback = feedback, suggestedRating = suggested, speak = true, reviewTurnId = message.reviewTurnId ?: "${activeSessionId}:${card.id}:${sessionRevision}", sessionRevision = sessionRevision, messageId = UUID.randomUUID().toString(), inReplyTo = message.messageId)
                emitWithOptionalDuplicate(eval)
            }
            is ClientMessage.RateCard -> {
                sessionRevision++
                recordRating(message.rating)
                val saved = ServerMessage.RatingSaved(sessionId = activeSessionId, cardId = message.cardId, rating = message.rating, nextInterval = when (message.rating) { Rating.AGAIN -> "10 minutes"; Rating.HARD -> "1 day"; Rating.GOOD -> "3 days"; Rating.EASY -> "7 days" }, reviewTurnId = message.reviewTurnId, sessionRevision = sessionRevision, messageId = UUID.randomUUID().toString(), inReplyTo = message.messageId)
                if (enableChaos && chaosRandom.nextDouble() < 0.2) delay(150L)
                _incomingMessages.emit(saved)
                if (simulateNetworkDelayMs > 0) delay(simulateNetworkDelayMs)
                currentCardIndex++; fakeReviewedToday++; sessionElapsedSeconds += 14; sessionRevision++
                if (currentCardIndex < sampleCards.size) { sendCurrentCard(); emitSessionProgress() } else {
                    _incomingMessages.emit(ServerMessage.SessionFinished(sessionId = activeSessionId, totalReviewed = sampleCards.size, summary = "Great job! You reviewed all ${sampleCards.size} cards in this deck.", details = finishedSessionSummary(), sessionRevision = sessionRevision))
                    activeSessionId = null
                }
            }
            is ClientMessage.RepeatQuestion -> sendCurrentCard(inReplyTo = message.messageId)
            is ClientMessage.RequestHint -> {
                val card = sampleCards.getOrNull(currentCardIndex) ?: sampleCards.first()
                val hint = when (card.id) { "card-001" -> "Consider hematoma volume over thirty milliliters and midline shift."; "card-002" -> "Think about PR segment depressions and widespread ST segment elevations."; "card-003" -> "Immediate needle thoracostomy decompression before chest tube."; "card-004" -> "Endothelial injury, stasis, and hypercoagulability."; else -> "Focus on atypical vs typical coverage with high-dose amoxicillin or doxycycline." }
                _incomingMessages.emit(ServerMessage.Hint(sessionId = activeSessionId, cardId = card.id, hintText = hint, speak = true, reviewTurnId = message.reviewTurnId, sessionRevision = sessionRevision, messageId = UUID.randomUUID().toString(), inReplyTo = message.messageId))
            }
            is ClientMessage.RequestExplanation -> {
                val card = sampleCards.getOrNull(currentCardIndex) ?: sampleCards.first()
                val explanation = when (card.id) { "card-001" -> "Evacuation is indicated for EDH volume > 30 cm3 regardless of GCS, or GCS < 9 with pupillary anisocoria or midline shift > 5 mm."; "card-002" -> "Pericarditis typically presents with widespread upward-concave ST segment elevation and PR segment depression (especially in II, aVF, V4-V6), with reciprocal PR elevation in aVR."; else -> "Detailed clinical explanation from Study PC Agent." }
                _incomingMessages.emit(ServerMessage.Explanation(sessionId = activeSessionId, cardId = card.id, explanationText = explanation, speak = true, reviewTurnId = message.reviewTurnId, sessionRevision = sessionRevision, messageId = UUID.randomUUID().toString(), inReplyTo = message.messageId))
            }
            is ClientMessage.RequestAnswer -> {
                val card = sampleCards.getOrNull(currentCardIndex) ?: sampleCards.first()
                _incomingMessages.emit(ServerMessage.Answer(sessionId = activeSessionId, cardId = card.id, answerText = "The full answer for ${card.id} is available in your notes.", speak = true, reviewTurnId = message.reviewTurnId, sessionRevision = sessionRevision, messageId = UUID.randomUUID().toString(), inReplyTo = message.messageId))
            }
            is ClientMessage.SkipCard -> {
                sessionRevision++; currentCardIndex++
                if (currentCardIndex < sampleCards.size) { sendCurrentCard(inReplyTo = message.messageId); emitSessionProgress() } else {
                    _incomingMessages.emit(ServerMessage.SessionFinished(sessionId = activeSessionId, totalReviewed = currentCardIndex, summary = "Deck completed.", details = finishedSessionSummary(), sessionRevision = sessionRevision, messageId = UUID.randomUUID().toString(), inReplyTo = message.messageId))
                    activeSessionId = null
                }
            }
            is ClientMessage.PauseSession -> { sessionRevision++; sessionPaused = true; _incomingMessages.emit(ServerMessage.SessionPaused(sessionId = activeSessionId, sessionRevision = sessionRevision, messageId = UUID.randomUUID().toString(), inReplyTo = message.messageId)) }
            is ClientMessage.ResumeSession -> { sessionRevision++; sessionPaused = false; _incomingMessages.emit(ServerMessage.SessionResumed(sessionId = activeSessionId, sessionRevision = sessionRevision, messageId = UUID.randomUUID().toString(), inReplyTo = message.messageId)) }
            is ClientMessage.EndSession -> { sessionRevision++; _incomingMessages.emit(ServerMessage.SessionFinished(sessionId = activeSessionId, totalReviewed = currentCardIndex, summary = "Session ended by user.", details = finishedSessionSummary(), sessionRevision = sessionRevision, messageId = UUID.randomUUID().toString(), inReplyTo = message.messageId)); activeSessionId = null }
            is ClientMessage.RequestSessionStatus -> {
                _incomingMessages.emit(ServerMessage.SessionStats(sessionId = activeSessionId, cardsStudied = currentCardIndex, recallRate = 85.0, remainingDue = sampleCards.size - currentCardIndex, sessionRevision = sessionRevision, messageId = UUID.randomUUID().toString(), inReplyTo = message.messageId))
                val currentCard = sampleCards.getOrNull(currentCardIndex)
                _incomingMessages.emit(ServerMessage.SessionSnapshot(sessionId = activeSessionId, isPaused = false, isFinished = currentCardIndex >= sampleCards.size, currentCardId = currentCard?.id, currentQuestion = currentCard?.question, remaining = currentCard?.remaining, reviewTurnId = currentCard?.let { "${activeSessionId}:${it.id}:${sessionRevision}" }, sessionRevision = sessionRevision, awaiting = if (currentCardIndex < sampleCards.size) "answer" else "none", remainingDue = sampleCards.size - currentCardIndex, cardsStudied = currentCardIndex, messageId = UUID.randomUUID().toString(), inReplyTo = message.messageId))
            }
            is ClientMessage.RequestSessionSnapshot -> {
                val currentCard = sampleCards.getOrNull(currentCardIndex)
                _incomingMessages.emit(ServerMessage.SessionSnapshot(sessionId = activeSessionId, isPaused = false, isFinished = currentCardIndex >= sampleCards.size, currentCardId = currentCard?.id, currentQuestion = currentCard?.question, remaining = currentCard?.remaining, reviewTurnId = currentCard?.let { "${activeSessionId}:${it.id}:${sessionRevision}" }, sessionRevision = sessionRevision, awaiting = if (currentCardIndex < sampleCards.size) "answer" else "none", messageId = UUID.randomUUID().toString(), inReplyTo = message.messageId))
            }
            is ClientMessage.RequestDashboard -> { if (simulateNetworkDelayMs > 0) delay(simulateNetworkDelayMs); _incomingMessages.emit(ServerMessage.DashboardSnapshotResponse(messageId = message.messageId, snapshot = buildDashboardSnapshot(), inReplyTo = message.messageId)) }
            is ClientMessage.RequestDecks -> _incomingMessages.emit(ServerMessage.DeckListResponse(messageId = message.messageId, decks = fakeDecks, inReplyTo = message.messageId))
            is ClientMessage.RequestComponentHealth -> _incomingMessages.emit(ServerMessage.ComponentHealthResponse(messageId = message.messageId, components = fakeHealth(), timestamp = currentFakeTimestamp(), inReplyTo = message.messageId))
            is ClientMessage.RequestStudyConfig -> _incomingMessages.emit(ServerMessage.StudyConfigResponse(messageId = message.messageId, config = fakeConfig, inReplyTo = message.messageId))
            is ClientMessage.UpdateStudyConfig -> {
                if (simulateNetworkDelayMs > 0) delay(simulateNetworkDelayMs)
                if (simulateConfigRejection) {
                    _incomingMessages.emit(ServerMessage.ErrorMessage(messageId = message.messageId, code = "config_rejected", message = "The Study Agent rejected this configuration (simulated).", inReplyTo = message.messageId))
                } else {
                    fakeConfig = message.config
                    _incomingMessages.emit(ServerMessage.StudyConfigUpdated(messageId = message.messageId, config = fakeConfig, inReplyTo = message.messageId))
                }
            }
            is ClientMessage.RequestHistory -> {
                val range = message.range
                val days = if (range == "today") fakeWeek().takeLast(1) else fakeWeek()
                _incomingMessages.emit(ServerMessage.StudyHistoryResponse(messageId = message.messageId, history = StudyHistoryPayload(range = range, days = days, ratingDistribution = fakeRatingDistribution()), inReplyTo = message.messageId))
            }
            is ClientMessage.RequestLearningInsights -> _incomingMessages.emit(ServerMessage.LearningInsightResponse(messageId = message.messageId, insights = listOf(fakeInsight()), inReplyTo = message.messageId))
            is ClientMessage.RequestAiUsage -> _incomingMessages.emit(ServerMessage.AiUsageResponse(messageId = message.messageId, usage = fakeAiUsage(message.range), inReplyTo = message.messageId))
        }
    }

    private fun currentFakeTimestamp(): String = java.time.Instant.now().toString()
    private val ratingCounts = mutableMapOf<Rating, Int>()
    private fun recordRating(rating: Rating) { ratingCounts[rating] = (ratingCounts[rating] ?: 0) + 1 }
    private fun fakeRatingDistribution(): RatingDistribution = RatingDistribution(again = 34 + (ratingCounts[Rating.AGAIN] ?: 0), hard = 61 + (ratingCounts[Rating.HARD] ?: 0), good = 268 + (ratingCounts[Rating.GOOD] ?: 0), easy = 64 + (ratingCounts[Rating.EASY] ?: 0))
    private fun emitSessionProgress() { scope.launch { _incomingMessages.emit(ServerMessage.SessionProgress(sessionId = activeSessionId, currentCardIndex = currentCardIndex, totalCards = sampleCards.size)); _incomingMessages.emit(ServerMessage.SessionStats(sessionId = activeSessionId, cardsStudied = currentCardIndex, recallRate = 86.0, remainingDue = sampleCards.size - currentCardIndex, sessionRevision = sessionRevision)) } }
    private fun activeSessionSummary(): SessionSummaryPayload? {
        val sessionId = activeSessionId ?: return null
        return SessionSummaryPayload(sessionId = sessionId, deck = activeSessionDeck, mode = activeSessionMode, cardsReviewed = currentCardIndex, totalCards = sampleCards.size, remainingCards = sampleCards.size - currentCardIndex, recallRate = 86.0, elapsedSeconds = sessionElapsedSeconds, avgSecondsPerCard = 14.0, isPaused = sessionPaused, ratingDistribution = RatingDistribution(again = ratingCounts[Rating.AGAIN] ?: 0, hard = ratingCounts[Rating.HARD] ?: 0, good = ratingCounts[Rating.GOOD] ?: 0, easy = ratingCounts[Rating.EASY] ?: 0))
    }
    private fun finishedSessionSummary(): SessionSummaryPayload = SessionSummaryPayload(sessionId = activeSessionId, deck = activeSessionDeck, mode = activeSessionMode, cardsReviewed = currentCardIndex, totalCards = sampleCards.size, recallRate = 86.0, elapsedSeconds = sessionElapsedSeconds.coerceAtLeast(currentCardIndex * 14L), avgSecondsPerCard = 14.0, ratingDistribution = RatingDistribution(again = ratingCounts[Rating.AGAIN] ?: 0, hard = ratingCounts[Rating.HARD] ?: 0, good = ratingCounts[Rating.GOOD] ?: 0, easy = ratingCounts[Rating.EASY] ?: 0), correctCount = (ratingCounts[Rating.GOOD] ?: 0) + (ratingCounts[Rating.EASY] ?: 0), weakTopics = listOf("Arrhythmias", "Valvular disease"), aiNote = "Solid session. Focus next on unstable arrhythmia management.")
    private fun buildDashboardSnapshot(): DashboardSnapshotPayload = DashboardSnapshotPayload(generatedAt = currentFakeTimestamp(), activeDeck = fakeDecks.firstOrNull { it.name == fakeConfig.activeDeck } ?: fakeDecks.first(), today = fakeToday.copy(cardsReviewed = fakeReviewedToday), currentSession = activeSessionSummary(), goal = fakeGoal(), recentPerformance = RecentPerformance(days = fakeWeek(), ratingDistribution = fakeRatingDistribution(), range = "7d"), recommendation = fakeRecommendation(), insight = fakeInsight(), aiUsage = fakeAiUsage("today"), componentHealth = ComponentHealth.fromEntries(fakeHealth(), updatedAt = currentFakeTimestamp()))
    private suspend fun emitWithOptionalDuplicate(msg: ServerMessage) { _incomingMessages.emit(msg); if (enableChaos && chaosRandom.nextDouble() < 0.1) { delay(50L); _incomingMessages.emit(msg) } }
    private suspend fun sendCurrentCard(inReplyTo: String? = null) {
        val card = sampleCards.getOrNull(currentCardIndex) ?: return
        sessionRevision++; messageSeq++
        val turnId = "${activeSessionId}:${card.id}:${sessionRevision}"
        _incomingMessages.emit(ServerMessage.Question(sessionId = activeSessionId, cardId = card.id, question = card.question, cardNumber = card.cardNumber, remaining = card.remaining, speak = true, reviewTurnId = turnId, sessionRevision = sessionRevision, sequence = messageSeq, messageId = UUID.randomUUID().toString(), inReplyTo = inReplyTo))
    }
    suspend fun injectStaleEvaluation(cardId: String, text: String = "Stale feedback") { _incomingMessages.emit(ServerMessage.EvaluationResponse(sessionId = activeSessionId, cardId = cardId, shortFeedback = text, score = 50, speak = false)) }
    suspend fun injectDuplicateQuestion() { sendCurrentCard(); delay(10L); sendCurrentCard() }
    private fun evaluateMockAnswer(cardId: String, answer: String): EvaluationTuple {
        val lower = answer.lowercase()
        return when (cardId) {
            "card-001" -> {
                val hasVol = lower.contains("30") || lower.contains("volume") || lower.contains("thirty"); val hasShift = lower.contains("midline") || lower.contains("5") || lower.contains("five"); val hasNeuro = lower.contains("deterioration") || lower.contains("gcs") || lower.contains("coma") || lower.contains("pupil")
                val correct = mutableListOf<String>(); val missing = mutableListOf<String>()
                if (hasVol) correct.add("Volume greater than 30 mL") else missing.add("Volume > 30 mL")
                if (hasShift) correct.add("Midline shift greater than 5 mm") else missing.add("Midline shift > 5 mm")
                if (hasNeuro) correct.add("Neurological deterioration or GCS < 9") else missing.add("Neurological deterioration")
                val score = (correct.size * 100) / 3; val feedback = if (missing.isEmpty()) "Excellent and complete answer!" else "Good answer. You missed ${missing.joinToString(", ")}."; val rating = if (score >= 90) Rating.GOOD else if (score >= 60) Rating.HARD else Rating.AGAIN
                EvaluationTuple(score, feedback, correct, missing, rating)
            }
            "card-002" -> EvaluationTuple(92, "Great answer! PR depression and diffuse ST elevation are key findings.", listOf("PR depression", "Diffuse ST elevation"), listOf("Reciprocal ST depression in aVR"), Rating.GOOD)
            else -> EvaluationTuple(88, "Well done, key points covered accurately.", listOf("First-line management steps"), emptyList(), Rating.GOOD)
        }
    }
    private data class EvaluationTuple(val score: Int, val feedback: String, val correct: List<String>, val missing: List<String>, val suggested: Rating)

    override fun testConnection(profile: ServerProfile): Flow<ConnectionTestResult> = flow {
        emit(ConnectionTestResult(ConnectionTestResult.TestStage.NETWORK, true, "Network available"))
        delay(100)
        emit(ConnectionTestResult(ConnectionTestResult.TestStage.DNS, true, "Resolved ${profile.host}"))
        delay(100)
        emit(ConnectionTestResult(ConnectionTestResult.TestStage.TRANSPORT, true, "WebSocket open", 20L))
        delay(100)
        if (capabilityMode == FakeCapabilityMode.V1_ONLY) {
            emit(ConnectionTestResult(ConnectionTestResult.TestStage.HANDSHAKE, true, "Legacy handshake OK"))
            emit(ConnectionTestResult(ConnectionTestResult.TestStage.PROTOCOL, true, "Protocol v1"))
        } else {
            emit(ConnectionTestResult(ConnectionTestResult.TestStage.HANDSHAKE, true, "Welcome received"))
            emit(ConnectionTestResult(ConnectionTestResult.TestStage.PROTOCOL, true, "Protocol v2"))
        }
        delay(100)
        if (simulateAuthFailure) {
            emit(ConnectionTestResult(ConnectionTestResult.TestStage.AUTHENTICATION, false, "Auth failed"))
        } else {
            emit(ConnectionTestResult(ConnectionTestResult.TestStage.AUTHENTICATION, true, "Authenticated"))
            emit(ConnectionTestResult(ConnectionTestResult.TestStage.ANKI, true, "Anki ready"))
            emit(ConnectionTestResult(ConnectionTestResult.TestStage.AI, true, "AI ready", 240L))
        }
    }
}
