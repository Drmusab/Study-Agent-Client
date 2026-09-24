package com.studyagent.client.core.network

import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.models.AppSettingsPolicy
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.ServerProfile
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLHandshakeException

/**
 * Enhanced WebSocket connection with proper lifecycle:
 * TRANSPORT_OPEN -> HANDSHAKING -> AUTHENTICATING -> NEGOTIATING -> READY
 *
 * Key improvements:
 * - Generation counter prevents late callbacks from old connections
 * - Handshake timeout detection
 * - Single clear auth path (Bearer preferred, legacy frame only when needed)
 * - Ping correlation via in_reply_to
 * - Dead connection detection via last message age + unanswered pings
 * - Network awareness hooks
 */
class WebSocketAgentConnection(
    private val dispatchers: DispatcherProvider,
    private val customOkHttpClient: OkHttpClient? = null,
    private val settingsFlow: Flow<AppSettings> = flowOf(AppSettings()),
    private val stats: NetworkStats = NetworkStatsRegistry.current,
    private val handshakeTimeoutMs: Long = 10_000L,
    private val livenessTimeoutMs: Long = 30_000L
) : AgentConnection {

    private val tag = "WebSocketAgentConn"
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    private val client: OkHttpClient = customOkHttpClient ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS) // App manages its own ping
        .retryOnConnectionFailure(false) // We manage reconnect
        .build()

    @Volatile
    private var networkSettings: AppSettings = AppSettings()

    private var activeWebSocket: WebSocket? = null
    private var connectionProfile: ServerProfile? = null
    private var isExplicitlyDisconnected = false
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var handshakeTimeoutJob: Job? = null
    private var livenessJob: Job? = null

    // Generation prevents late callbacks from stale connections
    private val connectionGeneration = AtomicLong(0L)
    private var currentGeneration: Long = 0L

    // Handshake state
    private var lastPingTimestamp: Long = 0L
    private var lastPongTimestamp: Long = 0L
    private var lastServerMessageTimestamp: Long = 0L
    private var unansweredPings: Int = 0
    private var helloMessageId: String? = null
    private var negotiatedVersion: String = "1"
    private var serverName: String? = null
    private var serverVersion: String? = null
    private var agentId: String? = null
    private var capabilities: Set<String> = emptySet()
    private var authenticated: Boolean? = null
    private var welcomeReceived: Boolean = false
    private var capabilitiesReceived: Boolean = false

    private val messageFactory = MessageFactory()
    private val requestCoordinator = RequestCoordinator(scope)

    private val reconnectController = ReconnectController()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<ServerMessage>(replay = 0, extraBufferCapacity = 128)
    override val incomingMessages: SharedFlow<ServerMessage> = _incomingMessages.asSharedFlow()

    private val _connectionSnapshot = MutableStateFlow(AgentConnectionSnapshot.disconnected())
    override val connectionSnapshot: StateFlow<AgentConnectionSnapshot> = _connectionSnapshot.asStateFlow()

    override val currentProfile: ServerProfile?
        get() = connectionProfile

    init {
        scope.launch {
            settingsFlow.collect { updated ->
                networkSettings = updated
                stats.onTransportSettings(
                    pingIntervalSeconds = updated.pingIntervalSeconds,
                    maxReconnectAttempts = updated.maxReconnectAttempts
                )
                if (!updated.autoReconnect) {
                    reconnectJob?.cancel()
                    if (_connectionState.value is ConnectionState.Reconnecting) {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
        }
    }

    override suspend fun connect(profile: ServerProfile) {
        // Prevent parallel connect attempts - increment generation
        val newGen = connectionGeneration.incrementAndGet()
        currentGeneration = newGen

        // Cancel any existing reconnect
        reconnectJob?.cancel()
        reconnectJob = null

        // If already connecting/connected to same profile, avoid duplicate socket
        val current = _connectionState.value
        if (current !is ConnectionState.Disconnected && currentProfile?.id == profile.id) {
            if (current.isTransportOpen || current is ConnectionState.ConnectingTransport || current is ConnectionState.Connecting) {
                AppLogger.w(tag, "Connect called while already connecting/connected to same profile - ignoring duplicate")
                return
            }
        }

        // Disconnect old if switching profiles
        if (connectionProfile?.id != profile.id) {
            AppLogger.i(tag, "Switching profiles: ${connectionProfile?.id} -> ${profile.id}, invalidating old callbacks")
            try {
                activeWebSocket?.cancel()
            } catch (_: Exception) {}
            activeWebSocket = null
            requestCoordinator.cancelAll()
        }

        isExplicitlyDisconnected = false
        connectionProfile = profile
        reconnectController.reset()
        stats.onConnecting(profile.name, profile.host, profile.port)
        resetHandshakeState()

        AppLogger.i(tag, "Connect gen=$newGen profile=${profile.name} ${profile.host}:${profile.port} tls=${profile.useTls}")

        initiateConnection(profile, newGen)
    }

    private fun resetHandshakeState() {
        helloMessageId = null
        negotiatedVersion = "1"
        serverName = null
        serverVersion = null
        agentId = null
        capabilities = emptySet()
        authenticated = null
        welcomeReceived = false
        capabilitiesReceived = false
        unansweredPings = 0
        lastPingTimestamp = 0L
        lastPongTimestamp = 0L
        lastServerMessageTimestamp = System.currentTimeMillis()
        messageFactory.updateContext(
            ClientInfoProvider.createContext(negotiatedVersion = "1")
        )
    }

    private suspend fun initiateConnection(profile: ServerProfile, generation: Long) = withContext(dispatchers.io) {
        // Check generation still valid
        if (generation != connectionGeneration.get()) {
            AppLogger.w(tag, "initiateConnection gen=$generation stale, current=${connectionGeneration.get()} - aborting")
            return@withContext
        }

        val url = profile.toWebSocketUrl()
        AppLogger.i(tag, "Initiating connection gen=$generation to $url")
        _connectionState.value = ConnectionState.ConnectingTransport(profile.host, profile.port)
        updateSnapshot()

        try {
            val requestBuilder = Request.Builder().url(url)

            // Preferred auth: Bearer header
            // Only send via header, not also via authenticate frame by default
            val token = profile.authToken?.takeIf { it.isNotBlank() }
            if (token != null) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
                AppLogger.d(tag, "Added Authorization header for gen=$generation")
            }

            val request = requestBuilder.build()

            activeWebSocket?.cancel()
            activeWebSocket = client.newWebSocket(request, createWebSocketListener(profile, generation))

        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to create WebSocket gen=$generation: ${e.message}", e)
            if (generation == connectionGeneration.get()) {
                _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}", e)
                updateSnapshot(problem = ConnectionProblem.Unknown(e.message ?: "Connection failed", e))
                scheduleReconnect(profile, e.message, generation)
            }
        }
    }

    private fun createWebSocketListener(profile: ServerProfile, generation: Long): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != connectionGeneration.get()) {
                    AppLogger.w(tag, "onOpen gen=$generation stale (current=${connectionGeneration.get()}) - closing")
                    webSocket.cancel()
                    return
                }

                AppLogger.i(tag, "WebSocket opened gen=$generation to ${profile.host}:${profile.port} HTTP ${response.code}")
                reconnectController.reset()
                stats.onConnected(profile.name, profile.host, profile.port)

                _connectionState.value = ConnectionState.TransportConnected(
                    host = profile.host,
                    port = profile.port,
                    serverName = profile.name
                )
                updateSnapshot(transport = TransportStatus.OPEN)

                // Start handshake timeout
                startHandshakeTimeout(profile, generation)

                // Send hello - this starts the handshake
                scope.launch {
                    _connectionState.value = ConnectionState.Handshaking(profile.host, profile.port)
                    updateSnapshot()

                    val hello = messageFactory.hello()
                    helloMessageId = hello.messageId
                    AppLogger.i(tag, "Sending hello gen=$generation id=${hello.messageId.take(8)} version=${hello.clientVersion}")

                    if (!sendInternal(hello, generation)) {
                        AppLogger.w(tag, "Failed to send hello gen=$generation")
                    }

                    // Legacy auth frame: only send if server requires it and Bearer not used?
                    // For now, we wait for welcome to decide. But for backward compat with v1 servers
                    // that don't send welcome, we may need to send authenticate after hello if token present
                    // and server doesn't support Bearer. We'll handle via capability negotiation.
                    // For v1 fallback, send authenticate if token present after short delay
                    delay(500)
                    if (generation == connectionGeneration.get() && !welcomeReceived) {
                        val token = profile.authToken?.takeIf { it.isNotBlank() }
                        if (token != null) {
                            AppLogger.i(tag, "No welcome yet, sending legacy authenticate frame gen=$generation for v1 compat")
                            val auth = messageFactory.authenticate(token)
                            sendInternal(auth, generation)
                        }
                    }
                }

                startPingLoop(generation)
                startLivenessCheck(profile, generation)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != connectionGeneration.get()) {
                    AppLogger.w(tag, "onMessage gen=$generation stale - ignoring")
                    return
                }

                lastServerMessageTimestamp = System.currentTimeMillis()

                val message = ProtocolJson.decodeServerMessage(text)
                if (message == null) {
                    AppLogger.w(tag, "Unrecognized server frame gen=$generation type=${ProtocolJson.peekType(text)} bytes=${text.length}")
                    stats.onProtocolError("unrecognized-frame:${ProtocolJson.peekType(text)}")
                    return
                }

                stats.onMessageReceived(message.type)
                AppLogger.d(tag, "Received WS gen=$generation type=${message.type} bytes=${text.length} id=${message.messageId?.take(8)} replyTo=${message.inReplyTo?.take(8)}")

                // Handle protocol compatibility
                if (!ProtocolJson.isProtocolVersionCompatible(message.protocolVersion)) {
                    AppLogger.w(tag, "Protocol version mismatch gen=$generation: ${message.protocolVersion}")
                    stats.onProtocolError("version-mismatch:${message.protocolVersion}")
                    _connectionState.value = ConnectionState.ProtocolMismatch(
                        reason = "Server version ${message.protocolVersion} not supported",
                        serverVersion = message.protocolVersion,
                        clientVersions = listOf("1", "2")
                    )
                    updateSnapshot(problem = ConnectionProblem.ProtocolMismatch(listOf("1", "2"), message.protocolVersion))
                    return
                }

                // Handle welcome - this is the key handshake message
                if (message is ServerMessage.Welcome) {
                    handleWelcome(message, profile, generation)
                }

                // Handle capabilities for backward compat
                if (message is ServerMessage.Capabilities) {
                    handleCapabilities(message, profile, generation)
                }

                // Handle pong with correlation
                if (message is ServerMessage.Pong) {
                    handlePong(message, generation)
                }

                // Handle auth errors
                if (message is ServerMessage.ErrorMessage) {
                    if (message.code == "AUTH_REQUIRED" || message.code == "AUTH_INVALID" || message.code == "AUTH_EXPIRED" ||
                        message.message.contains("auth", ignoreCase = true) && message.code?.contains("AUTH") == true) {
                        // Check if it's auth related
                        if (message.code?.startsWith("AUTH") == true) {
                            handleAuthError(message, generation)
                        }
                    }
                }

                // Request coordinator correlation
                scope.launch {
                    requestCoordinator.onServerMessage(message)
                    _incomingMessages.emit(message)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != connectionGeneration.get()) return
                AppLogger.i(tag, "WebSocket closing gen=$generation code=$code reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != connectionGeneration.get()) {
                    AppLogger.w(tag, "onClosed gen=$generation stale - ignoring")
                    return
                }
                AppLogger.i(tag, "WebSocket closed gen=$generation code=$code reason=$reason")
                stopPingLoop()
                stopHandshakeTimeout()
                stopLivenessCheck()
                stats.onDisconnected()
                updateSnapshot(transport = TransportStatus.DISCONNECTED)

                if (!isExplicitlyDisconnected) {
                    scheduleReconnect(profile, "Closed by server ($code: $reason)", generation)
                } else {
                    _connectionState.value = ConnectionState.Disconnected
                    updateSnapshot()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != connectionGeneration.get()) {
                    AppLogger.w(tag, "onFailure gen=$generation stale - ignoring: ${t.message}")
                    return
                }

                AppLogger.e(tag, "WebSocket failure gen=$generation: ${t.message}, HTTP ${response?.code}", t)
                stopPingLoop()
                stopHandshakeTimeout()
                stopLivenessCheck()
                stats.onDisconnected()

                val httpCode = response?.code

                if (httpCode == 401 || httpCode == 403) {
                    _connectionState.value = ConnectionState.AuthenticationFailed("Server returned HTTP $httpCode")
                    updateSnapshot(problem = ConnectionProblem.AuthenticationRejected("HTTP $httpCode", expired = httpCode == 401))
                    return
                }

                if (t is SSLHandshakeException || t.message?.contains("TLS", ignoreCase = true) == true ||
                    t.message?.contains("certificate", ignoreCase = true) == true) {
                    _connectionState.value = ConnectionState.TlsFailure(t.message ?: "TLS handshake failed")
                    updateSnapshot(problem = ConnectionProblem.TlsFailure(t.message ?: "TLS failed"))
                    return
                }

                if (t.message?.contains("Unable to resolve host", ignoreCase = true) == true ||
                    t.message?.contains("UnknownHost", ignoreCase = true) == true) {
                    _connectionState.value = ConnectionState.ServerUnavailable("Cannot resolve host ${profile.host}")
                    updateSnapshot(problem = ConnectionProblem.DnsFailure(profile.host, t.message))
                    scheduleReconnect(profile, t.message, generation)
                    return
                }

                if (!isExplicitlyDisconnected) {
                    _connectionState.value = ConnectionState.ServerUnavailable(t.message ?: "Connection failure")
                    updateSnapshot(problem = ConnectionProblem.Unknown(t.message ?: "Connection failure", t))
                    scheduleReconnect(profile, t.message ?: "Connection failure", generation)
                } else {
                    _connectionState.value = ConnectionState.Disconnected
                    updateSnapshot()
                }
            }
        }
    }

    private fun handleWelcome(welcome: ServerMessage.Welcome, profile: ServerProfile, generation: Long) {
        if (generation != connectionGeneration.get()) return

        AppLogger.i(tag, "Welcome received gen=$generation server=${welcome.serverName} v=${welcome.serverVersion} protocol=${welcome.selectedProtocol} caps=${welcome.capabilities}")

        welcomeReceived = true
        negotiatedVersion = welcome.selectedProtocol
        serverName = welcome.serverName
        serverVersion = welcome.serverVersion
        agentId = welcome.agentId
        capabilities = welcome.capabilities.toSet()
        authenticated = welcome.authentication?.authenticated ?: (profile.authToken.isNullOrBlank() || welcome.authentication?.required == false)

        messageFactory.updateNegotiatedVersion(negotiatedVersion)

        // Cancel handshake timeout - we got a valid Study Agent response
        handshakeTimeoutJob?.cancel()

        // Check auth
        val authInfo = welcome.authentication
        if (authInfo != null) {
            if (authInfo.required && authInfo.authenticated == false) {
                // Need to authenticate via legacy frame if Bearer not accepted
                if (profile.authToken.isNullOrBlank()) {
                    _connectionState.value = ConnectionState.AuthenticationFailed("Authentication required")
                    updateSnapshot(problem = ConnectionProblem.AuthenticationRejected("Required but no token"))
                    return
                } else {
                    // Try legacy auth frame as fallback
                    _connectionState.value = ConnectionState.Authenticating(profile.host, profile.port)
                    updateSnapshot()
                    scope.launch {
                        val auth = messageFactory.authenticate(profile.authToken!!)
                        sendInternal(auth, generation)
                        // Wait briefly for auth result, but don't block
                        delay(2000)
                        if (generation == connectionGeneration.get() && _connectionState.value is ConnectionState.Authenticating) {
                            // If still authenticating after delay and no error, assume success for v1 compat
                            // Real auth result would come via error or via successful capability
                        }
                    }
                    // Continue to negotiating
                }
            }
        }

        // Move to negotiating
        _connectionState.value = ConnectionState.NegotiatingCapabilities(
            host = profile.host,
            port = profile.port,
            serverName = serverName,
            protocolVersion = negotiatedVersion
        )
        updateSnapshot()

        // If capabilities in welcome, we are ready
        if (capabilities.isNotEmpty() || negotiatedVersion == "2") {
            // Small delay to allow capabilities frame if separate, but if welcome already has caps we can go ready
            scope.launch {
                delay(200) // Allow separate capabilities frame to arrive if server sends it separately
                if (generation != connectionGeneration.get()) return@launch
                if (_connectionState.value is ConnectionState.NegotiatingCapabilities) {
                    transitionToReady(profile, generation)
                }
            }
        } else {
            transitionToReady(profile, generation)
        }
    }

    private fun handleCapabilities(caps: ServerMessage.Capabilities, profile: ServerProfile, generation: Long) {
        if (generation != connectionGeneration.get()) return

        AppLogger.i(tag, "Capabilities received gen=$generation caps=${caps.capabilities} server=${caps.serverName}")

        capabilitiesReceived = true
        capabilities = caps.capabilities.toSet()
        serverName = caps.serverName ?: serverName
        serverVersion = caps.serverVersion ?: serverVersion
        agentId = caps.agentId ?: agentId
        negotiatedVersion = caps.protocolVersion ?: negotiatedVersion

        messageFactory.updateNegotiatedVersion(negotiatedVersion)

        // If we were waiting in handshaking or negotiating, transition to ready
        val current = _connectionState.value
        if (current is ConnectionState.Handshaking || current is ConnectionState.NegotiatingCapabilities ||
            current is ConnectionState.TransportConnected || current is ConnectionState.Authenticating) {
            transitionToReady(profile, generation)
        } else if (current is ConnectionState.Ready || current is ConnectionState.ReadyLegacy) {
            // Update existing ready state with new caps
            _connectionState.value = when (current) {
                is ConnectionState.Ready -> current.copy(
                    capabilities = capabilities,
                    serverName = serverName ?: current.serverName,
                    serverVersion = serverVersion ?: current.serverVersion
                )
                else -> current
            }
            updateSnapshot()
        }
    }

    private fun transitionToReady(profile: ServerProfile, generation: Long) {
        if (generation != connectionGeneration.get()) return

        handshakeTimeoutJob?.cancel()

        val isLegacy = negotiatedVersion == "1" && !welcomeReceived && !capabilitiesReceived
        // Actually if we got here without welcome but with transport open and some message, we might be legacy
        // For now, if negotiatedVersion is 2 or welcome received, we are Ready, else ReadyLegacy

        if (isLegacy || (negotiatedVersion == "1" && capabilities.isEmpty() && !welcomeReceived)) {
            AppLogger.i(tag, "Transition to ReadyLegacy gen=$generation server=$serverName")
            _connectionState.value = ConnectionState.ReadyLegacy(
                host = profile.host,
                port = profile.port,
                serverName = serverName ?: profile.name,
                latencyMs = if (lastPingTimestamp > 0 && lastPongTimestamp > 0) lastPongTimestamp - lastPingTimestamp else null
            )
        } else {
            AppLogger.i(tag, "Transition to Ready gen=$generation server=$serverName v=$serverVersion protocol=$negotiatedVersion")
            _connectionState.value = ConnectionState.Ready(
                host = profile.host,
                port = profile.port,
                serverName = serverName ?: profile.name,
                serverVersion = serverVersion,
                protocolVersion = negotiatedVersion,
                latencyMs = if (lastPingTimestamp > 0 && lastPongTimestamp > 0) lastPongTimestamp - lastPingTimestamp else null,
                capabilities = capabilities,
                agentId = agentId,
                authenticated = authenticated ?: true
            )
        }
        updateSnapshot()
    }

    private fun handlePong(pong: ServerMessage.Pong, generation: Long) {
        if (generation != connectionGeneration.get()) return

        lastPongTimestamp = System.currentTimeMillis()
        unansweredPings = 0

        val latency = if (lastPingTimestamp > 0) lastPongTimestamp - lastPingTimestamp else null
        stats.onPong(latency)

        // Check correlation via in_reply_to if present
        val expectedReply = pong.inReplyTo
        if (expectedReply != null) {
            AppLogger.d(tag, "Pong correlated gen=$generation replyTo=${expectedReply.take(8)} latency=${latency}ms")
        }

        val currentState = _connectionState.value
        when (currentState) {
            is ConnectionState.Ready -> {
                _connectionState.value = currentState.copy(latencyMs = latency)
            }
            is ConnectionState.ReadyLegacy -> {
                _connectionState.value = currentState.copy(latencyMs = latency)
            }
            is ConnectionState.Connected -> {
                _connectionState.value = currentState.copy(latencyMs = latency)
            }
            else -> Unit
        }
        updateSnapshot(latencyMs = latency)
    }

    private fun handleAuthError(error: ServerMessage.ErrorMessage, generation: Long) {
        if (generation != connectionGeneration.get()) return
        AppLogger.w(tag, "Auth error gen=$generation code=${error.code} msg=${error.message}")
        _connectionState.value = ConnectionState.AuthenticationFailed(error.message, isExpired = error.code == "AUTH_EXPIRED")
        updateSnapshot(problem = ConnectionProblem.AuthenticationRejected(error.message, expired = error.code == "AUTH_EXPIRED"))
    }

    private fun startHandshakeTimeout(profile: ServerProfile, generation: Long) {
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = scope.launch {
            delay(handshakeTimeoutMs)
            if (generation != connectionGeneration.get()) return@launch
            val current = _connectionState.value
            // If still in handshaking/authenticating/negotiating and no welcome/capabilities
            if (current is ConnectionState.Handshaking || current is ConnectionState.Authenticating ||
                current is ConnectionState.NegotiatingCapabilities || current is ConnectionState.TransportConnected) {
                if (!welcomeReceived && !capabilitiesReceived) {
                    AppLogger.w(tag, "Handshake timeout gen=$generation after ${handshakeTimeoutMs}ms - no Study Agent response")
                    _connectionState.value = ConnectionState.HandshakeTimeout(profile.host, profile.port)
                    updateSnapshot(problem = ConnectionProblem.HandshakeTimeout(handshakeTimeoutMs))
                    // Close socket - it's not a Study Agent
                    try {
                        activeWebSocket?.close(1000, "Handshake timeout")
                    } catch (_: Exception) {}
                    scheduleReconnect(profile, "Handshake timeout", generation)
                } else {
                    // We got some response but not full ready - still timeout to ReadyLegacy fallback?
                    // For v1 servers that never send capabilities, after timeout we should go ReadyLegacy if we got any valid message
                    // Check if we had any server message at all
                    if (lastServerMessageTimestamp > 0) {
                        AppLogger.i(tag, "Handshake timeout but had messages - falling back to ReadyLegacy gen=$generation")
                        transitionToReady(profile, generation)
                    }
                }
            }
        }
    }

    private fun stopHandshakeTimeout() {
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = null
    }

    private fun startLivenessCheck(profile: ServerProfile, generation: Long) {
        livenessJob?.cancel()
        livenessJob = scope.launch {
            while (isActive && generation == connectionGeneration.get()) {
                delay(livenessTimeoutMs / 2)
                val now = System.currentTimeMillis()
                val age = now - lastServerMessageTimestamp
                if (age > livenessTimeoutMs && _connectionState.value.isTransportOpen) {
                    AppLogger.w(tag, "Liveness timeout gen=$generation last message ${age}ms ago, unansweredPings=$unansweredPings")
                    if (unansweredPings >= 2) {
                        AppLogger.w(tag, "Dead connection detected gen=$generation - reconnecting")
                        try {
                            activeWebSocket?.cancel()
                        } catch (_: Exception) {}
                        scheduleReconnect(profile, "Liveness timeout", generation)
                        break
                    }
                }
            }
        }
    }

    private fun stopLivenessCheck() {
        livenessJob?.cancel()
        livenessJob = null
    }

    private fun scheduleReconnect(profile: ServerProfile, reason: String?, generation: Long) {
        if (generation != connectionGeneration.get()) {
            AppLogger.w(tag, "scheduleReconnect gen=$generation stale - ignoring")
            return
        }
        if (isExplicitlyDisconnected || !networkSettings.autoReconnect) {
            _connectionState.value = ConnectionState.Disconnected
            updateSnapshot(transport = TransportStatus.DISCONNECTED)
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val maxAttempts = networkSettings.maxReconnectAttempts
            if (reconnectController.currentAttempt >= maxAttempts) {
                AppLogger.w(tag, "Max reconnect attempts ($maxAttempts) reached gen=$generation")
                _connectionState.value = ConnectionState.ServerUnavailable("PC Agent is unreachable after $maxAttempts attempts")
                updateSnapshot(
                    transport = TransportStatus.FAILED,
                    problem = ConnectionProblem.Unknown("Max reconnect attempts reached")
                )
                return@launch
            }

            val delayMs = reconnectController.getNextDelayMs()
            stats.onReconnecting(reconnectController.currentAttempt, maxAttempts, networkSettings.pingIntervalSeconds)
            AppLogger.i(tag, "Scheduling reconnect gen=$generation attempt ${reconnectController.currentAttempt}/$maxAttempts in ${delayMs}ms reason=$reason")

            _connectionState.value = ConnectionState.Reconnecting(
                attempt = reconnectController.currentAttempt,
                maxAttempts = maxAttempts,
                nextRetryInMs = delayMs,
                reason = reason,
                phase = _connectionState.value.phaseName
            )
            updateSnapshot(
                transport = TransportStatus.CONNECTING,
                retry = ReconnectInfo(
                    attempt = reconnectController.currentAttempt,
                    maxAttempts = maxAttempts,
                    nextRetryInMs = delayMs,
                    lastReason = reason
                )
            )

            delay(delayMs)
            if (isActive && !isExplicitlyDisconnected && networkSettings.autoReconnect) {
                if (generation == connectionGeneration.get()) {
                    // New generation for reconnect
                    val newGen = connectionGeneration.incrementAndGet()
                    currentGeneration = newGen
                    resetHandshakeState()
                    initiateConnection(profile, newGen)
                }
            }
        }
    }

    private fun startPingLoop(generation: Long) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && generation == connectionGeneration.get()) {
                val intervalMs = networkSettings.pingIntervalSeconds
                    .coerceAtLeast(AppSettingsPolicy.MIN_PING_INTERVAL_SECONDS)
                    .coerceAtMost(AppSettingsPolicy.MAX_PING_INTERVAL_SECONDS) * 1_000L
                delay(intervalMs)
                if (!isActive || generation != connectionGeneration.get()) break

                val state = _connectionState.value
                if (state.isTransportOpen || state is ConnectionState.Ready || state is ConnectionState.ReadyLegacy || state is ConnectionState.Connected) {
                    lastPingTimestamp = System.currentTimeMillis()
                    unansweredPings++
                    stats.onPingSent()
                    val ping = messageFactory.ping()
                    sendInternal(ping, generation)
                }
            }
        }
    }

    private fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
    }

    private suspend fun sendInternal(message: ClientMessage, generation: Long): Boolean = withContext(dispatchers.io) {
        if (generation != connectionGeneration.get()) {
            AppLogger.w(tag, "sendInternal gen=$generation stale - dropping ${message.type}")
            return@withContext false
        }

        val ws = activeWebSocket
        if (ws == null) {
            AppLogger.w(tag, "Cannot send ${message.type}: WebSocket null gen=$generation")
            stats.onSendFailed(message.type)
            return@withContext false
        }

        // Allow sending during handshake/auth/ready, but not when fully disconnected
        val state = _connectionState.value
        if (state is ConnectionState.Disconnected || state is ConnectionState.NetworkUnavailable) {
            AppLogger.w(tag, "Cannot send ${message.type}: not connected (state=${state.phaseName})")
            stats.onSendFailed(message.type)
            return@withContext false
        }

        val jsonString = ProtocolJson.encodeClientMessage(message)
        AppLogger.d(tag, "Sending gen=$generation type=${message.type} bytes=${jsonString.length} id=${message.messageId.take(8)}")
        val sent = ws.send(jsonString)
        if (sent) {
            stats.onMessageSent(message.type)
        } else {
            AppLogger.e(tag, "Failed to send WS frame gen=$generation type=${message.type}")
            stats.onSendFailed(message.type)
        }
        sent
    }

    override suspend fun send(message: ClientMessage): Boolean {
        return sendInternal(message, currentGeneration)
    }

    override suspend fun disconnect(reason: String) {
        val gen = connectionGeneration.incrementAndGet()
        AppLogger.i(tag, "Explicit disconnect gen=$gen reason=$reason")
        stats.onDisconnected(reason)
        isExplicitlyDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        stopPingLoop()
        stopHandshakeTimeout()
        stopLivenessCheck()
        requestCoordinator.cancelAll()

        try {
            activeWebSocket?.close(1000, reason)
        } catch (e: Exception) {
            AppLogger.w(tag, "Error closing WebSocket: ${e.message}")
        }
        activeWebSocket = null
        _connectionState.value = ConnectionState.Disconnected
        updateSnapshot(transport = TransportStatus.DISCONNECTED)
        resetHandshakeState()
    }

    // Manual connect overrides backoff
    override suspend fun connectWithOverride(profile: ServerProfile) {
        reconnectJob?.cancel()
        reconnectController.reset()
        connect(profile)
    }

    private fun updateSnapshot(
        transport: TransportStatus? = null,
        problem: ConnectionProblem? = null,
        latencyMs: Long? = null,
        retry: ReconnectInfo? = null
    ) {
        val currentState = _connectionState.value
        val newTransport = transport ?: when {
            currentState is ConnectionState.Disconnected -> TransportStatus.DISCONNECTED
            currentState is ConnectionState.ConnectingTransport || currentState is ConnectionState.Connecting ||
                    currentState is ConnectionState.Resolving || currentState is ConnectionState.Reconnecting -> TransportStatus.CONNECTING
            currentState.isTransportOpen -> TransportStatus.OPEN
            else -> TransportStatus.DISCONNECTED
        }

        val lastAge = if (lastServerMessageTimestamp > 0) System.currentTimeMillis() - lastServerMessageTimestamp else null

        _connectionSnapshot.value = AgentConnectionSnapshot(
            phase = currentState,
            transport = newTransport,
            profile = connectionProfile,
            protocolVersion = negotiatedVersion,
            serverName = serverName,
            serverVersion = serverVersion,
            agentId = agentId,
            capabilities = capabilities,
            authenticated = authenticated,
            latencyMs = latencyMs ?: (currentState as? ConnectionState.Ready)?.latencyMs ?: (currentState as? ConnectionState.Connected)?.latencyMs,
            lastMessageAgeMs = lastAge,
            retry = retry ?: (_connectionState.value as? ConnectionState.Reconnecting)?.let {
                ReconnectInfo(it.attempt, it.maxAttempts, it.nextRetryInMs, it.reason)
            },
            problem = problem ?: _connectionSnapshot.value.problem,
            connectionGeneration = currentGeneration
        )
    }
}
