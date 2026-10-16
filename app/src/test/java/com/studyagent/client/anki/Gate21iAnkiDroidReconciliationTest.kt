package com.studyagent.client.anki

import com.studyagent.client.core.anki.AnkiCardRef
import com.studyagent.client.core.anki.ReconcileReviewerActionRequest
import com.studyagent.client.core.anki.ReviewerAction
import com.studyagent.client.core.anki.ReviewerActionId
import com.studyagent.client.core.anki.ReviewerActionReconciliationResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GATE 21I regression: AnkiDroid card state is not a transaction-correlated historical receipt. */
class Gate21iAnkiDroidReconciliationTest {

    @Test
    fun `AnkiDroid does not advertise or perform historical action reconciliation`() = runTest {
        val chain = Gate21dAnkiDroidChain(backgroundScope)
        val backend = chain.backend
        val queriesBefore = chain.providerClient.queryLog.size
        val updatesBefore = chain.providerClient.updateLog.size

        listOf(ReviewerAction.BuryCard, ReviewerAction.SuspendCard).forEach { action ->
            val semantics = backend.reviewerActionSemantics(action)
            assertTrue("the audited state-setting retry claim remains", semantics.supportsIdempotentReplay)
            assertFalse("current state is not an authoritative historical receipt",
                semantics.supportsAuthoritativeReconciliation)

            val request = ReconcileReviewerActionRequest(
                actionId = ReviewerActionId("historical-${action.key}"),
                cardRef = AnkiCardRef(
                    backendId = backend.id,
                    cardId = "42",
                    noteId = "17",
                    cardOrd = 0
                ),
                action = action,
                submittedAtEpochMs = 1_000L,
                windowEndEpochMs = 2_000L
            )
            val result = backend.reconcileReviewerAction(request)
            assertTrue("historical action stays unresolved",
                result is ReviewerActionReconciliationResult.Unresolved)
        }

        assertEquals("no provider query may be used as historical proof",
            queriesBefore, chain.providerClient.queryLog.size)
        assertEquals("no mutation is issued during reconciliation",
            updatesBefore, chain.providerClient.updateLog.size)
    }
}
