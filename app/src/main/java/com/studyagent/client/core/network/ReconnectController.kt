package com.studyagent.client.core.network

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with jitter (§40/§123).
 *
 * [random] is injectable so reconnection timing is reproducible in tests: a controller built with
 * `Random(seed)` produces exactly the same delay sequence on every run, and a controller built
 * with a zero-jitter `Random` is fully deterministic. The default keeps real jitter in production,
 * which is what prevents a fleet of clients from reconnecting in lockstep.
 */
class ReconnectController(
    private val initialDelayMs: Long = 1000L,
    private val maxDelayMs: Long = 20000L,
    private val factor: Double = 1.5,
    private val jitterFraction: Double = 0.2,
    private val random: Random = Random.Default
) {
    private var attemptCount = 0

    fun reset() {
        attemptCount = 0
    }

    val currentAttempt: Int
        get() = attemptCount

    fun getNextDelayMs(): Long {
        attemptCount++
        val baseDelay = (initialDelayMs * factor.pow((attemptCount - 1).toDouble())).toLong()
        val cappedDelay = min(baseDelay, maxDelayMs)
        val jitter = (cappedDelay * jitterFraction * (random.nextDouble() * 2 - 1)).toLong()
        return (cappedDelay + jitter).coerceAtLeast(500L)
    }
}
