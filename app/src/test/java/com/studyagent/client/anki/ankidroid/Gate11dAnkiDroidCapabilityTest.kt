package com.studyagent.client.anki.ankidroid

import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.CommitSemantics
import com.studyagent.client.core.anki.CommitGuaranteeLevel
import com.studyagent.client.core.anki.ReconciliationSupport
import com.studyagent.client.data.anki.ankidroid.AnkiDroidBackend
import com.studyagent.client.data.anki.ankidroid.AnkiDroidIntegrationState
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidCardGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidDeckGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidGateway
import com.studyagent.client.data.anki.ankidroid.FakeAnkiDroidReviewGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GATE 11D PART IV — the AnkiDroid reconciliation capability report, pinned to the audited
 * v2.24.1 source (commit 9f579c10bb151146728220729c510acbbd8faba7; full audit in
 * `docs/GATE_11C_ANKIDROID_COMMIT_AUDIT.md`).
 *
 * The seven audited questions, answered by the pinned provider surface:
 *
 * | # | Question | Answer |
 * |---|---|---|
 * | 1 | Can it be queried by ReviewCommitId? | **No** — `FlashcardsContract` has no ReviewCommitId column; the schedule URI accepts only `note_id`/`ord`/`answer_ease`/`time_taken`. |
 * | 2 | Does it deduplicate a repeated commit? | **No** — `CardContentProvider.update` applies the schedule unconditionally; there is no dedup table. |
 * | 3 | Can it prove a commit AFTER the response was lost? | **No** — `answerCard` swallows scheduler exceptions and still reports one updated row; a lost response is indistinguishable from a lost review. |
 * | 4 | Can it prove a NON-commit? | **No** — there is no pre-write marker; "no change observed" is not "the write did not happen". |
 * | 5 | Can it distinguish external reviews (user reviewed in Anki itself)? | **No** — reps/interval/due/next-card/last-review-time are observable but cannot be attributed to a `ReviewCommitId`; they are hints, never proof. |
 * | 6 | Does it expose a strong collection identity? | **Partially** — the provider exposes the collection via the database, but no transaction-scoped identity that survives the write. |
 * | 7 | Can a missing card be interpreted transactionally? | **No** — card deletion proves nothing about whether the review was applied. |
 *
 * Therefore the backend reports [ReconciliationSupport.UNSUPPORTED]: no provider call is issued
 * for reconciliation, weak scheduler heuristics are never trusted, and an AnkiDroid commit stays
 * AT_MOST_ONCE_FAIL_CLOSED (GATE 11C's conclusion, re-verified here against the live adapter).
 */
class Gate11dAnkiDroidCapabilityTest {

    private fun backend() = AnkiDroidBackend(
        gateway = FakeAnkiDroidGateway(stateToReturn = AnkiDroidIntegrationState.initial(0L)),
        scope = CoroutineScope(Dispatchers.Unconfined),
        deckGateway = FakeAnkiDroidDeckGateway(),
        reviewGateway = FakeAnkiDroidReviewGateway(),
        cardGateway = FakeAnkiDroidCardGateway()
    )

    @Test fun `capability report — reconciliation is UNSUPPORTED on the pinned provider surface`() {
        val backend = backend()
        assertEquals(ReconciliationSupport.UNSUPPORTED, backend.reconciliationSupport())
    }

    @Test fun `capability report — the guarantee stays clamped to AT_MOST_ONCE_FAIL_CLOSED`() {
        val backend = backend()
        assertEquals(CommitSemantics.ANKIDROID, backend.commitSemantics)
        assertEquals(CommitGuaranteeLevel.AT_MOST_ONCE_FAIL_CLOSED, backend.commitSemantics.guaranteeLevel)
        assertEquals(false, backend.commitSemantics.supportsAuthoritativeReconciliation)
        assertEquals(false, backend.commitSemantics.supportsIdempotentReplay)
    }

    @Test fun `capability report — the identity is the AnkiDroid-local backend, never a PC profile`() {
        val backend = backend()
        assertEquals(AnkiBackendId.AnkiDroidLocal, backend.id)
        assertEquals("ankidroid_local", backend.id.stableId)
    }

    @Test fun `capability report — UNSUPPORTED means the reconciler must not call the provider`() {
        // The AnkiReviewCommitReconciler gate is exercised end-to-end in
        // Gate11dRecoveryAuditTest.`ver4 …` (zero reconcileCalls for an AnkiDroidLocal record).
        // Here the capability itself is asserted at the source: a capability that cannot be
        // upgraded mid-transaction and cannot be faked by a scheduler observation.
        val backend = backend()
        val support = backend.reconciliationSupport()
        assertEquals("ordinal order is capability order: UNSUPPORTED is the weakest state",
            ReconciliationSupport.UNSUPPORTED, ReconciliationSupport.values().first())
        assertEquals(support, ReconciliationSupport.values().first())
    }
}
