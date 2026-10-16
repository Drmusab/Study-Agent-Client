package com.studyagent.client.core.anki.create

import com.studyagent.client.core.anki.AnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendRegistry
import com.studyagent.client.core.anki.AnkiContinuationBlockReason
import com.studyagent.client.core.anki.AnkiContinuationVerdict
import com.studyagent.client.core.anki.AnkiIdentityAssurance
import com.studyagent.client.core.anki.AnkiIdentityAssurancePolicy
import com.studyagent.client.core.anki.AnkiIdentityCheck
import com.studyagent.client.core.anki.AnkiIdentityContinuation
import com.studyagent.client.core.anki.AnkiIdentityObservation
import com.studyagent.client.core.anki.AnkiOperationClass
import com.studyagent.client.core.anki.AnkiOperationFacts

/**
 * GATE 21K — where one creation (media store, note creation, hydration read) may go, or why it may
 * not go anywhere right now.
 *
 * `Routed.backend` is always the instance found by the creation's own identity — the draft's
 * context for a new creation, the durable record's `backendId` for everything else. It is never a
 * substitute, never the global preference, never an AUTO result, and never "the first ready
 * backend".
 */
sealed interface NoteCreationRoute {

    data class Routed(
        val backend: AnkiBackend,
        val operation: AnkiOperationClass,
        val assurance: AnkiIdentityAssurance?
    ) : NoteCreationRoute

    /**
     * The creation must not reach any backend. [token] is the stable, content-free name of the
     * blocked cell (`backend_not_registered`, `collection_mismatch`, …) and is what the refusal
     * projection and diagnostics persist.
     */
    data class Blocked(
        val token: String,
        val operation: AnkiOperationClass,
        val assurance: AnkiIdentityAssurance? = null
    ) : NoteCreationRoute
}

/**
 * The operation class of the next backend touch for a durable creation, decided from durable state
 * only. A record that has never crossed a boundary (`PREPARED`, zero attempts, no step entered) is
 * a new mutation admitted inside a bound context (`OP-M1`). Any later dispatch is the repeat of a
 * creation the backend has already proven it did not perform (`OP-M2`) — the only re-dispatch the
 * GATE 18 status machine allows (`RETRY_ALLOWED`, INV-18-08).
 */
fun NoteCreationRecord.creationOperationClass(): AnkiOperationClass =
    if (status == NoteCreationStatus.PREPARED && attemptCount == 0 && lastEnteredStep < 0) {
        AnkiOperationClass.OP_M1
    } else {
        AnkiOperationClass.OP_M2
    }

/**
 * GATE 21K — the creation family's routing decision, built from the locked GATE 21C identity seams
 * and the GATE 21D registry. One pure policy per transaction family (the GATE 21H/21I/21J pattern):
 * no second registry, no resolver, no ledger, no retry engine, no backend call.
 *
 * It reads the global preference **never**. AUTO is permitted only before context binding — to seed
 * a brand-new [AnkiNoteCreationContext] — and that seeding lives at the route boundary, not here.
 * After binding, every decision is taken for the creation's own backend.
 *
 * ## Admission (`OP-M1`)
 *
 * A new creation is admitted against the draft's declared context: the owner must be registered,
 * and its live collection evidence must not contradict the collection the draft was assembled
 * under. The draft's model schema came from a read under that context; creating from it into a
 * verifiably different collection could produce a malformed note (DECISION-21B-21, alternative 1).
 * The draft was assembled in this process under a live binding, so the admission check states
 * `boundIsLiveInThisProcess = true` — the only place this family claims session continuity.
 *
 * ## DECISION-21K-02 — the family's proof of non-creation
 *
 * `OP-M2` (a user retry after proven non-creation) is permitted under nominal or continuity
 * evidence: the `ConfirmedNotCreated` / `ConfirmedNotStored` classification is made by the backend
 * before its irreversible write (the pinned provider throws before `col.addNote` is reached —
 * docs/GATE_18 §9) and does not rest on collection identity — the creation-family analogue of
 * DECISION-21J-02. Under a verified mismatch or a degraded key it is blocked like every other
 * operation: a provably changed collection cannot host a draft assembled for another one.
 *
 * ## Reconciliation (`OP-M3`) and recovery (`OP-P`)
 *
 * This family exposes no reconciliation backend call at all: the pinned backends offer no
 * authoritative "was this note created?" read (CONTRACT-18-20), and the only escape from
 * `AMBIGUOUS` is a human attestation that touches no backend. `authoritativeReconciliationAvailable`
 * is therefore read from the backend's semantics (false at this pin) purely so a future family's
 * fact set stays honest; no route in this gate is ever asked for `OP-M3`. The family's
 * process-death rule is the record's durable status conversion on ledger load plus this routing by
 * `record.backendId`; it does not rest on collection proof (`familyRecoveryRequiresCollectionProof
 * = false`), mirroring 21J.
 *
 * ## One matrix, no family branches
 *
 * Every operation class is answered by [AnkiIdentityContinuation.continuationVerdict], the pure
 * transcription of locked §3.3. This policy re-decides no cell of its own (the DECISION-21J-03
 * lesson): the family's contribution is the [AnkiOperationFacts] it states.
 */
class NoteCreationRoutingPolicy(private val registry: AnkiBackendRegistry) {

    /**
     * Admission of a new creation against its bound context (`OP-M1`): the owner must be
     * registered and its live collection evidence must not contradict the context.
     */
    fun admit(context: AnkiNoteCreationContext): NoteCreationRoute {
        val backend = registry.find(context.backendId)
            ?: return NoteCreationRoute.Blocked(TOKEN_NOT_REGISTERED, AnkiOperationClass.OP_M1)
        return route(
            backend = backend,
            bound = context.observation,
            operation = AnkiOperationClass.OP_M1,
            boundIsLiveInThisProcess = true
        )
    }

    /**
     * Verdict for one durable record and one operation class.
     *
     * [boundIsLiveInThisProcess] is the caller's statement that this process still holds the
     * binding the record belongs to (the in-process retry payload). It is the only input that can
     * produce `SessionContinuityVerified`, and it is never assumed: `false` gives the conservative
     * reading (hydration reads and post-restart recovery always pass `false`).
     */
    fun route(
        record: NoteCreationRecord,
        operation: AnkiOperationClass = record.creationOperationClass(),
        boundIsLiveInThisProcess: Boolean
    ): NoteCreationRoute {
        val backend = registry.find(record.backendId)
            ?: return NoteCreationRoute.Blocked(TOKEN_NOT_REGISTERED, operation)
        return route(
            backend = backend,
            bound = AnkiIdentityObservation(record.backendId, record.collectionKey),
            operation = operation,
            boundIsLiveInThisProcess = boundIsLiveInThisProcess
        )
    }

    private fun route(
        backend: AnkiBackend,
        bound: AnkiIdentityObservation,
        operation: AnkiOperationClass,
        boundIsLiveInThisProcess: Boolean
    ): NoteCreationRoute {
        val live = backend.currentCollectionIdentity?.takeIf { it.backendId == backend.id }
        val check = AnkiIdentityAssurancePolicy.check(
            bound = bound,
            live = AnkiIdentityObservation(backend.id, live?.collectionKey),
            boundIsLiveInThisProcess = boundIsLiveInThisProcess
        )
        if (check is AnkiIdentityCheck.ForeignBackend) {
            // Unreachable: both sides are built from the creation's own backendId and backend.id,
            // which the registry matched exactly. Refused anyway, so a future change cannot route
            // across instances.
            return NoteCreationRoute.Blocked(
                AnkiContinuationBlockReason.FOREIGN_BACKEND.name.lowercase(), operation
            )
        }
        val assurance = (check as AnkiIdentityCheck.SameBackend).assurance

        val facts = AnkiOperationFacts(
            // DECISION-21K-02: the backend's pre-write refusal classification is collection-independent.
            nonApplicationProofIsCollectionIndependent = true,
            authoritativeReconciliationAvailable =
                backend.noteCreationSemantics.authoritativeCreationReconciliation,
            // The creation family's process-death rule is the record's own durable status plus its
            // own backend; recovery never needs collection proof.
            familyRecoveryRequiresCollectionProof = false,
            backendCurrentlyOffersNoCollectionKey = live == null
        )
        return when (val verdict = AnkiIdentityContinuation.continuationVerdict(operation, check, facts)) {
            is AnkiContinuationVerdict.Permitted ->
                NoteCreationRoute.Routed(backend, operation, verdict.assurance)
            is AnkiContinuationVerdict.Blocked ->
                NoteCreationRoute.Blocked(verdict.reason.name.lowercase(), operation, assurance)
        }
    }

    companion object {
        /** Stable token: the owning instance is not configured in this process. */
        const val TOKEN_NOT_REGISTERED: String = "backend_not_registered"
    }
}
