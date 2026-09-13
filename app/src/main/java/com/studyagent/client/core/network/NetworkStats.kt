package com.studyagent.client.core.network

import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.SystemAppClock

/**
 * Network-side technical counters (§60/§108/§123/§124).
 *
 * Two jobs:
 *
 * 1. Make reconnect behaviour *observable*. "It disconnected" is not a diagnostic; "4 reconnects
 *    in 6 minutes, last one 2 s after a 30 s gap" is.
 * 2. Make accidental chatter visible. [NetworkStatsSnapshot.messagesPerMinute] is the cheapest
 *    detector for a polling loop that nobody intended — an accidental dashboard poll shows up as
 *    a rate, not as an error.
 *
 * Bounded by construction: counters and a single first-message timestamp. No message history is
 * retained, and no payload content is ever passed to this class — only a message *type* string.
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

    /** Window start for the message-rate metric; reset on clear/reset. */
    private var firstMessageAtMs = -1L
    private var messageWindowCount = 0L

    fun onConnecting(profileName: String?, host: String?, port: Int, transport: String = "WebSocket") {
        synchronized(lock) {
            stateLabel = "Connecting"
            this.profileName = profileName
            this.host = host
            this.port = port
            this.transport = transport
            connectStartedAtMs = clock.nowMillis()
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
            if (reason != null) lastProtocolError = null // disconnect reasons are not protocol errors
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
                messagesPerMinute = rate
            )
        }
    }
}

/**
 * Immutable network diagnostics view. `-1`/null mean "not measured" — Diagnostics renders those
 * as `-`/`Unknown` rather than as a zero (§66). No token, header or payload is ever included (§60).
 */
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
    val messagesPerMinute: Double = -1.0
)

/**
 * The instance Diagnostics reads.
 *
 * The connection classes default to this object so a caller that constructs
 * `WebSocketAgentConnection(dispatchers)` (as the existing integration test does) still feeds the
 * diagnostics view without a signature change. Tests that need isolation construct their own
 * [NetworkStats] and inject it.
 */
object NetworkStatsRegistry {
    @Volatile
    var current: NetworkStats = NetworkStats()

    /** Test hook: replaces the shared instance so cases cannot observe each other's counters. */
    fun resetForTests() {
        current = NetworkStats()
    }
}
