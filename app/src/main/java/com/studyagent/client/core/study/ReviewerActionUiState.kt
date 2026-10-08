package com.studyagent.client.core.study

import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.ReviewerAction
import com.studyagent.client.core.anki.ReviewerActionBlockReason
import com.studyagent.client.core.anki.ReviewerActionKind

/**
 * GATE 13 STEP 41/43 — the ephemeral reviewer-action projection of the current turn.
 *
 * Deliberately **not** [ReviewCommitStatus] (STEP 42): that vocabulary belongs to the rating
 * transaction, where `AMBIGUOUS` means "a *rating* may have been applied, and a durable ledger
 * remembers it". A reviewer action has no ledger and no transaction identity, so it gets its own
 * small state — and reusing `AMBIGUOUS` here would make two different guarantees look alike
 * (INV-13-16).
 *
 * ```text
 * Idle  ──request──▶  Applying  ──Applied──▶  Idle          (projection updated)
 *                        │
 *                        ├──Rejected──▶  Failed               (same turn continues; retry is safe)
 *                        ├──Unknown───▶  VerificationRequired  (nothing may progress)
 *                        └──blocked───▶  Blocked               (policy refused before dispatch)
 * ```
 *
 * `Applied` deliberately has no state of its own: once the result is projected (flag metadata or
 * an invalidated turn), there is nothing left to remember — the card state is the backend's truth,
 * not this app's (STEP 43: "Applied may simply return to Idle").
 */
sealed interface ReviewerActionUiState {

    /** Nothing in flight; no failed or unresolved action. The normal state. */
    data object Idle : ReviewerActionUiState

    /** One action is being applied; a second concurrent action must not be dispatched. */
    data class Applying(val action: ReviewerAction) : ReviewerActionUiState

    /**
     * The backend proved the action was not applied. The turn is untouched and rating remains
     * available; the user may try again (STEP 39).
     */
    data class Failed(val action: ReviewerAction, val reason: AnkiError) : ReviewerActionUiState

    /**
     * The action's outcome is unknown; the mutation may have applied. No automatic progression,
     * no replay (STEP 31/40, INV-13-13). Only an explicit session restart leaves this state.
     */
    data class VerificationRequired(
        val action: ReviewerAction,
        val reason: AnkiError? = null,
        val detail: String? = null
    ) : ReviewerActionUiState

    /**
     * The policy refused the request before any dispatch (STEP 24-29). Purely presentational:
     * nothing was sent, so nothing changed.
     */
    data class Blocked(
        val action: ReviewerAction,
        val reason: ReviewerActionBlockReason,
        val detail: String
    ) : ReviewerActionUiState

    /** The action kind currently being applied, if any — used for duplicate suppression. */
    val inFlightKind: ReviewerActionKind?
        get() = (this as? Applying)?.action?.kind

    /** The action kind whose outcome is still unknown, if any — used for fail-closed gating. */
    val unresolvedKind: ReviewerActionKind?
        get() = (this as? VerificationRequired)?.action?.kind

    /**
     * True while the user is being told *why* an action was not dispatched. It is not a blocker
     * for later actions: the next request re-evaluates the policy from scratch.
     */
    val isBlocked: Boolean get() = this is Blocked
}
