package com.studyagent.client.testutil

import com.studyagent.client.core.network.AgentConnectionSnapshot
import com.studyagent.client.core.network.ConnectionTestResult
import kotlinx.coroutines.flow.emptyFlow
import com.studyagent.client.core.models.ClientMessage
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.ServerMessage
import com.studyagent.client.core.models.ServerProfile
import com.studyagent.client.data.repository.ConnectionRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections

/**
 * Programmable connection double (§12/§27).
 *
 * It implements the *transport contract* and nothing else — no session logic, no protocol
 * interpretation — so a test that drives it is really testing the client. Everything a flaky
 * network does is a switch here:
 *
 * - [failAllSends] / [failingTypes] — send failure (checked before the message is accepted).
 * - [sendDelayMs] — write latency, in virtual time.
 * - [loseConnection] / [restoreConnection] / [setReconnecting] — every [ConnectionState] the
 *   machine reacts to, including `Reconnecting`, so the reconnect-count behaviour is exercised.
 * - [deliver] — an inbound frame; call it twice to simulate a duplicate, out of order to simulate
 *   a reordered server, or after a disconnect to simulate a message that arrived too late.
 */
class FakeConnectionRepository(
    initialState: ConnectionState = ConnectionState.Connected("localhost", 8000),
    private val clock: () -> Long = System::currentTimeMillis
) : ConnectionRepository {

    private val _connectionState = MutableStateFlow(initialState)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _incoming = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 256)
    override val incomingMessages: Flow<ServerMessage> = _incoming

    private val _activeProfile = MutableStateFlow<ServerProfile?>(ServerProfile.defaultLocalProfile())
    override val activeProfile: Flow<ServerProfile?> = _activeProfile

    /** Every message the client handed to the transport, in order. */
    val sentMessages: MutableList<ClientMessage> = Collections.synchronizedList(mutableListOf())

    /** Send failures are simulated per type so a test can fail exactly one message. */
    val failingTypes: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    var failAllSends: Boolean = false

    /** Virtual write latency applied inside [send]. */
    var sendDelayMs: Long = 0L

    /**
     * Called for every message the client writes, after it is recorded in [sentMessages].
     *
     * This is how the harness connects the client to [FakeStudyServer] without the client knowing
     * anything about it: the transport contract stays "hand it a message", and the scripted peer
     * decides what comes back.
     */
    var onSendHook: ((ClientMessage) -> Unit)? = null

    /** Connections/disconnections requested through the repository API. */
    var connectCalls: Int = 0
        private set
    var disconnectCalls: Int = 0
        private set

    val isConnected: Boolean get() = _connectionState.value.let {
        it is ConnectionState.Connected || it is ConnectionState.Ready || it is ConnectionState.ReadyLegacy
    }

    override suspend fun connect(profile: ServerProfile?) {
        connectCalls++
        if (profile != null) _activeProfile.value = profile
        _connectionState.value = ConnectionState.Connected(
            host = profile?.host ?: "localhost",
            port = profile?.port ?: 8000,
            serverName = profile?.name
        )
    }

    override suspend fun disconnect(reason: String) {
        disconnectCalls++
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun send(message: ClientMessage): Boolean {
        sentMessages += message
        if (sendDelayMs > 0L) delay(sendDelayMs)
        if (failAllSends) return false
        if (message.type in failingTypes) return false
        if (!isConnected) return false
        onSendHook?.invoke(message)
        return true
    }

    override fun toggleFakeAgent(useFake: Boolean) = Unit

    // Members the ConnectionRepository interface gained in an earlier gate without this fake being
    // updated (pre-existing compile error in every harness-based study test, fixed in GATE 11).
    override val connectionSnapshot: StateFlow<AgentConnectionSnapshot> =
        MutableStateFlow(AgentConnectionSnapshot.disconnected())

    override suspend fun connectWithOverride(profile: ServerProfile?) = connect(profile)

    override fun testConnection(profile: ServerProfile): Flow<ConnectionTestResult> = emptyFlow()

    override fun getConnectionDiagnostics(): String = "fake-connection"

    // ------------------------------------------------------------------ scripted inbound

    /** Delivers one server frame. Returns false when the buffer is full. */
    fun deliver(message: ServerMessage): Boolean = _incoming.tryEmit(message)

    fun deliverAll(vararg messages: ServerMessage) {
        messages.forEach { deliver(it) }
    }

    /** Frames the client sent, filtered by wire type. */
    fun sentOfType(type: String): List<ClientMessage> =
        synchronized(sentMessages) { sentMessages.filter { it.type == type } }

    val sentCount: Int get() = synchronized(sentMessages) { sentMessages.size }

    fun clearSent() {
        synchronized(sentMessages) { sentMessages.clear() }
    }

    // ------------------------------------------------------------------ connection scripting

    fun loseConnection() {
        _connectionState.value = ConnectionState.Disconnected
    }

    fun setReconnecting(attempt: Int = 1, maxAttempts: Int = 5, nextRetryInMs: Long = 1_000L) {
        _connectionState.value = ConnectionState.Reconnecting(
            attempt = attempt,
            maxAttempts = maxAttempts,
            nextRetryInMs = nextRetryInMs,
            reason = "test"
        )
    }

    fun setError(message: String = "connection error") {
        _connectionState.value = ConnectionState.Error(message)
    }

    fun setServerUnavailable(message: String = "server unavailable") {
        _connectionState.value = ConnectionState.ServerUnavailable(message)
    }

    fun setNetworkUnavailable() {
        _connectionState.value = ConnectionState.NetworkUnavailable
    }

    fun restoreConnection(host: String = "localhost", port: Int = 8000) {
        _connectionState.value = ConnectionState.Connected(host, port, serverName = "test")
    }

    /** Restores via the state the real transport emits after a v2 handshake. */
    fun restoreReady(host: String = "localhost", port: Int = 8000) {
        _connectionState.value = ConnectionState.Ready(host, port, serverName = "test")
    }

    /** Restores via the state the real transport emits after a v1 handshake. */
    fun restoreReadyLegacy(host: String = "localhost", port: Int = 8000) {
        _connectionState.value = ConnectionState.ReadyLegacy(host, port, serverName = "test")
    }

    fun currentStateLabel(): String = _connectionState.value.label

    /** Wall-clock reading, exposed so a test can assert on "age of last message" style metrics. */
    fun now(): Long = clock()
}
