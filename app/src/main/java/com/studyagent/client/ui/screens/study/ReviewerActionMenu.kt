package com.studyagent.client.ui.screens.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.ReviewerAction
import com.studyagent.client.core.anki.ReviewerActionKind
import com.studyagent.client.core.study.AnswerReviewModel
import com.studyagent.client.ui.theme.AppColors
import com.studyagent.client.ui.theme.AppSpacing

/**
 * GATE 13 §7/§17/§22/§31 — the reviewer-action (flag / bury / suspend) menu, mounted in the study
 * screen.
 *
 * The composable owns **no** decision: capability, availability and phrase come from the pure
 * projection ([ReviewerActionMenuUi.from], [reviewerActionStatusOf]) that the reducer derived from
 * durable action truth (§6/§7, AUDIT 3). It renders the rows that projection produced, dispatches
 * user intent through its callbacks — which reach the state machine and then the
 * `ReviewerActionCoordinator`, never a backend or a coordinator directly (INV-13-18) — and shows the
 * one legal next step for an unfinished action (retry the *same* identity, or reconcile it read-only).
 *
 * Consequences the layout encodes:
 *
 * - A card the backend cannot mutate offer no disabled controls: the menu is [ReviewerActionMenuUi.Hidden]
 *   and nothing renders at all (§22/INV-13-16).
 * - While a durable action exists (`Saving` / `RetryAvailable` / `VerificationRequired`) every row is
 *   disabled: one active action per turn (§15), and an interrupted one must be resolved by the status
 *   line's explicit retry/reconcile instead of a second mutation (§13/§26).
 * - Bury and suspend ask for confirmation first: they close the current review turn and, unlike a
 *   rating, deliberately record no review.
 * - The flag row opens a value chooser (a flag is a value, not a command) and marks the flag the
 *   backend currently reports.
 */
@Composable
fun ReviewerActionMenu(
    model: AnswerReviewModel,
    onAction: (ReviewerAction) -> Unit,
    onRetry: () -> Unit,
    onRecover: () -> Unit,
    modifier: Modifier = Modifier
) {
    val menu = remember(model) { ReviewerActionMenuUi.from(model) }
    if (menu is ReviewerActionMenuUi.Hidden) return
    val status = remember(model) { reviewerActionStatusOf(model) }

    var expanded by remember { mutableStateOf(false) }
    var flagChooserFor by remember { mutableStateOf<ReviewerActionKind?>(null) }
    var confirmKind by remember { mutableStateOf<ReviewerActionKind?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (menu.busy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(16.dp)
                        .semantics { contentDescription = ReviewerActionCopy.SAVING },
                    strokeWidth = 2.dp,
                    color = AppColors.brandPrimary
                )
                Spacer(modifier = Modifier.width(AppSpacing.XS))
            }
            Box {
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier
                        .testTag(ReviewerActionCopy.TAG_MENU_BUTTON)
                        .semantics { contentDescription = ReviewerActionCopy.MENU_OPEN }
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = null,
                        tint = AppColors.contentSecondary
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.testTag(ReviewerActionCopy.TAG_MENU)
                ) {
                    menu.items.forEach { item ->
                        DropdownMenuItem(
                            enabled = item.enabled,
                            onClick = {
                                expanded = false
                                when (item.kind) {
                                    // A flag is a value: open the chooser instead of setting one
                                    // arbitrary colour.
                                    ReviewerActionKind.FLAG -> flagChooserFor = item.kind
                                    else -> if (item.requiresConfirmation) {
                                        confirmKind = item.kind
                                    } else {
                                        onAction(actionOf(item.kind))
                                    }
                                }
                            },
                            modifier = Modifier.testTag(item.tag),
                            text = {
                                Column {
                                    Text(
                                        text = item.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (item.enabled) {
                                            AppColors.contentPrimary
                                        } else {
                                            AppColors.contentMuted
                                        }
                                    )
                                    Text(
                                        text = item.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AppColors.contentMuted
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

        // The durable projection's own status line: a refusal (nothing was sent), a proven
        // non-application (retry the same identity), or an unproven outcome (reconcile, never replay).
        if (status != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = status.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status.isWarning) AppColors.statusWarning else AppColors.contentSecondary,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(status.tag)
                )
                when (status.action) {
                    ReviewerActionStatusAction.RETRY -> TextButton(onClick = onRetry) {
                        Text(text = ReviewerActionCopy.RETRY, style = MaterialTheme.typography.labelMedium)
                    }

                    ReviewerActionStatusAction.VERIFY -> TextButton(onClick = onRecover) {
                        Text(text = ReviewerActionCopy.VERIFY, style = MaterialTheme.typography.labelMedium)
                    }

                    ReviewerActionStatusAction.NONE -> Unit
                }
            }
        }
    }

    // Flag chooser: one row per settable value; the backend-reported flag is marked as the current
    // one, without claiming a transaction succeeded.
    val chooser = flagChooserFor
    if (chooser != null) {
        AlertDialog(
            onDismissRequest = { flagChooserFor = null },
            title = { Text(ReviewerActionCopy.FLAG_CHOOSER_TITLE) },
            text = {
                Column {
                    Text(
                        text = ReviewerActionCopy.flagChooserHint(menu.currentFlag),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.contentSecondary
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.XS))
                    menu.flagChoices.forEach { choice ->
                        TextButton(
                            enabled = choice.enabled,
                            onClick = {
                                flagChooserFor = null
                                onAction(ReviewerAction.SetFlag(choice.flag))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(choice.tag)
                                .semantics {
                                    contentDescription = choice.label
                                    stateDescription = if (choice.selected) {
                                        ReviewerActionCopy.FLAG_CURRENT
                                    } else {
                                        ""
                                    }
                                }
                        ) {
                            Text(
                                text = if (choice.selected) {
                                    "${choice.label} • ${ReviewerActionCopy.FLAG_CURRENT}"
                                } else {
                                    choice.label
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { flagChooserFor = null }) {
                    Text(ReviewerActionCopy.CANCEL)
                }
            }
        )
    }

    // Confirmation for the turn-invalidating actions: the consequence is stated before it happens.
    val confirming = confirmKind
    if (confirming != null) {
        AlertDialog(
            onDismissRequest = { confirmKind = null },
            title = { Text(ReviewerActionCopy.confirmationTitle(confirming)) },
            text = { Text(ReviewerActionCopy.confirmationBody(confirming)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmKind = null
                        onAction(actionOf(confirming))
                    }
                ) {
                    Text(ReviewerActionCopy.CONFIRM)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmKind = null }) {
                    Text(ReviewerActionCopy.CANCEL)
                }
            },
            modifier = Modifier.testTag(ReviewerActionCopy.TAG_CONFIRM_DIALOG)
        )
    }
}

/**
 * The one mapping from a menu row to the action it dispatches. Total by construction: a kind outside
 * the flag family cannot be sent with a flag value ([AnkiFlag.NONE] never reaches the backend from
 * here — the chooser owns flag values and always names one).
 */
private fun actionOf(kind: ReviewerActionKind): ReviewerAction = when (kind) {
    ReviewerActionKind.FLAG -> ReviewerAction.SetFlag(AnkiFlag.NONE)
    ReviewerActionKind.BURY -> ReviewerAction.BuryCard
    ReviewerActionKind.SUSPEND -> ReviewerAction.SuspendCard
}
