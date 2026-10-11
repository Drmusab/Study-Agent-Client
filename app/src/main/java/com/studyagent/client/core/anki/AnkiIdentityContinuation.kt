package com.studyagent.client.core.anki

/**
 * GATE 21C — the operation-by-assurance decision matrix (§3.3) and its verdicts.
 *
 * Assurance alone decides nothing: what an operation may do under a given category depends on the
 * *operation class*. The eight classes below are the contract's, and the matrix is transcribed
 * cell-for-cell — including the three cells that depend on a fact the caller must state (a proven
 * non-application that does not rest on collection identity, an authoritative reconciliation read,
 * and the backend offering no key at all in the `OP-R1` degraded cell), and the cells that are *not
 * applicable* but are answered fail-closed because a combination that cannot arise must never be
 * silently permitted.
 *
 * The one rule that makes the matrix safe: **a new connection, a fresh probe, a foreground refresh or
 * an agent handshake never changes a category**, and no cell grants mutation recovery on
 * connectivity. This object is pure: it connects to nothing, resolves nothing, retries nothing.
 */

/**
 * The contract's operation classes (BACKEND_IDENTITY_CONTRACT §3.3), named with their own codes so a
 * report or a test can cite a cell unambiguously.
 */
enum class AnkiOperationClass {
    /** `OP-R1` — a new unbound read: no prior identity exists to honour. */
    OP_R1,

    /** `OP-R2` — an existing bound read (library row → details, editor base, open browser context). */
    OP_R2,

    /** `OP-S1` — a new StudySession (the binding moment). */
    OP_S1,

    /** `OP-S2` — continuation of an existing StudySession (next card, hydrate, bury/suspend, end). */
    OP_S2,

    /** `OP-M1` — a new mutation admitted inside a bound context (rating, action, edit, creation). */
    OP_M1,

    /** `OP-M2` — retry of a mutation that is *proven* not applied. */
    OP_M2,

    /** `OP-M3` — reconciliation of a mutation whose outcome is *ambiguous*. */
    OP_M3,

    /** `OP-P` — recovery after process death: durable record → the backend it was written against. */
    OP_P
}

/**
 * The family-specific facts the matrix depends on. Defaults are the conservative answers, so a
 * caller that forgets to state its family's semantics gets a refusal rather than a permission.
 *
 * `familyRecoveryRequiresCollectionProof` defaults to `true` (fail-closed). The locked contract
 * records that all four transaction families implemented today may pass `false` — their recovery
 * rules do not rest on collection identity — and 21H–21L supply that answer per family.
 */
data class AnkiOperationFacts(
    /** `OP-M2`: true only when a family can prove non-application without collection identity. */
    val nonApplicationProofIsCollectionIndependent: Boolean = false,

    /** `OP-M3`: true only when the family exposes an authoritative reconciliation read. */
    val authoritativeReconciliationAvailable: Boolean = false,

    /** `OP-P`: true when this family's recovery rule needs collection proof. */
    val familyRecoveryRequiresCollectionProof: Boolean = true,

    /**
     * `OP-R1` under `IDENTITY_UNVERIFIABLE` (degraded): the live backend is reporting **no**
     * collection key at the moment of the comparison.
     *
     * §3.3's `OP-R1` degraded cell is conditional — "Permitted for a **new** binding only if the
     * backend currently offers no key at all; otherwise blocked" — and this flag is that condition.
     * The permitted branch is the *key lost* direction of §3.5 (the new binding promises nothing, so
     * a lost key cannot be misattributed); the blocked branch is the *key appeared* direction, whose
     * key is "evidence the collection context moved" (§3.5, LOCKED).
     *
     * Default `false` = blocked: a caller that does not state the observation fails closed.
     */
    val backendCurrentlyOffersNoCollectionKey: Boolean = false
)

/** Why one operation may not proceed under the observed identity evidence. Stable, content-free tokens. */
enum class AnkiContinuationBlockReason {
    /** Both keys are known and unequal: fail closed, never migrate, never re-interpret refs. */
    COLLECTION_MISMATCH,

    /** A key was known or expected and is now unavailable: mismatch-grade. */
    IDENTITY_UNVERIFIABLE_DEGRADED,

    /** The bound side and the live side name different configured backends. */
    FOREIGN_BACKEND,

    /** `OP-R1` with degraded evidence: the backend offers a key where the binding had none (§3.5). */
    NEW_BINDING_BLOCKED_BY_APPEARED_KEY,

    /** `OP-R1` with a mismatch: there is no prior binding to contradict. */
    NEW_BINDING_HAS_NO_PRIOR_IDENTITY,

    /** `OP-M3` on continuity alone: continuity cannot establish whether a mutation applied. */
    CONTINUITY_IS_NOT_COLLECTION_EVIDENCE,

    /** `OP-M2` where the family's proof of non-application depends on collection identity. */
    NON_APPLICATION_PROOF_NEEDS_COLLECTION_IDENTITY,

    /** `OP-M3` without both an authoritative reconciliation read and an authoritative match. */
    RECONCILIATION_REQUIRES_AUTHORITATIVE_MATCH,

    /** `OP-P` on continuity: after process death there is no in-process binding to continue. */
    NO_IN_PROCESS_BINDING,

    /** `OP-P` for a family whose recovery rule needs collection proof that is not available. */
    FAMILY_REQUIRES_COLLECTION_PROOF
}

/** What the matrix answers for one operation on one identity evidence. */
sealed interface AnkiContinuationVerdict {

    /** The operation may proceed, under exactly this [assurance]. */
    data class Permitted(val assurance: AnkiIdentityAssurance) : AnkiContinuationVerdict

    /**
     * The operation must not proceed. [observed] carries the whole evidence so the caller can
     * explain itself; a block never substitutes a different backend and never creates a retry.
     */
    data class Blocked(
        val reason: AnkiContinuationBlockReason,
        val observed: AnkiIdentityCheck
    ) : AnkiContinuationVerdict
}

/** §3.3 — the matrix, as one pure function. */
object AnkiIdentityContinuation {

    fun continuationVerdict(
        operation: AnkiOperationClass,
        check: AnkiIdentityCheck,
        facts: AnkiOperationFacts = AnkiOperationFacts()
    ): AnkiContinuationVerdict = when (check) {
        // Two configured instances are never comparable, so no cell can permit one of them for a
        // context bound to the other — including "unverifiable", which would be a false comfort.
        is AnkiIdentityCheck.ForeignBackend ->
            blocked(AnkiContinuationBlockReason.FOREIGN_BACKEND, check)

        is AnkiIdentityCheck.SameBackend ->
            verdictFor(operation, check.assurance, facts, check)
    }

    private fun verdictFor(
        operation: AnkiOperationClass,
        assurance: AnkiIdentityAssurance,
        facts: AnkiOperationFacts,
        observed: AnkiIdentityCheck
    ): AnkiContinuationVerdict = when (assurance) {

        is AnkiIdentityAssurance.AuthoritativeMatch -> when (operation) {
            // The only cell where collection proof exists. Reconciliation still needs the family's
            // own authoritative read: a match proves *which collection*, not *whether it applied*.
            AnkiOperationClass.OP_M3 ->
                if (facts.authoritativeReconciliationAvailable) permitted(assurance)
                else blocked(AnkiContinuationBlockReason.RECONCILIATION_REQUIRES_AUTHORITATIVE_MATCH, observed)

            else -> permitted(assurance)
        }

        AnkiIdentityAssurance.SessionContinuityVerified -> when (operation) {
            // Continuity cannot establish whether an ambiguous mutation applied.
            AnkiOperationClass.OP_M3 ->
                blocked(AnkiContinuationBlockReason.CONTINUITY_IS_NOT_COLLECTION_EVIDENCE, observed)

            // After process death there is no in-process binding; the durable-record rules apply.
            AnkiOperationClass.OP_P ->
                blocked(AnkiContinuationBlockReason.NO_IN_PROCESS_BINDING, observed)

            // A retry may proceed only when the family's non-application proof does not rest on
            // collection identity; connectivity alone is never proof.
            AnkiOperationClass.OP_M2 ->
                if (facts.nonApplicationProofIsCollectionIndependent) permitted(assurance)
                else blocked(AnkiContinuationBlockReason.NON_APPLICATION_PROOF_NEEDS_COLLECTION_IDENTITY, observed)

            else -> permitted(assurance)
        }

        is AnkiIdentityAssurance.IdentityUnverifiable -> when (assurance.qualifier) {

            // Mismatch-grade everywhere, with the one conditional cell the contract spells out.
            AnkiUnverifiabilityQualifier.DEGRADED -> when (operation) {
                AnkiOperationClass.OP_R1 ->
                    if (facts.backendCurrentlyOffersNoCollectionKey) permitted(assurance)
                    else blocked(AnkiContinuationBlockReason.NEW_BINDING_BLOCKED_BY_APPEARED_KEY, observed)

                else -> blocked(AnkiContinuationBlockReason.IDENTITY_UNVERIFIABLE_DEGRADED, observed)
            }

            // Nominal: the backend never had a key for this binding, so the operation continues
            // under the recorded category — disclosed, never described as verified.
            AnkiUnverifiabilityQualifier.NOMINAL -> when (operation) {
                AnkiOperationClass.OP_R1,
                AnkiOperationClass.OP_R2,
                AnkiOperationClass.OP_S1,
                AnkiOperationClass.OP_S2,
                AnkiOperationClass.OP_M1 -> permitted(assurance)

                AnkiOperationClass.OP_M2 ->
                    if (facts.nonApplicationProofIsCollectionIndependent) permitted(assurance)
                    else blocked(
                        AnkiContinuationBlockReason.NON_APPLICATION_PROOF_NEEDS_COLLECTION_IDENTITY,
                        observed
                    )

                AnkiOperationClass.OP_M3 ->
                    blocked(AnkiContinuationBlockReason.RECONCILIATION_REQUIRES_AUTHORITATIVE_MATCH, observed)

                AnkiOperationClass.OP_P ->
                    if (facts.familyRecoveryRequiresCollectionProof) {
                        blocked(AnkiContinuationBlockReason.FAMILY_REQUIRES_COLLECTION_PROOF, observed)
                    } else {
                        permitted(assurance)
                    }
            }
        }

        is AnkiIdentityAssurance.AuthoritativeMismatch -> when (operation) {
            // "n/a" in the contract: a new binding has no prior identity to contradict. Answered as
            // a refusal so a confused caller cannot turn it into a permission.
            AnkiOperationClass.OP_R1 ->
                blocked(AnkiContinuationBlockReason.NEW_BINDING_HAS_NO_PRIOR_IDENTITY, observed)

            else -> blocked(AnkiContinuationBlockReason.COLLECTION_MISMATCH, observed)
        }
    }

    private fun permitted(assurance: AnkiIdentityAssurance): AnkiContinuationVerdict =
        AnkiContinuationVerdict.Permitted(assurance)

    private fun blocked(
        reason: AnkiContinuationBlockReason,
        observed: AnkiIdentityCheck
    ): AnkiContinuationVerdict = AnkiContinuationVerdict.Blocked(reason, observed)
}
