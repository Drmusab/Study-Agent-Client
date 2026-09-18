package com.studyagent.client.core.models

/**
 * Enhanced connection lifecycle separating transport from agent readiness.
 *
 * Target lifecycle:
 * DISCONNECTED -> RESOLVING -> CONNECTING_TRANSPORT -> TRANSPORT_OPEN ->
 * HANDSHAKING -> AUTHENTICATING -> NEGOTIATING_CAPABILITIES -> READY
 *
 * Failure branches: NETWORK_UNAVAILABLE, SERVER_UNREACHABLE, TLS_FAILED,
 * AUTHENTICATION_FAILED, INCOMPATIBLE_PROTOCOL, HANDSHAKE_TIMEOUT, AGENT_UNAVAILABLE
 */
sealed interface ConnectionState {

    // --- Stable happy path ---

    data object Disconnected : ConnectionState

    data class Resolving(
        val host: String,
        val port: Int
    ) : ConnectionState

    /**
     * Transport connecting (TCP/WebSocket handshake in progress).
     * Legacy alias: Connecting
     */
    data class ConnectingTransport(
        val host: String,
        val port: Int
    ) : ConnectionState

    /** Transport open but agent not yet verified */
    data class TransportConnected(
        val host: String,
        val port: Int,
        val serverName: String? = null
    ) : ConnectionState

    data class Handshaking(
        val host: String,
        val port: Int
    ) : ConnectionState

    data class Authenticating(
        val host: String,
        val port: Int
    ) : ConnectionState

    data class NegotiatingCapabilities(
        val host: String,
        val port: Int,
        val serverName: String? = null,
        val protocolVersion: String? = null
    ) : ConnectionState

    /**
     * Fully ready - agent verified, protocol negotiated, authenticated, capabilities known
     */
    data class Ready(
        val host: String,
        val port: Int,
        val serverName: String,
        val serverVersion: String? = null,
        val protocolVersion: String = "2",
        val latencyMs: Long? = null,
        val capabilities: Set<String> = emptySet(),
        val agentId: String? = null,
        val authenticated: Boolean = true
    ) : ConnectionState

    /** Legacy v1 agent - basic study works, management disabled */
    data class ReadyLegacy(
        val host: String,
        val port: Int,
        val serverName: String? = null,
        val latencyMs: Long? = null
    ) : ConnectionState

    // --- Reconnecting ---

    data class Reconnecting(
        val attempt: Int,
        val maxAttempts: Int,
        val nextRetryInMs: Long,
        val reason: String? = null,
        val phase: String? = null
    ) : ConnectionState

    // --- Failure states ---

    data object NetworkUnavailable : ConnectionState

    data class AuthenticationFailed(
        val reason: String,
        val isExpired: Boolean = false
    ) : ConnectionState

    data class TlsFailure(
        val reason: String
    ) : ConnectionState

    data class ProtocolMismatch(
        val reason: String,
        val serverVersion: String? = null,
        val clientVersions: List<String> = listOf("1", "2")
    ) : ConnectionState

    data class AgentUnavailable(
        val reason: String
    ) : ConnectionState

    data class ServerUnavailable(
        val reason: String
    ) : ConnectionState

    data class HandshakeTimeout(
        val host: String,
        val port: Int
    ) : ConnectionState

    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : ConnectionState

    // --- Compatibility aliases for existing code ---

    /** Legacy: maps to ConnectingTransport */
    data class Connecting(
        val host: String,
        val port: Int
    ) : ConnectionState

    /** Legacy: maps to Ready with minimal info - kept for backward compat */
    data class Connected(
        val host: String,
        val port: Int,
        val serverName: String? = null,
        val latencyMs: Long? = null
    ) : ConnectionState

    // --- Computed properties ---

    val isConnected: Boolean
        get() = when (this) {
            is Ready, is ReadyLegacy, is Connected -> true
            else -> false
        }

    /** True when agent is verified and ready for study */
    val isAgentReady: Boolean
        get() = when (this) {
            is Ready, is ReadyLegacy, is Connected -> true
            else -> false
        }

    val isReady: Boolean
        get() = isAgentReady

    val isTransportOpen: Boolean
        get() = when (this) {
            is TransportConnected, is Handshaking, is Authenticating,
            is NegotiatingCapabilities, is Ready, is ReadyLegacy, is Connected -> true
            else -> false
        }

    val isReconnecting: Boolean
        get() = this is Reconnecting

    val isFailedAuth: Boolean
        get() = this is AuthenticationFailed

    val label: String
        get() = when (this) {
            is Disconnected -> "Disconnected"
            is Resolving -> "Resolving $host:$port..."
            is ConnectingTransport -> "Connecting to $host:$port..."
            is Connecting -> "Connecting to $host:$port..."
            is TransportConnected -> "Transport open to $host:$port"
            is Handshaking -> "Handshaking with $host:$port..."
            is Authenticating -> "Authenticating..."
            is NegotiatingCapabilities -> "Negotiating capabilities..."
            is Ready -> "Ready: ${serverName} (${protocolVersion})"
            is ReadyLegacy -> "Ready (legacy): ${serverName ?: "$host:$port"}"
            is Connected -> "Connected to ${serverName ?: "$host:$port"}"
            is Reconnecting -> "Reconnecting (attempt $attempt/$maxAttempts)..."
            is AuthenticationFailed -> "Authentication failed: $reason"
            is TlsFailure -> "TLS failed: $reason"
            is ProtocolMismatch -> "Protocol mismatch: $reason"
            is AgentUnavailable -> "Agent unavailable: $reason"
            is ServerUnavailable -> "Server unavailable ($reason)"
            is NetworkUnavailable -> "Network unavailable"
            is HandshakeTimeout -> "Handshake timeout with $host:$port"
            is Error -> "Connection error: $message"
        }

    /** Machine-readable phase for diagnostics */
    val phaseName: String
        get() = when (this) {
            is Disconnected -> "DISCONNECTED"
            is Resolving -> "RESOLVING"
            is ConnectingTransport, is Connecting -> "CONNECTING_TRANSPORT"
            is TransportConnected -> "TRANSPORT_OPEN"
            is Handshaking -> "HANDSHAKING"
            is Authenticating -> "AUTHENTICATING"
            is NegotiatingCapabilities -> "NEGOTIATING_CAPABILITIES"
            is Ready -> "READY"
            is ReadyLegacy -> "READY_LEGACY"
            is Connected -> "READY" // legacy maps to ready for UI compat
            is Reconnecting -> "RECONNECTING"
            is NetworkUnavailable -> "NETWORK_UNAVAILABLE"
            is AuthenticationFailed -> "AUTHENTICATION_FAILED"
            is TlsFailure -> "TLS_FAILED"
            is ProtocolMismatch -> "INCOMPATIBLE_PROTOCOL"
            is AgentUnavailable -> "AGENT_UNAVAILABLE"
            is ServerUnavailable -> "SERVER_UNREACHABLE"
            is HandshakeTimeout -> "HANDSHAKE_TIMEOUT"
            is Error -> "ERROR"
        }
}

/** Helper to get host/port from any state that has them */
fun ConnectionState.hostPort(): Pair<String, Int>? = when (this) {
    is ConnectionState.Resolving -> host to port
    is ConnectionState.ConnectingTransport -> host to port
    is ConnectionState.Connecting -> host to port
    is ConnectionState.TransportConnected -> host to port
    is ConnectionState.Handshaking -> host to port
    is ConnectionState.Authenticating -> host to port
    is ConnectionState.NegotiatingCapabilities -> host to port
    is ConnectionState.Ready -> host to port
    is ConnectionState.ReadyLegacy -> host to port
    is ConnectionState.Connected -> host to port
    is ConnectionState.HandshakeTimeout -> host to port
    else -> null
}
