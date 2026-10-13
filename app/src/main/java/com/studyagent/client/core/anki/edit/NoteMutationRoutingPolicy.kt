package com.studyagent.client.core.anki.edit

import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiContinuationBlockReason
import com.studyagent.client.core.anki.AnkiIdentityAssurance
import com.studyagent.client.core.anki.AnkiIdentityAssurancePolicy
import com.studyagent.client.core.anki.AnkiIdentityCheck
import com.studyagent.client.core.anki.AnkiIdentityContinuation
import com.studyagent.client.core.anki.AnkiIdentityObservation
import com.studyagent.client.core.anki.AnkiOperationClass
import com.studyagent.client.core.anki.AnkiOperationFacts
import com.studyagent.client.core.anki.AnkiContinuationVerdict
import com.studyagent.client.core.anki.AnkiUnverifiabilityQualifier

/**
 * GATE 21J — where one note mutation may go, or why it may not go anywhere right now.
 *
 * `Routed.backend` is always the instance found by the record's own `backendId`. It is never a
 * substitute, never the global preference, never an AUTO result, and never "the first ready backend".
 */
sealed interface NoteMutationRoute {

    data class Routed(
        val backend: AnkiBackend,
        val operation: AnkiOperationClass,
        val assurance: AnkiIdentityAssurance?
    ) : NoteMutationRoute

    /**
     * The mutation must not reach any backend. [token] is the stable, content-free name of the blocked
     * cell (`backend_not_registered`, `collection_mismatch`, `collection_identity_degraded`, …) and is
     * what the refusal projection and diagnostics persist.
     */
    data class Blocked(
        val token: String,
        val operation: AnkiOperationClass,
        val assurance: AnkiIdentityAssurance? = null
    ) : NoteMutationRoute
}

/**
 * The operation class of the next backend touch for a durable note mutation, decided from durable
 * state only. A record that has never crossed the boundary (`PREPARED`, zero attempts) is a new
 * mutation admitted inside a bound context (`OP-M1`). Any later boundary entry is a repeat of a
 * mutation already admitted (`OP-M2`), whose permission depends on the proof of non-application.
 */
fun NoteMutationRecord.mutationOperationClass(): AnkiOperationClass =
    if (status == NoteMutationStatus.PREPARED && attemptCount == 0) AnkiOperationClass.OP_M1
    else AnkiOperationClass.OP_M2

/**
 * GATE 21J — the note-mutation family's routing decision, built from the locked GATE 21C identity
 * seams and the GATE 21D registry. One pure policy per transaction family (the GATE 21H/21I pattern):
 * no second registry, no resolver, no ledger, no retry engine, no backend call.
 *
 * It reads the global preference **never**. It consults the registry for existence and the two
 * identity observations (the record's admission-time collection versus the owner's live collection)
 * only. Every decision is taken for the record's own backend.
 *
 * ## DECISION-21J-02 — the family's proof of non-application
 *
 * `OP-M2` (a repeat after a proven pre-write refusal) is permitted under nominal or continuity
 * evidence: the GATE 17 refusal classification is made by the backend before its single provider write
 * (`NoteMutationBackendResult.ConfirmedNotApplied`) and does not rest on collection identity. Under a
 * verified mismatch or a degraded key it is blocked like every other operation.
 *
 * ## DECISION-21J-03 — the family's reconciliation cell (deviates from the generic §3.3 cell, on purpose)
 *
 * The generic matrix blocks `OP-M3` on nominal and continuity evidence because continuity cannot prove
 * that a mutation applied. A note reconciliation is not a continuity claim: it is the owning backend's
 * own read-only check of the same note, and the backend returns `Unresolved` when it has no such read
 * (the GATE 17 default; AnkiDroid claims no authoritative reconciliation). The family therefore blocks
 * `OP-M3` **only** on a verified mismatch or a degraded identity, and otherwise lets the owner's own
 * read decide. The coordinator never applies a status on continuity alone. This is the same family-level
 * decision GATE 21I recorded as DECISION-21I-03, stated here instead of inherited silently.
 *
 * ## Admission (`OP-M1`) and reads
 *
 * A new admission has no prior binding to contradict: the collection observed now is the collection
 * the record is bound to. Only registry existence is checked. Reads that re-check the owner before
 * showing state (`OP-R2`) use the locked matrix unchanged.
 */
class NoteMutationRoutingPolicy(private val registry: AnkiBackendRegistry) {

    /** Admission of a new mutation: the owner must be registered; nothing else is decided here. */
    fun admit(backendId: AnkiBackendId): NoteMutationRoute {
        val backend = registry.find(backendId)
            ?: return NoteMutationRoute.Blocked(TOKEN_NOT_REGISTERED, AnkiOperationClass.OP_M1)
        return NoteMutationRoute.Routed(backend, AnkiOperationClass.OP_M1, assurance = null)
    }

    /**
     * Verdict for one durable record and one operation class.
     *
     * [boundIsLiveInThisProcess] is the caller's statement that this process still holds the binding
     * the record belongs to (the in-process payload). It is the only input that can produce
     * `SessionContinuityVerified`, and it is never assumed: `false` gives the conservative reading.
     */
    fun route(
        record: NoteMutationRecord,
        operation: AnkiOperationClass = record.mutationOperationClass(),
        boundIsLiveInThisProcess: Boolean
    ): NoteMutationRoute {
        val backend = registry.find(record.backendId)
            ?: return NoteMutationRoute.Blocked(TOKEN_NOT_REGISTERED, operation)

        val live = backend.currentCollectionIdentity?.takeIf { it.backendId == backend.id }
        val check = AnkiIdentityAssurancePolicy.check(
            bound = AnkiIdentityObservation(record.backendId, record.collectionRef?.collectionKey),
            live = AnkiIdentityObservation(backend.id, live?.collectionKey),
            boundIsLiveInThisProcess = boundIsLiveInThisProcess
        )
        if (check is AnkiIdentityCheck.ForeignBackend) {
            // Unreachable: both sides are built from record.backendId and backend.id, which the registry
            // matched exactly. Refused anyway, so a future change cannot route across instances.
            return NoteMutationRoute.Blocked(AnkiContinuationBlockReason.FOREIGN_BACKEND.name.lowercase(), operation)
        }
        val assurance = (check as AnkiIdentityCheck.SameBackend).assurance

        if (operation == AnkiOperationClass.OP_M3) {
            // DECISION-21J-03: only a verified mismatch or a degraded key blocks reconciliation.
            val blockedReason = when {
                assurance is AnkiIdentityAssurance.AuthoritativeMismatch -> AnkiContinuationBlockReason.COLLECTION_MISMATCH
                assurance is AnkiIdentityAssurance.IdentityUnverifiable &&
                    assurance.qualifier == AnkiUnverifiabilityQualifier.DEGRADED ->
                    AnkiContinuationBlockReason.IDENTITY_UNVERIFIABLE_DEGRADED
                else -> null
            }
            return if (blockedReason != null) {
                NoteMutationRoute.Blocked(blockedReason.name.lowercase(), operation, assurance)
            } else {
                NoteMutationRoute.Routed(backend, operation, assurance)
            }
        }

        val facts = AnkiOperationFacts(
            // DECISION-21J-02: the backend's pre-write refusal classification is collection-independent.
            nonApplicationProofIsCollectionIndependent = true,
            authoritativeReconciliationAvailable = backend.noteMutationSemantics.authoritativeReconciliation,
            // The note family's process-death rule is the record's own durable status plus its own backend.
            familyRecoveryRequiresCollectionProof = false,
            backendCurrentlyOffersNoCollectionKey = live == null
        )
        return when (val verdict = AnkiIdentityContinuation.continuationVerdict(operation, check, facts)) {
            is AnkiContinuationVerdict.Permitted -> NoteMutationRoute.Routed(backend, operation, verdict.assurance)
            is AnkiContinuationVerdict.Blocked ->
                NoteMutationRoute.Blocked(verdict.reason.name.lowercase(), operation, assurance)
        }
    }

    companion object {
        /** Stable token: the owning instance is not configured in this process. */
        const val TOKEN_NOT_REGISTERED: String = "backend_not_registered"
    }
}
