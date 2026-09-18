package com.studyagent.client.core.network

enum class TransportStatus {
    DISCONNECTED,
    RESOLVING,
    CONNECTING,
    OPEN,
    CLOSING,
    FAILED
}

data class ReconnectInfo(
    val attempt: Int,
    val maxAttempts: Int,
    val nextRetryInMs: Long,
    val lastReason: String? = null
)
