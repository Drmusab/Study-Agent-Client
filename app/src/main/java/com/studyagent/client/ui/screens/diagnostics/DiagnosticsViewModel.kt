package com.studyagent.client.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiBackendRoutingDiagnostics
import com.studyagent.client.core.anki.AnkiBackendState
import com.studyagent.client.core.anki.AnkiStudyBackendLockTracker
import com.studyagent.client.core.anki.AnkiUnresolvedTransactionRead
import com.studyagent.client.core.anki.AnkiUnresolvedTransactionReader
import com.studyagent.client.core.anki.BackendPreference
import com.studyagent.client.core.audio.AudioDeviceInfoModel
import com.studyagent.client.core.audio.EffectiveStudyAudioRoute
import com.studyagent.client.core.audio.PhoneModeMetrics
import com.studyagent.client.core.audio.StudyAudioAttention
import com.studyagent.client.core.common.LogEntry
import com.studyagent.client.core.diagnostics.DiagnosticEvent
import com.studyagent.client.core.diagnostics.PerformanceSnapshot
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.voice.stt.RecognitionHealthSnapshot
import com.studyagent.client.core.voice.tts.TtsHealthSnapshot
import com.studyagent.client.data.repository.DiagnosticsRepository
import com.studyagent.client.ui.components.anki.AnkiBackendDiagnosticsInput
import com.studyagent.client.ui.components.anki.buildAnkiBackendDiagnosticsLines
import com.studyagent.client.ui.components.anki.pendingTransactionGroups
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Diagnostics screen state.
 *
 * Every section is a *pull*: rows are rendered from the repositories that own the data, so the
 * screen never accumulates its own copy of session or network state (§58).
 */
class DiagnosticsViewModel(
    private val diagnosticsRepository: DiagnosticsRepository,
    /**
     * GATE 21M — read-only Anki backend diagnostics (CHECKPOINT 21M-16 … 21M-19).
     *
     * Every parameter is an existing authoritative projection; all are optional so the screen keeps
     * working (and existing call sites keep compiling) when this surface is not wired. This ViewModel
     * gains **no** authority from them: it reads snapshots and renders tokens. It never writes a
     * ledger, resets a mutation status, forces a recovery, reassigns a transaction or probes a
     * backend (AUDIT-21M-R2-13).
     */
    private val ankiBackendStates: StateFlow<List<AnkiBackendState>>? = null,
    private val ankiBackendPreference: StateFlow<BackendPreference>? = null,
    private val ankiBackendRouting: (() -> AnkiBackendRoutingDiagnostics?)? = null,
    private val ankiSessionLocks: AnkiStudyBackendLockTracker? = null,
    private val ankiBackendRegistry: AnkiBackendRegistry? = null,
    private val ankiUnresolvedTransactions: AnkiUnresolvedTransactionReader? = null
) : ViewModel() {

    private val _ankiPendingTransactions =
        MutableStateFlow(AnkiUnresolvedTransactionRead.NOT_READ)

    /** One read of the unresolved-transaction projections, taken when the screen was opened. */
    val ankiPendingTransactions: StateFlow<AnkiUnresolvedTransactionRead> =
        _ankiPendingTransactions.asStateFlow()

    init {
        // Read-only ledger reads: no Anki call, no probe, no mutation and no polling
        // (VER-21M-R2-36). A family that cannot be read is reported as not projected.
        val reader = ankiUnresolvedTransactions
        if (reader != null) {
            viewModelScope.launch { _ankiPendingTransactions.value = reader.read() }
        }
    }

    /** True when the Anki backend section has anything authoritative to render. */
    val hasAnkiBackendDiagnostics: Boolean get() = ankiBackendStates != null

    /**
     * Anki backend rows: one configured instance's identity, availability, capability truth on both
     * dimensions, the four selection roles, the active session's own owner with its collection
     * assurance category, and each family's unresolved transactions grouped by the backend that owns
     * them. Tokens and counts only — see `AnkiBackendDiagnosticsUi` for what may not appear here.
     */
    fun ankiBackendRows(): List<Pair<String, String>> {
        val input = ankiBackendDiagnosticsInput() ?: return emptyList()
        return buildAnkiBackendDiagnosticsLines(input).map { it.toPair() }
    }

    private fun ankiBackendDiagnosticsInput(): AnkiBackendDiagnosticsInput? {
        val states = ankiBackendStates?.value ?: return null
        val registry = ankiBackendRegistry
        return AnkiBackendDiagnosticsInput(
            states = states,
            preference = ankiBackendPreference?.value,
            routing = ankiBackendRouting?.invoke(),
            sessionLocks = ankiSessionLocks?.locks?.value ?: emptyMap(),
            // Observations only: reading a backend's already-published collection discriminator is not
            // a probe, and the value itself is never rendered (only its presence).
            liveCollectionKeys = registry?.registered
                ?.associate { it.id to it.currentCollectionIdentity?.collectionKey }
                ?: emptyMap(),
            customStudyClaims = registry?.registered
                ?.associate { it.id to it.customStudyCapabilities }
                ?: emptyMap(),
            pendingTransactions = pendingTransactionGroups(_ankiPendingTransactions.value),
            pendingTransactionsLoaded = _ankiPendingTransactions.value.loaded
        )
    }

    val logs: StateFlow<List<LogEntry>> = diagnosticsRepository.logs
    val connectionState: StateFlow<ConnectionState> = diagnosticsRepository.connectionState
    val activeOutputDevice: StateFlow<AudioDeviceInfoModel> = diagnosticsRepository.activeOutputDevice
    val isHeadsetConnected: StateFlow<Boolean> = diagnosticsRepository.isHeadsetConnected
    val ttsHealth: StateFlow<TtsHealthSnapshot> = diagnosticsRepository.ttsHealth
    val recognitionHealth: StateFlow<RecognitionHealthSnapshot> = diagnosticsRepository.recognitionHealth

    /** Effective study audio route (§67/§68) — preference, then what is actually in use. */
    val studyAudioRoute: StateFlow<EffectiveStudyAudioRoute> = diagnosticsRepository.studyAudioRoute
    val audioRouteAttention: StateFlow<StudyAudioAttention?> = diagnosticsRepository.audioRouteAttention

    /**
     * Real recognizer status: provider, backend actually in use, per-language model state,
     * live turn identity and counters. Never a synthetic "listening" boolean.
     */
    fun recognitionRows(): List<Pair<String, String>> = diagnosticsRepository.recognitionDiagnosticsRows()

    /** Study-audio routing rows: mode, effective mode, output, input, headset, metrics (§67). */
    fun studyAudioRows(): List<Pair<String, String>> = diagnosticsRepository.studyAudioDiagnosticsRows()

    /** Session identity, turn, pending action, recovery, bounded-structure sizes (§59). */
    fun sessionRows(): List<Pair<String, String>> = diagnosticsRepository.sessionDiagnosticsRows()

    /** Connection, transport, reconnect, ping, message counts and rates (§60). */
    fun networkRows(): List<Pair<String, String>> = diagnosticsRepository.networkDiagnosticsRows()

    /** Capability negotiation, server identity, last protocol error (§61). */
    fun protocolRows(): List<Pair<String, String>> = diagnosticsRepository.protocolDiagnosticsRows()

    /** Bounded latency percentiles, failure rates, queues and memory (§65). */
    fun performanceRows(): List<Pair<String, String>> = diagnosticsRepository.performanceDiagnosticsRows()

    fun dashboardRows(): List<Pair<String, String>> = diagnosticsRepository.dashboardDiagnosticsRows()

    fun controlRows(): List<Pair<String, String>> = diagnosticsRepository.controlDiagnosticsRows()

    fun persistenceRows(): List<Pair<String, String>> = diagnosticsRepository.persistenceDiagnosticsRows()

    /**
     * GATE 02 — AnkiDroid integration, technical view (§35/§100): authority, package, provider
     * spec, permission, collection state, last check, duration and the last failure code.
     * Content-free by construction: there is no card or deck data in the health snapshot.
     */
    fun ankiDroidRows(): List<Pair<String, String>> = diagnosticsRepository.ankiDroidDiagnosticsRows()

    /** Last [limit] structured events, oldest first (§67/§69). */
    fun timeline(limit: Int = 200): List<DiagnosticEvent> = diagnosticsRepository.timelineEvents(limit)

    fun performanceSnapshot(): PerformanceSnapshot = diagnosticsRepository.performanceSnapshot()

    /** Local Phone Mode counters (counts/timings only — never transcripts, §111). */
    fun phoneModeMetrics(): PhoneModeMetrics = diagnosticsRepository.phoneModeMetrics()

    fun clearLogs() {
        diagnosticsRepository.clearLogs()
    }

    fun clearTimeline() {
        diagnosticsRepository.clearTimeline()
    }

    /** Full export: header, every section, bounded timeline, sanitized logs (§157). */
    fun getExportText(): String = diagnosticsRepository.getExportText()

    /** Compact shareable summary (§88). */
    fun getSummaryText(): String = diagnosticsRepository.getSummaryText()
}
