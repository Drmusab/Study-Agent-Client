// Harness stub — androidx.lifecycle.compose (lifecycle-runtime-compose 2.8.x). See Runtime.kt.
package androidx.lifecycle.compose

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

@Composable
fun <T> StateFlow<T>.collectAsStateWithLifecycle(
    context: Context = LocalContext.current
): State<T> = collectAsState()

@Composable
fun <T> SharedFlow<T>.collectAsStateWithLifecycle(
    context: Context = LocalContext.current
): State<T?> = object : State<T?> {
    override val value: T? get() = null
}

@Composable
fun <T : R, R> Flow<T>.collectAsStateWithLifecycle(
    initialValue: R,
    context: Context = LocalContext.current
): State<R> = collectAsState(initialValue)
