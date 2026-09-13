package com.studyagent.client.core.diagnostics

import com.studyagent.client.core.common.LogSanitizer
import com.studyagent.client.core.common.LogTimeFormat

/** Which subsystem an event came from — the coarse filter in Diagnostics (§158). */
enum class DiagnosticCategory(val label: String) {
    SESSION("Session"),
    NETWORK("Network"),
    VOICE("Voice"),
    TTS("TTS"),
    STT("STT"),
    AUDIO("Audio"),
    PERFORMANCE("Performance"),
    APP("App")
}

/**
 * One structured diagnostic event (§67/§68).
 *
 * Structured, not a log line: a race is diagnosed by looking at the *order* of
 * `QUESTION_RECEIVED → TTS_START → TTS_DONE → STT_READY → STT_FINAL → ANSWER_SENT`, and that
 * ordering is only trustworthy if the records are typed and carry the turn identity.
 *
 * Privacy: [metadata] holds identifiers and counts only. It never holds a transcript, a question,
 * an answer, a token or a raw frame; values are sanitized on the way in and length-capped, and
 * `turnId`/`requestId` are already content-free by construction (`epoch:cardId:generation`).
 */
data class DiagnosticEvent(
    val timestampMs: Long,
    val category: DiagnosticCategory,
    val event: String,
    val sessionEpoch: Long? = null,
    val turnId: String? = null,
    val requestId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    /**
     * Monotonic id assigned by the timeline. Unique even when two events land in the same
     * millisecond — which is what makes it usable as a `LazyColumn` key (§94) and as a stable
     * reference in a failing chaos report (§172).
     */
    val sequence: Long = 0L
) {
    val formattedTime: String get() = LogTimeFormat.format(timestampMs)

    /**
     * One export/render line, e.g.
     * `08:10:01.002  SESSION  QUESTION_RECEIVED  epoch=1 turn=1:c3:2`.
     */
    fun render(): String {
        val sb = StringBuilder(80)
        sb.append(formattedTime).append("  ")
        sb.append(category.label.uppercase().padEnd(11)).append(' ')
        sb.append(event)
        sessionEpoch?.let { sb.append("  epoch=").append(it) }
        turnId?.let { sb.append("  turn=").append(it) }
        requestId?.let { sb.append("  req=").append(it) }
        if (metadata.isNotEmpty()) {
            sb.append("  ")
            sb.append(metadata.entries.joinToString(" ") { (k, v) -> "$k=$v" })
        }
        return sb.toString()
    }
}

/**
 * Bounded chronological ring buffer of [DiagnosticEvent]s (§67/§69).
 *
 * Bounded on purpose: the timeline exists to explain the *last few minutes* of a session, not to
 * accumulate a multi-hour transcript of the app's behaviour. When full, the oldest event is
 * overwritten, so a 1000-card endurance run leaves exactly [capacity] events behind.
 *
 * A second, hard rule: high-frequency telemetry (RMS levels, partial transcripts) is never
 * recorded here. Only state changes and lifecycle milestones are, which is what keeps the buffer
 * meaningful instead of mostly-noise (§70/§77/§78).
 */
class DiagnosticTimeline(
    val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val lock = Any()
    private val events = ArrayDeque<DiagnosticEvent>(capacity)

    /** Also the source of [DiagnosticEvent.sequence]; atomic so ids are unique across threads. */
    private val totalRecorded = java.util.concurrent.atomic.AtomicLong(0L)

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /** Records one event. Returns the stored event so callers can echo it if they wish. */
    fun record(
        category: DiagnosticCategory,
        event: String,
        sessionEpoch: Long? = null,
        turnId: String? = null,
        requestId: String? = null,
        metadata: Map<String, String> = emptyMap()
    ): DiagnosticEvent {
        val sanitizedMetadata = if (metadata.isEmpty()) {
            emptyMap()
        } else {
            metadata.entries
                .take(MAX_METADATA_ENTRIES)
                .associate { (key, value) ->
                    key.take(MAX_METADATA_KEY_CHARS) to
                        LogSanitizer.sanitize(value).take(MAX_METADATA_VALUE_CHARS)
                }
        }
        val sequence = totalRecorded.incrementAndGet()
        val entry = DiagnosticEvent(
            timestampMs = clock(),
            category = category,
            event = event,
            sessionEpoch = sessionEpoch,
            turnId = turnId,
            requestId = requestId,
            metadata = sanitizedMetadata,
            sequence = sequence
        )
        synchronized(lock) {
            if (events.size >= capacity) events.removeFirst()
            events.addLast(entry)
        }
        return entry
    }

    /** Oldest → newest. Copies on demand; this is not on any hot path. */
    fun snapshot(): List<DiagnosticEvent> = synchronized(lock) { ArrayList(events) }

    /** The newest [limit] events, oldest first — what an export wants. */
    fun recent(limit: Int): List<DiagnosticEvent> {
        if (limit <= 0) return emptyList()
        return synchronized(lock) {
            val size = events.size
            if (size <= limit) ArrayList(events) else ArrayList(events).subList(size - limit, size).toList()
        }
    }

    /** Events of one category, newest [limit] kept (used by the filter chips). */
    fun recentInCategory(category: DiagnosticCategory, limit: Int = 200): List<DiagnosticEvent> =
        recent(capacity).filter { it.category == category }.takeLast(limit)

    fun clear() {
        synchronized(lock) {
            events.clear()
            totalRecorded.set(0L)
        }
    }

    /** Events currently retained — never more than [capacity]. */
    val size: Int get() = synchronized(lock) { events.size }

    /** Events recorded since the last [clear]; how much history has been rotated out. */
    val recordedTotal: Long get() = totalRecorded.get()

    /** Renders the timeline for the diagnostics export. */
    fun render(limit: Int = DEFAULT_EXPORT_LIMIT): String {
        val selected = recent(limit)
        if (selected.isEmpty()) return "(no diagnostic events recorded)"
        val sb = StringBuilder(selected.size * 96)
        selected.forEach { sb.append(it.render()).append('\n') }
        return sb.toString()
    }

    companion object {
        /** Events retained. Enough to reconstruct several turns; small enough to stay irrelevant. */
        const val DEFAULT_CAPACITY = 500

        /** How many events the text export includes by default. */
        const val DEFAULT_EXPORT_LIMIT = 200

        const val MAX_METADATA_ENTRIES = 8
        const val MAX_METADATA_KEY_CHARS = 24
        const val MAX_METADATA_VALUE_CHARS = 48
    }
}

/**
 * Process-lifetime diagnostic holder.
 *
 * Deliberately a tiny object rather than a DI graph: the timeline is written from the session
 * machine, the orchestrators and the connection, and read by Diagnostics — threading it through
 * every constructor would add coupling without adding a single testable behaviour. Tests inject
 * their own [DiagnosticTimeline] into the component under test and never touch this instance.
 */
object AppDiagnostics {
    val timeline = DiagnosticTimeline()

    /** Test hook: empties the shared timeline so cases cannot observe each other's events. */
    fun resetForTests() {
        timeline.clear()
    }
}

/** Field-value access used by both diagnostics rows and tests. Never renders `0` for "unmeasured". */
object DiagnosticsFormatting {
    const val UNKNOWN = "Unknown"
    const val NOT_MEASURED = "-"

    fun millis(value: Long?): String = when {
        value == null || value < 0L -> NOT_MEASURED
        else -> "${value}ms"
    }

    fun boolean(value: Boolean?): String = when (value) {
        true -> "Yes"
        false -> "No"
        null -> UNKNOWN
    }

    /** `15 / 427 (3.5%)` — a failure count without a denominator is not a rate (§165). */
    fun rate(numerator: Long, denominator: Long): String {
        if (denominator <= 0L) return "$numerator / -"
        val percent = (numerator * 1000.0 / denominator).let { Math.round(it) / 10.0 }
        return "$numerator / $denominator ($percent%)"
    }

    /** Abbreviates an identifier for display without leaking the whole thing (§59/§61). */
    fun abbreviate(id: String?, keep: Int = 8): String {
        if (id.isNullOrBlank()) return NOT_MEASURED
        return if (id.length <= keep) id else id.take(keep) + "…"
    }

    fun ageMs(ms: Long?): String = when {
        ms == null || ms < 0L -> UNKNOWN
        ms < 1_000L -> "${ms}ms ago"
        ms < 60_000L -> "${ms / 1_000L}s ago"
        ms < 3_600_000L -> "${ms / 60_000L}m ago"
        else -> "${ms / 3_600_000L}h ago"
    }
}
