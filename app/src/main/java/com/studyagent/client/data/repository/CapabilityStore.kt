package com.studyagent.client.data.repository

import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.models.AgentCapabilities
import com.studyagent.client.core.models.AgentCapabilities.NegotiationStatus
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The ONE authoritative capability state for the whole app (§109).
 *
 * Dashboard, Control Center and Connection screens all observe this store —
 * no ViewModel guesses features on its own.
 *
 * Negotiation model:
 *  - On connect: status becomes [NegotiationStatus.NEGOTIATING]; a v2 server is
 *    expected to send a `capabilities` frame shortly after the hello exchange.
 *  - If the frame arrives: [NegotiationStatus.NEGOTIATED_V2] with the exact
 *    advertised capability set (possibly empty — v2 but nothing supported).
 *  - If the frame does not arrive within [negotiationTimeoutMs]: the server is
 *    treated as a legacy Protocol v1 agent ([NegotiationStatus.LEGACY_V1]).
 *    Basic study keeps working; management features are gated off instead of
 *    failing (§12).
 *  - On any disconnect: back to [NegotiationStatus.UNKNOWN].
 */
class CapabilityStore(
    connectionRepository: ConnectionRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val negotiationTimeoutMs: Long = DEFAULT_NEGOTIATION_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val tag = "CapabilityStore"

    private val _capabilities = MutableStateFlow(AgentCapabilities())
    val capabilities: StateFlow<AgentCapabilities> = _capabilities.asStateFlow()

    private var negotiationTimeoutJob: Job? = null
    private var connectionSerial: Long = 0L

    init {
        scope.launch {
            connectionRepository.connectionState.collect { state -> onConnectionState(state) }
        }
        scope.launch {
            connectionRepository.incomingMessages.collect { message -> onMessage(message) }
        }
    }

    private fun onConnectionState(state: ConnectionState) {
        when (state) {
            is ConnectionState.Connected -> {
                val serial = ++connectionSerial
                _capabilities.value = AgentCapabilities(
                    status = NegotiationStatus.NEGOTIATING,
                    protocolVersion = "2",
                    serverName = state.serverName
                )
                negotiationTimeoutJob?.cancel()
                negotiationTimeoutJob = scope.launch {
                    delay(negotiationTimeoutMs)
                    // Only downgrade if THIS connection is still waiting for the frame.
                    if (connectionSerial == serial && _capabilities.value.status == NegotiationStatus.NEGOTIATING) {
                        AppLogger.i(tag, "No capabilities frame received; treating server as Protocol v1")
                        _capabilities.value = AgentCapabilities(
                            status = NegotiationStatus.LEGACY_V1,
                            protocolVersion = "1",
                            serverName = state.serverName
                        )
                    }
                }
            }

            else -> {
                connectionSerial++
                negotiationTimeoutJob?.cancel()
                negotiationTimeoutJob = null
                if (_capabilities.value.status != NegotiationStatus.UNKNOWN) {
                    _capabilities.value = AgentCapabilities()
                }
            }
        }
    }

    private fun onMessage(message: ServerMessage) {
        if (message !is ServerMessage.Capabilities) return
        negotiationTimeoutJob?.cancel()
        negotiationTimeoutJob = null
        val caps = AgentCapabilities(
            status = NegotiationStatus.NEGOTIATED_V2,
            protocolVersion = message.protocolVersion ?: "2",
            capabilities = message.capabilities.toSet(),
            serverName = message.serverName ?: _capabilities.value.serverName,
            serverVersion = message.serverVersion
        )
        AppLogger.i(tag, "Capabilities negotiated: ${caps.capabilities} (server=${caps.serverName} v=${caps.serverVersion})")
        _capabilities.value = caps
    }

    companion object {
        const val DEFAULT_NEGOTIATION_TIMEOUT_MS = 4_000L
    }
}

/** True once the server explicitly negotiated Protocol v2. */
val AgentCapabilities.isProtocolV2: Boolean
    get() = status == NegotiationStatus.NEGOTIATED_V2

/** True when the server negotiated v2 AND advertised [capability]. */
fun AgentCapabilities.supportsV2(capability: String): Boolean =
    isProtocolV2 && supports(capability)
