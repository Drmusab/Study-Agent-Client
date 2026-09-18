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
 * The ONE authoritative capability state for the whole app.
 * Dashboard, Control Center and Connection screens all observe this store.
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
            is ConnectionState.TransportConnected,
            is ConnectionState.Handshaking,
            is ConnectionState.Authenticating,
            is ConnectionState.NegotiatingCapabilities,
            is ConnectionState.ConnectingTransport,
            is ConnectionState.Connecting,
            is ConnectionState.Resolving -> {
                val serial = ++connectionSerial
                if (_capabilities.value.status == NegotiationStatus.UNKNOWN) {
                    _capabilities.value = AgentCapabilities(
                        status = NegotiationStatus.NEGOTIATING,
                        protocolVersion = "2",
                        serverName = (state as? ConnectionState.TransportConnected)?.serverName
                    )
                }
                // Start timeout if not already
                if (negotiationTimeoutJob == null) {
                    negotiationTimeoutJob?.cancel()
                    negotiationTimeoutJob = scope.launch {
                        delay(negotiationTimeoutMs)
                        if (connectionSerial == serial && _capabilities.value.status == NegotiationStatus.NEGOTIATING) {
                            AppLogger.i(tag, "No capabilities/welcome received; treating server as Protocol v1")
                            _capabilities.value = AgentCapabilities(
                                status = NegotiationStatus.LEGACY_V1,
                                protocolVersion = "1",
                                serverName = _capabilities.value.serverName
                            )
                        }
                    }
                }
            }

            is ConnectionState.Ready -> {
                negotiationTimeoutJob?.cancel()
                negotiationTimeoutJob = null
                _capabilities.value = AgentCapabilities(
                    status = NegotiationStatus.NEGOTIATED_V2,
                    protocolVersion = state.protocolVersion,
                    capabilities = state.capabilities,
                    serverName = state.serverName,
                    serverVersion = state.serverVersion
                )
            }

            is ConnectionState.ReadyLegacy -> {
                negotiationTimeoutJob?.cancel()
                negotiationTimeoutJob = null
                _capabilities.value = AgentCapabilities(
                    status = NegotiationStatus.LEGACY_V1,
                    protocolVersion = "1",
                    serverName = state.serverName
                )
            }

            is ConnectionState.Connected -> {
                val serial = ++connectionSerial
                // Legacy path - treat Connected as negotiating
                if (_capabilities.value.status == NegotiationStatus.UNKNOWN) {
                    _capabilities.value = AgentCapabilities(
                        status = NegotiationStatus.NEGOTIATING,
                        protocolVersion = "2",
                        serverName = state.serverName
                    )
                }
                negotiationTimeoutJob?.cancel()
                negotiationTimeoutJob = scope.launch {
                    delay(negotiationTimeoutMs)
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
        when (message) {
            is ServerMessage.Capabilities -> {
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
            is ServerMessage.Welcome -> {
                negotiationTimeoutJob?.cancel()
                negotiationTimeoutJob = null
                val caps = AgentCapabilities(
                    status = NegotiationStatus.NEGOTIATED_V2,
                    protocolVersion = message.selectedProtocol,
                    capabilities = message.capabilities.toSet(),
                    serverName = message.serverName,
                    serverVersion = message.serverVersion
                )
                AppLogger.i(tag, "Welcome negotiated: protocol=${message.selectedProtocol} caps=${caps.capabilities} server=${message.serverName}")
                _capabilities.value = caps
            }
            else -> Unit
        }
    }

    companion object {
        const val DEFAULT_NEGOTIATION_TIMEOUT_MS = 4_000L
    }
}

val AgentCapabilities.isProtocolV2: Boolean
    get() = status == NegotiationStatus.NEGOTIATED_V2

fun AgentCapabilities.supportsV2(capability: String): Boolean =
    isProtocolV2 && supports(capability)
