package com.studyagent.client.data

import com.studyagent.client.core.models.DataFreshness
import com.studyagent.client.data.repository.FreshnessPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §14/§129: freshness labels must be honest — cached data is never presented
 * as live, and stale data says so.
 */
class FreshnessPolicyTest {

    private val now = 1_000_000_000_000L

    @Test
    fun `no data is unavailable unless refreshing`() {
        val idle = FreshnessPolicy.evaluate(hasData = false, isConnected = true, updatedAtEpochMs = null, nowMs = now)
        assertEquals(DataFreshness.Unavailable, idle)

        val loading = FreshnessPolicy.evaluate(hasData = false, isConnected = true, updatedAtEpochMs = null, nowMs = now, isRefreshing = true)
        assertEquals(DataFreshness.Loading, loading)
    }

    @Test
    fun `fresh data on a live connection is Live`() {
        val freshness = FreshnessPolicy.evaluate(
            hasData = true,
            isConnected = true,
            updatedAtEpochMs = now - 30_000L,
            nowMs = now
        )
        assertTrue(freshness is DataFreshness.Live)
    }

    @Test
    fun `old data while connected is Stale, never Live`() {
        val freshness = FreshnessPolicy.evaluate(
            hasData = true,
            isConnected = true,
            updatedAtEpochMs = now - FreshnessPolicy.STALE_CONNECTED_AFTER_MS - 1,
            nowMs = now
        )
        assertTrue(freshness is DataFreshness.Stale)
    }

    @Test
    fun `disconnected data is labelled Cached`() {
        val freshness = FreshnessPolicy.evaluate(
            hasData = true,
            isConnected = false,
            updatedAtEpochMs = now - 12 * 60_000L,
            nowMs = now
        )
        assertTrue(freshness is DataFreshness.Cached)
        assertEquals(now - 12 * 60_000L, freshness.updatedAtEpochMs)
    }

    @Test
    fun `very old disconnected data degrades to Stale`() {
        val freshness = FreshnessPolicy.evaluate(
            hasData = true,
            isConnected = false,
            updatedAtEpochMs = now - FreshnessPolicy.STALE_CACHED_AFTER_MS - 1,
            nowMs = now
        )
        assertTrue(freshness is DataFreshness.Stale)
    }

    @Test
    fun `reconnect with fresh response transitions Cached to Live`() {
        val updated = now - 10_000L
        val beforeReconnect = FreshnessPolicy.evaluate(true, isConnected = false, updatedAtEpochMs = updated, nowMs = now)
        val afterReconnect = FreshnessPolicy.evaluate(true, isConnected = true, updatedAtEpochMs = updated, nowMs = now)
        assertTrue(beforeReconnect is DataFreshness.Cached)
        assertTrue(afterReconnect is DataFreshness.Live)
    }
}
