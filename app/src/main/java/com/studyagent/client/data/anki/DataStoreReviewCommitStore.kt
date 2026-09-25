package com.studyagent.client.data.anki

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.studyagent.client.core.anki.ReviewCommitStore
import com.studyagent.client.core.anki.ReviewCommitStoreRead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/**
 * GATE 11 — the durable [ReviewCommitStore], on the persistence library the app already uses.
 *
 * DataStore gives what the ledger needs: `edit` is a serialized read-modify-write whose new file
 * is written, synced and atomically renamed before `edit` returns, so after process death the
 * snapshot is either the old one or the new one — never half of each.
 *
 * The file is excluded from backup and device transfer. It is deliberately *without* a replace-on-corruption handler: the settings
 * store resets to defaults when its file is corrupt, which here would silently forget AMBIGUOUS
 * commits. A corrupt ledger file surfaces as [ReviewCommitStoreRead.Unreadable], and the ledger fails closed
 * (commits refused before dispatch) instead of starting empty.
 */
class DataStoreReviewCommitStore(private val dataStore: DataStore<Preferences>) : ReviewCommitStore {

    override suspend fun read(): ReviewCommitStoreRead = try {
        ReviewCommitStoreRead.Snapshot(dataStore.data.first()[SNAPSHOT])
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // CorruptionException / IOException: fail closed, never "empty".
        ReviewCommitStoreRead.Unreadable(error::class.java.simpleName)
    }

    override suspend fun write(snapshot: String): Boolean = try {
        dataStore.edit { preferences -> preferences[SNAPSHOT] = snapshot }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    companion object {
        private val SNAPSHOT = stringPreferencesKey("review_commit_ledger")
        private const val FILE_NAME = "anki_review_commit_ledger"

        /** One instance per process (DataStore forbids two active stores for one file). */
        fun create(context: Context, scope: CoroutineScope): DataStoreReviewCommitStore =
            DataStoreReviewCommitStore(
                PreferenceDataStoreFactory.create(
                    corruptionHandler = null,
                    // File IO belongs on the IO dispatcher; the caller's scope supplies the lifetime.
                    scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                    produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) }
                )
            )
    }
}
