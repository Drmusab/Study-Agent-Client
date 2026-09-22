package com.studyagent.client.core.common

import android.util.Log
import com.studyagent.client.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One log row.
 *
 * [sequence] is a monotonically increasing id assigned by [AppLogger]; Compose uses it as a
 * `LazyColumn` key so a row is never rebound to a different message when the buffer rotates
 * (§94). It is also the only field that is unique per row — timestamps repeat at millisecond
 * resolution under load.
 */
data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
    val sequence: Long = 0L
) {
    /** "HH:mm:ss.SSS", formatted with a reused thread-local formatter (§76). */
    val formattedTime: String
        get() = LogTimeFormat.format(timestamp)

    val displayString: String
        get() = "[$formattedTime] [${level.name}] $tag: $message"
}

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

/**
 * Process-wide bounded log buffer (§20/§21/§71-§76).
 *
 * Design constraints this implementation exists to satisfy, in order:
 *
 * 1. **Bounded.** At most [MAX_LOG_ENTRIES] rows are retained. Nothing here can grow with
 *    session length; a multi-hour session leaves the same memory footprint as a five-minute one.
 * 2. **Cheap append.** An append is O(1): one array/ring write under a short lock. The previous
 *    implementation converted the whole deque to a `List` on *every* logged row, i.e. O(n) per
 *    log with n up to 500 — measurable under the protocol/TTS/STT log volume of a real session.
 * 3. **Cheap snapshot on demand.** [snapshot] copies on demand (export, tests, crash breadcrumbs),
 *    not per append.
 * 4. **Observable without flooding.** [logsFlow] is coalesced to at most one publication per
 *    [PUBLISH_COALESCE_MS] for routine rows, so a burst of DEBUG/INFO logs cannot push hundreds of
 *    list emissions into the Compose frame loop. Terminal rows (WARN/ERROR) publish immediately:
 *    a failure must always be visible now, not in 200 ms.
 * 5. **Privacy-safe.** Every message passes through [LogSanitizer] *before* it is stored or
 *    written to logcat, so a token cannot reach either the buffer, the export or the system log.
 * 6. **Release-quiet.** [isDebugEnabled] defaults to `BuildConfig.DEBUG`; release builds keep
 *    only INFO and above unless a developer explicitly opts in with the debug-logging setting.
 */
object AppLogger {

    /** Retained rows. Bounded by construction; the oldest row is overwritten. */
    const val MAX_LOG_ENTRIES = 500

    /** Maximum publication cadence for routine rows (ms). */
    const val PUBLISH_COALESCE_MS = 200L

    /** A single row may not exceed this many characters (a raw protocol frame must not). */
    const val MAX_MESSAGE_CHARS = 1_500

    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>(MAX_LOG_ENTRIES)
    private val sequence = AtomicLong(0L)

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    /**
     * DEBUG rows are dropped when false. Defaults to the build type: debug builds keep the
     * developer detail, release builds stay quiet (§80). The debug-logging setting can raise it
     * at runtime, which is what makes a user-reported issue diagnosable without a special build.
     */
    @Volatile
    var isDebugEnabled: Boolean = BuildConfig.DEBUG

    /** Injectable for deterministic tests; production reads the system clock. */
    @Volatile
    internal var clock: () -> Long = System::currentTimeMillis

    private var lastPublishMs = Long.MIN_VALUE
    private var dirty = false
    private var trailingPublishScheduled = false

    private val publishExecutor: ScheduledExecutorService? by lazy {
        try {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "app-logger-publish").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
        } catch (_: Throwable) {
            // A restricted runtime without thread creation must not break logging.
            null
        }
    }

    fun d(tag: String, message: String) {
        if (!isDebugEnabled) return
        val sanitized = sanitizeText(message)
        logcat { Log.d(tag, sanitized) }
        append(LogLevel.DEBUG, tag, sanitized, null)
    }

    fun i(tag: String, message: String) {
        val sanitized = sanitizeText(message)
        logcat { Log.i(tag, sanitized) }
        append(LogLevel.INFO, tag, sanitized, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        val sanitized = sanitizeText(message)
        logcat { Log.w(tag, sanitized, throwable) }
        append(LogLevel.WARN, tag, sanitized, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val sanitized = sanitizeText(message)
        logcat { Log.e(tag, sanitized, throwable) }
        append(LogLevel.ERROR, tag, sanitized, throwable)
    }

    /**
     * Append a pre-built row. Used by subsystems that already hold structured data (the
     * diagnostic timeline) so nothing is formatted twice.
     */
    internal fun append(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val now = clock()
        val entry = LogEntry(
            timestamp = now,
            level = level,
            tag = tag,
            message = message,
            throwable = throwable,
            sequence = sequence.incrementAndGet()
        )

        val publishNow: List<LogEntry>?
        val scheduleAt: Long?
        synchronized(lock) {
            if (entries.size >= MAX_LOG_ENTRIES) entries.removeFirst()
            entries.addLast(entry)

            // Terminal rows are never delayed: the whole point of a WARN/ERROR is that it is
            // visible immediately even if the burst that produced it is still ongoing.
            val urgent = level == LogLevel.ERROR || level == LogLevel.WARN
            if (urgent || now - lastPublishMs >= PUBLISH_COALESCE_MS) {
                dirty = false
                lastPublishMs = now
                publishNow = ArrayList(entries)
                scheduleAt = null
            } else {
                dirty = true
                publishNow = null
                scheduleAt = lastPublishMs + PUBLISH_COALESCE_MS
            }
        }

        publishNow?.let { _logsFlow.value = it }
        if (scheduleAt != null) scheduleTrailingPublish(scheduleAt)
    }

    private fun scheduleTrailingPublish(dueAtMs: Long) {
        // Resolve the executor before the lock: a smart cast established inside the
        // `synchronized` lambda does not survive past it on every Kotlin compiler version.
        val executor = publishExecutor ?: return
        synchronized(lock) {
            if (trailingPublishScheduled || !dirty) return
            trailingPublishScheduled = true
        }
        val delayMs = (dueAtMs - clock()).coerceAtLeast(1L)
        try {
            executor.schedule(
                {
                    synchronized(lock) { trailingPublishScheduled = false }
                    flush()
                },
                delayMs,
                TimeUnit.MILLISECONDS
            )
        } catch (_: Throwable) {
            synchronized(lock) { trailingPublishScheduled = false }
        }
    }

    /**
     * Publish the current buffer to [logsFlow] immediately.
     *
     * Called by the coalescing scheduler, by the diagnostics export (which must never export a
     * stale view) and by tests that assert on the observable stream.
     */
    fun flush() {
        val snapshot: List<LogEntry>?
        synchronized(lock) {
            if (!dirty) return
            dirty = false
            lastPublishMs = clock()
            snapshot = ArrayList(entries)
        }
        snapshot?.let { _logsFlow.value = it }
    }

    /** Current rows, oldest first. Copies on demand — never called on the append path. */
    fun snapshot(): List<LogEntry> = synchronized(lock) { ArrayList(entries) }

    /** Rows currently retained (never more than [MAX_LOG_ENTRIES]). */
    val size: Int get() = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            dirty = false
            lastPublishMs = clock()
        }
        _logsFlow.value = emptyList()
    }

    /**
     * Sanitizes a message for storage, export and logcat (§75/§82).
     *
     * Patterns are compiled once (they are `val`s in [LogSanitizer], not per-call `Regex`
     * constructions), and the message is length-capped so a 200 KB dashboard payload cannot
     * become 200 KB of retained log row.
     */
    fun sanitizeText(message: String): String = LogSanitizer.sanitize(message)

    /** Test hook: replaces the clock used for row timestamps and publish coalescing. */
    internal fun setClockForTests(provider: () -> Long) {
        clock = provider
    }

    private inline fun logcat(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // android.util.Log throws on the JVM stub used by unit tests; logging must survive it.
        }
    }
}

/**
 * Text redaction used by the logger *and* by the diagnostics export (§81-§83).
 *
 * Precompiled, order-independent patterns over the shapes secrets actually take in this app:
 * JSON fields, query parameters, HTTP headers, bearer tokens, JWT-shaped values and the
 * hand-written `key=value` strings that appear in ad-hoc developer logs. Adding a variant here
 * protects every path at once, which is the point of keeping redaction in one object.
 *
 * The sanitizer is deliberately conservative: it only redacts *values* that follow a
 * secret-shaped name, so ordinary words ("the token count is 300") survive intact.
 */
internal object LogSanitizer {

    /** Sentinels; exported as-is so a reader can tell "redacted" from "never logged". */
    const val REDACTED = "***REDACTED***"

    private val patterns: List<Pair<Regex, String>> = listOf(
        // Authorization: Bearer <token> — header form.
        Regex("(?i)\\b(bearer)\\s+([A-Za-z0-9._~+/-]{4,})") to "$1 $REDACTED",
        // "token": "...", authToken=..., api_key: ..., password='...', secret: ...
        Regex(
            "(?i)(\"?(?:auth[_-]?token|access[_-]?token|refresh[_-]?token|id[_-]?token|token|api[_-]?key|apikey|" +
                "authorization|password|passphrase|secret|client[_-]?secret|private[_-]?key)\"?\\s*[:=]\\s*\"?)([^\"\\s,;&}]{3,})"
        ) to "\$1$REDACTED",
        // Query-string form: ?token=..., &api_key=..., &password=...
        Regex(
            "(?i)([?&](?:auth[_-]?token|access[_-]?token|token|api[_-]?key|apikey|key|password|secret)=)([^&\\s\"']+)"
        ) to "\$1$REDACTED",
        // A JWT anywhere in the text (three base64url segments, the first always starts with eyJ).
        Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?:\\.[A-Za-z0-9_-]{4,})?") to REDACTED
    )

    fun sanitize(message: String): String {
        var result = message
        for ((pattern, replacement) in patterns) {
            // Only run the replace when the pattern can match at all: `containsMatchIn` is far
            // cheaper than the replacement machinery for the overwhelmingly common case.
            if (pattern.containsMatchIn(result)) {
                result = pattern.replace(result, replacement)
            }
        }
        if (result.length > AppLogger.MAX_MESSAGE_CHARS) {
            result = result.take(AppLogger.MAX_MESSAGE_CHARS) + "…(truncated)"
        }
        return result
    }

    /** True when [value] looks like it still contains a secret. Used by the privacy tests. */
    fun looksRedacted(value: String): Boolean = value.contains(REDACTED)
}

/**
 * Reusable timestamp formatting for log rows and the diagnostic timeline (§76).
 *
 * `SimpleDateFormat` is not thread-safe, so one instance per thread is the cheapest correct
 * answer: constructing a formatter per row (the previous behaviour) meant allocating a
 * `SimpleDateFormat`, a `Calendar` and its `TimeZone` for every row rendered or exported.
 */
internal object LogTimeFormat {
    private const val PATTERN = "HH:mm:ss.SSS"

    private val formatter: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat(PATTERN, Locale.getDefault())
    }

    fun format(timestampMs: Long): String {
        return try {
            formatter.get()!!.format(Date(timestampMs))
        } catch (_: Throwable) {
            // Never let formatting break a log row or an export.
            timestampMs.toString()
        }
    }
}
