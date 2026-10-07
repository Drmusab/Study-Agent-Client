package com.studyagent.client.core.anki

import kotlinx.coroutines.withTimeoutOrNull

/**
 * GATE 11D §11 — what a backend can *authoritatively* prove about a commit whose response was
 * lost. Each backend reports exactly one of the three states; the reconciler enforces it.
 *
 * | State | Meaning |
 * |---|---|
 * | [SUPPORTED] | The backend can prove both directions with transaction-correlated evidence (an answer the backend issued for *this* [ReviewCommitId], not a scheduler observation). |
 * | [PARTIAL] | The backend can authoritatively prove that the mutation did **not** happen, but cannot prove a positive commit. An `Applied` observation is downgraded to [ReviewCommitReconciliationResult.Unresolved] instead of being trusted. |
 * | [UNSUPPORTED] | No transaction-correlated evidence exists. No provider call is issued for reconciliation at all; the record stays blocked. Weak scheduler heuristics (reps/interval/due-date/next-card changes) are never evidence (GATE 11D §13). |
 *
 * Declaration order is capability order (ordinal grows with strength), so `minBy { it.ordinal }`
 * expresses "the capability can only shrink".
 */
enum class ReconciliationSupport { UNSUPPORTED, PARTIAL, SUPPORTED }

/**
 * GATE 11D §9 — the canonical result of one read-only reconciliation of a durable
 * [ReviewCommitRecord].
 *
 * There are deliberately **no probabilistic transaction states**: the backend proved committed,
 * proved not-committed, or could not prove either. "Could not prove" is a first-class answer, not
 * an error to be retried into a guess (INV-11D-19).
 */
sealed interface ReviewCommitReconciliationResult {

    /**
     * Authoritative evidence that the scheduler mutation for this commit was applied.
     * [receipt] is a backend-issued receipt when the backend provides one; it is `null` for
     * backends with no public receipt concept (AnkiDroid) — a null receipt is never fabricated.
     */
    data class ConfirmedCommitted(val receipt: CommitReceipt? = null) : ReviewCommitReconciliationResult

    /** Authoritative evidence that the scheduler mutation for this commit did not happen. */
    data object ConfirmedNotCommitted : ReviewCommitReconciliationResult

    /**
     * The backend could not prove either way: backend or collection unreachable, reconciliation
     * unsupported, card missing, external activity indistinguishable, timeout. The record stays
     * blocked; nothing is inferred (GATE 11D §19/§20). [reason] is a content-free typed error, or
     * `null` when the backend gave an inconclusive observation without a typed error.
     */
    data class Unresolved(val reason: AnkiError? = null) : ReviewCommitReconciliationResult
}

/**
 * GATE 11D §9 — the canonical contract for deciding a durable [ReviewCommitRecord] after the
 * original response was lost.
 *
 * **Read-only** (INV-11D-09): an implementation must not rate, answer, bury, suspend,
 * load-and-advance the scheduler, or mutate a note/card. It gathers evidence only, which is what
 * makes re-running it safe (GATE 11D §34).
 *
 * **Identity rules** (GATE 11D §15-§18):
 * - the backend is resolved from [ReviewCommitRecord.backendId] — the backend the transaction was
 *   locked into when it was created — never from the current global backend preference
 *   (INV-11D-12);
 * - only the record's own card is consulted; the currently-due card is never substituted
 *   (INV-11D-14);
 * - a collection mismatch is [ReviewCommitReconciliationResult.Unresolved], never a redirect of
 *   the transaction to another collection (INV-11D-13).
 */
interface ReviewCommitReconciler {
    suspend fun reconcile(record: ReviewCommitRecord): ReviewCommitReconciliationResult
}

/**
 * Default [ReviewCommitReconciler]: dispatches to the backend the transaction belongs to and maps
 * that backend's evidence into the canonical result.
 *
 * The capability gate uses the **frozen** semantics recorded with the transaction when they exist
 * (a transaction is never upgraded or downgraded mid-flight); the frozen promise is then capped by
 * what the backend still claims to support live, because a capability can only shrink. When the
 * effective capability is [ReconciliationSupport.UNSUPPORTED] no backend call is issued at all —
 * an AnkiDroid-class backend (GATE 11D §12) cannot attribute a card-state change to a
 * `ReviewCommitId`, so calling it would only produce a heuristic this type refuses to report.
 */
class AnkiReviewCommitReconciler(
    private val registry: AnkiBackendRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) : ReviewCommitReconciler {
    init { require(timeoutMs > 0) }

    /** The effective capability for this record: frozen promise ∩ live backend capability. */
    fun effectiveSupport(record: ReviewCommitRecord, backend: AnkiBackend): ReconciliationSupport {
        val live = backend.reconciliationSupport()
        if (record.frozenGuarantee == null) return live
        val promised = if (record.frozenAuthoritativeReconciliation) ReconciliationSupport.SUPPORTED
        else ReconciliationSupport.UNSUPPORTED
        return if (promised.ordinal <= live.ordinal) promised else live
    }

    override suspend fun reconcile(record: ReviewCommitRecord): ReviewCommitReconciliationResult {
        // §15 — the backend locked into the original transaction, not the current preference.
        val backend = registry.find(record.backendId)
            ?: return ReviewCommitReconciliationResult.Unresolved(AnkiError.BackendUnavailable())
        // §18 — the transaction's own card identity must belong to that backend.
        if (record.card.backendId != backend.id ||
            record.deckRef?.let { it.backendId != backend.id } == true
        ) {
            return ReviewCommitReconciliationResult.Unresolved(AnkiError.SessionInvalid())
        }
        val support = effectiveSupport(record, backend)
        if (support == ReconciliationSupport.UNSUPPORTED) {
            // §11/§12 — no provider call, no heuristic, no guess: the record stays blocked.
            return ReviewCommitReconciliationResult.Unresolved(
                AnkiError.UnsupportedApi(null, 0, "authoritative_reconciliation_unsupported"))
        }
        val submittedAt = record.submittedAtEpochMs ?: record.createdAtEpochMs
        val windowEnd = maxOf(submittedAt, record.resolvedAtEpochMs ?: clock())
        // Read-only and bounded: a hung provider query expires as "still unknown", never as
        // "not applied" (INV-11D-15/§20). Backend methods return typed results and propagate
        // only coroutine cancellation (AnkiBackend contract); the coordinator guards the call
        // against contract violations.
        val evidence: ReconcileCommitResult? = withTimeoutOrNull(timeoutMs) {
            backend.reconcileCommit(ReconcileCommitRequest(
                record.commitId, record.card, record.rating, record.evidence, submittedAt, windowEnd
            ))
        }
        return when (val result = evidence) {
            null -> ReviewCommitReconciliationResult.Unresolved(AnkiError.QueryFailure("reconcile_timeout"))
            is ReconcileCommitResult.Applied ->
                if (support == ReconciliationSupport.SUPPORTED) {
                    // §16 — only the strongest state trusts a positive commit; PARTIAL downgrades.
                    ReviewCommitReconciliationResult.ConfirmedCommitted(result.receipt)
                } else {
                    ReviewCommitReconciliationResult.Unresolved(
                        AnkiError.UnsupportedApi(null, 0, "partial_reconciliation_cannot_confirm_commit"))
                }
            is ReconcileCommitResult.NotApplied -> ReviewCommitReconciliationResult.ConfirmedNotCommitted
            is ReconcileCommitResult.StillAmbiguous -> ReviewCommitReconciliationResult.Unresolved(
                AnkiError.QueryFailure(result.detail))
            is ReconcileCommitResult.Unavailable -> ReviewCommitReconciliationResult.Unresolved(result.error)
            is ReconcileCommitResult.Unsupported -> ReviewCommitReconciliationResult.Unresolved(
                AnkiError.UnsupportedApi(null, 0, result.detail))
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 10_000L
    }
}
