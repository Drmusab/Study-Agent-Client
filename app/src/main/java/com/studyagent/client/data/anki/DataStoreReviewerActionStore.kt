package com.studyagent.client.data.anki

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.studyagent.client.core.anki.ReviewerActionStore
import com.studyagent.client.core.anki.ReviewerActionStoreRead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/**
 * GATE 13 §14 — the durable [ReviewerActionStore], on the same persistence library the app already
 * uses, in a **separate file** from the rating ledger.
 *
 * DataStore gives what the ledger needs: `edit` is a serialized read-modify-write whose new file is
 * written, synced and atomically renamed before `edit` returns, so after process death the snapshot
 * is either the old one or the new one — never half of each.
 *
 * The file is excluded from backup and device transfer (see `AndroidManifest`/backup rules) and is
 * deliberately **without** a replace-on-corruption handler: the settings store resets to defaults
 * when its file is corrupt, which here would silently forget `AMBIGUOUS` actions — the one thing
 * §30 says must survive a restart. A corrupt action ledger surfaces as
 * [ReviewerActionStoreRead.Unreadable] and the ledger fails closed.
 */
class DataStoreReviewerActionStore(private val dataStore: DataStore<Preferences>) : ReviewerActionStore {

    override suspend fun read(): ReviewerActionStoreRead = try {
        ReviewerActionStoreRead.Snapshot(dataStore.data.first()[SNAPSHOT])
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // CorruptionException / IOException: fail closed, never "empty".
        ReviewerActionStoreRead.Unreadable(error::class.java.simpleName)
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
        private val SNAPSHOT = stringPreferencesKey("reviewer_action_ledger")
        private const val FILE_NAME = "anki_reviewer_action_ledger"

        /** One instance per process (DataStore forbids two active stores for one file). */
        fun create(context: Context, scope: CoroutineScope): DataStoreReviewerActionStore =
            DataStoreReviewerActionStore(
                PreferenceDataStoreFactory.create(
                    corruptionHandler = null,
                    // File IO belongs on the IO dispatcher; the caller's scope supplies the lifetime.
                    scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                    produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) }
                )
            )
    }
}
