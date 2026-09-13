package com.studyagent.client.core

import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.LogLevel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The log buffer is a fixed-size, privacy-safe, cheap-to-append ring (§20/§21/§71-§76).
 *
 * Why this is worth a suite of its own: the logger is the component every other component uses, it
 * runs on the voice path, and it used to be the most expensive object in the app — a full `List`
 * copy per appended row, a `SimpleDateFormat` per rendered row, unbounded storage and no
 * redaction. The three properties that must never regress:
 *
 * 1. **Bounded** — a multi-hour session leaves the same footprint as a short one.
 * 2. **Cheap** — appending must not become the thing it measures (see the timing guards below).
 * 3. **Safe** — a token, header or payload that reaches the logger must not reach the buffer, an
 *    export, or logcat.
 */
class AppLoggerHardeningTest {

    private var debugWasEnabled = false

    @Before
    fun setUp() {
        // These tests are *about* logging, so DEBUG rows must actually be produced.
        debugWasEnabled = AppLogger.isDebugEnabled
        AppLogger.isDebugEnabled = true
        AppLogger.clear()
        AppLogger.setClockForTests { 1_700_000_000_000L }
    }

    @After
    fun tearDown() {
        AppLogger.clear()
        AppLogger.isDebugEnabled = debugWasEnabled
    }

    // ------------------------------------------------------------------ bounded

    @Test
    fun `ten thousand rows leave exactly the bounded window`() {
        repeat(10_000) { index -> AppLogger.d("Load", "row $index") }

        assertEquals(AppLogger.MAX_LOG_ENTRIES, AppLogger.size)
        val rows = AppLogger.snapshot()
        assertEquals(AppLogger.MAX_LOG_ENTRIES, rows.size)
        // The newest row is retained and the oldest rows were rotated out.
        assertTrue("the newest row must survive", rows.last().message == "row 9999")
        assertTrue("the oldest row must be gone", rows.none { it.message == "row 0" })
    }

    @Test
    fun `every row has a unique, monotonic sequence`() {
        repeat(1_000) { AppLogger.i("Seq", "row $it") }
        val sequences = AppLogger.snapshot().map { it.sequence }
        assertEquals("ids must be unique", sequences.size, sequences.toSet().size)
        assertEquals("ids must be ordered oldest → newest", sequences.sorted(), sequences)
        assertNotNull("a row must carry a formatted time", AppLogger.snapshot().first().formattedTime)
    }

    @Test
    fun `appending stays linear in the number of rows`() {
        // Timing guards, not benchmarks: they exist to catch a return to O(n) per append (the
        // previous implementation copied the whole buffer on every row) and nothing else. The
        // limits are two orders of magnitude above what a JVM needs for this work, so a slow CI
        // machine cannot fail them — a quadratic implementation will.
        val time100 = measureAppends(100)
        val time1k = measureAppends(1_000)
        val time10k = measureAppends(10_000)

        assertTrue("100 appends took ${time100}ms", time100 < 500)
        assertTrue("1,000 appends took ${time1k}ms", time1k < 2_000)
        assertTrue("10,000 appends took ${time10k}ms", time10k < 10_000)
        // 100× the rows must not cost anywhere near 100× the per-row time.
        assertTrue(
            "per-row cost must not grow with buffer size (100 rows: ${time100}ms, 10k rows: ${time10k}ms)",
            time10k <= (time100.coerceAtLeast(1) * 100L) + 2_000L
        )
    }

    private fun measureAppends(count: Int): Long {
        AppLogger.clear()
        val start = System.nanoTime()
        repeat(count) { AppLogger.d("Load", "measured row $it") }
        return (System.nanoTime() - start) / 1_000_000
    }

    // ------------------------------------------------------------------ privacy

    @Test
    fun `a bearer token never reaches the buffer`() {
        AppLogger.i("Net", "Sending: Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payloadpart.signature")
        AppLogger.w("Net", "token=first-secret-token")
        AppLogger.e("Net", """{"api_key":"fake-api-key-do-not-leak","ok":true}""")
        AppLogger.i("Auth", "password=hunter2-not-a-real-password")

        val everything = AppLogger.snapshot().joinToString(" ") { it.message }
        assertFalse("a bearer token leaked", everything.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertFalse("a query token leaked", everything.contains("first-secret-token"))
        assertFalse("an API key leaked", everything.contains("fake-api-key-do-not-leak"))
        assertFalse("a password leaked", everything.contains("hunter2-not-a-real-password"))
        assertTrue("redaction must be visible, not silent", everything.contains("***REDACTED***"))
    }

    @Test
    fun `redaction does not destroy ordinary engineering messages`() {
        AppLogger.i("Study", "SESSION_TRANSITION from=WaitingForAnswer to=WaitingForEvaluation")
        AppLogger.i("TTS", "TTS metrics: completed=12 failed=0 cancelled=1")
        AppLogger.i("STT", "Final transcript chars=57 candidates=3 conf=0.92")

        val everything = AppLogger.snapshot().joinToString(" ") { it.message }
        assertTrue(everything.contains("WaitingForEvaluation"))
        assertTrue(everything.contains("completed=12"))
        assertTrue(everything.contains("conf=0.92"))
    }

    @Test
    fun `an over-long message is truncated instead of stored whole`() {
        AppLogger.i("Big", "x".repeat(200_000))
        val row = AppLogger.snapshot().single()
        assertTrue(
            "a 200 KB payload must not become a 200 KB log row (was ${row.message.length})",
            row.message.length <= AppLogger.MAX_MESSAGE_CHARS + 16
        )
    }

    // ------------------------------------------------------------------ policy

    @Test
    fun `debug rows are dropped when debug logging is disabled`() {
        AppLogger.isDebugEnabled = false
        AppLogger.d("Quiet", "this must not be retained")
        AppLogger.i("Quiet", "this must be retained")

        val rows = AppLogger.snapshot()
        assertEquals(1, rows.size)
        assertEquals(LogLevel.INFO, rows.single().level)
    }

    @Test
    fun `a burst of info rows is published once, and a warning is never delayed`() {
        // Rapid-fire INFO rows within the coalescing window must not push a new list into the
        // Compose frame loop for every row.
        repeat(100) { AppLogger.i("Burst", "routine row $it") }
        val publishedAfterBurst = AppLogger.logsFlow.value.size

        AppLogger.w("Burst", "something went wrong")
        val publishedAfterWarning = AppLogger.logsFlow.value.size

        assertTrue("the burst must be coalesced, not published row by row", publishedAfterBurst <= 1)
        assertTrue(
            "a WARN must be visible immediately (after burst=$publishedAfterBurst, after warn=$publishedAfterWarning)",
            publishedAfterWarning >= 101
        )
    }

    @Test
    fun `flush publishes the coalesced rows`() {
        repeat(50) { AppLogger.i("Flush", "row $it") }
        AppLogger.flush()
        assertTrue(AppLogger.logsFlow.value.size >= 50)
    }

    @Test
    fun `clearing empties both the buffer and the published view`() {
        repeat(20) { AppLogger.i("Clear", "row $it") }
        AppLogger.flush()
        assertTrue(AppLogger.logsFlow.value.isNotEmpty())

        AppLogger.clear()

        assertEquals(0, AppLogger.size)
        assertTrue(AppLogger.logsFlow.value.isEmpty())
    }

    @Test
    fun `rows render as readable single lines`() {
        AppLogger.i("Study", "hello")
        val row = AppLogger.snapshot().single()
        assertEquals("[${row.formattedTime}] [INFO] Study: hello", row.displayString)
        assertTrue("time must be HH:mm:ss.SSS", Regex("""\d{2}:\d{2}:\d{2}\.\d{3}""").containsMatchIn(row.formattedTime))
    }
}
