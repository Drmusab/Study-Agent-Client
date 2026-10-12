package com.studyagent.client.core.anki

/**
 * GATE 21D — does the **application** implement this capability? (CHECKPOINT 21D-05)
 *
 * A fact about the wired object graph, never about the third-party API underneath it: AnkiDroid's
 * provider can theoretically create a note, but until Study-Agent wires an adapter for it the answer
 * is [UNSUPPORTED] (INV-21D-10). It is also not the data-layer `CapabilitySupport`, which answers the
 * *different* question "does the provider API at this spec offer it?" — the two must never be
 * substituted for one another.
 *
 * The default of every unknown is [UNKNOWN], never [SUPPORTED] (INV-21D-11).
 */
enum class AnkiImplementedSupport {
    /** Implemented, by a static claim or by runtime evidence that is still valid. */
    SUPPORTED,

    /** Known not to be implemented (or negotiated away). */
    UNSUPPORTED,

    /** No static claim exists and there is no currently valid runtime evidence. */
    UNKNOWN
}

/**
 * GATE 21D — can the capability be used **right now**? Independent of [AnkiImplementedSupport]: an
 * implemented capability of a backend that is momentarily unreachable is `SUPPORTED` + `NOT_READY`,
 * and those must never be shown as one condition (VER-21D-15).
 */
enum class AnkiCapabilityReadiness {
    /** Positive knowledge only: the backend is [AnkiAvailability.Ready] and both projections agree. */
    READY,

    /** Known not usable now (unreachable, unsupported, or the projections do not agree). */
    NOT_READY,

    /** Nothing has been verified yet ([AnkiAvailability.Checking]). */
    UNKNOWN
}

/**
 * One capability of one backend, along the two dimensions the registry can honestly report.
 *
 * Authorization is deliberately **not** a third field. Whether an operation is *permitted* is decided
 * only inside the binding that owns it (session lock, record identity, editor route) after its
 * pre-binding checks (BACKEND_SELECTION_POLICY §2.1); a registry that reported it would become the
 * authority it must not be (INV-21D-05).
 */
data class AnkiCapabilityStatus(
    val implemented: AnkiImplementedSupport,
    val operational: AnkiCapabilityReadiness
) {
    init {
        // The honesty invariant, enforced at construction: nothing is ready unless it is implemented.
        require(
            operational != AnkiCapabilityReadiness.READY || implemented == AnkiImplementedSupport.SUPPORTED
        ) { "A capability that is not known to be implemented cannot be ready" }
    }
}

/** Where a state's capability knowledge comes from — so a consumer can tell a claim from evidence. */
enum class AnkiCapabilityEvidence {
    /** The backend states what its wired adapter implements ([AnkiBackend.implementedCapabilities]). */
    STATIC_CLAIM,

    /** No static claim; the capabilities come from the current, valid readiness probe. */
    RUNTIME_NEGOTIATED,

    /** No static claim and no valid probe: nothing is known. Never "everything is supported". */
    NONE
}

/**
 * GATE 21D — everything the registry can say about **one** configured backend at **one** instant
 * (CHECKPOINT 21D-06).
 *
 * It *wraps* [AnkiBackendDescriptor] (identity + availability + live capabilities, which stays the
 * single raw snapshot — DECISION-21B-10), adds the backend's static implementation claim, and
 * derives everything else. It owns no second copy of any field.
 *
 * ### What a state is not
 *
 * - **Not a verification receipt.** It is the backend's own two projections read together; it can
 *   carry positive knowledge only as `Ready` with both capability projections agreeing. There is
 *   deliberately no timestamp: a timestamp would read as "verified at", which no field here can
 *   truthfully say. Staleness is handled by re-reading (pull) or observing (push); uncertainty is
 *   [AnkiAvailability.Checking] / `UNKNOWN`, never a quiet carry-over of an older `Ready`.
 * - **Not a continuity claim.** Nothing here says a collection is the same one as before. A
 *   reconnect changes [availability] and nothing else; continuity is the 21C assurance matrix,
 *   evaluated by whoever owns the binding (INV-21D-14).
 * - **Not a selection.** There is no preferred, best, active or default anywhere in this type.
 *
 * Immutable and content-free: availability payloads (`detail`, `reason`) are carried only inside the
 * wrapped descriptor for the resolver; the [diagnostics] projection exposes tokens only.
 */
data class AnkiBackendState(
    val descriptor: AnkiBackendDescriptor,
    /** Static claim of the wired adapter; `null` when the backend states none (runtime-negotiated). */
    val implementedClaim: AnkiCapabilities?
) {
    val backendId: AnkiBackendId get() = descriptor.id
    val kind: AnkiBackendKind get() = descriptor.kind
    val locality: AnkiBackendLocality get() = descriptor.locality
    val availability: AnkiAvailability get() = descriptor.availability

    /** Why this backend is not usable now; `null` when it is `Ready` or still `Checking`. */
    val unavailableReason: AnkiBackendUnavailableReason? get() = availability.unavailableReason()

    val capabilityEvidence: AnkiCapabilityEvidence
        get() = when {
            implementedClaim != null -> AnkiCapabilityEvidence.STATIC_CLAIM
            availability is AnkiAvailability.Ready -> AnkiCapabilityEvidence.RUNTIME_NEGOTIATED
            else -> AnkiCapabilityEvidence.NONE
        }

    /**
     * The two dimensions of one capability.
     *
     * **Implemented.** A static claim answers for itself, whatever the backend is doing now. With no
     * claim, only a *currently valid* probe can answer — and only while the backend is `Ready`. The
     * moment it is not (a disconnect, a reconnect in progress, a version change that sent it back to
     * `Checking`), negotiated evidence is dropped to UNKNOWN instead of being kept: a reconnect must
     * never resurrect yesterday's negotiation.
     *
     * **Operational.** READY is positive knowledge only, and it is exactly
     * [AnkiBackendDescriptor.supports]: `Ready` and the live projection *and* the probe-time
     * projection all agree (DECISION-21B-06: no "best effort" read of either one). A claim can only
     * ever *narrow* that — a capability the application does not implement is never ready, whatever a
     * probe says.
     */
    fun capability(required: AnkiRequiredCapability): AnkiCapabilityStatus {
        val ready = availability is AnkiAvailability.Ready
        val verified = ready && descriptor.supports(required)
        val claim = implementedClaim
        val implemented = when {
            claim != null ->
                if (claim.supports(required)) AnkiImplementedSupport.SUPPORTED else AnkiImplementedSupport.UNSUPPORTED
            ready ->
                if (verified) AnkiImplementedSupport.SUPPORTED else AnkiImplementedSupport.UNSUPPORTED
            else -> AnkiImplementedSupport.UNKNOWN
        }
        val operational = when {
            availability is AnkiAvailability.Checking -> AnkiCapabilityReadiness.UNKNOWN
            !ready -> AnkiCapabilityReadiness.NOT_READY
            implemented != AnkiImplementedSupport.SUPPORTED -> AnkiCapabilityReadiness.NOT_READY
            verified -> AnkiCapabilityReadiness.READY
            else -> AnkiCapabilityReadiness.NOT_READY
        }
        return AnkiCapabilityStatus(implemented, operational)
    }

    /** Every known capability, in declaration order — total, so a gap cannot hide as "absent". */
    fun capabilities(): Map<AnkiRequiredCapability, AnkiCapabilityStatus> =
        AnkiRequiredCapability.entries.associateWith { capability(it) }

    /** Tokens only: nothing in it can carry a credential, a host, a path or collection content. */
    fun diagnostics(): AnkiBackendDiagnostics = AnkiBackendDiagnostics(
        backendId = backendId,
        kind = kind,
        locality = locality,
        statusToken = availability.statusCode,
        unavailableReason = unavailableReason,
        capabilityEvidence = capabilityEvidence,
        capabilities = capabilities()
    )

    companion object {
        /**
         * Reads the backend's own projections. Never probes, refreshes or writes: observing a state
         * must not change it (INV-21D-16).
         */
        fun of(backend: AnkiBackend): AnkiBackendState = AnkiBackendState(
            descriptor = AnkiBackendDescriptor.of(backend),
            implementedClaim = backend.implementedCapabilities
        )
    }
}

/**
 * GATE 21D — the safe, typed, non-sensitive projection of one backend for diagnostics (21M).
 *
 * Every field is an identity token or an enum. There is no free-text field, so there is no place for
 * a credential, an endpoint, provider text, a transcript or card content to hide
 * (DECISION-21B-27 §9.2). The display label is intentionally absent: it is presentation, owned by
 * the UI layer, and is never an identity (rule A6).
 */
data class AnkiBackendDiagnostics(
    val backendId: AnkiBackendId,
    val kind: AnkiBackendKind,
    val locality: AnkiBackendLocality,
    /** [AnkiAvailability.statusCode] — the stable status vocabulary, not localized copy. */
    val statusToken: String,
    val unavailableReason: AnkiBackendUnavailableReason?,
    val capabilityEvidence: AnkiCapabilityEvidence,
    val capabilities: Map<AnkiRequiredCapability, AnkiCapabilityStatus>
)

/**
 * The answer to "what is the state of *this exact* backend?".
 *
 * Absent and unavailable are different types on purpose: a backend that is not registered has no
 * state (and no reason), while a registered backend that is down has a state with a reason. A caller
 * can never receive a sibling's state for a missing identity (INV-21D-04).
 */
sealed interface AnkiBackendStateLookup {
    data class Registered(val state: AnkiBackendState) : AnkiBackendStateLookup
    data class NotRegistered(val backendId: AnkiBackendId) : AnkiBackendStateLookup
}
