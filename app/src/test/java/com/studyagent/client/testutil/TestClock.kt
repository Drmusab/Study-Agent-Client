package com.studyagent.client.testutil

import com.studyagent.client.core.common.AppClock

/**
 * Deterministic clock (§10/§148).
 *
 * Starts at a fixed, non-zero instant so a "first request is never rate limited" style guard that
 * compares against `Long.MIN_VALUE` behaves the same way it does in production, where the epoch
 * is never zero in practice.
 */
class TestClock(var nowMs: Long = DEFAULT_START_MS) : AppClock {

    override fun nowMillis(): Long = nowMs

    fun advance(millis: Long) {
        nowMs += millis
    }

    fun advanceTo(timestampMs: Long) {
        nowMs = timestampMs
    }

    /** Adapter for the many production APIs that still take a `() -> Long`. */
    fun asProvider(): () -> Long = { nowMs }

    companion object {
        /** 2023-11-14T22:13:20Z — arbitrary, stable, and obviously not "now". */
        const val DEFAULT_START_MS = 1_700_000_000_000L
    }
}
