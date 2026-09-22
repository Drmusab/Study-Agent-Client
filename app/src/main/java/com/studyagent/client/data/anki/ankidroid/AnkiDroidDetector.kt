package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiError

/**
 * GATE 02 — "is AnkiDroid here, may we talk to it, and can it answer right now?" (§11/§102).
 *
 * The detector answers only those questions. It never browses decks, never fetches cards and
 * never mutates anything (§11: "Do not put deck/card queries here"; §79/§80/§104).
 */
interface AnkiDroidDetector {

    /** Runs one detection pass. Callers get a result, never an exception (§55/§90). */
    suspend fun detect(): AnkiDroidDetectionResult
}

/**
 * Default detection pass. Pure orchestration over [AnkiDroidProbe] and
 * [AnkiDroidPermissionManager], so every branch below is covered by JVM tests with fakes
 * (GATE 02 §49-§64).
 *
 * ## Order of checks (cheapest first, and each one changes the answer)
 *
 * ```
 * resolve provider authority            (no read, no permission needed)
 *   ├─ nothing resolves → package present?  → ProviderUnavailable : NotInstalled
 *   ├─ resolved but not ours (package mismatch / disabled) → ProviderUnavailable
 *   ├─ spec below minimum  → Unsupported(spec)
 *   ├─ permission not granted → PermissionRequired          (no probe issued)
 *   └─ bounded read probe (selected_deck, 1 row, read-only)
 *         ├─ reached   → Ready            (collection answered)
 *         └─ failed    → mapped by failure category (§21/§61/§55)
 * ```
 *
 * ## Why `Ready` carries [AnkiCapabilities.NONE]
 *
 * GATE 02 validates *reachability*: provider + permission + supported API + a collection that
 * answers a bounded read. It does not validate review, deck listing, media or editing, and an
 * unprobed capability must be reported as not-yet-probed rather than `true` (§95). `Ready` is
 * therefore a statement about the communication foundation, not a promise that study features
 * work yet (§96) — which is also why `AnkiAvailability.isReadyForReview` stays `false` for
 * AnkiDroid until the review gate lands, leaving today's PC study path untouched (§39/§91).
 */
class DefaultAnkiDroidDetector(
    private val probe: AnkiDroidProbe,
    private val permissions: AnkiDroidPermissionManager,
    private val endpoints: List<AnkiDroidEndpoint>
) : AnkiDroidDetector {

    override suspend fun detect(): AnkiDroidDetectionResult {
        val checkedAuthorities = endpoints.map { it.authority }

        for (endpoint in endpoints) {
            val facts = try {
                probe.providerFacts(endpoint)
            } catch (throwable: Throwable) {
                // Discovery itself failed: report the provider as unavailable rather than
                // propagating a platform exception into the app (§20/§90).
                return result(
                    installed = false,
                    facts = null,
                    checkedAuthorities = checkedAuthorities,
                    availability = AnkiAvailability.ProviderUnavailable(
                        detail = "Provider lookup failed for ${endpoint.authority}."
                    ),
                    failure = AnkiDroidFailureClassifier.classify(
                        throwable,
                        AnkiDroidOperationStage.PROVIDER_RESOLUTION
                    )
                )
            }

            if (facts == null) continue

            if (!facts.packageMatchesExpected) {
                // Something answers on this authority, but it is not the AnkiDroid we expect.
                // Reported as unavailable — never as Ready (§9) — and the observed package stays
                // visible in diagnostics.
                return result(
                    installed = true,
                    facts = facts,
                    checkedAuthorities = checkedAuthorities,
                    availability = AnkiAvailability.ProviderUnavailable(
                        detail = "Authority ${endpoint.authority} is served by an unexpected package."
                    ),
                    failure = synthesizedFailure(
                        AnkiDroidFailureCategory.ENDPOINT_UNAVAILABLE,
                        AnkiDroidFailureEvidence.UNEXPECTED_PROVIDER_PACKAGE,
                        token = facts.providerPackage
                    )
                )
            }

            if (!facts.enabled) {
                return result(
                    installed = true,
                    facts = facts,
                    checkedAuthorities = checkedAuthorities,
                    availability = AnkiAvailability.ProviderUnavailable(
                        detail = "AnkiDroid's integration provider is disabled."
                    ),
                    failure = synthesizedFailure(
                        AnkiDroidFailureCategory.ENDPOINT_UNAVAILABLE,
                        AnkiDroidFailureEvidence.PROVIDER_DISABLED,
                        token = endpoint.authority
                    )
                )
            }

            if (facts.providerSpec < AnkiDroidProviderSpec.MIN_SUPPORTED_SPEC) {
                return result(
                    installed = true,
                    facts = facts,
                    checkedAuthorities = checkedAuthorities,
                    availability = AnkiAvailability.Unsupported(
                        reason = "Provider spec ${facts.providerSpec} is below the minimum " +
                            "supported spec ${AnkiDroidProviderSpec.MIN_SUPPORTED_SPEC}."
                    ),
                    failure = synthesizedFailure(
                        AnkiDroidFailureCategory.UNSUPPORTED_API,
                        AnkiDroidFailureEvidence.SPEC_BELOW_MINIMUM,
                        token = facts.providerSpec.toString()
                    )
                )
            }

            // Permission before probe: asking a provider we may not read would only produce a
            // SecurityException, and "permission required" is the more useful answer (§17).
            val permissionState = try {
                permissions.state(endpoint)
            } catch (throwable: Throwable) {
                return result(
                    installed = true,
                    facts = facts,
                    checkedAuthorities = checkedAuthorities,
                    availability = AnkiAvailability.PermissionRequired(
                        detail = "Permission state could not be read."
                    ),
                    failure = AnkiDroidFailureClassifier.classify(
                        throwable,
                        AnkiDroidOperationStage.PERMISSION_CHECK
                    )
                )
            }

            if (!permissionState.granted) {
                return result(
                    installed = true,
                    facts = facts,
                    checkedAuthorities = checkedAuthorities,
                    permissionGranted = false,
                    permissionProtectionLevel = permissionState.protectionLevel,
                    availability = AnkiAvailability.PermissionRequired(
                        detail = "Permission ${endpoint.readWritePermission} is not granted."
                    ),
                    failure = synthesizedFailure(
                        AnkiDroidFailureCategory.PERMISSION_DENIED,
                        AnkiDroidFailureEvidence.PLATFORM_PERMISSION_CHECK,
                        token = endpoint.readWritePermission
                    )
                )
            }

            val outcome = try {
                probe.probeCollection(endpoint)
            } catch (throwable: Throwable) {
                // Includes the SecurityException path: a provider that refuses despite a granted
                // permission is still classified, never swallowed (§20/§60).
                val failure = AnkiDroidFailureClassifier.classify(
                    throwable,
                    AnkiDroidOperationStage.COLLECTION_PROBE
                )
                return result(
                    installed = true,
                    facts = facts,
                    checkedAuthorities = checkedAuthorities,
                    permissionGranted = true,
                    permissionProtectionLevel = permissionState.protectionLevel,
                    collectionReady = collectionReadinessFor(failure),
                    availability = availabilityFor(failure),
                    failure = failure
                )
            }

            return when (outcome) {
                is AnkiDroidProbeOutcome.Reached -> result(
                    installed = true,
                    facts = facts,
                    checkedAuthorities = checkedAuthorities,
                    permissionGranted = true,
                    permissionProtectionLevel = permissionState.protectionLevel,
                    collectionReady = true,
                    availability = AnkiAvailability.Ready(AnkiCapabilities.NONE)
                )

                is AnkiDroidProbeOutcome.Failed -> result(
                    installed = true,
                    facts = facts,
                    checkedAuthorities = checkedAuthorities,
                    permissionGranted = true,
                    permissionProtectionLevel = permissionState.protectionLevel,
                    collectionReady = collectionReadinessFor(outcome.failure),
                    availability = availabilityFor(outcome.failure),
                    failure = outcome.failure
                )
            }
        }

        // No authority resolved. The package decides between "not installed" and "installed but
        // not exposing its provider" — and the package is only consulted *after* the provider,
        // because a provider is the stronger evidence (§9).
        val installedEndpoint = endpoints.firstOrNull { endpoint ->
            try {
                probe.isPackageInstalled(endpoint.expectedPackage)
            } catch (_: Throwable) {
                false
            }
        }

        return if (installedEndpoint == null) {
            result(
                installed = false,
                facts = null,
                checkedAuthorities = checkedAuthorities,
                availability = AnkiAvailability.NotInstalled,
                // Not installed is a normal state, not a failure: nothing "went wrong", so
                // diagnostics report the state itself rather than a fabricated error code (§34).
                failure = null
            )
        } else {
            result(
                installed = true,
                facts = null,
                checkedAuthorities = checkedAuthorities,
                availability = AnkiAvailability.ProviderUnavailable(
                    detail = "AnkiDroid is installed but its integration provider is not available."
                ),
                failure = synthesizedFailure(
                    AnkiDroidFailureCategory.ENDPOINT_UNAVAILABLE,
                    AnkiDroidFailureEvidence.PACKAGE_PRESENT_PROVIDER_MISSING,
                    token = installedEndpoint.expectedPackage
                )
            )
        }
    }

    /**
     * Failure category → unified availability (GATE 02 §21/§36/§37).
     *
     * Only [AnkiDroidFailureCategory.COLLECTION_NOT_READY] produces
     * [AnkiAvailability.CollectionNotInitialized], and that category is only reachable from a
     * *documented* setup signature (see [AnkiDroidFailureClassifier]). A bare
     * `IllegalStateException` becomes a classified fault instead — it stays visible instead of
     * being relabelled as "finish setup" (§61).
     */
    private fun availabilityFor(failure: AnkiDroidFailure): AnkiAvailability = when (failure.category) {
        AnkiDroidFailureCategory.PERMISSION_DENIED -> AnkiAvailability.PermissionRequired(
            detail = "AnkiDroid refused the request without the integration permission."
        )

        AnkiDroidFailureCategory.COLLECTION_NOT_READY -> AnkiAvailability.CollectionNotInitialized

        AnkiDroidFailureCategory.COLLECTION_UNAVAILABLE -> AnkiAvailability.Fault(
            AnkiError.CollectionUnavailable()
        )

        AnkiDroidFailureCategory.COLLECTION_LOCKED -> AnkiAvailability.TemporarilyUnavailable(
            reason = "AnkiDroid reports the collection is locked right now."
        )

        AnkiDroidFailureCategory.TIMEOUT -> AnkiAvailability.Fault(
            AnkiError.QueryFailure(causeCategory = "timeout")
        )

        AnkiDroidFailureCategory.UNSUPPORTED_API -> AnkiAvailability.Unsupported(
            reason = "The installed AnkiDroid exposes an unsupported API."
        )

        AnkiDroidFailureCategory.CONTRACT_MISMATCH -> AnkiAvailability.Unsupported(
            reason = "AnkiDroid did not accept the probe query; the integration contract may " +
                "differ in this AnkiDroid version."
        )

        AnkiDroidFailureCategory.PROVIDER_ERROR -> AnkiAvailability.Fault(
            AnkiError.QueryFailure(causeCategory = "provider-error")
        )

        AnkiDroidFailureCategory.ENDPOINT_UNAVAILABLE -> AnkiAvailability.ProviderUnavailable(
            detail = null
        )

        AnkiDroidFailureCategory.UNEXPECTED -> AnkiAvailability.Fault(
            AnkiError.Unknown(cause = failure.exceptionClass)
        )
    }

    /**
     * Whether the collection may be reported as unusable.
     *
     * `false` is reserved for failures whose evidence is about the collection itself; a timeout,
     * a contract mismatch or a foreign package leaves the collection question unanswered
     * (`null`) rather than answered "no" (§12: `null` means "not known", and a state must never
     * claim more than its evidence).
     */
    private fun collectionReadinessFor(failure: AnkiDroidFailure): Boolean? = when (failure.category) {
        AnkiDroidFailureCategory.COLLECTION_NOT_READY,
        AnkiDroidFailureCategory.COLLECTION_UNAVAILABLE,
        AnkiDroidFailureCategory.COLLECTION_LOCKED -> false

        else -> null
    }

    private fun synthesizedFailure(
        category: AnkiDroidFailureCategory,
        evidence: AnkiDroidFailureEvidence,
        token: String?
    ): AnkiDroidFailure = AnkiDroidFailure(
        category = category,
        evidence = evidence,
        exceptionClass = null,
        evidenceToken = token
    )

    /** Single place that assembles the immutable result, so no branch forgets a field. */
    private fun result(
        installed: Boolean,
        facts: AnkiDroidProviderFacts?,
        checkedAuthorities: List<String>,
        availability: AnkiAvailability,
        failure: AnkiDroidFailure?,
        permissionGranted: Boolean? = null,
        permissionProtectionLevel: String? = null,
        collectionReady: Boolean? = null
    ): AnkiDroidDetectionResult = AnkiDroidDetectionResult(
        installed = installed,
        packageName = facts?.providerPackage,
        providerAvailable = facts != null && facts.packageMatchesExpected && facts.enabled,
        endpointLabel = facts?.endpointLabel,
        authority = facts?.authority ?: checkedAuthorities.firstOrNull(),
        checkedAuthorities = checkedAuthorities,
        providerFacts = facts,
        providerSpec = facts?.providerSpec,
        providerSpecKnown = facts?.providerSpecSource == AnkiDroidProviderSpecSource.METADATA,
        permissionGranted = permissionGranted,
        permissionProtectionLevel = permissionProtectionLevel,
        collectionReady = collectionReady,
        availability = availability,
        capabilities = AnkiCapabilities.NONE,
        failure = failure
    )
}
