package com.studyagent.client.data.anki.ankidroid

import com.studyagent.client.core.anki.AnkiAvailability
import com.studyagent.client.core.anki.AnkiCapabilities
import com.studyagent.client.core.anki.AnkiError

/**
 * GATE 02 — health models for the AnkiDroid integration.
 *
 * Two types, deliberately not three:
 *
 * 1. [AnkiDroidDetectionResult] — the *facts* a detection produced (what is installed, what
 *    resolved, which permission state, which provider spec, whether the collection answered,
 *    and the resulting unified [AnkiAvailability]).
 * 2. [AnkiDroidHealthSnapshot] — that result plus *when* and *how long* it took.
 *
 * The snapshot composes the detection result instead of copying its fields (GATE 02 §94: one
 * representation per fact). Everything here is Android-free, so the detector, the classifier and
 * every state transition run on the JVM with fakes (§64).
 */

/** Where the provider spec number came from. Diagnostics-only; the number itself is the fact. */
enum class AnkiDroidProviderSpecSource {
    /** The provider published `com.ichi2.anki.provider.spec` in its metadata. */
    METADATA,

    /**
     * The provider resolved but published no spec metadata, so the official API's documented
     * fallback value applies (`DEFAULT_PROVIDER_SPEC_VALUE = 1`). Recorded so diagnostics can
     * distinguish "spec 1 because metadata absent" from "spec 1 because old AnkiDroid".
     */
    IMPLICIT_FALLBACK
}

/**
 * What the platform reported about one resolved provider. Every field is a *platform fact*, not
 * a conclusion: conclusions live in [AnkiDroidDetectionResult.availability].
 */
data class AnkiDroidProviderFacts(
    /** Endpoint label this provider was resolved from (`release`, `debug`). */
    val endpointLabel: String,
    /** Authority that resolved. */
    val authority: String,
    /** Package that actually serves [authority], as reported by the platform. */
    val providerPackage: String?,
    /** True when [providerPackage] is the package the endpoint expects. */
    val packageMatchesExpected: Boolean,
    /** Provider `enabled` flag as reported by the platform. */
    val enabled: Boolean,
    /** Provider spec as published, or the documented fallback (see [providerSpecSource]). */
    val providerSpec: Int,
    val providerSpecSource: AnkiDroidProviderSpecSource
)

/** Result of the bounded read probe (§22/§23). Never carries a Cursor or row content. */
sealed interface AnkiDroidProbeOutcome {

    /**
     * The provider answered. [selectedDeckRowPresent] is the only thing read from the bounded
     * `selected_deck` query — a row means the collection layer is answering. No deck id, deck
     * name or card data is read, kept or logged (§68/§104).
     */
    data class Reached(val selectedDeckRowPresent: Boolean) : AnkiDroidProbeOutcome

    /** The provider could not answer; [failure] is already classified. */
    data class Failed(val failure: AnkiDroidFailure) : AnkiDroidProbeOutcome
}

/**
 * The facts and the verdict of one detection pass (GATE 02 §12).
 *
 * `null` is meaningful everywhere and never guessed:
 *  - [permissionGranted] is `null` when the provider never resolved (the permission question is
 *    moot until there is a provider to ask about).
 *  - [collectionReady] is `null` when the collection question could not be reached (missing
 *    package/provider/permission). `true`/`false` are only used when the probe actually
 *    produced evidence.
 *  - [providerSpecKnown] distinguishes "spec 1 from metadata" from "spec 1 by fallback".
 */
data class AnkiDroidDetectionResult(
    val installed: Boolean,
    val packageName: String?,
    val providerAvailable: Boolean,
    /** Endpoint label that produced this result, when one resolved. */
    val endpointLabel: String?,
    /** Authority that resolved, or the first authority that was looked for. */
    val authority: String?,
    /** Every authority this pass attempted, in order. Support/diagnostics only. */
    val checkedAuthorities: List<String>,
    val providerFacts: AnkiDroidProviderFacts?,
    val providerSpec: Int?,
    val providerSpecKnown: Boolean,
    val permissionGranted: Boolean?,
    /**
     * Protection level the platform reports for the endpoint permission (`dangerous` for the
     * AnkiDroid custom permission), or `null` when it is not visible to us — typically because
     * the expected package is not installed. Recorded so the "granted at install time, not
     * requestable at runtime" contract is verifiable on a real device (§18/§99).
     */
    val permissionProtectionLevel: String?,
    val collectionReady: Boolean?,
    /** The unified, cross-backend availability state (never a boolean, §14). */
    val availability: AnkiAvailability,
    /**
     * Capabilities this pass *validated*. GATE 02 validates reachability only, so this is
     * [AnkiCapabilities.NONE]: the provider API being reachable does not prove review, media or
     * editing, and claiming those would be a lie the later gates would inherit (§95).
     */
    val capabilities: AnkiCapabilities,
    /** Classified failure, when this pass failed. Never a `Throwable` (§36). */
    val failure: AnkiDroidFailure?
)

/**
 * One completed health check: the detection result plus its timing (GATE 02 §25/§101).
 */
data class AnkiDroidHealthSnapshot(
    /** Wall-clock milliseconds when the check completed. */
    val checkedAtEpochMs: Long,
    /** How long the check took, for the latency line in diagnostics. */
    val durationMs: Long,
    val detection: AnkiDroidDetectionResult
) {
    val availability: AnkiAvailability get() = detection.availability

    val providerSpec: Int? get() = detection.providerSpec

    val packageName: String? get() = detection.packageName

    val permissionGranted: Boolean? get() = detection.permissionGranted

    val collectionReady: Boolean? get() = detection.collectionReady

    val failure: AnkiDroidFailure? get() = detection.failure

    /** Plain-language headline/action pair for normal UI (§34/§37). */
    val guidance: AnkiDroidHealthGuidance get() = AnkiDroidGuidance.of(detection.availability)

    companion object {
        /** The state before any check has run (§89: the app opens, the check resolves later). */
        fun checking(nowMs: Long): AnkiDroidHealthSnapshot = AnkiDroidHealthSnapshot(
            checkedAtEpochMs = nowMs,
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
                availability = AnkiAvailability.Checking,
                capabilities = AnkiCapabilities.NONE,
                failure = null
            )
        )
    }
}

/** User-facing text for one availability state. Non-technical by contract (§34). */
data class AnkiDroidHealthGuidance(
    /** Plain-language status line. */
    val headline: String,
    /** What the user can do about it, or `null` when nothing is needed. */
    val action: String?
)

/**
 * Maps availability → plain language.
 *
 * Never exposes provider vocabulary (`ContentProvider null`, `SecurityException`,
 * `IllegalStateException`) to normal UI (§34); technical detail belongs to diagnostics (§35).
 */
object AnkiDroidGuidance {

    fun of(availability: AnkiAvailability): AnkiDroidHealthGuidance = when (availability) {
        AnkiAvailability.Checking -> AnkiDroidHealthGuidance(
            headline = "Checking AnkiDroid…",
            action = null
        )

        AnkiAvailability.NotInstalled -> AnkiDroidHealthGuidance(
            headline = "AnkiDroid is not available on this phone.",
            action = "Optional: install AnkiDroid to study your local Anki collection on this phone."
        )

        is AnkiAvailability.ProviderUnavailable -> AnkiDroidHealthGuidance(
            headline = "Study-Agent cannot reach AnkiDroid's integration provider.",
            action = "Open AnkiDroid once, then refresh. If this keeps happening, update AnkiDroid."
        )

        is AnkiAvailability.PermissionRequired -> AnkiDroidHealthGuidance(
            headline = "Study-Agent cannot access AnkiDroid.",
            action = "Android grants this access when Study-Agent is installed, so install or update " +
                "Study-Agent while AnkiDroid is already installed, then refresh."
        )

        AnkiAvailability.CollectionNotInitialized -> AnkiDroidHealthGuidance(
            headline = "Finish AnkiDroid setup before using local Anki study.",
            action = "Open AnkiDroid and complete its first-run setup, then refresh."
        )

        is AnkiAvailability.TemporarilyUnavailable -> AnkiDroidHealthGuidance(
            headline = "AnkiDroid is busy right now.",
            action = "Leave AnkiDroid on its deck list (or close it) and refresh."
        )

        is AnkiAvailability.Unsupported -> AnkiDroidHealthGuidance(
            headline = "This AnkiDroid version is not supported.",
            action = "Update AnkiDroid to the latest version, then refresh."
        )

        is AnkiAvailability.Ready -> AnkiDroidHealthGuidance(
            headline = "AnkiDroid — Ready",
            action = null
        )

        is AnkiAvailability.Fault -> AnkiDroidHealthGuidance(
            headline = "Study-Agent could not check AnkiDroid.",
            action = when ((availability.error as? AnkiError.QueryFailure)?.causeCategory) {
                "timeout" -> "AnkiDroid did not answer in time. Refresh to try again."
                // A provider-side read failure with no documented cause: retry first, and mention
                // the one plausible remedy without claiming it happened (§61).
                "provider-error" ->
                    "Refresh to try again. If it keeps failing, open AnkiDroid and complete its setup."
                else -> "Refresh to try again. If it keeps failing, open AnkiDroid and then refresh."
            }
        )

        // PC-path states are not AnkiDroid states; the AnkiDroid section renders them as
        // "not applicable" rather than borrowing a local failure message (§38).
        AnkiAvailability.AgentDisconnected,
        AnkiAvailability.AgentAnkiUnavailable -> AnkiDroidHealthGuidance(
            headline = "Not checked (PC Agent state).",
            action = null
        )
    }
}
