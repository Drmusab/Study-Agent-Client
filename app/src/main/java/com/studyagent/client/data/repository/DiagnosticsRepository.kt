package com.studyagent.client.data.repository

import com.studyagent.client.core.audio.AudioDeviceInfoModel
import com.studyagent.client.core.audio.AudioRouteManager
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.LogEntry
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.voice.tts.SpeechOrchestrator
import com.studyagent.client.core.voice.tts.TtsHealthSnapshot
import kotlinx.coroutines.flow.StateFlow

interface DiagnosticsRepository {
    val logs: StateFlow<List<LogEntry>>
    val connectionState: StateFlow<ConnectionState>
    val activeOutputDevice: StateFlow<AudioDeviceInfoModel>
    val isHeadsetConnected: StateFlow<Boolean>
    val ttsHealth: StateFlow<TtsHealthSnapshot>

    fun clearLogs()
    fun getFormattedLogsText(): String
}

class DefaultDiagnosticsRepository(
    private val connectionRepository: ConnectionRepository,
    private val audioRouteManager: AudioRouteManager,
    speechOrchestrator: SpeechOrchestrator
) : DiagnosticsRepository {

    override val logs: StateFlow<List<LogEntry>> = AppLogger.logsFlow
    override val connectionState: StateFlow<ConnectionState> = connectionRepository.connectionState
    override val activeOutputDevice: StateFlow<AudioDeviceInfoModel> = audioRouteManager.activeOutputDevice
    override val isHeadsetConnected: StateFlow<Boolean> = audioRouteManager.isHeadsetConnected
    override val ttsHealth: StateFlow<TtsHealthSnapshot> = speechOrchestrator.health

    override fun clearLogs() {
        AppLogger.clear()
    }

    override fun getFormattedLogsText(): String {
        val currentLogs = logs.value
        val health = ttsHealth.value
        val sb = StringBuilder()
        sb.append("=== Study Agent Diagnostics Export ===\n")
        sb.append("Timestamp: ${System.currentTimeMillis()}\n")
        sb.append("Connection State: ${connectionState.value.label}\n")
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
        sb.append("TTS Last Error: ${health.lastError?.code?.name ?: "none"}\n")
        sb.append("--------------------------------------\n\n")
        for (log in currentLogs) {
            sb.append(log.displayString).append("\n")
            log.throwable?.let { t ->
                sb.append("   Throwable: ").append(t.stackTraceToString()).append("\n")
            }
        }
        return sb.toString()
    }
}
