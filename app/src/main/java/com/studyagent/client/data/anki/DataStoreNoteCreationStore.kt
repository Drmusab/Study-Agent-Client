package com.studyagent.client.data.anki

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.studyagent.client.core.anki.create.NoteCreationStore
import com.studyagent.client.core.anki.create.NoteCreationStoreRead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/**
 * GATE 18 — the durable [NoteCreationStore], on the persistence library the app already uses, in a
 * **separate file** from the rating, reviewer-action and note-mutation ledgers (INV-18-17).
 *
 * Same guarantees as the other stores: `edit` is an atomic replace, so after process death the
 * snapshot is the old one or the new one. No replace-on-corruption handler: a corrupt creation file
 * surfaces as [NoteCreationStoreRead.Unreadable] and the ledger fails closed, so an AMBIGUOUS
 * creation is never silently forgotten. The snapshot holds transaction metadata only
 * (see `NoteCreationCodec`), never field values, tag text or media content.
 */
class DataStoreNoteCreationStore(private val dataStore: DataStore<Preferences>) : NoteCreationStore {

    override suspend fun read(): NoteCreationStoreRead = try {
        NoteCreationStoreRead.Snapshot(dataStore.data.first()[SNAPSHOT])
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // CorruptionException / IOException: fail closed, never "empty".
        NoteCreationStoreRead.Unreadable(error::class.java.simpleName)
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
        private val SNAPSHOT = stringPreferencesKey("note_creation_ledger")
        private const val FILE_NAME = "anki_note_creation_ledger"

        /** One instance per process (DataStore forbids two active stores for one file). */
        fun create(context: Context, scope: CoroutineScope): DataStoreNoteCreationStore =
            DataStoreNoteCreationStore(
                PreferenceDataStoreFactory.create(
                    corruptionHandler = null,
                    scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                    produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) }
                )
            )
    }
}
