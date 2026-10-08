package com.studyagent.client.core.anki

/**
 * GATE 13 §15/§23/§25 — when a reviewer action may be dispatched, as a pure function of **durable
 * state**.
 *
 * The policy is a function of state, not a set of UI checks: the reducer calls it, the UI
 * projection derives from its result, and the tests call it directly. One rule, one implementation
 * (the same discipline GATE 11E applied to the next-card barrier).
 *
 * ## Rating-transaction interaction (§23, normative)
 *
 * | `ReviewCommitStatus` | Flag | Bury | Suspend | Why |
 * |---|---|---|---|---|
 * | no commit record | allow | allow | allow | no rating transaction exists; nothing to protect |
 * | `PREPARED` | block | block | block | commit intent exists; the turn's mutation slot is claimed |
 * | `SUBMITTING` | block | block | block | a scheduler mutation may already be crossing the provider |
 * | `RETRY_ALLOWED` | block | block | block | unresolved transaction; resolve or abandon it first |
 * | `AMBIGUOUS` | block | block | block | the write may land at any time; only reconciliation or session end may move |
 * | `COMMITTED` | block | block | block | the turn is already resolved; actions belong to a future turn |
 *
 * §23/§24 are explicit that **Flag is blocked too**: allowing it concurrently would introduce
 * multiple mutable backend operations on the same card in the same review turn with different
 * recovery semantics, for little product benefit. Once a rating transaction exists for the active
 * turn, no reviewer action mutation may begin. A future gate may relax this with proof.
 *
 * ## Reviewer-action state (§15)
 *
 * - an action already `PREPARED`/`SUBMITTING` for this turn blocks every other action
 *   (one active action per turn; a duplicate cannot produce a second mutation, INV-13-19);
 * - `RETRY_ALLOWED` blocks a *new* action and points at the retry of the same identity
 *   (INV-13-11);
 * - `AMBIGUOUS` blocks everything until reconciliation proves something (INV-13-10);
 * - `APPLIED` does not block: §12 says a *new* user action is a new [ReviewerActionId], and the
 *   coordinator answers a repeated identical action from the ledger instead of the backend.
 */
object ReviewerActionPolicy {

    /**
     * @param commitStatus the turn's rating transaction, as the durable ledger knows it (`null` =
     *   no commit record for this turn).
     * @param activeActionStatus the turn's active (non-`APPLIED`) reviewer action, if any.
     */
    fun decide(
        action: ReviewerAction,
        capabilities: ReviewerActionCapabilities,
        turnPresented: Boolean,
        commitStatus: ReviewCommitStatus?,
        activeActionStatus: ReviewerActionStatus?,
        turnResolved: Boolean
    ): ReviewerActionDecision = when {
        !turnPresented -> ReviewerActionDecision.Blocked(
            ReviewerActionBlockReason.NO_ACTIVE_TURN, "no_available_turn")
        !capabilities.supports(action) -> ReviewerActionDecision.Blocked(
            ReviewerActionBlockReason.ACTION_UNSUPPORTED, action.key)
        turnResolved || commitStatus == ReviewCommitStatus.COMMITTED ->
            ReviewerActionDecision.Blocked(ReviewerActionBlockReason.TURN_RESOLVED, "committed")
        commitStatus != null -> ReviewerActionDecision.Blocked(
            commitBlockReason(commitStatus), "commit_${commitStatus.name.lowercase()}")
        activeActionStatus != null -> ReviewerActionDecision.Blocked(
            actionBlockReason(activeActionStatus), "action_${activeActionStatus.name.lowercase()}")
        else -> ReviewerActionDecision.Allowed
    }

    private fun commitBlockReason(status: ReviewCommitStatus): ReviewerActionBlockReason = when (status) {
        ReviewCommitStatus.PREPARED -> ReviewerActionBlockReason.COMMIT_PENDING
        ReviewCommitStatus.SUBMITTING -> ReviewerActionBlockReason.COMMIT_MUTATION_IN_FLIGHT
        ReviewCommitStatus.RETRY_ALLOWED -> ReviewerActionBlockReason.COMMIT_RETRY_PENDING
        ReviewCommitStatus.AMBIGUOUS -> ReviewerActionBlockReason.COMMIT_UNRESOLVED
        ReviewCommitStatus.COMMITTED -> ReviewerActionBlockReason.TURN_RESOLVED
    }

    private fun actionBlockReason(status: ReviewerActionStatus): ReviewerActionBlockReason = when (status) {
        ReviewerActionStatus.PREPARED, ReviewerActionStatus.SUBMITTING ->
            ReviewerActionBlockReason.ACTION_IN_FLIGHT
        ReviewerActionStatus.RETRY_ALLOWED -> ReviewerActionBlockReason.ACTION_RETRY_AVAILABLE
        ReviewerActionStatus.AMBIGUOUS -> ReviewerActionBlockReason.ACTION_OUTCOME_UNRESOLVED
        // An APPLIED record never reaches here (it is not "active"), but a total function keeps the
        // table honest if a caller passes one.
        ReviewerActionStatus.APPLIED -> ReviewerActionBlockReason.ACTION_ALREADY_APPLIED
    }

    /**
     * The reverse direction (§25): may a rating be selected while reviewer-action state exists?
     *
     * Any **unresolved** action blocks rating — the card may be mid-mutation or may already have
     * left the scheduler's queue, so a rating would enter the GATE 11 pipeline for a turn that is
     * no longer being reviewed. `APPLIED` does not block: a confirmed flag leaves the turn exactly
     * as it was, and a confirmed bury/suspend has already closed the turn (so no turn can be rated
     * against it).
     *
     * Mutual exclusion belongs here and in the coordinator, never in a disabled button (§25).
     */
    fun ratingBlockReason(activeActionStatus: ReviewerActionStatus?): ReviewerActionBlockReason? =
        when (activeActionStatus) {
            null, ReviewerActionStatus.APPLIED -> null
            ReviewerActionStatus.PREPARED, ReviewerActionStatus.SUBMITTING ->
                ReviewerActionBlockReason.ACTION_IN_FLIGHT
            ReviewerActionStatus.RETRY_ALLOWED -> ReviewerActionBlockReason.ACTION_RETRY_AVAILABLE
            ReviewerActionStatus.AMBIGUOUS -> ReviewerActionBlockReason.ACTION_OUTCOME_UNRESOLVED
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
    /** The frozen per-session capability set says this backend cannot do it (INV-13-16). */
    ACTION_UNSUPPORTED,

    /** No active review turn exists to act on. */
    NO_ACTIVE_TURN,

    /** The current turn's review is already resolved (a committed rating) — §23. */
    TURN_RESOLVED,

    /** A rating transaction exists but has not been dispatched (`PREPARED`) — §23. */
    COMMIT_PENDING,

    /** The rating mutation is in flight (`SUBMITTING`) — §23. */
    COMMIT_MUTATION_IN_FLIGHT,

    /** The rating outcome is unknown (`AMBIGUOUS`) — §23. */
    COMMIT_UNRESOLVED,

    /** A rating transaction exists and is provably not applied, but unresolved (`RETRY_ALLOWED`). */
    COMMIT_RETRY_PENDING,

    /** The same turn already has an active reviewer action (`PREPARED`/`SUBMITTING`) — §15. */
    ACTION_IN_FLIGHT,

    /** The turn's action is proven not applied and awaits an explicit retry of the same id — §13. */
    ACTION_RETRY_AVAILABLE,

    /** The turn's action may have been applied; nothing is replayed — INV-13-10. */
    ACTION_OUTCOME_UNRESOLVED,

    /** The turn's action is already confirmed applied; a *new* action needs a new identity — §12. */
    ACTION_ALREADY_APPLIED
}
