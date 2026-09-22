package com.studyagent.client.core.anki

/** Immutable explicit registry, not a mutable active-backend singleton or a service locator. */
class AnkiBackendRegistry(backends: List<AnkiBackend>) {
    private val byId = backends.associateBy { it.id }
    init { require(byId.size == backends.size) { "Duplicate logical backend identity" } }
    val ids: Set<AnkiBackendId> get() = byId.keys.toSet()
    fun find(id: AnkiBackendId): AnkiBackend? = byId[id]
}

/**
 * Resolve BEFORE session binding only. Never refresh/probe platforms or mutate preferences here.
 * AUTO: local review-ready first, then a single ready PC profile. Multiple eligible PC profiles
 * are ambiguous, never list-order-selected. Fake identities are never automatically selected.
 * A session coordinator retains the returned logical ID and resolves that SAME ID after recovery.
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

    fun resolve(preference: AnkiBackendMode): Resolution = resolve(preference, registry.ids) { id ->
        val backend = checkNotNull(registry.find(id))
        val state = backend.availability.value
        // Both signals must agree. During a capability transition fail closed, never overclaim.
        if (state is AnkiAvailability.Ready && !backend.capabilities.value.review) {
            AnkiAvailability.Ready(backend.capabilities.value)
        } else state
    }

    companion object {
        /** Pure policy seam retained from GATE 01; useful without Android or a registry instance. */
        fun resolve(
            preference: AnkiBackendMode,
            implemented: Set<AnkiBackendId>,
            availabilityOf: (AnkiBackendId) -> AnkiAvailability
        ): Resolution {
            val eligible = implemented.filter(preference::accepts)
            val snapshots = eligible.associateWith(availabilityOf)
            val ready = eligible.filter { snapshots.getValue(it).isReadyForReview }
            if (preference != AnkiBackendMode.PC_AGENT && AnkiBackendId.AnkiDroidLocal in ready) {
                return Resolution.Resolved(AnkiBackendId.AnkiDroidLocal)
            }
            val pc = ready.filterIsInstance<AnkiBackendId.PcAgent>()
            if (pc.size == 1) return Resolution.Resolved(pc.single())
            if (pc.size > 1) return Resolution.Ambiguous(pc.toSet())
            val reason = when {
                preference == AnkiBackendMode.AUTO -> UnavailableReason.NO_READY_BACKEND
                eligible.isEmpty() -> UnavailableReason.EXPLICIT_BACKEND_NOT_IMPLEMENTED
                else -> UnavailableReason.EXPLICIT_BACKEND_NOT_READY
            }
            val error = snapshots.values.singleOrNull()?.let {
                it.unavailabilityError() ?: AnkiError.UnsupportedAction("review")
            } ?: AnkiError.BackendUnavailable()
            return Resolution.Unavailable(reason, error)
        }
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
