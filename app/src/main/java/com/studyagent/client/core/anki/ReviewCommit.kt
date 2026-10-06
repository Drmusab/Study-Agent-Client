package com.studyagent.client.core.anki

import com.studyagent.client.core.models.Rating
import kotlinx.serialization.Serializable

/**
 * GATE 11 — durable lifecycle of one rating transaction (one review turn ⇒ at most one intentional
 * scheduler mutation).
 *
 * ```text
 *   NOT_STARTED ──claim──▶ SUBMITTING ──backend confirms applied──▶ COMMITTED
 *        │                     │ ──proven not applied──▶ FAILED_SAFE_TO_RETRY | FAILED_NOT_RETRYABLE
 *        │                     │ ──unknown / timeout / crash──▶ AMBIGUOUS
 *        └─prepare refused─▶ FAILED_SAFE_TO_RETRY     AMBIGUOUS ──authoritative reconciliation──▶ terminal
 * ```
 *
 * Safe-to-retry and non-retryable failures are distinct states. Both mean *known not applied*;
 * AMBIGUOUS means *may have been applied*. Nothing ever moves SUBMITTING back to NOT_STARTED, and
 * nothing retries an AMBIGUOUS commit without reconciliation evidence first.
 */
@Serializable
enum class ReviewCommitState {
    /** Prepared and persisted. The backend has provably not been asked to mutate for this attempt. */
    NOT_STARTED,

    /** An attempt is in progress; [ReviewCommitRecord.phase] says whether the call may have begun. */
    SUBMITTING,

    /** The backend confirmed — or reconciliation evidence proved — that the scheduler applied it. */
    COMMITTED,

    /** Known not applied and explicitly safe to retry with the same commit id and payload. */
    FAILED_SAFE_TO_RETRY,

    /** Known not applied, but the backend or request is not safe to retry. */
    FAILED_NOT_RETRYABLE,

    /** May or may not have been applied. Blocks progression; never retried without reconciliation. */
    AMBIGUOUS
}

/** Physical attempt progress, separate from the logical state. Entered means *may* have mutated. */
@Serializable
enum class CommitAttemptPhase {
    PREPARED,
    MUTATION_CALL_ENTERED,
    MUTATION_RESPONSE_RECEIVED,
    LOCAL_RESULT_PERSISTED
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
 * counters). Persisted with SUBMITTING so reconciliation still has a baseline after process death.
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
data class ReviewCommitFailure(val category: String, val safeToRetry: Boolean) {
    init { require(category.isNotBlank()) }
}

/**
 * One persisted rating transaction. Identity is [commitId] = backend + study session + review
 * turn — never a card id or a timestamp — so it is stable across retries and restores.
 *
 * [rating], [card], [ratedAtEpochMs] and [answerDurationMs] are fixed when the record is created:
 * a retry re-sends exactly [toRequest], and a different rating for the same commit id is a
 * conflict, never an overwrite (the rating is immutable once recorded).
 */
@Serializable
data class ReviewCommitRecord(
    val commitId: ReviewCommitId,
    val card: AnkiCardRef,
    val rating: Rating,
    val state: ReviewCommitState,
    val attemptCount: Int,
    /** Null only before the first attempt (or for a legacy pre-attempt failure). */
    val phase: CommitAttemptPhase? = null,
    /** A definitive backend response, durably recorded before the terminal transition. */
    val response: CommitResponseEvidence? = null,
    /** Turn's deck/collection identity; never a display name. */
    val deckRef: AnkiDeckRef? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val ratedAtEpochMs: Long,
    val answerDurationMs: Long? = null,
    val evidence: ReviewCommitEvidence? = null,
    /** When the latest attempt was marked SUBMITTING (the start of the mutation window). */
    val submittedAtEpochMs: Long? = null,
    /** When the latest attempt (or reconciliation) resolved. */
    val resolvedAtEpochMs: Long? = null,
    val failure: ReviewCommitFailure? = null,
    /** Stable token saying *how* the state was reached (for diagnostics and the report). */
    val resolution: String? = null,
    /** The collection identity is retained when it is known, even if the card ref omitted it. */
    val collectionRef: AnkiCollectionIdentity? = null,
    /** Explicitly separate the user's selection from a proven scheduler effect. */
    val committedRating: Rating? = if (state == ReviewCommitState.COMMITTED) rating else null,
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
     * Never a state, and never proof the scheduler mutation did not happen.
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
        require(state == ReviewCommitState.NOT_STARTED || attemptCount >= 1 ||
            state in FAILED_REVIEW_COMMIT_STATES) { "Only an undispatched transaction may have zero attempts" }
        require(state !in FAILED_REVIEW_COMMIT_STATES || failure != null) { "A known not-applied state needs a reason" }
        require(state != ReviewCommitState.FAILED_SAFE_TO_RETRY || failure?.safeToRetry == true)
        require(state != ReviewCommitState.FAILED_NOT_RETRYABLE || failure?.safeToRetry == false)
        require(phase != CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED || response != null)
        require(response == null || phase == CommitAttemptPhase.MUTATION_RESPONSE_RECEIVED ||
            phase == CommitAttemptPhase.LOCAL_RESULT_PERSISTED)
        require(response?.kind != CommitResponseKind.CONFIRMED_COMMITTED ||
            state !in setOf(ReviewCommitState.AMBIGUOUS, ReviewCommitState.FAILED_SAFE_TO_RETRY,
                ReviewCommitState.FAILED_NOT_RETRYABLE))
        require(if (state == ReviewCommitState.COMMITTED) committedRating == rating else committedRating == null) {
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
    val safeToRetry: Boolean get() = state == ReviewCommitState.FAILED_SAFE_TO_RETRY
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

internal val FAILED_REVIEW_COMMIT_STATES = setOf(
    ReviewCommitState.FAILED_SAFE_TO_RETRY,
    ReviewCommitState.FAILED_NOT_RETRYABLE
)

/** Stable resolution tokens persisted in [ReviewCommitRecord.resolution]. */
object ReviewCommitResolution {
    const val BACKEND_CONFIRMED = "backend_confirmed"
    const val BACKEND_NOT_APPLIED = "backend_not_applied"
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
