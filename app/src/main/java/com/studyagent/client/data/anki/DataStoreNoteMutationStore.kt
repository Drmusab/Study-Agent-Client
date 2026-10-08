package com.studyagent.client.data.anki

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.studyagent.client.core.anki.edit.NoteMutationStore
import com.studyagent.client.core.anki.edit.NoteMutationStoreRead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/**
 * GATE 17 — the durable [NoteMutationStore], on the persistence library the app already uses, in a
 * **separate file** from the rating and reviewer-action ledgers.
 *
 * Same guarantees as the reviewer-action store: `edit` is an atomic replace, so after process death
 * the snapshot is the old one or the new one. The file has no replace-on-corruption handler: a corrupt
 * note-mutation file surfaces as [NoteMutationStoreRead.Unreadable] and the ledger fails closed, so
 * an AMBIGUOUS edit is never silently forgotten. The snapshot holds transaction metadata only (see
 * `NoteMutationCodec`), never field values.
 */
class DataStoreNoteMutationStore(private val dataStore: DataStore<Preferences>) : NoteMutationStore {

    override suspend fun read(): NoteMutationStoreRead = try {
        NoteMutationStoreRead.Snapshot(dataStore.data.first()[SNAPSHOT])
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // CorruptionException / IOException: fail closed, never "empty".
        NoteMutationStoreRead.Unreadable(error::class.java.simpleName)
    }

    override suspend fun write(encoded: String): Boolean = try {
        dataStore.edit { preferences -> preferences[SNAPSHOT] = encoded }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    companion object {
        private val SNAPSHOT = stringPreferencesKey("note_mutation_ledger")
        private const val FILE_NAME = "anki_note_mutation_ledger"

        /** One instance per process (DataStore forbids two active stores for one file). */
        fun create(context: Context, scope: CoroutineScope): DataStoreNoteMutationStore =
            DataStoreNoteMutationStore(
                PreferenceDataStoreFactory.create(
                    corruptionHandler = null,
                    scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                    produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) }
                )
            )
    }
}
