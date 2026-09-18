package com.studyagent.client.core.network

import com.studyagent.client.core.models.AgentCapabilities
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.ServerProfile

/**
 * Coherent connection truth - one snapshot instead of scattered booleans.
 * This is the authoritative source for UI, diagnostics, and repositories.
 */
data class AgentConnectionSnapshot(
    val phase: ConnectionState,
    val transport: TransportStatus,
    val profile: ServerProfile?,
    val protocolVersion: String?,
    val serverName: String?,
    val serverVersion: String?,
    val agentId: String?,
    val capabilities: Set<String>,
    val authenticated: Boolean?,
    val latencyMs: Long?,
    val lastMessageAgeMs: Long?,
    val retry: ReconnectInfo?,
    val problem: ConnectionProblem?,
    val networkAvailable: Boolean = true,
    val connectionGeneration: Long = 0L
) {
    val isReady: Boolean
        get() = phase.isAgentReady

    val isTransportOpen: Boolean
        get() = transport == TransportStatus.OPEN

    companion object {
        fun disconnected(): AgentConnectionSnapshot = AgentConnectionSnapshot(
            phase = ConnectionState.Disconnected,
            transport = TransportStatus.DISCONNECTED,
            profile = null,
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

/** Legacy alias for docs compatibility */
typealias AgentConnectionPhase = ConnectionState
