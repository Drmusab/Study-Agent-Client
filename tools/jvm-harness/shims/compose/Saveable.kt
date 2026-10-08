// Harness stub — androidx.compose.runtime.saveable. See Runtime.kt for the policy.
package androidx.compose.runtime.saveable

import androidx.compose.runtime.Composable

interface Saver<Original, Saveable> {
    fun save(value: Original): Saveable?
    fun restore(value: Saveable): Original?
}

class SaverScope(val canBeSaved: (Any) -> Boolean = { true }) {
    fun requireCanBeSaved(value: Any) = Unit
}

fun <T> autoSaver(): Saver<T, Any> = object : Saver<T, Any> {
    override fun save(value: T): Any? = value
    override fun restore(value: Any): T? = @Suppress("UNCHECKED_CAST") (value as T)
}

@Composable
fun <T : Any> rememberSaveable(
    vararg inputs: Any?,
    saver: Saver<T, out Any> = autoSaver(),
    key: String? = null,
    init: () -> T
): T = init()

@Composable
fun <T> rememberSaveable(
    vararg inputs: Any?,
    stateSaver: Saver<T, out Any>,
    key: String? = null,
    init: () -> androidx.compose.runtime.MutableState<T>
): androidx.compose.runtime.MutableState<T> = init()
