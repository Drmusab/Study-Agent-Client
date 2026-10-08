// Harness stub — androidx.compose.foundation.selection. See Runtime.kt for the policy.
package androidx.compose.foundation.selection

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

fun Modifier.selectable(
    selected: Boolean?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.Checkbox,
    onSelectedChange: ((Boolean) -> Boolean)? = null,
    onClick: () -> Unit
): Modifier = modifier

fun Modifier.selectable(
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.Checkbox,
    onClick: () -> Unit
): Modifier = modifier

fun Modifier.toggleable(
    value: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.Checkbox,
    onValueChange: (Boolean) -> Unit
): Modifier = modifier

@Composable
fun TriStateCheckbox(state: Any?, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier,
                     enabled: Boolean = true, interactionSource: Any? = null) = Unit
