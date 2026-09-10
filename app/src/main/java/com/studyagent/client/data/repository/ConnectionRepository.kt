package com.studyagent.client.data.repository

import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.core.network.AgentConnection
import com.studyagent.client.core.network.FakeAgentConnection
import com.studyagent.client.core.network.WebSocketAgentConnection
import com.studyagent.client.data.preferences.PreferencesDataStore
import com.studyagent.client.data.preferences.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

interface ConnectionRepository {
    val connectionState: StateFlow<ConnectionState>
    val incomingMessages: Flow<ServerMessage>
    val activeProfile: Flow<ServerProfile?>

    suspend fun connect(profile: ServerProfile? = null)
    suspend fun disconnect(reason: String = "User requested disconnect")
    suspend fun send(message: ClientMessage): Boolean
    fun toggleFakeAgent(useFake: Boolean)
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultConnectionRepository(
    private val profileRepository: ProfileRepository,
    private val preferencesDataStore: PreferencesDataStore,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)
) : ConnectionRepository {

    private val tag = "ConnectionRepo"

    private val realConnection = WebSocketAgentConnection(dispatchers)
    private val fakeConnection = FakeAgentConnection(scope)

    private val _isFakeMode = MutableStateFlow(false)
    private val activeConnectionFlow = _isFakeMode.mapConnection()

    private fun MutableStateFlow<Boolean>.mapConnection(): StateFlow<AgentConnection> {
        val flow = MutableStateFlow<AgentConnection>(realConnection)
        scope.launch {
            this@mapConnection.collect { useFake ->
                flow.value = if (useFake) fakeConnection else realConnection
            }
        }
        return flow
    }

    override val activeProfile: Flow<ServerProfile?> = profileRepository.activeProfile

    override val connectionState: StateFlow<ConnectionState> = activeConnectionFlow
        .flatMapLatest { it.connectionState }
        .stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    override val incomingMessages: Flow<ServerMessage> = activeConnectionFlow
        .flatMapLatest { it.incomingMessages }

    init {
        scope.launch {
            preferencesDataStore.settingsFlow.distinctUntilChanged().collect { settings ->
                _isFakeMode.value = settings.useFakeAgent
                AppLogger.isDebugEnabled = settings.debugLogging
            }
        }
    }

    override suspend fun connect(profile: ServerProfile?) {
        val targetProfile = profile ?: profileRepository.getActiveProfileOnce() ?: ServerProfile.defaultLocalProfile()
        AppLogger.i(tag, "Connecting using ${_isFakeMode.value} mode to ${targetProfile.name} (${targetProfile.host}:${targetProfile.port})")
        val currentConn = activeConnectionFlow.value
        currentConn.connect(targetProfile)
    }

    override suspend fun disconnect(reason: String) {
        AppLogger.i(tag, "Disconnecting from connection: $reason")
        activeConnectionFlow.value.disconnect(reason)
    }

    override suspend fun send(message: ClientMessage): Boolean {
        return activeConnectionFlow.value.send(message)
    }

    override fun toggleFakeAgent(useFake: Boolean) {
        scope.launch {
            if (_isFakeMode.value != useFake) {
                disconnect("Switching connection mode")
                preferencesDataStore.updateSettings { it.copy(useFakeAgent = useFake) }
                _isFakeMode.value = useFake
            }
        }
    }
}
