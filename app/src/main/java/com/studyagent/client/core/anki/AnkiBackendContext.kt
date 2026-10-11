package com.studyagent.client.core.anki

import kotlinx.serialization.Serializable

/**
 * GATE 21C — the immutable identity context of backend-bound work (CHECKPOINT 21C-05).
 *
 * One configured backend instance plus the collection context it is working with, and nothing else.
 * It is the shape that DECISION-21B-04 uses as the enclosing qualification for a resource, and the
 * shape later sub-gates bind to: read contexts (21F), a StudySession's immutable owner (21G), durable
 * transaction ownership (21H–21L) and recovery routing.
 *
 * What it deliberately does **not** contain:
 *
 * ```text
 * the currently selected backend      → that is a preference, not an identity
 * the global AUTO preference          → policy, owned by AnkiBackendResolver
 * mutable UI state                    → presentation
 * a transport/connection object       → runtime state; identity survives it (allocation rule A4)
 * capability or availability snapshots → those are observations with their own lifetime
 * ```
 *
 * [collection] is the *only* collection representation; `null` means UNKNOWN (§2.1) and is never
 * upgraded to a wildcard, a sentinel or a verified match. The contract's "known vs unknown
 * collection" distinction lives here; the *assurance* of continuity is computed separately
 * ([AnkiIdentityAssurancePolicy]) because it changes with connection state while this value does not.
 *
 * Equality is structural: same instance + same collection evidence (including its absence).
 */
@Serializable
data class AnkiBackendContext(
    val backendId: AnkiBackendId,
    val collection: AnkiCollectionIdentity? = null
) {
    init {
        // A collection identity naming a different backend than its own context is self-contradictory
        // data — the same structural rule AnkiSessionContext applies to its own collection.
        require(collection == null || collection.backendId == backendId) {
            "A context's collection must belong to its own backend"
        }
    }

    /** The backend-issued discriminator, or `null` when it is UNKNOWN. Never a sentinel. */
    val collectionKey: String? get() = collection?.collectionKey

    /** True only when the backend actually issued a discriminator (§2.1). */
    val hasAuthoritativeCollectionIdentity: Boolean get() = collection?.collectionKey != null

    /** This context as one side of an identity comparison. */
    val observation: AnkiIdentityObservation get() = AnkiIdentityObservation(backendId, collectionKey)

    /** The same context with the collection evidence that is actually known for it. */
    fun withCollection(collection: AnkiCollectionIdentity?): AnkiBackendContext =
        AnkiBackendContext(backendId, collection)

    /**
     * CHECKPOINT 21C-15 — "does a resource with this backend-qualified identity belong to this
     * context?"
     *
     * The answer is typed, and *unknown never answers yes*:
     *
     * ```text
     * same backend, both keys known and equal   → SameBackend(AUTHORITATIVE_MATCH)
     * same backend, both keys known, unequal    → SameBackend(AUTHORITATIVE_MISMATCH)
     * same backend, one side has no key         → SameBackend(IDENTITY_UNVERIFIABLE(degraded))
     * same backend, neither side has a key      → SameBackend(IDENTITY_UNVERIFIABLE(nominal))
     * different configured instance             → ForeignBackend (never unverifiable)
     * ```
     *
     * `boundIsLiveInThisProcess` is `false` on purpose: a *resource* is not the live backend, so the
     * continuity category cannot be claimed for one. With no key on either side the honest answer is
     * nominal unverifiability, not a verified match.
     *
     * This helper performs no I/O: no connection, no preference change, no AUTO resolution, no
     * mutation, no retry. It expresses identity semantics only.
     */
    fun resourceRelation(backendId: AnkiBackendId, collectionKey: String?): AnkiIdentityCheck =
        AnkiIdentityAssurancePolicy.check(
            bound = observation,
            live = AnkiIdentityObservation(backendId, collectionKey),
            boundIsLiveInThisProcess = false
        )
}

/**
 * The identity context of a session lock: the same instance + collection the session actually bound
 * to (`AnkiSessionContext`). A projection, not a second source of truth — the session context stays
 * authoritative for the lock.
 */
val AnkiSessionContext.backendContext: AnkiBackendContext
    get() = AnkiBackendContext(backendId, collection)

/**
 * The identity context of a durable rating transaction: the commit's own backend and the deck/card
 * collection evidence the record carries (`ReviewCommitRecord.collectionKey` is itself derived from
 * `collectionRef ?: card ?: deckRef`, all of which belong to one backend by construction).
 *
 * Used by recovery routing (21H) and diagnostics to compare what a record *was written against* with
 * what a backend *reports now*; it never changes the record's ownership.
 */
val ReviewCommitRecord.backendContext: AnkiBackendContext
    get() = AnkiBackendContext(backendId, collectionKey?.let { AnkiCollectionIdentity(backendId, it) })
