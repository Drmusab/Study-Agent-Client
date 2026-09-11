package com.studyagent.client.core.models

/**
 * Protocol capability identifiers negotiated with the PC Study Agent (Protocol v2).
 *
 * Capability names are exchanged as plain strings on the wire so that a newer
 * server can advertise capabilities this client does not know yet without
 * breaking deserialization. Unknown capability strings are preserved in
 * [AgentCapabilities.capabilities] and simply ignored by the UI.
 */
object AgentCapability {
    const val DASHBOARD = "dashboard"
    const val DECK_LIST = "deck_list"
    const val STUDY_CONFIG = "study_config"
    const val HISTORY = "history"
    const val AI_USAGE = "ai_usage"
    const val LEARNING_INSIGHTS = "learning_insights"
    const val COMPONENT_HEALTH = "component_health"
    const val SESSION_PROGRESS = "session_progress"
}

/**
 * Result of protocol negotiation with the connected PC agent.
 *
 * - [NegotiationStatus.UNKNOWN]: no connection has been established yet.
 * - [NegotiationStatus.NEGOTIATING]: connected, waiting for a `capabilities` frame.
 * - [NegotiationStatus.NEGOTIATED_V2]: server explicitly advertised v2 capabilities.
 * - [NegotiationStatus.LEGACY_V1]: server never sent capabilities -> treated as a
 *   protocol v1 agent. Study functionality stays fully available; dashboard
 *   analytics are marked unsupported instead of failing.
 */
data class AgentCapabilities(
    val status: NegotiationStatus = NegotiationStatus.UNKNOWN,
    val protocolVersion: String = "1",
    val capabilities: Set<String> = emptySet(),
    val serverName: String? = null,
    val serverVersion: String? = null
) {
    enum class NegotiationStatus { UNKNOWN, NEGOTIATING, NEGOTIATED_V2, LEGACY_V1 }

    val isResolved: Boolean
        get() = status == NegotiationStatus.NEGOTIATED_V2 || status == NegotiationStatus.LEGACY_V1

    val isLegacyV1: Boolean
        get() = status == NegotiationStatus.LEGACY_V1

    fun supports(capability: String): Boolean = capabilities.contains(capability)
}