package androidx.datastore.preferences
import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.InMemoryPreferencesDataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlin.properties.ReadOnlyProperty
fun preferencesDataStore(
    name: String,
    corruptionHandler: ReplaceFileCorruptionHandler<Preferences>? = null,
    produceMigrations: (Context) -> List<DataMigration<Preferences>> = { listOf() },
    scope: CoroutineScope? = null
): ReadOnlyProperty<Context, DataStore<Preferences>> {
    val store = InMemoryPreferencesDataStore()
    return ReadOnlyProperty { _, _ -> store }
}
fun Context.preferencesDataStoreFile(name: String): File = File("$name.preferences_pb")
