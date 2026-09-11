package com.studyagent.client.data.repository

import com.studyagent.client.core.audio.AudioDeviceInfoModel
import com.studyagent.client.core.audio.AudioRouteManager
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.LogEntry
import com.studyagent.client.core.models.ConnectionState
import com.studyagent.client.core.voice.stt.RecognitionCapabilities
import com.studyagent.client.core.voice.stt.RecognitionHealthSnapshot
import com.studyagent.client.core.voice.stt.SpeechRecognitionOrchestrator
import com.studyagent.client.core.voice.tts.SpeechOrchestrator
import com.studyagent.client.core.voice.tts.TtsHealthSnapshot
import kotlinx.coroutines.flow.StateFlow

interface DiagnosticsRepository {
    val logs: StateFlow<List<LogEntry>>
    val connectionState: StateFlow<ConnectionState>
    val activeOutputDevice: StateFlow<AudioDeviceInfoModel>
    val isHeadsetConnected: StateFlow<Boolean>
    val ttsHealth: StateFlow<TtsHealthSnapshot>
    val recognitionHealth: StateFlow<RecognitionHealthSnapshot>

    fun clearLogs()
    fun getFormattedLogsText(): String

    /** Rendered rows for the STT diagnostics section (§89). */
    fun recognitionDiagnosticsRows(): List<Pair<String, String>>
}

class DefaultDiagnosticsRepository(
    private val connectionRepository: ConnectionRepository,
    private val audioRouteManager: AudioRouteManager,
    speechOrchestrator: SpeechOrchestrator,
    recognitionOrchestrator: SpeechRecognitionOrchestrator
) : DiagnosticsRepository {

    override val logs: StateFlow<List<LogEntry>> = AppLogger.logsFlow
    override val connectionState: StateFlow<ConnectionState> = connectionRepository.connectionState
    override val activeOutputDevice: StateFlow<AudioDeviceInfoModel> = audioRouteManager.activeOutputDevice
    override val isHeadsetConnected: StateFlow<Boolean> = audioRouteManager.isHeadsetConnected
    override val ttsHealth: StateFlow<TtsHealthSnapshot> = speechOrchestrator.health
    override val recognitionHealth: StateFlow<RecognitionHealthSnapshot> = recognitionOrchestrator.health

    override fun clearLogs() {
        AppLogger.clear()
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

    /**
     * Diagnostic export (§133).
     *
     * Transcript text is deliberately absent: `AppLogger` never receives it unless the user
     * explicitly enabled `sttDebugTranscriptLogging`, so this export stays safe to share even
     * though study answers can contain personal or clinical detail.
     */
    override fun getFormattedLogsText(): String {
        val currentLogs = logs.value
        val health = ttsHealth.value
        val sb = StringBuilder()
        sb.append("=== Study Agent Diagnostics Export ===\n")
        sb.append("Timestamp: ${System.currentTimeMillis()}\n")
        sb.append("Connection State: ${connectionState.value.label}\n\n")

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

        sb.append("--- Speech Recognition ---\n")
        for ((label, value) in recognitionDiagnosticsRows()) {
            sb.append(label.padEnd(24)).append(value).append("\n")
        }

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
