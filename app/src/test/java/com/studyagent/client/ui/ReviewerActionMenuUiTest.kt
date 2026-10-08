package com.studyagent.client.ui

import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.anki.ReviewerAction
import com.studyagent.client.core.anki.ReviewerActionBlockReason
import com.studyagent.client.core.anki.ReviewerActionId
import com.studyagent.client.core.anki.ReviewerActionKind
import com.studyagent.client.core.study.AnswerCompareMode
import com.studyagent.client.core.study.AnswerRevealState
import com.studyagent.client.core.study.AnswerReviewModel
import com.studyagent.client.core.study.ReviewerActionRefusal
import com.studyagent.client.core.study.ReviewerActionUiState
import com.studyagent.client.ui.screens.study.ReviewerActionCopy
import com.studyagent.client.ui.screens.study.ReviewerActionMenuUi
import com.studyagent.client.ui.screens.study.ReviewerActionStatusAction
import com.studyagent.client.ui.screens.study.reviewerActionStatusOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 13 §7/§22/§31 — the card-action menu as a *projection*.
 *
 * These are the rules the Compose menu renders without deciding anything itself: which rows exist
 * (capability, never a disabled lie), when a new action is possible at all (only `Idle` — one active
 * action per turn), which single next step an unfinished action permits (retry the same identity, or
 * reconcile read-only), and which flag value the backend currently reports.
 */
class ReviewerActionMenuUiTest {

    private val allKinds = listOf(ReviewerActionKind.FLAG, ReviewerActionKind.BURY, ReviewerActionKind.SUSPEND)

    private fun model(
        state: ReviewerActionUiState = ReviewerActionUiState.Idle,
        kinds: List<ReviewerActionKind> = allKinds,
        enabled: Boolean = true,
        refusal: ReviewerActionRefusal? = null,
        currentFlag: AnkiFlag? = null
    ) = AnswerReviewModel(
        turnId = ReviewTurnId("turn-1"),
        userAnswerText = null,
        referenceAnswerText = null,
        evaluationFeedback = null,
        evaluationSummary = null,
        suggestedRating = null,
        revealState = AnswerRevealState.HIDDEN,
        compareMode = AnswerCompareMode.ORIGINAL,
        availableReviewerActionKinds = kinds,
        reviewerActionState = state,
        reviewerActionsEnabled = enabled,
        reviewerActionRefusal = refusal,
        currentFlag = currentFlag
    )

    @Test
    fun `a turn the backend cannot mutate renders no menu at all`() {
        assertEquals(
            ReviewerActionMenuUi.Hidden,
            ReviewerActionMenuUi.from(model(kinds = emptyList()))
        )
        assertEquals(
            "no active turn is not a menu with dead rows",
            ReviewerActionMenuUi.Hidden,
            ReviewerActionMenuUi.from(model(enabled = false))
        )
    }

    @Test
    fun `idle offers exactly the frozen capability set in a stable order`() {
        val menu = ReviewerActionMenuUi.from(model(kinds = listOf(ReviewerActionKind.BURY))) as ReviewerActionMenuUi.Available
        assertEquals(listOf(ReviewerActionKind.BURY), menu.items.map { it.kind })
        assertTrue("nothing is applied yet", menu.canStartNewAction)
        assertTrue(menu.items.single().enabled)
        assertTrue("bury closes the turn, so it must be confirmed", menu.items.single().requiresConfirmation)
        assertTrue("no flag capability, no flag chooser", menu.flagChoices.isEmpty())

        val full = ReviewerActionMenuUi.from(model()) as ReviewerActionMenuUi.Available
        assertEquals(allKinds, full.items.map { it.kind })
        assertFalse("a flag is metadata, not a turn-invalidating action", full.items.first().requiresConfirmation)
        assertTrue(full.items[1].requiresConfirmation)
        assertTrue(full.items[2].requiresConfirmation)
        assertEquals(ReviewerActionCopy.TAG_BURY_ITEM, full.items[1].tag)
    }

    @Test
    fun `a flag is a value - the chooser offers the named flags and marks the current one`() {
        val menu = ReviewerActionMenuUi.from(model(currentFlag = AnkiFlag.BLUE)) as ReviewerActionMenuUi.Available

        assertEquals(
            "every flag a user may set, and nothing else",
            listOf(
                AnkiFlag.NONE, AnkiFlag.RED, AnkiFlag.ORANGE, AnkiFlag.GREEN,
                AnkiFlag.BLUE, AnkiFlag.PINK, AnkiFlag.TURQUOISE, AnkiFlag.PURPLE
            ),
            menu.flagChoices.map { it.flag }
        )
        assertTrue(
            "'this build cannot name the backend's flag' is a read, not a settable value",
            menu.flagChoices.none { it.flag == AnkiFlag.UNKNOWN }
        )
        assertEquals(AnkiFlag.BLUE, menu.flagChoices.single { it.selected }.flag)
        assertEquals("Blue", ReviewerActionCopy.flagShortName(AnkiFlag.BLUE))
        assertEquals("Flag: Blue", ReviewerActionCopy.menuItemLabel(ReviewerActionKind.FLAG, AnkiFlag.BLUE))
    }

    @Test
    fun `no reported flag selects none and never implies the flag was cleared`() {
        val menu = ReviewerActionMenuUi.from(model(currentFlag = null)) as ReviewerActionMenuUi.Available
        assertTrue(menu.flagChoices.none { it.selected })
        assertEquals("Flag", ReviewerActionCopy.menuItemLabel(ReviewerActionKind.FLAG, null))
        assertEquals(AnkiFlag.NONE, menu.flagChoices.first().flag)
        assertTrue("clearing is a choice, not the default state", menu.flagChoices.first().flag == AnkiFlag.NONE)
    }

    @Test
    fun `while one action is unfinished no new action may start`() {
        val actionId = ReviewerActionId("a1")
        val states = listOf(
            ReviewerActionUiState.Saving(actionId, ReviewerAction.BuryCard),
            ReviewerActionUiState.RetryAvailable(actionId, ReviewerAction.BuryCard),
            ReviewerActionUiState.VerificationRequired(actionId, ReviewerAction.BuryCard)
        )
        states.forEach { state ->
            val menu = ReviewerActionMenuUi.from(model(state = state)) as ReviewerActionMenuUi.Available
            assertFalse("$state must not allow a second action", menu.canStartNewAction)
            assertTrue("every row is disabled", menu.items.none { it.enabled })
            assertTrue("and so is every flag value", menu.flagChoices.none { it.enabled })
        }
    }

    @Test
    fun `the busy state is only the durable Saving projection`() {
        val actionId = ReviewerActionId("a1")
        assertTrue(
            (ReviewerActionMenuUi.from(model(state = ReviewerActionUiState.Saving(actionId, ReviewerAction.BuryCard)))
                as ReviewerActionMenuUi.Available).busy
        )
        assertFalse(
            (ReviewerActionMenuUi.from(
                model(state = ReviewerActionUiState.VerificationRequired(actionId, ReviewerAction.BuryCard))
            ) as ReviewerActionMenuUi.Available).busy
        )
    }

    @Test
    fun `the status line offers retry only for a proven non-application`() {
        val actionId = ReviewerActionId("a1")
        val retry = ReviewerActionUiState.RetryAvailable(actionId, ReviewerAction.SetFlag(AnkiFlag.RED))
        val status = reviewerActionStatusOf(retry)

        assertEquals(ReviewerActionStatusAction.RETRY, status?.action)
        assertTrue(status!!.isWarning)
        assertTrue("the message names the action", status.message.contains("Flag Red"))
        // Idle and Saving without a refusal have no line at all: the spinner is the Saving state.
        assertNull(reviewerActionStatusOf(ReviewerActionUiState.Idle))
        assertNull(
            reviewerActionStatusOf(
                ReviewerActionUiState.Saving(actionId, ReviewerAction.SetFlag(AnkiFlag.RED))
            )
        )
    }

    @Test
    fun `the status line offers verification only for an unproven outcome`() {
        val actionId = ReviewerActionId("a1")
        val status = reviewerActionStatusOf(
            ReviewerActionUiState.VerificationRequired(actionId, ReviewerAction.SuspendCard)
        )
        assertEquals(ReviewerActionStatusAction.VERIFY, status?.action)
        assertTrue(status!!.isWarning)
        assertTrue(status.message.contains("Suspend"))
        assertTrue(
            "the copy must never suggest a repeat",
            !status.message.contains("again", ignoreCase = true) ||
                !status.message.contains("retry", ignoreCase = true)
        )
    }

    @Test
    fun `a refusal is not a state - it explains itself and offers nothing`() {
        val refusal = ReviewerActionRefusal(
            action = ReviewerAction.BuryCard,
            reason = ReviewerActionBlockReason.COMMIT_MUTATION_IN_FLIGHT,
            detail = "commit_submitting"
        )
        val status = reviewerActionStatusOf(model(refusal = refusal))
        assertEquals(ReviewerActionStatusAction.NONE, status?.action)
        assertEquals(ReviewerActionCopy.blockMessage(refusal.reason), status?.message)
        assertTrue(status!!.isWarning)
        // A refused request leaves the menu usable: nothing was sent, so the user may ask again.
        val menu = ReviewerActionMenuUi.from(model(refusal = refusal)) as ReviewerActionMenuUi.Available
        assertTrue(menu.canStartNewAction)
    }
}
