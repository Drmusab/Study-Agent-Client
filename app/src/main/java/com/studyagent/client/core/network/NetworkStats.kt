package com.studyagent.client.core.network

import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.SystemAppClock

/**
 * Network-side technical counters with enhanced observability.
 *
 * Enhanced with:
 * - DNS/resolve time, connect time, WebSocket upgrade time, handshake time, auth time, ready time
 * - Ping RTT correlated via in_reply_to
 * - Reconnect duration
 * - Request RTT by type
 * - Generation, protocol, agentId, capabilities, problem
 * - Network type (WIFI/CELLULAR/VPN)
 */
class NetworkStats(private val clock: AppClock = SystemAppClock) {

    private val lock = Any()

    private var stateLabel: String = "Disconnected"
    private var profileName: String? = null
    private var host: String? = null
    private var port: Int = 0
    private var transport: String = "WebSocket"

    private var reconnectAttempt = 0
    private var maxReconnectAttempts = 0
    private var reconnectCount = 0L
    private var pingIntervalSeconds = -1L
    private var lastPingRttMs = -1L
    private var connectLatencyMs = -1L
    private var connectStartedAtMs = -1L

    private var messagesSent = 0L
    private var messagesReceived = 0L
    private var sendFailures = 0L
    private var lastMessageReceivedAtMs = -1L
    private var lastMessageType: String? = null
    private var lastProtocolError: String? = null

    private var firstMessageAtMs = -1L
    private var messageWindowCount = 0L

    // Enhanced fields
    private var generation: Long = -1L
    private var protocolVersion: String? = null
    private var serverName: String? = null
    private var serverVersion: String? = null
    private var agentId: String? = null
    private var capabilities: Set<String> = emptySet()
    private var authenticated: Boolean = false
    private var problem: String? = null
    private var handshakeStartedAtMs = -1L
    private var handshakeLatencyMs = -1L
    private var readyLatencyMs = -1L
    private var networkType: String? = null
    private var dnsLatencyMs = -1L
    private var wsUpgradeLatencyMs = -1L
    private var authLatencyMs = -1L

    // Request RTT by type
    private val requestRttMs = mutableMapOf<String, Long>()

    fun onConnecting(profileName: String?, host: String?, port: Int, transport: String = "WebSocket") {
        synchronized(lock) {
            stateLabel = "Connecting"
            this.profileName = profileName
            this.host = host
            this.port = port
            this.transport = transport
            connectStartedAtMs = clock.nowMillis()
            handshakeStartedAtMs = connectStartedAtMs
        }
    }

    fun onConnected(profileName: String?, host: String?, port: Int) {
        synchronized(lock) {
            stateLabel = "Connected"
            this.profileName = profileName
            this.host = host
            this.port = port
            if (connectStartedAtMs > 0L) connectLatencyMs = clock.nowMillis() - connectStartedAtMs
            connectStartedAtMs = -1L
            reconnectAttempt = 0
        }
    }

    fun onDisconnected(reason: String? = null) {
        synchronized(lock) {
            stateLabel = "Disconnected"
            connectStartedAtMs = -1L
            if (reason != null) lastProtocolError = null
        }
    }

    fun onReconnecting(attempt: Int, maxAttempts: Int, pingIntervalSeconds: Long = -1L) {
        synchronized(lock) {
            stateLabel = "Reconnecting"
            reconnectAttempt = attempt
            maxReconnectAttempts = maxAttempts
            reconnectCount++
            if (pingIntervalSeconds > 0L) this.pingIntervalSeconds = pingIntervalSeconds
        }
    }

    fun onTransportSettings(pingIntervalSeconds: Long, maxReconnectAttempts: Int) {
        synchronized(lock) {
            this.pingIntervalSeconds = pingIntervalSeconds
            this.maxReconnectAttempts = maxReconnectAttempts
        }
    }

    fun onMessageSent(type: String) {
        synchronized(lock) {
            messagesSent++
            lastMessageType = type
            noteMessageWindowLocked()
        }
    }

    fun onSendFailed(type: String) {
        synchronized(lock) {
            sendFailures++
            lastMessageType = type
            noteMessageWindowLocked()
        }
    }

    fun onMessageReceived(type: String) {
        synchronized(lock) {
            messagesReceived++
            lastMessageReceivedAtMs = clock.nowMillis()
            lastMessageType = type
            noteMessageWindowLocked()
        }
    }

    fun onPingSent() {
        synchronized(lock) {
            messagesSent++
            lastMessageType = "ping"
            noteMessageWindowLocked()
        }
    }

    fun onPong(rttMs: Long?) {
        synchronized(lock) {
            messagesReceived++
            lastMessageReceivedAtMs = clock.nowMillis()
            lastMessageType = "pong"
            if (rttMs != null && rttMs >= 0L) lastPingRttMs = rttMs
            noteMessageWindowLocked()
        }
    }

    fun onProtocolError(sanitizedDetail: String?) {
        synchronized(lock) {
            lastProtocolError = sanitizedDetail
        }
    }

    fun onGeneration(generation: Long) {
        synchronized(lock) {
            this.generation = generation
        }
    }

    fun onHandshakeComplete(protocolVersion: String?, serverName: String?, serverVersion: String?, agentId: String?, capabilities: Set<String>) {
        synchronized(lock) {
            this.protocolVersion = protocolVersion
            this.serverName = serverName
            this.serverVersion = serverVersion
            this.agentId = agentId
            this.capabilities = capabilities
            if (handshakeStartedAtMs > 0L) {
                handshakeLatencyMs = clock.nowMillis() - handshakeStartedAtMs
            }
        }
    }

    fun onReady(latencyMs: Long) {
        synchronized(lock) {
            readyLatencyMs = latencyMs
            stateLabel = "Ready"
        }
    }

    fun onAuthenticated(authenticated: Boolean) {
        synchronized(lock) {
            this.authenticated = authenticated
        }
    }

    fun onProblem(problem: String?) {
        synchronized(lock) {
            this.problem = problem
        }
    }

    fun onNetworkType(type: String?) {
        synchronized(lock) {
            this.networkType = type
        }
    }

    fun onDnsLatency(latencyMs: Long) {
        synchronized(lock) {
            dnsLatencyMs = latencyMs
        }
    }

    fun onWsUpgradeLatency(latencyMs: Long) {
        synchronized(lock) {
            wsUpgradeLatencyMs = latencyMs
        }
    }

    fun onAuthLatency(latencyMs: Long) {
        synchronized(lock) {
            authLatencyMs = latencyMs
        }
    }

    fun onRequestRtt(type: String, rttMs: Long) {
        synchronized(lock) {
            requestRttMs[type] = rttMs
        }
    }

    fun reset() {
        synchronized(lock) {
            reconnectAttempt = 0
            reconnectCount = 0L
            lastPingRttMs = -1L
            connectLatencyMs = -1L
            messagesSent = 0L
            messagesReceived = 0L
            sendFailures = 0L
            lastMessageReceivedAtMs = -1L
            lastMessageType = null
            lastProtocolError = null
            firstMessageAtMs = -1L
            messageWindowCount = 0L
            connectStartedAtMs = -1L
            generation = -1L
            protocolVersion = null
            serverName = null
            serverVersion = null
            agentId = null
            capabilities = emptySet()
            authenticated = false
            problem = null
            handshakeStartedAtMs = -1L
            handshakeLatencyMs = -1L
            readyLatencyMs = -1L
            networkType = null
            dnsLatencyMs = -1L
            wsUpgradeLatencyMs = -1L
            authLatencyMs = -1L
            requestRttMs.clear()
        }
    }

    private fun noteMessageWindowLocked() {
        val now = clock.nowMillis()
        if (firstMessageAtMs < 0L) firstMessageAtMs = now
        messageWindowCount++
    }

    fun snapshot(): NetworkStatsSnapshot {
        synchronized(lock) {
            val now = clock.nowMillis()
            val lastAge = if (lastMessageReceivedAtMs > 0L) now - lastMessageReceivedAtMs else null
            val elapsedMs = if (firstMessageAtMs > 0L) now - firstMessageAtMs else -1L
            val rate = if (elapsedMs >= 1_000L && messageWindowCount > 0L) {
                messageWindowCount * 60_000.0 / elapsedMs
            } else {
                -1.0
            }
            return NetworkStatsSnapshot(
                state = stateLabel,
                profileName = profileName,
                host = host,
                port = port,
                transport = transport,
                reconnectAttempt = reconnectAttempt,
                maxReconnectAttempts = maxReconnectAttempts,
                reconnectCount = reconnectCount,
                pingIntervalSeconds = pingIntervalSeconds,
                lastPingRttMs = lastPingRttMs,
                connectLatencyMs = connectLatencyMs,
                lastMessageAgeMs = lastAge,
                messagesSent = messagesSent,
                messagesReceived = messagesReceived,
                sendFailures = sendFailures,
                lastMessageType = lastMessageType,
                lastProtocolError = lastProtocolError,
                messagesPerMinute = rate,
                generation = generation,
                protocolVersion = protocolVersion,
                serverName = serverName,
                serverVersion = serverVersion,
                agentId = agentId,
                capabilities = capabilities,
                authenticated = authenticated,
                problem = problem,
                handshakeLatencyMs = handshakeLatencyMs,
                readyLatencyMs = readyLatencyMs,
                networkType = networkType,
                dnsLatencyMs = dnsLatencyMs,
                wsUpgradeLatencyMs = wsUpgradeLatencyMs,
                authLatencyMs = authLatencyMs,
                requestRttMs = requestRttMs.toMap()
            )
        }
    }
}

data class NetworkStatsSnapshot(
    val state: String = "Disconnected",
    val profileName: String? = null,
    val host: String? = null,
    val port: Int = 0,
    val transport: String = "WebSocket",
    val reconnectAttempt: Int = 0,
    val maxReconnectAttempts: Int = 0,
    val reconnectCount: Long = 0L,
    val pingIntervalSeconds: Long = -1L,
    val lastPingRttMs: Long = -1L,
    val connectLatencyMs: Long = -1L,
    val lastMessageAgeMs: Long? = null,
    val messagesSent: Long = 0L,
    val messagesReceived: Long = 0L,
    val sendFailures: Long = 0L,
    val lastMessageType: String? = null,
    val lastProtocolError: String? = null,
    val messagesPerMinute: Double = -1.0,
    val generation: Long = -1L,
    val protocolVersion: String? = null,
    val serverName: String? = null,
    val serverVersion: String? = null,
    val agentId: String? = null,
    val capabilities: Set<String> = emptySet(),
    val authenticated: Boolean = false,
    val problem: String? = null,
    val handshakeLatencyMs: Long = -1L,
    val readyLatencyMs: Long = -1L,
    val networkType: String? = null,
    val dnsLatencyMs: Long = -1L,
    val wsUpgradeLatencyMs: Long = -1L,
    val authLatencyMs: Long = -1L,
    val requestRttMs: Map<String, Long> = emptyMap(),
    val latencyMs: Long = -1L
)

object NetworkStatsRegistry {
    @Volatile
    var current: NetworkStats = NetworkStats()

    fun resetForTests() {
        current = NetworkStats()
    }
}
