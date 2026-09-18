package com.studyagent.client.core.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport abstraction - separates raw WebSocket lifecycle from agent protocol.
 *
 * Responsibilities:
 * - Socket open/close
 * - Generation counter to prevent parallel sockets
 * - Liveness detection (ping loop with correlation)
 * - Frame size limits
 * - Bearer auth header injection
 * - Network type awareness
 *
 * Does NOT handle:
 * - Hello/welcome handshake
 * - Authentication result
 * - Capability negotiation
 * - Session recovery
 *
 * Those are owned by AgentClient which sits above Transport.
 */
interface Transport {

    val state: StateFlow<TransportStatus>
    val incomingFrames: Flow<String>
    val generation: Long

    suspend fun connect(url: String, bearerToken: String? = null): Boolean
    suspend fun disconnect(reason: String = "User disconnect")
    suspend fun send(frame: String): Boolean

    fun isOpen(): Boolean
}

/**
 * Transport configuration
 */
data class TransportConfig(
    val pingIntervalMs: Long = 30_000L,
    val pongTimeoutMs: Long = 10_000L,
    val handshakeTimeoutMs: Long = 8_000L,
    val maxFrameSizeBytes: Int = 2 * 1024 * 1024,
    val enablePingCorrelation: Boolean = true
)

/**
 * Factory for creating transports - allows testing with FakeTransport
 */
interface TransportFactory {
    fun create(config: TransportConfig): Transport
}
