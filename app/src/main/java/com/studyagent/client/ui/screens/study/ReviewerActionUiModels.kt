package com.studyagent.client.ui.screens.study

import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.ReviewerAction
import com.studyagent.client.core.anki.ReviewerActionBlockReason
import com.studyagent.client.core.anki.ReviewerActionKind
import com.studyagent.client.core.study.AnswerReviewModel
import com.studyagent.client.core.study.ReviewerActionRefusal
import com.studyagent.client.core.study.ReviewerActionUiState

/**
 * GATE 13 §7/§22 — the card-action menu, as a presentation model.
 *
 * Pure data, derived from [AnswerReviewModel]: the composable renders exactly what is here and never
 * decides capability, policy or phrasing itself (AUDIT 3: no UI bypass). The state it renders is a
 * projection of durable action truth — `Saving` / `RetryAvailable` / `VerificationRequired` — and
 * never a second business state.
 *
 * The object is named `…Copy` (not `…Semantics`) on purpose: `ReviewerActionSemantics` is the
 * *domain* value describing a backend's audited action contract (§28), and two different things may
 * not share one name (AUDIT 1).
 */
object ReviewerActionCopy {
    const val MENU_LABEL = "Card actions"
    const val MENU_OPEN = "Open card actions menu"

    const val FLAG = "Flag"
    const val BURY = "Bury"
    const val SUSPEND = "Suspend"

    const val FLAG_NONE = "No flag"
    const val FLAG_DESCRIPTION = "Change this card's flag. The review continues on this card."
    const val BURY_DESCRIPTION =
        "Bury this card for today. It leaves the current review and comes back on another day."
    const val SUSPEND_DESCRIPTION =
        "Suspend this card. It stops appearing in reviews until you unsuspend it in AnkiDroid."

    const val CONFIRM_BURY_TITLE = "Bury this card?"
    const val CONFIRM_SUSPEND_TITLE = "Suspend this card?"
    const val CONFIRM_BURY_BODY =
        "This card will leave the current review. Your rating for it is not saved, and no review is recorded."
    const val CONFIRM_SUSPEND_BODY =
        "This card will stop appearing in reviews until you unsuspend it in AnkiDroid. " +
            "No review is recorded for it."
    const val CONFIRM = "Confirm"
    const val CANCEL = "Cancel"
    const val RETRY = "Try again"
    const val VERIFY = "Check in AnkiDroid"

    const val TAG_MENU_BUTTON = "anki_card_actions_button"
    const val TAG_MENU = "anki_card_actions_menu"
    const val TAG_FLAG_ITEM = "anki_action_flag"
    const val TAG_BURY_ITEM = "anki_action_bury"
    const val TAG_SUSPEND_ITEM = "anki_action_suspend"
    const val TAG_CONFIRM_DIALOG = "anki_action_confirm_dialog"
    const val TAG_STATUS = "anki_action_status"

    fun itemTag(kind: ReviewerActionKind): String = when (kind) {
        ReviewerActionKind.FLAG -> TAG_FLAG_ITEM
        ReviewerActionKind.BURY -> TAG_BURY_ITEM
        ReviewerActionKind.SUSPEND -> TAG_SUSPEND_ITEM
    }

    /**
     * §22 — a flag is never identified by colour alone. The menu item says which flag it sets, and
     * the flag's own name travels in the accessibility description for every flag value.
     */
    fun flagLabel(flag: AnkiFlag): String = when (flag) {
        AnkiFlag.NONE -> FLAG_NONE
        AnkiFlag.UNKNOWN -> "Flag (unknown)"
        else -> "Flag ${flag.name.lowercase().replaceFirstChar { it.uppercase() }}"
    }

    fun actionLabel(action: ReviewerAction): String = when (action) {
        is ReviewerAction.SetFlag -> flagLabel(action.flag)
        ReviewerAction.BuryCard -> BURY
        ReviewerAction.SuspendCard -> SUSPEND
    }

    fun actionDescription(kind: ReviewerActionKind): String = when (kind) {
        ReviewerActionKind.FLAG -> FLAG_DESCRIPTION
        ReviewerActionKind.BURY -> BURY_DESCRIPTION
        ReviewerActionKind.SUSPEND -> SUSPEND_DESCRIPTION
    }

    /** The confirmation body for a turn-invalidating action (never hide the consequence). */
    fun confirmationBody(kind: ReviewerActionKind): String = when (kind) {
        ReviewerActionKind.BURY -> CONFIRM_BURY_BODY
        ReviewerActionKind.SUSPEND -> CONFIRM_SUSPEND_BODY
        ReviewerActionKind.FLAG -> ""
    }

    fun confirmationTitle(kind: ReviewerActionKind): String = when (kind) {
        ReviewerActionKind.BURY -> CONFIRM_BURY_TITLE
        ReviewerActionKind.SUSPEND -> CONFIRM_SUSPEND_TITLE
        ReviewerActionKind.FLAG -> ""
    }

    /** Honest, non-technical copy for every policy refusal (never a silent dead button). */
    fun blockMessage(reason: ReviewerActionBlockReason): String = when (reason) {
        ReviewerActionBlockReason.ACTION_UNSUPPORTED ->
            "This action is not supported by the connected Anki backend."
        ReviewerActionBlockReason.NO_ACTIVE_TURN ->
            "There is no active card to act on."
        ReviewerActionBlockReason.TURN_RESOLVED ->
            "This review is already saved. Card actions are available again on the next card."
        ReviewerActionBlockReason.COMMIT_PENDING, ReviewerActionBlockReason.COMMIT_RETRY_PENDING ->
            "Your rating for this card is still being resolved. Finish that first."
        ReviewerActionBlockReason.COMMIT_MUTATION_IN_FLIGHT, ReviewerActionBlockReason.COMMIT_UNRESOLVED ->
            "Anki may already be changing this card for your rating. Wait until it is confirmed."
        ReviewerActionBlockReason.ACTION_IN_FLIGHT ->
            "An action is already being applied to this card."
        ReviewerActionBlockReason.ACTION_RETRY_AVAILABLE ->
            "The last action was not applied. Try it again instead of starting a new one."
        ReviewerActionBlockReason.ACTION_OUTCOME_UNRESOLVED ->
            "Study-Agent could not confirm the last action, so it will not send another one."
        ReviewerActionBlockReason.ACTION_ALREADY_APPLIED ->
            "This action is already applied to the card."
    }
}

/** One row of the card-action menu. Only ever built for a supported action. */
data class ReviewerActionItemUi(
    val kind: ReviewerActionKind,
    val label: String,
    val description: String,
    val requiresConfirmation: Boolean,
    val enabled: Boolean,
    val tag: String
)

/** Presentation state of the action menu. */
sealed interface ReviewerActionMenuUi {
    /** No action is available for this turn (capability set empty, or no turn). */
    data object Hidden : ReviewerActionMenuUi

    /** The menu may be opened; [busy] while one action is being applied. */
    data class Available(
        val items: List<ReviewerActionItemUi>,
        val busy: Boolean
    ) : ReviewerActionMenuUi

    companion object {
        fun from(model: AnswerReviewModel): ReviewerActionMenuUi {
            if (!model.reviewerActionsEnabled || model.availableReviewerActionKinds.isEmpty()) return Hidden
            val state = model.reviewerActionState
            val busy = state is ReviewerActionUiState.Saving
            val blocked = state is ReviewerActionUiState.VerificationRequired
            val items = model.availableReviewerActionKinds.map { kind ->
                val action = when (kind) {
                    ReviewerActionKind.FLAG -> ReviewerAction.SetFlag(AnkiFlag.NONE)
                    ReviewerActionKind.BURY -> ReviewerAction.BuryCard
                    ReviewerActionKind.SUSPEND -> ReviewerAction.SuspendCard
                }
                ReviewerActionItemUi(
                    kind = kind,
                    label = ReviewerActionCopy.actionLabel(action),
                    description = ReviewerActionCopy.actionDescription(kind),
                    // bury/suspend leave the active review flow; the user must confirm.
                    requiresConfirmation = action.invalidatesCurrentTurn,
                    enabled = !busy && !blocked,
                    tag = ReviewerActionCopy.itemTag(kind)
                )
            }
            return Available(items = items, busy = busy)
        }
    }
}

/** The model's own status line, refusal included — the form a composable should call. */
fun reviewerActionStatusOf(model: AnswerReviewModel): ReviewerActionStatusUi? =
    reviewerActionStatusOf(model.reviewerActionState, model.reviewerActionRefusal)

/** The one-line status under the menu: why an action is refused, retired, or unconfirmed. */
data class ReviewerActionStatusUi(
    val message: String,
    val isWarning: Boolean,
    val tag: String = ReviewerActionCopy.TAG_STATUS
)

/**
 * The status line for the durable action projection (§7) plus a policy refusal (which is not a
 * state: nothing was sent). `Saving` shows nothing — a spinner is already the menu's `busy`.
 */
fun reviewerActionStatusOf(
    state: ReviewerActionUiState,
    refusal: ReviewerActionRefusal? = null
): ReviewerActionStatusUi? = when (state) {
    ReviewerActionUiState.Idle, is ReviewerActionUiState.Saving -> refusal?.let {
        ReviewerActionStatusUi(
            message = ReviewerActionCopy.blockMessage(it.reason),
            isWarning = true
        )
    }

    is ReviewerActionUiState.RetryAvailable -> ReviewerActionStatusUi(
        message = "Anki did not apply ${ReviewerActionCopy.actionLabel(state.action)}. " +
            "Nothing was changed; you can try it again.",
        isWarning = true
    )

    is ReviewerActionUiState.VerificationRequired -> ReviewerActionStatusUi(
        message = "Study-Agent could not confirm whether ${ReviewerActionCopy.actionLabel(state.action)} " +
            "was applied in Anki, so it will not repeat it.",
        isWarning = true
    )
}
