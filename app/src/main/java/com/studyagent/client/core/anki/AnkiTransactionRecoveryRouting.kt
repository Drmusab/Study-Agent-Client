package com.studyagent.client.core.anki

/** Why a durable transaction group cannot be recovered right now. Content-free. */
enum class BackendTransactionBlockReason {
    /** No backend with that identity is registered in this build/process. */
    BACKEND_NOT_REGISTERED,

    /** The backend exists but is not answering. The record stays blocked; it is never re-routed. */
    BACKEND_UNREACHABLE,

    /**
     * The backend reconnected but a *different* collection is active than the one the transaction was
     * written against (DECISION-21-16). Only decidable when collection identity exists; a backend
     * without one never produces this (DISCOVERY-21-03).
     */
    COLLECTION_MISMATCH
}

/** Where one backend's durable transactions must go. Never "wherever a backend is available". */
sealed interface BackendTransactionRoute {

    /** The exact backend the records were written against. */
    data class Routed(val backendId: AnkiBackendId, val backend: AnkiBackend) : BackendTransactionRoute

    /**
     * This group stays blocked. Blocking is per group: a PC outage must not stop AnkiDroid recovery
     * and vice-versa (DECISION-21-31, VER-21-23/24).
     */
    data class Blocked(
        val backendId: AnkiBackendId,
        val reason: BackendTransactionBlockReason
    ) : BackendTransactionRoute
}

/**
 * GATE 21 — transaction recovery routing (DECISION-21-14/15/30/31, INV-21-03/04).
 *
 * The rule this class exists to enforce:
 *
 * ```text
 * load unresolved durable transactions
 *   ↓ group by the backendId PERSISTED IN THE RECORD
 *   ↓ locate that exact backend in the registry
 *   ↓ validate collection identity where it exists
 *   ↓ recover through that record's own transaction family
 * ```
 *
 * What it deliberately does **not** do: it never consults [AnkiBackendResolver], never reads the
 * preferred backend, and never picks "the first available backend". A transaction written against
 * backend A stays blocked until A returns — replaying it through B would mutate a collection that
 * never saw the original attempt (DECISION-21-15, INV-21-05).
 *
 * It is pure and synchronous so the routing itself is testable without storage or a device.
 */
class AnkiTransactionRecoveryPlanner(private val registry: AnkiBackendRegistry) {

    /**
     * One route per distinct transaction-owner identity. [collectionKeys] carries the collection each
     * group was written against, when the record has one; an absent or null key means "this family
     * has no collection identity", which blocks nothing (DISCOVERY-21-03 — absence is recorded
     * honestly rather than invented).
     *
     * [activeCollectionKeys] is what each backend reports *now*. A mismatch fails closed.
     */
    fun plan(
        ownerBackendIds: Collection<AnkiBackendId>,
        collectionKeys: Map<AnkiBackendId, String?> = emptyMap(),
        activeCollectionKeys: Map<AnkiBackendId, String?> = emptyMap()
    ): Map<AnkiBackendId, BackendTransactionRoute> =
        ownerBackendIds.distinct().associateWith { id -> route(id, collectionKeys[id], activeCollectionKeys) }

    private fun route(
        backendId: AnkiBackendId,
        recordCollectionKey: String?,
        activeCollectionKeys: Map<AnkiBackendId, String?>
    ): BackendTransactionRoute {
        val backend = registry.find(backendId)
            ?: return BackendTransactionRoute.Blocked(backendId, BackendTransactionBlockReason.BACKEND_NOT_REGISTERED)
        if (backend.availability.value !is AnkiAvailability.Ready) {
            return BackendTransactionRoute.Blocked(backendId, BackendTransactionBlockReason.BACKEND_UNREACHABLE)
        }
        val active = activeCollectionKeys[backendId]
        // Only a *known* pair can disagree. Unknown on either side is not a mismatch — it is a
        // backend that cannot prove collection identity, and pretending otherwise would either block
        // every recovery or fabricate equivalence (DECISION-21-02, INV-21-06).
        if (recordCollectionKey != null && active != null && recordCollectionKey != active) {
            return BackendTransactionRoute.Blocked(backendId, BackendTransactionBlockReason.COLLECTION_MISMATCH)
        }
        return BackendTransactionRoute.Routed(backendId, backend)
    }

    companion object {
        /**
         * Group durable records by the backend identity *stored in each record*.
         *
         * Grouping by anything else — the current preference, a resolved backend, the backend that
         * happens to be ready — is the cross-backend replay this gate prohibits.
         */
        fun <T> groupByOwnerBackend(
            records: Collection<T>,
            backendOf: (T) -> AnkiBackendId
        ): Map<AnkiBackendId, List<T>> = records.groupBy(backendOf)
    }
}
