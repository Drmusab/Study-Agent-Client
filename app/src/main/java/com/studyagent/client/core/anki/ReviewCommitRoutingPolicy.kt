package com.studyagent.client.core.anki

/**
 * GATE 21H — the **ReviewCommit family's** answers to the questions GATE 21C deliberately left to it,
 * and the one place where a rating transaction's own backend and collection evidence are turned into
 * an admission/routing verdict.
 *
 * ```text
 * ReviewCommitRecord (durable)
 *   ├── backendId            commitId.backendId      → exact registry instance (never AUTO/preference)
 *   ├── binding collection   collectionRef?.collectionKey — what the transaction was admitted against
 *   └── operation class      OP-M1 (new) / OP-M2 (retry) / OP-M3 (reconcile) / OP-P (process death)
 *                 ↓
 * AnkiIdentityAssurancePolicy.check   (§3.5; binding vs live, one configured instance)
 *                 ↓
 * AnkiIdentityContinuation.continuationVerdict   (locked §3.3 matrix + this family's facts)
 *                 ↓
 * Routed(exact backend) | Blocked(stable token)
 * ```
 *
 * What this file is *not*: it is not a second resolver, not a second registry, not a second ledger,
 * and not a retry engine. It never reads the global preference, never calls AUTO, never rewrites a
 * `backendId` or a `collectionKey`, and never issues a backend call — it only decides whether the
 * locked contract permits the operation to be attempted against the record's *own* backend.
 *
 * ## Binding evidence vs resource qualification
 *
 * The collection a transaction was **admitted against** is `ReviewCommitRecord.collectionRef` — the
 * session/context collection written at admission (`StudyReducer` builds `CommitRatingRequest` from
 * `session.context.collection`). A card or deck ref may carry its own collection key as *resource
 * qualification*; that key qualifies the resource, it does not assert what the transaction was bound
 * to, so it is never used to *construct* binding evidence here. When binding evidence exists it is
 * always compared (mismatch and degraded both fail closed); when it is absent the collection
 * dimension is unknown for that transaction, which is the normal AnkiDroid case and is never treated
 * as a verified match (`BACKEND_FAILURE_MATRIX` §6, `BACKEND_IDENTITY_CONTRACT` §6.2 row 1).
 */
object ReviewCommitFamilyFacts {

    /**
     * The ReviewCommit family's answers to [AnkiOperationFacts].
     *
     * - `nonApplicationProofIsCollectionIndependent = true`: a proven non-application for a rating is
     *   a durable, transaction-correlated fact — PREPARED means the mutation boundary was provably
     *   never entered (`ReviewCommitStatus`), and a backend refusal is certified for *this*
     *   `ReviewCommitId` before the provider write. It is never a scheduler observation, so it does
     *   not rest on collection identity (no due-date/interval/reps inference is involved).
     * - `authoritativeReconciliationAvailable` is taken from the **live** backend capability
     *   ([AnkiBackend.reconciliationSupport]), never from a guess: a backend that cannot attribute a
     *   change to a `ReviewCommitId` (`UNSUPPORTED`) cannot reconcile at all, so `OP-M3` stays blocked
     *   for it even under an authoritative match. `PARTIAL` *is* an authoritative read — of the
     *   negative direction only — so it satisfies this fact; which directions a read may prove is
     *   enforced one level down by GATE 11D's capability table, where a `PARTIAL` positive
     *   observation is demoted to "unresolved" instead of being trusted (`ReviewCommitReconciliation`).
     * - `familyRecoveryRequiresCollectionProof = false`: the family's process-death recovery rule is
     *   the record's own durable status plus its own backend (`BACKEND_IDENTITY_CONTRACT` §6.1/§6.2,
     *   `BACKEND_OWNERSHIP_INVARIANTS` §3.3); it does not require a collection proof, and a record
     *   whose collection is unknown recovers through the same family rule with the collection check
     *   inert.
     * - `backendCurrentlyOffersNoCollectionKey` is passed deliberately (GATE 21C residual R-21C-04):
     *   it is the `OP-R1` degraded-cell condition and is stated, never defaulted.
     */
    fun of(backend: AnkiBackend?): AnkiOperationFacts = AnkiOperationFacts(
        nonApplicationProofIsCollectionIndependent = true,
        authoritativeReconciliationAvailable =
            backend?.reconciliationSupport()?.let { it != ReconciliationSupport.UNSUPPORTED } == true,
        familyRecoveryRequiresCollectionProof = false,
        backendCurrentlyOffersNoCollectionKey = backend?.currentCollectionIdentity == null
    )
}

/**
 * The operation class of the attempt that is about to run, decided from durable state only.
 *
 * A record that has never attempted a mutation is a new mutation admitted inside a bound context
 * (`OP-M1`). Anything else that may still reach the scheduler is a repeat attempt of an already
 * admitted transaction — proven-not-applied (`RETRY_ALLOWED`) or a `PREPARED` record whose earlier
 * claim was released — and is therefore `OP-M2`, the class whose permission may depend on whether the
 * family's non-application proof needs collection identity.
 */
fun ReviewCommitRecord.mutationOperationClass(): AnkiOperationClass =
    if (status == ReviewCommitStatus.PREPARED && attemptCount == 0) AnkiOperationClass.OP_M1
    else AnkiOperationClass.OP_M2

/** Where one rating transaction may go, or why it may not go anywhere right now. */
sealed interface ReviewCommitRoute {

    /**
     * The exact backend the transaction belongs to, permitted for this operation under [assurance].
     * The backend is always the one found by `record.backendId` — never a substitute.
     */
    data class Routed(
        val backend: AnkiBackend,
        val operation: AnkiOperationClass,
        val assurance: AnkiIdentityAssurance
    ) : ReviewCommitRoute

    /**
     * The transaction must not reach any backend. [token] is the stable, content-free name of the
     * blocked cell (`backend_not_registered`, `collection_mismatch`, `collection_identity_degraded`,
     * `reconciliation_requires_authoritative_match`, …) and is what diagnostics persist.
     */
    data class Blocked(
        val token: String,
        val error: AnkiError,
        val operation: AnkiOperationClass,
        val assurance: AnkiIdentityAssurance? = null
    ) : ReviewCommitRoute {
        val reason: String get() = token
    }
}

/**
 * GATE 21H — transaction admission and recovery routing for the rating family.
 *
 * Pure with respect to the outside world: it consults the registry (existence, availability) and the
 * two identity observations only. No scheduler call, no preference read, no AUTO resolution, no
 * mutation, no ledger write.
 */
class ReviewCommitRoutingPolicy(
    private val registry: AnkiBackendRegistry,
    private val planner: AnkiTransactionRecoveryPlanner = AnkiTransactionRecoveryPlanner(registry)
) {

    /**
     * The collection a transaction was **admitted against**. Absence is honest evidence, not a
     * wildcard (see this file's header): it never becomes a match and never blocks by itself.
     */
    fun bindingCollectionKey(record: ReviewCommitRecord): String? = record.collectionRef?.collectionKey

    /**
     * Verdict for one durable record.
     *
     * [boundIsLiveInThisProcess] is the caller's statement that *this process* still holds the
     * binding the record belongs to (a session it bound and has not ended). It is the only input
     * that can produce `SESSION_CONTINUITY_VERIFIED`, and it is never assumed: a caller that does not
     * know passes `false` and gets the conservative reading.
     */
    fun route(
        record: ReviewCommitRecord,
        operation: AnkiOperationClass = record.mutationOperationClass(),
        boundIsLiveInThisProcess: Boolean
    ): ReviewCommitRoute = route(
        ownerBackendId = record.backendId,
        bindingCollectionKey = bindingCollectionKey(record),
        operation = operation,
        boundIsLiveInThisProcess = boundIsLiveInThisProcess
    )

    /**
     * The same verdict for a *session-scoped* operation whose binding is the session lock rather than
     * a durable record (a rating admitted inside the live session's own lock).
     */
    fun route(
        ownerBackendId: AnkiBackendId,
        bindingCollectionKey: String?,
        operation: AnkiOperationClass,
        boundIsLiveInThisProcess: Boolean
    ): ReviewCommitRoute {
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
        return when (val verdict = AnkiIdentityContinuation.continuationVerdict(
            operation, check, ReviewCommitFamilyFacts.of(backend)
        )) {
            is AnkiContinuationVerdict.Permitted ->
                ReviewCommitRoute.Routed(backend, operation, verdict.assurance)
            is AnkiContinuationVerdict.Blocked -> blocked(
                blockReasonToken(verdict.reason),
                blockError(verdict.reason),
                operation,
                (check as? AnkiIdentityCheck.SameBackend)?.assurance
            )
        }
    }

    /**
     * The recovery pass: group durable records by the backend identity **each record carries**, then
     * ask the GATE 21D planner whether that exact instance is registered, reachable and on a
     * compatible collection — with the live keys read from the backends themselves, so the
     * "key recorded, live key unknown" case fails closed instead of routing on the assumption that a
     * missing key is fine (`BACKEND_FAILURE_MATRIX` §6).
     *
     * Returns one route per owner identity. Blocking is per group: one unavailable backend never
     * blocks, rewrites or clears another backend's records.
     */
    fun recoveryPlan(records: Collection<ReviewCommitRecord>): Map<AnkiBackendId, BackendTransactionRoute> {
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
    ): ReviewCommitRoute.Blocked = ReviewCommitRoute.Blocked(token, error, operation, assurance)

    companion object {
        /** Stable detail token for a foreign binding: the record names a backend the caller did not. */
        const val FOREIGN_BACKEND: String = "foreign_backend"
    }
}
