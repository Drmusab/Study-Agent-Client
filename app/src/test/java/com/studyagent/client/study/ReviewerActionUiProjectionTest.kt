package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.AnkiFlag
import com.studyagent.client.core.anki.ReviewerAction
import com.studyagent.client.core.anki.ReviewerActionBackendResult
import com.studyagent.client.core.anki.ReviewerActionBlockReason
import com.studyagent.client.core.models.Rating
import com.studyagent.client.core.study.AnswerReviewModel
import com.studyagent.client.core.study.ReviewerActionUiState
import com.studyagent.client.ui.screens.study.ReviewerActionCopy
import com.studyagent.client.ui.screens.study.ReviewerActionMenuUi
import com.studyagent.client.ui.screens.study.ReviewerActionStatusAction
import com.studyagent.client.ui.screens.study.reviewerActionStatusOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 13 §7/§22/§31 (AUDIT 5/INV-13-18) — the presentation projection, produced by the *real*
 * reducer + executor + coordinator and read exactly the way the study screen reads it.
 *
 * The Compose menu is a thin renderer over these values, and these are the values that decide
 * whether the user may act, retry or merely check in AnkiDroid:
 *
 * | Durable status | Projection | Menu offers |
 * |---|---|---|
 * | no record | `Idle` | every frozen capability |
 * | `APPLIED` flag | `Idle` + the projected flag | every frozen capability (same turn) |
 * | `RETRY_ALLOWED` | `RetryAvailable` | *retry that same action only* |
 * | `AMBIGUOUS` | `VerificationRequired` | read-only reconciliation only |
 * | refused request | `Idle` + a refusal message | nothing durable; asking again is fine |
 */
class ReviewerActionUiProjectionTest {

    private fun model(harness: ReviewerActionHarness): AnswerReviewModel =
        checkNotNull(AnswerReviewModel.from(harness.state)) { "no turn to project" }

    @Test
    fun `a confirmed flag keeps the turn, projects the flag and leaves the rating bar usable`() = runBlocking {
        val harness = ReviewerActionHarness.flagCapable()
        harness.loadToRating()
        assertNull("nothing is set before the action", model(harness).currentFlag)

        harness.requestAction(ReviewerAction.SetFlag(AnkiFlag.RED))
        harness.drain()

        val projected = model(harness)
        assertEquals("the backend-reported flag is projected", AnkiFlag.RED, projected.currentFlag)
        assertEquals("APPLIED returns the projection to Idle", ReviewerActionUiState.Idle, projected.reviewerActionState)
        assertNull("no refusal remains", projected.reviewerActionRefusal)
        assertTrue("the same turn is still reviewable and rateable", projected.ratingControlsEnabled)

        val menu = ReviewerActionMenuUi.from(projected) as ReviewerActionMenuUi.Available
        assertTrue("a new action is possible again", menu.canStartNewAction)
        assertEquals(AnkiFlag.RED, menu.flagChoices.single { it.selected }.flag)
        assertEquals("Flag: Red", menu.items.first().label)
    }

    @Test
    fun `a proven non-application offers the same action again and blocks the rating`() = runBlocking {
        val harness = ReviewerActionHarness()
        harness.loadToRating()
        harness.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.ConfirmedNotApplied(AnkiError.UnsupportedAction("bury"))
            )
        )

        harness.requestAction(ReviewerAction.BuryCard)
        harness.drain()

        val projected = model(harness)
        assertTrue(
            "RETRY_ALLOWED is RetryAvailable, never a silent success",
            projected.reviewerActionState is ReviewerActionUiState.RetryAvailable
        )
        assertFalse(
            "the card may already be affected, so a rating must not start",
            projected.ratingControlsEnabled
        )
        val menu = ReviewerActionMenuUi.from(projected) as ReviewerActionMenuUi.Available
        assertFalse("no second action while one is unfinished", menu.canStartNewAction)
        assertEquals(
            ReviewerActionStatusAction.RETRY,
            reviewerActionStatusOf(projected)?.action
        )
        assertTrue(
            "the status names the action that must be retried",
            reviewerActionStatusOf(projected)!!.message.contains(ReviewerActionCopy.BURY)
        )
    }

    @Test
    fun `an unproven outcome asks for verification and never for a replay`() = runBlocking {
        val harness = ReviewerActionHarness()
        harness.loadToRating()
        harness.fake.scriptActions(
            FakeAnkiBackend.ActionStep(
                ReviewerActionBackendResult.OutcomeUnknown(AnkiError.ProviderUnavailable())
            )
        )

        harness.requestAction(ReviewerAction.BuryCard)
        harness.drain()

        val projected = model(harness)
        assertTrue(
            "OutcomeUnknown is VerificationRequired, not a retry grant",
            projected.reviewerActionState is ReviewerActionUiState.VerificationRequired
        )
        assertFalse(projected.ratingControlsEnabled)
        val menu = ReviewerActionMenuUi.from(projected) as ReviewerActionMenuUi.Available
        assertFalse(menu.canStartNewAction)
        assertEquals(ReviewerActionStatusAction.VERIFY, reviewerActionStatusOf(projected)?.action)
        assertEquals(
            "no card may advance until the outcome is known (§22)",
            0,
            harness.takeNextEffects().size
        )
    }

    @Test
    fun `a refused action is a message, not a state, and the menu stays usable`() = runBlocking {
        val harness = ReviewerActionHarness()
        harness.loadToRating()
        harness.rate(Rating.GOOD)

        harness.requestAction(ReviewerAction.BuryCard)

        val projected = model(harness)
        assertEquals(
            "nothing durable exists for a refused request",
            ReviewerActionUiState.Idle,
            projected.reviewerActionState
        )
        assertEquals(
            "the refusal explains the rating transaction that owns the turn",
            ReviewerActionBlockReason.COMMIT_PENDING,
            projected.reviewerActionRefusal?.reason
        )
        val status = reviewerActionStatusOf(projected)
        assertNotNull(status)
        assertEquals(ReviewerActionStatusAction.NONE, status!!.action)
        assertEquals(ReviewerActionCopy.blockMessage(ReviewerActionBlockReason.COMMIT_PENDING), status.message)
        assertTrue(
            "a refusal changed nothing, so the user may ask again",
            (ReviewerActionMenuUi.from(projected) as ReviewerActionMenuUi.Available).canStartNewAction
        )
    }
}
