package com.studyagent.client.study

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.CommitRatingRequest
import com.studyagent.client.core.anki.ReviewCommitId
import com.studyagent.client.core.anki.ReviewCommitStatus
import com.studyagent.client.core.anki.ReviewTurnId
import com.studyagent.client.core.study.AnkiRatingCommit
import com.studyagent.client.core.study.RatingCommitUiState
import com.studyagent.client.core.study.projectForUi
import com.studyagent.client.core.study.ratingCommitUiState
import com.studyagent.client.core.models.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 11B §14 — the projection table, pinned as a total function of the durable status.
 *
 * The projection is the only thing the UI ever sees, so it must be derivable, never invented: the
 * same durable status always produces the same [RatingCommitUiState], and no projection exists
 * that a durable status cannot produce. `AwaitingRating` is what "no transaction" looks like —
 * there is no durable status for it (INV-11B-05).
 */
class RatingCommitUiStateTest {

    private val backendId = AnkiBackendId.Fake()
    private val commitId = ReviewCommitId(backendId, "study-1", ReviewTurnId("turn-1"))
    private val request = CommitRatingRequest(
        commitId, AnkiCardRef(backendId, cardId = "c1"), Rating.GOOD, ratedAtEpochMs = 1_000L
    )

    private fun commit(status: ReviewCommitStatus) = AnkiRatingCommit(request, status)

    @Test fun `no durable record projects to AwaitingRating`() {
        assertEquals(RatingCommitUiState.AwaitingRating, null.projectForUi(commitId, Rating.GOOD))
        assertEquals(RatingCommitUiState.AwaitingRating, (null as AnkiRatingCommit?).ratingCommitUiState())
        assertTrue(RatingCommitUiState.AwaitingRating.ratingControlsEnabled)
    }

    @Test fun `the projection table is a total function of the durable status`() {
        val table = mapOf(
            ReviewCommitStatus.PREPARED to RatingCommitUiState.Saving(commitId),
            ReviewCommitStatus.SUBMITTING to RatingCommitUiState.Saving(commitId),
            ReviewCommitStatus.RETRY_ALLOWED to RatingCommitUiState.RetryAvailable(commitId),
            ReviewCommitStatus.AMBIGUOUS to RatingCommitUiState.VerificationRequired(commitId),
            ReviewCommitStatus.COMMITTED to RatingCommitUiState.Saved(commitId, Rating.GOOD)
        )
        // Every durable status has exactly one projection, and the projection is the same whether
        // it is read off the record or off the study-level view of it.
        for ((status, expected) in table) {
            assertEquals(expected, status.projectForUi(commitId, Rating.GOOD))
            assertEquals(expected, commit(status).commitUiState)
        }
        assertEquals(ReviewCommitStatus.entries.size, table.size)
    }

    @Test fun `only AwaitingRating leaves the rating controls live`() {
        for (status in ReviewCommitStatus.entries) {
            val projection = commit(status).commitUiState
            assertFalse("$status must disable the rating controls", projection.ratingControlsEnabled)
        }
    }

    @Test fun `a saved projection carries the committed rating, a saving one does not`() {
        val saving = commit(ReviewCommitStatus.SUBMITTING)
        assertNull(saving.committedRating)
        assertEquals(Rating.GOOD, saving.selectedRating)
        val saved = commit(ReviewCommitStatus.COMMITTED)
        assertEquals(Rating.GOOD, saved.committedRating)
        assertEquals(RatingCommitUiState.Saved(commitId, Rating.GOOD), saved.commitUiState)
    }
}
