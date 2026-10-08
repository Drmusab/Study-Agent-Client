package com.studyagent.client.core.study

import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Evaluation
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
    val projectionMismatch: CommitProjectionMismatch? = null,
    // ---- GATE 12 answer reveal, reference answer, evaluation & compare state ----
    val revealState: AnswerRevealState = AnswerRevealState.HIDDEN,
    val compareMode: AnswerCompareMode = request.defaultCompareMode,
    val evaluation: Evaluation? = null,
    val evaluationStatus: AnswerEvaluationStatus = AnswerEvaluationStatus.NOT_REQUESTED,
    val evaluationFailureReason: String? = null,
    val activeEvaluationRequestId: String? = null,
    val audioSequencePhase: AnswerAudioSequencePhase = AnswerAudioSequencePhase.IDLE,
    val renderFallbackReason: String? = null,
    val showRawReferenceAnswer: Boolean = false,
    /**
     * GATE 13 — the current turn's reviewer-action transaction (flag/bury/suspend), in exactly the
     * shape of [commit]: the durable [ReviewerActionStatus] as far as the reducer knows it, plus the
     * fixed request. It is a *separate* transaction family from [commit] by contract — different
     * ledger, different identity, different recovery semantics (INV-13-02/INV-13-05).
     */
    val reviewerAction: AnkiReviewerAction? = null,
    /** Presentation-only: the last request the policy refused before anything was recorded. */
    val reviewerActionRefusal: ReviewerActionRefusal? = null,
    /** True when [reviewerAction] was found by the startup recovery scan, not created live. */
    val restoredAction: Boolean = false
) {
    /** Canonical final user answer transcript for comparison (STEP 3). */
    val userAnswerText: String? get() = transcript
    /** Advisory AI suggestion only; never auto-selected or auto-committed (STEP 16, 17). */
    val suggestedRating: Rating? get() = evaluation?.suggestedRating
    /** User choice only; before durable COMMITTED it is not a scheduler fact. */
    val selectedRating: Rating? get() = commit?.selectedRating
    /** Non-null only after the durable transaction is COMMITTED. */
    val committedRating: Rating? get() = commit?.committedRating

    /** GATE 13 — the action being applied right now, if any (duplicate suppression, §15). */
    val reviewerActionInFlight: ReviewerActionKind? get() = reviewerAction?.inFlightKind
    /** GATE 13 — the action whose outcome is not proven, if any (fail-closed gate, §25). */
    val reviewerActionUnresolved: ReviewerActionKind? get() = reviewerAction?.unresolvedKind
    /** The durable action status as this projection knows it; `null` = no action record. */
    val reviewerActionStatus: ReviewerActionStatus? get() = reviewerAction?.status
    /** The §7 presentation projection of the durable action status. */
    val reviewerActionUi: ReviewerActionUiState
        get() = reviewerAction?.uiState(restoredAction) ?: ReviewerActionUiState.Idle
    /** §25 — why a rating is blocked by reviewer-action state, if it is. */
    val ratingBlockedByAction: ReviewerActionBlockReason?
        get() = ReviewerActionPolicy.ratingBlockReason(reviewerAction?.status)
    /** Whether the current turn already spent its review (a committed rating closed it). */
    val ratingResolved: Boolean get() = commit?.status == ReviewCommitStatus.COMMITTED

    /** Projects this interaction into the pure GATE 12 [AnswerReviewModel]. */
    fun toAnswerReviewModel(
        phase: SessionPhase = SessionPhase.WaitingForRating,
        cardTurn: CardTurn? = null
    ): AnswerReviewModel? = AnswerReviewModel.from(this, phase, cardTurn)
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

/**
 * The reducer's view of the current turn's **reviewer-action transaction** (GATE 13).
 *
 * [status] is the durable [ReviewerActionStatus] as far as the reducer knows it — the same
 * vocabulary, never a study-level synonym: `PREPARED` = the intent is durable and the mutation
 * boundary is un-entered; `SUBMITTING` = the boundary marker is durable, so the backend may already
 * have applied the action; `APPLIED` / `RETRY_ALLOWED` / `AMBIGUOUS` = the coordinator's final,
 * persisted classification.
 *
 * Presentation is derived from this into [ReviewerActionUiState]; the two are never stored apart
 * (§6/§7). [request] is fixed when the action is accepted and re-sent verbatim by a retry (same
 * [ReviewerActionId], same action, same card), which is what makes the retry the *same* logical
 * action (INV-13-11).
 */
data class AnkiReviewerAction(
    val request: ReviewerActionRequest,
    val status: ReviewerActionStatus,
    /** Attempts the coordinator has started (1 = first submission). */
    val attempt: Int = 0,
    val failureCategory: String? = null,
    /** A read-only reconciliation is in flight (`SUBMITTING`/`AMBIGUOUS` only). */
    val reconciling: Boolean = false,
    /** True only when reconciliation evidence, not the original answer, proved the outcome. */
    val verifiedByReconciliation: Boolean = false
) {
    val actionId: ReviewerActionId get() = request.actionId
    val action: ReviewerAction get() = request.action
    val cardRef: AnkiCardRef get() = request.cardRef
    val turnId: ReviewTurnId get() = request.turnId

    /** The action being applied right now (duplicate suppression, §15). */
    val inFlightKind: ReviewerActionKind?
        get() = if (status == ReviewerActionStatus.PREPARED || status == ReviewerActionStatus.SUBMITTING) {
            action.kind
        } else null

    /** The action whose outcome is not proven (§25 fail-closed gate). */
    val unresolvedKind: ReviewerActionKind?
        get() = if (status == ReviewerActionStatus.RETRY_ALLOWED || status == ReviewerActionStatus.AMBIGUOUS) {
            action.kind
        } else null

    /** The §7 presentation projection of this transaction. */
    fun uiState(recovered: Boolean = false): ReviewerActionUiState =
        ReviewerActionUiState.from(status, actionId, action, recovered)
}

/**
 * The executor's persisted classification of one reviewer action (or reconciliation), as delivered
 * to the reducer. It always reflects the durable ledger *after* the executor wrote it — [status] is
 * the durable status, never a second vocabulary (§9).
 */
sealed interface AnkiReviewerActionOutcome {
    /**
     * The durable [ReviewerActionStatus] this outcome reflects, or `null` for [NotRecorded] — which
     * is not a status at all, because no durable record exists.
     */
    val status: ReviewerActionStatus?

    /**
     * The backend's own state shows the action took effect and the success is durable.
     *
     * [flag] is the flag the backend reports after a confirmed `SetFlag` (used for the minimal turn
     * projection); [cardState] is the post-action card projection; [detail] is a stable token.
     */
    data class Applied(
        val source: String,
        val detail: String? = null,
        val cardState: ReviewerCardState? = null,
        val flag: AnkiFlag? = null
    ) : AnkiReviewerActionOutcome {
        override val status: ReviewerActionStatus get() = ReviewerActionStatus.APPLIED
    }

    /** Proven not applied; the same action identity may be retried (§13). */
    data class RetryAvailable(val category: String) : AnkiReviewerActionOutcome {
        override val status: ReviewerActionStatus get() = ReviewerActionStatus.RETRY_ALLOWED
    }

    /** May have been applied. Progression stays blocked; nothing is replayed (§13). */
    data class Ambiguous(val category: String) : AnkiReviewerActionOutcome {
        override val status: ReviewerActionStatus get() = ReviewerActionStatus.AMBIGUOUS
    }

    /**
     * The answer could not be made durable. **Not** a backend failure and **not** a retry grant:
     * the durable status stays whatever the ledger holds (normally `PREPARED` or `SUBMITTING`).
     */
    data class PersistenceFailure(val category: String) : AnkiReviewerActionOutcome {
        override val status: ReviewerActionStatus get() = ReviewerActionStatus.PREPARED
    }

    /**
     * The action was refused **before** any backend mutation and nothing durable exists. The
     * coordinator never dispatches without a durable `SUBMITTING` write (INV-13-08), so the card is
     * provably untouched; the presentation returns to `Idle` with a problem, and the user may simply
     * try again.
     */
    data class NotRecorded(val category: String) : AnkiReviewerActionOutcome {
        override val status: ReviewerActionStatus? get() = null
    }

    companion object {
        /** The answer came from this attempt's own backend call. */
        const val SOURCE_BACKEND_CONFIRMED = "backend_confirmed"
        /** The answer came from the durable ledger, with no backend call at all (§12). */
        const val SOURCE_LEDGER_REPLAY = "ledger_replay"
        /** Authoritative read-only evidence, not the lost original answer (§27). */
        const val SOURCE_RECONCILED = "reconciled"
    }
}

/** Caller supplies a new logical session ID; a display deck name is never an identity. */
data class AnkiStudyRequest(
    val studySessionId: String,
    val preference: AnkiBackendMode,
    val deck: AnkiDeckRef,
    val speakQuestion: Boolean = true,
    val evaluateAnswers: Boolean = false,
    val speakFeedback: Boolean = true,
    val speakAnswer: Boolean = false,
    val defaultCompareMode: AnswerCompareMode = AnswerCompareMode.ORIGINAL,
    /**
     * GATE 13 — the reviewer-action capability set **frozen at session start**, exactly like the
     * commit semantics frozen from the negotiated agent capabilities (GATE 11 §XI.5). A later
     * backend or preference change cannot re-shape a decision about this session's turns
     * (INV-13-15); the default offers nothing, so a caller that has not consulted the backend's
     * capabilities cannot accidentally enable a mutation it never audited.
     */
    val reviewerActions: ReviewerActionCapabilities = ReviewerActionCapabilities.NONE
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
 * GATE 11E PART I §6 — the **one** production next-card barrier.
 *
 * The rule itself lives in the transaction domain as [nextCardAllowed]
 * (`nextCardAllowed(status) ⟺ status == COMMITTED`); this is its only call form for a delivered
 * outcome. A new site that wants to advance the session to the next card must go through one of
 * the two functions — never through another `status == COMMITTED` comparison (INV-11E-18), because
 * a second copy of the rule is a second rule, and two rules drift.
 */
fun AnkiCommitOutcome.allowsNextCard(): Boolean = nextCardAllowed(status)

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

    // ---- GATE 13 reviewer action events (flag / bury / suspend) ----

    /**
     * User intent (menu tap, voice command, remote control) to run one reviewer action on the
     * current turn. Correlation fields are optional because the caller is the *user*: the reducer
     * resolves them against the authoritative turn and rejects anything stale, so a late tap can
     * never act on a card that is no longer presented.
     *
     * The durable steps that follow (`PREPARED`, `SUBMITTING`, the final status) are owned by the
     * [com.studyagent.client.core.anki.ReviewerActionCoordinator]; the reducer's projection mirrors
     * them and is corrected by [ReviewerActionResolved]. There is deliberately no event that *is*
     * the boundary — a projection is not transaction truth (§6/§7).
     */
    data class ReviewerActionRequested(
        val action: ReviewerAction,
        val epoch: Long? = null,
        val turnId: ReviewTurnId? = null,
        val cardId: String? = null
    ) : AnkiStudyEvent

    /** Final, persisted classification of one action attempt (flag / bury / suspend). */
    data class ReviewerActionResolved(
        val epoch: Long,
        val actionId: ReviewerActionId,
        val outcome: AnkiReviewerActionOutcome
    ) : AnkiStudyEvent

    /** User intent: retry an action that is proven not applied — same action id, same action. */
    data class RetryReviewerAction(val epoch: Long, val actionId: ReviewerActionId) : AnkiStudyEvent

    /** User intent: check an unresolved action against read-only backend evidence (§27). */
    data class RecoverReviewerAction(val epoch: Long, val actionId: ReviewerActionId) : AnkiStudyEvent

    /** Persisted outcome of a read-only reconciliation (§27). */
    data class ReviewerActionReconciled(
        val epoch: Long,
        val actionId: ReviewerActionId,
        val outcome: AnkiReviewerActionOutcome
    ) : AnkiStudyEvent

    /**
     * The startup recovery scan found an unfinished action for this backend/collection/session
     * (§26/§30): no `beginReview` and no `nextCard` query has happened, and none may happen until
     * the record is reconciled or the user ends the session.
     */
    data class ReviewerActionRecoveryBlocked(
        val epoch: Long,
        val record: ReviewerActionRecord
    ) : AnkiStudyEvent

    // ---- GATE 12 answer evaluation & review events ----

    data class AnswerEvaluationCompleted(
        val epoch: Long,
        val sessionId: String,
        val turnId: ReviewTurnId,
        val cardRef: AnkiCardRef,
        val requestId: String,
        val evaluation: Evaluation,
        val speakFeedback: Boolean = true
    ) : AnkiStudyEvent

    data class AnswerEvaluationFailed(
        val epoch: Long,
        val sessionId: String,
        val turnId: ReviewTurnId,
        val cardRef: AnkiCardRef,
        val requestId: String,
        val reason: String
    ) : AnkiStudyEvent

    companion object {
        fun RevealAnswerRequested(
            turnId: ReviewTurnId? = null,
            epoch: Long? = null,
            cardId: String? = null
        ): StudyEvent.RevealAnswerRequested = StudyEvent.RevealAnswerRequested(turnId, epoch, cardId)

        fun SelectCompareMode(
            turnId: ReviewTurnId? = null,
            mode: AnswerCompareMode,
            epoch: Long? = null
        ): StudyEvent.SelectAnswerCompareMode = StudyEvent.SelectAnswerCompareMode(turnId, mode, epoch)

        fun RepeatAnswerRequested(
            turnId: ReviewTurnId? = null,
            cardId: String? = null,
            epoch: Long? = null
        ): StudyEvent.RepeatAnswerRequested = StudyEvent.RepeatAnswerRequested(turnId, cardId, epoch)

        fun RepeatFeedbackRequested(
            turnId: ReviewTurnId? = null,
            cardId: String? = null,
            epoch: Long? = null
        ): StudyEvent.RepeatFeedbackRequested = StudyEvent.RepeatFeedbackRequested(turnId, cardId, epoch)

        fun AnswerRenderFallbackTriggered(
            turnId: ReviewTurnId,
            reason: String,
            epoch: Long? = null,
            sessionId: String? = null,
            cardRef: AnkiCardRef? = null,
            generation: Long? = null
        ): StudyEvent.AnswerRenderFallbackTriggered =
            StudyEvent.AnswerRenderFallbackTriggered(turnId, reason, epoch, sessionId, cardRef, generation)
    }
}

/**
 * Anki effects. Read effects (Begin/Next/Hydrate) run in one cancellable read job. The GATE 11
 * write-side effects run in their own job that session end, stop or UI recreation never cancels:
 * an in-flight commit finishes, is persisted, and its late result is correlated (and rejected as
 * stale if the turn is gone).
 *
 * GATE 13 adds [PerformReviewerAction], [RetryReviewerAction] and [RecoverReviewerAction] to the *same* write lane: it is a mutation, so it must never
 * be cancelled by a read refresh, and it must be ordered behind any rating mutation already
 * emitted — while the reducer's policy keeps it from being emitted at all while a rating is
 * unresolved.
 */
sealed interface AnkiStudyEffect : StudyEffect {
    data class Begin(val epoch: Long, val request: AnkiStudyRequest, val startedAtMs: Long) : AnkiStudyEffect
    data class Next(val epoch: Long, val session: AnkiReviewSession) : AnkiStudyEffect
    data class Hydrate(val epoch: Long, val turn: AnkiReviewTurn) : AnkiStudyEffect
    data class EvaluateAnswer(
        val epoch: Long,
        val request: AnkiAnswerEvaluationRequest,
        val speakFeedback: Boolean = true
    ) : AnkiStudyEffect
    data object CancelReads : AnkiStudyEffect

    /** The single rating mutation path. [retry] = explicit retry of RETRY_ALLOWED. */
    data class CommitRating(val epoch: Long, val request: CommitRatingRequest, val retry: Boolean = false) : AnkiStudyEffect

    /**
     * GATE 13 §16/§17 — the single reviewer-action path. The executor hands it to the
     * [com.studyagent.client.core.anki.ReviewerActionCoordinator], which owns durable ordering,
     * duplicate suppression and recovery; a result that cannot be attributed comes back as
     * [AnkiStudyEvent.ReviewerActionResolved] with an ambiguous outcome rather than being re-sent.
     */
    data class PerformReviewerAction(
        val epoch: Long,
        val sessionId: String,
        val turnId: ReviewTurnId,
        val cardRef: AnkiCardRef,
        val action: ReviewerAction,
        val collectionRef: AnkiCollectionIdentity? = null,
        /** The semantics frozen at session start (§28); null = ask the backend. */
        val semantics: ReviewerActionSemantics? = null
    ) : AnkiStudyEffect

    /** §13 — retry the *same* logical action (same [ReviewerActionId]) after non-application. */
    data class RetryReviewerAction(
        val epoch: Long,
        val actionId: ReviewerActionId
    ) : AnkiStudyEffect

    /** §27 — read-only reconciliation of an unresolved action. Never a mutation. */
    data class RecoverReviewerAction(
        val epoch: Long,
        val actionId: ReviewerActionId
    ) : AnkiStudyEffect

    /** Read-only reconciliation of an AMBIGUOUS commit. */
    data class ReconcileCommit(val epoch: Long, val commitId: ReviewCommitId) : AnkiStudyEffect

    /** Release the backend's session handle after the user ended the session. Not a mutation. */
    data class EndReview(val session: AnkiReviewSession) : AnkiStudyEffect
}
