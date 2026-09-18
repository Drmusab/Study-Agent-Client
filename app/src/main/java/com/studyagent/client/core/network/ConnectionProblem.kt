package com.studyagent.client.core.network

/**
 * Typed connection problem model - stable machine-readable reasons.
 * UI maps these to actionable user messages.
 */
sealed interface ConnectionProblem {
    data object NetworkMissing : ConnectionProblem
    data class DnsFailure(val host: String, val cause: String? = null) : ConnectionProblem
    data class ConnectionRefused(val host: String, val port: Int) : ConnectionProblem
    data class Timeout(val phase: String, val elapsedMs: Long) : ConnectionProblem
    data class TlsFailure(val reason: String) : ConnectionProblem
    data class AuthenticationRejected(val reason: String, val expired: Boolean = false) : ConnectionProblem
    data class ProtocolMismatch(val clientVersions: List<String>, val serverVersion: String?) : ConnectionProblem
    data class HandshakeTimeout(val elapsedMs: Long) : ConnectionProblem
    data class AgentBusy(val reason: String? = null) : ConnectionProblem
    data class AgentNotStudyAgent(val detail: String) : ConnectionProblem
    data class Unknown(val message: String, val cause: Throwable? = null) : ConnectionProblem

    val userMessage: String
        get() = when (this) {
            is NetworkMissing -> "No network connection"
            is DnsFailure -> "Cannot resolve host: $host"
            is ConnectionRefused -> "Connection refused by $host:$port"
            is Timeout -> "Connection timed out during $phase"
            is TlsFailure -> "Secure connection failed: $reason"
            is AuthenticationRejected -> if (expired) "Authentication expired" else "Authentication rejected: $reason"
            is ProtocolMismatch -> "Incompatible protocol versions"
            is HandshakeTimeout -> "Agent did not respond to handshake"
            is AgentBusy -> "Agent busy"
            is AgentNotStudyAgent -> "Server found, but no Study Agent handshake received"
            is Unknown -> message
        }

    val userAction: String
        get() = when (this) {
            is NetworkMissing -> "Check Wi-Fi or Tailscale connection"
            is DnsFailure -> "Verify host address is correct"
            is ConnectionRefused -> "Start the PC Study Agent and check firewall"
            is Timeout -> "Check network and that PC Agent is running"
            is TlsFailure -> "Check secure server address/certificate"
            is AuthenticationRejected -> "Edit token or pair again"
            is ProtocolMismatch -> "Update app and PC Agent to compatible versions"
            is HandshakeTimeout -> "Verify this is a Study Agent endpoint, not another service"
            is AgentBusy -> "Wait a moment and retry"
            is AgentNotStudyAgent -> "Verify host/port points to Study Agent, not another service"
            is Unknown -> "Check connection details and try again"
        }
}
