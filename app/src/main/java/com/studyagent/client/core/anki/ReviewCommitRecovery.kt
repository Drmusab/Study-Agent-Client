package com.studyagent.client.core.anki

/**
 * GATE 11D §2 — the canonical **recovery events**.
 *
 * Recovery may produce only these transaction-level events; UI/navigation events are separate and
 * never named here. Every recovery status decision in the codebase is expressed as one
 * `(current, event)` pair evaluated by [recoveryTransition] — startup code, the coordinator, the
 * reconciler, the ledger and the UI must not spread the table.
 */
enum class ReviewCommitRecoveryEvent {

    /**
     * Spec: `RecoveryDetected`. An unfinished transaction was found (for example at process
     * restore). Detection alone never classifies the outcome, so the durable status is unchanged.
     */
    RECOVERY_DETECTED,

    /**
     * Spec: `RetryRequested`. An explicit retry of the same logical transaction was requested.
     * Legal from [ReviewCommitStatus.PREPARED] (stays PREPARED, same identity) and from
     * [ReviewCommitStatus.RETRY_ALLOWED] (returns to PREPARED, §12/§13). Forbidden from
     * SUBMITTING, AMBIGUOUS and COMMITTED (§24) — pressing a button never creates evidence.
     */
    RETRY_REQUESTED,

    /**
     * Spec: `ReconciliationStarted`. A read-only reconciliation began. Legal only from
     * [ReviewCommitStatus.SUBMITTING] (§7) and [ReviewCommitStatus.AMBIGUOUS] (§15); the durable
     * status remains the current one while reconciliation runs — reconciliation never introduces
     * a durable status of its own (§22).
     */
    RECONCILIATION_STARTED,

    /**
     * Spec: `ReconciliationConfirmedCommitted`. Authoritative reconciliation proved the scheduler
     * mutation happened. Legal from SUBMITTING and AMBIGUOUS; the only evidence-based path to
     * [ReviewCommitStatus.COMMITTED] for an unfinished transaction (§8/§16).
     */
    RECONCILIATION_CONFIRMED_COMMITTED,

    /**
     * Spec: `ReconciliationConfirmedNotCommitted`. Authoritative reconciliation proved the
     * scheduler mutation did **not** happen. Legal from SUBMITTING and AMBIGUOUS; the only
     * evidence-based path to [ReviewCommitStatus.RETRY_ALLOWED] (§9/§17).
     */
    RECONCILIATION_CONFIRMED_NOT_COMMITTED,

    /**
     * Spec: `ReconciliationUnresolved`. Reconciliation could not determine the outcome
     * (unsupported, unavailable, timeout, insufficient evidence). SUBMITTING becomes explicitly
     * uncertain ([ReviewCommitStatus.AMBIGUOUS], §10/§27/§28); AMBIGUOUS remains AMBIGUOUS —
     * which is not an error (§18).
     */
    RECONCILIATION_UNRESOLVED,

    /**
     * Spec: `IntegrityViolationDetected`. The durable record contradicts itself or its backend
     * (§32). Truth is never rewritten to escape a contradiction: the status stays exactly what it
     * was and recovery surfaces [ReviewCommitRecoveryAction.IntegrityFailure] instead.
     */
    INTEGRITY_VIOLATION_DETECTED
}

/**
 * GATE 11D §36/§37 — the **one pure recovery transition function**.
 *
 * ```kotlin
 * recoveryTransition(current, event) -> next status, or null when the pair is invalid
 * ```
 *
 * The table is closed: every `(status, event)` pair is one of the rows below, and every pair the
 * canonical table does not specify returns `null` and MUST be rejected before any durable write.
 * Status mutation for recovery is never written anywhere else — the ledger's recovery commands
 * ([ReviewCommitTransition.BeginRetry], [ReviewCommitTransition.ReconciliationConfirmedCommitted],
 * [ReviewCommitTransition.ReconciliationConfirmedNotCommitted],
 * [ReviewCommitTransition.ReconciliationInconclusive]) validate themselves against this function,
 * so startup code, ViewModels, repositories and UI cannot grow a second table.
 *
 * Canonical closed transition table (GATE 11D §37; `-` = invalid, reject):
 *
 * | Current       | RecoveryDetected | RetryRequested | ReconciliationStarted | ConfirmedCommitted | ConfirmedNotCommitted | Unresolved  | IntegrityViolation |
 * |---------------|------------------|----------------|-----------------------|--------------------|-----------------------|-------------|--------------------|
 * | `PREPARED`    | `PREPARED`       | `PREPARED`     | -                     | -                  | -                     | -           | `PREPARED`         |
 * | `SUBMITTING`  | `SUBMITTING`     | -              | `SUBMITTING`          | `COMMITTED`        | `RETRY_ALLOWED`       | `AMBIGUOUS` | `SUBMITTING`       |
 * | `RETRY_ALLOWED`| `RETRY_ALLOWED` | `PREPARED`     | -                     | -                  | -                     | -           | `RETRY_ALLOWED`    |
 * | `AMBIGUOUS`   | `AMBIGUOUS`      | -              | `AMBIGUOUS`           | `COMMITTED`        | `RETRY_ALLOWED`       | `AMBIGUOUS` | `AMBIGUOUS`        |
 * | `COMMITTED`   | `COMMITTED`      | -              | `COMMITTED`           | `COMMITTED`        | -                     | `COMMITTED` | `COMMITTED`        |
 *
 * `COMMITTED` is terminal (§20/§21): no event changes the status. The two events that would
 * rewrite truth (`RetryRequested`, `ReconciliationConfirmedNotCommitted`) are rejected outright;
 * the remaining events re-state the durable truth without mutating it.
 *
 * Restart is deliberately **not** an event of this table: restart alone never rewrites
 * transaction truth (§26). What runs after restart is recovery classification — which produces
 * exactly these events (for example RECONCILIATION_UNRESOLVED normalizing a recovered SUBMITTING
 * into AMBIGUOUS, §28).
 *
 * @return the next durable status, or `null` when the pair is invalid and must be rejected.
 */
fun recoveryTransition(
    current: ReviewCommitStatus,
    event: ReviewCommitRecoveryEvent
): ReviewCommitStatus? = when (event) {
    ReviewCommitRecoveryEvent.RECOVERY_DETECTED -> current

    ReviewCommitRecoveryEvent.RETRY_REQUESTED -> when (current) {
        // §24: PREPARED stays the same transaction (same commit id), RETRY_ALLOWED re-enters
        // PREPARED through the locked retry sequence. Everything else is forbidden (§11/§19/§21).
        ReviewCommitStatus.PREPARED, ReviewCommitStatus.RETRY_ALLOWED -> ReviewCommitStatus.PREPARED
        else -> null
    }

    ReviewCommitRecoveryEvent.RECONCILIATION_STARTED -> when (current) {
        // §7/§15: the durable status remains while reconciliation executes. Reconciliation is
        // meaningless for a provably un-entered PREPARED or a proven RETRY_ALLOWED — those pairs
        // are invalid. On a terminal COMMITTED it is a no-op restatement of truth.
        ReviewCommitStatus.SUBMITTING, ReviewCommitStatus.AMBIGUOUS, ReviewCommitStatus.COMMITTED -> current
        else -> null
    }

    ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_COMMITTED -> when (current) {
        // §8/§16: authoritative proof of success, from either unfinished status.
        ReviewCommitStatus.SUBMITTING, ReviewCommitStatus.AMBIGUOUS -> ReviewCommitStatus.COMMITTED
        ReviewCommitStatus.COMMITTED -> ReviewCommitStatus.COMMITTED // terminal: re-stated, never replayed
        else -> null
    }

    ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_NOT_COMMITTED -> when (current) {
        // §9/§17: authoritative proof of non-application, from either unfinished status.
        ReviewCommitStatus.SUBMITTING, ReviewCommitStatus.AMBIGUOUS -> ReviewCommitStatus.RETRY_ALLOWED
        else -> null // a COMMITTED record may never be downgraded (§21)
    }

    ReviewCommitRecoveryEvent.RECONCILIATION_UNRESOLVED -> when (current) {
        // §10/§18/§27/§28: unresolved is a first-class answer — SUBMITTING is normalized into
        // explicit uncertainty; AMBIGUOUS remains AMBIGUOUS, which is not an error.
        ReviewCommitStatus.SUBMITTING, ReviewCommitStatus.AMBIGUOUS -> ReviewCommitStatus.AMBIGUOUS
        ReviewCommitStatus.COMMITTED -> ReviewCommitStatus.COMMITTED
        else -> null
    }

    ReviewCommitRecoveryEvent.INTEGRITY_VIOLATION_DETECTED -> current
}

/**
 * GATE 11D §25 — the exact next-card rule:
 *
 * ```text
 * nextCardAllowed(status) = status == COMMITTED
 * ```
 *
 * No other durable status may advance the session to the next card.
 */
fun nextCardAllowed(status: ReviewCommitStatus): Boolean = status == ReviewCommitStatus.COMMITTED

/**
 * GATE 11D §36 — the recovery event a durable [ReviewCommitTransition] expresses, or `null` for a
 * command that belongs to the normal commit pipeline (GATE 11D §5) or carries metadata only.
 *
 * The ledger's transition engine uses this to route every recovery-originated command through
 * [recoveryTransition], so the closed table above is the single authority for recovery status
 * changes:
 *
 * | Command | Recovery event |
 * |---|---|
 * | [ReviewCommitTransition.BeginRetry] | `RetryRequested` |
 * | [ReviewCommitTransition.ReconciliationConfirmedCommitted] | `ReconciliationConfirmedCommitted` |
 * | [ReviewCommitTransition.ReconciliationConfirmedNotCommitted] | `ReconciliationConfirmedNotCommitted` |
 * | [ReviewCommitTransition.ReconciliationInconclusive] | `ReconciliationUnresolved` |
 */
fun ReviewCommitTransition.recoveryEventOrNull(): ReviewCommitRecoveryEvent? = when (this) {
    is ReviewCommitTransition.BeginRetry -> ReviewCommitRecoveryEvent.RETRY_REQUESTED
    is ReviewCommitTransition.ReconciliationConfirmedCommitted ->
        ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_COMMITTED
    is ReviewCommitTransition.ReconciliationConfirmedNotCommitted ->
        ReviewCommitRecoveryEvent.RECONCILIATION_CONFIRMED_NOT_COMMITTED
    is ReviewCommitTransition.ReconciliationInconclusive -> ReviewCommitRecoveryEvent.RECONCILIATION_UNRESOLVED
    else -> null
}
