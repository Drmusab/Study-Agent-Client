package com.studyagent.client.core.anki

/**
 * GATE 13 STEP 24-29 — when a reviewer action may be dispatched, as a pure function.
 *
 * The policy is a *function of state*, not a set of UI checks: the reducer calls it, the UI state
 * projection calls it, and the tests call it directly. One rule, one implementation
 * (the same discipline GATE 11E applied to the next-card barrier).
 *
 * ## Rating-transaction interaction (STEP 24-29)
 *
 * | `ReviewCommitStatus` | Flag | Bury / Suspend | Why |
 * |---|---|---|---|
 * | none | allow | allow | no rating transaction exists; nothing to protect (STEP 24) |
 * | `PREPARED` | allow | **block** | commit intent exists; bury/suspend must not silently *replace* it (STEP 27) |
 * | `SUBMITTING` | **block** | **block** | a scheduler mutation may already be crossing the provider (STEP 25/29) |
 * | `RETRY_ALLOWED` | allow | **block** | proven not applied, but the transaction is unresolved; the turn must be resolved or explicitly abandoned first (STEP 26) |
 * | `AMBIGUOUS` | **block** | **block** | the write may land at any time; only reconciliation or session end may move (STEP 25/40) |
 * | `COMMITTED` | **block** | **block** | the turn is resolved; reviewer actions belong to a *future* turn, never to a finished one (STEP 28) |
 *
 * Flag is treated differently on purpose: it is a metadata mutation, so it is allowed whenever no
 * scheduler mutation is in flight — which is exactly [ReviewCommitStatus.PREPARED] and
 * [ReviewCommitStatus.RETRY_ALLOWED], where the backend has not been (and is not being) mutated.
 * Bury/suspend change review *availability*, so they need a fully resolved transaction.
 *
 * ## Local action state (STEP 18/31/40)
 *
 * - an action already in flight blocks every other action (duplicate input cannot produce a second
 *   mutation, INV-13-19);
 * - an unresolved outcome ([ReviewerActionResult.OutcomeUnknown]) blocks everything until the
 *   session is restarted or the outcome is resolved by an explicit policy: a lost response must
 *   never be turned into a blind replay (INV-13-13).
 */
object ReviewerActionPolicy {

    /**
     * Decides whether [action] may be dispatched now.
     *
     * @param capabilities the action set **frozen for this session** (INV-13-15: a later global
     *   preference change must not re-shape a decision about this turn).
     * @param commitStatus the current turn's rating-transaction status, or `null` when no rating
     *   transaction exists yet.
     * @param actionInFlight the action currently being applied, if any.
     * @param actionOutcomeUnresolved the action whose outcome is still unknown, if any.
     * @param turnAvailable false when there is no active review turn to act on.
     * @param turnResolved true when the current turn's review is already resolved (a committed
     *   rating): the turn is finished and its action menu must not mutate it (STEP 28).
     */
    fun decide(
        action: ReviewerAction,
        capabilities: ReviewerActionCapabilities,
        commitStatus: ReviewCommitStatus?,
        actionInFlight: ReviewerActionKind? = null,
        actionOutcomeUnresolved: ReviewerActionKind? = null,
        turnAvailable: Boolean = true,
        turnResolved: Boolean = false
    ): ReviewerActionDecision {
        if (!capabilities.supports(action)) {
            return ReviewerActionDecision.Blocked(
                ReviewerActionBlockReason.ACTION_UNSUPPORTED, action.key
            )
        }
        if (actionInFlight != null) {
            return ReviewerActionDecision.Blocked(
                ReviewerActionBlockReason.ACTION_IN_FLIGHT, actionInFlight.name.lowercase()
            )
        }
        if (actionOutcomeUnresolved != null) {
            return ReviewerActionDecision.Blocked(
                ReviewerActionBlockReason.ACTION_OUTCOME_UNRESOLVED,
                actionOutcomeUnresolved.name.lowercase()
            )
        }
        if (!turnAvailable) {
            return ReviewerActionDecision.Blocked(ReviewerActionBlockReason.NO_ACTIVE_TURN, "no_turn")
        }
        if (turnResolved) {
            return ReviewerActionDecision.Blocked(ReviewerActionBlockReason.TURN_RESOLVED, "committed")
        }
        return when (commitStatus) {
            null -> ReviewerActionDecision.Allowed

            ReviewCommitStatus.PREPARED -> if (action.invalidatesTurn) {
                ReviewerActionDecision.Blocked(ReviewerActionBlockReason.COMMIT_PENDING, "prepared")
            } else {
                ReviewerActionDecision.Allowed
            }

            ReviewCommitStatus.SUBMITTING -> ReviewerActionDecision.Blocked(
                ReviewerActionBlockReason.COMMIT_MUTATION_IN_FLIGHT, "submitting"
            )

            ReviewCommitStatus.RETRY_ALLOWED -> if (action.invalidatesTurn) {
                ReviewerActionDecision.Blocked(ReviewerActionBlockReason.COMMIT_RETRY_PENDING, "retry_allowed")
            } else {
                ReviewerActionDecision.Allowed
            }

            ReviewCommitStatus.AMBIGUOUS -> ReviewerActionDecision.Blocked(
                ReviewerActionBlockReason.COMMIT_UNRESOLVED, "ambiguous"
            )

            ReviewCommitStatus.COMMITTED -> ReviewerActionDecision.Blocked(
                ReviewerActionBlockReason.TURN_RESOLVED, "committed"
            )
        }
    }

    /**
     * The reverse direction: may a rating be selected while reviewer-action state exists?
     *
     * An **in-flight** action mutates the same card whose rating would enter the GATE 11 pipeline,
     * and an **unresolved** turn-invalidating action means the scheduler may already have moved the
     * card out of the queue. Both are blocked — fail closed, exactly like an unresolved rating
     * commit blocks the next card (INV-13-14 in the other direction, GATE 13 §40).
     *
     * A *rejected* action leaves nothing to protect: the card state provably did not change, so
     * rating stays available (VERIFICATION 6-8).
     */
    fun ratingBlockReason(
        actionInFlight: ReviewerActionKind? = null,
        actionOutcomeUnresolved: ReviewerActionKind? = null
    ): ReviewerActionBlockReason? = when {
        actionInFlight != null -> ReviewerActionBlockReason.ACTION_IN_FLIGHT
        actionOutcomeUnresolved != null -> ReviewerActionBlockReason.ACTION_OUTCOME_UNRESOLVED
        else -> null
    }
}

/** The outcome of [ReviewerActionPolicy.decide]. */
sealed interface ReviewerActionDecision {

    /** The action may be dispatched; the caller still owns correlation and duplicate suppression. */
    data object Allowed : ReviewerActionDecision

    /**
     * The action must not be dispatched. [detail] is a small, stable, content-free token.
     * [reason] is part of the domain vocabulary, so the UI can render its own honest copy for it
     * instead of parsing a string.
     */
    data class Blocked(
        val reason: ReviewerActionBlockReason,
        val detail: String
    ) : ReviewerActionDecision
}

/**
 * Why a reviewer action is not available right now. Every value is a *state*, never an error text:
 * the UI maps it to copy, the diagnostics log it as a token, and the tests assert on it.
 */
enum class ReviewerActionBlockReason {
    /** The frozen per-session capability set says this backend cannot do it (INV-13-17). */
    ACTION_UNSUPPORTED,

    /** No active review turn exists to act on. */
    NO_ACTIVE_TURN,

    /** The current turn's review is already resolved (a committed rating) — STEP 28. */
    TURN_RESOLVED,

    /** A rating transaction exists but has not been dispatched (`PREPARED`) — STEP 27. */
    COMMIT_PENDING,

    /** The rating mutation is in flight (`SUBMITTING`) — STEP 25. */
    COMMIT_MUTATION_IN_FLIGHT,

    /** The rating outcome is unknown (`AMBIGUOUS`) — STEP 25/40. */
    COMMIT_UNRESOLVED,

    /** A rating transaction exists and is provably not applied, but unresolved (`RETRY_ALLOWED`) — STEP 26. */
    COMMIT_RETRY_PENDING,

    /** Another reviewer action is being applied right now (duplicate input) — INV-13-19. */
    ACTION_IN_FLIGHT,

    /** A previous reviewer action's outcome is unknown; nothing may be replayed — INV-13-13. */
    ACTION_OUTCOME_UNRESOLVED
}
