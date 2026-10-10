package com.studyagent.client.core.anki

/**
 * Immutable explicit registry, not a mutable active-backend singleton or a service locator.
 *
 * GATE 21 note: the registry is the **existence and state** source (AUDIT-21-14). It never routes:
 * routing is [AnkiBackendResolver]'s job, and an active session's backend is the session's own locked
 * identity, not a registry lookup of "whatever is current".
 */
class AnkiBackendRegistry(backends: List<AnkiBackend>) {
    private val byId = backends.associateBy { it.id }
    init { require(byId.size == backends.size) { "Duplicate logical backend identity" } }

    /** Registered backends in registration order. Order is never a routing input. */
    val registered: List<AnkiBackend> get() = byId.values.toList()

    val ids: Set<AnkiBackendId> get() = byId.keys.toSet()

    fun find(id: AnkiBackendId): AnkiBackend? = byId[id]

    /**
     * GATE 21 — one snapshot per registered backend (IMPLEMENTATION-21-04). A snapshot, not a live
     * view: availability and capabilities move during runtime, so this is only valid for the
     * resolution that asked for it.
     */
    fun descriptors(): List<AnkiBackendDescriptor> = byId.values.map(AnkiBackendDescriptor::of)
}

/**
 * Resolve BEFORE session binding only. Never refresh/probe platforms or mutate preferences here.
 *
 * GATE 21: this class is now the **session-start adapter** over [AnkiBackendResolver] — the policy
 * lives in exactly one place, so a change to AUTO cannot make study-start and read-context routing
 * disagree (AUDIT-21-14). The pure companion remains the framework-free seam from GATE 01.
 *
 * AUTO: local review-ready first, then a single ready PC profile. Multiple eligible PC profiles are
 * ambiguous, never list-order-selected. Fake identities are never automatically selected. A session
 * coordinator retains the returned logical ID and resolves that SAME ID after recovery.
 */
class AnkiBackendSelector(private val registry: AnkiBackendRegistry) {
    enum class UnavailableReason {
        EXPLICIT_BACKEND_NOT_IMPLEMENTED, EXPLICIT_BACKEND_NOT_READY, NO_READY_BACKEND
    }

    sealed interface Resolution {
        data class Resolved(val backendId: AnkiBackendId) : Resolution
        data class Unavailable(
            val reason: UnavailableReason,
            val error: AnkiError = AnkiError.BackendUnavailable()
        ) : Resolution
        data class Ambiguous(val candidates: Set<AnkiBackendId>) : Resolution
    }

    fun resolve(preference: AnkiBackendMode): Resolution = toResolution(
        preference,
        AnkiBackendResolver.resolve(
            BackendResolutionRequest(
                preference = preference,
                requiredCapabilities = REVIEW_REQUIREMENT,
                purpose = "session_start"
            ),
            registry.descriptors()
        )
    )

    companion object {
        /**
         * A study session is the complete rating loop; anything less is not a study backend.
         *
         * GATE 21: aliased to the shared declaration so the policy is stated once and the selector
         * cannot drift from it. Same contents as before — no selection behaviour changed.
         */
        val REVIEW_REQUIREMENT: Set<AnkiRequiredCapability> = STUDY_SESSION_REQUIREMENTS

        /** The GATE 21 result shape folded back into the GATE 01 result shape (1:1, no new cases). */
        fun toResolution(
            preference: AnkiBackendMode,
            result: BackendResolutionResult
        ): Resolution = when (result) {
            is BackendResolutionResult.Resolved -> Resolution.Resolved(result.backendId)
            is BackendResolutionResult.Ambiguous -> Resolution.Ambiguous(result.candidates)
            is BackendResolutionResult.Failure -> Resolution.Unavailable(
                reason = when {
                    preference == AnkiBackendMode.AUTO -> UnavailableReason.NO_READY_BACKEND
                    result.considered.isEmpty() -> UnavailableReason.EXPLICIT_BACKEND_NOT_IMPLEMENTED
                    else -> UnavailableReason.EXPLICIT_BACKEND_NOT_READY
                },
                error = result.error
            )
        }

        /** Pure policy seam retained from GATE 01; useful without Android or a registry instance. */
        fun resolve(
            preference: AnkiBackendMode,
            implemented: Set<AnkiBackendId>,
            availabilityOf: (AnkiBackendId) -> AnkiAvailability
        ): Resolution = toResolution(
            preference,
            AnkiBackendResolver.resolve(
                BackendResolutionRequest(
                    preference = preference,
                    requiredCapabilities = REVIEW_REQUIREMENT,
                    purpose = "session_start"
                ),
                implemented.map { id ->
                    val availability = availabilityOf(id)
                    AnkiBackendDescriptor(
                        id = id,
                        availability = availability,
                        capabilities = (availability as? AnkiAvailability.Ready)?.capabilities
                            ?: AnkiCapabilities.NONE
                    )
                }
            )
        )
    }
}

/** Current usability, not post-write retry evidence. Ready still requires feature gating. */
fun AnkiAvailability.unavailabilityError(): AnkiError? = when (this) {
    is AnkiAvailability.Ready -> null
    is AnkiAvailability.PermissionRequired -> AnkiError.PermissionRequired()
    AnkiAvailability.CollectionNotInitialized -> AnkiError.CollectionUnavailable()
    is AnkiAvailability.ProviderUnavailable -> AnkiError.ProviderUnavailable()
    is AnkiAvailability.Unsupported -> AnkiError.UnsupportedAction("backend_api")
    is AnkiAvailability.Fault -> error
    else -> AnkiError.BackendUnavailable()
}
