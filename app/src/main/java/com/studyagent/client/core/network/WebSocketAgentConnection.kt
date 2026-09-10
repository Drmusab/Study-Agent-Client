package com.studyagent.client.core.network

import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.ServerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class WebSocketAgentConnection(
    private val dispatchers: DispatcherProvider,
    private val customOkHttpClient: OkHttpClient? = null
) : AgentConnection {

    private val tag = "WebSocketAgentConn"
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    private val client: OkHttpClient = customOkHttpClient ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var activeWebSocket: WebSocket? = null
    private var connectionProfile: ServerProfile? = null
    private var isExplicitlyDisconnected = false
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var lastPingTimestamp: Long = 0L

    private val reconnectController = ReconnectController()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<ServerMessage>(replay = 1, extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<ServerMessage> = _incomingMessages.asSharedFlow()

    override val currentProfile: ServerProfile?
        get() = connectionProfile

    override suspend fun connect(profile: ServerProfile) {
        isExplicitlyDisconnected = false
        connectionProfile = profile
        reconnectController.reset()
        initiateConnection(profile)
    }

    private suspend fun initiateConnection(profile: ServerProfile) = withContext(dispatchers.io) {
        val url = profile.toWebSocketUrl()
        AppLogger.i(tag, "Initiating connection to $url")
        _connectionState.value = ConnectionState.Connecting(profile.host, profile.port)

        try {
            val requestBuilder = Request.Builder().url(url)
            profile.authToken?.takeIf { it.isNotBlank() }?.let { token ->
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }
            val request = requestBuilder.build()

            activeWebSocket?.cancel()
            activeWebSocket = client.newWebSocket(request, createWebSocketListener(profile))
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to create WebSocket: ${e.message}", e)
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}", e)
            scheduleReconnect(profile, e.message)
        }
    }

    private fun createWebSocketListener(profile: ServerProfile): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLogger.i(tag, "WebSocket opened successfully to ${profile.host}:${profile.port}")
                reconnectController.reset()
                _connectionState.value = ConnectionState.Connected(
                    host = profile.host,
                    port = profile.port,
                    serverName = profile.name
                )

                // Send Hello message
                scope.launch {
                    val hello = ClientMessage.Hello()
                    send(hello)

                    // If profile has token, send authenticate frame as well
                    if (!profile.authToken.isNullOrBlank()) {
                        val auth = ClientMessage.Authenticate(token = profile.authToken)
                        send(auth)
                    }
                }

                startPingLoop()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                AppLogger.d(tag, "Received WS text message: $text")
                val message = ProtocolJson.decodeServerMessage(text)
                if (message != null) {
                    if (!ProtocolJson.isProtocolVersionCompatible(message.protocolVersion)) {
                        AppLogger.w(tag, "Protocol version mismatch: ${message.protocolVersion}")
                    }

                    if (message is ServerMessage.Pong) {
                        val latency = if (lastPingTimestamp > 0) System.currentTimeMillis() - lastPingTimestamp else null
                        val currentState = _connectionState.value
                        if (currentState is ConnectionState.Connected) {
                            _connectionState.value = currentState.copy(latencyMs = latency)
                        }
                    }

                    scope.launch {
                        _incomingMessages.emit(message)
                    }
                } else {
                    AppLogger.w(tag, "Unrecognized server message format: $text")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                AppLogger.i(tag, "WebSocket closing code=$code, reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLogger.i(tag, "WebSocket closed code=$code, reason=$reason")
                stopPingLoop()
                if (!isExplicitlyDisconnected) {
                    scheduleReconnect(profile, "Closed by server ($code: $reason)")
                } else {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AppLogger.e(tag, "WebSocket failure: ${t.message}, HTTP code: ${response?.code}", t)
                stopPingLoop()

                if (response?.code == 401 || response?.code == 403) {
                    _connectionState.value = ConnectionState.AuthenticationFailed("Server returned HTTP ${response.code}")
                    return
                }

                if (!isExplicitlyDisconnected) {
                    scheduleReconnect(profile, t.message ?: "Connection failure")
                } else {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }
    }

    private fun scheduleReconnect(profile: ServerProfile, reason: String?) {
        if (isExplicitlyDisconnected) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val maxAttempts = 15
            if (reconnectController.currentAttempt >= maxAttempts) {
                AppLogger.w(tag, "Max reconnect attempts ($maxAttempts) reached.")
                _connectionState.value = ConnectionState.ServerUnavailable("PC Agent is unreachable after $maxAttempts attempts")
                return@launch
            }

            val delayMs = reconnectController.getNextDelayMs()
            AppLogger.i(tag, "Scheduling reconnect attempt ${reconnectController.currentAttempt}/$maxAttempts in ${delayMs}ms...")
            _connectionState.value = ConnectionState.Reconnecting(
                attempt = reconnectController.currentAttempt,
                maxAttempts = maxAttempts,
                nextRetryInMs = delayMs,
                reason = reason
            )

            delay(delayMs)
            if (isActive && !isExplicitlyDisconnected) {
                initiateConnection(profile)
            }
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && _connectionState.value is ConnectionState.Connected) {
                delay(15000L)
                if (isActive && _connectionState.value is ConnectionState.Connected) {
                    lastPingTimestamp = System.currentTimeMillis()
                    send(ClientMessage.Ping())
                }
            }
        }
    }

    private fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
    }

    override suspend fun send(message: ClientMessage): Boolean = withContext(dispatchers.io) {
        val ws = activeWebSocket
        if (ws == null || _connectionState.value !is ConnectionState.Connected) {
            AppLogger.w(tag, "Cannot send message: WebSocket is not connected (type: ${message.type})")
            return@withContext false
        }

        val jsonString = ProtocolJson.encodeClientMessage(message)
        AppLogger.d(tag, "Sending message type=${message.type}: $jsonString")
        val sent = ws.send(jsonString)
        if (!sent) {
            AppLogger.e(tag, "Failed to send WebSocket message frame")
        }
        sent
    }

    override suspend fun disconnect(reason: String) {
        AppLogger.i(tag, "Explicit disconnect requested: $reason")
        isExplicitlyDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        stopPingLoop()

        try {
            activeWebSocket?.close(1000, reason)
        } catch (e: Exception) {
            AppLogger.w(tag, "Error closing WebSocket: ${e.message}")
        }
        activeWebSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }
}
