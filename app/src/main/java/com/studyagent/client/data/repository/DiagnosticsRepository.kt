package com.studyagent.client.data.repository

import com.studyagent.client.core.anki.ReviewCommitLedger
import com.studyagent.client.core.anki.statusCode
import com.studyagent.client.core.audio.AudioDeviceInfoModel
import com.studyagent.client.core.audio.AudioRouteManager
import com.studyagent.client.core.audio.DefaultStudyAudioModeResolver
import com.studyagent.client.core.audio.AudioRouteSnapshot
import com.studyagent.client.core.audio.EffectiveStudyAudioRoute
import com.studyagent.client.core.audio.PhoneModeMetrics
import com.studyagent.client.core.audio.StudyAudioAttention
import com.studyagent.client.core.audio.StudyAudioMode
import com.studyagent.client.core.audio.StudyAudioRouteCoordinator
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.LogEntry
import com.studyagent.client.core.common.SystemAppClock
import com.studyagent.client.core.diagnostics.AppDiagnostics
import com.studyagent.client.core.diagnostics.AppPerformanceMetrics
import com.studyagent.client.core.diagnostics.DiagnosticEvent
import com.studyagent.client.core.diagnostics.DiagnosticTimeline
import com.studyagent.client.core.diagnostics.DiagnosticsFormatting
import com.studyagent.client.core.diagnostics.PerformanceMetrics
import com.studyagent.client.core.diagnostics.PerformanceSnapshot
import com.studyagent.client.core.diagnostics.PersistenceDiagnostics
import com.studyagent.client.core.diagnostics.PersistenceDiagnosticsSnapshot
import com.studyagent.client.core.models.AgentCapabilities
import com.studyagent.client.core.models.AgentCapability
import com.studyagent.client.core.models.AppSettings
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.network.NetworkStats
import com.studyagent.client.core.network.NetworkStatsRegistry
import com.studyagent.client.core.network.NetworkStatsSnapshot
import com.studyagent.client.core.study.MachineResourceCounts
import com.studyagent.client.core.study.SessionDiagnosticsSnapshot
import com.studyagent.client.core.voice.stt.RecognitionCapabilities
import com.studyagent.client.core.voice.stt.RecognitionHealthSnapshot
import com.studyagent.client.core.voice.stt.SpeechRecognitionOrchestrator
import com.studyagent.client.core.voice.tts.SpeechOrchestrator
import com.studyagent.client.core.voice.tts.TtsHealthSnapshot
import com.studyagent.client.data.anki.AnkiLibraryRepository
import com.studyagent.client.data.anki.ankidroid.AnkiDroidBackend
import com.studyagent.client.data.anki.ankidroid.AnkiDroidGateway
import com.studyagent.client.data.anki.ankidroid.AnkiDroidHealthRepository
import com.studyagent.client.data.anki.ankidroid.AnkiDroidProviderSpecSource
import com.studyagent.client.data.anki.ankidroid.CapabilitySupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Identity of the build the diagnostics came from (§84).
 *
 * No hardware serial, no advertising id, no account: version strings and a device *model* are
 * what make a report reproducible without making it identifying.
 */
data class DiagnosticsAppInfo(
    val appVersion: String = "unknown",
    val buildType: String = "unknown",
    val androidVersion: String = "unknown",
    val deviceModel: String = "unknown",
    val protocolVersion: String = "unknown",
    val serverVersion: String? = null
)

/**
 * Read-only diagnostics surface (§58).
 *
 * The repository is a *view*, not an owner: every value is read from the subsystem that owns it.
 * New sections are added as rows, so the Compose layer renders them generically and a section can
 * be added without touching the UI (§158 keeps filtering simple for the same reason).
 *
 * Privacy contract for everything reachable from here:
 * no transcript, no question, no answer, no token, no header, no raw frame — identifiers are
 * abbreviated, and every counter is local and bounded.
 */
interface DiagnosticsRepository {
    val logs: StateFlow<List<LogEntry>>
    val connectionState: StateFlow<ConnectionState>
    val activeOutputDevice: StateFlow<AudioDeviceInfoModel>
    val isHeadsetConnected: StateFlow<Boolean>
    val ttsHealth: StateFlow<TtsHealthSnapshot>
    val recognitionHealth: StateFlow<RecognitionHealthSnapshot>

    /** Effective study audio route — preference vs. what is actually happening (§67/§68). */
    val studyAudioRoute: StateFlow<EffectiveStudyAudioRoute>
    val audioRouteAttention: StateFlow<StudyAudioAttention?>

    /** Study-audio rows, shown alongside the recognition rows (§67). */
    fun studyAudioDiagnosticsRows(): List<Pair<String, String>>

    /** Local Phone Mode counters — counts and timings only, never transcripts (§110/§111). */
    fun phoneModeMetrics(): PhoneModeMetrics

    fun clearLogs()
    fun getFormattedLogsText(): String

    /** Rendered rows for the STT diagnostics section (§89). */
    fun recognitionDiagnosticsRows(): List<Pair<String, String>>

    // ------------------------------------------------------------------ expanded snapshot (§58)

    /** Session identity, turn, pending action, recovery state (§59). */
    fun sessionDiagnosticsRows(): List<Pair<String, String>> = emptyList()

    /** Connection, transport, reconnect, ping and message counters (§60). */
    fun networkDiagnosticsRows(): List<Pair<String, String>> = emptyList()

    /** Negotiated capability, server identity, last protocol error (§61/§62/§63). */
    fun protocolDiagnosticsRows(): List<Pair<String, String>> = emptyList()

    /** Bounded latency percentiles, failure rates and queue depths (§65/§162-§165). */
    fun performanceDiagnosticsRows(): List<Pair<String, String>> = emptyList()

    /** Compact, one-line-per-family performance summary used by the export and the study screen. */
    fun performanceSummaryLines(): List<String> = emptyList()

    /** Dashboard capability, snapshot freshness, deck count, cache age (§62). */
    fun dashboardDiagnosticsRows(): List<Pair<String, String>> = emptyList()

    /** Study-config capability, draft state, save state (§63). */
    fun controlDiagnosticsRows(): List<Pair<String, String>> = emptyList()

    /** Settings schema, write errors, cache ages, draft presence (§64). */
    fun persistenceDiagnosticsRows(): List<Pair<String, String>> = emptyList()

    /**
     * GATE 02 — AnkiDroid integration section (§35/§100): authority, package, provider spec,
     * permission, collection readiness, last check time, duration and the last failure code.
     *
     * Technical by design — this is the section an engineer reads — while remaining content-free:
     * no card, deck or note data exists here to leak (§68/§69/§104).
     */
    fun ankiDroidDiagnosticsRows(): List<Pair<String, String>> = emptyList()

    /** Bounded structured timeline, oldest first (§67/§69). */
    fun timelineEvents(limit: Int = DiagnosticTimeline.DEFAULT_EXPORT_LIMIT): List<DiagnosticEvent> = emptyList()

    /** Clears the session-local timeline. Lifetime counters are deliberately untouched (§160/§161). */
    fun clearTimeline() {}

    /** Full export: header + every section + bounded timeline + sanitized logs (§157). */
    fun getExportText(): String = getFormattedLogsText()

    /** Short "copy summary" variant: no log rows, no timeline spam — shareable in one paste (§88). */
    fun getSummaryText(): String = ""

    /** Structured performance snapshot for callers that want numbers, not rows. */
    fun performanceSnapshot(): PerformanceSnapshot = AppPerformanceMetrics.metrics.snapshot()
}

class DefaultDiagnosticsRepository(
    private val connectionRepository: ConnectionRepository,
    private val audioRouteManager: AudioRouteManager,
    speechOrchestrator: SpeechOrchestrator,
    recognitionOrchestrator: SpeechRecognitionOrchestrator,
    private val studyAudioRouteCoordinator: StudyAudioRouteCoordinator? = null,
    settingsFlow: Flow<AppSettings>? = null,
    scope: CoroutineScope? = null,
    // ---- added for the expanded diagnostics snapshot (§58-§65) ----
    private val performanceMetrics: PerformanceMetrics = AppPerformanceMetrics.metrics,
    private val timeline: DiagnosticTimeline = AppDiagnostics.timeline,
    private val networkStats: NetworkStats = NetworkStatsRegistry.current,
    private val capabilityStore: CapabilityStore? = null,
    private val sessionDiagnostics: (() -> SessionDiagnosticsSnapshot?)? = null,
    private val machineResources: (() -> MachineResourceCounts?)? = null,
    private val dashboardRepository: DashboardRepository? = null,
    private val studyControlRepository: StudyControlRepository? = null,
    private val persistenceDiagnostics: PersistenceDiagnostics? = null,
    private val persistenceSnapshot: (() -> PersistenceDiagnosticsSnapshot?)? = null,
    private val appInfo: () -> DiagnosticsAppInfo = { DiagnosticsAppInfo() },
    private val clock: AppClock = SystemAppClock,
    // ---- GATE 02: AnkiDroid integration health (§35/§100) ----
    private val ankiDroidHealthRepository: AnkiDroidHealthRepository? = null,
    // ---- GATE 04: gateway + backend for capability matrix (§64/§65/§121) ----
    private val ankiDroidGateway: AnkiDroidGateway? = null,
    private val ankiDroidBackend: AnkiDroidBackend? = null,
    private val ankiLibraryRepository: AnkiLibraryRepository? = null,
    private val reviewCommitLedger: ReviewCommitLedger? = null
) : DiagnosticsRepository {

    override val logs: StateFlow<List<LogEntry>> = AppLogger.logsFlow
    override val connectionState: StateFlow<ConnectionState> = connectionRepository.connectionState
    override val activeOutputDevice: StateFlow<AudioDeviceInfoModel> = audioRouteManager.activeOutputDevice
    override val isHeadsetConnected: StateFlow<Boolean> = audioRouteManager.isHeadsetConnected
    override val ttsHealth: StateFlow<TtsHealthSnapshot> = speechOrchestrator.health
    override val recognitionHealth: StateFlow<RecognitionHealthSnapshot> = recognitionOrchestrator.health

    override val studyAudioRoute: StateFlow<EffectiveStudyAudioRoute> =
        studyAudioRouteCoordinator?.effectiveRoute ?: MutableStateFlow(phoneOnlyRoute)
    override val audioRouteAttention: StateFlow<StudyAudioAttention?> =
        studyAudioRouteCoordinator?.attention ?: MutableStateFlow(null)

    /** Latest settings, mirrored for the diagnostics rows that are settings-derived (§67). */
    private val latestSettings = MutableStateFlow(AppSettings())

    init {
        val source = settingsFlow
        val collectorScope = scope
        if (source != null && collectorScope != null) {
            collectorScope.launch { source.collect { latestSettings.value = it } }
        }
        // Warm the durable health check off the UI thread; row rendering never blocks on disk.
        if (collectorScope != null) {
            reviewCommitLedger?.let { ledger -> collectorScope.launch { ledger.health() } }
        }
    }

    override fun studyAudioDiagnosticsRows(): List<Pair<String, String>> =
        (studyAudioRouteCoordinator?.diagnosticsRows() ?: emptyList()) + listOf(
            // Interaction mode, not a route: hands-free is available on both routes.
            "Hands-free study" to if (latestSettings.value.handsFreeMode) "On" else "Off",
            "Auto-play question" to if (latestSettings.value.autoPlayQuestion) "On" else "Off",
            "Listen for spoken rating" to if (latestSettings.value.listenForSpokenRating) "On" else "Off"
        )

    override fun phoneModeMetrics(): PhoneModeMetrics =
        studyAudioRouteCoordinator?.metrics?.metrics?.value ?: PhoneModeMetrics()

    override fun clearLogs() {
        AppLogger.clear()
    }

    /** §160: logs and the session-local timeline are cleared together, and it says so. */
    override fun clearTimeline() {
        timeline.clear()
    }

    override fun timelineEvents(limit: Int): List<DiagnosticEvent> = timeline.recent(limit)

    private companion object {
        /** What the app assumes before any device enumeration: a bare, working phone (§71). */
        val phoneOnlyRoute: EffectiveStudyAudioRoute =
            DefaultStudyAudioModeResolver().resolve(
                StudyAudioMode.AUTO,
                AudioRouteSnapshot.PHONE_ONLY
            )
    }

    // ------------------------------------------------------------------ session (§59)

    override fun sessionDiagnosticsRows(): List<Pair<String, String>> {
        val session = sessionDiagnostics?.invoke()
        val resources = machineResources?.invoke()
        if (session == null) {
            return listOf(
                "Session" to if (resources == null) DiagnosticsFormatting.UNKNOWN else "Idle",
                "Session diagnostics" to "unavailable in this build"
            )
        }
        return buildList {
            add("Session" to DiagnosticsFormatting.abbreviate(session.sessionId))
            add("Epoch" to session.epoch.toString())
            add("Phase" to session.phase)
            add("Card" to DiagnosticsFormatting.abbreviate(session.currentCardId))
            add(
                "Turn" to buildString {
                    append("gen=").append(session.turnGeneration ?: "-")
                    append(" id=").append(DiagnosticsFormatting.abbreviate(session.turnId))
                }
            )
            add(
                "Pending action" to (
                    session.pendingAction?.let { action ->
                        if (session.pendingActionAgeMs >= 0L) {
                            "$action (${session.pendingActionAgeMs}ms old)"
                        } else {
                            action
                        }
                    } ?: "None"
                )
            )
            add("Paused" to DiagnosticsFormatting.boolean(session.isPaused))
            add("Recovering" to DiagnosticsFormatting.boolean(session.isRecovering))
            add("Last accepted event" to (session.lastAcceptedEvent ?: DiagnosticsFormatting.NOT_MEASURED))
            add("Last rejected event" to (session.lastRejectedEvent ?: "None"))
            session.lastRejectedReason?.let { add("Rejection reason" to it) }
            add(
                "Submissions" to "ledger=${session.ledgerSize} " +
                    "answers=${session.inFlightAnswers} ratings=${session.inFlightRatings}"
            )
            add("Dedup window" to session.recentMessageIdsCount.toString())
            add(
                "Bounded histories" to "transitions=${session.historySize} turns=${session.cardTurnHistorySize}"
            )
            add("Pending transcript" to DiagnosticsFormatting.boolean(session.hasPendingTranscript))
            add(
                "Pending rating confirmation" to
                    DiagnosticsFormatting.boolean(session.hasPendingRatingConfirmation)
            )
            session.errorCode?.let { add("Session error" to "$it recoverable=${session.errorRecoverable}") }
            if (session.finishingIsLocalOnly) add("Session end" to "local only (server unreachable)")
            resources?.let { res ->
                add("Pending timers" to res.pendingTimers.toString())
                add("Events awaiting processing" to res.eventsAwaitingProcessing.toString())
                add("Active speech / STT" to "${res.activeSpeechEffects} / ${res.activeRecognitionEffects}")
            }
        }
    }

    // ------------------------------------------------------------------ network (§60/§61)

    override fun networkDiagnosticsRows(): List<Pair<String, String>> {
        val stats: NetworkStatsSnapshot = networkStats.snapshot()
        val connection = connectionState.value
        return buildList {
            add("Connection" to connection.label)
            add("Phase" to connection.phaseName)
            add("Profile" to (stats.profileName ?: DiagnosticsFormatting.UNKNOWN))
            add("Transport" to stats.transport)
            add(
                "Endpoint" to if (stats.host.isNullOrBlank()) {
                    DiagnosticsFormatting.UNKNOWN
                } else {
                    "${stats.host}:${stats.port}"
                }
            )
            add("Generation" to if (stats.generation >= 0) stats.generation.toString() else DiagnosticsFormatting.NOT_MEASURED)
            add("Network type" to (stats.networkType ?: DiagnosticsFormatting.UNKNOWN))
            add(
                "Reconnect attempt" to if (stats.maxReconnectAttempts > 0) {
                    "${stats.reconnectAttempt}/${stats.maxReconnectAttempts}"
                } else {
                    "None"
                }
            )
            add("Reconnects" to stats.reconnectCount.toString())
            add("DNS latency" to DiagnosticsFormatting.millis(stats.dnsLatencyMs))
            add("Connect latency" to DiagnosticsFormatting.millis(stats.connectLatencyMs))
            add("WS upgrade latency" to DiagnosticsFormatting.millis(stats.wsUpgradeLatencyMs))
            add("Handshake latency" to DiagnosticsFormatting.millis(stats.handshakeLatencyMs))
            add("Auth latency" to DiagnosticsFormatting.millis(stats.authLatencyMs))
            add("Ready latency" to DiagnosticsFormatting.millis(stats.readyLatencyMs))
            add(
                "Ping interval" to if (stats.pingIntervalSeconds > 0L) {
                    "${stats.pingIntervalSeconds}s"
                } else {
                    DiagnosticsFormatting.NOT_MEASURED
                }
            )
            add("Last ping RTT (correlated)" to DiagnosticsFormatting.millis(stats.lastPingRttMs))
            add("Last message received" to DiagnosticsFormatting.ageMs(stats.lastMessageAgeMs))
            add("Last message type" to (stats.lastMessageType ?: DiagnosticsFormatting.NOT_MEASURED))
            add("Messages sent" to stats.messagesSent.toString())
            add("Messages received" to stats.messagesReceived.toString())
            add("Send failures" to stats.sendFailures.toString())
            add(
                "Traffic" to if (stats.messagesPerMinute < 0.0) {
                    DiagnosticsFormatting.NOT_MEASURED
                } else {
                    String.format(Locale.US, "%.1f msg/min", stats.messagesPerMinute)
                }
            )
            add("Problem" to (stats.problem ?: "None"))
            add("Last protocol error" to (stats.lastProtocolError ?: "None"))
            if (stats.requestRttMs.isNotEmpty()) {
                add("Request RTTs" to stats.requestRttMs.entries.joinToString(", ") { "${it.key}=${it.value}ms" })
            }
        }
    }

    override fun protocolDiagnosticsRows(): List<Pair<String, String>> {
        val caps: AgentCapabilities? = capabilityStore?.capabilities?.value
        val stats = networkStats.snapshot()
        return buildList {
            add("Protocol version" to (stats.protocolVersion ?: caps?.protocolVersion ?: DiagnosticsFormatting.UNKNOWN))
            add("Negotiation" to (caps?.status?.name ?: DiagnosticsFormatting.UNKNOWN))
            add("Server name" to (stats.serverName ?: caps?.serverName ?: DiagnosticsFormatting.NOT_MEASURED))
            add("Server version" to (stats.serverVersion ?: caps?.serverVersion ?: DiagnosticsFormatting.NOT_MEASURED))
            add("Agent ID" to (stats.agentId ?: caps?.agentId ?: DiagnosticsFormatting.NOT_MEASURED))
            add("Authenticated" to DiagnosticsFormatting.boolean(stats.authenticated))
            add(
                "Capabilities" to if (caps == null && stats.capabilities.isEmpty()) {
                    DiagnosticsFormatting.UNKNOWN
                } else {
                    val allCaps = if (stats.capabilities.isNotEmpty()) stats.capabilities else caps?.capabilities ?: emptySet()
                    if (allCaps.isEmpty()) "None advertised" else allCaps.sorted().joinToString(", ")
                }
            )
            add("Last server message" to (stats.lastMessageType ?: DiagnosticsFormatting.NOT_MEASURED))
            add("Last protocol error" to (stats.lastProtocolError ?: "None"))
            add("Raw frames" to "not stored (privacy)")
        }
    }

    // ------------------------------------------------------------------ performance (§65)

    override fun performanceDiagnosticsRows(): List<Pair<String, String>> {
        val snapshot = performanceMetrics.snapshot()
        val session = snapshot.session
        val resources = machineResources?.invoke()
        return buildList {
            add("Session turns" to session.turns.toString())
            add("Session duration" to formatDuration(session.sessionDurationMs))
            add("Question → speech" to session.questionToSpeechStart.format())
            add("Speech → listen" to session.speechDoneToListen.format())
            add("STT finalize" to session.sttFinalize.format())
            add("Evaluation RTT" to session.evaluationRoundTrip.format())
            add("Rating → next" to session.ratingToNextQuestion.format())
            add("Start → request" to session.startToRequest.format())

            val attempted = snapshot.tts.totalAttempted
            add(
                "TTS failures" to DiagnosticsFormatting.rate(snapshot.tts.requestsFailed, attempted)
            )
            add(
                "STT no-speech" to DiagnosticsFormatting.rate(snapshot.stt.noSpeech, snapshot.stt.turnsCompleted + snapshot.stt.turnsFailed)
            )
            add(
                "STT no-match" to DiagnosticsFormatting.rate(snapshot.stt.noMatch, snapshot.stt.turnsCompleted + snapshot.stt.turnsFailed)
            )
            add(
                "Recognizer busy" to DiagnosticsFormatting.rate(snapshot.stt.busy, snapshot.stt.turnsCompleted + snapshot.stt.turnsFailed)
            )
            add(
                "Route interruptions" to DiagnosticsFormatting.rate(snapshot.audio.routeInterruptions, session.turns.coerceAtLeast(1L))
            )
            add(
                "Suspected self-echo" to snapshot.audio.suspectedSelfEcho.toString()
            )
            add(
                "Listen route" to "phone=${snapshot.audio.phoneTurns} headset=${snapshot.audio.headsetTurns}"
            )

            add("TTS queue" to snapshot.tts.queueDepth.toString())
            add(
                "STT" to if (snapshot.stt.activeRequests > 0) "active (${snapshot.stt.activeRequests})" else "idle"
            )
            add(
                "Pending session effects" to (resources?.eventsAwaitingProcessing?.toString() ?: DiagnosticsFormatting.UNKNOWN)
            )
            add("Pending timers" to (resources?.pendingTimers?.toString() ?: DiagnosticsFormatting.UNKNOWN))

            add("Heap used" to formatBytes(snapshot.memory.usedHeapBytes))
            add("Heap limit" to formatBytes(snapshot.memory.maxHeapBytes))
            add("Peak heap observed" to formatBytes(snapshot.memory.peakUsedHeapBytes))
            // PSS is reported only when the platform gave it to us; otherwise it is "Unknown",
            // never a fabricated number (§65/§66).
            add("PSS" to formatBytes(snapshot.memory.pssBytes))
        }
    }

    override fun performanceSummaryLines(): List<String> {
        val snapshot = performanceMetrics.snapshot()
        val session = snapshot.session
        return listOf(
            "turns=${session.turns} duration=${formatDuration(session.sessionDurationMs)}",
            "question→speech ${session.questionToSpeechStart.p50Label()}  " +
                "speech→listen ${session.speechDoneToListen.p50Label()}  " +
                "stt-finalize ${session.sttFinalize.p50Label()}",
            "evaluation ${session.evaluationRoundTrip.p50Label()}  " +
                "rating→next ${session.ratingToNextQuestion.p50Label()}",
            "tts fail ${DiagnosticsFormatting.rate(snapshot.tts.requestsFailed, snapshot.tts.totalAttempted)}  " +
                "stt fail ${DiagnosticsFormatting.rate(snapshot.stt.turnsFailed, snapshot.stt.turnsCompleted + snapshot.stt.turnsFailed)}"
        )
    }

    override fun performanceSnapshot(): PerformanceSnapshot = performanceMetrics.snapshot()

    // ------------------------------------------------------------------ dashboard / control / persistence

    override fun dashboardDiagnosticsRows(): List<Pair<String, String>> {
        val capabilities = capabilityStore?.capabilities?.value
        val data = dashboardRepository?.data?.value
        return buildList {
            add(
                "Dashboard capability" to if (capabilities == null) {
                    DiagnosticsFormatting.UNKNOWN
                } else {
                    DiagnosticsFormatting.boolean(
                        capabilities.isResolved && capabilities.supports(AgentCapability.DASHBOARD)
                    )
                }
            )
            if (data == null) {
                add("Snapshot" to DiagnosticsFormatting.UNKNOWN)
                return@buildList
            }
            add(
                "Snapshot" to when {
                    data.snapshot == null -> "None"
                    data.snapshotFromCache -> "Cached"
                    else -> "Live"
                }
            )
            add(
                "Snapshot age" to DiagnosticsFormatting.ageMs(
                    data.snapshotUpdatedAtMs?.let { (clock.nowMillis() - it).coerceAtLeast(0L) }
                )
            )
            add("Decks loaded" to data.decks.size.toString())
            add("Decks age" to DiagnosticsFormatting.ageMs(
                data.decksUpdatedAtMs?.let { (clock.nowMillis() - it).coerceAtLeast(0L) }
            ))
            add("Refreshing" to DiagnosticsFormatting.boolean(data.isRefreshing))
            add("Live session" to if (data.activeSession != null) "Yes" else "No")
            add("Last refresh error" to (data.error?.let { it::class.simpleName } ?: "None"))
        }
    }

    override fun controlDiagnosticsRows(): List<Pair<String, String>> {
        val capabilities = capabilityStore?.capabilities?.value
        val repository = studyControlRepository
        return buildList {
            add(
                "Study-config capability" to if (capabilities == null) {
                    DiagnosticsFormatting.UNKNOWN
                } else {
                    DiagnosticsFormatting.boolean(
                        capabilities.isResolved && capabilities.supports(AgentCapability.STUDY_CONFIG)
                    )
                }
            )
            if (repository == null) {
                add("Control Center" to DiagnosticsFormatting.UNKNOWN)
                return@buildList
            }
            add("Server config" to if (repository.serverConfig.value == null) "Unknown" else "Known")
            add("Draft" to if (repository.draft.value == null) "None" else "Dirty")
            add(
                "Save state" to when (val state = repository.saveState.value) {
                    is ConfigSaveState.Idle -> "Idle"
                    is ConfigSaveState.Saving -> "Saving"
                    is ConfigSaveState.Saved -> if (state.localOnly) "Saved (local)" else "Saved"
                    is ConfigSaveState.Error -> if (state.timedOut) "Timed out" else "Rejected"
                }
            )
            // The draft/config JSON itself is never rendered (§63).
        }
    }

    /**
     * AnkiDroid integration (GATE 02 + GATE 04).
     * Every value is either observed or explicitly unknown —
     * "Unknown" is rendered rather than a plausible-looking default (§100: only expose values
     * actually known).
     *
     * GATE 04 adds:
     * - backend id, gateway state, capability matrix
     * - API support vs Study-Agent implementation status (§64/§65/§121)
     */
    private fun commitLedgerRows(): List<Pair<String, String>> = reviewCommitLedger?.diagnosticsSnapshot()?.let { data ->
        listOf(
            "Ledger health" to data.health,
            "Ledger records" to (data.records?.toString() ?: "Not checked"),
            "Ledger submitting" to (data.submitting?.toString() ?: "Not checked"),
            "Ledger ambiguous" to (data.ambiguous?.toString() ?: "Not checked"),
            "Ledger committed" to (data.committed?.toString() ?: "Not checked"),
            "Ledger safe failures" to (data.safeFailures?.toString() ?: "Not checked"),
            "Ledger interrupted on restore" to (data.interruptedOnRestore?.toString() ?: "Not checked"),
            "Ledger last write failed" to if (data.lastWriteFailed) "Yes" else "No",
            "Commit attempts" to data.attemptTotal.toString(),
            "Commit successes" to data.successTotal.toString(),
            "Commit safe failures" to data.safeFailureTotal.toString(),
            "Commit ambiguous outcomes" to data.ambiguousTotal.toString(),
            "Commit conflicts" to data.conflictTotal.toString(),
            "Commit duplicate rejected" to data.duplicateRejectedTotal.toString(),
            "Commit recoveries" to data.recoveryTotal.toString(),
            "Reconciliation unresolved" to data.reconciliationUnresolvedTotal.toString(),
            "Exactly-once claim" to "Not claimed"
        )
    } ?: emptyList()

    override fun ankiDroidDiagnosticsRows(): List<Pair<String, String>> {
        val repository = ankiDroidHealthRepository
        val gateway = ankiDroidGateway
        val backend = ankiDroidBackend

        // If neither is wired, report not wired
        if (repository == null && gateway == null) {
            return listOf("Integration" to "not wired in this build") + commitLedgerRows()
        }

        // Prefer gateway state when available (GATE 04 single source of truth, §45)
        val integrationState = gateway?.currentState()
        val snapshot = repository?.health?.value
        val detection = integrationState?.healthSnapshot?.detection ?: snapshot?.detection

        if (detection == null && integrationState == null) {
            return listOf("Integration" to "no data yet",
                "Commit guarantee" to (backend?.commitSemantics?.guaranteeLevel?.name ?: "Not wired / unverified"),
                "Idempotent replay" to if (backend?.commitSemantics?.supportsIdempotentReplay == true) "Verified" else "No / unverified",
                "Authoritative reconciliation" to if (backend?.commitSemantics?.supportsAuthoritativeReconciliation == true)
                    "Verified" else "No commit-correlated evidence") + commitLedgerRows()
        }

        val facts = detection?.providerFacts
        val failure = detection?.failure ?: integrationState?.healthSnapshot?.detection?.failure

        val checkedAtMs = integrationState?.lastCheckAtMs ?: snapshot?.checkedAtEpochMs ?: 0L
        val ageMs = if (checkedAtMs > 0) clock.nowMillis() - checkedAtMs else -1L

        val spec = integrationState?.metadata?.providerSpec ?: detection?.providerSpec
        val specText = when {
            spec == null -> DiagnosticsFormatting.UNKNOWN
            facts?.providerSpecSource == AnkiDroidProviderSpecSource.METADATA -> "$spec (published)"
            integrationState?.metadata?.providerSpecSource == AnkiDroidProviderSpecSource.METADATA -> "$spec (published)"
            else -> "$spec (implicit fallback: no metadata)"
        }

        val permissionGranted = integrationState?.metadata?.permissionGranted ?: detection?.permissionGranted
        val permissionText = when (permissionGranted) {
            null -> DiagnosticsFormatting.UNKNOWN
            else -> {
                val level = detection?.permissionProtectionLevel
                if (level == null) DiagnosticsFormatting.boolean(permissionGranted)
                else "${DiagnosticsFormatting.boolean(permissionGranted)} (protectionLevel=$level)"
            }
        }

        val rows = mutableListOf<Pair<String, String>>()

        // Basic health (GATE 02)
        rows.add("Status" to (detection?.availability?.statusCode ?: integrationState?.availability?.statusCode ?: DiagnosticsFormatting.UNKNOWN))
        rows.add("Backend" to (backend?.id?.stableId ?: "ankidroid_local"))
        val semantics = backend?.commitSemantics
        rows.add("Commit guarantee" to when (semantics?.guaranteeLevel) {
            com.studyagent.client.core.anki.CommitGuaranteeLevel.AT_MOST_ONCE_FAIL_CLOSED -> "At-most-once / fail-closed"
            com.studyagent.client.core.anki.CommitGuaranteeLevel.IDEMPOTENT_REPLAY_SUPPORTED -> "Idempotent replay"
            com.studyagent.client.core.anki.CommitGuaranteeLevel.END_TO_END_EXACTLY_ONCE -> "End-to-end exactly-once"
            com.studyagent.client.core.anki.CommitGuaranteeLevel.LOCAL_DEDUP_ONLY -> "Local dedup only"
            null -> "Not wired / unverified"
        })
        rows.add("Idempotent replay" to if (semantics?.supportsIdempotentReplay == true) "Verified" else "No / unverified")
        rows.add("Authoritative reconciliation" to if (semantics?.supportsAuthoritativeReconciliation == true)
            "Verified" else "No commit-correlated evidence")
        rows.add("Commit receipt" to (semantics?.commitReceiptKind?.name ?: "Not wired"))
        rows.addAll(commitLedgerRows())
        rows.add("Endpoint" to (detection?.endpointLabel ?: integrationState?.metadata?.endpointLabel ?: DiagnosticsFormatting.NOT_MEASURED))
        rows.add("Authority" to (detection?.authority ?: integrationState?.metadata?.authority ?: DiagnosticsFormatting.NOT_MEASURED))
        rows.add("Authorities checked" to if (detection?.checkedAuthorities?.isEmpty() == false) detection.checkedAuthorities.joinToString(", ") else integrationState?.metadata?.checkedAuthorities?.joinToString(", ") ?: DiagnosticsFormatting.NOT_MEASURED)
        rows.add("Package" to (detection?.packageName ?: integrationState?.metadata?.packageName ?: DiagnosticsFormatting.UNKNOWN))
        rows.add("Package version" to (integrationState?.metadata?.packageVersion ?: DiagnosticsFormatting.UNKNOWN))
        rows.add("Provider" to when {
            facts == null && integrationState?.metadata == null -> DiagnosticsFormatting.UNKNOWN
            (facts?.packageMatchesExpected == true && facts.enabled) || (integrationState?.metadata?.providerReachable == true) -> "available"
            else -> "unavailable"
        })
        rows.add("Provider package expected" to DiagnosticsFormatting.boolean(facts?.packageMatchesExpected))
        rows.add("Provider spec" to specText)
        rows.add("Permission" to permissionText)
        rows.add("Collection usable" to DiagnosticsFormatting.boolean(detection?.collectionReady ?: integrationState?.metadata?.collectionReady))
        rows.add("Last check" to DiagnosticsFormatting.ageMs(if (ageMs >= 0) ageMs else null))
        rows.add("Check duration" to DiagnosticsFormatting.millis(integrationState?.latencyMs ?: snapshot?.durationMs))
        rows.add("Last failure code" to (failure?.technicalLabel ?: integrationState?.lastError?.let { it::class.simpleName } ?: "None"))
        rows.add("Last failure exception" to (failure?.exceptionClass ?: DiagnosticsFormatting.NOT_MEASURED))
        rows.add("Probe" to "selected_deck (1 row, read-only)")

        // GATE 04: capability matrix (§121)
        val apiReport = integrationState?.apiCapabilities
        if (apiReport != null) {
            rows.add("--- API Capabilities (provider) ---" to "")
            rows.add("Deck Listing API" to apiReport.deckListing.name)
            rows.add("Deck Counts API" to apiReport.deckCounts.name)
            rows.add("Scheduled Review API" to apiReport.scheduledReview.name)
            rows.add("Rendered Card API" to apiReport.renderedCards.name)
            rows.add("Simple Text API" to apiReport.simpleCardText.name)
            rows.add("Next Intervals API" to apiReport.nextReviewIntervals.name)
            rows.add("Rating Mutation API" to apiReport.ratingCommit.name)
            rows.add("Flags API" to apiReport.flags.name)
            rows.add("Bury API" to apiReport.bury.name)
            rows.add("Suspend API" to apiReport.suspend.name)
            rows.add("Note Read API" to apiReport.noteRead.name)
            rows.add("Note Edit API" to apiReport.noteEdit.name)
            rows.add("Note Create API" to apiReport.noteCreate.name)
            rows.add("Media Read API" to apiReport.mediaRead.name)
            rows.add("Media Write API" to apiReport.mediaWrite.name)
            rows.add("Search API" to apiReport.search.name)
        }

        val capabilities = integrationState?.capabilities ?: detection?.capabilities
        if (capabilities != null) {
            rows.add("--- Study-Agent Implementation ---" to "")
            rows.add("Deck Listing" to if (capabilities.deckListing) "Implemented" else "Pending")
            rows.add("Deck Counts" to if (capabilities.deckCounts) "Verified" else "Mapped, not verified")
            // GATE 06 split the review row in two on purpose: the scheduler can be asked for the
            // next card, and a rating still cannot be written. One row would have had to call one
            // of those "implemented" when it is not (§75/§147).
            rows.add("Scheduled Review" to if (capabilities.scheduledReview) "Implemented" else "Pending GATE 06")
            rows.add("Rating Commit" to if (capabilities.review) "Implemented" else "Pending GATE 11")
            rows.add("Rendered Cards" to if (capabilities.renderedCards) "Implemented" else "Pending GATE 07")
            rows.add("Review Intervals" to if (capabilities.reviewIntervals) "Implemented" else "Pending GATE 06")
            rows.add("Media" to if (capabilities.media) "Implemented" else "Pending GATE 09")
            rows.add("Flags" to if (capabilities.flags) "Implemented" else "Pending GATE 11")
            rows.add("Bury" to if (capabilities.bury) "Implemented" else "Pending GATE 11")
            rows.add("Suspend" to if (capabilities.suspendCards) "Implemented" else "Pending GATE 11")
            rows.add("Edit Notes" to if (capabilities.editNotes) "Implemented" else "Pending GATE 17")
            rows.add("Create Notes" to if (capabilities.createNotes) "Implemented" else "Pending GATE 17")
            rows.add("Search" to if (capabilities.search) "Implemented" else "Pending GATE 14")
        }

        // Detailed capability provenance (§122)
        integrationState?.capabilityDetails?.forEach { detail ->
            val apiSupport = detail.apiSupport.name
            val maturity = detail.maturity.name
            rows.add("Capability ${detail.name}" to "$apiSupport / $maturity (${detail.reason})")
        }

        // Last error
        rows.add("Last Error" to (integrationState?.lastError?.message ?: "None"))

        return rows
    }

    override fun persistenceDiagnosticsRows(): List<Pair<String, String>> {
        val snapshot = persistenceSnapshot?.invoke() ?: persistenceDiagnostics?.snapshot?.value
            ?: PersistenceDiagnosticsSnapshot()
        return listOf(
            "Settings schema" to snapshot.settingsSchemaVersion.toString(),
            "Last settings write error" to (snapshot.lastSettingsWriteError ?: "None"),
            "Dashboard cache age" to DiagnosticsFormatting.ageMs(snapshot.dashboardCacheAgeMs),
            "Control cache age" to DiagnosticsFormatting.ageMs(snapshot.controlCacheAgeMs),
            "Draft present" to DiagnosticsFormatting.boolean(snapshot.draftPresent)
        )
    }

    /**
     * §89 — actual recognizer status, not a best guess.
     *
     * Every row that cannot be determined renders as `Unknown`. Reporting `No` for an
     * unqueried capability would tell the user offline recognition is unavailable when the
     * honest answer is that nobody asked.
     */
    override fun recognitionDiagnosticsRows(): List<Pair<String, String>> {
        val health = recognitionHealth.value
        val caps: RecognitionCapabilities = health.capabilities
        val metrics = health.metrics
        val ageSeconds = if (health.activeRequestAgeMs >= 0) {
            String.format("%.1fs", health.activeRequestAgeMs / 1000.0)
        } else {
            "-"
        }

        return listOf(
            "Recognizer" to yesNo(caps.recognitionAvailable),
            "Backend" to when (health.backendInUse) {
                com.studyagent.client.core.voice.stt.RecognitionBackendKind.ON_DEVICE -> "On-device"
                com.studyagent.client.core.voice.stt.RecognitionBackendKind.SYSTEM -> "System"
                com.studyagent.client.core.voice.stt.RecognitionBackendKind.UNKNOWN -> "Unknown"
            },
            // Provider package is shown only when the platform actually told us (§84).
            "Service" to (caps.defaultRecognizerPackage ?: caps.onDeviceRecognizerPackage ?: "Not reported"),
            "On-device available" to maybe(caps.onDeviceAvailable),
            "Language detection" to maybe(caps.languageDetectionSupported),
            "Language switching" to maybe(caps.languageSwitchSupported),
            "Vocabulary biasing" to maybe(caps.vocabularyBiasingSupported),
            "English model" to languageModelState(caps, "en"),
            "Arabic model" to languageModelState(caps, "ar"),
            "Input route" to health.inputRouteLabel,
            "Output route" to "${activeOutputDevice.value.name} (${activeOutputDevice.value.typeName})",
            "Active request" to (health.activePurpose?.name ?: "-"),
            "Request age" to ageSeconds,
            "Retry attempt" to health.retryAttempt.toString(),
            "Last confidence" to (health.lastConfidence?.let { String.format("%.2f", it) } ?: "-"),
            "Last error" to (health.lastError?.code?.name ?: "None"),
            "Avg ready latency" to latency(metrics.avgReadyLatencyMs),
            "Avg finalize latency" to latency(metrics.avgFinalizationMs),
            "Turns" to "ok=${metrics.completedTurns} failed=${metrics.failedTurns} " +
                "cancelled=${metrics.cancelledTurns}",
            "No speech / no match" to "${metrics.noSpeechCount} / ${metrics.noMatchCount}",
            "Busy / rate-limited" to "${metrics.busyErrors} / ${metrics.rateLimitRejections}",
            "Watchdog timeouts" to metrics.watchdogTimeouts.toString(),
            "Stale callbacks dropped" to metrics.staleCallbacksDropped.toString(),
            "On-device / network turns" to "${metrics.onDeviceTurns} / ${metrics.networkTurns}"
        )
    }

    private fun languageModelState(caps: RecognitionCapabilities, primary: String): String {
        if (caps.installedLanguages.isEmpty() && caps.supportedLanguages.isEmpty()) return "Unknown"
        val installed = caps.installedLanguages.any { it.lowercase().startsWith(primary) }
        if (installed) return "Installed"
        val supported = caps.supportedLanguages.any { it.lowercase().startsWith(primary) }
        return if (supported) "Supported, not installed" else "Not supported"
    }

    private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

    private fun maybe(value: Boolean?): String = when (value) {
        true -> "Yes"
        false -> "No"
        null -> "Unknown"
    }

    private fun latency(ms: Long): String = if (ms < 0) "-" else "${ms}ms"

    private fun formatDuration(ms: Long): String = when {
        ms < 0L -> DiagnosticsFormatting.NOT_MEASURED
        ms < 1_000L -> "${ms}ms"
        ms < 60_000L -> String.format(Locale.US, "%.1fs", ms / 1000.0)
        ms < 3_600_000L -> "${ms / 60_000L}m ${(ms % 60_000L) / 1_000L}s"
        else -> "${ms / 3_600_000L}h ${(ms % 3_600_000L) / 60_000L}m"
    }

    /** `Unknown` when the platform does not report it — never "0 B" (§66). */
    private fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes < 0L) return DiagnosticsFormatting.UNKNOWN
        return when {
            bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
            bytes >= 1_024L -> String.format(Locale.US, "%.0f KB", bytes / 1_024.0)
            else -> "$bytes B"
        }
    }

    // ------------------------------------------------------------------ export (§81-§88/§157)

    /**
     * Compact support snapshot: system status, health, technical performance, last few timeline
     * events. No log rows — that is what "Export detailed" is for (§88).
     */
    override fun getSummaryText(): String {
        val sb = StringBuilder(1_024)
        appendHeader(sb, detailed = false)
        sb.append("--- System ---\n")
        sb.append(pad("Connection")).append(connectionState.value.label).append('\n')
        sb.append(pad("Study audio")).append(studyAudioRoute.value.statusLabel).append('\n')
        sb.append(pad("Output")).append(activeOutputDevice.value.name).append('\n')
        sb.append(pad("Headset")).append(if (isHeadsetConnected.value) "Yes" else "No").append('\n')
        sb.append('\n')

        val session = sessionDiagnostics?.invoke()
        if (session != null) {
            sb.append("--- Session ---\n")
            sessionDiagnosticsRows().forEach { (label, value) -> sb.append(pad(label)).append(value).append('\n') }
            sb.append('\n')
        }

        sb.append("--- Performance ---\n")
        performanceSummaryLines().forEach { sb.append(it).append('\n') }
        sb.append('\n')

        sb.append("--- Recent events ---\n")
        sb.append(timeline.render(SUMMARY_TIMELINE_EVENTS))
        sb.append('\n')
        return sb.toString()
    }

    /**
     * Detailed export.
     *
     * Included: build/device header, every diagnostics section, the bounded structured timeline and
     * the sanitized log rows.
     *
     * Excluded by construction: auth tokens and headers (the connection never logs them, and
     * [AppLogger] redacts the shapes that could reach a message anyway), transcripts and questions
     * (never logged unless the developer opt-in is on), raw protocol frames (§83), and full
     * exception stack traces (sanitized and length-capped, §87).
     */
    override fun getFormattedLogsText(): String {
        // The export must never render a stale view of the buffer.
        AppLogger.flush()
        val currentLogs = AppLogger.snapshot()
        val health = ttsHealth.value
        val sb = StringBuilder(8_192)

        appendHeader(sb, detailed = true)

        sb.append("--- Connection ---\n")
        sb.append("Connection State: ").append(connectionState.value.label).append('\n')
        appendSection(sb, "Network", networkDiagnosticsRows())
        appendSection(sb, "Protocol", protocolDiagnosticsRows())

        val session = sessionDiagnostics?.invoke()
        if (session != null) appendSection(sb, "Session", sessionDiagnosticsRows())

        sb.append("--- TTS ---\n")
        sb.append("Audio Route: ${activeOutputDevice.value.name} (${activeOutputDevice.value.typeName})\n")
        sb.append("Headset Connected: ${isHeadsetConnected.value}\n")
        sb.append("TTS Engine: ${health.enginePackage ?: "unknown"} (${health.engineStatus})\n")
        sb.append("TTS Voices: en=${health.englishVoiceDisplay ?: "auto"} ar=${health.arabicVoiceDisplay ?: "auto"}\n")
        sb.append("TTS Queue Depth: ${health.queueDepth}\n")
        sb.append(
            "TTS Metrics: completed=${health.metrics.completedRequests} " +
                "failed=${health.metrics.failedRequests} cancelled=${health.metrics.cancelledRequests} " +
                "timeToReadyMs=${health.metrics.timeToReadyMs}\n"
        )
        sb.append("TTS Last Error: ${health.lastError?.code?.name ?: "none"}\n\n")

        appendSection(sb, "Study Audio Routing", studyAudioDiagnosticsRows())
        if (studyAudioRouteCoordinator == null) sb.append("Study audio routing: unavailable in this build\n")

        appendSection(sb, "Speech Recognition", recognitionDiagnosticsRows())
        appendSection(sb, "Performance", performanceDiagnosticsRows())
        appendSection(sb, "Dashboard", dashboardDiagnosticsRows())
        appendSection(sb, "Control Center", controlDiagnosticsRows())
        appendSection(sb, "Persistence", persistenceDiagnosticsRows())
        appendSection(sb, "AnkiDroid integration", ankiDroidDiagnosticsRows())

        sb.append("--- Diagnostic timeline (last ${DiagnosticTimeline.DEFAULT_EXPORT_LIMIT}) ---\n")
        sb.append(timeline.render(DiagnosticTimeline.DEFAULT_EXPORT_LIMIT))
        sb.append('\n')

        sb.append("--- Log (${currentLogs.size} rows, sanitized) ---\n")
        for (log in currentLogs) {
            sb.append(log.displayString).append('\n')
            log.throwable?.let { t ->
                // §87: a stack trace can carry a URL or a payload fragment. It goes through the
                // same sanitizer and the same length cap as any other message.
                sb.append("   Throwable: ").append(AppLogger.sanitizeText(t.toString())).append('\n')
                sb.append("   At: ")
                    .append(AppLogger.sanitizeText(t.stackTrace.take(3).joinToString(" | ")))
                    .append('\n')
            }
        }
        sb.append("--------------------------------------\n")
        return sb.toString()
    }

    private fun appendHeader(sb: StringBuilder, detailed: Boolean) {
        val info = appInfo()
        sb.append("=== Study Agent Diagnostics ").append(if (detailed) "Export" else "Summary").append(" ===\n")
        sb.append("Generated: ").append(clock.nowMillis()).append('\n')
        sb.append("App: ").append(info.appVersion).append(" (").append(info.buildType).append(")\n")
        sb.append("Android: ").append(info.androidVersion).append('\n')
        sb.append("Device: ").append(info.deviceModel).append('\n')
        sb.append("Protocol: ").append(info.protocolVersion)
        info.serverVersion?.let { sb.append(" (server ").append(it).append(')') }
        sb.append("\n\n")
    }

    private fun appendSection(sb: StringBuilder, title: String, rows: List<Pair<String, String>>) {
        if (rows.isEmpty()) return
        sb.append("--- ").append(title).append(" ---\n")
        rows.forEach { (label, value) -> sb.append(pad(label)).append(value).append('\n') }
        sb.append('\n')
    }

    private fun pad(label: String): String = label.padEnd(28)
}

/** Timeline rows included in the compact summary (§88). */
private const val SUMMARY_TIMELINE_EVENTS = 20
