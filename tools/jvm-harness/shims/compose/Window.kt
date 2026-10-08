// Harness stub — androidx.compose.ui.window. See Runtime.kt for the policy.
package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) = content()

class DialogProperties(
    val dismissOnBackPress: Boolean = true,
    val dismissOnClickOutside: Boolean = true,
    val decorFitsSystemWindows: Boolean = true,
    val usePlatformDefaultWidth: Boolean = true,
    val securePolicy: Any? = null
)

@Composable
fun Popup(
    alignment: androidx.compose.ui.Alignment = androidx.compose.ui.Alignment.TopStart,
    offset: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero,
    onDismissRequest: (() -> Unit)? = null,
    properties: Any? = null,
    content: @Composable () -> Unit
) = content()
