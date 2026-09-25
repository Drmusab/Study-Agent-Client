package androidx.datastore.preferences.core
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataMigration
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

abstract class Preferences internal constructor() {
    class Key<T> internal constructor(val name: String) {
        override fun equals(other: Any?) = other is Key<*> && other.name == name
        override fun hashCode() = name.hashCode()
        override fun toString() = name
    }
    class Pair<T> internal constructor(internal val key: Key<T>, internal val value: T)
    abstract operator fun <T> contains(key: Key<T>): Boolean
    abstract operator fun <T> get(key: Key<T>): T?
    abstract fun asMap(): Map<Key<*>, Any>
    fun toMutablePreferences(): MutablePreferences = MutablePreferences(LinkedHashMap(asMap()))
    fun toPreferences(): Preferences = MutablePreferences(LinkedHashMap(asMap()))
}
class MutablePreferences internal constructor(internal val map: MutableMap<Preferences.Key<*>, Any> = LinkedHashMap()) : Preferences() {
    override fun <T> contains(key: Key<T>) = map.containsKey(key)
    @Suppress("UNCHECKED_CAST") override fun <T> get(key: Key<T>): T? = map[key] as T?
    override fun asMap(): Map<Key<*>, Any> = java.util.Collections.unmodifiableMap(LinkedHashMap(map))
    operator fun <T> set(key: Key<T>, value: T) { if (value == null) map.remove(key) else map[key] = value as Any }
    fun <T> remove(key: Key<T>): T? { @Suppress("UNCHECKED_CAST") return map.remove(key) as T? }
    fun clear() = map.clear()
    operator fun plusAssign(prefs: Preferences) { map.putAll(prefs.asMap()) }
    operator fun plusAssign(pair: Preferences.Pair<*>) { map[pair.key] = pair.value as Any }
    fun putAll(vararg pairs: Preferences.Pair<*>) { pairs.forEach { map[it.key] = it.value as Any } }
    override fun equals(other: Any?) = other is Preferences && other.asMap() == asMap()
    override fun hashCode() = map.hashCode()
    override fun toString() = map.toString()
}
infix fun <T> Preferences.Key<T>.to(value: T): Preferences.Pair<T> = Preferences.Pair(this, value)
fun stringPreferencesKey(name: String) = Preferences.Key<String>(name)
fun booleanPreferencesKey(name: String) = Preferences.Key<Boolean>(name)
fun intPreferencesKey(name: String) = Preferences.Key<Int>(name)
fun longPreferencesKey(name: String) = Preferences.Key<Long>(name)
fun floatPreferencesKey(name: String) = Preferences.Key<Float>(name)
fun doublePreferencesKey(name: String) = Preferences.Key<Double>(name)
fun stringSetPreferencesKey(name: String) = Preferences.Key<Set<String>>(name)
fun emptyPreferences(): Preferences = MutablePreferences()
fun mutablePreferencesOf(vararg pairs: Preferences.Pair<*>): MutablePreferences = MutablePreferences().apply { putAll(*pairs) }
fun preferencesOf(vararg pairs: Preferences.Pair<*>): Preferences = mutablePreferencesOf(*pairs)
suspend fun DataStore<Preferences>.edit(transform: suspend (MutablePreferences) -> Unit): Preferences =
    updateData { it.toMutablePreferences().apply { transform(this) } }

/** In-memory store; the real one is file-backed. */
class InMemoryPreferencesDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock { transform(state.value).toPreferences().also { state.value = it } }
}
object PreferenceDataStoreFactory {
    fun create(
        corruptionHandler: ReplaceFileCorruptionHandler<Preferences>? = null,
        migrations: List<DataMigration<Preferences>> = listOf(),
        scope: CoroutineScope? = null,
        produceFile: () -> File
    ): DataStore<Preferences> = InMemoryPreferencesDataStore()
}
