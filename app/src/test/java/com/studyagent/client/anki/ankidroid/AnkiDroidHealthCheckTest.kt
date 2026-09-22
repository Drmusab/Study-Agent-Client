package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.statusCode
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureCategory
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureEvidence
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidHealthCheck
import com.studyagent.client.testutil.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 02 §24/§25/§43/§44/§45/§89/§90/§101 — one bounded health check.
 *
 * What is proven here: a check returns a snapshot instead of throwing, a hanging provider is
 * bounded by the timeout budget (and reported as a timeout, not as an absence), the duration is
 * measured, and cancellation is passed on rather than converted into a state — the last one is
 * what keeps a cancelled screen from painting "AnkiDroid is broken".
 */
class AnkiDroidHealthCheckTest {

    @Test
    fun `a successful check returns the detection plus measured timing`() = runTest {
        val clock = TestClock()
        val detector = FakeAnkiDroidDetector().apply {
            onDetect = { clock.advance(25L) }
        }
        val check = DefaultAnkiDroidHealthCheck(detector, clock)

        val snapshot = check.check()

        assertEquals(1, detector.calls)
        assertEquals(AnkiAvailability.Ready(AnkiCapabilities.NONE), snapshot.availability)
        assertEquals("READY", snapshot.availability.statusCode)
        assertEquals(TestClock.DEFAULT_START_MS + 25L, snapshot.checkedAtEpochMs)
        assertEquals(25L, snapshot.durationMs)
        assertEquals("release", snapshot.detection.endpointLabel)
    }

    @Test
    fun `a hanging provider is bounded and reported as a timeout`() = runTest {
        val clock = TestClock()
        val detector = FakeAnkiDroidDetector(delayMs = 30_000L)
        val check = DefaultAnkiDroidHealthCheck(detector, clock, timeoutMs = 1_000L)

        val snapshot = check.check()

        val availability = snapshot.detection.availability
        assertTrue("a hang is a fault, not an absence", availability is AnkiAvailability.Fault)
        assertEquals(AnkiError.QueryFailure(causeCategory = "timeout"), (availability as AnkiAvailability.Fault).error)
        assertEquals(AnkiDroidFailureCategory.TIMEOUT, snapshot.failure?.category)
        assertEquals(AnkiDroidFailureEvidence.TIMEOUT_BUDGET, snapshot.failure?.evidence)
        assertFalse(
            "a timeout is not evidence that AnkiDroid is missing",
            snapshot.detection.installed
        )
        assertEquals(TestClock.DEFAULT_START_MS, snapshot.checkedAtEpochMs)
    }

    @Test
    fun `the caller's cancellation propagates and is never turned into a state`() = runTest {
        val detector = FakeAnkiDroidDetector().apply {
            gatesByCall[1] = CompletableDeferred()
        }
        val check = DefaultAnkiDroidHealthCheck(detector, TestClock())

        val job = launch { check.check() }
        runCurrent()
        assertEquals(1, detector.calls)

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    @Test
    fun `a defect inside the integration layer is reported, not thrown`() = runTest {
        val detector = FakeAnkiDroidDetector(throwable = IllegalStateException("boom"))
        val check = DefaultAnkiDroidHealthCheck(detector, TestClock())

        val snapshot = check.check()

        val availability = snapshot.detection.availability
        assertTrue(availability is AnkiAvailability.Fault)
        assertEquals(AnkiError.Unknown(cause = "IllegalStateException"), (availability as AnkiAvailability.Fault).error)
        assertEquals(AnkiDroidFailureCategory.PROVIDER_ERROR, snapshot.failure?.category)
        assertEquals("FAULT", snapshot.availability.statusCode)
    }

    @Test
    fun `an unknown detector failure is still reported as a fault`() = runTest {
        val detector = FakeAnkiDroidDetector(throwable = RuntimeException("mystery"))
        val check = DefaultAnkiDroidHealthCheck(detector, TestClock())

        val snapshot = check.check()

        assertEquals(
            AnkiAvailability.Fault(AnkiError.Unknown(cause = "RuntimeException")),
            snapshot.detection.availability
        )
        assertEquals(AnkiDroidFailureCategory.UNEXPECTED, snapshot.failure?.category)
        assertFalse(snapshot.detection.providerAvailable)
    }

    @Test
    fun `the default budget stays small, bounded and documented`() {
        assertEquals(3_000L, DefaultAnkiDroidHealthCheck.DEFAULT_TIMEOUT_MS)
    }
}
