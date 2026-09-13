package com.studyagent.client.core.common

/**
 * The one wall clock the app reads when timing matters (§9/§10).
 *
 * Why this exists: timeouts, retry backoff, cache freshness, session duration, request age and
 * every latency metric are *decisions*. A decision that reads `System.currentTimeMillis()`
 * directly cannot be replayed, so the tests for it either sleep (slow, flaky) or assert nothing
 * (useless). Injecting the clock keeps those rules deterministic without turning every
 * timestamp in the codebase into an abstraction — only the systems that make time-based
 * decisions take one.
 *
 * Monotonicity: this is a *wall* clock, deliberately. It is used for "how long ago", never for
 * measuring a duration that must be monotonic down to the nanosecond. Where a duration is
 * measured inside one turn (TTS/STT latencies) the callers subtract two readings of the same
 * clock, which is what the existing subsystems already do.
 */
interface AppClock {
    /** Milliseconds since the Unix epoch, matching [System.currentTimeMillis]. */
    fun nowMillis(): Long
}

/** Production clock. The only implementation that touches the system time. */
object SystemAppClock : AppClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Elapsed time between [startMs] and now, never negative (a clock step must not corrupt metrics). */
fun AppClock.elapsedSince(startMs: Long): Long = (nowMillis() - startMs).coerceAtLeast(0L)

/** Age of a timestamp relative to now; `null` when the timestamp is unknown or in the future. */
fun AppClock.ageOf(timestampMs: Long?): Long? {
    if (timestampMs == null || timestampMs <= 0L) return null
    val age = nowMillis() - timestampMs
    return if (age < 0L) 0L else age
}

/** Convenience adapter for the many call sites that still take a `() -> Long` lambda. */
fun AppClock.asProvider(): () -> Long = { nowMillis() }
