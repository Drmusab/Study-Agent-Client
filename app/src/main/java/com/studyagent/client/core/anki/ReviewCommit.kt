package com.studyagent.client.core.anki

import com.studyagent.client.core.models.Rating
import kotlinx.serialization.Serializable

/**
 * GATE 11 — durable lifecycle of one rating transaction (one review turn ⇒ at most one intentional
 * scheduler mutation).
 *
 * ```text
 *   NOT_STARTED ──claim──▶ SUBMITTING ──backend confirms applied──▶ COMMITTED
 *        │                     │ ──backend proves NOT applied──▶ FAILED (safeToRetry?)
 *        │                     │ ──unknown / timeout / crash──▶ AMBIGUOUS
 *        └─prepare refused─▶ FAILED            AMBIGUOUS ──authoritative reconciliation──▶ COMMITTED | FAILED
 * ```
 *
 * [FAILED] and [AMBIGUOUS] are deliberately separate facts: FAILED means *known not applied*,
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

    /** Known NOT applied. [ReviewCommitFailure.safeToRetry] says whether the same commit may retry. */
    FAILED,

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
    /** The user has seen and dismissed a FAILED/AMBIGUOUS outcome; only then may it be pruned. */
    val acknowledged: Boolean = false
) {
    init {
        require(commitId.backendId == card.backendId) { "A commit and its card share one backend" }
        require(deckRef == null || (deckRef.backendId == backendId &&
            (deckRef.collectionKey == null || card.collectionKey == null || deckRef.collectionKey == card.collectionKey)))
        require(attemptCount >= 0)
        require(state == ReviewCommitState.NOT_STARTED || attemptCount >= 1 ||
            state == ReviewCommitState.FAILED) { "Only a never-dispatched record has zero attempts" }
        require(state != ReviewCommitState.FAILED || failure != null) { "FAILED needs a reason" }
        require(ratedAtEpochMs >= 0 && (answerDurationMs == null || answerDurationMs >= 0))
    }

    val sessionId: String get() = commitId.studySessionId
    val turnId: ReviewTurnId get() = commitId.turnId
    val backendId: AnkiBackendId get() = commitId.backendId
    val cardRef: AnkiCardRef get() = card
    val safeToRetry: Boolean get() = state == ReviewCommitState.FAILED && failure?.safeToRetry == true

    /** The exact request every attempt sends — identical across retries and restores. */
    fun toRequest(): CommitRatingRequest =
        CommitRatingRequest(commitId, card, rating, ratedAtEpochMs, answerDurationMs, evidence, deckRef)

    /** Same transaction payload: identity, card, deck and rating. Timing fields never make a conflict. */
    fun samePayload(request: CommitRatingRequest): Boolean =
        request.commitId == commitId && request.card == card && request.rating == rating &&
            (deckRef == null || request.deckRef == deckRef)
}

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
