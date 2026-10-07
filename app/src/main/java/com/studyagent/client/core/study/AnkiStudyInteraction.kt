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
    val priorUnresolvedCommits: Int = 0,
    /** A durable unfinished transaction blocked startup before any scheduler query. */
    val restoredCommit: Boolean = false,
    val blockedByPriorCommit: Boolean = false,
    /**
     * GATE 11B PART V — the durable ledger contradicted this session's projection, and the ledger
     * won. Recorded (never silently dropped) so diagnostics can emit
     * [CommitTruthDiagnostics.EVENT_COMMIT_STATE_PROJECTION_MISMATCH]. It is a fact *about* the
     * projection, not a second commit state.
     */
    val projectionMismatch: CommitProjectionMismatch? = null
) {
    /** User choice only; before durable COMMITTED it is not a scheduler fact. */
    val selectedRating: Rating? get() = commit?.selectedRating
    /** Non-null only after the durable transaction is COMMITTED. */
    val committedRating: Rating? get() = commit?.committedRating
}

/**
 * The reducer's view of the current turn's rating transaction.
 *
 * [status] is the durable [ReviewCommitStatus] as far as the reducer knows it — the same
 * vocabulary, never a study-level synonym: PREPARED = the attempt was prepared and dispatched to
 * the coordinator; SUBMITTING = the coordinator reported the durable boundary marker, so the
 * backend mutation may have happened; RETRY_ALLOWED / AMBIGUOUS / COMMITTED = the coordinator's
 * final, persisted classification.
 *
 * Presentation is derived from this into [RatingCommitUiState]; the two are never stored apart.
 *
 * [request] is fixed at selection time and re-sent verbatim by a retry (same commit id, same
 * rating, same timing), which is what makes the retry idempotent.
 */
data class AnkiRatingCommit(
    val request: CommitRatingRequest,
    val status: ReviewCommitStatus,
    /** Attempts the reducer dispatched (1 = first submission). */
    val attempt: Int = 1,
    val failureCategory: String? = null,
    /** A reconciliation read is in flight (AMBIGUOUS only). */
    val reconciling: Boolean = false,
    /** True only when reconciliation evidence, not the original response, proved COMMITTED. */
    val verifiedByReconciliation: Boolean = false,
    /** Guarantee frozen with the durable record; null until the executor reports it. */
    val guaranteeLevel: CommitGuaranteeLevel? = null
) {
    val commitId: ReviewCommitId get() = request.commitId
    val rating: Rating get() = request.rating
    val card: AnkiCardRef get() = request.card
    val selectedRating: Rating get() = request.rating
    val committedRating: Rating? get() = if (status == ReviewCommitStatus.COMMITTED) request.rating else null
    val isPending: Boolean get() = status == ReviewCommitStatus.PREPARED || status == ReviewCommitStatus.SUBMITTING
    /** The presentation projection of this transaction (GATE 11B §14). */
    val commitUiState: RatingCommitUiState
        get() = when (status) {
            ReviewCommitStatus.PREPARED, ReviewCommitStatus.SUBMITTING -> RatingCommitUiState.Saving(commitId)
            ReviewCommitStatus.RETRY_ALLOWED -> RatingCommitUiState.RetryAvailable(commitId)
            ReviewCommitStatus.AMBIGUOUS -> RatingCommitUiState.VerificationRequired(commitId)
            ReviewCommitStatus.COMMITTED -> RatingCommitUiState.Saved(commitId, request.rating)
        }
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
    /** The durable [ReviewCommitStatus] this outcome reflects. Never a second status vocabulary. */
    val status: ReviewCommitStatus

    /** [source]: `backend_confirmed`, `ledger_replay` (already COMMITTED, no call made) or `reconciled`. */
    data class Committed(val source: String) : AnkiCommitOutcome {
        override val status: ReviewCommitStatus get() = ReviewCommitStatus.COMMITTED
    }

    /**
     * Known NOT applied: the backend proved it did not mutate. [dispatched] says whether the
     * backend was invoked for this attempt at all.
     */
    data class Failed(val category: String, val dispatched: Boolean) : AnkiCommitOutcome {
        override val status: ReviewCommitStatus get() = ReviewCommitStatus.RETRY_ALLOWED
    }

    /** May have been applied. Progression stays blocked. */
    data class Ambiguous(val category: String) : AnkiCommitOutcome {
        override val status: ReviewCommitStatus get() = ReviewCommitStatus.AMBIGUOUS
    }

    /** The backend result cannot yet be durably recorded; NOT a backend failure or retry grant. */
    data class PersistenceFailure(val category: String) : AnkiCommitOutcome {
        override val status: ReviewCommitStatus get() = ReviewCommitStatus.SUBMITTING
    }

    companion object {
        /** The answer came from this attempt's own backend call. */
        const val SOURCE_BACKEND_CONFIRMED = "backend_confirmed"
        /** The answer came from the durable ledger, with no backend call at all. */
        const val SOURCE_LEDGER_REPLAY = "ledger_replay"
        /** The transaction identity was retired; the ledger answered from its tombstone. */
        const val SOURCE_LEDGER_TOMBSTONE = "ledger_tombstone"
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
        /** Unresolved AMBIGUOUS commits in other collections (informational only). */
        val priorUnresolvedCommits: Int = 0
    ) : AnkiStudyEvent
    /** Startup scan found an affected session/collection: no beginReview/nextCard has occurred. */
    data class RecoveryBlocked(val epoch: Long, val record: ReviewCommitRecord) : AnkiStudyEvent
    data class Scheduled(val epoch: Long, val result: NextCardResult) : AnkiStudyEvent
    data class Hydrated(
        val epoch: Long,
        val turnId: ReviewTurnId,
        val result: AnkiResult<AnkiRenderedCard>
    ) : AnkiStudyEvent
    data class SelectRating(val epoch: Long, val turnId: ReviewTurnId, val rating: Rating) : AnkiStudyEvent

    /**
     * The transaction row is durable and the backend's guarantee has been frozen with it. This is
     * correlation, not dispatch: the write has not been claimed and a preflight refusal must still
     * be able to fail safe, so no phase or commit-state change depends on this event.
     */
    data class RatingCommitPrepared(
        val epoch: Long,
        val commitId: ReviewCommitId,
        val guaranteeLevel: CommitGuaranteeLevel?
    ) : AnkiStudyEvent {
        val sessionId: String get() = commitId.studySessionId
        val turnId: ReviewTurnId get() = commitId.turnId
    }

    /** SUBMITTING/PREPARED is durable; backend preflight may still precede call entry. */
    data class RatingCommitStarted(
        val epoch: Long,
        val commitId: ReviewCommitId,
        val attempt: Int,
        /** Guarantee frozen when the transaction was created; diagnostics correlation only. */
        val guaranteeLevel: CommitGuaranteeLevel? = null
    ) : AnkiStudyEvent {
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

    /** The single rating mutation path. [retry] = explicit retry of RETRY_ALLOWED. */
    data class CommitRating(val epoch: Long, val request: CommitRatingRequest, val retry: Boolean = false) : AnkiStudyEffect

    /** Read-only reconciliation of an AMBIGUOUS commit. */
    data class ReconcileCommit(val epoch: Long, val commitId: ReviewCommitId) : AnkiStudyEffect

    /** Release the backend's session handle after the user ended the session. Not a mutation. */
    data class EndReview(val session: AnkiReviewSession) : AnkiStudyEffect
}
