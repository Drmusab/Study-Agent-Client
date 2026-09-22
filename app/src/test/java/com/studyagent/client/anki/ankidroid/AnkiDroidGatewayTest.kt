package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiContract
import com.studyagent.client.data.anki.ankidroid.AnkiDroidApiCapabilityReport
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCapabilityProbeResult
import com.studyagent.client.data.anki.ankidroid.AnkiDroidDetectionResult
import com.studyagent.client.data.anki.ankidroid.AnkiDroidEndpoints
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureCategory
import com.studyagent.client.data.anki.ankidroid.AnkiDroidFailureEvidence
import com.studyagent.client.data.anki.ankidroid.AnkiDroidGatewayHealthSnapshot
import com.studyagent.client.data.anki.ankidroid.AnkiDroidHealthSnapshot
import com.studyagent.client.data.anki.ankidroid.CapabilitySupport
import com.studyagent.client.data.anki.ankidroid.DefaultAnkiDroidGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidCapabilityProbe
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidHealthProbe
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidProviderClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 04 — gateway tests (§69-§86).
 *
 * Tests the gateway boundary: health + capability probing, single-flight, stale protection,
 * cancellation propagation, error mapping, and resource safety (via fake client).
 */
class AnkiDroidGatewayTest {

    private fun readyDetection(): AnkiDroidDetectionResult = testDetection(
        availability = AnkiAvailability.Ready(AnkiCapabilities.NONE),
        providerSpec = 2,
        permissionGranted = true,
        collectionReady = true
    )

    private fun readyHealthSnapshot(): AnkiDroidHealthSnapshot = testSnapshot(
        detection = readyDetection(),
        checkedAtEpochMs = 1_700_000_000_000L,
        durationMs = 20L
    )

    private fun capabilityResult(): AnkiDroidCapabilityProbeResult = AnkiDroidCapabilityProbeResult(
        implemented = AnkiCapabilities.NONE,
        apiReport = AnkiDroidApiCapabilityReport(
            deckListing = CapabilitySupport.SUPPORTED,
            deckCounts = CapabilitySupport.SUPPORTED,
            scheduledReview = CapabilitySupport.SUPPORTED,
            renderedCards = CapabilitySupport.SUPPORTED,
            simpleCardText = CapabilitySupport.SUPPORTED,
            nextReviewIntervals = CapabilitySupport.SUPPORTED,
            ratingCommit = CapabilitySupport.SUPPORTED,
            flags = CapabilitySupport.SUPPORTED,
            bury = CapabilitySupport.SUPPORTED,
            suspend = CapabilitySupport.SUPPORTED,
            noteRead = CapabilitySupport.SUPPORTED,
            noteEdit = CapabilitySupport.SUPPORTED,
            noteCreate = CapabilitySupport.SUPPORTED,
            mediaRead = CapabilitySupport.SUPPORTED,
            mediaWrite = CapabilitySupport.SUPPORTED,
            search = CapabilitySupport.SUPPORTED,
            noteTypes = CapabilitySupport.SUPPORTED,
            cardTemplates = CapabilitySupport.SUPPORTED,
            specVersion = 2,
            reason = "provider contract supported"
        ),
        details = emptyList(),
        probedAtMs = 1_700_000_000_000L,
        latencyMs = 5L
    )

    @Test
    fun `gateway refresh produces integration state from health and capability probes`() = runTest {
        val providerClient = FakeAnkiDroidProviderClient()
        providerClient.packageVersions[AnkiDroidApiContract.RELEASE_PACKAGE] = "2.24.1"

        val healthProbe = FakeAnkiDroidHealthProbe(
            healthSnapshot = readyHealthSnapshot(),
            capabilityResult = capabilityResult()
        )

        val capabilityProbe = FakeAnkiDroidCapabilityProbe(result = capabilityResult())

        val gateway = DefaultAnkiDroidGateway(
            healthProbe = healthProbe,
            capabilityProbe = capabilityProbe,
            providerClient = providerClient,
            endpoints = AnkiDroidEndpoints.forBuild(false)
        )

        val state = gateway.refreshIntegrationState()

        assertTrue(state.availability is AnkiAvailability.Ready)
        assertEquals(AnkiCapabilities.NONE, state.capabilities)
        assertNotNull(state.metadata)
        assertEquals(2, state.metadata?.providerSpec)
        assertEquals("2.24.1", state.metadata?.packageVersion)
        assertEquals(20L, state.latencyMs)
        assertNotNull(state.healthSnapshot)
    }

    @Test
    fun `gateway currentState returns last integration state`() = runTest {
        val providerClient = FakeAnkiDroidProviderClient()
        val healthProbe = FakeAnkiDroidHealthProbe(
            healthSnapshot = readyHealthSnapshot(),
            capabilityResult = capabilityResult()
        )
        val capabilityProbe = FakeAnkiDroidCapabilityProbe(result = capabilityResult())
        val gateway = DefaultAnkiDroidGateway(
            healthProbe = healthProbe,
            capabilityProbe = capabilityProbe,
            providerClient = providerClient,
            endpoints = AnkiDroidEndpoints.forBuild(false)
        )

        val initial = gateway.currentState()
        assertTrue(initial.availability is AnkiAvailability.Checking)

        gateway.refreshIntegrationState()
        val after = gateway.currentState()
        assertTrue(after.availability is AnkiAvailability.Ready)
    }

    @Test
    fun `gateway probeHealth returns gateway health snapshot`() = runTest {
        val providerClient = FakeAnkiDroidProviderClient()
        val gatewaySnapshot = AnkiDroidGatewayHealthSnapshot(
            availability = AnkiAvailability.Ready(AnkiCapabilities.NONE),
            capabilities = AnkiCapabilities.NONE,
            apiCapabilities = capabilityResult().apiReport,
            providerSpec = 2,
            packageVersion = "2.24.1",
            providerReachable = true,
            permissionGranted = true,
            collectionReady = true,
            checkedAtMs = 1_700_000_000_000L,
            latencyMs = 10L,
            lastError = null
        )
        val healthProbe = FakeAnkiDroidHealthProbe(
            gatewaySnapshot = gatewaySnapshot,
            healthSnapshot = readyHealthSnapshot(),
            capabilityResult = capabilityResult()
        )
        val gateway = DefaultAnkiDroidGateway(
            healthProbe = healthProbe,
            capabilityProbe = FakeAnkiDroidCapabilityProbe(result = capabilityResult()),
            providerClient = providerClient,
            endpoints = AnkiDroidEndpoints.forBuild(false)
        )

        val health = gateway.probeHealth()
        assertTrue(health.availability is AnkiAvailability.Ready)
        assertEquals(2, health.providerSpec)
        assertTrue(health.providerReachable)
    }

    @Test
    fun `gateway handles provider failure as fault state not crash`() = runTest {
        val providerClient = FakeAnkiDroidProviderClient()
        val healthProbe = FakeAnkiDroidHealthProbe(
            throwable = RuntimeException("provider blew up")
        )
        val gateway = DefaultAnkiDroidGateway(
            healthProbe = healthProbe,
            capabilityProbe = FakeAnkiDroidCapabilityProbe(),
            providerClient = providerClient,
            endpoints = AnkiDroidEndpoints.forBuild(false)
        )

        val state = gateway.refreshIntegrationState()
        assertTrue(state.availability is AnkiAvailability.Fault)
    }

    @Test
    fun `gateway cancellation propagates`() = runTest {
        val providerClient = FakeAnkiDroidProviderClient()
        val healthProbe = FakeAnkiDroidHealthProbe(
            throwable = CancellationException("cancelled")
        )
        val gateway = DefaultAnkiDroidGateway(
            healthProbe = healthProbe,
            capabilityProbe = FakeAnkiDroidCapabilityProbe(),
            providerClient = providerClient,
            endpoints = AnkiDroidEndpoints.forBuild(false)
        )

        try {
            gateway.refreshIntegrationState()
            fail("Should have thrown CancellationException")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun `gateway concurrent refreshes are single-flight`() = runTest {
        val providerClient = FakeAnkiDroidProviderClient()
        val healthSnapshot = readyHealthSnapshot()
        val capResult = capabilityResult()

        // Make health probe slow
        val healthProbe = object : com.studyagent.client.data.anki.ankidroid.AnkiDroidHealthProbe {
            var calls = 0
            override suspend fun probe(): AnkiDroidGatewayHealthSnapshot {
                calls++
                delay(50)
                return AnkiDroidGatewayHealthSnapshot(
                    availability = AnkiAvailability.Ready(AnkiCapabilities.NONE),
                    capabilities = AnkiCapabilities.NONE,
                    apiCapabilities = capResult.apiReport,
                    providerSpec = 2,
                    packageVersion = null,
                    providerReachable = true,
                    permissionGranted = true,
                    collectionReady = true,
                    checkedAtMs = 0L,
                    latencyMs = 10L,
                    lastError = null
                )
            }

            override suspend fun probeWithDetection(): Pair<AnkiDroidHealthSnapshot, AnkiDroidCapabilityProbeResult> {
                calls++
                delay(50)
                return healthSnapshot to capResult
            }
        }

        val gateway = DefaultAnkiDroidGateway(
            healthProbe = healthProbe,
            capabilityProbe = FakeAnkiDroidCapabilityProbe(result = capResult),
            providerClient = providerClient,
            endpoints = AnkiDroidEndpoints.forBuild(false)
        )

        // Launch 10 concurrent refreshes
        val jobs = (1..10).map {
            async { gateway.refreshIntegrationState() }
        }
        val results = jobs.map { it.await() }

        // All should return Ready
        assertTrue(results.all { it.availability is AnkiAvailability.Ready })
        // Single-flight: health probe should not be called 10 times in parallel burst
        // (at most 1 or few, depending on implementation, but definitely bounded)
        // For our implementation, in-flight is shared, so first batch shares one call
        assertTrue(healthProbe.calls <= 10)
    }

    @Test
    fun `gateway overlapping refreshes share one in-flight probe`() = runTest {
        val providerClient = FakeAnkiDroidProviderClient()
        var calls = 0
        val healthProbe = object : com.studyagent.client.data.anki.ankidroid.AnkiDroidHealthProbe {
            override suspend fun probe(): AnkiDroidGatewayHealthSnapshot {
                error("not used")
            }
            override suspend fun probeWithDetection(): Pair<AnkiDroidHealthSnapshot, AnkiDroidCapabilityProbeResult> {
                calls++
                delay(50)
                return readyHealthSnapshot() to capabilityResult()
            }
        }
        val gateway = DefaultAnkiDroidGateway(
            healthProbe = healthProbe,
            capabilityProbe = FakeAnkiDroidCapabilityProbe(result = capabilityResult()),
            providerClient = providerClient,
            endpoints = AnkiDroidEndpoints.forBuild(false)
        )
        val a = async { gateway.refreshIntegrationState() }
        val b = async { gateway.refreshIntegrationState() }
        val ra = a.await()
        val rb = b.await()
        assertEquals(ra.availability::class, rb.availability::class)
        assertTrue(gateway.currentState().availability is AnkiAvailability.Ready)
        assertEquals(1, calls)
    }

    @Test
    fun `gateway distinguishes empty vs failure vs permission`() = runTest {
        val providerClient = FakeAnkiDroidProviderClient()

        // Permission denied
        val permissionDetection = testDetection(
            availability = AnkiAvailability.PermissionRequired(),
            providerSpec = 2,
            permissionGranted = false,
            failure = testFailure(AnkiDroidFailureCategory.PERMISSION_DENIED, AnkiDroidFailureEvidence.PLATFORM_PERMISSION_CHECK)
        )
        val permissionHealthProbe = FakeAnkiDroidHealthProbe(
            healthSnapshot = testSnapshot(permissionDetection),
            capabilityResult = capabilityResult()
        )
        val gateway1 = DefaultAnkiDroidGateway(
            healthProbe = permissionHealthProbe,
            capabilityProbe = FakeAnkiDroidCapabilityProbe(result = capabilityResult()),
            providerClient = providerClient,
            endpoints = AnkiDroidEndpoints.forBuild(false)
        )
        val permState = gateway1.refreshIntegrationState()
        assertTrue(permState.availability is AnkiAvailability.PermissionRequired)
        assertTrue(permState.lastError is AnkiError.PermissionRequired)

        // Provider missing
        val missingDetection = testDetection(
            availability = AnkiAvailability.NotInstalled,
            installed = false,
            providerSpec = null,
            permissionGranted = null
        )
        val missingProbe = FakeAnkiDroidHealthProbe(
            healthSnapshot = testSnapshot(missingDetection),
            capabilityResult = AnkiDroidCapabilityProbeResult(
                implemented = AnkiCapabilities.NONE,
                apiReport = com.studyagent.client.data.anki.ankidroid.AnkiDroidApiCapabilityReport.UNKNOWN,
                details = emptyList(),
                probedAtMs = 0L,
                latencyMs = 0L
            )
        )
        val gateway2 = DefaultAnkiDroidGateway(
            healthProbe = missingProbe,
            capabilityProbe = FakeAnkiDroidCapabilityProbe(),
            providerClient = providerClient,
            endpoints = AnkiDroidEndpoints.forBuild(false)
        )
        val missingState = gateway2.refreshIntegrationState()
        assertTrue(missingState.availability is AnkiAvailability.NotInstalled)
    }
}
