package com.studyagent.client.core.anki

/**
 * GATE 21E — the typed answer to "which backend owns this new unbound operation?".
 *
 * The result is *pre-binding* only: it decides one [AnkiBackendId] for a context that does not yet
 * exist. Once a StudySession, editor, creation draft or read context is bound, its owner is immutable
 * and resolution is never re-invoked for it (INV-21E-06).
 *
 * Resolution never returns a nullable backend: every failure is typed and carries the set of
 * [considered] identities so UI/diagnostics can explain *why* (DECISION-21B-08, BACKEND_SELECTION_POLICY §5).
 */
sealed interface BackendResolutionResult2 {

    /** Exactly one eligible backend was chosen. */
    data class Resolved(
        val backendId: AnkiBackendId,
        val reason: BackendResolutionReason
    ) : BackendResolutionResult2

    /** A typed, binding-blocking failure. */
    sealed interface Failed : BackendResolutionResult2 {
        /** Every identity the policy considered (mode-eligible), whether usable or not. */
        val considered: Set<AnkiBackendId>
        /** The structured failure classification (never a generic string). */
        val failure: BackendResolutionFailure
        /** A content-free AnkiError suitable for surfacing; never carries credentials or raw exception text. */
        val error: AnkiError
    }

    /** Nothing of any AUTO/requested kind is registered. */
    data class NoBackendConfigured(
        override val considered: Set<AnkiBackendId>,
        override val failure: BackendResolutionFailure = BackendResolutionFailure.NO_BACKEND_CONFIGURED,
        override val error: AnkiError = AnkiError.BackendUnavailable("no_backend_configured")
    ) : Failed

    /** Candidates exist, but none passes readiness + capability gating. */
    data class NoEligibleBackend(
        override val considered: Set<AnkiBackendId>,
        val consideredReasons: Map<AnkiBackendId, AnkiBackendUnavailableReason>,
        override val failure: BackendResolutionFailure = BackendResolutionFailure.NO_ELIGIBLE_BACKEND,
        override val error: AnkiError
    ) : Failed

    /** The explicitly pinned backend is not registered at all. */
    data class ExplicitBackendMissing(
        val pinnedId: AnkiBackendId,
        override val considered: Set<AnkiBackendId>,
        override val failure: BackendResolutionFailure = BackendResolutionFailure.EXPLICIT_BACKEND_MISSING,
        override val error: AnkiError = AnkiError.InvalidRequest("explicit_backend_missing")
    ) : Failed

    /** The explicitly pinned backend is registered but not ready (unavailable / permission / auth / fault). */
    data class ExplicitBackendUnavailable(
        val pinnedId: AnkiBackendId,
        val reason: AnkiBackendUnavailableReason,
        override val considered: Set<AnkiBackendId>,
        override val failure: BackendResolutionFailure = BackendResolutionFailure.EXPLICIT_BACKEND_UNAVAILABLE,
        override val error: AnkiError
    ) : Failed

    /** Candidates exist and are ready, but none provides every required capability. */
    data class CapabilityUnsupported(
        val required: Set<AnkiRequiredCapability>,
        val missing: Map<AnkiBackendId, Set<AnkiRequiredCapability>>,
        override val considered: Set<AnkiBackendId>,
        override val failure: BackendResolutionFailure = BackendResolutionFailure.CAPABILITY_UNSUPPORTED,
        override val error: AnkiError = AnkiError.UnsupportedAction("required_capability")
    ) : Failed

    /** ≥2 equally eligible instances of the same ambiguity class — choosing is a user decision. */
    data class Ambiguous(
        val candidates: Set<AnkiBackendId>,
        override val considered: Set<AnkiBackendId>,
        override val failure: BackendResolutionFailure = BackendResolutionFailure.AMBIGUOUS_CONFIGURATION,
        override val error: AnkiError = AnkiError.InvalidRequest("ambiguous_backend")
    ) : Failed

    /** The request or configuration is malformed (unknown mode token, duplicate identity, etc.). */
    data class ConfigurationInvalid(
        val detail: String,
        override val considered: Set<AnkiBackendId> = emptySet(),
        override val failure: BackendResolutionFailure = BackendResolutionFailure.CONFIGURATION_INVALID,
        override val error: AnkiError = AnkiError.InvalidRequest("configuration_invalid")
    ) : Failed
}

/**
 * Stable, content-free failure classification. These tokens may be logged or exported; they never
 * carry credentials, endpoint strings, or free-text backend messages (INV-21E-23).
 */
enum class BackendResolutionFailure {
    NO_BACKEND_CONFIGURED,
    NO_ELIGIBLE_BACKEND,
    EXPLICIT_BACKEND_MISSING,
    EXPLICIT_BACKEND_UNAVAILABLE,
    CAPABILITY_UNSUPPORTED,
    AUTHENTICATION_REQUIRED,
    PERMISSION_REQUIRED,
    AMBIGUOUS_CONFIGURATION,
    CONFIGURATION_INVALID
}
