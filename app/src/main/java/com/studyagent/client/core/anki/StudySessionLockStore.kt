package com.studyagent.client.core.anki

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GATE 21G — durable ownership of the active StudySession (CHECKPOINT 21G-06).
 *
 * The store holds at most one lock: the app admits at most one active StudySession (the reducer
 * rejects a second `Start` while one is active), so the persisted owner is singular by
 * construction. Implementations persist the codec bytes, never a live backend object.
 *
 * Saving overwrites any previous lock (a new binding replaces the old one atomically);
 * [clear] removes the lock only when it names [studySessionId], so ending one session can never
 * delete another session's ownership.
 */
interface StudySessionLockStore {
    /** Persist [lock] as the active session's owner. `false` = not durable, fail the start. */
    suspend fun save(lock: StudySessionBackendLock): Boolean

    /** The persisted owner: `null` = no session was persisted, never a guessed lock. */
    suspend fun load(): DecodedSessionLock?

    /**
     * Drop the persisted lock, but only when it names [studySessionId]. Returns `true` when
     * nothing remains for that session (including when nothing was stored at all).
     */
    suspend fun clear(studySessionId: String): Boolean
}

/**
 * Process-local [StudySessionLockStore]. Bytes round-trip through [StudySessionLockCodec] on
 * every write and read, so tests exercise the persisted representation rather than object
 * identity. Production uses the DataStore implementation; this one is the deterministic
 * substitute and the harness fixture.
 */
class InMemoryStudySessionLockStore : StudySessionLockStore {
    private val mutex = Mutex()
    private var raw: String? = null

    override suspend fun save(lock: StudySessionBackendLock): Boolean = mutex.withLock {
        raw = StudySessionLockCodec.encode(lock)
        true
    }

    override suspend fun load(): DecodedSessionLock? = mutex.withLock {
        StudySessionLockCodec.decode(raw)
    }

    override suspend fun clear(studySessionId: String): Boolean = mutex.withLock {
        val current = StudySessionLockCodec.decode(raw)
        val stored = (current as? DecodedSessionLock.Valid)?.lock
        if (stored != null && stored.studySessionId != studySessionId) {
            // Another session's lock: not ours to delete. Still a success — the named session
            // holds no lock here, and nothing else was touched.
            return true
        }
        raw = null
        true
    }
}
