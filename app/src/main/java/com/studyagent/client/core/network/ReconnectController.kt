package com.studyagent.client.core.network

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

class ReconnectController(
    private val initialDelayMs: Long = 1000L,
    private val maxDelayMs: Long = 20000L,
    private val factor: Double = 1.5,
    private val jitterFraction: Double = 0.2
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
        val jitter = (cappedDelay * jitterFraction * (Random.nextDouble() * 2 - 1)).toLong()
        return (cappedDelay + jitter).coerceAtLeast(500L)
    }
}
