// Harness stub — androidx.activity.compose. See ../compose/Runtime.kt for the policy.
package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.Modifier

@Composable
fun ComponentActivity.setContent(
    parent: CompositionContext? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) = content()

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) = Unit
