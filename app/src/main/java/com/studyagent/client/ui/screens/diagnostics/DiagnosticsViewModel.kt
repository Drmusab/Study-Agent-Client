package com.studyagent.client.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
import com.studyagent.client.core.audio.AudioDeviceInfoModel
import com.studyagent.client.core.common.LogEntry
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.voice.stt.RecognitionHealthSnapshot
import com.studyagent.client.core.voice.tts.TtsHealthSnapshot
import com.studyagent.client.data.repository.DiagnosticsRepository
import kotlinx.coroutines.flow.StateFlow

class DiagnosticsViewModel(
    private val diagnosticsRepository: DiagnosticsRepository
) : ViewModel() {

    val logs: StateFlow<List<LogEntry>> = diagnosticsRepository.logs
    val connectionState: StateFlow<ConnectionState> = diagnosticsRepository.connectionState
    val activeOutputDevice: StateFlow<AudioDeviceInfoModel> = diagnosticsRepository.activeOutputDevice
    val isHeadsetConnected: StateFlow<Boolean> = diagnosticsRepository.isHeadsetConnected
    val ttsHealth: StateFlow<TtsHealthSnapshot> = diagnosticsRepository.ttsHealth
    val recognitionHealth: StateFlow<RecognitionHealthSnapshot> = diagnosticsRepository.recognitionHealth

    /**
     * Real recognizer status: provider, backend actually in use, per-language model state,
     * live turn identity and counters. Never a synthetic "listening" boolean.
     */
    fun recognitionRows(): List<Pair<String, String>> = diagnosticsRepository.recognitionDiagnosticsRows()

    fun clearLogs() {
        diagnosticsRepository.clearLogs()
    }

    fun getExportText(): String {
        return diagnosticsRepository.getFormattedLogsText()
    }
}
