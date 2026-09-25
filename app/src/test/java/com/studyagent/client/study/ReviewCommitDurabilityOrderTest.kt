package com.studyagent.client.study

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.InMemoryReviewCommitStore
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.study.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * GATE 11 — durability-order evidence (§398/§404).
 *
 * One ordered trace interleaves three independent observers: every durable ledger write (decoded
 * from the store), every backend call made by the real executor, and the fake's physical mutation
 * counter. The asserted sequence *is* the durability contract:
 *
 *   intent durable → PREPARED durable → CALL_ENTERED durable → physical mutation →
 *   RESPONSE_RECEIVED durable → COMMITTED durable → next card requested
 */
class ReviewCommitDurabilityOrderTest {

    /** Delegating backend that stamps each executor call, and the mutation boundary, into the trace. */
    private class TracingBackend(
        private val inner: FakeAnkiBackend,
        private val trace: MutableList<String>
    ) : AnkiBackend by inner {
        override suspend fun nextCard(session: AnkiReviewSession): NextCardResult {
            trace += "backend:nextCard"
            return inner.nextCard(session)
        }

        override suspend fun commitRating(request: CommitRatingRequest, mutationEntry: suspend () -> Boolean): CommitRatingResult {
            trace += "backend:commitRating(enter)"
            val result = inner.commitRating(request) {
                trace += "boundary:mutationEntry-requested"
                val allowed = mutationEntry()
                trace += "boundary:mutationEntry=$allowed physical=${inner.physicalCommitCalls}"
                allowed
            }
            trace += "backend:commitRating(return) physical=${inner.physicalCommitCalls}"
            return result
        }
    }

    private fun tracing(store: InMemoryReviewCommitStore, trace: MutableList<String>) {
        store.onDurableWrite = { snapshot ->
            val record = (ReviewCommitLedgerCodec.decode(snapshot) as ReviewCommitLedgerCodec.Decoded.Records)
                .records.lastOrNull()
            trace += "durable:${record?.state}/${record?.phase}"
        }
    }

    @Test fun `commit durability order is write-before-effect and committed-before-next-card`() = runTest {
        val trace = mutableListOf<String>()
        val store = InMemoryReviewCommitStore()
        val h = AnkiCommitHarness(store = store, wrap = { TracingBackend(it, trace) })
        h.loadToRating()
        tracing(store, trace)
        trace.clear()

        val rate = h.rate()
        assertTrue(rate.accepted)
        // The reducer asks for exactly one commit and never for the next card in the same result.
        assertEquals(1, rate.effects.count { it is AnkiStudyEffect.CommitRating })
        assertFalse(rate.effects.any { it is AnkiStudyEffect.Next })
        h.drain()

        assertEquals(listOf(
            "durable:NOT_STARTED/null",                        // intent row before anything else
            "durable:SUBMITTING/PREPARED",                     // claim (CAS) before the backend call
            "backend:commitRating(enter)",
            "boundary:mutationEntry-requested",
            "durable:SUBMITTING/MUTATION_CALL_ENTERED",        // durable before the effect
            "boundary:mutationEntry=true physical=0",          // effect not yet applied
            "backend:commitRating(return) physical=1",         // exactly one physical effect
            "durable:SUBMITTING/MUTATION_RESPONSE_RECEIVED",   // response durable…
            "durable:COMMITTED/LOCAL_RESULT_PERSISTED",        // …then terminal
            "backend:nextCard"                                 // only now may the session advance
        ), trace)
        assertEquals("B", h.turn!!.cardRef.cardId)
        assertEquals(1, h.fake.physicalCommitCalls)
    }

    @Test fun `a failed mutation-entry write means the provider is never touched`() = runTest {
        val trace = mutableListOf<String>()
        val store = InMemoryReviewCommitStore()
        // Right after the claim is durable, make the next write (MUTATION_CALL_ENTERED) fail.
        val failEntryWrite = CommitFaultInjector { point ->
            if (point == CommitFaultPoint.AFTER_PREPARED) store.failNextWrites = 1
        }
        val h = AnkiCommitHarness(store = store, faults = failEntryWrite, wrap = { TracingBackend(it, trace) })
        h.loadToRating()
        tracing(store, trace)
        trace.clear()
        h.rate()
        h.drain()
        assertEquals(0, h.fake.physicalCommitCalls)
        assertTrue(trace.toString(), trace.contains("boundary:mutationEntry=false physical=0"))
        assertFalse(trace.contains("durable:SUBMITTING/MUTATION_CALL_ENTERED"))
        assertFalse(trace.contains("backend:nextCard"))
        assertEquals("A", h.turn!!.cardRef.cardId)
    }
}
