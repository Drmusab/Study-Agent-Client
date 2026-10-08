// Harness stub — the ktx `collectAsState` helpers the lifecycle stub delegates to.
package androidx.compose.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@Composable
fun <T> Flow<T>.collectAsState(initial: T): State<T> = object : State<T> {
    override val value: T get() = initial
}

@Composable
fun <T> StateFlow<T>.collectAsState(): State<T> = object : State<T> {
    override val value: T get() = this@collectAsState.value
}
