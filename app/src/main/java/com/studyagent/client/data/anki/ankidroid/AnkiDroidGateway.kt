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
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * GATE 04 — AnkiDroid gateway boundary (§3/§104).
 *
 * Owns all direct communication with AnkiDroid public API.
 * Converts Android/AnkiDroid responses into Study-Agent domain models.
 * Truthfully reports runtime capabilities and health.
 * Isolates provider/API failures.
 * Prevents Cursor/ContentResolver leakage.
 *
 * Later gates may add queryDecks, queryCard, queryReview, etc.
 * This gate establishes the common foundation.
 *
 * Visibility: internal (§105) — upper layers depend on AnkiBackend, not gateway.
 */
internal interface AnkiDroidGateway {

    suspend fun refreshIntegrationState(): AnkiDroidIntegrationState

    suspend fun probeCapabilities(): AnkiDroidCapabilityProbeResult

    suspend fun probeHealth(): AnkiDroidGatewayHealthSnapshot

    fun currentState(): AnkiDroidIntegrationState
}

/**
 * Implementation that composes:
 * - health probe (which uses health check + capability probe)
 * - provider client (for package version and safe queries)
 * - capability probe
 *
 * Single-flight + stale protection (§35/§36) via generation counter and Mutex.
 * All provider calls off main thread (§31).
 */
internal class DefaultAnkiDroidGateway(
    private val healthProbe: AnkiDroidHealthProbe,
    private val capabilityProbe: AnkiDroidCapabilityProbe,
    private val providerClient: AnkiDroidProviderClient,
    private val endpoints: List<AnkiDroidEndpoint>,
    private val clock: AppClock = SystemAppClock,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider(),
    private val scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + DefaultDispatcherProvider().default
    )
) : AnkiDroidGateway {

    private val mutex = Mutex()
    private var state: AnkiDroidIntegrationState = AnkiDroidIntegrationState.initial(clock.nowMillis())
    private var generation: Long = 0L

    // Single-flight: shared in-flight refresh
    private var inFlightRefresh: kotlinx.coroutines.Deferred<AnkiDroidIntegrationState>? = null
    private val inFlightLock = Any()

    override fun currentState(): AnkiDroidIntegrationState = state

    override suspend fun probeCapabilities(): AnkiDroidCapabilityProbeResult = withContext(dispatchers.io) {
        try {
            // Use latest detection from current health snapshot if available
            val detection = state.healthSnapshot?.detection
                ?: AnkiDroidDetectionResult(
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
                    availability = AnkiAvailability.Checking,
                    capabilities = AnkiCapabilities.NONE,
                    failure = null
                )
            capabilityProbe.probe(detection)
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
    }

    override suspend fun probeHealth(): AnkiDroidGatewayHealthSnapshot = withContext(dispatchers.io) {
        try {
            healthProbe.probe()
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
    }

    override suspend fun refreshIntegrationState(): AnkiDroidIntegrationState {
        // Single-flight: if a refresh is already in flight, await it instead of starting another
        val deferred: kotlinx.coroutines.Deferred<AnkiDroidIntegrationState> = synchronized(inFlightLock) {
            val existing = inFlightRefresh
            if (existing != null && existing.isActive) {
                existing
            } else {
                val newGen: Long
                synchronized(this) {
                    generation += 1
                    newGen = generation
                }
                val d = scope.async(dispatchers.io) {
                    performRefresh(newGen)
                }
                inFlightRefresh = d
                d
            }
        }

        return try {
            deferred.await()
        } finally {
            synchronized(inFlightLock) {
                if (inFlightRefresh === deferred && deferred.isCompleted) {
                    inFlightRefresh = null
                }
            }
        }
    }

    private suspend fun performRefresh(
        requestGen: Long
    ): AnkiDroidIntegrationState {
        try {
            AppLogger.i("AnkiDroidGateway", "ANKI_HEALTH_CHECK_STARTED gen=$requestGen")

            val startMs = clock.nowMillis()

            // Probe health + capabilities (healthProbe internally does both)
            val (healthSnapshot, capResult) = try {
                healthProbe.probeWithDetection()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // Map to fault snapshot
                val failure = AnkiDroidFailureClassifier.classify(throwable, AnkiDroidOperationStage.COLLECTION_PROBE)
                val faultDetection = AnkiDroidDetectionResult(
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
                val faultCap = AnkiDroidCapabilityProbeResult(
                    implemented = AnkiCapabilities.NONE,
                    apiReport = AnkiDroidApiCapabilityReport.UNKNOWN,
                    details = emptyList(),
                    probedAtMs = clock.nowMillis(),
                    latencyMs = 0L
                )
                val faultHealth = AnkiDroidHealthSnapshot(
                    checkedAtEpochMs = clock.nowMillis(),
                    durationMs = 0L,
                    detection = faultDetection
                )
                faultHealth to faultCap
            }

            val detection = healthSnapshot.detection

            // Package version for diagnostics (§47) — best effort, never fails refresh
            val packageVersion = try {
                val pkg = detection.packageName ?: detection.providerFacts?.providerPackage
                if (pkg != null) providerClient.getPackageVersion(pkg) else null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }

            val metadata = AnkiDroidMetadata.fromDetection(detection, packageVersion)

            val lastError: AnkiError? = when (val avail = detection.availability) {
                is AnkiAvailability.Fault -> avail.error
                is AnkiAvailability.Unsupported -> AnkiError.UnsupportedAction("backend_api")
                is AnkiAvailability.ProviderUnavailable -> AnkiError.ProviderUnavailable(detail = avail.detail)
                is AnkiAvailability.PermissionRequired -> AnkiError.PermissionRequired()
                AnkiAvailability.CollectionNotInitialized -> AnkiError.CollectionUnavailable()
                is AnkiAvailability.TemporarilyUnavailable -> AnkiError.QueryFailure("temporarily_unavailable")
                else -> detection.failure?.let { AnkiDroidErrorMapper.mapFailure(it, "health_probe") }
            }

            val newState = AnkiDroidIntegrationState(
                availability = detection.availability,
                capabilities = capResult.implemented,
                apiCapabilities = capResult.apiReport,
                metadata = metadata,
                lastCheckAtMs = healthSnapshot.checkedAtEpochMs,
                latencyMs = healthSnapshot.durationMs,
                lastError = lastError,
                healthSnapshot = healthSnapshot,
                capabilityDetails = capResult.details
            )

            // Stale result protection (§36): only publish if this generation is still current
            val shouldPublish = mutex.withLock {
                if (requestGen >= generation) {
                    state = newState
                    true
                } else {
                    false
                }
            }

            if (shouldPublish) {
                when (newState.availability) {
                    is AnkiAvailability.Ready -> AppLogger.i("AnkiDroidGateway", "ANKI_HEALTH_READY spec=${metadata.providerSpec}")
                    is AnkiAvailability.PermissionRequired -> AppLogger.i("AnkiDroidGateway", "ANKI_PERMISSION_REQUIRED")
                    is AnkiAvailability.ProviderUnavailable -> AppLogger.i("AnkiDroidGateway", "ANKI_PROVIDER_FAILURE provider unavailable")
                    else -> AppLogger.i("AnkiDroidGateway", "ANKI_HEALTH_UPDATED ${newState.availability::class.simpleName}")
                }
                AppLogger.i("AnkiDroidGateway", "ANKI_CAPABILITIES_UPDATED ${capResult.implemented}")
            } else {
                AppLogger.i("AnkiDroidGateway", "ANKI_HEALTH_STALE_DISCARDED gen=$requestGen current=$generation")
            }

            newState
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            AppLogger.w("AnkiDroidGateway", "ANKI_HEALTH_CHECK_FAILED ${throwable::class.java.simpleName}", throwable)
            val fallback = AnkiDroidIntegrationState(
                availability = AnkiAvailability.Fault(AnkiError.Unknown(cause = throwable::class.java.simpleName)),
                capabilities = AnkiCapabilities.NONE,
                apiCapabilities = AnkiDroidApiCapabilityReport.UNKNOWN,
                metadata = null,
                lastCheckAtMs = clock.nowMillis(),
                latencyMs = null,
                lastError = AnkiError.Unknown(cause = throwable::class.java.simpleName),
                healthSnapshot = null,
                capabilityDetails = emptyList()
            )
            // Only publish fallback if generation still current
            mutex.withLock {
                if (requestGen >= generation) {
                    state = fallback
                }
            }
            fallback
        }
    }
}

/**
 * Fake gateway for JVM tests.
 */
internal class FakeAnkiDroidGateway(
    var stateToReturn: AnkiDroidIntegrationState = AnkiDroidIntegrationState.initial(0L),
    var throwable: Throwable? = null
) : AnkiDroidGateway {

    var refreshCalls: Int = 0
        private set
    var capabilityCalls: Int = 0
        private set
    var healthCalls: Int = 0
        private set

    private var internalState: AnkiDroidIntegrationState = stateToReturn

    override fun currentState(): AnkiDroidIntegrationState = internalState

    override suspend fun refreshIntegrationState(): AnkiDroidIntegrationState {
        refreshCalls++
        throwable?.let { throw it }
        internalState = stateToReturn
        return internalState
    }

    override suspend fun probeCapabilities(): AnkiDroidCapabilityProbeResult {
        capabilityCalls++
        throwable?.let { throw it }
        return AnkiDroidCapabilityProbeResult(
            implemented = stateToReturn.capabilities,
            apiReport = stateToReturn.apiCapabilities,
            details = stateToReturn.capabilityDetails,
            probedAtMs = stateToReturn.lastCheckAtMs,
            latencyMs = stateToReturn.latencyMs ?: 0L
        )
    }

    override suspend fun probeHealth(): AnkiDroidGatewayHealthSnapshot {
        healthCalls++
        throwable?.let { throw it }
        return AnkiDroidGatewayHealthSnapshot(
            availability = stateToReturn.availability,
            capabilities = stateToReturn.capabilities,
            apiCapabilities = stateToReturn.apiCapabilities,
            providerSpec = stateToReturn.metadata?.providerSpec,
            packageVersion = stateToReturn.metadata?.packageVersion,
            providerReachable = stateToReturn.metadata?.providerReachable ?: false,
            permissionGranted = stateToReturn.metadata?.permissionGranted == true,
            collectionReady = stateToReturn.metadata?.collectionReady == true,
            checkedAtMs = stateToReturn.lastCheckAtMs,
            latencyMs = stateToReturn.latencyMs,
            lastError = stateToReturn.lastError
        )
    }
}
