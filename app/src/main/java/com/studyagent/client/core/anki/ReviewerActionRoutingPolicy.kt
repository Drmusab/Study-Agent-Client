package com.studyagent.client.core.anki

/**
 * GATE 21I — the **ReviewerAction family's** answers to the questions GATE 21C deliberately left to
 * each transaction family, and the one place where a reviewer action's own backend and collection
 * evidence are turned into an admission/recovery verdict.
 *
 * ```text
 * ReviewerActionRecord (durable)
 *   ├── backendId            record.backendId        → the exact registered instance (never AUTO,
 *   │                                                      never the preference, never "first ready")
 *   ├── binding collection   collectionRef?.collectionKey — what the action was admitted against
 *   └── operation class      OP-M1 (first attempt) / OP-M2 (repeat) / OP-M3 (reconcile) / OP-P (recovery)
 *                 ↓
 * AnkiIdentityAssurancePolicy.check   (GATE 21C §3.5; binding vs live, one configured instance)
 *                 ↓
 * AnkiIdentityContinuation.continuationVerdict   (locked §3.3 matrix + this family's facts)
 *                 ↓
 * Routed(exact backend) | Blocked(stable token)
 * ```
 *
 * What this file is *not*: it is not a second resolver, not a second registry, not a second
 * ledger, and not a retry engine. It never reads the global preference, never calls AUTO, never
 * rewrites a `backendId` or a `collectionKey`, and never issues a backend call — it only decides
 * whether the locked identity contract permits the operation to be attempted against the
 * record's *own* backend.
 *
 * ## Binding evidence vs resource qualification
 *
 * The collection an action was **admitted against** is `ReviewerActionRecord.collectionRef` —
 * the session/context collection written at admission (`StudyReducer` builds
 * `ReviewerActionRequest` from `session.context.collection`). A card ref may carry its own
 * collection key as *resource qualification*; that key qualifies the resource, it does not assert
 * what the transaction was bound to, so it is never used to *construct* binding evidence here.
 * When binding evidence exists it is always compared (mismatch and degraded both fail closed);
 * when it is absent the collection dimension is unknown for that transaction — the normal
 * AnkiDroid case (no key exists anywhere, DISCOVERY-21-03) — and unknown is never treated as a
 * verified match (`BACKEND_FAILURE_MATRIX` §6, `BACKEND_IDENTITY_CONTRACT` §6.2 row 1).
 *
 * ## DECISION-21I-03 — the locked `OP-M3` matrix applies without a family exception
 *
 * `OP-M3` is permitted only when **both** conditions hold: the transaction has an authoritative
 * collection match and the family has authoritative reconciliation capability, frozen at admission
 * and still advertised live. A read of the card's present flag/queue state is not, by itself,
 * evidence that a particular historical request applied: another Anki client may have changed that
 * state, and no transaction-correlated action receipt exists in AnkiDroid's public contract.
 *
 * Therefore this policy delegates `OP-M3` assurance to the locked
 * `AnkiIdentityContinuation.continuationVerdict` matrix. `AuthoritativeMismatch`,
 * `IdentityUnverifiable(DEGRADED)`, `IdentityUnverifiable(NOMINAL)`, and
 * `SessionContinuityVerified` all block reconciliation. Only `AuthoritativeMatch` can proceed, and
 * even that cell blocks unless the exact action's frozen and live semantics both advertise
 * authoritative historical reconciliation. This is not an extension of GATE 13's ledger or status
 * machine; it is the GATE 21B identity-assurance rule for reaching the existing family reconciler.
 */
object ReviewerActionFamilyFacts {

    /**
     * The ReviewerAction family's answers to [AnkiOperationFacts].
     *
     * - `nonApplicationProofIsCollectionIndependent = true`: a proven non-application for an
     *   action is a durable, transaction-correlated fact — `PREPARED` means the mutation boundary
     *   was provably never entered, and a backend refusal is certified for *this*
     *   `ReviewerActionId` before the provider write (GATE 13 §17). It is never a scheduler
     *   observation, so it does not rest on collection identity.
     * - `authoritativeReconciliationAvailable` is taken from the **live** backend semantics for
     *   this *exact action* (never a per-backend default). For `OP-M3`, `reconcileRoute` further
     *   intersects it with the capability frozen in the record before passing the facts into the
     *   locked identity matrix; a current-state observation is not historical evidence by itself.
     * - `familyRecoveryRequiresCollectionProof = false`: the `OP-P` recovery plan preserves each
     *   record's durable status and exact owner (`BACKEND_IDENTITY_CONTRACT` §6.1/§6.2,
     *   `BACKEND_OWNERSHIP_INVARIANTS` §3.3); planning the owner's recovery group does not itself
     *   mutate or reconcile anything. It does **not** grant `OP-M3`: the separate reconciliation
     *   operation still requires an authoritative collection match.
     * - `backendCurrentlyOffersNoCollectionKey` is passed deliberately (GATE 21C residual
     *   R-21C-04): it is the `OP-R1` degraded-cell condition and is stated, never defaulted.
     */
    fun of(backend: AnkiBackend?, action: ReviewerAction): AnkiOperationFacts = AnkiOperationFacts(
        nonApplicationProofIsCollectionIndependent = true,
        authoritativeReconciliationAvailable =
            backend?.reviewerActionSemantics(action)?.supportsAuthoritativeReconciliation == true,
        familyRecoveryRequiresCollectionProof = false,
        backendCurrentlyOffersNoCollectionKey = backend?.currentCollectionIdentity == null
    )
}

/**
 * The operation class of the attempt that is about to run, decided from durable state only.
 *
 * A record that has never attempted a mutation (a freshly admitted `PREPARED`, or a restored
 * `PREPARED` — provably un-entered) is a new mutation admitted inside a bound context
 * (`OP-M1`). Anything else that may still reach the backend is a repeat attempt of an already
 * admitted transaction — proven-not-applied (`RETRY_ALLOWED`) — and is therefore `OP-M2`, the
 * class whose permission may depend on whether the family's non-application proof needs
 * collection identity.
 */
fun ReviewerActionRecord.mutationOperationClass(): AnkiOperationClass =
    if (status == ReviewerActionStatus.PREPARED && attemptCount == 0) AnkiOperationClass.OP_M1
    else AnkiOperationClass.OP_M2

/** Where one reviewer action may go, or why it may not go anywhere right now. */
sealed interface ReviewerActionRoute {

    /**
     * The exact backend the action belongs to, permitted for this operation under [assurance].
     * The backend is always the one found by `record.backendId` — never a substitute.
     */
    data class Routed(
        val backend: AnkiBackend,
        val operation: AnkiOperationClass,
        val assurance: AnkiIdentityAssurance
    ) : ReviewerActionRoute

    /**
     * The action must not reach any backend. [token] is the stable, content-free name of the
     * blocked cell (`backend_not_registered`, `collection_mismatch`,
     * `collection_identity_degraded`, `reconciliation_requires_authoritative_match`, …) and is
     * what diagnostics and the refusal projection persist.
     */
    data class Blocked(
        val token: String,
        val error: AnkiError,
        val operation: AnkiOperationClass,
        val assurance: AnkiIdentityAssurance? = null
    ) : ReviewerActionRoute {
        val reason: String get() = token
    }
}

/**
 * GATE 21I — transaction admission and recovery routing for the reviewer-action family.
 *
 * Pure with respect to the outside world: it consults the registry (existence) and the two
 * identity observations only. No scheduler call, no preference read, no AUTO resolution, no
 * mutation, no ledger write. The rating family has its own policy
 * ([ReviewCommitRoutingPolicy]); a shared mutable "router" is deliberately not introduced — one
 * policy per family is the point (GATE 21H handoff).
 */
class ReviewerActionRoutingPolicy(
    private val registry: AnkiBackendRegistry,
    private val planner: AnkiTransactionRecoveryPlanner = AnkiTransactionRecoveryPlanner(registry)
) {

    /**
     * The collection an action was **admitted against**. Absence is honest evidence, not a
     * wildcard: it never becomes a match. For OP-M3, the locked matrix requires a match, so absence
     * blocks historical reconciliation even when the backend advertises a read capability.
     */
    fun bindingCollectionKey(record: ReviewerActionRecord): String? = record.collectionRef?.collectionKey

    /**
     * Verdict for one durable record and one operation class (`OP-M1`/`OP-M2` for mutations,
     * `OP-P` for the recovery pass).
     *
     * [boundIsLiveInThisProcess] is the caller's statement that *this process* still holds the
     * binding the record belongs to (a session it bound and has not ended). It is the only input
     * that can produce `SESSION_CONTINUITY_VERIFIED`, and it is never assumed: a caller that does
     * not know passes `false` and gets the conservative reading.
     */
    fun route(
        record: ReviewerActionRecord,
        operation: AnkiOperationClass = record.mutationOperationClass(),
        boundIsLiveInThisProcess: Boolean
    ): ReviewerActionRoute = route(
        ownerBackendId = record.backendId,
        bindingCollectionKey = bindingCollectionKey(record),
        operation = operation,
        boundIsLiveInThisProcess = boundIsLiveInThisProcess,
        action = record.action
    )

    /**
     * The same verdict for an action that has **no durable record yet** (a new admission,
     * `OP-M1`): the session-scoped identity the request will be admitted under. A blocked answer
     * means the coordinator must be refused *before* anything durable exists — no record, no
     * mutation, no second backend.
     */
    fun route(
        ownerBackendId: AnkiBackendId,
        bindingCollectionKey: String?,
        operation: AnkiOperationClass,
        boundIsLiveInThisProcess: Boolean,
        action: ReviewerAction? = null
    ): ReviewerActionRoute {
        val backend = registry.find(ownerBackendId)
            ?: return blocked(
                BackendTransactionBlockReason.BACKEND_NOT_REGISTERED.name.lowercase(),
                AnkiError.BackendUnavailable(BackendTransactionBlockReason.BACKEND_NOT_REGISTERED.name.lowercase()),
                operation
            )
        val check = AnkiIdentityAssurancePolicy.check(
            bound = AnkiIdentityObservation(ownerBackendId, bindingCollectionKey),
            live = AnkiIdentityObservation(ownerBackendId, backend.currentCollectionIdentity?.collectionKey),
            boundIsLiveInThisProcess = boundIsLiveInThisProcess
        )
        val facts = ReviewerActionFamilyFacts.of(
            backend, action ?: ReviewerAction.representative(ReviewerActionKind.BURY))
        return when (val verdict = AnkiIdentityContinuation.continuationVerdict(operation, check, facts)) {
            is AnkiContinuationVerdict.Permitted ->
                ReviewerActionRoute.Routed(backend, operation, verdict.assurance)
            is AnkiContinuationVerdict.Blocked -> blocked(
                blockReasonToken(verdict.reason),
                blockError(verdict.reason),
                operation,
                (check as? AnkiIdentityCheck.SameBackend)?.assurance
            )
        }
    }

    /**
     * The `OP-M3` verdict for one durable record. Identity and capability are evaluated by the
     * same locked continuation matrix used by the other transaction families: a collection match
     * is mandatory, and a match alone is not historical-action evidence.
     */
    fun reconcileRoute(
        record: ReviewerActionRecord,
        boundIsLiveInThisProcess: Boolean = false
    ): ReviewerActionRoute {
        val operation = AnkiOperationClass.OP_M3
        val backend = registry.find(record.backendId)
            ?: return blocked(
                BackendTransactionBlockReason.BACKEND_NOT_REGISTERED.name.lowercase(),
                AnkiError.BackendUnavailable(BackendTransactionBlockReason.BACKEND_NOT_REGISTERED.name.lowercase()),
                operation
            )
        val check = AnkiIdentityAssurancePolicy.check(
            bound = AnkiIdentityObservation(record.backendId, bindingCollectionKey(record)),
            live = AnkiIdentityObservation(record.backendId, backend.currentCollectionIdentity?.collectionKey),
            boundIsLiveInThisProcess = boundIsLiveInThisProcess
        )
        val facts = ReviewerActionFamilyFacts.of(backend, record.action).copy(
            authoritativeReconciliationAvailable = familyReconciliationAvailable(record, backend)
        )
        return when (val verdict = AnkiIdentityContinuation.continuationVerdict(operation, check, facts)) {
            is AnkiContinuationVerdict.Permitted ->
                ReviewerActionRoute.Routed(backend, operation, verdict.assurance)
            is AnkiContinuationVerdict.Blocked -> blocked(
                blockReasonToken(verdict.reason),
                blockError(verdict.reason),
                operation,
                (check as? AnkiIdentityCheck.SameBackend)?.assurance
            )
        }
    }

    /**
     * The frozen promise ∩ live capability (GATE 13 §28): a capability can only shrink, never
     * grow, and an action admitted without authoritative historical-reconciliation support can
     * never gain it later. The OP-M3 continuation matrix separately requires an authoritative
     * collection match; this helper is never a substitute for that identity proof.
     */
    private fun familyReconciliationAvailable(
        record: ReviewerActionRecord,
        backend: AnkiBackend
    ): Boolean =
        record.frozenAuthoritativeReconciliation &&
            backend.reviewerActionSemantics(record.action).supportsAuthoritativeReconciliation

    /**
     * The recovery pass: group durable records by the backend identity **each record carries**,
     * then ask the GATE 21D planner whether that exact instance is registered, reachable and on
     * a compatible collection — with the live keys read from the backends themselves, so the
     * "key recorded, live key unknown" case fails closed instead of routing on the assumption
     * that a missing key is fine (`BACKEND_FAILURE_MATRIX` §6).
     *
     * Returns one route per owner identity. Blocking is per group: one unavailable backend never
     * blocks, rewrites or clears another backend's records (DECISION-21B-16 rule 8).
     */
    fun recoveryPlan(records: Collection<ReviewerActionRecord>): Map<AnkiBackendId, BackendTransactionRoute> {
        val owners = AnkiTransactionRecoveryPlanner.groupByOwnerBackend(records) { it.backendId }
        val bindingKeys = owners.mapValues { (_, group) -> group.firstNotNullOfOrNull(::bindingCollectionKey) }
        val liveKeys = owners.keys.associateWith { registry.find(it)?.currentCollectionIdentity?.collectionKey }
        return planner.plan(owners.keys, bindingKeys, liveKeys)
    }

    private fun blocked(
        token: String,
        error: AnkiError,
        operation: AnkiOperationClass,
        assurance: AnkiIdentityAssurance? = null
    ): ReviewerActionRoute.Blocked = ReviewerActionRoute.Blocked(token, error, operation, assurance)
}
