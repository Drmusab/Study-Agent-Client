package com.studyagent.client.core.anki

import com.studyagent.client.core.models.Rating
import kotlinx.serialization.Serializable

/**
 * GATE 11B — NORMATIVE NAMING MODEL.
 *
 * Seven layers, seven vocabularies. A name belongs to exactly one of them:
 *
 * | Layer | Type | Example |
 * |---|---|---|
 * | study workflow | `StudyState` / `SessionPhase` | `WaitingForRating` |
 * | durable transaction truth | [ReviewCommitStatus] | `AMBIGUOUS` |
 * | attempt progress (diagnostics) | [ReviewCommitPhase] | `MUTATION_BOUNDARY_ENTERED` |
 * | backend evidence | [BackendCommitResult] | `OutcomeUnknown` |
 * | coordinator result | [ReviewCommitOutcome] | `Ambiguous` |
 * | recovery decision | [ReviewCommitRecoveryAction] | `Reconcile` |
 * | presentation | `RatingCommitUiState` | `VerificationRequired` |
 *
 * `Rating` is the single rating type of this codebase (AGAIN/HARD/GOOD/EASY). GATE 11B's
 * specification writes `AnkiRating` in its recommended model; no second rating enum is introduced
 * because that would create exactly the synonym the gate forbids.
 */
object ReviewCommitNaming

/**
 * The **only** durable transaction status type (INV-11B-01).
 *
 * ```text
 *   (no record) ──PrepareCommit──▶ PREPARED ──EnterMutationBoundary──▶ SUBMITTING
 *                                     ▲                                    │
 *                                     │                    ┌───────────────┼───────────────┐
 *                                     │                    ▼               ▼               ▼
 *                                     │              COMMITTED        AMBIGUOUS      RETRY_ALLOWED
 *                                     │              (terminal)            │               │
 *                                     └──────────────BeginRetry────────────┘               │
 *                                                    (after reconciliation proves          │
 *                                                     no mutation)  ◀──────────────────────┘
 * ```
 *
 * There is deliberately **no `NOT_STARTED`** (INV-11B-05): a transaction that does not exist is
 * already expressed by the absence of a [ReviewCommitRecord]. The first durable status of a record
 * that exists is [PREPARED].
 *
 * The durable status directly encodes the mutation boundary, so recovery never has to infer
 * safety from status + phase (GATE 11B §27):
 *
 * ```text
 * PREPARED   = the backend mutation boundary has NOT been entered
 * SUBMITTING = the backend mutation boundary HAS been entered
 * ```
 */
@Serializable
enum class ReviewCommitStatus {
    /**
     * A logical review commit exists and its intent is durably recorded, but the backend mutation
     * boundary has not yet been entered. The mutation may still be safely avoided, and after a
     * process restart the same commit may be submitted again.
     */
    PREPARED,

    /**
     * The transaction has crossed the mutation boundary: the backend mutation may have occurred.
     * Potentially dangerous after process loss — it must never retry automatically after restart.
     */
    SUBMITTING,

    /**
     * Backend success has been confirmed and the successful transaction result has been durably
     * recorded. **Terminal** (INV-11B-08). Only this status makes next-card progression legal
     * (INV-11B-15).
     */
    COMMITTED,

    /**
     * There is authoritative evidence that the backend mutation did **not** occur, so the same
     * logical commit may be submitted again. The only durable status from which a mutation retry
     * may begin (INV-11B-06).
     *
     * This replaces the retired `FAILED_SAFE_TO_RETRY` / `FAILED_NOT_RETRYABLE` split: the canonical
     * status set is closed, and a permanently refused rating is still *safe* to submit again (it
     * simply fails the same way). Whether the *UI offers* that retry, and whether an automatic
     * retry is allowed, is policy — not a second status. The old distinction survives as a stable
     * [ReviewCommitResolution] token for diagnostics only.
     */
    RETRY_ALLOWED,

    /**
     * The backend mutation may or may not have occurred and the system cannot currently prove
     * which. No retry and no next card (INV-11B-07). Requires reconciliation or session
     * abandonment.
     */
    AMBIGUOUS
}

/**
 * How far the **latest execution attempt** progressed (INV-11B-02). Diagnostics and crash analysis
 * only — it is not transaction truth and recovery safety never depends on it (GATE 11B §27).
 *
 * Status and phase never share a name, so `status = SUBMITTING, phase = INTENT_PERSISTED` is not
 * an expressible combination and every log line names exactly one layer.
 */
@Serializable
enum class ReviewCommitPhase {
    /** The durable intent exists; the boundary has not been entered. */
    INTENT_PERSISTED,

    /** The durable `SUBMITTING` write landed, immediately before the real scheduler mutation. */
    MUTATION_BOUNDARY_ENTERED,

    /** The classified backend answer has been durably recorded, before any terminal status. */
    BACKEND_RESPONSE_RECEIVED,

    /** The terminal status of this attempt is durable. */
    FINAL_STATUS_PERSISTED
}

/** Only a durably recorded definitive response can be finalized after process death without replay. */
@Serializable
enum class CommitResponseKind { CONFIRMED_COMMITTED, CONFIRMED_NOT_APPLIED, OUTCOME_UNKNOWN }

/** Content-free copy of an actual backend response. Not a fabricated backend receipt. */
@Serializable
data class CommitResponseEvidence(
    val kind: CommitResponseKind,
    val failure: ReviewCommitFailure? = null,
    val backendReceiptId: String? = null
) {
    init {
        require(kind == CommitResponseKind.CONFIRMED_COMMITTED || backendReceiptId == null)
        require(kind == CommitResponseKind.CONFIRMED_COMMITTED || failure != null)
        require(kind != CommitResponseKind.CONFIRMED_COMMITTED || failure == null)
        require(backendReceiptId == null || backendReceiptId.isNotBlank())
    }
}

/**
 * Opaque evidence a backend captured *before* mutating (for AnkiDroid: the card's stored review
 * counters). Persisted with the record so reconciliation still has a baseline after process death.
 *
 * Content-free by contract: identifiers and counters only — never question/answer text, HTML,
 * transcripts or file paths. Only the backend that produced it interprets [token].
 */
@Serializable
data class ReviewCommitEvidence(val format: String, val token: String) {
    init {
        require(format.isNotBlank()) { "Evidence needs a format tag" }
        require(token.length <= MAX_TOKEN_LENGTH) { "Evidence is a small counter snapshot, not content" }
    }

    companion object {
        const val MAX_TOKEN_LENGTH = 512
    }
}

/** Why an attempt did not commit. [category] is a stable, content-free token. */
@Serializable
data class ReviewCommitFailure(val category: String) {
    init { require(category.isNotBlank()) }
}

/**
 * One persisted rating transaction. Identity is [commitId] = backend + study session + review
 * turn — never a card id or a timestamp — so it is stable across retries and restores.
 *
 * [rating], [card], [ratedAtEpochMs] and [answerDurationMs] are fixed when the record is created:
 * a retry re-sends exactly [toRequest], and a different rating for the same commit id is a
 * conflict, never an overwrite (the rating is immutable once recorded).
 *
 * [status] is the durable transaction truth; [phase] is attempt progress. Nothing else in the
 * codebase may name a commit transaction state.
 */
@Serializable
data class ReviewCommitRecord(
    val commitId: ReviewCommitId,
    val card: AnkiCardRef,
    val rating: Rating,
    val status: ReviewCommitStatus,
    val attemptCount: Int,
    /** Null only for a legacy record that never entered an attempt. */
    val phase: ReviewCommitPhase? = null,
    /** A definitive backend response, durably recorded before the terminal transition. */
    val response: CommitResponseEvidence? = null,
    /** Turn's deck/collection identity; never a display name. */
    val deckRef: AnkiDeckRef? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val ratedAtEpochMs: Long,
    val answerDurationMs: Long? = null,
    val evidence: ReviewCommitEvidence? = null,
    /**
     * When the *current* attempt was claimed for mutation, i.e. handed to exactly one caller.
     *
     * GATE 11B §28: this is a durable fact about the attempt, not a status. It is what makes the
     * claim exclusive without inventing a fifth status: a claim is refused while it is set, and
     * every transition that leaves PREPARED clears it. A record that is still PREPARED with the
     * marker set was interrupted before the boundary, so load-time recovery clears it.
     */
    val claimedAtEpochMs: Long? = null,
    /** When the latest attempt entered the mutation boundary (the start of the mutation window). */
    val submittedAtEpochMs: Long? = null,
    /** When the latest attempt (or reconciliation) resolved. */
    val resolvedAtEpochMs: Long? = null,
    val failure: ReviewCommitFailure? = null,
    /** Stable token saying *how* the status was reached (for diagnostics and the report). */
    val resolution: String? = null,
    /** The collection identity is retained when it is known, even if the card ref omitted it. */
    val collectionRef: AnkiCollectionIdentity? = null,
    /** Explicitly separate the user's selection from a proven scheduler effect. */
    val committedRating: Rating? = if (status == ReviewCommitStatus.COMMITTED) rating else null,
    /** The user has seen and dismissed a failed/ambiguous outcome; only then may it be pruned. */
    val acknowledged: Boolean = false,
    /**
     * Optimistic concurrency token. The ledger increments it on every successful write.
     * `0` is the value of a newly prepared record that has not yet transitioned.
     */
    val version: Long = 0,
    /** Semantics frozen when the record was created. Null on records written before this field. */
    val frozenGuarantee: CommitGuaranteeLevel? = null,
    val frozenIdempotentReplay: Boolean = false,
    val frozenAuthoritativeReconciliation: Boolean = false,
    /**
     * Historical note that the UI session was left while this transaction was unfinished.
     * Never a status, and never proof the scheduler mutation did not happen.
     */
    val abandonedAtEpochMs: Long? = null
) {
    init {
        require(commitId.backendId == card.backendId) { "A commit and its card share one backend" }
        require(deckRef == null || (deckRef.backendId == backendId &&
            (deckRef.collectionKey == null || card.collectionKey == null || deckRef.collectionKey == card.collectionKey)))
        require(collectionRef == null || collectionRef.backendId == backendId)
        require(collectionRef?.collectionKey == null || card.collectionKey == null ||
            collectionRef.collectionKey == card.collectionKey)
        require(collectionRef?.collectionKey == null || deckRef?.collectionKey == null ||
            collectionRef.collectionKey == deckRef.collectionKey)
        require(attemptCount >= 0)
        require(status != ReviewCommitStatus.AMBIGUOUS || attemptCount >= 1)
        require(status != ReviewCommitStatus.COMMITTED || attemptCount >= 1)
        require(status != ReviewCommitStatus.SUBMITTING || attemptCount >= 1)
        // GATE 11B §21: only an unfinished-but-resolved transaction carries a reason — a
        // proven not-applied one (RETRY_ALLOWED) and an unknown one (AMBIGUOUS). PREPARED,
        // SUBMITTING and COMMITTED carry none.
        require(failure == null || status in setOf(
            ReviewCommitStatus.RETRY_ALLOWED, ReviewCommitStatus.AMBIGUOUS)) {
            "Only a resolved-but-unfinished transaction carries a failure reason"
        }
        require(status != ReviewCommitStatus.RETRY_ALLOWED || failure != null) {
            "A proven not-applied transaction needs a reason"
        }
        require(status != ReviewCommitStatus.AMBIGUOUS || failure != null) {
            "An unknown-outcome transaction needs a reason"
        }
        require(phase != ReviewCommitPhase.BACKEND_RESPONSE_RECEIVED || response != null)
        require(response == null || phase == ReviewCommitPhase.BACKEND_RESPONSE_RECEIVED ||
            phase == ReviewCommitPhase.FINAL_STATUS_PERSISTED)
        require(response?.kind != CommitResponseKind.CONFIRMED_COMMITTED ||
            status !in setOf(ReviewCommitStatus.AMBIGUOUS, ReviewCommitStatus.RETRY_ALLOWED))
        require(if (status == ReviewCommitStatus.COMMITTED) committedRating == rating else committedRating == null) {
            "Only a committed transaction carries committedRating, equal to the selection"
        }
        require(ratedAtEpochMs >= 0 && (answerDurationMs == null || answerDurationMs >= 0))
        require(version >= 0)
        require(abandonedAtEpochMs == null || abandonedAtEpochMs >= 0)
        require(!frozenIdempotentReplay || frozenGuarantee == CommitGuaranteeLevel.IDEMPOTENT_REPLAY_SUPPORTED ||
            frozenGuarantee == CommitGuaranteeLevel.END_TO_END_EXACTLY_ONCE)
    }

    val sessionId: String get() = commitId.studySessionId
    val turnId: ReviewTurnId get() = commitId.turnId
    val backendId: AnkiBackendId get() = commitId.backendId
    val cardRef: AnkiCardRef get() = card
    val collectionKey: String? get() = collectionRef?.collectionKey ?: card.collectionKey ?: deckRef?.collectionKey
    val selectedRating: Rating get() = rating
    /** GATE 11B §14: PREPARED and SUBMITTING both project to `Saving`. */
    val isPending: Boolean get() = status == ReviewCommitStatus.PREPARED || status == ReviewCommitStatus.SUBMITTING
    val receipt: CommitReceipt?
        get() = response?.takeIf {
            it.kind == CommitResponseKind.CONFIRMED_COMMITTED && it.backendReceiptId != null
        }?.let { CommitReceipt(backendId, it.backendReceiptId, committedRating ?: selectedRating) }

    /** The exact request every attempt sends — identical across retries and restores. */
    fun toRequest(): CommitRatingRequest =
        CommitRatingRequest(commitId, card, rating, ratedAtEpochMs, answerDurationMs, evidence, deckRef, collectionRef)

    /** Same transaction payload: identity, card, collection, deck and rating. */
    fun samePayload(request: CommitRatingRequest): Boolean =
        request.commitId == commitId && request.card == card && request.rating == rating &&
            (deckRef == null || request.deckRef == deckRef) &&
            (collectionRef == null || request.collectionRef == collectionRef)
}

/** Stable resolution tokens persisted in [ReviewCommitRecord.resolution]. */
object ReviewCommitResolution {
    const val BACKEND_CONFIRMED = "backend_confirmed"
    const val BACKEND_NOT_APPLIED = "backend_not_applied"
    /** The backend refused the request outright; retrying is safe but will fail the same way. */
    const val BACKEND_REJECTED = "backend_rejected"
    const val BACKEND_AMBIGUOUS = "backend_ambiguous"
    const val REFUSED_BEFORE_DISPATCH = "refused_before_dispatch"
    const val INTERRUPTED_AFTER_DISPATCH = "interrupted_after_dispatch"
    const val PROCESS_RESTART_WHILE_SUBMITTING = "process_restart_while_submitting"
    const val RECOVERED_PREPARED = "recovered_before_mutation"
    const val RECOVERED_RESPONSE = "recovered_durable_response"
    const val RECONCILED_APPLIED = "reconciled_applied"
    const val RECONCILED_NOT_APPLIED = "reconciled_not_applied"
    const val RECONCILIATION_INCONCLUSIVE = "reconciliation_inconclusive"
}

/**
 * GATE 11B §11 — the status a durable backend fact must produce. The mapping is total and lives in
 * exactly one place, so the coordinator, the ledger and the tests cannot disagree.
 */
fun BackendCommitResult.toReviewCommitStatus(): ReviewCommitStatus = when (this) {
    is BackendCommitResult.ConfirmedCommitted -> ReviewCommitStatus.COMMITTED
    is BackendCommitResult.ConfirmedNotCommitted -> ReviewCommitStatus.RETRY_ALLOWED
    is BackendCommitResult.OutcomeUnknown -> ReviewCommitStatus.AMBIGUOUS
}

/** Content-free failure token for a domain error (never exception or provider text). */
fun AnkiError.commitCategory(): String = when (this) {
    is AnkiError.QueryFailure -> "query_failure:${causeCategory}"
    is AnkiError.MalformedResponse -> "malformed:${detail ?: "unspecified"}"
    is AnkiError.InvalidRequest -> "invalid_request:${detail ?: "unspecified"}"
    is AnkiError.StaleCardReference -> "stale_card:${detail ?: "unspecified"}"
    is AnkiError.UnsupportedAction -> "unsupported:$action"
    is AnkiError.Unknown -> "unknown:${cause ?: "unspecified"}"
    is AnkiError.ProviderUnavailable -> "provider_unavailable"
    is AnkiError.UnsupportedApi -> "unsupported_api"
    is AnkiError.BackendUnavailable -> "backend_unavailable"
    is AnkiError.PermissionRequired -> "permission_required"
    is AnkiError.CollectionUnavailable -> "collection_unavailable"
    is AnkiError.DeckNotFound -> "deck_not_found"
    is AnkiError.CardNotFound -> "card_not_found"
    is AnkiError.CommitConflict -> "commit_conflict"
    is AnkiError.NoteNotFound -> "note_not_found"
    is AnkiError.SessionInvalid -> "session_invalid"
    is AnkiError.CommitLedgerUnavailable -> "commit_ledger_unavailable"
    is AnkiError.StaleTurn -> "stale_turn"
    is AnkiError.MediaUnavailable -> "media_unavailable"
}.take(96)
