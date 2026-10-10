package com.studyagent.client.core.anki

/**
 * GATE 21 — what *kind* of thing a backend is (IMPLEMENTATION-21-01).
 *
 * A kind is metadata about an identity, never the identity itself: [AnkiBackendId.PcAgent] carries
 * a profile discriminator precisely because two PC agent configurations are two different backends
 * that happen to share a kind (DECISION-21-01). Nothing may branch on a kind to decide whether an
 * operation is allowed — that is what [AnkiRequiredCapability] is for (AUDIT-21-13).
 */
enum class AnkiBackendKind {
    /** The device's own AnkiDroid collection, reached through its exported provider. */
    ANKIDROID_LOCAL,

    /** A desktop Anki reached through the PC agent. Always profile-qualified. */
    PC_AGENT,

    /** Test double. Never selectable through any [AnkiBackendMode] (see [AnkiBackendMode.accepts]). */
    FAKE
}

/**
 * Where the collection physically lives. A *policy input* for AUTO ordering (a local collection is
 * preferred over a remote one because it has no transport that can vanish mid-review) — never an
 * identity, and never a claim that one is faster than the other (DISCOVERY-21-32 records that no
 * such measurement exists in this repository).
 */
enum class AnkiBackendLocality {
    LOCAL_DEVICE,
    REMOTE_AGENT,
    TEST_DOUBLE
}

val AnkiBackendId.kind: AnkiBackendKind
    get() = when (this) {
        is AnkiBackendId.AnkiDroidLocal -> AnkiBackendKind.ANKIDROID_LOCAL
        is AnkiBackendId.PcAgent -> AnkiBackendKind.PC_AGENT
        is AnkiBackendId.Fake -> AnkiBackendKind.FAKE
    }

val AnkiBackendKind.locality: AnkiBackendLocality
    get() = when (this) {
        AnkiBackendKind.ANKIDROID_LOCAL -> AnkiBackendLocality.LOCAL_DEVICE
        AnkiBackendKind.PC_AGENT -> AnkiBackendLocality.REMOTE_AGENT
        AnkiBackendKind.FAKE -> AnkiBackendLocality.TEST_DOUBLE
    }

/**
 * GATE 21 — one registered backend described without exposing the implementation object
 * (IMPLEMENTATION-21-04).
 *
 * This is a **snapshot**, not a live view: availability and capabilities change during runtime
 * (DISCOVERY-21-07/09), so a descriptor is only valid for the resolution that produced it. Nothing
 * here is a secret: no transport detail, host, token or profile path — a profile ID is the only PC
 * discriminator and it is already part of the public [AnkiBackendId].
 */
data class AnkiBackendDescriptor(
    val id: AnkiBackendId,
    val availability: AnkiAvailability,
    val capabilities: AnkiCapabilities,
    val kind: AnkiBackendKind = id.kind,
    val locality: AnkiBackendLocality = kind.locality
) {
    init {
        // A descriptor whose kind contradicts its identity is self-refuting data (the same rule
        // AnkiSessionContext applies to a foreign collection identity).
        require(kind == id.kind) { "A descriptor's kind must match its identity" }
        require(locality == kind.locality) { "A descriptor's locality must match its kind" }
    }

    /** Positive knowledge that a *rating-writing* review may start here (GATE 01 §72). */
    val isReadyForReview: Boolean get() = availability.isReadyForReview

    /** The backend exists and answers right now, for *some* operation. */
    val isReachable: Boolean get() = availability is AnkiAvailability.Ready

    /**
     * The capability projection carried *inside* the availability state. [AnkiAvailability.Ready]
     * snapshots capabilities at probe time, while [capabilities] is the live projection; the two can
     * disagree during a capability transition. GATE 01's rule is fail-closed — never overclaim — so
     * a requirement must be met by **both** (see `AnkiBackendSelectorTest`,
     * "disagreeing capability projections fail closed").
     */
    private val availabilityCapabilities: AnkiCapabilities
        get() = (availability as? AnkiAvailability.Ready)?.capabilities ?: AnkiCapabilities.NONE

    /** Stable, content-free ordering key: AUTO must never depend on registry list order. */
    val orderingKey: String get() = "${kind.ordinal}:${id.stableId}"

    fun missingCapabilities(required: Set<AnkiRequiredCapability>): Set<AnkiRequiredCapability> =
        required.filterNot { supports(it) }.toSet()

    fun supportsAll(required: Set<AnkiRequiredCapability>): Boolean = required.all { supports(it) }

    fun supports(required: AnkiRequiredCapability): Boolean =
        capabilities.supports(required) && availabilityCapabilities.supports(required)

    companion object {
        fun of(backend: AnkiBackend): AnkiBackendDescriptor = AnkiBackendDescriptor(
            id = backend.id,
            availability = backend.availability.value,
            capabilities = backend.capabilities.value
        )
    }
}
