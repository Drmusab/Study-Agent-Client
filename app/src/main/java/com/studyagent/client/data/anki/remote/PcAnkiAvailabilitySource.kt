package com.studyagent.client.data.anki.remote

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.models.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * GATE 21D — PC Anki readiness: STRUCTURE ONLY.
 *
 * No PC Anki backend type is declared or constructed anywhere in this repository and the agent
 * protocol carries no Anki traffic (`ProtocolMessages.kt` has no Anki message). DECISION-21B
 * (handoff, 21D BLOCKERS) therefore allows exactly this: prepare how a PC backend's availability
 * would be produced, and **never fabricate a PC `Ready`**. Nothing in production constructs the types
 * below — an audit test pins that — so no PC state can reach the registry from this file today.
 *
 * What it settles, once, for whoever writes the backend (BACKEND_FAILURE_MATRIX §3.1/§10):
 *
 *   R0 transport reachable  →  R1 authenticated  →  protocol negotiated  →  R2 Anki beneath is ready
 *
 * Each rung implies only itself. In particular "the agent is connected and ready for AI/TTS" is
 * **not** "Anki on that PC is ready": the agent connection is keyed by the agent, and its protocol
 * capabilities (`ConnectionState.Ready.capabilities`) are *AI features*, never Anki capabilities
 * (INV-21B-18, INV-21D-09). Anki readiness enters only as an explicit [PcAnkiReadinessReport].
 *
 * Privacy: `ConnectionState` carries host, port and free-text reasons. None of it is read here —
 * only the *kind* of state — so no endpoint, credential or rejection text can reach availability,
 * diagnostics or a log (INV-21D-19).
 */

/**
 * What the PC agent has *reported* about the Anki application beneath it. Absent evidence is its own
 * value: not reported is not "not ready" (the agent never said so) and certainly not "ready".
 */
sealed interface PcAnkiReadinessReport {
    /** The agent has not said anything about Anki on this connection. */
    data object NotReported : PcAnkiReadinessReport

    /** The agent says the desktop Anki application is not running or not reachable. */
    data object AnkiUnavailable : PcAnkiReadinessReport

    /** The agent says Anki is running but no collection is open. */
    data object CollectionUnavailable : PcAnkiReadinessReport

    /**
     * The agent says Anki is usable, with the Anki capabilities **negotiated for this connection**.
     * These are the only Anki capabilities a PC backend may advertise — never the agent's AI
     * features, and never anything carried over from an earlier connection.
     */
    data class Ready(val negotiated: AnkiCapabilities) : PcAnkiReadinessReport
}

/**
 * The pure ladder: connection evidence + Anki report → [AnkiAvailability].
 *
 * Exhaustive over [ConnectionState] with no `else`: a new connection state forces a decision here at
 * compile time instead of being silently treated as ready (or as down). `Ready` is produced in
 * exactly one place — an authenticated, fully negotiated agent connection **and** an explicit
 * Anki-ready report — and in no other combination, whatever the transport is doing.
 */
object PcAnkiAvailabilityMapper {

    /** Fixed, content-free tokens: never the peer's own reason text. */
    const val PROTOCOL_INCOMPATIBLE = "pc_protocol_incompatible"
    const val PROTOCOL_LEGACY = "pc_protocol_legacy"

    fun availability(connection: ConnectionState, anki: PcAnkiReadinessReport): AnkiAvailability =
        when (connection) {
            is ConnectionState.Ready ->
                if (connection.authenticated) fromAnkiReport(anki) else AnkiAvailability.AgentAuthenticationRequired

            // A v1 agent (or the legacy alias with no auth/protocol evidence) cannot have negotiated an
            // Anki-capable protocol, and "Ready" may only follow negotiation (DECISION-21B-28).
            is ConnectionState.ReadyLegacy,
            is ConnectionState.Connected -> AnkiAvailability.Unsupported(PROTOCOL_LEGACY)

            is ConnectionState.AuthenticationFailed -> AnkiAvailability.AgentAuthenticationRequired
            is ConnectionState.ProtocolMismatch -> AnkiAvailability.Unsupported(PROTOCOL_INCOMPATIBLE)

            // In flight: not usable, not (yet) failed — and certainly not ready.
            is ConnectionState.Resolving,
            is ConnectionState.ConnectingTransport,
            is ConnectionState.Connecting,
            is ConnectionState.TransportConnected,
            is ConnectionState.Handshaking,
            is ConnectionState.Authenticating,
            is ConnectionState.NegotiatingCapabilities -> AnkiAvailability.Checking

            // No usable transport. A reconnect in progress is still "not usable now".
            is ConnectionState.Disconnected,
            is ConnectionState.NetworkUnavailable,
            is ConnectionState.ServerUnavailable,
            is ConnectionState.HandshakeTimeout,
            is ConnectionState.TlsFailure,
            is ConnectionState.AgentUnavailable,
            is ConnectionState.Reconnecting,
            is ConnectionState.Error -> AnkiAvailability.AgentDisconnected
        }

    private fun fromAnkiReport(report: PcAnkiReadinessReport): AnkiAvailability = when (report) {
        is PcAnkiReadinessReport.NotReported -> AnkiAvailability.Checking
        is PcAnkiReadinessReport.AnkiUnavailable -> AnkiAvailability.AgentAnkiUnavailable
        is PcAnkiReadinessReport.CollectionUnavailable -> AnkiAvailability.CollectionNotInitialized
        is PcAnkiReadinessReport.Ready -> AnkiAvailability.Ready(report.negotiated)
    }
}

/**
 * An opaque token naming one authenticated, negotiated agent connection. A report is only accepted
 * for the session it was observed on. A plain class on purpose: a data class would expose `copy()`
 * and let a caller forge a session it never observed.
 */
class PcAnkiConnectionSession internal constructor(internal val epoch: Long)

/**
 * Turns an *existing* agent connection state flow plus explicit Anki reports into availability.
 *
 * It **creates no connection and owns none**: it observes the `StateFlow` it is handed, so it cannot
 * open a second transport, authenticate, or log anything (VER-21D-26). It adds exactly one rule that a
 * pure mapper cannot: **evidence belongs to a connection session**.
 *
 * ```text
 * session 1:  Ready(agent A)  ── report(s1, Ready) ──►  availability Ready
 *             Disconnected                              AgentDisconnected   (report cleared)
 * session 2:  Ready(agent A)                            Checking            (nothing reported yet)
 *             report(s1, Ready)  → rejected             a late result from session 1 cannot revive it
 *             report(s2, Ready)  → accepted             Ready
 * ```
 *
 * So a reconnect never resurrects an earlier negotiation, a late result from before the reconnect
 * cannot overwrite the new session, and a change of agent, protocol or server version on a live
 * connection starts a new session too (a capability claim does not survive a version change).
 * "Reconnected" proves reachability and nothing about the collection: this class has no vocabulary
 * for collection continuity (INV-21D-14).
 */
class PcAnkiAvailabilitySource(
    connection: StateFlow<ConnectionState>,
    scope: CoroutineScope
) {
    /** What identifies one live session; deliberately excludes host, port and latency. */
    private data class SessionPrint(
        val agentId: String?,
        val protocolVersion: String,
        val serverVersion: String?
    )

    private data class Evidence(
        val connection: ConnectionState,
        val epoch: Long,
        val report: PcAnkiReadinessReport
    )

    private fun ConnectionState.sessionPrint(): SessionPrint? {
        val ready = this as? ConnectionState.Ready ?: return null
        return if (ready.authenticated) SessionPrint(ready.agentId, ready.protocolVersion, ready.serverVersion) else null
    }

    private val evidence = MutableStateFlow(
        Evidence(
            connection = connection.value,
            epoch = if (connection.value.sessionPrint() != null) 1L else 0L,
            report = PcAnkiReadinessReport.NotReported
        )
    )

    /** Availability of the PC Anki backend. Never `Ready` without an explicit, current report. */
    val availability: StateFlow<AnkiAvailability> = evidence
        .map { PcAnkiAvailabilityMapper.availability(it.connection, it.report) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = PcAnkiAvailabilityMapper.availability(evidence.value.connection, evidence.value.report)
        )

    init {
        scope.launch {
            connection.collect { next -> evidence.update { advance(it, next) } }
        }
    }

    /** The session a report observed *now* belongs to. */
    fun currentSession(): PcAnkiConnectionSession = PcAnkiConnectionSession(evidence.value.epoch)

    /**
     * Record what the agent said about Anki during [session]. Returns `false` — and changes nothing —
     * when that session is no longer the live one (disconnected, reconnected, or a different agent
     * or version), so a late or replayed answer can never overwrite newer evidence.
     */
    fun report(session: PcAnkiConnectionSession, report: PcAnkiReadinessReport): Boolean {
        while (true) {
            val current = evidence.value
            if (current.connection.sessionPrint() == null || session.epoch != current.epoch) return false
            if (current.report == report) return true
            if (evidence.compareAndSet(current, current.copy(report = report))) return true
        }
    }

    private fun advance(previous: Evidence, next: ConnectionState): Evidence {
        val nextPrint = next.sessionPrint()
        val continuing = nextPrint != null && previous.connection.sessionPrint() == nextPrint
        return Evidence(
            connection = next,
            // Entering a session (or changing its agent/protocol/server version) starts a new epoch.
            epoch = if (nextPrint != null && !continuing) previous.epoch + 1 else previous.epoch,
            // Leaving a session, or starting a new one, discards what the agent said before.
            report = if (continuing) previous.report else PcAnkiReadinessReport.NotReported
        )
    }
}
