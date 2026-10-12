package com.studyagent.client.core.anki

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Explicit registry of configured backend instances — not a mutable active-backend singleton and
 * not a service locator.
 *
 * GATE 21 note: the registry is the **existence and state** source (AUDIT-21-14). It never routes:
 * routing is [AnkiBackendResolver]'s job, and an active session's backend is the session's own
 * locked identity, not a registry lookup of "whatever is current".
 *
 * ## GATE 21D — what this class owns, and what it does not (DECISION-21B-10)
 *
 * **Owns:** which configured instances exist ([ids], [registered]); exact lookup by identity
 * ([find], [stateOf]); a per-instance availability + capability snapshot ([descriptors], [states]);
 * refusal of a second claim on a held identity ([register]); and a read-only re-check request for
 * one exact instance ([refresh]).
 *
 * **Never owns:** which backend a *new* context should use (no preference, no priority, no
 * fallback — AUTO is the resolver's, INV-21D-01); session state; transaction truth or recovery;
 * scheduler calculations; retry policy; collection continuity; or any backend's lifecycle — it holds
 * references the composition root created and never constructs, starts, stops or closes one.
 *
 * **Membership is an immutable snapshot, replaced atomically.** A registration or removal builds a
 * new [AnkiBackendMembership] and swaps it in with one compare-and-set, so a reader sees either the
 * old set or the new one, never a half-applied one, and there is no "current" pointer anywhere
 * (21B asked 21D to define dynamic registration "without a mutable current backend pointer").
 * Enumeration follows registration order for determinism, but that order is display order only —
 * never a routing input.
 *
 * **Absent is not unavailable.** An identity that is not held has no state and no reason
 * ([AnkiBackendStateLookup.NotRegistered], `find == null`); a held identity whose backend is down
 * has a state with a reason. Neither ever answers with a sibling.
 *
 * Availability and capabilities stay owned by each backend's own flows; this class only *reads*
 * them. It therefore cannot publish a stale probe over a newer one — ordering of publication is the
 * backend's duty ([AnkiBackend.refreshAvailability]) and is verified through the real adapter.
 */
class AnkiBackendRegistry(backends: List<AnkiBackend>) {
    private val membership = MutableStateFlow(AnkiBackendMembership.of(backends))

    /**
     * The membership as an observable snapshot, for the observation point that must follow
     * registration and removal. Infrastructure boundary: it carries raw instances, so it is not part
     * of the surface UI or diagnostics read — they observe [AnkiBackendStatusCenter.states].
     */
    internal val members: StateFlow<AnkiBackendMembership> = membership.asStateFlow()

    /** Registered backends in registration order. Order is never a routing input. */
    val registered: List<AnkiBackend> get() = membership.value.backends

    val ids: Set<AnkiBackendId> get() = membership.value.ids

    /** Exact lookup. `null` means *not registered* — never "the closest match", never a sibling. */
    fun find(id: AnkiBackendId): AnkiBackend? = membership.value[id]

    /**
     * GATE 21 — one snapshot per registered backend (IMPLEMENTATION-21-04). A snapshot, not a live
     * view: availability and capabilities move during runtime, so this is only valid for the
     * resolution that asked for it. GATE 21D keeps this the single raw snapshot source.
     */
    fun descriptors(): List<AnkiBackendDescriptor> = membership.value.backends.map(AnkiBackendDescriptor::of)

    /**
     * GATE 21D — one [AnkiBackendState] per registered backend, ordered by stable identity so the
     * output never depends on registration order and is never mistaken for a priority.
     * Pull-only: reads each backend's own projections, never probes.
     */
    fun states(): List<AnkiBackendState> =
        membership.value.backends.map(AnkiBackendState::of).sortedBy { it.backendId.stableId }

    /** GATE 21D — the state of this exact backend, or [AnkiBackendStateLookup.NotRegistered]. */
    fun stateOf(id: AnkiBackendId): AnkiBackendStateLookup {
        val backend = membership.value[id] ?: return AnkiBackendStateLookup.NotRegistered(id)
        return AnkiBackendStateLookup.Registered(AnkiBackendState.of(backend))
    }

    /**
     * GATE 21D — hold one more configured instance, atomically.
     *
     * Identity first ([registrationRefusal]), then the one rule that matters: an identity that is
     * already held is **never replaced**. The very same instance is idempotent; a different object
     * claiming the same identity is refused and the original stays exactly as it was.
     */
    fun register(backend: AnkiBackend): AnkiBackendRegistration {
        val id = backend.id
        id.registrationRefusal()?.let { return AnkiBackendRegistration.IdentityRefused(id, it) }
        while (true) {
            val current = membership.value
            val existing = current[id]
            if (existing === backend) return AnkiBackendRegistration.AlreadyRegistered(id)
            if (existing != null) return AnkiBackendRegistration.DuplicateIdentity(id)
            if (membership.compareAndSet(current, current.plus(backend))) {
                return AnkiBackendRegistration.Registered(id)
            }
        }
    }

    /**
     * GATE 21D — drop one configured instance from membership, and nothing else.
     *
     * Durable records owned by [id] keep their owner (the registry holds no ledger); at recovery time
     * they become `Blocked(BACKEND_NOT_REGISTERED)` — blocked, never reassigned. A later
     * registration under the same identity is the same logical owner again, but collection
     * continuity must be re-established by whoever owns the binding (BACKEND_IDENTITY_CONTRACT §1.4).
     */
    fun remove(id: AnkiBackendId): AnkiBackendRemoval {
        while (true) {
            val current = membership.value
            if (current[id] == null) return AnkiBackendRemoval.NotRegistered(id)
            if (membership.compareAndSet(current, current.minus(id))) return AnkiBackendRemoval.Removed(id)
        }
    }

    /**
     * GATE 21D — ask **this exact instance** to re-run its own read-only availability check.
     *
     * The only thing sent to the backend is [AnkiBackend.refreshAvailability], whose contract is
     * read-only, ordered and cancellable; no rating, reviewer action, edit or creation can be reached
     * from here (INV-21D-16). Nothing is queued, retried, timed or fanned out: coalescing and
     * ordering are the backend's, and this class adds no probe loop. A missing identity sends the
     * probe nowhere — in particular not to a sibling that happens to be ready.
     */
    suspend fun refresh(id: AnkiBackendId): AnkiBackendRefresh {
        val backend = membership.value[id] ?: return AnkiBackendRefresh.NotRegistered(id)
        backend.refreshAvailability()
        // Removed while the probe ran: report absence, never an outcome for an instance nobody holds.
        if (membership.value[id] !== backend) return AnkiBackendRefresh.NotRegistered(id)
        return AnkiBackendRefresh.Requested(id)
    }
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
