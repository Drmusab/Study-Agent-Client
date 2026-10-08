// Harness stub — androidx.compose.ui.viewinterop. See Runtime.kt for the policy.
package androidx.compose.ui.viewinterop

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun <T : View> AndroidView(
    factory: @Composable (Context) -> T,
    modifier: Modifier = Modifier,
    update: @Composable (T) -> Unit = {}
) = Unit

@Composable
fun <T : View> AndroidView(
    factory: @Composable (Context) -> T,
    modifier: Modifier = Modifier,
    onRelease: ((T) -> Unit)? = null,
    update: @Composable (T) -> Unit = {}
) = Unit
