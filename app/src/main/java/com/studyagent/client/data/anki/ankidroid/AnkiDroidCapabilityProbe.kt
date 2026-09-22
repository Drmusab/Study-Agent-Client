package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.core.common.DispatcherProvider
import com.studyagent.client.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * GATE 04 — capability probe (§14).
 *
 * Focused logic that probes AnkiCapabilities from:
 * - API spec / provider metadata
 * - small read-only probes (no mutation, §15)
 * - runtime readiness
 *
 * Never mutates collection (no test card, no rating, no bury, §15).
 * Avoids expensive queries (no full collection scan, §16).
 *
 * Capabilities must be truthful (§11): true only when
 * supported by current API contract + required access present + implementation exists.
 */
internal interface AnkiDroidCapabilityProbe {
    suspend fun probe(detection: AnkiDroidDetectionResult): AnkiDroidCapabilityProbeResult
}

data class AnkiDroidCapabilityProbeResult(
    val implemented: AnkiCapabilities,
    val apiReport: AnkiDroidApiCapabilityReport,
    val details: List<AnkiDroidCapabilityDetail>,
    val probedAtMs: Long,
    val latencyMs: Long
)

internal class DefaultAnkiDroidCapabilityProbe(
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) : AnkiDroidCapabilityProbe {

    override suspend fun probe(detection: AnkiDroidDetectionResult): AnkiDroidCapabilityProbeResult =
        withContext(dispatchers.io) {
            try {
                val startMs = clock()

                // Capability detection is NOT backend-name detection (§9).
                // Must come from API contract + runtime readiness + verified feature availability.
                val spec = detection.providerSpec
                val isReady = detection.availability is AnkiAvailability.Ready

                val apiReport = AnkiDroidCompatibilityPolicy.apiCapabilitiesForSpec(spec)

                // GATE 05: deckListing is implemented when Ready; review/media/edit stay false.
                val implemented = AnkiDroidCompatibilityPolicy.implementedCapabilitiesFor(spec, isReady)

                val details = buildDetails(apiReport, implemented, detection)

                val endMs = clock()
                val result = AnkiDroidCapabilityProbeResult(
                    implemented = implemented,
                    apiReport = apiReport,
                    details = details,
                    probedAtMs = endMs,
                    latencyMs = (endMs - startMs).coerceAtLeast(0L)
                )

                AppLogger.i(
                    "AnkiDroidCapabilityProbe",
                    "ANKI_CAPABILITIES_UPDATED spec=${spec ?: "unknown"} ready=$isReady implemented=$implemented api=${apiReport.reason}"
                )

                result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                AppLogger.w(
                    "AnkiDroidCapabilityProbe",
                    "ANKI_CAPABILITY_PROBE_FAILED ${throwable::class.java.simpleName}",
                    throwable
                )
                AnkiDroidCapabilityProbeResult(
                    implemented = AnkiCapabilities.NONE,
                    apiReport = AnkiDroidApiCapabilityReport.UNKNOWN,
                    details = emptyList(),
                    probedAtMs = clock(),
                    latencyMs = 0L
                )
            }
        }

    private fun buildDetails(
        apiReport: AnkiDroidApiCapabilityReport,
        implemented: AnkiCapabilities,
        detection: AnkiDroidDetectionResult
    ): List<AnkiDroidCapabilityDetail> {
        // The rows are built from the *same* AnkiCapabilities the probe publishes, so a row can
        // never claim more than the backend actually offers (GATE 06 replaced the previous
        // name-matching, whose keys did not line up with the report's own row names and therefore
        // reported every row as unimplemented).
        return apiReport.toCapabilityDetailList(implemented).map { detail ->
            val reason = when {
                detection.availability !is AnkiAvailability.Ready ->
                    "backend not ready: ${detection.availability::class.simpleName}"
                detail.apiSupport == CapabilitySupport.UNSUPPORTED ->
                    "API unsupported at spec ${apiReport.specVersion}"
                detail.maturity == CapabilityMaturity.API_SUPPORTED_NOT_IMPLEMENTED ->
                    "API supported, Study-Agent implementation pending"
                else -> "implemented; not yet verified on a real device"
            }
            detail.copy(reason = reason)
        }
    }
}

/**
 * Fake capability probe for JVM tests.
 */
internal class FakeAnkiDroidCapabilityProbe(
    var result: AnkiDroidCapabilityProbeResult = AnkiDroidCapabilityProbeResult(
        implemented = AnkiCapabilities.NONE,
        apiReport = AnkiDroidApiCapabilityReport.UNKNOWN,
        details = emptyList(),
        probedAtMs = 0L,
        latencyMs = 0L
    ),
    var throwable: Throwable? = null
) : AnkiDroidCapabilityProbe {

    var calls: Int = 0
        private set

    override suspend fun probe(detection: AnkiDroidDetectionResult): AnkiDroidCapabilityProbeResult {
        calls++
        throwable?.let { throw it }
        return result
    }
}
