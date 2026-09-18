package com.studyagent.client.data.repository

import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.core.network.AgentConnection
import com.studyagent.client.core.network.AgentConnectionSnapshot
import com.studyagent.client.core.network.ConnectionTestResult
import com.studyagent.client.core.network.FakeAgentConnection
import com.studyagent.client.core.network.TransportStatus
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

interface ConnectionRepository {
    val connectionState: StateFlow<ConnectionState>
    val connectionSnapshot: StateFlow<AgentConnectionSnapshot>
    val incomingMessages: Flow<ServerMessage>
    val activeProfile: Flow<ServerProfile?>

    suspend fun connect(profile: ServerProfile? = null)
    suspend fun connectWithOverride(profile: ServerProfile? = null)
    suspend fun disconnect(reason: String = "User requested disconnect")
    suspend fun send(message: ClientMessage): Boolean
    fun toggleFakeAgent(useFake: Boolean)

    fun testConnection(profile: ServerProfile): Flow<ConnectionTestResult>
    fun getConnectionDiagnostics(): String
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultConnectionRepository(
    private val profileRepository: ProfileRepository,
    private val preferencesDataStore: PreferencesDataStore,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)
) : ConnectionRepository {

    private val tag = "ConnectionRepo"

    private val realConnection = WebSocketAgentConnection(
        dispatchers = dispatchers,
        settingsFlow = preferencesDataStore.settingsFlow
    )
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

    override val connectionSnapshot: StateFlow<AgentConnectionSnapshot> = activeConnectionFlow
        .flatMapLatest {
            try {
                it.connectionSnapshot
            } catch (_: NotImplementedError) {
                flowOf(AgentConnectionSnapshot.disconnected())
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, AgentConnectionSnapshot.disconnected())

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
        // Validate before connecting - don't hide bad config
        val normalized = targetProfile.normalized()
        if (normalized == null) {
            AppLogger.w(tag, "Invalid profile config: ${targetProfile.name}")
            return
        }
        AppLogger.i(tag, "Connecting using ${_isFakeMode.value} mode to ${targetProfile.name} (${targetProfile.host}:${targetProfile.port})")
        val currentConn = activeConnectionFlow.value
        currentConn.connect(targetProfile)
    }

    override suspend fun connectWithOverride(profile: ServerProfile?) {
        val targetProfile = profile ?: profileRepository.getActiveProfileOnce() ?: ServerProfile.defaultLocalProfile()
        val normalized = targetProfile.normalized()
        if (normalized == null) return
        AppLogger.i(tag, "Manual connect override to ${targetProfile.name}")
        activeConnectionFlow.value.connectWithOverride(targetProfile)
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

    override fun testConnection(profile: ServerProfile): Flow<ConnectionTestResult> {
        return activeConnectionFlow.value.testConnection(profile)
    }

    override fun getConnectionDiagnostics(): String {
        val snapshot = connectionSnapshot.value
        val state = connectionState.value
        return buildString {
            appendLine("=== Connection Diagnostics (sanitized) ===")
            appendLine("App: ${getAppVersion()}")
            appendLine("Phase: ${state.phaseName}")
            appendLine("Transport: ${snapshot.transport}")
            appendLine("Host: ${snapshot.profile?.host ?: "none"}:${snapshot.profile?.port ?: 0}")
            appendLine("Server: ${snapshot.serverName ?: "unknown"} ${snapshot.serverVersion ?: ""}")
            appendLine("Protocol: ${snapshot.protocolVersion ?: "unknown"}")
            appendLine("Capabilities: ${snapshot.capabilities.joinToString(", ")}")
            appendLine("Authenticated: ${snapshot.authenticated ?: "unknown"}")
            appendLine("Latency: ${snapshot.latencyMs?.let { "${it}ms" } ?: "unknown"}")
            appendLine("Last message: ${snapshot.lastMessageAgeMs?.let { "${it}ms ago" } ?: "never"}")
            appendLine("Generation: ${snapshot.connectionGeneration}")
            appendLine("Problem: ${snapshot.problem?.userMessage ?: "none"}")
            appendLine("Profile: ${snapshot.profile?.name ?: "none"}")
            appendLine("Connection: ${state.label}")
        }
    }

    private fun getAppVersion(): String {
        return try {
            val clazz = Class.forName("com.studyagent.client.BuildConfig")
            clazz.getField("VERSION_NAME").get(null) as? String ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
