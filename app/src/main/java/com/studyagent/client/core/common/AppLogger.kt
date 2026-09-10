package com.studyagent.client.core.common

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    val displayString: String
        get() = "[$formattedTime] [${level.name}] $tag: $message"
}

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

object AppLogger {
    private const val MAX_LOG_ENTRIES = 500
    private val logDeque = ConcurrentLinkedDeque<LogEntry>()
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    var isDebugEnabled: Boolean = true

    fun d(tag: String, message: String) {
        if (!isDebugEnabled) return
        val sanitized = sanitize(message)
        try {
            Log.d(tag, sanitized)
        } catch (_: Throwable) {}
        appendLog(LogLevel.DEBUG, tag, sanitized)
    }

    fun i(tag: String, message: String) {
        val sanitized = sanitize(message)
        try {
            Log.i(tag, sanitized)
        } catch (_: Throwable) {}
        appendLog(LogLevel.INFO, tag, sanitized)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        val sanitized = sanitize(message)
        try {
            Log.w(tag, sanitized, throwable)
        } catch (_: Throwable) {}
        appendLog(LogLevel.WARN, tag, sanitized, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val sanitized = sanitize(message)
        try {
            Log.e(tag, sanitized, throwable)
        } catch (_: Throwable) {}
        appendLog(LogLevel.ERROR, tag, sanitized, throwable)
    }

    fun clear() {
        logDeque.clear()
        _logsFlow.value = emptyList()
    }

    private fun appendLog(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(
            level = level,
            tag = tag,
            message = message,
            throwable = throwable
        )
        logDeque.addLast(entry)
        while (logDeque.size > MAX_LOG_ENTRIES) {
            logDeque.pollFirst()
        }
        _logsFlow.value = logDeque.toList()
    }

    private fun sanitize(message: String): String {
        return message
            .replace(Regex("\"token\"\\s*:\\s*\"[^\"]+\""), "\"token\":\"***REDACTED***\"")
            .replace(Regex("\"authToken\"\\s*:\\s*\"[^\"]+\""), "\"authToken\":\"***REDACTED***\"")
            .replace(Regex("token=[^&\\s]+"), "token=***REDACTED***")
            .replace(Regex("Bearer\\s+[A-Za-z0-9_.-]+"), "Bearer ***REDACTED***")
    }
}
