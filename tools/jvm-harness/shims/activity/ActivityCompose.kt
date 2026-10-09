// Harness stub — androidx.activity.compose. See ../compose/Runtime.kt for the policy.
package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultContract
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

@Composable
fun ComponentActivity.setContent(
    parent: CompositionContext? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) = content()

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) = Unit

/**
 * GATE 18 — the Add Note screen picks media through this contract API. The stub never launches a
 * real picker: compile-level verification only, exactly like the rest of the harness.
 */
@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit
): ActivityResultLauncher<I> = remember(contract, onResult) {
    object : ActivityResultLauncher<I>() {
        override fun launch(input: I) = Unit
    }
}
