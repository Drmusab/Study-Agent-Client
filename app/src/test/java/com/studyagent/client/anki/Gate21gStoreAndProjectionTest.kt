package com.studyagent.client.anki

import com.studyagent.client.anki.fake.FakeAnkiBackend
import com.studyagent.client.core.anki.AnkiBackendId
import com.studyagent.client.core.anki.AnkiBackendKind
import com.studyagent.client.core.anki.AnkiBackendMode
import com.studyagent.client.core.anki.AnkiCollectionIdentity
import com.studyagent.client.core.anki.AnkiDeckRef
import com.studyagent.client.core.anki.AnkiError
import com.studyagent.client.core.anki.DecodedSessionLock
import com.studyagent.client.core.anki.InMemoryStudySessionLockStore
import com.studyagent.client.core.anki.StudySessionBackendLock
import com.studyagent.client.core.anki.StudySessionLockCodec
import com.studyagent.client.core.study.AnkiStudyInteraction
import com.studyagent.client.core.study.AnkiStudyRequest
import com.studyagent.client.core.study.SessionMachineState
import com.studyagent.client.core.study.SessionPhase
import com.studyagent.client.core.study.SessionProblem
import com.studyagent.client.core.study.StudySessionOwnershipProjection
import com.studyagent.client.core.study.projectSessionOwnership
import com.studyagent.client.data.preferences.StudySessionLockDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 21G — the durable lock stores and the read-only ownership projection (VER-21G-30…34).
 *
 * The stores move identity bytes without interpreting them; the projection reports the lock for
 * diagnostics, never the preference, and carries no secrets.
 */
class Gate21gStoreAndProjectionTest {

    private val local = AnkiBackendId.AnkiDroidLocal

    private fun lock(session: String = "s1"): StudySessionBackendLock = StudySessionBackendLock(
        studySessionId = session,
        backendId = local,
        collection = AnkiCollectionIdentity(local, "c1"),
        deckRef = AnkiDeckRef(local, "1", "c1"),
        startedAtEpochMs = 100L
    )

    /** The projection reads the live tracker entry, so tests hand it the rebuilt context. */
    private fun liveContext(session: String = "s1") =
        lock(session).toSessionContext(FakeAnkiBackend.REVIEW_CAPABILITIES)

    // ------------------------------------------------------------------ the in-memory store

    @Test fun `the in-memory store round-trips the encoded lock`() = runTest {
        val store = InMemoryStudySessionLockStore()
        assertNull(store.load())
        assertTrue(store.save(lock()))
        assertEquals(DecodedSessionLock.Valid(lock()), store.load())
    }

    @Test fun `clear drops only the named session`() = runTest {
        val store = InMemoryStudySessionLockStore()
        store.save(lock("s1"))
        assertTrue(store.clear("s2"))
        assertEquals("s1", (store.load() as DecodedSessionLock.Valid).lock.studySessionId)
        assertTrue(store.clear("s1"))
        assertNull(store.load())
    }

    // ------------------------------------------------------------ the datastore-backed store

    @Test fun `the datastore adapter saves encodable bytes`() = runTest {
        var saved: String? = null
        val store = StudySessionLockDataStore(
            lockFlow = MutableStateFlow(null),
            saveRaw = { raw -> saved = raw; true },
            clearRaw = { true }
        )
        assertTrue(store.save(lock()))
        assertEquals(DecodedSessionLock.Valid(lock()), StudySessionLockCodec.decode(saved))
    }

    @Test fun `the datastore adapter loads through the flow`() = runTest {
        val flow = MutableStateFlow<String?>(null)
        val store = StudySessionLockDataStore(
            lockFlow = flow,
            saveRaw = { true },
            clearRaw = { true }
        )
        assertNull(store.load())
        flow.value = StudySessionLockCodec.encode(lock("s7"))
        assertEquals("s7", (store.load() as DecodedSessionLock.Valid).lock.studySessionId)
    }

    @Test fun `the datastore adapter passes the cleared session through`() = runTest {
        var cleared: String? = null
        val store = StudySessionLockDataStore(
            lockFlow = MutableStateFlow(null),
            saveRaw = { true },
            clearRaw = { session -> cleared = session; true }
        )
        assertTrue(store.clear("s3"))
        assertEquals("s3", cleared)
    }

    // ------------------------------------------------------- the ownership projection (21M)

    @Test fun `an idle machine without a lock projects empty ownership`() {
        val projection = projectSessionOwnership(SessionMachineState.initial(), null)
        assertNull(projection.studySessionId)
        assertNull(projection.lockedBackendId)
        assertNull(projection.backendKind)
        assertNull(projection.collectionState)
        assertEquals("Idle", projection.lifecycleState)
        assertNull(projection.blockedReason)
        assertEquals(false, projection.hasLock)
    }

    @Test fun `the projection reports the lock, never the preference`() {
        // The request asked for the PC agent; the session bound the on-device backend. The
        // projection must show what owns the session, not what was asked for.
        val request = AnkiStudyRequest("s1", AnkiBackendMode.PC_AGENT, AnkiDeckRef(local, "1"))
        val state = SessionMachineState.initial().copy(
            anki = AnkiStudyInteraction(request),
            phase = SessionPhase.WaitingForRating
        )
        val projection = projectSessionOwnership(state, liveContext())
        assertEquals("s1", projection.studySessionId)
        assertEquals(local, projection.lockedBackendId)
        assertEquals(AnkiBackendKind.ANKIDROID_LOCAL, projection.backendKind)
        assertEquals(
            StudySessionOwnershipProjection.COLLECTION_KEY_PRESENT, projection.collectionState
        )
        assertEquals("WaitingForRating", projection.lifecycleState)
        assertNull(projection.blockedReason)
        assertTrue(projection.hasLock)
    }

    @Test fun `an unknown collection projects as unknown`() {
        val request = AnkiStudyRequest("s1", AnkiBackendMode.AUTO, AnkiDeckRef(local, "1"))
        val state = SessionMachineState.initial().copy(anki = AnkiStudyInteraction(request))
        val projection = projectSessionOwnership(state, liveContext().copy(collection = null))
        assertEquals(
            StudySessionOwnershipProjection.COLLECTION_UNKNOWN, projection.collectionState
        )
    }

    @Test fun `a held failure and a prior-commit block project as blocked reasons`() {
        val request = AnkiStudyRequest("s1", AnkiBackendMode.AUTO, AnkiDeckRef(local, "1"))
        val failed = SessionMachineState.initial().copy(
            anki = AnkiStudyInteraction(request, failure = AnkiError.InvalidRequest("x"))
        )
        assertEquals("failure_invalidrequest", projectSessionOwnership(failed, liveContext()).blockedReason)

        val blocked = SessionMachineState.initial().copy(
            anki = AnkiStudyInteraction(request, blockedByPriorCommit = true)
        )
        assertEquals(
            "blocked_by_prior_commit", projectSessionOwnership(blocked, liveContext()).blockedReason
        )

        val errored = SessionMachineState.initial().copy(
            anki = AnkiStudyInteraction(request),
            phase = SessionPhase.Error(SessionProblem.NETWORK_LOST)
        )
        assertEquals("error_network_lost", projectSessionOwnership(errored, liveContext()).blockedReason)
    }

    @Test fun `the projection never leaks the collection key value`() {
        val request = AnkiStudyRequest("s1", AnkiBackendMode.AUTO, AnkiDeckRef(local, "1"))
        val state = SessionMachineState.initial().copy(anki = AnkiStudyInteraction(request))
        val keyed = liveContext().copy(
            collection = AnkiCollectionIdentity(local, "secret-key-9"),
            deckRef = AnkiDeckRef(local, "1", "secret-key-9")
        )
        val projection = projectSessionOwnership(state, keyed)
        assertEquals(
            StudySessionOwnershipProjection.COLLECTION_KEY_PRESENT, projection.collectionState
        )
        assertFalse(projection.toString().contains("secret-key-9"))
    }
}
