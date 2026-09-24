package com.studyagent.client.core.study

import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating

/**
 * Local Anki interaction data (GATE 10 read path + GATE 11 rating transaction).
 *
 * [commit] is the one rating transaction of the current turn. It is created when a rating is
 * accepted and carries that rating as data for the whole SubmittingRating → outcome lifecycle —
 * there are no per-rating states, and the rating cannot change once recorded.
 */
data class AnkiStudyInteraction(
    val request: AnkiStudyRequest,
    val reviewSession: AnkiReviewSession? = null,
    val turn: AnkiReviewTurn? = null,
    val commit: AnkiRatingCommit? = null,
    val transcript: String? = null,
    val failure: AnkiError? = null,
    val completion: AnkiStudyCompletion? = null,
    /** When the current turn's question was presented; the start of the measured answer time. */
    val turnPresentedAtMs: Long? = null,
    /** AMBIGUOUS commits from earlier sessions, surfaced (never dropped) when a session begins. */
    val priorUnresolvedCommits: Int = 0
) {
    /** GATE 10 name kept for callers: the accepted rating, once a commit exists. */
    val selectedRating: Rating? get() = commit?.rating
}

/**
 * The reducer's view of the current turn's rating transaction.
 *
 * [state] mirrors the durable ledger as far as the reducer knows it: NOT_STARTED = an attempt was
 * prepared and dispatched to the executor; SUBMITTING = the executor reported the ledger marker as
 * durable and the backend call started; COMMITTED / FAILED / AMBIGUOUS = the executor's final,
 * persisted classification. [request] is fixed at selection time and re-sent verbatim by a retry
 * (same commit id, same rating, same timing), which is what makes the retry idempotent.
 */
data class AnkiRatingCommit(
    val request: CommitRatingRequest,
    val state: ReviewCommitState,
    /** Attempts the reducer dispatched (1 = first submission). */
    val attempt: Int = 1,
    val safeToRetry: Boolean = false,
    val failureCategory: String? = null,
    /** A reconciliation read is in flight (AMBIGUOUS only). */
    val reconciling: Boolean = false
) {
    val commitId: ReviewCommitId get() = request.commitId
    val rating: Rating get() = request.rating
    val card: AnkiCardRef get() = request.card
    val isPending: Boolean get() = state == ReviewCommitState.NOT_STARTED || state == ReviewCommitState.SUBMITTING
}

/** Caller supplies a new logical session ID; a display deck name is never an identity. */
data class AnkiStudyRequest(
    val studySessionId: String,
    val preference: AnkiBackendMode,
    val deck: AnkiDeckRef,
    val speakQuestion: Boolean = true
) {
    init { require(studySessionId.isNotBlank()) }
}

enum class AnkiStudyCompletion { NO_DUE_CARDS, USER_ENDED }

/**
 * The executor's persisted classification of one commit attempt or reconciliation, as delivered to
 * the reducer. It always reflects the durable ledger *after* the executor wrote it.
 */
sealed interface AnkiCommitOutcome {
    val state: ReviewCommitState

    /** [source]: `backend_confirmed`, `ledger_replay` (already COMMITTED, no call made) or `reconciled`. */
    data class Committed(val source: String) : AnkiCommitOutcome {
        override val state: ReviewCommitState get() = ReviewCommitState.COMMITTED
    }

    /** Known NOT applied. [dispatched] = the backend was called for this attempt. */
    data class Failed(val category: String, val safeToRetry: Boolean, val dispatched: Boolean) : AnkiCommitOutcome {
        override val state: ReviewCommitState get() = ReviewCommitState.FAILED
    }

    /** May have been applied. Progression stays blocked. */
    data class Ambiguous(val category: String) : AnkiCommitOutcome {
        override val state: ReviewCommitState get() = ReviewCommitState.AMBIGUOUS
    }
}

/**
 * Every read result is scoped to the session epoch; hydration also carries the original turn.
 * GATE 11 commit events are additionally correlated by [ReviewCommitId], which carries the study
 * session id and the review turn id (`sessionId` / `turnId` below), so a result that arrives for
 * any other session, turn or attempt can update the ledger but never the current turn.
 */
sealed interface AnkiStudyEvent : StudyEvent {
    data class Start(val request: AnkiStudyRequest) : AnkiStudyEvent
    data class Begun(
        val epoch: Long,
        val result: AnkiResult<AnkiReviewSession>,
        /** Unresolved AMBIGUOUS commits left by earlier sessions (surfaced, never dropped). */
        val priorUnresolvedCommits: Int = 0
    ) : AnkiStudyEvent
    data class Scheduled(val epoch: Long, val result: NextCardResult) : AnkiStudyEvent
    data class Hydrated(
        val epoch: Long,
        val turnId: ReviewTurnId,
        val result: AnkiResult<AnkiRenderedCard>
    ) : AnkiStudyEvent
    data class SelectRating(val epoch: Long, val turnId: ReviewTurnId, val rating: Rating) : AnkiStudyEvent

    /** The executor made SUBMITTING durable and is starting the backend call for [attempt]. */
    data class RatingCommitStarted(val epoch: Long, val commitId: ReviewCommitId, val attempt: Int) : AnkiStudyEvent {
        val sessionId: String get() = commitId.studySessionId
        val turnId: ReviewTurnId get() = commitId.turnId
    }

    /** Final, persisted outcome of one commit attempt. */
    data class RatingCommitResolved(
        val epoch: Long,
        val commitId: ReviewCommitId,
        val outcome: AnkiCommitOutcome
    ) : AnkiStudyEvent {
        val sessionId: String get() = commitId.studySessionId
        val turnId: ReviewTurnId get() = commitId.turnId
    }

    /** User intent: retry a commit that is known NOT applied — same commit id, same rating. */
    data class RetryRatingCommit(val epoch: Long, val commitId: ReviewCommitId) : AnkiStudyEvent

    /** User intent: check an AMBIGUOUS commit against backend evidence (never a re-rate). */
    data class ReconcileRatingCommit(val epoch: Long, val commitId: ReviewCommitId) : AnkiStudyEvent

    /** Persisted outcome of a reconciliation read. */
    data class RatingCommitReconciled(
        val epoch: Long,
        val commitId: ReviewCommitId,
        val outcome: AnkiCommitOutcome
    ) : AnkiStudyEvent {
        val sessionId: String get() = commitId.studySessionId
        val turnId: ReviewTurnId get() = commitId.turnId
    }

    /** User intent: the next-card *read* failed after a COMMITTED rating; ask the scheduler again. */
    data class RetryNextCard(val epoch: Long) : AnkiStudyEvent
}

/**
 * Anki effects. Read effects (Begin/Next/Hydrate) run in one cancellable read job. The GATE 11
 * write-side effects run in their own job that session end, stop or UI recreation never cancels:
 * an in-flight commit finishes, is persisted, and its late result is correlated (and rejected as
 * stale if the turn is gone). There is still no skip, bury or suspend operation.
 */
sealed interface AnkiStudyEffect : StudyEffect {
    data class Begin(val epoch: Long, val request: AnkiStudyRequest, val startedAtMs: Long) : AnkiStudyEffect
    data class Next(val epoch: Long, val session: AnkiReviewSession) : AnkiStudyEffect
    data class Hydrate(val epoch: Long, val turn: AnkiReviewTurn) : AnkiStudyEffect
    data object CancelReads : AnkiStudyEffect

    /** The single rating mutation path. [retry] = an explicit user retry of a FAILED-safe commit. */
    data class CommitRating(val epoch: Long, val request: CommitRatingRequest, val retry: Boolean = false) : AnkiStudyEffect

    /** Read-only reconciliation of an AMBIGUOUS commit. */
    data class ReconcileCommit(val epoch: Long, val commitId: ReviewCommitId) : AnkiStudyEffect

    /** Release the backend's session handle after the user ended the session. Not a mutation. */
    data class EndReview(val session: AnkiReviewSession) : AnkiStudyEffect
}
