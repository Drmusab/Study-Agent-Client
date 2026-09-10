package com.studyagent.client.data.repository

import com.studyagent.client.core.audio.AudioDeviceInfoModel
import com.studyagent.client.core.audio.AudioRouteManager
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.LogEntry
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.models.ServerProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class DiagnosticSnapshot(
    val connectionState: ConnectionState,
    val activeProfile: ServerProfile?,
    val activeAudioDevice: AudioDeviceInfoModel,
    val isHeadsetConnected: Boolean,
    val logsCount: Int
)

interface DiagnosticsRepository {
    val logs: StateFlow<List<LogEntry>>
    val connectionState: StateFlow<ConnectionState>
    val activeOutputDevice: StateFlow<AudioDeviceInfoModel>
    val isHeadsetConnected: StateFlow<Boolean>

    fun clearLogs()
    fun getFormattedLogsText(): String
}

class DefaultDiagnosticsRepository(
    private val connectionRepository: ConnectionRepository,
    private val audioRouteManager: AudioRouteManager
) : DiagnosticsRepository {

    override val logs: StateFlow<List<LogEntry>> = AppLogger.logsFlow
    override val connectionState: StateFlow<ConnectionState> = connectionRepository.connectionState
    override val activeOutputDevice: StateFlow<AudioDeviceInfoModel> = audioRouteManager.activeOutputDevice
    override val isHeadsetConnected: StateFlow<Boolean> = audioRouteManager.isHeadsetConnected

    override fun clearLogs() {
        AppLogger.clear()
    }

    override fun getFormattedLogsText(): String {
        val currentLogs = logs.value
        val sb = StringBuilder()
        sb.append("=== Study Agent Diagnostics Export ===\n")
        sb.append("Timestamp: ${System.currentTimeMillis()}\n")
        sb.append("Connection State: ${connectionState.value.label}\n")
        sb.append("Audio Route: ${activeOutputDevice.value.name} (${activeOutputDevice.value.typeName})\n")
        sb.append("Headset Connected: ${isHeadsetConnected.value}\n")
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
