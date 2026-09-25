package com.studyagent.client.anki

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.anki.fake.FakeBackendCommitStore
import com.studyagent.client.core.anki.*
import com.studyagent.client.core.models.Rating
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Logical commit, delivery/attempt and backend scheduler effects are deliberately distinct. */
class ReviewCommitBackendContractTest {
    private fun fake(level: CommitGuaranteeLevel, store: FakeBackendCommitStore = FakeBackendCommitStore()) =
        FakeAnkiBackend(fakeId, listOf(deck()), listOf(card("A")), instanceId = "contract",
            guaranteeLevel = level, persistedEffectStore = store)

    private suspend fun request(backend: FakeAnkiBackend): CommitRatingRequest {
        val session = backend.begin()
        val turn = (backend.nextCard(session) as NextCardResult.Card).turn
        return turn.request()
    }

    @Test fun `default fake models serialized at-most-once and accurate counters`() = runTest {
        val backend = fake(CommitGuaranteeLevel.AT_MOST_ONCE_FAIL_CLOSED)
        val r = request(backend)
        assertEquals(CommitGuaranteeLevel.AT_MOST_ONCE_FAIL_CLOSED, backend.commitSemantics.guaranteeLevel)
        assertFalse(backend.commitSemantics.supportsIdempotentReplay)
        assertTrue(backend.commitRating(r) is CommitRatingResult.Committed)
        assertTrue(backend.commitRating(r) is CommitRatingResult.Committed)
        assertEquals(1, backend.logicalCommitCount)
        assertEquals(2, backend.deliveryCount)
        assertEquals(1, backend.physicalCommitCalls)
        assertEquals(1, backend.backendEffectCount)
        assertTrue(backend.commitRating(r.copy(rating = Rating.HARD)) is CommitRatingResult.Rejected)
    }

    @Test fun `local dedup only backend can apply repeated delivery twice but ledger must stop it`() = runTest {
        val backend = fake(CommitGuaranteeLevel.LOCAL_DEDUP_ONLY)
        val r = request(backend)
        assertEquals(CommitGuaranteeLevel.LOCAL_DEDUP_ONLY, backend.commitSemantics.guaranteeLevel)
        backend.commitRating(r)
        backend.commitRating(r)
        assertEquals(1, backend.logicalCommitCount)
        assertEquals(2, backend.physicalCommitCalls)
        assertEquals(2, backend.backendEffectCount)
    }

    @Test fun `ambiguous post-effect failure is never auto-retried on at-most-once backend`() = runTest {
        val backend = fake(CommitGuaranteeLevel.AT_MOST_ONCE_FAIL_CLOSED)
        val r = request(backend)
        backend.commitThrowable = IllegalStateException("lost response")
        backend.applyBeforeThrow = true
        try { backend.commitRating(r); fail("expected lost response") } catch (_: IllegalStateException) {}
        assertTrue(backend.commitRating(r) is CommitRatingResult.Ambiguous)
        assertEquals(1, backend.physicalCommitCalls)
        assertEquals(1, backend.backendEffectCount)
    }

    @Test fun `idempotent fake replays the same logical id after response loss without another effect`() = runTest {
        val store = FakeBackendCommitStore()
        val backend = fake(CommitGuaranteeLevel.IDEMPOTENT_REPLAY_SUPPORTED, store)
        val r = request(backend)
        backend.commitThrowable = IllegalStateException("response lost")
        backend.applyBeforeThrow = true
        try { backend.commitRating(r); fail("expected lost response") } catch (_: IllegalStateException) {}
        val recreated = fake(CommitGuaranteeLevel.IDEMPOTENT_REPLAY_SUPPORTED, store)
        // The fake backend was rebuilt with the shared effect table; ReviewCommitId alone
        // correlates the scheduler effect (this in-memory simulation is not PC server proof).
        assertEquals(CommitGuaranteeLevel.IDEMPOTENT_REPLAY_SUPPORTED, recreated.commitSemantics.guaranteeLevel)
        assertTrue(recreated.commitSemantics.supportsIdempotentReplay)
        assertTrue(recreated.commitRating(r) is CommitRatingResult.Committed)
        assertEquals(1, recreated.logicalCommitCount)
        assertEquals(1, recreated.backendEffectCount)
        assertEquals(0, recreated.physicalCommitCalls)
        assertTrue(recreated.commitRating(r.copy(rating = Rating.EASY)) is CommitRatingResult.Rejected)
        assertEquals(1, recreated.backendEffectCount)
    }
}
