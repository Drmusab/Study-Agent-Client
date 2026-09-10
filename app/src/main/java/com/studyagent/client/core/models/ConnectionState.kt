package com.studyagent.client.core.models

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val host: String, val port: Int) : ConnectionState
    data class Connected(
        val host: String,
        val port: Int,
        val serverName: String? = null,
        val latencyMs: Long? = null
    ) : ConnectionState
    data class Reconnecting(
        val attempt: Int,
        val maxAttempts: Int,
        val nextRetryInMs: Long,
        val reason: String? = null
    ) : ConnectionState
    data class AuthenticationFailed(val reason: String) : ConnectionState
    data class ServerUnavailable(val reason: String) : ConnectionState
    data object NetworkUnavailable : ConnectionState
    data class Error(val message: String, val cause: Throwable? = null) : ConnectionState

    val isConnected: Boolean
        get() = this is Connected

    val isReconnecting: Boolean
        get() = this is Reconnecting

    val label: String
        get() = when (this) {
            is Disconnected -> "Disconnected"
            is Connecting -> "Connecting to $host:$port..."
            is Connected -> "Connected to ${serverName ?: "$host:$port"}"
            is Reconnecting -> "Reconnecting (attempt $attempt/$maxAttempts)..."
            is AuthenticationFailed -> "Authentication failed: $reason"
            is ServerUnavailable -> "Server unavailable ($reason)"
            is NetworkUnavailable -> "Network unavailable"
            is Error -> "Connection error: $message"
        }
}
