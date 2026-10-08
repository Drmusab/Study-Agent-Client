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

    /** Spinner semantics while a durable action is being applied. */
    const val SAVING = "Applying a card action"

    /** Marks the flag the backend currently reports (never "applied successfully"). */
    const val FLAG_CURRENT = "current"

    /** The chooser's one-line explanation, including what the backend says right now. */
    fun flagChooserHint(currentFlag: AnkiFlag?): String = when (currentFlag) {
        null -> "Anki did not report this card's flag. Choosing a value sets it."
        AnkiFlag.NONE -> "This card has no flag."
        AnkiFlag.UNKNOWN -> "This card carries a flag this app cannot name. Choosing a value replaces it."
        else -> "This card is marked ${flagLabel(currentFlag)}."
    }

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

    /** One tag per flag *value*, so a UI test can pick a colour without depending on a label. */
    fun flagChoiceTag(flag: AnkiFlag): String = "${TAG_FLAG_ITEM}_${flag.name.lowercase()}"

    /**
     * The flags a user may actually set: the eight named values plus [AnkiFlag.NONE] (clear it).
     *
     * [AnkiFlag.UNKNOWN] is deliberately absent. It means "this build could not name the backend's
     * flag" — a fact about a *read*, not something a user can write, and
     * [ReviewerAction.SetFlag] rejects it at construction.
     */
    val SETTABLE_FLAGS: List<AnkiFlag> = listOf(
        AnkiFlag.NONE,
        AnkiFlag.RED,
        AnkiFlag.ORANGE,
        AnkiFlag.GREEN,
        AnkiFlag.BLUE,
        AnkiFlag.PINK,
        AnkiFlag.TURQUOISE,
        AnkiFlag.PURPLE
    )

    /**
     * §22 — a flag is never identified by colour alone. The menu item says which flag it sets, and
     * the flag's own name travels in the accessibility description for every flag value.
     */
    fun flagLabel(flag: AnkiFlag): String = when (flag) {
        AnkiFlag.NONE -> FLAG_NONE
        AnkiFlag.UNKNOWN -> "Flag (unknown)"
        else -> "Flag ${flagShortName(flag)}"
    }

    /**
     * The *menu row* label for a kind.
     *
     * A flag row cannot name one flag value: the row opens the chooser instead, and the row's label
     * only says where the card is now ("Flag: Blue", or plain "Flag" when the backend reports none or
     * does not report at all).
     */
    fun menuItemLabel(kind: ReviewerActionKind, currentFlag: AnkiFlag? = null): String = when (kind) {
        ReviewerActionKind.FLAG -> when (currentFlag) {
            null, AnkiFlag.NONE -> FLAG
            else -> "$FLAG: ${flagShortName(currentFlag)}"
        }
        ReviewerActionKind.BURY -> BURY
        ReviewerActionKind.SUSPEND -> SUSPEND
    }

    /** The flag's own name, without the "Flag " prefix (chooser rows and menu labels). */
    fun flagShortName(flag: AnkiFlag): String = when (flag) {
        AnkiFlag.NONE -> FLAG_NONE
        AnkiFlag.UNKNOWN -> "Unknown"
        else -> flag.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    /** The chooser heading, so a colour row is never mistaken for the whole action. */
    const val FLAG_CHOOSER_TITLE = "Flag this card"

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

/**
 * One selectable flag value in the menu's flag chooser.
 *
 * [selected] mirrors the flag the backend currently reports ([AnswerReviewModel.currentFlag]); it
 * says *where the card is*, never that a SetFlag transaction succeeded.
 */
data class ReviewerActionFlagChoiceUi(
    val flag: AnkiFlag,
    val label: String,
    val selected: Boolean,
    val enabled: Boolean,
    val tag: String
)

/** Presentation state of the action menu. */
sealed interface ReviewerActionMenuUi {
    /** No action is available for this turn (capability set empty, or no turn). */
    data object Hidden : ReviewerActionMenuUi

    /**
     * The menu may be opened. [canStartNewAction] is false whenever a durable action record exists
     * (`Saving` / `RetryAvailable` / `VerificationRequired`): while one logical action is unfinished
     * the turn has no free mutation slot (§15/§22), so every row is disabled and the status line
     * offers the only legal next step (retry the same identity, or reconcile it).
     */
    data class Available(
        val items: List<ReviewerActionItemUi>,
        val busy: Boolean,
        val canStartNewAction: Boolean,
        val flagChoices: List<ReviewerActionFlagChoiceUi> = emptyList(),
        val currentFlag: AnkiFlag? = null
    ) : ReviewerActionMenuUi

    companion object {
        fun from(model: AnswerReviewModel): ReviewerActionMenuUi {
            if (!model.reviewerActionsEnabled || model.availableReviewerActionKinds.isEmpty()) return Hidden
            val state = model.reviewerActionState
            val busy = state is ReviewerActionUiState.Saving
            // Idle is the only projection with no durable action behind it (§7 mapping).
            val canStartNewAction = state is ReviewerActionUiState.Idle
            val items = model.availableReviewerActionKinds.map { kind ->
                val action = when (kind) {
                    ReviewerActionKind.FLAG -> ReviewerAction.SetFlag(AnkiFlag.NONE)
                    ReviewerActionKind.BURY -> ReviewerAction.BuryCard
                    ReviewerActionKind.SUSPEND -> ReviewerAction.SuspendCard
                }
                ReviewerActionItemUi(
                    kind = kind,
                    label = ReviewerActionCopy.menuItemLabel(kind, model.currentFlag),
                    description = ReviewerActionCopy.actionDescription(kind),
                    // bury/suspend leave the active review flow; the user must confirm.
                    requiresConfirmation = action.invalidatesCurrentTurn,
                    enabled = canStartNewAction,
                    tag = ReviewerActionCopy.itemTag(kind)
                )
            }
            // A flag is a value, not a single command: the menu offers every settable value, and the
            // chooser disappears entirely when this backend cannot write flags (INV-13-16).
            val flagChoices = if (ReviewerActionKind.FLAG in model.availableReviewerActionKinds) {
                ReviewerActionCopy.SETTABLE_FLAGS.map { flag ->
                    ReviewerActionFlagChoiceUi(
                        flag = flag,
                        label = ReviewerActionCopy.flagLabel(flag),
                        selected = model.currentFlag == flag,
                        enabled = canStartNewAction,
                        tag = ReviewerActionCopy.flagChoiceTag(flag)
                    )
                }
            } else {
                emptyList()
            }
            return Available(
                items = items,
                busy = busy,
                canStartNewAction = canStartNewAction,
                flagChoices = flagChoices,
                currentFlag = model.currentFlag
            )
        }
    }
}

/** The model's own status line, refusal included — the form a composable should call. */
fun reviewerActionStatusOf(model: AnswerReviewModel): ReviewerActionStatusUi? =
    reviewerActionStatusOf(model.reviewerActionState, model.reviewerActionRefusal)

/**
 * The only step the durable projection permits right now, if any.
 *
 * It is derived from the same [ReviewerActionUiState] the message is: `RetryAvailable` is the one
 * state where the *same* action identity may be resubmitted (§13), and `VerificationRequired` is the
 * one state where a read-only reconciliation may be asked for (§27). Everything else offers nothing —
 * so the menu can never grow a second way to mutate the card.
 */
enum class ReviewerActionStatusAction {
    /** Nothing to do but read the message. */
    NONE,

    /** `RETRY_ALLOWED` (or a restored `PREPARED`) — retry the same [com.studyagent.client.core.anki.ReviewerActionId]. */
    RETRY,

    /** `AMBIGUOUS` / restored `SUBMITTING` — ask for read-only reconciliation, never a retry. */
    VERIFY
}

/** The one-line status under the menu: why an action is refused, retired, or unconfirmed. */
data class ReviewerActionStatusUi(
    val message: String,
    val isWarning: Boolean,
    val action: ReviewerActionStatusAction = ReviewerActionStatusAction.NONE,
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
        isWarning = true,
        action = ReviewerActionStatusAction.RETRY
    )

    is ReviewerActionUiState.VerificationRequired -> ReviewerActionStatusUi(
        message = "Study-Agent could not confirm whether ${ReviewerActionCopy.actionLabel(state.action)} " +
            "was applied in Anki, so it will not repeat it.",
        isWarning = true,
        action = ReviewerActionStatusAction.VERIFY
    )
}
