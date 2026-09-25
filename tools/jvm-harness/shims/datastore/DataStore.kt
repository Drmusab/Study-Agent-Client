// Harness stub: functional in-memory subset of androidx.datastore (NOT the real library).
package androidx.datastore.core
import kotlinx.coroutines.flow.Flow
interface DataStore<T> {
    val data: Flow<T>
    suspend fun updateData(transform: suspend (t: T) -> T): T
}
interface DataMigration<T> {
    suspend fun shouldMigrate(currentData: T): Boolean
    suspend fun migrate(currentData: T): T
    suspend fun cleanUp()
}
