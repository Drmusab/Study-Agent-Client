package com.studyagent.client.study

import com.studyagent.client.core.anki.CommitFaultException
import com.studyagent.client.core.anki.CommitFaultPoint
import com.studyagent.client.core.anki.CommitGuaranteeLevel
import com.studyagent.client.core.anki.CommitSemantics
import com.studyagent.client.core.anki.PcRatingReplayPolicy
import com.studyagent.client.core.anki.ReviewCommitState
import com.studyagent.client.core.anki.ThrowingCommitFault
import com.studyagent.client.core.anki.commitSemanticsFromAgent
import com.studyagent.client.core.models.AgentCapabilities
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crash windows are modeled by an injected fault plus a ledger restart, not by sleeping.
 * A restarted ledger is a new process: in-memory results must not authorize a second mutation.
 */
class ReviewCommitCrashWindowTest {

    @Test fun `crash after call entered and before the provider does not resubmit`() = runTest {
        val h = AnkiCommitHarness(faults = ThrowingCommitFault(CommitFaultPoint.AFTER_CALL_ENTERED))
        h.loadToRating()
        h.rate()
        val effect = h.pending.removeFirst()
        try {
            h.run(effect)
            error("injected crash should leave the executor")
        } catch (fault: CommitFaultException) {
            assertEquals(CommitFaultPoint.AFTER_CALL_ENTERED, fault.point)
        }
        assertEquals(0, h.fake.physicalCommitCalls)
        h.restartLedger()
        val recovered = h.ledger.snapshot().single()
        assertEquals(ReviewCommitState.AMBIGUOUS, recovered.state)
        assertFalse(recovered.safeToRetry)
        h.pending.clear()
        h.drain()
        assertEquals(0, h.fake.physicalCommitCalls)
    }

    @Test fun `crash after the provider returns and before the response is durable stays ambiguous`() = runTest {
        val h = AnkiCommitHarness(faults = ThrowingCommitFault(CommitFaultPoint.AFTER_PROVIDER_MUTATION))
        h.loadToRating()
        h.rate()
        val effect = h.pending.removeFirst()
        try {
            h.run(effect)
            error("injected crash should leave the executor")
        } catch (fault: CommitFaultException) {
            assertEquals(CommitFaultPoint.AFTER_PROVIDER_MUTATION, fault.point)
        }
        assertEquals(1, h.fake.physicalCommitCalls)
        h.restartLedger()
        val recovered = h.ledger.snapshot().single()
        assertEquals(ReviewCommitState.AMBIGUOUS, recovered.state)
        assertFalse(recovered.safeToRetry)
        h.pending.clear()
        h.drain()
        assertEquals("restart must not send a second scheduler mutation", 1, h.fake.physicalCommitCalls)
    }

    @Test fun `crash before the ledger record exists does not call the provider`() = runTest {
        val h = AnkiCommitHarness(faults = ThrowingCommitFault(CommitFaultPoint.BEFORE_LEDGER_CREATE))
        h.loadToRating()
        h.rate()
        val effect = h.pending.removeFirst()
        try {
            h.run(effect)
            error("injected crash should leave the executor")
        } catch (fault: CommitFaultException) {
            assertEquals(CommitFaultPoint.BEFORE_LEDGER_CREATE, fault.point)
        }
        assertEquals(0, h.fake.physicalCommitCalls)
        assertTrue(h.ledger.snapshot().isEmpty())
    }

    @Test fun `pc capabilities do not upgrade a rating into an automatic replay`() {
        assertFalse(PcRatingReplayPolicy.automaticReplayAllowed(null))
        assertFalse(PcRatingReplayPolicy.automaticReplayAllowed(CommitSemantics.UNVERIFIED))
        assertFalse(PcRatingReplayPolicy.automaticReplayAllowed(CommitSemantics.ANKIDROID))
        val missing = commitSemanticsFromAgent(null)
        assertEquals(CommitSemantics.UNVERIFIED, missing)
        val both = commitSemanticsFromAgent(AgentCapabilities.fromStrings(setOf(
            "review_commit_idempotency", "commit_reconciliation"
        )))
        assertEquals(CommitGuaranteeLevel.IDEMPOTENT_REPLAY_SUPPORTED, both.guaranteeLevel)
        assertFalse(both.guaranteeLevel == CommitGuaranteeLevel.END_TO_END_EXACTLY_ONCE)
        val id = PcRatingReplayPolicy.logicalCommitId("session", "turn-1")
        assertFalse(id.contains("good"))
        assertFalse(id.contains("again"))
    }
}
