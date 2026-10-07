package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.BackendCommitResult
import com.studyagent.client.core.anki.MutationBoundary
import com.studyagent.client.core.models.Rating
import com.studyagent.client.data.anki.ankidroid.AnkiDroidAnswerDispatch
import com.studyagent.client.data.anki.ankidroid.AnkiDroidCommitResultClassifier
import com.studyagent.client.data.anki.ankidroid.toAnkiDroidEase
import org.junit.Assert.assertEquals
import org.junit.Test

/** GATE 11C — boundary classification and the audited provider mapping. */
class AnkiDroidCommitClassificationTest {

    @Test
    fun `the audited mapper covers every domain rating exactly once`() {
        assertEquals(listOf(1, 2, 3, 4), Rating.entries.map { it.toAnkiDroidEase() })
    }

    @Test
    fun `an exception before provider entry is proven not committed`() {
        val result = AnkiDroidCommitResultClassifier.unexpected(
            MutationBoundary.BEFORE_CALL,
            detail = "validation"
        )

        assertEquals(
            BackendCommitResult.ConfirmedNotCommitted(AnkiError.Unknown("pre_mutation_validation")),
            result
        )
    }

    @Test
    fun `an exception after provider entry is outcome unknown`() {
        val result = AnkiDroidCommitResultClassifier.unexpected(
            MutationBoundary.AFTER_CALL_ENTERED,
            detail = "binder"
        )

        assertEquals(
            BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("post_mutation_binder")),
            result
        )
    }

    @Test
    fun `a provider refusal marked not dispatched is retryable evidence`() {
        val result = AnkiDroidCommitResultClassifier.dispatch(
            AnkiDroidAnswerDispatch.NotDispatched(AnkiError.PermissionRequired())
        )

        assertEquals(
            BackendCommitResult.ConfirmedNotCommitted(AnkiError.PermissionRequired()),
            result
        )
    }

    @Test
    fun `a returned row count without scheduler evidence stays unknown`() {
        val result = AnkiDroidCommitResultClassifier.synchronousAnswer(
            AnkiDroidAnswerDispatch.Returned(1),
            verdict = com.studyagent.client.data.anki.ankidroid.AnkiDroidCommitVerifier.Verdict.Unchanged(
                "no_state_change_observed"
            ),
            inFilteredDeck = false
        )

        assertEquals(
            BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("answer_not_confirmed")),
            result
        )
    }

    @Test
    fun `zero or remote failure rows never confirm a scheduler answer`() {
        val evidence = com.studyagent.client.data.anki.ankidroid.AnkiDroidCommitVerifier.Verdict.ConsistentWithAnswer(
            "reps_plus_one_in_window"
        )
        listOf(0, -1, 2).forEach { rowCount ->
            assertEquals(
                BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("answer_outcome_unavailable")),
                AnkiDroidCommitResultClassifier.synchronousAnswer(
                    AnkiDroidAnswerDispatch.Returned(rowCount), evidence, inFilteredDeck = false
                )
            )
        }
    }

    @Test
    fun `only a normal one-row answer with consistent evidence confirms`() {
        val result = AnkiDroidCommitResultClassifier.synchronousAnswer(
            AnkiDroidAnswerDispatch.Returned(1),
            com.studyagent.client.data.anki.ankidroid.AnkiDroidCommitVerifier.Verdict.ConsistentWithAnswer(
                "reps_plus_one_in_window"
            ),
            inFilteredDeck = false
        )

        assertEquals(BackendCommitResult.ConfirmedCommitted(), result)
    }

    @Test
    fun `filtered deck evidence remains fail closed even when it looks like a normal answer`() {
        val result = AnkiDroidCommitResultClassifier.synchronousAnswer(
            AnkiDroidAnswerDispatch.Returned(1),
            com.studyagent.client.data.anki.ankidroid.AnkiDroidCommitVerifier.Verdict.ConsistentWithAnswer(
                "reps_plus_one_in_window"
            ),
            inFilteredDeck = true
        )

        assertEquals(
            BackendCommitResult.OutcomeUnknown(AnkiError.Unknown("filtered_deck_result_unverified")),
            result
        )
    }
}
