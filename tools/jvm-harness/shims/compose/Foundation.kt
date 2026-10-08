// Harness stub — androidx.compose.foundation (+ .layout, .lazy, .shape, .selection, .gestures,
// .text.selection). See Runtime.kt for the policy.
package androidx.compose.foundation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RectangleShape

@Composable
fun isSystemInDarkTheme(): Boolean = false

class ScrollState(val initial: Int = 0) {
    val value: Int get() = initial
    val isScrollable: Boolean get() = true
    suspend fun scrollTo(value: Int) = Unit
    suspend fun animateScrollTo(value: Int) = Unit
}

@Composable
fun rememberScrollState(initial: Int = 0): ScrollState = ScrollState(initial)

fun Modifier.verticalScroll(state: ScrollState, enabled: Boolean = true,
                            reverseScrolling: Boolean = false): Modifier = this
fun Modifier.horizontalScroll(state: ScrollState, enabled: Boolean = true): Modifier = this

typealias InteractionSource = androidx.compose.foundation.interaction.MutableInteractionSource

// `androidx.compose.foundation.BorderStroke` is how the app imports the type the graphics module
// declares, so the alias has to exist for the import to resolve.
typealias BorderStroke = androidx.compose.ui.graphics.BorderStroke

// `androidx.compose.foundation.background` is where the real library declares these (Color overload
// has no alpha parameter, Brush overload does).
fun Modifier.background(color: androidx.compose.ui.graphics.Color, shape: Shape = RectangleShape): Modifier = this
fun Modifier.background(
    brush: Brush,
    shape: Shape = RectangleShape,
    alpha: Float = 1.0f
): Modifier = this

fun Modifier.clickable(
    interactionSource: InteractionSource? = null,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = this

fun Modifier.clickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = this

class IndicationNodeFactory

object NoIndication

@androidx.compose.runtime.Composable
fun Canvas(modifier: Modifier = Modifier, onDraw: androidx.compose.ui.graphics.DrawScope.() -> Unit) = Unit

@androidx.compose.runtime.Composable
fun Spacer(modifier: Modifier = Modifier) = Unit
