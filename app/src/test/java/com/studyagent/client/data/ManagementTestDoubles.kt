package com.studyagent.client.data

import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.core.network.AgentConnectionSnapshot
import com.studyagent.client.data.repository.ConnectionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Controllable ConnectionRepository for management-layer unit tests.
 * Sent messages are recorded; server frames are injected via [emit].
 */
class FakeConnectionRepository : ConnectionRepository {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incoming = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 256)
    override val incomingMessages: Flow<ServerMessage> = _incoming.asSharedFlow()

    private val _connectionSnapshot = MutableStateFlow(AgentConnectionSnapshot.disconnected())
    override val connectionSnapshot: StateFlow<AgentConnectionSnapshot> = _connectionSnapshot.asStateFlow()

    override val activeProfile: Flow<ServerProfile?> = flowOf(null)

    val sentMessages = mutableListOf<ClientMessage>()
    var sendAllowed = true

    /** Optional auto-responder hook (runs synchronously inside send). */
    var onSend: ((ClientMessage) -> Unit)? = null

    override suspend fun connect(profile: ServerProfile?) {
        setConnected(true)
    }

    override suspend fun connectWithOverride(profile: ServerProfile?) {
        setConnected(true)
    }

    override suspend fun disconnect(reason: String) {
        setConnected(false)
    }

    override suspend fun send(message: ClientMessage): Boolean {
        if (!sendAllowed) return false
        sentMessages.add(message)
        onSend?.invoke(message)
        return true
    }

    override fun toggleFakeAgent(useFake: Boolean) = Unit

    override fun testConnection(profile: ServerProfile): Flow<com.studyagent.client.core.network.ConnectionTestResult> = flowOf(
        com.studyagent.client.core.network.ConnectionTestResult(
            stage = com.studyagent.client.core.network.ConnectionTestResult.TestStage.TRANSPORT,
            success = true,
            message = "ok"
        )
    )

    override fun getConnectionDiagnostics(): String = "fake" 

    fun setConnected(connected: Boolean) {
        _connectionState.value = if (connected) {
            ConnectionState.Connected(host = "10.0.0.2", port = 8765, serverName = "Test Agent")
        } else {
            ConnectionState.Disconnected
        }
    }

    suspend fun emit(message: ServerMessage) {
        _incoming.emit(message)
    }

    fun sentOfType(type: String): List<ClientMessage> = sentMessages.filter { it.type == type }
}

class TestDispatcherProvider(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val unconfined: CoroutineDispatcher = dispatcher
}
