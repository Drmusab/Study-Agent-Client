package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.statusCode
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureCategory
import com.studyagent.client.data.anki.ankidroid.AnkiDroidHealthCheck
import com.studyagent.client.data.anki.ankidroid.AnkiDroidHealthPublicationGuard
import com.studyagent.client.data.anki.ankidroid.AnkiDroidHealthRepository
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidHealthCheck
import com.studyagent.client.testutil.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 02 §26/§27/§28/§30/§45/§46/§47/§57/§59/§102 — the single runtime owner of AnkiDroid
 * availability.
 *
 * The interesting failures of a health owner are not "does it return a value": they are stale
 * results overwriting fresh ones, overlapping provider calls, refresh loops, work that outlives
 * its owner, and a first frame blocked on an optional integration. Those are what this test
 * targets.
 */
class AnkiDroidHealthRepositoryTest {

    private fun TestScope.repository(
        check: AnkiDroidHealthCheck,
        clock: TestClock = TestClock(),
        minRefreshIntervalMs: Long = AnkiDroidHealthRepository.DEFAULT_MIN_REFRESH_INTERVAL_MS
    ): AnkiDroidHealthRepository =
        AnkiDroidHealthRepository(check, backgroundScope, clock, minRefreshIntervalMs)

    @Test
    fun `before the first check the state is checking, never ready and never failed`() = runTest {
        val repository = repository(FakeAnkiDroidHealthCheck())

        assertEquals(AnkiAvailability.Checking, repository.health.value.availability)
        assertEquals("CHECKING", repository.health.value.availability.statusCode)
        assertEquals(AnkiAvailability.Checking, repository.availability.value)
    }

    @Test
    fun `a refresh publishes the snapshot and the derived availability`() = runTest {
        val detector = FakeAnkiDroidDetector()
        val repository = repository(DefaultAnkiDroidHealthCheck(detector, TestClock()))

        val snapshot = repository.refresh()
        advanceUntilIdle()

        assertEquals(AnkiAvailability.Ready(AnkiCapabilities.NONE), snapshot.availability)
        assertEquals(snapshot, repository.health.value)
        assertEquals(AnkiAvailability.Ready(AnkiCapabilities.NONE), repository.availability.value)
        assertEquals("READY", repository.availability.value.statusCode)
    }

    @Test
    fun `fire-and-forget refresh completes and publishes like a direct refresh`() = runTest {
        val detector = FakeAnkiDroidDetector()
        val repository = repository(DefaultAnkiDroidHealthCheck(detector, TestClock()))

        repository.requestRefresh().join()
        advanceUntilIdle()

        assertEquals(1, detector.calls)
        assertEquals(AnkiAvailability.Ready(AnkiCapabilities.NONE), repository.health.value.availability)
    }

    @Test
    fun `a superseded result can never be observed`() = runTest {
        val clock = TestClock()
        val stale = testDetection(
            availability = AnkiAvailability.NotInstalled,
            installed = false,
            packageName = null,
            providerAvailable = false,
            endpointLabel = null,
            authority = null,
            providerSpec = null,
            providerSpecKnown = false,
            permissionGranted = null
        )
        val fresh = testDetection(AnkiAvailability.Ready(AnkiCapabilities.NONE), collectionReady = true)
        val detector = FakeAnkiDroidDetector().apply {
            resultsByCall[1] = stale
            resultsByCall[2] = fresh
            gatesByCall[1] = CompletableDeferred()
            gatesByCall[2] = CompletableDeferred()
        }
        val repository = repository(DefaultAnkiDroidHealthCheck(detector, clock))

        val observed = mutableListOf<AnkiAvailability>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.health.collect { observed += it.availability }
        }

        val first = backgroundScope.launch { repository.refresh() }
        val second = backgroundScope.launch { repository.refresh() }
        runCurrent()
        assertEquals("both requests are registered before either check finishes", 1, detector.calls)

        // The older check finishes first — but its request was already superseded.
        detector.gatesByCall[1]!!.complete(Unit)
        runCurrent()
        detector.gatesByCall[2]!!.complete(Unit)
        runCurrent()
        advanceUntilIdle()

        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
        assertEquals("only the newest result may be published", fresh.availability, repository.health.value.availability)
        assertFalse("a stale availability was published", observed.contains(AnkiAvailability.NotInstalled))
        assertEquals(1, detector.maxConcurrentDetects)
    }

    @Test
    fun `concurrent refresh requests never overlap on the provider`() = runTest {
        val check = FakeAnkiDroidHealthCheck(delayMs = 50L)
        val repository = repository(check)

        val jobs = (1..8).map { backgroundScope.launch { repository.refresh() } }
        advanceUntilIdle()

        assertEquals(8, check.calls)
        assertEquals("at most one provider check at a time", 1, check.maxConcurrentChecks)
        assertTrue(jobs.all { it.isCompleted })
        assertEquals(AnkiAvailability.Ready(AnkiCapabilities.NONE), repository.health.value.availability)
    }

    @Test
    fun `foreground refresh runs at most once per debounce window`() = runTest {
        val clock = TestClock()
        val detector = FakeAnkiDroidDetector()
        val repository = repository(DefaultAnkiDroidHealthCheck(detector, clock), clock)

        repository.onAppForeground()
        advanceUntilIdle()
        assertEquals(1, detector.calls)

        repository.onAppForeground()
        advanceUntilIdle()
        assertEquals("the resume burst collapses into the check that just ran", 1, detector.calls)

        clock.advance(AnkiDroidHealthRepository.DEFAULT_MIN_REFRESH_INTERVAL_MS + 1L)
        repository.onAppForeground()
        advanceUntilIdle()
        assertEquals(2, detector.calls)
    }

    @Test
    fun `an unrelated OnStart must not be able to starve the first check`() = runTest {
        val clock = TestClock()
        val detector = FakeAnkiDroidDetector(delayMs = 10L)
        val repository = repository(DefaultAnkiDroidHealthCheck(detector, clock), clock)

        // Activity onStart fires twice in quick succession during startup (create + resume).
        repository.onAppForeground()
        repository.onAppForeground()
        advanceUntilIdle()

        assertEquals("startup still produces a result", 1, detector.calls)
        assertTrue(repository.health.value.availability is AnkiAvailability.Ready)
    }

    @Test
    fun `installing AnkiDroid and returning to the app recovers without a restart`() = runTest {
        val clock = TestClock()
        val detector = FakeAnkiDroidDetector()
        val repository = repository(DefaultAnkiDroidHealthCheck(detector, clock), clock)

        detector.result = testDetection(
            availability = AnkiAvailability.NotInstalled,
            installed = false,
            packageName = null,
            providerAvailable = false,
            endpointLabel = null,
            authority = null,
            providerSpec = null,
            providerSpecKnown = false,
            permissionGranted = null
        )
        repository.refresh()
        assertEquals(AnkiAvailability.NotInstalled, repository.health.value.availability)

        // §112.7: the user installs AnkiDroid, completes first-run setup, comes back.
        detector.result = testDetection(AnkiAvailability.Ready(AnkiCapabilities.NONE), collectionReady = true)
        clock.advance(5_000L)
        repository.onAppForeground()
        advanceUntilIdle()

        assertEquals(AnkiAvailability.Ready(AnkiCapabilities.NONE), repository.health.value.availability)
        assertEquals("READY", repository.availability.value.statusCode)
        assertEquals(true, repository.health.value.collectionReady)
    }

    @Test
    fun `cancelling a refresh leaves the previous state intact and does not wedge the owner`() =
        runTest {
            val detector = FakeAnkiDroidDetector().apply {
                gatesByCall[1] = CompletableDeferred()
            }
            val repository = repository(DefaultAnkiDroidHealthCheck(detector, TestClock()))

            val job = backgroundScope.launch { repository.refresh() }
            runCurrent()
            assertEquals(1, detector.calls)

            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertEquals(
                "cancellation is not a health state",
                AnkiAvailability.Checking,
                repository.health.value.availability
            )

            // Nothing is stuck behind the cancelled check: the next refresh completes normally.
            repository.refresh()
            assertEquals(AnkiAvailability.Ready(AnkiCapabilities.NONE), repository.health.value.availability)
        }

    @Test
    fun `a defect in the check becomes a fault snapshot instead of a crash`() = runTest {
        val check = FakeAnkiDroidHealthCheck(throwable = IllegalStateException("boom"))
        val repository = repository(check)

        val snapshot = repository.refresh()

        assertTrue(snapshot.availability is AnkiAvailability.Fault)
        assertEquals(AnkiDroidFailureCategory.PROVIDER_ERROR, snapshot.failure?.category)
        assertEquals("FAULT", repository.health.value.availability.statusCode)
    }

    @Test
    fun `repeated refreshes leak no coroutines`() = runTest {
        val check = FakeAnkiDroidHealthCheck()
        val repository = repository(check)
        advanceUntilIdle()
        val before = backgroundChildJobCount()

        repeat(25) { repository.refresh() }
        advanceUntilIdle()

        assertEquals(25, check.calls)
        assertEquals(
            "health ownership adds no per-refresh coroutines",
            before,
            backgroundChildJobCount()
        )
    }

    @Test
    fun `the publication guard only lets the newest request publish`() {
        val guard = AnkiDroidHealthPublicationGuard()

        val first = guard.newRequest()
        var firstPublications = 0
        guard.publishIfCurrent(first) { firstPublications += 1 }
        assertEquals(1, firstPublications)

        val second = guard.newRequest()
        guard.publishIfCurrent(first) { firstPublications += 1 }
        assertEquals("a superseded result must not publish", 1, firstPublications)

        var secondPublications = 0
        guard.publishIfCurrent(second) { secondPublications += 1 }
        assertEquals(1, secondPublications)
        assertEquals(second, guard.currentRequestId)
    }

    @Test
    fun `health budgets stay bounded and documented`() {
        assertEquals(3_000L, DefaultAnkiDroidHealthCheck.DEFAULT_TIMEOUT_MS)
        assertEquals(2_000L, AnkiDroidHealthRepository.DEFAULT_MIN_REFRESH_INTERVAL_MS)
    }

    /** Children of the test's background scope — a leak in the owner shows up here. */
    private fun TestScope.backgroundChildJobCount(): Int =
        coroutineContext[Job]?.children?.count() ?: 0
}
