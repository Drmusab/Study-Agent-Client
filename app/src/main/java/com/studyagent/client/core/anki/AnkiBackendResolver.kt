package com.studyagent.client.core.anki

/**
 * GATE 21 — legacy result shape retained for callers that still expose kind-level routing status.
 * New code should use [BackendResolutionResult2], which preserves instance-pin and typed failure
 * semantics without projecting them into the older mode-only result.
 */
sealed interface BackendResolutionResult {
    data class Resolved(
        val backendId: AnkiBackendId,
        val reason: BackendSelectionReason
    ) : BackendResolutionResult

    data class NoBackendAvailable(
        override val considered: Set<AnkiBackendId>,
        override val error: AnkiError
    ) : BackendResolutionResult.Failure

    data class PreferredBackendUnavailable(
        val preference: AnkiBackendMode,
        override val considered: Set<AnkiBackendId>,
        override val error: AnkiError
    ) : BackendResolutionResult.Failure

    data class RequiredCapabilityUnavailable(
        val required: Set<AnkiRequiredCapability>,
        val missing: Map<AnkiBackendId, Set<AnkiRequiredCapability>>,
        override val considered: Set<AnkiBackendId>,
        override val error: AnkiError
    ) : BackendResolutionResult.Failure

    data class Ambiguous(
        val candidates: Set<AnkiBackendId>,
        override val considered: Set<AnkiBackendId>,
        override val error: AnkiError
    ) : BackendResolutionResult.Failure

    data class ConfigurationInvalid(
        val detail: String,
        override val considered: Set<AnkiBackendId> = emptySet(),
        override val error: AnkiError = AnkiError.InvalidRequest(detail)
    ) : BackendResolutionResult.Failure

    interface Failure : BackendResolutionResult {
        /** Mode-eligible identities, whether or not they were usable. Empty = nothing is registered. */
        val considered: Set<AnkiBackendId>
        val error: AnkiError
    }
}

/** Why a backend was chosen in the legacy mode-only result. */
enum class BackendSelectionReason {
    EXPLICIT_PREFERENCE,
    AUTO_LOCAL_PREFERRED,
    AUTO_SINGLE_REMOTE,
    ONLY_CANDIDATE
}

/** Legacy kind-level request; kept as a source-compatible adapter to the GATE 21E policy. */
data class BackendResolutionRequest(
    val preference: AnkiBackendMode,
    val requiredCapabilities: Set<AnkiRequiredCapability> = emptySet(),
    val purpose: String? = null
) {
    init { require(purpose == null || purpose.isNotBlank()) }
}

/**
 * Compatibility adapter over the one GATE 21E selection policy.
 *
 * This class exists for pre-21E consumers of [BackendResolutionRequest] and
 * [BackendResolutionResult]. It owns no routing rules: both overloads delegate to
 * [AnkiBackendResolver2], so AUTO ordering and eligibility cannot drift between callers.
 */
class AnkiBackendResolver(private val registry: AnkiBackendRegistry) {
    fun resolve(request: BackendResolutionRequest): BackendResolutionResult =
        resolve(request, registry.descriptors())

    /** Full instance-aware API available through the existing application-scoped resolver. */
    fun resolve(request: BackendResolutionRequest2): BackendResolutionResult2 =
        AnkiBackendResolver2.resolve(request, registry.descriptors())

    companion object {
        /** Legacy pure seam; adapts mode-only inputs/results around the authoritative policy. */
        fun resolve(
            request: BackendResolutionRequest,
            descriptors: List<AnkiBackendDescriptor>
        ): BackendResolutionResult {
            val fullRequest = BackendResolutionRequest2(
                preference = request.preference.toPreference(),
                requiredCapabilities = request.requiredCapabilities,
                purpose = request.purpose
            )
            val result = AnkiBackendResolver2.resolve(fullRequest, descriptors)
            return toLegacyResult(request.preference, result)
        }

        private fun toLegacyResult(
            preference: AnkiBackendMode,
            result: BackendResolutionResult2
        ): BackendResolutionResult = when (result) {
            is BackendResolutionResult2.Resolved -> BackendResolutionResult.Resolved(
                backendId = result.backendId,
                reason = when (result.reason) {
                    BackendResolutionReason.EXPLICIT_PIN,
                    BackendResolutionReason.ONLY_ELIGIBLE_CANDIDATE -> BackendSelectionReason.ONLY_CANDIDATE
                    BackendResolutionReason.AUTO_LOCAL_PREFERRED -> BackendSelectionReason.AUTO_LOCAL_PREFERRED
                    BackendResolutionReason.AUTO_SINGLE_REMOTE -> BackendSelectionReason.AUTO_SINGLE_REMOTE
                }
            )
            is BackendResolutionResult2.NoBackendConfigured -> BackendResolutionResult.NoBackendAvailable(
                considered = result.considered,
                error = result.error
            )
            is BackendResolutionResult2.ExplicitBackendKindUnavailable -> BackendResolutionResult.NoBackendAvailable(
                considered = result.considered,
                error = result.error
            )
            is BackendResolutionResult2.ExplicitBackendMissing -> BackendResolutionResult.NoBackendAvailable(
                considered = emptySet(),
                error = AnkiError.BackendUnavailable()
            )
            is BackendResolutionResult2.NoEligibleBackend -> BackendResolutionResult.PreferredBackendUnavailable(
                preference = preference,
                considered = result.considered,
                error = result.error
            )
            is BackendResolutionResult2.ExplicitBackendUnavailable -> BackendResolutionResult.PreferredBackendUnavailable(
                preference = preference,
                considered = result.considered,
                error = result.error
            )
            is BackendResolutionResult2.CapabilityUnsupported -> BackendResolutionResult.RequiredCapabilityUnavailable(
                required = result.required,
                missing = result.missing,
                considered = result.considered,
                error = result.error
            )
            is BackendResolutionResult2.Ambiguous -> BackendResolutionResult.Ambiguous(
                candidates = result.candidates,
                considered = result.considered,
                error = result.error
            )
            is BackendResolutionResult2.ConfigurationInvalid -> BackendResolutionResult.ConfigurationInvalid(
                detail = result.detail,
                considered = result.considered,
                error = result.error
            )
        }
    }
}
