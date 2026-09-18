package com.studyagent.client.core.network

import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.models.*
import com.studyagent.client.data.preferences.ProfileRepository
import com.studyagent.client.data.preferences.PreferencesDataStore
import com.studyagent.client.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AgentClient owns handshake, auth, protocol negotiation, request correlation, capabilities.
 * Architecture:
 * UI -> ConnectionRepository -> AgentClient -> RequestCoordinator -> AgentConnection -> WebSocket
 */
class AgentClient(
    private val connection: AgentConnection,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)
) {
    private val tag = "AgentClient"

    private val messageFactory = MessageFactory()
    private val requestCoordinator = RequestCoordinator(scope)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<ServerMessage>(replay = 0, extraBufferCapacity = 128)
    val incomingMessages: SharedFlow<ServerMessage> = _incomingMessages.asSharedFlow()

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private val _snapshot = MutableStateFlow(AgentConnectionSnapshot.disconnected())
    val snapshot: StateFlow<AgentConnectionSnapshot> = _snapshot.asStateFlow()

    private var currentProfile: ServerProfile? = null
    private var currentSessionId: String? = null

    init {
        // Forward connection state
        scope.launch {
            connection.connectionState.collect { state ->
                _connectionState.value = state
                // On ready, reconcile session
                if (state.isAgentReady) {
                    // Session recovery
                    currentSessionId?.let { sid ->
                        AppLogger.i(tag, "Agent ready, requesting session snapshot for recovery sid=$sid")
                        scope.launch {
                            requestSessionSnapshot()
                        }
                    }
                }
            }
        }

        // Handle incoming messages with correlation
        scope.launch {
            connection.incomingMessages.collect { msg ->
                requestCoordinator.onServerMessage(msg)
                _incomingMessages.emit(msg)
                mapToEvent(msg)?.let { _events.emit(it) }

                // Track session
                when (msg) {
                    is ServerMessage.SessionStarted -> currentSessionId = msg.sessionId
                    is ServerMessage.SessionFinished -> currentSessionId = null
                    else -> {}
                }
            }
        }

        // Forward snapshot if connection provides it
        scope.launch {
            try {
                connection.connectionSnapshot.collect { snap ->
                    _snapshot.value = snap
                }
            } catch (_: NotImplementedError) {
                // Fallback: build snapshot from state
                connection.connectionState.collect { state ->
                    _snapshot.value = AgentConnectionSnapshot(
                        phase = state,
                        transport = if (state.isTransportOpen) TransportStatus.OPEN else TransportStatus.DISCONNECTED,
                        profile = currentProfile,
                        protocolVersion = null,
                        serverName = null,
                        serverVersion = null,
                        agentId = null,
                        capabilities = emptySet(),
                        authenticated = null,
                        latencyMs = null,
                        lastMessageAgeMs = null,
                        retry = null,
                        problem = null
                    )
                }
            }
        }
    }

    suspend fun connect(profile: ServerProfile) {
        currentProfile = profile
        messageFactory.updateContext(ClientInfoProvider.createContext())
        connection.connect(profile)
    }

    suspend fun connectWithOverride(profile: ServerProfile) {
        currentProfile = profile
        messageFactory.updateContext(ClientInfoProvider.createContext())
        connection.connectWithOverride(profile)
    }

    suspend fun disconnect(reason: String = "User requested") {
        requestCoordinator.cancelAll()
        connection.disconnect(reason)
        _connectionState.value = ConnectionState.Disconnected
    }

    suspend fun send(message: ClientMessage): Boolean {
        return connection.send(message)
    }

    private fun mapToEvent(msg: ServerMessage): AgentEvent? = when (msg) {
        is ServerMessage.Question -> AgentEvent.Question(msg)
        is ServerMessage.EvaluationResponse -> AgentEvent.Evaluation(msg)
        is ServerMessage.RatingSaved -> AgentEvent.RatingSaved(msg)
        is ServerMessage.SessionProgress -> AgentEvent.SessionProgress(msg)
        is ServerMessage.SessionFinished -> AgentEvent.SessionFinished(msg)
        is ServerMessage.SessionSnapshot -> AgentEvent.SessionSnapshot(msg)
        is ServerMessage.ComponentHealthResponse -> AgentEvent.ComponentHealth(msg)
        is ServerMessage.StudyConfigUpdated -> AgentEvent.ConfigUpdated(msg)
        is ServerMessage.DashboardSnapshotResponse -> AgentEvent.Dashboard(msg)
        is ServerMessage.ErrorMessage -> AgentEvent.Error(msg)
        is ServerMessage.Unknown -> AgentEvent.Unknown(msg.rawType ?: "unknown")
        else -> null
    }

    // --- Typed API methods with correlation ---

    private suspend inline fun <reified T : ServerMessage> requestWithCorrelation(
        clientMessage: ClientMessage,
        crossinline isExpected: (ServerMessage) -> Boolean = { it is T }
    ): AgentApiResult<T> {
        if (!_connectionState.value.isAgentReady) {
            return AgentApiResult.Disconnected("Not ready: ${_connectionState.value.phaseName}")
        }

        val deferred = requestCoordinator.register(clientMessage.messageId, clientMessage.type)

        val sent = connection.send(clientMessage)
        if (!sent) {
            requestCoordinator.cancel(clientMessage.messageId)
            return AgentApiResult.Disconnected("Failed to send ${clientMessage.type}")
        }

        return try {
            val response = withTimeoutOrNull(requestCoordinator.timeoutFor(clientMessage.type)) {
                deferred.await()
            }

            if (response == null) {
                AgentApiResult.Timeout(clientMessage.type, requestCoordinator.timeoutFor(clientMessage.type), clientMessage.messageId)
            } else if (response is ServerMessage.ErrorMessage) {
                AgentApiResult.Rejected(
                    AgentApiError.fromCode(response.code, response.message, response.details).copy(
                        inReplyTo = response.inReplyTo
                    )
                )
            } else if (isExpected(response)) {
                @Suppress("UNCHECKED_CAST")
                AgentApiResult.Success(response as T, response.messageId, (response as? ServerMessage.SessionStarted)?.sessionRevision)
            } else {
                AgentApiResult.ProtocolFailure("Unexpected response type ${response.type} for ${clientMessage.type}")
            }
        } catch (e: RequestCoordinator.RequestTimeoutException) {
            AgentApiResult.Timeout(e.requestType, e.elapsedMs, e.messageId)
        } catch (e: Exception) {
            AgentApiResult.ProtocolFailure("Request failed: ${e.message}", e)
        }
    }

    suspend fun startSession(deck: String?, mode: String, config: SessionStartConfig?): AgentApiResult<ServerMessage.SessionStarted> {
        val msg = messageFactory.createStartSession(deck, mode, config)
        return requestWithCorrelation(msg)
    }

    suspend fun submitAnswer(cardId: String, text: String, reviewTurnId: String?, sessionRevision: Long?): AgentApiResult<ServerMessage.EvaluationResponse> {
        val msg = messageFactory.submitAnswer(cardId, text, reviewTurnId, sessionRevision)
        return requestWithCorrelation(msg)
    }

    suspend fun rateCard(cardId: String, rating: Rating, reviewTurnId: String?, sessionRevision: Long?): AgentApiResult<ServerMessage.RatingSaved> {
        val msg = messageFactory.rateCard(cardId, rating, reviewTurnId, sessionRevision)
        return requestWithCorrelation(msg)
    }

    suspend fun requestSessionSnapshot(): AgentApiResult<ServerMessage.SessionSnapshot> {
        val msg = messageFactory.requestSessionSnapshot()
        return requestWithCorrelation(msg)
    }

    suspend fun getDashboard(): AgentApiResult<ServerMessage.DashboardSnapshotResponse> {
        val msg = messageFactory.requestDashboard()
        return requestWithCorrelation(msg)
    }

    suspend fun getDecks(): AgentApiResult<ServerMessage.DeckListResponse> {
        val msg = messageFactory.requestDecks()
        return requestWithCorrelation(msg)
    }

    suspend fun getComponentHealth(): AgentApiResult<ServerMessage.ComponentHealthResponse> {
        val msg = messageFactory.requestComponentHealth()
        return requestWithCorrelation(msg)
    }

    suspend fun getStudyConfig(): AgentApiResult<ServerMessage.StudyConfigResponse> {
        val msg = messageFactory.requestStudyConfig()
        return requestWithCorrelation(msg)
    }

    suspend fun updateStudyConfig(config: StudyControlConfig): AgentApiResult<ServerMessage.StudyConfigUpdated> {
        val msg = messageFactory.updateStudyConfig(config)
        return requestWithCorrelation(msg)
    }

    fun getProtocolContext(): ProtocolContext = messageFactory.getContext()
    fun getMessageFactory(): MessageFactory = messageFactory
}
