package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiError

/**
 * GATE 04 — single source of truth for AnkiDroid integration (§45/§46).
 *
 * Preferred model:
 * AnkiDroidIntegrationState
 *   ├── availability
 *   ├── capabilities (implemented)
 *   ├── apiCapabilities (what provider supports)
 *   ├── metadata
 *   ├── healthSnapshot
 *   └── lastError
 *
 * Then expose derived values. Do not maintain three unrelated mutable stores.
 */
data class AnkiDroidIntegrationState(
    val availability: AnkiAvailability,
    val capabilities: AnkiCapabilities,
    val apiCapabilities: AnkiDroidApiCapabilityReport,
    val metadata: AnkiDroidMetadata?,
    val lastCheckAtMs: Long,
    val latencyMs: Long?,
    val lastError: AnkiError?,
    val healthSnapshot: AnkiDroidHealthSnapshot?,
    val capabilityDetails: List<AnkiDroidCapabilityDetail> = emptyList()
) {
    val isReady: Boolean get() = availability is AnkiAvailability.Ready

    companion object {
        fun initial(clockMs: Long): AnkiDroidIntegrationState = AnkiDroidIntegrationState(
            availability = AnkiAvailability.Checking,
            capabilities = AnkiCapabilities.NONE,
            apiCapabilities = AnkiDroidApiCapabilityReport.UNKNOWN,
            metadata = null,
            lastCheckAtMs = clockMs,
            latencyMs = null,
            lastError = null,
            healthSnapshot = AnkiDroidHealthSnapshot.checking(clockMs),
            capabilityDetails = emptyList()
        )
    }
}

/**
 * Provider metadata useful for diagnostics (§47).
 * Does NOT expose private paths, database path, collection file path.
 */
data class AnkiDroidMetadata(
    val packageName: String?,
    val providerPackage: String?,
    val authority: String?,
    val endpointLabel: String?,
    val providerSpec: Int?,
    val providerSpecSource: AnkiDroidProviderSpecSource?,
    val packageVersion: String?,
    val providerReachable: Boolean,
    val permissionGranted: Boolean?,
    val collectionReady: Boolean?,
    val checkedAuthorities: List<String> = emptyList()
) {
    companion object {
        fun fromDetection(
            detection: AnkiDroidDetectionResult,
            packageVersion: String?
        ): AnkiDroidMetadata = AnkiDroidMetadata(
            packageName = detection.packageName,
            providerPackage = detection.providerFacts?.providerPackage ?: detection.packageName,
            authority = detection.authority,
            endpointLabel = detection.endpointLabel,
            providerSpec = detection.providerSpec,
            providerSpecSource = detection.providerFacts?.providerSpecSource,
            packageVersion = packageVersion,
            providerReachable = detection.providerAvailable,
            permissionGranted = detection.permissionGranted,
            collectionReady = detection.collectionReady,
            checkedAuthorities = detection.checkedAuthorities
        )
    }
}

/**
 * Gateway health snapshot — summarizes runtime operability (§17/§18).
 * Reuses GATE 02 detection but adds GATE 04 fields.
 */
data class AnkiDroidGatewayHealthSnapshot(
    val availability: AnkiAvailability,
    val capabilities: AnkiCapabilities,
    val apiCapabilities: AnkiDroidApiCapabilityReport,
    val providerSpec: Int?,
    val packageVersion: String?,
    val providerReachable: Boolean,
    val permissionGranted: Boolean,
    val collectionReady: Boolean,
    val checkedAtMs: Long,
    val latencyMs: Long?,
    val lastError: AnkiError?
)
