package com.studyagent.client.core.common

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Parsing/formatting helpers for server timestamps and durations.
 * Pure JVM (java.time is available on minSdk 26+), unit-testable.
 */
object TimeFormatting {

    private val fallbackPatterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd"
    )

    /**
     * Leniently parses ISO-8601 timestamps produced by different PC agents
     * (Kotlin `currentIsoTimestamp`, Python `datetime.isoformat()`, plain dates).
     * Returns null when the value is missing or unparseable.
     */
    fun parseIsoToEpochMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val trimmed = iso.trim()
        try {
            return Instant.parse(trimmed).toEpochMilli()
        } catch (_: Exception) {
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
        } catch (_: Exception) {
        }
        try {
            return LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (_: Exception) {
        }
        for (pattern in fallbackPatterns) {
            try {
                val df = SimpleDateFormat(pattern, Locale.US)
                df.timeZone = TimeZone.getTimeZone("UTC")
                return df.parse(trimmed)?.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** Formats an epoch timestamp as a short "time ago" label relative to [nowMs]. */
    fun formatRelativeTime(epochMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
        if (epochMs == null) return "never"
        val deltaMs = nowMs - epochMs
        if (abs(deltaMs) < 45_000L) return "just now"
        val minutes = (deltaMs / 60_000L)
        if (abs(minutes) < 60) return if (minutes > 0) "${minutes}m ago" else "in ${-minutes}m"
        val hours = minutes / 60
        if (abs(hours) < 24) return if (hours > 0) "${hours}h ago" else "in ${-hours}h"
        val days = hours / 24
        return if (days > 0) "${days}d ago" else "in ${-days}d"
    }

    /** Formats a duration in seconds as a compact label: "45s", "28m", "1h 42m". */
    fun formatDurationSeconds(totalSeconds: Long): String {
        if (totalSeconds <= 0) return "0s"
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            minutes > 0 && seconds > 0 && totalSeconds < 600 -> "${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    /** Formats elapsed milliseconds as "mm:ss" or "h:mm:ss" for session timers. */
    fun formatClock(elapsedMs: Long): String {
        val safe = if (elapsedMs < 0) 0L else elapsedMs
        val totalSeconds = safe / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /** Formats a percentage (0..100) as a rounded integer label, or "—" when null. */
    fun formatPercent(value: Double?, dash: String = "—"): String =
        if (value == null) dash else "${value.roundToInt()}%"

    /** Formats a large token count compactly: 3800000 -> "3.8M". */
    fun formatCompactNumber(value: Long): String = when {
        value >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", value / 1_000_000_000.0)
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 10_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }

    /** Formats a date string (yyyy-MM-dd or ISO) as a short display label like "Jan 13". */
    fun formatShortDate(iso: String?, fallback: String = "—"): String {
        val epoch = parseIsoToEpochMs(iso) ?: return fallback
        return try {
            val df = SimpleDateFormat("MMM d", Locale.getDefault())
            df.timeZone = TimeZone.getTimeZone("UTC")
            df.format(Date(epoch))
        } catch (_: Exception) {
            fallback
        }
    }
}