package com.studyagent.client.core.anki

/**
 * GATE 11B §10 — what the coordinator **returns**. It is a conclusion of local transaction policy,
 * never a restatement of a backend fact: the backend reports [BackendCommitResult], the coordinator
 * turns it into one of these.
 *
 * `Committed` / `RetryAllowed` / `Ambiguous` carry the durable [ReviewCommitRecord], so a caller
 * can never act on an outcome that was not durably recorded.
 */
sealed interface ReviewCommitOutcome {

    /** The scheduler mutation is durably confirmed. Next-card progression becomes legal. */
    data class Committed(val record: ReviewCommitRecord) : ReviewCommitOutcome

    /** Authoritative evidence that no mutation happened; the same logical commit may be retried. */
    data class RetryAllowed(val record: ReviewCommitRecord) : ReviewCommitOutcome

    /** The mutation may or may not have happened. No retry and no next card. */
    data class Ambiguous(val record: ReviewCommitRecord) : ReviewCommitOutcome

    /** Refused before any mutation, or the durable record could not be written/claimed. */
    data class Conflict(val error: AnkiError) : ReviewCommitOutcome

    val commitId: ReviewCommitId?
        get() = when (this) {
            is Committed -> record.commitId
            is RetryAllowed -> record.commitId
            is Ambiguous -> record.commitId
            is Conflict -> null
        }

    val statusOrNull: ReviewCommitStatus?
        get() = when (this) {
            is Committed -> record.status
            is RetryAllowed -> record.status
            is Ambiguous -> record.status
            is Conflict -> null
        }
}

/**
 * GATE 11B §36 — the single entry point for rating-commit transactions.
 *
 * The coordinator owns the transaction, not the study interaction: [StudySessionMachine] decides
 * *that* a rating was selected, the coordinator decides *whether and how* it may reach the
 * scheduler exactly once. Nothing outside this interface may re-send a rating.
 */
interface ReviewCommitCoordinator {

    /**
     * Submit one rating for a turn that has no logical commit yet.
     *
     * Ordered exactly as GATE 11B §29: create `PREPARED` → durably persist → durable attempt claim
     * → enter the mutation boundary (`SUBMITTING`, durable) → invoke the backend → persist the
     * classified answer → persist the terminal status.
     *
     * Existing-record behaviour (GATE 11B §37), always with the **same** [ReviewCommitId]:
     *
     * | Durable status | `commit()` |
     * |---|---|
     * | `PREPARED` | resumes the existing transaction (it is provably un-entered); no new id |
     * | `COMMITTED` | returns the prior [ReviewCommitOutcome.Committed]; the backend is never called again |
     * | `SUBMITTING` | [ReviewCommitOutcome.Conflict] — already in progress |
     * | `RETRY_ALLOWED` | [ReviewCommitOutcome.Conflict] — use [retry] |
     * | `AMBIGUOUS` | [ReviewCommitOutcome.Conflict] — use [recover] |
     */
    suspend fun commit(request: CommitRatingRequest): ReviewCommitOutcome

    /**
     * Submit an existing transaction again after it was proven not applied (GATE 11B §38).
     *
     * Only legal from [ReviewCommitStatus.RETRY_ALLOWED]. The sequence is always
     * `RETRY_ALLOWED → BeginRetry → PREPARED → durable claim → EnterMutationBoundary → SUBMITTING`
     * (INV-11B-09) — never a direct `RETRY_ALLOWED → SUBMITTING`.
     */
    suspend fun retry(commitId: ReviewCommitId): ReviewCommitOutcome

    /**
     * Classify (and, where read-only evidence can decide it, resolve) a transaction after an
     * interruption (GATE 11B §39). Used for [ReviewCommitStatus.AMBIGUOUS] and for a
     * [ReviewCommitStatus.SUBMITTING] record found after process recovery.
     *
     * It never performs a blind mutation replay: the only backend call it may make is the
     * read-only [AnkiBackend.reconcileCommit] probe.
     */
    suspend fun recover(commitId: ReviewCommitId): ReviewCommitRecoveryResult
}
