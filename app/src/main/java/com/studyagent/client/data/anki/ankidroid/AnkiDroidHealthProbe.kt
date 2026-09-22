package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.common.AppClock
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.common.DefaultDispatcherProvider
import com.studyagent.client.core.common.SystemAppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * GATE 04 — health probe (§17-§21).
 *
 * Health summarizes runtime operability and is side-effect free (§21):
 * no scheduler advance, no rating commit, no deck change, no note modification.
 *
 * Health != Capabilities (§18):
 * READY means endpoint reachable + permission + collection usable + core contract supported.
 * It does NOT mean every future feature has been implemented (§19).
 *
 * Reuses GATE 02 health models but exposes GATE 04 snapshot with:
 * availability, capabilities, providerSpec, packageVersion, providerReachable,
 * permissionGranted, collectionReady, checkedAt, latencyMs, lastError.
 */
internal interface AnkiDroidHealthProbe {
    suspend fun probe(): AnkiDroidGatewayHealthSnapshot
    suspend fun probeWithDetection(): Pair<AnkiDroidHealthSnapshot, AnkiDroidCapabilityProbeResult>
}

internal class DefaultAnkiDroidHealthProbe(
    private val healthCheck: AnkiDroidHealthCheck,
    private val capabilityProbe: AnkiDroidCapabilityProbe,
    private val providerClient: AnkiDroidProviderClient,
    private val endpoints: List<AnkiDroidEndpoint>,
    private val clock: AppClock = SystemAppClock,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider()
) : AnkiDroidHealthProbe {

    override suspend fun probe(): AnkiDroidGatewayHealthSnapshot = withContext(dispatchers.io) {
        try {
            val (snapshot, capResult) = probeWithDetectionInternal()
            toGatewaySnapshot(snapshot, capResult)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            AppLogger.w(
                "AnkiDroidHealthProbe",
                "ANKI_PROVIDER_FAILURE ${throwable::class.java.simpleName}",
                throwable
            )
            AnkiDroidGatewayHealthSnapshot(
                availability = AnkiAvailability.Fault(AnkiError.Unknown(cause = throwable::class.java.simpleName)),
                capabilities = AnkiCapabilities.NONE,
                apiCapabilities = AnkiDroidApiCapabilityReport.UNKNOWN,
                providerSpec = null,
                packageVersion = null,
                providerReachable = false,
                permissionGranted = false,
                collectionReady = false,
                checkedAtMs = clock.nowMillis(),
                latencyMs = null,
                lastError = AnkiError.Unknown(cause = throwable::class.java.simpleName)
            )
        }
    }

    override suspend fun probeWithDetection(): Pair<AnkiDroidHealthSnapshot, AnkiDroidCapabilityProbeResult> =
        withContext(dispatchers.io) {
            probeWithDetectionInternal()
        }

    private suspend fun probeWithDetectionInternal(): Pair<AnkiDroidHealthSnapshot, AnkiDroidCapabilityProbeResult> {
        val snapshot: AnkiDroidHealthSnapshot = try {
            healthCheck.check()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // Health check guarantees not to throw, but last defense (§90)
            val failure = AnkiDroidFailureClassifier.classify(throwable, AnkiDroidOperationStage.COLLECTION_PROBE)
            AnkiDroidHealthSnapshot(
                checkedAtEpochMs = clock.nowMillis(),
                durationMs = 0L,
                detection = AnkiDroidDetectionResult(
                    installed = false,
                    packageName = null,
                    providerAvailable = false,
                    endpointLabel = null,
                    authority = null,
                    checkedAuthorities = emptyList(),
                    providerFacts = null,
                    providerSpec = null,
                    providerSpecKnown = false,
                    permissionGranted = null,
                    permissionProtectionLevel = null,
                    collectionReady = null,
                    availability = AnkiAvailability.Fault(AnkiError.Unknown(cause = failure.exceptionClass)),
                    capabilities = AnkiCapabilities.NONE,
                    failure = failure
                )
            )
        }

        val capResult = try {
            capabilityProbe.probe(snapshot.detection)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AnkiDroidCapabilityProbeResult(
                implemented = AnkiCapabilities.NONE,
                apiReport = AnkiDroidApiCapabilityReport.UNKNOWN,
                details = emptyList(),
                probedAtMs = clock.nowMillis(),
                latencyMs = 0L
            )
        }

        return snapshot to capResult
    }

    private suspend fun toGatewaySnapshot(
        snapshot: AnkiDroidHealthSnapshot,
        capResult: AnkiDroidCapabilityProbeResult
    ): AnkiDroidGatewayHealthSnapshot {
        val detection = snapshot.detection
        val packageVersion = try {
            val pkg = detection.packageName ?: detection.providerFacts?.providerPackage
            if (pkg != null) providerClient.getPackageVersion(pkg) else null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

        val availability = detection.availability
        val lastError: AnkiError? = when (availability) {
            is AnkiAvailability.Fault -> availability.error
            is AnkiAvailability.Unsupported -> AnkiError.UnsupportedAction("backend_api")
            is AnkiAvailability.ProviderUnavailable -> AnkiError.ProviderUnavailable(detail = availability.detail)
            is AnkiAvailability.PermissionRequired -> AnkiError.PermissionRequired()
            AnkiAvailability.CollectionNotInitialized -> AnkiError.CollectionUnavailable()
            is AnkiAvailability.TemporarilyUnavailable -> AnkiError.QueryFailure("temporarily_unavailable")
            else -> detection.failure?.let { AnkiDroidErrorMapper.mapFailure(it, "health_probe") }
        }

        return AnkiDroidGatewayHealthSnapshot(
            availability = availability,
            capabilities = capResult.implemented,
            apiCapabilities = capResult.apiReport,
            providerSpec = detection.providerSpec,
            packageVersion = packageVersion,
            providerReachable = detection.providerAvailable,
            permissionGranted = detection.permissionGranted == true,
            collectionReady = detection.collectionReady == true,
            checkedAtMs = snapshot.checkedAtEpochMs,
            latencyMs = snapshot.durationMs,
            lastError = lastError
        )
    }
}

/**
 * Fake health probe for tests.
 */
internal class FakeAnkiDroidHealthProbe(
    var gatewaySnapshot: AnkiDroidGatewayHealthSnapshot = AnkiDroidGatewayHealthSnapshot(
        availability = AnkiAvailability.Ready(AnkiCapabilities.NONE),
        capabilities = AnkiCapabilities.NONE,
        apiCapabilities = AnkiDroidApiCapabilityReport.UNKNOWN,
        providerSpec = 2,
        packageVersion = "2.24.1",
        providerReachable = true,
        permissionGranted = true,
        collectionReady = true,
        checkedAtMs = 0L,
        latencyMs = 10L,
        lastError = null
    ),
    var healthSnapshot: AnkiDroidHealthSnapshot = AnkiDroidHealthSnapshot.checking(0L),
    var capabilityResult: AnkiDroidCapabilityProbeResult = AnkiDroidCapabilityProbeResult(
        implemented = AnkiCapabilities.NONE,
        apiReport = AnkiDroidApiCapabilityReport.UNKNOWN,
        details = emptyList(),
        probedAtMs = 0L,
        latencyMs = 0L
    ),
    var throwable: Throwable? = null
) : AnkiDroidHealthProbe {

    var calls: Int = 0
        private set

    override suspend fun probe(): AnkiDroidGatewayHealthSnapshot {
        calls++
        throwable?.let { throw it }
        return gatewaySnapshot
    }

    override suspend fun probeWithDetection(): Pair<AnkiDroidHealthSnapshot, AnkiDroidCapabilityProbeResult> {
        calls++
        throwable?.let { throw it }
        return healthSnapshot to capabilityResult
    }
}
