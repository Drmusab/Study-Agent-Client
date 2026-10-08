// Harness stub: the functional subset of androidx.lifecycle the app's ViewModels and their JVM
// unit tests actually touch (NOT the real library, and deliberately not a Compose shim).
//
// Why it exists: every `*ViewModel.kt` in `app/src/main` imports exactly two framework symbols,
// `androidx.lifecycle.ViewModel` and the `viewModelScope` extension, and the ViewModel tests use
// `ViewModelStore` to reproduce the "activity is destroyed / store is cleared" path. Stubbing them
// here lets the harness compile and run the real ViewModel layer — the GATE 16
// `CardDetailsViewModel` included — instead of excluding whole directories and claiming the state
// machine is "not compiled". Scope semantics are honoured for real: the store owns the ViewModel,
// clearing the store cancels `viewModelScope`, so a test that forgets to clear leaks a live scope
// exactly as it would on a device.
package androidx.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * Stand-in for `androidx.lifecycle.ViewModel`.
 *
 * `onCleared()` is `protected open` like the real class, so a subclass override compiles
 * identically. The scope is created lazily and cancelled on [clear], which is the behaviour the
 * "stale response after the owner is gone" tests depend on.
 */
abstract class ViewModel {
    private val closeables = mutableListOf<Closeable>()
    internal var harnessScope: CoroutineScope? = null

    /** Real lifecycle clears viewmodel scopes before invoking [onCleared]. */
    internal open fun harnessClear() {
        harnessScope?.cancel()
        harnessScope = null
        val owned = closeables.toList()
        closeables.clear()
        owned.forEach { closeable ->
            if (closeable === this) return@forEach
            runCatching { closeable.close() }
        }
        onCleared()
    }

    fun addCloseable(closeable: Closeable) {
        closeables += closeable
    }

    protected open fun onCleared() = Unit
}

/**
 * Stand-in for the ktx `viewModelScope`: a `SupervisorJob` + `Dispatchers.Main.immediate` scope
 * tied to the ViewModel, cancelled when the ViewModel is cleared.
 *
 * `Main.immediate` is exactly what the real dependency uses, so tests that install a `TestDispatcher`
 * through `Dispatchers.setMain` (as the repository tests do) get the same eager-execution behaviour
 * they would under Gradle.
 */
val ViewModel.viewModelScope: CoroutineScope
    get() {
        harnessScope?.let { return it }
        val created = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        harnessScope = created
        return created
    }

/** Stand-in for `androidx.lifecycle.ViewModelStore`, keyed the same way. */
class ViewModelStore {
    private val map = LinkedHashMap<String, ViewModel>()

    fun put(key: String, viewModel: ViewModel) {
        val old = map.put(key, viewModel)
        if (old != null && old !== viewModel) old.harnessClear()
    }

    fun get(key: String): ViewModel? = map[key]

    fun keys(): Set<String> = map.keys.toSet()

    fun clear() {
        val values = map.values.toList()
        map.clear()
        values.forEach(ViewModel::harnessClear)
    }
}

/** Stand-in for `ViewModelStoreOwner`; used by tests that emulate a navigation host. */
interface ViewModelStoreOwner {
    val viewModelStore: ViewModelStore
}

/**
 * The lifecycle owner surface the Compose navigation host needs: `MainActivity`/`ComponentActivity`
 * are `LifecycleOwner`s and screens reach `lifecycleScope`, so the stub has to model both or the
 * `ui` layer cannot be type-checked. Deliberately minimal — no real state machine.
 */
interface LifecycleObserver

abstract class Lifecycle {
    enum class State { INITIALIZED, DESTROYED, CREATED, STARTED, RESUMED }

    open val currentState: State get() = State.INITIALIZED
    open fun addObserver(observer: LifecycleObserver) = Unit
    open fun removeObserver(observer: LifecycleObserver) = Unit
}

interface LifecycleOwner {
    val lifecycle: Lifecycle
}

class LifecycleRegistry(private val provider: LifecycleOwner) : Lifecycle()

/**
 * Stand-in for `LifecycleCoroutineScope`. It *is* a `CoroutineScope` (like the real class) so
 * `lifecycleScope.launch { }` type-checks, and `launchWhen*` keep their "run at/after state" shape.
 */
class LifecycleCoroutineScope(val lifecycle: Lifecycle) : CoroutineScope, Closeable {
    private val delegate = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val coroutineContext = delegate.coroutineContext
    fun <T> launchWhenStarted(block: suspend CoroutineScope.() -> T) = delegate.launch { block() }
    fun <T> launchWhenResumed(block: suspend CoroutineScope.() -> T) = delegate.launch { block() }
    fun <T> launchWhenCreated(block: suspend CoroutineScope.() -> T) = delegate.launch { block() }
    override fun close() = delegate.cancel()
}

val LifecycleOwner.lifecycleScope: LifecycleCoroutineScope get() = LifecycleCoroutineScope(lifecycle)

/** `CreationExtras` and `ViewModelProvider.Factory` as the compose `viewModel {}` builder sees them. */
class CreationExtras {
    companion object {
        val Empty: CreationExtras = CreationExtras()
    }

    operator fun <T : Any> get(key: Any): T? = null
}

interface HasDefaultViewModelProviderFactory {
    val defaultViewModelCreationExtras: CreationExtras get() = CreationExtras.Empty
    val defaultViewModelProviderFactory: ViewModelProvider.Factory? get() = null
}

class ViewModelProvider {
    interface Factory {
        fun <T : ViewModel> create(modelClass: Class<T>): T =
            throw UnsupportedOperationException("harness stub")
    }
}
