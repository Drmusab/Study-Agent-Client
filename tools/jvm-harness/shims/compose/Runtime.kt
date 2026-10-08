// Harness stub — the API-shaped surface of androidx.compose.runtime (Compose 1.6.8) used by the
// screens this harness type-checks. NOT the real library and NOT a behavioural Compose runtime.
//
// Why it exists: Gradle cannot resolve the Compose BOM in this sandbox, so `assembleDebug` cannot
// run and the Compose layer is the one place a wrong call site (bad parameter name, renamed helper,
// wrong receiver) survives every unit test. These declarations mirror the real signatures closely
// enough that the compiler resolves the app's real call sites, so a mistake in *app* code fails the
// harness build. Parameter lists are never narrower than the real ones; where they are wider it is
// only so call sites that use real parameters keep compiling. Composition, recomposition and
// snapshot semantics are not modelled: `remember` runs its calculation, effects run once.
package androidx.compose.runtime

import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER,
    AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY, AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD
)
annotation class Composable

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class Stable

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class Immutable

// ---- state ------------------------------------------------------------------------------

interface SnapshotMutationPolicy<T>

fun <T> structuralEqualityPolicy(): SnapshotMutationPolicy<T> = object : SnapshotMutationPolicy<T> {}

interface State<out T> {
    val value: T
}

interface MutableState<T> : State<T> {
    override var value: T
    operator fun component1(): T
    operator fun component2(): (T) -> Unit
}

private class MutableStateImpl<T>(initial: T) : MutableState<T> {
    private var storage: T = initial
    override var value: T
        get() = storage
        set(next) { storage = next }
    override fun component1(): T = storage
    override fun component2(): (T) -> Unit = { storage = it }
    override fun toString(): String = "MutableState($storage)"
}

fun <T> mutableStateOf(value: T, policy: SnapshotMutationPolicy<T>? = null): MutableState<T> =
    MutableStateImpl(value)

interface IntState : State<Int> {
    val intValue: Int
    override val value: Int
        get() = intValue
}

interface MutableIntState : IntState {
    override var intValue: Int
    override var value: Int
}

private class MutableIntStateImpl(initial: Int) : MutableIntState {
    private var storage: Int = initial
    override var intValue: Int
        get() = storage
        set(next) { storage = next }
    override var value: Int
        get() = storage
        set(next) { storage = next }
    override fun toString(): String = "MutableIntState($storage)"
}

fun mutableIntStateOf(value: Int): MutableIntState = MutableIntStateImpl(value)

interface FloatState : State<Float> {
    val floatValue: Float
    override val value: Float
        get() = floatValue
}

interface MutableFloatState : FloatState {
    override var floatValue: Float
    override var value: Float
}

private class MutableFloatStateImpl(initial: Float) : MutableFloatState {
    private var storage: Float = initial
    override var floatValue: Float
        get() = storage
        set(next) { storage = next }
    override var value: Float
        get() = storage
        set(next) { storage = next }
}

fun mutableFloatStateOf(value: Float): MutableFloatState = MutableFloatStateImpl(value)

interface LongState : State<Long> {
    val longValue: Long
    override val value: Long
        get() = longValue
}

interface MutableLongState : LongState {
    override var longValue: Long
    override var value: Long
}

private class MutableLongStateImpl(initial: Long) : MutableLongState {
    private var storage: Long = initial
    override var longValue: Long
        get() = storage
        set(next) { storage = next }
    override var value: Long
        get() = storage
        set(next) { storage = next }
}

fun mutableLongStateOf(value: Long): MutableLongState = MutableLongStateImpl(value)

// `by remember { mutableStateOf(...) }` only compiles when the delegate operators resolve, which is
// exactly the property the harness is checking for the screen's local UI state.
inline operator fun <T> State<T>.getValue(thisObj: Any?, property: KProperty<*>): T = value

inline operator fun <T> MutableState<T>.setValue(
    thisObj: Any?,
    property: KProperty<*>,
    value: T
) {
    this.value = value
}

operator fun IntState.getValue(thisObj: Any?, property: KProperty<*>): Int = intValue

operator fun MutableIntState.setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
    this.intValue = value
}

operator fun FloatState.getValue(thisObj: Any?, property: KProperty<*>): Float = floatValue

operator fun MutableFloatState.setValue(thisObj: Any?, property: KProperty<*>, value: Float) {
    this.floatValue = value
}

operator fun LongState.getValue(thisObj: Any?, property: KProperty<*>): Long = longValue

operator fun MutableLongState.setValue(thisObj: Any?, property: KProperty<*>, value: Long) {
    this.longValue = value
}

// ---- composition ------------------------------------------------------------------------

@Composable
fun <T> remember(calculation: @Composable () -> T): T = calculation()

@Composable
fun <T> remember(key1: Any?, calculation: @Composable () -> T): T = calculation()

@Composable
fun <T> remember(key1: Any?, key2: Any?, calculation: @Composable () -> T): T = calculation()

@Composable
fun <T> remember(
    key1: Any?,
    key2: Any?,
    key3: Any?,
    calculation: @Composable () -> T
): T = calculation()

@Composable
fun <T> remember(vararg keys: Any?, calculation: @Composable () -> T): T = calculation()

@Composable
fun <T> remember(
    key1: Any?,
    policy: SnapshotMutationPolicy<T> = structuralEqualityPolicy(),
    calculation: @Composable () -> T
): T = calculation()

@Composable
fun <T> rememberUpdatedState(newValue: T): State<T> = object : State<T> {
    override val value: T get() = newValue
}

@Composable
fun <T> key(vararg keys: Any?, block: @Composable () -> T): T = block()

@Composable
fun <T> key(key1: Any?, block: @Composable () -> T): T = block()

@Composable
fun <T> key(key1: Any?, key2: Any?, block: @Composable () -> T): T = block()

@Composable
fun <T> key(key1: Any?, key2: Any?, key3: Any?, block: @Composable () -> T): T = block()

class DisposableEffectScope {
    fun onDispose(action: () -> Unit): DisposableEffectResult = DisposableEffectResult(action)
}

class DisposableEffectResult(internal val onDispose: () -> Unit)

@Composable
fun DisposableEffect(key1: Any?, effect: DisposableEffectScope.() -> DisposableEffectResult) {
    effect(DisposableEffectScope()).onDispose()
}

@Composable
fun DisposableEffect(
    key1: Any?,
    key2: Any?,
    effect: DisposableEffectScope.() -> DisposableEffectResult
) {
    effect(DisposableEffectScope()).onDispose()
}

@Composable
fun DisposableEffect(
    key1: Any?,
    key2: Any?,
    key3: Any?,
    effect: DisposableEffectScope.() -> DisposableEffectResult
) {
    effect(DisposableEffectScope()).onDispose()
}

@Composable
fun <T> LaunchedEffect(key1: Any?, block: suspend CoroutineScope.() -> T): Unit {
    CoroutineScope(EmptyCoroutineContext).launch { block() }
}

@Composable
fun <T> LaunchedEffect(key1: Any?, key2: Any?, block: suspend CoroutineScope.() -> T): Unit {
    CoroutineScope(EmptyCoroutineContext).launch { block() }
}

@Composable
fun <T> LaunchedEffect(
    key1: Any?,
    key2: Any?,
    key3: Any?,
    block: suspend CoroutineScope.() -> T
): Unit {
    CoroutineScope(EmptyCoroutineContext).launch { block() }
}

@Composable
fun <T> LaunchedEffect(
    key1: Any?,
    key2: Any?,
    key3: Any?,
    key4: Any?,
    block: suspend CoroutineScope.() -> T
): Unit {
    CoroutineScope(EmptyCoroutineContext).launch { block() }
}

@Composable
fun <T> LaunchedEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> T): Unit {
    CoroutineScope(EmptyCoroutineContext).launch { block() }
}

/** `setContent(parent: CompositionContext?)` in androidx.activity.compose takes this. */
interface CompositionContext {
    fun <T> runCompose(block: () -> T): T
}

val DefaultCompositionContext: CompositionContext = object : CompositionContext {
    override fun <T> runCompose(block: () -> T): T = block()
}

@Composable
fun rememberCoroutineScope(): kotlinx.coroutines.CoroutineScope =
    remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate) }

@Composable
fun SideEffect(effect: () -> Unit) = effect()

// ---- composition locals -----------------------------------------------------------------

class ProvidedValue<T>(internal val local: ProvidableCompositionLocal<T>, internal val provided: Any?)

class ProvidableCompositionLocal<T>(internal val defaultFactory: () -> T) {
    val current: T get() = defaultFactory()
    infix fun provides(value: T): ProvidedValue<T> = ProvidedValue(this, value)
}

fun <T> compositionLocalOf(defaultFactory: () -> T): ProvidableCompositionLocal<T> =
    ProvidableCompositionLocal(defaultFactory)

fun <T> staticCompositionLocalOf(defaultFactory: () -> T): ProvidableCompositionLocal<T> =
    ProvidableCompositionLocal(defaultFactory)

@Composable
fun CompositionLocalProvider(value: ProvidedValue<*>, content: @Composable () -> Unit) = content()

@Composable
fun CompositionLocalProvider(vararg values: ProvidedValue<*>, content: @Composable () -> Unit) =
    content()
