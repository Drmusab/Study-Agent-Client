package com.studyagent.client.data.preferences

import com.studyagent.client.core.anki.DecodedSessionLock
import com.studyagent.client.core.anki.StudySessionBackendLock
import com.studyagent.client.core.anki.StudySessionLockCodec
import com.studyagent.client.core.anki.StudySessionLockStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * GATE 21G — the production [StudySessionLockStore] over DataStore preferences.
 *
 * The lock is one JSON string under `study_session_backend_lock` (identity only — see
 * [StudySessionBackendLock]). Encoding and validation belong to [StudySessionLockCodec]; this
 * class only moves bytes between DataStore and the codec, and reports write failures honestly
 * (`false`) so the executor can refuse a start it cannot own.
 *
 * Constructor takes flows and writer lambdas (not the DataStore itself) so the byte-moving
 * contract is unit-testable without Android.
 */
class StudySessionLockDataStore(
    private val lockFlow: Flow<String?>,
    private val saveRaw: suspend (String) -> Boolean,
    private val clearRaw: suspend (String) -> Boolean
) : StudySessionLockStore {

    override suspend fun save(lock: StudySessionBackendLock): Boolean =
        saveRaw(StudySessionLockCodec.encode(lock))

    override suspend fun load(): DecodedSessionLock? =
        StudySessionLockCodec.decode(lockFlow.first())

    override suspend fun clear(studySessionId: String): Boolean =
        clearRaw(studySessionId)

    companion object {
        /** Wire the store to the app's [PreferencesDataStore]. */
        fun fromPreferences(store: PreferencesDataStore): StudySessionLockStore =
            StudySessionLockDataStore(
                lockFlow = store.studySessionBackendLockFlow,
                saveRaw = store::saveStudySessionBackendLock,
                clearRaw = store::clearStudySessionBackendLock
            )
    }
}
