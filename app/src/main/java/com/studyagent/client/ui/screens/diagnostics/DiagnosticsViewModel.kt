package com.studyagent.client.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
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
import kotlinx.coroutines.flow.StateFlow

/**
 * Diagnostics screen state.
 *
 * Every section is a *pull*: rows are rendered from the repositories that own the data, so the
 * screen never accumulates its own copy of session or network state (§58).
 */
class DiagnosticsViewModel(
    private val diagnosticsRepository: DiagnosticsRepository
) : ViewModel() {

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
